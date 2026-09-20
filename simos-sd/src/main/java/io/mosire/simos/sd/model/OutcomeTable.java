package io.mosire.simos.sd.model;

import java.util.List;

/**
 * 结局表（spec §三.3，N2）：**categorical distribution / weighted outcome table**。
 *
 * <p>★ 空表**非法**（构造期强制）：一个阶段没有候选结局 ⇒ 无法"恰选一个"。权重合法性（{@code weight > 0}）在 {@link OutcomeOption} 构造期。
 *
 * <p>★ {@code options} **保序不可变**（{@code List.copyOf} 保序，且拒绝 null 元素）。
 */
public record OutcomeTable(List<OutcomeOption> options) {

  public OutcomeTable {
    if (options == null) {
      throw new IllegalArgumentException("options 不得为 null");
    }
    if (options.isEmpty()) {
      throw new IllegalArgumentException("OutcomeTable.options 不得为空（N2）");
    }
    options = List.copyOf(options);
  }
}
