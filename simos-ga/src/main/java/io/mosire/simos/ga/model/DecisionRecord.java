package io.mosire.simos.ga.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionItemVerdict;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 一个决策包的<b>唯一世界状态</b>（设计书 §5）。
 *
 * <p>精确状态机只在 {@link #packageState()}（AgentLib {@link DecisionPackageSnapshot}）；本 record 的其余分量是
 * Simos 领域扩展：branch/tick/proposer、NL intent、revision 留痕与逐 call 的展示/执行元数据。 {@link DecisionPacket}
 * 只是本 record 的只读视图，不是第二套状态。
 *
 * @param id 包 id（世界状态表的键；必须等于 {@code packageState.pkg().id()}）
 * @param branch 分支
 * @param tick 所属 tick
 * @param proposerId 提议者；{@code value()} 必须等于 AgentLib {@code proposerKey}
 * @param intent 领域 NL 轨（不参与 AgentLib 状态机）
 * @param createdAtRevision 建包 revision
 * @param confirmedAtRevision DM 领域确认标记（不改 {@link PackageState}）
 * @param reasonInfoId 预留 INFO 引用
 * @param decidedAtRevision 裁决发生时的 revision（仅 {@code decision != null} 时存在）
 * @param callMeta 逐 call 的 Simos 扩展（键集合必须等于包内条目 index 集合）
 * @param packageState AgentLib 的完整运行态快照（唯一状态机）
 */
public record DecisionRecord(
    DecisionPacketId id,
    String branch,
    long tick,
    DecisionMakerId proposerId,
    String intent,
    long createdAtRevision,
    OptionalLong confirmedAtRevision,
    Optional<String> reasonInfoId,
    OptionalLong decidedAtRevision,
    Map<Integer, DecisionCallMeta> callMeta,
    DecisionPackageSnapshot packageState) {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  public DecisionRecord {
    Objects.requireNonNull(id, "id");
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("DecisionRecord.branch 不得为空白");
    }
    if (tick < 0L) {
      throw new IllegalArgumentException("DecisionRecord.tick 必须 ≥ 0: " + tick);
    }
    Objects.requireNonNull(proposerId, "proposerId");
    Objects.requireNonNull(intent, "intent（无 NL 意图用空字符串）");
    if (createdAtRevision < 0L) {
      throw new IllegalArgumentException(
          "DecisionRecord.createdAtRevision 必须 ≥ 0: " + createdAtRevision);
    }
    Objects.requireNonNull(confirmedAtRevision, "confirmedAtRevision");
    if (confirmedAtRevision.isPresent() && confirmedAtRevision.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "DecisionRecord.confirmedAtRevision 必须 ≥ 0: " + confirmedAtRevision.getAsLong());
    }
    Objects.requireNonNull(reasonInfoId, "reasonInfoId");
    Objects.requireNonNull(decidedAtRevision, "decidedAtRevision");
    if (decidedAtRevision.isPresent() && decidedAtRevision.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "DecisionRecord.decidedAtRevision 必须 ≥ 0: " + decidedAtRevision.getAsLong());
    }
    Objects.requireNonNull(callMeta, "callMeta");
    Objects.requireNonNull(packageState, "packageState");
    DecisionPackage pkg = packageState.pkg();
    Objects.requireNonNull(pkg, "packageState.pkg");

    if (!id.value().equals(pkg.id())) {
      throw new IllegalArgumentException(
          "DecisionRecord.id 必须等于 packageState.pkg().id(): id=" + id.value() + " pkg=" + pkg.id());
    }
    if (!proposerId.value().equals(pkg.proposerKey())) {
      throw new IllegalArgumentException(
          "DecisionRecord.proposerId 必须等于 packageState.pkg().proposerKey(): proposer="
              + proposerId.value()
              + " pkgProposer="
              + pkg.proposerKey());
    }
    if (packageState.contentVersion() < 1L) {
      throw new IllegalArgumentException(
          "DecisionRecord.packageState.contentVersion 必须 ≥ 1: " + packageState.contentVersion());
    }
    if (pkg.state() == PackageState.PROPOSED && pkg.decision() != null) {
      throw new IllegalArgumentException("PROPOSED 决策包的 pkg.decision 必须为 null");
    }
    boolean hasDecision = pkg.decision() != null;
    if (hasDecision != decidedAtRevision.isPresent()) {
      throw new IllegalArgumentException(
          "pkg.decision 与 decidedAtRevision 必须同现同缺: hasDecision="
              + hasDecision
              + " decidedAtRevision="
              + decidedAtRevision);
    }

    Set<Integer> itemIndexes = new LinkedHashSet<>();
    for (PackageItem item : pkg.items()) {
      itemIndexes.add(item.index());
    }
    Map<Integer, DecisionCallMeta> metaCopy = new LinkedHashMap<>();
    for (Map.Entry<Integer, DecisionCallMeta> entry : callMeta.entrySet()) {
      Integer index = entry.getKey();
      if (index == null) {
        throw new IllegalArgumentException("DecisionRecord.callMeta 的键不得为 null");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("DecisionRecord.callMeta 的值不得为 null: " + index);
      }
      metaCopy.put(index, entry.getValue());
    }
    if (!metaCopy.keySet().equals(itemIndexes)) {
      throw new IllegalArgumentException(
          "DecisionRecord.callMeta 的键集合必须等于包内条目 index 集合: callMeta="
              + metaCopy.keySet()
              + " items="
              + itemIndexes);
    }
    callMeta = Collections.unmodifiableMap(metaCopy);
  }

  /**
   * 只读领域视图（设计书 §5.4）。
   *
   * <p>{@link DecisionPacket} 的所有状态/显示字段都从本 record 现场投影；精确状态请读 {@link
   * #packageState()}。本方法不读取也不缓存任何第二份状态。
   */
  public DecisionPacket view() {
    DecisionPackage pkg = packageState.pkg();
    Decision decision = pkg.decision();
    boolean merged = callMeta.values().stream().anyMatch(meta -> meta.mergedPlanId().isPresent());
    PacketStatus packetStatus =
        merged ? PacketStatus.MERGED : packetStatus(pkg, packageState.verdicts());
    List<FormattedCall> calls = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      DecisionCallMeta meta = callMeta.get(item.index());
      calls.add(
          new FormattedCall(
              item.index(),
              item.toolName(),
              json(item.args()),
              meta.targets(),
              meta.previewJson(),
              meta.draftChecks(),
              callStatus(pkg, packageState.verdicts(), meta, item.index()),
              meta.mergedPlanId(),
              meta.outcomeJson()));
    }
    Optional<String> decidedBy =
        decision == null ? Optional.empty() : Optional.ofNullable(decision.by());
    Optional<String> decisionNote =
        decision == null ? Optional.empty() : Optional.ofNullable(decision.note());
    return DecisionPacket.of(
        id,
        branch,
        tick,
        proposerId,
        packetStatus,
        intent,
        calls,
        createdAtRevision,
        decidedBy,
        decidedAtRevision,
        reasonInfoId,
        decisionNote);
  }

  private static PacketStatus packetStatus(
      DecisionPackage pkg, List<DecisionItemVerdict> verdicts) {
    return switch (pkg.state()) {
      case PROPOSED, DECIDING -> PacketStatus.PENDING;
      case DECIDED, COMMITTED -> aggregateStatus(pkg, verdicts);
      case REJECTED, CONFLICTED, EXPIRED -> PacketStatus.REJECTED;
    };
  }

  private static PacketStatus aggregateStatus(
      DecisionPackage pkg, List<DecisionItemVerdict> verdicts) {
    Set<Integer> approved = new LinkedHashSet<>();
    Decision decision = pkg.decision();
    if (decision != null) {
      approved.addAll(decision.approvedIndexes());
    } else {
      for (DecisionItemVerdict verdict : verdicts) {
        if (verdict.approved()) {
          approved.add(verdict.index());
        }
      }
    }
    if (approved.isEmpty()) {
      return PacketStatus.REJECTED;
    }
    return approved.size() == pkg.items().size()
        ? PacketStatus.APPROVED
        : PacketStatus.PARTIALLY_APPROVED;
  }

  private static CallStatus callStatus(
      DecisionPackage pkg, List<DecisionItemVerdict> verdicts, DecisionCallMeta meta, int index) {
    if (meta.mergedPlanId().isPresent()) {
      return CallStatus.MERGED;
    }
    Decision decision = pkg.decision();
    if (decision != null) {
      return decision.approves(index) ? CallStatus.APPROVED : CallStatus.REJECTED;
    }
    for (DecisionItemVerdict verdict : verdicts) {
      if (verdict.index() == index) {
        return verdict.approved() ? CallStatus.APPROVED : CallStatus.REJECTED;
      }
    }
    return switch (pkg.state()) {
      case REJECTED, CONFLICTED, EXPIRED -> CallStatus.REJECTED;
      case PROPOSED, DECIDING, DECIDED, COMMITTED -> CallStatus.PENDING;
    };
  }

  private static String json(Map<String, Object> value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("决策包条目 args 不是合法 JSON 对象", e);
    }
  }
}
