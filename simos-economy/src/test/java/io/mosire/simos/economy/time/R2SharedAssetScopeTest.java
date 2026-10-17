package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>R2 共享资产 scope 最小修复的独立验收载体</b>（D-032 R2；约束设计书 {@code
 * 2026-10-17-d032-r2-shared-asset-minimal-design.md} §4）。
 *
 * <p>★★ <b>判据（逐条对应 §4.1/§4.2，不按实现反推）</b>：
 *
 * <table border="1">
 *   <caption>判据 → 本类载体</caption>
 *   <tr><th>#</th><th>判据</th><th>载体</th></tr>
 *   <tr><td>P1</td><td>单 unit、LAND=100 ⇒ 该 unit 得到 100（与改前逐值相同）</td><td>{@link #singleUnitKeepsTheWholeOwnedPool()}</td></tr>
 *   <tr><td>P2</td><td>同 scope 两 unit、LAND=100 ⇒ 两边 allocations 空、unallocated=100、都具名拒绝，不再 100+100</td><td>{@link #twoUnitsInTheSameScopeNeverSeeThePoolTwice()}</td></tr>
 *   <tr><td>P3</td><td>两 unit 不同 (industry, operator)，各有 100 ⇒ 各得 100</td><td>{@link #differentScopesEachKeepTheirOwnPool()}</td></tr>
 *   <tr><td>P4</td><td>共享 scope 的预计/排队：无可生产 offer、无劳动分配</td><td>{@link #sharedScopeOfferIsNamedInfeasibleAndGetsNoLabor()}</td></tr>
 *   <tr><td>P4</td><td>共享 scope 的预计（ExpectedProfitBook）不把整池复制给现有 unit</td><td>{@link #sharedScopeExistingUnitProspectIsNamedInfeasible()}</td></tr>
 *   <tr><td>P5</td><td>共享 scope 的实际执行：无产出/进度/经验/实际劳动/周期投入</td><td>{@link #sharedScopeUnitsAreRejectedBeforeAnyProductionWrite()}</td></tr>
 *   <tr><td>P7</td><td>同输入同输出、稳定序、纯函数可复算</td><td>{@link #assetBudgetIsAPureStableDerivation()}</td></tr>
 * </table>
 *
 * <p>★★ <b>本类不宣称 R2 已完整关闭</b>：多实例真实分配（实例/地点占用、占用期、释放）仍归 D4/A；共享 scope 的 具名理由 {@code
 * asset-scope-shared:awaiting-D4} 就是这条边界的读口。
 */
class R2SharedAssetScopeTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("r2-farm", 0, 0);
  private static final IndustryId WEAVE = IndustryHexKeys.id("r2-weave", 0, 0);
  private static final ActorRef OPERATOR_A = new ActorRef(ActorKind.ORGANIZATION, "org-r2-a");
  private static final ActorRef OPERATOR_B = new ActorRef(ActorKind.ORGANIZATION, "org-r2-b");
  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-r2-shared");
  private static final ActorRef HOUSEHOLD_ACTOR = HouseholdActors.of(HOUSEHOLD);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final ProductionModeId MODE = DefaultProductionModes.HANDICRAFT_WORKSHOP;
  private static final long LAND = 100L;
  private static final String SHARED = AssetBudgetBook.SHARED_SCOPE_REJECTION;

  // ── P1：单实例逐值不变 ───────────────────────────────────────────────────────────────

  @Test
  void singleUnitKeepsTheWholeOwnedPool() {
    ProductionProcess unit = unit(FARM, OPERATOR_A, "unit-r2-single");
    Map<ProductionUnitId, ProductionProcess> units = Map.of(unit.id(), unit);
    Map<AssetShareId, OwnershipStake> shares =
        Map.of(share(FARM, OPERATOR_A, LAND).id(), share(FARM, OPERATOR_A, LAND));

    AssetBudgetBook.AssetBudget budget = AssetBudgetBook.allocate(units, shares);

    assertThat(budget.allocationOf(unit.id()))
        .as("P1：单 unit scope 沿用整池 100（不得因 R2 收口被清零）")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.unallocated())
        .as("P1：单 unit scope 没有未分配量")
        .doesNotContainKey(new AssetBudgetBook.AssetScope(FARM, OPERATOR_A));
    assertThat(budget.rejectionOf(unit.id())).as("P1：单 unit scope 不产生共享拒绝").isEmpty();

    SettlementIndex index = settlementIndex(Map.of(unit.id(), unit), shares);
    assertThat(index.usableAssetsOf(unit))
        .as("P1：索引读口与纯函数逐值一致（100）")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(index.assetRejectionOf(unit.id())).isEmpty();
    assertThat(ProductionProcessBook.capacityScaleOf(unit, plan(FARM, LAND), index))
        .as("P1：capacityPerUnit=100、LAND=100 ⇒ 规模 1（与改前一致）")
        .isEqualTo(1L);

    // ★ 单实例回归的第二条腿：份额扫描版窄入口与索引版逐值相等（证明"单 unit 不等于被清零"）。
    assertThat(ProductionProcessBook.usableAssets(unit, shares))
        .as("P1：单 unit 窄入口（份额扫描）仍返回整池 100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
  }

  // ── P2：原 R2 复现 ──────────────────────────────────────────────────────────────────

  @Test
  void twoUnitsInTheSameScopeNeverSeeThePoolTwice() {
    ProductionProcess first = unit(FARM, OPERATOR_A, "unit-r2-first");
    ProductionProcess second = unit(FARM, OPERATOR_A, "unit-r2-second");
    Map<ProductionUnitId, ProductionProcess> units = orderedUnits(first, second);
    OwnershipStake land = share(FARM, OPERATOR_A, LAND);
    Map<AssetShareId, OwnershipStake> shares = Map.of(land.id(), land);

    AssetBudgetBook.AssetBudget budget = AssetBudgetBook.allocate(units, shares);

    assertThat(budget.allocations())
        .as("P2：共享 scope 的每个 unit 分配表为空（不是各拿整份 100）")
        .containsEntry(first.id(), Map.of())
        .containsEntry(second.id(), Map.of());
    assertThat(budget.unallocated())
        .as("P2：整池 100 保留在 unallocated（只读可见，不静默丢弃）")
        .containsEntry(
            new AssetBudgetBook.AssetScope(FARM, OPERATOR_A), Map.of(AssetKind.LAND, LAND));
    assertThat(budget.rejectionOf(first.id())).contains(SHARED);
    assertThat(budget.rejectionOf(second.id())).contains(SHARED);

    SettlementIndex index = settlementIndex(units, shares);
    assertThat(index.usableAssetsOf(first)).as("P2：unit1 可用资产为空").isEmpty();
    assertThat(index.usableAssetsOf(second)).as("P2：unit2 可用资产为空").isEmpty();
    assertThat(ProductionProcessBook.capacityScaleOf(first, plan(FARM, LAND), index))
        .as("P2：unit1 规模 0（不是 1）")
        .isZero();
    assertThat(ProductionProcessBook.capacityScaleOf(second, plan(FARM, LAND), index))
        .as("P2：unit2 规模 0（不是 1）")
        .isZero();
    assertThat(
            ProductionProcessBook.capacityScaleOf(first, plan(FARM, LAND), index)
                + ProductionProcessBook.capacityScaleOf(second, plan(FARM, LAND), index))
        .as("★★ 原 R2 复现：Σ规模 = 0（旧实现是 1 + 1 = 2；实有只有 100）")
        .isZero();
    assertThat(index.assetBudget().unallocated())
        .as("P2：索引持有的同一份预算也保留 unallocated=100")
        .containsEntry(
            new AssetBudgetBook.AssetScope(FARM, OPERATOR_A), Map.of(AssetKind.LAND, LAND));

    // ★ R2-I5：份额总账一字不改（只读派生，不搬走、不清零）。
    assertThat(shares.get(land.id()))
        .as("P2：OwnershipStake 的 quantity/owner/operator/kind 一字不改")
        .isEqualTo(land);
  }

  // ── P3：不同 scope 互不影响 ──────────────────────────────────────────────────────────

  @Test
  void differentScopesEachKeepTheirOwnPool() {
    ProductionProcess farmA = unit(FARM, OPERATOR_A, "unit-r2-a");
    ProductionProcess farmB = unit(FARM, OPERATOR_B, "unit-r2-b");
    Map<ProductionUnitId, ProductionProcess> units = orderedUnits(farmA, farmB);
    OwnershipStake shareA = share(FARM, OPERATOR_A, LAND);
    OwnershipStake shareB = share(FARM, OPERATOR_B, LAND);
    Map<AssetShareId, OwnershipStake> shares = orderedShares(shareA, shareB);

    AssetBudgetBook.AssetBudget budget = AssetBudgetBook.allocate(units, shares);
    assertThat(budget.allocationOf(farmA.id()))
        .as("P3：scope A 得自己的 100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.allocationOf(farmB.id()))
        .as("P3：scope B 得自己的 100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.rejections()).as("P3：不同 scope 不产生共享拒绝").isEmpty();
    assertThat(budget.unallocated()).as("P3：不同 scope 没有未分配量").isEmpty();

    SettlementIndex index = settlementIndex(units, shares);
    assertThat(index.usableAssetsOf(farmA)).containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(index.usableAssetsOf(farmB)).containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(ProductionProcessBook.capacityScaleOf(farmA, plan(FARM, LAND), index)).isEqualTo(1L);
    assertThat(ProductionProcessBook.capacityScaleOf(farmB, plan(FARM, LAND), index)).isEqualTo(1L);
  }

  // ── P4：预计/排队同源 ───────────────────────────────────────────────────────────────

  @Test
  void sharedScopeOfferIsNamedInfeasibleAndGetsNoLabor() {
    SharedWorld world = sharedWorld();
    LaborQueueBook.Offer offer =
        LaborQueueBook.offer(
            world.first(),
            world.industry(),
            market(),
            null,
            world.index(),
            Optional.of(MODE),
            MODE.value(),
            HOUSEHOLD,
            world.session());

    assertThat(offer.maxAbsorbableLaborMilli()).as("P4：共享 scope 不产生可生产 offer（最大可吸收劳动 0）").isZero();
    assertThat(offer.reason()).as("P4：具名拒绝与 SettlementIndex/AssetBudget 同源").isEqualTo(SHARED);
    assertThat(offer.queueable()).as("P4：共享 scope 的 offer 不可排队").isFalse();

    LaborQueueBook.Plan plan = LaborQueueBook.plan(HOUSEHOLD, 10_000L, 0L, List.of(offer));
    assertThat(plan.decisions()).hasSize(1);
    assertThat(plan.decisions().get(0).outcome())
        .as("P4：队列把它记为具名业务拒绝，而不是静默保留旧配额")
        .isEqualTo("REJECTED:" + SHARED);
    assertThat(plan.decisions().get(0).grantedLaborMilli()).as("P4：共享 scope 不分配任何劳动").isZero();
    assertThat(plan.allocatedLaborMilli()).as("P4：本 tick 劳动分配总量为 0").isZero();
  }

  @Test
  void sharedScopeExistingUnitProspectIsNamedInfeasible() {
    SharedWorld world = sharedWorld();
    ExpectedProfitBook.Prospect prospect =
        ExpectedProfitBook.prospect(
            world.dayView(),
            HOUSEHOLD,
            MODE,
            HEX,
            world.industry(),
            market(),
            demandBook(GRAIN, 1_000_000L),
            world.shares(),
            Set.of(),
            world.accounts(),
            world.units(),
            Map.of(),
            world.topology(),
            0L,
            world.runtime());

    assertThat(prospect.feasible()).as("P4：共享 scope 的现有 unit 预计不可行（不得按整池份额算出一份可生产候选）").isFalse();
    assertThat(prospect.reason()).as("P4：预计侧具名理由与排队/执行同源").isEqualTo(SHARED);
    assertThat(prospect.plannedOutputMilli()).as("P4：不可行时不得有预计产出").isEmpty();
  }

  // ── P5：执行前拒绝、零半状态 ─────────────────────────────────────────────────────────

  @Test
  void sharedScopeUnitsAreRejectedBeforeAnyProductionWrite() {
    SharedDayWorld world = sharedDayWorld();
    ProductionRuntimeEngine engine = world.engine();
    long experienceBefore = engine.state().experienceOf(HOUSEHOLD, world.trackId());
    long grainBefore = 1_000_000L;

    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.INFO)) {
      ProductionLedger dayOne = world.stepper().step(1L);
      // ★ P5 的判别点必须在**第 1 天结束当刻**读实例：若实现"先推进再拒绝"，第二天关账时 progress 会
      //   被重置成 0（1 天周期）或重置/继续，只在最后读会漏掉第一天的半状态。
      ProductionProcess firstAfterDayOne = engine.units().get(world.firstId());
      ProductionProcess secondAfterDayOne = engine.units().get(world.secondId());
      assertThat(firstAfterDayOne.progressDays())
          .as("P5：第 1 天结束时 unit1 没有进度写入（若执行后拒绝这里会是 1）")
          .isZero();
      assertThat(secondAfterDayOne.progressDays()).as("P5：第 1 天结束时 unit2 没有进度写入").isZero();
      assertThat(firstAfterDayOne.cycleContributions()).as("P5：第 1 天 unit1 无逐户实际劳动写入").isEmpty();
      assertThat(secondAfterDayOne.cycleContributions()).as("P5：第 1 天 unit2 无逐户实际劳动写入").isEmpty();
      assertThat(firstAfterDayOne.cycleInputUsedMilli()).as("P5：第 1 天 unit1 无周期投入写入").isEmpty();
      assertThat(secondAfterDayOne.cycleInputUsedMilli()).as("P5：第 1 天 unit2 无周期投入写入").isEmpty();
      assertThat(engine.state().experienceOf(HOUSEHOLD, world.trackId()))
          .as("P5：第 1 天没有经验增长（执行前拒绝，不是 progress 之后再拒）")
          .isEqualTo(experienceBefore);

      ProductionLedger dayTwo = world.stepper().step(2L);

      assertThat(gross(dayOne, FARM, GRAIN)).as("P5：第 1 天无产出").isZero();
      assertThat(gross(dayTwo, FARM, GRAIN)).as("P5：第 2 天仍无产出").isZero();
      assertThat(inputOf(dayOne, FARM, GRAIN)).as("P5：第 1 天无周期投入扣减").isZero();
      assertThat(inputOf(dayTwo, FARM, GRAIN)).as("P5：第 2 天无周期投入扣减").isZero();
      assertThat(world.stepper().accounts().householdGoods().get(HOUSEHOLD).get(GRAIN))
          .as("P5：家户投入库存一字未动（执行前拒绝，不是扣完再拒）")
          .isEqualTo(grainBefore);

      ProductionProcess first = engine.units().get(world.firstId());
      ProductionProcess second = engine.units().get(world.secondId());
      assertThat(first.progressDays()).as("P5：共享 scope unit1 没有进度写入（若执行后拒绝这里会是 1/2）").isZero();
      assertThat(second.progressDays()).as("P5：共享 scope unit2 没有进度写入").isZero();
      assertThat(first.cycleContributions()).as("P5：unit1 无逐户实际劳动写入").isEmpty();
      assertThat(second.cycleContributions()).as("P5：unit2 无逐户实际劳动写入").isEmpty();
      assertThat(first.cycleInputUsedMilli()).as("P5：unit1 无周期投入写入").isEmpty();
      assertThat(second.cycleInputUsedMilli()).as("P5：unit2 无周期投入写入").isEmpty();
      assertThat(engine.state().experienceOf(HOUSEHOLD, world.trackId()))
          .as("P5：没有经验增长（执行前拒绝，不是 progress 之后再拒）")
          .isEqualTo(experienceBefore);

      assertThat(log.lines())
          .as("P5：INFO 具名记录共享 scope 拒绝")
          .anySatisfy(
              line -> assertThat(line).contains("PRODUCTION_DAY_REJECTED").contains(SHARED));
    }
  }

  // ── P7：纯函数 + 稳定序 ─────────────────────────────────────────────────────────────

  @Test
  void assetBudgetIsAPureStableDerivation() {
    ProductionProcess first = unit(FARM, OPERATOR_A, "unit-r2-first");
    ProductionProcess second = unit(FARM, OPERATOR_A, "unit-r2-second");
    OwnershipStake landA = share(FARM, OPERATOR_A, LAND);
    OwnershipStake landB = share(FARM, OPERATOR_B, 250L);
    Map<AssetShareId, OwnershipStake> shares = orderedShares(landA, landB);

    // 反向插入 unit，结果仍按稳定 id 升序：顺序只影响遍历，不影响结果。
    LinkedHashMap<ProductionUnitId, ProductionProcess> reversed = new LinkedHashMap<>();
    reversed.put(second.id(), second);
    reversed.put(first.id(), first);

    AssetBudgetBook.AssetBudget firstRun = AssetBudgetBook.allocate(reversed, shares);
    AssetBudgetBook.AssetBudget secondRun = AssetBudgetBook.allocate(reversed, shares);

    assertThat(firstRun).as("P7：同输入同输出（纯函数可复算）").isEqualTo(secondRun);
    assertThat(firstRun.allocations().keySet())
        .as("P7：共享 scope 的 unit 按 id 升序稳定排列")
        .containsExactly(first.id(), second.id());
    assertThat(firstRun.rejections().keySet())
        .as("P7：拒绝表也按同一稳定序")
        .containsExactly(first.id(), second.id());
    assertThat(firstRun.unallocated())
        .as("P7：两个 scope 都保留在 unallocated，且 scope A 的整池为 100")
        .containsEntry(
            new AssetBudgetBook.AssetScope(FARM, OPERATOR_A), Map.of(AssetKind.LAND, LAND))
        .containsEntry(
            new AssetBudgetBook.AssetScope(FARM, OPERATOR_B), Map.of(AssetKind.LAND, 250L));
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static ProductionProcess unit(IndustryId industry, ActorRef operator, String unitId) {
    return new ProductionProcess(
        new ProductionUnitId(unitId),
        industry,
        ProductionLocation.parse(HEX_KEY),
        operator,
        industry.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static OwnershipStake share(IndustryId industry, ActorRef operator, long quantity) {
    return new OwnershipStake(
        OwnershipStake.idOf(industry, AssetKind.LAND, operator, operator, AssetRightKind.OWNED, 0L),
        industry,
        AssetKind.LAND,
        operator,
        operator,
        quantity,
        AssetRightKind.OWNED);
  }

  private static Map<ProductionUnitId, ProductionProcess> orderedUnits(ProductionProcess... units) {
    Map<ProductionUnitId, ProductionProcess> ordered = new LinkedHashMap<>();
    for (ProductionProcess unit : units) {
      ordered.put(unit.id(), unit);
    }
    return ordered;
  }

  private static Map<AssetShareId, OwnershipStake> orderedShares(OwnershipStake... shares) {
    Map<AssetShareId, OwnershipStake> ordered = new LinkedHashMap<>();
    for (OwnershipStake share : shares) {
      ordered.put(share.id(), share);
    }
    return ordered;
  }

  private static SettlementIndex settlementIndex(
      Map<ProductionUnitId, ProductionProcess> units, Map<AssetShareId, OwnershipStake> shares) {
    Map<IndustryId, IndustryDefinitionView> industries =
        Map.of(FARM, plan(FARM, 1L), WEAVE, plan(WEAVE, 1L));
    return SettlementIndex.build(
        units, industries, shares, Map.of(), householdRows(), Map.of(), Map.of());
  }

  private static Map<HouseholdId, HouseholdEconomy> householdRows() {
    return Map.of(
        HOUSEHOLD,
        new HouseholdEconomy(
            HOUSEHOLD,
            new CohortKey(HEX, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
            100L,
            10_000L,
            1_000,
            0L,
            Map.of(),
            Map.of(),
            0L));
  }

  /** capacityPerUnit 只留 LAND（100/规模），让规模是 LAND 的单值函数。 */
  private static IndustryDefinitionView plan(IndustryId industry, long landPerScale) {
    return IndustryViews.industry(
        industry,
        "R2 资产夹具",
        new RegimeId("tenant"),
        1L,
        Map.of(AssetKind.LAND, landPerScale),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0));
  }

  private static MarketTable market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    return EconomyFixtures.quote(SILVER, prices);
  }

  private static MarketDemandBook.Book demandBook(CommodityId commodity, long addressable) {
    return new MarketDemandBook.Book(
        Map.of(
            HEX,
            Map.of(
                commodity,
                new MarketDemandBook.Demand(
                    HEX, commodity, 0, 0, 0, 0, 0, 0, addressable, List.of("r2-test")))),
        0L,
        10L);
  }

  private static long gross(ProductionLedger ledger, IndustryId industry, CommodityId commodity) {
    return ledger.gross().getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  private static long inputOf(ProductionLedger ledger, IndustryId industry, CommodityId commodity) {
    return ledger.inputOf(industry, commodity);
  }

  // ── 共享 scope 的预计/排队世界（两个同 (industry, operator) 的 unit）──────────────────

  private record SharedWorld(
      IndustryDefinitionView industry,
      ProductionProcess first,
      ProductionProcess second,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> shares,
      SettlementIndex index,
      AccountSession accounts,
      MarketTopology topology,
      ProductionRuntime runtime,
      EconomySession session,
      io.mosire.simos.economy.EconomyDayView dayView) {}

  private static SharedWorld sharedWorld() {
    IndustryDefinitionView industry =
        new IndustryDefinitionView(
            FARM,
            "R2 共享资产预计夹具",
            new RegimeId("handicraft"),
            Optional.empty(),
            10L,
            0L,
            Map.of(AssetKind.LAND, 1L),
            Map.of(),
            0L,
            1_000L,
            Map.of(GRAIN, 2L),
            Map.of(),
            List.of(new ClassSlot(new SocialClassId("poor_peasant"), "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            io.mosire.simos.production.contract.ProductionExperienceUse.NONE,
            List.of(),
            Set.of(AssetRightKind.OWNED));
    ProductionProcess first =
        new ProductionProcess(
            new ProductionUnitId("unit-r2-offer-first"),
            FARM,
            ProductionLocation.parse(HEX_KEY),
            HOUSEHOLD_ACTOR,
            MODE.value(),
            0L,
            Map.of(),
            Map.of());
    ProductionProcess second =
        new ProductionProcess(
            new ProductionUnitId("unit-r2-offer-second"),
            FARM,
            ProductionLocation.parse(HEX_KEY),
            HOUSEHOLD_ACTOR,
            MODE.value(),
            0L,
            Map.of(),
            Map.of());
    Map<ProductionUnitId, ProductionProcess> units = orderedUnits(first, second);
    OwnershipStake land = share(FARM, HOUSEHOLD_ACTOR, LAND);
    Map<AssetShareId, OwnershipStake> shares = Map.of(land.id(), land);

    Map<HouseholdId, HouseholdEconomy> rows =
        Map.of(
            HOUSEHOLD,
            new HouseholdEconomy(
                HOUSEHOLD,
                new CohortKey(HEX, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
                100L,
                100_000L,
                1_000,
                0L,
                Map.of(),
                Map.of(),
                0L));
    Map<MarketZoneId, MarketTable> marketTables = EconomyFixtures.hexMarkets(Map.of(HEX, market()));
    MarketTopology topology = MarketTopology.singleHex(marketTables, Map.of());
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(rows)
            .withClassMemberships(
                Map.of(
                    HOUSEHOLD,
                    new HouseholdClassMembership(
                        HOUSEHOLD,
                        DefaultProductionModes.positionId(
                                MODE, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
                            .orElseThrow(),
                        DefaultProductionModes.positionId(
                                MODE, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
                            .orElseThrow(),
                        Map.of(),
                        0L,
                        0L,
                        "r2-shared")))
            .withOwnershipStakes(shares)
            .withMarketTables(marketTables);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        HOUSEHOLD, HEX, Map.of(GRAIN, 100_000L), Map.of(), Map.of(), Map.of());
    ProductionData definitions =
        ProductionData.empty().withModes(DefaultProductionModes.modes()).withIndustry(industry);
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(definitions);
    EconomySession session = new EconomySession(base, definitions, engine);
    return new SharedWorld(
        industry,
        first,
        second,
        units,
        shares,
        SettlementIndex.build(
            units, Map.of(FARM, industry), shares, Map.of(), rows, Map.of(), Map.of()),
        accounts,
        topology,
        engine,
        session,
        EconomyFixtures.dayView(base, definitions));
  }

  // ── 共享 scope 的真日循环世界 ─────────────────────────────────────────────────────────

  private record SharedDayWorld(
      IndustryId industry,
      ProductionUnitId firstId,
      ProductionUnitId secondId,
      ProductionRuntimeEngine engine,
      io.mosire.simos.production.contract.ExperienceTrackId trackId,
      EconomyDayStepper stepper) {}

  private static SharedDayWorld sharedDayWorld() {
    io.mosire.simos.production.contract.ExperienceTrackId track =
        new io.mosire.simos.production.contract.ExperienceTrackId("r2-day-track");
    io.mosire.simos.production.contract.ExpressionDefinition growthFormula =
        io.mosire.simos.production.contract.ExpressionDefinition.ofSingleInput(
            io.mosire.simos.production.contract.ExperienceTrack.EXPERIENCE_INPUT,
            new io.mosire.simos.production.contract.RestrictedExpression.Input(
                io.mosire.simos.production.contract.ExperienceTrack.EXPERIENCE_INPUT));
    io.mosire.simos.production.contract.ExperienceTrack trackDefinition =
        new io.mosire.simos.production.contract.ExperienceTrack(
            track, "R2 日循环经验", 10_000L, 1_000L, 0L, growthFormula);
    IndustryDefinitionView industry =
        new IndustryDefinitionView(
            FARM,
            "R2 共享资产日循环夹具",
            new RegimeId("tenant"),
            Optional.empty(),
            2L,
            0L,
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            1_000L,
            Map.of(GRAIN, 5L),
            Map.of(AssetKind.LAND, Map.of(GRAIN, 1_000L)),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            new io.mosire.simos.production.contract.ProductionExperienceUse(
                Optional.of(track),
                Optional.empty(),
                io.mosire.simos.production.contract.ProductionEffectScope.TOTAL,
                growthFormula,
                0L),
            List.of(),
            Set.of(AssetRightKind.OWNED));
    ProductionUnitId firstId = new ProductionUnitId("unit-r2-day-first");
    ProductionUnitId secondId = new ProductionUnitId("unit-r2-day-second");
    ProductionProcess first =
        new ProductionProcess(
            firstId,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            OPERATOR_A,
            FARM.value(),
            0L,
            Map.of(),
            Map.of());
    ProductionProcess second =
        new ProductionProcess(
            secondId,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            OPERATOR_A,
            FARM.value(),
            0L,
            Map.of(),
            Map.of());
    OwnershipStake land = share(FARM, OPERATOR_A, 1_000L);
    HouseholdEconomy row =
        new HouseholdEconomy(
            HOUSEHOLD,
            new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            100L,
            20_000L,
            1_000,
            0L,
            Map.of(),
            Map.of(),
            0L);
    PeopleLotId lot = new PeopleLotId("rural:0_0:MALE:1");
    HouseholdLaborCommitment firstCommitment =
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-r2-day-first"),
            lot,
            HOUSEHOLD,
            OPERATOR_A,
            firstId.value(),
            10_000L,
            1L);
    HouseholdLaborCommitment secondCommitment =
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-r2-day-second"),
            lot,
            HOUSEHOLD,
            OPERATOR_A,
            secondId.value(),
            10_000L,
            1L);
    ProductionRules firstRules =
        new ProductionRules(
            firstId, OPERATOR_A, new Payee.ToActor(HOUSEHOLD_ACTOR), List.of(), OPERATOR_A);
    ProductionRules secondRules =
        new ProductionRules(
            secondId, OPERATOR_A, new Payee.ToActor(HOUSEHOLD_ACTOR), List.of(), OPERATOR_A);
    EconomyData base =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new io.mosire.simos.economy.model.EconomyMeta(
                        "r2-shared-day",
                        0L,
                        java.util.OptionalLong.empty(),
                        io.mosire.simos.economy.model.EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty())))
            .withHouseholdEconomies(Map.of(HOUSEHOLD, row))
            .withOwnershipStakes(Map.of(land.id(), land))
            .withLaborCommitments(
                Map.of(
                    firstCommitment.id(), firstCommitment,
                    secondCommitment.id(), secondCommitment))
            .withRelations(Map.of(firstId, firstRules, secondId, secondRules));
    ProductionData definitions =
        ProductionData.empty()
            .withIndustry(industry)
            .withExperienceTrack(trackDefinition)
            .withInstance(first)
            .withInstance(second)
            .withHouseholdExperience(
                HOUSEHOLD,
                track,
                new io.mosire.simos.production.contract.ProductionExperienceFeature(0L, 0L));
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(definitions);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        HOUSEHOLD, HEX, Map.of(GRAIN, 1_000_000L), Map.of(), Map.of(), Map.of());
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            definitions,
            engine,
            accounts,
            MarketTopology.singleHex(base.marketTables(), base.marketZones()));
    return new SharedDayWorld(FARM, firstId, secondId, engine, track, stepper);
  }
}
