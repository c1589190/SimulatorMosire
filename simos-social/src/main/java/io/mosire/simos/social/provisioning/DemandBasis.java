package io.mosire.simos.social.provisioning;

/**
 * ★★ <b>一个需求系数的时间口径</b>（{@link DemandPeriod} + 周期天数）：把 {@link DemandCoefficient} 里成对出现的 {@code
 * (period, cycleDays)} 收成一个值对象。
 *
 * <p>它的两个用途：
 *
 * <ul>
 *   <li>{@link DemandCoefficient#basis()} 的返回类型——不变量校验与线格式都不必再关心"这俩字段是否自洽"；
 *   <li>{@link SocialProvisioning#globalDemandBasis(io.mosire.simos.economy.api.id.CommodityId)} /
 *       {@link SocialProvisioning#globalDemandBases()} 的"某商品已知默认口径"视图类型——后续展开
 *       按商品取口径时，不硬编码任何商品名，也不必重新解释 {@link DemandPeriod} 的语义。
 * </ul>
 *
 * <p>★ <b>不变量</b>（构造期判、坏数据当场抛）：{@code period} 不得为 null； {@code PER_CYCLE_DAYS} ⇒ {@code cycleDays
 * >= 1}；{@code PER_CALENDAR_YEAR} ⇒ {@code cycleDays == 0} （历年口径的"多久"由历法时钟给，不用也不许填天数）。
 *
 * <p>★ 本类型零 Jackson 注解：它只活在内存里的不变量/视图，不单独进线格式（线格式仍由 {@code SocialCodec} 按 {@code DemandCoefficient}
 * 的六个组件写）。
 *
 * @param period 时间口径；不得为 null
 * @param cycleDays {@code PER_CYCLE_DAYS} 的天数（≥1）；{@code PER_CALENDAR_YEAR} 时必须为 0
 */
public record DemandBasis(DemandPeriod period, long cycleDays) {

  public DemandBasis {
    if (period == null) {
      throw ProvisioningReject.reject("DemandBasis.period 不得为 null");
    }
    if (period == DemandPeriod.PER_CYCLE_DAYS && cycleDays < 1L) {
      throw ProvisioningReject.reject(
          "DemandBasis.cycleDays 在 PER_CYCLE_DAYS 口径下必须 ≥ 1: " + cycleDays);
    }
    if (period == DemandPeriod.PER_CALENDAR_YEAR && cycleDays != 0L) {
      throw ProvisioningReject.reject(
          "DemandBasis.cycleDays 在 PER_CALENDAR_YEAR 口径下必须为 0（不参与）: " + cycleDays);
    }
  }
}
