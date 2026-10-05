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
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.FlowRow;
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
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ORGANIZATION, TARGET_INDUSTRY.value());

  private record Fixture(
      EconomyData base,
      AccountSession accounts,
      MarketTopology topology,
      Map<HexCoord, Market> markets,
      AssetShareId sourceShareId,
      AssetShareId targetShareId,
      ProductionUnitId sourceUnitId) {}

  private static Market market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    return new Market(SILVER, prices);
  }

  private static Industry industry(IndustryId id, String label, long outputUnits, long inputMilli) {
    Map<io.mosire.simos.actor.api.asset.AssetKind, Map<CommodityId, Long>> cycleInput =
        inputMilli <= 0L
            ? Map.of()
            : Map.of(AssetKind.WORKSHOP, Map.of(GRAIN, inputMilli));
    return new Industry(
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

  private static ClassRow row(
      HouseholdId id, HexCoord hex, long population, long laborMilli, Map<CommodityId, Long> needs) {
    return new ClassRow(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        laborMilli,
        1000,
        0L,
        List.of(),
        needs,
        Map.of(),
        0L);
  }

  private static ClassPositionId familyPosition() {
    return DefaultProductionModes
        .positionId(DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
        .orElseThrow();
  }

  /**
   * @param sourceOutputUnits 源户当前产业每规模产出（亏损用例填 1）
   * @param sourceInputMilli 源户当前产业每规模 GRAIN 投入（毫；亏损用例填 5,000,000）
   * @param buyer1NeedPerDay H1 额外买方日需求（毫 GRAIN/日）
   * @param buyer2NeedPerDay H2 买方日需求（毫 GRAIN/日）
   * @param sourceMoney 源户银余额（毫；控制 A 规则流动性）
   * @param sourceHasOrganization 是否给源户挂 ProductionOrganization（D-022 执行用例需要）
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
    ClassPositionId position = familyPosition();
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    classes.put(
        SOURCE,
        row(SOURCE, H1, 100L, 1_000L, Map.of(GRAIN, 100L)));
    classes.put(
        BUYER1, row(BUYER1, H1, 100L, 0L, Map.of(GRAIN, buyer1NeedPerDay)));
    classes.put(
        BUYER2, row(BUYER2, H2, 100L, 0L, Map.of(GRAIN, buyer2NeedPerDay)));

    Map<HouseholdId, ClassStanding> standings = new LinkedHashMap<>();
    for (HouseholdId household : List.of(SOURCE, BUYER1, BUYER2)) {
      standings.put(
          household,
          new ClassStanding(household, position, position, Map.of(), 0L, 0L, "test-d024"));
    }

    Industry sourceIndustry = industry(SOURCE_INDUSTRY, "source-industry", sourceOutputUnits, sourceInputMilli);
    Industry targetIndustry = industry(TARGET_INDUSTRY, "target-industry", 100L, 0L);
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(SOURCE_INDUSTRY, sourceIndustry);
    industries.put(TARGET_INDUSTRY, targetIndustry);

    ActorRef sourceActor = HouseholdActors.of(SOURCE);
    AssetShareId h1ShareId =
        AssetShare.idOf(
            SOURCE_INDUSTRY, AssetKind.WORKSHOP, sourceActor, sourceActor, AssetShare.RightKind.OWNED, 0L);
    AssetShare h1Share =
        new AssetShare(
            h1ShareId, SOURCE_INDUSTRY, AssetKind.WORKSHOP, sourceActor, sourceActor, 1L,
            AssetShare.RightKind.OWNED);
    ActorRef targetShareOwner = targetShareOwnedByEstate ? ESTATE : sourceActor;
    AssetShareId h2ShareId =
        AssetShare.idOf(
            TARGET_INDUSTRY,
            AssetKind.WORKSHOP,
            targetShareOwner,
            targetShareOwner,
            AssetShare.RightKind.OWNED,
            0L);
    AssetShare h2Share =
        new AssetShare(
            h2ShareId, TARGET_INDUSTRY, AssetKind.WORKSHOP, targetShareOwner, targetShareOwner, 100L,
            AssetShare.RightKind.OWNED);
    Map<AssetShareId, AssetShare> shares = new LinkedHashMap<>();
    shares.put(h1ShareId, h1Share);
    shares.put(h2ShareId, h2Share);

    ProductionUnitId sourceUnitId = ProductionUnitId.idOf(SOURCE_INDUSTRY, sourceActor);
    ProductionUnit sourceUnit =
        new ProductionUnit(
            sourceUnitId, SOURCE_INDUSTRY, sourceActor, "mode:family_farm", 0L, 0L, Map.of());
    ProductionRelation sourceRelation =
        new ProductionRelation(
            sourceUnitId, sourceActor, new Recipient.ToActor(sourceActor), List.of(), sourceActor);

    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H1, market());
    markets.put(H2, market());

    EconomyData base =
        EconomyData.empty()
            .withModes(DefaultProductionModes.modes())
            .withClassStructures(DefaultProductionModes.classStructures())
            .withClassPositions(DefaultProductionModes.classPositions())
            .withClasses(classes)
            .withClassStandings(standings)
            .withIndustries(industries)
            .withUnits(Map.of(sourceUnitId, sourceUnit))
            .withRelations(Map.of(sourceUnitId, sourceRelation))
            .withAssetShares(shares)
            .withMarkets(markets);
    if (sourceHasOrganization) {
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.idOf(
              DefaultProductionModes.FAMILY_FARM,
              position,
              SOURCE,
              IndustryHexKeys.hexKey(H1.q(), H1.r()));
      base =
          base.withProductionOrganizations(
              Map.of(
                  organizationId,
                  new ProductionOrganization(
                      organizationId,
                      DefaultProductionModes.FAMILY_FARM,
                      position,
                      Optional.of(sourceUnitId),
                      sourceActor,
                      List.of(SOURCE),
                      List.of(h1ShareId),
                      List.of(new Recipient.ToActor(sourceActor)),
                      new Recipient.ToActor(sourceActor),
                      Optional.of("fixture:d024"),
                      ProductionOrganization.Status.ACTIVE,
                      "")));
    }

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(SOURCE, H1, Map.of(),
        sourceMoney <= 0L ? Map.of() : Map.of(SILVER, sourceMoney),
        Map.of(),
        Map.of());
    accounts.registerHousehold(BUYER1, H1, Map.of(), Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(BUYER2, H2, Map.of(), Map.of(), Map.of(), Map.of());

    return new Fixture(
        base, accounts, MarketTopology.singleHex(markets), markets, h1ShareId, h2ShareId, sourceUnitId);
  }

  /**
   * ★★ claimed 的唯一正确来源是调用方传入的**当天工作副本**（{@code organizations + units + assetShares}）：
   * 生产路径 {@code plan} 内部走三参 {@link ModeMigrationPolicy#claimedAssetShares(Map, Map, Map)}。
   * 这里显式复制一份组织工作副本，绝不把 {@code base} 的 tick0 快照当工作副本递进去；unit 占用那一格也按
   * 当天 units/shares 一并传入。
   */
  private static Map<ProductionOrganizationId, ProductionOrganization> workingCopy(
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return new LinkedHashMap<>(organizations);
  }

  private static Set<AssetShareId> claimedFrom(
      Fixture fixture, Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return ModeMigrationPolicy.claimedAssetShares(
        organizations, fixture.base().units(), fixture.base().assetShares());
  }

  private static ModeMigrationPolicy.MigrationPlan plan(Fixture fixture) {
    return plan(fixture, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ModeMigrationPolicy.MigrationPlan plan(
      Fixture fixture, Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return ModeMigrationPolicy.plan(
        fixture.base(),
        organizations,
        fixture.base().units(),
        fixture.base().classes(),
        fixture.base().classStandings(),
        fixture.base().assetShares(),
        fixture.base().relations(),
        Map.of(),
        fixture.markets(),
        fixture.base().debtContracts(),
        fixture.accounts(),
        new OrganizationProfitBook.Book(Map.of(), Map.of(), Map.of()),
        10L,
        fixture.topology(),
        List.of());
  }

  private static ExpectedProfitBook.Prospect currentProspect(Fixture fixture) {
    return currentProspect(fixture, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ExpectedProfitBook.Prospect currentProspect(
      Fixture fixture, Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return ExpectedProfitBook.prospect(
        fixture.base(),
        SOURCE,
        DefaultProductionModes.FAMILY_FARM,
        H1,
        fixture.base().industries().get(SOURCE_INDUSTRY),
        fixture.markets().get(H1),
        demandOf(fixture),
        fixture.base().assetShares(),
        claimedFrom(fixture, organizations),
        fixture.accounts(),
        fixture.base().units(),
        fixture.base().relations(),
        fixture.topology(),
        10L);
  }

  private static ExpectedProfitBook.Prospect targetProspect(Fixture fixture, ProductionModeId mode) {
    return targetProspect(fixture, mode, workingCopy(fixture.base().productionOrganizations()));
  }

  private static ExpectedProfitBook.Prospect targetProspect(
      Fixture fixture,
      ProductionModeId mode,
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    return ExpectedProfitBook.prospect(
        fixture.base(),
        SOURCE,
        mode,
        H2,
        fixture.base().industries().get(TARGET_INDUSTRY),
        fixture.markets().get(H2),
        demandOf(fixture),
        fixture.base().assetShares(),
        claimedFrom(fixture, organizations),
        fixture.accounts(),
        fixture.base().units(),
        fixture.base().relations(),
        fixture.topology(),
        10L);
  }

  private static MarketDemandBook.Book demandOf(Fixture fixture) {
    return MarketDemandBook.build(
        List.of(),
        fixture.topology(),
        fixture.base().classes(),
        fixture.base().units(),
        fixture.base().industries(),
        fixture.base().assetShares(),
        fixture.base().shipments(),
        fixture.accounts(),
        fixture.markets(),
        10L,
        10L);
  }

  private static final ProductionModeId FAMILY = DefaultProductionModes.FAMILY_FARM;

  @Test
  void hasReadingFalseButPositiveExpectedCandidateYieldsPlan() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：没有任何既有组织 ⇒ hasReading=false")
        .isEmpty();

    ExpectedProfitBook.Prospect target = targetProspect(fixture, FAMILY);
    assertThat(target.feasible()).isTrue();
    assertThat(target.netPerLaborScaled()).as("★ 预期正收益候选").isPositive();

    ModeMigrationPolicy.MigrationPlan plan = plan(fixture);
    assertThat(plan.moves()).as("★ D-024：没有既存读数也能产出非空迁移计划").isNotEmpty();
    assertThat(plan.moves())
        .allSatisfy(
            move -> {
              assertThat(move.source()).isEqualTo(SOURCE);
              assertThat(move.target()).isNotEqualTo(move.source());
              assertThat(move.transferSpeedPerMille()).isEqualTo(ModeMigrationPolicy.MIGRATION_PER_MILLE);
              assertThat(move.reason())
                  .isEqualTo(ModeMigrationPolicy.MigrationMove.REASON_PROFIT_WEIGHTED);
            });
    assertThat(plan.moves().stream().mapToLong(ModeMigrationPolicy.MigrationMove::population).sum())
        .as("基线迁移人口 = max(1, 100 × 10 ÷ 1000) = 1")
        .isEqualTo(1L);
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

    // 有足够流动性 ⇒ A 规则不触发，只走 10‰ 基线（或没有更高目标）。
    Fixture liquid = build(1L, 5_000_000L, 5_000L, 10_000L, 1_000_000L, false);
    ModeMigrationPolicy.MigrationPlan liquidPlan = plan(liquid);
    assertThat(liquidPlan.moves())
        .as("★ 流动性足够 ⇒ 不得出现 1000‰ A 规则迁移")
        .noneMatch(
            move ->
                move.source().equals(SOURCE)
                    && move.transferSpeedPerMille() == 1000L
                    && move.reason().equals(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED));
    assertThat(liquidPlan.moves())
        .as("但目标预期更高 ⇒ 仍可走 10‰ 基线")
        .allSatisfy(
            move -> {
              if (move.source().equals(SOURCE)) {
                assertThat(move.transferSpeedPerMille())
                    .isEqualTo(ModeMigrationPolicy.MIGRATION_PER_MILLE);
              }
            });

    // 预期净收益 >=0 ⇒ A 规则条件的第一项就不满足。
    Fixture profitable = build(100L, 0L, 5_000L, 10_000L, 0L, false);
    assertThat(currentProspect(profitable).netMilli()).isNotNegative();
    ModeMigrationPolicy.MigrationPlan profitablePlan = plan(profitable);
    assertThat(profitablePlan.moves())
        .as("★ 预期净收益非负 ⇒ 即使流动性为 0 也不触发 A 规则")
        .noneMatch(
            move ->
                move.source().equals(SOURCE)
                    && move.reason().equals(ModeMigrationPolicy.MigrationMove.REASON_A_RULE_MAX_SPEED));
  }

  @Test
  void planAndExecutionKeepSourceModeStandingOrganizationAndUnitModeKey() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, true);
    EconomyData before = fixture.base();
    ClassStanding beforeStanding = before.classStandings().get(SOURCE);
    ProductionOrganization beforeOrganization =
        before.productionOrganizations().values().stream()
            .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
            .findFirst()
            .orElseThrow();
    ProductionUnit beforeUnit = before.units().get(fixture.sourceUnitId());

    ModeMigrationPolicy.MigrationPlan plan = plan(fixture);
    assertThat(plan.moves()).as("基线迁移计划非空").isNotEmpty();

    // 计划是纯函数：调用后 base 的源户身份字段逐值不变。
    assertThat(before.classStandings().get(SOURCE)).isEqualTo(beforeStanding);
    assertThat(before.classPositions().get(beforeStanding.currentPositionId()).modeId())
        .isEqualTo(DefaultProductionModes.FAMILY_FARM);
    assertThat(before.productionOrganizations().values().stream()
            .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
            .findFirst()
            .orElseThrow()
            .modeId())
        .isEqualTo(DefaultProductionModes.FAMILY_FARM);
    assertThat(before.units().get(fixture.sourceUnitId()).modeKey()).isEqualTo("mode:family_farm");

    // 执行：源户只缩编、不被原地改成目标 mode；org/unit 身份字段不变。
    EconomySession session = new EconomySession(before);
    session
        .flows()
        .put(
            SOURCE,
            new FlowRow(
                SOURCE,
                Map.of(),
                Map.of(),
                0L,
                0L,
                0L,
                0L,
                0L,
                Map.of(),
                0L,
                0L,
                Map.of(),
                Map.of()));
    ModeMigrationSettlement.apply(session, fixture.accounts(), plan, before, 10L);
    EconomyData after = session.preview();

    assertThat(after.classStandings().get(SOURCE))
        .as("★ D-022：执行后源户 ClassStanding 逐值不变")
        .isEqualTo(beforeStanding);
    assertThat(after.classPositions().get(after.classStandings().get(SOURCE).currentPositionId()).modeId())
        .as("★ D-022：源户当前 position 仍指向 family_farm")
        .isEqualTo(DefaultProductionModes.FAMILY_FARM);
    ProductionOrganization afterOrganization =
        after.productionOrganizations().values().stream()
            .filter(org -> org.organizer().equals(HouseholdActors.of(SOURCE)))
            .findFirst()
            .orElseThrow();
    assertThat(afterOrganization.modeId())
        .as("★ D-022：源户 organization.modeId 不变")
        .isEqualTo(beforeOrganization.modeId());
    assertThat(after.units().get(fixture.sourceUnitId()).modeKey())
        .as("★ D-022：源户 unit.modeKey 不变")
        .isEqualTo(beforeUnit.modeKey());
    assertThat(after.classes().get(SOURCE).population())
        .as("基线迁移只缩编 1 人，源户仍在")
        .isEqualTo(99L);
  }

  /**
   * ★★ <b>D-024 修复 1 端到端回归（过期快照）</b>：H2 目标产业的 WORKSHOP 份额由 {@link #ESTATE} 自营
   * （owner==operator），tick0 快照 {@code base.productionOrganizations()} 为空，但当天工作副本里有一条既有组织
   * 正在使用该份额。claimed 必须从工作副本算，H2 候选才不可行；若退回 base 快照重算（空），旧的"可抢占"行为会让
   * 计划把源户迁往 H2。
   */
  @Test
  void workingCopyClaimedSetStopsStaleSnapshotFromRentingOrganizationAssets() {
    Fixture fixture = build(100L, 0L, 5_000L, 10_000L, 0L, false, true);
    assertThat(fixture.base().productionOrganizations())
        .as("夹具：tick0 快照 organizations 为空（旧实现重算 claimed 的输入）")
        .isEmpty();

    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            DefaultProductionModes.FAMILY_FARM,
            familyPosition(),
            SOURCE,
            IndustryHexKeys.hexKey(H2.q(), H2.r()));
    Map<ProductionOrganizationId, ProductionOrganization> workingCopy = new LinkedHashMap<>();
    workingCopy.put(
        organizationId,
        new ProductionOrganization(
            organizationId,
            DefaultProductionModes.FAMILY_FARM,
            familyPosition(),
            Optional.empty(),
            ESTATE,
            List.of(),
            List.of(fixture.targetShareId()),
            List.of(),
            new Recipient.ToActor(ESTATE),
            Optional.of("fixture:estate-claim"),
            ProductionOrganization.Status.SHORTAGE,
            "夹具：ESTATE 自营份额已被组织使用"));

    Set<AssetShareId> fromWorkingCopy = claimedFrom(fixture, workingCopy);
    Set<AssetShareId> fromStaleBase = claimedFrom(fixture, fixture.base().productionOrganizations());
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
}
