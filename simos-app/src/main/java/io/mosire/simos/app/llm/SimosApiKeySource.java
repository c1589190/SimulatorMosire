package io.mosire.simos.app.llm;

import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 取密钥的 SPI 实现（D-B 裁定的"逃生口"）：**先 {@code keys.*}（AgentLib 的 ConfigStore），再 ENV，再 FILE**。
 *
 * <p>★ **为什么需要它**：AgentLib 官方的 {@code ConfigApiKeySource} **有意只认 {@code keys.*}**（读环境变量取密钥是被
 * AgentLib 侧明令禁止的第二条密钥通路）。而 simos 的旧配置支持 {@code ENV} / {@code FILE} 形态（部署时更友好）。 裁定给的出路是**实现官方
 * SPI**，而不是去改 AgentLib、更不是绕开 {@code ConfigStore} 直接读 env —— 那两条都会让"密钥 从哪来"失去单一答案。本类就是那条官方出路。
 *
 * <p>★ **解析顺序（优先级）**：① {@code keys.<name>}（AgentLib 配置里，SYSTEM 可写——生产正途）；② 环境变量 {@code
 * SIMO_LLM_KEY_<NAME>}；③ 文件 {@code <store>/keys/<name>}（内容 trim）。
 *
 * <p>三条都落空 ⇒ 返回 {@link Optional#empty()}（= **匿名调用**，本地部署如 Ollama 的正常形态），**不抛**—— AgentLib 的 SPI
 * 契约把"没有密钥"定义成空、把"取不到密钥"定义成异常（取密钥实现自己抛），两件事不能混：混了会让"没配" 看起来像"配错了"。
 *
 * <p>★ **密钥纪律（不可协商）**：值**绝不**进日志 / 异常 / 事件 / argv / stdio；诊断信息只有**引用名**与**长度**。
 * 引用名过保守白名单才回显（引用名本身可能是含凭据的 URL）。引用名当文件名时**只取末段**并净化——用引用名直接拼路径会读出 store 之外的文件（越界读）。
 *
 * <p>本类**有状态但不可变**：一条路由一个实例，密钥引用在装配时定下（同一条路由的引用不会中途换人）。这样 {@link #apiKey()} 才能满足"每次调用现取"的 SPI
 * 契约（**可轮换的是密钥值，不是引用**）。
 */
public final class SimosApiKeySource implements OpenAICompatibleLlmClient.ApiKeySource {

  private static final Logger LOG = LoggerFactory.getLogger(SimosApiKeySource.class);

  /** 环境变量前缀（{@code SIMO_LLM_KEY_DEEPSEEK} 之类）。 */
  public static final String ENV_PREFIX = "SIMO_LLM_KEY_";

  /** store 下的密钥文件目录：{@code <store>/keys/<name>}。 */
  static final String KEY_DIR_NAME = "keys";

  /** 可安全回显的引用名（保守白名单：不含点、冒号、斜杠等可拼出 URL/路径的字符）。 */
  private static final String SAFE_NAME_PATTERN = "[A-Za-z0-9_-]{1,64}";

  private static final String REDACTED = "<不可显示的引用名>";

  private final AgentLibLlmConfig config;
  private final Path storeRoot;
  private final Map<String, String> env;

  /** 当前路由的密钥引用（{@code keys.<name>} / 裸 ENV 名 / 裸文件路径；空白 = 匿名）。 */
  private final String reference;

  public SimosApiKeySource(
      AgentLibLlmConfig config, Path storeRoot, String credentialsRef, Map<String, String> env) {
    this.config = Objects.requireNonNull(config, "config");
    this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
    this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
    this.reference = credentialsRef == null ? "" : credentialsRef.strip();
  }

  /** 生产构造：环境变量取 {@link System#getenv()}。 */
  public SimosApiKeySource(AgentLibLlmConfig config, Path storeRoot, String credentialsRef) {
    this(config, storeRoot, credentialsRef, System.getenv());
  }

  @Override
  public Optional<String> apiKey() {
    return resolve(reference);
  }

  /** 解析引用：{@code keys.<name>} → ENV → FILE；都落空 ⇒ 空（匿名），**不抛**。 */
  Optional<String> resolve(String credentialsRef) {
    if (credentialsRef == null || credentialsRef.isBlank()) {
      return Optional.empty(); // 有意的匿名：本地部署（Ollama 等）的正常形态，不是失败
    }
    Optional<String> fromConfig = resolveFromConfig(credentialsRef);
    if (fromConfig.isPresent()) {
      return fromConfig;
    }
    Optional<String> fromEnv = resolveFromEnv(credentialsRef);
    if (fromEnv.isPresent()) {
      return fromEnv;
    }
    return resolveFromFile(credentialsRef);
  }

  /** ① {@code keys.<name>}（AgentLib 配置存储）。 */
  private Optional<String> resolveFromConfig(String credentialsRef) {
    return AgentLibLlmConfig.keyNameOf(credentialsRef)
        .flatMap(name -> config.configStore().get("keys", name))
        .filter(node -> node.isTextual() && !node.asText().isBlank())
        .map(
            node -> {
              String value = node.asText();
              LOG.info(
                  "读取密钥引用 kind=CONFIG name={} length={}", safeName(credentialsRef), value.length());
              return value;
            });
  }

  /** ② 环境变量 {@code SIMO_LLM_KEY_<NAME>}。 */
  private Optional<String> resolveFromEnv(String credentialsRef) {
    String variable = ENV_PREFIX + normalizeEnvName(credentialsRef);
    String value = env.get(variable);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    LOG.info("读取密钥引用 kind=ENV name={} length={}", variable, value.length());
    return Optional.of(value);
  }

  /** ③ store 下的密钥文件 {@code <store>/keys/<name>}。 */
  private Optional<String> resolveFromFile(String credentialsRef) {
    Path path = storeRoot.resolve(KEY_DIR_NAME).resolve(safeFileName(credentialsRef));
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      String value = Files.readString(path, StandardCharsets.UTF_8).trim();
      if (value.isBlank()) {
        LOG.warn("密钥文件为空 path={}", path);
        return Optional.empty();
      }
      LOG.info("读取密钥引用 kind=FILE path={} length={}", path, value.length());
      return Optional.of(value);
    } catch (IOException e) {
      LOG.warn("密钥文件读取失败 path={}", path);
      return Optional.empty();
    }
  }

  /** 引用名 → 环境变量名（大写、非字母数字换 {@code _}）。 */
  static String normalizeEnvName(String credentialsRef) {
    StringBuilder out = new StringBuilder();
    for (char c : credentialsRef.toCharArray()) {
      out.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
    }
    return out.toString();
  }

  /** 引用名 → 文件名（**只取末段**：用引用名当路径会走出 store 之外，那是越界读）。 */
  static String safeFileName(String credentialsRef) {
    String last = credentialsRef;
    int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
    if (slash >= 0) {
      last = last.substring(slash + 1);
    }
    return last.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  private static String safeName(String name) {
    return name.matches(SAFE_NAME_PATTERN) ? name : REDACTED;
  }
}
