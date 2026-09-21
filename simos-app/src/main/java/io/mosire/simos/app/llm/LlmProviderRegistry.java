package io.mosire.simos.app.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM provider 注册表（M11 spec §二 + 用户裁定「三层配置」）：**app 层基础设施**。
 *
 * <p>★ **三层配置与优先级**（用户裁定逐字）：① **仓库默认配置** {@code <repo>/config/llm-providers.json}
 * （入库，**可带 key**）；② **store 覆盖** {@code <store>/llm-providers.json}（运行时改、**不进 git**）；③
 * 代码里**零 key**。**② 按 id 覆盖 ①**；两处都不存在 / 都空 ⇒ 解析时**明确报"未配置"**（见 {@link
 * LlmProviderResolver}），**绝不静默兜底**。
 *
 * <p>★ **不是世界事实**（铁律 3）：它不进 sd、不落 revision、不进 {@code modes.js} 命令白名单。决策人只持有 provider 的
 * id，解析发生在**使用期**（{@link LlmProviderResolver}）。
 *
 * <p>★ **密钥纪律**（判据 C2/C3）：{@code ENV}/{@code FILE} 引用只存引用、值绝不落盘；{@code LITERAL}
 * 是用户裁定的例外（配置文件直接携带值），但 {@link #view} 一律掩码、日志只报长度、异常只点名引用。
 *
 * <p>★ 列表按 id 字典序（{@link TreeMap}）⇒ 同一状态两次响应逐字节相同。删除对 **默认层**的 provider 也有效：
 * store 文件记 {@code deletedIds} 墓碑，合并时剔除（否则默认层的项删不掉）。
 */
public final class LlmProviderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(LlmProviderRegistry.class);

  /** store 覆盖文件名（放 store 根下，与 {@code simos.db} 同层）。 */
  public static final String FILE_NAME = "llm-providers.json";

  /** 仓库默认配置的相对路径（相对进程工作目录；{@code Shell} 从仓根启动时命中）。 */
  public static final Path DEFAULT_REPO_CONFIG = Path.of("config", FILE_NAME);

  private static final int FILE_VERSION = 1;

  /** 字面密钥在视图里的掩码（绝不回显值）。 */
  private static final String MASK = "****";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final Path storeFile;
  private final Path defaultsFile;
  private final Map<String, String> env;

  /** 默认层（仓库配置，加载后不变）。 */
  private final TreeMap<String, LlmProvider> defaults = new TreeMap<>();

  /** store 层（运行时增删，写盘）。 */
  private final TreeMap<String, LlmProvider> storeRows = new TreeMap<>();

  /** store 层的删除墓碑：删掉默认层某项时记下 id，合并时剔除。 */
  private final TreeSet<String> deletedIds = new TreeSet<>();

  private final boolean defaultsPresent;
  private final boolean storePresent;

  private LlmProviderRegistry(
      Path storeFile,
      Path defaultsFile,
      Map<String, String> env,
      List<LlmProvider> defaults,
      List<LlmProvider> storeRows,
      List<String> deletedIds,
      boolean defaultsPresent,
      boolean storePresent) {
    this.storeFile = storeFile;
    this.defaultsFile = defaultsFile;
    this.env = Map.copyOf(env);
    for (LlmProvider provider : defaults) {
      this.defaults.put(provider.id(), provider);
    }
    for (LlmProvider provider : storeRows) {
      this.storeRows.put(provider.id(), provider);
    }
    this.deletedIds.addAll(deletedIds);
    this.defaultsPresent = defaultsPresent;
    this.storePresent = storePresent;
  }

  /** 加载 {@link #DEFAULT_REPO_CONFIG} + {@code <store>/llm-providers.json}（生产路径）。 */
  public static LlmProviderRegistry load(Path storeDir) {
    return load(storeDir, DEFAULT_REPO_CONFIG, System.getenv());
  }

  /** 同 {@link #load(Path)}，环境变量可注入（测试用；生产走 {@link System#getenv()}）。 */
  public static LlmProviderRegistry load(Path storeDir, Map<String, String> env) {
    return load(storeDir, DEFAULT_REPO_CONFIG, env);
  }

  /**
   * 三层加载：默认层 + store 覆盖层（按 id 覆盖）。文件存在但读不动 / 不是合法 JSON ⇒ **抛**（不静默当空表）。
   *
   * @param storeDir store 根目录（{@code <store>/llm-providers.json}）
   * @param defaultsFile 仓库默认配置路径（不存在 ⇒ 默认层为空，不报错）
   * @param env 环境变量（供 {@code ENV} 引用解析）
   */
  public static LlmProviderRegistry load(
      Path storeDir, Path defaultsFile, Map<String, String> env) {
    Objects.requireNonNull(storeDir, "storeDir");
    Objects.requireNonNull(defaultsFile, "defaultsFile");
    Objects.requireNonNull(env, "env");
    Path storeFile = storeDir.resolve(FILE_NAME);
    boolean defaultsPresent = Files.exists(defaultsFile);
    boolean storePresent = Files.exists(storeFile);
    List<LlmProvider> base = readProviders(defaultsFile, defaultsPresent);
    ProviderFile parsed = readStoreFile(storeFile, storePresent);
    return new LlmProviderRegistry(
        storeFile,
        defaultsFile,
        env,
        base,
        parsed.providers(),
        parsed.deletedIds(),
        defaultsPresent,
        storePresent);
  }

  /** store 覆盖文件路径（诊断用；不打内容）。 */
  public Path file() {
    return storeFile;
  }

  /** 仓库默认配置文件路径（诊断用；不打内容）。 */
  public Path defaultsFile() {
    return defaultsFile;
  }

  /** 两处配置是否都不存在（供"未配置"消息措辞）。 */
  public boolean hasAnyLayer() {
    return defaultsPresent || storePresent;
  }

  /** 合并后的 provider（按 id 字典序）。 */
  public List<LlmProvider> list() {
    return List.copyOf(merged().values());
  }

  /** 查一条（合并后）。 */
  public Optional<LlmProvider> find(String id) {
    return id == null ? Optional.empty() : Optional.ofNullable(merged().get(id));
  }

  /** 新增或覆盖一条，并写盘（清掉同 id 的墓碑）。 */
  public LlmProvider upsert(LlmProvider provider) {
    Objects.requireNonNull(provider, "provider");
    storeRows.put(provider.id(), provider);
    deletedIds.remove(provider.id());
    write();
    return provider;
  }

  /**
   * 删除一条（含**默认层**的项：落墓碑而非只删 store 行），并写盘。返回合并后原本是否存在。
   */
  public boolean delete(String id) {
    if (id == null || !merged().containsKey(id)) {
      return false;
    }
    storeRows.remove(id);
    if (defaults.containsKey(id)) {
      deletedIds.add(id);
    }
    write();
    return true;
  }

  /**
   * 解析密钥引用：ENV ⇒ 环境变量值；FILE ⇒ 文件内容（trim）；LITERAL ⇒ 值本身。
   *
   * <p>★ **只打印 kind + ref + 长度**（LITERAL 只打长度，不打值）；失败时只打 kind + ref。
   */
  public Optional<String> resolveSecret(SecretRef ref) {
    Objects.requireNonNull(ref, "ref");
    return switch (ref.kind()) {
      case ENV -> {
        String value = env.get(ref.ref());
        if (value == null || value.isBlank()) {
          LOG.warn("密钥引用不可解析 kind=ENV name={}", ref.ref());
          yield Optional.empty();
        }
        LOG.info("读取密钥引用 kind=ENV name={} length={}", ref.ref(), value.length());
        yield Optional.of(value);
      }
      case FILE -> {
        Path path = Path.of(ref.ref());
        if (!Files.exists(path)) {
          LOG.warn("密钥引用不可解析 kind=FILE path={}", path);
          yield Optional.empty();
        }
        try {
          String value = Files.readString(path, StandardCharsets.UTF_8).trim();
          if (value.isBlank()) {
            LOG.warn("密钥引用为空 kind=FILE path={}", path);
            yield Optional.empty();
          }
          LOG.info("读取密钥引用 kind=FILE path={} length={}", path, value.length());
          yield Optional.of(value);
        } catch (IOException e) {
          LOG.warn("密钥引用读取失败 kind=FILE path={}", path);
          yield Optional.empty();
        }
      }
      case LITERAL -> {
        String value = ref.ref();
        LOG.info("读取密钥引用 kind=LITERAL length={}", value.length());
        yield Optional.of(value);
      }
    };
  }

  /** 掩码视图：**绝不回显密钥值**（LITERAL 掩成 {@code ****}），只报引用与"是否可解析"。 */
  public Map<String, Object> view(LlmProvider provider) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", provider.id());
    view.put("name", provider.name());
    view.put("protocol", provider.protocol());
    view.put("baseUrl", provider.baseUrl());
    view.put("model", provider.model());
    view.put("temperature", provider.temperature());
    view.put("maxTokens", provider.maxTokens());
    view.put("timeoutMs", provider.timeout().toMillis());
    Map<String, Object> keyRef = new LinkedHashMap<>();
    keyRef.put("kind", provider.apiKeyRef().kind().name());
    keyRef.put(
        "ref", provider.apiKeyRef().kind() == SecretRef.Kind.LITERAL ? MASK : provider.apiKeyRef().ref());
    view.put("apiKeyRef", keyRef);
    view.put("secretResolvable", resolveSecret(provider.apiKeyRef()).isPresent());
    return view;
  }

  /** 全部 provider 的掩码视图（按 id 字典序）。 */
  public List<Map<String, Object>> views() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (LlmProvider provider : merged().values()) {
      out.add(view(provider));
    }
    return List.copyOf(out);
  }

  private TreeMap<String, LlmProvider> merged() {
    TreeMap<String, LlmProvider> merged = new TreeMap<>(defaults);
    merged.keySet().removeAll(deletedIds);
    merged.putAll(storeRows);
    return merged;
  }

  private void write() {
    List<ProviderRow> rows = new ArrayList<>(storeRows.size());
    for (LlmProvider provider : storeRows.values()) {
      rows.add(ProviderRow.from(provider));
    }
    ProviderFile out = new ProviderFile(FILE_VERSION, rows, List.copyOf(deletedIds));
    String json;
    try {
      json = MAPPER.writeValueAsString(out);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("LLM provider 配置序列化失败", e);
    }
    try {
      Path parent = storeFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(storeFile, json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("LLM provider 配置写入失败: " + storeFile, e);
    }
  }

  private static List<LlmProvider> readProviders(Path file, boolean present) {
    if (!present) {
      return List.of();
    }
    ProviderFile parsed = read(file);
    List<LlmProvider> out = new ArrayList<>();
    if (parsed.providers() != null) {
      for (ProviderRow row : parsed.providers()) {
        out.add(row.toProvider());
      }
    }
    return List.copyOf(out);
  }

  private static ProviderFile readStoreFile(Path file, boolean present) {
    if (!present) {
      return new ProviderFile(FILE_VERSION, List.of(), List.of());
    }
    ProviderFile parsed = read(file);
    return new ProviderFile(
        FILE_VERSION,
        parsed.providers() == null ? List.of() : parsed.providers(),
        parsed.deletedIds() == null ? List.of() : parsed.deletedIds());
  }

  private static ProviderFile read(Path file) {
    try {
      return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), ProviderFile.class);
    } catch (IOException e) {
      throw new IllegalStateException("LLM provider 配置读取失败: " + file, e);
    }
  }

  /** 线格式根（{@code {"version":1,"providers":[…],"deletedIds":[…]}}）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ProviderFile(int version, List<ProviderRow> providers, List<String> deletedIds) {}

  /**
   * 一条 provider 的线格式。字段用**装箱类型**表示"未给"（{@code null}），由 {@link #toProvider()} 填默认；
   * 密钥二选一：{@code apiKeyRef}（引用）或 {@code apiKey}（字面值，仅配置文件可携带）。
   *
   * <p>★ {@code timeoutMs} 是 {@code long}——{@code Duration} 不是裸 databind 的原生类型，序列化成毫秒整数避免依赖未装配的
   * jsr310 模块。
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ProviderRow(
      String id,
      String name,
      String protocol,
      String baseUrl,
      String model,
      Double temperature,
      Integer maxTokens,
      Long timeoutMs,
      SecretRefRow apiKeyRef,
      String apiKey) {

    static ProviderRow from(LlmProvider provider) {
      if (provider.apiKeyRef().kind() == SecretRef.Kind.LITERAL) {
        return new ProviderRow(
            provider.id(),
            provider.name(),
            provider.protocol(),
            provider.baseUrl(),
            provider.model(),
            provider.temperature(),
            provider.maxTokens(),
            provider.timeout().toMillis(),
            null,
            provider.apiKeyRef().ref());
      }
      return new ProviderRow(
          provider.id(),
          provider.name(),
          provider.protocol(),
          provider.baseUrl(),
          provider.model(),
          provider.temperature(),
          provider.maxTokens(),
          provider.timeout().toMillis(),
          new SecretRefRow(provider.apiKeyRef().kind().name(), provider.apiKeyRef().ref()),
          null);
    }

    LlmProvider toProvider() {
      SecretRef ref;
      if (apiKey != null && !apiKey.isBlank()) {
        ref = SecretRef.literal(apiKey);
      } else if (apiKeyRef != null) {
        ref = new SecretRef(SecretRef.Kind.valueOf(apiKeyRef.kind()), apiKeyRef.ref());
      } else {
        throw new IllegalArgumentException("provider " + id + " 既无 apiKey 也无 apiKeyRef");
      }
      String nameOrDefault = name == null || name.isBlank() ? id : name;
      String protocolOrDefault =
          protocol == null || protocol.isBlank()
              ? LlmProvider.PROTOCOL_OPENAI_COMPATIBLE
              : protocol;
      double temperatureOrDefault =
          temperature == null ? LlmProvider.DEFAULT_TEMPERATURE : temperature;
      int maxTokensOrDefault = maxTokens == null ? LlmProvider.DEFAULT_MAX_TOKENS : maxTokens;
      long timeoutOrDefault = timeoutMs == null ? LlmProvider.DEFAULT_TIMEOUT_MS : timeoutMs;
      return new LlmProvider(
          id,
          nameOrDefault,
          protocolOrDefault,
          baseUrl,
          model,
          temperatureOrDefault,
          maxTokensOrDefault,
          ref,
          Duration.ofMillis(timeoutOrDefault));
    }
  }

  /** 密钥引用的线格式（{@code {"kind":"ENV|FILE|LITERAL","ref":"…"}}）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SecretRefRow(String kind, String ref) {}
}
