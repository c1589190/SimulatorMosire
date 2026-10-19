package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>设计 §8.1 第 3 条：{@link ModeMigrationPolicy} 的 D-024 判据</b>。
 *
 * <ul>
 *   <li>{@code hasReading=false}（传空真实利润账）但预期正收益的候选仍产出非空迁移计划；
 *   <li>权重方向 = {@code max(0, target.netPerLaborScaled − current.netPerLaborScaled)}；
 *   <li>A 规则用预期利润：{@code net<0 && liquidity<inputCost} 触发 1000‰，否则不触发；
 *   <li>计划与执行前后源户 {@code mode / standings.currentPositionId / org.modeId / unit.modeKey} 不变（D-022）。
 * </ul>
 */
class ModeMigrationPolicyD024Test {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");

  private static final HouseholdId SOURCE = HouseholdId.parse("hh-source");
  private static final HouseholdId BUYER1 = HouseholdId.parse("hh-buyer-1");
  private static final HouseholdId BUYER2 = HouseholdId.parse("hh-buyer-2");

  private static final IndustryId SOURCE_INDUSTRY = IndustryHexKeys.id("source", H1.q(), H1.r());
  private static final IndustryId TARGET_INDUSTRY = IndustryHexKeys.id("target", H2.q(), H2.r());

  /** ★ 过期快照回归：H2 目标份额的 ESTATE 所有者（owner==operator，且不是迁移源户）。 */
  private static final ActorRef ESTATE =
      new ActorRef(ActorKind.ORGANIZATION, TARGET_INDUSTRY.value());

  /**
   * ★★ W1：市场 = **逐区价表**（{@code marketTables}）；逐格读口见 {@link #tableAt(HexCoord)}（改动前 {@code
   * markets.get(hex)} 的替身）。
   */
  private record Fixture(
      EconomyData base,
      ProductionData definitions,
      AccountSession accounts,
      MarketTopology topology,
      Map<MarketZoneId, MarketTable> marketTables,
      AssetShareId sourceShareId,
      AssetShareId targetShareId,
      ProductionUnitId sourceUnitId) {

    MarketTable tableAt(HexCoord hex) {
      MarketZoneId id = EconomyFixtures.marketIdOf(hex);
      return marketTables.get(id);
    }
  }

