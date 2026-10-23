package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketItemRef;
import io.mosire.simos.economy.api.market.MarketOrder;
import io.mosire.simos.economy.api.market.MarketOrderId;
import io.mosire.simos.economy.api.market.MarketOrderSide;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★★ <b>D-032 U2b：生产侧 {@link MarketIntent} 与统一 {@link MarketOrder} 的唯一桥。</b>
 *
 * <p>目标运行位置：S7 市场轮的 {@code prepareMarketRound} → 注入订单 → {@code MarketMatcher.matchAll} 之前。生产侧
 * {@link MarketIntent}/{@link MarketLandIntentBook} 暂时保留为意图 DTO；本类只负责把它们包装成统一订单，并在提交给既有 typed
 * matcher 前转回 {@link BuyOrder}/{@link SellOrder}/{@link LandOrder}。桥本身不生成价格、不重算预算、不改撮合公式。
 *
 * <p>★ 订单身份是内容的纯函数：同一 intent 在任意次运行/任意线程数下得到同一个 {@link MarketOrderId}；本类不读时钟、不使用 UUID/随机数。{@code
 * budget} 一律为空（外部 {@code cashBudgetMilliOf} 是唯一预算来源）。
 */
final class MarketOrderBridge {

  private MarketOrderBridge() {}

