package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.state.resolve}（spec §7.1 读工具）：把地址文本解析成稳定实体候选（canonical 回显）。
 *
 * <p>经 {@link QueryService#resolve}，每次重放目标状态（spec §5.1）。坏地址 / 未注册命名空间**明确失败**（util 契约）， 折成 {@code
 * BAD_REQUEST}——不静默编造候选。
 */
public final class StateResolveTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.state.resolve";

  private final QueryService query;
  private final String mapId;

  public StateResolveTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "把地址文本（如 map:Map1:[1,1] / unit:u-1）解析为稳定实体：返回 canonicalAddress / typeName / id";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("address", ToolSupport.prop("string", "地址文本"));
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
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      QueryResult result = query.resolve(ToolSupport.requiredText(args, "address"), target);
      // ★ **筛掉不可见的主体**（T10）：**不是**回全量、也**不是**整调拒——解析得到的候选与"这个地址解析不出东西"
      //   必须分不开（否则"存在但你看不到"会从候选数里漏出去）。
      GameMap map = ToolSupport.gameMap(query.stateAt(target));
      List<Map<String, Object>> candidates = new ArrayList<>(result.candidates().size());
      for (ResolvedSubject subject : result.candidates()) {
        if (!ToolSupport.subjectVisible(context, mapId, map, subject)) {
          continue;
        }
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("namespace", subject.id().namespace());
        id.put("localId", subject.id().localId());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", id);
        candidate.put("canonicalAddress", subject.canonicalAddress());
        candidate.put("typeName", subject.typeName());
        candidates.add(candidate);
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("candidates", candidates);
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
