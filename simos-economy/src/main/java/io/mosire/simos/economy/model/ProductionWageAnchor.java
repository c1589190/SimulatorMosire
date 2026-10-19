package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★★ <b>U20 工资锚</b>（D-032 P4 §6.1；{@code EconomyData} 第 36 个组件）。
 *
 * <p>锚按 {@link WageAnchorKey} = {@code (marketZone, industry, role)} 持久化；同一市场区内跨 unit / 雇主共享。
 * 它是显式命令 / seed 写入的<b>唯一</b>锚状态，不另落第二份工资报价账。
 *
 * <p>★ <b>量纲</b>：{@code wagePerLaborMilli} = 毫货币 / 毫小时劳动；{@code wage = wagePerLaborMilli ×
 * laborMilli / 1000}。{@code wagePerLaborMilli = 0} 合法（U18/U19：零薪不是缺锚）；缺锚由 {@code Optional.empty()}
 * / {@code WageAnchorBook#require} 具名区分，不用 0 冒充。
 *
 * <p>★ <b>历史字段</b>：{@code shortageStreak}/{@code satisfiedStreak}/{@code lastObserved*} 均 ≥ 0；
 * {@code lastAdjustedTick = -1} 表示从未改价（构造期显式允许 -1），{@code lastAdjustmentDelta} 可负。 这些字段只服务 P4c 的
 * U20 观测/调价；P4b1 只负责持久与显式写入。
 *
 * <p>★ 本类型只做本域构造期校验；{@code currency == 市场区 legalTender} 属跨表约束，由 {@code
 * EconomyDataProductionInvariants} / {@code HouseholdReferenceValidation} / 命令与 seed 边界判。
 *
 * @param currency 锚的币种；不得为 null
 * @param wagePerLaborMilli 单位劳动工资（毫货币/毫小时；≥ 0，0 合法）
 * @param shortageStreak 连续缺工 tick 计数（≥ 0）
 * @param satisfiedStreak 连续满足 tick 计数（≥ 0）
 * @param lastAdjustedTick 最近一次实际改价的世界日（≥ -1；-1 = 从未改价）
 * @param lastObservedMatchedLaborMilli 上一 tick 末观测到的匹配劳动（≥ 0）
 * @param lastObservedTargetLaborMilli 上一 tick 末观测到的目标劳动（≥ 0）
 * @param lastAdjustmentDelta 最近一次改价的带符号差（可负）
 */
public record ProductionWageAnchor(
    CurrencyId currency,
    long wagePerLaborMilli,
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedTick,
    long lastObservedMatchedLaborMilli,
    long lastObservedTargetLaborMilli,
    long lastAdjustmentDelta) {

  public ProductionWageAnchor {
    Objects.requireNonNull(currency, "ProductionWageAnchor.currency 不得为 null");
    if (wagePerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionWageAnchor.wagePerLaborMilli 不得为负（0 是合法工资）: " + wagePerLaborMilli);
    }
    if (shortageStreak < 0L || satisfiedStreak < 0L) {
      throw new IllegalArgumentException(
          "ProductionWageAnchor 的 streak 不得为负: shortage="
              + shortageStreak
              + " satisfied="
              + satisfiedStreak);
    }
    if (lastAdjustedTick < -1L) {
      throw new IllegalArgumentException(
          "ProductionWageAnchor.lastAdjustedTick 必须是 -1（从未）或 ≥ 0: " + lastAdjustedTick);
    }
    if (lastObservedMatchedLaborMilli < 0L || lastObservedTargetLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionWageAnchor 的 lastObserved* 不得为负: matched="
              + lastObservedMatchedLaborMilli
              + " target="
              + lastObservedTargetLaborMilli);
    }
  }
}
