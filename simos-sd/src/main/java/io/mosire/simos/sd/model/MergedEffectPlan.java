package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import java.util.List;
import java.util.Optional;

/**
 * 合并效果集（D2 定义、D3 使用）：冲突决策包经 GM 显式排序/改写后的**有序效果计划**。
 *
 * <p>★ D2 只把这张表放进 {@code SdState}（空表、Codec key 注册），不产生计划；{@code outcome} 是 D3 执行后回写。
 */
public record MergedEffectPlan(
    MergedEffectPlanId id,
    long tick,
    List<DecisionMakerId> participantIds,
    List<MergedEffect> orderedEffects,
    List<String> sources,
    Optional<String> reasonInfoId,
    Optional<String> outcome) {

  public MergedEffectPlan {
    if (id == null) {
      throw new IllegalArgumentException("MergedEffectPlan.id 不得为 null");
    }
    if (tick < 0) {
      throw new IllegalArgumentException("MergedEffectPlan.tick 必须 ≥ 0: " + tick);
    }
    if (participantIds == null) {
      throw new IllegalArgumentException("MergedEffectPlan.participantIds 不得为 null（无参与者用空表）");
    }
    if (orderedEffects == null) {
      throw new IllegalArgumentException("MergedEffectPlan.orderedEffects 不得为 null（无效果用空表）");
    }
    if (sources == null) {
      throw new IllegalArgumentException("MergedEffectPlan.sources 不得为 null（无来源用空表）");
    }
    if (reasonInfoId == null) {
      throw new IllegalArgumentException(
          "MergedEffectPlan.reasonInfoId 不得为 null（无引用用 Optional.empty()）");
    }
    if (outcome == null) {
      throw new IllegalArgumentException(
          "MergedEffectPlan.outcome 不得为 null（未执行为 Optional.empty()）");
    }
    for (DecisionMakerId participant : participantIds) {
      if (participant == null) {
        throw new IllegalArgumentException("MergedEffectPlan.participantIds 不得含 null");
      }
    }
    for (MergedEffect effect : orderedEffects) {
      if (effect == null) {
        throw new IllegalArgumentException("MergedEffectPlan.orderedEffects 不得含 null");
      }
    }
    for (String source : sources) {
      if (source == null || source.isBlank()) {
        throw new IllegalArgumentException("MergedEffectPlan.sources 不得含空白");
      }
    }
    participantIds = List.copyOf(participantIds); // ★ 冻在赋值处
    orderedEffects = List.copyOf(orderedEffects);
    sources = List.copyOf(sources);
  }

  /** 仅换 {@code outcome} 的那一版（D3 执行器同批回写；{@code null} ⇒ 拒）。 */
  public MergedEffectPlan withOutcome(Optional<String> newOutcome) {
    if (newOutcome == null) {
      throw new IllegalArgumentException("newOutcome 不得为 null（未执行变化用 Optional.empty()）");
    }
    return new MergedEffectPlan(
        id, tick, participantIds, orderedEffects, sources, reasonInfoId, newOutcome);
  }
}
