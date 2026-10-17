package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolQueries;
import io.mosire.simos.ga.tools.GaToolReads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.ga.decision-makers}（工具面 M4）：**决策人清单**。
 *
 * <p>补的是 M4 侦察报告里的一条完全缺口：GUI 有 {@code GET /api/ga/decision-makers}，MCP 侧没有 ⇒ Agent
 * 连"这个世界里有几个决策人、各挂在哪支军队上"都问不出来（{@code ga.CreateDecisionMaker} 只能写、写完看不见）。
 *
 * <p>★ **形状与 GUI 同源**：{@link ApiViews#decisionMakers(List)}（GUI {@code decisionMakersReply} 用同一份）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：报告 §二-2 实测该视图含 {@code allowedTools}（**安全白名单本身**）、 {@code
 * providerId}/{@code viewScope}（**对手的感知配置**）、{@code rootUnit}（**对手的编制根**）—— 语义上就是"读别人的底牌"。GM
 * 全权可读；决策人桶不开。
 */
public final class DecisionMakersTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.ga.decision-makers";

  private final GaToolQueries queries;

  public DecisionMakersTool(GaToolQueries queries) {
    this.queries = queries;
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("affiliation", ToolSchema.prop("string", "归属过滤（缺省全部）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String filterRaw = ToolSchema.optionalText(args, "affiliation", null);
      List<Map<String, Object>> makers =
          queries.decisionMakers(filterRaw, GaToolReads.branch(args), GaToolReads.revision(args));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("decisionMakers", makers);
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
