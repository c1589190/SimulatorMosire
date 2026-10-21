package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-032 C8：LAND + transport_horse 混合向上跃迁与最大余数拆分。 */
class CarrierC8UpwardMobilityTest {

  private static final HexCoord HOME = new HexCoord(0, 0);
  private static final HexCoord LAND_HEX = new HexCoord(1, 0);
  private static final HexCoord OWNER_SEED_HEX = new HexCoord(9, 9);
  private static final HouseholdId BUYER = HouseholdId.parse("hh-c8-buyer");
  private static final HouseholdId LAND_TARGET = HouseholdId.parse("hh-c8-land-target");
  private static final HouseholdId HORSE_TARGET = HouseholdId.parse("hh-c8-horse-target");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final IndustryId FARM = IndustryHexKeys.id("c8-grain-farm", 0, 0);
  private static final HouseholdCultureKey CULTURE =
      new HouseholdCultureKey("latium", "roman", List.of(), "c8");
  private static final HouseholdCultureKeyLookup CULTURE_LOOKUP = h -> Optional.of(CULTURE);

  @Test
  void mixedLandAndHorseAcquisitionSplitsMigrantsAndMovesAssetsToTheirOwnTargets() {
    World world = world(1_000L, 10_000L);
    PostLandTradeClassCheck.UpwardMobilityResult result = world.settle();

    assertThat(result.landResult().landTransferred()).isEqualTo(10_000L);
    assertThat(result.horseTransferredMilli()).isEqualTo(1_000L);
    assertThat(result.totalMigrants()).as("LAND 吸收 10、马匹吸收 1；总迁移受速度帽 10").isEqualTo(10L);
    assertThat(result.landResult().migrants()).isEqualTo(9L);
    assertThat(result.horseMigrants()).isEqualTo(1L);
    assertThat(world.landOf(LAND_TARGET, LAND_HEX)).isEqualTo(10_000L);
    assertThat(world.goodsOf(HORSE_TARGET).get(CaravanTransportTemplate.TRANSPORT_HORSE))
        .as("马匹全量转入 buyer 市场前 homeHex 的 OWNER 目标")
        .isEqualTo(1_000L);
    assertThat(world.goodsOf(BUYER).getOrDefault(CaravanTransportTemplate.TRANSPORT_HORSE, 0L))
        .as("buyer 不残留 transport_horse")
        .isZero();
    assertThat(world.landOf(BUYER, LAND_HEX)).isZero();
    assertThat(java.util.Arrays.stream(AssetKind.values()).map(Enum::name))
        .as("马匹不得新增 AssetKind.HORSE")
        .doesNotContain("HORSE");
    assertThat(world.ctx.session.sheet().assetShares().values())
        .as("马匹不得建 OwnershipStake")
        .noneMatch(share -> share.asset() == AssetKind.LAND);
  }

  @Test
  void zeroHorseShareStillTransfersAllHorsesWithoutMigratingExtraPeople() {
    World world = world(999L, 10_000L);
    PostLandTradeClassCheck.UpwardMobilityResult result = world.settle();

    assertThat(result.horseMigrants()).as("马匹侧人口份额为 0").isZero();
    assertThat(result.landResult().migrants()).as("总迁移帽 10 全给 LAND").isEqualTo(10L);
    assertThat(result.horseMoves())
        .singleElement()
        .satisfies(
            move -> {
              assertThat(move.acquiredHorseMilli()).isEqualTo(999L);
              assertThat(move.horseTransferredMilli()).isEqualTo(999L);
            });
    assertThat(world.goodsOf(HORSE_TARGET).get(CaravanTransportTemplate.TRANSPORT_HORSE))
        .as("即使人口份额 0，马匹仍全量转目标 OWNER")
        .isEqualTo(999L);
  }

  @Test
  void missingHorseInventoryFailsClosedByName() {
    World world = world(0L, 10_000L);
    assertThatThrownBy(world::settle)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("post-upward-horse-insufficient-source-inventory");
  }

  private static final class World {
    final SettlementDayContext ctx;
    final MarketSettlement.MarketRound marketRound;
    final AccountSession accounts;

    World(SettlementDayContext ctx, MarketSettlement.MarketRound round, AccountSession accounts) {
      this.ctx = ctx;
      this.marketRound = round;
      this.accounts = accounts;
      ctx.marketRound = round;
    }

    PostLandTradeClassCheck.UpwardMobilityResult settle() {
      return PostLandTradeClassCheck.settle(ctx, ctx.day, report());
    }

    Map<CommodityId, Long> goodsOf(HouseholdId household) {
      return accounts.householdGoods().getOrDefault(household, Map.of());
    }

    long landOf(HouseholdId household, HexCoord hex) {
      LandHolding holding =
          ctx.session.sheet().landHoldings().get(new LandHoldingKey(hex, household));
      return holding == null ? 0L : holding.quantityMilliMu();
    }

