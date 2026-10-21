package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC8c：每户每 tick 唯一 DailyDemandBill，S7 用 S4 后账单+当前库存</b>。
 *
 * <p>锚点：同户同 day 返回同一对象；同 (commodity, source) 不重复行；BASE/EXTRA 按补货窗口 ×5、PRODUCTION_INPUT 不乘 5 且先扣 S4
 * 已提供量；卖侧 actualSurplus = 当前库存 − 同一账单保留项。
 */
class Rc8cDailyDemandBillTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-rc8c-demand");
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final long RESTOCK_DAYS = MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;

  @Test
  void oneBillPerHouseholdPerDayAndOneLinePerCommoditySource() {
    SettlementDayContext ctx = context(0L);

    DailyDemandBill first = ctx.dailyDemandBill(HOUSEHOLD);
    DailyDemandBill again = ctx.dailyDemandBill(HOUSEHOLD);

    assertThat(again).as("同户同 tick 唯一账单 ⇒ 返回同一对象").isSameAs(first);
    assertThat(ctx.dailyDemandBills).containsOnlyKeys(HOUSEHOLD);
    first.addQuantity(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE, 100L);
    first.addQuantity(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE, 50L);
    assertThat(first.lines()).as("同 (commodity, source) 合并为一行").hasSize(1);
    assertThat(first.line(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE).quantityMilli())
        .isEqualTo(150L);
  }

  @Test
  void fiveDayMultiplierAppliesToBaseAndExtraButNotProductionInputProvided() {
    DailyDemandBill bill = DailyDemandBill.create(5L, HOUSEHOLD);
    bill.addQuantity(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE, 100L);
    bill.addQuantity(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_DAILY_EXTRA, 200L);
    bill.addQuantity(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_INPUT, 300L);
    bill.addProvidedFromStock(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_INPUT, 100L);

    assertThat(bill.marketGapFor(GRAIN, RESTOCK_DAYS, 0L))
        .as("BASE 100×5 + EXTRA 200×5 + INPUT max(0,300−100) = 1,700")
        .isEqualTo(1_700L);
    assertThat(bill.marketReserveFor(GRAIN, RESTOCK_DAYS)).as("卖侧保留项与买侧同一市场口径").isEqualTo(1_700L);

    bill.applyMarketPurchaseNeed(GRAIN, 1_700L, RESTOCK_DAYS);
    assertThat(
            bill.line(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE)
                .marketPurchaseNeedMilli())
        .isEqualTo(500L);
    assertThat(
            bill.line(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_DAILY_EXTRA)
                .marketPurchaseNeedMilli())
        .isEqualTo(1_000L);
    assertThat(
            bill.line(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_INPUT)
                .marketPurchaseNeedMilli())
        .as("S4 已提供的 100 不得再次挂买；INPUT 不乘 5")
        .isEqualTo(200L);
    assertThat(bill.lines().stream().mapToLong(DailyDemandBill.Line::marketPurchaseNeedMilli).sum())
        .isEqualTo(1_700L);
  }

  @Test
  void marketIntentsUseTheBillAndPostS4StockInsteadOfExpectedIntent() {
    SettlementDayContext shortStock = context(200L);
    DailyDemandBill shortBill = shortStock.dailyDemandBill(HOUSEHOLD);
    shortBill.addQuantity(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE, 100L);

    MarketIntent buy =
        MarketIntentBook.dailyDemandBillMarketIntents(shortStock).stream()
            .filter(intent -> intent.kind() == MarketIntentKind.COMMODITY)
            .filter(intent -> intent.side() == MarketIntentSide.BUY)
            .filter(intent -> intent.commodity().orElseThrow().equals(GRAIN))
            .findFirst()
            .orElseThrow();

    assertThat(buy.quantity()).as("BUY = max(0, 100×5 − 当前库存 200) = 300").isEqualTo(300L);
    assertThat(buy.limitPrice()).as("价格接受者买盘不得带限价").isEmpty();

    SettlementDayContext surplusStock = context(2_000L);
    DailyDemandBill surplusBill = surplusStock.dailyDemandBill(HOUSEHOLD);
    surplusBill.addQuantity(GRAIN, DailyDemandBill.DemandSource.BASE_SUBSISTENCE, 100L);

    List<MarketIntent> intents = MarketIntentBook.dailyDemandBillMarketIntents(surplusStock);
    assertThat(intents)
        .as("库存覆盖账单保留项 ⇒ 不挂买盘")
        .noneMatch(
            intent ->
                intent.kind() == MarketIntentKind.COMMODITY
                    && intent.side() == MarketIntentSide.BUY
                    && intent.commodity().orElseThrow().equals(GRAIN));
    MarketIntent sell =
        intents.stream()
            .filter(intent -> intent.kind() == MarketIntentKind.COMMODITY)
            .filter(intent -> intent.side() == MarketIntentSide.SELL)
            .filter(intent -> intent.commodity().orElseThrow().equals(GRAIN))
            .findFirst()
            .orElseThrow();
    assertThat(sell.quantity())
        .as("actualSurplus = 当前库存 2,000 − 账单保留 500 = 1,500（不卖未来产出）")
        .isEqualTo(1_500L)
        .isLessThanOrEqualTo(2_000L);
  }

  @Test
  void productionInputProvidedIsNotBoughtAgainByMarketIntents() {
    SettlementDayContext ctx = context(0L);
    DailyDemandBill bill = ctx.dailyDemandBill(HOUSEHOLD);
    bill.addQuantity(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_INPUT, 300L);
    bill.addProvidedFromStock(GRAIN, DailyDemandBill.DemandSource.PRODUCTION_INPUT, 100L);

    MarketIntent buy =
        MarketIntentBook.dailyDemandBillMarketIntents(ctx).stream()
            .filter(intent -> intent.kind() == MarketIntentKind.COMMODITY)
            .filter(intent -> intent.side() == MarketIntentSide.BUY)
            .findFirst()
            .orElseThrow();

    assertThat(buy.quantity())
        .as("INPUT 缺口 = 300 − S4 provided 100 = 200（若乘 5 或重复买则为 1,500）")
        .isEqualTo(200L);
  }

  private static SettlementDayContext context(long grainStock) {
    HouseholdEconomy row =
        new HouseholdEconomy(
            HOUSEHOLD,
            new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            1L,
            1_000L,
            1_000,
            0L,
            Map.of(GRAIN, 100L),
            Map.of(),
            0L);
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(Map.of(HOUSEHOLD, row))
            .withMarketTables(
                EconomyFixtures.hexMarkets(
                    Map.of(HEX, EconomyFixtures.quote(SILVER, Map.of(GRAIN, 10L)))));
    EconomySession session =
        new EconomySession(
            base,
            ProductionData.empty(),
            new ProductionRuntimeEngine(ProductionData.empty()),
            new UnavailableAssetOccupancyLookup(),
            new UnavailableParticipantAttributeLookup(),
            null,
            HouseholdCultureKeyLookup.unavailable());
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        HOUSEHOLD,
        HEX,
        grainStock == 0L ? Map.of() : Map.of(GRAIN, grainStock),
        Map.of(SILVER, 10_000L),
        Map.of(),
        Map.of());
    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            5L,
            accounts,
            null,
            false,
            new ProductionLedger.Accumulator(5L),
            EconomyParallelism.singleThreaded(),
            null,
            Map.of(),
            Set.of(),
            null,
            null,
            null,
            null);
    ctx.base = base;
    ctx.dayView = new WorkingDayView(session.sheet());
    ctx.householdEconomies = session.sheet().householdEconomies();
    ctx.householdGoods = accounts.householdGoods();
    ctx.householdMoney = accounts.householdMoney();
    ctx.markets = new LinkedHashMap<>(base.marketTables());
    return ctx;
  }
}
