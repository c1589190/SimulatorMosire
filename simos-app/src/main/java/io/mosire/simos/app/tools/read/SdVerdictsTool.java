package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.verdicts}（工具面补齐，2026-09-25）：**判决只读面**。
 *
 * <p>补的是 GUI 读口 {@code GET /api/sd/verdicts[?as=&lt;dmId&gt;]}（{@code GuiServer:285} 常量、{@code
 * GuiServer:612} 分支）在 MCP 侧的对应工具。数据源是 {@code SdState.verdicts}（{@code Verdict}：id / breakpoint /
 * subject / atRevision；FULL 披露下另含模型原始输出 {@code payload} 与 {@code meta}）。
 *
 * <p>★★ **它与 {@code sd.DecisionResults} 不是同一份数据**（所以不能并入）：{@code sd.DecisionResults}（{@link
 * DecisionResultsTool}）读的是 sd INFO 覆盖层里 {@code sd:adjudication.<tick>} 的**裁决结局摘要**（按 tags 归给决策人），
 * 而本工具读的是 {@code Verdict} 实体本身——**模型冻结下来的原始输出 + meta**。两者不可互相推导。
 *
 * <p>★★ **省略 {@code actor} = FULL 全量披露**（与 GUI 同口径：{@code GuiServer:612-616} 无 {@code as=} 走 {@code
 * RedactingQueryService.verdicts(target)}，后者写死 {@code DisclosurePolicy.FULL}，把 {@code payload} 与
 * {@code meta{model,promptVersion,inputBriefDigest}} 一并发出）。**这正是本工具只给 GM 桶的理由**：模型原始输出与
 * provider/提示版本属于**内部观测面**，GM 面本就全权（{@code gui} 的 GM 调试视图同款）；决策人与外部 Agent 不该读别人的 模型输出。带 {@code
 * actor} ⇒ 走 {@code verdicts(actor, target)} 的 redaction（按该决策人的 adjudication 披露策略裁剪字段），语义与 GUI 的
 * {@code as=} 完全一致——★ **不把 {@code as=} 当成可省略的装饰**：给了就真裁剪。
 */
public final class SdVerdictsTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.verdicts";

  private final RedactingQueryService redacting;

  public SdVerdictsTool(QueryService query, String mapId) {
    this.redacting = new RedactingQueryService(query, DecisionScopeFunctions.defaults(), mapId);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "判决清单：{verdicts:[{id,breakpoint,subject,atRevision[,payload,meta]}]}"
        + "（省略 actor = FULL 全量；给 actor = 按该决策人的披露策略裁剪）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("actor", ToolSupport.prop("string", "决策人 id（省略 = FULL 全量披露，仅 GM 面；给了 = 按该视角裁剪）"));
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
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      String actor = ToolSupport.optionalText(args, "actor", null);
      List<Map<String, Object>> views =
          actor == null || actor.isBlank()
              ? redacting.verdicts(target)
              : redacting.verdicts(new DecisionMakerId(actor), target);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("verdicts", views);
      return ToolSupport.ok(body);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
