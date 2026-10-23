package io.mosire.simos.app.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionCommitPlan;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.model.MergedEffect;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.ga.spi.UpsertMergedEffectPlanHandler;
import io.mosire.simos.ga.tools.write.DecisionPacketPayloads;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 决策效果执行器：把被批 call / 合并计划的命令、callMeta outcome、最终 {@link DecisionRecord} 与审计 放进同一条 revision。
 *
 * <p>规划失败在提交前收口（零命令）；批被拒/冲突时 core 整批不落（零 revision）。成功路径才由 {@link
 * DecisionPackageTransitions#completeCommit} 生成最终快照，并追加 {@code
 * ga.ApplyDecisionPackage}，保证效果与包状态同批原子。
 */
public final class DecisionEffectExecutor {

  public enum Status {
    COMMITTED,
    REJECTED,
    CONFLICT,
    PLANNING_FAILED
  }

  /**
   * 一条待执行效果。
   *
   * @param packetId 来源 packet id（合并计划效果没有单一来源时 = null；来源由 {@code sourceCallRefs} 表达）
   * @param callIndex packet 内 callIndex；合并计划效果用 orderedEffects 下标
   * @param toolName 可执行决策面里的真实工具名
   * @param argsJson 目标工具参数 JSON 对象文本
   * @param sourceCallRefs 来源 call 稳定引用（{@code <packetId>:<callIndex>}），保序
   */
  public record EffectCall(
      String packetId,
      int callIndex,
      String toolName,
      String argsJson,
      List<String> sourceCallRefs) {

    public EffectCall {
      if (callIndex < 0) {
        throw new IllegalArgumentException("EffectCall.callIndex 必须 ≥ 0: " + callIndex);
      }
      if (toolName == null || toolName.isBlank()) {
        throw new IllegalArgumentException("EffectCall.toolName 不得为空白");
      }
      if (argsJson == null) {
        throw new IllegalArgumentException("EffectCall.argsJson 不得为 null（无参数用 {}）");
      }
      if (sourceCallRefs == null) {
        throw new IllegalArgumentException("EffectCall.sourceCallRefs 不得为 null（无来源用空表）");
      }
      for (String ref : sourceCallRefs) {
        if (ref == null || ref.isBlank()) {
          throw new IllegalArgumentException("EffectCall.sourceCallRefs 不得含空白");
        }
      }
      sourceCallRefs = List.copyOf(sourceCallRefs);
    }

    /** 从 AgentLib 提交计划的一条被批 call 构造（args 明文转 JSON 对象文本）。 */
    public static EffectCall forResolvedCall(DecisionPacketId packetId, ResolvedCall call) {
      Objects.requireNonNull(packetId, "packetId");
      Objects.requireNonNull(call, "call");
      return new EffectCall(
          packetId.value(),
          call.index(),
          call.toolName(),
          ToolSupport.json(call.args()),
          List.of());
    }

    public static EffectCall forMergedEffect(MergedEffect effect, int effectIndex) {
      return new EffectCall(
          null, effectIndex, effect.toolName(), effect.argsJson(), effect.sourceCallRefs());
    }
  }

  public record CallOutcome(
      EffectCall call, String result, String outcomeJson, int commandFrom, int commandTo) {

    public boolean empty() {
      return commandFrom < 0 || commandTo < 0;
    }
  }

  public record ExecutionResult(
      String batchId,
      Status status,
      StateRef ref,
      String failureCode,
      String reason,
      String summaryJson,
      List<CallOutcome> outcomes) {

    public ExecutionResult {
      outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }

    public boolean committed() {
      return status == Status.COMMITTED;
    }
  }

  /** 直接提交决策包的结果。失败时 {@code record/receipt} 为 null，表示世界零变化、包保持原快照。 */
  public record DecisionCommitExecution(
      ExecutionResult execution, DecisionRecord record, CommitReceipt receipt) {

    public DecisionCommitExecution {
      Objects.requireNonNull(execution, "execution");
    }

    public boolean committed() {
      return execution.committed() && record != null && receipt != null;
    }
  }

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final CoreSimos core;
  private final DecisionCallPreviewer previewer;

  public DecisionEffectExecutor(CoreSimos core, DecisionCallPreviewer previewer) {
    this.core = Objects.requireNonNull(core, "core");
    this.previewer = Objects.requireNonNull(previewer, "previewer");
  }

