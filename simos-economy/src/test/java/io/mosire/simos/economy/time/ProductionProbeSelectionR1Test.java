package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionCreationRequest;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>R1（§15.1 / §15.12）：两把估计/排序链的物理量必须取自 {@code ProductionRuntime.probe}</b> （D-032 修复轮 T9
 * 批；期望值按 §15.1 的负向判据 ①–④ 与 F2 账本 §3 的中性推导手算）。
 *
 * <p>★★ <b>缺陷现场（回代码核过）</b>：{@code ExpectedProfitBook.productionProspect} 自算 {@code
 * scale=min(资产,劳动)}、{@code plannedOutput=outputPerUnit×scale×1000}；{@code LaborQueueBook.offer} 的
 * {@code outputValue/net} 同样直接按旧配方单产×市价算 ⇒ 熟练度/GM 覆盖/解锁条件都不进选择链（复现：预计 100、实际 200）。
 *
 * <p>★ <b>四条判据</b>：① 熟练度 2 的候选排序高于熟练度 1（且预计产出按 E 严格增大）；② GM 产出覆盖 ×2 ⇒ 预计与实际都 ×2； ③ {@code locked}
 * 候选不进选择链（{@code offer.maxAbsorbable=0} + 具名 {@code PROBE_REJECTED:locked} + 队列不给 grant）； ④
 * 中性世界（E=1000/TOTAL/无覆盖/库存足）下 {@code Offer}/{@code Prospect} 的关键字段与 F2 账本 §3 推导的旧口径
 * <b>逐字段相等</b>（双侧对照）。
 */
class ProductionProbeSelectionR1Test {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final ExperienceTrackId TRACK = new ExperienceTrackId("r1-farming");

  private static final HouseholdId MASTER_LOW = HouseholdId.parse("hh-r1-low");
  private static final HouseholdId MASTER_HIGH = HouseholdId.parse("hh-r1-high");

  private static final IndustryId CANDIDATE_PLAN = IndustryHexKeys.id("r1-weave", 0, 0);
  private static final ProductionModeId MODE = DefaultProductionModes.HANDICRAFT_WORKSHOP;
  private static final ClassPositionId POSITION =
      DefaultProductionModes.positionId(MODE, DefaultProductionModes.ROLE_WORKSHOP_OWNER)
          .orElseThrow();

  /** 效率公式 {@code 1000 + a}（a = 专门经验条熟练度，千分制）：熟练度 500 ⇒ E=1500、1000 ⇒ E=2000。 */
  private static final ExpressionDefinition ONE_PLUS_MASTERY =
      new ExpressionDefinition(
          ExpressionDefinition.CURRENT_VERSION,
          List.of(ProductionExperienceUse.PRIMARY_MASTERY_INPUT),
          new RestrictedExpression.Add(
              new RestrictedExpression.Const(1_000L),
              new RestrictedExpression.Input(ProductionExperienceUse.PRIMARY_MASTERY_INPUT)));

  // ── ① 熟练度进入候选排序：熟练度 2 的候选排序高于熟练度 1 ─────────────────────────────────

