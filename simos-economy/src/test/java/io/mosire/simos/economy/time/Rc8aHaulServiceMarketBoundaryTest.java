package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC8a：运输服务成市判据与交付 fault fail-closed</b>。
 *
 * <p>服务成市 = ①该 hex 有 haul 牌价 ∧ ②该 hex 当前至少一户有可交付 haul 货（现货 − 冻结 &gt; 0）。不满足 ② ⇒
 * 不进入服务商品腿；交付点取不到服务货时必须返回 -1 放弃整笔交易（调用方 fail-closed）。
 */
class Rc8aHaulServiceMarketBoundaryTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;
  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CommodityId HAUL = MarketSettlementFixtures.HAUL;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-rc8a-boundary-carrier");

  @Test
  void haulServiceMarketHexRequiresDeliverableHaulStockAndNotJustAPrice() {
    SettlementDayContext noStock = fixture(0L, 0L);
    assertThat(ShipmentSettlement.haulServiceHexes(noStock))
        .as("★ 只有 haul 牌价、没有可交付服务货 ⇒ 该 hex 不成服务市场")
        .isEmpty();

    SettlementDayContext stock = fixture(1_000L, 0L);
    assertThat(ShipmentSettlement.haulServiceHexes(stock))
        .as("有服务货的 hex 才进入服务成市集合")
        .containsExactly(H1);

    SettlementDayContext fullyFrozen = fixture(1_000L, 1_000L);
    assertThat(ShipmentSettlement.haulServiceHexes(fullyFrozen))
        .as("★ 可交付量 = max(0, 现货 − 冻结)；全冻结 ⇒ 不成市")
        .isEmpty();
  }

  @Test
  void deliveryWithoutHaulStockFailsClosedBeforeMutatingAccounts() {
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, MarketSettlementFixtures.grainAndHaulMarket(10L, 4L))
            .market(H2, MarketSettlementFixtures.grainAndHaulMarket(10L, 4L))
            .carrier(CARRIER, H1, 1_000L, 0L)
            .build();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketModelBook.MatchContext ctx =
        new MarketModelBook.MatchContext(
            round.round(),
            world.marketTables(),
            world.topology(),
            MerchantCapacityPool.empty(),
            MarketRegulation.none(),
            CurrencyValuation.none(),
            SettlementOrderBook.empty(round.round().day()));

    MerchantCapacityPool.CarrierChoice choice =
        new MerchantCapacityPool.CarrierChoice(
            HouseholdActors.of(CARRIER),
            CARRIER,
            H1,
            MerchantPolicy.MerchantTier.PORTER,
            0L,
            false,
            100L,
            100L);
    MerchantCapacityPool.CarrierAllocation allocation =
        new MerchantCapacityPool.CarrierAllocation(List.of(choice), 100L, 0L, true);
    MarketModelBook.RouteContext route =
        new MarketModelBook.RouteContext(
            H1, H2, GRAIN, 10L, 0L, 0L, 1L, 0L, 1_000L, 0, 5L, true, true, 4L);

    Map<CommodityId, Long> goodsBefore =
        new LinkedHashMap<>(round.round().householdGoods.getOrDefault(CARRIER, Map.of()));

    long delivered = MarketShipmentBook.deliverHaulService(ctx, route, allocation);

    assertThat(delivered).as("★ 服务货在交付点缺失 ⇒ 返回 -1，调用方不得发货/铸钱腿").isEqualTo(-1L);
    assertThat(round.round().householdGoods.getOrDefault(CARRIER, Map.of()))
        .as("fail-closed 发生在任何账目写入之前：承运户商品账不变")
        .isEqualTo(goodsBefore);
    assertThat(round.ledger().toLedger().transfers()).as("没有运费/货款腿").isEmpty();
    assertThat(ctx.haulServiceSoldMilli).as("交付量为 0").isZero();
  }

  private static SettlementDayContext fixture(long haulStock, long frozenHaul) {
    HouseholdEconomy row =
        new HouseholdEconomy(
            CARRIER,
            new CohortKey(H1, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            1L,
            1_000L,
            1_000,
            0L,
            Map.of(),
            Map.of(),
            0L);
    EconomyData base =
        EconomyData.empty()
            .withHouseholdEconomies(Map.of(CARRIER, row))
            .withMarketTables(
                EconomyFixtures.hexMarkets(
                    Map.of(
                        H1, MarketSettlementFixtures.grainAndHaulMarket(10L, 4L),
                        H2, MarketSettlementFixtures.grainAndHaulMarket(10L, 4L))));
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        CARRIER,
        H1,
        haulStock == 0L ? Map.of() : Map.of(HAUL, haulStock),
        Map.of(SILVER, 0L),
        frozenHaul == 0L ? Map.of() : Map.of(HAUL, frozenHaul),
        Map.of());
    SettlementDayContext ctx =
        new SettlementDayContext(
            null,
            5L,
            accounts,
            null,
            false,
            null,
            EconomyParallelism.singleThreaded(),
            null,
            Map.of(),
            Set.of(),
            null,
            null,
            null,
            null);
    ctx.dayView = base;
    ctx.householdEconomies = new LinkedHashMap<>(Map.of(CARRIER, row));
    return ctx;
  }
}
