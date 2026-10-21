package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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
 * ★★★ <b>D-032 P3c3：M6 第二遍实际生产编排</b>。
 *
 * <p>在 M4 统一市场与 M5 每日消费之后运行：
 *
 * <ol>
 *   <li>M3 {@link EmploymentLock} <b>只读</b>：雇主/雇工/角色/劳动/工资/币种一字不改；锁失败只标记 position failed；
 *   <li>先扣锁定雇佣劳动；剩余劳动只在 M3 {@code positiveCandidates} 集合内按同一 {@link PositionEconomics} 重排；
 *   <li>用 M4/M5 后的实际库存/货币/土地重新调用 {@code probeRoster}；本轮新取得土地（{@link
 *       SettlementDayContext#landAcquiredThisRound()}）不进入任何位置；
 *   <li>输出瞬态 {@link ActualProductionPlan}；最终劳动承诺仍写回既有 {@code laborCommitments} 工作副本（不是第二套状态）。
 * </ol>
 *
 * <p>★ M6 <b>不产生</b>任何 {@link MarketIntent}/市场订单；工资支付仍在 M8（P4）。
 */
final class SettlementDayActualProductionPipeline {

  private SettlementDayActualProductionPipeline() {}

  static void planActualProduction(SettlementDayContext ctx) {
    ExpectedProductionPlan expected = ctx.expectedProductionPlan;
    if (expected == null) {
      throw new IllegalStateException(
          "M6 需要 M3 的 ExpectedProductionPlan（P3c3 日序被绕过）: day=" + ctx.day);
    }
    Map<ProductionUnitId, ProductionRoster> rosters = ctx.session.sheet().productionRostersOrBase();
    // ★ P4c1：M6 只读 M3 的 WageDemand 清单（同一 tick 快照）；不重算、不改锁、不写锚。
    Map<ProductionUnitId, List<ProductionPlanBridge.PositionFacts>> actualFactsByUnit =
        actualFacts(ctx, rosters, expected.wageDemands());

    Map<HouseholdId, Map<String, ProductionPlanBridge.PositionFacts>> actualBySlot =
        new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<ProductionPlanBridge.PositionFacts>> entry :
        actualFactsByUnit.entrySet()) {
      for (ProductionPlanBridge.PositionFacts fact : entry.getValue()) {
        actualBySlot
            .computeIfAbsent(fact.household(), ignored -> new LinkedHashMap<>())
            .put(slotKey(fact.position().unit(), fact.position().role()), fact);
      }
    }

    // ── 锁：只读 + 实际可行性（unit 存活 / 实际正收益 / 预算）校验；失败只标记，不换人/不换份额 ──
    // ★ P3e：先按 actualBySlot、正收益、unit 存活、预算过滤出 actualValidLocks，再 materialize；
    //   positionNet <= 0 / fact == null 的锁不得写进 laborCommitments，也不得进入 ActualProductionPlan.locks。
    List<EmploymentLock> actualValidLocks = new ArrayList<>();
    Map<HouseholdId, Long> lockedLaborByWorker = new LinkedHashMap<>();
    for (EmploymentLock lock : expected.locks()) {
      String rejection = lockRejection(ctx, lock, rosters);
      if (rejection != null) {
        ProductionPlanBridge.logPositionFailed(ctx, lock.unit(), lock.worker(), rejection);
        continue;
      }
      ProductionPlanBridge.PositionFacts actualFact =
          actualBySlot.getOrDefault(lock.worker(), Map.of()).get(slotKey(lock.unit(), lock.role()));
      if (actualFact == null || actualFact.position().positionNetMilli() <= 0L) {
        ProductionPlanBridge.logPositionFailed(
            ctx, lock.unit(), lock.worker(), "locked-position-infeasible-after-market");
        continue;
      }
      long already = lockedLaborByWorker.getOrDefault(lock.worker(), 0L);
      long budget =
          ctx.householdEconomies.containsKey(lock.worker())
              ? ctx.householdEconomies.get(lock.worker()).laborMilli()
              : 0L;
      if (already + lock.laborMilliPerDay() > budget) {
        ProductionPlanBridge.logPositionFailed(
            ctx, lock.unit(), lock.worker(), "locked-labor-exceeds-budget");
        continue;
      }
      lockedLaborByWorker.merge(lock.worker(), lock.laborMilliPerDay(), Math::addExact);
      actualValidLocks.add(lock);
    }

    Map<HouseholdId, HouseholdProductionPlan> byHousehold = new LinkedHashMap<>();
    List<ProductionPlanBridge.PositionFacts> chosenFacts = new ArrayList<>();
    Set<ProductionUnitId> chosenUnits = new LinkedHashSet<>();
    // M3 家户序 = 纯库存财富降序 → id；byHousehold 的键序已冻结，直接沿用。
    for (HouseholdId household : expected.byHousehold().keySet()) {
      long budget =
          ctx.householdEconomies.containsKey(household)
              ? ctx.householdEconomies.get(household).laborMilli()
              : 0L;
      long usedLabor = lockedLaborByWorker.getOrDefault(household, 0L);
      long expectedNet = 0L;
      List<PositionPlan> chosen = new ArrayList<>();
      Set<String> lockedSlots = new LinkedHashSet<>();
      for (EmploymentLock lock : actualValidLocks) {
        if (!lock.worker().equals(household)) {
          continue;
        }
        ProductionPlanBridge.PositionFacts fact =
            actualBySlot.getOrDefault(household, Map.of()).get(slotKey(lock.unit(), lock.role()));
        if (fact == null || fact.position().positionNetMilli() <= 0L) {
          // actualValidLocks 已过滤；这里只做防御，不重复记同一条业务拒绝。
          continue;
        }
        chosen.add(fact.position());
        chosenFacts.add(fact);
        chosenUnits.add(fact.position().unit());
        expectedNet = Math.addExact(expectedNet, fact.position().positionNetMilli());
        lockedSlots.add(slotKey(lock.unit(), lock.role()));
      }
      // 非雇佣小生产：只在 M3 正收益候选集合内重排；不得新增 M3 没算过的位置。
      List<PositionPlan> candidates =
          new ArrayList<>(expected.positiveCandidates().getOrDefault(household, List.of()));
      candidates.sort(
          Comparator.<PositionPlan>comparingLong(PositionPlan::positionYieldPerLaborScaled)
              .reversed()
              .thenComparing(position -> position.unit().value())
              .thenComparing(position -> position.role().value()));
      Set<String> chosenSlots = new LinkedHashSet<>();
      for (PositionPlan candidate : candidates) {
        String key = slotKey(candidate.unit(), candidate.role());
        if (lockedSlots.contains(key) || !chosenSlots.add(key)) {
          continue;
        }
        ProductionPlanBridge.PositionFacts actual =
            actualBySlot.getOrDefault(household, Map.of()).get(key);
        if (actual == null || actual.position().positionNetMilli() <= 0L) {
          ProductionPlanBridge.logPositionFailed(
              ctx, candidate.unit(), household, "actual-replan-candidate-infeasible");
          continue;
        }
        // ★ P3e：零劳动 organizer/产品份额位置只要正收益即可入选，不消耗本户劳动预算。
        long selfLabor = actual.position().selfLaborMilliPerDay();
        if (selfLabor > 0L && usedLabor + selfLabor > budget) {
          continue;
        }
        if (selfLabor > 0L) {
          usedLabor += selfLabor;
        }
        expectedNet = Math.addExact(expectedNet, actual.position().positionNetMilli());
        chosen.add(actual.position());
        chosenFacts.add(actual);
        chosenUnits.add(actual.position().unit());
      }
      byHousehold.put(
          household, new HouseholdProductionPlan(household, chosen, expectedNet, budget));
    }

    // 锁定雇佣不可变 → 先写进既有 laborCommitments 工作副本，再由 labor queue 原样保留。
    materializeLocks(ctx, actualValidLocks, rosters);
    // ★ RC8c：保留本次实际编排的位置事实（只读瞬态）。S4 账单用它取 probe 的投入需求，S7 LAND 用它取实际位置需求。
    ctx.actualPositionFacts = List.copyOf(chosenFacts);
    Map<ProductionUnitId, PositionPlan.UnitPlan> byUnit = unitPlans(chosenFacts);
    ctx.actualProductionPlan =
        new ActualProductionPlan(ctx.day, byHousehold, byUnit, actualValidLocks);
    if (EconomyLog.settlement().isInfoEnabled()) {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "D032_P3_ACTUAL_REPLAN",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  ctx.day,
                  "positions",
                  chosenFacts.size(),
                  "units",
                  chosenUnits.size(),
                  "locks",
                  actualValidLocks.size(),
                  "reason",
                  "m6-actual-replan-with-read-only-employment-locks"));
    }
  }

  /** 以实际库存/土地/劳动重新调 {@code probeRoster}（同公式，只换信息集）。 */
  private static Map<ProductionUnitId, List<ProductionPlanBridge.PositionFacts>> actualFacts(
      SettlementDayContext ctx,
      Map<ProductionUnitId, ProductionRoster> rosters,
      List<WageDemand> wageDemands) {
    Map<ProductionUnitId, List<ProductionPlanBridge.PositionFacts>> byUnit = new LinkedHashMap<>();
    List<ProductionUnitId> unitIds = new ArrayList<>(ctx.units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
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
      Optional<String> rejection =
          ProductionPlanBridge.positionLocationRejection(ctx, unit, roster, industry);
      if (rejection.isPresent()) {
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
                      rejection.get()));
        }
        continue;
      }
      List<ProductionPlanBridge.PositionFacts> facts =
          ProductionPlanBridge.evaluateRoster(ctx, unit, roster, industry, wageDemands);
      if (!facts.isEmpty()) {
        byUnit.put(unitId, facts);
      }
    }
    return byUnit;
  }

  private static String lockRejection(
      SettlementDayContext ctx,
      EmploymentLock lock,
      Map<ProductionUnitId, ProductionRoster> rosters) {
    ProductionProcess unit = ctx.units.get(lock.unit());
    if (unit == null) {
      return ProductionPlanBridge.UNIT_MISSING;
    }
    IndustryDefinitionView industry = ctx.industries.get(unit.industry());
    if (industry == null) {
      return "position-industry-missing";
    }
    ProductionRoster roster = ProductionPlanBridge.effectiveRoster(ctx, unit, industry, rosters);
    if (roster == null) {
      return "position-roster-missing";
    }
    if (!roster.organizerHousehold().equals(lock.employer())) {
      return "position-employer-changed";
    }
    boolean participantPresent = false;
    for (ProductionParticipant participant : roster.participants()) {
      if (participant.household().equals(lock.worker()) && participant.role().equals(lock.role())) {
        participantPresent = true;
        break;
      }
    }
    if (!participantPresent) {
      return "position-worker-missing";
    }
    return ProductionPlanBridge.positionLocationRejection(ctx, unit, roster, industry).orElse(null);
  }

  /** 把锁定雇佣写入既有 {@code laborCommitments} 工作副本：复用同 (unit, worker, role) 行 id，没有才新发；角色/雇主/劳动不改。 */
  private static void materializeLocks(
      SettlementDayContext ctx,
      List<EmploymentLock> locks,
      Map<ProductionUnitId, ProductionRoster> rosters) {
    if (locks.isEmpty()) {
      return;
    }
    Map<HouseholdId, List<HouseholdLaborCommitment>> byHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment commitment : ctx.laborCommitments.values()) {
      byHousehold
          .computeIfAbsent(commitment.household(), ignored -> new ArrayList<>())
          .add(commitment);
    }
    for (EmploymentLock lock : locks) {
      ProductionProcess unit = ctx.units.get(lock.unit());
      if (unit == null) {
        continue;
      }
      PeopleLotId lot = LaborCandidateBook.chooseLot(lock.worker(), byHousehold, ctx.composition);
      if (lot == null) {
        ProductionPlanBridge.logPositionFailed(
            ctx, lock.unit(), lock.worker(), "locked-labor-no-lot");
        continue;
      }
      var id =
          HouseholdLaborCommitment.idOfProduction(lock.unit(), lock.role(), lot, lock.worker());
      HouseholdLaborCommitment existing = ctx.laborCommitments.get(id);
      if (existing != null) {
        if (existing.kind() != LaborCommitmentKind.PRODUCTION
            || existing.productionRole().isEmpty()
            || !existing.productionRole().get().equals(lock.role())) {
          ProductionPlanBridge.logPositionFailed(
              ctx, lock.unit(), lock.worker(), "locked-commitment-conflict");
          continue;
        }
        ctx.laborCommitments.put(
            id, LaborQueueWritebackBook.withLaborMilli(existing, lock.laborMilliPerDay()));
        continue;
      }
      HouseholdLaborCommitment fresh =
          new HouseholdLaborCommitment(
              id,
              lot,
              lock.worker(),
              unit.operator(),
              lock.unit().value(),
              lock.laborMilliPerDay(),
              1L,
              LaborCommitmentKind.PRODUCTION,
              Optional.of(lock.role()));
      ctx.laborCommitments.put(id, fresh);
      byHousehold.computeIfAbsent(lock.worker(), ignored -> new ArrayList<>()).add(fresh);
    }
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

  private static String slotKey(
      ProductionUnitId unit, io.mosire.simos.economy.api.production.ProductionRoleId role) {
    return unit.value() + "\u0000" + role.value();
  }
}
