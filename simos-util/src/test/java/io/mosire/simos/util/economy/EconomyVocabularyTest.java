package io.mosire.simos.util.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.YearFraction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **口粮口径的两个纯函数**（V5；v2 spec §八.6）：累计（人 × 天 ⇒ 毫粮）与逐日差分（人 × 第几天 ⇒ 该日毫粮）。
 *
 * <p>★★ **病灶形态**：v1 把 {@code 10,000 ÷ 120 = 83.33} 的**商** 83 当"每人每日口粮"，再乘人口与天数 ⇒ 每人每周期实吃 {@code 120
 * × 83 = 9,960 ≠ 10,000}（**−0.4%**，三国全境每周期少约 47.3 万粮）。残差**在定义上**就丢了，不是"除法取整的误差"。
 *
 * <p>★ 逐日差分则一分不丢：{@code Σ(第 1..120 天) == 人口 × 10,000} 精确成立（telescoping：Σ 相邻差 = 首尾累计之差）。
 */
class EconomyVocabularyTest {

  /** 口径的两个数（这里**读**它们、不重写数值）：10,000 毫粮 / 人 · 120 天。 */
  private static final long PER_CYCLE = EconomyVocabulary.RATION_MILLI_PER_PERSON;

  private static final long CYCLE_DAYS = EconomyVocabulary.RATION_CYCLE_DAYS;

  /**
   * ★★ **一整个周期的 Σ 日耗 == 人口 × 10,000 毫粮，精确**（§八.6 的验收判据）。
   *
   * <p>★ 逐日累加（**不**用"天数 × 某一天的量"那种式子）；样本覆盖除得尽与除不尽两类人口。
   */
  @Test
  void summingTheDailyRationsOverOneCycleIsExactlyThePopulationTimesTenThousand() {
    for (long population : List.of(1L, 7L, 100L, 450L, 1_000L, 14_806L)) {
      long sum = 0L;
      for (long day = 1L; day <= CYCLE_DAYS; day++) {
        sum += EconomyVocabulary.dailyRationMilli(population, day);
      }
      assertThat(sum)
          .as("人口 %d：Σ(第 1..120 天) 必须恰为 人口 × 10,000", population)
          .isEqualTo(population * PER_CYCLE);
      assertThat(sum)
          .as("人口 %d：累计函数在同一处给出同一个数（Σ 相邻差 == 首尾累计之差）", population)
          .isEqualTo(EconomyVocabulary.cumulativeRationMilli(population, CYCLE_DAYS));
    }
  }

  /**
   * ★★ **逐日差分**：每一天的量都等于相邻两个累计值之差（这是"残差不丢"的实现口径，不是巧合）。
   *
   * <p>★ 同时钉住"逐日不同"这个事实：100 人的第 1、2 天各 8,333，第 3 天是 8,334 —— 旧的"每人每日 83"常数正是**在这里说谎**。
   */
  @Test
  void theDailyRationIsTheDifferenceOfNeighbouringCumulativeValues() {
    for (long day : List.of(1L, 2L, 3L, 7L, CYCLE_DAYS, CYCLE_DAYS + 1L, 2L * CYCLE_DAYS)) {
      assertThat(EconomyVocabulary.dailyRationMilli(100L, day))
          .as("第 %d 天 == 累计(%d) − 累计(%d)", day, day, day - 1L)
          .isEqualTo(
              EconomyVocabulary.cumulativeRationMilli(100L, day)
                  - EconomyVocabulary.cumulativeRationMilli(100L, day - 1L));
    }
    assertThat(EconomyVocabulary.dailyRationMilli(100L, 1L))
        .as("100 人第 1 天：floor(100 × 10,000 ÷ 120) = 8,333")
        .isEqualTo(8_333L);
    assertThat(EconomyVocabulary.dailyRationMilli(100L, 2L)).as("第 2 天仍 8,333").isEqualTo(8_333L);
    assertThat(EconomyVocabulary.dailyRationMilli(100L, 3L))
        .as("★ 第 3 天是 8,334（= 25,000 − 16,666）⇒ 日耗**逐日不同**，乘不出来")
        .isEqualTo(8_334L);
    assertThat(EconomyVocabulary.dailyRationMilli(1L, 1L))
        .as("1 人第 1 天：floor(10,000 ÷ 120) = 83（这才是旧的「每人每日 83」那一档）")
        .isEqualTo(83L);
  }

