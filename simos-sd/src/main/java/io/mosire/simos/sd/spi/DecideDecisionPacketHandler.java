package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@code sd.DecideDecisionPacket} 命令的处理器（D2 决策包计划 §2）：GM 对 PENDING 决策包做整包/逐 call true-positive 裁决。
 *
 * <pre>{@code {"id":"pkt-...","decision":"APPROVE|DENY|MERGE","note":"...","callIndexes":[0,1]?,
 *  "mergedPlanId":"merge-360-1"?,"decidedBy":"external-mcp"}}</pre>
 *
 * <p>★ 语义：
 *
 * <ul>
 *   <li>{@code APPROVE} 无 {@code callIndexes}（或空数组）⇒ 全部 call {@link CallStatus#APPROVED}，包 {@link
 *       PacketStatus#APPROVED}；
 *   <li>{@code APPROVE} 带 {@code callIndexes} ⇒ 点名的 APPROVED、其余 REJECTED；全部点名 ⇒ APPROVED，部分 ⇒
 *       PARTIALLY_APPROVED；
 *   <li>{@code DENY} ⇒ 全部 call REJECTED，包 REJECTED；
 *   <li>{@code MERGE} ⇒ 必须给 {@code mergedPlanId} 且目标 plan 必须已存在；包置 {@link PacketStatus#MERGED}，
 *       **当前 PENDING** 的 call 置 {@link CallStatus#MERGED} 并写 {@code mergedPlanId}；{@code
 *       callIndexes} 与 MERGE 互斥。
 * </ul>
 *
 * <p>★ {@code decidedBy} 由 payload 带入，但**由 GM 工具从 {@code context.identity()} 派生后写入**——handler
 * 结构性看不见 ToolContext，故身份只在这一条窄口上过界；模型自报的值不会被 GM 工具采用。
 *
 * <p>★ {@code decidedAtRevision} 用 base revision（{@code state.meta().ref().revision().value()}）。
 */
public final class DecideDecisionPacketHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "sd.DecideDecisionPacket";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SdPayloads.parse(payloadJson);
    DecisionPacketId id = DecisionPacketId.parse(SdPayloads.requireText(payload, "id"));
    return List.of(ResourcePaths.sd("decision-packet", id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String packetForLog = null;
    String decisionForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionPacketId id = DecisionPacketId.parse(SdPayloads.requireText(payload, "id"));
      packetForLog = id.value();
      String decision = SdPayloads.requireText(payload, "decision").trim();
      decisionForLog = decision;
      String decidedBy = SdPayloads.requireText(payload, "decidedBy");
      Optional<String> note = SdPayloads.optionalText(payload, "note");
      Optional<Set<Integer>> callIndexes = optionalCallIndexes(payload, "callIndexes");
      DecisionPacket packet = base.decisionPackets().get(id);
      if (packet == null) {
        return rejected("决策包不存在: " + id.value(), "packet", packetForLog);
      }
      if (packet.status() != PacketStatus.PENDING) {
        return rejected(
            "只有 PENDING 决策包可裁决，当前状态: " + packet.status() + "（" + id.value() + "）",
            "packet",
            packetForLog,
            "status",
            packet.status());
      }
      long decidedAtRevision = state.meta().ref().revision().value();
      Optional<String> mergedPlanId = SdPayloads.optionalText(payload, "mergedPlanId");
      if (!"APPROVE".equals(decision) && !"DENY".equals(decision) && !"MERGE".equals(decision)) {
        return rejected(
            "decision 只允许 APPROVE|DENY|MERGE: " + decision,
            "packet",
            packetForLog,
            "decision",
            decisionForLog);
      }
      if ("MERGE".equals(decision)) {
        if (callIndexes.isPresent()) {
          return rejected(
              "MERGE 与 callIndexes 互斥：MERGE 是整包并入合并计划",
              "packet",
              packetForLog,
              "decision",
              decisionForLog);
        }
        if (mergedPlanId.isEmpty()) {
          return rejected(
              "decision=MERGE 时必须给 mergedPlanId",
              "packet",
              packetForLog,
              "decision",
              decisionForLog);
        }
        MergedEffectPlanId planId = MergedEffectPlanId.parse(mergedPlanId.get());
        MergedEffectPlan plan = base.mergedEffectPlans().get(planId);
        if (plan == null) {
          return rejected(
              "合并计划不存在: " + planId.value(), "packet", packetForLog, "mergedPlan", planId.value());
        }
        Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
        DecisionPacket mergedPacket =
            packet.withDecision(
                PacketStatus.MERGED,
                mergePending(packet.calls(), planId.value()),
                Optional.of(decidedBy),
                OptionalLong.of(decidedAtRevision),
                note);
        next.put(id, mergedPacket);
        EventLog.channel(SdLog.decision())
            .info(
                LogEvent.of(
                    "SD_DECIDE_DECISION_PACKET_APPLIED",
                    SdLogSource.SD_DECISION,
                    "packet",
                    id.value(),
                    "decision",
                    decision,
                    "status",
                    mergedPacket.status(),
                    "calls",
                    mergedPacket.calls().size(),
                    "approved",
                    countStatus(mergedPacket.calls(), CallStatus.APPROVED),
                    "rejected",
                    countStatus(mergedPacket.calls(), CallStatus.REJECTED),
                    "merged",
                    countStatus(mergedPacket.calls(), CallStatus.MERGED),
                    "mergedPlan",
                    planId.value(),
                    "decidedBy",
                    decidedBy,
                    "notePresent",
                    note.isPresent()));
        return new HandlerOutcome.Applied(
            SdChangeSet.between(base, base.withDecisionPackets(next)));
      }
      if (mergedPlanId.isPresent()) {
        return rejected(
            "mergedPlanId 只在 decision=MERGE 时有意义",
            "packet",
            packetForLog,
            "decision",
            decisionForLog);
      }
      List<FormattedCall> nextCalls =
          "DENY".equals(decision)
              ? rejectAll(packet.calls())
              : approve(packet.calls(), callIndexes.orElse(Set.of()));
      if ("APPROVE".equals(decision) && callIndexes.isPresent() && nextCalls.isEmpty()) {
        return rejected(
            "决策包没有可裁决的 call: " + id.value(), "packet", packetForLog, "decision", decisionForLog);
      }
      PacketStatus nextStatus =
          "DENY".equals(decision)
              ? PacketStatus.REJECTED
              : approvedStatus(packet.calls(), callIndexes);
      Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
      DecisionPacket decidedPacket =
          packet.withDecision(
              nextStatus,
              nextCalls,
              Optional.of(decidedBy),
              OptionalLong.of(decidedAtRevision),
              note);
      next.put(id, decidedPacket);
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_DECIDE_DECISION_PACKET_APPLIED",
                  SdLogSource.SD_DECISION,
                  "packet",
                  id.value(),
                  "decision",
                  decision,
                  "status",
                  nextStatus,
                  "calls",
                  nextCalls.size(),
                  "approved",
                  countStatus(nextCalls, CallStatus.APPROVED),
                  "rejected",
                  countStatus(nextCalls, CallStatus.REJECTED),
                  "merged",
                  countStatus(nextCalls, CallStatus.MERGED),
                  "decidedBy",
                  decidedBy,
                  "notePresent",
                  note.isPresent()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionPackets(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "packet", packetForLog, "decision", decisionForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_DECISION,
        "SD_DECIDE_DECISION_PACKET_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  private static long countStatus(List<FormattedCall> calls, CallStatus status) {
    return calls.stream().filter(call -> call.status() == status).count();
  }

  private static List<FormattedCall> rejectAll(List<FormattedCall> calls) {
    List<FormattedCall> out = new ArrayList<>(calls.size());
    for (FormattedCall call : calls) {
      out.add(call.withStatus(CallStatus.REJECTED));
    }
    return List.copyOf(out);
  }

  /** MERGE：把**当前 PENDING**的 call 置 MERGED + 写 mergedPlanId；已 APPROVED/REJECTED 的原样保留。 */
  private static List<FormattedCall> mergePending(List<FormattedCall> calls, String mergedPlanId) {
    List<FormattedCall> out = new ArrayList<>(calls.size());
    for (FormattedCall call : calls) {
      if (call.status() == CallStatus.PENDING) {
        out.add(call.withMergedPlanId(Optional.of(mergedPlanId)).withStatus(CallStatus.MERGED));
      } else {
        out.add(call);
      }
    }
    return List.copyOf(out);
  }

  private static List<FormattedCall> approve(List<FormattedCall> calls, Set<Integer> selected) {
    if (!selected.isEmpty()) {
      for (int index : selected) {
        if (!containsCallIndex(calls, index)) {
          throw new IllegalArgumentException("callIndexes 含包内不存在的 callIndex: " + index);
        }
      }
    }
    List<FormattedCall> out = new ArrayList<>(calls.size());
    for (FormattedCall call : calls) {
      boolean approved = selected.isEmpty() || selected.contains(call.callIndex());
      out.add(call.withStatus(approved ? CallStatus.APPROVED : CallStatus.REJECTED));
    }
    return List.copyOf(out);
  }

  /** 包级汇总：无 callIndexes（或空）⇒ APPROVED；全部 call 都被点名 ⇒ APPROVED；否则 PARTIALLY_APPROVED。 */
  private static PacketStatus approvedStatus(
      List<FormattedCall> calls, Optional<Set<Integer>> callIndexes) {
    if (callIndexes.isEmpty() || callIndexes.get().isEmpty()) {
      return PacketStatus.APPROVED;
    }
    Set<Integer> selected = callIndexes.get();
    for (FormattedCall call : calls) {
      if (!selected.contains(call.callIndex())) {
        return PacketStatus.PARTIALLY_APPROVED;
      }
    }
    return PacketStatus.APPROVED;
  }

  private static boolean containsCallIndex(List<FormattedCall> calls, int callIndex) {
    for (FormattedCall call : calls) {
      if (call.callIndex() == callIndex) {
        return true;
      }
    }
    return false;
  }

  /** 可选 {@code callIndexes} 数组：键缺席/为 null ⇒ 空（整包口径）；给了必须是非空数组且元素不重复。 */
  private static Optional<Set<Integer>> optionalCallIndexes(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [整数…] 数组: " + payload);
    }
    Set<Integer> out = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isIntegralNumber() || !element.canConvertToInt()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是整数: " + element);
      }
      if (!out.add(element.asInt())) {
        throw new IllegalArgumentException("字段 " + field + " 的元素不得重复: " + element);
      }
    }
    return Optional.of(out);
  }
}
