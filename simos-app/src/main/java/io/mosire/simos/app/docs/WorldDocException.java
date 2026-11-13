package io.mosire.simos.app.docs;

/**
 * 世界文档错误：把“输入坏 / 不存在 / 已存在”分成稳定错误码，HTTP/MCP 层才能给出确定回答。
 *
 * <p>D-036：世界文档是独立持久数据，不随 world revision 变化；因此这里的错误码与命令面
 * （{@code CommandResult}）无关，是文档面自己的契约。
 */
public final class WorldDocException extends RuntimeException {

  /** 稳定错误码。 */
  public enum Code {
    INVALID,
    NOT_FOUND,
    EXISTS
  }

  private final Code code;

  public WorldDocException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public WorldDocException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public static WorldDocException invalid(String message) {
    return new WorldDocException(Code.INVALID, message);
  }

  public static WorldDocException notFound(String message) {
    return new WorldDocException(Code.NOT_FOUND, message);
  }

  public static WorldDocException exists(String message) {
    return new WorldDocException(Code.EXISTS, message);
  }
}
