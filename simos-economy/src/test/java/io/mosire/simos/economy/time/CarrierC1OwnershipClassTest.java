package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-032 C1：ownershipClass 由资产派生，PROLETARIAN 不得组织 PRODUCT_SHARE 生产位置。 */
class CarrierC1OwnershipClassTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-c1-owner-check");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  @Test
  void ownershipClassComesFromLandHorseOrNonLandDurableAssetOnly() {
    assertThat(classOf(landKey(1L), Map.of(), Map.of()))
        .as("LAND > 0 ⇒ OWNER")
        .isEqualTo(OwnershipClass.OWNER);
    assertThat(
            classOf(Map.of(), Map.of(CaravanTransportTemplate.TRANSPORT_HORSE, 1_000L), Map.of()))
        .as("transport_horse > 0 ⇒ OWNER")
        .isEqualTo(OwnershipClass.OWNER);
    assertThat(
            classOf(
                Map.of(),
                Map.of(),
                Map.of(
                    new AssetShareId("c1-cattle"),
                    new OwnershipStake(
                        new AssetShareId("c1-cattle"),
                        CaravanTransportTemplate.industryId(H),
                        AssetKind.CATTLE,
                        HouseholdActors.of(HOUSEHOLD),
                        HouseholdActors.of(HOUSEHOLD),
                        1L,
                        io.mosire.simos.economy.api.asset.AssetRightKind.OWNED,
                        ProductionLocation.ofPlan(CaravanTransportTemplate.industryId(H))
                            .orElseThrow()))))
        .as("非 LAND 耐久资产 > 0 ⇒ OWNER")
        .isEqualTo(OwnershipClass.OWNER);
    assertThat(classOf(Map.of(), Map.of(new CommodityId("grain"), 1_000_000L), Map.of()))
        .as("普通消费/投入库存不计生产资料；现金也不在输入里")
        .isEqualTo(OwnershipClass.PROLETARIAN);
  }

  @Test
  void proletarianOrganizerIsRejectedButOwnerOrganizerIsAccepted() {
    World world = world();
    var industry = CaravanTransportTemplate.industry(H);
    ProductionProcess unit =
        new ProductionProcess(
            ProductionUnitId.idOf(industry.id(), HouseholdActors.of(HOUSEHOLD)),
            industry.id(),
            ProductionLocation.ofPlan(industry.id()).orElseThrow(),
            HouseholdActors.of(HOUSEHOLD),
            DefaultProductionModes.MERCHANT.value(),
            0L,
            Map.of(),
            Map.of());
    ProductionRoster proletarianRoster = roster(unit, industry);

    assertThat(
            ProductionPlanBridge.positionLocationRejection(
                world.ctx, unit, proletarianRoster, industry))
        .as("PROLETARIAN 占 organizer/PRODUCT_SHARE ⇒ 具名拒绝")
        .contains(ProductionPlanBridge.PROLETARIAN_CANNOT_OWN_POSITION);

    world.ctx.householdGoods.put(
        HOUSEHOLD, Map.of(CaravanTransportTemplate.TRANSPORT_HORSE, 1_000L));
    assertThat(
            ProductionPlanBridge.positionLocationRejection(
                world.ctx, unit, proletarianRoster, industry))
        .as("同一位置、同一 mode 下仅因资产 > 0 变 OWNER ⇒ 放行；生产方式切换不改 class")
        .isEmpty();
  }

  private static ProductionRoster roster(
      ProductionProcess unit, io.mosire.simos.production.contract.IndustryDefinitionView industry) {
    return new ProductionRoster(
        unit.id(),
        industry.id(),
        List.of(
            ProductionParticipant.of(
                HOUSEHOLD, CaravanTransportTemplate.ROLES.get(0).id(), true, 0L)));
  }

  private static OwnershipClass classOf(
      Map<LandHoldingKey, LandHolding> land,
      Map<CommodityId, Long> goods,
      Map<AssetShareId, OwnershipStake> shares) {
    return OwnershipClassBook.classOf(land, goods, shares, HOUSEHOLD);
  }

  private static Map<LandHoldingKey, LandHolding> landKey(long quantity) {
    return Map.of(new LandHoldingKey(H, HOUSEHOLD), new LandHolding(HOUSEHOLD, H, quantity));
  }

  private static World world() {
    Map<HouseholdId, HouseholdEconomy> rows =
        Map.of(
            HOUSEHOLD,
            new HouseholdEconomy(
                HOUSEHOLD,
                new CohortKey(H, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
                10L,
                1_000L,
                1_000,
                0L,
                Map.of(),
                Map.of(),
                0L));
    EconomyData base = EconomyData.empty().withHouseholdEconomies(rows);
    EconomySession session =
        new EconomySession(
            base, ProductionData.empty(), new ProductionRuntimeEngine(ProductionData.empty()));
    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            5L,
            AccountSession.empty(),
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
    ctx.householdEconomies = new LinkedHashMap<>(rows);
    ctx.householdGoods = new LinkedHashMap<>();
    ctx.assetShares = new LinkedHashMap<>();
    ctx.dayView = new WorkingDayView(session.sheet());
    return new World(ctx);
  }

  private record World(SettlementDayContext ctx) {}
}
