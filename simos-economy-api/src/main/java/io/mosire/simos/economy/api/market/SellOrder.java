package io.mosire.simos.economy.api.market;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>卖订单</b>（M2.1）：某个主体想从某一格、从某个世界日起、按一个底价卖出一种商品。
 *
 * <pre>
 * supplier          谁要卖（actor 身份）
 * dispatchFrom      从哪一格发货（账户 = (actor, location) ⇒ 发货格必须显式）
 * commodity         卖什么
 * sellable          可卖多少（毫商品；> 0；★ 已扣过冻结/必要投入/生活保留，见生成方）
 * minPrice          愿意接受的最低价格（毫计价货币 / 商品单位；> 0）
 * availableFromTick 从哪一天起可发（本层区内即时 ⇒ 与下单同一天）
 * receiveWith       收哪种**币种**
 * </pre>
 *
 * <p>★★ <b>{@code sellable} 不是"持有量"</b>：它是生成方按 {@code max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)}
 * 算出来的<b>可卖余量</b>（各项互不重复扣除）。 ⇒ 本记录只保证那个数 &gt; 0，不重新解释库存。
 *
 * <p>★ {@code availableFromTick} 与 {@link BuyOrder#latestArrivalTick()} 是一对：本层区内即时，两者都取下单日；L2
 * 跨区运输才拉开。
 *
 * <p>★★ <b>3c（2026-10-10 订单可选币）：收款币由订单决定，缺省 = 本格计价币</b> ——
 *
 * <ul>
 *   <li><b>它的语义 = "我接受哪种币"（接受集）</b>：成交的必要条件之一是 {@code 买方支付币 ∈ 本集合} （V-1 的候选成立条件；判定唯一拼写点 = {@code
 *       MarketSettlement#acceptsCurrency}）。钱腿本身永远铸买方支付币 ⇒ "卖方收什么"与"买方付什么"必须是同一种钱，这条过滤正是保证它成立的那道闸；
 *   <li><b>缺省语义中性</b>：本批（P-T1e 之前）所有生成点都填<b>本格计价币</b>，且接受集 = 全币 ⇒ 旧世界逐值不变（I-C2）；
 *   <li><b>它不是价格尺度</b>：{@link #minPrice()} 的量纲仍由<b>本格价表</b>（{@code Market.numeraire}）给出 —— 计划 §2.1
 *       冻结"一格一张价表、一个尺度"；卖方对这些币"值多少"的判断走买方支付币 ↔ 本格计价币的估值 （{@code CurrencyValuation}），不因收款币改口径。
 * </ul>
 *
 * @param supplier 卖方主体；不得为 null
 * @param dispatchFrom 发货格；不得为 null
 * @param commodity 商品；不得为 null
 * @param sellable 可卖数量（毫商品）；必须 &gt; 0（0 不是一条订单）
 * @param minPrice 最低价（毫计价货币 / 商品单位）；必须 ≥ 0（0 = 明确免费交易）
 * @param availableFromTick 可发货的世界日；不得为负
 * @param receiveWith 收款币种；不得为 null
 */
public record SellOrder(
    ActorRef supplier,
    HexCoord dispatchFrom,
    CommodityId commodity,
    long sellable,
    long minPrice,
    long availableFromTick,
    CurrencyId receiveWith) {

  public SellOrder {
    if (supplier == null) {
      throw new IllegalArgumentException("SellOrder.supplier 不得为 null（订单必须有卖方）");
    }
    if (dispatchFrom == null) {
      throw new IllegalArgumentException("SellOrder.dispatchFrom 不得为 null（货从一格发出，账户才有键）");
    }
    if (commodity == null) {
      throw new IllegalArgumentException("SellOrder.commodity 不得为 null");
    }
    if (sellable <= 0L) {
      throw new IllegalArgumentException("SellOrder.sellable 必须 > 0（0 不是一条订单）: " + sellable);
    }
    if (minPrice < 0L) {
      throw new IllegalArgumentException(
          "SellOrder.minPrice 不得为负（0 = 明确免费交易；未定价的商品不生成订单）: " + minPrice);
    }
    if (availableFromTick < 0L) {
      throw new IllegalArgumentException(
          "SellOrder.availableFromTick 不得为负（世界日从 0 起，创世没有订单）: " + availableFromTick);
    }
    if (receiveWith == null) {
      throw new IllegalArgumentException("SellOrder.receiveWith 不得为 null（收款币种必须显式）");
    }
  }
}
