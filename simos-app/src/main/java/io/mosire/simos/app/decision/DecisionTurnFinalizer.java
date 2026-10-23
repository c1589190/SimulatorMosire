package io.mosire.simos.app.decision;

import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.ga.decision.SimosDecisionClock;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.write.RequestTool;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 决策回合统一结算器（设计书 §7.2）：把本回合 {@link PendingDecisionQueue} 里的 Ask 条目追加进 {@code pkt-<dm>-<tick>}，一次
 * {@code ga.ApplyDecisionPackage} 落一条 revision。
 *
 * <p>显式 intent/confirm/revise/drop/reopen 已由 DM 工具直接写 {@link DecisionRecord}；本类只负责回合末 flush：按
 * AgentLib 状态机 propose/append，生成每条新条目的 {@link DecisionCallMeta}，并在纯文本回合 没有包时补一条 {@code
 * simos.ga.request}。
 */
final class DecisionTurnFinalizer {

  private static final LogChannel DECISION = EventLog.channel(AppLog.decision());

  private static final String INITIATOR = "system:decision-turn-finalizer";

  private final CoreSimos core;
  private final DecisionCallPreviewer previewer;
  private final GaToolIdentity identity;
  private final String mapId;
  private final Object finalizeLock = new Object();

  DecisionTurnFinalizer(
      CoreSimos core, DecisionCallPreviewer previewer, GaToolIdentity identity, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.previewer = Objects.requireNonNull(previewer, "previewer");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  /** 正常回合结束的 flush。 */
  void finalizeTurn(
      BranchId branch, DecisionMakerId decisionMakerId, DecisionAgentRunner.DecisionTurn turn) {
    synchronized (finalizeLock) {
      finalizeInternal(branch, decisionMakerId, turn);
    }
  }

  /** 异常/预算中止时的保守结算：消费异常载体交回的本轮队列，走与正常回合完全相同的 §7.2 append/Apply。 队列对象只被消费一次；本方法不复制、不重建队列。 */
  void finalizeAborted(
      BranchId branch, DecisionMakerId decisionMakerId, PendingDecisionQueue pendingDecisions) {
    Objects.requireNonNull(pendingDecisions, "pendingDecisions");
    synchronized (finalizeLock) {
      finalizeInternal(
          branch,
          decisionMakerId,
          new DecisionAgentRunner.DecisionTurn(
              "aborted", 0, List.of(), Optional.empty(), Optional.empty(), pendingDecisions));
    }
  }

  /** 兼容旧调用点：没有异常载体时不制造队列，也不写空包。 */
  void finalizeAborted(BranchId branch, DecisionMakerId decisionMakerId) {
    synchronized (finalizeLock) {
      finalizeInternal(
          branch,
          decisionMakerId,
          new DecisionAgentRunner.DecisionTurn(
              "aborted", 0, List.of(), Optional.empty(), Optional.empty()));
    }
  }

  private void finalizeInternal(
      BranchId branch, DecisionMakerId decisionMakerId, DecisionAgentRunner.DecisionTurn turn) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(turn, "turn");

    Optional<RevisionId> head = core.head(branch);
    if (head.isEmpty()) {
      return;
    }
    RevisionId firstExpected = head.get();
    SimulationState firstState = core.replay(new StateRef(branch, firstExpected));
    long tick = firstState.meta().timestamp().tick();
    long now = logicalNow(tick);
    DecisionPacketId packetId =
        DecisionPacketId.parse("pkt-" + decisionMakerId.value() + "-" + tick);
    PendingDecisionQueue queue = turn.pendingDecisions();
    List<PendingDecisionQueue.PendingEntry> pendingEntries = queue.snapshot();
    Optional<ProposeRequest> queueRequest = queue.toProposeRequest(now);
    String text = turn.lastAssistantText().orElse("").strip();

    for (int attempt = 0; attempt < 2; attempt++) {
      Optional<RevisionId> currentHead = core.head(branch);
      if (currentHead.isEmpty()) {
        return;
      }
      RevisionId expected = currentHead.get();
      SimulationState state = core.replay(new StateRef(branch, expected));
      GaState ga = ToolSupport.gaState(state);
      long currentTick = state.meta().timestamp().tick();
      if (currentTick != tick) {
        throw new IllegalStateException(
            "决策回合 flush 期间世界 tick 改变：开始=" + tick + " 当前=" + currentTick);
      }
      DecisionRecord existing = ga.decisionPackets().get(packetId);
      boolean hasQueued = !pendingEntries.isEmpty();
      boolean needsFallbackRequest = !hasQueued && existing == null && !text.isBlank();
      boolean needsIntent = !text.isBlank() && (existing == null || existing.intent().isBlank());
      if (!hasQueued && !needsFallbackRequest && !needsIntent) {
        DECISION.debug(
            LogEvent.of(
                "DECISION_TURN_FINALIZE_EMPTY",
                AppLogSource.DECISION_TURN,
                "dm",
                decisionMakerId.value(),
                "tick",
                tick,
                "packet",
                packetId.value()));
        return;
      }
      if (existing != null && (hasQueued || needsIntent) && !editable(existing)) {
        throw new IllegalStateException(
            "PACKAGE_CONTENT_FROZEN：包内容已冻结，回合末不能再 append/intent："
                + packetId.value()
                + " state="
                + existing.packageState().pkg().state()
                + " verdicts="
                + existing.packageState().verdicts().size());
      }

      long expectedContentVersion =
          existing == null ? 0L : existing.packageState().contentVersion();
      long baseIndex = existing == null ? 0L : maxIndex(existing) + 1L;
      Map<Integer, DecisionCallMeta> newMeta = new LinkedHashMap<>();
      List<PackageItem> newItems = new ArrayList<>();
      if (hasQueued) {
        for (int i = 0; i < pendingEntries.size(); i++) {
          PendingDecisionQueue.PendingEntry entry = pendingEntries.get(i);
          int index = Math.toIntExact(baseIndex + i);
          PackageItem source = entry.packageItem();
          PackageItem item =
              new PackageItem(
                  index, entry.toolName(), entry.args(), source.argsDigest(), source.summary());
          newItems.add(item);
          newMeta.put(index, previewMeta(entry.toolName(), state, decisionMakerId, entry.args()));
        }
      } else if (needsFallbackRequest) {
        int index = Math.toIntExact(baseIndex);
        Map<String, Object> args = Map.of("text", text);
        newItems.add(
            new PackageItem(
                index,
                RequestTool.NAME,
                args,
                DecisionItemDigest.argsDigest(args),
                DecisionItemDigest.itemSummary(RequestTool.NAME, args)));
        newMeta.put(index, emptyRequestMeta());
      }

      DecisionPackageSnapshot packageState;
      if (existing == null) {
        if (queueRequest.isEmpty() && !needsFallbackRequest) {
          return;
        }
        AccessToken caller = queueRequest.map(ProposeRequest::caller).orElse(AccessToken.DEFAULT);
        ProposeRequest propose =
            new ProposeRequest(
                decisionMakerId.value(),
                caller,
                List.copyOf(newItems),
                SimosDecisionClock.NO_WALL_CLOCK_DEADLINE);
        packageState = DecisionPackageTransitions.propose(packetId.value(), propose, now);
      } else if (newItems.isEmpty()) {
        packageState = existing.packageState();
      } else {
        packageState =
            DecisionPackageTransitions.append(
                existing.packageState(), List.copyOf(newItems), expectedContentVersion, now);
      }

      Map<Integer, DecisionCallMeta> callMeta = new LinkedHashMap<>();
      if (existing != null) {
        callMeta.putAll(existing.callMeta());
      }
      callMeta.putAll(newMeta);
      String intent =
          existing == null ? text : existing.intent().isBlank() ? text : existing.intent();
      DecisionRecord next =
          new DecisionRecord(
              packetId,
              branch.value(),
              tick,
              decisionMakerId,
              intent,
              existing == null ? expected.value() + 1L : existing.createdAtRevision(),
              existing == null ? OptionalLong.empty() : existing.confirmedAtRevision(),
              existing == null ? Optional.empty() : existing.reasonInfoId(),
              existing == null ? OptionalLong.empty() : existing.decidedAtRevision(),
              callMeta,
              packageState);

      String batchId = UUID.randomUUID().toString();
      CommandEnvelope apply =
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              INITIATOR,
              branch,
              expected,
              ApplyDecisionPackageHandler.TYPE,
              GaCodec.encodeApplyDecisionPackagePayload(expectedContentVersion, next));
      BatchResult result = core.submitBatch(List.of(apply));
      if (result instanceof BatchResult.Committed committed) {
        long expectedRevision = expected.value() + 1L;
        if (committed.ref().revision().value() != expectedRevision) {
          throw new IllegalStateException(
              "决策包 flush 已提交但 revision 对账失败：base="
                  + expected.value()
                  + " actual="
                  + committed.ref().revision().value());
        }
        DECISION.info(
            LogEvent.of(
                "DECISION_TURN_FINALIZED",
                AppLogSource.DECISION_TURN,
                "dm",
                decisionMakerId.value(),
                "tick",
                tick,
                "packet",
                packetId.value(),
                "items",
                next.packageState().pkg().items().size(),
                "newItems",
                newItems.size(),
                "revision",
                committed.ref().revision().value()));
        return;
      }
      String reason = failureReason(result);
      boolean retryable =
          result instanceof BatchResult.Conflict
              || (result instanceof BatchResult.Rejected
                  && reason.contains(ApplyDecisionPackageHandler.PACKAGE_VERSION_STALE));
      if (retryable && attempt == 0) {
        DECISION.info(
            LogEvent.of(
                "DECISION_TURN_FINALIZE_RETRY",
                AppLogSource.DECISION_TURN,
                "dm",
                decisionMakerId.value(),
                "tick",
                tick,
                "packet",
                packetId.value(),
                "reason",
                reason));
        continue;
      }
      throw new IllegalStateException(
          "决策回合 flush 失败 decisionMaker="
              + decisionMakerId.value()
              + " packet="
              + packetId.value()
              + " result="
              + reason);
    }
  }

  private DecisionCallMeta previewMeta(
      String toolName,
      SimulationState state,
      DecisionMakerId decisionMakerId,
      Map<String, Object> args) {
    if (RequestTool.NAME.equals(toolName)) {
      return emptyRequestMeta();
    }
    DecisionCallMeta meta = previewer.callMeta(toolName, state, args);
    Optional<String> violation =
        identity.scopeViolation(decisionMakerId, state, meta.targets(), mapId);
    if (violation.isPresent()) {
      throw new IllegalStateException("决策包条目目标越界，回合末拒绝整批入包: " + toolName + " — " + violation.get());
    }
    return meta;
  }

  private static DecisionCallMeta emptyRequestMeta() {
    return new DecisionCallMeta(List.of(), "{}", List.of(), Optional.empty(), Optional.empty());
  }

  private static boolean editable(DecisionRecord record) {
    return record.packageState().pkg().state() == io.mosire.agentlib.decision.PackageState.PROPOSED
        && record.packageState().verdicts().isEmpty();
  }

  private static long maxIndex(DecisionRecord record) {
    long max = -1L;
    for (PackageItem item : record.packageState().pkg().items()) {
      max = Math.max(max, item.index());
    }
    return max;
  }

  /** 逻辑时刻：本批不用墙钟；包 deadline 是 Long.MAX_VALUE 哨兵，now 取非负 tick。 */
  private static long logicalNow(long tick) {
    return Math.max(0L, tick);
  }

  private static String failureReason(BatchResult result) {
    return switch (result) {
      case BatchResult.Committed committed -> "committed@" + committed.ref().revision().value();
      case BatchResult.Conflict conflict ->
          "CONFLICT head=" + conflict.current().revision().value();
      case BatchResult.Rejected rejected -> {
        List<String> reasons = new ArrayList<>();
        for (CommandOutcome outcome : rejected.outcomes()) {
          CommandResult commandResult = outcome.result();
          if (commandResult instanceof CommandResult.Rejected refusal
              && !refusal.reason().startsWith("整批未提交")) {
            reasons.add(refusal.reason());
          }
        }
        yield reasons.isEmpty() ? "REJECTED（无逐条拒因）" : String.join("; ", reasons);
      }
    };
  }
}
