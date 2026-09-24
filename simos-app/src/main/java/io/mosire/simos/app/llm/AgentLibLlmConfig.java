package io.mosire.simos.app.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRouteAssembler;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelCapabilities;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM provider 配置的**唯一入口**（M11′ 对接版）：把"provider 配置"这件事整个交给 AgentLib 的 {@link
 * ConfigStore}（`<store>/agentlib/config.json` 的 `llm.routes.<name>` / `keys.<name>`），simos
 * 侧**不再自造存储、 不再自造 HTTP 客户端、不再自造路由类型**。
 *
 * <p>★ **用户裁定**（逐字）：「Agent 的上下文数据格式、Provider 配置等**都由 AgentLib 负责**」「我这边**只需要直接用、对接**」 ⇒ 本类只做三件
 * AgentLib 没做的事：
 *
 * <ol>
 *   <li>**决定配置根**（AgentLib 的 {@code FileConfigStore} 要一个目录；本类把它定在 {@code <store>/agentlib/}）；
 *   <li>**把 simos 的旧配置迁进去**（用户的既有 {@code <store>/llm-providers.json} 是"迁移来源 / 缓存"，见 {@link
 *       #migrateLegacyIfPresent()}）；
 *   <li>**给 CRUD 提供 AgentLib 的 schema**（{@code llm.routes.*} / {@code keys.*} 的形态约束；AgentLib 的写是
 *       "校验合并后的整个目标文件"，schema 由写的人给）。
 * </ol>
 *
 * <p>★ **密钥只走 AgentLib 的 `keys.*`**（D-B 裁定）：{@code credentialsRef} 一律写成 {@code keys.<name>}；ENV /
 * FILE 那类"值不在配置里"的引用由 {@link SimosApiKeySource} 以官方 SPI （{@code
 * OpenAICompatibleLlmClient.ApiKeySource}）实现，**不改 AgentLib、不绕 ConfigStore 读 env**。
 *
 * <p>★ **读/装配都走 AgentLib 的权威入口**：枚举用 {@link LlmRouteLoader#availableNames}（**只列名字、不校验内容** ⇒
 * 写坏的那条也看得见），装配用 {@link LlmRouteAssembler#client} / {@link LlmRouteAssembler#provider}（**全有或全无** ⇒
 * 坏路由不会悄悄缺席）。两个口径的分工是 AgentLib 刻意的设计，本类**不另立一套**。
 *
 * <p>★ **不是世界事实**（铁律 3）：它不进 sd、不落 revision、不进命令白名单；决策人只持有 provider 的 id（不透明串）。
 */
public final class AgentLibLlmConfig {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLibLlmConfig.class);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 配置根目录名（相对 store 根）：{@code <store>/agentlib/}。 */
  public static final String CONFIG_DIR_NAME = "agentlib";

  /** 旧格式（M11 WIP）的文件名：**只作迁移来源**，不再被本类写。 */
  public static final String LEGACY_FILE_NAME = "llm-providers.json";

  /**
   * 仓库默认配置（相对 CWD 的 {@code config/llm-providers.json}）：**只作兜底种子**——当 store 里没有任何路由、 也没有 {@code
   * <store>/llm-providers.json} 时，用它把用户/仓库预置的 provider 迁进 AgentLib 的配置根。
   *
   * <p>★ 优先级：{@code <store>/llm-providers.json}（更具体）优先于它；两者都没有则什么都不迁。
   */
  public static final Path DEFAULT_REPO_CONFIG = Path.of("config", LEGACY_FILE_NAME);

  /** 承载路由表的段（AgentLib 口径：{@code llm.routes.<name>}）。 */
  private static final String SECTION_LLM = "llm";

  private static final String KEY_ROUTES = "routes";

  /**
   * 路由里承载**能力描述**的子结点名（AgentLib 的口径：{@code llm.routes.<name>.capabilities.*}）。
   *
   * <p>★ 本类**不解释**它的内容（那是 {@code LlmRouteLoader} 的事），只在重写路由时**原样搬运**它——见 {@link #upsertRoute}。
   */
  private static final String KEY_CAPABILITIES = "capabilities";

  /** 承载密钥值的段（AgentLib 口径：{@code keys.<name>}）。 */
  private static final String SECTION_KEYS = "keys";

  /** 写配置用的身份：{@code llm.*} / {@code keys.*} 仅 SYSTEM 可写（AgentLib 的前缀授权）。 */
  private static final AgentPermissionSet SYSTEM = AgentPermissionSet.system();

  /**
   * 写 {@code llm.routes.<name>} 时用的 **整文件** schema。
   *
   * <p>★ **为什么不是"路由条目"的 schema**：AgentLib 的写入校验的是**合并后的整个目标文件**——传一份"路由条目"形状 的 schema
   * 会把整份文档拿去按它校验，{@code llm.routes} 是个"名字 → 路由"的映射，必然不符（实测：{@code 未通过关键字: required，共 2 处}）。故这份
   * schema 描述的是**文件**：{@code llm} 是对象、{@code llm.routes} 是对象、 {@code keys} 是对象，其余键一律放行（{@code
   * additionalProperties} 不设 {@code false}：AgentLib 将来加段时 schema 不该先把用户挡在门外）。
   *
   * <p>★ **形态校验不在这里**：协议名、超时正数、必填键是否在场，都由 AgentLib 的 {@code LlmRouteLoader} 在**读侧**做——
   * 两处各判一半、互不重复；写侧只防"整份文档不成形"。
   */
  private static final JsonNode ROUTES_FILE_SCHEMA =
      schema(
          """
          {"type":"object",
           "properties":{
             "llm":{"type":"object",
               "properties":{
                 "routes":{"type":"object"},
                 "baseUrl":{"type":"string"},
                 "model":{"type":"string"},
                 "credentialsRef":{"type":"string"},
                 "route":{"type":"string"}
               }},
             "keys":{"type":"object"},
             "agents":{"type":"object"},
             "runtime":{"type":"object"}
           }}
          """);

  /**
   * 写 {@code keys.<name>} 时用的**整文件** schema：{@code keys} 必须是"名字 → 文本"的映射（值不得为容器/null）。
   *
   * <p>同样按"整份文档"校验（见 {@link #ROUTES_FILE_SCHEMA} 的说明）。
   */
  private static final JsonNode KEYS_FILE_SCHEMA =
      schema(
          """
          {"type":"object",
           "properties":{
             "keys":{"type":"object","additionalProperties":{"type":"string","minLength":1}},
             "llm":{"type":"object"},
             "agents":{"type":"object"},
             "runtime":{"type":"object"}
           }}
          """);

  private final Path storeRoot;
  private final Path configRoot;
  private final ConfigStore configStore;
  private final Path repoDefaultConfig;

  private AgentLibLlmConfig(
      Path storeRoot, Path configRoot, ConfigStore configStore, Path repoDefaultConfig) {
    this.storeRoot = storeRoot;
    this.configRoot = configRoot;
    this.configStore = configStore;
    this.repoDefaultConfig = repoDefaultConfig;
  }

  /**
   * 建在 {@code <storeDir>/agentlib/}（目录不存在则创建），随后按需迁移旧格式。
   *
   * @param storeDir simos 的 store 根（与 {@code simos.db} 同层）
   */
  public static AgentLibLlmConfig open(Path storeDir) {
    return open(storeDir, DEFAULT_REPO_CONFIG);
  }

  /**
   * 同 {@link #open(Path)}，但仓库默认种子文件可注入（生产走 {@link #DEFAULT_REPO_CONFIG}；测试给临时文件）。
   *
   * @param repoDefaultConfig 兜底种子（可为 null）
   */
  static AgentLibLlmConfig open(Path storeDir, Path repoDefaultConfig) {
    Objects.requireNonNull(storeDir, "storeDir");
    Path configRoot = storeDir.resolve(CONFIG_DIR_NAME);
    AgentLibLlmConfig config =
        new AgentLibLlmConfig(
            storeDir, configRoot, new FileConfigStore(configRoot), repoDefaultConfig);
    config.migrateLegacyIfPresent();
    return config;
  }

  /** 供测试注入（如 {@code @TempDir} 直给的配置根），**不做迁移**。 */
  static AgentLibLlmConfig withStore(Path storeRoot, Path configRoot, ConfigStore configStore) {
    return new AgentLibLlmConfig(storeRoot, configRoot, configStore, null);
  }

  /** AgentLib 的配置存储（装配入口 {@link LlmRouteAssembler} 要它）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "AgentLib 的装配入口 LlmRouteAssembler.client(store,...) 就必须拿到这个 store；"
              + "它是本类持有的唯一真相源，不是内部表示外泄")
  public ConfigStore configStore() {
    return configStore;
  }

  /** 配置根（诊断用；**不打内容**）。 */
  public Path configRoot() {
    return configRoot;
  }

  /**
   * 已配置的路由名（升序）——{@link LlmRouteLoader#availableNames} 的直通。
   *
   * <p>★ **只列名字、不校验内容**：写坏的那条也在列表里（配置页要能看见它，才谈得上去修）。
   */
  public List<String> availableNames() {
    return LlmRouteLoader.availableNames(configStore);
  }

  /**
   * 一条路由的**掩码视图**（配置页用）：字段逐条来自 AgentLib，**不含任何密钥值**。
   *
   * <p>★ **坏条目如实上报**：{@link LlmRouteLoader#load} 抛 {@link ConfigException} 时，视图只带 {@code
   * errorCode}（非敏感错误码）与 {@code valid:false}，**绝不**把该条目从列表里抹掉（那不叫"看不见"叫"删了"）。
   */
  public Map<String, Object> view(String name) {
    Objects.requireNonNull(name, "name");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", name);
    try {
      ModelRoute route = LlmRouteLoader.load(configStore, name);
      ModelCapabilities caps = LlmRouteLoader.capabilities(configStore, name);
      view.put("valid", true);
      view.put("baseUrl", route.baseUrl());
      view.put("model", route.model());
      view.put("protocol", route.transport().protocol().name().toLowerCase(java.util.Locale.ROOT));
      view.put("readTimeoutMs", route.transport().readTimeout().toMillis());
      view.put("connectTimeoutMs", route.transport().connectTimeout().toMillis());
      view.put("credentialsRef", maskCredentials(route.credentialsRef()));
      view.put("keyConfigured", keyConfigured(route.credentialsRef()));
      view.put("capabilities", capabilitiesView(caps));
    } catch (ConfigException e) {
      view.put("valid", false);
      view.put("errorCode", e.code());
      view.put("error", e.getMessage());
    }
    return view;
  }

  /** 全部路由的视图（按名字升序，与 {@link #availableNames()} 同序 ⇒ 两次调用响应逐字节相同）。 */
  public List<Map<String, Object>> views() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String name : availableNames()) {
      out.add(view(name));
    }
    return List.copyOf(out);
  }

  /** 一条路由的**可装配客户端**；路由缺失/坏掉 ⇒ AgentLib 的 {@link ConfigException} 原样冒泡（不兜底）。 */
  public LlmClient client(String name) {
    return LlmRouteAssembler.client(configStore, name, AccessToken.SYSTEM);
  }

  /** 是否已配置任何路由（两处都空 ⇒ false）。 */
  public boolean isEmpty() {
    return availableNames().isEmpty();
  }

  /**
   * 新增 / 覆盖一条路由（**只写路由，不碰密钥**）。
   *
   * <p>★ **密钥走 {@link #putKey}**：本方法刻意不收密钥值——把"端点配置"与"凭据"分两个键写，是 AgentLib 的配置形态 （{@code
   * llm.routes.<name>.credentialsRef = "keys.<name>"}），也是本仓密钥纪律的结构化。
   *
   * @param name 路由名（AgentLib 的合法字符集由 {@code put} 的前缀授权与读侧共同约束）
   * @param baseUrl API 根（**含** {@code /v1}，不含 {@code /chat/completions}——AgentLib 约定）
   * @param model 模型名
   * @param credentialsRef 密钥引用（空白 = 匿名调用，本地部署形如 Ollama）
   * @param readTimeoutMs 单次调用整体读超时（必须为正）
   */
  public void upsertRoute(
      String name, String baseUrl, String model, String credentialsRef, long readTimeoutMs) {
    Objects.requireNonNull(name, "name");
    requireText(baseUrl, "baseUrl");
    requireText(model, "model");
    if (readTimeoutMs <= 0) {
      throw new IllegalArgumentException("readTimeoutMs 必须为正: " + readTimeoutMs);
    }
    ObjectNode route = MAPPER.createObjectNode();
    route.put("baseUrl", baseUrl);
    route.put("model", model);
    if (credentialsRef != null && !credentialsRef.isBlank()) {
      route.put("credentialsRef", credentialsRef);
    }
    route.put("timeoutMs", readTimeoutMs);
    // ★★ **把既有的 capabilities 原样搬过来**（P4，2026-09-24）：本方法**不管理能力位**，而 {@code configStore.put}
    //   是"整体替换该结点"⇒ 不搬的话，在配置页上保存一次路由就会把 {@code capabilities.vision} 抹掉。症状是
    //   **决策人忽然收不到图了，而配置页看上去一切正常**（那种"改了 A、坏在 B"的形态正是本仓最贵的一类）。
    //   能力位目前由直接编辑 config.json 写入（将来若有能力编辑面，也应经由它、而不是顺手在这里默认值化）。
    JsonNode existing = configStore.get(SECTION_LLM, KEY_ROUTES + "." + name).orElse(null);
    if (existing != null && existing.get(KEY_CAPABILITIES) != null) {
      route.set(KEY_CAPABILITIES, existing.get(KEY_CAPABILITIES));
    }
    configStore.put(SECTION_LLM, KEY_ROUTES + "." + name, route, SYSTEM, null, ROUTES_FILE_SCHEMA);
  }

  /** 删除一条路由（**幂等**：不存在 = 无操作，AgentLib 的 {@code remove} 语义）。 */
  public void removeRoute(String name) {
    Objects.requireNonNull(name, "name");
    configStore.remove(SECTION_LLM, KEY_ROUTES + "." + name, SYSTEM, null, ROUTES_FILE_SCHEMA);
  }

  /**
   * 写入一条密钥（{@code keys.<name>}）。★ 密钥值**只进这里**，绝不进 {@link #upsertRoute} 的任何参数。
   *
   * <p>{@code keys.*} 只由 SYSTEM 身份写（AgentLib 的前缀授权硬拦）；值不落日志、不落异常、不进视图。
   */
  public void putKey(String name, String value) {
    requireText(name, "name");
    requireText(value, "value");
    configStore.put(
        SECTION_KEYS,
        name,
        MAPPER.getNodeFactory().textNode(value),
        SYSTEM,
        null,
        KEYS_FILE_SCHEMA);
    LOG.info("写入 LLM 密钥配置项 name={} length={}", name, value.length());
  }

  /** 删除一条密钥（幂等）。 */
  public void removeKey(String name) {
    Objects.requireNonNull(name, "name");
    configStore.remove(SECTION_KEYS, name, SYSTEM, null, KEYS_FILE_SCHEMA);
  }

  /** 该引用名对应的密钥是否可取（只报布尔，**不回报值**）。 */
  public boolean keyConfigured(String credentialsRef) {
    return keyNameOf(credentialsRef)
        .map(
            name ->
                configStore
                    .get(SECTION_KEYS, name)
                    .filter(node -> node.isTextual() && !node.asText().isBlank())
                    .isPresent())
        .orElse(false);
  }

  /** {@code keys.<name>} ⇒ {@code <name>}；其它形态（含空白 = 匿名）⇒ 空。 */
  static Optional<String> keyNameOf(String credentialsRef) {
    if (credentialsRef == null
        || credentialsRef.isBlank()
        || !credentialsRef.startsWith(SECTION_KEYS + ".")) {
      return Optional.empty();
    }
    String name = credentialsRef.substring(SECTION_KEYS.length() + 1);
    return name.isBlank() ? Optional.empty() : Optional.of(name);
  }

  /**
   * 迁移旧格式：**只在目标为空时做**，且**不删旧文件**。
   *
   * <p>按优先级依次尝试：① {@code <store>/llm-providers.json}（更具体的 store 覆盖）；② 仓库默认配置 {@code
   * config/llm-providers.json}（{@link #DEFAULT_REPO_CONFIG}，兜底种子）。第一个能迁出条目的源即止——
   * 两者是"同一份旧格式的两种来源"，不是叠加，故不把第二个源的内容并进来。
   *
   * <p>★ **为什么保留旧文件**：它是"迁移来源 / 缓存"（D-A 裁定）——迁移是一次性动作，旧文件留着给用户对照/回退； 删它是另一件事，不在本类的射程内。★
   * **为什么只在目标为空时迁移**：避免覆盖用户已经在 AgentLib 配置里改过的东西。
   *
   * <p>旧的密钥形态折算：{@code LITERAL} 与 {@code ENV} / {@code FILE} 的**值此刻可解析**时写成 {@code keys.<id>}；
   * 解析不到的**不猜**（只留路由、{@code credentialsRef} 保留原引用名，由 {@link SimosApiKeySource} 在使用期 按 ENV/FILE
   * 形态解析）——`ENV`/`FILE` 引用名可能与 AgentLib 的密钥名不同名，直接当密钥名会造出一个空键。
   */
  public void migrateLegacyIfPresent() {
    if (!isEmpty()) {
      return;
    }
    int migrated = migrateFrom(storeRoot.resolve(LEGACY_FILE_NAME));
    if (migrated == 0) {
      migrated = migrateFrom(repoDefaultConfig);
    }
    if (migrated > 0) {
      LOG.info("旧 LLM 配置已迁移到 AgentLib 配置根：{} 项", migrated);
    }
  }

  private int migrateFrom(Path legacy) {
    if (legacy == null || !Files.isRegularFile(legacy)) {
      return 0;
    }
    List<LegacyRow> rows = readLegacy(legacy);
    int migrated = 0;
    for (LegacyRow row : rows) {
      try {
        String credentialsRef = row.migratedCredentialsRef();
        if (row.keyValue() != null) {
          putKey(row.migratedKeyName(), row.keyValue());
        }
        upsertRoute(row.id(), row.baseUrl(), row.model(), credentialsRef, row.readTimeoutMs());
        migrated++;
      } catch (RuntimeException e) {
        LOG.warn(
            "旧 LLM 配置项迁移失败（跳过该项，其余照迁） id={} reason={}", row.id(), e.getClass().getSimpleName());
      }
    }
    if (migrated > 0) {
      LOG.info("旧 LLM 配置已迁移：{} 项（源文件保留：{}）", migrated, legacy.getFileName());
    }
    return migrated;
  }

  /** 旧格式的一行（只取本类需要的字段；未知字段忽略）。 */
  private record LegacyRow(
      String id,
      String baseUrl,
      String model,
      String envRef,
      String fileRef,
      String literalValue,
      long readTimeoutMs) {

    /** 目标密钥名：{@code keys.<id>}（id 已在写入前做过形态校验）。 */
    String migratedKeyName() {
      return id;
    }

    /** 迁移到 AgentLib 形态的 {@code credentialsRef}。 */
    String migratedCredentialsRef() {
      if (literalValue != null || envRef != null || fileRef != null) {
        return "keys." + id;
      }
      return "";
    }

    /** 能被 **AgentLib 的 `keys.*`** 承载的值：只有 LITERAL（值就在配置里）。ENV/FILE 的值此刻读得到也不写盘。 */
    String keyValue() {
      return literalValue;
    }
  }

  private List<LegacyRow> readLegacy(Path legacy) {
    JsonNode root;
    try {
      root = MAPPER.readTree(Files.readString(legacy, StandardCharsets.UTF_8));
    } catch (IOException e) {
      LOG.warn("旧 LLM 配置文件不可读（忽略，不迁移）: {}", legacy);
      return List.of();
    }
    JsonNode providers = root.path("providers");
    if (!providers.isArray()) {
      return List.of();
    }
    List<LegacyRow> rows = new ArrayList<>();
    for (JsonNode node : providers) {
      String id = textOrNull(node, "id");
      String baseUrl = textOrNull(node, "baseUrl");
      String model = textOrNull(node, "model");
      if (id == null || baseUrl == null || model == null) {
        continue;
      }
      String envRef = null;
      String fileRef = null;
      String literal = textOrNull(node, "apiKey");
      JsonNode ref = node.get("apiKeyRef");
      if (ref != null && ref.isObject()) {
        String kind = textOrNull(ref, "kind");
        String value = textOrNull(ref, "ref");
        if ("ENV".equals(kind)) {
          envRef = value;
        } else if ("FILE".equals(kind)) {
          fileRef = value;
        } else if ("LITERAL".equals(kind)) {
          literal = value;
        }
      }
      JsonNode timeout = node.get("timeoutMs");
      long readTimeoutMs = timeout != null && timeout.isNumber() ? timeout.asLong() : 120_000L;
      rows.add(new LegacyRow(id, baseUrl, model, envRef, fileRef, literal, readTimeoutMs));
    }
    return rows;
  }

  /** 掩码：密钥引用**不是密钥值**，但形态上可能内嵌凭据（如把 URL 当引用）⇒ 白名单外一律抹成占位符。 */
  private static String maskCredentials(String credentialsRef) {
    if (credentialsRef == null || credentialsRef.isBlank()) {
      return "";
    }
    return credentialsRef.matches("[A-Za-z0-9_.-]{1,64}") ? credentialsRef : "****";
  }

  private static Map<String, Object> capabilitiesView(ModelCapabilities caps) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("toolCalling", caps.toolCalling());
    view.put("reasoning", caps.reasoning());
    view.put("maxContext", caps.maxContext());
    view.put("maxOutput", caps.maxOutput());
    // ★ P4（2026-09-24）：vision 也报出来。它是**决策人链路是否发图**的唯一依据（见 ProviderLlm）——
    //   配了却在界面上看不见，就等于让运维无法回答"这个人为什么收不到图"。
    view.put("vision", caps.vision());
    return view;
  }

  private static JsonNode schema(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new IllegalStateException("内置 schema 不是合法 JSON（编程错误）", e);
    }
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText();
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
  }

  /** 写路由时用的整文件 schema（供测试引用）。 */
  static JsonNode routesFileSchema() {
    return ROUTES_FILE_SCHEMA;
  }
}