  @Test
  void higherMasteryCandidateRanksAboveAndPlansStrictlyMoreOutput() {
    ProductionData definitions = masteryWorld();
    ProductionRuntimeEngine runtime = new ProductionRuntimeEngine(definitions);

    ExpectedProfitBook.Prospect low =
        prospect(definitions, runtime, MASTER_LOW, sharesFor(MASTER_LOW, CANDIDATE_PLAN));
    ExpectedProfitBook.Prospect high =
        prospect(definitions, runtime, MASTER_HIGH, sharesFor(MASTER_HIGH, CANDIDATE_PLAN));

    // 手算：baseScale=100（劳动 100,000÷1,000=100、资产 100÷1=100）、cycle=1；
    //   熟练度 500 ⇒ E=1500 ⇒ finalScale=150 ⇒ plannedOutput=150×1×1000=150,000；
    //   熟练度 1000 ⇒ E=2000 ⇒ finalScale=200 ⇒ plannedOutput=200,000。
    assertThat(low.plannedOutputMilli())
        .as("熟练度 500 ⇒ E=1500 ⇒ 预计产出 150,000 毫（旧口径恒按配方 100,000）")
        .containsEntry(GRAIN, 150_000L);
    assertThat(high.plannedOutputMilli())
        .as("熟练度 1000 ⇒ E=2000 ⇒ 预计产出 200,000 毫")
        .containsEntry(GRAIN, 200_000L);
    assertThat(low.feasibleScale()).as("产出规模随熟练度增大（150 → 200）").isEqualTo(150L);
    assertThat(high.feasibleScale()).isEqualTo(200L);

    assertThat(high.netPerLaborScaled())
        .as("★ 判据①：熟练度 2 的候选排序键（预期单位劳动净收益）必须高于熟练度 1")
        .isGreaterThan(low.netPerLaborScaled())
        .isEqualTo(16_560L);
    assertThat(low.netPerLaborScaled())
        .as(
            "手算：bid=9（报价 10 − 价差 1）⇒ revenue=(150,000×9)÷1000=1350；"
                + "laborCost=100,000×144×10÷1e6=144；rent=0（无分账规则）；"
                + "net=1206；perLabor=⌊1206×1e6÷100,000⌋=12,060")
        .isEqualTo(12_060L);
  }

  // ── ② GM 产出覆盖 ×2 ⇒ 预计与实际都 ×2（同一份投入、同一入口）────────────────────────────

  @Test
  void gmOutputOverrideDoublesBothTheProspectAndTheActualHarvest() {
    ProductionData plain = overrideWorld(1L);
    ProductionData doubled = overrideWorld(2L);

    IndustryId overridePlan = plain.industries().keySet().iterator().next();
    ExpectedProfitBook.Prospect plainProspect =
        prospect(
            plain,
            new ProductionRuntimeEngine(plain),
            MASTER_LOW,
            sharesFor(MASTER_LOW, overridePlan));
    ExpectedProfitBook.Prospect doubledProspect =
        prospect(
            doubled,
            new ProductionRuntimeEngine(doubled),
            MASTER_LOW,
            sharesFor(MASTER_LOW, overridePlan));

    assertThat(plainProspect.plannedOutputMilli()).containsEntry(GRAIN, 100_000L);
    assertThat(doubledProspect.plannedOutputMilli())
        .as("★ 判据②：GM 覆盖 1→2 ⇒ 预计产出 100,000 → 200,000（旧口径不读覆盖）")
        .containsEntry(GRAIN, 200_000L);

    // 实际：同一份投入（劳动 100,000、占用 WORKSHOP 100、无原料；laborPerUnit=1,000 ⇒ baseScale=100）
    // ⇒ 与预计同源（I6）。
    long plainActual = harvestOf(plain, 100_000L);
    long doubledActual = harvestOf(doubled, 100_000L);
    assertThat(plainActual).as("实际（无覆盖）：baseScale=100、E=1000 ⇒ 100×1×1000").isEqualTo(100_000L);
    assertThat(doubledActual).as("★ 判据②：实际执行同样吃 GM 覆盖 ⇒ 100×2×1000 = 200,000").isEqualTo(200_000L);
    assertThat(plainActual)
        .as("★ I6：预计与实际同源（无覆盖时逐值相等）")
        .isEqualTo(plainProspect.plannedOutputMilli().get(GRAIN));
    assertThat(doubledActual)
        .as("★ I6：有覆盖时预计与实际也逐值相等")
        .isEqualTo(doubledProspect.plannedOutputMilli().get(GRAIN));
  }

  // ── ③ locked 候选不进选择链（offer + 队列都不给它 grant）───────────────────────────────

