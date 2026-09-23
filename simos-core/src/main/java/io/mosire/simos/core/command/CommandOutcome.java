package io.mosire.simos.core.command;

import java.util.Objects;

/**
 * 批中一条命令的结局（{@link BatchResult} 的元素）。
 *
 * <p>★ **为什么复用 {@link CommandResult} 而不是为批另造一套 per-command 结局**：批中一条命令的结局**就是**单条提交会给出的那三种 （{@link
 * CommandResult.Committed} / {@link CommandResult.Rejected} / {@link
 * CommandResult.Conflict}）——复用让调用方在 单条与批量两处拿到同一种形态，不必学两套。本类**不改动** {@link CommandResult} 的任何既有语义。
 *
 * <p>★ **批提交成功时**每条都是 {@code Committed}，且 **{@code ref} 相同**——一批 = 一条 revision，全部命令生效于同一个新坐标。
 *
 * <p>★ **整批未提交时**：真正被拒的那些带自己的拒因；已被 handler 接受、但因同批有命令被拒而**随整批复原**的那些，也报 {@code
 * Rejected}（原因写明是整批未提交）。批是原子的——从世界的角度看它们都没生效。
 */
public record CommandOutcome(CommandEnvelope command, CommandResult result) {

  public CommandOutcome {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(result, "result");
  }

  /** 该条是否**已生效**（{@code Committed} ⇒ 批提交成功、该条落在批的新坐标上）。 */
  public boolean committed() {
    return result instanceof CommandResult.Committed;
  }

  /** 该条是否**未生效**（拒 / 冲突 / 随整批复原）。 */
  public boolean rejected() {
    return result instanceof CommandResult.Rejected;
  }
}
