package io.mosire.simos.util.time;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 历法年分数：非负精确有理数 {@code numerator / denominator}（构造即约分，分母恒正）。
 *
 * <p>用途是把「某区间跨过的历年的一部分」表达成精确分数：整年 = {@link #ONE}（1/1），闰年窗口用 366 作分母。 放在 util 是为了让 calendar 的日历折算与
 * EconomyVocabulary 的衣着年长共用同一个值类型，避免出现两套 「年分数」形状（设计稿 §四、§六）。
 *
 * <p>纯值类型、不可变；不依赖任何领域模块（util 是 util → map → calendar 的最上游）。
 */
public record YearFraction(long numerator, long denominator) {

  /** 0/1。 */
  public static final YearFraction ZERO = new YearFraction(0, 1);

  /** 1/1。 */
  public static final YearFraction ONE = new YearFraction(1, 1);

  /**
   * 构造期校验并约分：{@code numerator >= 0}、{@code denominator > 0}，随后用 gcd 约分—— {@code gcd(0, d) = d}，故
   * 0/d 一律归一为 {@link #ZERO}（0/1）。
   *
   * @throws IllegalArgumentException numerator 为负，或 denominator 非正
   */
  public YearFraction {
    if (numerator < 0) {
      throw new IllegalArgumentException("numerator 不得为负：" + numerator);
    }
    if (denominator <= 0) {
      throw new IllegalArgumentException("denominator 必须为正：" + denominator);
    }
    long divisor = gcd(numerator, denominator);
    numerator /= divisor;
    denominator /= divisor;
  }

  /**
   * 精确相加：{@code a/b + c/d = (a·d + c·b) / (b·d)}，结果再约分。
   *
   * <p>中间积溢出时不静默回绕：先用 {@code Math.multiplyExact}/{@code Math.addExact} 走快路， 只有真的溢出才退到 BigInteger
   * 精确计算；最终结果仍必须放得回 long（否则抛 ArithmeticException）。
   */
  public YearFraction add(YearFraction other) {
    Objects.requireNonNull(other, "other");
    try {
      long sum =
          Math.addExact(
              Math.multiplyExact(numerator, other.denominator),
              Math.multiplyExact(other.numerator, denominator));
      long product = Math.multiplyExact(denominator, other.denominator);
      return new YearFraction(sum, product);
    } catch (ArithmeticException overflow) {
      BigInteger sum =
          BigInteger.valueOf(numerator)
              .multiply(BigInteger.valueOf(other.denominator))
              .add(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
      BigInteger product =
          BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));
      // 先在 BigInteger 里约分：中间积可能超出 long，但约简后的结果仍可能放得回 long
      // （例：1/2^32 + 1/2^32 = 1/2^31；裸积 2^64 溢出，约简后才落回 long）。
      BigInteger divisor = sum.gcd(product);
      return new YearFraction(
          sum.divide(divisor).longValueExact(), product.divide(divisor).longValueExact());
    }
  }

  /**
   * {@code floor(value × numerator / denominator)}——对负的 {@code value} 也按数学上的向下取整 （负无穷方向，{@link
   * Math#floorDiv(long, long)} 语义），不是向零截断。
   *
   * <p>溢出处理同 {@link #add(YearFraction)}：快路溢出不静默回绕，退 BigInteger 精确取 floor。
   */
  public long multiplyFloor(long value) {
    try {
      return Math.floorDiv(Math.multiplyExact(value, numerator), denominator);
    } catch (ArithmeticException overflow) {
      BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
      BigInteger[] quotientAndRemainder =
          product.divideAndRemainder(BigInteger.valueOf(denominator));
      BigInteger quotient = quotientAndRemainder[0];
      if (product.signum() < 0 && quotientAndRemainder[1].signum() != 0) {
        quotient = quotient.subtract(BigInteger.ONE); // BigInteger.divide 向零截断 ⇒ 负商补一格 floor
      }
      return quotient.longValueExact();
    }
  }

  /** 转 double（仅展示/近似估算用；精确语义一律走整数分子分母）。 */
  public double toDouble() {
    return (double) numerator / (double) denominator;
  }

  /** 非负整数的欧几里得 gcd；任一参数为 0 时返回另一参数（故 gcd(0, d) = d > 0）。 */
  private static long gcd(long a, long b) {
    while (b != 0) {
      long remainder = a % b;
      a = b;
      b = remainder;
    }
    return a;
  }
}
