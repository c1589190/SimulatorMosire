package io.mosire.simos.ga.tools.write;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.model.MergedEffect;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.spi.UpsertMergedEffectPlanHandler;
import io.mosire.simos.ga.tools.GaEffectExecution;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gm.mergedPlan.upsert}（D3 契约 §8）：GM 建/改一个 {@link MergedEffectPlan}——冲突决策包的**有序效果计划**。
 *
 * <p>★ 参数 {planId?, tick?, participantIds?, sources?, reason?,
 * orderedEffects[{toolName,argsJson,sourceCallRefs?}...]}。 {@code planId} 缺省 {@code
 * merge-<tick>-<n>}（n = 该 tick 已有计划数 + 1，稳定确定性）；每条 effect 的工具必须在宿主可执行工具面里 （{@link
 * GaEffectExecution#knownTool(String)}；D3 不允许任意工具，避免执行面失控）。
 *
 * <p>★★ G2：来源 {@code sourceCallRefs}/{@code sources} 必须能按 {@code <packetId>:<callIndex>} 解析到当前
 * {@link GaState} 里的 {@link DecisionRecord} 与对应 {@code callMeta}；本工具把这些 call 的 {@code mergedPlanId}
 * 写成 plan id，并与 {@link MergedEffectPlan} 用**同一条 batch / 同一个 revision** 落盘。 来源缺失/越界/格式坏/重复/已属别的 plan
 * ⇒ 具名拒且整批零写。
 *
 * <p>★ 顺序约束：先 upsert，再 {@code simos.gm.packet.decide(MERGE, mergedPlanId)}，最后 {@code
 * simos.gm.mergedPlan.apply}。
 *
 * <p>★ 只在 GM 桶；工具名不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS；资源声明 sd UNRESTRICTED；敏感写。
 */
public final class GmMergedPlanUpsertTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.gm.mergedPlan.upsert";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaEffectExecution effects;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param effects 可执行工具清单口（效果工具必须存在）
   */
  public GmMergedPlanUpsertTool(
      ToolWritePort writes, ToolReadPort reads, GaEffectExecution effects) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.effects = Objects.requireNonNull(effects, "effects");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 建/改合并效果计划（写）：参数 {planId?(缺省 merge-<tick>-<n>), tick?(缺省当前 tick), "
        + "participantIds?[决策人 id], sources?[来源引用], reason?(审批摘要用), "
        + "orderedEffects[{toolName(必须在可执行工具面), argsJson(JSON 对象), sourceCallRefs?[]}...](必填非空)}。"
        + "原子批：一条 ga.UpsertMergedEffectPlan + 每个来源 record 的 ga.ApplyDecisionPackage（同一 revision），"
        + "把来源 call 的 mergedPlanId 写成 planId。返回 {planId, tick, participantIds, orderedEffects, "
        + "sources, mergedSourceCalls, mergedSourceCallCount, outcome?, submission?}。先本工具建计划，再 "
        + "gm.packet.decide(MERGE, planId)，最后 gm.mergedPlan.apply。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("planId", ToolSchema.prop("string", "计划 id（缺省 merge-<tick>-<该 tick 已有计划数+1>）"));
    props.put("tick", ToolSchema.prop("integer", "所属 tick（缺省当前世界 tick）"));
    Map<String, Object> participants = ToolSchema.prop("array", "参与者决策人 id（允许历史已删除）");
    participants.put("items", ToolSchema.prop("string", "决策人 id"));
    props.put("participantIds", participants);
    Map<String, Object> sources = ToolSchema.prop("array", "来源 call 引用（形如 pkt-…:0）");
    sources.put("items", ToolSchema.prop("string", "来源引用"));
    props.put("sources", sources);
    props.put("reason", ToolSchema.prop("string", "可选：审批摘要用的原因（本批不落盘）"));
    Map<String, Object> effects = ToolSchema.prop("array", "有序效果（必填非空；顺序即执行顺序）");
    Map<String, Object> effectItem = new LinkedHashMap<>();
    effectItem.put("type", "object");
    Map<String, Object> effectProps = new LinkedHashMap<>();
    effectProps.put("toolName", ToolSchema.prop("string", "真实工具名（必须在可执行工具面）"));
    effectProps.put("argsJson", ToolSchema.prop("string", "目标工具参数 JSON 对象文本"));
    Map<String, Object> refs = ToolSchema.prop("array", "来源 call 引用（形如 pkt-…:0）");
    refs.put("items", ToolSchema.prop("string", "来源引用"));
    effectProps.put("sourceCallRefs", refs);
    effectItem.put("properties", effectProps);
    effectItem.put("required", List.of("toolName", "argsJson"));
    effects.put("items", effectItem);
    props.put("orderedEffects", effects);
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("orderedEffects"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GA_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "建/改合并计划 planId="
            + args.get("planId")
            + " tick="
            + args.get("tick")
            + " effects="
            + (args.get("orderedEffects") instanceof List<?> list ? list.size() : "?")
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      // reason 本批只进审批摘要（MergedEffectPlan 没有 reason 字段；reasonInfoId 留 D4）：读出来防误当未知参数静默忽略。
      ToolSchema.optionalText(args, "reason", null);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      if (tick < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tick);
      }
      List<MergedEffect> effects = requiredEffects(args);
      List<DecisionMakerId> participantIds = optionalDecisionMakerIds(args, "participantIds");
      List<String> sources = optionalStringList(args, "sources", "sources");
      String planIdArg = ToolSchema.optionalText(args, "planId", null);
      String planId =
          planIdArg == null || planIdArg.isBlank() ? defaultPlanId(ga, tick) : planIdArg.trim();
      MergedEffectPlan plan =
          new MergedEffectPlan(
              MergedEffectPlanId.parse(planId),
              tick,
              participantIds,
              effects,
              sources,
              Optional.empty(),
              Optional.empty());
      // ★★ G2：先把所有来源 call 解析/校验完，再组一个原子批：
      //   plan 本身 + 每个受影响 DecisionRecord 的 ga.ApplyDecisionPackage（同一 revision）。
      SourceUpdates sourceUpdates = resolveSourceUpdates(ga, branch, planId, effects, sources);
      List<ToolWritePort.Request> batch = new ArrayList<>(sourceUpdates.requests().size() + 1);
      batch.add(
          new ToolWritePort.Request(
              UpsertMergedEffectPlanHandler.TYPE, DecisionPacketPayloads.mergedPlan(plan)));
      batch.addAll(sourceUpdates.requests());
      ToolWritePort.BatchReceipt receipt =
          writes.submitBatchWithReceipt(
              branch, new RevisionId(state.meta().ref().revision().value()), List.copyOf(batch));
      Map<String, Object> view = planView(plan, branch, state.meta().ref().revision().value());
      view.put("mergedSourceCalls", sourceUpdates.updatedRefs());
      view.put("mergedSourceCallCount", sourceUpdates.updatedRefs().size());
      return foldBatch(receipt, view);
    } catch (SourceRejection e) {
      return ToolResult.error(e.code(), e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "建合并计划失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 参数解析 ────────────────────────────────────────────────────────────────────────

  private List<MergedEffect> requiredEffects(Map<String, Object> args) {
    Object raw = args.get("orderedEffects");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("参数 orderedEffects 必填且为非空数组");
    }
    List<MergedEffect> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> row)) {
        throw new IllegalArgumentException("orderedEffects 的元素必须是对象: " + item);
      }
      Object toolNameRaw = row.get("toolName");
      if (!(toolNameRaw instanceof String toolName) || toolName.isBlank()) {
        throw new IllegalArgumentException("orderedEffect.toolName 必填且为非空文本: " + item);
      }
      if (!effects.knownTool(toolName)) {
        throw new IllegalArgumentException("效果工具不在可执行工具面，不可执行: " + toolName);
      }
      Object argsJsonRaw = row.get("argsJson");
      if (argsJsonRaw != null && !(argsJsonRaw instanceof String)) {
        throw new IllegalArgumentException("orderedEffect.argsJson 必须是 JSON 对象文本: " + item);
      }
      String argsJson = argsJsonRaw == null ? "{}" : (String) argsJsonRaw;
      requireJsonObject(argsJson, "orderedEffect.argsJson");
      List<String> refs =
          optionalStringListFrom(row, "sourceCallRefs", "orderedEffect.sourceCallRefs");
      out.add(new MergedEffect(toolName, argsJson, refs));
    }
    return List.copyOf(out);
  }

  private static List<DecisionMakerId> optionalDecisionMakerIds(
      Map<String, Object> args, String field) {
    List<String> raw = optionalStringList(args, field, field);
    List<DecisionMakerId> out = new ArrayList<>(raw.size());
    for (String id : raw) {
      out.add(DecisionMakerId.parse(id));
    }
    return List.copyOf(out);
  }

  private static List<String> optionalStringList(
      Map<String, Object> args, String field, String label) {
    return optionalStringListFrom(args, field, label);
  }

  private static List<String> optionalStringListFrom(Map<?, ?> args, String field, String label) {
    Object raw = args.get(field);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + label + " 若给出必须是 [字符串…] 数组");
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 " + label + " 的元素必须是非空文本: " + item);
      }
      out.add(text);
    }
    return List.copyOf(out);
  }

  private static void requireJsonObject(String json, String label) {
    if (json.isBlank()) {
      throw new IllegalArgumentException(label + " 不得为空白（无参数用 {}）");
    }
    try {
      Map<String, Object> parsed =
          MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      if (parsed == null) {
        throw new IllegalArgumentException(label + " 必须是 JSON 对象: " + json);
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(label + " 必须是 JSON 对象: " + e.getMessage(), e);
    }
  }

  /** 缺省 planId：{@code merge-<tick>-<n>}，n = 该 tick 已有计划数 + 1；撞既有 id（历史异常数据）就继续 +1。 */
  private static String defaultPlanId(GaState ga, long tick) {
    long count = 0L;
    for (MergedEffectPlan plan : ga.mergedEffectPlans().values()) {
      if (plan.tick() == tick) {
        count++;
      }
    }
    long n = count + 1;
    String candidate = "merge-" + tick + "-" + n;
    while (ga.mergedEffectPlans().containsKey(MergedEffectPlanId.parse(candidate))) {
      n++;
      candidate = "merge-" + tick + "-" + n;
    }
    return candidate;
  }

  // ── MERGED 来源解析（G2）────────────────────────────────────────────────────────────

  /**
   * 解析 plan 的 effect.sourceCallRefs 与顶层 sources，返回需要与 plan **同批**提交的来源 {@code DecisionRecord} 更新。
   *
   * <p>稳定格式（与 app {@code DecisionEffectExecutor} 同一口径）：{@code <packetId>:<callIndex>}，按最后一个 {@code
   * ':'} 切分。effect 的 source refs 之间不允许重复；顶层 sources 内部不允许重复；同一 call 同时出现在 两层是允许的（顶层 sources 可以是
   * effect refs 的聚合）。任何来源坏/不存在/越界/已被别的 plan 合并 ⇒ 整批零写。
   */
  private static SourceUpdates resolveSourceUpdates(
      GaState ga,
      BranchId branch,
      String planId,
      List<MergedEffect> effects,
      List<String> sources) {
    List<SourceCall> allRefs = new ArrayList<>();
    Set<SourceCall> effectRefs = new LinkedHashSet<>();
    for (MergedEffect effect : effects) {
      for (String raw : effect.sourceCallRefs()) {
        SourceCall ref = parseSourceRef(raw);
        if (!effectRefs.add(ref)) {
          throw sourceReject(
              "DUPLICATE_SOURCE_CALL_REF",
              "sourceCallRefs 重复引用同一来源 call（重复会带来合并归属歧义）: " + ref.canonical());
        }
        allRefs.add(ref);
      }
    }
    Set<SourceCall> planRefs = new LinkedHashSet<>();
    for (String raw : sources) {
      SourceCall ref = parseSourceRef(raw);
      if (!planRefs.add(ref)) {
        throw sourceReject(
            "DUPLICATE_SOURCE_CALL_REF", "sources 重复引用同一来源 call（重复会带来合并归属歧义）: " + ref.canonical());
      }
      allRefs.add(ref);
    }

    Set<SourceCall> resolved = new LinkedHashSet<>();
    Map<DecisionPacketId, DecisionRecord> records = new LinkedHashMap<>();
    Map<DecisionPacketId, Set<Integer>> changedIndexes = new LinkedHashMap<>();
    List<String> changedRefs = new ArrayList<>();
    for (SourceCall ref : allRefs) {
      if (!resolved.add(ref)) {
        continue; // 同一 call 在 effectRefs 与 sources 各出现一次：允许，只更新一次。
      }
      DecisionRecord record = ga.decisionPackets().get(ref.packetId());
      if (record == null) {
        throw sourceReject("SOURCE_PACKET_NOT_FOUND", "来源决策包不存在: " + ref.canonical());
      }
      if (!record.branch().equals(branch.value())) {
        throw sourceReject(
            "SOURCE_BRANCH_MISMATCH",
            "来源决策包不在本分支: "
                + ref.canonical()
                + " recordBranch="
                + record.branch()
                + " commandBranch="
                + branch.value());
      }
      DecisionCallMeta meta = record.callMeta().get(ref.callIndex());
      if (meta == null) {
        throw sourceReject("SOURCE_CALL_NOT_FOUND", "来源决策包内没有该 callIndex: " + ref.canonical());
      }
      Optional<String> existing = meta.mergedPlanId();
      if (existing.isPresent()) {
        if (!existing.get().equals(planId)) {
          throw sourceReject(
              "SOURCE_CALL_ALREADY_MERGED",
              "来源 call 已属于另一个合并计划（" + existing.get() + "），拒绝改动: " + ref.canonical());
        }
        // 已经指向本 plan：幂等，无需再写。
        continue;
      }
      records.putIfAbsent(ref.packetId(), record);
      changedIndexes
          .computeIfAbsent(ref.packetId(), ignored -> new LinkedHashSet<>())
          .add(ref.callIndex());
      changedRefs.add(ref.canonical());
    }

    List<ToolWritePort.Request> requests = new ArrayList<>(changedIndexes.size());
    for (Map.Entry<DecisionPacketId, Set<Integer>> entry : changedIndexes.entrySet()) {
      DecisionRecord record = records.get(entry.getKey());
      Map<Integer, DecisionCallMeta> nextMeta = new LinkedHashMap<>(record.callMeta());
      for (Integer callIndex : entry.getValue()) {
        DecisionCallMeta old = nextMeta.get(callIndex);
        nextMeta.put(
            callIndex,
            new DecisionCallMeta(
                old.targets(),
                old.previewJson(),
                old.draftChecks(),
                Optional.of(planId),
                old.outcomeJson()));
      }
      DecisionRecord next =
          new DecisionRecord(
              record.id(),
              record.branch(),
              record.tick(),
              record.proposerId(),
              record.intent(),
              record.createdAtRevision(),
              record.confirmedAtRevision(),
              record.reasonInfoId(),
              record.decidedAtRevision(),
              nextMeta,
              record.packageState());
      requests.add(
          new ToolWritePort.Request(
              ApplyDecisionPackageHandler.TYPE,
              GaCodec.encodeApplyDecisionPackagePayload(
                  record.packageState().contentVersion(), next)));
    }
    return new SourceUpdates(List.copyOf(requests), List.copyOf(changedRefs));
  }

  private static SourceCall parseSourceRef(String raw) {
    if (raw == null) {
      throw sourceReject("INVALID_SOURCE_CALL_REF", "sourceCallRef 不得为 null");
    }
    String ref = raw.strip();
    int colon = ref.lastIndexOf(':');
    if (colon <= 0 || colon == ref.length() - 1) {
      throw sourceReject(
          "INVALID_SOURCE_CALL_REF", "sourceCallRef 形状非法（应为 <packetId>:<callIndex>）: " + raw);
    }
    String packetIdText = ref.substring(0, colon).strip();
    String callIndexText = ref.substring(colon + 1).strip();
    if (packetIdText.isEmpty() || callIndexText.isEmpty()) {
      throw sourceReject(
          "INVALID_SOURCE_CALL_REF", "sourceCallRef 的 packetId/callIndex 不得为空: " + raw);
    }
    int callIndex;
    try {
      callIndex = Integer.parseInt(callIndexText);
    } catch (NumberFormatException e) {
      throw sourceReject("INVALID_SOURCE_CALL_REF", "sourceCallRef 的 callIndex 不是整数: " + raw);
    }
    if (callIndex < 0) {
      throw sourceReject("INVALID_SOURCE_CALL_REF", "sourceCallRef 的 callIndex 不得为负: " + raw);
    }
    try {
      return new SourceCall(DecisionPacketId.parse(packetIdText), callIndex);
    } catch (IllegalArgumentException e) {
      throw sourceReject("INVALID_SOURCE_CALL_REF", "sourceCallRef 的 packetId 非法: " + raw);
    }
  }

  private static SourceRejection sourceReject(String code, String message) {
    return new SourceRejection(code, message);
  }

  private static ToolResult foldBatch(
      ToolWritePort.BatchReceipt receipt, Map<String, Object> view) {
    String commandId =
        receipt.commandIds().isEmpty() ? receipt.correlationId() : receipt.commandIds().get(0);
    return switch (receipt.outcome()) {
      case ToolWritePort.BatchOutcome.Committed committed -> {
        view.put(
            "submission",
            ToolResults.committedView(committed.ref(), commandId, receipt.correlationId()));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.BatchOutcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", receipt.correlationId());
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.BatchOutcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", firstRejectionReason(rejected));
        submission.put("commandId", commandId);
        submission.put("correlationId", receipt.correlationId());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  private static String firstRejectionReason(ToolWritePort.BatchOutcome.Rejected rejected) {
    for (ToolWritePort.Outcome outcome : rejected.outcomes()) {
      if (outcome instanceof ToolWritePort.Outcome.Rejected refusal) {
        return refusal.reason();
      }
    }
    return "批量提交被拒（无逐条拒因）";
  }

  private record SourceCall(DecisionPacketId packetId, int callIndex) {
    private String canonical() {
      return packetId.value() + ":" + callIndex;
    }
  }

  private record SourceUpdates(List<ToolWritePort.Request> requests, List<String> updatedRefs) {}

  private static final class SourceRejection extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private SourceRejection(String code, String message) {
      super(message);
      this.code = Objects.requireNonNull(code, "code");
    }

    private String code() {
      return code;
    }
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      MergedEffectPlan plan, BranchId branch, long baseRevision) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("planId", plan.id().value());
    view.put("tick", plan.tick());
    view.put("participantIds", plan.participantIds().stream().map(DecisionMakerId::value).toList());
    List<Map<String, Object>> effects = new ArrayList<>(plan.orderedEffects().size());
    for (MergedEffect effect : plan.orderedEffects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("toolName", effect.toolName());
      row.put("argsJson", effect.argsJson());
      row.put("args", parseObject(effect.argsJson()));
      row.put("sourceCallRefs", effect.sourceCallRefs());
      effects.add(row);
    }
    view.put("orderedEffects", effects);
    view.put("sources", plan.sources());
    plan.outcome().ifPresent(outcome -> view.put("outcome", outcome));
    view.put("branch", branch.value());
    view.put("baseRevision", baseRevision);
    return view;
  }

  private static Map<String, Object> parseObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("合并计划 argsJson 不是对象: " + e.getMessage(), e);
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