  @Test
  void lockedCandidateDoesNotEnterTheLaborSelectionChain() {
    OfferWorld locked = offerWorld(100L, 0L);
    LaborQueueBook.Offer lockedOffer = offerOf(locked);
    assertThat(lockedOffer.maxAbsorbableLaborMilli())
        .as("★ 判据③：probe 具名 locked ⇒ 最大可吸收劳动 0（不进利润队列）")
        .isZero();
    assertThat(lockedOffer.reason()).isEqualTo("PROBE_REJECTED:locked");
    assertThat(lockedOffer.queueable()).as("不可排队").isFalse();

    LaborQueueBook.Plan plan = LaborQueueBook.plan(MASTER_LOW, 10_000L, 0L, List.of(lockedOffer));
    assertThat(plan.decisions()).hasSize(1);
    assertThat(plan.decisions().get(0).outcome()).isEqualTo("IDLE_MAX_ABSORBABLE_ZERO");
    assertThat(plan.decisions().get(0).grantedLaborMilli()).as("队列不给任何劳动").isZero();

    // 正对照：同一世界去掉解锁门槛（unlockAt=0）⇒ 同一个 offer 可排队、拿到劳动（证明拒绝的原因是 locked）。
    OfferWorld unlocked = offerWorld(0L, 0L);
    LaborQueueBook.Offer unlockedOffer = offerOf(unlocked);
    assertThat(unlockedOffer.maxAbsorbableLaborMilli())
        .as("解锁后可用（usableScale=1 × laborPerUnit=1000）")
        .isEqualTo(1_000L);
    assertThat(unlockedOffer.reason()).isEmpty();
    LaborQueueBook.Plan unlockedPlan =
        LaborQueueBook.plan(MASTER_LOW, 10_000L, 0L, List.of(unlockedOffer));
    assertThat(unlockedPlan.decisions().get(0).outcome()).isEqualTo("GRANTED");
    assertThat(unlockedPlan.decisions().get(0).grantedLaborMilli()).isEqualTo(1_000L);
  }

  /**
   * ★★ 判据①的 offer 侧载体：<b>熟练度进 offer 的每规模物理量</b>。
   *
   * <p>手算（REF=1000）：probe 合成 baseScale=1000、E=1000+500=1500 ⇒ finalScale=1500 ⇒
   * expectedOutput=1500×1×1000=1,500,000 ⇒ perScaleOutput=1,500 ⇒
   * outputValue=1,500×9÷1000=13、net=13、 key=⌊13×1e6÷1,000⌋=13,000。★ 旧配方（单产×价）会给 9 —— 判别点。
   */
  @Test
  void offerReadsHouseholdMasteryIntoItsPerScalePhysics() {
    LaborQueueBook.Offer mastered = offerOf(offerWorld(0L, 500L));

    assertThat(mastered.maxAbsorbableLaborMilli()).isEqualTo(1_000L);
    assertThat(mastered.expectedOutputValueMilli())
        .as("★ 熟练度 500 ⇒ E=1500 ⇒ 每规模产出 1.5 单位 ⇒ 13（旧配方 9）")
        .isEqualTo(13L);
    assertThat(mastered.expectedNetMilli()).isEqualTo(13L);
    assertThat(mastered.expectedLaborMilli())
        .as("laborRequired 是基准规模口径 ⇒ 每 1 基准规模仍 1,000（熟练度只放大产出规模）")
        .isEqualTo(1_000L);
    assertThat(mastered.netPerLaborScaled()).as("⌊13×1e6÷1,000⌋ = 13,000").isEqualTo(13_000L);
  }

  // ── ④ 中性世界双侧对照：Offer / Prospect 关键字段逐值等于 F2 账本 §3 的旧口径推导 ─────────

  @Test
  void neutralOfferMatchesTheOldFormulaDerivationFieldByField() {
    LaborQueueBook.Offer offer = offerOf(offerWorld(0L, 0L));

    // F2 账本 §3.1 的中性推导（E=1000、TOTAL、无覆盖、无 availability、库存足）：
    //   perScaleOutput=1×1000/1000=1 ⇒ outputValue=1×9=9（bid = 报价 10 − 价差 1）；inputCost=0；net=9；
    //   perScaleLabor=1000/1000=1 ⇒ expectedLabor=1；key=⌊9×1e6÷1⌋=9,000；
    //   maxAbsorbable=usableScale(1)×laborPerUnit(1000)=1000。
    assertThat(offer.maxAbsorbableLaborMilli())
        .as("旧口径：usableScale × laborPerUnit = 1×1000")
        .isEqualTo(1_000L);
    assertThat(offer.outputPriced()).isTrue();
    assertThat(offer.expectedOutputValueMilli())
        .as("旧口径：Σ(单产×买入价) = 1×9 = 9（毫钱/规模；bid = 报价 10 − 价差 1）")
        .isEqualTo(9L);
    assertThat(offer.expectedInputCostMilli()).as("旧口径：无原料 ⇒ 0").isZero();
    assertThat(offer.expectedNetMilli()).as("旧口径：9 − 0 = 9").isEqualTo(9L);
    assertThat(offer.expectedLaborMilli())
        .as("旧口径：每 1 基准规模劳动 = laborPerUnit = 1000")
        .isEqualTo(1_000L);
    assertThat(offer.netPerLaborScaled())
        .as("旧口径：⌊9 × 1e6 ÷ max(1,1000)⌋ = 9,000")
        .isEqualTo(9_000L);
    assertThat(offer.reason()).as("旧词表：中性世界没有理由").isEmpty();
  }

