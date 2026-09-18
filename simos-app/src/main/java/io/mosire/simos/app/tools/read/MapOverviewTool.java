package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.overview}（spec §7.1 读工具）：地图只读总览（格、区域、城市、地形类型）。
 *
 * <p>经 {@link QueryService#stateAt} 取目标状态 → map 切片；坐标一律 {@code {q,r}}。
 */
public final class MapOverviewTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.overview";

  private final QueryService query;
  private final String mapId;

  public MapOverviewTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "地图只读总览：mapId / hexCount / hexes / regions / cities / terrainTypes";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(ToolSupport.targetProps(), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireMapRead(context, mapId);
      Map<String, Object> args = context.arguments();
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      GameMap map = ToolSupport.gameMap(state);
      return ToolSupport.ok(ToolSupport.mapOverview(mapId, map));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
