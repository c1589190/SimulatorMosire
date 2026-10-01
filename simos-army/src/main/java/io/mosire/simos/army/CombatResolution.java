package io.mosire.simos.army;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * 交战阶段的**投骰判定**（阶段 D4 / 用户设计 D-009 补裁 + D-010，2026-10-02）：按权重在阶段概率表里选一个结局——全仓**唯一**的判定算法落点。
 *
 * <p>★★ <b>命令与工具都用这一份</b>：{@code army.ResolveCombatStage} 的 handler 直接调它；app 的 {@code
 * simos.army.resolveCombat} 在组批前 也调它（因为要先知道选中结局的损失才能组 {@code unit.AdjustComposition}
 * 命令），然后把自己的结果作为**显式 outcome + seed** 传回命令——命令再用它复核 一遍（见下）。两处不可能掷出不同结果，因为只有这一处掷骰。
 *
 * <p>★★ <b>可复现（硬要求）</b>：
 *
 * <ul>
 *   <li>调用方给了 {@code seed} ⇒ 用它；没给 ⇒ 由 {@link #deriveSeed}(combatId, stageId, tick, 概率表) 做 FNV-1a
 *       64 位派生 ——同记录同阶段同表 ⇒ 同种子，无墙钟、无系统随机源；
 *   <li>随机源是 {@link Random}（{@code java.util.Random} 的算法由 JVM 规范钉死：同种子同序），**禁 {@code
 *       Math.random()}**；
 *   <li>抽取口径固定：{@code total = Σ weight}（{@code Math.addExact}，溢出 ⇒ 具名拒）、{@code r =
 *       random.nextLong(total)}、 按 {@code outcomes} 的顺序逐项减 weight，首个使 {@code r < 0} 的即为选中——概率与
 *       weight 成正比，遍历序 = 表序。
 * </ul>
 *
 * <p>★★ <b>显式 outcome 与 seed 的关系（三条语义，命令/工具共用）</b>：
 *
 * <ol>
 *   <li><b>只给 outcome</b> ⇒ 不投骰，选中它；记录里 {@code selectedOutcomeId} 在场、{@code rollSeed} 空；
 *   <li><b>只给 seed</b> ⇒ 用该 seed 投骰；记录里 selected + seed 都在场；
 *   <li><b>两个都给</b> ⇒ <b>复核</b>：按该 seed 投出的结果必须等于给的 outcome，否则具名拒（防止"记了一个与该 seed 不一致的结局"）； 复核通过 ⇒
 *       两者都写入记录。
 * </ol>
 *
 * <p>★ <b>语义分组都在本类</b>：显式 outcome 必须 ∈ 该阶段 outcomes；不允许"表为空但显式结局"；不允许"没有概率表还投骰"。坏输入一律 {@link
 * IllegalArgumentException}（handler 折 {@code Rejected}，工具折 {@code BAD_REQUEST}）。
 */
public final class CombatResolution {

  private CombatResolution() {}

  /**
   * 一次判定的结果。
   *
   * @param outcome 选中的结局（必在手，且必 ∈ 传入的 outcomes）
   * @param seed 要写进记录 {@code rollSeed} 的种子：投骰路径必在场；显式 outcome 且未给 seed ⇒ 空
   */
  public record Selection(CombatOutcome outcome, Optional<Long> seed) {

    public Selection {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(seed, "seed");
    }
  }

  /**
   * 判定入口（三条语义见类注）。
   *
   * @param combatId 交战记录 id（参与派生种子；必非 null）
   * @param stageId 阶段 id（参与派生种子；必非 null）
   * @param tick 记录的世界日（参与派生种子；域类型已保证 ≥ 0）
   * @param outcomes 阶段概率表（保序；id 不重复、weight &gt; 0 已由 {@link CombatStage} 判）
   * @param explicitOutcomeId 显式结局（空 = 没给）
   * @param explicitSeed 显式种子（空 = 没给）
   * @throws IllegalArgumentException 显式结局不在表里 / 表为空无法投骰 / seed 与 outcome 不一致 / 权重和溢出
   */
  public static Selection select(
      CombatRecordId combatId,
      CombatStageId stageId,
      long tick,
      List<CombatOutcome> outcomes,
      Optional<CombatOutcomeId> explicitOutcomeId,
      Optional<Long> explicitSeed) {
    Objects.requireNonNull(combatId, "combatId");
    Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(outcomes, "outcomes");
    Objects.requireNonNull(explicitOutcomeId, "explicitOutcomeId");
    Objects.requireNonNull(explicitSeed, "explicitSeed");
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }

    if (explicitOutcomeId.isPresent()) {
      CombatOutcome explicit = findByOutcomeId(outcomes, explicitOutcomeId.get());
      if (explicitSeed.isEmpty()) {
        return new Selection(explicit, Optional.empty());
      }
      CombatOutcome rolled = roll(explicitSeed.get(), outcomes);
      if (!rolled.id().equals(explicit.id())) {
        throw new IllegalArgumentException(
            "显式结局与 seed 不一致：seed="
                + explicitSeed.get()
                + " 按权重应选 "
                + rolled.id().value()
                + "，但给的是 "
                + explicit.id().value());
      }
      return new Selection(explicit, Optional.of(explicitSeed.get()));
    }

    if (outcomes.isEmpty()) {
      throw new IllegalArgumentException("阶段没有结局概率表（outcomes 为空），无法投骰: " + stageId.value());
    }
    long seed = explicitSeed.orElseGet(() -> deriveSeed(combatId, stageId, tick, outcomes));
    return new Selection(roll(seed, outcomes), Optional.of(seed));
  }

  /** 按权重投骰（遍历序 = 表序；口径见类注）。 */
  public static CombatOutcome roll(long seed, List<CombatOutcome> outcomes) {
    Objects.requireNonNull(outcomes, "outcomes");
    if (outcomes.isEmpty()) {
      throw new IllegalArgumentException("outcomes 不得为空（没有概率表就无法投骰）");
    }
    long total;
    try {
      total = 0L;
      for (CombatOutcome outcome : outcomes) {
        if (outcome == null) {
          throw new IllegalArgumentException("outcomes 不得含 null");
        }
        total = Math.addExact(total, outcome.weight());
      }
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("结局权重之和溢出 long，无法形成均匀区间", e);
    }
    Random random = new Random(seed);
    long pick = random.nextLong(total);
    for (CombatOutcome outcome : outcomes) {
      pick -= outcome.weight();
      if (pick < 0L) {
        return outcome;
      }
    }
    // 上面的累加与扣除是同一个 total，数学上不可能走到这里；留着是 fail-loud 而不是静默返回最后一个。
    throw new IllegalStateException("投骰落点没有命中任何 outcome（概率表自相矛盾）");
  }

  /**
   * 确定性派生种子：FNV-1a 64 位，输入 = {@code combatId | stageId | tick | (outcomeId:weight; …)}（顺序 = 表序）。
   *
   * <p>★ 只吃 D-010 明列的四个来源（combatId + stageId + tick + 概率表）；label 不参与——判定只看 id 与 weight，改 label
   * 不该改结果。
   */
  public static long deriveSeed(
      CombatRecordId combatId, CombatStageId stageId, long tick, List<CombatOutcome> outcomes) {
    Objects.requireNonNull(combatId, "combatId");
    Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(outcomes, "outcomes");
    StringBuilder text = new StringBuilder();
    text.append(combatId.value())
        .append('|')
        .append(stageId.value())
        .append('|')
        .append(tick)
        .append('|');
    for (CombatOutcome outcome : outcomes) {
      if (outcome == null) {
        throw new IllegalArgumentException("outcomes 不得含 null");
      }
      text.append(outcome.id().value()).append(':').append(outcome.weight()).append(';');
    }
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < text.length(); i++) {
      hash ^= text.charAt(i);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  /** 显式结局必须在本阶段概率表里（不在 ⇒ 具名拒，不视作"新结局"）。 */
  private static CombatOutcome findByOutcomeId(
      List<CombatOutcome> outcomes, CombatOutcomeId outcomeId) {
    for (CombatOutcome outcome : outcomes) {
      if (outcome.id().equals(outcomeId)) {
        return outcome;
      }
    }
    throw new IllegalArgumentException("结局不在该阶段的概率表里: " + outcomeId.value());
  }
}