  /** 合并计划执行路径：规划效果命令、同批回写来源 {@link DecisionRecord#callMeta()} outcome，并落计划 outcome。 */
  public ExecutionResult execute(
      SimulationState base,
      String initiator,
      List<EffectCall> calls,
      Optional<MergedEffectPlan> mergedPlan) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(calls, "calls");
    Objects.requireNonNull(mergedPlan, "mergedPlan");
    if (calls.isEmpty()) {
      throw new IllegalArgumentException("执行批不得为空：至少要有一条 call/effect");
    }
    BranchId branch = base.meta().ref().branch();
    RevisionId expectedRevision = base.meta().ref().revision();
    long tick = base.meta().timestamp().tick();
    String batchId = UUID.randomUUID().toString();

    PlanOutcome plan = planAll(calls, base);
    if (!plan.ok()) {
      return failure(batchId, Status.PLANNING_FAILED, plan.failureCode(), plan.failureReason());
    }

    List<CommandEnvelope> batch = new ArrayList<>();
    List<CallOutcome> callOutcomes =
        effectCommandsAndOutcomes(
            batch, calls, plan.planned(), batchId, initiator, branch, expectedRevision);
    String summaryJson = summaryJson(batchId, tick, mergedPlan, callOutcomes);

    GaState ga = ToolSupport.gaState(base);
    if (mergedPlan.isPresent() && mergedPlan.get().outcome().isPresent()) {
      return failure(batchId, Status.PLANNING_FAILED, "BAD_REQUEST", "合并计划已有 outcome，拒绝重复 apply");
    }
    Map<String, Map<Integer, String>> packetOutcomes;
    try {
      packetOutcomes = collectPacketOutcomes(ga, callOutcomes);
    } catch (IllegalArgumentException e) {
      return failure(batchId, Status.PLANNING_FAILED, "BAD_REQUEST", e.getMessage());
    }
    for (String packetId : sortedKeys(packetOutcomes)) {
      DecisionRecord record = ga.decisionPackets().get(DecisionPacketId.parse(packetId));
      DecisionRecord updated = withCallOutcomes(record, packetOutcomes.get(packetId));
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              ApplyDecisionPackageHandler.TYPE,
              GaCodec.encodeApplyDecisionPackagePayload(
                  record.packageState().contentVersion(), updated)));
    }
    if (mergedPlan.isPresent()) {
      MergedEffectPlan updated = mergedPlan.get().withOutcome(Optional.of(summaryJson));
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              UpsertMergedEffectPlanHandler.TYPE,
              DecisionPacketPayloads.mergedPlan(updated)));
    }
    appendAudit(batch, batchId, initiator, branch, expectedRevision, tick, summaryJson);

    BatchResult result = core.submitBatch(batch);
    return adaptResult(result, batchId, summaryJson, callOutcomes, batch);
  }

  /**
   * 决策包 commit 路径：效果命令 + outcome/callMeta + 最终快照（含 receipt）同一批。
   *
   * <p>规划失败时一条命令都不提交。成功前预构造 {@code COMMITTED} receipt 并放进批尾的 {@code
   * ga.ApplyDecisionPackage}；若任一效果被拒，整批（含最终快照）回滚。
   */
  public DecisionCommitExecution executeDecisionPackage(
      SimulationState base, String initiator, DecisionRecord record, DecisionCommitPlan plan) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(plan, "plan");
    BranchId branch = base.meta().ref().branch();
    RevisionId expectedRevision = base.meta().ref().revision();
    long tick = base.meta().timestamp().tick();
    String batchId = UUID.randomUUID().toString();
    long committedRevision = expectedRevision.value() + 1L;

    List<EffectCall> calls = new ArrayList<>(plan.calls().size());
    for (ResolvedCall call : plan.calls()) {
      calls.add(EffectCall.forResolvedCall(record.id(), call));
    }
    PlanOutcome planned = planAll(calls, base);
    if (!planned.ok()) {
      return new DecisionCommitExecution(
          failure(batchId, Status.PLANNING_FAILED, planned.failureCode(), planned.failureReason()),
          null,
          null);
    }

    List<CommandEnvelope> batch = new ArrayList<>();
    List<CallOutcome> callOutcomes =
        effectCommandsAndOutcomes(
            batch, calls, planned.planned(), batchId, initiator, branch, expectedRevision);
    List<CommitReceipt.ItemOutcome> itemOutcomes = new ArrayList<>(callOutcomes.size());
    for (int i = 0; i < callOutcomes.size(); i++) {
      itemOutcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
    }
    CommitReceipt receipt =
        new CommitReceipt(
            CommitReceipt.Status.COMMITTED, String.valueOf(committedRevision), itemOutcomes, "");
    DecisionPackageSnapshot afterSnapshot;
    try {
      afterSnapshot = DecisionPackageTransitions.completeCommit(plan, receipt);
    } catch (RuntimeException e) {
      return new DecisionCommitExecution(
          failure(batchId, Status.PLANNING_FAILED, "COMMIT_FAILED", e.getMessage()), null, null);
    }

    Map<Integer, String> outcomesByIndex = outcomesByIndex(callOutcomes);
    DecisionRecord afterRecord =
        withSnapshotAndOutcomes(record, afterSnapshot, outcomesByIndex, committedRevision);
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            initiator,
            branch,
            expectedRevision,
            ApplyDecisionPackageHandler.TYPE,
            GaCodec.encodeApplyDecisionPackagePayload(
                record.packageState().contentVersion(), afterRecord)));
    String summaryJson = summaryJson(batchId, tick, Optional.empty(), callOutcomes);
    appendAudit(batch, batchId, initiator, branch, expectedRevision, tick, summaryJson);

    BatchResult result = core.submitBatch(batch);
    ExecutionResult execution = adaptResult(result, batchId, summaryJson, callOutcomes, batch);
    if (!execution.committed()) {
      return new DecisionCommitExecution(execution, null, null);
    }
    if (execution.ref().revision().value() != committedRevision) {
      throw new IllegalStateException(
          "决策包 commit 已提交但 revision 对账失败：base="
              + expectedRevision.value()
              + " actual="
              + execution.ref().revision().value());
    }
    return new DecisionCommitExecution(execution, afterRecord, receipt);
  }

  private PlanOutcome planAll(List<EffectCall> calls, SimulationState base) {
    List<List<PlannedCommand>> planned = new ArrayList<>(calls.size());
    for (int i = 0; i < calls.size(); i++) {
      EffectCall call = calls.get(i);
      PlanStep step;
      try {
        step = planCall(call, base);
      } catch (IllegalArgumentException e) {
        return PlanOutcome.fail("BAD_REQUEST", callLabel(i, call, e.getMessage()));
      } catch (RuntimeException e) {
        return PlanOutcome.fail(
            "TOOL_ERROR",
            callLabel(
                i,
                call,
                e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "")));
      }
      if (!step.ok()) {
        return PlanOutcome.fail(step.failureCode(), callLabel(i, call, step.failureReason()));
      }
      planned.add(step.commands());
    }
    return PlanOutcome.ok(List.copyOf(planned));
  }

  private static List<CallOutcome> effectCommandsAndOutcomes(
      List<CommandEnvelope> batch,
      List<EffectCall> calls,
      List<List<PlannedCommand>> planned,
      String batchId,
      String initiator,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CallOutcome> outcomes = new ArrayList<>(calls.size());
    for (int i = 0; i < calls.size(); i++) {
      EffectCall call = calls.get(i);
      int from = batch.size();
      for (PlannedCommand command : planned.get(i)) {
        batch.add(
            new CommandEnvelope(
                UUID.randomUUID().toString(),
                batchId,
                initiator,
                branch,
                expectedRevision,
                command.type(),
                command.payloadJson()));
      }
      if (planned.get(i).isEmpty()) {
        outcomes.add(new CallOutcome(call, "empty", emptyOutcomeJson(batchId), -1, -1));
      } else {
        int to = batch.size() - 1;
        outcomes.add(
            new CallOutcome(call, "committed", committedOutcomeJson(batchId, from, to), from, to));
      }
    }
    return outcomes;
  }

  private ExecutionResult adaptResult(
      BatchResult result,
      String batchId,
      String summaryJson,
      List<CallOutcome> callOutcomes,
      List<CommandEnvelope> batch) {
    if (result instanceof BatchResult.Committed committed) {
      return new ExecutionResult(
          batchId,
          Status.COMMITTED,
          committed.ref(),
          null,
          null,
          summaryJson,
          List.copyOf(callOutcomes));
    }
    if (result instanceof BatchResult.Conflict conflict) {
      return failure(
          batchId,
          Status.CONFLICT,
          "CONFLICT",
          "提交冲突：当前 head=" + conflict.current().revision().value(),
          conflict.current());
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    return failure(batchId, Status.REJECTED, "REJECTED", rejectionReason(rejected, batch));
  }

  private void appendAudit(
      List<CommandEnvelope> batch,
      String batchId,
      String initiator,
      BranchId branch,
      RevisionId expectedRevision,
      long tick,
      String summaryJson) {
    Map<String, Object> infoPayload = new LinkedHashMap<>();
    infoPayload.put("address", "ga:execution." + batchId);
    infoPayload.put("key", "result");
    infoPayload.put("value", summaryJson);
    infoPayload.put("tick", tick);
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            initiator,
            branch,
            expectedRevision,
            PutInfoHandler.TYPE,
            ToolSupport.json(infoPayload)));
  }

  private PlanStep planCall(EffectCall call, SimulationState base) {
    AgentTool tool = previewer.requireTool(call.toolName());
    Map<String, Object> args = new LinkedHashMap<>(parseJsonObject(call.argsJson(), "argsJson"));
    args.put("preview", false);
    args.put("planOnly", true);
    String branch = base.meta().ref().branch().value();
    long revision = base.meta().ref().revision().value();
    args.put("branch", branch);
    args.put("revision", revision);
    args.put("expectedRevision", revision);
    AgentPermissionSet permissions = AgentPermissionSet.unrestricted(AccessToken.SYSTEM);
    ToolContext context =
        new ToolContext(AccessToken.SYSTEM, permissions, Map.of(), args, AgentIdentity.external())
            .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
    ToolResult result;
    try {
      result = tool.execute(context);
    } catch (ResourceDeniedException e) {
      return PlanStep.fail("FORBIDDEN", "目标工具资源拒: " + e.getMessage());
    }
    if (!result.success()) {
      return PlanStep.fail(
          result.code() == null ? "REJECTED" : result.code(),
          "目标工具规划失败(" + result.code() + "): " + result.message());
    }
    Map<String, Object> view = parseJsonObject(result.message(), "plannedCommands 结果");
    Object raw = view.get("plannedCommands");
    if (!(raw instanceof List<?> rows)) {
      return PlanStep.fail(
          "TOOL_ERROR", "目标工具未按 planOnly 契约返回 plannedCommands: " + result.message());
    }
    List<PlannedCommand> commands = new ArrayList<>(rows.size());
    for (Object item : rows) {
      if (!(item instanceof Map<?, ?> row)) {
        return PlanStep.fail("TOOL_ERROR", "plannedCommands 的元素必须是对象: " + item);
      }
      Object type = row.get("type");
      Object payloadJson = row.get("payloadJson");
      if (!(type instanceof String typeText)
          || typeText.isBlank()
          || !(payloadJson instanceof String payloadText)
          || payloadText.isBlank()) {
        return PlanStep.fail("TOOL_ERROR", "plannedCommands 元素缺 type/payloadJson: " + item);
      }
      commands.add(new PlannedCommand(typeText, payloadText));
    }
    return PlanStep.ok(List.copyOf(commands));
  }

  private static Map<String, Object> parseJsonObject(String json, String label) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(label + " 为空");
    }
    try {
      Map<String, Object> parsed =
          MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      if (parsed == null) {
        throw new IllegalArgumentException(label + " 不是 JSON 对象: " + json);
      }
      return parsed;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(label + " 不是 JSON 对象: " + e.getMessage(), e);
    }
  }

  private static Map<String, Map<Integer, String>> collectPacketOutcomes(
      GaState ga, List<CallOutcome> outcomes) {
    Map<String, Map<Integer, String>> byPacket = new LinkedHashMap<>();
    for (CallOutcome outcome : outcomes) {
      EffectCall call = outcome.call();
      if (call.packetId() != null) {
        putPacketOutcome(ga, byPacket, call.packetId(), call.callIndex(), outcome);
      }
      for (String ref : call.sourceCallRefs()) {
        SourceRef parsed = parseSourceRef(ref);
        putPacketOutcome(ga, byPacket, parsed.packetId(), parsed.callIndex(), outcome);
      }
    }
    return byPacket;
  }

  private static void putPacketOutcome(
      GaState ga,
      Map<String, Map<Integer, String>> byPacket,
      String packetId,
      int callIndex,
      CallOutcome outcome) {
    DecisionRecord record = ga.decisionPackets().get(DecisionPacketId.parse(packetId));
    if (record == null) {
      throw new IllegalArgumentException("outcome 回写目标决策包不存在: " + packetId);
    }
    DecisionCallMeta meta = record.callMeta().get(callIndex);
    if (meta == null) {
      throw new IllegalArgumentException("outcome 回写目标 call 不存在: " + packetId + ":" + callIndex);
    }
    if (meta.outcomeJson().isPresent()) {
      throw new IllegalArgumentException(
          "call 已有 outcome，拒绝重复执行/覆盖: " + packetId + ":" + callIndex);
    }
    byPacket
        .computeIfAbsent(packetId, key -> new LinkedHashMap<>())
        .put(callIndex, outcome.outcomeJson());
  }

  private static DecisionRecord withCallOutcomes(
      DecisionRecord record, Map<Integer, String> outcomes) {
    Map<Integer, DecisionCallMeta> meta = new LinkedHashMap<>();
    for (Map.Entry<Integer, DecisionCallMeta> entry : record.callMeta().entrySet()) {
      String outcome = outcomes.get(entry.getKey());
      DecisionCallMeta value = entry.getValue();
      meta.put(
          entry.getKey(),
          outcome == null
              ? value
              : new DecisionCallMeta(
                  value.targets(),
                  value.previewJson(),
                  value.draftChecks(),
                  value.mergedPlanId(),
                  Optional.of(outcome)));
    }
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        record.intent(),
        record.createdAtRevision(),
        record.confirmedAtRevision(),
        record.reasonInfoId(),
        record.decidedAtRevision(),
        meta,
        record.packageState());
  }

  private static DecisionRecord withSnapshotAndOutcomes(
      DecisionRecord record,
      DecisionPackageSnapshot packageState,
      Map<Integer, String> outcomes,
      long committedRevision) {
    Map<Integer, DecisionCallMeta> meta = new LinkedHashMap<>();
    for (Map.Entry<Integer, DecisionCallMeta> entry : record.callMeta().entrySet()) {
      String outcome = outcomes.get(entry.getKey());
      DecisionCallMeta value = entry.getValue();
      meta.put(
          entry.getKey(),
          outcome == null
              ? value
              : new DecisionCallMeta(
                  value.targets(),
                  value.previewJson(),
                  value.draftChecks(),
                  value.mergedPlanId(),
                  Optional.of(outcome)));
    }
    OptionalLong decidedAtRevision = record.decidedAtRevision();
    if (decidedAtRevision.isEmpty() && packageState.pkg().decision() != null) {
      // DECIDING 由 prepareCommit 在本次提交里终结：裁决落盘 revision 就是本批新 revision。
      decidedAtRevision = OptionalLong.of(committedRevision);
    }
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        record.intent(),
        record.createdAtRevision(),
        record.confirmedAtRevision(),
        record.reasonInfoId(),
        decidedAtRevision,
        meta,
        packageState);
  }

  private static Map<Integer, String> outcomesByIndex(List<CallOutcome> outcomes) {
    Map<Integer, String> byIndex = new LinkedHashMap<>();
    for (CallOutcome outcome : outcomes) {
      byIndex.put(outcome.call().callIndex(), outcome.outcomeJson());
    }
    return byIndex;
  }

  private static String summaryJson(
      String batchId,
      long tick,
      Optional<MergedEffectPlan> mergedPlan,
      List<CallOutcome> outcomes) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("result", "committed");
    summary.put("batchId", batchId);
    summary.put("tick", tick);
    if (mergedPlan.isPresent()) {
      summary.put("planId", mergedPlan.get().id().value());
      List<Map<String, Object>> effects = new ArrayList<>(outcomes.size());
      for (CallOutcome outcome : outcomes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("effectIndex", outcome.call().callIndex());
        row.put("tool", outcome.call().toolName());
        row.put("sourceCallRefs", outcome.call().sourceCallRefs());
        row.put("result", outcome.result());
        if (!outcome.empty()) {
          row.put("commandFrom", outcome.commandFrom());
          row.put("commandTo", outcome.commandTo());
        }
        effects.add(row);
      }
      summary.put("effects", effects);
    } else {
      List<Map<String, Object>> calls = new ArrayList<>(outcomes.size());
      for (CallOutcome outcome : outcomes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("packetId", outcome.call().packetId());
        row.put("callIndex", outcome.call().callIndex());
        row.put("tool", outcome.call().toolName());
        row.put("result", outcome.result());
        if (!outcome.empty()) {
          row.put("commandFrom", outcome.commandFrom());
          row.put("commandTo", outcome.commandTo());
        }
        calls.add(row);
      }
      summary.put("calls", calls);
    }
    return ToolSupport.json(summary);
  }

  private static String committedOutcomeJson(String batchId, int from, int to) {
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("result", "committed");
    outcome.put("batchId", batchId);
    outcome.put("commandFrom", from);
    outcome.put("commandTo", to);
    return ToolSupport.json(outcome);
  }

  private static String emptyOutcomeJson(String batchId) {
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("result", "empty");
    outcome.put("batchId", batchId);
    return ToolSupport.json(outcome);
  }

  private static ExecutionResult failure(
      String batchId, Status status, String code, String reason) {
    return new ExecutionResult(batchId, status, null, code, reason, null, List.of());
  }

  private static ExecutionResult failure(
      String batchId, Status status, String code, String reason, StateRef current) {
    return new ExecutionResult(batchId, status, current, code, reason, null, List.of());
  }

  private static String callLabel(int index, EffectCall call, String detail) {
    return "call[" + index + "] " + call.toolName() + "(" + describeRef(call) + "): " + detail;
  }

  private static String describeRef(EffectCall call) {
    if (call.packetId() != null) {
      return call.packetId() + ":" + call.callIndex();
    }
    return call.sourceCallRefs().isEmpty()
        ? "merged-effect"
        : String.join(",", call.sourceCallRefs());
  }

  private static String rejectionReason(
      BatchResult.Rejected rejected, List<CommandEnvelope> batch) {
    List<String> rows = new ArrayList<>();
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      CommandResult result = outcomes.get(i).result();
      if (result instanceof CommandResult.Rejected rejection
          && !rejection.reason().startsWith("整批未提交")) {
        rows.add(batch.get(i).type() + ": " + rejection.reason());
      }
    }
    return rows.isEmpty() ? "整批被拒（无逐条拒因）" : String.join("; ", rows);
  }

  private static List<String> sortedKeys(Map<String, Map<Integer, String>> byPacket) {
    List<String> keys = new ArrayList<>(byPacket.keySet());
    Collections.sort(keys);
    return keys;
  }

  private static SourceRef parseSourceRef(String ref) {
    int colon = ref.lastIndexOf(':');
    if (colon <= 0 || colon == ref.length() - 1) {
      throw new IllegalArgumentException("sourceCallRef 形状非法（应为 <packetId>:<callIndex>）: " + ref);
    }
    String packetId = ref.substring(0, colon);
    int callIndex;
    try {
      callIndex = Integer.parseInt(ref.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("sourceCallRef 的 callIndex 不是整数: " + ref, e);
    }
    if (callIndex < 0) {
      throw new IllegalArgumentException("sourceCallRef 的 callIndex 不得为负: " + ref);
    }
    return new SourceRef(packetId, callIndex);
  }

  private record SourceRef(String packetId, int callIndex) {}

  private record PlannedCommand(String type, String payloadJson) {}

  private record PlanStep(List<PlannedCommand> commands, String failureCode, String failureReason) {
    private static PlanStep ok(List<PlannedCommand> commands) {
      return new PlanStep(commands, null, null);
    }

    private static PlanStep fail(String code, String reason) {
      return new PlanStep(List.of(), code, reason);
    }

    private boolean ok() {
      return failureReason == null;
    }
  }

  private record PlanOutcome(
      List<List<PlannedCommand>> planned, String failureCode, String failureReason) {
    private static PlanOutcome ok(List<List<PlannedCommand>> planned) {
      return new PlanOutcome(planned, null, null);
    }

    private static PlanOutcome fail(String code, String reason) {
      return new PlanOutcome(List.of(), code, reason);
    }

    private boolean ok() {
      return failureReason == null;
    }
  }
}
