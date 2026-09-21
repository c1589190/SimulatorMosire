package io.mosire.simos.app.llm;

/**
 * 密钥**引用**（M11 spec §1.3 + 用户裁定「密钥可放配置文件」）：记"值在哪"（环境变量名 / 文件路径），或**字面值**。
 *
 * <p>★ **引用是本仓的默认形态**：凡是可能被序列化、打印或进视图的地方，都只拿引用、拿不到值——值只在 {@link
 * LlmProviderRegistry#resolveSecret} 调用时短暂存在。判据 C2：{@code ENV}/{@code FILE} 引用下，解析出的值
 * **绝不落进配置文件字节 / 日志 / 视图**。
 *
 * <p>★ **{@code LITERAL} 是用户裁定的例外**：允许配置文件（{@code config/llm-providers.json} 或 {@code
 * <store>/llm-providers.json}）直接携带密钥值。此时 {@code ref} **就是值本身**；{@link
 * LlmProviderRegistry#view} 把它掩成 {@code ****}、日志只报长度——即"值只活在用户自己的配置文件里，**绝不进源码**"
 * （源码零 key 由 {@code NoHardcodedSecretsTest} 守卫）。
 */
public record SecretRef(Kind kind, String ref) {

  /** 引用种类：环境变量名 / 文件路径 / 字面值（仅配置文件可携带）。 */
  public enum Kind {
    ENV,
    FILE,
    LITERAL
  }

  public SecretRef {
    if (kind == null) {
      throw new IllegalArgumentException("kind 不得为 null");
    }
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("ref 不得为空白（密钥引用必须点名变量名、路径或值）");
    }
  }

  public static SecretRef env(String name) {
    return new SecretRef(Kind.ENV, name);
  }

  public static SecretRef file(String path) {
    return new SecretRef(Kind.FILE, path);
  }

  public static SecretRef literal(String value) {
    return new SecretRef(Kind.LITERAL, value);
  }
}
