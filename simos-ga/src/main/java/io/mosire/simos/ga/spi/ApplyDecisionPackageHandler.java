package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.model.DecisionRecord;
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

/**
 * {@code ga.ApplyDecisionPackage}：决策包状态的<b>唯一内部写口</b>（设计书 §6.1）。
 *
 * <p>所有包状态写入（DM 回合 flush、GM decide/itemDecide/commit）都必须经这一条命令进入 {@code Command -> ChangeSet ->
 * Revision}。它不直接暴露给 DM/GM 工具，而由宿主侧端口/回合 finalizer 生成。
 *
 * <p>乐观并发：{@code expectedContentVersion} 对新建必须为 0；对更新必须等于当前 {@link
 * DecisionRecord#packageState()}{@code .contentVersion()}。不匹配 ⇒ 具名 {@code PACKAGE_VERSION_STALE}，
 * 零状态变化。记录自身的 key/id/proposer/callMeta 一致性由 {@link DecisionRecord} 构造期把守，本 handler 再判 更新时的
 * branch/tick/proposer 身份不变。
 */
public final class ApplyDecisionPackageHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "ga.ApplyDecisionPackage";

  /** 版本不匹配的具名拒绝码。 */
  public static final String PACKAGE_VERSION_STALE = "PACKAGE_VERSION_STALE";

  /** 同一 id 的包不允许改 branch/tick/proposer。 */
  public static final String PACKAGE_IDENTITY_MISMATCH = "PACKAGE_IDENTITY_MISMATCH";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = GaPayloads.parse(payloadJson);
    DecisionRecord record = GaCodec.decodeApplyDecisionPackageRecord(payload.get("record"));
    return List.of(ResourcePaths.ga("decision-package", record.id().value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String packetForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      long expectedContentVersion = GaPayloads.requireLong(payload, "expectedContentVersion");
      if (expectedContentVersion < 0L) {
        throw new IllegalArgumentException(
            "expectedContentVersion 不得为负: " + expectedContentVersion);
      }
      DecisionRecord record = GaCodec.decodeApplyDecisionPackageRecord(payload.get("record"));
      packetForLog = record.id().value();
      String stateBranch = state.meta().ref().branch().value();
      if (!record.branch().equals(stateBranch)) {
        throw new IllegalArgumentException(
            "决策包 branch 必须等于命令目标分支: record=" + record.branch() + " commandBranch=" + stateBranch);
      }

      DecisionRecord existing = base.decisionPackets().get(record.id());
      long currentContentVersion = existing == null ? 0L : existing.packageState().contentVersion();
      if (existing == null && expectedContentVersion != 0L) {
        return rejectedVersionStale(expectedContentVersion, currentContentVersion, packetForLog);
      }
      if (existing != null && expectedContentVersion != currentContentVersion) {
        return rejectedVersionStale(expectedContentVersion, currentContentVersion, packetForLog);
      }
      if (existing != null && !sameIdentity(existing, record)) {
        String reason =
            PACKAGE_IDENTITY_MISMATCH
                + "：同一决策包 id 不允许改 branch/tick/proposer（id="
                + record.id().value()
                + "）";
        GaPayloads.logRejected(
            GaLog.decision(),
            GaLogSource.GA_DECISION,
            "GA_APPLY_DECISION_PACKAGE_REJECTED",
            reason,
            "packet",
            packetForLog);
        return new HandlerOutcome.Rejected(reason);
      }

      Map<io.mosire.simos.ga.id.DecisionPacketId, DecisionRecord> next =
          new LinkedHashMap<>(base.decisionPackets());
      next.put(record.id(), record);
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "GA_APPLY_DECISION_PACKAGE_APPLIED",
                  GaLogSource.GA_DECISION,
                  "packet",
                  record.id().value(),
                  "proposer",
                  record.proposerId().value(),
                  "tick",
                  record.tick(),
                  "packageState",
                  record.packageState().pkg().state(),
                  "contentVersion",
                  record.packageState().contentVersion(),
                  "oldContentVersion",
                  currentContentVersion,
                  "newPacket",
                  existing == null,
                  "callCount",
                  record.packageState().pkg().items().size(),
                  "decisionPackets",
                  next.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withDecisionPackets(next)));
    } catch (IllegalArgumentException e) {
      GaPayloads.logRejected(
          GaLog.decision(),
          GaLogSource.GA_DECISION,
          "GA_APPLY_DECISION_PACKAGE_REJECTED",
          e.getMessage(),
          "packet",
          packetForLog);
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static boolean sameIdentity(DecisionRecord existing, DecisionRecord next) {
    return existing.branch().equals(next.branch())
        && existing.tick() == next.tick()
        && existing.proposerId().equals(next.proposerId())
        && existing.createdAtRevision() == next.createdAtRevision();
  }

  private static HandlerOutcome rejectedVersionStale(
      long expectedContentVersion, long currentContentVersion, String packetId) {
    String reason =
        PACKAGE_VERSION_STALE
            + "：expected="
            + expectedContentVersion
            + " current="
            + currentContentVersion
            + "（决策包 "
            + packetId
            + "；零状态变化）";
    GaPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "GA_APPLY_DECISION_PACKAGE_REJECTED",
        reason,
        "packet",
        packetId,
        "expected",
        expectedContentVersion,
        "current",
        currentContentVersion);
    return new HandlerOutcome.Rejected(reason);
  }
}
