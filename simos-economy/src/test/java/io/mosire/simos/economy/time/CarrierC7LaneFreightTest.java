package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportHexObservation;
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

/** D-032 C7b2：本地 lane 硬约束、无运力不成债、有实际承运才成普通运费债、0 价合法。 */
class CarrierC7LaneFreightTest {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final HouseholdId SELLER = HouseholdId.parse("hh-c7l-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-c7l-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-c7l-carrier");
  private static final long ROUND = 5L;

  @Test
  void positiveAnchorCreatesOneFreightLegAndOnlyUnpaidPartBecomesCarrierDebt() {
    World world = world(false);
    MarketSettlement.MarketOutcome outcome = world.run(10_000L);

    assertThat(outcome.report().fills()).as("本地 lane 有承诺 ⇒ 成交").hasSize(1);
    assertThat(outcome.report().freightPaidByCurrency().getOrDefault(SILVER, 0L))
        .as("买方无现金 ⇒ 实付运费 0")
        .isZero();
    assertThat(world.debts.values())
        .filteredOn(debt -> debt.creditor().equals(CARRIER))
        .singleElement()
        .satisfies(
            debt -> {
              assertThat(debt.principal()).as("锚价 >0 且买方无现金 ⇒ 运费欠款 >0").isPositive();
              assertThat(debt.unit()).isInstanceOf(DebtUnit.Money.class);
              assertThat(debt.source()).isEqualTo(DebtSource.ORDINARY_LOAN);
            });
  }

  @Test
  void zeroAnchorIsFreeAndMustNotBeForcedToOneByMaxOne() {
    World world = world(false);
    MarketSettlement.MarketOutcome outcome = world.run(0L);

    assertThat(outcome.report().fills()).as("0 价不等于无运力；有本地承诺仍应成交").hasSize(1);
    assertThat(outcome.report().fills().get(0).freightMilli())
        .as("0 锚价必须保持 0 运费，不得被 max(1,…) 强改为 1")
        .isZero();
    assertThat(world.debts.values())
        .filteredOn(debt -> debt.creditor().equals(CARRIER))
        .as("0 价成交不得凭空产生运费债")
        .isEmpty();
  }

  @Test
  void noLocalCapacityMeansNoFillAndNoFreightDebt() {
    World world = world(true);
    MarketSettlement.MarketOutcome outcome = world.run(10L);

    assertThat(outcome.report().fills()).as("只有 H2 批次，H1 发货 lane 无本地运力 ⇒ 不成交").isEmpty();
    assertThat(world.debts.values()).as("无本地运力不成债").isEmpty();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class World {
    final MarketSettlementFixtures.World base;
    final MarketSettlement.MarketRound round;
    final Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    final LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        new LinkedHashMap<>();
    final EconomySession session;

    World(boolean wrongHexBatch) {
      this.base =
          MarketSettlementFixtures.builder()
              .market(H1, 1L)
              .market(H2, 1L)
              .household(SELLER, H1, 0L, Map.of(GRAIN, 100_000L), Map.of(SILVER, 0L), Map.of())
              .household(BUYER, H2, 0L, Map.of(), Map.of(SILVER, 0L), Map.of())
              .household(CARRIER, H1, 0L, Map.of(), Map.of(SILVER, 0L), Map.of())
              .build();
      MarketSettlementFixtures.Round baseRound =
          MarketSettlementFixtures.round(base, base.defaultsOf());
      this.round = baseRound.round().withDeferredPayments(10L, debts);
      round.addInjectedSellOrder(
          new SellOrder(HouseholdActors.of(SELLER), H1, GRAIN, 1_000L, 0L, ROUND, SILVER));
      round.addInjectedBuyOrder(
          new BuyOrder(
              HouseholdActors.of(BUYER),
              H2,
              GRAIN,
              1_000L,
              OptionalLong.empty(),
              ROUND,
              new Budget(100_000L, SILVER),
              SILVER));
      MarketZoneId zone = base.marketIdByHex().get(H1);
      HexCoord carrierHex = wrongHexBatch ? H2 : H1;
      batches.put(
          new TransportCapacityBatchKey(CARRIER, carrierHex, ROUND),
          new TransportCapacityBatch(zone, 1_000_000L));
      EconomyData data =
          EconomyData.empty()
              .withMarketZones(base.marketZones())
              .withMarketTables(base.marketTables());
      this.session =
          new EconomySession(
              data, ProductionData.empty(), new ProductionRuntimeEngine(ProductionData.empty()));
    }

    MarketSettlement.MarketOutcome run(long anchorPrice) {
      TransportRoundPlan raw = plan(0L);
      TransportRoundPlan priced = priced(raw, anchorPrice);
      return MarketRoundRunner.clearOncePerCycle(
          base.marketTables(),
          round,
          MarketTrigger.PERIODIC,
          base.topology(),
          EconomyParallelism.singleThreaded(),
          MerchantCapacityPool.empty(),
          Set.of(),
          base.marketZones(),
          SettlementOrderBook.empty(round.day),
          Map.of(),
          new LandArableSupply(Map.of()),
          matchCtx ->
              matchCtx.installTransportRoundPlan(
                  priced, MerchantCapacityPool.ofTransportPlan(priced, batches)));
    }

    private TransportRoundPlan plan(long anchorPrice) {
      SettlementDayContext ctx =
          new SettlementDayContext(
              session,
              ROUND,
              AccountSession.empty(),
              base.topology(),
              false,
              new ProductionLedger.Accumulator(ROUND),
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
      return TransportDemandBook.planRound(ctx, round, batches);
    }

    private TransportRoundPlan priced(TransportRoundPlan raw, long anchorPrice) {
      Map<HexCoord, TransportHexObservation> observations = new LinkedHashMap<>();
      for (Map.Entry<HexCoord, TransportHexObservation> entry :
          raw.observationsByShippingHex().entrySet()) {
        TransportHexObservation value = entry.getValue();
        observations.put(
            entry.getKey(),
            new TransportHexObservation(
                value.marketZone(),
                value.shippingHex(),
                value.marketRoundId(),
                value.demandWorkMilli(),
                value.offeredWorkMilli(),
                value.committedWorkMilli(),
                value.unservedWorkMilli(),
                value.unsoldWorkMilli(),
                anchorPrice));
      }
      return new TransportRoundPlan(
          raw.marketRoundId(),
          raw.demands(),
          raw.commitments(),
          observations,
          raw.demandSupplyByZone(),
          raw.totalDemandWorkMilli(),
          raw.totalOfferedWorkMilli(),
          raw.totalCommittedWorkMilli());
    }
  }

  private static World world(boolean wrongHexBatch) {
    return new World(wrongHexBatch);
  }
}
