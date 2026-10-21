package io.mosire.simos.economy.time;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C8：向上跃迁权重的最大余数拆分唯一实现。</b>
 *
 * <p>C3 的 LAND 多 hex 拆分与 C8 的 LAND/马匹两步拆分必须共用同一份算法；不得在任一调用点复制 quotient/remainder 分配或
 * tie-break。调用方只提供权重与确定性 tie-order。
 *
 * <pre>
 * floorShare_i = floor(total × weight_i / Σweights)
 * remainder_i  = total × weight_i mod Σweights
 * leftover      = total − ΣfloorShare_i
 * leftover 个名额按 remainder desc → tieOrder 的顺序各 +1
 * </pre>
 *
 * <p>权重非负、total 非负；有 total 时 Σweights 必须 &gt; 0。返回数组逐项与权重同序，且 Σ返回 = total。
 */
final class UpwardMobilityAllocation {

  private UpwardMobilityAllocation() {}

  static long[] largestRemainder(long total, long[] weights, Comparator<Integer> tieOrder) {
    Objects.requireNonNull(weights, "largestRemainder.weights");
    Objects.requireNonNull(tieOrder, "largestRemainder.tieOrder");
    if (total < 0L) {
      throw new IllegalArgumentException("largestRemainder.total 不得为负: " + total);
    }
    long weightTotal = 0L;
    for (int index = 0; index < weights.length; index++) {
      long weight = weights[index];
      if (weight < 0L) {
        throw new IllegalArgumentException(
            "largestRemainder.weights[" + index + "] 不得为负: " + weight);
      }
      weightTotal = Math.addExact(weightTotal, weight);
    }
    long[] shares = new long[weights.length];
    if (total == 0L) {
      return shares;
    }
    if (weightTotal == 0L) {
      throw new IllegalArgumentException("largestRemainder.total > 0 时权重和必须 > 0: total=" + total);
    }

    BigInteger denominator = BigInteger.valueOf(weightTotal);
    List<Remainder> remainders = new ArrayList<>(weights.length);
    long allocated = 0L;
    for (int index = 0; index < weights.length; index++) {
      BigInteger numerator = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[index]));
      BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
      long share = quotientAndRemainder[0].longValueExact();
      shares[index] = share;
      allocated = Math.addExact(allocated, share);
      remainders.add(new Remainder(index, quotientAndRemainder[1]));
    }

    remainders.sort(
        Comparator.comparing(Remainder::remainder, Comparator.reverseOrder())
            .thenComparing(Remainder::index, tieOrder));
    long leftover = Math.subtractExact(total, allocated);
    if (leftover < 0L || leftover > weights.length) {
      throw new IllegalStateException(
          "largestRemainder 余数名额非法: total="
              + total
              + " allocated="
              + allocated
              + " leftover="
              + leftover
              + " weights="
              + weights.length);
    }
    for (int position = 0; position < Math.toIntExact(leftover); position++) {
      int index = remainders.get(position).index();
      shares[index] = Math.addExact(shares[index], 1L);
    }

    long sum = 0L;
    for (long share : shares) {
      sum = Math.addExact(sum, share);
    }
    if (sum != total) {
      throw new IllegalStateException("largestRemainder 不守恒: total=" + total + " actual=" + sum);
    }
    return shares;
  }

  private record Remainder(int index, BigInteger remainder) {}
}
