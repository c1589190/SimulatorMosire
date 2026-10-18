package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★★ <b>D-032 P3e：工资报价/招工目标的唯一职责类</b>（自 {@link ProductionPlanBridge} 机械抽取）。
 *
 * <p>本类只回答三类只读问题：① 某格属于哪个市场区、工资锚键是什么；② 本 tick 的工资报价（锚唯一权威，缺锚不按 0）；
 * ③ 某 {@code (unit, role)} 的招工目标（WageDemand → 实际计划 → 工程默认）。它不写任何锚、不支付工资、不读旧工资表。
 */
final class WageQuoteBridge {

  private static final Logger PRODUCTION = EconomyLog.settlement();

  /**
   * ★★★ <b>D-032 P4c3：U20 工资锚日志的两个具名事件</b>（唯一拼写点）。
   *
   * <ul>
   *   <li>{@link #ANCHOR_USED}：本 tick 该 {@code (市场区, 产业, 角色)} 的锚存在，报价/锁定/结算都取锚；
   *   <li>{@link #ANCHOR_MISSING}：锚缺 ⇒ 具名 {@code wage-anchor-missing}，<b>不得</b>用 0 或旧表兜底。
   * </ul>
   */
  static final String ANCHOR_USED = "D032_P4_ANCHOR_USED";

  static final String ANCHOR_MISSING = "D032_P4_ANCHOR_MISSING";

  /** 缺锚的稳定拒绝词（M3 位置不可行 / M8 义务拒绝同一拼写）。 */
  static final String WAGE_ANCHOR_MISSING = "wage-anchor-missing";

  /**
   * ★★ <b>一次工资报价解析的结论</b>：
   *
   * @param anchorKey 派生出的锚键（unit 地点无法定出市场区时为空）
   * @param quote 解析出的报价（空 = 锚缺失，调用方必须具名拒绝）
   * @param source {@code anchor} / {@code missing}（只服务日志与账本）
   */
  record WageQuoteResolution(
      Optional<WageAnchorKey> anchorKey, Optional<ProductionWageQuote> quote, String source) {
    WageQuoteResolution {
      Objects.requireNonNull(anchorKey, "WageQuoteResolution.anchorKey");
      Objects.requireNonNull(quote, "WageQuoteResolution.quote");
      Objects.requireNonNull(source, "WageQuoteResolution.source");
    }
  }

  /**
   * ★★ <b>这一格属于哪个市场区</b>：先按持久区成员格（{@link MarketZoneBook#zoneOfHex}），再按派生价表
   * （{@link MarketTables#marketIdAt}）。两处都不命中 ⇒ 空（"这格没有市场"是合法状态，不猜）。
   */
  static Optional<MarketZoneId> marketZoneOf(EconomyDayView view, ProductionLocation location) {
    io.mosire.simos.map.hex.HexCoord hex = ProductionPlanBridge.hexOf(location);
    if (hex == null) {
      return Optional.empty();
    }
    Optional<io.mosire.simos.economy.api.market.MarketZone> zone =
        MarketZoneBook.zoneOfHex(view, hex);
    if (zone.isPresent()) {
      return Optional.of(zone.get().zoneId());
    }
    return MarketTables.marketIdAt(view, hex);
  }

