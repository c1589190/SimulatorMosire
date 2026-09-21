package io.mosire.simos.sd.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 阶段链的**命令期**校验助手（spec §三.3 N1，计划 C1）。
 *
 * <p>★ **链式条件**：{@code stages[i].exit} 必须与 {@code stages[i+1].entry} **相等**（JP
 * 5-0「上一阶段的结束条件就是下一阶段的起始条件」）—— 不满足时在**命令期**拒绝，理由是 `AddCombatStage：...`（与 {@code SdState}
 * 构造期那条兜底消息**故意不同**，使命令级理由可被判据精确命中）。
 */
public final class CombatStages {

  private CombatStages() {}

  /** 追加一个阶段并返回新列表；非首阶段时校验链式条件。 */
  public static List<CombatStage> append(List<CombatStage> existing, CombatStage next) {
    Objects.requireNonNull(existing, "existing");
    Objects.requireNonNull(next, "next");
    if (!existing.isEmpty()) {
      CombatStage last = existing.get(existing.size() - 1);
      if (!last.exit().equals(next.entry())) {
        throw new IllegalArgumentException(
            "AddCombatStage：新阶段 "
                + next.id()
                + " 的 entry 与上一阶段 "
                + last.id()
                + " 的 exit 不相等（N1 链式条件）");
      }
    }
    List<CombatStage> out = new ArrayList<>(existing);
    out.add(next);
    return List.copyOf(out);
  }

  /** 与 {@link #append} 同款校验，但作用于整份阶段表（供替换/整体校验用）。 */
  public static void requireChain(List<CombatStage> stages) {
    Objects.requireNonNull(stages, "stages");
    for (int i = 0; i + 1 < stages.size(); i++) {
      if (!stages.get(i).exit().equals(stages.get(i + 1).entry())) {
        throw new IllegalArgumentException(
            "AddCombatStage：阶段 "
                + stages.get(i).id()
                + " 的 exit 与 "
                + stages.get(i + 1).id()
                + " 的 entry 不相等（N1 链式条件）");
      }
    }
  }
}