  /** 把生产侧意向包装成统一订单；sourceLotId 一律为空（生产侧意向不携带具体 lot）。 */
  static MarketOrder fromIntent(MarketIntent intent) {
    Objects.requireNonNull(intent, "MarketOrderBridge.fromIntent 的 intent 不得为 null");
    MarketItemRef item =
        switch (intent.kind()) {
          case COMMODITY ->
              new MarketItemRef.Commodity(
                  intent
                      .commodity()
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "COMMODITY MarketIntent 必须带 commodity")));
          case LAND ->
              new MarketItemRef.LotType(
                  InventoryLotKind.LAND,
                  LandTypeId.ARABLE.wireValue(),
                  Optional.of(intent.location()));
        };
    return new MarketOrder(
        orderIdOf(intent),
        sideOf(intent.side()),
        intent.actor(),
        intent.location(),
        item,
        Optional.empty(),
        intent.quantity(),
        intent.limitPrice(),
        intent.payWith(),
        Optional.empty(),
        intent.priority());
  }

  /**
   * 把统一买单转成现有 {@link BuyOrder}：限价恒为 {@link OptionalLong#empty()}（价格接受者语义不变）。
   *
   * <p>★ 2 参形态给只关心订单形状的调用方；生产路径用 3 参形态带入本轮世界日（{@code latestArrivalTick} 的唯一来源）。
   */
  static BuyOrder toBuyOrder(MarketOrder order, long budgetMilli) {
    return toBuyOrder(order, budgetMilli, 0L);
  }

  /** 把统一买单转成现有 {@link BuyOrder}，并显式带入本轮世界日。 */
  static BuyOrder toBuyOrder(MarketOrder order, long budgetMilli, long latestArrivalTick) {
    Objects.requireNonNull(order, "MarketOrderBridge.toBuyOrder 的 order 不得为 null");
    MarketItemRef.Commodity commodity = requireCommodity(order, "BUY");
    return new BuyOrder(
        order.actor(),
        order.location(),
        commodity.commodityId(),
        order.quantityMilli(),
        OptionalLong.empty(),
        latestArrivalTick,
        new Budget(budgetMilli, order.payWith()),
        order.payWith());
  }

  /**
   * 把统一卖单转成现有 {@link SellOrder}：要求 item = {@link MarketItemRef.Commodity} 且 limit 必须 present。
   *
   * <p>★ 2 参形态给只关心订单形状的调用方；生产路径用 3 参形态带入本轮世界日（{@code availableFromTick} 的唯一来源）。
   */
  static SellOrder toSellOrder(MarketOrder order) {
    return toSellOrder(order, 0L);
  }

  /** 把统一卖单转成现有 {@link SellOrder}，并显式带入本轮世界日。 */
  static SellOrder toSellOrder(MarketOrder order, long availableFromTick) {
    Objects.requireNonNull(order, "MarketOrderBridge.toSellOrder 的 order 不得为 null");
    MarketItemRef.Commodity commodity = requireCommodity(order, "SELL");
    if (order.limitUnitValueMilli().isEmpty()) {
      throw new IllegalArgumentException(
          "market-order-sell-limit-missing: " + order.orderId().value());
    }
    return new SellOrder(
        order.actor(),
        order.location(),
        commodity.commodityId(),
        order.quantityMilli(),
        order.limitUnitValueMilli().getAsLong(),
        availableFromTick,
        order.payWith());
  }

  /**
   * 把统一土地订单转成现有 {@link LandOrder}：要求 item = {@link MarketItemRef.LotType}(kind=LAND) 或 {@link
   * MarketItemRef.SpecificLot}；BUY 限价必须 empty、SELL 必须 present（既有规则不变）。
   */
  static LandOrder toLandOrder(MarketOrder order) {
    Objects.requireNonNull(order, "MarketOrderBridge.toLandOrder 的 order 不得为 null");
    requireLandItem(order);
    if (order.side() == MarketOrderSide.BUY) {
      if (order.limitUnitValueMilli().isPresent()) {
        throw new IllegalArgumentException(
            "land-order-buy-must-be-price-taker: " + order.orderId().value());
      }
    } else if (order.limitUnitValueMilli().isEmpty()) {
      throw new IllegalArgumentException(
          "land-order-sell-limit-missing: " + order.orderId().value());
    }
    return new LandOrder(
        order.side() == MarketOrderSide.BUY ? LandOrder.Side.BUY : LandOrder.Side.SELL,
        order.actor(),
        order.location(),
        order.quantityMilli(),
        order.limitUnitValueMilli(),
        order.payWith());
  }

  private static MarketItemRef.Commodity requireCommodity(MarketOrder order, String sideText) {
    if (order.item() instanceof MarketItemRef.Commodity commodity) {
      return commodity;
    }
    throw new IllegalArgumentException(
        "market-order-"
            + sideText.toLowerCase(java.util.Locale.ROOT)
            + "-requires-commodity-item: "
            + order.orderId().value()
            + " item="
            + order.item());
  }

  private static void requireLandItem(MarketOrder order) {
    MarketItemRef item = order.item();
    if (item instanceof MarketItemRef.LotType lotType) {
      if (lotType.kind() != InventoryLotKind.LAND) {
        throw new IllegalArgumentException(
            "market-order-land-item-kind-mismatch: "
                + order.orderId().value()
                + " kind="
                + lotType.kind());
      }
      return;
    }
    if (item instanceof MarketItemRef.SpecificLot) {
      return;
    }
    throw new IllegalArgumentException(
        "market-order-land-requires-lot-type-or-specific-lot: "
            + order.orderId().value()
            + " item="
            + item);
  }

  private static MarketOrderSide sideOf(MarketIntentSide side) {
    return switch (side) {
      case BUY -> MarketOrderSide.BUY;
      case SELL -> MarketOrderSide.SELL;
    };
  }

  /** 订单身份的稳定派生：只看 intent 内容，不读时钟/随机/UUID。 */
  private static MarketOrderId orderIdOf(MarketIntent intent) {
    String itemKey =
        switch (intent.kind()) {
          case COMMODITY -> "commodity:" + intent.commodity().orElseThrow().value();
          case LAND ->
              "lot-type:"
                  + InventoryLotKind.LAND.name()
                  + ":"
                  + LandTypeId.ARABLE.wireValue()
                  + ":"
                  + locationKey(intent.location());
        };
    String canonical =
        String.join(
            "|",
            "m3-intent",
            intent.kind().name(),
            intent.side().name(),
            intent.actor().toString(),
            locationKey(intent.location()),
            itemKey,
            Long.toString(intent.quantity()),
            limitKey(intent.limitPrice()),
            intent.payWith().value(),
            Integer.toString(intent.priority()),
            intent.sourceUnit().map(unit -> unit.value()).orElse("-"));
    return MarketOrderId.parse(canonical);
  }

  private static String locationKey(HexCoord location) {
    return location.q() + "," + location.r();
  }

  private static String limitKey(OptionalLong limit) {
    return limit.isPresent() ? Long.toString(limit.getAsLong()) : "price-taker";
  }
}
