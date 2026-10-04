package io.mosire.simos.economy.api.market;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.InstrumentId;
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
 * receiveWith       用哪种货币工具收款
 * </pre>
 *
 * <p>★★ <b>{@code sellable} 不是"持有量"</b>：它是生成方按 {@code max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)}
 * 算出来的<b>可卖余量</b>（各项互不重复扣除）。 ⇒ 本记录只保证那个数 &gt; 0，不重新解释库存。
 *
 * <p>★ {@code availableFromTick} 与 {@link BuyOrder#latestArrivalTick()} 是一对：本层区内即时，两者都取下单日；L2
 * 跨区运输才拉开。
 *
 * @param supplier 卖方主体；不得为 null
 * @param dispatchFrom 发货格；不得为 null
 * @param commodity 商品；不得为 null
 * @param sellable 可卖数量（毫商品）；必须 &gt; 0（0 不是一条订单）
 * @param minPrice 最低价（毫计价货币 / 商品单位）；必须 ≥ 0（0 = 明确免费交易）
 * @param availableFromTick 可发货的世界日；不得为负
 * @param receiveWith 收款工具；不得为 null
 */
public record SellOrder(
    ActorRef supplier,
    HexCoord dispatchFrom,
    CommodityId commodity,
    long sellable,
    long minPrice,
    long availableFromTick,
    InstrumentId receiveWith) {

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
      throw new IllegalArgumentException("SellOrder.receiveWith 不得为 null（收款工具必须显式）");
    }
  }
}
