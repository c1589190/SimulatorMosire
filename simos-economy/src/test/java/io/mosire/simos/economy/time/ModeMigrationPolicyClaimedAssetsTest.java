package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-024 修复 1b：三参 {@link ModeMigrationPolicy#claimedAssetShares(Map, Map, Map)} 的单元判据</b>。
 *
 * <p>claimed = 组织引用 ∪ 在产 unit 占用（同 industry、同 operator、quantity&gt;0）。这里逐格钉死：
 * 组织引用、unit 占用、operator/industry/quantity 不匹配、单参旧重载的兼容语义，以及
 * “organizations 空 + 在产 ESTATE unit + 对应份额”必须让新进入者判 NO_ASSET（不得把在产份额当闲置租用）。
 */
class ModeMigrationPolicyClaimedAssetsTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final ProductionModeId CRAFT = DefaultProductionModes.HANDICRAFT_WORKSHOP;

  private static final IndustryId FARM =
      IndustryHexKeys.id("farm", H.q(), H.r());
  private static final IndustryId CRAFT_INDUSTRY =
      IndustryHexKeys.id("craft", H.q(), H.r());
  private static final IndustryId OTHER_INDUSTRY =
      IndustryHexKeys.id("craft", H2.q(), H2.r());
  private static final HouseholdId HOUSE = HouseholdId.parse("hh-claimed-house");
  private static final HouseholdId NEWCOMER = HouseholdId.parse("hh-claimed-newcomer");

  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "estate-claimed-test");
  private static final ActorRef OP1 = new ActorRef(ActorKind.ESTATE, "op-1");
  private static final ActorRef OP2 = new ActorRef(ActorKind.ESTATE, "op-2");

  private static AssetShare share(
      IndustryId industry, AssetKind asset, ActorRef owner, ActorRef operator, long quantity, long sequence) {
    AssetShareId id =
        AssetShare.idOf(
            industry, asset, owner, operator, AssetShare.RightKind.OWNED, sequence);
    return new AssetShare(id, industry, asset, owner, operator, quantity, AssetShare.RightKind.OWNED);
  }

  private static ProductionUnit unit(IndustryId industry, ActorRef operator) {
    ProductionUnitId id = ProductionUnitId.idOf(industry, operator);
    return new ProductionUnit(id, industry, operator, industry.value(), 0L, 0L, Map.of());
  }

  private static ProductionOrganization organization(
      ProductionModeId mode, ClassPositionId position, HouseholdId organizer, String hexKey, List<AssetShareId> sources) {
    ProductionOrganizationId id =
        ProductionOrganizationId.idOf(mode, position, organizer, hexKey);
    return new ProductionOrganization(
        id,
        mode,
        position,
        Optional.empty(),
        HouseholdActors.of(organizer),
        List.of(organizer),
        sources,
        List.of(new Recipient.ToActor(HouseholdActors.of(organizer))),
        new Recipient.ToActor(HouseholdActors.of(organizer)),
        Optional.of("fixture:claimed-test"),
        ProductionOrganization.Status.SHORTAGE,
        "fixture: organization status is irrelevant for claimed-asset set");
  }

  private static ClassPositionId craftOwnerPosition() {
    return DefaultProductionModes
        .positionId(CRAFT, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
        .orElseThrow();
  }

  private static Industry craftIndustry() {
    return new Industry(
        CRAFT_INDUSTRY,
        "craft-claimed-test",
        new RegimeId("handicraft"),
        10L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(CLOTH, 2L),
        Map.of(AssetKind.WORKSHOP, Map.of()),
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static Market market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(CLOTH, 10L);
    prices.put(GRAIN, 10L);
    return new Market(SILVER, prices);
  }

  private static MarketDemandBook.Book demand() {
    MarketDemandBook.Demand demand =
        new MarketDemandBook.Demand(
            H, CLOTH, 0L, 0L, 0L, 0L, 0L, 0L, 10_000L, List.of("test"));
    return new MarketDemandBook.Book(Map.of(H, Map.of(CLOTH, demand)), 0L, 1L);
  }

  /**
   * ★★ 需求点：organizations 为空 + 当天 units 有一条在产 ESTATE unit + 对应份额。
   *
   * <p>单位份额 owner==operator（旧“闲置”形状），但已被 unit 实际占用 ⇒ 新进入者不得把它当闲置租用。
   */
  private record EstateUnitFixture(
      EconomyData base,
      Map<AssetShareId, AssetShare> shares,
      Map<ProductionUnitId, ProductionUnit> units,
      AssetShareId estateShareId,
      AccountSession accounts,
      MarketTopology topology) {}

  private static EstateUnitFixture estateUnitFixture() {
    AssetShare estateShare = share(CRAFT_INDUSTRY, AssetKind.WORKSHOP, ESTATE, ESTATE, 3L, 0L);
    ProductionUnit estateUnit = unit(CRAFT_INDUSTRY, ESTATE);
    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    shares.put(estateShare.id(), estateShare);
    Map<ProductionUnitId, ProductionUnit> units = new LinkedHashMap<>();
    units.put(estateUnit.id(), estateUnit);

    ClassPositionId position = craftOwnerPosition();
    ClassRow row =
        new ClassRow(
            NEWCOMER,
            new CohortKey(H, ResidenceKind.RURAL, POOR),
            50L,
            10_000L,
            1000,
            0L,
            List.of(),
            Map.of(CLOTH, 100L),
            Map.of(),
            0L);
    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withClassPositions(DefaultProductionModes.classPositions())
            .withClasses(Map.of(NEWCOMER, row))
            .withClassStandings(
                Map.of(
                    NEWCOMER,
                    new ClassStanding(NEWCOMER, position, position, Map.of(), 0L, 0L, "test-claimed")))
            .withIndustries(Map.of(CRAFT_INDUSTRY, craftIndustry()))
            .withUnits(units)
            .withRelations(Map.of())
            .withAssetShares(shares)
            .withMarkets(Map.of(H, market()));

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        NEWCOMER, HouseholdActors.of(NEWCOMER), H, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerOperator(ESTATE, H, Map.of(), Map.of(), Map.of(), Map.of());
    MarketTopology topology = MarketTopology.singleHex(Map.of(H, market()));
    return new EstateUnitFixture(base, shares, units, estateShare.id(), accounts, topology);
  }

  private static ExpectedProfitBook.Prospect newcomerProspect(
      EstateUnitFixture fixture, Set<AssetShareId> claimed) {
    return ExpectedProfitBook.prospect(
        fixture.base(),
        NEWCOMER,
        CRAFT,
        H,
        fixture.base().industries().get(CRAFT_INDUSTRY),
        market(),
        demand(),
        fixture.shares(),
        claimed,
        fixture.accounts(),
        fixture.units(),
        fixture.base().relations(),
        fixture.topology(),
        0L);
  }

  // ── 单元判据 ────────────────────────────────────────────────────────────────────────────

  @Test
  void organizationReferencesAreClaimed() {
    AssetShareId s1 = AssetShareId.parse("share-org-1");
    AssetShareId s2 = AssetShareId.parse("share-org-2");
    Map<ProductionOrganizationId, ProductionOrganization> organizations = new LinkedHashMap<>();
    ProductionOrganization organization =
        organization(CRAFT, craftOwnerPosition(), HOUSE, IndustryHexKeys.hexKey(H.q(), H.r()), List.of(s1, s2));
    organizations.put(organization.id(), organization);

    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedAssetShares(organizations, Map.of(), Map.of());

    assertThat(claimed)
        .as("第 ① 格：组织 assetSources 全部计入 claimed")
        .containsExactlyInAnyOrder(s1, s2);
  }

  @Test
  void producingUnitClaimsMatchingShareOnly() {
    ProductionUnit farmUnit = unit(FARM, OP1);
    AssetShare sMatch = share(FARM, AssetKind.LAND, OP1, OP1, 3L, 0L);
    AssetShare sZeroQty = share(FARM, AssetKind.LAND, OP1, OP1, 0L, 1L);
    AssetShare sOtherOperator = share(FARM, AssetKind.LAND, OP1, OP2, 3L, 2L);
    AssetShare sOtherIndustry = share(CRAFT_INDUSTRY, AssetKind.WORKSHOP, OP1, OP1, 3L, 3L);

    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    shares.put(sMatch.id(), sMatch);
    shares.put(sZeroQty.id(), sZeroQty);
    shares.put(sOtherOperator.id(), sOtherOperator);
    shares.put(sOtherIndustry.id(), sOtherIndustry);

    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedAssetShares(
            Map.of(), Map.of(farmUnit.id(), farmUnit), shares);

    assertThat(claimed)
        .as("第 ② 格：同 industry + 同 operator + quantity>0 的份额被在产 unit 占用")
        .containsExactly(sMatch.id());
    assertThat(claimed)
        .as("quantity=0 / operator 不符 / industry 不符都不得被算成占用")
        .doesNotContain(sZeroQty.id(), sOtherOperator.id(), sOtherIndustry.id());
  }

  @Test
  void singleArgOverloadCountsOnlyOrganizationReferences() {
    AssetShareId organizationShare = AssetShareId.parse("share-compat-org");
    Map<ProductionOrganizationId, ProductionOrganization> organizations = new LinkedHashMap<>();
    ProductionOrganization organization =
        organization(
            CRAFT,
            craftOwnerPosition(),
            HOUSE,
            IndustryHexKeys.hexKey(H.q(), H.r()),
            List.of(organizationShare));
    organizations.put(organization.id(), organization);

    ProductionUnit farmUnit = unit(FARM, OP1);
    AssetShare unitShare = share(FARM, AssetKind.LAND, OP1, OP1, 3L, 0L);

    Set<AssetShareId> singleArg = ModeMigrationPolicy.claimedAssetShares(organizations);
    Set<AssetShareId> threeArg =
        ModeMigrationPolicy.claimedAssetShares(
            organizations, Map.of(farmUnit.id(), farmUnit), Map.of(unitShare.id(), unitShare));

    assertThat(singleArg)
        .as("★ 兼容重载的语义只算组织引用（等价于三参传空 units/shares）")
        .isEqualTo(ModeMigrationPolicy.claimedAssetShares(organizations, Map.of(), Map.of()))
        .contains(organizationShare)
        .doesNotContain(unitShare.id());
    assertThat(threeArg)
        .as("★ 生产路径的三参版本同时计入组织引用与在产 unit 占用")
        .contains(organizationShare, unitShare.id());
  }

  @Test
  void estateUnitWithoutOrganizationMakesShareNotIdleAndBlocksNewcomer() {
    EstateUnitFixture fixture = estateUnitFixture();
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：tick0 / 当天 organizations 都为空——旧写法正是从这里重算 claimed")
        .isEmpty();

    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedAssetShares(
            fixture.base().productionOrganizations(), fixture.units(), fixture.shares());

    assertThat(claimed)
        .as("★ organizations 空 + 在产 ESTATE unit + 对应份额 ⇒ unit 占用必须进 claimed")
        .contains(fixture.estateShareId());
    AssetShare estateShare = fixture.shares().get(fixture.estateShareId());
    assertThat(ModeMigrationPolicy.isIdleShare(estateShare, claimed))
        .as("★ owner==operator 的份额被在产 unit 占用 ⇒ 不是闲置可租")
        .isFalse();
    assertThat(ModeMigrationPolicy.isIdleShare(estateShare, Set.of()))
        .as("正对照：没人占用时同一条 owner==operator 份额仍算闲置")
        .isTrue();

    ExpectedProfitBook.Prospect blocked = newcomerProspect(fixture, claimed);
    assertThat(blocked.feasible())
        .as("★ 新进入者不得把 ESTATE 在产份额当闲置租用 ⇒ NO_ASSET")
        .isFalse();
    assertThat(blocked.reason()).startsWith("NO_ASSET");

    ExpectedProfitBook.Prospect idle = newcomerProspect(fixture, ModeMigrationPolicy.claimedAssetShares(Map.of()));
    assertThat(idle.feasible())
        .as("正对照：单参兼容重载（只算组织引用，空）仍把该份额当闲置 ⇒ 可行")
        .isTrue();
    assertThat(idle.feasibleScale()).as("闲置份额 3 ⇒ assetScale=3").isEqualTo(3L);
  }
}
