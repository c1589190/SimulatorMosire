package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
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
  private final RedactingQueryService redacting;
  private final String mapId;

  public MapOverviewTool(QueryService query, String mapId) {
    this.query = query;
    this.redacting = new RedactingQueryService(query);
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("actor", ToolSupport.prop("string", "决策人 id（给出则按该决策人的 viewScope 脱敏；缺省 = GM 全量）"));
    return ToolSupport.schema(props, List.of());
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
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      String actor = ToolSupport.optionalText(args, "actor", null);
      if (actor != null) {
        return ToolSupport.ok(redacting.mapOverview(new DecisionMakerId(actor), target, mapId));
      }
      SimulationState state = query.stateAt(target);
      GameMap map = ToolSupport.gameMap(state);
      return ToolSupport.ok(ToolSupport.mapOverview(mapId, map));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
