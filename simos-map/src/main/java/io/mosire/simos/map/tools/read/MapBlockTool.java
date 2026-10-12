package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.map.block}（工具面补齐，2026-09-25）：**某格所在的地形块**（整块成员格 + 大小）。
 *
 * <p>补的是 GUI 读口 {@code GET /api/map/block}（{@code GuiServer:573} 分支、{@code GuiServer:860} {@code
 * mapBlockReply}）在 MCP 侧的对应工具。它是**独立于** {@code simos.map.hex}（单格）与 {@code
 * simos.map.overview}（全块多边形）的第三种形状：后两者都没有"这一整块连通同地形由哪些格组成"—— 而块成员格正是"一次把整块换成另一种地形"（油漆桶）要的输入。
 *
 * <p>★ **形状与 GUI 同源**：直接走注入的 {@link MapToolViews#mapBlock}（GUI {@code mapBlockReply} 用的是同一份 view
 * 函数的转调）。不在图上的格 ⇒ {@code NOT_FOUND}（与 GUI 的 404 同口径，不区分"图外"与"不存在"）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：GUI 该端点**显式拒 {@code as=}**（{@code GuiServer:575}）——
 * 它是地图结构（GM 编辑动作的取块口），没有视角 redaction 语义；与同为地形探测的 {@code simos.map.path} 同档，
 * 不四桶共享（否则等于给决策人一条绕过可见范围的地形结构通道）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （{@link ToolReadPort} 读口 + {@link MapToolViews} 视图口）；形状、文案、拒绝码、资源声明逐字不变。
 */
public final class MapBlockTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.block";

  private final ToolReadPort reads;
  private final MapToolViews views;

  public MapBlockTool(ToolReadPort reads, MapToolViews views) {
    this.reads = reads;
    this.views = views;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "某格所在的地形块：{q,r,terrain,hexCount,hexes:[{q,r}…]}（整块连通同地形，供整块改写）";
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
      GameMap map = MapToolReads.gameMapForView(state);
      HexCoord coord = new HexCoord(q, r);
      if (!map.hexes().containsKey(coord)) {
        // ★ 坐标串取 HexCoord.toString()（L6：坐标串只有一份实现），文案逐字不变。
        return ToolResult.error("NOT_FOUND", "六角格不存在: " + coord);
      }
      return ToolResults.ok(
          views.mapBlock(coord, map.terrainAt(coord), map.terrainBlockHexes(coord)));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
