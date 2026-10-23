package io.mosire.simos.ga.model;

import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 决策包的<b>只读领域视图</b>（设计书 §5.4）。
 *
 * <p>它不是世界状态：{@link io.mosire.simos.ga.GaState} 只持久化 {@link DecisionRecord}，精确状态只在 {@link
 * DecisionRecord#packageState()}。本类型保留给既有 GUI / 读工具迁移，只能经 {@link DecisionRecord#view()}
 * 从记录现场投影；构造器对 {@code model} 包外不可见，且刻意<b>没有</b> {@code withStatus/withDecision/withCalls} 之类写方法。
 *
 * <p>{@link PacketStatus}/{@link CallStatus} 只描述展示口径；任何裁决/执行状态判断都必须读 AgentLib 快照。
 */
public final class DecisionPacket {

  private final DecisionPacketId id;
  private final String branch;
  private final long tick;
  private final DecisionMakerId proposerId;
  private final PacketStatus status;
  private final String intent;
  private final List<FormattedCall> calls;
  private final long createdAtRevision;
  private final Optional<String> decidedBy;
  private final OptionalLong decidedAtRevision;
  private final Optional<String> reasonInfoId;
  private final Optional<String> decisionNote;

  private DecisionPacket(
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
    this.id = Objects.requireNonNull(id, "id");
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("DecisionPacket.branch 不得为空白");
    }
    this.branch = branch;
    if (tick < 0L) {
      throw new IllegalArgumentException("DecisionPacket.tick 必须 ≥ 0: " + tick);
    }
    this.tick = tick;
    this.proposerId = Objects.requireNonNull(proposerId, "proposerId");
    this.status = Objects.requireNonNull(status, "status");
    this.intent = Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(calls, "calls");
    List<FormattedCall> callCopy = new ArrayList<>(calls.size());
    int previous = Integer.MIN_VALUE;
    for (FormattedCall call : calls) {
      Objects.requireNonNull(call, "calls 不得含 null");
      if (call.callIndex() <= previous) {
        throw new IllegalArgumentException(
            "DecisionPacket.calls 的 callIndex 必须严格递增且不重复: " + call.callIndex());
      }
      previous = call.callIndex();
      callCopy.add(call);
    }
    this.calls = Collections.unmodifiableList(callCopy);
    if (createdAtRevision < 0L) {
      throw new IllegalArgumentException(
          "DecisionPacket.createdAtRevision 必须 ≥ 0: " + createdAtRevision);
    }
    this.createdAtRevision = createdAtRevision;
    this.decidedBy = Objects.requireNonNull(decidedBy, "decidedBy");
    this.decidedAtRevision = Objects.requireNonNull(decidedAtRevision, "decidedAtRevision");
    this.reasonInfoId = Objects.requireNonNull(reasonInfoId, "reasonInfoId");
    this.decisionNote = Objects.requireNonNull(decisionNote, "decisionNote");
  }

  /** 只供 {@link DecisionRecord#view()} 在同一个 {@code model} 包内构造视图。 */
  static DecisionPacket of(
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
    return new DecisionPacket(
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
  }

  public DecisionPacketId id() {
    return id;
  }

  public String branch() {
    return branch;
  }

  public long tick() {
    return tick;
  }

  public DecisionMakerId proposerId() {
    return proposerId;
  }

  public PacketStatus status() {
    return status;
  }

  public String intent() {
    return intent;
  }

  public List<FormattedCall> calls() {
    return calls;
  }

  public long createdAtRevision() {
    return createdAtRevision;
  }

  public Optional<String> decidedBy() {
    return decidedBy;
  }

  public OptionalLong decidedAtRevision() {
    return decidedAtRevision;
  }

  public Optional<String> reasonInfoId() {
    return reasonInfoId;
  }

  public Optional<String> decisionNote() {
    return decisionNote;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DecisionPacket that)) {
      return false;
    }
    return tick == that.tick
        && createdAtRevision == that.createdAtRevision
        && id.equals(that.id)
        && branch.equals(that.branch)
        && proposerId.equals(that.proposerId)
        && status == that.status
        && intent.equals(that.intent)
        && calls.equals(that.calls)
        && decidedBy.equals(that.decidedBy)
        && decidedAtRevision.equals(that.decidedAtRevision)
        && reasonInfoId.equals(that.reasonInfoId)
        && decisionNote.equals(that.decisionNote);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
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
  }

  @Override
  public String toString() {
    return "DecisionPacket["
        + "id="
        + id
        + ", branch="
        + branch
        + ", tick="
        + tick
        + ", proposerId="
        + proposerId
        + ", status="
        + status
        + ", intent="
        + intent
        + ", calls="
        + calls
        + ", createdAtRevision="
        + createdAtRevision
        + ", decidedBy="
        + decidedBy
        + ", decidedAtRevision="
        + decidedAtRevision
        + ", reasonInfoId="
        + reasonInfoId
        + ", decisionNote="
        + decisionNote
        + "]";
  }
}
