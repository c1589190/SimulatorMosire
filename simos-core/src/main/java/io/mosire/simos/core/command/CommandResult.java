package io.mosire.simos.core.command;

import io.mosire.simos.util.state.StateRef;
import java.util.Objects;

/**
 * {@code CommandBus.submit} 的三种结局（spec §4.4）。**封闭**——Core 的 {@code switch} 因此是穷尽的，这**不违反** C16： 被
 * switch 的是 Core 自己的类型，不是领域载荷。
 *
 * <p>★ **{@code Conflict} 带的是"真实的 head"**（含分支），不是被拒绝的那个期望值：调用方拿到它就能直接重试或分岔， 不必再查一次库。★ 分支不存在时**没有
 * head 可报**，走 {@link Rejected} 而不是编一个坐标出来。
 */
public sealed interface CommandResult {

  /** 提交成功：给出**新 revision** 的坐标。 */
  record Committed(StateRef ref) implements CommandResult {

    public Committed {
      Objects.requireNonNull(ref, "ref");
    }
  }

  /** 命令被拒（handler 拒绝、类型未注册、分支不存在）：理由是给人/给事件看的文本。 */
  record Rejected(String reason) implements CommandResult {

    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** 乐观并发冲突（C17）：{@code current} 是**真实 head**。 */
  record Conflict(StateRef current) implements CommandResult {

    public Conflict {
      Objects.requireNonNull(current, "current");
    }
  }
}
