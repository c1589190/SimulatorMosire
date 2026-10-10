package io.mosire.simos.economy.model;

import java.util.Objects;

/**
 * ★★ <b>W1（2026-10-10）：一个（市场，商品）在**一轮市场轮**里的供需读数</b>（市场轮统计簿的一行）。
 *
 * <pre>
 * demandMilli        本轮"想要"的总量（毫商品）—— 现有口径：买订单量按"按当前价付得起的量"折回、并剔掉被运力截断的那一份
 *                    （{@code MarketSettlement.adaptPrices} 的累加器；★ W2 会把 D 改成"全算"）
 * supplyMilli        本轮"能上架"的总量（毫商品）—— 只含真能卖的（剔掉被运力截断的部分）
 * tradedMilli        本轮**成交**总量（毫商品；按**卖方所在市场**归属）
 * averagePriceMilli  成交加权均价（毫计价货币 / 商品单位；向下取整；0 成交 ⇒ 0）
 * unmetMilli         本轮**未满足**的买方量（毫商品；按**买方所在市场**归属：未成交原因表里买方侧的合计）
 * </pre>
 *
 * <p>★★ <b>它们从哪来（不是新算一套）</b>：D/S 是自适应定价**已经在算**的两个累加器（改动前只用于改价、用完即弃）； 成交量/均价取自 {@code
 * MarketReport.fills}；未满足量取自 {@code MarketReport.unfilled} 的买方侧。W1 只做"**让它可读、可落盘**"。
 *
 * <p>★ <b>量纲统一</b>：三个量都是毫商品（与全仓"数量一律毫单位"同一条口径）；均价是毫计价货币 / 商品单位（与 {@code MarketTable.prices} 同一量纲）。★
 * 逐值 ≥ 0，非负由构造期守卫判死。
 */
public record CommodityRound(
    long demandMilli, long supplyMilli, long tradedMilli, long averagePriceMilli, long unmetMilli) {

  public CommodityRound {
    if (demandMilli < 0L
        || supplyMilli < 0L
        || tradedMilli < 0L
        || averagePriceMilli < 0L
        || unmetMilli < 0L) {
      throw new IllegalArgumentException(
          "供需读数不得为负: D="
              + demandMilli
              + " S="
              + supplyMilli
              + " 成交="
              + tradedMilli
              + " 均价="
              + averagePriceMilli
              + " 未满足="
              + unmetMilli);
    }
  }

  /** ★ 一行全 0（该商品本轮一个订单都没有：合法状态，不是缺数据）。 */
  public static final CommodityRound ZERO = new CommodityRound(0L, 0L, 0L, 0L, 0L);

  /** ★ 两轮相加（周期累计用）：量相加；均价按**成交量加权**（0 成交 ⇒ 0，不做除零）。 */
  public CommodityRound plus(CommodityRound other) {
    Objects.requireNonNull(other, "other");
    long traded = Math.addExact(tradedMilli, other.tradedMilli);
    long average;
    if (traded == 0L) {
      average = 0L;
    } else {
      // ★ 加权均价用 BigInteger 中间量：毫量 × 毫价在真档量级上会越过 long（与 adaptiveNextPrice 同一条纪律）。
      java.math.BigInteger weighted =
          java.math.BigInteger.valueOf(tradedMilli)
              .multiply(java.math.BigInteger.valueOf(averagePriceMilli))
              .add(
                  java.math.BigInteger.valueOf(other.tradedMilli)
                      .multiply(java.math.BigInteger.valueOf(other.averagePriceMilli)));
      average = weighted.divide(java.math.BigInteger.valueOf(traded)).longValueExact();
    }
    return new CommodityRound(
        Math.addExact(demandMilli, other.demandMilli),
        Math.addExact(supplyMilli, other.supplyMilli),
        traded,
        average,
        Math.addExact(unmetMilli, other.unmetMilli));
  }

  /** 一行人类可读摘要（日志/探针用；§一.9 的日志纪律：只输出具名计数）。 */
  public String describe() {
    return "D="
        + demandMilli
        + " S="
        + supplyMilli
        + " 成交="
        + tradedMilli
        + " 均价="
        + averagePriceMilli
        + " 未满足="
        + unmetMilli;
  }
}
