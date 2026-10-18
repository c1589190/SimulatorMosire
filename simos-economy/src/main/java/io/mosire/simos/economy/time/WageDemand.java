package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.WageAnchorKey;
import java.util.Comparator;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P4c1：M3 入选工资位置的招工需求（WageDemand）</b>。
 *
 * <p>它是 M3 第一遍编排的<b>瞬态</b>产物：每条记录说"这个 unit 的这个工资角色本 tick 想要多少劳动、按哪个市场区的
 * 法定币计价"。它只活在 {@link ExpectedProductionPlan} 里，<b>不落</b> {@code EconomyData}/ChangeSet/Codec，也不写任何账。
 *
 * <p>★★ <b>target 的唯一口径</b>（P4c1 冻结）：
 *
 * <pre>
 * target = PositionPlan.hiredLaborMilliPerDay &gt; 0 ? hiredLaborMilliPerDay
 *        : floor(role.standardLaborMilliPerCycle / max(1, cycleDays))   // 工程默认
 * </pre>
 *
 * <p>★ <b>target = 0 不生成 WageDemand</b>（没有招工需求 ≠ 需求为 0 的记录）；{@code targetLaborMilliPerDay} 构造期非负。
 * ★ <b>同 {@code (unit, role)} 至多一条</b>：M3 逐 unit/role 去重，不做跨位置累加（跨 unit 的锚级聚合是 P4c2 的事）。
 *
 * <p>★ {@link #anchorKey()} 是 {@link WageAnchorKey} 的唯一派生点：{@code (marketZone, industry, role)}。同一锚跨 unit /
 * 雇主共享，故多个 unit 可以带同一个键。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param marketZone unit 所在市场区（锚的分区维度）；不得为 null
 * @param industry 产业/方案稳定身份；不得为 null
 * @param role 方案内工资角色；不得为 null
 * @param targetLaborMilliPerDay 日目标劳动（毫小时/日）；≥ 0（= 0 的记录不应被构造）
 * @param currency 计价币种（锚/法定币）；不得为 null
 */
record WageDemand(
    ProductionUnitId unit,
    MarketZoneId marketZone,
    IndustryId industry,
    ProductionRoleId role,
    long targetLaborMilliPerDay,
    CurrencyId currency) {

  WageDemand {
    Objects.requireNonNull(unit, "WageDemand.unit 不得为 null");
    Objects.requireNonNull(marketZone, "WageDemand.marketZone 不得为 null");
    Objects.requireNonNull(industry, "WageDemand.industry 不得为 null");
    Objects.requireNonNull(role, "WageDemand.role 不得为 null");
    Objects.requireNonNull(currency, "WageDemand.currency 不得为 null");
    if (targetLaborMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "WageDemand.targetLaborMilliPerDay 不得为负: " + targetLaborMilliPerDay);
    }
  }

  /** ★ 本需求指向的 U20 工资锚键（唯一派生点：市场区 + 产业 + 角色）。 */
  WageAnchorKey anchorKey() {
    return new WageAnchorKey(marketZone, industry, role);
  }

  /** 规范序：{@code (unit, marketZone, industry, role)} 升序（迭代序 = 内容的纯函数）。 */
  static final Comparator<WageDemand> CANONICAL_ORDER =
      Comparator.comparing((WageDemand demand) -> demand.unit().value())
          .thenComparing(demand -> demand.marketZone().value())
          .thenComparing(demand -> demand.industry().value())
          .thenComparing(demand -> demand.role().value());
}
