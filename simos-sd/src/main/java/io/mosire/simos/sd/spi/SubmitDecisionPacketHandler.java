package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.SubmitDecisionPacket} 命令的处理器（D2 决策包计划 §2）：把 DRAFT 决策包置为 {@link PacketStatus#PENDING}。
 *
 * <pre>{@code {"id":"pkt-dm-1-360","proposerId":"dm-1"}}</pre>
 *
 * <p>★ **仅 DRAFT 可提交**：非 DRAFT ⇒ 具名拒（工具侧对"已 PENDING"走幂等成功，不落到这里；已裁决一律拒）。
 *
 * <p>★ 全部 call 保持原状态（拟稿期只产生 PENDING；本命令不改逐 call 状态）。
 */
public final class SubmitDecisionPacketHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "sd.SubmitDecisionPacket";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SdPayloads.parse(payloadJson);
    return List.of(
        UpsertDecisionPacketHandler.decisionPacketPath(
            SdPayloads.requireText(payload, "proposerId")));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String packetForLog = null;
    String proposerForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionPacketId id = DecisionPacketId.parse(SdPayloads.requireText(payload, "id"));
      packetForLog = id.value();
      DecisionMakerId proposerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "proposerId"));
      proposerForLog = proposerId.value();
      DecisionPacket packet = base.decisionPackets().get(id);
      if (packet == null) {
        return rejected("决策包不存在: " + id.value(), "packet", packetForLog);
      }
      if (!packet.proposerId().equals(proposerId)) {
        return rejected(
            "决策包不属于该决策人: " + id.value() + " 的 proposer=" + packet.proposerId().value(),
            "packet",
            packetForLog,
            "proposer",
            proposerForLog);
      }
      if (packet.status() != PacketStatus.DRAFT) {
        return rejected(
            "只有 DRAFT 决策包可提交，当前状态: " + packet.status() + "（" + id.value() + "）",
            "packet",
            packetForLog,
            "status",
            packet.status());
      }
      Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
      next.put(id, packet.withStatus(PacketStatus.PENDING));
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_SUBMIT_DECISION_PACKET_APPLIED",
                  SdLogSource.SD_DECISION,
                  "packet",
                  id.value(),
                  "proposer",
                  proposerId.value(),
                  "fromStatus",
                  packet.status(),
                  "toStatus",
                  PacketStatus.PENDING,
                  "calls",
                  packet.calls().size(),
                  "decisionPackets",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionPackets(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "packet", packetForLog, "proposer", proposerForLog);
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
        "SD_SUBMIT_DECISION_PACKET_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
