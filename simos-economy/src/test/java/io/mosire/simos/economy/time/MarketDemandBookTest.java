package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.market.TradeRoute;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>设计 §8.1 第 1 条：{@link MarketDemandBook} 的单元判据</b>。
 *
 * <ol>
 *   <li>有上一轮买方未成交时，{@code addressable = max(未成交, 基本需求残差)}，<b>不重复相加</b>；
 *   <li>没有报告时回退 {@code max(0, consumerNeed + inputNeed + externalDemand − stock − inTransit)}，
 *       {@code evidence} 含 {@code fundamental/noReport}；
 *   <li>同一输入两次 {@code build} 逐值相同（含 map 迭代序）。
 * </ol>
 */
class MarketDemandBookTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final IndustryId CRAFT = IndustryHexKeys.id("craft", H.q(), H.r());

  private static final HouseholdId HOUSE = HouseholdId.parse("hh-demand");
  private static final ActorRef ACTOR = HouseholdActors.of(HOUSE);

  private static HouseholdEconomy row(
      long population, long laborMilli, Map<CommodityId, Long> needs) {
    return new HouseholdEconomy(
        HOUSE,
        new CohortKey(H, ResidenceKind.RURAL, POOR),
        population,
        laborMilli,
        1000,
        0L,
        needs,
        Map.of(),
        0L);
  }

  private static Industry craftWithFiberInput() {
    return new Industry(
        CRAFT,
        "craft-demand",
        new io.mosire.simos.economy.api.id.RegimeId("handicraft"),
        10L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1000L,
        Map.of(CLOTH, 1L),
        Map.of(AssetKind.WORKSHOP, Map.of(FIBER, 500L)),
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static Market market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    prices.put(FIBER, 20L);
    prices.put(CLOTH, 30L);
    return new Market(new io.mosire.simos.economy.api.id.CurrencyId("silver"), prices);
  }

  private static MarketReport reportWithUnfilled(long quantity) {
    return new MarketReport(
        5L,
        MarketTrigger.PERIODIC,
        false,
        List.of(),
        List.of(
            new MarketReport.Unfilled(
                ACTOR, true, GRAIN, quantity, MarketUnfilledReason.NO_BUDGET, H)),
        List.of(),
        // ★★ P-T4（2026-10-10）：两个运费读数改为**逐币表**（禁跨币种求和，I-C10）⇒ 夹具给空表。
        Map.of(),
        Map.of(),
        0L,
        0,
        0,
        io.mosire.simos.economy.api.market.PriceMode.FIXED,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  @Test
  void unfilledBuyerAndFundamentalResidualUseMaxNotSum() {
    // consumerNeed = 100/天 × 10 天 = 1,000；上一轮未成交 = 1,500。
    Market market = market();
    Map<HexCoord, Market> markets = Map.of(H, market);
    MarketTopology topology = MarketTopology.singleHex(markets);
    Map<HouseholdId, HouseholdEconomy> rows = Map.of(HOUSE, row(10L, 10_000L, Map.of(GRAIN, 100L)));
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(HOUSE, H, Map.of(), Map.of(), Map.of(), Map.of());

    MarketDemandBook.Book book =
        MarketDemandBook.build(
            List.of(reportWithUnfilled(1_500L)),
            topology,
            rows,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            accounts,
            markets,
            5L,
            10L);
    MarketDemandBook.Demand demand = book.byMarketHex().get(H).get(GRAIN);

    assertThat(demand.consumerNeedMilli()).as("基本需求 = 100 × 10").isEqualTo(1_000L);
    assertThat(demand.unfilledBuyerMilli()).as("上一轮买方未成交").isEqualTo(1_500L);
    assertThat(demand.addressableMilli())
        .as("★ max(1,500, 1,000) = 1,500；若把两者相加会得到 2,500")
        .isEqualTo(1_500L);
    assertThat(demand.evidence()).contains("lastReport", "fundamental");

    // 未成交低于基本需求残差时，addressable 取残差（仍然不是相加）。
    MarketDemandBook.Book second =
        MarketDemandBook.build(
            List.of(reportWithUnfilled(100L)),
            topology,
            rows,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            accounts,
            markets,
            5L,
            10L);
    MarketDemandBook.Demand secondDemand = second.byMarketHex().get(H).get(GRAIN);
    assertThat(secondDemand.addressableMilli())
        .as("★ max(100, 1,000) = 1,000；不是 1,100")
        .isEqualTo(1_000L);
  }

  @Test
  void noReportsFallsBackToFundamentalResidualAndNamesNoReport() {
    Market market = market();
    Map<HexCoord, Market> markets = Map.of(H, market);
    MarketTopology topology = MarketTopology.singleHex(markets);
    Map<HouseholdId, HouseholdEconomy> rows = Map.of(HOUSE, row(10L, 10_000L, Map.of(GRAIN, 100L)));

    // 该 unit 的引致需求：FIBER 500 毫/规模 × 1 规模 × ceil(10/10)=1 = 500。
    Industry craft = craftWithFiberInput();
    ProductionUnitId unitId = ProductionUnitId.idOf(CRAFT, ACTOR);
    ProductionProcess unit =
        new ProductionProcess(unitId, CRAFT, ACTOR, "mode:test", 0L, 0L, Map.of());
    AssetShareId shareId =
        OwnershipStake.idOf(
            CRAFT, AssetKind.WORKSHOP, ACTOR, ACTOR, OwnershipStake.RightKind.OWNED, 0L);
    OwnershipStake share =
        new OwnershipStake(
            shareId, CRAFT, AssetKind.WORKSHOP, ACTOR, ACTOR, 1L, OwnershipStake.RightKind.OWNED);

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(HOUSE, H, Map.of(GRAIN, 300L), Map.of(), Map.of(), Map.of());

    // 在途：H2 → H、GRAIN 400 毫、day=7 时尚未到达 ⇒ 参与“无报告回退”的 − inTransit 项。
    ShipmentId shipmentId = ShipmentId.parse("ship-demand");
    TradeRoute route = new TradeRoute(H2, H, 10_000L, 1L, 1L, 0);
    ShipmentBatch batch =
        new ShipmentBatch(
            route,
            GRAIN,
            1L,
            8L,
            400L,
            List.of(new ShipmentAllocation(ACTOR, ACTOR, H, 400L, LossBearer.BUYER)));

    MarketDemandBook.Book book =
        MarketDemandBook.build(
            List.of(),
            topology,
            rows,
            Map.of(unitId, unit),
            Map.of(CRAFT, craft),
            Map.of(shareId, share),
            Map.of(shipmentId, batch),
            accounts,
            markets,
            7L,
            10L);

    MarketDemandBook.Demand grain = book.byMarketHex().get(H).get(GRAIN);
    assertThat(grain.consumerNeedMilli()).isEqualTo(1_000L);
    assertThat(grain.stockMilli()).as("家户库存 GRAIN 300").isEqualTo(300L);
    assertThat(grain.inTransitMilli()).as("到 H 的在途 GRAIN 400").isEqualTo(400L);
    assertThat(grain.addressableMilli())
        .as("★ 无报告回退 = max(0, 1,000 + 0 + 0 − 300 − 400) = 300")
        .isEqualTo(300L);
    assertThat(grain.evidence())
        .as("回退路径必须具名 fundamental/noReport")
        .contains("fundamental", "noReport");

    MarketDemandBook.Demand fiber = book.byMarketHex().get(H).get(FIBER);
    assertThat(fiber.inputNeedMilli()).as("引致需求 = 500 × 1 × 1").isEqualTo(500L);
    assertThat(fiber.addressableMilli()).as("★ 引致需求 − 库存 = 500").isEqualTo(500L);
    assertThat(fiber.evidence()).contains("fundamental", "noReport");
  }

  @Test
  void buildIsDeterministicIncludingMapIterationOrder() {
    Market market = market();
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H, market);
    markets.put(new HexCoord(1, 0), market);
    MarketTopology topology = MarketTopology.singleHex(markets);
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(HOUSE, row(10L, 10_000L, Map.of(GRAIN, 100L, CLOTH, 7L)));

    MarketDemandBook.Book first =
        MarketDemandBook.build(
            java.util.Arrays.asList(reportWithUnfilled(1_500L), null),
            topology,
            rows,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            AccountSession.empty(),
            markets,
            5L,
            10L);
    MarketDemandBook.Book second =
        MarketDemandBook.build(
            java.util.Arrays.asList(reportWithUnfilled(1_500L), null),
            topology,
            rows,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            AccountSession.empty(),
            markets,
            5L,
            10L);

    assertThat(second).as("record 值相等").isEqualTo(first);
    assertThat(new ArrayList<>(second.byMarketHex().keySet()))
        .as("★ 外层 map 迭代序相同")
        .isEqualTo(new ArrayList<>(first.byMarketHex().keySet()));
    for (HexCoord hex : first.byMarketHex().keySet()) {
      assertThat(new ArrayList<>(second.byMarketHex().get(hex).keySet()))
          .as("★ 逐格商品 map 迭代序相同: %s", hex)
          .isEqualTo(new ArrayList<>(first.byMarketHex().get(hex).keySet()));
      for (CommodityId commodity : first.byMarketHex().get(hex).keySet()) {
        assertThat(second.byMarketHex().get(hex).get(commodity).evidence())
            .as("evidence 逐项同序: (%s,%s)", hex, commodity)
            .isEqualTo(first.byMarketHex().get(hex).get(commodity).evidence());
      }
    }
  }
}
