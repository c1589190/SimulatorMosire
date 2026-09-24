package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.decision-makers}（工具面 M4）：**决策人清单**。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/sd/decision-makers}，MCP 侧没有 ⇒ Agent
 * 连"这个世界里有几个决策人、各挂在哪支军队上"都问不出来（{@code sd.CreateDecisionMaker} 只能写、写完看不见）。
 *
 * <p>★ **形状与 GUI 同源**：{@link ApiViews#decisionMakers(List)}（GUI {@code decisionMakersReply} 用同一份）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：报告 §二-2 实测该视图含 {@code allowedTools}（**安全白名单本身**）、 {@code
 * providerId}/{@code viewScope}（**对手的感知配置**）、{@code rootUnit}（**对手的编制根**）—— 语义上就是"读别人的底牌"。GM
 * 全权可读；决策人桶不开。
 */
public final class DecisionMakersTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.decision-makers";

  private final SdQueryService sd;

  public DecisionMakersTool(QueryService query) {
    this.sd = new SdQueryService(query);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人清单：{decisionMakers:[{id,affiliation,rootUnit,allowedTools,providerId,…}]}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("affiliation", ToolSupport.prop("string", "归属过滤（缺省全部）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      SdQueryService.AffiliationFilter filter =
          SdQueryService.parseFilter(ToolSupport.optionalText(args, "affiliation", null));
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      List<SdQueryService.DecisionMakerInfo> makers = sd.listDecisionMakers(filter, target);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("decisionMakers", ApiViews.decisionMakers(makers));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
