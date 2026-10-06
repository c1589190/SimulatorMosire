package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.PlanRoute} 命令的处理器（spec §四）：{@code id, waypoints[{q,r}…]}。
 *
 * <p>★ **载荷只给 waypoints，不给 path**（spec §四原文）⇒ 本 handler 把 {@code waypoints} 同时当作 {@link Route} 的
 * {@code path}：即要求点列本身是**逐格相邻**的走法（**允许回到已走过的格**——巡逻环线，2026-09-24 裁定）。这是 spec 载荷与 M3 {@code
 * Route}（waypoints 为 path 子序列、可含中间格）之间唯一的可重建口径——相邻性与个数由 {@code Route} 构造期判、折成拒绝。需要"waypoints 少而
 * path 多"的稀疏路径时须先补载荷字段（记入 t4-report 取代说明候选）。
 *
 * <p>起点必须等于该单位在 {@code at} 的有效位置（M3 口径），否则拒绝。
 */
public final class PlanRouteHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}；P1.5 起 Plan 类直接引用本常量，不再另抄字面量）。 */
  public static final String TYPE = "unit.PlanRoute";

  /**
   * ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：**只**声明被规划路线的那个单位——路线住在 {@code UnitState} 里，{@code
   * waypoints} 是路线的值，**不是**被写的资源。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return TYPE;
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
      Route route = new Route(waypoints, waypoints);
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.planRoute(snapshot.state(), id, route, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
