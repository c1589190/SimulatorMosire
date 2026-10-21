package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportDemandKey;
import io.mosire.simos.economy.model.TransportRoundPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-032 C7b1：运输需求、本地 lane 承诺、区级 D/S 与逐 hex observation。 */
class CarrierC7TransportDemandPlanTest {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final HexCoord H3 = new HexCoord(2, 0);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final HouseholdId SELLER = HouseholdId.parse("hh-c7-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-c7-buyer");
  private static final HouseholdId CARRIER_H1 = HouseholdId.parse("hh-c7-carrier-h1");
  private static final HouseholdId CARRIER_H2 = HouseholdId.parse("hh-c7-carrier-h2");

  @Test
  void sameHexHasNoDemandAndOnlyTheShippingHexBatchCanCommit() {
    World world = world();
    world.sell(H1, 100L);
    world.buy(H2, 100L);
    world.putBatch(CARRIER_H1, H1, 500L);
    world.putBatch(CARRIER_H2, H2, 500L);

    TransportRoundPlan plan = world.plan();
    MarketZoneId zone = world.zoneOf(H1);
    TransportDemandKey key = new TransportDemandKey(zone, H1, H2, GRAIN);

    assertThat(plan.demands()).containsKey(key);
    assertThat(plan.commitments().get(key))
        .as("只有 homeHex == shippingHex(H1) 的批次可参与；H2 的批次不能跨 hex 调拨")
        .isNotEmpty()
        .allSatisfy(commitment -> assertThat(commitment.batchKey().homeHex()).isEqualTo(H1));
    assertThat(plan.observationsByShippingHex().get(H1).demandWorkMilli()).isPositive();
    assertThat(plan.observationsByShippingHex().get(H1).offeredWorkMilli()).isEqualTo(500L);
    assertThat(plan.observationsByShippingHex().get(H1).committedWorkMilli())
        .isEqualTo(plan.demands().get(key).workMilli());
    assertThat(plan.observationsByShippingHex().get(H2).demandWorkMilli()).isZero();
    assertThat(plan.observationsByShippingHex().get(H2).unsoldWorkMilli()).isEqualTo(500L);
    assertThat(plan.demandSupplyByZone().get(zone)[0])
        .as("区级 D = Σ demand.workMilli，不做本地未服务/未售净额抵消")
        .isEqualTo(plan.demands().get(key).workMilli());
    assertThat(plan.demandSupplyByZone().get(zone)[1]).isEqualTo(1_000L);
    assertThat(world.batches).as("planRound 只读批次，不修改 C6 持久表").hasSize(2);
  }

  @Test
  void noLocalCapacityLeavesDemandUnservedAndNeverBorrowsOtherHexCapacity() {
    World world = world();
    world.sell(H1, 100L);
    world.buy(H2, 100L);
    world.putBatch(CARRIER_H2, H2, 500L);

    TransportRoundPlan plan = world.plan();
    MarketZoneId zone = world.zoneOf(H1);
    TransportDemandKey key = new TransportDemandKey(zone, H1, H2, GRAIN);

    assertThat(plan.demands()).containsKey(key);
    assertThat(plan.commitments().get(key)).as("无本地运力 ⇒ 不成交/不成债；不得调用 H2 批次补足").isEmpty();
    assertThat(plan.observationsByShippingHex().get(H1).unservedWorkMilli())
        .isEqualTo(plan.demands().get(key).workMilli());
    assertThat(plan.observationsByShippingHex().get(H2).unsoldWorkMilli()).isEqualTo(500L);
  }

  @Test
  void sameBatchCapacityIsSharedAcrossMultipleLanesWithoutOversell() {
    World world = world();
    world.sell(H1, 100L);
    world.buy(H2, 60L);
    world.buy(H3, 60L);
    world.putBatch(CARRIER_H1, H1, 70L);

    TransportRoundPlan plan = world.plan();
    long committed =
        plan.commitments().values().stream()
            .flatMap(java.util.List::stream)
            .mapToLong(commitment -> commitment.committedWorkMilli())
            .sum();
    assertThat(committed).as("同一批次跨多条 lane 共享容量，总承诺不得超过批次数").isLessThanOrEqualTo(70L);
    assertThat(plan.commitments().values()).as("同一 shippingHex 的两条 lane 都被记录").hasSize(2);
  }

  @Test
  void sameHexOnlyAndLandOnlyProduceNoTransportDemand() {
    World world = world();
    world.sell(H1, 100L);
    world.buy(H1, 100L);
    world.injectLandOnlyOrder(H1);
    assertThat(world.plan().demands()).as("同 hex 商品与 LAND 本身都不产生运输需求").isEmpty();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class World {
    final MarketSettlementFixtures.World base;
    final MarketSettlementFixtures.Round round;
    final EconomySession session;
    final LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        new LinkedHashMap<>();

    World(MarketSettlementFixtures.World base) {
      this.base = base;
      this.round = MarketSettlementFixtures.round(base, base.defaultsOf());
      EconomyData data =
          EconomyData.empty()
              .withMarketZones(base.marketZones())
              .withMarketTables(base.marketTables());
      this.session =
          new EconomySession(
              data, ProductionData.empty(), new ProductionRuntimeEngine(ProductionData.empty()));
    }

    MarketZoneId zoneOf(HexCoord hex) {
      return base.marketIdByHex().get(hex);
    }

    void sell(HexCoord from, long sellable) {
      round
          .round()
          .addInjectedSellOrder(
              new SellOrder(HouseholdActors.of(SELLER), from, GRAIN, sellable, 0L, 5L, SILVER));
    }

    void buy(HexCoord to, long quantity) {
      round
          .round()
          .addInjectedBuyOrder(
              new BuyOrder(
                  HouseholdActors.of(BUYER),
                  to,
                  GRAIN,
                  quantity,
                  OptionalLong.empty(),
                  5L,
                  new Budget(100_000L, SILVER),
                  SILVER));
    }

    void putBatch(HouseholdId carrier, HexCoord homeHex, long capacity) {
      batches.put(
          new TransportCapacityBatchKey(carrier, homeHex, 5L),
          new TransportCapacityBatch(zoneOf(homeHex), capacity));
    }

    void injectLandOnlyOrder(HexCoord hex) {
      round
          .round()
          .addLandOrder(
              new LandOrder(
                  LandOrder.Side.BUY,
                  HouseholdActors.of(BUYER),
                  hex,
                  1_000L,
                  OptionalLong.empty(),
                  SILVER));
    }

    TransportRoundPlan plan() {
      SettlementDayContext ctx =
          new SettlementDayContext(
              session,
              5L,
              AccountSession.empty(),
              base.topology(),
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
      ctx.dayView = new WorkingDayView(session.sheet());
      ctx.marketTrigger = MarketTrigger.PERIODIC;
      ctx.marketRound = round.round();
      return TransportDemandBook.planRound(ctx, round.round(), batches);
    }
  }

  private static World world() {
    return new World(
        MarketSettlementFixtures.builder().market(H1, 1L).market(H2, 1L).market(H3, 1L).build());
  }
}
