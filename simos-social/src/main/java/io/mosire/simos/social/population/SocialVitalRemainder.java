package io.mosire.simos.social.population;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;

/**
 * 一条<b>每 tick 生死余数</b>（2026-10-09 Social 每 tick 计划 §3.4）：键 = {@code (householdId, lotId, kind)}，值 =
 * 累计分子（{@code 0..999_999}）。
 *
 * <p>★★ <b>为什么需要它</b>：ppm/tick 的率乘上小批次人数后常常不足 1 人，若每 tick 各自整除，分子会被截断吞光。 余数跨 tick 保留，{@code
 * numerator = 旧余数 + 份额 × rate}，{@code 人数 = numerator / 1_000_000}、 {@code 新余数 = numerator %
 * 1_000_000}。
 *
 * <p>★ <b>为什么组件用列表不用复合键 map</b>：map 的键若用 record 会逼 {@code SocialCodec} 再注册一套 key
 * deserializer；列表的键是记录字段，Jackson 的 record 内省直接处理。
 *
 * @param householdId 家户 id；不得为 null
 * @param lotId 批次 id；不得为 null
 * @param kind 生死方向；不得为 null
 * @param numerator 累计分子；∈ [0, {@value #MAX_NUMERATOR}]
 */
public record SocialVitalRemainder(
    HouseholdId householdId, PeopleLotId lotId, VitalKind kind, long numerator) {

  /** 余数分子的上界（进制）：人 = 分子 ÷ 1_000_000。 */
  public static final long MAX_NUMERATOR = 999_999L;

  public SocialVitalRemainder {
    if (householdId == null) {
      throw new IllegalArgumentException("SocialVitalRemainder.householdId 不得为 null");
    }
    if (lotId == null) {
      throw new IllegalArgumentException("SocialVitalRemainder.lotId 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("SocialVitalRemainder.kind 不得为 null");
    }
    if (numerator < 0L || numerator > MAX_NUMERATOR) {
      throw new IllegalArgumentException(
          "SocialVitalRemainder.numerator 必须在 [0, " + MAX_NUMERATOR + "]: " + numerator);
    }
  }
}
