package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
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
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.sd.directives}（P7c，2026-10-01 后端 + MCP 稳定化计划）：**决策记录 GM-only MCP 读口**。
 *
 * <p>补的是 GUI 已有 {@code GET /api/sd/directives[?decisionMakerId=<id>]}、MCP 侧没有的缺口：Agent 能写 {@code
 * ga.IssueDirective}，却看不到"这个决策人到底下了什么令"（目标、结构化命令、执行原文、裁决状态本来就在 {@code SdState.directives()}
 * 里，缺的只是读口）。
 *
 * <p>★ **形状与 GUI 完全同形**：数据源与投影都只走 GUI 同一对函数——{@code SdQueryService.listDirectives}（全量）/ {@code
 * SdQueryService.listDirectives(id, target)}（单人）+ {@code ApiViews.directives(List)}。 本类只装配 {@code
 * {"directives":[…]}}，**不重排、不增减字段**：同状态两次调用逐字节一致（顺序由 service 的 tick 降序 + id 字典序决定）。
 *
 * <p>★ **只在 GM 桶**（{@link GmOnlyRead}）：记录含 target 规范地址、意图原文、结构化命令清单与裁决 verdict/status，
 * 语义上就是"读别人的底牌"（与 {@link DecisionMakersTool} 同档）；决策人桶只应看到自己的 {@code sd.DecisionResults}/{@code
 * sd.DecisionDocs}。
 *
 * <p>★ **未知 {@code decisionMakerId} ⇒ {@code NOT_FOUND}**（不是空列表）：空列表只表示"这个人还没出过令"，折成同一个
 * 响应会把"没查到"伪装成"不存在"（与 GUI 404、{@link DecisionMakerTool} 同口径）。不筛时是**集合**语义，空库同样是 {@code
 * {"directives":[]}}。
 */
public final class SdDirectivesTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.directives";

  /** 本工具的资源声明：sd 只读（与 {@code DecisionDocsTool} 的 sd 只读同口径）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final GaToolQueries queries;

  public SdDirectivesTool(GaToolQueries queries) {
    this.queries = queries;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策记录清单（GM 专用，与 GUI /api/sd/directives 同形）："
        + "{directives:[{directiveId,decisionMakerId,tick,target,intentInfoKey,intentInfo,commands,effects,"
        + "verdict,status}]}；可选 decisionMakerId 只看该决策人（未知 ⇒ NOT_FOUND），不筛 = 全部"
        + "（空库 = 空列表）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("decisionMakerId", ToolSchema.prop("string", "只看该决策人的记录（缺省 = 全部）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      // 给了就必须是非空文本（空串 ⇒ BAD_REQUEST，不静默当成"没给"）。
      String raw = ToolSchema.optionalText(args, "decisionMakerId", null);
      Optional<List<Map<String, Object>>> infos =
          queries.directives(raw, GaToolReads.branch(args), GaToolReads.revision(args));
      if (infos.isEmpty()) {
        return ToolResult.error("NOT_FOUND", "决策人不存在: " + raw);
      }
      Map<String, Object> view = new LinkedHashMap<>();
      // ★ 不重排：形状与顺序原样来自查询口（ApiViews.directives 逐条转调 ApiViews.directive）。
      view.put("directives", infos.get());
      return ToolResults.ok(view);
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
