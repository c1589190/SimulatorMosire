package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.time.EconomySettlement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★ **跨模块一致性：{@code EconomySettlement.allocate ≡ EconomySeeder.splitProportional}**（v2 spec §八.7
 * 的「口径统一」）。
 *
 * <p>★★ **为什么这条用例是"统一"的证据而不是注释**：v1 里两个函数**同名不同义**：{@code allocate} 的分母恒 1000‰、残差
 * **按索引序人人均摊**；{@code splitProportional} 的分母是 **Σ权重**、残差按**下标序**逐个 +1。两者对同一组输入给出不同的数，
 * 而代码里却写着「已统一」（本仓最忌的**假统一**）。
 *
 * <p>★ 现在两者都走 {@code ProportionalSplit.byDenominator} 且分母同为 Σ权重 ⇒ 本用例逐值对拍（同输入 ⇒ 同输出）。
 *
 * <p>★ 判别力（变异实测）：把**任一方**的分母改回 1000 ⇒ 本类逐值对拍当场红（3 条里 2 条红）。
 *
 * <p>★★ **诚实边界**：把**残差规则本身**（最大余数法 → 按下标序）改回 v1 —— 因为两边共用同一份实现 —— **本类会保持绿**
 * （变异实测）。规则本身的守卫在别处：{@code ProportionalSplitTest.theLargestRemainderTakesTheResidue}、 {@code
 * EconomySettlementTest.allocateDistributesResidueByLargestRemainderKeepingTheTotal}、 {@code
 * EconomySeederTest.roundingResidualKeepsTheTotalAndGoesToTheLargestRemainders}（同一变异下这三处红）。 ⇒
 * 本类守的是「两个入口仍是同一份口径」，**不是**「残差规则是最大余数法」。
 */
class EconomyAllocationConsistencyTest {

  /** 真档每格农业四行的**真实**权重（means 700 × 土地占比 + labor 300 × 劳动占比，§八.7 的数）= 464/354/144/36。 */
  private static final long[] REAL_WEIGHTS = {464L, 354L, 144L, 36L};

  /** 同一组输入分别喂两个入口 ⇒ **逐值相等**。 */
  @Test
  void allocateAndSplitProportionalAgreeValueByValue() {
    List<long[]> weightSets =
        List.of(
            REAL_WEIGHTS,
            new long[] {700L, 300L},
            new long[] {1L, 1L, 1L},
            new long[] {999L},
            new long[] {0L, 0L, 0L, 0L},
            new long[] {3L, 5L, 7L, 11L, 13L});
    List<Long> totals = List.of(5_950_000L, 201_469_000L, 7L, 1_000L, 0L, 999_999_999L);

    for (long[] weights : weightSets) {
      for (long total : totals) {
        assertThat(EconomySettlement.allocate(total, weights))
            .as("allocate(%d, %s) 必须与 splitProportional 逐值相等", total, Arrays.toString(weights))
            .containsExactly(EconomySeeder.splitProportional(total, weights));
      }
    }
  }

  /** 同一条不变式在两侧都成立：**Σ结果 == total**（分母 = Σ权重 ⇒ 残差只是取整余数，不丢总量）。 */
  @Test
  void bothSidesAlwaysSumToTheTotal() {
    for (long total : List.of(5_950_000L, 201_469_000L, 1_000L, 7L)) {
      for (long[] weights :
          List.of(REAL_WEIGHTS, new long[] {700L, 300L}, new long[] {1L, 1L, 1L})) {
        long allocated = sum(EconomySettlement.allocate(total, weights));
        long split = sum(EconomySeeder.splitProportional(total, weights));
        assertThat(allocated)
            .as("allocate 的 Σ == total（权重 %s）", Arrays.toString(weights))
            .isEqualTo(total);
        assertThat(split).as("splitProportional 的 Σ == total").isEqualTo(total);
      }
    }
  }

  /**
   * ★★ **真档那笔账**：按权重 464/354/144/36 分 5,950,000 —— 每一行都 ≈ 按权重应得（差 ≤ 1），尤其是权重最小的那一行 （地主
   * 36‰）不再拿"均摊"的那一份。
   */
  @Test
  void theSmallestWeightRowGetsItsWeightProportionalShareOnTheRealWeights() {
    long total = 5_950_000L;
    long[] parts = EconomySettlement.allocate(total, REAL_WEIGHTS);
    long weightSum = sum(REAL_WEIGHTS);

    for (int i = 0; i < parts.length; i++) {
      assertThat(parts[i])
          .as("第 %d 行：实得与 按权重应得 floor(total × w ÷ Σw) 的差 ≤ 1", i)
          .isBetween(total * REAL_WEIGHTS[i] / weightSum, total * REAL_WEIGHTS[i] / weightSum + 1L);
    }
    // v1 的口径（分母 1000 + 人均摊）会给最小权重那一行 5,950,000 × 36 ÷ 1000 + 2,975 = 217,175，
    // 而它按权重只该得 214,629 ⇒ 实得是应得的 7 倍（spec §八.7 的原始病灶）。
    assertThat(parts[3]).as("★ 与 v1 的均摊口径必须不同").isNotEqualTo(217_175L);
  }

  private static long sum(long[] values) {
    long total = 0L;
    for (long value : values) {
      total += value;
    }
    return total;
  }
}
