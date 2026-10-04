package io.mosire.simos.social.api.population;

/**
 * 家户/区域人口按年龄段的只读视图（2026-10-09 家户/人口架构 §4.3）。
 *
 * <p>{@code increaseRatePerTick} 只有最小年龄段（0 岁档）承载出生结果；其他年龄段的增加用人口事件表达，不是率。 {@code deathRatePerTick}
 * 是逐年龄段死亡率。数量与率都不得为负，区间必须 {@code minAgeDays <= maxAgeDays}。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param bracketId 年龄档短名（如 {@code 0-4}）；非空白
 * @param minAgeDays 档下界（天）
 * @param maxAgeDays 档上界（天）；不得小于下界
 * @param sex 性别词表；不得为 null
 * @param count 当前人数；不得为负
 * @param increaseRatePerTick 每 tick 增加率（千分/同口径由消费方定）；不得为负
 * @param deathRatePerTick 每 tick 死亡率；不得为负
 */
public record AgeBracketView(
    String bracketId,
    long minAgeDays,
    long maxAgeDays,
    Sex sex,
    long count,
    long increaseRatePerTick,
    long deathRatePerTick) {

  public AgeBracketView {
    if (bracketId == null || bracketId.isBlank()) {
      throw new IllegalArgumentException("AgeBracketView.bracketId 不得为空白");
    }
    if (sex == null) {
      throw new IllegalArgumentException("AgeBracketView.sex 不得为 null");
    }
    if (minAgeDays > maxAgeDays) {
      throw new IllegalArgumentException(
          "AgeBracketView 的 minAgeDays 不得大于 maxAgeDays: " + minAgeDays + " > " + maxAgeDays);
    }
    if (count < 0L) {
      throw new IllegalArgumentException("AgeBracketView.count 不得为负: " + count);
    }
    if (increaseRatePerTick < 0L) {
      throw new IllegalArgumentException(
          "AgeBracketView.increaseRatePerTick 不得为负: " + increaseRatePerTick);
    }
    if (deathRatePerTick < 0L) {
      throw new IllegalArgumentException(
          "AgeBracketView.deathRatePerTick 不得为负: " + deathRatePerTick);
    }
  }
}
