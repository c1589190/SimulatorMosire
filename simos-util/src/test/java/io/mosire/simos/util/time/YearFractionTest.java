package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 设计稿 §四/§六 与 §十判据 4：calendar 与 EconomyVocabulary 共用的精确年分数值类型——构造即约分、精确相加、 向下取整（负值朝负无穷），以及中间积溢出时的
 * BigInteger 回退。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>构造器漏 gcd/约分或没把 0/d 归一成 ZERO ⇒ {@link #constructorReducesToLowestTerms} 红；
 *   <li>{@code add} 用 double 或漏约分 ⇒ {@link #addIsExactAndReduces} 红；
 *   <li>{@code add} 的溢出 catch 里不约分/回绕 ⇒ {@link #addOverflowFallsBackToBigInteger} 红；
 *   <li>{@code multiplyFloor} 用 {@code /}（向零截断）而非 {@link Math#floorDiv} ⇒ 负值两条红；
 *   <li>BigInteger 路径忘了负余数补一格的 floor 修正 ⇒ {@link #multiplyFloorOverflowFallsBackToBigInteger} 负向条红；
 *   <li>构造器漏负分子/非正分母校验 ⇒ {@link #rejectsNegativeNumeratorAndNonPositiveDenominator} 红。
 * </ul>
 */
class YearFractionTest {

  /** 判据：{@code new YearFraction(2,4)} == 1/2；{@code 0/7} 归一为 ZERO（0/1）；正分母构造后分母恒正。 */
  @Test
  void constructorReducesToLowestTerms() {
    YearFraction reduced = new YearFraction(2, 4);
    assertThat(reduced).isEqualTo(new YearFraction(1, 2));
    assertThat(reduced.numerator()).isEqualTo(1L);
    assertThat(reduced.denominator()).isEqualTo(2L);

    assertThat(new YearFraction(0, 7)).isEqualTo(YearFraction.ZERO);
    assertThat(new YearFraction(0, 7).numerator()).isZero();
    assertThat(new YearFraction(0, 7).denominator()).isEqualTo(1L);

    assertThat(new YearFraction(10, 5)).isEqualTo(new YearFraction(2, 1));
    assertThat(new YearFraction(3, 9).denominator()).isPositive();
    assertThat(new YearFraction(7, 14).denominator()).isEqualTo(2L);
  }

  /** 判据：{@code ONE == 1/1}、{@code ZERO == 0/1}。 */
  @Test
  void constantsAreOneAndZeroOverOne() {
    assertThat(YearFraction.ONE.numerator()).isEqualTo(1L);
    assertThat(YearFraction.ONE.denominator()).isEqualTo(1L);
    assertThat(YearFraction.ZERO.numerator()).isZero();
    assertThat(YearFraction.ZERO.denominator()).isEqualTo(1L);
    assertThat(YearFraction.ONE.toDouble()).isEqualTo(1.0);
    assertThat(YearFraction.ZERO.toDouble()).isZero();
  }

  /** 判据：精确相加并约分；1/3 + 1/6 = 1/2；同分母与加零。 */
  @Test
  void addIsExactAndReduces() {
    assertThat(new YearFraction(1, 3).add(new YearFraction(1, 6)))
        .isEqualTo(new YearFraction(1, 2));
    assertThat(new YearFraction(2, 5).add(new YearFraction(1, 5)))
        .isEqualTo(new YearFraction(3, 5));
    assertThat(YearFraction.ZERO.add(new YearFraction(2, 7))).isEqualTo(new YearFraction(2, 7));
    assertThat(new YearFraction(2, 7).add(YearFraction.ZERO)).isEqualTo(new YearFraction(2, 7));
  }

  /**
   * 判据：真能触发快路溢出的用例——{@code 1/2^32 + 1/2^32} 的裸积 {@code 2^32 × 2^32 = 2^64} 溢出，但结果 {@code 1/2^31}
   * 仍放得回 long。断言 {@code Math.multiplyExact} 确实在这一步抛，证明 BigInteger 回退不是摆设。
   */
  @Test
  void addOverflowFallsBackToBigInteger() {
    YearFraction halfOfTwoTo32 = new YearFraction(1L, 1L << 32);
    YearFraction sum = halfOfTwoTo32.add(halfOfTwoTo32);
    assertThat(sum).isEqualTo(new YearFraction(1L, 1L << 31));
    assertThat(sum.numerator()).isEqualTo(1L);
    assertThat(sum.denominator()).isEqualTo(1L << 31);

    assertThatThrownBy(() -> Math.multiplyExact(1L << 32, 1L << 32))
        .isInstanceOf(ArithmeticException.class);
  }

  /** 判据：{@code multiplyFloor} 向下取整（Math.floorDiv 语义），负值不向零截断。 */
  @Test
  void multiplyFloorRoundsTowardNegativeInfinity() {
    assertThat(new YearFraction(2, 3).multiplyFloor(7L)).isEqualTo(4L); // 14/3 = 4.66…
    assertThat(new YearFraction(2, 3).multiplyFloor(-7L)).isEqualTo(-5L); // 截断会得 -4
    assertThat(new YearFraction(1, 3).multiplyFloor(-1L)).isEqualTo(-1L); // 截断会得 0
    assertThat(new YearFraction(2, 3).multiplyFloor(0L)).isZero();
  }

  /**
   * 判据：快路 {@code Math.multiplyExact(value, numerator)} 真实溢出时走 BigInteger，且负值有余数时补一格 floor。
   *
   * <p>正向：{@code 3 × Long.MAX_VALUE = 27670116110564327421} 溢出，floor(/4) = 6917529027641081855。
   * 负向：{@code 3 × (Long.MIN_VALUE+1) = -27670116110564327421}，BigInteger 向零截断得余 -1，修正后 =
   * -6917529027641081856。两条都断言裸 long 乘确实抛。
   */
  @Test
  void multiplyFloorOverflowFallsBackToBigInteger() {
    assertThat(new YearFraction(3, 4).multiplyFloor(Long.MAX_VALUE))
        .isEqualTo(6917529027641081855L);
    assertThatThrownBy(() -> Math.multiplyExact(Long.MAX_VALUE, 3L))
        .isInstanceOf(ArithmeticException.class);

    assertThat(new YearFraction(3, 4).multiplyFloor(Long.MIN_VALUE + 1L))
        .isEqualTo(-6917529027641081856L);
    assertThatThrownBy(() -> Math.multiplyExact(Long.MIN_VALUE + 1L, 3L))
        .isInstanceOf(ArithmeticException.class);
  }

  /** 判据：负分子、denominator=0/负 一律 IllegalArgumentException（分母不翻转成正）。 */
  @Test
  void rejectsNegativeNumeratorAndNonPositiveDenominator() {
    assertThatThrownBy(() -> new YearFraction(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new YearFraction(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new YearFraction(1, -2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new YearFraction(0, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
