package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 按时间戳物化移动（M3 spec §4.5）：**纯函数**，不写回状态（写回属 M4 的两阶段推进，spec 偏离 5）。
 *
 * <p>★ **日制预算模型（2026-09-24 用户裁定，取代旧的"1 tick = 1 小时"口径）**：速度以**小时**为基准 （{@code speedAtDeparture}
 * 的单位是 **MP/小时**），而推进的一刻度是一**天**——一天按 24 小时流结算，故 `budget = speedAtDeparture × 1000 × HOURS_PER_DAY
 * × (at.tick − departedAt.tick)`（毫 MP，`Δtick` 以**天**计； 中间量走 `long`）。这里只在公式里补 24 而**不改 {@code
 * Unit.speed} 的数值**：MP/小时 是速度的自然量纲（设计稿 §3 的旧小时速度也就无需 ×24 换算），日预算由 {@link #HOURS_PER_DAY} 一处表达。
 *
 * <p>沿 `path` **逐格按地形成本付费**：付不起下一格时**允许停在两格之间**——位置仍是最后一个已付清的格，差量留在 `IN_TRANSIT` 的
 * `remainingEdgeCostMillis` 里（"向进入下一格之前取整"）。`evaluate` 是"从出发时刻起按总预算重算"的 纯函数 ⇒ **余量天然跨日保留**（同一条行程在
 * `Δtick = 2` 时从出发起算的总预算里仍含着第 1 天没花掉的那部分）。 全付清 ⇒ `ARRIVED`。
 *
 * <p>★ **机动性冻结口在此**：成本函数从 `Unit` 上读 `mobilityPerMille()`，而在途行程必须用 `mobilityAtDeparture` ⇒
 * 本类**副本一份单位**再调成本函数（不是改 `MovementCost` 的签名）。
 */
public final class UnitMoves {

  /**
   * ★ **日制裁定的一部分**：一 tick = 一天 = 24 小时，行进的日预算 = {@code speedAtDeparture × 1000 × 本值}（毫 MP）。
   *
   * <p>为什么常量放在**公式里**而不把 `Unit.speed` 的数值改成"MP/日"：速度的自然量纲是 MP/小时（设计稿 §3 的旧小时速度
   * 无需换算），"一天多少预算"是**时间语义**而非单位换算——把 24 收在这一处，日制若再变（如半天 tick）只改这里。
   */
  public static final int HOURS_PER_DAY = 24;

  private UnitMoves() {}

  /**
   * 计算 `at` 时刻的移动状态。
   *
   * @throws IllegalArgumentException 单位没有在途路线，或 `at` 早于出发时刻（调用方 bug）
   */
  public static MovementState evaluate(
      Unit unit, SimosTimestamp at, GameMap map, MovementCost cost) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(cost, "cost");
    Movement movement =
        unit.movement()
            .orElseThrow(
                () -> {
                  if (UnitLog.advance().isDebugEnabled()) {
                    EventLog.channel(UnitLog.advance())
                        .debug(
                            LogEvent.of(
                                "UNIT_MOVE_REJECTED",
                                UnitLogSource.UNIT_ADVANCE,
                                "day",
                                at.tick(),
                                "unit",
                                unit.id().value(),
                                "at",
                                at,
                                "reason",
                                "noInFlightRoute"));
                  }
                  return new IllegalArgumentException("单位没有在途路线: " + unit.id());
                });
    if (at.compareTo(movement.departedAt()) < 0) {
      EventLog.channel(UnitLog.advance())
          .debug(
              LogEvent.of(
                  "UNIT_MOVE_REJECTED",
                  UnitLogSource.UNIT_ADVANCE,
                  "day",
                  at.tick(),
                  "unit",
                  unit.id().value(),
                  "at",
                  at,
                  "departedAt",
                  movement.departedAt(),
                  "reason",
                  "timestampBeforeDeparture"));
      throw new IllegalArgumentException("查询时刻早于出发时刻: " + at + " < " + movement.departedAt());
    }
    long budget =
        movement.speedAtDeparture()
            * 1000L
            * HOURS_PER_DAY
            * (at.tick() - movement.departedAt().tick());
    Unit frozen =
        new Unit(
            unit.id(),
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.equipment(),
            unit.speed(),
            movement.mobilityAtDeparture(),
            unit.movement(),
            unit.status(),
            unit.attached(),
            unit.offset(),
            unit.rejoinTarget(),
            unit.visionRadius(),
            unit.jurisdiction(),
            unit.module(),
            unit.stateDescriptions(),
            unit.households());

    List<HexCoord> path = movement.route().path();
    long totalBudget = budget;
    for (int i = 0; i + 1 < path.size(); i++) {
      HexCoord from = path.get(i);
      HexCoord to = path.get(i + 1);
      OptionalLong step = cost.costMillis(from, to, frozen, map);
      if (step.isEmpty()) {
        if (UnitLog.advance().isDebugEnabled()) {
          EventLog.channel(UnitLog.advance())
              .debug(
                  LogEvent.of(
                      "UNIT_MOVE_NEED_REPLAN",
                      UnitLogSource.UNIT_ADVANCE,
                      "unit",
                      unit.id().value(),
                      "day",
                      at.tick(),
                      "from",
                      from,
                      "to",
                      to,
                      "reason",
                      "edgeImpassable"));
        }
        MovementState state =
            new MovementState(
                from, Optional.empty(), OptionalLong.empty(), MovementStatus.NEED_REPLAN);
        traceEvaluation(unit, at, state, totalBudget, budget);
        return state;
      }
      long edgeCost = step.getAsLong();
      if (budget >= edgeCost) {
        budget -= edgeCost;
        continue;
      }
      MovementState state =
          new MovementState(
              from, Optional.of(to), OptionalLong.of(edgeCost - budget), MovementStatus.IN_TRANSIT);
      traceEvaluation(unit, at, state, totalBudget, budget);
      return state;
    }
    MovementState state =
        new MovementState(
            path.get(path.size() - 1),
            Optional.empty(),
            OptionalLong.empty(),
            MovementStatus.ARRIVED);
    traceEvaluation(unit, at, state, totalBudget, budget);
    return state;
  }

  /** 逐单位逐 tick 的移动物化读数（TRACE，默认关；先看开关再构造事件，避免热点上的无谓分配）。 */
  private static void traceEvaluation(
      Unit unit, SimosTimestamp at, MovementState state, long totalBudget, long leftBudget) {
    if (!UnitLog.trace().isTraceEnabled()) {
      return;
    }
    long edgeRemaining =
        state.remainingEdgeCostMillis().isPresent()
            ? state.remainingEdgeCostMillis().getAsLong()
            : 0L;
    EventLog.channel(UnitLog.trace())
        .trace(
            LogEvent.of(
                "UNIT_MOVE_EVALUATED",
                UnitLogSource.UNIT_ADVANCE,
                "unit",
                unit.id().value(),
                "day",
                at.tick(),
                "status",
                state.status(),
                "from",
                state.currentHex(),
                "to",
                state.nextHex().map(HexCoord::toString).orElse("-"),
                "budget",
                totalBudget,
                "leftBudget",
                leftBudget,
                "remaining",
                edgeRemaining));
  }
}
