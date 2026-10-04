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
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.ProductionRelation;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>设计 §8.1 第 2 条：{@link ExpectedProfitBook} 的单元判据</b>。
 *
 * <ul>
 *   <li>没有任何既有组织（无 {@code ProductionOrganization}、无真实利润账）的 (mode, hex) + 有价格 + 有可寻址需求
 *       ⇒ {@code feasible=true}、{@code scale>0}、{@code sellable>0}、{@code netPerLaborScaled != 0}——D-024 的核心反转；
 *   <li>可寻址需求为 0 ⇒ {@code sellable=0}、{@code netPerLaborScaled<=0}、{@code demandCapped=true}；
 *   <li>缺资产/缺劳动/缺产业模板/缺运力 ⇒ {@code feasible=false} 且 reason 具名；
 *   <li>同 mode 不同 hex 独立计算；
 *   <li>量纲专项：{@code laborNeed = laborPerUnit × scale × cycleDays}，收入/成本按手算逐值核对。
 * </ul>
 */
class ExpectedProfitBookTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final IndustryId CRAFT = IndustryHexKeys.id("craft", H.q(), H.r());
  private static final IndustryId CRAFT2 = IndustryHexKeys.id("craft", H2.q(), H2.r());
  private static final HouseholdId HOUSE = HouseholdId.parse("hh-profit");
  private static final ActorRef ACTOR = HouseholdActors.of(HOUSE);
  /** ★ 过期快照回归用的第二个家户：在 H 格没有任何自有资产，只能靠同格"闲置"份额进产。 */
  private static final HouseholdId NEWCOMER = HouseholdId.parse("hh-estate-newcomer");
  /** ★ 过期快照回归用的 ESTATE 主体（owner==operator 自营份额；产业型 id 必须指向已存在产业）。 */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, CRAFT.value());

  /** 一个手搭的最小世界（新形状 Industry/Unit/AssetShare/Relation；无组织、无真实利润账）。 */
  private record Fixture(
      EconomyData base,
      Map<AssetShareId, AssetShare> shares,
      AccountSession accounts,
      MarketTopology topology,
      Map<HexCoord, Market> markets,
      MarketDemandBook.Book demand,
      /** ★ 当天工作副本 organizations；claimed 集合的唯一正确来源（绝不用 base.productionOrganizations()）。 */
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {

    /** 本夹具的 H 格市场（H2 独立用例不走这里）。 */
    Market market() {
      return markets.get(H);
    }
  }

  private static Market market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(CLOTH, 10L);
    prices.put(FIBER, 20L);
    prices.put(GRAIN, 10L);
    return new Market(SILVER, prices);
  }

  /** craft 配方（手算基准）：cycle=10、WORKSHOP 1/规模、FIBER 5,000 毫/规模、labor 1,000、CLOTH 2 单位/规模。 */
  private static Industry craft(IndustryId id) {
    return new Industry(
        id,
        "craft-test",
        new RegimeId("handicraft"),
        10L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(CLOTH, 2L),
        Map.of(AssetKind.WORKSHOP, Map.of(FIBER, 5_000L)),
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static ClassRow row(long laborMilli, Map<CommodityId, Long> naturalNeeds) {
    return new ClassRow(
        HOUSE,
        new CohortKey(H, ResidenceKind.RURAL, POOR),
        10L,
        laborMilli,
        1000,
        0L,
        List.of(),
        naturalNeeds,
        Map.of(),
        0L);
  }

  private static AssetShare workshopShare(IndustryId industry, long quantity) {
    AssetShareId id =
        AssetShare.idOf(industry, AssetKind.WORKSHOP, ACTOR, ACTOR, AssetShare.RightKind.OWNED, 0L);
    return new AssetShare(
        id, industry, AssetKind.WORKSHOP, ACTOR, ACTOR, quantity, AssetShare.RightKind.OWNED);
  }

  private static MarketDemandBook.Book demandBook(IndustryId industry, CommodityId commodity, long addressable) {
    MarketDemandBook.Demand demand =
        new MarketDemandBook.Demand(
            H,
            commodity,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            addressable,
            List.of("test"));
    return new MarketDemandBook.Book(Map.of(H, Map.of(commodity, demand)), 0L, 10L);
  }

  private static Fixture fixture(
      Industry industry, long workshopQuantity, long laborMilli, MarketDemandBook.Book demand) {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(industry.id(), industry);
    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    AssetShare share = workshopShare(industry.id(), workshopQuantity);
    shares.put(share.id(), share);

    ProductionUnitId unitId = ProductionUnitId.idOf(industry.id(), ACTOR);
    ProductionUnit unit =
        new ProductionUnit(
            unitId, industry.id(), ACTOR, "mode:handicraft_workshop", 0L, 0L, Map.of());
    ProductionRelation relation =
        new ProductionRelation(
            unitId, ACTOR, new Recipient.ToActor(ACTOR), List.of(), ACTOR);

    ClassPositionId position =
        DefaultProductionModes
            .positionId(DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
            .orElseThrow();
    ClassRow row = row(laborMilli, Map.of(CLOTH, 100L));
    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withClassPositions(DefaultProductionModes.classPositions())
            .withClasses(Map.of(HOUSE, row))
            .withClassStandings(
                Map.of(
                    HOUSE,
                    new ClassStanding(HOUSE, position, position, Map.of(), 0L, 0L, "test-profit")))
            .withIndustries(industries)
            .withUnits(Map.of(unitId, unit))
            .withRelations(Map.of(unitId, relation))
            .withAssetShares(shares)
            .withMarkets(Map.of(H, market()));

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(HOUSE, ACTOR, H, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerOperator(ACTOR, H, Map.of(), Map.of(), Map.of(), Map.of());
    MarketTopology topology = MarketTopology.singleHex(Map.of(H, market()));
    return new Fixture(
        base, shares, accounts, topology, Map.of(H, market()), demand, Map.of());
  }

  /**
   * ★ 兼容重载专项：本类夹具的 claimed 证据只有“组织引用”一格，故保留单参
   * {@link ModeMigrationPolicy#claimedAssetShares(Map)}（等价于三参传空 units/shares）的兼容用法，
   * 同时验证“不得退回 {@code base.productionOrganizations()} 的 tick0 快照”。
   *
   * <p>★ 生产路径 = 三参 {@link ModeMigrationPolicy#claimedAssetShares(Map, Map, Map)}（{@code plan} 内部按
   * 当天工作副本 organizations + units + shares 构建）；unit 占用与“organizations 空 + 在产 ESTATE unit”的
   * 新语义单测见 {@code ModeMigrationPolicyClaimedAssetsTest}。
   */
  private static Set<AssetShareId> claimedFrom(
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return ModeMigrationPolicy.claimedAssetShares(organizations);
  }

  private static ExpectedProfitBook.Prospect prospect(Fixture fixture, Industry industry) {
    return prospectWithClaimed(
        fixture, HOUSE, industry, claimedFrom(fixture.organizations()));
  }

  private static ExpectedProfitBook.Prospect prospectWithClaimed(
      Fixture fixture,
      HouseholdId household,
      Industry industry,
      Set<AssetShareId> claimedByOrganizations) {
    return ExpectedProfitBook.prospect(
        fixture.base(),
        household,
        DefaultProductionModes.HANDICRAFT_WORKSHOP,
        H,
        industry,
        fixture.market(),
        fixture.demand(),
        fixture.shares(),
        claimedByOrganizations,
        fixture.accounts(),
        fixture.base().units(),
        fixture.base().relations(),
        fixture.topology(),
        0L);
  }

  @Test
  void noExistingOrganizationStillFeasibleAndNonZeroPerLabor() {
    Fixture fixture = fixture(craft(CRAFT), 3L, 10_000L, demandBook(CRAFT, CLOTH, 4_000L));
    assertThat(fixture.base().productionOrganizations()).as("夹具：没有任何既有组织").isEmpty();
    assertThat(fixture.base().merchantFirms()).isEmpty();

    ExpectedProfitBook.Prospect p = prospect(fixture, craft(CRAFT));

    assertThat(p.feasible()).as("★ D-024：没有既有组织/真实读数也能可行").isTrue();
    assertThat(p.feasibleScale()).as("assetScale=3, laborScale=10 ⇒ 3").isEqualTo(3L);
    assertThat(p.sellableMilli().get(CLOTH)).as("可售量必须 >0").isPositive();
    assertThat(p.netPerLaborScaled()).as("★ 单位劳动净收益必须非零（不是 hasReading 短路出的 0）").isNotZero();
  }

  @Test
  void zeroAddressableDemandCapsRevenueAndNeverPositive() {
    Fixture fixture = fixture(craft(CRAFT), 3L, 10_000L, demandBook(CRAFT, CLOTH, 0L));

    ExpectedProfitBook.Prospect p = prospect(fixture, craft(CRAFT));

    assertThat(p.sellableMilli().getOrDefault(CLOTH, 0L))
        .as("★ 可寻址需求 0 ⇒ 卖不出去")
        .isZero();
    assertThat(p.demandCapped()).as("plan − self = 5,000 > 0 ⇒ demandCapped").isTrue();
    assertThat(p.netPerLaborScaled()).as("★ 需求上限真实限制收入：单位劳动净收益不得为正").isLessThanOrEqualTo(0L);
    assertThat(p.reason()).contains("DEMAND_CAPPED");
  }

  @Test
  void infeasibleCasesAreNamed() {
    // ① 没有资产：WORKSHOP 份额 0 ⇒ assetScale=0。
    Fixture noAsset = fixture(craft(CRAFT), 0L, 10_000L, demandBook(CRAFT, CLOTH, 4_000L));
    ExpectedProfitBook.Prospect a = prospect(noAsset, craft(CRAFT));
    assertThat(a.feasible()).isFalse();
    assertThat(a.reason()).startsWith("NO_ASSET").contains(CRAFT.value());

    // ② 没有劳动：assetScale=1，laborScale=0 ⇒ NO_LABOR。
    Fixture noLabor = fixture(craft(CRAFT), 1L, 0L, demandBook(CRAFT, CLOTH, 4_000L));
    ExpectedProfitBook.Prospect b = prospect(noLabor, craft(CRAFT));
    assertThat(b.feasible()).isFalse();
    assertThat(b.reason()).startsWith("NO_LABOR").contains(HOUSE.value());

    // ③ 没有产业模板：industryOrNull=null 且该格没有模板 ⇒ NO_INDUSTRY。
    Fixture noIndustry = fixture(craft(CRAFT), 1L, 10_000L, demandBook(CRAFT, CLOTH, 4_000L));
    // 该 fixture 仍带 craft 产业；换一个「没有产业」的世界重测。
    EconomyData emptyIndustry =
        noIndustry
            .base()
            .withRelations(Map.of())
            .withUnits(Map.of())
            .withAssetShares(Map.of())
            .withIndustries(Map.of());
    ExpectedProfitBook.Prospect c2 =
        ExpectedProfitBook.prospect(
            emptyIndustry,
            HOUSE,
            DefaultProductionModes.HANDICRAFT_WORKSHOP,
            H,
            null,
            noIndustry.market(),
            noIndustry.demand(),
            Map.of(),
            claimedFrom(Map.of()),
            noIndustry.accounts(),
            Map.of(),
            Map.of(),
            noIndustry.topology(),
            0L);
    assertThat(c2.feasible()).isFalse();
    assertThat(c2.reason()).isEqualTo("NO_INDUSTRY");

    // ④ 缺 merchant 运力/产业模板 ⇒ NO_MERCHANT_CAPACITY（D-024 §3.2.5）。
    ExpectedProfitBook.Prospect merchant =
        ExpectedProfitBook.prospect(
            emptyIndustry,
            HOUSE,
            DefaultProductionModes.MERCHANT,
            H,
            null,
            noIndustry.market(),
            noIndustry.demand(),
            Map.of(),
            claimedFrom(Map.of()),
            noIndustry.accounts(),
            Map.of(),
            Map.of(),
            noIndustry.topology(),
            0L);
    assertThat(merchant.feasible()).isFalse();
    assertThat(merchant.reason()).startsWith("NO_MERCHANT_CAPACITY");
  }

  @Test
  void twoCandidateModesOrderMatchesHandCalculation() {
    // 候选 A：craft（labor 1,000/规模，CLOTH 2 单位，FIBER 5,000 毫/规模）——手算见 identical fixture。
    Fixture craftFixture = fixture(craft(CRAFT), 3L, 10_000L, demandBook(CRAFT, CLOTH, 4_000L));
    ExpectedProfitBook.Prospect craftProspect = prospect(craftFixture, craft(CRAFT));

    // 候选 B：family 产业（household regime、labor 10/规模、CLOTH 10 单位/规模、FIBER 100 毫/规模）。
    Industry family =
        new Industry(
            CRAFT2,
            "family-test",
            new RegimeId("household"),
            10L,
            Map.of(AssetKind.WORKSHOP, 1L),
            Map.of(),
            0L,
            10L,
            Map.of(CLOTH, 10L),
            Map.of(AssetKind.WORKSHOP, Map.of(FIBER, 100L)),
            List.of(new ClassSlot(POOR, "贫农", 800)),
            new AllocationRule.Split(500, 500));
    Fixture familyFixture = fixture(family, 1L, 10_000L, demandBook(CRAFT2, CLOTH, 4_000L));
    ExpectedProfitBook.Prospect familyProspect =
        ExpectedProfitBook.prospect(
            familyFixture.base(),
            HOUSE,
            DefaultProductionModes.FAMILY_FARM,
            H,
            family,
            familyFixture.market(),
            familyFixture.demand(),
            familyFixture.shares(),
            claimedFrom(familyFixture.organizations()),
            familyFixture.accounts(),
            familyFixture.base().units(),
            familyFixture.base().relations(),
            familyFixture.topology(),
            0L);

    // 手算 A：scale=min(3,10)=3；laborNeed=1,000×3×10=30,000；revenue=(1,000+3,000)×9/1000=36；
    //        inputCost=15,000×21/1000=315；laborCost=30,000×144×10/1e6=43；net=−322。见量纲专项用例逐值断言。
    // 手算 B：scale=min(1,1,000)=1；planned=10×1×1000=10,000；self=1,000；sell=3,000；
    //        revenue=(1,000+3,000)×9/1000=36；inputCost=100×21/1000=2；laborNeed=10×1×10=100；
    //        laborCost=100×144×10/1e6=0；net=34；perLabor=34×1e6/100=340,000。
    assertThat(craftProspect.netPerLaborScaled()).isEqualTo(-10_734L);
    assertThat(familyProspect.netPerLaborScaled()).isEqualTo(340_000L);
    assertThat(familyProspect.netPerLaborScaled())
        .as("★ 排序方向与手算一致：family 单位劳动净收益 > craft")
        .isGreaterThan(craftProspect.netPerLaborScaled());
    assertThat(craftProspect.netPerLaborScaled()).isNotZero();
  }

  @Test
  void dimensionsAreHandCheckedForOneFarmLikeCraft() {
    Fixture fixture = fixture(craft(CRAFT), 3L, 10_000L, demandBook(CRAFT, CLOTH, 4_000L));
    ExpectedProfitBook.Prospect p = prospect(fixture, craft(CRAFT));

    assertThat(p.plannedOutputMilli()).hasSize(1).containsEntry(CLOTH, 6_000L);
    assertThat(p.selfConsumedMilli()).hasSize(1).containsEntry(CLOTH, 1_000L);
    assertThat(p.sellableMilli()).hasSize(1).containsEntry(CLOTH, 3_000L);
    assertThat(p.revenueMilli()).as("(1,000+3,000) × 9 ÷ 1000").isEqualTo(36L);
    assertThat(p.inputCostMilli()).as("5,000 × 3 × 21 ÷ 1000").isEqualTo(315L);
    assertThat(p.laborNeedMilli())
        .as("★ laborPerUnit × scale × cycleDays = 1,000 × 3 × 10（不是 ×1）")
        .isEqualTo(30_000L);
    assertThat(p.laborCostMilli()).as("30,000 × 144 × 10 ÷ 1e6").isEqualTo(43L);
    assertThat(p.netMilli()).as("36 − 315 − 43").isEqualTo(-322L);
    assertThat(p.rentMilli()).isZero();
    assertThat(p.netPerLaborScaled()).as("floor(−322 × 1e6 ÷ 30,000)").isEqualTo(-10_734L);
    assertThat(p.demandCapped()).isTrue();
    assertThat(p.feasible()).isTrue();
  }

  @Test
  void sameModeDifferentHexIsIndependent() {
    // 同 mode（HANDICRAFT_WORKSHOP）在 H2 的价格/需求独立：H2 需求为 0 ⇒ sellable=0，而 H 仍可卖。
    Industry craft2 = craft(CRAFT2);
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(CRAFT, craft(CRAFT));
    industries.put(CRAFT2, craft2);
    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    AssetShare s1 = workshopShare(CRAFT, 3L);
    AssetShare s2 = workshopShare(CRAFT2, 3L);
    shares.put(s1.id(), s1);
    shares.put(s2.id(), s2);
    ProductionUnitId unit1 = ProductionUnitId.idOf(CRAFT, ACTOR);
    ProductionUnitId unit2 = ProductionUnitId.idOf(CRAFT2, ACTOR);
    ProductionUnit u1 = new ProductionUnit(unit1, CRAFT, ACTOR, "m1", 0L, 0L, Map.of());
    ProductionUnit u2 = new ProductionUnit(unit2, CRAFT2, ACTOR, "m1", 0L, 0L, Map.of());
    ProductionRelation r1 =
        new ProductionRelation(unit1, ACTOR, new Recipient.ToActor(ACTOR), List.of(), ACTOR);
    ProductionRelation r2 =
        new ProductionRelation(unit2, ACTOR, new Recipient.ToActor(ACTOR), List.of(), ACTOR);
    ClassPositionId position =
        DefaultProductionModes
            .positionId(DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
            .orElseThrow();
    ClassRow row = row(10_000L, Map.of(CLOTH, 100L));
    Map<HexCoord, Market> markets = Map.of(H, market(), H2, market());
    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withClassPositions(DefaultProductionModes.classPositions())
            .withClasses(Map.of(HOUSE, row))
            .withClassStandings(
                Map.of(HOUSE, new ClassStanding(HOUSE, position, position, Map.of(), 0L, 0L, "test")))
            .withIndustries(industries)
            .withUnits(Map.of(unit1, u1, unit2, u2))
            .withRelations(Map.of(unit1, r1, unit2, r2))
            .withAssetShares(shares)
            .withMarkets(markets);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(HOUSE, ACTOR, H, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerOperator(ACTOR, H, Map.of(), Map.of(), Map.of(), Map.of());
    MarketTopology topology = MarketTopology.singleHex(markets);
    MarketDemandBook.Book demand =
        new MarketDemandBook.Book(
            Map.of(
                H,
                Map.of(
                    CLOTH,
                    new MarketDemandBook.Demand(
                        H, CLOTH, 0, 0, 0, 0, 0, 0, 4_000L, List.of("test"))),
                H2,
                Map.of(
                    CLOTH,
                    new MarketDemandBook.Demand(
                        H2, CLOTH, 0, 0, 0, 0, 0, 0, 0L, List.of("test")))),
            0L,
            10L);

    ExpectedProfitBook.Prospect atH =
        ExpectedProfitBook.prospect(
            base, HOUSE, DefaultProductionModes.HANDICRAFT_WORKSHOP, H, craft(CRAFT),
            markets.get(H), demand, shares, claimedFrom(Map.of()), accounts, base.units(),
            base.relations(), topology, 0L);
    ExpectedProfitBook.Prospect atH2 =
        ExpectedProfitBook.prospect(
            base, HOUSE, DefaultProductionModes.HANDICRAFT_WORKSHOP, H2, craft2,
            markets.get(H2), demand, shares, claimedFrom(Map.of()), accounts, base.units(),
            base.relations(), topology, 0L);

    assertThat(atH.sellableMilli().get(CLOTH)).isEqualTo(3_000L);
    assertThat(atH2.sellableMilli()).as("★ 同 mode 不同 hex 独立：H2 可寻址需求 0 ⇒ 无销量").isEmpty();
    assertThat(atH2.demandCapped()).isTrue();
  }

  /**
   * ★★ <b>D-024 修复 1 回归（过期快照）夹具</b>：刻意让 {@code base.productionOrganizations()} 为空（tick0 快照），
   * 而某条 {@code owner==operator} 的 ESTATE 份额已被**当天工作副本** {@code organizations} 里的组织引用。
   * 候选家户 {@link #NEWCOMER} 在该格没有任何自有资产，因此它能否进产完全取决于这条份额是否被判为"闲置"。
   */
  private static Fixture staleSnapshotFixture() {
    Industry industry = craft(CRAFT);
    ClassPositionId position =
        DefaultProductionModes
            .positionId(
                DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
            .orElseThrow();
    ClassRow newcomerRow =
        new ClassRow(
            NEWCOMER,
            new CohortKey(H, ResidenceKind.RURAL, POOR),
            10L,
            10_000L,
            1000,
            0L,
            List.of(),
            Map.of(CLOTH, 100L),
            Map.of(),
            0L);
    AssetShare estateShare =
        new AssetShare(
            AssetShare.idOf(
                CRAFT, AssetKind.WORKSHOP, ESTATE, ESTATE, AssetShare.RightKind.OWNED, 0L),
            CRAFT,
            AssetKind.WORKSHOP,
            ESTATE,
            ESTATE,
            3L,
            AssetShare.RightKind.OWNED);
    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    shares.put(estateShare.id(), estateShare);

    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withClassPositions(DefaultProductionModes.classPositions())
            .withClasses(Map.of(NEWCOMER, newcomerRow))
            .withClassStandings(
                Map.of(
                    NEWCOMER,
                    new ClassStanding(NEWCOMER, position, position, Map.of(), 0L, 0L, "test-stale")))
            .withIndustries(Map.of(CRAFT, industry))
            .withAssetShares(shares)
            .withMarkets(Map.of(H, market()));

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        NEWCOMER, HouseholdActors.of(NEWCOMER), H, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerOperator(ESTATE, H, Map.of(), Map.of(), Map.of(), Map.of());
    MarketTopology topology = MarketTopology.singleHex(Map.of(H, market()));
    return new Fixture(
        base,
        shares,
        accounts,
        topology,
        Map.of(H, market()),
        demandBook(CRAFT, CLOTH, 4_000L),
        Map.of());
  }

  @Test
  void claimedSetMustComeFromWorkingCopyOrganizationsNotStaleBaseSnapshot() {
    Fixture fixture = staleSnapshotFixture();
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：tick0 快照 organizations 为空（旧实现正是从这里重算 claimed）")
        .isEmpty();
    AssetShareId estateShareId = fixture.shares().keySet().iterator().next();

    ClassPositionId position =
        DefaultProductionModes
            .positionId(
                DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
            .orElseThrow();
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            DefaultProductionModes.HANDICRAFT_WORKSHOP,
            position,
            NEWCOMER,
            IndustryHexKeys.hexKey(H.q(), H.r()));
    Map<ProductionOrganizationId, ProductionOrganization> workingCopy = new LinkedHashMap<>();
    workingCopy.put(
        organizationId,
        new ProductionOrganization(
            organizationId,
            DefaultProductionModes.HANDICRAFT_WORKSHOP,
            position,
            Optional.empty(),
            ESTATE,
            List.of(),
            List.of(estateShareId),
            List.of(),
            new Recipient.ToActor(ESTATE),
            Optional.of("fixture:stale-claimed"),
            ProductionOrganization.Status.SHORTAGE,
            "夹具：ESTATE 自营份额已被既有组织使用"));

    Set<AssetShareId> fromWorkingCopy = claimedFrom(workingCopy);
    Set<AssetShareId> fromStaleBase = claimedFrom(fixture.base().productionOrganizations());
    assertThat(fromWorkingCopy).as("工作副本组织引用 ESTATE 份额 ⇒ claimed 命中").contains(estateShareId);
    assertThat(fromStaleBase).as("★ tick0 快照为空 ⇒ 旧写法漏掉 claimed").doesNotContain(estateShareId);

    ExpectedProfitBook.Prospect claimed =
        prospectWithClaimed(fixture, NEWCOMER, craft(CRAFT), fromWorkingCopy);
    assertThat(claimed.feasible()).as("★ 传工作副本 claimed ⇒ ESTATE 份额不是闲置，候选不可行").isFalse();
    assertThat(claimed.reason()).startsWith("NO_ASSET");
    assertThat(claimed.feasibleScale()).isZero();

    ExpectedProfitBook.Prospect idle =
        prospectWithClaimed(fixture, NEWCOMER, craft(CRAFT), claimedFrom(Map.of()));
    assertThat(idle.feasible()).as("正对照：同一份额无人引用 ⇒ 仍算同格闲置可租").isTrue();
    assertThat(idle.feasibleScale()).as("ESTATE 份额 3 ⇒ assetScale=3").isEqualTo(3L);

    ExpectedProfitBook.Prospect stale =
        prospectWithClaimed(fixture, NEWCOMER, craft(CRAFT), fromStaleBase);
    assertThat(stale.feasible())
        .as("★ 区分对照：若图省事传 base.productionOrganizations()（空）⇒ 回到旧的'可抢占'")
        .isTrue();
    assertThat(stale.netPerLaborScaled()).isEqualTo(idle.netPerLaborScaled());
  }
}
