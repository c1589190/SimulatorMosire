package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffect;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.util.spi.CommandTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 决策包 / 合并效果集命令载荷的**唯一拼写点**（propose/intent 写 {@code sd.UpsertDecisionPacket}；GM 写 {@code
 * sd.DecideDecisionPacket}；D3 的 GM 工具与执行器写 {@code sd.UpsertMergedEffectPlan}）。
 *
 * <p>★ 工具与 handler 之间的线格式是扁平 JSON（handler 用 {@code SdPayloads} 逐字段读）；把领域对象 → 载荷的映射收在一处， 避免
 * propose/intent/GM/执行器各拼一份、加字段时静默丢一个。
 *
 * <p>★ D3 起本类对 {@code app.decision.DecisionEffectExecutor} 公开（outcome 回写要在同一批里重建整包载荷）。
 */
public final class DecisionPacketPayloads {

  private DecisionPacketPayloads() {}

  /** {@code sd.UpsertDecisionPacket} 的整包载荷（propose 追加 call / intent 改写 / 执行器 outcome 回写共用）。 */
  public static String upsert(DecisionPacket packet) {
    Objects.requireNonNull(packet, "packet");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packet.id().value());
    payload.put("branch", packet.branch());
    payload.put("tick", packet.tick());
    payload.put("proposerId", packet.proposerId().value());
    payload.put("status", packet.status().name());
    payload.put("intent", packet.intent());
    payload.put("createdAtRevision", packet.createdAtRevision());
    payload.put("decidedBy", packet.decidedBy().orElse(null));
    payload.put(
        "decidedAtRevision",
        packet.decidedAtRevision().isPresent() ? packet.decidedAtRevision().getAsLong() : null);
    payload.put("reasonInfoId", packet.reasonInfoId().orElse(null));
    payload.put("decisionNote", packet.decisionNote().orElse(null));
    List<Map<String, Object>> calls = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      calls.add(callView(call));
    }
    payload.put("calls", calls);
    return ToolSupport.json(payload);
  }

  /** {@code sd.SubmitDecisionPacket} 载荷。 */
  public static String submit(String packetId, String proposerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packetId);
    payload.put("proposerId", proposerId);
    return ToolSupport.json(payload);
  }

  /**
   * {@code sd.DecideDecisionPacket} 载荷（{@code callIndexes} 为空 ⇒ 整包口径；MERGE 时带 {@code
   * mergedPlanId}）。
   */
  static String decide(
      String packetId,
      String decision,
      Optional<String> note,
      List<Integer> callIndexes,
      String decidedBy,
      Optional<String> mergedPlanId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packetId);
    payload.put("decision", decision);
    payload.put("decidedBy", decidedBy);
    if (note != null && note.isPresent()) {
      payload.put("note", note.get());
    }
    if (callIndexes != null && !callIndexes.isEmpty()) {
      payload.put("callIndexes", List.copyOf(callIndexes));
    }
    if (mergedPlanId != null && mergedPlanId.isPresent()) {
      payload.put("mergedPlanId", mergedPlanId.get());
    }
    return ToolSupport.json(payload);
  }

  /** {@code sd.UpsertMergedEffectPlan} 的整包扁平载荷（GM 建计划 / 执行器 outcome 回写共用）。 */
  public static String mergedPlan(MergedEffectPlan plan) {
    Objects.requireNonNull(plan, "plan");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", plan.id().value());
    payload.put("tick", plan.tick());
    payload.put("participantIds", plan.participantIds().stream().map(id -> id.value()).toList());
    List<Map<String, Object>> effects = new ArrayList<>(plan.orderedEffects().size());
    for (MergedEffect effect : plan.orderedEffects()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("toolName", effect.toolName());
      row.put("argsJson", effect.argsJson());
      row.put("sourceCallRefs", effect.sourceCallRefs());
      effects.add(row);
    }
    payload.put("orderedEffects", effects);
    payload.put("sources", plan.sources());
    payload.put("reasonInfoId", plan.reasonInfoId().orElse(null));
    payload.put("outcome", plan.outcome().orElse(null));
    return ToolSupport.json(payload);
  }

  /** 一条 call 的线格式（{@code targets} 逐条 {namespace,path}；{@code outcomeJson} 未执行 ⇒ null）。 */
  public static Map<String, Object> callView(FormattedCall call) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("callIndex", call.callIndex());
    row.put("toolName", call.toolName());
    row.put("argsJson", call.argsJson());
    List<Map<String, Object>> targets = new ArrayList<>(call.targets().size());
    for (CommandTarget target : call.targets()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("namespace", target.namespace());
      item.put("path", target.path());
      targets.add(item);
    }
    row.put("targets", targets);
    row.put("previewJson", call.previewJson());
    row.put("draftChecks", call.draftChecks());
    row.put("status", call.status().name());
    row.put("mergedPlanId", call.mergedPlanId().orElse(null));
    row.put("outcomeJson", call.outcomeJson().orElse(null));
    return row;
  }

  /** 命令三结局的统一折叠（propose/submit/intent/GM decide 共用一份，避免四处各写一遍）。 */
  static ToolResult fold(CommandResult result, Map<String, Object> view, String commandId) {
    return switch (result) {
      case CommandResult.Committed committed -> {
        view.put("submission", ToolSupport.committedView(committed.ref(), commandId, commandId));
        yield ToolSupport.ok(view);
      }
      case CommandResult.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolSupport.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }
}
