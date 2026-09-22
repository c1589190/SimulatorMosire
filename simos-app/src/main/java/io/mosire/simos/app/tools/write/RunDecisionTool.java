package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.agentlib.tool.ToolResultTruncator;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.spi.RunDecisionHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.RunDecision} 窄工具（T11C）：**让某个决策人的 agent 真跑一轮**的触发入口，**只在 GM 桶**（= 运行时 MCP 口）。
 *
 * <p>★ **它与 {@code sd.StartDecision} 的语义不同**：后者是"开始一次**判决**"（结果折成裁决者冻结的 {@code
 * sd.SubmitVerdict}）；本工具是"让某决策人**自己调工具**跑一轮"（结果折成它自己的读工具调用 + {@code sd.IssueDirective}）。
 * 两者都落一条**触发事实**、都不占 R4 的名额。
 *
 * <p>★★ **两步走，且顺序有意义**：
 *
 * <ol>
 *   <li>**先落触发事实**（{@code sd.RunDecision} 命令，经 {@link AbstractNarrowWriteTool#submit}）——铁律 2 无例外：
 *       世界上的每次改动都走 {@code Command → ChangeSet → Revision}，连"谁让谁跑了一轮"也不例外（AAR 要看得见）；
 *   <li>**再跑一轮**（{@link DecisionAgentService#runRound}）——世界版本取**刚落盘的新 head**，不是调用方手里那份：
 *       触发事实与这一轮看到的世界因此同源，不会出现"在旧世界上跑、却用新 revision 出令"。**落盘失败（冲突/被拒）就直接返回、不跑** ——不浪费一次真 LLM
 *       调用，也不让决策人读一份过期的世界。
 * </ol>
 *
 * <p>★ **轨迹是给"触发者"看的**（本工具的返回值）：模型依次调了哪些工具、每次**结果摘要**、最后一句文本、用了几轮、是否因预算中止。
 * 摘要是**回灌给模型的那段文本的同源副本**（{@code ToolInvocation#resultSummary}）——这才是"决策人报告它能看见了什么"的载体， 而不是事后从会话里猜。
 *
 * <p>★ **超长内容按既有惯例截断**（{@link ToolResultTruncator}，AgentLib 的那一份，本仓不自造阈值）：真档上 {@code
 * simos.map.overview} 一次就可能几十 KB，轨迹里原样塞回去会让触发者拿到一条比整个世界还大的结果。
 *
 * <p>★ **决策本身不经本工具落盘**：它仍走运行流内部的 {@code sd.IssueDirective} / {@code sd.SubmitVerdict}（铁律 2，
 * 运行流已如此）——本工具只多落那**一条触发事实**。
 */
public final class RunDecisionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.RunDecision";

  /** 每条轨迹摘要的上限（沿用 AgentLib 的截断惯例；截断点由它给，本类不另立阈值）。 */
  private static final int SUMMARY_MAX_CHARS = ToolResultTruncator.DEFAULT_MAX_CHARS;

  /** 本工具只碰 sd 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool}。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final DecisionAgentService decisionAgent;

  public RunDecisionTool(
      CoreSimos core, String initiator, String mapId, DecisionAgentService decisionAgent) {
    super(core, initiator, mapId);
    this.decisionAgent = Objects.requireNonNull(decisionAgent, "decisionAgent");
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  /** 审批摘要（**不解析载荷**：`summary` 由 `gate()` 在**校验之前**调用，解析失败会从审批链里逃出去）。 */
  @Override
  protected String summary(Map<String, Object> args) {
    return "让决策人的 agent 跑一轮 payload="
        + args.get("payloadJson")
        + " branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 触发：固定 sd.RunDecision，载荷 {decisionMakerId}——先落一条触发事实（revision），"
        + "再让该决策人的 agent 真跑一轮（真 LLM 自行调工具读世界、出令），返回本轮轨迹"
        + "（finalText / toolCalls[工具名+结果摘要] / llmCalls / abortedByBudget）";
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 触发事实写的是**该决策人**的决策域（{@code sd:decision-maker/<自己>}；GM 身份回落成整个决策人集合）。 */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }

  @Override
  protected ToolResult afterSubmit(ToolContext context, SubmittedCommand submitted) {
    if (!(submitted.result() instanceof CommandResult.Committed committed)) {
      // ★ 触发事实没落盘 ⇒ **不跑**（见类注 第 2 条）：把真实结局（CONFLICT/REJECTED）如实交回去。
      return super.afterSubmit(context, submitted);
    }
    // ★ 载荷字段名的拼写点在 sd 那边（RunDecisionHandler.decisionMakerIdOf）——命令认得它，运行流也认得同一个它。
    DecisionMakerId decisionMakerId =
        RunDecisionHandler.decisionMakerIdOf(
            ToolSupport.optionalText(context.arguments(), "payloadJson", "{}"));
    String conversationId = DecisionAgentService.conversationIdOf(decisionMakerId);
    try {
      DecisionAgentRunner.DecisionTurn turn =
          decisionAgent.runRound(
              committed.ref().branch(), committed.ref().revision(), decisionMakerId);
      return ToolResult.ok(
          ToolSupport.json(completedView(submitted, committed, decisionMakerId, turn)));
    } catch (DecisionAgentRunner.TurnBudgetExceeded e) {
      // ★ 中止**不是失败到没有信息**：触发事实已落盘、这一轮的每条消息也都在会话里（运行流的保证）⇒ 如实报"因预算中止"
      //   并给出会话 id（下一 tick 从这段会话续）。轨迹本身拿不到（运行流在抛出时才中止，不返回半份账）。
      Map<String, Object> view = baseView("aborted", committed, decisionMakerId, conversationId);
      view.put("reason", "turn-budget");
      view.put("llmCalls", e.llmCalls());
      view.put("abortedByBudget", true);
      view.put("detail", e.getMessage());
      return ToolResult.error("TOOL_ERROR", ToolSupport.json(view));
    } catch (RuntimeException e) {
      // ★ 未绑定 provider（fail-closed）/ 路由坏掉 / 决策人查无：**如实报**，绝不静默当作"跑过了"。
      Map<String, Object> view = baseView("failed", committed, decisionMakerId, conversationId);
      view.put("reason", e.getClass().getSimpleName());
      view.put("abortedByBudget", false);
      view.put("detail", e.getMessage());
      return ToolResult.error("TOOL_ERROR", ToolSupport.json(view));
    }
  }

  /** 跑完一轮的视图：提交结局（与别的窄工具同形）+ 轨迹。 */
  private static Map<String, Object> completedView(
      SubmittedCommand submitted,
      CommandResult.Committed committed,
      DecisionMakerId decisionMakerId,
      DecisionAgentRunner.DecisionTurn turn) {
    Map<String, Object> view =
        new LinkedHashMap<>(
            ToolSupport.committedView(
                committed.ref(), submitted.commandId(), submitted.commandId()));
    traceFields(view, decisionMakerId, turn.llmCalls(), turn.conversationId());
    view.put("finalText", turn.finalText().orElse(null));
    view.put("toolCalls", toolCallsView(turn.toolInvocations()));
    // ★ 与中止路径**同一字段名**（"不省略字段、调用方一次判空即可"，与 ApiViews 同口径）：这一条恒为假，它是判别位不是结论。
    view.put("abortedByBudget", false);
    return view;
  }

  /** 没跑成（中止 / 失败）的视图：触发事实的坐标 + 原因 + 会话 id（历史已落盘，下一轮可续）。 */
  private static Map<String, Object> baseView(
      String result,
      CommandResult.Committed committed,
      DecisionMakerId decisionMakerId,
      String conversationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", result);
    view.put("trigger", ToolSupport.stateRef(committed.ref()));
    traceFields(view, decisionMakerId, 0, conversationId);
    return view;
  }

  private static void traceFields(
      Map<String, Object> view,
      DecisionMakerId decisionMakerId,
      int llmCalls,
      String conversationId) {
    view.put("decisionMakerId", decisionMakerId.value());
    view.put("conversationId", conversationId);
    view.put("llmCalls", llmCalls);
  }

  /** 轨迹：每次工具调用一行（工具名 + 成败 + 码 + **结果摘要**，摘要按既有惯例截断）。 */
  private static List<Map<String, Object>> toolCallsView(
      List<DecisionAgentRunner.ToolInvocation> invocations) {
    List<Map<String, Object>> out = new ArrayList<>(invocations.size());
    for (DecisionAgentRunner.ToolInvocation invocation : invocations) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tool", invocation.toolName());
      row.put("ok", invocation.success());
      row.put("code", invocation.code());
      row.put(
          "summary", ToolResultTruncator.truncate(invocation.resultSummary(), SUMMARY_MAX_CHARS));
      out.add(row);
    }
    return out;
  }
}
