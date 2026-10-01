package io.mosire.simos.army;

import io.mosire.simos.unit.UnitId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 交战阶段概率表里的**一个结局**（阶段 D4 / 用户设计 D-009 补裁 + D-010，2026-10-02）：{@code id + label + weight + losses}。
 *
 * <p>★★ <b>权重与判定口径</b>：{@code weight > 0}（在构造期判，命令层不重复）；投骰按权重占比在 {@link CombatResolution}
 * 一处实现。{@code label} 是给人看的自然语言（"胜"/"惨胜"/"城破"），{@code id} 才是稳定身份；判定结果在阶段里记的是 {@code id}。
 *
 * <p>★ <b>损失</b>：{@code losses} 是"每单位的人力/装备有符号增量"（{@link CombatUnitLoss} 列表），缺省空表 = 这个结局不改任何单位。同一
 * outcome 里**不得重复 unit**（重复会让结算产生两条同单位命令，合并语义没有裁决，故构造期具名拒）。
 *
 * <p>★ <b>保序不可变</b>：{@code losses} 一律保序冻结（不用 Map.copyOf/Set.copyOf 这类不承诺顺序的容器）。
 */
public record CombatOutcome(
    CombatOutcomeId id, String label, long weight, List<CombatUnitLoss> losses) {

  public CombatOutcome {
    Objects.requireNonNull(id, "id");
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("label 不得为空白（结局必须有人读得懂的名字）");
    }
    if (weight <= 0L) {
      throw new IllegalArgumentException("weight 必须 > 0: " + weight);
    }
    if (losses == null) {
      throw new IllegalArgumentException("losses 不得为 null（空表合法）");
    }
    Set<UnitId> seen = new LinkedHashSet<>();
    for (CombatUnitLoss loss : losses) {
      if (loss == null) {
        throw new IllegalArgumentException("losses 的元素不得为 null");
      }
      if (!seen.add(loss.unit())) {
        throw new IllegalArgumentException("同一 outcome 的 losses 不得重复 unit: " + loss.unit().value());
      }
    }
    losses = List.copyOf(losses); // ★ 冻在赋值处（保序；逐项 null 已查过）
  }

  /** 本结局会不会动单位（两条表都为空的条目不算）。 */
  public boolean changesUnits() {
    for (CombatUnitLoss loss : losses) {
      if (!loss.empty()) {
        return true;
      }
    }
    return false;
  }
}
