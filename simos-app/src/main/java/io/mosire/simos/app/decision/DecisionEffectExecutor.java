package io.mosire.simos.app.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.DecisionPacketPayloads;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffect;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.sd.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.sd.spi.UpsertMergedEffectPlanHandler;
import io.mosire.simos.sd.state.SdState;
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
import java.util.UUID;

/**
 * ★★ D3 决策效果执行器（契约 §6/§7）：把一个 tick 的**已批准 call / 合并计划**在**一条 revision**里执行掉。
 *
 * <p>★★ <b>为什么需要它</b>：决策人只 propose（写 packet），GM 裁决后真正落盘的是"目标工具的命令批"。目标工具的 {@code preview=false}
 * 路径直接 {@code submitBatch}——若执行器也走那条路，N 条 call 会落 N 条 revision，且不可能把 outcome 与效果写进同一批。故目标工具新增内部参数
 * {@code planOnly=true}：走与 {@code preview=false} **完全相同**的纯推导与组批，但在提交前把命令批返回给本执行器。执行器收齐后拼成**一个**
 * {@link CommandEnvelope} 列表（同 branch/expectedRevision、同 batchId、逐条新 commandId），并把 outcome
 * 回写命令追加在批尾，最后一次 {@link CoreSimos#submitBatch} 落一条 revision。
 *
 * <p>★★ <b>执行者身份</b>：目标工具调用一律用系统上下文（{@link AccessToken#SYSTEM} + {@link
 * AgentPermissionSet#unrestricted} + {@link ResourceAuthorizer#of} 目标工具自己的 {@code
 * resources()}）；{@code initiator} 由调用方给（GM）。proposer 留在 packet / outcome 里，不冒充执行者。
 *
 * <p>★★ <b>失败语义</b>：任意一条 call 规划失败、任一 sourceCallRef 悬空、批被拒 / 冲突 ⇒ **整批零 revision**，具名返回； 不静默剔
 * call、不部分执行、不写 outcome。{@code call} 产出 0 条命令是合法退化（outcome 记 {@code empty}）。
 *
 * <p>★ <b>稳定序</b>：由调用方保证——{@code gm.packet.execute} 按 packetId 字符串序 → callIndex 升序； {@code
 * gm.mergedPlan.apply} 按 plan.orderedEffects 顺序。
 *
 * <p>★ <b>outcome 回写</b>（同批）：涉及的每个 packet 追加一条 {@code sd.UpsertDecisionPacket}（执行到的 call 写 {@code
 * outcomeJson}，其余字段原样）；合并计划追加一条 {@code sd.UpsertMergedEffectPlan}（{@code outcome} = 同一摘要 JSON
 * 字符串）；最后追加一条 {@code sd.PutInfo}（地址 {@code sd:execution.<batchId>}，key={@code result}， value=同一摘要
 * JSON 字符串）作为可读审计。合并计划的 sourceCallRefs 会反向把 outcome 写到来源 call 上。
 */
public final class DecisionEffectExecutor {

  /** 一次执行的结局档位。 */
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
   * @param toolName ProposalCatalog 清单里的真实工具名
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

    /** 从一条已批准 call 构造（gm.packet.execute）。 */
    public static EffectCall forPacketCall(DecisionPacketId packetId, FormattedCall call) {
      return new EffectCall(
          packetId.value(), call.callIndex(), call.toolName(), call.argsJson(), List.of());
    }

