package io.mosire.simos.social.provisioning;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;

/**
 * ★★ <b>人口需求系数</b>（2026-10-09 家户结构修复计划 §3.2）：按 {@code (年龄档, 性别, 商品)}
 * 给出的"每人多少"——<b>不是</b>"每户多少"，也<b>不是</b>"每人每天多少"。
 *
 * <pre>
 * amountMilli  — 最小计量单位（粮 = 毫粮，布 = 毫布）
 * period       — 时间口径；PER_CYCLE_DAYS 时 cycleDays 是周期天数，PER_CALENDAR_YEAR 时 cycleDays 必须为 0
 * </pre>
 *
 * <p>★★ <b>逐日展开的机制由 Social 收口</b>（计划 §3.5 机制第 1~3 条）： "家户总额 = Σ(成员份额 × amountMilli)"，粮按 {@code
 * 累计(day) − 累计(day−1)}（只在家户层取整一次）、 布按 {@code YearFraction.multiplyFloor(总额)}；本类型只负责"这一行的值合法且键唯一"。
 *
 * <p>★ <b>不变量</b>（构造期判、坏数据当场抛）：四个引用字段不得为 null；{@code amountMilli >= 0}； {@link
 * DemandPeriod#PER_CYCLE_DAYS} ⇒ {@code cycleDays >= 1}； {@link DemandPeriod#PER_CALENDAR_YEAR} ⇒
 * {@code cycleDays == 0}。
 *
 * <p>★ {@link #key()} 是<b>表内唯一键</b>（年龄档 × 性别 × 商品），供 {@link SocialProvisioning}
 * 在构造期拒绝重复行；它与线格式无关，也不是商品词表的一部分。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由 {@code SocialCodec} 按 record 组件写出/读回，键的反序列化器也只在 那个 mapper 上注册。
 *
 * @param ageBracket 年龄档；不得为 null
 * @param sex 性别；不得为 null
 * @param commodity 商品 id；不得为 null
 * @param amountMilli 每人每个 {@code period} 的最小计量单位数；不得为负
 * @param period 时间口径；不得为 null
 * @param cycleDays {@code PER_CYCLE_DAYS} 的周期天数（≥1）；{@code PER_CALENDAR_YEAR} 时必须为 0
 */
public record DemandCoefficient(
    AgeBracket ageBracket,
    Sex sex,
    CommodityId commodity,
    long amountMilli,
    DemandPeriod period,
    long cycleDays) {

  public DemandCoefficient {
    if (ageBracket == null) {
      throw ProvisioningReject.reject("DemandCoefficient.ageBracket 不得为 null");
    }
    if (sex == null) {
      throw ProvisioningReject.reject("DemandCoefficient.sex 不得为 null");
    }
    if (commodity == null) {
      throw ProvisioningReject.reject("DemandCoefficient.commodity 不得为 null");
    }
    if (amountMilli < 0L) {
      throw ProvisioningReject.reject("DemandCoefficient.amountMilli 不得为负: " + amountMilli);
    }
    if (period == null) {
      throw ProvisioningReject.reject("DemandCoefficient.period 不得为 null");
    }
    if (period == DemandPeriod.PER_CYCLE_DAYS && cycleDays < 1L) {
      throw ProvisioningReject.reject(
          "DemandCoefficient.cycleDays 在 PER_CYCLE_DAYS 口径下必须 ≥ 1: " + cycleDays);
    }
    if (period == DemandPeriod.PER_CALENDAR_YEAR && cycleDays != 0L) {
      throw ProvisioningReject.reject(
          "DemandCoefficient.cycleDays 在 PER_CALENDAR_YEAR 口径下必须为 0（不参与）: " + cycleDays);
    }
  }

  /** 表内唯一键：{@code (年龄档, 性别, 商品)}；同一张表内不得出现两行同键。 */
  public DemandKey key() {
    return new DemandKey(ageBracket, sex, commodity);
  }

  /** 时间口径值对象（把 {@code period + cycleDays} 收成一处，后续按商品取口径时直接用）。 */
  public DemandBasis basis() {
    return new DemandBasis(period, cycleDays);
  }

  /**
   * 需求系数的唯一键（{@code (年龄档, 性别, 商品)}）。
   *
   * <p>它只在内存里参与"表内是否重复"的判定：{@code record} 的 {@code equals}/{@code hashCode} 正好是 三维键的相等语义，{@code
   * LinkedHashSet} 因此可以在保序的同时当场抓到重复行。
   *
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   */
  public record DemandKey(AgeBracket ageBracket, Sex sex, CommodityId commodity) {

    public DemandKey {
      if (ageBracket == null) {
        throw ProvisioningReject.reject("DemandCoefficient.DemandKey.ageBracket 不得为 null");
      }
      if (sex == null) {
        throw ProvisioningReject.reject("DemandCoefficient.DemandKey.sex 不得为 null");
      }
      if (commodity == null) {
        throw ProvisioningReject.reject("DemandCoefficient.DemandKey.commodity 不得为 null");
      }
    }
  }
}
