package io.mosire.simos.app.llm;

/**
 * 密钥**引用**（M11 spec §1.3，判据 C2/C3）：只记"值在哪"（环境变量名 / 文件路径），**绝不记值**。
 *
 * <p>★ 本类型进配置文件、进日志与视图；它是密钥纪律的结构化落点——凡是可能被序列化或打印的地方，都只能拿到引用、 拿不到值（值只在 {@link
 * LlmProviderRegistry#resolveSecret} 调用时短暂存在）。
 */
public record SecretRef(Kind kind, String ref) {

  /** 引用种类：环境变量名 / 文件路径。 */
  public enum Kind {
    ENV,
    FILE
  }

  public SecretRef {
    if (kind == null) {
      throw new IllegalArgumentException("kind 不得为 null");
    }
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("ref 不得为空白（密钥引用必须点名变量名或路径）");
    }
  }

  public static SecretRef env(String name) {
    return new SecretRef(Kind.ENV, name);
  }

  public static SecretRef file(String path) {
    return new SecretRef(Kind.FILE, path);
  }
}
