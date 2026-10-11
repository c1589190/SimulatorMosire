package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.tools.MapToolPerspective;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.overview}（spec §7.1 读工具）：地图只读总览（格、区域、城市、地形类型）。
 *
 * <p>经注入的 {@link ToolReadPort} 取目标状态 → map 切片；坐标一律 {@code {q,r}}。
 *
 * <p>★ **部分可见**（T10）：逐区域 / 逐格按**调用者现算的范围**筛，越界的不进结果——**整调拒**在这里是错的语义 （一个决策人只是看不见别国的区域，不是禁止它看地图）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （读口 + 视图口 + 视角口）；形状、文案、拒绝码、资源声明逐字不变。
 */
public final class MapOverviewTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.overview";

  private final ToolReadPort reads;
  private final MapToolViews views;
  private final MapToolPerspective perspective;
  private final String mapId;

  public MapOverviewTool(
      ToolReadPort reads, MapToolViews views, MapToolPerspective perspective, String mapId) {
    this.reads = reads;
    this.views = views;
    this.perspective = perspective;
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      SimulationState state = MapToolReads.state(reads, args);
      GameMap map = MapToolReads.gameMap(state);
      // ★ **按调用者现算的可见性逐项筛**（T10）：身份与范围都从 ToolContext 来（不再有自报 `actor` 参数——
      //   那条路**可省略**、省略即拿全量，限制纯自愿，spec §1.3 第 1 条）。
      return ToolResults.ok(
          views.overview(
              mapId,
              map,
              coord -> perspective.hexVisible(context, mapId, map, coord),
              region -> perspective.regionVisible(context, mapId, region),
              // ★ **邻国标识**（T11，spec §3.4：国家决策人的字段表里有它）：只有解得出"本国"时才给。
              //   军队决策人给空（它那一行要的是**逐格的归属国家**，由 map.hex 的 nation 给——两条信息不同源）；
              //   GM 也给空（它没有"本国"这个概念，而这一项在 spec 里就是**决策人视角**的字段）。
              perspective.viewerNeighbors(context, state, map)));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