  /** ★★ **残差不是零头，是被旧口径丢掉的那 0.4%**：旧式子 {@code 人口 × 83 × 120} 与口径值必须**不相等** （若相等，说明这个口径改动没有内容）。 */
  @Test
  void theOldEightyThreePerPersonLosesTheResidual() {
    long population = 1_000L;
    long exact = EconomyVocabulary.cumulativeRationMilli(population, CYCLE_DAYS);
    long oldApproximation = population * 83L * CYCLE_DAYS;

    assertThat(exact).as("精确值 = 人口 × 10,000").isEqualTo(10_000_000L);
    assertThat(oldApproximation).as("旧的近似值 = 人口 × 83 × 120").isEqualTo(9_960_000L);
    assertThat(exact)
        .as("★ 判别力：两者差 40,000 毫粮/千人·周期（−0.4%），旧的 83 口径在定义上就丢了它")
        .isNotEqualTo(oldApproximation);
  }

  /** 累计函数在整周期处**恰好**是口径的整数倍 ⇒ 残差**不跨周期漂移**（第 2 个周期的和同样精确）。 */
  @Test
  void everyWholeCycleSumsExactlySoTheResidualDoesNotDrift() {
    for (long cycles : List.of(1L, 2L, 3L)) {
      assertThat(
              EconomyVocabulary.cumulativeRationMilli(7L, cycles * CYCLE_DAYS)
                  - EconomyVocabulary.cumulativeRationMilli(7L, (cycles - 1L) * CYCLE_DAYS))
          .as("第 %d 个周期的口粮合计 = 人口 × 10,000", cycles)
          .isEqualTo(7L * PER_CYCLE);
    }
  }