  @Test
  void neutralProspectMatchesTheOldFormulaDerivationFieldByField() {
    NeutralProspectWorld world = neutralProspectWorld();
    ExpectedProfitBook.Prospect p =
        ExpectedProfitBook.prospect(
            EconomyFixtures.dayView(world.base(), world.definitions()),
            MASTER_LOW,
            MODE,
            H,
            world.industry(),
            world.market(),
            world.demand(),
            world.shares(),
            Set.of(),
            world.accounts(),
            Map.of(),
            Map.of(),
            world.topology(),
            0L,
            world.runtime());

    // F2 账本 §3.2 的中性推导（旧口径 min(资产 3, 劳动 10,000÷1,000=10)=3）：
    //
    // plannedOutput=2×3×1000=6,000；selfConsumed=1,000；sellable=3,000；revenue=(1,000+3,000)×9÷1000=36；
    //   inputCost=5,000×3×21÷1000=315；laborNeed=1,000×3×10=30,000；laborCost=30,000×144×10÷1e6=43；
    //
    // rent=21（默认分账估算；新旧同式）；net=36−315−43−21=−343；perLabor=⌊−343×1e6÷30,000⌋=−11,434；DEMAND_CAPPED。
    //   （bid = 报价 10 − 价差 1；fiber ask = 报价 20 + 价差 1 = 21 —— 与 ExpectedProfitBookTest 的既有手算一致。）
    assertThat(p.feasible()).as("前置：中性世界必须可行（否则下面的逐值断言失去判别力）").isTrue();
    assertThat(p.feasibleScale()).as("旧口径：scale = min(资产 3, 劳动 10) = 3").isEqualTo(3L);
    assertThat(p.plannedOutputMilli()).containsEntry(CLOTH, 6_000L);
    assertThat(p.selfConsumedMilli()).containsEntry(CLOTH, 1_000L);
    assertThat(p.sellableMilli()).containsEntry(CLOTH, 3_000L);
    assertThat(p.revenueMilli()).as("(1,000+3,000) × 9 ÷ 1000 = 36").isEqualTo(36L);
    assertThat(p.inputCostMilli()).as("15,000 × 21 ÷ 1000 = 315").isEqualTo(315L);
    assertThat(p.laborNeedMilli()).as("1,000 × 3 × 10 = 30,000").isEqualTo(30_000L);
    assertThat(p.laborCostMilli()).as("30,000 × 144 × 10 ÷ 1e6 = 43").isEqualTo(43L);
    assertThat(p.netMilli())
        .as("36 − 315 − 43 − 21 = −343（−21 = 默认分账估算 rent；该估算法在旧/新两侧同一份）")
        .isEqualTo(-343L);
    assertThat(p.netPerLaborScaled()).as("⌊−343 × 1e6 ÷ 30,000⌋ = −11,434").isEqualTo(-11_434L);
    assertThat(p.demandCapped()).isTrue();
    assertThat(p.reason())
        .as("默认分账估算会带 SHARE_WEIGHT_ESTIMATE（旧/新同式），需求上限照旧")
        .contains("DEMAND_CAPPED");
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────────

  /** 熟练度世界：同一方案，仅经验账不同（500 / 1000），效率公式 {@code 1000 + a}。 */
  private static ProductionData masteryWorld() {
    IndustryDefinitionView plan =
        craftPlan(
            CANDIDATE_PLAN,
            1L,
            Map.of(),
            1L,
            new ProductionExperienceUse(
                Optional.of(TRACK),
                Optional.empty(),
                ProductionEffectScope.TOTAL,
                ONE_PLUS_MASTERY,
                0L));
    return ProductionData.empty()
        .withModes(DefaultProductionModes.modes())
        .withExperienceTrack(track())
        .withIndustry(plan)
        .withHouseholdExperience(MASTER_LOW, TRACK, new ProductionExperienceFeature(500L, 0L))
        .withHouseholdExperience(MASTER_HIGH, TRACK, new ProductionExperienceFeature(1_000L, 0L));
  }

  /** 覆盖世界：每规模产 1 谷（GM 覆盖替换成参数值 1 或 2），中性效率。 */
  private static ProductionData overrideWorld(long override) {
    IndustryId id = IndustryHexKeys.id("r1-override", 0, 0);
    IndustryDefinitionView plan = craftPlan(id, 1L, Map.of(), 1L, ProductionExperienceUse.NONE);
    return ProductionData.empty()
        .withModes(DefaultProductionModes.modes())
        .withIndustry(plan)
        .withOutputQuantityOverride(id, GRAIN, override);
  }

  /** 实际收获（同一份投入：劳动 100、占用 LAND 100、无原料；方案已登记、地点一致 ⇒ 走创建分支）。 */
  private static long harvestOf(ProductionData definitions, long laborMilli) {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(definitions);
    IndustryId planId = definitions.industries().keySet().iterator().next();
    ProductionUnitId unit = new ProductionUnitId("unit-r1-override");
    ProductionExecutionOutcome.Executed executed =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                new ProductionExecutionRequest(
                    Optional.empty(),
                    Optional.of(
                        new ProductionCreationRequest(
                            planId,
                            unit,
                            new ProductionLocation("0_0"),
                            HouseholdActors.of(MASTER_LOW),
                            "mode:" + planId.value())),
                    Long.MAX_VALUE,
                    List.of(
                        new ParticipantContribution(
                            MASTER_LOW, ProductionRoleId.parse("operator"), laborMilli)),
                    Map.of(AssetKind.WORKSHOP, 100L),
                    Map.of(),
                    ExternalAttributeFacts.EMPTY,
                    Map.of(),
                    Map.of(MASTER_LOW, laborMilli),
                    Map.of()));
    return executed.receipt().grossOutput().getOrDefault(GRAIN, 0L);
  }

