package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.ProductionExperienceMoveOutcome;
import io.mosire.simos.social.api.id.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>R4（§15.4 / P 批判据）：合户经验按"来源 ∪ 目标"并集逐条做人口加权平均，缺行 = 0 参与</b> （D-032 修复轮 T9 批；期望值按 §15.4
 * 的定点公式手算）。
 *
 * <p>★★ <b>缺陷现场（回代码核过）</b>：{@code ProductionData.mergeExperienceInto} 旧版只枚举<b>来源户已有</b>的经验条， 且
 * {@code sourceFeature.allZero() ⇒ continue} ⇒ 目标独有条不稀释、来源缺行被当成"没有这件事"。 §15.4 的用户例式： 目标 10 人经验 100
 * + 来源迁入 10 人经验 0（缺行）⇒ 应 50，旧实现给 100。
 *
 * <p>★★ <b>公式（§15.4 原文，逐值手算）</b>：
 *
 * <pre>
 *   exact_h = value_h × 1000 + remainder_h                      （缺行 = ZERO = 0）
 *   mergedExact = ⌊(exact_from × moved + exact_to × target) ÷ (moved + target)⌋
 *   结果 = (mergedExact ÷ 1000, mergedExact mod 1000)；mergedExact == 0 ⇒ 删行
 * </pre>
 *
 * <p>★ 本类逐条覆盖判据要求的四个场景：<b>目标独有条 / 来源独有条 / 显式全零行 / 多次迁入累计</b>。
 */
class ProductionRuntimeMergeUnionR4Test {

  private static final HouseholdId FROM = ProductionTestFixtures.H2;
  private static final HouseholdId TO = ProductionTestFixtures.H3;
  private static final ExperienceTrackId FARMING = ProductionTestFixtures.FARMING;
  private static final ExperienceTrackId MILLING = ProductionTestFixtures.MILLING;
  private static final ExperienceTrackId CRAFTING = new ExperienceTrackId("crafting");

  /**
   * ★ 场景一（用户例式）：<b>目标独有条</b> —— 目标 10 人 farming=100，来源 10 人 farming 缺行（=0）⇒ 50（不是 100）。
   *
   * <pre>
   *   exact_from = 0×1000 + 0 = 0；exact_to = 100×1000 + 0 = 100,000
   *   mergedExact = ⌊(0×10 + 100,000×10) ÷ 20⌋ = ⌊1,000,000 ÷ 20⌋ = 50,000
   *   ⇒ value=50、remainder=0
   * </pre>
   */
  @Test
  void targetOnlyTrackIsDilutedByTheZeroExperienceMovers() {
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(
            ProductionData.empty()
                .withExperienceTrack(ProductionTestFixtures.track(FARMING, 1_000_000L, 1_000L, 0L))
                .withHouseholdExperience(TO, FARMING, ProductionTestFixtures.feature(100L, 0L)));

    engine.mergeExperience(FROM, 10L, TO, 10L);

    assertThat(engine.experienceOf(TO, FARMING))
        .as("★ 用户例式：目标 10 人 100 + 迁入 10 人缺行(=0) ⇒ ⌊1,000,000÷20⌋÷1000 = 50（旧实现 100）")
        .isEqualTo(50L);
    assertThat(engine.experienceStateOf(TO, FARMING).remainder()).as("余数 0（整值）").isZero();
  }

  /**
   * ★ 场景二：<b>来源独有条</b> —— 来源 10 人 milling=60，目标 30 人 milling 缺行（=0）⇒ 15。
   *
   * <pre>
   *   mergedExact = ⌊(60,000×10 + 0×30) ÷ 40⌋ = ⌊600,000 ÷ 40⌋ = 15,000 ⇒ value=15
   * </pre>
   */
  @Test
  void sourceOnlyTrackEntersTheAverageAsZeroOnTheTargetSide() {
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(
            ProductionData.empty()
                .withExperienceTrack(ProductionTestFixtures.track(MILLING, 1_000_000L, 1_000L, 0L))
                .withHouseholdExperience(FROM, MILLING, ProductionTestFixtures.feature(60L, 0L)));

    engine.mergeExperience(FROM, 10L, TO, 30L);

    assertThat(engine.experienceOf(TO, MILLING))
        .as(
            "★ 来源独有条：⌊60,000×10 ÷ 40⌋ = 15,000 ⇒ 15（旧实现只枚举来源条 ⇒ 也是 15；"
                + "判别力在'下限 = 0 参与'与并集枚举，见下面两条）")
        .isEqualTo(15L);
    assertThat(engine.experienceOf(FROM, MILLING)).as("★ 源户不减").isEqualTo(60L);
  }

