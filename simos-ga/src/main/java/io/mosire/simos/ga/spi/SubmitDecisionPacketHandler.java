package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ga.SubmitDecisionPacket} 命令的处理器（D2 决策包计划 §2；<b>★ 2026-10-12 批次 3a 自 {@code
 * simos-sd/sd.spi} 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）：把 DRAFT 决策包置为 {@link
 * PacketStatus#PENDING}。
 *
 * <pre>{@code {"id":"pkt-dm-1-360","proposerId":"dm-1"}}</pre>
 *
 * <p>★ <b>仅 DRAFT 可提交</b>：非 DRAFT ⇒ 具名拒（工具侧对"已 PENDING"走幂等成功，不落到这里；已裁决一律拒）。
 *
 * <p>★ 全部 call 保持原状态（拟稿期只产生 PENDING；本命令不改逐 call 状态）。
 *
 * <p>★ 改名理由与权限口径同 {@link UpsertDecisionPacketHandler} 的类注（{@code namespaceOf} 按类型前缀选 codec； <b>不打
 * {@code DmUsableCommand} ⇒ 仅 GM</b>）。
 */
public final class SubmitDecisionPacketHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "ga.SubmitDecisionPacket";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = GaPayloads.parse(payloadJson);
    return List.of(
        UpsertDecisionPacketHandler.decisionPacketPath(
            GaPayloads.requireText(payload, "proposerId")));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String packetForLog = null;
    String proposerForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      DecisionPacketId id = DecisionPacketId.parse(GaPayloads.requireText(payload, "id"));
      packetForLog = id.value();
      DecisionMakerId proposerId =
          DecisionMakerId.parse(GaPayloads.requireText(payload, "proposerId"));
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
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_SUBMIT_DECISION_PACKET_APPLIED",
                  GaLogSource.GA_DECISION,
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
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withDecisionPackets(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "packet", packetForLog, "proposer", proposerForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GaPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GaPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_SUBMIT_DECISION_PACKET_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
