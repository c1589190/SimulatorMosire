package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code sd.UpsertDecisionPacket} 命令的处理器（D2 决策包计划 §2）：整包写入/覆盖一个 {@link DecisionPacket}。
 *
 * <pre>{@code
 * {"id":"pkt-dm-1-360","branch":"main","tick":360,"proposerId":"dm-1","status":"DRAFT",
 *  "intent":"...","createdAtRevision":12,
 *  "decidedBy":null,"decidedAtRevision":null,"reasonInfoId":null,"decisionNote":null,
 *  "calls":[{"callIndex":0,"toolName":"simos.unit.raiseUnit","argsJson":"{...}",
 *            "targets":[{"namespace":"map","path":"Map1/region/r1"}],
 *            "previewJson":"{...}","draftChecks":["scope-ok"],"status":"PENDING","mergedPlanId":null}]}
 * }</pre>
 *
 * <p>★ 同 id **幂等替换**（整包覆盖）：propose 追加 call、intent 改写都走这一条命令 ⇒ 一条 revision。
 *
 * <p>★ 状态期后备在 {@link SdState} 构造期（键 == 值内 id、proposerId 存在、callIndex 严格递增、MERGED 必带
 * mergedPlanId）；handler 期只做具名前置拒（决策人不存在），让拒因更可读。
 *
 * <p>★ {@link CommandTargets#targetPaths} 返回 {@code decision-packet/<proposerId>}——**不是** packet
 * id：决策人的 自指前缀按人拼、tick 不同也同前缀（{@code DecisionCallerFactory.selfDecisionScope} 的第二条前缀）。
 */
public final class UpsertDecisionPacketHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "sd.UpsertDecisionPacket";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SdPayloads.parse(payloadJson);
    return List.of(decisionPacketPath(SdPayloads.requireText(payload, "proposerId")));
  }

  /** 决策人自己的决策包前缀路径（{@code decision-packet/<proposerId>}）。 */
  static String decisionPacketPath(String proposerId) {
    return ResourcePaths.sd("decision-packet", proposerId);
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
      String branch = SdPayloads.requireText(payload, "branch");
      long tick = SdPayloads.requireLong(payload, "tick");
      DecisionMakerId proposerId =
          DecisionMakerId.parse(SdPayloads.requireText(payload, "proposerId"));
      proposerForLog = proposerId.value();
      PacketStatus status = SdPayloads.requirePacketStatus(payload, "status");
      String intent = SdPayloads.optionalText(payload, "intent").orElse("");
      long createdAtRevision = SdPayloads.requireLong(payload, "createdAtRevision");
      List<FormattedCall> calls = SdPayloads.optionalFormattedCalls(payload, "calls");
      Optional<String> decidedBy = SdPayloads.optionalText(payload, "decidedBy");
      OptionalLong decidedAtRevision = SdPayloads.optionalLongValue(payload, "decidedAtRevision");
      Optional<String> reasonInfoId = SdPayloads.optionalText(payload, "reasonInfoId");
      Optional<String> decisionNote = SdPayloads.optionalText(payload, "decisionNote");

      // ★ 新建包要求 proposer 存在；已有历史包的 outcome/status 回写允许 proposer 已被删除（D3 执行器写 outcome
      //   时会覆盖同一个 id），否则"删了决策人 ⇒ 历史包永远无法回写 outcome"。
      boolean newPacket = !base.decisionPackets().containsKey(id);
      if (newPacket && !base.decisionMakers().containsKey(proposerId)) {
        return rejected(
            "决策人不存在: " + proposerId.value(), "packet", packetForLog, "proposer", proposerForLog);
      }
      DecisionPacket packet =
          new DecisionPacket(
              id,
              branch,
              tick,
              proposerId,
              status,
              intent,
              calls,
              createdAtRevision,
              decidedBy,
              decidedAtRevision,
              reasonInfoId,
              decisionNote);
      Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
      next.put(id, packet); // 整包覆盖，同 id 幂等替换
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_UPSERT_DECISION_PACKET_APPLIED",
                  SdLogSource.SD_DECISION,
                  "packet",
                  id.value(),
                  "branch",
                  branch,
                  "tick",
                  tick,
                  "proposer",
                  proposerId.value(),
                  "status",
                  status,
                  "calls",
                  calls.size(),
                  "newPacket",
                  newPacket,
                  "decidedBy",
                  decidedBy.orElse("-"),
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
        "SD_UPSERT_DECISION_PACKET_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
