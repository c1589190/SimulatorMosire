package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>执行期工资岗位</b>（D-032 D3 §2.2；{@code EconomyData} 第 34 个组件）。
 *
 * <p>★★ <b>唯一执行权威（A3）</b>：已存在实例的匹配/结算只读本表；候选 default 只服务实例存在前的估价与物化。 同一 {@code (unit, role)} 至多一条；键
 * == 值内身份。
 *
 * <p>★ 量纲：{@code targetLaborMilliPerDay} = 毫小时/日；{@code wagePerLaborMilli} = 毫货币/小时； {@code wage =
 * wagePerLaborMilli × laborMilli / 1000}。{@code wagePerLaborMilli = 0} 合法，缺报价用 {@code
 * Optional.empty()}，不得用 0 冒充。
 *
 * <p>★ 本类型只做本域构造期校验；{@code currency == unit 所在市场区 legalTender} 与角色存在性/支付类型属跨切片， 由 {@code
 * EconomyReferenceValidation}/命令层判。
 *
 * @param unit 生产实例稳定身份；键必须与值内一致
 * @param role 方案内工资角色；键必须与值内一致；跨切片须为 {@code MONEY_WAGE}
 * @param targetLaborMilliPerDay 日目标劳动（≥ 0；0 = 显式关闭岗位，仍不得把缺失当 0）
 * @param currency 报价币种；必须等于 unit 所在市场区法定币（跨切片校验）
 * @param wagePerLaborMilli 每 1000 毫小时劳动的毫货币（≥ 0，0 合法）
 * @param quoteSource 报价来源；不得为 null
 * @param shortageStreak 连续缺工 tick 计数（≥ 0）
 * @param satisfiedStreak 连续满足 tick 计数（≥ 0）
 * @param lastAdjustedTick 最近一次实际改价的世界日（≥ 0；无改价可为 0）
 * @param lastAdjustmentDelta 最近一次改价的带符号差（毫货币/小时；无改价 0）
 * @param lastObservedMatchedLabor 上一 tick 末观测到的匹配劳动（≥ 0）
 * @param lastObservedEffectiveTarget 上一 tick 末观测到的有效目标（≥ 0）
 * @param lastMaxAbsorbable 上一 tick 末观测到的最大可吸收劳动（≥ 0）
 */
public record ProductionWagePosting(
    ProductionUnitId unit,
    ProductionRoleId role,
    long targetLaborMilliPerDay,
    CurrencyId currency,
    long wagePerLaborMilli,
    WageQuoteSource quoteSource,
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedTick,
    long lastAdjustmentDelta,
    long lastObservedMatchedLabor,
    long lastObservedEffectiveTarget,
    long lastMaxAbsorbable) {

  public ProductionWagePosting {
    Objects.requireNonNull(unit, "ProductionWagePosting.unit 不得为 null");
    Objects.requireNonNull(role, "ProductionWagePosting.role 不得为 null");
    Objects.requireNonNull(currency, "ProductionWagePosting.currency 不得为 null");
    Objects.requireNonNull(quoteSource, "ProductionWagePosting.quoteSource 不得为 null");
    if (targetLaborMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "ProductionWagePosting.targetLaborMilliPerDay 不得为负（0 = 显式关闭岗位）: "
              + targetLaborMilliPerDay);
    }
    if (wagePerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionWagePosting.wagePerLaborMilli 不得为负（0 是合法报价）: " + wagePerLaborMilli);
    }
    if (shortageStreak < 0L
        || satisfiedStreak < 0L
        || lastAdjustedTick < 0L
        || lastObservedMatchedLabor < 0L
        || lastObservedEffectiveTarget < 0L
        || lastMaxAbsorbable < 0L) {
      throw new IllegalArgumentException(
          "ProductionWagePosting 的 streak/观测值/改价日必须 ≥ 0: shortage="
              + shortageStreak
              + " satisfied="
              + satisfiedStreak
              + " lastAdjustedTick="
              + lastAdjustedTick
              + " observedMatched="
              + lastObservedMatchedLabor
              + " observedTarget="
              + lastObservedEffectiveTarget
              + " maxAbsorbable="
              + lastMaxAbsorbable);
    }
  }

  /** 本值对应的稳定复合键（键 == 值内二元组的唯一拼写点）。 */
  public WagePostingKey key() {
    return new WagePostingKey(unit, role);
  }
}
