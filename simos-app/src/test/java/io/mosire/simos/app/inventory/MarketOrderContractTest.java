package io.mosire.simos.app.inventory;

import static io.mosire.simos.app.inventory.InventoryTestFixtures.actorData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableComponent;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableLand;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.commodity;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.components;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.currency;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.economyData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.household;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.inventory;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.mapWith;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.ownerAttrs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.MarketItemRef;
import io.mosire.simos.economy.api.market.MarketListing;
import io.mosire.simos.economy.api.market.MarketOrder;
import io.mosire.simos.economy.api.market.MarketOrderBook;
import io.mosire.simos.economy.api.market.MarketOrderId;
import io.mosire.simos.economy.api.market.MarketOrderSide;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * D-032 T1（U2a）：统一 {@link MarketOrder} / {@link MarketListing} / {@link MarketOrderBook} 契约。
 *
 * <p>判据来自任务书 §8.1 / §6.1，并按 U2b §6.2 更新 SELL 规则：{@link MarketItemRef.SpecificLot} 的 SELL 必须带
 * sourceLotId 且必须等于 item.lotId；{@link MarketItemRef.Commodity}/{@link MarketItemRef.LotType} 的聚合
 * SELL 允许 sourceLotId 为空；BUY 可空。budget currency 必须等于 payWith；MarketOrderBook 重复 orderId /
 * sourceLotId 具名拒、稳定排序；MarketListingBook 对 COMMODITY 与 LAND 生成正确 item、估值 empty / 有值、稳定排序。
 */
class MarketOrderContractTest {

  private static final HexCoord HEX = InventoryTestFixtures.ORIGIN;
  private static final HouseholdId H1 = household("h1");
  private static final CommodityId GRAIN = commodity("grain");
  private static final CurrencyId SILVER = currency("silver");
  private static final CurrencyId GOLD = currency("gold");
  private static final ActorRef ACTOR = new ActorRef(ActorKind.HOUSEHOLD, "h1");
  private static final InventoryLotId LOT_A = InventoryLotId.parse("land:arable@0_0:0");
  private static final InventoryLotId LOT_B = InventoryLotId.parse("land:arable@0_0:1");

  // ── MarketOrder 构造守卫 ──────────────────────────────────────────────────

  @Test
  void aggregateCommodityAndLotTypeSellMayOmitSourceLotId() {
    MarketOrder commoditySell =
        order(
            "o-sell-commodity",
            MarketOrderSide.SELL,
            new MarketItemRef.Commodity(GRAIN),
            Optional.empty(),
            OptionalLong.of(2_000L),
            Optional.empty(),
            0);
    assertThat(commoditySell.sourceLotId()).as("★ Commodity 聚合 SELL 允许空 sourceLotId").isEmpty();

    MarketOrder lotTypeSell =
        order(
            "o-sell-lot-type",
            MarketOrderSide.SELL,
            new MarketItemRef.LotType(InventoryLotKind.LAND, "arable", Optional.of(HEX)),
            Optional.empty(),
            OptionalLong.of(2_000L),
            Optional.empty(),
            0);
    assertThat(lotTypeSell.sourceLotId()).as("★ LotType 聚合 SELL 允许空 sourceLotId").isEmpty();
  }

  @Test
  void specificLotSellRequiresSourceLotIdAndMustMatchItemLot() {
    assertThatThrownBy(
            () ->
                order(
                    "o-sell-no-lot",
                    MarketOrderSide.SELL,
                    new MarketItemRef.SpecificLot(LOT_A),
                    Optional.empty(),
                    OptionalLong.of(2_000L),
                    Optional.empty(),
                    0))
        .as("★ SpecificLot SELL 缺 sourceLotId 仍拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-order-sell-source-lot-missing");

    assertThatThrownBy(
            () ->
                order(
                    "o-sell-mismatch",
                    MarketOrderSide.SELL,
                    new MarketItemRef.SpecificLot(LOT_A),
                    Optional.of(LOT_B),
                    OptionalLong.of(2_000L),
                    Optional.empty(),
                    0))
        .as("★ SpecificLot SELL 的 sourceLotId 与 item.lotId 不一致仍拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-order-specific-lot-source-mismatch");

    MarketOrder matching =
        order(
            "o-sell-matching",
            MarketOrderSide.SELL,
            new MarketItemRef.SpecificLot(LOT_A),
            Optional.of(LOT_A),
            OptionalLong.of(2_000L),
            Optional.empty(),
            0);
    assertThat(matching.sourceLotId()).contains(LOT_A);
  }

