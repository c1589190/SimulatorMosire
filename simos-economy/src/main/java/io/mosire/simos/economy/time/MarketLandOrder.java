package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★★ <b>D-032 RA1b2c2：一张标量土地订单（按 hex 的任意数量，无 {@code AssetShareId} 整块）。</b>
 *
 * <p>★★ <b>它取代了什么</b>：旧 P2f 订单定向到具体 {@code AssetShareId} 且数量必须 == 整块。本记录改为 {@code (hex, side,
 * quantityMilliMu, limitPricePerMu, payWith)}：同一 hex 可挂任意正数量、可部分成交、 可与多个对手方逐笔消耗剩余量。它仍由 {@code
 * MarketSettlement.clearOncePerCycle} 的<b>唯一</b>统一撮合入口 {@code MarketMatcher.matchAll}
 * 处理（土地是该入口内部私有分支 {@code MarketMatcher.matchLand}），没有第二个市场阶段、没有第二个 matcher。
 *
 * <pre>
 * side              BUY / SELL
 * actor             买方或卖方主体（现金腿/产权腿要求它能解析到已登记活家户）
 * hex               土地所在格（用于定位市场区与标量持有；同一 hex 内任意数量转移）
 * quantityMilliMu   订单数量（千分亩；必须 &gt; 0；可部分成交）
 * limitPricePerMu   ★ RF1：SELL = 最低愿收（必须 present，0 合法）；BUY = 价格接受者（必须 empty，无上限）
 * payWith           买方支付币 / 卖方收款币（同一笔必须相等：钱腿只铸买方支付币）
 * </pre>
 *
 * <p>★★ <b>D-032 RF1</b>：家户土地 BUY 是<b>数量型价格接受者</b> —— 本形状里没有买价字段，撮合给任何非负地价都接受； 成交现金不足走 {@code
 * MarketTradeExecutor.executeLandTrade} 的既有普通货币债分支。SELL 侧最低保护价不变。
 *
 * <p>★★ <b>不进入 HouseholdGoods</b>：土地是 {@code (hex, quantityMilliMu)} 标量产权，成交只走 {@code
 * LandLedgerState.transfer}/{@code LandBook.apply(LandTransfer)}，不搬任何商品库存、不生成 Pledge、无运费、不征土地税。
 *
 * <p>★★ <b>稳定排序键</b>（{@link #stableKey()}）：内容的纯函数 —— 同一条订单在任意次运行/任意线程数下给出同一个串（禁 HashMap
 * 迭代序/时钟/随机数）。它只用于撮合的稳定破平与日志，不是新的身份类型。
 *
 * @param side 买/卖
 * @param actor 挂单主体
 * @param hex 土地所在格
 * @param quantityMilliMu 订单数量（千分亩；&gt; 0；可部分成交）
 * @param limitPricePerMu SELL 最低价（毫计价货币 / 整亩；present 且 ≥ 0）；BUY 必须 empty
 * @param payWith 支付/收款币
 */
record LandOrder(
    Side side,
    ActorRef actor,
    HexCoord hex,
    long quantityMilliMu,
    OptionalLong limitPricePerMu,
    CurrencyId payWith) {

  /** 订单方向（土地只有买/卖两向；可部分成交）。 */
  enum Side {
    BUY,
    SELL
  }

  LandOrder {
    Objects.requireNonNull(side, "LandOrder.side 不得为 null");
    Objects.requireNonNull(actor, "LandOrder.actor 不得为 null");
    Objects.requireNonNull(hex, "LandOrder.hex 不得为 null");
    Objects.requireNonNull(payWith, "LandOrder.payWith 不得为 null");
    Objects.requireNonNull(
        limitPricePerMu, "LandOrder.limitPricePerMu 不得为 null（买盘请用 OptionalLong.empty()）");
    if (quantityMilliMu <= 0L) {
      throw new IllegalArgumentException("LandOrder.quantityMilliMu 必须 > 0: " + quantityMilliMu);
    }
    if (limitPricePerMu.isPresent() && limitPricePerMu.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "LandOrder.limitPricePerMu 不得为负: " + limitPricePerMu.getAsLong());
    }
    if (side == Side.BUY && limitPricePerMu.isPresent()) {
      throw new IllegalArgumentException(
          "LAND BUY 是价格接受者，不得带限价（数量只由真实缺口决定）: " + limitPricePerMu.getAsLong());
    }
    if (side == Side.SELL && limitPricePerMu.isEmpty()) {
      throw new IllegalArgumentException("LAND SELL 必须带最低保护价（0 = 明确免费交易）");
    }
  }

  /**
   * ★ 稳定性破平/日志用的 canonical 串（内容的纯函数；不进任何判定，只回答"这是同一张订单"）。
   *
   * <p>★ 它刻意包含全部字段：同一 hex、同一主体的两张"同形订单"仍会给出同一个串 —— 那正是需要的语义（重复挂单应当在日志里一眼看出是重复），而不是造一个随时间漂移的订单 id。
   */
  String stableKey() {
    return side.name()
        + "|"
        + hex.q()
        + ","
        + hex.r()
        + "|"
        + actor.kind()
        + ":"
        + actor.id()
        + "|"
        + quantityMilliMu
        + "|"
        + (limitPricePerMu.isPresent() ? limitPricePerMu.getAsLong() : "price-taker")
        + "|"
        + payWith.value();
  }
}
