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
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.army.resolveCombat}（阶段 D4 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02）：GM-only
 * 组合工具——**结算一个交战阶段**： 一批（一条 revision）= 逐单位 {@code unit.AdjustComposition}（有符号损失）+ 一条 {@code
 * army.ResolveCombatStage}（选定结局/seed 落记录）+（记录内所有 阶段都已判定时）逐单位 {@code unit.SetStateDescription} 清除
 * {@code combat} 状态链接。
 *
 * <p>★★ <b>判定语义（三条，与 {@code CombatResolution} 同源）</b>：
 *
 * <ol>
 *   <li>只给 {@code outcomeId} ⇒ 不投骰，选中它（记录里 {@code rollSeed} 空）；
 *   <li>只给 {@code seed} ⇒ 用该 seed 投骰；
 *   <li>两个都给 ⇒ 按该 seed 复核（投出的必须就是给的 outcome，否则拒）；
 *   <li>都不给 ⇒ 由 {@code combatId+stageId+tick+概率表} 确定性派生 seed 后投骰（可复现；seed 落记录）。
 * </ol>
 *
 * <p>★★ <b>状态链接的"结束"口径（R3）</b>：本次判定后若记录里所有阶段都已判定，工具在**同一批**里显式清除所有"状态键 combat 且地址恰为 {@code
 * army:combat.<id>}"的链接（只清恰链到本记录的；本来没有链接的不发命令）。还有未判定阶段 ⇒ 链接保留到下一次显式清除。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：它既改记录又改单位，是战果结算；工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}。它提交的 {@code unit.AdjustComposition} 本身标了 {@code GmOnlyCommand}（GM
 * 直改原语），与此工具同一条权限边界。
 *
 * <p>★ <b>资源声明</b>：只写 {@code army}/{@code unit} 两个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}），与批内命令逐条对齐；**不新开写口**——GM 直改人力/装备仍走 D3a 的 {@code
 * simos.unit.set-composition}/{@code simos.unit.adjust-composition}，本工具复用 {@code
 * unit.AdjustComposition} 命令。
 *
 * <p>★ <b>参数</b>：{@code combatId(必填), stageId(必填), outcomeId?, seed?, preview?(缺省 true), branch?,
 * expectedRevision?(preview=false 必填)}。
 *
 * <p>★ <b>失败具名</b>：记录/阶段不存在、阶段已判定、显式 outcome 不在表里、seed 与 outcome 不一致、损失指向不存在单位 ⇒ {@code
 * BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED} 带逐条真拒因；冲突 ⇒ {@code CONFLICT} 带真实 head。
 */
public final class ResolveCombatTool implements AgentTool {

