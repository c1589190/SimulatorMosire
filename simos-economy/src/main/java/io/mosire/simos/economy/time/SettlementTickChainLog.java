package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>D-032 P3e：每 tick 一条全链路 INFO 汇总（{@code D032_TICK_CHAIN}）</b>。
 *
 * <p>它把 M3 预期计划、M6 实际计划、M7 执行、M8 工资结算的<b>具名计数</b>串到同一行，供小世界全链路诊断 （"这户为什么没种地 / 为什么没被雇 /
 * 工资付了多少"）。逐户明细走 DEBUG/TRACE，不进 INFO。
 *
 * <p>★★ <b>P6a2：{@code D032_HOUSEHOLD_CHAIN} DEBUG 逐户明细</b>——在 INFO 汇总之后，按 {@code
 * household.value()} 稳定升序每户一行；字段只读自本 tick 的瞬态计划/回执/工资读数，缺阶段按 0。逐户日志失败逐个吞掉，绝不影响结算。
 *
 * <p>★ 只读日志：不写状态、不改公式；计数溢出按饱和处理，日志失败不得影响结算。
 */
final class SettlementTickChainLog {

  private static final Logger PRODUCTION = EconomyLog.settlement();

  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId FIBER = new CommodityId(EconomyVocabulary.FIBER_COMMODITY_ID);
  private static final String HIRED_LABORER_ROLE = "hired_laborer";

  private SettlementTickChainLog() {}

  /** 每个 tick 末尾调用一次（legacy 回退分支也要调用；缺失的阶段计数记 0）。 */
  static void logTickChain(SettlementDayContext ctx, boolean marketDay) {
    boolean infoEnabled = PRODUCTION.isInfoEnabled();
    boolean debugEnabled = PRODUCTION.isDebugEnabled();
    if (!infoEnabled && !debugEnabled) {
      return;
    }
    if (infoEnabled) {
      try {
        logTickChainInfo(ctx, marketDay);
      } catch (RuntimeException ignored) {
        // ★ 日志失败不影响结算（P6a2 纪律）。
      }
    }
    if (debugEnabled) {
      try {
        logHouseholdChains(ctx);
      } catch (RuntimeException ignored) {
        // ★ 日志失败不影响结算（P6a2 纪律）。
      }
    }
  }

