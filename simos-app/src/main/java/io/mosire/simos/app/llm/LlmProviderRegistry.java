package io.mosire.simos.app.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM provider 注册表（M11 spec §二）：**app 层基础设施**，落盘 {@code <store>/llm-providers.json}。
 *
 * <p>★ **不是世界事实**（铁律 3）：它不进 sd、不落 revision、不进 {@code modes.js} 命令白名单。决策人只持有 provider 的
 * id，解析发生在**使用期**（{@link LlmProviderResolver}）。
 *
 * <p>★ **密钥纪律**（判据 C2/C3）：本类只存 {@link SecretRef}（引用），**绝不存值**；{@link #resolveSecret}
 * 只在调用时短暂读取值，并**只打印 {@code kind + ref + 长度}**（本仓密钥纪律）。异常消息只点名引用，不点名值。
 *
 * <p>★ 列表按 id 字典序（{@link TreeMap}）⇒ 同一状态两次响应逐字节相同（与 GUI 的 C15 口径一致）。
 */
public final class LlmProviderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(LlmProviderRegistry.class);

  /** 配置文件固定名（放 store 根下，与 {@code simos.db} 同层）。 */
  public static final String FILE_NAME = "llm-providers.json";

  private static final int FILE_VERSION = 1;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final Path file;
  private final Map<String, String> env;

  /** 保序按 id（字典序），让落盘与视图都可复现。 */
  private final TreeMap<String, LlmProvider> providers = new TreeMap<>();

  private LlmProviderRegistry(Path file, Map<String, String> env, List<LlmProvider> initial) {
    this.file = file;
    this.env = Map.copyOf(env);
    for (LlmProvider provider : initial) {
      providers.put(provider.id(), provider);
    }
  }

  /** 从 store 目录加载（`<store>/llm-providers.json`；不存在 ⇒ 空表，不报错）。 */
  public static LlmProviderRegistry load(Path storeDir) {
    return load(storeDir, System.getenv());
  }

  /**
   * 从 store 目录加载，环境变量可注入（测试用；生产走 {@link System#getenv()}）。
   *
   * @throws IllegalStateException 文件存在但读不动 / 不是合法 JSON（**不静默当空表**）
   */
  public static LlmProviderRegistry load(Path storeDir, Map<String, String> env) {
    Objects.requireNonNull(storeDir, "storeDir");
    Objects.requireNonNull(env, "env");
    Path file = storeDir.resolve(FILE_NAME);
    List<LlmProvider> initial = new ArrayList<>();
    if (Files.exists(file)) {
      try {
        ProviderFile parsed = MAPPER.readValue(Files.readString(file), ProviderFile.class);
        if (parsed.providers() != null) {
          for (ProviderRow row : parsed.providers()) {
            initial.add(row.toProvider());
          }
        }
      } catch (IOException e) {
        throw new IllegalStateException("LLM provider 配置读取失败: " + file, e);
      }
    }
    return new LlmProviderRegistry(file, env, initial);
  }

  /** 配置文件路径（诊断用；不打内容）。 */
  public Path file() {
    return file;
  }

  /** 按 id 字典序列出全部 provider。 */
  public List<LlmProvider> list() {
    return List.copyOf(providers.values());
  }

  /** 查一条。 */
  public Optional<LlmProvider> find(String id) {
    return id == null ? Optional.empty() : Optional.ofNullable(providers.get(id));
  }

  /** 新增或覆盖一条，并写盘。 */
  public LlmProvider upsert(LlmProvider provider) {
    Objects.requireNonNull(provider, "provider");
    providers.put(provider.id(), provider);
    write();
    return provider;
  }

  /** 删除一条，并写盘。返回是否确有该条。 */
  public boolean delete(String id) {
    boolean removed = providers.remove(id) != null;
    if (removed) {
      write();
    }
    return removed;
  }

  /**
   * 解析密钥引用：ENV ⇒ 环境变量值；FILE ⇒ 文件内容（trim）。引用不存在 / 值为空白 ⇒ {@link Optional#empty()}。
   *
   * <p>★ **只打印引用 + 长度**：成功时 {@code kind/ref/length}，失败时 {@code kind/ref}（异常消息同样只含引用）。
   */
  public Optional<String> resolveSecret(SecretRef ref) {
    Objects.requireNonNull(ref, "ref");
    if (ref.kind() == SecretRef.Kind.ENV) {
      String value = env.get(ref.ref());
      if (value == null || value.isBlank()) {
        LOG.warn("密钥引用不可解析 kind=ENV name={}", ref.ref());
        return Optional.empty();
      }
      LOG.info("读取密钥引用 kind=ENV name={} length={}", ref.ref(), value.length());
      return Optional.of(value);
    }
    Path path = Path.of(ref.ref());
    if (!Files.exists(path)) {
      LOG.warn("密钥引用不可解析 kind=FILE path={}", path);
      return Optional.empty();
    }
    try {
      String value = Files.readString(path).trim();
      if (value.isBlank()) {
        LOG.warn("密钥引用为空 kind=FILE path={}", path);
        return Optional.empty();
      }
      LOG.info("读取密钥引用 kind=FILE path={} length={}", path, value.length());
      return Optional.of(value);
    } catch (IOException e) {
      LOG.warn("密钥引用读取失败 kind=FILE path={}", path);
      return Optional.empty();
    }
  }

  /** 掩码视图：**绝不回显密钥值**，只报引用与"是否可解析"。 */
  public Map<String, Object> view(LlmProvider provider) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", provider.id());
    view.put("baseUrl", provider.baseUrl());
    view.put("model", provider.model());
    view.put("timeoutMs", provider.timeout().toMillis());
    Map<String, Object> keyRef = new LinkedHashMap<>();
    keyRef.put("kind", provider.apiKeyRef().kind().name());
    keyRef.put("ref", provider.apiKeyRef().ref());
    view.put("apiKeyRef", keyRef);
    view.put("secretResolvable", resolveSecret(provider.apiKeyRef()).isPresent());
    return view;
  }

  /** 全部 provider 的掩码视图（按 id 字典序）。 */
  public List<Map<String, Object>> views() {
    List<Map<String, Object>> out = new ArrayList<>(providers.size());
    for (LlmProvider provider : providers.values()) {
      out.add(view(provider));
    }
    return List.copyOf(out);
  }

  private void write() {
    List<ProviderRow> rows = new ArrayList<>(providers.size());
    for (LlmProvider provider : providers.values()) {
      rows.add(ProviderRow.from(provider));
    }
    String json;
    try {
      json = MAPPER.writeValueAsString(new ProviderFile(FILE_VERSION, rows));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("LLM provider 配置序列化失败", e);
    }
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, json);
    } catch (IOException e) {
      throw new IllegalStateException("LLM provider 配置写入失败: " + file, e);
    }
  }

  /** 线格式根（`{"version":1,"providers":[…]}`）。 */
  record ProviderFile(int version, List<ProviderRow> providers) {}

  /**
   * 一条 provider 的线格式：★ `timeoutMs` 是 {@code long}——{@code Duration} 不是裸 databind 的原生类型，
   * 序列化成毫秒整数避免依赖未装配的 jsr310 模块。
   */
  record ProviderRow(
      String id,
      String baseUrl,
      String model,
      String apiKeyRefKind,
      String apiKeyRef,
      long timeoutMs) {

    static ProviderRow from(LlmProvider provider) {
      return new ProviderRow(
          provider.id(),
          provider.baseUrl(),
          provider.model(),
          provider.apiKeyRef().kind().name(),
          provider.apiKeyRef().ref(),
          provider.timeout().toMillis());
    }

    LlmProvider toProvider() {
      SecretRef ref = new SecretRef(SecretRef.Kind.valueOf(apiKeyRefKind), apiKeyRef);
      return new LlmProvider(id, baseUrl, model, ref, Duration.ofMillis(timeoutMs));
    }
  }
}
