package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
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
 * 决策包 / 合并效果集命令载荷的**唯一拼写点**（propose/intent 写 {@code ga.UpsertDecisionPacket}；GM 写 {@code
 * ga.DecideDecisionPacket}；D3 的 GM 工具与执行器写 {@code ga.UpsertMergedEffectPlan}）。★ 2026-10-12 批次
 * 3a：四条命令连同两张表自 {@code sd.*} 改名/迁入 {@code ga.*}，<b>载荷形状逐字未变</b>（改的只是类型串）。
 *
 * <p>★ 工具与 handler 之间的线格式是扁平 JSON（handler 用 {@code SdPayloads} 逐字段读）；把领域对象 → 载荷的映射收在一处， 避免
 * propose/intent/GM/执行器各拼一份、加字段时静默丢一个。
 *
 * <p>★ D3 起本类对 {@code app.decision.DecisionEffectExecutor} 公开（outcome 回写要在同一批里重建整包载荷）。
 *
 * <p>★ 2026-10-12 A1：{@link #fold} 的结局类型由 core 的 {@code CommandResult} 换成 {@link
 * ToolWritePort.Outcome} （本模块摘掉 {@code simos-core} 依赖）；载荷拼装（本类的其余方法）与结局语义**一字未改**。
 */
public final class DecisionPacketPayloads {

  private DecisionPacketPayloads() {}

  /** {@code ga.UpsertDecisionPacket} 的整包载荷（propose 追加 call / intent 改写 / 执行器 outcome 回写共用）。 */
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
    return ToolResults.json(payload);
  }

  /** {@code ga.SubmitDecisionPacket} 载荷。 */
  public static String submit(String packetId, String proposerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packetId);
    payload.put("proposerId", proposerId);
    return ToolResults.json(payload);
  }

  /**
   * {@code ga.DecideDecisionPacket} 载荷（{@code callIndexes} 为空 ⇒ 整包口径；MERGE 时带 {@code
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
    return ToolResults.json(payload);
  }

  /** {@code ga.UpsertMergedEffectPlan} 的整包扁平载荷（GM 建计划 / 执行器 outcome 回写共用）。 */
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
    return ToolResults.json(payload);
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

  /**
   * 命令三结局的统一折叠（propose/submit/intent/GM decide 共用一份，避免四处各写一遍）。
   *
   * <p>★★ <b>2026-10-12 A1：改吃 {@link ToolWritePort.Outcome}</b>（core 的 {@code CommandResult}
   * 的镜像）——本模块的 9 个工具改走 {@link ToolWritePort} 后不再看得见 core 类型。结局语义逐条同形（{@code Committed}/{@code
   * Conflict}/{@code Rejected}），且折叠出的 {@code submission} 块与 {@link ToolResults#fold} **逐字段逐序相同**
   * （{@code result}/{@code ref}|{@code current}/{@code reason}/{@code commandId}/{@code
   * correlationId}）。
   *
   * <p>★ <b>为什么本类还留着自己这一份、不直接调 {@link ToolResults#fold}</b>：两者的差别只有一处 —— {@code Committed} 时本类要把
   * {@code submission} 键**追加在工具自己的视图之后**（{@code ToolResults.fold} 折的是一份只有结局的独立 视图）⇒
   * 保留本方法才能让这五个工具的返回字段**顺序**逐字不变（{@code packetId/status/callIndex/tool/targets/ preview} 在前、{@code
   * submission} 在后）。
   *
   * @param outcome 窄写口给的三结局之一（分支/命令 id 由装配面按端口适配规则生成）
   * @param view 工具自己的视图（本方法在它之上追加 {@code submission} 键）
   * @param commandId 本次提交的命令 id（单命令链 ⇒ {@code correlationId} 同值，与今天同口径）
   */
  static ToolResult fold(
      ToolWritePort.Outcome outcome, Map<String, Object> view, String commandId) {
    return switch (outcome) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }
}