  private static MarketTable market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    return EconomyFixtures.quote(SILVER, prices);
  }

  private static IndustryDefinitionView industry(
      IndustryId id, String label, long outputUnits, long inputMilli) {
    Map<io.mosire.simos.actor.api.asset.AssetKind, Map<CommodityId, Long>> cycleInput =
        inputMilli <= 0L ? Map.of() : Map.of(AssetKind.WORKSHOP, Map.of(GRAIN, inputMilli));
    return IndustryViews.industry(
        id,
        label,
        new RegimeId("household"),
        10L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, outputUnits),
        cycleInput,
        List.of(new ClassSlot(POOR, "贫农", 800)),
        new AllocationRule.Split(500, 500));
  }

  private static HouseholdEconomy row(
      HouseholdId id,
      HexCoord hex,
      long population,
      long laborMilli,
      Map<CommodityId, Long> needs) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        laborMilli,
        1000,
        0L,
        needs,
        Map.of(),
        0L);
  }

  private static ClassPositionId grainPosition() {
    return DefaultProductionModes.positionId(
            DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
        .orElseThrow();
  }

  /**
   * @param sourceOutputUnits 源户当前产业每规模产出（亏损用例填 1）
   * @param sourceInputMilli 源户当前产业每规模 GRAIN 投入（毫；亏损用例填 5,000,000）
   * @param buyer1NeedPerDay H1 额外买方日需求（毫 GRAIN/日）
   * @param buyer2NeedPerDay H2 买方日需求（毫 GRAIN/日）
   * @param sourceMoney 源户银余额（毫；控制 A 规则流动性）
   * @param sourceHasOrganization 是否给源户挂 ProductionEnterprise（D-022 执行用例需要）
   */
  private static Fixture build(
      long sourceOutputUnits,
      long sourceInputMilli,
      long buyer1NeedPerDay,
      long buyer2NeedPerDay,
      long sourceMoney,
      boolean sourceHasOrganization) {
    return build(
        sourceOutputUnits,
        sourceInputMilli,
        buyer1NeedPerDay,
        buyer2NeedPerDay,
        sourceMoney,
        sourceHasOrganization,
        false);
  }

  /**
   * @param targetShareOwnedByEstate true ⇒ H2 目标产业的 WORKSHOP 份额由 {@link #ESTATE} 自营
   *     （owner==operator，且不是迁移源户）——过期快照回归用它验证"工作副本 claimed 才能挡住抢占"。
   */
  private static Fixture build(
      long sourceOutputUnits,
      long sourceInputMilli,
      long buyer1NeedPerDay,
      long buyer2NeedPerDay,
      long sourceMoney,
      boolean sourceHasOrganization,
      boolean targetShareOwnedByEstate) {
    return build(
        sourceOutputUnits,
        sourceInputMilli,
        buyer1NeedPerDay,
        buyer2NeedPerDay,
        sourceMoney,
        sourceHasOrganization,
        targetShareOwnedByEstate,
        DEFAULT_H2_SHARE_QUANTITY);
  }

  /** 既有 100 单位（HEAD 逐值）—— 逐格夹具的默认作业资本量；W9f 的负向用例把它压到 need − 1。 */
  private static final long DEFAULT_H2_SHARE_QUANTITY = 100L;

  /**
   * @param h2ShareQuantity ★★★ <b>W9f 测试批（2026-10-11）加的第八参</b>：H2 那份 {@code WORKSHOP} 份额的数量 —— 计划期
   *     {@code reserveIdleAssetsFor} 判"凑不凑得齐作业资本"读的就是它（{@code isIdleShare}：数量 &gt; 0 ∧ operator ==
   *     owner ∧ 未被在产单位占用）。把它参数化，才能做"计划期预留尺度 == 执行期索取尺度"的边界用例（need − 1 ⇒ 计划期就该拒）。
   */
  private static Fixture build(
      long sourceOutputUnits,
      long sourceInputMilli,
      long buyer1NeedPerDay,
      long buyer2NeedPerDay,
      long sourceMoney,
      boolean sourceHasOrganization,
      boolean targetShareOwnedByEstate,
      long h2ShareQuantity) {
    ClassPositionId position = grainPosition();
    Map<HouseholdId, HouseholdEconomy> classes = new LinkedHashMap<>();
    classes.put(SOURCE, row(SOURCE, H1, 100L, 1_000L, Map.of(GRAIN, 100L)));
    classes.put(BUYER1, row(BUYER1, H1, 100L, 0L, Map.of(GRAIN, buyer1NeedPerDay)));
    // ★★★ W9-fix 测试批（2026-10-11）：**H2 的既有并入目标户劳动 = 0**（与 HEAD 逐值相同：`git show
    //   HEAD:…ModeMigrationPolicyD024Test.java` 的第 183 行就是 `row(BUYER2, H2, 100L, 0L, …)`）。
    //
    //   ★ 这一行**同时就是 U1 的回归用例**：W9 首版把"参与率折算劳动 = 0"的户从**同一张表**里删掉，而那张表
    //     既是"源户候选表"、又是 `bestExistingTarget` 的**并入目标注册表** ⇒ 劳动 0 的 H2 户不再能被并入，
    //     迁入只能**新建户**、撞上实物腿闸（`in-kind-leg-unavailable:cloth`）⇒ 计划变空（W9d 实测 `moves=0`）。
    //     W9d 当时把这一格改成 1,000 正是为了绕开那个缺陷。
    //   ★★ W9-fix 的 ③ 把两张表**分开**（源户候选表要求劳动 > 0；并入目标注册表**不**要求）⇒ 这一格可以、
    //     也必须改回 0 —— 改回后计划应**仍非空且落在并入支**（`target == BUYER2`，见
    //     `hasReadingFalseButPositiveExpectedCandidateYieldsPlan` 的断言）。★ 若哪天有人把两张表再合回去，
    //     本行 + 那条断言会当场红 —— 这就是它的判别力所在。
    //   ★ 取值 0 也让 BUYER2 自己**不产生** move（它在 H2 的当前读数高于它在 H1 的候选 ⇒ 收益差为负），
    //     本类"源户只有 SOURCE"的断言因此仍然成立。
    classes.put(BUYER2, row(BUYER2, H2, 100L, 0L, Map.of(GRAIN, buyer2NeedPerDay)));

    Map<HouseholdId, HouseholdClassMembership> standings = new LinkedHashMap<>();
    for (HouseholdId household : List.of(SOURCE, BUYER1, BUYER2)) {
      standings.put(
          household,
          new HouseholdClassMembership(
              household, position, position, Map.of(), 0L, 0L, "test-d024"));
    }

    IndustryDefinitionView sourceIndustry =
        industry(SOURCE_INDUSTRY, "source-industry", sourceOutputUnits, sourceInputMilli);
    IndustryDefinitionView targetIndustry = industry(TARGET_INDUSTRY, "target-industry", 100L, 0L);
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    industries.put(SOURCE_INDUSTRY, sourceIndustry);
    industries.put(TARGET_INDUSTRY, targetIndustry);

    ActorRef sourceActor = HouseholdActors.of(SOURCE);
    AssetShareId h1ShareId =
        OwnershipStake.idOf(
            SOURCE_INDUSTRY,
            AssetKind.WORKSHOP,
            sourceActor,
            sourceActor,
            AssetRightKind.OWNED,
            new ProductionLocation(IndustryHexKeys.hexKey(H1.q(), H1.r())),
            0L);
    OwnershipStake h1Share =
        new OwnershipStake(
            h1ShareId,
            SOURCE_INDUSTRY,
            AssetKind.WORKSHOP,
            sourceActor,
            sourceActor,
            1L,
            AssetRightKind.OWNED,
            new ProductionLocation(IndustryHexKeys.hexKey(H1.q(), H1.r())));
    ActorRef targetShareOwner = targetShareOwnedByEstate ? ESTATE : sourceActor;
    AssetShareId h2ShareId =
        OwnershipStake.idOf(
            TARGET_INDUSTRY,
            AssetKind.WORKSHOP,
            targetShareOwner,
            targetShareOwner,
            AssetRightKind.OWNED,
            new ProductionLocation(IndustryHexKeys.hexKey(H2.q(), H2.r())),
            0L);
    OwnershipStake h2Share =
        new OwnershipStake(
            h2ShareId,
            TARGET_INDUSTRY,
            AssetKind.WORKSHOP,
            targetShareOwner,
            targetShareOwner,
            h2ShareQuantity,
            AssetRightKind.OWNED,
            new ProductionLocation(IndustryHexKeys.hexKey(H2.q(), H2.r())));
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(h1ShareId, h1Share);
    shares.put(h2ShareId, h2Share);

    ProductionUnitId sourceUnitId = ProductionUnitId.idOf(SOURCE_INDUSTRY, sourceActor);
    ProductionProcess sourceUnit =
        new ProductionProcess(
            sourceUnitId,
            SOURCE_INDUSTRY,
            ProductionLocation.ofPlan(SOURCE_INDUSTRY).orElseThrow(),
            sourceActor,
            "mode:grain_farm",
            0L,
            Map.of(),
            Map.of());
    ProductionRules sourceRelation =
        new ProductionRules(
            sourceUnitId, sourceActor, new Payee.ToActor(sourceActor), List.of(), sourceActor);

    // ★★ W1：逐格报价 → 一格一张派生价表（键 = "hex:<q>_<r>"，与 MarketTopology.singleHex 的节点 id 同源）。
    Map<HexCoord, MarketTable> quotes = new LinkedHashMap<>();
    quotes.put(H1, market());
    quotes.put(H2, market());
    Map<MarketZoneId, MarketTable> marketTables = EconomyFixtures.hexMarkets(quotes);

    // ★★★ D-032 T1：本夹具的生产实例住在 production（唯一权威）—— 旧形状把它放在 economy 的 units 分量里；
    //   现在随定义一起挂在 production 切片上（`instances()` 是 `ProductionRuntime.units()` 的同义读口）。
    ProductionData definitions =
        ProductionData.empty()
            .withModes(DefaultProductionModes.modes())
            .withIndustries(industries)
            .withInstance(sourceUnit);
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(classes)
            .withClassMemberships(standings)
            .withRelations(Map.of(sourceUnitId, sourceRelation))
            .withOwnershipStakes(shares)
            .withMarketTables(marketTables);
    if (sourceHasOrganization) {
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.idOf(
              DefaultProductionModes.GRAIN_FARM,
              position,
              SOURCE,
              IndustryHexKeys.hexKey(H1.q(), H1.r()));
      base =
          base.withProductionEnterprises(
              Map.of(
                  organizationId,
                  new ProductionEnterprise(
                      organizationId,
                      DefaultProductionModes.GRAIN_FARM,
                      position,
                      Optional.of(sourceUnitId),
                      sourceActor,
                      List.of(SOURCE),
                      List.of(h1ShareId),
                      List.of(new Payee.ToActor(sourceActor)),
                      new Payee.ToActor(sourceActor),
                      Optional.of("fixture:d024"),
                      ProductionEnterprise.Status.ACTIVE,
                      "")));
    }

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        SOURCE,
        H1,
        // ★★ T9（R1 新语义）：§15.12 B 起候选/当前户的"所需投入"取自 probe，而 probe 的投入通道读本户商品余额
        //   （缺键 ⇒ 0 ⇒ 具名 input-short）。本夹具的命题是"预期亏损驱动 A 规则"，不是"缺料被拒" ——
        //   按 SOURCE_INDUSTRY 的每规模投入（sourceInputMilli）备**一档**料（scale=1 时恰够），
        //   使候选仍可行、净收益仍为负；备料量不进入任何估值断言（inputCost 读 probe.inputRequired）。
        sourceInputMilli <= 0L ? Map.of() : Map.of(GRAIN, sourceInputMilli),
        sourceMoney <= 0L ? Map.of() : Map.of(SILVER, sourceMoney),
        Map.of(),
        Map.of());
    accounts.registerHousehold(BUYER1, H1, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(BUYER2, H2, Map.of(), Map.of(), Map.of(), Map.of());

    return new Fixture(
        base,
        definitions,
        accounts,
        MarketTopology.singleHex(marketTables, Map.of()),
        marketTables,
        h1ShareId,
        h2ShareId,
        sourceUnitId);
  }

  /**
   * ★★ claimed 的唯一正确来源是调用方传入的**当天工作副本**（{@code organizations + units + assetShares}）： 生产路径 {@code
   * plan} 内部走三参 {@link ModeMigrationPolicy#claimedOwnershipStakes(Map, Map, Map)}。
   * 这里显式复制一份组织工作副本，绝不把 {@code base} 的 tick0 快照当工作副本递进去；unit 占用那一格也按 当天 units/shares 一并传入。
   */
  private static Map<ProductionOrganizationId, ProductionEnterprise> workingCopy(
      Map<ProductionOrganizationId, ProductionEnterprise> organizations) {
    return new LinkedHashMap<>(organizations);
  }

  private static Set<AssetShareId> claimedFrom(
      Fixture fixture, Map<ProductionOrganizationId, ProductionEnterprise> organizations) {
    return ModeMigrationPolicy.claimedOwnershipStakes(
        organizations, fixture.definitions().instances(), fixture.base().assetShares());
  }

  private static ModeMigrationPolicy.MigrationPlan plan(Fixture fixture) {
    return plan(fixture, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ModeMigrationPolicy.MigrationPlan plan(
      Fixture fixture, Map<ProductionOrganizationId, ProductionEnterprise> organizations) {
    return ModeMigrationPolicy.plan(
        EconomyFixtures.dayView(fixture.base(), fixture.definitions()),
        organizations,
        fixture.definitions().instances(),
        fixture.base().classes(),
        fixture.base().classStandings(),
        fixture.base().assetShares(),
        fixture.base().relations(),
        Map.of(),
        fixture.marketTables(),
        fixture.base().debtContracts(),
        fixture.accounts(),
        new EnterpriseProfitBook.Book(Map.of(), Map.of(), Map.of()),
        10L,
        fixture.topology(),
        List.of(),
        // ★ T9（R1 签名）：plan 内逐户/逐候选的物理量取自 production 试算。
        EconomyFixtures.runtimeOf(fixture.definitions()));
  }

  private static ExpectedProfitBook.Prospect currentProspect(Fixture fixture) {
    return currentProspect(fixture, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ExpectedProfitBook.Prospect currentProspect(
      Fixture fixture, Map<ProductionOrganizationId, ProductionEnterprise> organizations) {
    return ExpectedProfitBook.prospect(
        EconomyFixtures.dayView(fixture.base(), fixture.definitions()),
        SOURCE,
        DefaultProductionModes.GRAIN_FARM,
        H1,
        fixture.definitions().industries().get(SOURCE_INDUSTRY),
        fixture.tableAt(H1),
        demandOf(fixture),
        fixture.base().assetShares(),
        claimedFrom(fixture, organizations),
        fixture.accounts(),
        fixture.definitions().instances(),
        fixture.base().relations(),
        fixture.topology(),
        10L,
        EconomyFixtures.runtimeOf(fixture.definitions()));
  }

  private static ExpectedProfitBook.Prospect targetProspect(
      Fixture fixture, ProductionModeId mode) {
    return targetProspect(fixture, mode, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ExpectedProfitBook.Prospect targetProspect(
      Fixture fixture,
      ProductionModeId mode,
      Map<ProductionOrganizationId, ProductionEnterprise> organizations) {
    return ExpectedProfitBook.prospect(
        EconomyFixtures.dayView(fixture.base(), fixture.definitions()),
        SOURCE,
        mode,
        H2,
        fixture.definitions().industries().get(TARGET_INDUSTRY),
        fixture.tableAt(H2),
        demandOf(fixture),
        fixture.base().assetShares(),
        claimedFrom(fixture, organizations),
        fixture.accounts(),
        fixture.definitions().instances(),
        fixture.base().relations(),
        fixture.topology(),
        10L,
        EconomyFixtures.runtimeOf(fixture.definitions()));
  }

  private static MarketDemandBook.Book demandOf(Fixture fixture) {
    AssetOccupancyBudget budget =
        ProductionProcessBook.assetOccupancyBudget(
            fixture.definitions().instances(),
            fixture.base().assetShares(),
            fixture.base().assetOccupancies(),
            fixture.definitions().industries(),
            fixture.base().productionRosters(),
            10L);
    return MarketDemandBook.build(
        List.of(),
        fixture.topology(),
        fixture.base().classes(),
        fixture.definitions().instances(),
        fixture.definitions().industries(),
        fixture.base().assetShares(),
        fixture.base().shipments(),
        fixture.accounts(),
        fixture.marketTables(),
        10L,
        10L,
        budget);
  }

  private static final ProductionModeId FAMILY = DefaultProductionModes.GRAIN_FARM;

  @Test
  void hasReadingFalseButPositiveExpectedCandidateYieldsPlan() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：没有任何既有组织 ⇒ hasReading=false")
        .isEmpty();

    ExpectedProfitBook.Prospect target = targetProspect(fixture, FAMILY);
    assertThat(target.feasible()).isTrue();
    assertThat(target.netPerLaborScaled()).as("★ 预期正收益候选").isPositive();

    // ★★★ W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：**速度不再是常量基线**（`MIGRATION_PER_MILLE` 已按 §一.11
    //   删除）—— 它由这一对 (当前, 目标) 的**预期收益差**现算。下面用夹具自己算出来的两把读数推期望值，
    //   算式与 main 的 `profitGapSpeedPerMille` 逐字同形（不写死一个数 ⇒ 换夹具即换期望，断言仍有效）；
    //   「算式本身逐值对不对」由本账本新增的 `ModeMigrationProfitGapSpeedW9Test` 用逐值表钉。
    long currentPerLabor = currentProspect(fixture).netPerLaborScaled();
    long targetPerLabor = target.netPerLaborScaled();
    assertThat(targetPerLabor)
        .as("★ 前置：差必须为正，否则下面 allSatisfy 会空洞地绿")
        .isGreaterThan(currentPerLabor);
    long expectedSpeed = expectedProfitGapSpeed(currentPerLabor, targetPerLabor);
    assertThat(expectedSpeed).as("★ 前置：正的收益差必须产生正的转业速度").isPositive();

    ModeMigrationPolicy.MigrationPlan plan = plan(fixture);
    assertThat(plan.moves()).as("★ D-024：没有既存读数也能产出非空迁移计划").isNotEmpty();
    assertThat(plan.moves())
        .as(
            "★★ U1 回归（W9-fix）：H2 的既住户 BUYER2 劳动 = 0（与 HEAD 的同格夹具逐值相同），但它**仍留在并入目标"
                + "注册表**里 ⇒ 这一笔必须落在'并入既有户'那一支（target == BUYER2），而不是新建户。"
                + "改前（W9 首版把两张表合一）：劳动 0 的户连并入目标都当不了 ⇒ 迁入落到新建支、撞实物腿闸 ⇒ 计划为空")
        .anyMatch(move -> move.target().equals(BUYER2));
    assertThat(plan.moves())
        .allSatisfy(
            move -> {
              assertThat(move.source()).isEqualTo(SOURCE);
              assertThat(move.target()).isNotEqualTo(move.source());
              assertThat(move.transferSpeedPerMille())
                  .as("★ W9：速度 = 收益差驱动（不再是常量基线）")
                  .isEqualTo(expectedSpeed);
              assertThat(move.reason())
                  .isEqualTo(ModeMigrationPolicy.MigrationMove.REASON_PROFIT_WEIGHTED);
            });
    assertThat(plan.moves().stream().mapToLong(ModeMigrationPolicy.MigrationMove::population).sum())
        .as(
            "★ W9-fix 可迁人口 = min(速度折算, 上限折算)（人口 100、速度 %d‰ ⇒ %d 人）",
            expectedSpeed, expectedMoveablePopulation(100L, expectedSpeed))
        .isEqualTo(expectedMoveablePopulation(100L, expectedSpeed));
  }

  /**
   * ★★★ <b>W9f 测试批（2026-10-11）：计划期"预留多少作业资本" == 执行期"索取多少"（尺度唯一的拼写点），且<b>少一份就必须在计划期被拒</b></b>。
   *
   * <p>★★ <b>这条判据补的是什么（W9-fix2 的判据缺口）</b>：W9-fix2 把"本笔需要多少作业资本"收成唯一拼写点 {@code
   * ModeMigrationPolicy.requiredWorkCapitalScale(IndustryDefinitionView, long)}，计划期<b>预留</b>（{@code
   * reserveIdleAssetsFor}）与执行期<b>索取</b>（{@code ModeMigrationSettlement.createEnterpriseAndProcess →
   * planAssetMoves}）共用它。两侧一旦各拼一遍（W9-fix2 之前就是），缺口表现为 <b>计划期少留、执行期多要</b> ⇒ 撞 fail-closed 守卫 ⇒ <b>整个
   * tick 抛（真世界 three-powers day=270 HTTP 500）</b>。 而本批实跑全仓 3,464 条 0 红 ⇒
   * 这条一致性在既有测试里<b>没有任何入口</b>。本用例就是那个入口。
   *
   * <p>★★ <b>为什么它是"真"判据而不是恒真断言</b>：判据链是"<b>先读这一笔真的会被搬多少人口</b>（{@code take}）⇒ 用与 main 同形但独立重写的算式算
   * need ⇒ 把目标格的闲置作业资本压到 {@code need − 1} ⇒ 计划必须<b>空</b>；给回 {@code need} ⇒
   * 计划必须<b>回来</b>"。任何一侧的尺度算式漂开（份额算少、劳动口径取错、预留不查），这条链都会断在"压到 {@code need − 1} 仍然有 move"上。
   *
   * <p>★ <b>读数层</b>：{@code take} 由夹具自己的两把读数（速度、人口）现算，不写死；need 由目标产业模板自己的 {@code laborPerUnit} /
   * {@code capacityPerUnit} 现算。
   */
  @Test
  void thePlanPhaseReservesExactlyTheWorkCapitalThatExecutionWillClaim() {
    // ① 富余夹具（H2 的 WORKSHOP 份额 = 夹具默认的 100 单位）：计划必须非空，并读出这一笔会搬多少人口。
    Fixture rich = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    ModeMigrationPolicy.MigrationPlan plan = plan(rich);
    assertThat(plan.moves()).as("★ 前置：富余作业资本的夹具必须产出非空计划（否则下面整条链空洞地绿）").isNotEmpty();
    long take =
        plan.moves().stream().mapToLong(ModeMigrationPolicy.MigrationMove::population).sum();

    IndustryDefinitionView targetIndustry = rich.definitions().industries().get(TARGET_INDUSTRY);
    HouseholdEconomy source = rich.base().classes().get(SOURCE);
    long laborPerUnit = targetIndustry.recipe().laborPerUnit();
    long perUnit = targetIndustry.recipe().capacityPerUnit().get(AssetKind.WORKSHOP);
    // 计划期的劳动入参 = 唯一拼写点 laborCarriedByMoveUpperBound（★ 本用例直接调它，等于把"计划期用哪一份劳动"也钉住）；
    // need = requiredWorkCapitalScale(模板, 那份劳动) × capacityPerUnit[每一维]（与执行期 planAssetMoves 同一乘）。
    long laborEstimate =
        ModeMigrationPolicy.laborCarriedByMoveUpperBound(
            source.laborMilli(), source.population(), take);
    long scale = ModeMigrationPolicy.requiredWorkCapitalScale(targetIndustry, laborEstimate);
    long need = Math.multiplyExact(scale, perUnit);

    assertThat(laborEstimate)
        .as(
            "计划期劳动入参 = ⌈源户劳动 %d × take %d ÷ 源户人口 %d⌉（唯一拼写点，不是落盘行的 0）",
            source.laborMilli(), take, source.population())
        .isPositive();
    assertThat(need)
        .as(
            "需要量 = scale(%d) × capacityPerUnit[WORKSHOP](%d) = %d 单位（laborPerUnit=%d）",
            scale, perUnit, need, laborPerUnit)
        .isPositive();

    // ② 负向：把目标格的闲置作业资本压到 need − 1 ⇒ 计划期**就该拒**（不能留给执行期撞守卫 ⇒ 那是 HTTP 500）。
    Fixture shortByOne =
        build(100L, 0L, 5_000L, 10_000L, 0L, false, false, Math.max(0L, need - 1L));
    assertThat(plan(shortByOne).moves())
        .as(
            "★ 只给 need − 1 = %d 单位作业资本 ⇒ 计划必须为空（预留不足的目标不许进计划；"
                + "这一笔若进了计划，执行期会按同一算式索取 %d 单位 ⇒ 撞 fail-closed 守卫 ⇒ tick 抛）",
            Math.max(0L, need - 1L), need)
        .isEmpty();

    // ③ 边界正向：给回 need ⇒ 计划必须回来（证明 ② 拒的是"少一份"，不是"这一格根本不迁"）。
    Fixture exactly = build(100L, 0L, 5_000L, 10_000L, 0L, false, false, need);
    assertThat(plan(exactly).moves())
        .as("★ 恰好 need = %d 单位 ⇒ 计划必须恢复（边界就在 need 这一份上）", need)
        .isNotEmpty();
    assertThat(
            plan(exactly).moves().stream()
                .mapToLong(ModeMigrationPolicy.MigrationMove::population)
                .sum())
        .as("★ 边界两侧的 take 逐值相同（预留只决定'这一笔在不在'，不改变搬多少人）")
        .isEqualTo(take);
  }

  /**
   * ★★★ <b>W9f 测试批（2026-10-11）：壳户（人口 ≤ 0）不产出迁移，也不改变别人的计划（判据"壳户跳过"）</b>。
   *
   * <p>口径出处：{@code ModeMigrationPolicy.java:457-460}（候选循环：{@code population <= 0 || classMembership
   * == null} ⇒ {@code tally.shellSkipped(true)} + {@code continue}）+ 用户派单 ⑥（W9：「壳户不刷屏」，只进轮级汇总）。
   * 真世界读数面 = {@code MODE_MIGRATION_CANDIDATES} 的 {@code skippedShellNoPopulation}（W9f sw
   * 0→360：Σ876； tp 0→360：Σ1716）；本用例是它**在计划层面的可执行判据**。
   *
   * <p>★★ <b>为什么是"逐值不变"而不是"少了几笔"</b>：壳户被跳过必须是**纯剔除**——它既不自己产生 move，也不能把别人的 move
   * 挤掉（否则"跳过壳户"就是一条会改世界行为的隐藏筛选）。所以判据 = ① 没有任何 move 的 source 是壳户；② 整份计划 （source →
   * target/targetMode/population/speed 的规范串）与**没有壳户的那份夹具逐值相同**。
   */
  @Test
  void aShellHouseholdProducesNoMoveAndDoesNotChangeAnyoneElsesPlan() {
    HouseholdId shell = HouseholdId.parse("hh-d024-shell-population-zero");
    HexCoord h3 = new HexCoord(2, 0);
    Fixture plain = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    Fixture withShell = withShellHousehold(plain, shell, h3);

    assertThat(withShell.base().classes())
        .as("★ 前置：壳户真的在夹具里（人口 0 ∧ 有阶层归属 ⇒ 走 shell 那一支，不是'没登记'那一支）")
        .containsKey(shell);
    assertThat(withShell.base().classes().get(shell).population()).isZero();
    assertThat(withShell.base().classStandings()).containsKey(shell);

    ModeMigrationPolicy.MigrationPlan shellPlan = plan(withShell);
    assertThat(signature(plan(plain)))
        .as("★ 判别力自检：没有壳户的那份计划必须**非空**（否则 ② 的逐值相等是空洞地绿）")
        .isNotEmpty();
    assertThat(shellPlan.moves())
        .as("① 壳户不得是任何一笔的源户（人口 0 ⇒ 没有可迁的人）")
        .noneMatch(move -> move.source().equals(shell));
    assertThat(signature(shellPlan))
        .as("② 整份计划必须与没有壳户时**逐值相同**（跳壳户是纯剔除，不许改别人的落点/人口/速度）")
        .isEqualTo(signature(plan(plain)));
    assertThat(withShell.base().classes()).as("③ 计划是只读的：壳户仍在（不被清理、也不被改写）").containsKey(shell);
  }

  /** 计划的规范串（只取"这一笔是谁→谁、什么 mode、多少人、多快"四维，避免依赖 move 的记录序）。 */
  private static java.util.List<String> signature(ModeMigrationPolicy.MigrationPlan plan) {
    return plan.moves().stream()
        .map(
            move ->
                move.source().value()
                    + "->"
                    + move.target().value()
                    + "/"
                    + move.targetMode().value()
                    + "/"
                    + move.population()
                    + "/"
                    + move.transferSpeedPerMille())
        .sorted()
        .toList();
  }

  /** 在既有夹具上**加**一个壳户（人口 0、劳动 0、有阶层归属、位于第三个格 H3）：只改两份成员表，其余逐值不动。 */
  private static Fixture withShellHousehold(Fixture base, HouseholdId shell, HexCoord hex) {
    Map<HouseholdId, HouseholdEconomy> classes = new LinkedHashMap<>(base.base().classes());
    classes.put(shell, row(shell, hex, 0L, 0L, Map.of()));
    Map<HouseholdId, HouseholdClassMembership> standings =
        new LinkedHashMap<>(base.base().classStandings());
    ClassPositionId position = grainPosition();
    standings.put(
        shell,
        new HouseholdClassMembership(
            shell, position, position, Map.of(), 0L, 0L, "test-d024-shell"));
    return new Fixture(
        base.base().withHouseholdEconomies(classes).withClassMemberships(standings),
        base.definitions(),
        base.accounts(),
        base.topology(),
        base.marketTables(),
        base.sourceShareId(),
        base.targetShareId(),
        base.sourceUnitId());
  }

  /**
   * ★★★ <b>W9：`profitGapSpeedPerMille` 的期望值（测试侧独立复述算式，防止断言跟着实现一起漂）</b>。
   *
   * <pre>
   * gap ≤ 0 ⇒ 0
   * 否则    ⇒ clamp(⌊SLOPE × gap ÷ 分母⌋, 1, 1000)，SLOPE = 100（政策常量）
   * 分母（★★ W9-fix，2026-10-11）= max(|当前|, |其他|, 收益规模基准)；本类调两参形态 ⇒ 基准 = 0
   * </pre>
   *
   * <p>★ 旧口径 {@code max(1, |当前|)} 在 {@code 收益_当前 ≈ 0} 时退化成 1 ⇒ 打满 1000‰ ⇒ 一笔搬空整户 （W9d 实测
   * three-powers 21 个零成交轮）；W9-fix 把分母改成"三个非负量取大" ⇒ 不再需要"不许除零"补丁。 ★
   * 这里**不写死任何速度值**：期望值由夹具自己的两把读数按上面算式现算 ⇒ 换夹具即换期望，断言仍有效。
   */
  private static long expectedProfitGapSpeed(long currentPerLabor, long targetPerLabor) {
    long gap = targetPerLabor - currentPerLabor;
    if (gap <= 0L) {
      return 0L;
    }
    long slope = ModeMigrationPolicy.PROFIT_GAP_SPEED_SLOPE_PER_MILLE;
    if (slope <= 0L) {
      return 0L;
    }
    long denominator = Math.max(Math.abs(currentPerLabor), Math.abs(targetPerLabor));
    long speed = slope * gap / denominator;
    return Math.min(1000L, Math.max(1L, speed));
  }

  /**
   * ★★ <b>W9-fix 的人口闸（测试侧独立复述）：本户本轮可转人口 = min(速度折算, 上限折算)</b>。
   *
   * <pre>
   * 速度折算 = min(人口, max(1, ⌊人口 × 速度 ÷ 1000⌋))
   * 上限折算 = min(人口, max(1, ⌊人口 × MIGRATION_SPEED_CAP_PER_MILLE ÷ 1000⌋))
   * </pre>
   *
   * <p>★ 为什么在计划级用例里也要复述一遍：W9 首版这里是 {@code max(1, ⌊人口 × 速度 ÷ 1000⌋)}（**没有上限**）—— 速度 ≈ 1000‰
   * 时它等于整户人口。上限是 W9-fix 的第二个机制，必须与速度**分开**验（两栏在 `MIGRATION_MOVE` 里同时可读：速度报算式输出、人口报这条闸）。
   */
  private static long expectedMoveablePopulation(long population, long speedPerMille) {
    return Math.min(
        Math.min(population, Math.max(1L, population * speedPerMille / 1000L)),
        Math.min(
            population,
            Math.max(1L, population * ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE / 1000L)));
  }

  @Test
  void weightFormulaDirectionMatchesHandCalculation() {
    // A：current netPerLabor=44,500，target=88,600 ⇒ target − current > 0 ⇒ 有权重、有迁移。
    Fixture up = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    long currentUp = currentProspect(up).netPerLaborScaled();
    long targetUp = targetProspect(up, FAMILY).netPerLaborScaled();
    assertThat(currentUp).as("手算 current = 445 × 1e6 ÷ 10,000").isEqualTo(44_500L);
    assertThat(targetUp).as("手算 target = 886 × 1e6 ÷ 10,000").isEqualTo(88_600L);
    assertThat(targetUp - currentUp).as("★ 权重方向 target − current").isPositive();
    ModeMigrationPolicy.MigrationPlan upPlan = plan(up);
    assertThat(upPlan.moves())
        .as("★ 目标净收益更高 ⇒ 非空计划")
        .anyMatch(move -> move.source().equals(SOURCE));

    // B：current=88,600，target=43,600 ⇒ max(0, target − current)=0 ⇒ 无源户迁移。
    Fixture down = build(100L, 0L, 10_000L, 5_000L, 0L, false);
    long currentDown = currentProspect(down).netPerLaborScaled();
    long targetDown = targetProspect(down, FAMILY).netPerLaborScaled();
    assertThat(currentDown).as("手算 current = 886 × 1e6 ÷ 10,000").isEqualTo(88_600L);
    assertThat(targetDown).as("手算 target = 436 × 1e6 ÷ 10,000").isEqualTo(43_600L);
    assertThat(targetDown - currentDown).as("★ 方向反转后 target − current 为负").isNegative();
    ModeMigrationPolicy.MigrationPlan downPlan = plan(down);
    assertThat(downPlan.moves())
        .as("★ max(0, 负权重)=0 ⇒ 源户不得迁移（也不得反向匹配）")
        .noneMatch(move -> move.source().equals(SOURCE));
  }

  /**
   * ★★★ <b>W9 判据（用户原话④）：「债务规模决定<b>本生产方式内</b>阶层跌落速度，生产方式收益决定<b>生产方式间</b>转业速度」 ⇒ <b>债务很高、但「别的方式收益 ≤
   * 当前」⇒ 必须一笔转业都不产生</b></b>。
   *
   * <p>★ <b>负向（本条的主角）</b>：给源户挂 <b>50 亿毫</b>债务，收益差仍 ≤ 0 ⇒ 源户迁移笔数必须为 0。 旧口径（{@code max(10‰,
   * f(债务/资产))}，W4）下这笔债会把速度抬起来 ⇒ 现在抬高速度的那个输入已被整体移除。
   *
   * <p>★ <b>正对照（必须有，否则"没迁移"可能只是因为整个世界没动静）</b>：同一个夹具把两格需求对调 ⇒ 收益差 &gt; 0 ⇒ 有转业；
   * 且**加上同一笔巨债后计划逐值不变**（目标/人口/速度/档位全同，只有债务分摊那一栏从 0 变成正数） —— 这就是"债务不是转业速度的输入"的可执行证据。
   */
  @Test
  void w9HighDebtDoesNotMigrateWhenTheProfitGapIsNonPositive() {
    // 负向世界：H1 需求 10,000 / H2 需求 5,000 ⇒ 当前(H1) 高于目标(H2) ⇒ 收益差 ≤ 0。
    Fixture down = build(100L, 0L, 10_000L, 5_000L, 0L, false);
    long downCurrent = currentProspect(down).netPerLaborScaled();
    long downTarget = targetProspect(down, FAMILY).netPerLaborScaled();
    assertThat(downTarget).as("★ 前置：负向世界的收益差必须 ≤ 0（否则这条用例没有判别力）").isLessThanOrEqualTo(downCurrent);
    assertThat(sourceMoves(plan(down))).as("无债务：收益差 ≤ 0 ⇒ 源户不转业").isEmpty();
    Fixture downWithDebt = withSourceDebt(down, 5_000_000_000L);
    assertThat(sourceMoves(plan(downWithDebt)))
        .as("★ 债务 50 亿毫（「债务很高」）∧ 收益差 ≤ 0 ⇒ 仍然一笔转业都没有（债务不再是转业速度的输入）")
        .isEmpty();

    // 正对照：同一个夹具把需求对调 ⇒ 收益差 > 0。
    Fixture up = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    assertThat(targetProspect(up, FAMILY).netPerLaborScaled())
        .as("★ 正对照前置：收益差 > 0")
        .isGreaterThan(currentProspect(up).netPerLaborScaled());
    List<ModeMigrationPolicy.MigrationMove> upMoves = sourceMoves(plan(up));
    assertThat(upMoves).as("★ 正对照：收益差 > 0 ⇒ 源户真的转业").isNotEmpty();
    Fixture upWithDebt = withSourceDebt(up, 5_000_000_000L);
    List<ModeMigrationPolicy.MigrationMove> upDebtMoves = sourceMoves(plan(upWithDebt));
    assertThat(planSignature(upDebtMoves))
        .as("★ 债务不改变计划：目标格/人口/速度/档位逐值相同（只有债务分摊那一栏不同）")
        .isEqualTo(planSignature(upMoves));
    assertThat(upDebtMoves)
        .as("★ 而那笔债确实被分摊进计划（不是被静默丢掉）")
        .allSatisfy(move -> assertThat(move.debtMilli()).isPositive());
  }

  /** 源户的那些迁移笔（本类的判据几乎都只看源户）。 */
  private static List<ModeMigrationPolicy.MigrationMove> sourceMoves(
      ModeMigrationPolicy.MigrationPlan plan) {
    return plan.moves().stream().filter(move -> move.source().equals(SOURCE)).toList();
  }

  /** 计划的**逐值签名**（把"债务以外的一切"压成可比字符串 —— 用于"债务不改变计划"那条断言）。 */
  private static List<String> planSignature(List<ModeMigrationPolicy.MigrationMove> moves) {
    return moves.stream()
        .map(
            move ->
                move.source().value()
                    + "->"
                    + move.target().value()
                    + "@"
                    + move.targetHex().q()
                    + ","
                    + move.targetHex().r()
                    + ":"
                    + move.targetMode().value()
                    + ":pop="
                    + move.population()
                    + ":speed="
                    + move.transferSpeedPerMille()
                    + ":tier="
                    + move.targetTierIndex()
                    + ":reason="
                    + move.reason())
        .toList();
  }

  /**
   * ★★ <b>W9：给夹具的源户挂一笔债务</b>（"债务很高"的负向用例要它）。
   *
   * <p>★ 公开前置：只换 {@code debtContracts} 一个组件，其余（账户/拓扑/价表/份额 id）逐值带过 —— 这样"加了债"与"没加债"
   * 两个世界只差那一笔债，因果是干净的。
   */
  private static Fixture withSourceDebt(Fixture fixture, long principalMilli) {
    DebtContract contract =
        new DebtContract(
            DebtContractId.idOf(SOURCE, BUYER2, DebtUnit.money(SILVER), DebtTerms.legacyDefault()),
            SOURCE,
            BUYER2,
            new DebtUnit.Money(SILVER),
            DebtTerms.legacyDefault(),
            DebtSource.ORDINARY_LOAN,
            principalMilli,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    return new Fixture(
        fixture.base().withDebtContracts(Map.of(contract.id(), contract)),
        fixture.definitions(),
        fixture.accounts(),
        fixture.topology(),
        fixture.marketTables(),
        fixture.sourceShareId(),
        fixture.targetShareId(),
        fixture.sourceUnitId());
  }

  @Test
  void aRuleUsesExpectedProfitNotRealBook() {
    // 预期亏损 + 流动性 < 下一周期投入 ⇒ A 规则 1000‰。
    Fixture loss = build(1L, 5_000_000L, 5_000L, 10_000L, 0L, false);
    ExpectedProfitBook.Prospect current = currentProspect(loss);
    assertThat(current.netMilli()).as("预期净收益 <0").isNegative();
    assertThat(current.inputCostMilli()).as("下一周期投入").isPositive();
    ModeMigrationPolicy.MigrationPlan lossPlan = plan(loss);
    List<ModeMigrationPolicy.MigrationMove> sourceMoves =
        lossPlan.moves().stream().filter(move -> move.source().equals(SOURCE)).toList();
    assertThat(sourceMoves).isNotEmpty();
    assertThat(sourceMoves)
        .allSatisfy(
            move -> {
              assertThat(move.transferSpeedPerMille()).isEqualTo(1000L);
              assertThat(move.reason())
                  .isEqualTo(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED);
            });
    assertThat(sourceMoves.stream().mapToLong(ModeMigrationPolicy.MigrationMove::population).sum())
        .as("A 规则把源户 100 人全部转出")
        .isEqualTo(100L);

    // 有足够流动性 ⇒ A 规则不触发，只走**收益差驱动**的转业速度（W9：常量基线已删）。
    Fixture liquid = build(1L, 5_000_000L, 5_000L, 10_000L, 1_000_000L, false);
    long liquidCurrent = currentProspect(liquid).netPerLaborScaled();
    long liquidTarget = targetProspect(liquid, FAMILY).netPerLaborScaled();
    long liquidExpectedSpeed = expectedProfitGapSpeed(liquidCurrent, liquidTarget);
    assertThat(liquidExpectedSpeed)
        .as("★ 前置：这一支必须真有正收益差（否则下面的 allSatisfy 空洞地绿、断言失去判别力）")
        .isPositive()
        .isLessThan(1000L);
    ModeMigrationPolicy.MigrationPlan liquidPlan = plan(liquid);
    assertThat(liquidPlan.moves())
        .as("★ 流动性足够 ⇒ 不得出现 1000‰ A 规则迁移")
        .noneMatch(
            move ->
                move.source().equals(SOURCE)
                    && move.transferSpeedPerMille() == 1000L
                    && move.reason()
                        .equals(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED));
    List<ModeMigrationPolicy.MigrationMove> liquidSourceMoves =
        liquidPlan.moves().stream().filter(move -> move.source().equals(SOURCE)).toList();
    assertThat(liquidSourceMoves).as("★ 非空（原断言用 allSatisfy，空集也会绿 —— W9 起改成先钉非空）").isNotEmpty();
    assertThat(liquidSourceMoves)
        .allSatisfy(
            move ->
                assertThat(move.transferSpeedPerMille())
                    .as("★ W9：速度 = 收益差驱动（不再是 10‰ 常量基线）")
                    .isEqualTo(liquidExpectedSpeed));

    // 预期净收益 >=0 ⇒ A 规则条件的第一项就不满足。
    Fixture profitable = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    assertThat(currentProspect(profitable).netMilli()).isNotNegative();
    ModeMigrationPolicy.MigrationPlan profitablePlan = plan(profitable);
    assertThat(profitablePlan.moves())
        .as("★ 预期净收益非负 ⇒ 即使流动性为 0 也不触发 A 规则")
        .noneMatch(
            move ->
                move.source().equals(SOURCE)
                    && move.reason()
                        .equals(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED));
  }

  @Test
  void planAndExecutionKeepSourceModeStandingOrganizationAndUnitModeKey() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, true);
    EconomyData before = fixture.base();
    HouseholdClassMembership beforeStanding = before.classStandings().get(SOURCE);
    ProductionEnterprise beforeOrganization =
        before.productionOrganizations().values().stream()
            .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
            .findFirst()
            .orElseThrow();
    ProductionProcess beforeUnit = fixture.definitions().instances().get(fixture.sourceUnitId());

    ModeMigrationPolicy.MigrationPlan plan = plan(fixture);
    assertThat(plan.moves()).as("基线迁移计划非空").isNotEmpty();

    // 计划是纯函数：调用后 base 的源户身份字段逐值不变。
    assertThat(before.classStandings().get(SOURCE)).isEqualTo(beforeStanding);
    assertThat(before.classPositions().get(beforeStanding.currentPositionId()).modeId())
        .isEqualTo(DefaultProductionModes.GRAIN_FARM);
    assertThat(
            before.productionOrganizations().values().stream()
                .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
                .findFirst()
                .orElseThrow()
                .modeId())
        .isEqualTo(DefaultProductionModes.GRAIN_FARM);
    assertThat(fixture.definitions().instances().get(fixture.sourceUnitId()).modeKey())
        .isEqualTo("mode:grain_farm");

    // 执行：源户只缩编、不被原地改成目标 mode；org/unit 身份字段不变。
    // ★★★ D-032 T1：实例读数改读 production 的**运行时**（唯一权威）——把 runtime 取到手上，
    //   好在推进之后读 unit.modeKey（`EconomyData.units()` 已随迁移删除）。
    io.mosire.simos.production.contract.ProductionRuntime runtime =
        runtimeOf(fixture.definitions());
    EconomySession session = new EconomySession(before, fixture.definitions(), runtime);
    session
        .flows()
        .put(
            SOURCE,
            new FlowRow(
                SOURCE, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, 0L, Map.of(),
                Map.of()));
    ModeMigrationSettlement.apply(
        session,
        fixture.accounts(),
        plan,
        EconomyFixtures.dayView(before, fixture.definitions()),
        10L);
    // ★★ P0（2026-10-10）：经济域**不写** householdEconomies 的 population/laborMilli —— 每笔 move 成功后
    //    只在会话 outbox 里留一条人口事实；由 App 翻译成 Social 工单、再把 Social 真值 delta 回写经济行。
    List<EconomyPopulationTransfer> transfers = session.drainPendingPopulationTransfers();
    assertThat(transfers).as("P0：成功迁移必须留下人口 outbox").isNotEmpty();
    Map<HouseholdId, Long> deltas = new LinkedHashMap<>();
    for (EconomyPopulationTransfer transfer : transfers) {
      deltas.merge(transfer.source(), -transfer.population(), Long::sum);
      deltas.merge(transfer.target(), transfer.population(), Long::sum);
    }
    assertThat(session.preview().classes().get(SOURCE).population())
        .as("P0：App 回写前，经济行人口一字未改")
        .isEqualTo(100L);
    // 模拟 App 的"Social 真值 delta 回写经济行"那一半（本测试的 Social 等价物 = outbox 本身）。
    EconomySettlement.applyHouseholdPopulationDeltasInto(session, deltas);
    EconomyData after = session.preview();

    assertThat(after.classStandings().get(SOURCE))
        .as("★ D-022：执行后源户 HouseholdClassMembership 逐值不变")
        .isEqualTo(beforeStanding);
    assertThat(
            after
                .classPositions()
                .get(after.classStandings().get(SOURCE).currentPositionId())
                .modeId())
        .as("★ D-022：源户当前 position 仍指向 grain_farm")
        .isEqualTo(DefaultProductionModes.GRAIN_FARM);
    ProductionEnterprise afterOrganization =
        after.productionOrganizations().values().stream()
            .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
            .findFirst()
            .orElseThrow();
    assertThat(afterOrganization.modeId())
        .as("★ D-022：源户 organization.modeId 不变")
        .isEqualTo(beforeOrganization.modeId());
    assertThat(runtime.units().get(fixture.sourceUnitId()).modeKey())
        .as("★ D-022：源户 unit.modeKey 不变")
        .isEqualTo(beforeUnit.modeKey());
    // ★ W9（2026-10-11）：迁出人口不再写死 1 人 —— 速度改成**收益差驱动**之后，可迁人口 =
    //   min(⌊人口 × 速度 ÷ 1000⌋, ⌊人口 × 上限 ÷ 1000⌋)（★ W9-fix 加的第二个机制 = 人口闸
    //   `MIGRATION_SPEED_CAP_PER_MILLE`），speed 由这一对 (当前, 目标) 的预期收益差现算。
    //   ⇒ 期望值从**测试侧独立复述的算式**推（不写死数字，也不抄 main 的输出）：
    //     ① 计划里源户那一笔的总人口 == expectedMoveablePopulation(人口, 速度)；
    //     ② outbox 回写后源户人口 == 100 − 那一笔的总人口（守恒，P0 口径）。
    long plannedMove =
        plan.moves().stream()
            .filter(move -> move.source().equals(SOURCE))
            .mapToLong(ModeMigrationPolicy.MigrationMove::population)
            .sum();
    long expectedSpeed =
        expectedProfitGapSpeed(
            currentProspect(fixture).netPerLaborScaled(),
            targetProspect(fixture, FAMILY).netPerLaborScaled());
    assertThat(plannedMove)
        .as(
            "★ W9-fix 可迁人口 = min(速度折算, 上限折算)（人口 100、速度 %d‰ ⇒ %d 人；不再是常量基线，也不许整户迁空）",
            expectedSpeed, expectedMoveablePopulation(100L, expectedSpeed))
        .isEqualTo(expectedMoveablePopulation(100L, expectedSpeed));
    assertThat(after.classes().get(SOURCE).population())
        .as("★ 源户缩编量 == 计划里那一笔（P0：outbox 回写后），源户仍在")
        .isEqualTo(100L - plannedMove)
        .isPositive();
  }

  /**
   * ★★ <b>D-024 修复 1 端到端回归（过期快照）</b>：H2 目标产业的 WORKSHOP 份额由 {@link #ESTATE} 自营
   * （owner==operator），tick0 快照 {@code base.productionOrganizations()} 为空，但当天工作副本里有一条既有组织
   * 正在使用该份额。claimed 必须从工作副本算，H2 候选才不可行；若退回 base 快照重算（空），旧的"可抢占"行为会让 计划把源户迁往 H2。
   */
  @Test
  void workingCopyClaimedSetStopsStaleSnapshotFromRentingOrganizationAssets() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, false, true);
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：tick0 快照 organizations 为空（旧实现重算 claimed 的输入）")
        .isEmpty();

    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            DefaultProductionModes.GRAIN_FARM,
            grainPosition(),
            SOURCE,
            IndustryHexKeys.hexKey(H2.q(), H2.r()));
    Map<ProductionOrganizationId, ProductionEnterprise> workingCopy = new LinkedHashMap<>();
    workingCopy.put(
        organizationId,
        new ProductionEnterprise(
            organizationId,
            DefaultProductionModes.GRAIN_FARM,
            grainPosition(),
            Optional.empty(),
            ESTATE,
            List.of(),
            List.of(fixture.targetShareId()),
            List.of(),
            new Payee.ToActor(ESTATE),
            Optional.of("fixture:estate-claim"),
            ProductionEnterprise.Status.SHORTAGE,
            "夹具：ESTATE 自营份额已被组织使用"));

    Set<AssetShareId> fromWorkingCopy = claimedFrom(fixture, workingCopy);
    Set<AssetShareId> fromStaleBase =
        claimedFrom(fixture, fixture.base().productionOrganizations());
    assertThat(fromWorkingCopy).contains(fixture.targetShareId());
    assertThat(fromStaleBase).doesNotContain(fixture.targetShareId());

    // 候选层：工作副本 claimed ⇒ H2 目标不可行；空 claimed ⇒ 可行（正对照）。
    ExpectedProfitBook.Prospect claimedTarget = targetProspect(fixture, FAMILY, workingCopy);
    assertThat(claimedTarget.feasible()).as("★ 工作副本 claimed ⇒ H2 候选 NO_ASSET").isFalse();
    assertThat(claimedTarget.reason()).startsWith("NO_ASSET");
    ExpectedProfitBook.Prospect idleTarget = targetProspect(fixture, FAMILY, Map.of());
    assertThat(idleTarget.feasible()).as("正对照：无人引用 ⇒ ESTATE 份额算同格闲置，H2 候选可行").isTrue();

    // 计划层：仅当 claimed 退回 base 快照（空）时才会出现迁往 H2 的计划项。
    ModeMigrationPolicy.MigrationPlan claimedPlan = plan(fixture, workingCopy);
    assertThat(claimedPlan.moves())
        .as("★ 工作副本 claimed ⇒ 不得把源户迁往 H2（ESTATE 份额不是闲置）")
        .noneMatch(move -> move.targetHex().equals(H2));
    ModeMigrationPolicy.MigrationPlan stalePlan =
        plan(fixture, workingCopy(fixture.base().productionOrganizations()));
    assertThat(stalePlan.moves())
        .as("★ 区分对照：若从 base 快照重算 claimed（空）⇒ 回到旧的'可抢占'，H2 目标重新出现")
        .anyMatch(move -> move.targetHex().equals(H2));
  }

  /** ★★ D-032 B2b/B3：会话/日推进器必须显式拿到 production 运行时（{@code runtime=null} 的旧构造器已删）。 */
  private static io.mosire.simos.production.contract.ProductionRuntime runtimeOf(
      io.mosire.simos.production.ProductionData definitions) {
    return new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
  }
}