  /** 边界与守卫：0 人 / 0 天 ⇒ 0；负数与"第 0 天" ⇒ 拒（创世是第 0 天，没有第 0 天这一天）。 */
  @Test
  void zeroIsZeroAndBadInputsAreRejected() {
    assertThat(EconomyVocabulary.cumulativeRationMilli(0L, CYCLE_DAYS)).isZero();
    assertThat(EconomyVocabulary.cumulativeRationMilli(100L, 0L)).isZero();
    assertThatThrownBy(() -> EconomyVocabulary.cumulativeRationMilli(-1L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人口不得为负");
    assertThatThrownBy(() -> EconomyVocabulary.cumulativeRationMilli(1L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("天数不得为负");
    assertThatThrownBy(() -> EconomyVocabulary.dailyRationMilli(1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须 ≥ 1");
  }

  /**
   * ★ **口径的载体就是这两个数**（`10,000 毫粮 / 人 · 120 天`）：谁把它改成"每人每日的量"，这条就红 —— 而"每人每日的量" 在整数域里**不存在**（10,000
   * ÷ 120 = 83.33…）。
   */
  @Test
  void theBasisIsAPerPersonPerCycleAmountNotAPerDayAmount() {
    assertThat(PER_CYCLE).as("分子：每人每 120 天 10 粮 = 10,000 毫粮").isEqualTo(10_000L);
    assertThat(CYCLE_DAYS).as("分母：120 天").isEqualTo(120L);
    assertThat(PER_CYCLE % CYCLE_DAYS)
        .as("★ 除不尽（余 40）—— 所以「每人每日的口粮」在整数域里不存在，口径只能以累计/差分的形式承载")
        .isEqualTo(40L);
  }

  // ── R3（T1）：商品词表与"每商品一条需求"的形状 ────────────────────────────────────────

  /**
   * ★★ **六个商品 id 各恰一份、值互不相同**（T1 的唯一拼写点）：字面量的"恰一份"由源扫描护栏 （{@code
   * EconomyVocabularyGuardTest}）把守，这里把**值**钉住 —— 两条互补：前者挡"第二处拼写"， 后者挡"两处拼写改成同一个值"。★ 判别力：把 {@code
   * CLOTH_COMMODITY_ID} 写成 {@code "grain"} ⇒ 本条红。
   */
  @Test
  void everyCommodityIdIsDistinct() {
    List<String> ids =
        List.of(
            EconomyVocabulary.GRAIN_COMMODITY_ID,
            EconomyVocabulary.CLOTH_COMMODITY_ID,
            EconomyVocabulary.FIBER_COMMODITY_ID,
            EconomyVocabulary.TOOL_COMMODITY_ID,
            EconomyVocabulary.IRON_COMMODITY_ID,
            EconomyVocabulary.WOOD_COMMODITY_ID);
    assertThat(ids)
        .as("粮 / 布 / 纤维 / 工具 / 铁 / 木：六个 id 互不相同（同名的两种商品会让守恒式莫名其妙不平）")
        .doesNotHaveDuplicates()
        .containsExactly("grain", "cloth", "fiber", "tool", "iron", "wood");
  }

  /** ★ 「每单位商品 = 1000 最小计量单位」是**商品无关**的口径：粮的别名与它必须同一个值。 */
  @Test
  void theMilliPrefixIsOneThousandForEveryCommodity() {
    assertThat(EconomyVocabulary.MILLI_PER_COMMODITY_UNIT).isEqualTo(1_000L);
    assertThat(EconomyVocabulary.MILLI_PER_GRAIN)
        .as("★ 粮的别名与它同值（两者是同一件事的两个名字）")
        .isEqualTo(EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
  }

  /**
   * ★★ **衣着口径与口粮口径刻意不同**（spec §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）： 粮是"每人每 120 天 10 粮"、布是"每人每**历法年**（365 或
   * 366 天）1 匹"。
   *
   * <p>★ 判别力：把两个时间尺度写成同一个数（"共用一条每人每周期的量"）⇒ 本条红。
   */
  @Test
  void theClothBasisIsDeliberatelyDifferentFromTheRationBasis() {
    assertThat(EconomyVocabulary.CLOTH_MILLI_PER_PERSON)
        .as("每人每历法年 1 匹布 = 1,000 毫布")
        .isEqualTo(1_000L);
    assertThat(EconomyVocabulary.RATION_CYCLE_DAYS)
        .as("★ 两条时间尺度必须不同：口粮是 120 天业务周期，衣着是历法年（本仓不再有固定 365 的衣着周期常量）")
        .isEqualTo(120L)
        .isNotEqualTo(365L);
  }

  /**
   * ★★ **整历法年的累计衣着需求 = 人口 × 1,000 毫布，精确**（设计稿 §六.1）。
   *
   * <p>★ {@code YearFraction.ONE} 表达"整个历法年"（平年 365/365、闰年 366/366 都约简为 1/1）——年长不再以固定 365
   * 除法的形式出现在口径里。
   */
  @Test
  void oneWholeCalendarYearOfClothIsExactlyPopulationTimesOneThousand() {
    for (long population : List.of(0L, 1L, 7L, 100L, 1_000L, 14_806L)) {
      assertThat(EconomyVocabulary.cumulativeClothMilli(population, YearFraction.ONE))
          .as("人口 %d：整历法年 ⇒ 人口 × 1,000 毫布（不因平/闰年而变）", population)
          .isEqualTo(population * EconomyVocabulary.CLOTH_MILLI_PER_PERSON);
    }
    // 平年 365/365 与闰年 366/366 都约简为 ONE ⇒ 与 ONE 同值；0 区间 ⇒ 0。
    assertThat(EconomyVocabulary.cumulativeClothMilli(123L, new YearFraction(365L, 365L)))
        .isEqualTo(123L * EconomyVocabulary.CLOTH_MILLI_PER_PERSON);
    assertThat(EconomyVocabulary.cumulativeClothMilli(123L, new YearFraction(366L, 366L)))
        .isEqualTo(123L * EconomyVocabulary.CLOTH_MILLI_PER_PERSON);
    assertThat(EconomyVocabulary.cumulativeClothMilli(123L, YearFraction.ZERO)).isZero();
  }

  /**
   * ★★ **单日的衣着需求按该日所在历法年的年长折算**：平年日 = 1/365、闰年日 = 1/366（设计稿 §六.1"闰年窗口用 366 分母"）。
   *
   * <p>★ 判别力：把 365/366 分母互换，或对 {@code YearFraction.ONE} 的特殊处理写错 ⇒ 本条红（与整年用例成对）。
   */
  @Test
  void oneDayOfClothUsesThatCalendarYearsOwnDenominator() {
    assertThat(EconomyVocabulary.dailyClothNeedMilli(100L, new YearFraction(1L, 365L)))
        .as("平年日：floor(100 × 1,000 ÷ 365) = 273")
        .isEqualTo(273L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(100L, new YearFraction(1L, 366L)))
        .as("闰年日：floor(100 × 1,000 ÷ 366) = 273（一年里两天的 floor 差会由累计口径补回，本函数只算当日分数）")
        .isEqualTo(273L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(1L, new YearFraction(1L, 365L))).isEqualTo(2L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(1L, new YearFraction(1L, 366L)))
        .as("1 人：floor(1000/365)=2 与 floor(1000/366)=2 相等，故换 3 人区分分母")
        .isEqualTo(2L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(3L, new YearFraction(1L, 365L)))
        .as("3 人平年日 floor(3000/365) = 8")
        .isEqualTo(8L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(3L, new YearFraction(1L, 366L)))
        .as("★ 3 人闰年日 floor(3000/366) = 8……但换 366 人可见差异")
        .isEqualTo(8L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(366L, new YearFraction(1L, 365L)))
        .as("366 人平年日 floor(366000/365) = 1002")
        .isEqualTo(1_002L);
    assertThat(EconomyVocabulary.dailyClothNeedMilli(366L, new YearFraction(1L, 366L)))
        .as("★ 366 人闰年日 = 1000 ⇒ 同一人口在平/闰年日不同，分母确实来自该历法年")
        .isEqualTo(1_000L);
  }

  /** 守卫：人口为负、年分数为 null ⇒ 拒；0 人 ⇒ 0。 */
  @Test
  void clothNeedsRejectBadInputs() {
    assertThat(EconomyVocabulary.cumulativeClothMilli(0L, YearFraction.ONE)).isZero();
    assertThatThrownBy(() -> EconomyVocabulary.cumulativeClothMilli(-1L, YearFraction.ONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人口不得为负");
    assertThatThrownBy(() -> EconomyVocabulary.cumulativeClothMilli(1L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("年分数不得为 null");
  }

  /**
   * ★★ **每商品一条需求**（T1 的形状；阈值与死亡作用留 R4）：{@code dailyNeedsMilli} 给出**保序**的两条 ——
   * 粮在前、布在后，值与两个单商品函数逐值相同。
   *
   * <p>★ 判别力：只发粮一条（"需求还是标量"）⇒ 本条红。
   */
  @Test
  void dailyNeedsCarryOneEntryPerCommodity() {
    YearFraction dayFraction = new YearFraction(1L, 365L);
    Map<String, Long> needs = EconomyVocabulary.dailyNeedsMilli(100L, 3L, dayFraction);

    assertThat(needs.keySet())
        .as("保序：词表序（粮、布）—— 结算把它写进 naturalNeeds，迭代序必须是内容的纯函数")
        .containsExactly(
            EconomyVocabulary.GRAIN_COMMODITY_ID, EconomyVocabulary.CLOTH_COMMODITY_ID);
    assertThat(needs.get(EconomyVocabulary.GRAIN_COMMODITY_ID))
        .isEqualTo(EconomyVocabulary.dailyRationMilli(100L, 3L));
    assertThat(needs.get(EconomyVocabulary.CLOTH_COMMODITY_ID))
        .isEqualTo(EconomyVocabulary.dailyClothNeedMilli(100L, dayFraction));
  }
}
