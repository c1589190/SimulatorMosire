package io.mosire.simos.social.population;

import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>Social 全局默认生死率表的权威落点</b>（2026-10-09 Social 每 tick 计划 §2.1 / §2.2）。
 *
 * <p>它是 {@code SocialData} 的组件（第七个），只装<b>全局默认</b>；家户覆盖仍复用
 * {@link io.mosire.simos.social.household.Household#vitalRates()}（空表/缺键 ⇒ 逐键回落本组件）。
 * 唯一查找入口是 {@code SocialData.findVitalRate(householdId, ageBracket, sex)}：家户覆盖优先、本组件兜底；
 * 两边都没有 ⇒ 具名拒（不静默给 0）。
 *
 * <p>★★ <b>默认值</b>（{@link #defaults()}，全仓唯一拼写点；计划 §2.2 的初始策略值）：
 *
 * <pre>
 * 死亡（ppm/tick，男女同值）: 0-14 = 67，15-59 = 33，60+ = 667
 * 出生（ppm/tick）          : 15-59 FEMALE = 667，其余键 = 0
 * </pre>
 *
 * <p>★ 本类型零 Jackson 注解：线格式由 {@code SocialCodec} 负责；内部无 map 键，不需要新增 key deserializer。
 *
 * @param globalDefaults 全局默认率表（逐 {@code (bracketId, sex)} 唯一）；不得为 null
 */
public record SocialVitalRates(HouseholdVitalRates globalDefaults) {

  public SocialVitalRates {
    if (globalDefaults == null) {
      throw new IllegalArgumentException(
          "SocialVitalRates.globalDefaults 不得为 null（旧档缺此组件已作废，不做缺省兜底）");
    }
  }

  /**
   * ★★ <b>计划 §2.2 的默认率表</b>：死亡 67/33/667（男女同值），出生仅 15-59 FEMALE = 667。
   *
   * <p>顺序固定为「未成年 → 成年 → 老年、每档 MALE → FEMALE」，让新档字节可复现；调用即构造一份新的
   * 保序不可变实例，并按 INFO 记一行默认表载入。</p>
   */
  public static SocialVitalRates defaults() {
    List<HouseholdVitalRate> rows =
        List.of(
            new HouseholdVitalRate(AgeBracket.CHILD.key(), Sex.MALE, 0L, 67L),
            new HouseholdVitalRate(AgeBracket.CHILD.key(), Sex.FEMALE, 0L, 67L),
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 33L),
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 667L, 33L),
            new HouseholdVitalRate(AgeBracket.ELDER.key(), Sex.MALE, 0L, 667L),
            new HouseholdVitalRate(AgeBracket.ELDER.key(), Sex.FEMALE, 0L, 667L));
    SocialVitalRates defaults = new SocialVitalRates(new HouseholdVitalRates(rows));
    SocialLog.population()
        .info(
            "event=SOCIAL_VITAL_RATES_DEFAULTS_SEEDED "
                + SocialLog.kv("rows", rows.size(), "unit", "ppmPerTick"));
    return defaults;
  }

  /**
   * 查全局默认率（不回落任何家户覆盖）：本表没有该 {@code (年龄档, 性别)} ⇒ {@link Optional#empty()}。
   *
   * @param bracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   */
  public Optional<HouseholdVitalRate> find(AgeBracket bracket, Sex sex) {
    requireArg(bracket, "bracket");
    requireArg(sex, "sex");
    return globalDefaults.find(bracket.key(), sex);
  }

  /**
   * 查全局默认率；缺键 ⇒ 具名 ERROR 日志 + {@link IllegalArgumentException}（"这一档没有口径"是坏数据，
   * 不静默给 0）。
   *
   * @param bracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @throws IllegalArgumentException 任一参数为 null，或全局默认表缺该键
   */
  public HouseholdVitalRate require(AgeBracket bracket, Sex sex) {
    Optional<HouseholdVitalRate> found = find(bracket, sex);
    if (found.isEmpty()) {
      String message =
          "全局默认率表缺键（家户覆盖也没有）: ageBracket="
              + (bracket == null ? "null" : bracket.key())
              + " sex="
              + sex;
      SocialLog.population().error("event=SOCIAL_VITAL_RATE_REJECTED reason={}", message);
      throw new IllegalArgumentException(message);
    }
    return found.get();
  }

  /** 引用参数的非 null 校验：坏参数不混进键比较。 */
  private static void requireArg(Object value, String field) {
    if (value == null) {
      String message = "SocialVitalRates." + field + " 不得为 null";
      SocialLog.population().error("event=SOCIAL_VITAL_RATE_REJECTED reason={}", message);
      throw new IllegalArgumentException(message);
    }
  }
}