  /** 工具名（全局唯一；用户给定）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.army.resolveCombat";

  /** 本工具只写 army / unit 两个命名空间（GM 侧两片 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RESOLVE_COMBAT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ARMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RESOLVE_COMBAT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ARMY_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public ResolveCombatTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "结算一个交战阶段（GM 组合工具，一批 = 一条 revision）：投骰或显式结局——逐单位 unit.AdjustComposition"
        + "（选中结局损失的有符号增量）→ army.ResolveCombatStage（选定结局/seed 写进记录）→ 若记录内所有阶段都已判定，"
        + "逐单位 unit.SetStateDescription 清除 state=\""
        + ResolveCombatPlan.STATE_KEY
        + "\" 且地址恰为 army:combat.<id> 的状态链接。参数 {combatId, stageId, outcomeId?, seed?, preview?（缺省 true）,"
        + " branch?, expectedRevision?（preview=false 必填）}。四种取法：outcomeId（不投骰）/ seed（显式投骰）/ 都不给（由"
        + " combatId+stageId+tick+概率表 确定性派生 seed）；两给则按 seed 复核 outcome。返回 {preview, submitted, combatId,"
        + " stageId, outcome, seed, seedProvided, outcomeProvided, tick, allStagesResolvedAfter, losses, clearedLinks,"
        + " commands}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("combatId", ToolSupport.prop("string", "交战记录 id（如 c-1）"));
    props.put("stageId", ToolSupport.prop("string", "要判定的阶段 id（如 start / s2）"));
    props.put("outcomeId", ToolSupport.prop("string", "显式结局 id（可选；给了就不投骰，必须在该阶段概率表里）"));
    props.put(
        "seed",
        ToolSupport.prop(
            "integer", "显式随机种子（可选；只给 seed = 显式投骰；与 outcomeId 同给 = 按 seed 复核 outcome）"));
    props.put(
        "preview",
        ToolSupport.prop(
            "boolean", "true（缺省）= 只算不写；false = 提交 AdjustComposition + ResolveCombatStage 同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=head）"));
    return ToolSupport.schema(props, List.of("combatId", "stageId"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return RESOLVE_COMBAT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "结算交战阶段 combatId="
            + args.get("combatId")
            + " stageId="
            + args.get("stageId")
            + " outcomeId="
            + args.get("outcomeId")
            + " seed="
            + args.get("seed")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String combatId = ToolSupport.requiredText(args, "combatId");
      String stageId = ToolSupport.requiredText(args, "stageId");
      Optional<String> outcomeId = optionalNonBlankText(args, "outcomeId");
      Optional<Long> seed = Optional.ofNullable(ToolSupport.optionalLong(args, "seed"));
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      ResolveCombatPlan.Plan plan =
          ResolveCombatPlan.derive(state, combatId, stageId, outcomeId, seed);
      if (preview) {
        return ToolSupport.ok(view(plan, true, false, null));
      }
      return apply(plan, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "结算交战阶段失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── preview / apply 共用视图与组批 ─────────────────────────────────────────────────

  private ToolResult apply(ResolveCombatPlan.Plan plan, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch = new ArrayList<>();
    for (String payload : plan.adjustPayloadsJson()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ResolveCombatPlan.ADJUST_COMPOSITION_TYPE,
              payload));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            ResolveCombatPlan.RESOLVE_STAGE_TYPE,
            plan.resolvePayloadJson()));
    for (String payload : plan.clearPayloadsJson()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              ResolveCombatPlan.SET_STATE_DESCRIPTION_TYPE,
              payload));
    }
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Committed committed) {
      return ToolSupport.ok(view(plan, false, true, committedView(committed, batchId)));
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      return ToolResult.error("CONFLICT", ToolSupport.json(view(plan, false, true, submission)));
    }
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效）。
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    return ToolResult.error("REJECTED", ToolSupport.json(view(plan, false, true, submission)));
  }

  private static Map<String, Object> committedView(
      BatchResult.Committed committed, String batchId) {
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "committed");
    submission.put("ref", ToolSupport.stateRef(committed.ref()));
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    return submission;
  }

  private static Map<String, Object> view(
      ResolveCombatPlan.Plan plan,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("combatId", plan.combatId().value());
    view.put("stageId", plan.stageId().value());
    view.put("outcome", outcomeView(plan.outcome()));
    view.put("seed", plan.seed().orElse(null));
    view.put("seedProvided", plan.seedProvided());
    view.put("outcomeProvided", plan.outcomeProvided());
    view.put("tick", plan.tick());
    view.put("allStagesResolvedAfter", plan.allStagesResolvedAfter());
    view.put("losses", plan.lossesView());
    view.put("clearedLinks", unitIdValues(plan.clearLinkUnits()));
    view.put("commands", plan.commandCounts());
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  private static Map<String, Object> outcomeView(CombatOutcome outcome) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", outcome.id().value());
    view.put("label", outcome.label());
    view.put("weight", outcome.weight());
    return view;
  }

  private static List<String> unitIdValues(List<UnitId> units) {
    List<String> values = new ArrayList<>(units.size());
    for (UnitId unit : units) {
      values.add(unit.value());
    }
    return List.copyOf(values);
  }

  /** 可选非空白文本参数：缺省/显式 null ⇒ 空；给出但非文本或空白 ⇒ 具名 {@link IllegalArgumentException}。 */
  private static Optional<String> optionalNonBlankText(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空白文本");
    }
    return Optional.of(text);
  }

  private CommandEnvelope envelope(
      String batchId, BranchId branch, long expectedRevision, String type, String payloadJson) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        batchId,
        initiator,
        branch,
        new RevisionId(expectedRevision),
        type,
        payloadJson);
  }
}
