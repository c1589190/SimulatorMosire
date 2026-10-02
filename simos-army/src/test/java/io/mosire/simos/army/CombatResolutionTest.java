package io.mosire.simos.army;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.testing.ArmyFixtures;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link CombatResolution} 的判定口径（T2a / D-009 补裁 + D-010）：显式结局 / 显式 seed / 两者复核三条语义，以及**同 seed
 * 同结果、表序参与结果、权重和溢出具名拒**。
 *
 * <p>★ 判别力装置的说明：本类**不复写** {@code roll} 的遍历逻辑。要钉"首项/末项落点"时，先用 JDK 规范的 {@link Random} 找出让 {@code
 * nextLong(bound)} 恰为 0 / bound-1 的种子，再断言 {@code CombatResolution} 把这两个落点分别映到表首 / 表尾——
 * 被测的是**映射**（谁吃 r=0、谁吃 r=末尾），随机源本身是 JVM 规范钉死的输入发生器。
 */
class CombatResolutionTest {

  private static final CombatRecordId COMBAT = new CombatRecordId("c-1");
  private static final CombatStageId STAGE = new CombatStageId("s1");
  private static final CombatOutcomeId A = new CombatOutcomeId("a");
  private static final CombatOutcomeId B = new CombatOutcomeId("b");

  private static final List<CombatOutcome> AB =
      List.of(ArmyFixtures.outcome(A, "甲", 1L), ArmyFixtures.outcome(B, "乙", 1L));
  private static final List<CombatOutcome> BA =
      List.of(ArmyFixtures.outcome(B, "乙", 1L), ArmyFixtures.outcome(A, "甲", 1L));

