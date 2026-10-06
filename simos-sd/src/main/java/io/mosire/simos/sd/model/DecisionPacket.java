package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 一个决策包（D2 决策包计划 §1）：一决策人 × 一 tick 恰好一个包，内含拟稿期追加的 {@link FormattedCall} 列表。
 *
 * <p>★ {@code intent} 是自然语言轨道（可为空字符串，不参与 true/false 机械执行）；{@code reasonInfoId} 预留 D4 的 INFO
 * 引用；{@code decidedBy}/{@code decidedAtRevision}/{@code decisionNote} 是 GM 裁决留痕。
 *
 * <p>★ {@code calls} 保序不可变、{@code callIndex} **严格递增不重复**（构造期校验）——执行顺序 = 列表顺序。
 *
 * <p>★ {@code createdAtRevision} 是**建包时**的 revision；后续重写保留原值（谁最早建的看得出）。
 */
public record DecisionPacket(
    DecisionPacketId id,
    String branch,
    long tick,
    DecisionMakerId proposerId,
    PacketStatus status,
    String intent,
    List<FormattedCall> calls,
    long createdAtRevision,
    Optional<String> decidedBy,
    OptionalLong decidedAtRevision,
    Optional<String> reasonInfoId,
    Optional<String> decisionNote) {

  public DecisionPacket {
    if (id == null) {
      throw new IllegalArgumentException("DecisionPacket.id 不得为 null");
    }
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("DecisionPacket.branch 不得为空白");
    }
    if (tick < 0) {
      throw new IllegalArgumentException("DecisionPacket.tick 必须 ≥ 0: " + tick);
    }
    if (proposerId == null) {
      throw new IllegalArgumentException("DecisionPacket.proposerId 不得为 null");
    }
    if (status == null) {
      throw new IllegalArgumentException("DecisionPacket.status 不得为 null");
    }
    if (intent == null) {
      throw new IllegalArgumentException("DecisionPacket.intent 不得为 null（无 NL 意图用空字符串）");
    }
    if (calls == null) {
      throw new IllegalArgumentException("DecisionPacket.calls 不得为 null（无调用用空表）");
    }
    if (createdAtRevision < 0) {
      throw new IllegalArgumentException(
          "DecisionPacket.createdAtRevision 必须 ≥ 0: " + createdAtRevision);
    }
    if (decidedBy == null) {
      throw new IllegalArgumentException(
          "DecisionPacket.decidedBy 不得为 null（未裁决用 Optional.empty()）");
    }
    if (decidedAtRevision == null) {
      throw new IllegalArgumentException(
          "DecisionPacket.decidedAtRevision 不得为 null（未裁决用 OptionalLong.empty()）");
    }
    if (reasonInfoId == null) {
      throw new IllegalArgumentException(
          "DecisionPacket.reasonInfoId 不得为 null（无引用用 Optional.empty()）");
    }
    if (decisionNote == null) {
      throw new IllegalArgumentException(
          "DecisionPacket.decisionNote 不得为 null（无注记用 Optional.empty()）");
    }
    int previous = Integer.MIN_VALUE;
    for (FormattedCall call : calls) {
      if (call == null) {
        throw new IllegalArgumentException("DecisionPacket.calls 不得含 null");
      }
      if (call.callIndex() <= previous) {
        throw new IllegalArgumentException(
            "DecisionPacket.calls 的 callIndex 必须严格递增且不重复: " + call.callIndex());
      }
      previous = call.callIndex();
    }
    calls = List.copyOf(calls); // ★ 冻在赋值处
  }

  /** 仅换 calls 的那一版（propose 追加一条）。 */
  public DecisionPacket withCalls(List<FormattedCall> newCalls) {
    return copy(newCalls, status, decidedBy, decidedAtRevision, reasonInfoId, decisionNote);
  }

  /** 仅换状态的那一版（submit 置 PENDING）。 */
  public DecisionPacket withStatus(PacketStatus newStatus) {
    if (newStatus == null) {
      throw new IllegalArgumentException("newStatus 不得为 null");
    }
    return copy(calls, newStatus, decidedBy, decidedAtRevision, reasonInfoId, decisionNote);
  }

  /** 仅换 NL 意图的那一版（intent 工具）。 */
  public DecisionPacket withIntent(String newIntent) {
    return new DecisionPacket(
        id,
        branch,
        tick,
        proposerId,
        status,
        newIntent,
        calls,
        createdAtRevision,
        decidedBy,
        decidedAtRevision,
        reasonInfoId,
        decisionNote);
  }

  /** 裁决的那一版（D2 的 GM APPROVE/DENY）：逐 call 状态 + 包状态 + 裁决留痕一起换。 */
  public DecisionPacket withDecision(
      PacketStatus newStatus,
      List<FormattedCall> newCalls,
      Optional<String> newDecidedBy,
      OptionalLong newDecidedAtRevision,
      Optional<String> newDecisionNote) {
    if (newStatus == null) {
      throw new IllegalArgumentException("newStatus 不得为 null");
    }
    return copy(
        newCalls, newStatus, newDecidedBy, newDecidedAtRevision, reasonInfoId, newDecisionNote);
  }

  /** 逐字段照抄的**唯一**拷贝点：record 没有 wither，helper 多了以后加字段会漏，故只允许一处展开。 */
  private DecisionPacket copy(
      List<FormattedCall> newCalls,
      PacketStatus newStatus,
      Optional<String> newDecidedBy,
      OptionalLong newDecidedAtRevision,
      Optional<String> newReasonInfoId,
      Optional<String> newDecisionNote) {
    return new DecisionPacket(
        id,
        branch,
        tick,
        proposerId,
        newStatus,
        intent,
        new ArrayList<>(newCalls),
        createdAtRevision,
        newDecidedBy,
        newDecidedAtRevision,
        newReasonInfoId,
        newDecisionNote);
  }
}
