package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.PathFinder;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.map.path}（工具面 M4）：**寻路试算**（A\*），起点由服务端取。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/map/path}，MCP 侧只能靠"写一条 {@code unit.PlanRoute}
 * 试试看"——那**是写**（落 revision），试算本不该有副作用。
 *
 * <p>★ **起点由服务端取**（该单位在 head 时刻的 {@code effectivePosition}），调用方只给终点——与 GUI 同一口径
 * （前端不传起点，避免第二份真相）；寻路调 {@link PathFinder#findPath}（app 层不另写寻路）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：报告 §二-4 实测它是**对未见地形的推断**——反复试算可逐格还原 地形成本，等于一条**绕过 {@code
 * viewScope} 的测地形通道**。GM 本来就看得见全图，故对 GM 无风险；对决策人 则必须"必填 actor + 对未见格 fail-closed"，本批不做 ⇒ 先只给 GM。
 */
public final class MapPathTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.path";

  private final QueryService query;

  public MapPathTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "寻路试算（只读）：给单位 id 与终点格，返回 {reachable, path}；起点由服务端按该单位的有效位置取";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("unit", ToolSupport.prop("string", "单位 id（起点取它的有效位置）"));
    props.put("q", ToolSupport.prop("integer", "终点格 q"));
    props.put("r", ToolSupport.prop("integer", "终点格 r"));
    return ToolSupport.schema(props, List.of("unit", "q", "r"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String unitText = ToolSupport.requiredText(args, "unit");
      int q = Math.toIntExact(ToolSupport.requiredLong(args, "q"));
      int r = Math.toIntExact(ToolSupport.requiredLong(args, "r"));
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      UnitState units = ToolSupport.unitState(state);
      UnitId unitId = new UnitId(unitText);
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        return ToolResult.error("NOT_FOUND", "单位不存在: " + unitText);
      }
      Optional<HexCoord> start = units.effectivePosition(unitId, state.meta().timestamp());
      Optional<List<HexCoord>> path =
          start.isEmpty()
              ? Optional.empty()
              : PathFinder.findPath(
                  ToolSupport.gameMap(state),
                  start.get(),
                  new HexCoord(q, r),
                  unit,
                  TerrainMovementCost.INSTANCE);
      return ToolSupport.ok(ApiViews.pathResult(path.isPresent(), path.orElse(List.of())));
    } catch (ArithmeticException e) {
      return ToolResult.error("BAD_REQUEST", "q/r 超出 int 范围");
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
