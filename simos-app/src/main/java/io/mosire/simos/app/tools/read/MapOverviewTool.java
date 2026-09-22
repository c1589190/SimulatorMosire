package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.NeighborNations;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.overview}（spec §7.1 读工具）：地图只读总览（格、区域、城市、地形类型）。
 *
 * <p>经 {@link QueryService#stateAt} 取目标状态 → map 切片；坐标一律 {@code {q,r}}。
 *
 * <p>★ **部分可见**（T10）：逐区域 / 逐格按**调用者现算的范围**筛，越界的不进结果——**整调拒**在这里是错的语义 （一个决策人只是看不见别国的区域，不是禁止它看地图）。
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      GameMap map = ToolSupport.gameMap(state);
      // ★ **按调用者现算的可见性逐项筛**（T10）：身份与范围都从 ToolContext 来（不再有自报 `actor` 参数——
      //   那条路**可省略**、省略即拿全量，限制纯自愿，spec §1.3 第 1 条）。
      return ToolSupport.ok(
          ToolSupport.mapOverview(
              mapId,
              map,
              coord -> ToolSupport.hexVisible(context, mapId, map, coord),
              region -> ToolSupport.regionVisible(context, mapId, region),
              // ★ **邻国标识**（T11，spec §3.4：国家决策人的字段表里有它）：只有解得出"本国"时才给。
              //   军队决策人给空（它那一行要的是**逐格的归属国家**，由 map.hex 的 nation 给——两条信息不同源）；
              //   GM 也给空（它没有"本国"这个概念，而这一项在 spec 里就是**决策人视角**的字段）。
              DecisionCallerFactory.viewerNationOf(context, state)
                  .map(nation -> NeighborNations.of(map, nation))));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
