package io.mosire.simos.app.query;

/**
 * ★★★ <b>W10（A1 启动竞态）：世界尚未就绪</b> —— 创世（{@code (main, 1)} 的 revision 行 + checkpoint）还没落盘， 而 GUI/MCP
 * 的口已经开始应答。
 *
 * <p>它是一条<b>具名</b>的、可判别的失败：读口据此回 <b>503</b>（而不是 500 + 14 行栈），且日志里能一眼看出 "是启动窗口，不是状态坏了"。
 *
 * <p>★ 它不是"重试语义"：{@link QueryService#stateAt} 在抛它之前已经**等过**创世就绪（见 {@code
 * CoreSimos#awaitGenesisReady}）；只有等待超时 / 本进程不创世却查不到分支时才走到这里。
 */
public final class WorldNotReadyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WorldNotReadyException(String message) {
    super(message);
  }
}
