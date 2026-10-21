package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RF3/RF3b 验收：landless / 新地主持有 LAND + labor&gt;0 ⇒ D4 请求非 0、动态劳动回退生效、 M6 候选 slot
 * 出现；roster 原 planned=0 不被回写</b>。
 *
 * <p>判据出处：{@code docs/superpowers/plans/2026-10-20-d032-rf3-land-owner-labor-fallback-taskbook.md}
 * §7、 {@code docs/superpowers/plans/2026-10-20-d032-rf3b-land-fallback-occupancy-taskbook.md} §7。
 */
class Rf3LandOwnerLaborFallbackAcceptanceTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("rf3-farm", 0, 0);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final HouseholdId H1 = HouseholdId.parse("hh-rf3-land-owner");
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-rf3-farm");
  private static final ActorRef OPERATOR = HouseholdActors.of(H1);
  private static final long HOUSEHOLD_LABOR = 200_000L;

  @Test
  void landHoldingWithLaborMakesD4AllocationNonZeroAndEffectiveLaborFallback() {
    World world = world(2_000L);

    assertThat(world.index.assetAllocationOf(UNIT).getOrDefault(AssetKind.LAND, 0L))
        .as("★ RF3b：持有 LAND + labor>0 ⇒ D4 LAND allocation > 0（旧闭环恒 0）")
        .isPositive();
    long effective =
        ProductionPlanBridge.effectivePlannedLaborMilliPerDay(
            world.unit, world.industry, world.organizer, world.households, world.index);
    assertThat(effective)
        .as("★ RF3：roster planned=0 但持地有劳动 ⇒ 有效计划劳动回退为当刻户劳动")
        .isEqualTo(HOUSEHOLD_LABOR);
    assertThat(world.roster.participants().get(0).plannedLaborMilliPerDay())
        .as("★ 不落盘改写 roster：历史快照原值仍为 0")
        .isZero();

    List<LaborCandidateBook.LaborSlot> slots =
        LaborCandidateBook.candidateSlotsOf(
            world.unit,
            world.households,
            Map.of(),
            Map.of(),
            world.index,
            world.definitions,
            Map.of(UNIT, world.roster));
    assertThat(slots).as("★ RF3：helper 同源进入 M6 候选 ⇒ organizer 得到 farm 劳动 slot").hasSize(1);
    assertThat(slots.get(0).household()).isEqualTo(H1);
    assertThat(slots.get(0).role()).isEqualTo(world.organizer.role());
  }

  @Test
  void landlessOrZeroLaborHouseholdDoesNotGetTheFallback() {
    World noLand = world(0L);
    assertThat(noLand.index.assetAllocationOf(UNIT).getOrDefault(AssetKind.LAND, 0L))
        .as("无 LAND 持有 ⇒ D4 无 LAND 分配")
        .isZero();
    assertThat(
            ProductionPlanBridge.effectivePlannedLaborMilliPerDay(
                noLand.unit, noLand.industry, noLand.organizer, noLand.households, noLand.index))
        .as("★ 负向：无 LAND 占用 ⇒ 不得凭空回退劳动")
        .isZero();

    World zeroLabor = world(2_000L, 0L);
    assertThat(
            ProductionPlanBridge.effectivePlannedLaborMilliPerDay(
                zeroLabor.unit,
                zeroLabor.industry,
                zeroLabor.organizer,
                zeroLabor.households,
                zeroLabor.index))
        .as("★ 负向：户 labor=0 ⇒ 不得回退")
        .isZero();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private record World(
      IndustryDefinitionView industry,
      ProductionProcess unit,
      ProductionRoster roster,
      ProductionParticipant organizer,
      Map<HouseholdId, HouseholdEconomy> households,
      SettlementIndex index,
      ProductionData definitions) {}

  private static World world(long landHoldingMilliMu) {
    return world(landHoldingMilliMu, HOUSEHOLD_LABOR);
  }

  private static World world(long landHoldingMilliMu, long householdLaborMilli) {
    IndustryDefinitionView industry = industry();
    ProductionProcess unit =
        new ProductionProcess(
            UNIT,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            OPERATOR,
            "mode:rf3-farm",
            0L,
            Map.of(),
            Map.of());
    ProductionParticipant organizer = ProductionParticipant.of(H1, roleId(), true, 0L);
    ProductionRoster roster = new ProductionRoster(UNIT, FARM, List.of(organizer));
    HouseholdEconomy row =
        new HouseholdEconomy(
            H1,
            new CohortKey(HEX, ResidenceKind.RURAL, POOR),
            1L,
            householdLaborMilli,
            1_000,
            0L,
            Map.of(GRAIN, 1_000L),
            Map.of(),
            0L);
    Map<HouseholdId, HouseholdEconomy> households = Map.of(H1, row);
    // ★ landless 变体必须让"每 hex 持有合计 == 固定可耕总量"仍成立：没有 LAND 时该 hex 的可耕总量也必须为 0。
    LandArableSupply arableSupply =
        landHoldingMilliMu <= 0L
            ? new LandArableSupply(Map.of())
            : new LandArableSupply(Map.of(HEX, landHoldingMilliMu));
    Map<LandHoldingKey, LandHolding> landHoldings =
        landHoldingMilliMu <= 0L
            ? Map.of()
            : Map.of(new LandHoldingKey(HEX, H1), new LandHolding(H1, HEX, landHoldingMilliMu));
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
            arableSupply,
            landHoldings,
            Map.of());
    ProductionData definitions = ProductionData.empty().withIndustry(industry).withInstance(unit);
    return new World(industry, unit, roster, organizer, households, index, definitions);
  }

  private static io.mosire.simos.economy.api.production.ProductionRoleId roleId() {
    return io.mosire.simos.economy.api.production.ProductionRoleId.parse("operator");
  }

  private static IndustryDefinitionView industry() {
    ProductionRoleDefinition organizer =
        new ProductionRoleDefinition(
            roleId(),
            "组织者",
            Optional.empty(),
            1_000L,
            IntraRoleSplitRule.EQUAL,
            0L,
            Optional.empty(),
            List.of(),
            new RolePayment.ProductShare());
    return new IndustryDefinitionView(
        FARM,
        "RF3 土地回退夹具",
        new RegimeId("tenant"),
        Optional.empty(),
        120L,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5_000L),
        Map.of(),
        List.of(new ClassSlot(POOR, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(io.mosire.simos.economy.api.asset.AssetRightKind.OWNED),
        List.of(organizer),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty());
  }
}
