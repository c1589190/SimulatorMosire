package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>家户阶层归属</b>（理想架构 §2.3/§2.9）：持久化一个家户的当前阶层位置、原所属位置与保留份额。它是 E1 建立的"家户 → 阶层"权威状态； 旧 {@code
 * ClassRow.view.stratum} 在 E1 仍是旧结算路径的权威，<b>本状态不接线结算、不改旧路径</b>。
 *
 * <p>★★ <b>为什么保留 {@code originalPositionId} 与 {@code
 * retainedShares}</b>：模式变迁时，一个家户可能只把一部分成员/权利迁入新位置，其余保留在原所属； 单一 {@code currentPositionId}
 * 表达不了这种混合归属（设计稿 §2.9）。{@code retainedShares} 以位置 id 为键、千分比为值，显式记下"保留了多少"。
 *
 * <p>★ <b>E1 只做形状与守恒边界</b>：不解释迁移比例、不把份额乘进人口/资产，也不改 {@code HouseholdClassRule} 的分类。份额的 {@code ≥ 0}
 * 与键身份是状态合法性判据；"份额合计是否等于 1000"等规则留到 E6 模式变迁裁决。
 *
 * <p>★★ <b>E5a 追加 {@code consecutiveDebtStressCycles}</b>：连续债务压力周期数（≥ 0）。★ <b>旧 JSON 缺这个键 ⇒
 * 0</b>（Jackson 对 record 的缺失原始 {@code long} 取类型默认值，与 {@code ClassRow.cycleNaturalNeedMilli} 同款约定），
 * 故本字段不需要另写迁移层；E5a 只落字段与构造期 ≥ 0 守卫，递增/清零在 E5b。
 *
 * @param householdId 家户稳定身份；不得为 null（键 = 值内 householdId）
 * @param originalPositionId 原所属阶层位置；不得为 null（创世迁移时通常等于当前位置）
 * @param currentPositionId 当前阶层位置；不得为 null
 * @param retainedShares 保留份额（位置 → 千分比）；不得为 null、键值不得为 null、逐值 ≥ 0，保序不可变
 * @param consecutiveDebtStressCycles 连续债务压力周期数（≥ 0）；E5a 只落字段，递增/清零在 E5b
 * @param lastTransitionDay 最近一次阶层变更日；不得为负
 * @param reason 最近一次变更原因（具名文本；可为空串 = 尚未发生变更）；不得为 null
 */
public record ClassStanding(
    HouseholdId householdId,
    ClassPositionId originalPositionId,
    ClassPositionId currentPositionId,
    Map<ClassPositionId, Long> retainedShares,
    long consecutiveDebtStressCycles,
    long lastTransitionDay,
    String reason) {

  public ClassStanding {
    if (householdId == null) {
      throw new IllegalArgumentException("ClassStanding.householdId 不得为 null");
    }
    if (originalPositionId == null) {
      throw new IllegalArgumentException("ClassStanding.originalPositionId 不得为 null");
    }
    if (currentPositionId == null) {
      throw new IllegalArgumentException("ClassStanding.currentPositionId 不得为 null");
    }
    if (retainedShares == null) {
      throw new IllegalArgumentException("ClassStanding.retainedShares 不得为 null（没有保留给空表）");
    }
    if (consecutiveDebtStressCycles < 0L) {
      throw new IllegalArgumentException(
          "ClassStanding.consecutiveDebtStressCycles 不得为负: " + consecutiveDebtStressCycles);
    }
    if (lastTransitionDay < 0L) {
      throw new IllegalArgumentException(
          "ClassStanding.lastTransitionDay 不得为负: " + lastTransitionDay);
    }
    if (reason == null) {
      throw new IllegalArgumentException("ClassStanding.reason 不得为 null（没有就给空串）");
    }
    Map<ClassPositionId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, Long> entry : retainedShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ClassStanding.retainedShares 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ClassStanding.retainedShares 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    retainedShares = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 只认它看得见的包装）
  }
}