  /** ★★ <b>U20 锚键的唯一派生点</b>：{@code (unit 所在市场区, unit.industry, role)}；定不出市场区 ⇒ 空。 */
  static Optional<WageAnchorKey> wageAnchorKeyOf(
      EconomyDayView view, ProductionProcess unit, ProductionRoleId role) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    return marketZoneOf(view, unit.location())
        .map(zone -> new WageAnchorKey(zone, unit.industry(), role));
  }

  /**
   * ★★★ <b>P4c3 工资报价解析：锚是唯一权威</b>。
   *
   * <pre>
   * key = (unit 所在市场区, unit.industry, role)
   * 锚存在 ⇒ quote = (anchor.currency, anchor.wagePerLaborMilli)；日志 ANCHOR_USED
   * 锚缺   ⇒ quote = empty；日志 ANCHOR_MISSING（ERROR 或 INFO，由调用方语义给定）
   * </pre>
   *
   * <p>★ <b>缺锚绝不当 0、不读旧表</b>：解析不到报价时返回 {@code Optional.empty()}，调用方必须拒绝（M3/M6 位置不可行 /
   * M8 义务拒绝）。★ <b>同一 tick 快照</b>：{@code anchors} 必须来自本 tick 的当刻视图（{@code
   * dayView.productionWageAnchors()}），调用方不得在 M8 内改锚。
   *
   * @param view 当刻只读视图（市场区 + 锚快照来源）
   * @param day 世界日（日志上下文）
   * @param stage 调用阶段名（{@code m3-m6-position-economics} / {@code m6-labor-queue} / {@code m8-wage-due} …）
   * @param anchors 本 tick 的锚快照（缺锚 = 不在表里）
   * @param missingIsContractFault 缺锚是否按契约故障记 ERROR（M8 = true；M3/M6 业务拒绝 = false）
   */
  static WageQuoteResolution resolveWageQuote(
      EconomyDayView view,
      long day,
      String stage,
      ProductionProcess unit,
      ProductionRoleId role,
      Map<WageAnchorKey, ProductionWageAnchor> anchors,
      boolean missingIsContractFault) {
    Objects.requireNonNull(view, "view");
    return resolveWageQuote(
        day,
        stage,
        unit,
        role,
        wageAnchorKeyOf(view, unit, role),
        anchors,
        missingIsContractFault);
  }

  /**
   * ★★ 同上，但锚键由调用方给定（M8 的义务在收集阶段已派生并随身携带，避免第二处派生）。
   *
   * @param anchorKey 已派生的锚键；{@code Optional.empty()} = 定不出市场区（缺锚，不猜区）
   */
  static WageQuoteResolution resolveWageQuote(
      long day,
      String stage,
      ProductionProcess unit,
      ProductionRoleId role,
      Optional<WageAnchorKey> anchorKey,
      Map<WageAnchorKey, ProductionWageAnchor> anchors,
      boolean missingIsContractFault) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(anchorKey, "anchorKey（缺键用 Optional.empty()）");
    Objects.requireNonNull(anchors, "anchors（无锚请用空表）");
    ProductionWageAnchor anchor = anchorKey.isPresent() ? anchors.get(anchorKey.get()) : null;
    if (anchor != null) {
      ProductionWageQuote quote =
          new ProductionWageQuote(anchor.currency(), anchor.wagePerLaborMilli());
      logAnchorEvent(
          ANCHOR_USED,
          false,
          day,
          stage,
          unit.id().value(),
          role.value(),
          anchorKey.get(),
          quote,
          "-");
      return new WageQuoteResolution(anchorKey, Optional.of(quote), "anchor");
    }
    logAnchorEvent(
        ANCHOR_MISSING,
        missingIsContractFault,
        day,
        stage,
        unit.id().value(),
        role.value(),
        anchorKey.orElse(null),
        null,
        anchorKey.isPresent() ? WAGE_ANCHOR_MISSING : "wage-anchor-key-unresolved");
    return new WageQuoteResolution(anchorKey, Optional.empty(), "missing");
  }

  /** 三个锚事件的唯一日志拼写点（{@code key}/{@code quote} 可缺 ⇒ 记 {@code -}）。 */
  private static void logAnchorEvent(
      String event,
      boolean error,
      long day,
      String stage,
      String unit,
      String role,
      WageAnchorKey key,
      ProductionWageQuote quote,
      String reason) {
    LogEvent logEvent =
        LogEvent.of(
            event,
            EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
            "day",
            day,
            "stage",
            stage,
            "unit",
            unit,
            "role",
            role,
            "anchorKey",
            key == null ? "-" : key.toString(),
            "wagePerLaborMilli",
            quote == null ? "-" : Long.toString(quote.wagePerLaborMilli()),
            "currency",
            quote == null ? "-" : quote.currency().value(),
            "reason",
            reason);
    if (error) {
      EventLog.channel(PRODUCTION).error(logEvent);
    } else if (ANCHOR_USED.equals(event)) {
      if (PRODUCTION.isDebugEnabled()) {
        EventLog.channel(PRODUCTION).debug(logEvent);
      }
    } else if (PRODUCTION.isInfoEnabled()) {
      EventLog.channel(PRODUCTION).info(logEvent);
    }
  }

  /**
   * ★★★ <b>WageDemand.target 的唯一公式</b>（P4c1 冻结）：
   *
   * <pre>
   * target = hiredLaborMilliPerDay &gt; 0 ? hiredLaborMilliPerDay
   *        : floor(role.standardLaborMilliPerCycle / max(1, cycleDays))   // 工程默认
   * </pre>
   *
   * <p>0 合法（= 没有招工需求 ⇒ 调用方不生成 WageDemand）；不读任何库存/市场价。
   */
  static long wageTargetLaborMilliPerDay(
      long hiredLaborMilliPerDay, ProductionRoleDefinition role, long cycleDays) {
    Objects.requireNonNull(role, "role");
    if (hiredLaborMilliPerDay > 0L) {
      return hiredLaborMilliPerDay;
    }
    long days = Math.max(1L, cycleDays);
    return role.standardLaborMilliPerCycle() / days;
  }

  /**
   * ★★ <b>本 unit 的 MONEY_WAGE 逐角色招工目标</b>：优先本 tick 的 {@link WageDemand}（按 role 聚合），其次同一 target
   * 公式的工程默认。不得回读旧工资表。
   *
   * <p>只返回正目标（0 = 无招工需求，不进 probe 的边界表）。
   */
  static Map<ProductionRoleId, Long> wageTargetsOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      List<WageDemand> demands) {
    Objects.requireNonNull(ctx, "ctx");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(industry, "industry");
    List<WageDemand> safeDemands = demands == null ? List.of() : demands;
    Map<ProductionRoleId, Long> targets = new LinkedHashMap<>();
    for (ProductionRoleDefinition role : industry.roles()) {
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      long target = wageTargetOf(ctx, unit, industry, role.id(), safeDemands);
      if (target > 0L) {
        targets.put(role.id(), target);
      }
    }
    return targets;
  }

  /** 单个 {@code (unit, role)} 的招工目标：WageDemand 聚合 → 工程默认（缺目标返回 0，不读旧表）。 */
  static long wageTargetOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionRoleId roleId,
      List<WageDemand> demands) {
    long demanded = 0L;
    if (demands != null) {
      for (WageDemand demand : demands) {
        if (demand.unit().equals(unit.id()) && demand.role().equals(roleId)) {
          demanded = Math.addExact(demanded, demand.targetLaborMilliPerDay());
        }
      }
    }
    if (demanded > 0L) {
      return demanded;
    }
    ProductionRoleDefinition role = ProductionPlanBridge.roleOf(industry, roleId).orElse(null);
    if (role != null) {
      long engineeringDefault = wageTargetLaborMilliPerDay(0L, role, industry.cycleDays());
      if (engineeringDefault > 0L) {
        return engineeringDefault;
      }
    }
    return 0L;
  }

  /**
   * ★★★ <b>D-032 P4c1：M3 入选位置 → WageDemand 清单</b>。
   *
   * <ul>
   *   <li>只对入选（{@code chosenFacts}）且角色为 {@code MONEY_WAGE} 的位置生成；
   *   <li>target 走 {@link #wageTargetLaborMilliPerDay}（hired &gt; 0 优先，否则标准劳动/周期天数的工程默认），
   *       <b>target = 0 不生成</b>；
   *   <li>同 {@code (unit, role)} 至多一条（不重复累加；跨 unit 的锚级聚合是 P4c2 的事）；
   *   <li>marketZone 定不出 / 币种定不出 ⇒ 具名 {@code D032_P4_ANCHOR_MISSING} 并跳过该需求（不伪造区/币）。
   * </ul>
   */
  static List<WageDemand> wageDemandsOf(
      SettlementDayContext ctx, List<ProductionPlanBridge.PositionFacts> chosenFacts) {
    Objects.requireNonNull(ctx, "ctx");
    Objects.requireNonNull(chosenFacts, "chosenFacts");
    List<ProductionPlanBridge.PositionFacts> ordered = new ArrayList<>(chosenFacts);
    ordered.sort(
        Comparator.comparing(
                (ProductionPlanBridge.PositionFacts fact) -> fact.position().unit().value())
            .thenComparing(fact -> fact.position().role().value()));
    Map<String, WageDemand> bySlot = new LinkedHashMap<>();
    for (ProductionPlanBridge.PositionFacts fact : ordered) {
      PositionPlan position = fact.position();
      IndustryDefinitionView industry = ctx.industries.get(position.plan());
      if (industry == null) {
        continue;
      }
      ProductionRoleDefinition role =
          ProductionPlanBridge.roleOf(industry, position.role()).orElse(null);
      if (role == null || !(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      ProductionProcess unit = ctx.units.get(position.unit());
      // ★ P3e：target 优先 roster 实际计划雇工（与 organizer 位置 hiredLaborMilliPerDay 同一事实源）；
      //   只有确实没有计划雇工时，wageTargetLaborMilliPerDay 才回退标准劳动/周期天数。
      Map<ProductionRoleId, Long> actualHiredByRole =
          unit == null ? Map.of() : ProductionPlanBridge.hiredWageLaborByRole(ctx, unit, industry);
      long target =
          wageTargetLaborMilliPerDay(
              actualHiredByRole.getOrDefault(role.id(), position.hiredLaborMilliPerDay()),
              role,
              industry.cycleDays());
      if (target <= 0L) {
        continue; // 没有招工需求 ⇒ 不生成 WageDemand（不记 0 需求）
      }
      String slot = position.unit().value() + "\u0000" + position.role().value();
      if (bySlot.containsKey(slot)) {
        continue; // 同 unit/role 不重复累加
      }
      if (unit == null) {
        logAnchorEvent(
            ANCHOR_MISSING,
            false,
            ctx.day,
            "m3-wage-demand",
            position.unit().value(),
            position.role().value(),
            null,
            null,
            "wage-demand-unit-missing");
        continue;
      }
      Optional<MarketZoneId> zone = marketZoneOf(ctx.dayView, unit.location());
      if (zone.isEmpty()) {
        logAnchorEvent(
            ANCHOR_MISSING,
            false,
            ctx.day,
            "m3-wage-demand",
            unit.id().value(),
            role.id().value(),
            null,
            null,
            "wage-demand-market-zone-missing");
        continue;
      }
      CurrencyId currency = demandCurrencyOf(ctx, zone.get());
      if (currency == null) {
        logAnchorEvent(
            ANCHOR_MISSING,
            false,
            ctx.day,
            "m3-wage-demand",
            unit.id().value(),
            role.id().value(),
            new WageAnchorKey(zone.get(), industry.id(), role.id()),
            null,
            "wage-demand-currency-missing");
        continue;
      }
      bySlot.put(
          slot,
          new WageDemand(
              unit.id(), zone.get(), industry.id(), role.id(), target, currency));
    }
    return List.copyOf(bySlot.values());
  }

  /** WageDemand 的币种：区法定币 → 该区价表计价币（派生市场形态）。两处都没有 ⇒ null（不猜）。 */
  private static CurrencyId demandCurrencyOf(SettlementDayContext ctx, MarketZoneId zone) {
    io.mosire.simos.economy.api.market.MarketZone marketZone =
        ctx.dayView.marketZones().get(zone);
    if (marketZone != null) {
      return marketZone.legalTender();
    }
    MarketTable table = ctx.dayView.marketTables().get(zone);
    return table == null ? null : table.numeraire();
  }
}
