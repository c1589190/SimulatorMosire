package io.mosire.simos.util.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ **最大余数法**（v2 spec §八.7）：分母给定时的定点切分 + 残差分派规则。
 *
 * <p>★★ **规则**：{@code parts[i] = total × w[i] ÷ 分母}（向下取整）；残差按**余数 {@code total × w[i] mod 分母}
 * 大者先得** 分派，**同余数按下标序**。它取代了 v1 的"按索引序逐个 +1 / 人人均摊"两套互相矛盾的残差口径。
 */
class ProportionalSplitTest {

  /** 余数大者先得：7 × 600 ÷ 1000 = 4 余 200，7 × 400 ÷ 1000 = 2 余 800 ⇒ 残差给第二项。 */
  @Test
  void theLargestRemainderTakesTheResidue() {
    assertThat(ProportionalSplit.byDenominator(7L, new long[] {600L, 400L}, 1000L))
        .containsExactly(4L, 3L);
  }

  /** 平手 ⇒ 按下标序（确定、可复现：不依赖任何库排序的稳定性）。 */
  @Test
  void tiesGoByIndexOrder() {
    assertThat(ProportionalSplit.byDenominator(1_000L, new long[] {333L, 333L, 333L}, 999L))
        .as("三项余数都是 333 ⇒ 残差 1 给下标最小者")
        .containsExactly(334L, 333L, 333L);
  }

  /** Σ结果 == total（分母 ≥ Σ权重 时恒成立）—— 这是"不丢总量"的唯一保证。 */
  @Test
  void thePartsAlwaysSumToTheTotal() {
    long[][] weightSets = {
      {464L, 354L, 144L, 36L},
      {1L, 1L, 1L},
      {0L, 0L, 0L, 5L},
      {999L},
      {3L, 5L, 7L, 11L, 13L},
    };
    for (long[] weights : weightSets) {
      long sum = 0L;
      for (long weight : weights) {
        sum += weight;
      }
      long[] parts = ProportionalSplit.byDenominator(5_950_000L, weights, sum);
      long assigned = 0L;
      for (long part : parts) {
        assigned += part;
      }
      assertThat(assigned)
          .as("Σparts == total（权重 %s）", java.util.Arrays.toString(weights))
          .isEqualTo(5_950_000L);
    }
  }

  /** 权重全 0（分母 0）⇒ 整份记在第一项：不丢总量，也不除零。 */
  @Test
  void anAllZeroDenominatorPutsEverythingOnTheFirstSlot() {
    assertThat(ProportionalSplit.byDenominator(1_000L, new long[] {0L, 0L, 0L, 0L}, 0L))
        .containsExactly(1_000L, 0L, 0L, 0L);
  }

  /** ★★ **Σ权重 &gt; 分母（比例表被改坏）⇒ 不静默归一化**：原样返回（Σ &gt; total），让调用方的守恒判据当场红。 */
  @Test
  void aBrokenWeightTableIsNotSilentlyRenormalised() {
    long[] parts =
        ProportionalSplit.byDenominator(1_000L, new long[] {450L, 350L, 150L, 100L}, 1000L);

    long sum = 0L;
    for (long part : parts) {
      sum += part;
    }
    assertThat(sum).as("Σ = 1050 ≠ 1000（负残差**不**从别处扣回来）").isEqualTo(1050L);
  }

  /** 没有可承载的槽位：total 非 0 ⇒ 拒（响亮）；total 为 0 ⇒ 空数组。 */
  @Test
  void emptyWeightsAreRejectedUnlessTheTotalIsZero() {
    assertThatThrownBy(() -> ProportionalSplit.byDenominator(1L, new long[0], 1000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有可承载的槽位");
    assertThat(ProportionalSplit.byDenominator(0L, new long[0], 1000L)).isEmpty();
  }
}
