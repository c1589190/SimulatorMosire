package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z1b/Z3a C7 行为契约：{@code GOV_SERVICE} 不可缩、不进队列、不被覆盖</b>（设计书 §10 C7 / §17.2； z1b 台账 §10.2、z3a
 * 台账 §4/§7.2.8）。
 *
 * <p>本类把五条"会缩劳动"的路径逐条钉住，并全部用**负向**断言（缩了/删了/静默兜底都要红）：
 *
 * <ol>
 *   <li>{@link LaborQueueSettlement}：带 {@code GOV_SERVICE} 的 activity 不进队列、行逐值不变；队列只写 {@code
 *       PRODUCTION}；确定性 id 撞上 GOV 行 ⇒ 契约 ISE（不覆盖）；
 *   <li>{@link EconomySettlement#applyLaborBudgetsInto}：GOV 整额保留、只缩/删 PRODUCTION；ΣGOV &gt; 预算 = 合法
 *       underfed 态（Z7d-1：不抛、不缩、不删 GOV_SERVICE；PRODUCTION 可用量归 0）；
 *   <li>{@link EconomySettlement#applyPopulationChange} 的批次缩放（{@code scaleLaborOfGroup}）同上；
 *   <li>饥荒路径的 {@code scaleLaborOfUnit} 同上；
 *   <li>旧档 {@code reallocateLabor} 的 pass②（按 unit 最大可吸收量修剪）与 pass③（按家户预算封顶）同上。
 * </ol>
 *
 * <p>★ <b>反射说明</b>：{@code scaleLaborOfUnit}/{@code reallocateLabor} 是 {@code private static}
 * 生产实现（本区不得改生产代码）； 它们没有包内入口，故按 z3a 台账 §4.2 的探针同法用反射直调**真方法**（不是复刻算式）。每条反射调用都断言"方法签名找到" （{@code
 * getDeclaredMethod} 找不到会当场抛）。
 *
 * <p>★ <b>日志断言边界</b>：{@code LABOR_COMMITMENT_CONTRACT} ERROR 事件走 slf4j；economy 测试 scope 没有可捕获的
 * appender， 故字段断言落在与 ERROR 同源的具名 {@link IllegalStateException} 消息上（{@code
 * household/GOV_SERVICE/总承诺/预算/reason} 逐项）。
 */
class GovServiceCommitmentC7Test {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId H = HouseholdId.parse("hh-c7-contract");
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final ActorRef OP = HouseholdActors.of(H);
  private static final IndustryId FARM = IndustryHexKeys.id("farm", HEX.q(), HEX.r());
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, OP);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  private static final LaborAllocationId GOV = new LaborAllocationId("c7-gov-service");
  private static final LaborAllocationId PRODUCTION_1 = new LaborAllocationId("c7-production-1");
  private static final LaborAllocationId PRODUCTION_2 = new LaborAllocationId("c7-production-2");

  // ── ① 队列：GOV 不进队列 / 只改写 PRODUCTION / id 撞车 fail-closed ─────────────────────

  /** 带 GOV_SERVICE 的 activity 不进队列：行逐值不变；计划里既没有它的决定，也没有新发的 PRODUCTION 行。 */
  @Test
  void laborQueueSkipsGovServiceActivityAndKeepsTheRowWhole() {
    HouseholdLaborCommitment govService = commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L);
    EconomyData base = world(10_000L, Map.of(GOV, govService));
    EconomySession session = new EconomySession(base);

    LaborQueueReport report =
        LaborQueueSettlement.apply(session, indexOf(base), 1L, composition(), Set.of());

    assertThat(session.sheet().laborCommitments())
        .as("队列不得删除/改写 GOV_SERVICE 行，也不得为它新发 PRODUCTION")
        .containsOnlyKeys(GOV);
    assertThat(session.sheet().laborCommitments().get(GOV)).isEqualTo(govService);
    LaborQueueBook.Plan plan = report.planOf(H).orElseThrow();
    assertThat(plan.decisions()).as("GOV_SERVICE 的 unit 不进队列（没有任何决定）").isEmpty();
    assertThat(plan.preservedLaborMilli()).as("整额保留").isEqualTo(4_000L);
    assertThat(plan.allocatedLaborMilli()).isEqualTo(4_000L);
  }

  /** 正对照：同一 fixture 上的 PRODUCTION 行**会**被队列改写 ⇒ 上面那条负向断言不是空气。 */
  @Test
  void laborQueueStillRewritesProductionCommitmentsOnTheSameUnit() {
    HouseholdLaborCommitment production =
        commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 4_000L);
    EconomyData base = world(10_000L, Map.of(PRODUCTION_1, production));
    EconomySession session = new EconomySession(base);

    LaborQueueReport report =
        LaborQueueSettlement.apply(session, indexOf(base), 1L, composition(), Set.of());

    HouseholdLaborCommitment after = session.sheet().laborCommitments().get(PRODUCTION_1);
    assertThat(after).isNotNull();
    assertThat(after.kind()).as("队列只发/只改 PRODUCTION").isEqualTo(LaborCommitmentKind.PRODUCTION);
    assertThat(after.laborMilli()).as("规模 1 × 劳动 1000 = 最大可吸收 1000").isEqualTo(1_000L);
    assertThat(report.planOf(H).orElseThrow().decisions()).isNotEmpty();
  }

  /**
   * 确定性 id 撞车：GOV 行挂在"非 unit 活动词"上（于是不会把该 unit 从候选里排除），但它的 id 恰是队列要为该 unit 新发的 id ⇒ 队列必须具名
   * fail-closed，绝不覆盖 GOV 行（负向：GOV 行仍在、量不变）。
   */
  @Test
  void laborQueueRefusesToOverwriteGovServiceOnGeneratedIdCollision() {
    LaborAllocationId collided = HouseholdLaborCommitment.idOf(UNIT, LOT, H);
    HouseholdLaborCommitment govService =
        new HouseholdLaborCommitment(
            collided, LOT, H, OP, "free-work", 4_000L, 1L, LaborCommitmentKind.GOV_SERVICE);
    EconomyData base = world(10_000L, Map.of(collided, govService));
    EconomySession session = new EconomySession(base);

    assertThatThrownBy(
            () -> LaborQueueSettlement.apply(session, indexOf(base), 1L, composition(), Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("撞上既有 GOV_SERVICE")
        .hasMessageContaining(collided.value());

    assertThat(session.sheet().laborCommitments())
        .as("撞车必须 fail-closed：GOV 行不得被覆盖/删除")
        .containsOnlyKeys(collided);
    assertThat(session.sheet().laborCommitments().get(collided)).isEqualTo(govService);
  }

  // ── ② 每 tick 预算刷新 applyLaborBudgetsInto ──────────────────────────────────────────

  /** 预算缩到 8000：GOV 4000 整额保留；两条 PRODUCTION 各 3000 → 各 2000（逐值）。 */
  @Test
  void applyLaborBudgetsIntoKeepsGovWholeAndScalesOnlyProduction() {
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 3_000L));
    commitments.put(PRODUCTION_2, commitment(PRODUCTION_2, LaborCommitmentKind.PRODUCTION, 3_000L));
    EconomySession session = new EconomySession(world(10_000L, commitments));

    EconomySettlement.applyLaborBudgetsInto(session, Map.of(H, 8_000L));

    Map<LaborAllocationId, HouseholdLaborCommitment> after = session.sheet().laborCommitments();
    assertThat(after.get(GOV).kind()).isEqualTo(LaborCommitmentKind.GOV_SERVICE);
    assertThat(after.get(GOV).laborMilli()).as("GOV 整额保留").isEqualTo(4_000L);
    assertThat(after.get(PRODUCTION_1).laborMilli()).as("只缩 PRODUCTION").isEqualTo(2_000L);
    assertThat(after.get(PRODUCTION_2).laborMilli()).isEqualTo(2_000L);
    assertThat(after.values().stream().mapToLong(HouseholdLaborCommitment::laborMilli).sum())
        .isEqualTo(8_000L);
    assertThat(session.sheet().householdEconomies().get(H).laborMilli()).isEqualTo(8_000L);
  }

  /**
   * ★★ Z7d-1：ΣGOV &gt; 预算（饥饿把预算饿少）是**合法 underfed 态** —— GOV_SERVICE 是职位/诉求，不缩不删； PRODUCTION 的可用量 =
   * budget − min(ΣGOV, budget) = 0 ⇒ 整条删掉。不抛异常。
   */
  @Test
  void applyLaborBudgetsIntoAllowsGovServiceOverBudgetAndRemovesOnlyProduction() {
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 1_000L));
    EconomySession session = new EconomySession(world(10_000L, commitments));

    EconomySettlement.applyLaborBudgetsInto(session, Map.of(H, 3_000L));

    Map<LaborAllocationId, HouseholdLaborCommitment> after = session.sheet().laborCommitments();
    assertThat(after.get(GOV).kind()).isEqualTo(LaborCommitmentKind.GOV_SERVICE);
    assertThat(after.get(GOV).laborMilli()).as("GOV 承诺整额保留（underfed 合法）").isEqualTo(4_000L);
    assertThat(after)
        .as("PRODUCTION 可用量 = max(0, 3000 − 4000) = 0 ⇒ 整条删掉，不缩 GOV")
        .doesNotContainKey(PRODUCTION_1);
    assertThat(session.sheet().householdEconomies().get(H).laborMilli()).isEqualTo(3_000L);
  }

  // ── ③ 出生/死亡：applyPopulationChange 的批次缩放 scaleLaborOfGroup ─────────────────────

  /** 人口 100→99：GOV 4000 不动；PRODUCTION 8000→7920；家户劳动 20000→19800。 */
  @Test
  void applyPopulationChangeGroupScaleKeepsGovWholeAndScalesOnlyProduction() {
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 8_000L));
    EconomyData base = world(20_000L, commitments);

    EconomyData after =
        EconomySettlement.applyPopulationChange(base, List.of(new LotChange(LOT, HEX, 0L, 1L)));

    assertThat(after.classes().get(H).population()).isEqualTo(99L);
    assertThat(after.classes().get(H).laborMilli()).isEqualTo(19_800L);
    assertThat(after.allocations().get(GOV).laborMilli())
        .as("GOV_SERVICE 不参与死亡比例缩")
        .isEqualTo(4_000L);
    assertThat(after.allocations().get(PRODUCTION_1).laborMilli())
        .as("PRODUCTION 按存活比例缩：8000 × 99/100 = 7920")
        .isEqualTo(7_920L);
  }

  /**
   * ★★ Z7d-1：死亡把家户预算缩到 GOV 以下 —— GOV_SERVICE 是职位/诉求，不参与死亡比例缩（承诺 4000 &gt; 新预算 2000 合法）； 没有
   * PRODUCTION 行时统一校验放行（PRODUCTION 可用量为 0，本就没有可越界的东西）。
   */
  @Test
  void applyPopulationChangeAllowsGovServiceOverShrunkBudgetWithoutProduction() {
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    EconomySession session = new EconomySession(world(20_000L, commitments));

    EconomySettlement.applyPopulationChangeInto(session, List.of(new LotChange(LOT, HEX, 0L, 90L)));

    assertThat(session.sheet().householdEconomies().get(H).population()).isEqualTo(10L);
    assertThat(session.sheet().householdEconomies().get(H).laborMilli())
        .as("劳动预算按存活比例缩：20000 × 10/100 = 2000")
        .isEqualTo(2_000L);
    assertThat(session.sheet().laborCommitments().get(GOV).laborMilli())
        .as("GOV_SERVICE 绝不参与死亡比例缩（预算 2000 < 承诺 4000 = 合法 underfed）")
        .isEqualTo(4_000L);
  }

  // ── ④ 饥荒路径的 private scaleLaborOfUnit ─────────────────────────────────────────────

  /** 饥荒死亡比例缩：GOV 行整额跳过，PRODUCTION 8000→7200。 */
  @Test
  void famineUnitScaleKeepsGovWhole() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = unitsMap();
    Map<IndustryId, Industry> industries = industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = householdsMap(20_000L);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 8_000L));
    SettlementIndex index = indexOfMaps(units, industries, commitments, households, 1L);

    invokeScaleLaborOfUnit(UNIT, 100L, 90L, commitments, index);

    assertThat(commitments.get(GOV).laborMilli()).as("GOV 整额跳过").isEqualTo(4_000L);
    assertThat(commitments.get(PRODUCTION_1).laborMilli()).isEqualTo(7_200L);
  }

  /**
   * ★★ Z7d-1：饥荒把预算缩到 GOV 以下 —— GOV 行不被缩（underfed 合法）；PRODUCTION 按存活比例缩到 0 后，统一校验 只盯"PRODUCTION
   * 是否越政府预留后可用量"（此处 0 ≤ 0）⇒ 放行、GOV 4000 整额保留。
   */
  @Test
  void famineUnitScaleAllowsGovServiceOverBudgetOnceProductionIsScaledToZero() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = unitsMap();
    Map<IndustryId, Industry> industries = industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = householdsMap(2_000L);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 1_000L));
    SettlementIndex index = indexOfMaps(units, industries, commitments, households, 1L);

    invokeScaleLaborOfUnit(UNIT, 100L, 0L, commitments, index);

    assertThat(commitments.get(GOV).laborMilli())
        .as("GOV 不被缩（预算 2000 < 承诺 4000）")
        .isEqualTo(4_000L);
    assertThat(commitments.get(PRODUCTION_1).laborMilli()).as("PRODUCTION 按存活比例缩到 0").isZero();
    invokeRequireGovServiceCommitmentsWithinBudgets(
        commitments, households, EconomyLogSource.ECONOMY_POPULATION_WRITE, -1L);
    // 不抛 = underfed 合法（PRODUCTION 已归 0，GOV 行整额保留）。
    assertThat(commitments.get(GOV).laborMilli()).isEqualTo(4_000L);
  }

  // ── ⑤ 旧档 reallocateLabor pass②（最大可吸收量）与 pass③（家户预算）───────────────────

  /** pass②：GOV 先整额占 room；PRODUCTION 只吃剩余 room（room=0 ⇒ 整条删掉），GOV 不被修剪。 */
  @Test
  void legacyReallocateLaborPassTwoKeepsGovWholeAndTrimsProductionToRoom() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = unitsMap();
    Map<IndustryId, Industry> industries = industries(); // need = 1 规模 × 1000 = 1000
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = householdsMap(10_000L);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 8_000L));
    SettlementIndex index = indexOfMaps(units, industries, commitments, households, 1L);

    invokeReallocateLabor(units, industries, households, commitments, index);

    assertThat(commitments.get(GOV).laborMilli())
        .as("GOV 先整额占住该 unit 的 room（即使 room < GOV）")
        .isEqualTo(4_000L);
    assertThat(commitments)
        .as("PRODUCTION 只吃 room=0 ⇒ 整条修剪（劳动留在空缺，不塞给别的 unit）")
        .doesNotContainKey(PRODUCTION_1);
  }

  /** pass③：pass② 都装得下时按家户预算封顶 —— GOV 整额保留、只对 PRODUCTION 按比例缩。 */
  @Test
  void legacyReallocateLaborPassThreeKeepsGovWholeAndScalesOnlyProduction() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = unitsMap();
    Map<IndustryId, Industry> industries = industries(); // need = 100,000 ⇒ pass② 不修剪
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = householdsMap(10_000L);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 4_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 6_000L));
    commitments.put(PRODUCTION_2, commitment(PRODUCTION_2, LaborCommitmentKind.PRODUCTION, 6_000L));
    SettlementIndex index = indexOfMaps(units, industries, commitments, households, 100L);

    invokeReallocateLabor(units, industries, households, commitments, index);

    assertThat(commitments.get(GOV).laborMilli()).as("GOV 不进比例权重").isEqualTo(4_000L);
    assertThat(commitments.get(PRODUCTION_1).laborMilli())
        .as("剩余预算 6000 按 6000:6000 分 ⇒ 各 3000")
        .isEqualTo(3_000L);
    assertThat(commitments.get(PRODUCTION_2).laborMilli()).isEqualTo(3_000L);
    assertThat(commitments.values().stream().mapToLong(HouseholdLaborCommitment::laborMilli).sum())
        .isEqualTo(10_000L);
  }

  /**
   * ★★ Z7d-1：pass③ ΣGOV &gt; 预算（underfed 合法）⇒ GOV 12000 整额保留；PRODUCTION 可用量 = max(0, 10000 − 12000)
   * = 0 ⇒ 整条修剪。不抛异常、不缩 GOV。
   */
  @Test
  void legacyReallocateLaborAllowsGovServiceOverBudgetAndDropsProduction() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = unitsMap();
    Map<IndustryId, Industry> industries = industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = householdsMap(10_000L);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(GOV, commitment(GOV, LaborCommitmentKind.GOV_SERVICE, 12_000L));
    commitments.put(PRODUCTION_1, commitment(PRODUCTION_1, LaborCommitmentKind.PRODUCTION, 1_000L));
    SettlementIndex index = indexOfMaps(units, industries, commitments, households, 100L);

    invokeReallocateLabor(units, industries, households, commitments, index);

    assertThat(commitments.get(GOV).laborMilli())
        .as("GOV 不进比例权重、整额保留（预算 10000 < 承诺 12000 = 合法 underfed）")
        .isEqualTo(12_000L);
    assertThat(commitments).as("PRODUCTION 可用量归 0 ⇒ 整条修剪，绝不缩 GOV").doesNotContainKey(PRODUCTION_1);
  }

  // ── 夹具 ──

  private static HouseholdLaborCommitment commitment(
      LaborAllocationId id, LaborCommitmentKind kind, long laborMilli) {
    return new HouseholdLaborCommitment(id, LOT, H, OP, UNIT.value(), laborMilli, 1L, kind);
  }

  private static HouseholdEconomy household(long laborMilli) {
    return new HouseholdEconomy(
        H,
        new CohortKey(HEX, ResidenceKind.RURAL, PEASANT),
        100L,
        laborMilli,
        1_000,
        0L,
        List.of(),
        Map.of(),
        Map.of(),
        0L);
  }

  private static Industry industry() {
    return new Industry(
        FARM,
        "farm",
        new RegimeId("tenant"),
        10L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 1L),
        Map.of(),
        List.of(new ClassSlot(PEASANT, "peasant", 1_000)),
        new AllocationRule.Split(500, 500));
  }

  private static ProductionProcess process() {
    return new ProductionProcess(UNIT, FARM, OP, FARM.value(), 0L, 0L, Map.of());
  }

  private static OwnershipStake share(long quantity) {
    AssetShareId id =
        OwnershipStake.idOf(FARM, AssetKind.LAND, OP, OP, OwnershipStake.RightKind.OWNED, 0L);
    return new OwnershipStake(
        id, FARM, AssetKind.LAND, OP, OP, quantity, OwnershipStake.RightKind.OWNED);
  }

  private static EconomyData world(
      long householdLaborMilli, Map<LaborAllocationId, HouseholdLaborCommitment> commitments) {
    OwnershipStake land = share(1L);
    return EconomyData.empty()
        .withIndustries(Map.of(FARM, industry()))
        .withHouseholdEconomies(Map.of(H, household(householdLaborMilli)))
        .withProcesses(Map.of(UNIT, process()))
        .withOwnershipStakes(Map.of(land.id(), land))
        .withMarkets(Map.of(HEX, new Market(SILVER, Map.of(GRAIN, 1L))))
        .withLaborCommitments(commitments);
  }

  private static SettlementIndex indexOf(EconomyData data) {
    return SettlementIndex.build(
        data.units(),
        data.industries(),
        data.assetShares(),
        data.allocations(),
        data.classes(),
        null,
        data.relations());
  }

  private static Map<HouseholdId, Map<PeopleLotId, Long>> composition() {
    return Map.of(H, Map.of(LOT, 1L));
  }

  private static LinkedHashMap<ProductionUnitId, ProductionProcess> unitsMap() {
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(UNIT, process());
    return units;
  }

  private static Map<IndustryId, Industry> industries() {
    return Map.of(FARM, industry());
  }

  private static LinkedHashMap<HouseholdId, HouseholdEconomy> householdsMap(long laborMilli) {
    LinkedHashMap<HouseholdId, HouseholdEconomy> households = new LinkedHashMap<>();
    households.put(H, household(laborMilli));
    return households;
  }

  private static SettlementIndex indexOfMaps(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments,
      LinkedHashMap<HouseholdId, HouseholdEconomy> households,
      long capacityScale) {
    OwnershipStake land = share(capacityScale);
    return SettlementIndex.build(
        units, industries, Map.of(land.id(), land), commitments, households, null, Map.of());
  }

  // ── 反射入口（private static 生产方法；签名找不到会当场抛）────────────────────────────

  private static void invokeScaleLaborOfUnit(
      ProductionUnitId unitId,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments,
      SettlementIndex index) {
    Method method;
    try {
      method =
          EconomySettlement.class.getDeclaredMethod(
              "scaleLaborOfUnit",
              ProductionUnitId.class,
              long.class,
              long.class,
              LinkedHashMap.class,
              SettlementIndex.class);
      method.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("生产方法 scaleLaborOfUnit 签名已变（测试需同步）", e);
    }
    invoke(method, unitId, before, after, commitments, index);
  }

  private static void invokeRequireGovServiceCommitmentsWithinBudgets(
      Map<LaborAllocationId, HouseholdLaborCommitment> commitments,
      Map<HouseholdId, HouseholdEconomy> households,
      EconomyLogSource source,
      long day) {
    Method method;
    try {
      method =
          EconomySettlement.class.getDeclaredMethod(
              "requireGovServiceCommitmentsWithinBudgets",
              Map.class,
              Map.class,
              EconomyLogSource.class,
              long.class);
      method.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "生产方法 requireGovServiceCommitmentsWithinBudgets 签名已变（测试需同步）", e);
    }
    invoke(method, commitments, households, source, day);
  }

  private static void invokeReallocateLabor(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> households,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments,
      SettlementIndex index) {
    Method method;
    try {
      method =
          EconomySettlement.class.getDeclaredMethod(
              "reallocateLabor",
              LinkedHashMap.class,
              Map.class,
              LinkedHashMap.class,
              LinkedHashMap.class,
              Map.class,
              SettlementIndex.class);
      method.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("生产方法 reallocateLabor 签名已变（测试需同步）", e);
    }
    invoke(
        method,
        units,
        industries,
        households,
        commitments,
        Map.<ProductionUnitId, OperatorCondition>of(),
        index);
  }

  private static void invoke(Method method, Object... args) {
    try {
      method.invoke(null, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("被调方法抛出受检异常: " + method, cause);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("反射调用失败: " + method, e);
    }
  }
}
