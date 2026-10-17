package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>R6（§15.6 / §15.11 / F1 账本）：入口 {@code probe} 的入参组合必须让候选真的能进产</b> （D-032 修复轮 T9 批；期望值按 §15.6
 * 的 A/B/C/D 对照与 F1 账本 §2 的新公式手算）。
 *
 * <p>★★ <b>缺陷现场（控制方独立复现）</b>：{@code EconomyEntrySettlement.probeEntry} 旧版固定传 {@code
 * assetOccupancy=Map.of()} ⇒ probe 的 {@code capacityScaleOf} 显式"空占用 ⇒ 0" ⇒ {@code scale=0}、 {@code
 * assetRequired} 空 ⇒ 入口在 {@code NO_ASSET_REQUIREMENT}/{@code PROBE_SCALE_ZERO} 处<b>必拒</b>；且它传的
 * {@code laborMilli} 是<b>每日</b>可用劳动，而 probe 按<b>周期累计</b> ÷ cycleDays ⇒ 即便补上占用，规模仍被低估 {@code
 * cycleDays} 倍。E1-A2 又修了两个残留：① 未物化方案首次采用时 probe 拿物化 id ⇒ {@code plan-unknown}； ② 二次夹 {@code
 * assetCap} 把 {@code trialScale} 压成 1。
 *
 * <p>★ <b>本类逐条判据（§15.6 负向 + F1 §5）</b>：
 *
 * <ol>
 *   <li><b>入口端到端</b>：有需求 + 未物化 unbound 方案 + 可解析资产来源 ⇒ 首次采用不再 {@code plan-unknown}，可 entered；
 *   <li><b>资产约束时</b>：{@code trialScale = min(MAX_TRIAL_SCALE_PER_ENTRY=10, probe.scale())}，且拆分腿
 *       {@code need = required × trialScale ÷ probeScale}（不是旧式 {@code required × trialScale}）；
 *   <li><b>空占用</b>：仍先给 {@code NO_ASSET_REQUIREMENT}（既有拒绝顺序不变），不是 {@code PROBE_SCALE_ZERO}；
 *   <li><b>probe 无副作用</b>：{@code planEntries} 后 production 全切片逐值不变、变更集为空、物化定义未登记。
 * </ol>
 *
 * <p>★ <b>手算（主例）</b>：方案 {@code cycle=1}、{@code capacityPerUnit={LAND:1}}、{@code
 * laborPerUnit=1}、每规模产 1 布、无原料； 家户每日劳动 100；来源（ESTATE）LAND=100；日 1。
 *
 * <pre>
 *   劳动 = 100（日）× 1（cycleDays）= 100        （旧口径传 100 也恰好是 100 —— 本世界 cycle=1，量纲差在 cycle>1 才显形）
 *   占用 = 自有 ∅ ∪ 来源 {LAND:100} = {LAND:100}
 *   probe：baseScale=min(劳动 100, 资产 100)=100、E=1000 ⇒ probe.scale=100
 *   trialScale = min(10, 100) = 10
 *   拆分 need = required(LAND 100) × 10 ÷ probeScale 100 = 10（旧式会拆 100 —— 量纲错，且会把可产候选假拒）
 *   intent.laborMilli = laborRequired 100 × 10 ÷ 100 = 10
 * </pre>
 */
class EconomyEntryProbeR6Test {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");

