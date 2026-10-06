package io.mosire.simos.social.api.population;

/**
 * 逐家户、逐 {@code (年龄档, 性别)} 的一档出生/死亡率（2026-10-09 家户/人口架构 §4.3；2026-10-09 每 tick 计划 §2.1）。
 *
 * <p>两个率都不得为负，单位恒为 <b>ppm/tick（每百万分之一每 tick）</b>：结算方按 {@code numerator = 余数 + 份额 × rate}、{@code 人数
 * = numerator / 1_000_000} 取整，余数跨 tick 累加。 育龄段的出生率有效（精确年龄 15 ≤ ageYears &lt; 45），其余年龄段为
 * 0；契约层只负责非负与形状。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param bracketId 年龄档短名；非空白
 * @param sex 性别词表；不得为 null
 * @param birthRatePerMillionPerTick 每 tick 每百万人出生率（ppm/tick）；不得为负
 * @param deathRatePerMillionPerTick 每 tick 每百万人死亡率（ppm/tick）；不得为负
 */
public record HouseholdVitalRate(
    String bracketId, Sex sex, long birthRatePerMillionPerTick, long deathRatePerMillionPerTick) {

  public HouseholdVitalRate {
    if (bracketId == null || bracketId.isBlank()) {
      throw new IllegalArgumentException("HouseholdVitalRate.bracketId 不得为空白");
    }
    if (sex == null) {
      throw new IllegalArgumentException("HouseholdVitalRate.sex 不得为 null");
    }
    if (birthRatePerMillionPerTick < 0L) {
      throw new IllegalArgumentException(
          "HouseholdVitalRate.birthRatePerMillionPerTick 不得为负: " + birthRatePerMillionPerTick);
    }
    if (deathRatePerMillionPerTick < 0L) {
      throw new IllegalArgumentException(
          "HouseholdVitalRate.deathRatePerMillionPerTick 不得为负: " + deathRatePerMillionPerTick);
    }
  }
}
