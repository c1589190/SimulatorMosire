package io.mosire.simos.app.inventory;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.market.MarketItemRef;
import io.mosire.simos.economy.api.market.MarketListing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * S7 市场轮开盘前的只读挂单投影：把 S1 的 {@link InventoryLotView} 转成本轮 {@link MarketListing}。
 *
 * <p>每个正数量 lot 生成一条 listing：COMMODITY 用商品身份，LAND/ASSET 用具体 lot。估值完全由调用方注入的纯函数 派生，缺价为空；本类不修改
 * view、不读时钟、不读随机数，输出按 {@code sourceLotId.value} 稳定排序。
 *
 * @param listings 已排序且不可变的 listing 列表
 */
public record MarketListingBook(List<MarketListing> listings) {

  private static final Comparator<MarketListing> LISTING_ORDER =
      Comparator.comparing(listing -> listing.sourceLotId().value());

  public MarketListingBook {
    Objects.requireNonNull(listings, "MarketListingBook.listings（空挂单簿用空列表）");
    List<MarketListing> copy = new ArrayList<>();
    Set<InventoryLotId> seen = new HashSet<>();
    for (MarketListing listing : listings) {
      Objects.requireNonNull(listing, "MarketListingBook.listings 不得含 null");
      if (!seen.add(listing.sourceLotId())) {
        throw new IllegalArgumentException(
            "market-listing-duplicate-source-lot: " + listing.sourceLotId().value());
      }
      copy.add(listing);
    }
    copy.sort(LISTING_ORDER);
    listings = Collections.unmodifiableList(copy);
  }

  /**
   * 从唯一库存视图纯派生挂单簿。
   *
   * @param view S1 的只读库存 lot 视图；不得为 null
   * @param valuation 按 lot 派生估值的纯函数；不得为 null，返回 null 视为契约故障
   * @return 按 source lot id 稳定排序的只读挂单簿
   */
  public static MarketListingBook from(
      InventoryLotView view, Function<InventoryLot, OptionalLong> valuation) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(valuation, "valuation");
    List<MarketListing> listings = new ArrayList<>();
    for (InventoryLot lot : view.lots()) {
      if (lot.quantityMilli() <= 0L) {
        continue;
      }
      MarketItemRef item =
          switch (lot.itemKind()) {
            case COMMODITY -> new MarketItemRef.Commodity(CommodityId.parse(lot.itemTypeId()));
            case LAND, ASSET -> new MarketItemRef.SpecificLot(lot.lotId());
          };
      OptionalLong estimatedValueMilli = valuation.apply(lot);
      if (estimatedValueMilli == null) {
        throw new IllegalArgumentException("market-listing-valuation-null: " + lot.lotId().value());
      }
      listings.add(new MarketListing(lot.lotId(), item, lot.quantityMilli(), estimatedValueMilli));
    }
    return new MarketListingBook(listings);
  }
}
