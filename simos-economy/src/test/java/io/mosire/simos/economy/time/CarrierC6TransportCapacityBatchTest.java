package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** D-032 C6：每 tick 运输能力批次、非库存服务通道与合户重键。 */
class CarrierC6TransportCapacityBatchTest {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-c6-carrier");
  private static final HouseholdId OTHER = HouseholdId.parse("hh-c6-other");
  private static final HouseholdId MISSING = HouseholdId.parse("hh-c6-missing");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final io.mosire.simos.economy.api.id.MarketZoneId ZONE =
      new io.mosire.simos.economy.api.id.MarketZoneId("c6-single-zone");

  @Test
  void marketRoundIdIsTheNextAbsoluteMarketDayAndRejectsNegative() {
    assertThat(TransportCapacityBatchBook.marketRoundIdOf(0L)).isEqualTo(0L);
    assertThat(TransportCapacityBatchBook.marketRoundIdOf(1L)).isEqualTo(5L);
    assertThat(TransportCapacityBatchBook.marketRoundIdOf(5L)).isEqualTo(5L);
    assertThat(TransportCapacityBatchBook.marketRoundIdOf(6L)).isEqualTo(10L);
    assertThatThrownBy(() -> TransportCapacityBatchBook.marketRoundIdOf(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");
  }

  @Test
  void batchesAccumulateByKeyAndClearOnlyTheCurrentRound() {
    World world = world();
    long current = TransportCapacityBatchBook.marketRoundIdOf(1L);
    long future = TransportCapacityBatchBook.marketRoundIdOf(6L);

    assertThat(
            TransportCapacityBatchBook.accumulate(
                world.sheet, world.view, CARRIER, H1, current, 300L, current))
        .isEqualTo(1);
    assertThat(
            TransportCapacityBatchBook.accumulate(
                world.sheet, world.view, CARRIER, H1, current, 700L, current))
        .isEqualTo(1);
    TransportCapacityBatchBook.accumulate(
        world.sheet, world.view, CARRIER, H1, future, 55L, future);

    assertThat(world.sheet.transportCapacityBatches())
        .containsEntry(
            new TransportCapacityBatchKey(CARRIER, H1, current),
            new TransportCapacityBatch(ZONE, 1_000L));
    assertThat(TransportCapacityBatchBook.clearCurrentMarketRound(world.sheet, current))
        .isEqualTo(1);
    assertThat(world.sheet.transportCapacityBatches())
        .as("S7-END 只清当前轮；未来轮批次仍保留")
        .containsOnlyKeys(new TransportCapacityBatchKey(CARRIER, H1, future));
  }

  @Test
  void zeroCapacityWritesNothingButWrongHexAndWrongRoundFailClosed() {
    World world = world();
    long current = TransportCapacityBatchBook.marketRoundIdOf(1L);

    assertThat(
            TransportCapacityBatchBook.accumulate(
                world.sheet, world.view, CARRIER, H1, current, 0L, current))
        .isZero();
    assertThat(world.sheet.transportCapacityBatches()).isEmpty();

    assertThatThrownBy(
            () ->
                TransportCapacityBatchBook.accumulate(
                    world.sheet, world.view, CARRIER, H2, current, 10L, current))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transport-capacity-operator-home-hex-mismatch");
    assertThatThrownBy(
            () ->
                TransportCapacityBatchBook.accumulate(
                    world.sheet, world.view, CARRIER, H1, current, 10L, current + 5L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transport-capacity-market-round-mismatch");
    assertThatThrownBy(
            () ->
                TransportCapacityBatchBook.accumulate(
                    world.sheet, world.view, MISSING, H1, current, 10L, current))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transport-capacity-operator-household-missing");
  }

  @Test
  void mergeRebindingAddsSameHomeHexBatchesAndKeepsDifferentHomeHexSeparate() {
    World world = world();
    long round = TransportCapacityBatchBook.marketRoundIdOf(1L);
    world
        .sheet
        .transportCapacityBatches()
        .put(
            new TransportCapacityBatchKey(CARRIER, H1, round),
            new TransportCapacityBatch(ZONE, 100L));
    world
        .sheet
        .transportCapacityBatches()
        .put(
            new TransportCapacityBatchKey(CARRIER, H2, round),
            new TransportCapacityBatch(ZONE, 200L));
    world
        .sheet
        .transportCapacityBatches()
        .put(
            new TransportCapacityBatchKey(OTHER, H1, round), new TransportCapacityBatch(ZONE, 30L));

    int rebound =
        HouseholdMergeRebindBook.rebindHouseholdOwnedPostState(world.sheet, CARRIER, OTHER, H2);

    assertThat(rebound).isEqualTo(2);
    assertThat(world.sheet.transportCapacityBatches())
        .as("同 (round, homeHex) 相加；不同 homeHex 分键保留；source 不丢批次")
        .containsEntry(
            new TransportCapacityBatchKey(OTHER, H1, round), new TransportCapacityBatch(ZONE, 130L))
        .containsEntry(
            new TransportCapacityBatchKey(OTHER, H2, round), new TransportCapacityBatch(ZONE, 200L))
        .hasSize(2);
  }

  @Test
  void caravanHarvestRoutesServiceOnlyToOperatorBatchAndNeverToInventory() {
    World world = world();
    var industry = CaravanTransportTemplate.industry(H1);
    ProductionUnitId unitId = ProductionUnitId.idOf(industry.id(), HouseholdActors.of(CARRIER));
    ProductionProcess unit =
        new ProductionProcess(
            unitId,
            industry.id(),
            ProductionLocation.ofPlan(industry.id()).orElseThrow(),
            HouseholdActors.of(CARRIER),
            DefaultProductionModes.MERCHANT.value(),
            0L,
            Map.of(),
            Map.of());
    ProductionRoster roster =
        new ProductionRoster(
            unitId,
            industry.id(),
            List.of(
                ProductionParticipant.of(
                    CARRIER, CaravanTransportTemplate.ROLES.get(0).id(), true, 0L)));
    SettlementIndex index =
        SettlementIndex.buildWithoutAssetOccupancy(
            Map.of(unitId, unit),
            Map.of(industry.id(), industry),
            Map.of(),
            Map.of(),
            world.rows,
            Map.of(),
            Map.of());
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    goods.put(CARRIER, new LinkedHashMap<>(Map.of(new CommodityId("grain"), 10L)));
    money.put(CARRIER, new LinkedHashMap<>(Map.of(SILVER, 0L)));
    AtomicLong sinkCapacity = new AtomicLong();
    List<HouseholdId> sinkHouseholds = new ArrayList<>();
    List<HexCoord> sinkHexes = new ArrayList<>();

    ProductionRunSettlement.harvest(
        5L,
        unit,
        industry,
        2L,
        Map.of(),
        Map.of(CARRIER, 2L),
        Map.of(),
        Map.of(),
        roster,
        Map.of(),
        new LinkedHashMap<>(world.rows),
        index,
        income,
        goods,
        money,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(HouseholdActors.of(CARRIER), CARRIER),
        new ProductionLedger.Accumulator(5L),
        null,
        (operator, homeHex, roundId, capacity) -> {
          sinkHouseholds.add(operator);
          sinkHexes.add(homeHex);
          sinkCapacity.addAndGet(capacity);
        });

    assertThat(sinkHouseholds).containsExactly(CARRIER);
    assertThat(sinkHexes).containsExactly(H1);
    assertThat(sinkCapacity.get()).isEqualTo(2_000L);
    assertThat(goods.get(CARRIER))
        .as("运输能力不进入 HouseholdInventory（普通库存仍只有原有 grain）")
        .containsOnlyKeys(new CommodityId("grain"))
        .doesNotContainKey(CaravanTransportTemplate.SERVICE_OUTPUT);
    assertThat(income).as("非库存服务产出不进收入/角色分配").isEmpty();
    assertThat(EconomyVocabulary.allCommodityIds())
        .as("内部服务键不在普通商品词表")
        .doesNotContain(CaravanTransportTemplate.SERVICE_OUTPUT_ID);
    assertThat(industry.recipe().durableCommodityCapacityPerUnit())
        .as("transport_horse 只作容量锚，不是消耗性 input")
        .containsKey(CaravanTransportTemplate.TRANSPORT_HORSE);
    assertThat(industry.recipe().inputPerUnit())
        .as("马匹不按趟消耗")
        .doesNotContainKey(CaravanTransportTemplate.TRANSPORT_HORSE);
  }

  private static World world() {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(CARRIER, row(CARRIER, H1));
    rows.put(OTHER, row(OTHER, H2));
    MarketTable table =
        new MarketTable(SILVER, Set.of(H1, H2), Map.of(new CommodityId("grain"), 1L));
    EconomyData base =
        EconomyData.empty().withHouseholdEconomies(rows).withMarketTables(Map.of(ZONE, table));
    EconomySession session =
        new EconomySession(
            base, ProductionData.empty(), new ProductionRuntimeEngine(ProductionData.empty()));
    return new World(session.sheet(), new WorkingDayView(session.sheet()), rows);
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
        10L,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private record World(
      EconomyStateBuilder sheet, WorkingDayView view, Map<HouseholdId, HouseholdEconomy> rows) {}
}
