package io.mosire.simos.economy.api.market;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 统一市场订单：商品与有状态 lot 共用同一条订单形状。
 *
 * <p>{@code location} 是 BUY 的交割格、SELL 的发货格；土地订单两边都等于该土地所在 hex。{@link MarketItemRef.SpecificLot} 的
 * SELL 必须携带 {@code sourceLotId}（且存在时必须等于 item.lotId）；{@link MarketItemRef.Commodity} / {@link
 * MarketItemRef.LotType} 的聚合 SELL 允许 {@code sourceLotId} 为空（聚合来源由 location/actor 表达）。BUY 可指定具体
 * lot，也可留空让 matcher 按 {@link MarketItemRef.LotType} 选择卖方 lot。
 *
 * <p>订单只活在一个市场轮内，不落盘、不进 ChangeSet/Codec；本类型只做形状与构造期守卫，不含撮合公式。
 *
 * @param orderId 订单稳定身份；不得为 null
 * @param side 买卖方向；不得为 null
 * @param actor 买方/卖方主体；不得为 null
 * @param location BUY 交割格 / SELL 发货格；不得为 null
 * @param item 物品引用；不得为 null
 * @param sourceLotId SpecificLot SELL 必填；Commodity/LotType 聚合 SELL 与 BUY 可空
 * @param quantityMilli 订单数量（最小计量单位）；必须大于 0
 * @param limitUnitValueMilli 单位保护价；present 时必须大于等于 0
 * @param payWith 支付币种；不得为 null
 * @param budget 买方独立预算；空表示由 planner 派生
 * @param priority 订单优先级；不得为负
 */
public record MarketOrder(
    MarketOrderId orderId,
    MarketOrderSide side,
    ActorRef actor,
    HexCoord location,
    MarketItemRef item,
    Optional<InventoryLotId> sourceLotId,
    long quantityMilli,
    OptionalLong limitUnitValueMilli,
    CurrencyId payWith,
    Optional<Budget> budget,
    int priority) {

  public MarketOrder {
    Objects.requireNonNull(orderId, "MarketOrder.orderId");
    Objects.requireNonNull(side, "MarketOrder.side");
    Objects.requireNonNull(actor, "MarketOrder.actor");
    Objects.requireNonNull(location, "MarketOrder.location");
    Objects.requireNonNull(item, "MarketOrder.item");
    Objects.requireNonNull(sourceLotId, "MarketOrder.sourceLotId（无来源用 Optional.empty()）");
    Objects.requireNonNull(
        limitUnitValueMilli, "MarketOrder.limitUnitValueMilli（价格接受者用 OptionalLong.empty()）");
    Objects.requireNonNull(payWith, "MarketOrder.payWith");
    Objects.requireNonNull(budget, "MarketOrder.budget（无预算用 Optional.empty()）");
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException("MarketOrder.quantityMilli 必须大于 0: " + quantityMilli);
    }
    if (limitUnitValueMilli.isPresent() && limitUnitValueMilli.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "MarketOrder.limitUnitValueMilli 不得为负: " + limitUnitValueMilli.getAsLong());
    }
    if (priority < 0) {
      throw new IllegalArgumentException("MarketOrder.priority 不得为负: " + priority);
    }
    if (item instanceof MarketItemRef.SpecificLot specificLot) {
      if (side == MarketOrderSide.SELL && sourceLotId.isEmpty()) {
        throw new IllegalArgumentException(
            "market-order-sell-source-lot-missing: " + orderId.value());
      }
      if (sourceLotId.isPresent() && !sourceLotId.get().equals(specificLot.lotId())) {
        throw new IllegalArgumentException(
            "market-order-specific-lot-source-mismatch: "
                + orderId.value()
                + " sourceLotId="
                + sourceLotId.get().value()
                + " itemLotId="
                + specificLot.lotId().value());
      }
    }
    if (budget.isPresent() && !budget.get().currency().equals(payWith)) {
      throw new IllegalArgumentException(
          "MarketOrder.payWith 必须与 budget.currency() 逐值相同（同一件事不许两处拼写）: "
              + payWith
              + " vs "
              + budget.get().currency());
    }
  }
}
