package io.mosire.simos.app.decision;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.DecisionPacketPayloads;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
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
 * ★★ **决策回合统一结算器**（用户 2026-10-22 裁定）：把“LLM 何时算提交/结束”从工具调用时刻改到**回合结束之后**。
 *
 * <p>三种分支：
 *
 * <ol>
 *   <li><b>一直没 submit 就停止说话</b>：本回合已写进 packet 的 intent/calls + 整轮最后一段自然语言，合并成一个最终 PENDING
 *       packet；自然语言作为 NL 决策一起交给 GM；
 *   <li><b>submit 后不再发命令</b>：已写进 packet 的命令照常审批；提交后的纯文本只留审计，不再额外算新的 NL 决策；
 *   <li><b>submit 后又继续 propose/intent</b>：保守合并——整个回合的所有命令 + 整轮最后一段自然语言，统一提交一次。
 * </ol>
 *
 * <p>★★ **为什么必须在这里做**：{@code simos.sd.packet.submit} 在决策回合里只登记标记（见 DecisionAgentRunner 的拦截），因为 LLM
 * 的真实行为是“提交后还可能继续补命令/继续说话”。回合结束后，由本类用 {@link CoreSimos#submitBatch} 一次性落：
 *
 * <ul>
 *   <li>{@code sd.UpsertDecisionPacket}（整理 intent/保留 calls，必要时建空包）
 *   <li>{@code sd.SubmitDecisionPacket}（统一提交）
 *   <li>{@code sd.PutInfo}（审计：为什么自动结算、最后一段文本是什么）
 * </ul>
 *
 * <p>★ 三条命令同 branch/expectedRevision/batchId ⇒ 结算本身只落一条 revision；GM 仍然是最终审批/执行方。 ★ 已经
 * PENDING/APPROVED/REJECTED/MERGED 的 packet 不覆盖（幂等：同一回合重复结算不会再把 GM 已处理的包改回去）。
 */
final class DecisionTurnFinalizer {

  /** 决策回合结算事件的发射通道（分类 = {@link AppLog#decision()}，来源 = {@link AppLogSource#DECISION_TURN}）。 */
  private static final LogChannel DECISION = EventLog.channel(AppLog.decision());

  /** 结算命令的 initiator：系统策略，不代表某个模型工具调用；DM id 写进载荷与审计。 */
  private static final String INITIATOR = "system:decision-turn-finalizer";

  /** 审计 INFO 的地址前缀。 */
  private static final String AUDIT_ADDRESS_PREFIX = "sd:decision-finalize.";

  private static final String AUTO_NO_SUBMIT = "auto-no-submit";
  private static final String SUBMIT_MARKER = "submit-marker";
  private static final String CONTINUED_AFTER_SUBMIT = "continued-after-submit";
  private static final String EMPTY = "empty";

  private final CoreSimos core;

  /**
   * ★★ **并发跑轮时最终化必须串行**：每个 DM 的回合结束都会读 head 再 submit 一批；若两个线程同时读同一 head， 后一个必 Conflict，已写进 packet
   * 的内容就会丢给模型侧“failed”。同一 Shell 只有一个 finalizer 实例， 这把锁保证“读 head + 落最终包 + 审计”是原子的。
   */
  private final Object finalizeLock = new Object();

  DecisionTurnFinalizer(CoreSimos core) {
    this.core = Objects.requireNonNull(core, "core");
  }

  /**
   * 对一个已跑完的决策回合做统一结算；没有决策内容且没有自然语言时零写。
   *
   * @param branch 分支（触发/运行所在分支）
   * @param decisionMakerId 本轮决策人
   * @param turn runner 的回合账（含 submit 标记与整轮最后文本）
   * @throws IllegalStateException 结算批被拒/冲突（响亮失败，不静默丢决策）
   */
  void finalizeTurn(
      BranchId branch, DecisionMakerId decisionMakerId, DecisionAgentRunner.DecisionTurn turn) {
    synchronized (finalizeLock) {
      finalizeInternal(branch, decisionMakerId, turn, null);
    }
  }

  /**
   * ★★ **预算中止/运行时失败时的保守结算**：模型还没“停止说话”，但已经写进 packet 的 intent/calls 不能丢。 这里把已有 DRAFT packet 直接结算成
   * PENDING（不追加自然语言），并落一条 {@code aborted} 审计交给 GM。
   */
  void finalizeAborted(BranchId branch, DecisionMakerId decisionMakerId) {
    synchronized (finalizeLock) {
      finalizeInternal(
          branch,
          decisionMakerId,
          new DecisionAgentRunner.DecisionTurn("aborted", 0, List.of(), Optional.empty()),
          "aborted");
    }
  }

  private void finalizeInternal(
      BranchId branch,
      DecisionMakerId decisionMakerId,
      DecisionAgentRunner.DecisionTurn turn,
      String forcedReason) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(turn, "turn");
    Optional<RevisionId> head = core.head(branch);
    if (head.isEmpty()) {
      return;
    }
    RevisionId expected = head.get();
    SimulationState state = core.replay(new StateRef(branch, expected));
    SdState sd = ToolSupport.sdState(state);
    long tick = state.meta().timestamp().tick();
    DecisionPacketId packetId =
        DecisionPacketId.parse("pkt-" + decisionMakerId.value() + "-" + tick);
    DecisionPacket packet = sd.decisionPackets().get(packetId);
    if (packet != null && packet.status() != PacketStatus.DRAFT) {
      // GM 已经处理过（或重复结算）：不再改写已决包。
      DECISION.debug(
          LogEvent.of(
              "DECISION_TURN_FINALIZE_SKIP",
              AppLogSource.DECISION_TURN,
              "dm",
              decisionMakerId.value(),
              "day",
              tick,
              "packet",
              packetId.value(),
              "status",
              packet.status()));
      return;
    }

    String reason;
    Optional<String> naturalText;
    if (forcedReason != null) {
      reason = forcedReason;
      naturalText = Optional.empty();
    } else if (turn.commandsAfterSubmit()) {
      reason = CONTINUED_AFTER_SUBMIT;
      naturalText = turn.lastAssistantText();
    } else if (turn.packetSubmitSeen()) {
      reason = SUBMIT_MARKER;
      naturalText = turn.lastTextBeforeSubmit();
    } else {
      reason = AUTO_NO_SUBMIT;
      naturalText = turn.lastAssistantText();
    }
    String finalIntent = mergeIntent(packet == null ? "" : packet.intent(), naturalText);
    boolean hasCalls = packet != null && !packet.calls().isEmpty();
    if (packet == null && finalIntent.isBlank()) {
      DECISION.debug(
          LogEvent.of(
              "DECISION_TURN_FINALIZE_EMPTY",
              AppLogSource.DECISION_TURN,
              "dm",
              decisionMakerId.value(),
              "day",
              tick,
              "reason",
              EMPTY));
      return;
    }
    if (packet != null && packet.calls().isEmpty() && finalIntent.isBlank()) {
      DECISION.debug(
          LogEvent.of(
              "DECISION_TURN_FINALIZE_EMPTY",
              AppLogSource.DECISION_TURN,
              "dm",
              decisionMakerId.value(),
              "day",
              tick,
              "packet",
              packetId.value(),
              "reason",
              EMPTY));
      return;
    }

    DecisionPacket draft;
    if (packet == null) {
      draft =
          new DecisionPacket(
              packetId,
              branch.value(),
              tick,
              decisionMakerId,
              PacketStatus.DRAFT,
              finalIntent,
              List.of(),
              expected.value(),
              Optional.empty(),
              OptionalLong.empty(),
              Optional.empty(),
              Optional.empty());
    } else if (finalIntent.equals(packet.intent())) {
      draft = packet;
    } else {
      draft = packet.withIntent(finalIntent);
    }

    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch = new ArrayList<>(3);
    if (packet == null || !finalIntent.equals(packet.intent())) {
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              INITIATOR,
              branch,
              expected,
              "sd.UpsertDecisionPacket",
              DecisionPacketPayloads.upsert(draft)));
    }
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            INITIATOR,
            branch,
            expected,
            "sd.SubmitDecisionPacket",
            DecisionPacketPayloads.submit(packetId.value(), decisionMakerId.value())));
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            INITIATOR,
            branch,
            expected,
            "sd.PutInfo",
            auditPayload(
                packetId.value(),
                decisionMakerId.value(),
                tick,
                reason,
                turn,
                hasCalls,
                finalIntent)));

    BatchResult result = core.submitBatch(batch);
    if (!(result instanceof BatchResult.Committed committed)) {
      throw new IllegalStateException(
          "决策回合统一结算失败 decisionMaker="
              + decisionMakerId.value()
              + " packet="
              + packetId.value()
              + " reason="
              + reason
              + " result="
              + result);
    }
    DECISION.info(
        LogEvent.of(
            "DECISION_TURN_FINALIZED",
            AppLogSource.DECISION_TURN,
            "dm",
            decisionMakerId.value(),
            "day",
            tick,
            "packet",
            packetId.value(),
            "reason",
            reason,
            "calls",
            hasCalls ? "present" : "none",
            "batch",
            batchId,
            "revision",
            committed.ref().revision().value()));
  }

  /** 最后一段文本合并进 packet intent；已有 intent 时不覆盖，只追加“最后陈述”段。 */
  private static String mergeIntent(String existingIntent, Optional<String> naturalText) {
    String existing = existingIntent == null ? "" : existingIntent;
    if (naturalText.isEmpty()) {
      return existing;
    }
    String text = naturalText.get();
    if (text == null || text.isBlank()) {
      return existing;
    }
    if (existing.isBlank()) {
      return text;
    }
    if (existing.contains(text)) {
      return existing;
    }
    return existing + "\n【最后陈述】" + text;
  }

  /** sd.PutInfo 载荷：值本身是 JSON 字符串，结构化内容一眼可读。 */
  private static String auditPayload(
      String packetId,
      String decisionMakerId,
      long tick,
      String reason,
      DecisionAgentRunner.DecisionTurn turn,
      boolean hasCalls,
      String finalIntent) {
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("packetId", packetId);
    audit.put("decisionMakerId", decisionMakerId);
    audit.put("tick", tick);
    audit.put("finalizeReason", reason);
    audit.put("packetSubmitSeen", turn.packetSubmitSeen());
    audit.put("commandsAfterSubmit", turn.commandsAfterSubmit());
    audit.put("hasCalls", hasCalls);
    audit.put("intent", finalIntent);
    audit.put("lastAssistantText", turn.lastAssistantText().orElse(null));
    audit.put("lastTextBeforeSubmit", turn.lastTextBeforeSubmit().orElse(null));
    audit.put("llmCalls", turn.llmCalls());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", AUDIT_ADDRESS_PREFIX + packetId);
    payload.put("key", "result");
    payload.put("value", ToolSupport.json(audit));
    payload.put("note", "决策回合统一结算");
    payload.put("tags", List.of(decisionMakerId));
    payload.put("tick", tick);
    return ToolSupport.json(payload);
  }
}
