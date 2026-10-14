package io.mosire.simos.app.decision;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.spi.SubmitDecisionPacketHandler;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.ga.tools.read.GmInboxTool;
import io.mosire.simos.ga.tools.write.DecisionPacketPayloads;
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
import java.util.Set;
import java.util.UUID;

/**
 * ★★ **决策回合统一结算器**（用户 2026-10-22 裁定；2026-10-14 批 B 改成**条件式**）。
 *
 * <p>★★ <b>现在的真实语义（两条分支，按顺序判）</b>：
 *
 * <ol>
 *   <li><b>本 tick 该 DM 还有"未答提问"</b>（它发出去的信里，存在 {@code to="gm"} 且没有 GM 回信 {@code replyTo} 指向的那一条）⇒
 *       <b>完全不结算</b>：不 upsert、不 submit、不写审计（已 PENDING / 已裁决的既有跳过分支也不参与）。 包**保持 DRAFT**（因此仍可被 {@code
 *       simos.gm.packet*} 读到 ⇒ <b>不丢</b>），等 GM 答完再跑该 DM 一轮。 ★★ 这是本批的要害：不这么做，"这一轮我只问了句话"的那句话会被当成
 *       **NL 决策**提交给 GM。
 *   <li><b>无未答提问</b> ⇒ 照旧结算：{@code auto-no-submit} 兜底——把包里已写的内容 + （**仅当 DM 从未显式写过 intent
 *       时**）整轮最后一段自然语言合并成一个最终 PENDING packet，一起交 GM。★ "不静默丢决策"这条红线不退让。
 * </ol>
 *
 * <p>★★ <b>{@code mergeIntent} 已是条件式</b>（补缺口 G10）：DM 显式写过 intent（{@code packet.intent()} 非空白， 即
 * {@code ga.UpsertDecisionPacket} 已经落过 intent）⇒ **不再追加「【最后陈述】」**，它的表达原样交给 GM；
 * 只有从未显式写过时才用最后一段自然语言兜底。
 *
 * <p>★★ <b>为什么"已提交"不再是本类的一个分支</b>（2026-10-14，缺口 G4 / 不变量 I10）：{@code simos.ga.packet.confirm} 现在是
 * DM 的**一次真实工具调用**——调了就已经是 PENDING，本类在上面第 2 步的"已 PENDING/已裁决 ⇒ 跳过"分支里原样保留它，不再有任何"提交标记 /
 * 提交后继续发命令"的判定（那三个信号连同 {@code DecisionAgentRunner.DecisionTurn} 的三个字段一起删除了，见那次改动的类注）。
 *
 * <p>★ <b>为什么仍在回合末做</b>：LLM 的真实行为是"说完话就停"，而"整轮最后一段文本算不算一条 NL 决策"只有回合结束才知道 ——故兜底必须在这里，且由 {@link
 * CoreSimos#submitBatch} 一次性落：
 *
 * <ul>
 *   <li>{@code ga.UpsertDecisionPacket}（整理 intent/保留 calls，必要时建空包）
 *   <li>{@code ga.SubmitDecisionPacket}（统一提交）
 *   <li>{@code ga.PutInfo}（审计：为什么自动结算、最后一段文本是什么）
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
  private static final String AUDIT_ADDRESS_PREFIX = "ga:decision-finalize.";

  /** 无未答提问时的兜底理由（唯一剩下的非 forced 理由；见类注第 2 条）。 */
  private static final String AUTO_NO_SUBMIT = "auto-no-submit";

  /** 零内容的理由标记（包空、文本空 ⇒ 一个字节都不写）。 */
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
   * 对一个已跑完的决策回合做统一结算；**本 tick 还有未答提问时完全不结算**（见类注第 1 条）；没有决策内容且没有自然语言时零写。
   *
   * @param branch 分支（触发/运行所在分支）
   * @param decisionMakerId 本轮决策人
   * @param turn runner 的回合账（{@code DecisionTurn}：本轮调了几次模型、跑了哪些工具、收尾文本与整轮最后一段非空文本）
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
   *
   * <p>★ <b>它**不**受"未答提问 ⇒ 不结算"那条守卫约束</b>（{@code forcedReason != null} 走的是同一条早返回）：中止不是 DM
   * 说完话，而是被动中断——此时"不丢已写进包的决策"（不变量 I4）优先于"别把提问当决策"。★ 中止路径**不追加**自然语言 （{@code naturalText = empty}）⇒
   * 它本来也不会凭空造出一条 NL 决策。
   */
  void finalizeAborted(BranchId branch, DecisionMakerId decisionMakerId) {
    synchronized (finalizeLock) {
      finalizeInternal(
          branch,
          decisionMakerId,
          // ★ 没有真回合账 ⇒ finalText 与 lastAssistantText 都是空：中止路径本来也不追加自然语言（见方法注）。
          new DecisionAgentRunner.DecisionTurn(
              "aborted", 0, List.of(), Optional.empty(), Optional.empty()),
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
    GaState ga = ToolSupport.gaState(state);
    long tick = state.meta().timestamp().tick();
    DecisionPacketId packetId =
        DecisionPacketId.parse("pkt-" + decisionMakerId.value() + "-" + tick);
    DecisionPacket packet = ga.decisionPackets().get(packetId);
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

    // ★★ 条件式结算（2026-10-14 批 B，设计书 §3.3 / 不变量 I4）：本 tick 还有"我发出去、GM 还没回"的提问 ⇒
    //   完全不结算——包保持 DRAFT（gm.packet* 仍读得到 ⇒ 不丢），等 GM 答完再跑我一轮。
    //   ★ 不这么做的话，"这一轮我只问了句话"的那句话会被当成 NL 决策自动提交给 GM。
    //   ★ 判据复用 GmInboxTool.repliedIds（simos-ga 里"谁被回过"的唯一实现），这里不写第二份 replyTo 扫描。
    if (forcedReason == null && hasOpenQuestions(ga, decisionMakerId, tick)) {
      DECISION.info(
          LogEvent.of(
              "DECISION_TURN_FINALIZE_DEFERRED",
              AppLogSource.DECISION_TURN,
              "dm",
              decisionMakerId.value(),
              "day",
              tick,
              "packet",
              packetId.value(),
              "reason",
              "open-questions"));
      return;
    }

    String reason;
    Optional<String> naturalText;
    if (forcedReason != null) {
      reason = forcedReason;
      naturalText = Optional.empty();
    } else {
      // ★ 唯一剩下的非 forced 理由：没确认（或确认后又撤回）就没未答提问地停了话 ⇒ 兜底提交，不静默丢决策。
      reason = AUTO_NO_SUBMIT;
      naturalText = turn.lastAssistantText();
    }
    // ★★ 条件式 mergeIntent（补缺口 G10）：DM 显式写过 intent（包里 intent 非空白）⇒ 原样采用，**不追加**
    //   「【最后陈述】」。判据是"包里的 intent 是不是它写的"，不是"文本里有没有某个标记"（那会看模型脸色）。
    String explicitIntent = packet == null ? "" : packet.intent();
    String finalIntent =
        (packet != null && !explicitIntent.isBlank())
            ? explicitIntent
            : mergeIntent(explicitIntent, naturalText);
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
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(draft)));
    }
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            INITIATOR,
            branch,
            expected,
            SubmitDecisionPacketHandler.TYPE,
            DecisionPacketPayloads.submit(packetId.value(), decisionMakerId.value())));
    batch.add(
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            INITIATOR,
            branch,
            expected,
            "ga.PutInfo",
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

  /**
   * ★★ <b>本 tick 该 DM 还有没有"发出去、GM 还没回"的提问</b>（2026-10-14 批 B 的条件式结算判据）。
   *
   * <p>判据（三条同时成立）：① {@code value.to == }{@link ToolResources#GM_ID}（发给 GM 的提问；发给别的决策人的信不算——
   * 本判据问的是"**GM 回答了吗**"，见类注与台账的偏差条）；② {@code tick == 本 tick}（早先 tick 的老问题不该让本轮永远不结算）； ③ 它的 id
   * **不在** {@link GmInboxTool#repliedIds} 里。
   *
   * <p>★★ <b>复用既有唯一实现</b>：{@code repliedIds(GaState)} 是"哪条信被回过"的全仓唯一拼法（给 {@code simos.gm.inbox} 的
   * {@code answered} 与 {@code simos.gm.round} 的 {@code openQuestions} 用），这里**不写第二份 {@code replyTo}
   * 扫描**（{@code DecisionResultsVisibilityGuardTest} 钉的是"归属判据恰一份"，本条同一条纪律）。
   */
  private static boolean hasOpenQuestions(GaState ga, DecisionMakerId decisionMakerId, long tick) {
    Set<String> replied = GmInboxTool.repliedIds(ga);
    String self = decisionMakerId.value();
    for (GmInboxTool.Letter letter : GmInboxTool.letters(ga)) {
      if (!self.equals(letter.from()) || letter.tick() != tick) {
        continue;
      }
      if (!ToolResources.GM_ID.equals(letter.to())) {
        continue;
      }
      if (!replied.contains(letter.id())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 最后一段文本兜底进 packet intent（★★ <b>只在 DM 从未显式写过 intent 时才会被调用</b>，见类注的"条件式 mergeIntent"）。
   *
   * <p>★ 走到这里说明 {@code existingIntent} 是空白（调用方的判据就是"它非空白就不用本方法"）：<b>有文本用它、没文本留空</b>
   * ——「【最后陈述】」那段拼接保留只是为了防"同一个 DM 在别的路径上已经写过 intent"这一种情况，本批起正常路径不再产生它。
   */
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

  /** ga.PutInfo 载荷：值本身是 JSON 字符串，结构化内容一眼可读。 */
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
    audit.put("hasCalls", hasCalls);
    audit.put("intent", finalIntent);
    audit.put("lastAssistantText", turn.lastAssistantText().orElse(null));
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
