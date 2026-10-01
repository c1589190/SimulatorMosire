package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.state.facets}（spec §7.1 读工具）：某地址上的全部 facet 视图（注册序）。
 *
 * <p>★ **调用方须传 canonical 主体**（T3 的硬接缝，spec §5.2 + 裁定 58）：{@code QueryService.facets}
 * **不改写**转交的地址，Index/Human 形（{@code map:Map1:[1,1]}）对 facet 来说等于"没有内容"。要按坐标查 facet 请用 {@code
 * simos.map.hex}（它自己造 canonical），或先用 {@code simos.state.resolve} 拿 canonical 地址。
 */
public final class StateFacetsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.state.facets";

  private final QueryService query;
  private final String mapId;

  public StateFacetsTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某 canonical 地址上的全部 facet（unitsHere / population 等）；非 canonical 地址返回空列表";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("address", ToolSupport.prop("string", "canonical 地址文本（如 map:Map1:hex.1_1）"));
    return ToolSupport.schema(props, List.of("address"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String address = ToolSupport.requiredText(args, "address");
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      Map<String, Object> view = new LinkedHashMap<>();
      // ★ **不可见的主体 ⇒ 空**（T10）：与"非 canonical 地址"（本就返回空列表）**同款**——
      //   调用方分不开"这个地址没内容"和"这段内容你看不到"，而全量返回正是要消灭的那条路。
      if (!anyCandidateVisible(context, address, target)) {
        view.put("entries", List.of());
        return ToolSupport.ok(view);
      }
      List<FacetEntry> entries = query.facets(address, target);
      view.put("entries", ToolSupport.facets(entries));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** facet 的可见性按**地址解析出的主体**判（facet 自己挂在那个主体上，资源也就随它）。 */
  private boolean anyCandidateVisible(ToolContext context, String address, QueryTarget target) {
    SimulationState state = query.stateAt(target);
    GameMap map = ToolSupport.gameMap(state);
    for (ResolvedSubject subject : query.resolve(address, target).candidates()) {
      if (ToolSupport.subjectVisible(context, mapId, map, state, subject)) {
        return true;
      }
    }
    return false;
  }
}
