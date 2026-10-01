package io.mosire.simos.army;

import io.mosire.simos.unit.UnitId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 单 tick 交战记录里的**一个阶段**（阶段 D4 / 用户设计 D-009 补裁 + D-010，2026-10-02）：{@code id / name / participants /
 * text / outcomes / selectedOutcomeId / rollSeed}。
 *
 * <p>★★ <b>D-009 的"不同阶段"</b>：阶段是**有序列表**（{@link CombatRecord#stages()}），记录自然语言过程与结局概率表；追加阶段走显式命令
 * {@code army.AppendCombatStage}（旧阶段只被整条替换，见 {@link CombatRecord} 的不可变口径）。
 *
 * <p>★★ <b>判定（selectedOutcomeId + rollSeed）</b>：未判定 ⇒ 两者都空；判定后 ⇒ {@code selectedOutcomeId}
 * 必须指向本阶段概率表里的一个 outcome。{@code rollSeed} **只在真的投了骰时有值**（含显式 seed 的投骰与确定性派生 seed 的投骰）；显式指定结局（没投骰）⇒
 * {@code rollSeed} 空。算法与可复现口径在 {@link CombatResolution}（全仓唯一拼写点，命令层与工具层不各掷一次）。
 *
 * <p>★ <b>本类自身的不变量</b>（全部构造期判）：
 *
 * <ul>
 *   <li>{@code participants} 非空且不重复（D-009：单方入场也可判交战——至少一个，不是"至少两个"）；
 *   <li>{@code outcomes} 内 id 不重复；{@code weight > 0} 由 {@link CombatOutcome} 自己判；
 *   <li>{@code selectedOutcomeId} 若在场必须 ∈ 本阶段 {@code outcomes}；{@code rollSeed} 在场 ⇒ {@code
 *       selectedOutcomeId} 必在场 （"投了骰却没有结局"不是一种状态）；
 *   <li>{@code name}/{@code text} 非空白、{@code id} 非 null。
 * </ul>
 *
 * <p>★ <b>保序不可变</b>：{@code participants}/{@code outcomes} 一律保序冻结（不用 Map.copyOf/Set.copyOf）。
 *
 * <p>★ <b>不背旧档</b>（D-011/R4）：阶段是阶段 D4 新引入的形状，没有旧的"阶段"字节要读；两个 {@link Optional} 组件缺键（Jackson 传 null）⇒
 * 构造期当场抛，不静默当空——静默当空会让"判定丢了"看起来像"还没判"。
 */
public record CombatStage(
    CombatStageId id,
    String name,
    List<UnitId> participants,
    String text,
    List<CombatOutcome> outcomes,
    Optional<CombatOutcomeId> selectedOutcomeId,
    Optional<Long> rollSeed) {

  public CombatStage {
    Objects.requireNonNull(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text 不得为空白（阶段过程必须写出来）");
    }
    if (participants == null) {
      throw new IllegalArgumentException("participants 不得为 null");
    }
    Set<UnitId> seenParticipants = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      if (!seenParticipants.add(unit)) {
        throw new IllegalArgumentException("participants 不得重复: " + unit.value());
      }
    }
    if (seenParticipants.isEmpty()) {
      throw new IllegalArgumentException("participants 至少要有 1 个单位（D-009：单方入场也可判交战）");
    }
    participants = List.copyOf(participants);
    if (outcomes == null) {
      throw new IllegalArgumentException("outcomes 不得为 null（空表合法 = 尚未给出概率表）");
    }
    Set<CombatOutcomeId> outcomeIds = new LinkedHashSet<>();
    for (CombatOutcome outcome : outcomes) {
      if (outcome == null) {
        throw new IllegalArgumentException("outcomes 不得含 null");
      }
      if (!outcomeIds.add(outcome.id())) {
        throw new IllegalArgumentException("outcomes 不得重复 id: " + outcome.id().value());
      }
    }
    outcomes = List.copyOf(outcomes);
    if (selectedOutcomeId == null) {
      // ★ 新格式不缺键：缺键是坏数据（见类注），不归一成 empty。
      throw new IllegalArgumentException("selectedOutcomeId 不得为 null（未判定用 Optional.empty()）");
    }
    if (rollSeed == null) {
      throw new IllegalArgumentException("rollSeed 不得为 null（未投骰用 Optional.empty()）");
    }
    if (rollSeed.isPresent() && selectedOutcomeId.isEmpty()) {
      throw new IllegalArgumentException("rollSeed 在场但 selectedOutcomeId 缺席：投了骰却没有结局不是合法状态");
    }
    if (selectedOutcomeId.isPresent() && !outcomeIds.contains(selectedOutcomeId.get())) {
      throw new IllegalArgumentException(
          "selectedOutcomeId 不在本阶段的 outcomes 里: " + selectedOutcomeId.get().value());
    }
  }

  /** 是否已经判定（selectedOutcomeId 在场）。 */
  public boolean resolved() {
    return selectedOutcomeId.isPresent();
  }

  /** 已判定 ⇒ 选中的那一个 outcome；未判定或（构造期已排除的）指向不存在 id ⇒ 空。 */
  public Optional<CombatOutcome> selectedOutcome() {
    for (CombatOutcome outcome : outcomes) {
      if (selectedOutcomeId.isPresent() && outcome.id().equals(selectedOutcomeId.get())) {
        return Optional.of(outcome);
      }
    }
    return Optional.empty();
  }

  /**
   * 产出"已判定"的新阶段（同一阶段的判定只做一次）：{@code outcomeId} 必须 ∈ 本阶段概率表，{@code seed} 按 {@link CombatResolution}
   * 的口径给出（投骰路径 = present，显式结局路径 = empty）。
   *
   * @throws IllegalArgumentException 已判定过、或 outcomeId 不在本阶段 outcomes 里
   */
  public CombatStage resolvedAs(CombatOutcomeId outcomeId, Optional<Long> seed) {
    Objects.requireNonNull(outcomeId, "outcomeId");
    Objects.requireNonNull(seed, "seed");
    if (resolved()) {
      throw new IllegalArgumentException("阶段已判定过，不可重复投骰: " + id.value());
    }
    boolean known = false;
    for (CombatOutcome outcome : outcomes) {
      if (outcome.id().equals(outcomeId)) {
        known = true;
        break;
      }
    }
    if (!known) {
      throw new IllegalArgumentException(
          "结局不在该阶段的概率表里: " + outcomeId.value() + "（阶段 " + id.value() + "）");
    }
    return new CombatStage(id, name, participants, text, outcomes, Optional.of(outcomeId), seed);
  }
}