  // ── Offer 世界（LaborQueueBook.offer 直接调用）──────────────────────────────────────────

  private record OfferWorld(
      IndustryDefinitionView industry,
      ProductionProcess unit,
      SettlementIndex index,
      MarketTable market,
      EconomySession session) {}

  /**
   * usableScale=1（WORKSHOP=1）、laborPerUnit=1000、无原料；{@code unlockAt}/{@code experience} 决定资格与 E。
   */
  private static OfferWorld offerWorld(long unlockAt, long experience) {
    IndustryId id = IndustryHexKeys.id("r1-offer", 0, 0);
    IndustryDefinitionView plan =
        craftPlan(
            id,
            1L,
            Map.of(),
            1L,
            new ProductionExperienceUse(
                Optional.of(TRACK),
                Optional.empty(),
                ProductionEffectScope.TOTAL,
                ONE_PLUS_MASTERY,
                unlockAt));
    ActorRef operator = HouseholdActors.of(MASTER_LOW);
    ProductionUnitId unitId = ProductionUnitId.idOf(id, operator);
    ProductionProcess unit =
        new ProductionProcess(
            unitId,
            id,
            new ProductionLocation("0_0"),
            operator,
            "mode:r1-offer",
            0L,
            Map.of(),
            Map.of());
    Map<ProductionUnitId, ProductionProcess> units = Map.of(unitId, unit);
    Map<IndustryId, IndustryDefinitionView> industries = Map.of(id, plan);
    OwnershipStake workshopShare =
        new OwnershipStake(
            OwnershipStake.idOf(
                id,
                AssetKind.WORKSHOP,
                operator,
                operator,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            id,
            AssetKind.WORKSHOP,
            operator,
            operator,
            1L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    Map<AssetShareId, OwnershipStake> shares = Map.of(workshopShare.id(), workshopShare);
    HouseholdEconomy row = row(MASTER_LOW, 10_000L, Map.of());
    Map<HouseholdId, HouseholdEconomy> households = Map.of(MASTER_LOW, row);
    SettlementIndex index =
        SettlementIndex.build(units, industries, shares, Map.of(), households, Map.of(), Map.of());
    MarketTable market = market();
    ProductionData definitions =
        ProductionData.empty()
            .withModes(DefaultProductionModes.modes())
            .withExperienceTrack(track())
            .withIndustry(plan)
            .withInstance(unit)
            .withHouseholdExperience(
                MASTER_LOW, TRACK, new ProductionExperienceFeature(experience, 0L));
    EconomyData base =
        EconomyData.empty()
            .withHouseholdEconomies(households)
            .withOwnershipStakes(shares)
            .withMarketTables(EconomyFixtures.hexMarkets(Map.of(H, market)));
    EconomySession session =
        new EconomySession(base, definitions, new ProductionRuntimeEngine(definitions));
    return new OfferWorld(plan, unit, index, market, session);
  }

  private static LaborQueueBook.Offer offerOf(OfferWorld world) {
    return LaborQueueBook.offer(
        world.unit(),
        world.industry(),
        world.market(),
        null,
        world.index(),
        Optional.of(MODE),
        MODE.value(),
        MASTER_LOW,
        ProductionRoleId.parse("operator"),
        world.session());
  }

  // ── Prospect 世界（ExpectedProfitBook.prospect 直接调用）─────────────────────────────────

  private record NeutralProspectWorld(
      EconomyData base,
      ProductionData definitions,
      IndustryDefinitionView industry,
      MarketTable market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      AccountSession accounts,
      MarketTopology topology,
      ProductionRuntimeEngine runtime) {}

  /** 中性世界：craft 配方（cycle=10、FIBER 5,000/规模、CLOTH 2/规模、labor 1,000）、无经验、库存足。 */
  private static NeutralProspectWorld neutralProspectWorld() {
    IndustryId id = IndustryHexKeys.id("r1-craft", 0, 0);
    IndustryDefinitionView craft =
        new IndustryDefinitionView(
            id,
            "r1 craft",
            new RegimeId("handicraft"),
            Optional.empty(),
            10L,
            0L,
            Map.of(AssetKind.WORKSHOP, 1L),
            Map.of(),
            0L,
            1_000L,
            Map.of(CLOTH, 2L),
            Map.of(AssetKind.WORKSHOP, Map.of(FIBER, 5_000L)),
            List.of(new ClassSlot(POOR, "贫农", 1_000)),
            new AllocationRule.Split(1_000, 0),
            MoneyOutput.NONE,
            ProductionExperienceUse.NONE,
            List.of(),
            Set.of(AssetRightKind.OWNED));
    ActorRef operator = HouseholdActors.of(MASTER_LOW);
    OwnershipStake workshop =
        new OwnershipStake(
            OwnershipStake.idOf(
                id,
                AssetKind.WORKSHOP,
                operator,
                operator,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            id,
            AssetKind.WORKSHOP,
            operator,
            operator,
            3L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    Map<AssetShareId, OwnershipStake> shares = Map.of(workshop.id(), workshop);

    MarketTable market = market();
    Map<MarketZoneId, MarketTable> marketTables = EconomyFixtures.hexMarkets(Map.of(H, market));
    MarketTopology topology = MarketTopology.singleHex(marketTables, Map.of());
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(
                Map.of(MASTER_LOW, row(MASTER_LOW, 10_000L, Map.of(CLOTH, 100L))))
            .withClassMemberships(
                Map.of(
                    MASTER_LOW,
                    new HouseholdClassMembership(
                        MASTER_LOW, POSITION, POSITION, Map.of(), 0L, 0L, "r1-neutral")))
            .withOwnershipStakes(shares)
            .withMarketTables(marketTables);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(MASTER_LOW, H, Map.of(FIBER, 15_000L), Map.of(), Map.of(), Map.of());
    ProductionData definitions =
        ProductionData.empty().withModes(DefaultProductionModes.modes()).withIndustry(craft);
    return new NeutralProspectWorld(
        base,
        definitions,
        craft,
        market,
        demandBook(CLOTH, 4_000L),
        shares,
        accounts,
        topology,
        new ProductionRuntimeEngine(definitions));
  }

  /** 候选 prospect：GRAIN 单产出（1/规模）、labor 1,000、LABOR 恒等（E 由熟练度公式给）。 */
  private static ExpectedProfitBook.Prospect prospect(
      ProductionData definitions,
      ProductionRuntimeEngine runtime,
      HouseholdId household,
      Map<AssetShareId, OwnershipStake> shares) {
    IndustryDefinitionView plan = definitions.industries().values().iterator().next();
    MarketTable market = market();
    Map<MarketZoneId, MarketTable> marketTables = EconomyFixtures.hexMarkets(Map.of(H, market));
    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(Map.of(household, row(household, 100_000L, Map.of())))
            .withClassMemberships(
                Map.of(
                    household,
                    new HouseholdClassMembership(
                        household, POSITION, POSITION, Map.of(), 0L, 0L, "r1")))
            .withOwnershipStakes(shares)
            .withMarketTables(marketTables);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(household, H, Map.of(), Map.of(), Map.of(), Map.of());
    return ExpectedProfitBook.prospect(
        EconomyFixtures.dayView(base, definitions),
        household,
        MODE,
        H,
        plan,
        market,
        demandBook(GRAIN, 1_000_000L),
        shares,
        Set.of(),
        accounts,
        Map.of(),
        Map.of(),
        MarketTopology.singleHex(marketTables, Map.of()),
        0L,
        runtime);
  }

  /** 某户在候选方案上的自营 WORKSHOP 份额（candidateUsableAssets 的 own 判据 = operator 为该户 actor）。 */
  private static Map<AssetShareId, OwnershipStake> sharesFor(
      HouseholdId household, IndustryId planId) {
    ActorRef actor = HouseholdActors.of(household);
    OwnershipStake share =
        new OwnershipStake(
            OwnershipStake.idOf(
                planId,
                AssetKind.WORKSHOP,
                actor,
                actor,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            planId,
            AssetKind.WORKSHOP,
            actor,
            actor,
            100L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    return Map.of(share.id(), share);
  }

  /**
   * 方案定义：{@code capacityPerUnit={LAND:1}}、{@code cycleDays} 与产出/投入按参数；其余（技能/日投入/铸币/可用性）取
   * "本来就没有"的显式值。
   */
  private static IndustryDefinitionView craftPlan(
      IndustryId id,
      long cycleDays,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      long outputPerUnit,
      ProductionExperienceUse use) {
    return new IndustryDefinitionView(
        id,
        "r1 " + id.value(),
        new RegimeId("handicraft"),
        Optional.empty(),
        cycleDays,
        0L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, outputPerUnit),
        cycleInputPerUnit,
        List.of(new ClassSlot(POOR, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        use,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  private static ExperienceTrack track() {
    return new ExperienceTrack(
        TRACK,
        "r1 farming",
        10_000L,
        0L,
        0L,
        ExpressionDefinition.ofSingleInput(
            ExperienceTrack.EXPERIENCE_INPUT,
            new RestrictedExpression.Input(ExperienceTrack.EXPERIENCE_INPUT)));
  }

  private static HouseholdEconomy row(
      HouseholdId household, long laborMilli, Map<CommodityId, Long> naturalNeeds) {
    return new HouseholdEconomy(
        household,
        new CohortKey(H, ResidenceKind.RURAL, POOR),
        10L,
        laborMilli,
        1000,
        0L,
        naturalNeeds,
        Map.of(),
        0L);
  }

  private static MarketTable market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    prices.put(FIBER, 20L);
    prices.put(CLOTH, 10L);
    return EconomyFixtures.quote(SILVER, prices);
  }

  private static MarketDemandBook.Book demandBook(CommodityId commodity, long addressable) {
    return new MarketDemandBook.Book(
        Map.of(
            H,
            Map.of(
                commodity,
                new MarketDemandBook.Demand(
                    H, commodity, 0, 0, 0, 0, 0, 0, addressable, List.of("r1-test")))),
        0L,
        10L);
  }
}
