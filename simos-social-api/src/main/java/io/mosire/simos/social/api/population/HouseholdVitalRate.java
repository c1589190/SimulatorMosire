package io.mosire.simos.social.api.population;

/**
 * 逐家户、逐 {@code (年龄档, 性别)} 的一档出生/死亡率（2026-10-09 家户/人口架构 §4.3）。
 *
 * <p>两个率都不得为负。育龄段的出生率有效，其余年龄段为 0；单位为"每千家户成员每 tick" 的口径由结算方解释， 契约层只负责非负与形状。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param bracketId 年龄档短名；非空白
 * @param sex 性别词表；不得为 null
 * @param birthRatePerMillePerTick 每 tick 每千人出生率；不得为负
 * @param deathRatePerMillePerTick 每 tick 每千人死亡率；不得为负
 */
public record HouseholdVitalRate(
    String bracketId, Sex sex, long birthRatePerMillePerTick, long deathRatePerMillePerTick) {

  public HouseholdVitalRate {
    if (bracketId == null || bracketId.isBlank()) {
      throw new IllegalArgumentException("HouseholdVitalRate.bracketId 不得为空白");
    }
    if (sex == null) {
      throw new IllegalArgumentException("HouseholdVitalRate.sex 不得为 null");
    }
    if (birthRatePerMillePerTick < 0L) {
      throw new IllegalArgumentException(
          "HouseholdVitalRate.birthRatePerMillePerTick 不得为负: " + birthRatePerMillePerTick);
    }
    if (deathRatePerMillePerTick < 0L) {
      throw new IllegalArgumentException(
          "HouseholdVitalRate.deathRatePerMillePerTick 不得为负: " + deathRatePerMillePerTick);
    }
  }
}
