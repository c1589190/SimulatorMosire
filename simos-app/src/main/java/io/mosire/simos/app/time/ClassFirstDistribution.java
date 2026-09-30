package io.mosire.simos.app.time;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ★★ <b>R2b：把"池级/批次级增量"逐值分到若干家户/批次的确定性算法</b>（{@link ClassFirstActorWriteback} 与 {@link
 * ClassFirstSocialWriteback} 共用同一份，不各写一遍）。
 *
 * <p>本仓对"分摊"的既有纪律是：<b>残差规则只许有一处</b>（见 {@code ProportionalSplit} 的类注）。故这里只提供两件纯函数：
 *
 * <ul>
 *   <li>{@link #largestRemainder(long, long[])}：正增量按权重最大余数法 ⇒ Σ = 增量，且不出现"因为四舍五入丢了一个单位"；
 *   <li>{@link #orderByDescending(long[])}：负增量瀑布扣减的确定性顺序（值降序、同值下标升序）——"下一个扣谁"不随 HashMap 迭代序漂移。
 * </ul>
 */
final class ClassFirstDistribution {

  private ClassFirstDistribution() {}

  /**
   * 把 {@code amount}（≥ 0）按 {@code weights}（≥ 0）最大余数法分出去：返回长度 = {@code weights.length} 且 Σ = {@code
   * amount}。
   *
   * <p>★ 权重全 0（"这个池/这批没有人口"）⇒ 全部落第一个（确定性、不丢）；调用方仍需保证"第一个"确实存在。
   */
  static long[] largestRemainder(long amount, long[] weights) {
    if (amount < 0L) {
      throw new IllegalArgumentException("amount 不得为负: " + amount);
    }
    long[] shares = new long[weights.length];
    if (amount == 0L) {
      return shares;
    }
    if (weights.length == 0) {
      throw new IllegalArgumentException("没有权重可分摊非零增量: " + amount);
    }
    long totalWeight = 0L;
    for (long weight : weights) {
      if (weight < 0L) {
        throw new IllegalArgumentException("权重不得为负: " + weight);
      }
      totalWeight += weight;
    }
    if (totalWeight == 0L) {
      shares[0] = amount;
      return shares;
    }
    long[] remainder = new long[weights.length];
    long assigned = 0L;
    for (int i = 0; i < weights.length; i++) {
      long product = Math.multiplyExact(amount, weights[i]);
      shares[i] = product / totalWeight;
      remainder[i] = product % totalWeight;
      assigned += shares[i];
    }
    long left = amount - assigned; // 0 ≤ left < weights.length
    for (int index : orderByDescending(remainder)) {
      if (left <= 0L) {
        break;
      }
      shares[index]++;
      left--;
    }
    return shares;
  }

  /** 下标按值降序、同值按下标升序（确定性）。 */
  static int[] orderByDescending(long[] values) {
    List<Integer> order = new ArrayList<>(values.length);
    for (int i = 0; i < values.length; i++) {
      order.add(i);
    }
    order.sort(
        Comparator.<Integer>comparingLong(index -> -values[index])
            .thenComparingInt(index -> index));
    int[] result = new int[order.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = order.get(i);
    }
    return result;
  }
}
