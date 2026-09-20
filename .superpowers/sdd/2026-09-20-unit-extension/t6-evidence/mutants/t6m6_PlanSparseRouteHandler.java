package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.PlanSparseRoute} 命令的处理器（spec §二.2 / P10）：{@code id, waypoints[{q,r}…]}，载荷与 {@code
 * unit.PlanRoute} **同形**，但 `waypoints` **允许非相邻**——逐段 A\* 展开成逐格 `path` 后交给 {@link
 * UnitOperations#planSparseRoute}。
 *
 * <p>★ **成本实现由装配注入**（裁定 U3）：{@code PlanSparseRouteHandler(MovementCost)}，`Shell` 传 {@code
 * TerrainMovementCost.INSTANCE}（与 {@link UnitTimeParticipant} 同源）。★ **`GameMap` 不进构造器**：由 {@link
 * #mapOf} 从 `state.module("map")` 读（照 `UnitTimeParticipant.mapOf` 的形制），缺切片/类型不符 ⇒
 * **装配故障当场炸**（{@link IllegalStateException}，与 {@code UnitSnapshots} / {@code MapResolver}
 * 同口径），**不走**拒绝路径、不静默兜底。
 *
 * <p>★ **任一相邻段不可达 ⇒ 拒绝**（P12）：展开在 op 层抛 {@link IllegalArgumentException}，这里折成 {@code
 * Rejected}；起点校验沿用既有的 {@code UnitOperations.planRoute}（spec §二.2「既有校验不动」）。
 *
 * <p>★ **删掉的话 Shell 不认这条命令**：与 {@code unit.PlanRoute} 并存的理由见 spec §二.2 末段（既有命令的"载荷必须逐格相邻"契约已被 WebUI
 * 与 e2e 引用）。
 */
public final class PlanSparseRouteHandler implements CommandHandler {

  private final MovementCost cost;

  public PlanSparseRouteHandler(MovementCost cost) {
    this.cost = Objects.requireNonNull(cost, "cost");
  }

  @Override
  public String type() {
    return "unit.PlanSparseRoute";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      List<HexCoord> waypoints = UnitPayloads.requireWaypoints(payload, "waypoints");
      GameMap map = mapOf(state);
      SimosTimestamp at = state.meta().timestamp();
      UnitState next =
          UnitOperations.planSparseRoute(snapshot.state(), id, map, waypoints, cost, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 与 {@code UnitTimeParticipant.mapOf} 同制：缺 `map` 切片或类型不符 ⇒ **装配故障当场炸**。 */
  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot = state.module("map").orElse(null);
    return snapshot instanceof MapSnapshot mapSnapshot ? mapSnapshot.map() : null;
  }
}