    private MarketReport report() {
      MarketReport.Fill fill =
          new MarketReport.Fill(
              LAND_HEX,
              HOME,
              CaravanTransportTemplate.TRANSPORT_HORSE,
              HouseholdActors.of(HORSE_TARGET),
              HouseholdActors.of(BUYER),
              Math.max(
                  1L, goodsOf(BUYER).getOrDefault(CaravanTransportTemplate.TRANSPORT_HORSE, 0L)),
              SILVER,
              0L,
              SILVER,
              0L,
              0L,
              0L,
              5L,
              true,
              "shipment-c8",
              0L);
      return new MarketReport(
          5L,
          MarketTrigger.PERIODIC,
          true,
          List.of(fill),
          List.of(),
          List.of(),
          Map.of(),
          Map.of(),
          0L,
          1L,
          0L,
          MarketSettlement.priceMode(),
          List.of(),
          List.of(),
          List.of());
    }
  }

  private static World world(long buyerHorseMilli, long acquiredLandMilli) {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(BUYER, row(BUYER, HOME, 100L));
    rows.put(LAND_TARGET, row(LAND_TARGET, LAND_HEX, 50L));
    rows.put(HORSE_TARGET, row(HORSE_TARGET, HOME, 40L));

    Map<LandHoldingKey, LandHolding> land = new LinkedHashMap<>();
    land.put(
        new LandHoldingKey(LAND_HEX, BUYER), new LandHolding(BUYER, LAND_HEX, acquiredLandMilli));
    land.put(
        new LandHoldingKey(OWNER_SEED_HEX, LAND_TARGET),
        new LandHolding(LAND_TARGET, OWNER_SEED_HEX, 1L));
    land.put(
        new LandHoldingKey(OWNER_SEED_HEX, HORSE_TARGET),
        new LandHolding(HORSE_TARGET, OWNER_SEED_HEX, 1L));

    IndustryDefinitionView industry =
        IndustryViews.industry(
            FARM,
            "C8 grain farm",
            new RegimeId("c8"),
            10L,
            Map.of(AssetKind.LAND, 1L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 4L),
            Map.of(AssetKind.LAND, Map.of(GRAIN, 389L)),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0));
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(rows);
    EconomySession session =
        new EconomySession(
            base,
            ProductionData.empty(),
            new ProductionRuntimeEngine(ProductionData.empty()),
            new UnavailableAssetOccupancyLookup(),
            new UnavailableParticipantAttributeLookup(),
            null,
            CULTURE_LOOKUP,
            EconomyFixtures.landLedger(land));
    AccountSession accounts = AccountSession.empty();
    for (HouseholdId household : rows.keySet()) {
      accounts.registerHousehold(
          household,
          rows.get(household).view().hex(),
          household.equals(BUYER)
              ? Map.of(CaravanTransportTemplate.TRANSPORT_HORSE, buyerHorseMilli)
              : Map.of(),
          Map.of(SILVER, 0L),
          Map.of(),
          Map.of());
    }
    MarketTable market =
        new MarketTable(
            SILVER,
            Set.of(HOME, LAND_HEX),
            Map.of(GRAIN, 1L),
            OptionalLong.empty(),
            MarketTable.DEFAULT_LOAN_RATE_PER_MILLE,
            MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE);
    MarketZoneId zone = new MarketZoneId("c8-market");
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(5L);
    SettlementIndex index =
        SettlementIndex.buildWithoutAssetOccupancy(
            Map.of(), Map.of(FARM, industry), Map.of(), Map.of(), rows, Map.of(), Map.of());
    MarketSettlement.MarketRound round =
        new MarketSettlement.MarketRound(
            5L,
            rows,
            accounts.householdGoods(),
            accounts.householdMoney(),
            accounts.householdFrozenGoods(),
            accounts.householdFrozenMoney(),
            new LinkedHashMap<>(),
            accounts.householdOfActor(),
            Map.of(FARM, industry),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            ledger,
            Map.of(),
            index,
            Map.of(),
            MarketRegulation.none(),
            null,
            null,
            Set.of());
    round.markLandAcquired(new LandHoldingKey(LAND_HEX, BUYER), acquiredLandMilli);
    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            5L,
            accounts,
            MarketTopology.singleHex(Map.of(zone, market), Map.of()),
            false,
            ledger,
            EconomyParallelism.singleThreaded(),
            null,
            Map.of(),
            Set.of(),
            null,
            null,
            null,
            null);
    ctx.dayView = new WorkingDayView(session.sheet());
    ctx.base = base;
    ctx.householdEconomies = session.sheet().householdEconomies();
    ctx.householdGoods = accounts.householdGoods();
    ctx.assetShares = new LinkedHashMap<>();
    ctx.assetShares = new LinkedHashMap<>();
    ctx.markets = new LinkedHashMap<>(Map.of(zone, market));
    ctx.industries = new LinkedHashMap<>(Map.of(FARM, industry));
    ctx.hexToIndustries = Map.of(IndustryHexKeys.hexKey(0, 0), List.of(FARM));
    Map<HouseholdId, OwnershipClass> ownership = new LinkedHashMap<>();
    ownership.put(BUYER, OwnershipClass.PROLETARIAN);
    ownership.put(LAND_TARGET, OwnershipClass.OWNER);
    ownership.put(HORSE_TARGET, OwnershipClass.OWNER);
    ctx.ownershipClassAtMarketTradeStart = ownership;
    ctx.homeHexAtMarketTradeStart = Map.of(BUYER, HOME);
    return new World(ctx, round, accounts);
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long population) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(GRAIN, 10_000L),
        Map.of(),
        0L);
  }
}
