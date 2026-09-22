package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 按时间戳物化移动（M3 spec §4.5）：**纯函数**，不写回状态（写回属 M4 的两阶段推进，spec 偏离 5）。
 *
 * <p>预算模型：`budget = speedAtDeparture × 1000 × (at.tick − departedAt.tick)`（毫 MP），沿 `path` 逐段付；
 * 付不起的那一段 ⇒ `IN_TRANSIT` 并给出余量；全付清 ⇒ `ARRIVED`。
 *
 * <p>★ **机动性冻结口在此**：成本函数从 `Unit` 上读 `mobilityPerMille()`，而在途行程必须用 `mobilityAtDeparture` ⇒
 * 本类**副本一份单位**再调成本函数（不是改 `MovementCost` 的签名）。
 */
public final class UnitMoves {

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
        unit.movement().orElseThrow(() -> new IllegalArgumentException("单位没有在途路线: " + unit.id()));
    if (at.compareTo(movement.departedAt()) < 0) {
      throw new IllegalArgumentException("查询时刻早于出发时刻: " + at + " < " + movement.departedAt());
    }
    long budget = movement.speedAtDeparture() * 1000L * (at.tick() - movement.departedAt().tick());
    Unit frozen =
        new Unit(
            unit.id(),
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            movement.mobilityAtDeparture(),
            unit.movement(),
            unit.status(),
            unit.attached(),
            unit.offset(),
            unit.rejoinTarget(),
            unit.visionRadius());

    List<HexCoord> path = movement.route().path();
    for (int i = 0; i + 1 < path.size(); i++) {
      HexCoord from = path.get(i);
      HexCoord to = path.get(i + 1);
      OptionalLong step = cost.costMillis(from, to, frozen, map);
      if (step.isEmpty()) {
        return new MovementState(
            from, Optional.empty(), OptionalLong.empty(), MovementStatus.NEED_REPLAN);
      }
      long edgeCost = step.getAsLong();
      if (budget >= edgeCost) {
        budget -= edgeCost;
        continue;
      }
      return new MovementState(
          from, Optional.of(to), OptionalLong.of(edgeCost - budget), MovementStatus.IN_TRANSIT);
    }
    return new MovementState(
        path.get(path.size() - 1), Optional.empty(), OptionalLong.empty(), MovementStatus.ARRIVED);
  }
}
