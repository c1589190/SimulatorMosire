package io.mosire.simos.economy.api.market;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一轮市场内统一挂单与订单的只读订单簿。
 *
 * <p>构造期复制并冻结输入列表，检查 listing 的 {@code sourceLotId} 与 order 的 {@code orderId} 均不重复，并按稳定键排序：
 *
 * <ul>
 *   <li>listings：{@code sourceLotId.value}；
 *   <li>orders：{@code side.ordinal} → item 粗分类/类型/位置/lotId → order 的 location q/r → {@code
 *       orderId.value}。
 * </ul>
 *
 * <p>空列表合法。订单簿是瞬时只读值，不落盘、不进 ChangeSet/Codec。
 *
 * @param listings 已排序且不可变的挂单列表
 * @param orders 已排序且不可变的订单列表
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "字段已在 compact 构造器/helper 冻结为 unmodifiable；项目同类 record 的既有豁免口径")
public record MarketOrderBook(List<MarketListing> listings, List<MarketOrder> orders) {

  private static final Comparator<MarketListing> LISTING_ORDER =
      Comparator.comparing(listing -> listing.sourceLotId().value());

  private static final Comparator<MarketItemRef> ITEM_ORDER =
      Comparator.comparingInt(MarketOrderBook::itemGroup)
          .thenComparingInt(MarketOrderBook::itemKindOrder)
          .thenComparing(MarketOrderBook::itemTypeKey)
          .thenComparing(MarketOrderBook::itemLocation, MarketOrderBook::compareLocations)
          .thenComparing(MarketOrderBook::itemLotIdKey);

  private static final Comparator<MarketOrder> ORDER_ORDER =
      Comparator.comparingInt((MarketOrder order) -> order.side().ordinal())
          .thenComparing(MarketOrder::item, ITEM_ORDER)
          .thenComparingInt(order -> order.location().q())
          .thenComparingInt(order -> order.location().r())
          .thenComparing(order -> order.orderId().value());

  public MarketOrderBook {
    listings = immutableListings(listings);
    orders = immutableOrders(orders);
  }

  /** 返回追加一张 listing 后的新订单簿；不修改当前实例。 */
  public MarketOrderBook withListing(MarketListing listing) {
    Objects.requireNonNull(listing, "listing");
    List<MarketListing> next = new ArrayList<>(listings);
    next.add(listing);
    return new MarketOrderBook(next, orders);
  }

  /** 返回追加一张订单后的新订单簿；不修改当前实例。 */
  public MarketOrderBook withOrder(MarketOrder order) {
    Objects.requireNonNull(order, "order");
    List<MarketOrder> next = new ArrayList<>(orders);
    next.add(order);
    return new MarketOrderBook(listings, next);
  }

  /** 按 order id 取唯一订单；缺失具名抛。 */
  public MarketOrder requireOrder(MarketOrderId orderId) {
    Objects.requireNonNull(orderId, "orderId");
    for (MarketOrder order : orders) {
      if (order.orderId().equals(orderId)) {
        return order;
      }
    }
    throw new IllegalArgumentException("market-order-not-found: " + orderId.value());
  }

  /** 按 source lot id 取唯一 listing；缺失具名抛。 */
  public MarketListing requireListing(InventoryLotId sourceLotId) {
    Objects.requireNonNull(sourceLotId, "sourceLotId");
    for (MarketListing listing : listings) {
      if (listing.sourceLotId().equals(sourceLotId)) {
        return listing;
      }
    }
    throw new IllegalArgumentException("market-listing-not-found: " + sourceLotId.value());
  }

  private static List<MarketListing> immutableListings(List<MarketListing> source) {
    Objects.requireNonNull(source, "MarketOrderBook.listings（空订单簿用空列表）");
    List<MarketListing> copy = new ArrayList<>();
    Set<InventoryLotId> seen = new HashSet<>();
    for (MarketListing listing : source) {
      Objects.requireNonNull(listing, "MarketOrderBook.listings 不得含 null");
      if (!seen.add(listing.sourceLotId())) {
        throw new IllegalArgumentException(
            "market-listing-duplicate-source-lot: " + listing.sourceLotId().value());
      }
      copy.add(listing);
    }
    copy.sort(LISTING_ORDER);
    return Collections.unmodifiableList(copy);
  }

  private static List<MarketOrder> immutableOrders(List<MarketOrder> source) {
    Objects.requireNonNull(source, "MarketOrderBook.orders（空订单簿用空列表）");
    List<MarketOrder> copy = new ArrayList<>();
    Set<MarketOrderId> seen = new HashSet<>();
    for (MarketOrder order : source) {
      Objects.requireNonNull(order, "MarketOrderBook.orders 不得含 null");
      if (!seen.add(order.orderId())) {
        throw new IllegalArgumentException("market-order-duplicate-id: " + order.orderId().value());
      }
      copy.add(order);
    }
    copy.sort(ORDER_ORDER);
    return Collections.unmodifiableList(copy);
  }

  private static int itemGroup(MarketItemRef item) {
    return switch (item) {
      case MarketItemRef.Commodity ignored -> 0;
      case MarketItemRef.LotType ignored -> 1;
      case MarketItemRef.SpecificLot ignored -> 2;
    };
  }

  private static int itemKindOrder(MarketItemRef item) {
    return switch (item) {
      case MarketItemRef.Commodity ignored -> InventoryLotKind.COMMODITY.ordinal();
      case MarketItemRef.LotType lotType -> lotType.kind().ordinal();
      case MarketItemRef.SpecificLot ignored -> InventoryLotKind.values().length;
    };
  }

  private static String itemTypeKey(MarketItemRef item) {
    return switch (item) {
      case MarketItemRef.Commodity commodity -> commodity.commodityId().value();
      case MarketItemRef.LotType lotType -> lotType.typeId();
      case MarketItemRef.SpecificLot ignored -> "";
    };
  }

  private static Optional<HexCoord> itemLocation(MarketItemRef item) {
    return switch (item) {
      case MarketItemRef.Commodity ignored -> Optional.empty();
      case MarketItemRef.LotType lotType -> lotType.location();
      case MarketItemRef.SpecificLot ignored -> Optional.empty();
    };
  }

  private static String itemLotIdKey(MarketItemRef item) {
    return switch (item) {
      case MarketItemRef.SpecificLot specificLot -> specificLot.lotId().value();
      case MarketItemRef.Commodity ignored -> "";
      case MarketItemRef.LotType ignored -> "";
    };
  }

  private static int compareLocations(Optional<HexCoord> left, Optional<HexCoord> right) {
    if (left.isEmpty() && right.isEmpty()) {
      return 0;
    }
    if (left.isEmpty()) {
      return 1;
    }
    if (right.isEmpty()) {
      return -1;
    }
    return left.get().compareTo(right.get());
  }
}
