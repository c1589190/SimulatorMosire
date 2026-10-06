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
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-024 修复 1b：{@code ExpectedProfitBook.employerOf} 的新口径（只读当天 units）</b>。
 *
 * <p>{@code employerOf} 是私有方法，这里全部通过 {@code prospect(base, …, WAGE_FARM, …)} 的可观察输出判定：
 *
 * <ul>
 *   <li>{@code base.productionOrganizations()} 为空 + 当天 units 有 farm 主 unit ⇒ wage prospect 可行，规模 =
 *       {@link
 *       io.mosire.simos.economy.time.ProductionProcessBook#capacityScaleOf(ProductionProcess,
 *       Industry, Map)}；
 *   <li>本户自己的 unit 被排除（只有自有 unit ⇒ NO_EMPLOYER）；
 *   <li>错格 / 错产业 unit 被排除；
 *   <li>同秩同规模取 unitId 升序第一个（用两条不同的 FIXED_MONEY_WAGE 关系从收入反证选中哪条）；
 *   <li>modeKey 直接命中（秩 2）优先于规模更大的同产业 regime 相符 unit（秩 1）；
 *   <li>只有零产能 unit ⇒ NO_EMPLOYER（且零产能不得挡掉低秩的可用雇主）。
 * </ul>
 */
class ExpectedProfitBookEmployerOfTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");

  private static final HouseholdId WORKER = HouseholdId.parse("hh-wage-worker");
  private static final ActorRef SELF = HouseholdActors.of(WORKER);
  private static final ActorRef ESTATE_A = new ActorRef(ActorKind.ORGANIZATION, "estate-a");
  private static final ActorRef ESTATE_B = new ActorRef(ActorKind.ORGANIZATION, "estate-b");

  private static final IndustryId FARM = IndustryHexKeys.id("farm", H.q(), H.r());
  private static final IndustryId FARM2 = IndustryHexKeys.id("farm", H2.q(), H2.r());
  private static final IndustryId CRAFT = IndustryHexKeys.id("craft", H.q(), H.r());

  /** WAGE_FARM 的默认 regime = feudal（秩 1 的判据）；laborPerUnit=1,000、cycleDays=10、LAND 1/规模。 */
  private static Industry farm(IndustryId id) {
    return new Industry(
        id,
        "farm-employer-test",
        new RegimeId("feudal"),
        10L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 10L),
        Map.of(),
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static Industry craft() {
    return new Industry(
        CRAFT,
        "craft-employer-test",
        new RegimeId("handicraft"),
        10L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 10L),
        Map.of(),
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static Market market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    return new Market(SILVER, prices);
  }

  private static OwnershipStake share(
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      long quantity,
      long sequence) {
    AssetShareId id =
        OwnershipStake.idOf(
            industry, asset, owner, operator, OwnershipStake.RightKind.OWNED, sequence);
    return new OwnershipStake(
        id, industry, asset, owner, operator, quantity, OwnershipStake.RightKind.OWNED);
  }

  private static ProductionProcess unit(
      String id, IndustryId industry, ActorRef operator, String modeKey) {
    return new ProductionProcess(
        ProductionUnitId.parse(id), industry, operator, modeKey, 0L, 0L, Map.of());
  }

  /** 一条按 labor 给本户发固定货币工资的关系：prospect 收入 = amount ⇒ 可反证选中的雇主 unit。 */
  private static ProductionRules wageRelation(
      ProductionUnitId unitId, ActorRef operator, long amount) {
    CompensationRule rule =
        new CompensationRule(
            RuleType.FIXED_MONEY_WAGE,
            new Payee.ToHousehold(WORKER),
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            amount,
            Optional.empty(),
            Optional.of(SILVER),
            10);
    return new ProductionRules(
        unitId, operator, new Payee.ToActor(operator), List.of(rule), operator, LaborSource.WAGE);
  }

  private static EconomyData base(
      Industry industry,
      Map<AssetShareId, OwnershipStake> shares,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations) {
    HouseholdEconomy row =
        new HouseholdEconomy(
            WORKER,
            new CohortKey(H, ResidenceKind.RURAL, POOR),
            50L,
            10_000L,
            1000,
            0L,
            List.of(),
            Map.of(GRAIN, 100L),
            Map.of(),
            0L);
    ClassPositionId wagePosition =
        DefaultProductionModes.positionId(
                DefaultProductionModes.WAGE_FARM, DefaultProductionModes.ROLE_WAGE_LABORER)
            .orElseThrow();
    // 夹具把三个产业都登记进 base.industries，便于“错格/错产业”用例的份额通过 EconomyData 的跨表引用守卫；
    // 具体对哪个 (industry, hex) 求 prospect 仍由调用方显式传入。
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm(FARM));
    industries.put(FARM2, farm(FARM2));
    industries.put(CRAFT, craft());
    industries.put(industry.id(), industry);
    return EconomyData.empty()
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(Map.of(WORKER, row))
        .withClassMemberships(
            Map.of(
                WORKER,
                new HouseholdClassMembership(
                    WORKER, wagePosition, wagePosition, Map.of(), 0L, 0L, "test-employer")))
        .withIndustries(industries)
        .withProcesses(units)
        .withRelations(relations)
        .withOwnershipStakes(shares)
        .withMarkets(Map.of(H, market()));
  }

  private static ExpectedProfitBook.Prospect prospect(
      EconomyData base,
      Industry industry,
      Map<AssetShareId, OwnershipStake> shares,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations) {
    return ExpectedProfitBook.prospect(
        base,
        WORKER,
        DefaultProductionModes.WAGE_FARM,
        H,
        industry,
        market(),
        new MarketDemandBook.Book(Map.of(), 0L, 1L),
        shares,
        ModeMigrationPolicy.claimedOwnershipStakes(Map.of(), units, shares),
        AccountSession.empty(),
        units,
        relations,
        MarketTopology.singleHex(Map.of(H, market())),
        0L);
  }

  @Test
  void findsWageEmployerFromUnitsWhenProductionOrganizationsEmpty() {
    Industry industry = farm(FARM);
    ProductionProcess estateUnit = unit("unit-estate-a", FARM, ESTATE_A, FARM.value());
    OwnershipStake land = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 5L, 0L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(land.id(), land);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(estateUnit.id(), estateUnit);
    EconomyData base = base(industry, shares, units, Map.of());

    assertThat(base.productionOrganizations())
        .as("夹具：没有任何既有 ProductionEnterprise（旧 employerOf 的过期读数来源为空）")
        .isEmpty();
    long expectedScale = ProductionProcessBook.capacityScaleOf(estateUnit, industry, shares);
    assertThat(expectedScale).as("LAND 5 ÷ capacityPerUnit 1").isEqualTo(5L);

    ExpectedProfitBook.Prospect p = prospect(base, industry, shares, units, Map.of());

    assertThat(p.feasible()).as("★ 当天 units 有农场主 unit ⇒ wage prospect 可行").isTrue();
    assertThat(p.feasibleScale())
        .as("★ 规模 = employer 的 capacityScaleOf（laborScale=10 不是瓶颈）")
        .isEqualTo(expectedScale)
        .isEqualTo(5L);
    assertThat(p.reason()).contains("WAGE_POSITION");
  }

  @Test
  void ownUnitIsNeverOwnEmployer() {
    Industry industry = farm(FARM);
    // 本户自有大农场（LAND 100）+ 别人小农场（LAND 4）⇒ 只能用别人的 4。
    ProductionProcess selfUnit = unit("unit-self", FARM, SELF, FARM.value());
    ProductionProcess otherUnit = unit("unit-other", FARM, ESTATE_A, FARM.value());
    OwnershipStake selfLand = share(FARM, AssetKind.LAND, SELF, SELF, 100L, 0L);
    OwnershipStake otherLand = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 4L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(selfLand.id(), selfLand);
    shares.put(otherLand.id(), otherLand);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(selfUnit.id(), selfUnit);
    units.put(otherUnit.id(), otherUnit);

    ExpectedProfitBook.Prospect withOther =
        prospect(base(industry, shares, units, Map.of()), industry, shares, units, Map.of());
    assertThat(withOther.feasible()).isTrue();
    assertThat(withOther.feasibleScale()).as("★ 自有 unit（规模 100）被排除，取别人 unit 的规模 4").isEqualTo(4L);

    Map<ProductionUnitId, ProductionProcess> onlySelf = new LinkedHashMap<>();
    onlySelf.put(selfUnit.id(), selfUnit);
    Map<AssetShareId, OwnershipStake> onlySelfShares = new LinkedHashMap<>();
    onlySelfShares.put(selfLand.id(), selfLand);
    ExpectedProfitBook.Prospect alone =
        prospect(
            base(industry, onlySelfShares, onlySelf, Map.of()),
            industry,
            onlySelfShares,
            onlySelf,
            Map.of());
    assertThat(alone.feasible()).as("只有本户自己的 unit ⇒ 不得把自己当雇主").isFalse();
    assertThat(alone.reason()).startsWith("NO_EMPLOYER");
    assertThat(alone.feasibleScale()).isZero();
  }

  @Test
  void wrongHexOrWrongIndustryUnitsAreExcluded() {
    Industry industry = farm(FARM);
    // 错格：同产业种类但产业 id 在 H2（产业 id 自带格键 ⇒ 不同 hex 必然不同 industry id）。
    ProductionProcess otherHex = unit("unit-other-hex", FARM2, ESTATE_A, FARM2.value());
    OwnershipStake otherHexLand = share(FARM2, AssetKind.LAND, ESTATE_A, ESTATE_A, 100L, 0L);
    // 错产业：同格 H、但 craft 产业。
    ProductionProcess otherIndustry = unit("unit-other-industry", CRAFT, ESTATE_B, CRAFT.value());
    OwnershipStake otherIndustryShop =
        share(CRAFT, AssetKind.WORKSHOP, ESTATE_B, ESTATE_B, 100L, 1L);

    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(otherHexLand.id(), otherHexLand);
    shares.put(otherIndustryShop.id(), otherIndustryShop);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(otherHex.id(), otherHex);
    units.put(otherIndustry.id(), otherIndustry);

    ExpectedProfitBook.Prospect p =
        prospect(base(industry, shares, units, Map.of()), industry, shares, units, Map.of());

    assertThat(p.feasible()).as("错格 / 错产业 unit 都不是目标 farm@H 的雇主").isFalse();
    assertThat(p.reason()).startsWith("NO_EMPLOYER");
  }

  @Test
  void equalScaleCandidatesPickSmallerUnitId() {
    Industry industry = farm(FARM);
    ProductionProcess unitA = unit("unit-a", FARM, ESTATE_A, FARM.value());
    ProductionProcess unitB = unit("unit-b", FARM, ESTATE_B, FARM.value());
    OwnershipStake landA = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 5L, 0L);
    OwnershipStake landB = share(FARM, AssetKind.LAND, ESTATE_B, ESTATE_B, 5L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(landA.id(), landA);
    shares.put(landB.id(), landB);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(unitA.id(), unitA);
    units.put(unitB.id(), unitB);
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    relations.put(unitA.id(), wageRelation(unitA.id(), ESTATE_A, 100L));
    relations.put(unitB.id(), wageRelation(unitB.id(), ESTATE_B, 777L));

    ExpectedProfitBook.Prospect p =
        prospect(base(industry, shares, units, relations), industry, shares, units, relations);

    assertThat(p.feasible()).isTrue();
    assertThat(p.feasibleScale()).as("同秩同规模 ⇒ 5").isEqualTo(5L);
    assertThat(p.revenueMilli())
        .as("★ 同规模取 unitId 小者（unit-a）：收入来自 unit-a 的 100，而不是 unit-b 的 777")
        .isEqualTo(100L);
  }

  @Test
  void modeKeyDirectHitWinsOverLargerRegimeMatchedUnit() {
    Industry industry = farm(FARM);
    String directModeKey =
        EconomyEnterpriseSettlement.MODE_KEY_PREFIX + DefaultProductionModes.WAGE_FARM.value();
    // 秩 2：modeKey 直接命中，但规模只有 2。
    ProductionProcess directHit = unit("unit-direct-hit", FARM, ESTATE_A, directModeKey);
    OwnershipStake directHitLand = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 2L, 0L);
    // 秩 1：产业 regime=feudal 与 WAGE_FARM 默认 regime 相符，规模 10（更大）。
    ProductionProcess regimeMatch = unit("unit-regime-match", FARM, ESTATE_B, FARM.value());
    OwnershipStake regimeLand = share(FARM, AssetKind.LAND, ESTATE_B, ESTATE_B, 10L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(directHitLand.id(), directHitLand);
    shares.put(regimeLand.id(), regimeLand);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(directHit.id(), directHit);
    units.put(regimeMatch.id(), regimeMatch);
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    relations.put(directHit.id(), wageRelation(directHit.id(), ESTATE_A, 100L));
    relations.put(regimeMatch.id(), wageRelation(regimeMatch.id(), ESTATE_B, 777L));

    ExpectedProfitBook.Prospect p =
        prospect(base(industry, shares, units, relations), industry, shares, units, relations);

    assertThat(p.feasible()).isTrue();
    assertThat(p.feasibleScale())
        .as("★ modeKey 直接命中优先 ⇒ 用秩 2 unit 的规模 2，而不是秩 1 unit 的 10")
        .isEqualTo(2L);
    assertThat(p.revenueMilli())
        .as("★ 收入来自 modeKey 命中 unit 的 100，而不是规模更大 unit 的 777")
        .isEqualTo(100L);
  }

  @Test
  void onlyZeroCapacityUnitYieldsNoEmployer() {
    Industry industry = farm(FARM);
    ProductionProcess emptyUnit = unit("unit-empty", FARM, ESTATE_A, FARM.value());
    OwnershipStake zeroLand = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 0L, 0L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(zeroLand.id(), zeroLand);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(emptyUnit.id(), emptyUnit);

    ExpectedProfitBook.Prospect p =
        prospect(base(industry, shares, units, Map.of()), industry, shares, units, Map.of());

    assertThat(p.feasible()).as("只有零产能 unit ⇒ 没有真实雇主").isFalse();
    assertThat(p.reason()).startsWith("NO_EMPLOYER");
    assertThat(p.feasibleScale()).isZero();
  }

  @Test
  void zeroCapacityHigherRankUnitDoesNotShadowLowerRankEmployer() {
    Industry industry = farm(FARM);
    String directModeKey =
        EconomyEnterpriseSettlement.MODE_KEY_PREFIX + DefaultProductionModes.WAGE_FARM.value();
    // 秩 2 但零产能：必须被跳过，不能挡住下面秩 1 的可用雇主。
    ProductionProcess zeroRank2 = unit("unit-zero-rank2", FARM, ESTATE_A, directModeKey);
    OwnershipStake zeroLand = share(FARM, AssetKind.LAND, ESTATE_A, ESTATE_A, 0L, 0L);
    ProductionProcess usableRank1 = unit("unit-usable-rank1", FARM, ESTATE_B, FARM.value());
    OwnershipStake usableLand = share(FARM, AssetKind.LAND, ESTATE_B, ESTATE_B, 7L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(zeroLand.id(), zeroLand);
    shares.put(usableLand.id(), usableLand);
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(zeroRank2.id(), zeroRank2);
    units.put(usableRank1.id(), usableRank1);
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    relations.put(usableRank1.id(), wageRelation(usableRank1.id(), ESTATE_B, 500L));

    ExpectedProfitBook.Prospect p =
        prospect(base(industry, shares, units, relations), industry, shares, units, relations);

    assertThat(p.feasible()).isTrue();
    assertThat(p.feasibleScale()).as("跳过零产能高秩后取秩 1 的规模 7").isEqualTo(7L);
    assertThat(p.revenueMilli()).isEqualTo(500L);
  }
}
