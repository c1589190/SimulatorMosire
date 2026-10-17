package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>候选级工资默认</b>（D-032 D3 §2.2.1；{@code EconomyData} 第 33 个组件）。
 *
 * <p>它只服务生产实例存在前的候选估价与选中物化：{@code quote = initialWagePerLaborMilli}、 {@code target =
 * initialTargetLaborMilliPerDay}。已存在实例的匹配/结算只读 {@link ProductionWagePosting}，本类型<b>不是</b>第二份执行报价。
 *
 * <p>★ 币种不在这里：由 {@code marketZone.legalTender} 派生。★ {@code initialWagePerLaborMilli = 0} 合法（U18）；
 * {@code initialTargetLaborMilliPerDay > 0}，缺目标不得静默当 0。
 *
 * @param plan 方案稳定身份；键必须与值内一致
 * @param role 方案内工资角色；键必须与值内一致；跨切片须为 {@code MONEY_WAGE}
 * @param marketZone 市场区；键必须与值内一致，且币种由该区法定币派生
 * @param initialWagePerLaborMilli 初始报价（毫货币/小时；≥ 0，0 合法）
 * @param initialTargetLaborMilliPerDay 初始日目标劳动（毫小时/日；&gt; 0）
 */
public record ProductionWageDefault(
    IndustryId plan,
    ProductionRoleId role,
    MarketZoneId marketZone,
    long initialWagePerLaborMilli,
    long initialTargetLaborMilliPerDay) {

  public ProductionWageDefault {
    Objects.requireNonNull(plan, "ProductionWageDefault.plan 不得为 null");
    Objects.requireNonNull(role, "ProductionWageDefault.role 不得为 null");
    Objects.requireNonNull(marketZone, "ProductionWageDefault.marketZone 不得为 null");
    if (initialWagePerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionWageDefault.initialWagePerLaborMilli 不得为负（0 是合法报价）: "
              + initialWagePerLaborMilli);
    }
    if (initialTargetLaborMilliPerDay <= 0L) {
      throw new IllegalArgumentException(
          "ProductionWageDefault.initialTargetLaborMilliPerDay 必须 > 0: "
              + initialTargetLaborMilliPerDay);
    }
  }

  /** 本值对应的稳定复合键（键 == 值内三元组的唯一拼写点）。 */
  public WageDefaultKey key() {
    return new WageDefaultKey(plan, role, marketZone);
  }
}
