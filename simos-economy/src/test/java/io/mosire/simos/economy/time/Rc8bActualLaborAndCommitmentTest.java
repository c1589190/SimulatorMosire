package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC8b：Social 当前 labor/naturalNeeds 是生产输入，迁移承诺按剩余人口保留</b>。
 *
 * <p>锚点：当前户 labor 覆盖创世 plannedLabor；160 迁 16 时 84,093 → 75,683；hired 可提供劳动随当前 180,000 变化，但只授予 farm
 * 的实际可吸收需求。
 */
class Rc8bActualLaborAndCommitmentTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("rc8b-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-rc8b-farm");
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-rc8b-source");
  private static final HouseholdId TARGET = HouseholdId.parse("hh-rc8b-target");
  private static final ActorRef SOURCE_ACTOR = HouseholdActors.of(SOURCE);
  private static final ProductionRoleId ROLE = new ProductionRoleId("operator");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final long HISTORICAL_PLANNED_LABOR = 160_000L;

  @Test
  void partialMigrationRetainsSourceProductionCommitmentByRemainingPopulation() {
    long before = 84_093L;
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(
                Map.of(
                    SOURCE, row(SOURCE, 160L, 200_000L),
                    TARGET, row(TARGET, 40L, 40_000L)))
            .withLaborCommitments(Map.of(ALLOCATION, commitment(SOURCE, before)));
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
    accounts.registerHousehold(SOURCE, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(TARGET, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(5L);

    HouseholdTransferSettlement.apply(
        session,
        accounts,
        ledger,
        5L,
        0,
        new HouseholdTransferSettlement.HouseholdTransferRequest(
            SOURCE,
            TARGET,
            16L,
            HEX,
            java.util.Optional.of(DefaultProductionModes.GRAIN_FARM),
            "rc8b-retention",
            false),
        160L,
        40L);

    long after = session.sheet().laborCommitments().get(ALLOCATION).laborMilli();
    assertThat(after)
        .as("160 迁 16 ⇒ 保留 144/160 = 90%%：84,093 → floor(84,093 × 144 / 160) = 75,683")
        .isEqualTo(75_683L);
    assertThat(session.sheet().laborCommitments())
        .as("target 不凭空获得 source 的生产承诺")
        .containsOnlyKeys(ALLOCATION);
  }

  @Test
  void effectivePlanUsesCurrentLaborNotHistoricalRosterSnapshot() {
    World world = laborWorld(20_000L, HISTORICAL_PLANNED_LABOR, true);

    long effective =
        ProductionPlanBridge.effectivePlannedLaborMilliPerDay(
            world.unit, world.industry, world.organizer, world.households, world.index);

    assertThat(effective).as("当前户 labor=20,000 覆盖创世 plannedLabor=160,000").isEqualTo(20_000L);
    assertThat(world.roster.participants().get(0).plannedLaborMilliPerDay())
        .as("只读投影不写回历史 roster 快照")
        .isEqualTo(HISTORICAL_PLANNED_LABOR);
  }

  @Test
  void hiredCanOfferCurrentLaborButIsGrantedOnlyFarmAbsorbableDemand() {
    World world = laborWorld(180_000L, 22_040L, false);

    long effective =
        ProductionPlanBridge.effectivePlannedLaborMilliPerDay(
            world.unit, world.industry, world.organizer, world.households, world.index);
    assertThat(effective).as("target 180 人的当前劳动不再固定为 22,040").isEqualTo(180_000L);

    LaborQueueBook.Offer farmDemand =
        new LaborQueueBook.Offer(
            UNIT,
            ROLE,
            Optional.empty(),
            "grain_farm",
            50_000L,
            true,
            100_000L,
            0L,
            100_000L,
            1_000L,
            1_000L,
            "rc8b-farm-actual-demand");
    LaborQueueBook.Plan plan = LaborQueueBook.plan(TARGET, 180_000L, 0L, List.of(farmDemand));

    assertThat(plan.allocatedLaborMilli())
        .as("farm 实际只需要 50,000 ⇒ 只授予 50,000，不吸收全部 180,000")
        .isEqualTo(50_000L);
    assertThat(plan.idleLaborMilli()).isEqualTo(130_000L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private record World(
      IndustryDefinitionView industry,
      ProductionProcess unit,
      ProductionRoster roster,
      ProductionParticipant organizer,
      Map<HouseholdId, HouseholdEconomy> households,
      SettlementIndex index) {}

  private static World laborWorld(
      long currentLaborMilli, long plannedLaborMilli, boolean organizer) {
    IndustryDefinitionView industry =
        IndustryViews.industry(
            FARM,
            "RC8b grain farm",
            new RegimeId("rc8b"),
            10L,
            Map.of(AssetKind.LAND, 1L),
            Map.of(),
            0L,
            0L,
            Map.of(EconomySettlement.GRAIN, 4L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0));
    ProductionProcess unit =
        new ProductionProcess(
            UNIT,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            SOURCE_ACTOR,
            "mode:rc8b-farm",
            0L,
            Map.of(),
            Map.of());
    ProductionParticipant participant =
        ProductionParticipant.of(
            SOURCE,
            organizer ? ROLE : new ProductionRoleId("hired-hand"),
            organizer,
            plannedLaborMilli);
    List<ProductionParticipant> participants =
        organizer
            ? List.of(participant)
            : List.of(ProductionParticipant.of(SOURCE, ROLE, true, 0L), participant);
    ProductionRoster roster = new ProductionRoster(UNIT, FARM, participants);
    HouseholdEconomy row = row(SOURCE, 180L, currentLaborMilli);
    Map<HouseholdId, HouseholdEconomy> households = Map.of(SOURCE, row);
    LandHolding land = new LandHolding(SOURCE, HEX, 1_000L);
    LandArableSupply supply = new LandArableSupply(Map.of(HEX, 1_000L));
    SettlementIndex index =
        SettlementIndex.build(
            Map.of(UNIT, unit),
            Map.of(FARM, industry),
            Map.of(),
            Map.of(),
            households,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(UNIT, roster),
            0L,
            Set.of(),
            supply,
            Map.of(land.key(), land),
            Map.of());
    return new World(industry, unit, roster, participant, households, index);
  }

  private static final LaborAllocationId ALLOCATION =
      HouseholdLaborCommitment.idOfProduction(UNIT, ROLE, LOT, SOURCE);

  private static HouseholdLaborCommitment commitment(HouseholdId household, long laborMilli) {
    return new HouseholdLaborCommitment(
        ALLOCATION,
        LOT,
        household,
        SOURCE_ACTOR,
        UNIT.value(),
        laborMilli,
        0L,
        LaborCommitmentKind.PRODUCTION,
        Optional.of(ROLE));
  }

  private static HouseholdEconomy row(HouseholdId household, long population, long laborMilli) {
    return new HouseholdEconomy(
        household,
        new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
        population,
        laborMilli,
        1_000,
        0L,
        Map.of(EconomySettlement.GRAIN, population * 100L),
        Map.of(),
        0L);
  }
}
