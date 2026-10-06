package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.state.SdState;
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
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionPacketId id = DecisionPacketId.parse(SdPayloads.requireText(payload, "id"));
      DecisionMakerId proposerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "proposerId"));
      DecisionPacket packet = base.decisionPackets().get(id);
      if (packet == null) {
        return new HandlerOutcome.Rejected("决策包不存在: " + id.value());
      }
      if (!packet.proposerId().equals(proposerId)) {
        return new HandlerOutcome.Rejected(
            "决策包不属于该决策人: " + id.value() + " 的 proposer=" + packet.proposerId().value());
      }
      if (packet.status() != PacketStatus.DRAFT) {
        return new HandlerOutcome.Rejected(
            "只有 DRAFT 决策包可提交，当前状态: " + packet.status() + "（" + id.value() + "）");
      }
      Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
      next.put(id, packet.withStatus(PacketStatus.PENDING));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionPackets(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
