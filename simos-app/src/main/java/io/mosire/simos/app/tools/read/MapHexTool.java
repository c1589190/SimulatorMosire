package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.hex}（spec §7.1 读工具）：按坐标查单格内容 + 该格 facet 汇总。
 *
 * <p>★ **canonical 主体由本类用 Address AST 造**（T3 的硬接缝，spec §5.2 + 裁定 58）：facet 只服务 {@code
 * map:<mapId>:hex.<q>_<r>}，且 {@code QueryService.facets} 不改写转交的地址。
 */
public final class MapHexTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.hex";

  private final QueryService query;
  private final String mapId;

  public MapHexTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查单个六角格：地形 / 高度 + 该格的 facet 汇总（unitsHere / population）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    props.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    return ToolSupport.schema(props, List.of("q", "r"));
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
      int q = (int) ToolSupport.requiredLong(args, "q");
      int r = (int) ToolSupport.requiredLong(args, "r");
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      GameMap map = ToolSupport.gameMap(state);
      HexCoord coord = new HexCoord(q, r);
      HexCell cell = map.hexes().get(coord);
      if (cell == null) {
        return ToolResult.error("NOT_FOUND", "六角格不存在: " + q + "_" + r);
      }
      List<FacetEntry> facets = query.facets(ToolSupport.canonicalHex(mapId, q, r), target);
      Map<String, Object> view = ToolSupport.hexCoord(coord);
      view.put("terrain", map.terrainAt(coord));
      view.put("height", cell.height());
      view.put("facets", ToolSupport.facets(facets));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