  private static void logTickChainInfo(SettlementDayContext ctx, boolean marketDay) {
    ExpectedProductionPlan expected = ctx.expectedProductionPlan;
    ActualProductionPlan actual = ctx.actualProductionPlan;
    long rosters = ctx.session.sheet().productionRostersOrBase().size();
    long plannedHouseholds = 0L;
    long positions = 0L;
    long expectedLocks = 0L;
    long intents = 0L;
    long wageDemands = 0L;
    if (expected != null) {
      for (HouseholdProductionPlan plan : expected.byHousehold().values()) {
        if (!plan.chosen().isEmpty()) {
          plannedHouseholds++;
        }
        positions = saturatingAdd(positions, plan.chosen().size());
      }
      expectedLocks = expected.locks().size();
      intents = expected.marketIntents().size();
      wageDemands = expected.wageDemands().size();
    }
    long actualLocks = actual == null ? 0L : actual.locksReadOnly().size();
    long executed = ctx.productionDay == null ? 0L : ctx.productionDay.receipts().size();
    BigInteger productionGrainMilli = BigInteger.ZERO;
    BigInteger productionFiberMilli = BigInteger.ZERO;
    BigInteger seedInputMilli = BigInteger.ZERO;
    long productionLaborMilli = 0L;
    long hiredLaborMilli = 0L;
    if (ctx.productionDay != null) {
      for (ProductionReceipt receipt : ctx.productionDay.receipts()) {
        productionGrainMilli =
            productionGrainMilli.add(
                BigInteger.valueOf(receipt.grossOutput().getOrDefault(GRAIN, 0L)));
        productionFiberMilli =
            productionFiberMilli.add(
                BigInteger.valueOf(receipt.grossOutput().getOrDefault(FIBER, 0L)));
        seedInputMilli =
            seedInputMilli.add(
                BigInteger.valueOf(receipt.consumedInputs().getOrDefault(GRAIN, 0L)));
        for (Map.Entry<ProductionParticipantKey, Long> contribution :
            receipt.laborContributionsMilli().entrySet()) {
          productionLaborMilli = saturatingAdd(productionLaborMilli, contribution.getValue());
          if (HIRED_LABORER_ROLE.equals(contribution.getKey().role().value())) {
            hiredLaborMilli = saturatingAdd(hiredLaborMilli, contribution.getValue());
          }
        }
      }
    }
    long wageDueMilli = 0L;
    long wagePaidMilli = 0L;
    long wageArrearsMilli = 0L;
    if (ctx.wageSettlement != null) {
      for (WageSettlementReadout readout : ctx.wageSettlement.readouts()) {
        wageDueMilli = saturatingAdd(wageDueMilli, readout.dueMilli());
        wagePaidMilli = saturatingAdd(wagePaidMilli, readout.paidMoneyMilli());
        wageArrearsMilli = saturatingAdd(wageArrearsMilli, readout.arrearsMilli());
      }
    }
    EventLog.channel(PRODUCTION)
        .info(
            LogEvent.of(
                "D032_TICK_CHAIN",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                ctx.day,
                "marketDay",
                marketDay,
                "path",
                expected == null ? "legacy" : "two-pass",
                "rosters",
                rosters,
                "plannedHouseholds",
                plannedHouseholds,
                "positions",
                positions,
                "expectedLocks",
                expectedLocks,
                "actualLocks",
                actualLocks,
                "intents",
                intents,
                "wageDemands",
                wageDemands,
                "executed",
                executed,
                "productionGrainMilli",
                productionGrainMilli,
                "productionFiberMilli",
                productionFiberMilli,
                "seedInputMilli",
                seedInputMilli,
                "productionLaborMilli",
                productionLaborMilli,
                "hiredLaborMilli",
                hiredLaborMilli,
                "wageDueMilli",
                wageDueMilli,
                "wagePaidMilli",
                wagePaidMilli,
                "wageArrearsMilli",
                wageArrearsMilli));
  }

  /** 逐户链路 DEBUG：每户一行，字段与顺序固定；单户失败不打断其它户。 */
  private static void logHouseholdChains(SettlementDayContext ctx) {
    ExpectedProductionPlan expected = ctx.expectedProductionPlan;
    ActualProductionPlan actual = ctx.actualProductionPlan;
    List<EmploymentLock> expectedLocks = expected == null ? List.of() : expected.locks();
    List<EmploymentLock> actualLocks = actual == null ? List.of() : actual.locksReadOnly();
    Map<HouseholdId, long[]> wagesByWorker = new LinkedHashMap<>();
    if (ctx.wageSettlement != null) {
      for (WageSettlementReadout readout : ctx.wageSettlement.readouts()) {
        long[] totals = wagesByWorker.computeIfAbsent(readout.worker(), ignored -> new long[3]);
        totals[0] = saturatingAdd(totals[0], readout.dueMilli());
        totals[1] = saturatingAdd(totals[1], readout.paidMoneyMilli());
        totals[2] = saturatingAdd(totals[2], readout.arrearsMilli());
      }
    }
    Map<HouseholdId, Integer> receiptCounts = receiptCounts(ctx, actual);
    for (HouseholdId household : householdsOf(ctx, expected, actual, wagesByWorker)) {
      try {
        HouseholdProductionPlan expectedPlan =
            expected == null ? null : expected.byHousehold().get(household);
        HouseholdProductionPlan actualPlan =
            actual == null ? null : actual.byHousehold().get(household);
        long expectedEmployer = locksAsEmployer(expectedLocks, household);
        long expectedWorker = locksAsWorker(expectedLocks, household);
        long actualEmployer = locksAsEmployer(actualLocks, household);
        long actualWorker = locksAsWorker(actualLocks, household);
        long[] wages = wagesByWorker.getOrDefault(household, new long[3]);
        EventLog.channel(PRODUCTION)
            .debug(
                LogEvent.of(
                    "D032_HOUSEHOLD_CHAIN",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "household",
                    household.value(),
                    "expectedPositions",
                    expectedPlan == null ? 0L : expectedPlan.chosen().size(),
                    "expectedNetMilli",
                    expectedPlan == null ? 0L : expectedPlan.expectedNetMilli(),
                    "expectedLocksAsEmployer",
                    expectedEmployer,
                    "expectedLocksAsWorker",
                    expectedWorker,
                    "actualPositions",
                    actualPlan == null ? 0L : actualPlan.chosen().size(),
                    "actualNetMilli",
                    actualPlan == null ? 0L : actualPlan.expectedNetMilli(),
                    "actualLocksAsEmployer",
                    actualEmployer,
                    "actualLocksAsWorker",
                    actualWorker,
                    "receipts",
                    receiptCounts.getOrDefault(household, 0),
                    "wageDueMilli",
                    wages[0],
                    "paidMoneyMilli",
                    wages[1],
                    "arrearsMilli",
                    wages[2]));
      } catch (RuntimeException ignored) {
        // ★ 单户日志失败不影响其它户，也不影响结算。
      }
    }
  }

