package io.mosire.simos.economy.api.market;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>买订单</b>（M2.1）：某个主体想在某一格、在某个时限之前、按一个限价买一种商品。
 *
 * <pre>
 * requester        谁要买（actor 身份）
 * deliverTo        提到哪一格（账户 = (actor, location) ⇒ 交割地点必须显式）
 * commodity        买什么
 * quantity         要买多少（毫商品；> 0）
 * maxLandedPrice   愿意接受的最高价格（毫计价货币 / 商品单位；≥ 0，0 = 明确免费交易）
 * latestArrivalTick 最迟到货的世界日（本层无运输 ⇒ 与下单同一天）
 * budget           独立预算（见 {@link Budget}）；不得为 null
 * payWith          用哪种**币种**支付；必须与 {@code budget.currency()} 逐值相同
 * </pre>
 *
 * <p>★★ <b>为什么这几个字段都在</b>：{@code requester} 与 {@code deliverTo} 缺一不可 —— 身份决定"这是谁的订单"，地点决定"货送到哪本账"
 * （同一 actor 在两地的账是两本）；{@code latestArrivalTick} 是"到货时限"这一维的落点，本层区内即时 ⇒ 取 {@link
 * #latestArrivalTick()} = 下单日， L2 的跨区运输才会真的产生大于下单日的值。
 *
 * <p>★ <b>预算与数量分开</b>：见 {@link Budget} 的类注 —— 本类型的构造期只校验两者各自合法、以及支付币一致，<b>不</b>把数量折成金额
 * （那是撮合的实现口径，不是订单形状）。
 *
 * <p>★★ <b>3c（2026-10-10 订单可选币）：支付币由订单决定，缺省 = 本格计价币</b> ——
 *
 * <ul>
 *   <li><b>它是"收/付哪种钱"的唯一真值</b>：撮合槽位、预算、冻结、限额折算、钱腿铸币都读它，格子的 {@code Market.numeraire}
 *       只在<b>订单生成</b>时充当缺省值（计划 §2.1 冻结：订单不带币 ⇒ 本格计价币 ⇒ 旧世界逐值不变，I-C2）；
 *   <li><b>候选成立条件（V-1）</b>：{@code payWith ∈ 口岸币种规则允许的币 ∩ 买方持有（可花）币集合}；空集 ⇒ 候选不成立 + 具名归因 （口岸那一侧见
 *       {@code MarketSettlement#acceptsCurrency}，P-T1e：规则键 =（币种, 挂单类型, 方向），两侧都要过； 持有那一侧见 {@code
 *       MarketUnfilledReason#NO_BUDGET}）；
 *   <li><b>价格尺度不动</b>：{@link #maxLandedPrice()} 的量纲仍是<b>本格价表的计价币</b>（一格一张价表、一个尺度），
 *       订单选币改的是"付哪种钱"，不是"价格写在哪张表上"。
 * </ul>
 *
 * @param requester 买方主体；不得为 null
 * @param deliverTo 交割格；不得为 null
 * @param commodity 商品；不得为 null
 * @param quantity 数量（毫商品）；必须 &gt; 0（0 不是一条订单）
 * @param maxLandedPrice 最高价（毫计价货币 / 商品单位）；必须 ≥ 0（0 = 明确免费交易，货款腿为 0、运费另计）
 * @param latestArrivalTick 最迟到货世界日；不得为负
 * @param budget 独立预算；不得为 null，且其币种必须与 {@code payWith} 逐值相同
 * @param payWith 支付币种；不得为 null，且必须与 {@code budget.currency()} 逐值相同
 */
public record BuyOrder(
    ActorRef requester,
    HexCoord deliverTo,
    CommodityId commodity,
    long quantity,
    long maxLandedPrice,
    long latestArrivalTick,
    Budget budget,
    CurrencyId payWith) {

  public BuyOrder {
    if (requester == null) {
      throw new IllegalArgumentException("BuyOrder.requester 不得为 null（订单必须有买方）");
    }
    if (deliverTo == null) {
      throw new IllegalArgumentException("BuyOrder.deliverTo 不得为 null（货要送到一格，账户才有键）");
    }
    if (commodity == null) {
      throw new IllegalArgumentException("BuyOrder.commodity 不得为 null");
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("BuyOrder.quantity 必须 > 0（0 不是一条订单）: " + quantity);
    }
    if (maxLandedPrice < 0L) {
      throw new IllegalArgumentException(
          "BuyOrder.maxLandedPrice 不得为负（0 = 明确免费交易；未定价的商品不生成订单）: " + maxLandedPrice);
    }
    if (latestArrivalTick < 0L) {
      throw new IllegalArgumentException(
          "BuyOrder.latestArrivalTick 不得为负（世界日从 0 起，创世没有订单）: " + latestArrivalTick);
    }
    if (budget == null) {
      throw new IllegalArgumentException("BuyOrder.budget 不得为 null（预算必须独立算，不许拿数量顶替）");
    }
    if (payWith == null) {
      throw new IllegalArgumentException("BuyOrder.payWith 不得为 null");
    }
    if (!budget.currency().equals(payWith)) {
      throw new IllegalArgumentException(
          "BuyOrder.payWith 必须与 budget.currency() 逐值相同（同一件事不许两处拼写）："
              + payWith
              + " vs "
              + budget.currency());
    }
  }
}
