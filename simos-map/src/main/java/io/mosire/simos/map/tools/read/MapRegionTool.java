package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.tools.MapToolPerspective;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.map.region}（工具面 M4）：**区域详情**（id / name / tag / hexCount / hexes / label / meta）。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/map/region/{id}}，MCP 侧没有——于是 Agent **看不到"一个 hex
 * 同时属于哪些区域"的另一半**（{@code OverviewTool} 只给区域条目，不给成员格）。
 *
 * <p>★ **形状与 GUI 同源**：视图直接走注入的 {@link MapToolViews#regionDetail}（GUI {@code regionReply} 用的就是同一份
 * view 函数的转调）——不是另写一份（那正是报告点名的"同一资源的两个形状"）。
 *
 * <p>★ **可见性**：走 {@link MapToolPerspective#regionVisible}（与 {@code unit.get} 同口径：不可见与不存在同款 {@code
 * NOT_FOUND}，不给存在性侧信道）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （读口 + 视图口 + 视角口）；形状、文案、拒绝码、资源声明逐字不变。
 */
public final class MapRegionTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.region";

  private final ToolReadPort reads;
  private final MapToolViews views;
  private final MapToolPerspective perspective;
  private final String mapId;

  public MapRegionTool(
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
    return "区域详情：id / name / tag / hexCount / hexes（成员格）/ label（质心）/ meta";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("regionId", ToolSchema.prop("string", "区域 id"));
    return ToolSchema.schema(props, List.of("regionId"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String id = ToolSchema.requiredText(args, "regionId");
      SimulationState state = MapToolReads.state(reads, args);
      RegionId regionId = RegionId.parse(id);
      Region region = MapToolReads.gameMapForView(state).regions().get(regionId);
      if (region == null || !perspective.regionVisible(context, mapId, regionId)) {
        return ToolResult.error("NOT_FOUND", "区域不存在: " + id);
      }
      return ToolResults.ok(views.regionDetail(region));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
