package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
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
import java.util.OptionalLong;
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
 *   <tr><td>P2</td><td>同 scope 两 unit、LAND=100、各请求 50 ⇒ 50/50、unallocated=0；缺请求事实 ⇒ 具名拒绝且 unallocated=100，不再 100+100</td><td>{@link #twoUnitsInTheSameScopeShareByExplicitRequest()} / {@link #twoUnitsWithoutRequestFactsKeepThePoolUnallocated()}</td></tr>
 *   <tr><td>P3</td><td>两 unit 不同 (industry, operator)，各有 100 ⇒ 各得 100</td><td>{@link #differentScopesEachKeepTheirOwnPool()}</td></tr>
 *   <tr><td>P4</td><td>共享 scope 的预计/排队：无可生产 offer、无劳动分配</td><td>{@link #sharedScopeOfferIsNamedInfeasibleAndGetsNoLabor()}</td></tr>
 *   <tr><td>P4</td><td>共享 scope 的预计（ExpectedProfitBook）不把整池复制给现有 unit</td><td>{@link #sharedScopeExistingUnitProspectIsNamedInfeasible()}</td></tr>
 *   <tr><td>P5</td><td>共享 scope 的实际执行：D4 真实 500/500 授予，允许逐 unit progress=1；本日无产出/经验/实际劳动/周期投入</td><td>{@link #sharedScopeUnitsUseRealD4AllocationWithoutDoubleGranting()}</td></tr>
 *   <tr><td>P7</td><td>同输入同输出、稳定序、纯函数可复算</td><td>{@link #assetBudgetIsAPureStableDerivation()}</td></tr>
 * </table>
 *
 * <p>★★ <b>D4 迁移口径</b>：本文件不再引用已删除的 {@code AssetBudgetBook}/{@code usableAssets}。纯预算是 {@link
 * AssetOccupancyBook#allocate} 的真实 D4 结果：有显式请求时按请求量分配并保留 {@code unallocated}；没有非资产请求事实的多实例 scope
 * 必须具名 {@code asset-occupancy-request-missing:<unit>} 且整池留在 {@code unallocated}。执行路径继续断言真实 D4
 * 授予（500/500）与逐 unit progress=1，且不得把旧 guard 当成通过结论。
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
  private static final String FORBIDDEN_SHARED = "asset-scope-shared:awaiting-D4";
  private static final String OCCUPANCY_REQUEST_MISSING_PREFIX = "asset-occupancy-request-missing:";

  // ── P1：单实例逐值不变 ───────────────────────────────────────────────────────────────

  @Test
  void singleUnitKeepsTheWholeOwnedPool() {
    ProductionProcess unit = unit(FARM, OPERATOR_A, "unit-r2-single");
    Map<ProductionUnitId, ProductionProcess> units = Map.of(unit.id(), unit);
    OwnershipStake land = share(FARM, OPERATOR_A, LAND);
    Map<AssetShareId, OwnershipStake> shares = Map.of(land.id(), land);

    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(unit, 0L, 3L, Map.of(AssetKind.LAND, 30L))),
            Map.of(),
            shares,
            Map.of(),
            units,
            0L);

    assertThat(budget.allocationOf(unit.id()))
        .as("P1：单 unit scope 沿用整池 100（不得因 R2 收口被清零）")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.unallocatedOf(scope(FARM, OPERATOR_A, 0L, 3L)))
        .as("P1：单 unit scope 没有未分配量")
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
    assertThat(budget.rejectionOf(unit.id())).as("P1：单 unit scope 不产生拒绝").isEmpty();

    SettlementIndex index = settlementIndex(units, shares);
    assertThat(index.assetAllocationOf(unit))
        .as("P1：索引读口与纯函数逐值一致（100）")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(index.assetRejectionOf(unit.id())).isEmpty();
    assertThat(ProductionProcessBook.capacityScaleOf(unit, plan(FARM, LAND), index))
        .as("P1：capacityPerUnit=100、LAND=100 ⇒ 规模 1（与改前一致）")
        .isEqualTo(1L);
  }

  // ── P2：原 R2 复现 ──────────────────────────────────────────────────────────────────

  @Test
  void twoUnitsInTheSameScopeShareByExplicitRequest() {
    ProductionProcess first = unit(FARM, OPERATOR_A, "unit-r2-first");
    ProductionProcess second = unit(FARM, OPERATOR_A, "unit-r2-second");
    Map<ProductionUnitId, ProductionProcess> units = orderedUnits(first, second);
    OwnershipStake land = share(FARM, OPERATOR_A, LAND);
    Map<AssetShareId, OwnershipStake> shares = Map.of(land.id(), land);

    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(
                request(first, 0L, 3L, Map.of(AssetKind.LAND, 50L)),
                request(second, 0L, 3L, Map.of(AssetKind.LAND, 50L))),
            Map.of(),
            shares,
            Map.of(),
            units,
            0L);

    assertThat(budget.allocationOf(first.id()))
        .as("P2/P22：共享 scope 按显式请求真实分配，A 得 50")
        .containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(budget.allocationOf(second.id()))
        .as("P2/P22：共享 scope 按显式请求真实分配，B 得 50")
        .containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(
            budget.allocationOf(first.id()).getOrDefault(AssetKind.LAND, 0L)
                + budget.allocationOf(second.id()).getOrDefault(AssetKind.LAND, 0L))
        .as("★★ 原 R2 复现：Σ实占 = 100 = 实有量（旧缺陷是 100+100=200）")
        .isEqualTo(LAND);
    assertThat(budget.grantsToWrite()).hasSize(2);
    assertThat(budget.grantsToWrite().stream().mapToLong(g -> g.quantity()).sum())
        .as("两条 grant 引用同一份 share，但合计不得超占")
        .isEqualTo(LAND);
    assertThat(budget.unallocatedOf(scope(FARM, OPERATOR_A, 0L, 3L)))
        .as("P2：显式请求分完后 unallocated=0")
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
    assertThat(budget.rejectionOf(first.id())).isEmpty();
    assertThat(budget.rejectionOf(second.id())).isEmpty();
    assertThat(budget.rejections()).doesNotContainValue(FORBIDDEN_SHARED);

    // ★ R2-I5：份额总账一字不改（只读派生，不搬走、不清零）。
    assertThat(shares.get(land.id()))
        .as("P2：OwnershipStake 的 quantity/owner/operator/kind/location 一字不改")
        .isEqualTo(land);
  }

  @Test
  void twoUnitsWithoutRequestFactsKeepThePoolUnallocated() {
    ProductionProcess first = unit(FARM, OPERATOR_A, "unit-r2-first");
    ProductionProcess second = unit(FARM, OPERATOR_A, "unit-r2-second");
    Map<ProductionUnitId, ProductionProcess> units = orderedUnits(first, second);
    OwnershipStake land = share(FARM, OPERATOR_A, LAND);
    Map<AssetShareId, OwnershipStake> shares = Map.of(land.id(), land);

    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(first, 0L, 3L, Map.of()), request(second, 0L, 3L, Map.of())),
            Map.of(),
            shares,
            Map.of(),
            units,
            0L);

    assertThat(budget.rejectionOf(first.id()))
        .as("P2/P23：缺非资产请求事实 ⇒ 具名 request-missing，不默认 1/整池")
        .contains(OCCUPANCY_REQUEST_MISSING_PREFIX + first.id().value());
    assertThat(budget.rejectionOf(second.id()))
        .contains(OCCUPANCY_REQUEST_MISSING_PREFIX + second.id().value());
    assertThat(budget.allocationOf(first.id())).isEmpty();
    assertThat(budget.allocationOf(second.id())).isEmpty();
    assertThat(budget.grantsToWrite()).isEmpty();
    assertThat(budget.unallocatedOf(scope(FARM, OPERATOR_A, 0L, 3L)))
        .as("P2：整池 100 保留在 unallocated（只读可见，不静默丢弃）")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));

    SettlementIndex index = settlementIndex(units, shares);
    assertThat(index.assetAllocationOf(first)).as("P2：索引 unit1 可用资产为空").isEmpty();
    assertThat(index.assetAllocationOf(second)).as("P2：索引 unit2 可用资产为空").isEmpty();
    assertThat(index.assetRejectionOf(first.id()))
        .contains(OCCUPANCY_REQUEST_MISSING_PREFIX + first.id().value());
    assertThat(ProductionProcessBook.capacityScaleOf(first, plan(FARM, LAND), index))
        .as("P2：unit1 规模 0（不是 1）")
        .isZero();
    assertThat(ProductionProcessBook.capacityScaleOf(second, plan(FARM, LAND), index))
        .as("P2：unit2 规模 0（不是 1）")
        .isZero();
    AssetOccupancyBudget indexBudget = index.d4AssetOccupancyBudget().orElseThrow();
    assertThat(indexBudget.unallocatedOf(scope(FARM, OPERATOR_A, 0L, 1L)))
        .as("P2：索引持有的同一份预算也保留 unallocated=100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
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

    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(
                request(farmA, 0L, 3L, Map.of(AssetKind.LAND, 10L)),
                request(farmB, 0L, 3L, Map.of(AssetKind.LAND, 10L))),
            Map.of(),
            shares,
            Map.of(),
            units,
            0L);
    assertThat(budget.allocationOf(farmA.id()))
        .as("P3：scope A 得自己的 100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.allocationOf(farmB.id()))
        .as("P3：scope B 得自己的 100")
        .containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(budget.rejections()).as("P3：不同 scope 不产生拒绝").isEmpty();
    assertThat(budget.unallocated())
        .as("P3：不同 scope 的未分配量逐项为 0（预算读口保留 0 值行是合法形状）")
        .hasSize(2)
        .allSatisfy(
            (scope, kinds) -> assertThat(kinds).containsExactly(Map.entry(AssetKind.LAND, 0L)));

    SettlementIndex index = settlementIndex(units, shares);
    assertThat(index.assetAllocationOf(farmA)).containsExactly(Map.entry(AssetKind.LAND, LAND));
    assertThat(index.assetAllocationOf(farmB)).containsExactly(Map.entry(AssetKind.LAND, LAND));
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
            ProductionRoleId.parse("operator"),
            world.session());

    assertThat(offer.maxAbsorbableLaborMilli()).as("P4：共享 scope 不产生可生产 offer（最大可吸收劳动 0）").isZero();
    String occupancyReason = OCCUPANCY_REQUEST_MISSING_PREFIX + world.first().id().value();
    assertThat(offer.reason())
        .as("P4：D4-Ib2a 新路径的具名拒绝 = 没有资产占用请求（不是旧 awaiting-D4）")
        .isEqualTo(occupancyReason);
    assertThat(offer.queueable()).as("P4：共享 scope 的 offer 不可排队").isFalse();

    LaborQueueBook.Plan plan = LaborQueueBook.plan(HOUSEHOLD, 10_000L, 0L, List.of(offer));
    assertThat(plan.decisions()).hasSize(1);
    assertThat(plan.decisions().get(0).outcome())
        .as("P4：队列把它记为具名 D4 请求缺失拒绝，而不是静默保留旧配额")
        .isEqualTo("REJECTED:" + occupancyReason);
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
    assertThat(prospect.reason())
        .as("P4：预计侧 D4-Ib2a 具名理由 = 没有资产占用请求")
        .isEqualTo(OCCUPANCY_REQUEST_MISSING_PREFIX + world.first().id().value());
    assertThat(prospect.plannedOutputMilli()).as("P4：不可行时不得有预计产出").isEmpty();
  }

  // ── P5：D4-Ib2a 真实分配（允许 progress=1），无产出/经验/投入半写 ───────────────────────

  @Test
  void sharedScopeUnitsUseRealD4AllocationWithoutDoubleGranting() {
    SharedDayWorld world = sharedDayWorld();
    ProductionRuntimeEngine engine = world.engine();
    long experienceBefore = engine.state().experienceOf(HOUSEHOLD, world.trackId());
    long grainBefore = 1_000_000L;

    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.INFO)) {
      ProductionLedger dayOne = world.stepper().step(1L);

      // D4-Ib2a 当刻真实行为：两个实例都按劳动事实得到 500/500 的 ACTIVE grant，并各自推进 1 天 progress；
      // 但本日没有逐户实际劳动/周期投入/产出/经验写入。这里断言真实分配，不把旧 sharing guard 当结论。
      ProductionProcess firstAfterDayOne = engine.units().get(world.firstId());
      ProductionProcess secondAfterDayOne = engine.units().get(world.secondId());
      assertThat(firstAfterDayOne.progressDays())
          .as("P5：D4-Ib2a 真实分配允许实例写 1 天进度（不是旧『两 unit 都零半状态』）")
          .isEqualTo(1L);
      assertThat(secondAfterDayOne.progressDays()).isEqualTo(1L);
      assertThat(firstAfterDayOne.cycleContributions()).as("P5：本日无逐户实际劳动写入").isEmpty();
      assertThat(secondAfterDayOne.cycleContributions()).isEmpty();
      assertThat(firstAfterDayOne.cycleInputUsedMilli()).as("P5：本日无周期投入写入").isEmpty();
      assertThat(secondAfterDayOne.cycleInputUsedMilli()).isEmpty();
      assertThat(engine.state().experienceOf(HOUSEHOLD, world.trackId()))
          .as("P5：本日没有经验增长")
          .isEqualTo(experienceBefore);

      assertThat(gross(dayOne, FARM, GRAIN)).as("P5：第 1 天无产出").isZero();
      assertThat(inputOf(dayOne, FARM, GRAIN)).as("P5：第 1 天无周期投入扣减").isZero();
      assertThat(world.stepper().accounts().householdGoods().get(HOUSEHOLD).get(GRAIN))
          .as("P5：家户投入库存一字未动")
          .isEqualTo(grainBefore);

      var grants = world.stepper().data().assetOccupancies().values();
      assertThat(grants).as("P5/P22：两个实例各有一条真实 D4 grant").hasSize(2);
      assertThat(grants.stream().mapToLong(g -> g.quantity()).sum())
          .as("★★ P22：实占合计 = 实有 LAND 1000，不是每个实例各 1000/Σ=2000")
          .isEqualTo(1_000L);
      assertThat(grants)
          .allSatisfy(
              grant -> {
                assertThat(grant.quantity()).as("★ 每个实例只拿预算分到的 500，不复制整池 1000").isEqualTo(500L);
                assertThat(grant.fromTick()).isEqualTo(1L);
                assertThat(grant.toTickExclusive()).isEqualTo(3L);
              });
      assertThat(grants.stream().map(g -> g.shareId()).distinct())
          .as("两个实例引用同一份 LAND 份额")
          .hasSize(1);
      assertThat(log.lines())
          .as("D4 新多实例路径不得把 asset-scope-shared:awaiting-D4 当唯一通过结果")
          .noneMatch(line -> line.contains(FORBIDDEN_SHARED));
    }
  }

  @Test
  void sharedScopeSecondDayMustReuseCommittedBudgetWithoutOverlapFault() {
    SharedDayWorld world = sharedDayWorld();
    world.stepper().step(1L);

    assertThatCode(() -> world.stepper().step(2L))
        .as(
            "★★ P3/P8：两个 unit 在同一 share 的 ACTIVE grant 可以并存（Σ≤quantity），"
                + "下一天必须作为 committed 重用，不得误报 asset-occupancy-overlap")
        .doesNotThrowAnyException();

    var grants = world.stepper().data().assetOccupancies().values();
    assertThat(grants.stream().mapToLong(g -> g.quantity()).sum())
        .as("跨 revision 后仍只有实有 LAND=1000 被两个实例合计占用")
        .isEqualTo(1_000L);
  }

  // ── P7：纯函数 + 稳定序 ─────────────────────────────────────────────────────────────

  @Test
  void assetBudgetIsAPureStableDerivation() {
    ProductionProcess first = unit(FARM, OPERATOR_A, "unit-r2-first");
    ProductionProcess second = unit(FARM, OPERATOR_A, "unit-r2-second");
    OwnershipStake landA = share(FARM, OPERATOR_A, LAND);
    Map<AssetShareId, OwnershipStake> shares = orderedShares(landA);

    // 反向插入 unit，结果仍按稳定 id 升序：顺序只影响遍历，不影响结果。
    LinkedHashMap<ProductionUnitId, ProductionProcess> reversed = new LinkedHashMap<>();
    reversed.put(second.id(), second);
    reversed.put(first.id(), first);

    AssetOccupancyBudget firstRun =
        AssetOccupancyBook.allocate(
            List.of(
                request(first, 0L, 3L, Map.of(AssetKind.LAND, 40L)),
                request(second, 0L, 3L, Map.of(AssetKind.LAND, 40L))),
            Map.of(),
            shares,
            Map.of(),
            reversed,
            0L);
    AssetOccupancyBudget secondRun =
        AssetOccupancyBook.allocate(
            List.of(
                request(second, 0L, 3L, Map.of(AssetKind.LAND, 40L)),
                request(first, 0L, 3L, Map.of(AssetKind.LAND, 40L))),
            Map.of(),
            shares,
            Map.of(),
            reversed,
            0L);

    assertThat(firstRun).as("P7：同输入同输出（纯函数可复算）").isEqualTo(secondRun);
    assertThat(firstRun.allocations().keySet())
        .as("P7：共享 scope 的 unit 按 id 升序稳定排列")
        .containsExactly(first.id(), second.id());
    assertThat(firstRun.rejections()).as("P7：显式请求路径不产生拒绝").isEmpty();
    assertThat(firstRun.unallocatedOf(scope(FARM, OPERATOR_A, 0L, 3L)))
        .as("P7：40/40 请求后余量 20 留在 unallocated")
        .containsExactly(Map.entry(AssetKind.LAND, 20L));
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
    // P2b2 后 LAND id 只含 (industry, LAND, location, sequence)，不再含 operator。
    // 同一 location 下不同 operator 的夹具必须用不同 sequence 区分土地块；A=0、B=1 保持稳定顺序。
    long sequence = OPERATOR_B.equals(operator) ? 1L : 0L;
    return new OwnershipStake(
        OwnershipStake.idOf(
            industry,
            AssetKind.LAND,
            operator,
            operator,
            AssetRightKind.OWNED,
            ProductionLocation.parse(HEX_KEY),
            sequence),
        industry,
        AssetKind.LAND,
        operator,
        operator,
        quantity,
        AssetRightKind.OWNED,
        ProductionLocation.parse(HEX_KEY));
  }

  /**
   * ★ D-032 RA1b2b：持久状态只保留非 LAND 份额；LAND 由 landArableSupply/landHoldings 唯一权威持有。
   *
   * <p>本文件的 {@code shares}（含 LAND）继续作为 D4 瞬态投影的显式入参传给 {@code SettlementIndex}/{@code
   * assetOccupancyBudget}，断言强度不变。
   */
  private static Map<AssetShareId, OwnershipStake> persistentShares(
      Map<AssetShareId, OwnershipStake> shares) {
    Map<AssetShareId, OwnershipStake> kept = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : shares.entrySet()) {
      if (entry.getValue().asset() != AssetKind.LAND) {
        kept.put(entry.getKey(), entry.getValue());
      }
    }
    return kept;
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
        units,
        industries,
        shares,
        Map.of(),
        householdRows(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        0L);
  }

  private static AssetOccupancyRequest request(
      ProductionProcess unit, long from, long to, Map<AssetKind, Long> quantities) {
    return new AssetOccupancyRequest(
        unit.id(),
        unit.industry(),
        unit.operator(),
        unit.location(),
        from,
        to,
        quantities,
        OptionalLong.empty(),
        Optional.empty());
  }

  private static AssetOccupancyScope scope(
      IndustryId industry, ActorRef operator, long from, long to) {
    return new AssetOccupancyScope(
        industry, operator, ProductionLocation.parse(HEX_KEY), AssetKind.LAND, from, to);
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
            // ★ D-032 RA1b2b：持久状态不得含 LAND 份额；本夹具的 `shares`（含 LAND）只作为
            //   SettlementIndex/assetOccupancyBudget 的瞬态 D4 投影入参显式传递，不进 EconomyData。
            .withOwnershipStakes(persistentShares(shares))
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
            units,
            Map.of(FARM, industry),
            shares,
            Map.of(),
            rows,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            0L),
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
            // ★ D-032 RA1b2b/RA5a：LAND 已改为标量唯一权威，且其 D4 授权是**瞬态投影**（写回时被过滤）。
            //   本用例要观察"共享资产 scope 的 grant 逐值落盘 + 不重复授予"，故改用非 LAND 的持久资产
            //   WORKSHOP —— 同一套 D4 共享逻辑，但授权会真实进 assetOccupancies；LAND 版本由
            //   LandProductionProjection 的专项测试覆盖。
            Map.of(AssetKind.WORKSHOP, 1_000L),
            Map.of(),
            0L,
            1_000L,
            Map.of(GRAIN, 5L),
            Map.of(),
            List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            // ★ 测试夹具修正（D-032 RA5a）：`growthFormula` 是 ExperienceTrack.masteryFormula
            //   （输入名 = experience），不能同时当效率公式用 —— ProductionExperienceUse 的效率公式输入
            //   是 primary_mastery/shared_mastery/primary_experience。本用例只测 D4 分配，日 1 无产出/无
            //   经验增长 ⇒ 效率用中性 1000‰（NEUTRAL_PER_MILLE）；断言强度不受影响。
            new io.mosire.simos.production.contract.ProductionExperienceUse(
                Optional.of(track),
                Optional.empty(),
                io.mosire.simos.production.contract.ProductionEffectScope.TOTAL,
                io.mosire.simos.production.contract.ExpressionDefinition.NEUTRAL_PER_MILLE,
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
    OwnershipStake workshop =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.WORKSHOP,
                OPERATOR_A,
                OPERATOR_A,
                AssetRightKind.OWNED,
                ProductionLocation.parse(HEX_KEY),
                0L),
            FARM,
            AssetKind.WORKSHOP,
            OPERATOR_A,
            OPERATOR_A,
            1_000L,
            AssetRightKind.OWNED,
            ProductionLocation.parse(HEX_KEY));
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
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator")));
    HouseholdLaborCommitment secondCommitment =
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-r2-day-second"),
            lot,
            HOUSEHOLD,
            OPERATOR_A,
            secondId.value(),
            10_000L,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator")));
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
            .withOwnershipStakes(Map.of(workshop.id(), workshop))
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
