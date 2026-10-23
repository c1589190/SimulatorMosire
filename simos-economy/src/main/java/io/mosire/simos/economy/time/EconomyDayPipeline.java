package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** 日序门面的执行壳：只按固定阶段顺序调用各阶段，不承载公式。 */
final class EconomyDayPipeline {

  private static final String ORDER_MARKET = "S3>S4>S5>S6>S7-DEBT>S7-MARKET";
  private static final String ORDER_CLOSE = "S3>S4>S5>S6>CLOSE";

  private EconomyDayPipeline() {}

  static void settle(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax,
      ProcurementPriorityInput procurementPriority,
      SurvivalSocialFacts survivalSocialFacts) {
    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            day,
            accounts,
            topology,
            plantingDrawsFirst,
            ledger,
            parallelism,
            profitCycle,
            composition,
            marketExcludedHouseholds,
            portEnforcement,
            portTax,
            procurementPriority,
            survivalSocialFacts);
    SettlementDayEntryPipeline.initialize(ctx);
    SettlementDayEntryPipeline.applyEntry(ctx);
    SettlementDayEntryPipeline.applyTransitionsAndOrganize(ctx);
    // 非市场日不会执行 S7-A；政府授权生命周期仍需要一张空成交表（不得用 null 当“没有成交”）。
    ctx.govMandateFills = Map.of();
    // ★★★ RC0a 冻结日序：S3 预期位置 → S4 消费/实际编排/劳动/投入/生产 → S5 收获 → S6 工资
    //   → S7-M2 债务清算（市场日）→ S7-A 统一市场（市场日）→ S7-post 债务估值/生命周期 → S8 收尾。
    //   市场日判据仍是唯一一处（MarketTriggerBook.triggerFor）；非市场日 S3–S6 照常、S7-A 与 S7-M2 不执行。
    boolean marketDay = MarketTriggerBook.triggerFor(ctx.day, ctx.markets) != MarketTrigger.NONE;
    // S2b 基础装配：家户/actor 反查、到货、套利收集器、匹配快照、cycleDays。
    SettlementDayProductionPipeline.prepareProductionBase(ctx);
    // ★★★ D-032 L1c：S3 之前从当前 lot 视图派生本 tick 的只读机会簿；本步不消费它，不改 S3 的任何输入/输出。
    buildProductionOpportunityBook(ctx);
    // S3：预期生产位置选择（只写瞬态 ExpectedProductionPlan + EmploymentLock + MarketIntent）。
    SettlementDayExpectedProductionPipeline.planExpectedProduction(ctx);
    // ★★★ P3c3/P3e legacy 回退：只有生产 roster 真正为空（旧生产形态）才走旧链。
    //   判据不得是 S3 的 byUnit 是否为空：有 roster 但因缺锚/缺地/负收益而没有入选 unit 是**新链合法结果**
    //   （无生产/无业），不得回退旧链、不得让旧生产/市场链掩盖新链问题。
    boolean legacyFallback = ctx.session.sheet().productionRostersOrBase().isEmpty();
    if (legacyFallback) {
      ctx.expectedProductionPlan = null;
      if (EconomyLog.settlement().isInfoEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "D032_P3_LEGACY_FALLBACK",
                    EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                    "day",
                    ctx.day,
                    "reason",
                    "no-production-roster",
                    "marketDay",
                    marketDay,
                    "path",
                    "legacy:consume+production+wages>debt>market"));
      }
      // prepareProductionBase 已跑过，不重跑；legacy 消费 → 生产 → 收获 → 工资，再债务/市场。
      SettlementDayProductionPipeline.legacyConsumeAfterBase(ctx);
      SettlementDayProductionPipeline.driveProduction(ctx);
      SettlementDayProductionPipeline.closeAndHarvest(ctx);
      SettlementDayWagePipeline.settleWageAndCapitalize(ctx, marketDay);
      if (marketDay) {
        // S7-M2 必须在 S6 工资之后、市场撮合之前；同日工资债由 DebtFirstSettlement 按 id 跳过。
        SettlementDayDebtPipeline.settleDebtsAtMarketOpen(ctx);
        SettlementDayMarketPipeline.settleMarket(ctx);
      }
      SettlementDayDebtPipeline.settleDebt(ctx);
      SettlementDayClosePipeline.reportGapsAndInterest(ctx);
      SettlementDayClosePipeline.settleOperatorState(ctx);
      SettlementDayClosePipeline.writeFlowsAndFinish(ctx);
      logDayOrder(ctx, marketDay, "legacy");
      SettlementTickChainLog.logTickChain(ctx, marketDay);
      return;
    }
    // S4：消费/实际编排/实际劳动/实际投入/生产执行（内部次序保持既有调用序，不重排公式）。
    //   市场买到的东西可用；新买土地不可用。市场轮已移到 S6 之后，本 tick 不再向生产/消费供货。
    SettlementDayProductionPipeline.settleConsumption(ctx);
    SettlementDayActualProductionPipeline.planActualProduction(ctx);
    SettlementDayProductionPipeline.planActualLabor(ctx);
    SettlementDayProductionPipeline.drawActualInputs(ctx);
    // ★★★ RC8c：S4 末尾生成每户每 tick 唯一 DailyDemandBill（S4 已提供的消费/投入写 providedFromStock）。
    SettlementDayProductionPipeline.buildDailyDemandBill(ctx);
    SettlementDayProductionPipeline.driveProduction(ctx);
    // S5：周期末收获/毛产分配。
    SettlementDayProductionPipeline.closeAndHarvest(ctx);
    // S6：工资结算（只付现金；本 tick 新建工资债 id 写入 ctx.sameDayWageDebtIds）。
    SettlementDayWagePipeline.settleWageAndCapitalize(ctx, marketDay);
    if (marketDay) {
      // S7-M2：债务本金清算在 S6 之后、市场撮合之前。
      SettlementDayDebtPipeline.settleDebtsAtMarketOpen(ctx);
      // S7-A：统一市场只执行本 tick 的 S4 DailyDemandBill 派生意向（商品 + LAND），
      //   不再使用 S3 expectedProductionPlan.marketIntents()；每次市场日只调用一次。
      MarketSettlement.MarketRound preparedRound =
          SettlementDayMarketPipeline.prepareMarketRound(
              ctx, MarketIntentBook.dailyDemandBillMarketIntents(ctx));
      SettlementDayMarketPipeline.executeMarketRound(ctx, preparedRound);
    }
    // 市场后处理 / 政府授权生命周期 / 债务估值 / 利息与关账（位置仍在 S7-A 之后）。
    SettlementDayDebtPipeline.settleDebt(ctx);
    SettlementDayClosePipeline.reportGapsAndInterest(ctx);
    SettlementDayClosePipeline.settleOperatorState(ctx);
    SettlementDayClosePipeline.writeFlowsAndFinish(ctx);
    logDayOrder(ctx, marketDay, "main");
    // ★ P3e：每 tick 一条全链路 INFO 汇总（legacy 分支末尾同样有一条）。
    SettlementTickChainLog.logTickChain(ctx, marketDay);
  }

  /**
   * ★★★ <b>D-032 L1c：S3 之前的只读机会簿装配</b>。
   *
   * <p>★ 只从本 tick 的 {@code InventoryLotView} + 产业定义 + 市场价纯派生；结果只写入 {@link
   * SettlementDayContext#productionOpportunityBook()}，<b>不</b>传给 {@code planExpectedProduction} 或任何
   * S3/S4/S5 阶段（L1c 暂不消费）。空视图或空产业 ⇒ 空簿、不抛（旧夹具逐值不变）。
   */
  private static void buildProductionOpportunityBook(SettlementDayContext ctx) {
    InventoryLotView view = ctx.session.inventoryLotView();
    int lotCount = view.lots().size();
    ProductionOpportunityBook book;
    if (view.lots().isEmpty() || ctx.industries == null || ctx.industries.isEmpty()) {
      book = new ProductionOpportunityBook(List.of());
    } else {
      book =
          ProductionOpportunityBook.from(
              view, ctx.industries, commodity -> firstPricedMarketPrice(ctx, commodity));
    }
    ctx.productionOpportunityBook = book;
    if (EconomyLog.settlement().isInfoEnabled()) {
      try {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "PRODUCTION_OPPORTUNITY_BOOK_BUILT",
                    EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                    "day",
                    ctx.day,
                    "lots",
                    lotCount,
                    "opportunities",
                    book.opportunities().size()));
      } catch (RuntimeException ignored) {
        // ★ 日志失败不影响结算（与 logDayOrder 同制）。
      }
    }
  }

  /**
   * ★★★ <b>D-032 L1c：稳定序第一个对商品定价的市场价</b>。
   *
   * <p>市场按 {@link MarketZoneId#value()} 升序取第一个 {@link MarketTable#hasPrice(CommodityId)} 为 true
   * 的表；缺价 ⇒ {@link OptionalLong#empty()}（不伪造价格，0 价也是明确定价）。
   */
  private static OptionalLong firstPricedMarketPrice(
      SettlementDayContext ctx, CommodityId commodity) {
    if (commodity == null || ctx.markets == null || ctx.markets.isEmpty()) {
      return OptionalLong.empty();
    }
    List<MarketZoneId> zones = new ArrayList<>(ctx.markets.keySet());
    zones.sort(Comparator.comparing(MarketZoneId::value));
    for (MarketZoneId zone : zones) {
      MarketTable market = ctx.markets.get(zone);
      if (market != null && market.hasPrice(commodity)) {
        return OptionalLong.of(market.priceOf(commodity));
      }
    }
    return OptionalLong.empty();
  }

  /**
   * ★★★ RC0a：每 tick 一条 INFO 汇总（day / marketDay / path / order / 同日工资债计数）。
   *
   * <p>只读日志：不写状态、不改公式；同日工资债集合为空或未初始化为 null 时按 0 记录。
   */
  private static void logDayOrder(SettlementDayContext ctx, boolean marketDay, String path) {
    if (!EconomyLog.settlement().isInfoEnabled()) {
      return;
    }
    int sameDayWageDebts = ctx.sameDayWageDebtIds == null ? 0 : ctx.sameDayWageDebtIds.size();
    try {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "D032_DAY_ORDER",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "marketDay",
                  marketDay,
                  "path",
                  path,
                  "order",
                  marketDay ? ORDER_MARKET : ORDER_CLOSE,
                  "sameDayWageDebtIds",
                  sameDayWageDebts));
    } catch (RuntimeException ignored) {
      // ★ 日志失败不影响结算。
    }
  }
}