  /** 参与本 tick 的稳定家户全集（composition ∪ 经济行 ∪ 两遍计划 ∪ 工资读数），按 id 升序。 */
  private static List<HouseholdId> householdsOf(
      SettlementDayContext ctx,
      ExpectedProductionPlan expected,
      ActualProductionPlan actual,
      Map<HouseholdId, long[]> wagesByWorker) {
    Set<HouseholdId> households = new LinkedHashSet<>();
    if (ctx.composition != null) {
      households.addAll(ctx.composition.keySet());
    }
    if (ctx.householdEconomies != null) {
      households.addAll(ctx.householdEconomies.keySet());
    }
    if (expected != null) {
      households.addAll(expected.byHousehold().keySet());
    }
    if (actual != null) {
      households.addAll(actual.byHousehold().keySet());
    }
    households.addAll(wagesByWorker.keySet());
    List<HouseholdId> ordered = new ArrayList<>(households);
    ordered.sort(Comparator.comparing(HouseholdId::value));
    return ordered;
  }

  /** 本 tick 每户收到几条生产回执（有名册按名册，缺名册按 M6 实际位置归户）。 */
  private static Map<HouseholdId, Integer> receiptCounts(
      SettlementDayContext ctx, ActualProductionPlan actual) {
    Map<HouseholdId, Integer> counts = new LinkedHashMap<>();
    if (ctx.productionDay == null || ctx.productionDay.receipts().isEmpty()) {
      return counts;
    }
    Map<ProductionUnitId, ProductionRoster> rosters = ctx.session.sheet().productionRostersOrBase();
    Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold = new LinkedHashMap<>();
    if (actual != null) {
      for (Map.Entry<HouseholdId, HouseholdProductionPlan> entry :
          actual.byHousehold().entrySet()) {
        Set<ProductionUnitId> units = new LinkedHashSet<>();
        for (PositionPlan position : entry.getValue().chosen()) {
          units.add(position.unit());
        }
        unitsByHousehold.put(entry.getKey(), units);
      }
    }
    for (ProductionReceipt receipt : ctx.productionDay.receipts()) {
      ProductionRoster roster = rosters.get(receipt.unit());
      if (roster != null) {
        for (HouseholdId household : roster.households()) {
          counts.merge(household, 1, Math::addExact);
        }
        continue;
      }
      for (Map.Entry<HouseholdId, Set<ProductionUnitId>> entry : unitsByHousehold.entrySet()) {
        if (entry.getValue().contains(receipt.unit())) {
          counts.merge(entry.getKey(), 1, Math::addExact);
        }
      }
    }
    return counts;
  }

  private static long locksAsEmployer(List<EmploymentLock> locks, HouseholdId household) {
    long count = 0L;
    for (EmploymentLock lock : locks) {
      if (lock.employer().equals(household)) {
        count++;
      }
    }
    return count;
  }

  private static long locksAsWorker(List<EmploymentLock> locks, HouseholdId household) {
    long count = 0L;
    for (EmploymentLock lock : locks) {
      if (lock.worker().equals(household)) {
        count++;
      }
    }
    return count;
  }

  private static long saturatingAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