    /** 从合并计划的一条 orderedEffect 构造（效果下标 = 顺序位；来源由 sourceCallRefs 表达）。 */
    public static EffectCall forMergedEffect(MergedEffect effect, int effectIndex) {
      return new EffectCall(
          null, effectIndex, effect.toolName(), effect.argsJson(), effect.sourceCallRefs());
    }
  }

  /**
   * 一条 call/effect 的执行结局。
   *
   * @param result {@code committed}（有命令）或 {@code empty}（0 条命令的合法退化）
   * @param outcomeJson 预构造的 outcome 摘要字符串（批提交成功才生效）
   * @param commandFrom 批内命令区间起点（0-based；empty 时 = -1）
   * @param commandTo 批内命令区间终点（0-based；empty 时 = -1）
   */
  public record CallOutcome(
      EffectCall call, String result, String outcomeJson, int commandFrom, int commandTo) {

    public boolean empty() {
      return commandFrom < 0 || commandTo < 0;
    }
  }

  /**
   * 一次执行的结果。
   *
   * @param batchId 批 id（= 所有信封的 correlationId；被拒/冲突时也返回，便于审计对齐）
   * @param status 四档结局
   * @param ref {@code COMMITTED} = 新坐标；{@code CONFLICT} = 真实 head；其余 null
   * @param failureCode 失败时的具名 code（{@code BAD_REQUEST}/{@code FORBIDDEN}/{@code TOOL_ERROR}/{@code
   *     REJECTED}/{@code CONFLICT}）；成功 null
   * @param reason 失败原因（给人看；成功 null）
   * @param summaryJson 成功时的批摘要 JSON 字符串（= plan.outcome = PutInfo value）
   * @param outcomes 成功时逐 call/effect 结局；失败 = 空表（零写入，不伪造 outcome）
   */
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

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final CoreSimos core;
  private final ProposalCatalog catalog;

  public DecisionEffectExecutor(CoreSimos core, ProposalCatalog catalog) {
    this.core = Objects.requireNonNull(core, "core");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /**
   * 执行一批效果。
   *
   * @param base 执行读/写的 base 坐标（branch/expectedRevision/tick 都取它）
   * @param initiator 落盘发起者（GM；proposer 留在 packet/outcome，不冒充执行者）
   * @param calls 按稳定序排好的效果（非空；空表 ⇒ 调用方应直接拒）
   * @param mergedPlan 合并计划 apply 时给出（执行器负责把 outcome 写回 plan）；packet.execute 传 empty
   */
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

    // ① 逐条规划：走目标工具与 preview=false 相同的纯推导/组批，但 planOnly=true ⇒ 不提交。
    List<List<PlannedCommand>> planned = new ArrayList<>(calls.size());
    for (int i = 0; i < calls.size(); i++) {
      EffectCall call = calls.get(i);
      PlanStep step;
      try {
        step = planCall(call, base);
      } catch (IllegalArgumentException e) {
        return failure(
            batchId, Status.PLANNING_FAILED, "BAD_REQUEST", callLabel(i, call, e.getMessage()));
      } catch (RuntimeException e) {
        return failure(
            batchId,
            Status.PLANNING_FAILED,
            "TOOL_ERROR",
            callLabel(
                i,
                call,
                e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "")));
      }
      if (!step.ok()) {
        return failure(
            batchId,
            Status.PLANNING_FAILED,
            step.failureCode(),
            callLabel(i, call, step.failureReason()));
      }
      planned.add(step.commands());
    }

    // ② 汇总成同 branch/expectedRevision、同 batchId、逐条新 commandId 的效果命令。
    List<CommandEnvelope> batch = new ArrayList<>();
    List<CallOutcome> callOutcomes = new ArrayList<>(calls.size());
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
        callOutcomes.add(new CallOutcome(call, "empty", emptyOutcomeJson(batchId), -1, -1));
      } else {
        int to = batch.size() - 1;
        callOutcomes.add(
            new CallOutcome(call, "committed", committedOutcomeJson(batchId, from, to), from, to));
      }
    }

    // ③ 预构造摘要（成功才生效）：plan.outcome / PutInfo value / 调用方 view 共用同一份字符串。
    String summaryJson = summaryJson(batchId, tick, mergedPlan, callOutcomes);

    // ④ 追加 outcome 回写命令（同批）。先校验目标 packet/call 存在且无旧 outcome（fail-closed，绝不静默覆盖/剔除）。
    SdState sd = ToolSupport.sdState(base);
    Map<String, Map<Integer, String>> packetOutcomes;
    try {
      packetOutcomes = collectPacketOutcomes(sd, callOutcomes);
    } catch (IllegalArgumentException e) {
      return failure(batchId, Status.PLANNING_FAILED, "BAD_REQUEST", e.getMessage());
    }
    if (mergedPlan.isPresent() && mergedPlan.get().outcome().isPresent()) {
      return failure(batchId, Status.PLANNING_FAILED, "BAD_REQUEST", "合并计划已有 outcome，拒绝重复 apply");
    }
    for (String packetId : sortedKeys(packetOutcomes)) {
      DecisionPacket packet = sd.decisionPackets().get(DecisionPacketId.parse(packetId));
      DecisionPacket updated = withOutcomes(packet, packetOutcomes.get(packetId));
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(updated)));
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
    Map<String, Object> infoPayload = new LinkedHashMap<>();
    infoPayload.put("address", "sd:execution." + batchId);
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

    // ⑤ 一条 revision：效果命令 + outcome 回写命令整批原子。
    BatchResult result = core.submitBatch(batch);
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
          "提交冲突：base="
              + expectedRevision.value()
              + "，当前 head="
              + conflict.current().revision().value(),
          conflict.current());
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    return failure(batchId, Status.REJECTED, "REJECTED", rejectionReason(rejected, batch));
  }

  // ── 规划 ────────────────────────────────────────────────────────────────────────────

  /** 用系统上下文把一条效果跑成"命令批文本"（planOnly=true；绝不提交）。 */
  private PlanStep planCall(EffectCall call, SimulationState base) {
    AgentTool tool = catalog.requireTool(call.toolName());
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

  // ── outcome 回写构造 ────────────────────────────────────────────────────────────────

  /**
   * 汇总 packet → callIndex → outcomeJson：直接 call（packetId 非空）按自己的区间；合并计划效果按 {@code sourceCallRefs}
   * 反向映射到来源 call。
   */
  private static Map<String, Map<Integer, String>> collectPacketOutcomes(
      SdState sd, List<CallOutcome> outcomes) {
    Map<String, Map<Integer, String>> byPacket = new LinkedHashMap<>();
    for (CallOutcome outcome : outcomes) {
      EffectCall call = outcome.call();
      if (call.packetId() != null) {
        putPacketOutcome(sd, byPacket, call.packetId(), call.callIndex(), outcome);
      }
      for (String ref : call.sourceCallRefs()) {
        SourceRef parsed = parseSourceRef(ref);
        putPacketOutcome(sd, byPacket, parsed.packetId(), parsed.callIndex(), outcome);
      }
    }
    return byPacket;
  }

  private static void putPacketOutcome(
      SdState sd,
      Map<String, Map<Integer, String>> byPacket,
      String packetId,
      int callIndex,
      CallOutcome outcome) {
    DecisionPacket packet = sd.decisionPackets().get(DecisionPacketId.parse(packetId));
    if (packet == null) {
      throw new IllegalArgumentException("outcome 回写目标决策包不存在: " + packetId);
    }
    FormattedCall call = findCall(packet, callIndex);
    if (call == null) {
      throw new IllegalArgumentException("outcome 回写目标 call 不存在: " + packetId + ":" + callIndex);
    }
    if (call.outcomeJson().isPresent()) {
      throw new IllegalArgumentException(
          "call 已有 outcome，拒绝重复执行/覆盖: " + packetId + ":" + callIndex);
    }
    byPacket
        .computeIfAbsent(packetId, key -> new LinkedHashMap<>())
        .put(callIndex, outcome.outcomeJson());
  }

  private static FormattedCall findCall(DecisionPacket packet, int callIndex) {
    for (FormattedCall call : packet.calls()) {
      if (call.callIndex() == callIndex) {
        return call;
      }
    }
    return null;
  }

  private static DecisionPacket withOutcomes(DecisionPacket packet, Map<Integer, String> outcomes) {
    List<FormattedCall> calls = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      String outcomeJson = outcomes.get(call.callIndex());
      calls.add(outcomeJson == null ? call : call.withOutcomeJson(Optional.of(outcomeJson)));
    }
    return packet.withCalls(calls);
  }

  // ── 摘要 JSON（全部经 ToolSupport.json，不手拼）───────────────────────────────────────

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

  /**
   * 单条 call 的 outcome 摘要：{@code {"result":"committed","batchId":…,"commandFrom":i,"commandTo":j}}。
   */
  private static String committedOutcomeJson(String batchId, int from, int to) {
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("result", "committed");
    outcome.put("batchId", batchId);
    outcome.put("commandFrom", from);
    outcome.put("commandTo", to);
    return ToolSupport.json(outcome);
  }

  /** 0 条命令的合法退化：{@code {"result":"empty","batchId":…}}。 */
  private static String emptyOutcomeJson(String batchId) {
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("result", "empty");
    outcome.put("batchId", batchId);
    return ToolSupport.json(outcome);
  }

  // ── 失败收口 ────────────────────────────────────────────────────────────────────────

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
}
