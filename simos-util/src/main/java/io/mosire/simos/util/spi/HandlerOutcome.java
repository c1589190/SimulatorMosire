package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import java.util.Objects;

/**
 * 命令处理的结果（spec §4.3）：**只有两种**——要么给出一个变更集，要么给出拒绝理由。
 *
 * <p>★ 用 {@code sealed} 而不是枚举 + 字段：两种形态的载荷**不同型**（一个是 {@link ChangeSet}，一个是 {@link
 * String}），枚举会让其中一半永远是 {@code null}。封闭性由 permits 保证 ⇒ Core 的 {@code switch} 是穷尽的（这**不违反** C16：被
 * switch 的是 Core 自己的封闭类型，不是领域载荷）。
 */
public sealed interface HandlerOutcome {

  /** 处理成功：变更集交给 Core 落盘。 */
  record Applied(ChangeSet changeSet) implements HandlerOutcome {

    public Applied {
      Objects.requireNonNull(changeSet, "changeSet");
    }
  }

  /** 处理失败：理由进 `simos.command.rejected` 事件的 payload。 */
  record Rejected(String reason) implements HandlerOutcome {

    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
