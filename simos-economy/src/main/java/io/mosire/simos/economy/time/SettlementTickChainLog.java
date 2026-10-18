package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import org.slf4j.Logger;

/**
 * ★★★ <b>D-032 P3e：每 tick 一条全链路 INFO 汇总（{@code D032_TICK_CHAIN}）</b>。
 *
 * <p>它把 M3 预期计划、M6 实际计划、M7 执行、M8 工资结算的<b>具名计数</b>串到同一行，供小世界全链路诊断
 * （"这户为什么没种地 / 为什么没被雇 / 工资付了多少"）。逐户明细走 DEBUG/TRACE，不进 INFO。
 *
 * <p>★ 只读日志：不写状态、不改公式；计数溢出按饱和处理，日志失败不得影响结算。
 */
final class SettlementTickChainLog {

  private static final Logger PRODUCTION = EconomyLog.settlement();

  private SettlementTickChainLog() {}

  /** 每个 tick 末尾调用一次（legacy 回退分支也要调用；缺失的阶段计数记 0）。 */
  static void logTickChain(SettlementDayContext ctx, boolean marketDay) {
    if (!PRODUCTION.isInfoEnabled()) {
      return;
    }
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
    long wageDueMilli = 0L;
    long wageArrearsMilli = 0L;
    if (ctx.wageSettlement != null) {
      for (WageSettlementReadout readout : ctx.wageSettlement.readouts()) {
        wageDueMilli = saturatingAdd(wageDueMilli, readout.dueMilli());
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
                "wageDueMilli",
                wageDueMilli,
                "wageArrearsMilli",
                wageArrearsMilli));
  }

  private static long saturatingAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
