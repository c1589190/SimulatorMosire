package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>D-032 P3c3：M3 第一遍预期生产编排</b>。
 *
 * <p>只在 {@code prepareProductionBase} 之后、M4 统一市场之前运行：
 *
 * <ol>
 *   <li>按纯库存财富降序 → HouseholdId 升序遍历家户；
 *   <li>对每个已有名册调用 {@code probeRoster}（A2）与 {@link ProductionPlanBridge#evaluateRoster}
 *       形成正收益位置候选（A4）；
 *   <li>本户按单位劳动净收益贪心选主位置与剩余位置，直到劳动预算用完/没有正收益；
 *   <li>把第一遍雇佣决策落成不可变 {@link EmploymentLock}（只读事实；P4 再支付）；
 *   <li>把生产投入缺口、基础消费缺口、可售剩余与未用土地转成 {@link MarketIntent}。
 * </ol>
 *
 * <p>★ <b>硬边界</b>：本类不写库存/货币/土地/债务/劳动承诺/生产实例；{@link ExpectedProductionPlan} 只放 {@link
 * SettlementDayContext} 瞬态。
 */
final class SettlementDayExpectedProductionPipeline {

  private SettlementDayExpectedProductionPipeline() {}

  static void planExpectedProduction(SettlementDayContext ctx) {
    Map<ProductionUnitId, ProductionRoster> rosters = ctx.session.sheet().productionRostersOrBase();
    List<ProductionUnitId> unitIds = new ArrayList<>(ctx.units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));

    Map<HouseholdId, List<ProductionPlanBridge.PositionFacts>> candidatesByHousehold =
        new LinkedHashMap<>();
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts = new LinkedHashMap<>();
    List<EmploymentLock> allLocks = new ArrayList<>();

    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = ctx.units.get(unitId);
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView industry = ctx.industries.get(unit.industry());
      if (industry == null) {
        continue;
      }
      ProductionRoster roster = ProductionPlanBridge.effectiveRoster(ctx, unit, industry, rosters);
      if (roster == null) {
        continue;
      }
      Optional<String> locationRejection =
          ProductionPlanBridge.positionLocationRejection(ctx, unit, roster, industry);
      if (locationRejection.isPresent()) {
        if (ProductionPlanBridge.LAND_ACQUIRED_THIS_ROUND.equals(locationRejection.get())
            || ProductionPlanBridge.SELF_LABOR_CROSS_HEX.equals(locationRejection.get())
            || ProductionPlanBridge.WORKER_NOT_ON_LAND_HEX.equals(locationRejection.get())) {
          // 业务拒绝 = INFO（D032_P3_LAND_FILTERED 的具名档）。
          if (EconomyLog.settlement().isInfoEnabled()) {
            EventLog.channel(EconomyLog.settlement())
                .info(
                    LogEvent.of(
                        "D032_P3_LAND_FILTERED",
                        EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                        "day",
                        ctx.day,
                        "unit",
                        unitId.value(),
                        "location",
                        unit.location().value(),
                        "reason",
                        locationRejection.get()));
          }
        } else {
          ProductionPlanBridge.logPositionFailed(
              ctx, unitId, roster.organizerHousehold(), locationRejection.get());
        }
        continue;
      }
      List<ProductionPlanBridge.PositionFacts> facts =
          ProductionPlanBridge.evaluateRoster(ctx, unit, roster, industry);
      if (facts.isEmpty()) {
        continue;
      }
      unitFacts.put(unitId, facts.get(0));
      for (ProductionPlanBridge.PositionFacts fact : facts) {
        candidatesByHousehold
            .computeIfAbsent(fact.household(), ignored -> new ArrayList<>())
            .add(fact);
      }
      allLocks.addAll(ProductionPlanBridge.employmentLocksOf(ctx, unit, roster, industry));
    }

    // ── 家户序：纯库存财富降序 → HouseholdId 升序 ─────────────────────────────────────────
    PureInventoryWealthBook.Snapshot wealth =
        PureInventoryWealthBook.snapshot(
            ctx.day,
            ctx.householdEconomies,
            ctx.householdGoods,
            ctx.householdMoney,
            ctx.session.sheet().landHoldingsOrBase(),
            ctx.markets,
            ctx.topology,
            SettlementDayDebtPipeline.currencyValuationAtOpen(ctx));
    List<HouseholdId> orderedHouseholds = new ArrayList<>(ctx.householdEconomies.keySet());
    orderedHouseholds.sort(
        Comparator.<HouseholdId>comparingLong(wealth::wealthOf)
            .reversed()
            .thenComparing(HouseholdId::value));

    Map<HouseholdId, HouseholdProductionPlan> byHousehold = new LinkedHashMap<>();
    Map<HouseholdId, List<PositionPlan>> positiveCandidates = new LinkedHashMap<>();
    List<ProductionPlanBridge.PositionFacts> chosenFacts = new ArrayList<>();
    Set<ProductionUnitId> chosenUnits = new LinkedHashSet<>();
    for (HouseholdId household : orderedHouseholds) {
      List<ProductionPlanBridge.PositionFacts> candidates =
          candidatesByHousehold.getOrDefault(household, List.of());
      if (candidates.isEmpty()) {
        byHousehold.put(household, new HouseholdProductionPlan(household, List.of(), 0L, 0L));
        continue;
      }
      List<ProductionPlanBridge.PositionFacts> sorted = new ArrayList<>(candidates);
      sorted.sort(
          Comparator.<ProductionPlanBridge.PositionFacts>comparingLong(
                  fact -> fact.position().positionYieldPerLaborScaled())
              .reversed()
              .thenComparing(fact -> fact.position().unit().value())
              .thenComparing(fact -> fact.position().role().value()));
      List<PositionPlan> positives = new ArrayList<>();
      for (ProductionPlanBridge.PositionFacts fact : sorted) {
        if (fact.position().positionNetMilli() > 0L) {
          positives.add(fact.position());
        }
      }
      if (!positives.isEmpty()) {
        positiveCandidates.put(household, positives);
      }
      long budget =
          ctx.householdEconomies.containsKey(household)
              ? ctx.householdEconomies.get(household).laborMilli()
              : 0L;
      long usedLabor = 0L;
      long expectedNet = 0L;
      List<PositionPlan> chosen = new ArrayList<>();
      // ★ P3e：同一 unit/role 不重复；零劳动 organizer/产品份额位置只要正收益即可入选，
      //   且不消耗本户劳动预算（纯组织者 + 工资招工在 economy 选择层不得被跳过）。
      Set<String> chosenSlots = new LinkedHashSet<>();
      for (ProductionPlanBridge.PositionFacts fact : sorted) {
        if (fact.position().positionNetMilli() <= 0L) {
          continue;
        }
        String slot = fact.position().unit().value() + "\u0000" + fact.position().role().value();
        if (!chosenSlots.add(slot)) {
          continue;
        }
        long selfLabor = fact.position().selfLaborMilliPerDay();
        if (selfLabor > 0L && usedLabor + selfLabor > budget) {
          chosenSlots.remove(slot);
          continue;
        }
        if (selfLabor > 0L) {
          usedLabor += selfLabor;
        }
        expectedNet = Math.addExact(expectedNet, fact.position().positionNetMilli());
        chosen.add(fact.position());
        chosenFacts.add(fact);
        chosenUnits.add(fact.position().unit());
      }
      byHousehold.put(
          household, new HouseholdProductionPlan(household, chosen, expectedNet, budget));
    }

    // ── 雇佣锁：只保留 M3 真正入选的 unit（未选中的组织者不产生锁）────────────────────────
    List<EmploymentLock> locks = new ArrayList<>();
    for (EmploymentLock lock : allLocks) {
      if (chosenUnits.contains(lock.unit())) {
        locks.add(lock);
        ProductionPlanBridge.logLocked(ctx, lock);
      }
    }

    // ── 物理投入/产出来自同一次 probeRoster（每个入选 unit 只计一次）────────────────────────
    Map<HouseholdId, Map<CommodityId, Long>> inputDemand = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> outputSupply = new LinkedHashMap<>();
    for (ProductionUnitId unitId : chosenUnits) {
      ProductionPlanBridge.PositionFacts unitFact = unitFacts.get(unitId);
      if (unitFact == null) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> input : unitFact.inputRequired().entrySet()) {
        inputDemand
            .computeIfAbsent(unitFact.organizer(), ignored -> new LinkedHashMap<>())
            .merge(input.getKey(), input.getValue(), Math::addExact);
      }
      for (Map.Entry<CommodityId, Long> output : unitFact.expectedOutput().entrySet()) {
        outputSupply
            .computeIfAbsent(unitFact.organizer(), ignored -> new LinkedHashMap<>())
            .merge(output.getKey(), output.getValue(), Math::addExact);
      }
    }
    List<MarketIntent> intents =
        MarketIntentBook.marketIntents(ctx, chosenFacts, inputDemand, outputSupply);

    Map<ProductionUnitId, PositionPlan.UnitPlan> byUnit = unitPlans(chosenFacts);
    // ★★★ P4c1：入选 MONEY_WAGE 位置 → WageDemand 清单（target = hired>0 ? hired : 标准劳动/周期天数；
    //   同 unit/role 至多一条；target = 0 不生成）。只保留清单，不写锚、不写工资账（P4c2 才聚合/调价）。
    List<WageDemand> wageDemands = WageQuoteBridge.wageDemandsOf(ctx, chosenFacts);
    ctx.expectedProductionPlan =
        new ExpectedProductionPlan(
            ctx.day, byHousehold, byUnit, locks, intents, positiveCandidates, wageDemands);
    ProductionPlanBridge.logExpectedPlan(ctx, chosenFacts, locks, intents, wageDemands);
  }

  private static Map<ProductionUnitId, PositionPlan.UnitPlan> unitPlans(
      List<ProductionPlanBridge.PositionFacts> chosenFacts) {
    Map<ProductionUnitId, List<PositionPlan>> grouped = new LinkedHashMap<>();
    for (ProductionPlanBridge.PositionFacts fact : chosenFacts) {
      grouped
          .computeIfAbsent(fact.position().unit(), ignored -> new ArrayList<>())
          .add(fact.position());
    }
    Map<ProductionUnitId, PositionPlan.UnitPlan> byUnit = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<PositionPlan>> entry : grouped.entrySet()) {
      PositionPlan first = entry.getValue().get(0);
      byUnit.put(
          entry.getKey(),
          new PositionPlan.UnitPlan(
              first.unit(), first.plan(), first.location(), entry.getValue()));
    }
    return byUnit;
  }
}
