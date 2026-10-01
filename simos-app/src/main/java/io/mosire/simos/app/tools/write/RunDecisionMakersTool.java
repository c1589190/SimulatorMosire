package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.agentlib.tool.ToolResultTruncator;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * ★★ {@code simos.sd.run-decision-makers}（P7a，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 显式名单批量派决策人</b>
 * ——输入一份显式 {@code decisionMakerIds} 名单，先同批落 N 条 {@code sd.RunDecision} 触发事实（一批 = 一条
 * revision），再按名单顺序逐个让决策人的 agent 真跑一轮，返回逐人汇总。
 *
 * <p>★★ <b>不做自动筛选 / 自动派出</b>（用户 2026-10-01 裁定 8）：{@code due} 只在 preview 里作<b>只读展示</b>，
 * 明确不参与选择；本工具只按调用方显式给的名单行动。行政能力 / 军令门是已知缺口，本批不实现。
 *
 * <p>★★ <b>两步走，顺序与 {@link RunDecisionTool} 同源、语义一致</b>：
 *
 * <ol>
 *   <li><b>先落触发事实</b>：每个 DM 一条 {@code sd.RunDecision}（载荷 {@code {decisionMakerId}}），全部共享同一 {@code
 *       branch/expectedRevision/batchId}，经 {@link CoreSimos#submitBatch} 原子提交 —— 全部成功 ⇒ 一条
 *       revision； 批内任一被拒 ⇒ 整批零 revision、逐条报真拒因、<b>不跑任何 LLM</b>；冲突 ⇒ 报真实 head、同样不跑 LLM。
 *   <li><b>再逐个跑轮</b>：对批的新坐标 {@code ref}，按输入顺序调用 {@link DecisionAgentService#runRound(BranchId,
 *       RevisionId, DecisionMakerId)}。每个 DM 独立：一个失败（运行时异常 / provider 未绑定 /
 *       预算中止）如实记进该人的结果，不静默；{@code continueOnError=false} 时在第一个失败处停止， 并用 {@code stoppedAfter} 记下是谁。
 * </ol>
 *
 * <p>★★ <b>触发事实与运行轮分开记</b>：触发批只落 {@code sd.RunDecision}（每条 = 一条 {@code sd.InfoEntry}）；决策人这一轮的产出仍走
 * 运行流内部的既有链路（{@code sd.IssueDirective} 等，含人工审批）——本工具<b>不</b>在这里提交 {@code sd.IssueDirective}
 * 或任何其他命令。运行轮各自产生的世界写入各自带 revision，本工具不汇总 revision 数。
 *
 * <p>★★ <b>并发与幂等</b>：本工具不做跨调用锁、不做自动重试。GM 并发对同一 DM 触发两次时，触发命令的 sd 语义与 {@code submitBatch}
 * 的乐观并发各自决定结局（同 expectedRevision 只有一个批能提交；不同的 expected 由真实 head 判冲突），如实报告。
 *
 * <p>★ <b>资源声明</b>：本工具只写 {@code sd} 命名空间（{@link ResourcePolicy#UNRESTRICTED}；GM 侧 sd 为 unlimited）。
 *
 * <p>★ <b>工具名不是命令类型</b>：{@code simos.sd.run-decision-makers} 不进 catalog / {@code
 * PAYLOAD_HINTS}；它组的是既有 {@code sd.RunDecision} 命令。
 */
public final class RunDecisionMakersTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.sd.run-decision-makers";

  /** 批内固定命令类型：与 {@link RunDecisionTool} 用同一个拼写点（工具名 == 命令类型）。 */
  private static final String TRIGGER_TYPE = RunDecisionTool.NAME;

  /** 每条轨迹摘要的上限（沿用 AgentLib 的截断惯例；与 {@link RunDecisionTool} 同一份阈值）。 */
  private static final int SUMMARY_MAX_CHARS = ToolResultTruncator.DEFAULT_MAX_CHARS;

  /** 默认串行（= 既有行为）；并发只影响“跑轮”这一段的墙钟时间，不影响触发批与裁决语义。 */
  private static final int DEFAULT_CONCURRENCY = 1;

  /** 并发上限：再高只会把 provider 限流/会话库写锁变成重试，收益递减。 */
  private static final int MAX_CONCURRENCY = 16;

  /** 本工具只碰 sd 命名空间；GM 侧 sd 表态 unlimited。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  /** preview 里 {@code due} 的固定说明（只读展示位，不参与选择）。 */
  private static final String DUE_NOTE =
      "due 仅展示：本工具按调用方显式给的 decisionMakerIds 派发，不按 due 自动筛选/自动派出；" + "due 与前置行政能力/军令门均不作为本工具的选择条件";

  private final CoreSimos core;
  private final QueryService query;
  private final DecisionAgentService decisionAgent;
  private final String initiator;

  /**
   * @param core 唯一写入口（触发批走 {@code submitBatch}；preview=true 零写）
   * @param query 只读入口（preview 读 base state 校验决策人；状态取 branch/expectedRevision 或 head）
   * @param initiator 触发事实的发起者（{@code <kind>:<id>} 形态）
   * @param decisionAgent 决策人运行流入口（批提交成功后逐人真跑一轮）
   */
  public RunDecisionMakersTool(
      CoreSimos core, QueryService query, String initiator, DecisionAgentService decisionAgent) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.decisionAgent = Objects.requireNonNull(decisionAgent, "decisionAgent");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 显式名单批量派决策人（组合工具；不按 due 自动筛选）："
        + "decisionMakerIds(必填、非空、保序去重、每项非空白) + preview?(缺省 true) + continueOnError?(缺省 true) + "
        + "concurrency?(缺省 "
        + DEFAULT_CONCURRENCY
        + "，取值 1.."
        + MAX_CONCURRENCY
        + "；>1 时并发跑轮，仅支持 continueOnError=true) + branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + ") + expectedRevision?(preview=false 必填，>=0) + reason(必填非空白)。"
        + "preview=true：零 revision，读 base state 逐个校验存在，返回每人 id/exists/providerId/cadence/due(仅展示)/"
        + "allowedTools 概要 + commandsPreview(每人一条 sd.RunDecision)，缺失任意 DM ⇒ BAD_REQUEST 具名列表；"
        + "preview=false：先同批提交 N 条 sd.RunDecision 触发事实（批成功恰一条 revision；批拒/冲突均零 revision、"
        + "逐条真因、不跑 LLM），成功后按名单顺序（concurrency=1）或并发（concurrency>1）调用决策人运行流跑真轮，返回逐人 "
        + "{decisionMakerId,status,finalText,llmCalls,toolCalls,conversationId,...}；continueOnError=false 时首个失败后停止并给 "
        + "stoppedAfter。本工具不做跨调用锁、不做自动重试，也不在这里提交 sd.IssueDirective/其他命令"
        + "（决策人轮内的产出仍走既有链路与人工审批）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "decisionMakerIds", ToolSupport.prop("array", "显式决策人 id 数组（必填、非空；保序去重；每个元素必须是非空白字符串）"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只读校验与列命令，零 revision；false = 提交触发批并逐人跑轮"));
    props.put(
        "continueOnError",
        ToolSupport.prop("boolean", "true（缺省）= 某决策人失败后继续后续；false = 首个失败即停，记 stoppedAfter"));
    props.put(
        "concurrency",
        ToolSupport.prop(
            "integer",
            "跑轮并发度（缺省 "
                + DEFAULT_CONCURRENCY
                + "，取值 1.."
                + MAX_CONCURRENCY
                + "；>1 仅支持 continueOnError=true，已在跑的任务无法撤回）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：触发批的乐观并发 base revision（>=0）；preview 可给，缺省 = 该分支 head"));
    props.put("reason", ToolSupport.prop("string", "派发原因（必填非空白；写进工具结果与审批摘要，不写命令载荷）"));
    return ToolSupport.schema(props, List.of("decisionMakerIds", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发；与其余 GM 组合写工具同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "批量派决策人 decisionMakerIds="
            + args.get("decisionMakerIds")
            + " preview="
            + args.getOrDefault("preview", true)
            + " continueOnError="
            + args.getOrDefault("continueOnError", true)
            + " concurrency="
            + args.getOrDefault("concurrency", DEFAULT_CONCURRENCY)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " expectedRevision="
            + args.get("expectedRevision")
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      List<String> decisionMakerIds = requiredTextArray(args, "decisionMakerIds");
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      boolean continueOnError = ToolSupport.optionalBoolean(args, "continueOnError").orElse(true);
      Long concurrencyArgRaw = ToolSupport.optionalLong(args, "concurrency");
      long concurrencyArg =
          concurrencyArgRaw == null ? (long) DEFAULT_CONCURRENCY : concurrencyArgRaw;
      if (concurrencyArg < 1L || concurrencyArg > MAX_CONCURRENCY) {
        return ToolResult.error(
            "BAD_REQUEST", "concurrency 必须 ∈ [1," + MAX_CONCURRENCY + "]: " + concurrencyArg);
      }
      if (concurrencyArg > 1L && !continueOnError) {
        return ToolResult.error(
            "BAD_REQUEST", "concurrency>1 只支持 continueOnError=true（已在跑的任务无法按首败撤回）");
      }
      int concurrency = (int) concurrencyArg;
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（触发批的乐观并发 base revision）");
      }
      // apply 分支已保证非 null；显式三目兜底只是避免把可空 Long 直接拆箱（与既有组合工具同写法）。
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      return preview
          ? preview(
              branch, expectedRevisionArg, decisionMakerIds, reason, continueOnError, concurrency)
          : apply(branch, expectedRevision, decisionMakerIds, reason, continueOnError, concurrency);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因必须原样逃到 ToolCallAuthorizer 的边界，不能折成 TOOL_ERROR。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "决策人批量派发失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── preview：零 revision 的只读校验 + 命令预览 ─────────────────────────────────────

  /**
   * preview：读 base state（{@code branch} + {@code expectedRevision}，缺省取该分支 head），逐个校验 DM 存在， 返回每人详情与
   * commandsPreview。任一缺失 ⇒ {@code BAD_REQUEST}（具名列表），不生成也不提交任何命令。
   */
  private ToolResult preview(
      BranchId branch,
      Long expectedRevision,
      List<String> decisionMakerIds,
      String reason,
      boolean continueOnError,
      int concurrency) {
    QueryTarget target =
        expectedRevision == null
            ? QueryTarget.head(branch)
            : QueryTarget.at(branch, new RevisionId(expectedRevision));
    // ★ 一次读全量、按 id 索引：同一 base state 上算出的 due/allowedTools 不会因逐次查询而漂移。
    Map<String, SdQueryService.DecisionMakerInfo> byId = new LinkedHashMap<>();
    for (SdQueryService.DecisionMakerInfo info :
        new SdQueryService(query).listDecisionMakers(SdQueryService.ALL, target)) {
      byId.put(info.maker().id().value(), info);
    }
    List<Map<String, Object>> rows = new ArrayList<>(decisionMakerIds.size());
    List<String> missing = new ArrayList<>();
    for (String id : decisionMakerIds) {
      SdQueryService.DecisionMakerInfo info = byId.get(id);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("exists", info != null);
      if (info == null) {
        missing.add(id);
        // 显式 null：查无就是"不知道"，不拿空串/0/false 顶替。
        row.put("providerId", null);
        row.put("cadence", null);
        row.put("due", null);
        row.put("lastDirectiveTick", null);
        row.put("ticksSinceLast", null);
        row.put("allowedTools", List.of());
        row.put("allowedToolsCount", 0);
        row.put("allowedToolsMode", null);
      } else {
        DecisionMaker maker = info.maker();
        List<String> allowedTools = new ArrayList<>(maker.allowedTools());
        allowedTools.sort(Comparator.naturalOrder());
        SdQueryService.PendingSignal pending = info.pending();
        row.put("providerId", maker.providerId().orElse(null));
        row.put("cadence", maker.decisionCadenceTicks());
        row.put("due", pending.due());
        row.put("lastDirectiveTick", pending.lastDirectiveTick());
        row.put("ticksSinceLast", pending.ticksSinceLast());
        row.put("allowedTools", allowedTools);
        row.put("allowedToolsCount", allowedTools.size());
        // 空 = 沿用全局 WHITELIST；非空 = WHITELIST ∩ allowedTools（P6a 裁定）。
        row.put(
            "allowedToolsMode",
            maker.allowedTools().isEmpty() ? "global-whitelist" : "explicit-subset");
      }
      rows.add(row);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", true);
    view.put("submitted", false);
    view.put("triggerRef", null);
    view.put("decisionMakerIds", decisionMakerIds);
    view.put("decisionMakers", rows);
    view.put("selection", "explicit-decisionMakerIds");
    view.put("dueNote", DUE_NOTE);
    view.put("continueOnError", continueOnError);
    view.put("concurrency", concurrency);
    view.put("reason", reason);
    if (!missing.isEmpty()) {
      view.put("missingDecisionMakerIds", missing);
      view.put("results", List.of());
      view.put("note", "存在缺失的决策人，未生成/提交任何 sd.RunDecision 触发命令（零 revision）：" + missing);
      return ToolResult.error("BAD_REQUEST", ToolSupport.json(view));
    }
    view.put("commandsPreview", commandsPreview(decisionMakerIds));
    view.put("results", List.of());
    view.put(
        "note",
        "preview 只读：零 revision；apply 时这 "
            + decisionMakerIds.size()
            + " 条 sd.RunDecision 触发命令同批落一条 revision，随后按名单"
            + (concurrency <= 1 ? "顺序逐个" : ("并发（" + concurrency + "）"))
            + "跑轮");
    return ToolResult.ok(ToolSupport.json(view));
  }

  /** commandsPreview：每人一条 {@code sd.RunDecision}，载荷字段名与 handler 的拼写点一致。 */
  private static List<Map<String, Object>> commandsPreview(List<String> decisionMakerIds) {
    List<Map<String, Object>> out = new ArrayList<>(decisionMakerIds.size());
    for (String id : decisionMakerIds) {
      Map<String, Object> payload = triggerPayload(id);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", TRIGGER_TYPE);
      row.put("decisionMakerId", id);
      row.put("payload", payload);
      row.put("payloadJson", ToolSupport.json(payload));
      out.add(row);
    }
    return List.copyOf(out);
  }

  /** {@code sd.RunDecision} 的唯一载荷字段（与 {@code RunDecisionHandler.decisionMakerIdOf} 同拼写）。 */
  private static Map<String, Object> triggerPayload(String decisionMakerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("decisionMakerId", decisionMakerId);
    return payload;
  }

  // ── apply：触发批 → 逐人跑轮 ───────────────────────────────────────────────────────

  /** apply：先组批并原子提交触发事实；只有 committed 才继续逐人跑轮。 */
  private ToolResult apply(
      BranchId branch,
      long expectedRevision,
      List<String> decisionMakerIds,
      String reason,
      boolean continueOnError,
      int concurrency) {
    RevisionId expected = new RevisionId(expectedRevision);
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch = buildTriggerBatch(batchId, branch, expected, decisionMakerIds);
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Conflict conflict) {
      // ★ 冲突：批未提交、零 revision，不跑任何 LLM；报真实 head。
      return ToolResult.error(
          "CONFLICT",
          ToolSupport.json(conflictView(batchId, decisionMakerIds, reason, conflict.current())));
    }
    if (result instanceof BatchResult.Rejected rejected) {
      // ★ 整批拒：逐条真拒因（含"随整批复原"的那些），零 revision，不跑任何 LLM。
      return ToolResult.error(
          "REJECTED",
          ToolSupport.json(rejectedView(batchId, batch, decisionMakerIds, reason, rejected)));
    }
    BatchResult.Committed committed = (BatchResult.Committed) result;
    StateRef triggerRef = committed.ref();
    List<Map<String, Object>> results =
        runRounds(triggerRef, decisionMakerIds, continueOnError, concurrency);
    String stoppedAfter = stoppedAfter(results, decisionMakerIds, continueOnError);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", false);
    view.put("submitted", true);
    view.put("triggerRef", ToolSupport.stateRef(triggerRef));
    view.put("decisionMakerIds", decisionMakerIds);
    view.put("results", results);
    view.put("continueOnError", continueOnError);
    view.put("concurrency", concurrency);
    view.put("reason", reason);
    if (stoppedAfter != null) {
      view.put("stoppedAfter", stoppedAfter);
    }
    view.put("note", applyNote(decisionMakerIds.size(), results, stoppedAfter, concurrency));
    return ToolResult.ok(ToolSupport.json(view));
  }

  /** 组批：每人一条 {@code sd.RunDecision}，共享 batchId/branch/expectedRevision；命令 id 各自新生成。 */
  private List<CommandEnvelope> buildTriggerBatch(
      String batchId, BranchId branch, RevisionId expectedRevision, List<String> decisionMakerIds) {
    List<CommandEnvelope> batch = new ArrayList<>(decisionMakerIds.size());
    for (String id : decisionMakerIds) {
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              TRIGGER_TYPE,
              ToolSupport.json(triggerPayload(id))));
    }
    return List.copyOf(batch);
  }

  /** 冲突视图：真实 head 在 {@code submission.current}，不跑任何轮次。 */
  private static Map<String, Object> conflictView(
      String batchId, List<String> decisionMakerIds, String reason, StateRef current) {
    Map<String, Object> view =
        baseApplyFailureView(decisionMakerIds, reason, "触发批冲突：零 revision，未跑任何决策人轮次");
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "conflict");
    submission.put("current", ToolSupport.stateRef(current));
    submission.put("batchId", batchId);
    submission.put("correlationId", batchId);
    view.put("submission", submission);
    return view;
  }

  /** 批拒视图：逐条真因（outcome 与 batch 逐位对应），不跑任何轮次。 */
  private static Map<String, Object> rejectedView(
      String batchId,
      List<CommandEnvelope> batch,
      List<String> decisionMakerIds,
      String reason,
      BatchResult.Rejected rejected) {
    Map<String, Object> view =
        baseApplyFailureView(decisionMakerIds, reason, "触发批被拒：整批零 revision，未跑任何决策人轮次");
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("batchId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>(batch.size());
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < batch.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      row.put("decisionMakerId", decisionMakerIds.get(i));
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    view.put("submission", submission);
    return view;
  }

  /**
   * apply 的失败视图（批被拒 / 冲突）：{@code submitted=true} 沿用既有组合工具的口径（这次调用走的是 apply 路径、已尝试提交）， 是否真落盘看 {@code
   * triggerRef}/{@code submission}；本视图 {@code triggerRef=null}、{@code results=[]}（零 revision、未跑
   * LLM）。
   */
  private static Map<String, Object> baseApplyFailureView(
      List<String> decisionMakerIds, String reason, String note) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", false);
    view.put("submitted", true);
    view.put("triggerRef", null);
    view.put("decisionMakerIds", decisionMakerIds);
    view.put("results", List.of());
    view.put("reason", reason);
    view.put("note", note);
    return view;
  }

  /**
   * 跑轮：{@code concurrency == 1} 时按名单顺序逐个跑（continueOnError=false 首败即停，既有行为逐字不变）； {@code concurrency
   * > 1} 时用固定线程池并发跑到每个人，结果仍按名单顺序回收。
   *
   * <p>★ 并发只发生在这一层：每个 DM 的 {@link DecisionAgentService#runRound} 各自 {@code core.replay}、各自会话
   * id、各自范围； 触发批已在前面同一条 revision 落盘。会话库自身有锁，revision 冲突由决策人运行流的重读逻辑承担，工具不做自动重试。
   */
  private List<Map<String, Object>> runRounds(
      StateRef triggerRef,
      List<String> decisionMakerIds,
      boolean continueOnError,
      int concurrency) {
    if (concurrency <= 1 || decisionMakerIds.size() <= 1) {
      return runRoundsSequential(triggerRef, decisionMakerIds, continueOnError);
    }
    return runRoundsConcurrent(triggerRef, decisionMakerIds, concurrency);
  }

  /** 串行跑轮（既有行为）。 */
  private List<Map<String, Object>> runRoundsSequential(
      StateRef triggerRef, List<String> decisionMakerIds, boolean continueOnError) {
    List<Map<String, Object>> results = new ArrayList<>(decisionMakerIds.size());
    for (String id : decisionMakerIds) {
      Map<String, Object> row = runOne(triggerRef, id);
      results.add(row);
      if (!continueOnError && !"ok".equals(row.get("status"))) {
        break;
      }
    }
    return List.copyOf(results);
  }

  /** 并发跑轮：固定线程池 + 按名单顺序回收；每个 DM 一个独立任务，失败/中止照常落成逐人结果。 */
  private List<Map<String, Object>> runRoundsConcurrent(
      StateRef triggerRef, List<String> decisionMakerIds, int concurrency) {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "run-decision-makers-worker");
          thread.setDaemon(true);
          return thread;
        };
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(concurrency, decisionMakerIds.size()), factory);
    try {
      List<Future<Map<String, Object>>> futures = new ArrayList<>(decisionMakerIds.size());
      for (String id : decisionMakerIds) {
        futures.add(pool.submit(() -> runOne(triggerRef, id)));
      }
      List<Map<String, Object>> results = new ArrayList<>(decisionMakerIds.size());
      for (int i = 0; i < futures.size(); i++) {
        String id = decisionMakerIds.get(i);
        try {
          results.add(futures.get(i).get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          results.add(
              failureView(
                  triggerRef, id, new IllegalStateException("并发等待被中断: " + e.getMessage(), e)));
        } catch (ExecutionException e) {
          Throwable cause = e.getCause() == null ? e : e.getCause();
          results.add(
              failureView(
                  triggerRef,
                  id,
                  new IllegalStateException("并发任务异常: " + cause.getMessage(), cause)));
        }
      }
      return List.copyOf(results);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 单个人跑一轮：成功/预算中止/运行失败都折成同构的逐人结果（与串行路径同一份视图）。 */
  private Map<String, Object> runOne(StateRef triggerRef, String id) {
    try {
      DecisionAgentRunner.DecisionTurn turn =
          decisionAgent.runRound(
              triggerRef.branch(), triggerRef.revision(), new DecisionMakerId(id));
      return turnView(id, turn);
    } catch (DecisionAgentRunner.TurnBudgetExceeded e) {
      return budgetView(triggerRef, id, e);
    } catch (RuntimeException e) {
      return failureView(triggerRef, id, e);
    }
  }

  /** 成功一轮的视图（字段与 {@link RunDecisionTool} 同口径：轨迹摘要按既有惯例截断）。 */
  private static Map<String, Object> turnView(
      String decisionMakerId, DecisionAgentRunner.DecisionTurn turn) {
    Map<String, Object> row = baseRunView(decisionMakerId, "ok", turn.conversationId());
    row.put("finalText", turn.finalText().orElse(null));
    row.put("llmCalls", turn.llmCalls());
    row.put("toolCalls", toolCallsView(turn.toolInvocations()));
    row.put("abortedByBudget", false);
    return row;
  }

  /** 撞预算中止：触发事实已落盘、历史已落会话，如实报中止与会话 id，不静默当成功。 */
  private Map<String, Object> budgetView(
      StateRef triggerRef, String decisionMakerId, DecisionAgentRunner.TurnBudgetExceeded e) {
    Map<String, Object> row =
        baseRunView(decisionMakerId, "aborted", conversationIdFor(triggerRef, decisionMakerId));
    row.put("reason", "turn-budget");
    row.put("detail", e.getMessage());
    row.put("llmCalls", e.llmCalls());
    row.put("toolCalls", List.of());
    row.put("abortedByBudget", true);
    return row;
  }

  /** 运行失败（未绑定 provider / 路由坏 / 查无等）：如实报类名与详情，绝不静默。 */
  private Map<String, Object> failureView(
      StateRef triggerRef, String decisionMakerId, RuntimeException e) {
    Map<String, Object> row =
        baseRunView(decisionMakerId, "failed", conversationIdFor(triggerRef, decisionMakerId));
    row.put("reason", e.getClass().getSimpleName());
    row.put("detail", e.getMessage());
    row.put("llmCalls", 0);
    row.put("toolCalls", List.of());
    row.put("abortedByBudget", false);
    return row;
  }

  /** 逐人结果的公共骨架：成功 / 中止 / 失败三种形态都带同一组键（查不到的值显式为 null，不靠缺字段表达）。 */
  private static Map<String, Object> baseRunView(
      String decisionMakerId, String status, String conversationId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("decisionMakerId", decisionMakerId);
    row.put("status", status);
    row.put("conversationId", conversationId);
    row.put("finalText", null);
    row.put("llmCalls", 0);
    row.put("toolCalls", List.of());
    row.put("abortedByBudget", false);
    return row;
  }

  /** 轨迹：每次工具调用一行（工具名 + 成败 + 码 + 结果摘要，摘要按既有惯例截断）。 */
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

  /**
   * 失败路径上报的会话 id：按世界事实（决策人 id + 会话世代）现算。
   *
   * <p>★ 本字段只是**报告辅助**：算不出来也不能把已经发生的失败折成工具级 TOOL_ERROR、更不能吞掉它——故这里捕获所有 {@link RuntimeException}
   * 并返回一句点名的说明（如"该版本世界里没有这个决策人"），让逐人结果照常完整。
   */
  private String conversationIdFor(StateRef ref, String decisionMakerId) {
    try {
      return decisionAgent.conversationIdOf(ref, new DecisionMakerId(decisionMakerId));
    } catch (RuntimeException e) {
      return "(无会话：" + decisionMakerId + "：" + e.getClass().getSimpleName() + ")";
    }
  }

  /** 是否因 continueOnError=false 停了：仅当最后一个已尝试的 DM 不是 ok。 */
  private static String stoppedAfter(
      List<Map<String, Object>> results, List<String> decisionMakerIds, boolean continueOnError) {
    if (continueOnError || results.isEmpty()) {
      return null;
    }
    Map<String, Object> last = results.get(results.size() - 1);
    return "ok".equals(last.get("status")) ? null : decisionMakerIds.get(results.size() - 1);
  }

  /** apply 汇总说明：把触发批的落盘与逐轮结果分开说，不把"触发事实已落盘"写成"世界已按决策执行"。 */
  private static String applyNote(
      int total, List<Map<String, Object>> results, String stoppedAfter, int concurrency) {
    int ok = 0;
    for (Map<String, Object> row : results) {
      if ("ok".equals(row.get("status"))) {
        ok++;
      }
    }
    if (stoppedAfter != null) {
      return "触发事实 "
          + total
          + " 条同批落一条 revision；已在 "
          + stoppedAfter
          + " 失败后按 continueOnError=false 停止，完成 "
          + ok
          + "/"
          + total
          + " 轮；失败/中止见 results";
    }
    String mode = concurrency <= 1 ? "顺序" : ("并发 " + concurrency);
    if (ok == total) {
      return "触发事实 "
          + total
          + " 条同批落一条 revision；"
          + total
          + " 轮全部完成（"
          + mode
          + "跑轮；各轮世界写入的 revision 本工具不汇总）";
    }
    return "触发事实 "
        + total
        + " 条同批落一条 revision；完成 "
        + ok
        + "/"
        + total
        + " 轮（"
        + mode
        + "），失败/中止见 results";
  }

  /**
   * 必填字符串数组：非空、元素非空白、保序去重。空数组 / 非数组 / 非字符串元素都抛 {@link IllegalArgumentException}（由 {@link #execute}
   * 折成具名 {@code BAD_REQUEST}）。
   */
  private static List<String> requiredTextArray(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为非空字符串数组");
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的元素必须是非空白字符串");
      }
      values.add(text);
    }
    return List.copyOf(values);
  }
}