  @Test
  void theSameSeedPicksTheSameOutcomeEveryTime() {
    CombatResolution.Selection first =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.empty(), Optional.of(42L));
    CombatResolution.Selection second =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.empty(), Optional.of(42L));

    assertThat(second).as("同输入同 seed ⇒ 逐字段相等（含 seed 在场）").isEqualTo(first);
    assertThat(CombatResolution.roll(42L, AB)).isEqualTo(CombatResolution.roll(42L, AB));
    assertThat(first.seed()).contains(42L);
  }

  @Test
  void theTableOrderIsPartOfTheResult() {
    assertThat(CombatResolution.roll(42L, AB).id())
        .as("同一 seed、表序换向 ⇒ 选中的结局不同（表序参与判定，不是按 id 排序）")
        .isNotEqualTo(CombatResolution.roll(42L, BA).id());

    CombatResolution.Selection forward =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.empty(), Optional.of(42L));
    CombatResolution.Selection backward =
        CombatResolution.select(COMBAT, STAGE, 5L, BA, Optional.empty(), Optional.of(42L));
    assertThat(backward.outcome().id()).isNotEqualTo(forward.outcome().id());
  }

  /**
   * ★ 分布边界：{@code r = 0} 必须落**首项**、{@code r = bound-1} 必须落**末项**（按权重逐项扣减的口径）。
   *
   * <p>两个方向各测一次 ⇒ 既能抓"从头开始减"的落点错，也能抓"按倒序选"的变异。
   */
  @Test
  void theFirstAndLastRollPositionsSelectTheFirstAndLastOutcomes() {
    long seedAtZero = seedWithPick(2L, 0L);
    long seedAtLast = seedWithPick(2L, 1L);

    assertThat(CombatResolution.roll(seedAtZero, AB).id()).as("r=0 ⇒ 表首").isEqualTo(A);
    assertThat(CombatResolution.roll(seedAtLast, AB).id()).as("r=1 ⇒ 表尾").isEqualTo(B);
    assertThat(CombatResolution.roll(seedAtZero, BA).id()).as("换向：r=0 ⇒（新）表首").isEqualTo(B);
    assertThat(CombatResolution.roll(seedAtLast, BA).id()).as("换向：r=1 ⇒（新）表尾").isEqualTo(A);
  }

  /** ★ 权重决定区间长度，不是"位置均分"：1:3 的表里 r=0 落首项、r=3（末位）落末项，换向后映射随之换。 */
  @Test
  void weightsDecideTheIntervalsNotThePosition() {
    List<CombatOutcome> oneThree =
        List.of(ArmyFixtures.outcome(A, "甲", 1L), ArmyFixtures.outcome(B, "乙", 3L));
    List<CombatOutcome> threeOne =
        List.of(ArmyFixtures.outcome(B, "乙", 3L), ArmyFixtures.outcome(A, "甲", 1L));

    assertThat(CombatResolution.roll(seedWithPick(4L, 0L), oneThree).id()).isEqualTo(A);
    assertThat(CombatResolution.roll(seedWithPick(4L, 2L), oneThree).id())
        .as("r=2 已越过首项权重 1 ⇒ 末项")
        .isEqualTo(B);
    assertThat(CombatResolution.roll(seedWithPick(4L, 3L), oneThree).id()).isEqualTo(B);
    assertThat(CombatResolution.roll(seedWithPick(4L, 0L), threeOne).id()).isEqualTo(B);
    assertThat(CombatResolution.roll(seedWithPick(4L, 3L), threeOne).id()).isEqualTo(A);
  }

  @Test
  void anOverflowingWeightSumIsRejectedByName() {
    List<CombatOutcome> overflow =
        List.of(ArmyFixtures.outcome(A, "甲", Long.MAX_VALUE), ArmyFixtures.outcome(B, "乙", 1L));

    assertThatThrownBy(() -> CombatResolution.roll(1L, overflow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");
    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 1L, overflow, Optional.empty(), Optional.of(1L)))
        .as("投骰路径（显式 seed）同样经 roll ⇒ 同样具名拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");
    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 1L, overflow, Optional.of(A), Optional.of(1L)))
        .as("显式 outcome + seed 的复核路径也要能掷爆")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");
  }

  @Test
  void emptyOutcomeTablesCannotBeRolled() {
    assertThatThrownBy(() -> CombatResolution.roll(1L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 1L, List.of(), Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无法投骰");
    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 1L, List.of(), Optional.of(A), Optional.empty()))
        .as("显式结局也必须在表里（空表 ⇒ 不在表里）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在该阶段的概率表");
  }

  @Test
  void anExplicitOutcomeSkipsTheRollAndKeepsTheSeedEmpty() {
    CombatResolution.Selection selection =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.of(A), Optional.empty());

    assertThat(selection.outcome().id()).as("只给结局 ⇒ 选中它，不投骰").isEqualTo(A);
    assertThat(selection.seed()).as("没投骰 ⇒ rollSeed 空（不是 0、不是派生 seed）").isEmpty();

    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 5L, AB, Optional.of(new CombatOutcomeId("z")), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在该阶段的概率表");
  }

  @Test
  void anExplicitOutcomeWithASeedIsCrossCheckedAgainstTheRoll() {
    long seed = 42L;
    CombatOutcome rolled = CombatResolution.roll(seed, AB);
    CombatOutcomeId other = rolled.id().equals(A) ? B : A;

    CombatResolution.Selection accepted =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.of(rolled.id()), Optional.of(seed));
    assertThat(accepted.outcome()).as("一致 ⇒ 接受").isEqualTo(rolled);
    assertThat(accepted.seed()).contains(seed);

    assertThatThrownBy(
            () ->
                CombatResolution.select(
                    COMBAT, STAGE, 5L, AB, Optional.of(other), Optional.of(seed)))
        .as("★ 记了一个与该 seed 不一致的结局 ⇒ 具名拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不一致")
        .hasMessageContaining(rolled.id().value())
        .hasMessageContaining(other.value());
  }

  @Test
  void aDerivedSeedIsPureAndLabelIsNotPartOfIt() {
    List<CombatOutcome> relabeled =
        List.of(ArmyFixtures.outcome(A, "另一个标签", 1L), ArmyFixtures.outcome(B, "又一个标签", 1L));

    long derived = CombatResolution.deriveSeed(COMBAT, STAGE, 5L, AB);

    assertThat(CombatResolution.deriveSeed(COMBAT, STAGE, 5L, AB)).isEqualTo(derived);
    assertThat(CombatResolution.deriveSeed(COMBAT, STAGE, 5L, relabeled))
        .as("label 不参与派生（改标签不该改结果）")
        .isEqualTo(derived);
    assertThat(CombatResolution.deriveSeed(COMBAT, STAGE, 5L, BA))
        .as("表序参与派生")
        .isNotEqualTo(derived);
    assertThat(CombatResolution.deriveSeed(COMBAT, STAGE, 6L, AB))
        .as("tick 参与派生")
        .isNotEqualTo(derived);

    CombatResolution.Selection first =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.empty(), Optional.empty());
    CombatResolution.Selection second =
        CombatResolution.select(COMBAT, STAGE, 5L, AB, Optional.empty(), Optional.empty());

    assertThat(first.seed()).as("都不给 ⇒ 派生 seed 并在场").contains(derived);
    assertThat(first.outcome()).isEqualTo(CombatResolution.roll(derived, AB));
    assertThat(second).as("同记录同阶段同表 ⇒ 同 seed 同结局").isEqualTo(first);
  }

  @Test
  void rejectsNegativeTickAndNullOutcomeElements() {
    assertThatThrownBy(
            () ->
                CombatResolution.select(COMBAT, STAGE, -1L, AB, Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick");
    assertThatThrownBy(
            () ->
                CombatResolution.roll(
                    1L, java.util.Arrays.asList(ArmyFixtures.outcome(A, "甲", 1L), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  /** 找出让 {@code new Random(seed).nextLong(bound)} 恰为 {@code pick} 的第一个非负种子。 */
  private static long seedWithPick(long bound, long pick) {
    for (long seed = 0L; seed < 1_000_000L; seed++) {
      if (new Random(seed).nextLong(bound) == pick) {
        return seed;
      }
    }
    throw new IllegalStateException("找不到 pick=" + pick + " 的种子（bound=" + bound + "）");
  }
}
