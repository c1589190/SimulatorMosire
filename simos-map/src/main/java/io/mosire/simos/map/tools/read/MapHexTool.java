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
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.tools.MapToolPerspective;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.hex}（spec §7.1 读工具）：按坐标查单格内容 + 该格 facet 汇总。
 *
 * <p>★ **canonical 主体由本类用 Address AST 造**（T3 的硬接缝，spec §5.2 + 裁定 58）：facet 只服务 {@code
 * map:<mapId>:hex.<q>_<r>}，且查询门面不改写转交的地址。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （{@link ToolReadPort} 读口 + {@link MapToolViews} 视图口 + {@link MapToolPerspective} 视角口——
 * 可见性判定与"格归谁"的派生事实各有唯一实现）；载荷、文案、拒绝码、资源声明逐字不变。
 */
public final class MapHexTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.hex";

  private final ToolReadPort reads;
  private final MapToolViews views;
  private final MapToolPerspective perspective;
  private final String mapId;

  public MapHexTool(
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
    return "查单个六角格：地形 / 高度 + 该格的 facet 汇总（unitsHere / population）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("q", ToolSchema.prop("integer", "六角列坐标 q"));
    props.put("r", ToolSchema.prop("integer", "六角行坐标 r"));
    return ToolSchema.schema(props, List.of("q", "r"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      int q = (int) ToolSchema.requiredLong(args, "q");
      int r = (int) ToolSchema.requiredLong(args, "r");
      SimulationState state = MapToolReads.state(reads, args);
      GameMap map = MapToolReads.gameMap(state);
      HexCoord coord = new HexCoord(q, r);
      HexCell cell = map.hexes().get(coord);
      // ★ **不可见与不存在必须长得一模一样**（T10）：两条都走这一支，拒因**逐字相同**——
      //   否则"这个格存在但你看不到"这件事会从拒因里漏出去。
      if (cell == null || !perspective.hexVisible(context, mapId, map, coord)) {
        // ★ 坐标串取 HexCoord.toString()（L6：坐标串只有一份实现），文案逐字不变。
        return ToolResult.error("NOT_FOUND", "六角格不存在: " + coord);
      }
      List<FacetEntry> facets =
          reads.facets(
              MapToolReads.canonicalHex(mapId, q, r),
              MapToolReads.branch(args),
              MapToolReads.revision(args));
      Map<String, Object> view = views.hexCoord(coord);
      view.put("terrain", map.terrainAt(coord));
      view.put("height", cell.height());
      // ★ **归属国家**（T11，spec §3.4 的字段表：军队决策人「每个 hex 的归属国家」）。★ 值是**列表**：M8-U1
      //   裁定区域从属是多对多 ⇒ 一个格可以同时属于多个国家区域，不许读成"唯一归属"。
      // ★ 这一项**不做角色分派**：格已经过 hexVisible 判定（调用者够得着它），"它归谁"就是该格自己的事实；
      //   给国家决策人时结果必然是它自己（本国的格），给 GM 时它本就无限制 ⇒ 不构成越界。
      view.put("nation", new ArrayList<>(perspective.hexNations(map, coord)));
      view.put("facets", views.facets(facets));
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