  @Test
  void buyMayOmitSourceLotId() {
    MarketOrder buyCommodity =
        order(
            "o-buy-commodity",
            MarketOrderSide.BUY,
            new MarketItemRef.Commodity(GRAIN),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    MarketOrder buyLotType =
        order(
            "o-buy",
            MarketOrderSide.BUY,
            new MarketItemRef.LotType(InventoryLotKind.COMMODITY, "grain", Optional.of(HEX)),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    MarketOrder buySpecificLot =
        order(
            "o-buy-lot",
            MarketOrderSide.BUY,
            new MarketItemRef.SpecificLot(LOT_A),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            0);

    assertThat(buyCommodity.sourceLotId()).as("BUY Commodity 可空").isEmpty();
    assertThat(buyLotType.sourceLotId()).as("BUY LotType 可空").isEmpty();
    assertThat(buySpecificLot.sourceLotId()).as("BUY SpecificLot 可空").isEmpty();
  }

  @Test
  void budgetCurrencyMustEqualPayWith() {
    assertThatThrownBy(
            () ->
                order(
                    "o-budget-mismatch",
                    MarketOrderSide.BUY,
                    new MarketItemRef.Commodity(GRAIN),
                    Optional.empty(),
                    OptionalLong.of(2_000L),
                    Optional.of(new Budget(10_000L, GOLD)),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payWith")
        .hasMessageContaining("budget.currency()");

    MarketOrder matching =
        order(
            "o-budget-ok",
            MarketOrderSide.BUY,
            new MarketItemRef.Commodity(GRAIN),
            Optional.empty(),
            OptionalLong.of(2_000L),
            Optional.of(new Budget(10_000L, SILVER)),
            0);
    assertThat(matching.budget()).contains(new Budget(10_000L, SILVER));
  }

  @Test
  void quantityLimitAndPriorityGuards() {
    assertThatThrownBy(
            () ->
                new MarketOrder(
                    new MarketOrderId("o-qty"),
                    MarketOrderSide.BUY,
                    ACTOR,
                    HEX,
                    new MarketItemRef.Commodity(GRAIN),
                    Optional.empty(),
                    0L,
                    OptionalLong.empty(),
                    SILVER,
                    Optional.empty(),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantityMilli 必须大于 0");
    assertThatThrownBy(
            () ->
                new MarketOrder(
                    new MarketOrderId("o-limit"),
                    MarketOrderSide.BUY,
                    ACTOR,
                    HEX,
                    new MarketItemRef.Commodity(GRAIN),
                    Optional.empty(),
                    1L,
                    OptionalLong.of(-1L),
                    SILVER,
                    Optional.empty(),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limitUnitValueMilli 不得为负");
    assertThatThrownBy(
            () ->
                new MarketOrder(
                    new MarketOrderId("o-priority"),
                    MarketOrderSide.BUY,
                    ACTOR,
                    HEX,
                    new MarketItemRef.Commodity(GRAIN),
                    Optional.empty(),
                    1L,
                    OptionalLong.empty(),
                    SILVER,
                    Optional.empty(),
                    -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priority 不得为负");
  }

  // ── MarketOrderBook ───────────────────────────────────────────────────────

  @Test
  void duplicateOrderIdAndListingSourceLotAreRejected() {
    MarketOrder first =
        order(
            "dup",
            MarketOrderSide.BUY,
            new MarketItemRef.Commodity(GRAIN),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    MarketOrder second =
        order(
            "dup",
            MarketOrderSide.SELL,
            new MarketItemRef.SpecificLot(LOT_A),
            Optional.of(LOT_A),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    assertThatThrownBy(() -> new MarketOrderBook(List.of(), List.of(first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-order-duplicate-id");

    MarketListing listing =
        listing(LOT_A, new MarketItemRef.SpecificLot(LOT_A), 1L, OptionalLong.empty());
    assertThatThrownBy(() -> new MarketOrderBook(List.of(listing, listing), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-listing-duplicate-source-lot");
  }

  @Test
  void ordersAndListingsUseStableSortKeys() {
    MarketOrder buyCommodity =
        order(
            "o-buy",
            MarketOrderSide.BUY,
            new MarketItemRef.Commodity(GRAIN),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    MarketOrder sellB =
        order(
            "o-sell-b",
            MarketOrderSide.SELL,
            new MarketItemRef.SpecificLot(LOT_A),
            Optional.of(LOT_A),
            OptionalLong.empty(),
            Optional.empty(),
            0);
    MarketOrder sellA =
        order(
            "o-sell-a",
            MarketOrderSide.SELL,
            new MarketItemRef.SpecificLot(LOT_A),
            Optional.of(LOT_A),
            OptionalLong.empty(),
            Optional.empty(),
            0);

    MarketOrderBook book =
        new MarketOrderBook(
            List.of(
                listing(LOT_B, new MarketItemRef.SpecificLot(LOT_B), 2L, OptionalLong.empty()),
                listing(LOT_A, new MarketItemRef.SpecificLot(LOT_A), 1L, OptionalLong.empty())),
            List.of(sellB, buyCommodity, sellA));

    assertThat(book.orders())
        .extracting(order -> order.orderId().value())
        .as("BUY 先于 SELL，同键再按 orderId")
        .containsExactly("o-buy", "o-sell-a", "o-sell-b");
    assertThat(book.listings())
        .extracting(listing -> listing.sourceLotId().value())
        .as("listings 按 sourceLotId 稳定排序")
        .containsExactly(LOT_A.value(), LOT_B.value());
    assertThat(book.requireOrder(new MarketOrderId("o-sell-a"))).isEqualTo(sellA);
  }

  // ── MarketListingBook ─────────────────────────────────────────────────────

  @Test
  void listingBookBuildsCommodityAndLandItemsWithValuation() {
    InventoryLotView view = worldView();
    Function<InventoryLot, OptionalLong> valuation =
        lot ->
            lot.itemKind() == InventoryLotKind.COMMODITY
                ? OptionalLong.of(1_234L)
                : OptionalLong.empty();

    MarketListingBook book = MarketListingBook.from(view, valuation);
    MarketListingBook again = MarketListingBook.from(view, valuation);

    assertThat(book.listings()).as("两次派生逐值相同").isEqualTo(again.listings());
    assertThat(book.listings()).hasSize(2);
    MarketListing commodityListing = book.listings().get(0);
    MarketListing landListing = book.listings().get(1);

    assertThat(commodityListing.sourceLotId().value()).startsWith("commodity:");
    assertThat(commodityListing.item()).isEqualTo(new MarketItemRef.Commodity(GRAIN));
    assertThat(commodityListing.quantityMilli()).isEqualTo(1_000L);
    assertThat(commodityListing.estimatedValueMilli()).hasValue(1_234L);

    assertThat(landListing.sourceLotId().value()).startsWith("land:");
    assertThat(landListing.item())
        .isEqualTo(
            new MarketItemRef.SpecificLot(
                InventoryLotId.forLand(new LandComponentId("arable@0_0:0"))));
    assertThat(landListing.quantityMilli()).isEqualTo(10_000L);
    assertThat(landListing.estimatedValueMilli()).as("缺价 = empty").isEmpty();
  }

  @Test
  void listingBookRejectsNullValuationAndDuplicateSourceLot() {
    InventoryLotView view = worldView();
    assertThatThrownBy(() -> MarketListingBook.from(view, lot -> null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-listing-valuation-null");

    MarketListing listing =
        listing(LOT_A, new MarketItemRef.SpecificLot(LOT_A), 1L, OptionalLong.empty());
    assertThatThrownBy(() -> new MarketListingBook(List.of(listing, listing)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market-listing-duplicate-source-lot");
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────

  private static InventoryLotView worldView() {
    ActorData actor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)));
    EconomyData economy = economyData(H1, HEX);
    GameMap map =
        mapWith(
            HEX,
            arableLand(components(arableComponent("arable@0_0:0", 10_000L, ownerAttrs("h1"))), 1L));
    return InventoryLotViewBuilder.build(actor, economy, map);
  }

  private static MarketListing listing(
      InventoryLotId sourceLotId,
      MarketItemRef item,
      long quantityMilli,
      OptionalLong estimatedValueMilli) {
    return new MarketListing(sourceLotId, item, quantityMilli, estimatedValueMilli);
  }

  private static MarketOrder order(
      String orderId,
      MarketOrderSide side,
      MarketItemRef item,
      Optional<InventoryLotId> sourceLotId,
      OptionalLong limitUnitValueMilli,
      Optional<Budget> budget,
      int priority) {
    return new MarketOrder(
        new MarketOrderId(orderId),
        side,
        ACTOR,
        HEX,
        item,
        sourceLotId,
        10L,
        limitUnitValueMilli,
        SILVER,
        budget,
        priority);
  }
}