  /**
   * ★ 场景三：<b>显式全零行</b> —— 来源与目标都显式登记了 crafting=(0,0)。
   *
   * <p>并集枚举必须看见它（旧实现的"只枚举来源条 + allZero 跳过"在这里恰好都退化成'不动'）， 且平均结果全零 ⇒ <b>从目标户删行</b> （与"没有这门经验"逐值等价）。
   */
  @Test
  void explicitAllZeroRowsAreAveragedAndDeletedInsteadOfBeingSkipped() {
    HouseholdExperienceKey key = ProductionTestFixtures.experienceKey(TO, CRAFTING);
    ProductionData base =
        ProductionData.empty()
            .withExperienceTrack(ProductionTestFixtures.track(CRAFTING, 1_000_000L, 1_000L, 0L))
            .withHouseholdExperience(FROM, CRAFTING, ProductionTestFixtures.feature(0L, 0L))
            .withHouseholdExperience(TO, CRAFTING, ProductionTestFixtures.feature(0L, 0L));
    assertThat(base.householdExperience()).as("前置：显式零行真的在表里（不是'没写进去'）").containsKey(key);
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(base);

    engine.mergeExperience(FROM, 10L, TO, 10L);

    assertThat(engine.experienceOf(TO, CRAFTING)).as("全零结果 ⇒ 主值 0").isZero();
    assertThat(engine.state().householdExperience())
        .as("★ 全零结果删行（与缺行逐值等价，不留显式零行）")
        .doesNotContainKey(key);
  }

  /**
   * ★ 场景四：<b>多次迁入累计</b> —— 同一目标户先后并入两批零经验人口，逐次重新加权。
   *
   * <pre>
   *   起点：TO 10 人 farming=100
   *   第一次：FROM 10 人缺行 ⇒ ⌊(0×10 + 100,000×10) ÷ 20⌋ = 50,000 ⇒ 50（TO 变 20 人）
   *   第二次：FROM2 20 人缺行、TO 20 人 50 ⇒ ⌊(0×20 + 50,000×20) ÷ 40⌋ = 25,000 ⇒ 25（TO 变 40 人）
   * </pre>
   */
  @Test
  void repeatedMergesAccumulateByReweightingTheCurrentTargetValue() {
    HouseholdId from2 = ProductionTestFixtures.H1;
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(
            ProductionData.empty()
                .withExperienceTrack(ProductionTestFixtures.track(FARMING, 1_000_000L, 1_000L, 0L))
                .withHouseholdExperience(TO, FARMING, ProductionTestFixtures.feature(100L, 0L)));

    engine.mergeExperience(FROM, 10L, TO, 10L);
    assertThat(engine.experienceOf(TO, FARMING)).as("第一次迁入 10 人缺行 ⇒ 50").isEqualTo(50L);

    engine.mergeExperience(from2, 20L, TO, 20L);
    assertThat(engine.experienceOf(TO, FARMING))
        .as("★ 第二次迁入 20 人缺行 ⇒ ⌊50,000×20 ÷ 40⌋ = 25,000 ⇒ 25（不得继续停在 50）")
        .isEqualTo(25L);
    assertThat(engine.experienceStateOf(TO, FARMING).remainder()).isZero();
  }

  /**
   * ★ 余数腿（完整特征参与平均）：目标独有条 value=20、remainder=700；来源缺行 ⇒ ⌊(0×10 + 20,700×30) ÷ 40⌋ = ⌊621,000 ÷ 40⌋
   * = 15,525 ⇒ value=15、remainder=525。
   */
  @Test
  void targetOnlyTrackCarriesItsRemainderIntoTheWeightedAverage() {
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(
            ProductionData.empty()
                .withExperienceTrack(ProductionTestFixtures.track(FARMING, 1_000_000L, 1_000L, 0L))
                .withHouseholdExperience(TO, FARMING, ProductionTestFixtures.feature(20L, 700L)));

    ProductionExperienceMoveOutcome receipt = engine.mergeExperience(FROM, 10L, TO, 30L);

    assertThat(engine.experienceOf(TO, FARMING)).as("主值 ⌊621,000÷40⌋÷1000 = 15").isEqualTo(15L);
    assertThat(engine.experienceStateOf(TO, FARMING).remainder())
        .as("★ 余数 525（完整特征参与平均，不是只平均主值）")
        .isEqualTo(525L);
    assertThat(receipt.changed()).as("确有变化").isTrue();
  }
}