  private static final HouseholdId HOUSE = HouseholdId.parse("hh-r6-entry");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ORGANIZATION, "org-r6-entry");

  /** 未绑地点的方案（方案池只收这种 id）；本格物化 id 由 {@code IndustryHexKeys.id} 现算。 */
  private static final IndustryId PLAN = new IndustryId("r6-weave");

  private static final IndustryId LAND_INDUSTRY = IndustryHexKeys.id("r6-estate", 0, 0);

  // ── ① 入口端到端：unbound 方案首次采用可 entered（不再 plan-unknown）─────────────────────

  @Test
  void unboundPlanFirstAdoptionFormsAnIntentWithoutPlanUnknown() {
    Fixture fixture = fixture(100L, true);

    EconomyEntrySettlement.Plan plan = planEntries(fixture);

    assertThat(plan.outcomes()).as("★ F1 修复后首次采用不再被 plan-unknown 拒：本批没有任何具名拒绝").isEmpty();
    assertThat(plan.intents()).as("★ 入口端到端：候选真的形成可执行意向（可 entered）").hasSize(1);

    EconomyEntrySettlement.EntryIntent intent = plan.intents().get(0);
    assertThat(intent.plan().id()).as("意向引用的是 unbound 方案本身").isEqualTo(PLAN);
    assertThat(intent.industryId()).as("实例将落在本格物化 id 上").isEqualTo(materializedPlan());

    // ── ③ 空占用前 probe 无副作用：planEntries 只读 ───────────────────────────────────────
    // ★ 意向段的"只读"由下面的独立缺陷复现用例钉住（本用例只判 F1 修复后的入口试算/拆分腿）。

    // ── ② trialScale / 拆分腿的量纲（f1 账本 §2-T2 的新公式）─────────────────────────────
    assertThat(intent.trialScale())
        .as("★ 判据②：cap = min(10, probe.scale=100) = 10（旧二次夹会把它压成 1）")
        .isEqualTo(10L);
    assertThat(intent.grants())
        .as("拆分腿：need = required(100) × trialScale(10) ÷ probeScale(100) = 10（旧式会拆 100）")
        .hasSize(1);
    EconomyEntrySettlement.AssetGrant grant = intent.grants().get(0);
    assertThat(grant.asset()).isEqualTo(AssetKind.LAND);
    assertThat(grant.quantity()).as("★ need 的逐值手算（量纲腿）").isEqualTo(10L);
    assertThat(grant.sourceShare()).isEqualTo(fixture.sourceShareId());
    assertThat(intent.laborMilli()).as("劳动同样按 scaleLinearly：100 × 10 ÷ 100 = 10").isEqualTo(10L);
    assertThat(intent.expectedDay()).as("进入日 1 + cycleDays 1 − 1 = 1").isEqualTo(1L);

    // ★★ 执行段"能否真的 entered"见下方独立缺陷复现用例：本轮发现 planEntries 已写入（registerPlan/createInstance）⇒
    //   execute 的重新校验撞 unit-conflict ⇒ changed=false / enteredUnitIds 空。该缺陷不在 R1–R6 清单内，按派单纪律
    //   **报 BLOCKED，不改 main**；最小复现 = 下面的 {@link
    // #planEntriesMustBeReadOnlyAndExecuteMustActuallyEnter()}。
  }

  /**
   * ★★★ <b>【真缺陷最小复现 / BLOCKED】入口意向段不是只读的，导致 execute 无法真正 entered</b>。
   *
   * <p>事实（本用例逐值读出）：
   *
   * <ol>
   *   <li>{@code planEntries} 内部对每条意向调用 {@code applyIntent}；B2 起 {@code applyIntent} 走 {@code
   *       runtime.registerPlan} + {@code runtime.createInstance} ⇒ <b>意向段改了权威 production 状态</b>
   *       （新增本格物化定义 + 新实例）并产生非空 changeSet；
   *   <li>随后 {@code execute} 对同一条意向重新评估并再次 {@code applyIntent} ⇒ {@code createInstance} 撞 {@code
   *       unit-conflict} ⇒ {@code Execution.changed()=false}、{@code enteredUnitIds} 空、 outcome =
   *       {@code INSTANCE_CREATE_REJECTED:unit-conflict}。
   * </ol>
   *
   * <p>与设计不符：设计书 §4.3/I5 与 {@code EconomyEntrySettlement} 类注（"planEntries 只读""真实状态一字不动"）、 F1 账本
   * §5-1（"写副作用仍只发生在 execute→applyIntent"）。
   *
   * <p>★★ <b>状态：已由 F5 修复批转绿</b>（`impl-fix/f5-batch-impl-ledger.md`：`WriteMode.SIMULATED_ONLY` =
   * 只写私有副本表， `WriteMode.REAL` 才调 `registerPlan`/`createInstance`）。本用例是**修复的验收判据**：三条断言必须一直绿；
   * 若有人把模拟路径改回真写，它会当场红（变异④也覆盖 plan-unknown 腿）。
   */
  @Test
  void planEntriesMustBeReadOnlyAndExecuteMustActuallyEnter() {
    Fixture fixture = fixture(100L, true);
    ProductionRuntimeEngine runtime = fixture.runtime();
    ProductionData before = runtime.state();

    EconomyEntrySettlement.Plan plan = planEntries(fixture);

    assertThat(runtime.state())
        .as("★ 缺陷：planEntries 必须是纯意向段（真实 production 状态一字不动）")
        .isEqualTo(before);
    assertThat(
            ((io.mosire.simos.production.change.ProductionChangeSet) runtime.changeSet()).isEmpty())
        .as("★ 缺陷：意向段不得产生变更集")
        .isTrue();

    EconomyEntrySettlement.Execution execution = execute(fixture, plan.intents());
    assertThat(execution.changed()).as("★ 缺陷：execute 必须真正完成进入（当前 unit-conflict）").isTrue();
    assertThat(execution.enteredUnitIds())
        .as("★ 缺陷：enteredUnitIds 必须含新实例（当前为空）")
        .contains(ProductionUnitId.idOf(materializedPlan(), HouseholdActors.of(HOUSE)));
  }

  // ── ② 资产约束时 trialScale = min(10, probe.scale())（probe.scale < 10 的一侧）──────────

  @Test
  void assetConstrainedCandidateUsesTrialScaleEqualsProbeScaleAndTheLinearSplitLeg() {
    Fixture fixture = fixture(5L, true);

    EconomyEntrySettlement.Plan plan = planEntries(fixture);
    assertThat(plan.outcomes()).as("5 单位 LAND 仍可生产（不是资产不足）").isEmpty();
    assertThat(plan.intents()).hasSize(1);
    EconomyEntrySettlement.EntryIntent intent = plan.intents().get(0);

    // 手算：baseScale = min(劳动 100, 资产 5) = 5 ⇒ probe.scale=5；trialScale = min(10, 5) = 5；
    //   required = baseScale 5 × capacity 1 = 5；need = 5 × 5 ÷ 5 = 5；laborMilli = laborRequired(5)
    // × 5 ÷ 5 = 5。
    assertThat(intent.trialScale()).as("★ 判据②：min(10, probe.scale=5) = 5").isEqualTo(5L);
    assertThat(intent.grants()).hasSize(1);
    assertThat(intent.grants().get(0).quantity()).as("need = 5×5÷5 = 5").isEqualTo(5L);
    assertThat(intent.laborMilli()).isEqualTo(5L);
  }

  // ── ③ 空占用仍先给 NO_ASSET_REQUIREMENT（既有拒绝顺序不变）─────────────────────────────

  @Test
  void emptyOccupancyStillYieldsNoAssetRequirementBeforeProbeScaleZero() {
    Fixture fixture = fixture(0L, false);

    EconomyEntrySettlement.Plan plan = planEntries(fixture);

    assertThat(plan.intents()).as("没有任何资产 ⇒ 不进产").isEmpty();
    assertThat(plan.outcomes()).hasSize(1);
    assertThat(plan.outcomes().get(0).reason())
        .as("★ 判据③：拒绝顺序不变 —— 先 NO_ASSET_REQUIREMENT，不是 PROBE_SCALE_ZERO")
        .startsWith("NO_ASSET_REQUIREMENT");
    assertThat(plan.outcomes().get(0).reason()).doesNotContain("PROBE_SCALE_ZERO");
    assertThat(fixture.runtime().state()).as("★ 拒绝同样零残留").isEqualTo(fixture.initialState());
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────────

  private record Fixture(
      ProductionRuntimeEngine runtime,
      ProductionData initialState,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<DemandId, HouseholdDemand> demands,
      Map<HouseholdId, HouseholdEconomy> households,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> shares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<IndustryId, ActorRef> planAssetSources,
      Map<MarketZoneId, MarketTable> marketTables,
      AssetShareId sourceShareId,
      long day) {}

  /**
   * @param sourceLand ★ ESTATE 提供的 LAND 数量（0 ⇒ 不建来源份额，用于"空占用"负向）
   * @param withAssetSource 是否声明 {@code planAssetSources}（unbound 方案的外部资产来源）
   */
  private static Fixture fixture(long sourceLand, boolean withAssetSource) {
    IndustryDefinitionView plan =
        new IndustryDefinitionView(
            PLAN,
            "r6 织造",
            new RegimeId("household"),
            Optional.empty(),
            1L,
            0L,
            Map.of(AssetKind.LAND, 1L),
            Map.of(),
            0L,
            1L,
            Map.of(CLOTH, 1L),
            Map.of(),
            List.of(new ClassSlot(POOR, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            ProductionExperienceUse.NONE,
            List.of(),
            Set.of(AssetRightKind.OWNED));
    Map<IndustryId, IndustryDefinitionView> industries = Map.of(PLAN, plan);
    ProductionData definitions =
        ProductionData.empty().withModes(DefaultProductionModes.modes()).withIndustry(plan);
    ProductionRuntimeEngine runtime = new ProductionRuntimeEngine(definitions);

    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    AssetShareId sourceShareId = null;
    if (sourceLand > 0L) {
      OwnershipStake land =
          new OwnershipStake(
              OwnershipStake.idOf(
                  LAND_INDUSTRY, AssetKind.LAND, ESTATE, ESTATE, AssetRightKind.OWNED, 0L),
              LAND_INDUSTRY,
              AssetKind.LAND,
              ESTATE,
              ESTATE,
              sourceLand,
              AssetRightKind.OWNED);
      sourceShareId = land.id();
      shares.put(sourceShareId, land);
    }

    HouseholdEconomy row =
        new HouseholdEconomy(
            HOUSE,
            new CohortKey(H, ResidenceKind.RURAL, POOR),
            10L,
            100L,
            1000,
            0L,
            Map.of(),
            Map.of(),
            0L);
    Map<HouseholdId, HouseholdEconomy> households = Map.of(HOUSE, row);
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = Map.of(HOUSE, Map.of());
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = Map.of(HOUSE, Map.of());
    Map<HouseholdId, Map<PeopleLotId, Long>> composition = Map.of(HOUSE, Map.of(LOT, 10L));
    Map<DemandId, HouseholdDemand> demands =
        Map.of(
            new DemandId("r6-demand"),
            new HouseholdDemand(
                new DemandId("r6-demand"),
                HouseholdDemand.DemandScope.HEX,
                Optional.empty(),
                Optional.of(H),
                CLOTH,
                HouseholdDemand.DemandKind.RECURRING,
                HouseholdDemand.DemandUnit.TOTAL,
                1_000L,
                0L,
                -1L,
                0,
                "r6-test"));
    Map<IndustryId, ActorRef> planAssetSources = withAssetSource ? Map.of(PLAN, ESTATE) : Map.of();
    Map<MarketZoneId, MarketTable> marketTables =
        EconomyFixtures.hexMarkets(
            Map.of(H, EconomyFixtures.quote(SILVER, Map.of(CLOTH, 10L, GRAIN, 10L))));
    return new Fixture(
        runtime,
        runtime.state(),
        industries,
        demands,
        households,
        householdGoods,
        householdMoney,
        composition,
        new LinkedHashMap<>(),
        new LinkedHashMap<>(shares),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        planAssetSources,
        marketTables,
        sourceShareId,
        1L);
  }

  private static EconomyEntrySettlement.Plan planEntries(Fixture fixture) {
    return EconomyEntrySettlement.planEntries(
        fixture.runtime(),
        fixture.demands(),
        fixture.industries(),
        fixture.planAssetSources(),
        Map.of(),
        SettlementIndex.build(
            fixture.units(),
            fixture.industries(),
            fixture.shares(),
            fixture.laborCommitments(),
            fixture.households(),
            Map.of(),
            fixture.relations()),
        MarketIndex.of(fixture.marketTables(), Map.of()),
        fixture.households(),
        fixture.householdGoods(),
        fixture.householdMoney(),
        fixture.composition(),
        fixture.units(),
        fixture.shares(),
        fixture.laborCommitments(),
        fixture.operatorConditions(),
        fixture.relations(),
        fixture.day());
  }

  private static EconomyEntrySettlement.Execution execute(
      Fixture fixture, List<EconomyEntrySettlement.EntryIntent> intents) {
    // ★ 工作副本（execute 就地写回；与日结算的形态一致）。
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>(fixture.units());
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>(fixture.shares());
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments =
        new LinkedHashMap<>(fixture.laborCommitments());
    Map<ProductionUnitId, OperatorCondition> conditions =
        new LinkedHashMap<>(fixture.operatorConditions());
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>(fixture.relations());
    return EconomyEntrySettlement.execute(
        fixture.runtime(),
        intents,
        fixture.demands(),
        fixture.industries(),
        fixture.planAssetSources(),
        Map.of(),
        units,
        shares,
        commitments,
        conditions,
        relations,
        fixture.households(),
        fixture.householdGoods(),
        fixture.householdMoney(),
        fixture.composition(),
        MarketIndex.of(fixture.marketTables(), Map.of()),
        fixture.day());
  }

  private static IndustryId materializedPlan() {
    return IndustryHexKeys.id(PLAN.value(), H.q(), H.r());
  }
}
