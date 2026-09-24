package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.map.region}（工具面 M4）：**区域详情**（id / name / tag / hexCount / hexes / label / meta）。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/map/region/{id}}，MCP 侧没有——于是 Agent **看不到"一个 hex
 * 同时属于哪些区域"的另一半**（{@code OverviewTool} 只给区域条目，不给成员格）。
 *
 * <p>★ **形状与 GUI 同源**：视图直接走 {@link ApiViews#regionDetail(Region)}（GUI {@code regionReply} 用的就是
 * 它）——不是另写一份（那正是报告点名的"同一资源的两个形状"）。
 *
 * <p>★ **可见性**：走 {@link ToolSupport#regionVisible}（与 {@code unit.get} 同口径：不可见与不存在同款 {@code
 * NOT_FOUND}，不给存在性侧信道）。
 */
public final class MapRegionTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.region";

  private final QueryService query;
  private final String mapId;

  public MapRegionTool(QueryService query, String mapId) {
    this.query = query;
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("regionId", ToolSupport.prop("string", "区域 id"));
    return ToolSupport.schema(props, List.of("regionId"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String id = ToolSupport.requiredText(args, "regionId");
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      RegionId regionId = RegionId.parse(id);
      Region region = ApiViews.gameMap(state).regions().get(regionId);
      if (region == null || !ToolSupport.regionVisible(context, mapId, regionId)) {
        return ToolResult.error("NOT_FOUND", "区域不存在: " + id);
      }
      return ToolSupport.ok(ApiViews.regionDetail(region));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
