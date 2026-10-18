package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Map;
import java.util.Set;

/** 日序门面的执行壳：只按原顺序调用各阶段，不承载公式。 */
final class EconomyDayPipeline {

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
    // 非市场日不会执行 M4；政府授权生命周期仍需要一张空成交表（不得用 null 当“没有成交”）。
    ctx.govMandateFills = Map.of();
    // ★★★ P3c3 冻结日序：M2 债 → M3 预期编排 → M4 市场 → M5 消费 → M6 实际编排 → M7 执行 → M8 工资。
    //   市场日判据仍是唯一一处（MarketTriggerBook.triggerFor）；非市场日不执行 M4，但 M3/M5/M6/M7 照常。
    boolean marketDay = MarketTriggerBook.triggerFor(ctx.day, ctx.markets) != MarketTrigger.NONE;
    // M2：债务最先（市场日）；随后重建 SettlementIndex 让后续阶段读新产权。
    if (marketDay) {
      SettlementDayDebtPipeline.settleDebtsAtMarketOpen(ctx);
    }
    // M3 之前的基础装配：家户/actor 反查、到货、套利收集器、匹配快照、cycleDays。
    SettlementDayProductionPipeline.prepareProductionBase(ctx);
    // M3：预期生产编排（只写瞬态 ExpectedProductionPlan + EmploymentLock + MarketIntent）。
    SettlementDayExpectedProductionPipeline.planExpectedProduction(ctx);
    // ★★★ P3c3/P3e legacy 回退：只有生产 roster 真正为空（旧生产形态）才走旧链。
    //   判据不得是 M3 的 byUnit 是否为空：有 roster 但因缺锚/缺地/负收益而没有入选 unit 是**新链合法结果**
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
                    "legacy-prepareConsumption+production+settleMarket"));
      }
      // prepareProductionBase 已跑过，不重跑；按 plantingDrawsFirst 恢复旧消费/劳动次序。
      SettlementDayProductionPipeline.legacyConsumeAfterBase(ctx);
      SettlementDayProductionPipeline.driveProduction(ctx);
      SettlementDayProductionPipeline.closeAndHarvest(ctx);
      SettlementDayWagePipeline.settleWageAndCapitalize(ctx, marketDay);
      if (marketDay) {
        SettlementDayMarketPipeline.settleMarket(ctx);
      }
      SettlementDayDebtPipeline.settleDebt(ctx);
      SettlementDayClosePipeline.reportGapsAndInterest(ctx);
      SettlementDayClosePipeline.settleOperatorState(ctx);
      SettlementDayClosePipeline.writeFlowsAndFinish(ctx);
      SettlementTickChainLog.logTickChain(ctx, marketDay);
      return;
    }
    // M4：统一市场只执行 M3 意向（append 到同一个 MarketRound 后唯一撮合）；非市场日不执行。
    if (marketDay) {
      MarketSettlement.MarketRound preparedRound =
          SettlementDayMarketPipeline.prepareMarketRound(
              ctx, ctx.expectedProductionPlan.marketIntents());
      SettlementDayMarketPipeline.executeMarketRound(ctx, preparedRound);
    }
    // M5：每日基础消费（市场买到的东西可用；新买土地不可用）。
    SettlementDayProductionPipeline.settleConsumption(ctx);
    // M6：实际生产编排（雇佣锁只读；非雇佣小生产在 M3 正收益候选内重排；不产生市场订单）。
    SettlementDayActualProductionPipeline.planActualProduction(ctx);
    SettlementDayProductionPipeline.planActualLabor(ctx);
    // M7：实际投入现扣 → 生产执行（同一环节入库）。
    SettlementDayProductionPipeline.drawActualInputs(ctx);
    SettlementDayProductionPipeline.driveProduction(ctx);
    SettlementDayProductionPipeline.closeAndHarvest(ctx);
    // M8：工资结算（P4 算法不变；本批只保留调用点）。
    SettlementDayWagePipeline.settleWageAndCapitalize(ctx, marketDay);
    // 市场后处理 / 政府授权生命周期 / 债务估值 / 利息与关账（位置不变，仍在 M4 之后）。
    SettlementDayDebtPipeline.settleDebt(ctx);
    SettlementDayClosePipeline.reportGapsAndInterest(ctx);
    SettlementDayClosePipeline.settleOperatorState(ctx);
    SettlementDayClosePipeline.writeFlowsAndFinish(ctx);
    // ★ P3e：每 tick 一条全链路 INFO 汇总（legacy 分支末尾同样有一条）。
    SettlementTickChainLog.logTickChain(ctx, marketDay);
  }
}
