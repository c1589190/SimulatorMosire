package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.spi.UpsertIndustryHandler;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-031 P1 §5 V3：反事实权威切换</b>（本段唯一写者，2026-10-16）。
 *
 * <p>判据原文（设计书 §5 V3）：正式 {@code production.UpsertIndustry} 在<b>无引用</b>定义上把产出 7 改
 * 11，然后创建<b>两个独立实例</b>； 原料/劳动/需求充足时，<b>真实生产与预期利润都读新定义</b>。再次修改已被引用定义<b>被拒</b>；新版本定义<b>不篡改旧实例</b>。
 * <b>必须断言具体非零读数，不只断"不同"。</b>
 *
 * <p>★★ <b>每个数字都手算得来（不从被测代码反算）</b>：产业 = 作坊（{@code cycleDays=1}、{@code
 * capacityPerUnit={WORKSHOP:1}}、 {@code laborPerUnit=1000}、{@code outputPerUnit={cloth:n}}）。逐 unit：
 *
 * <pre>
 * scale = min( ⌊WORKSHOP 数 ÷ 1⌋, ⌊本周期实际劳动 ÷ 1000⌋ )
 * 毛产   = scale × n × 1000 毫（毫单位换算：1 商品单位 = 1000 毫）
 * 预期利润 plannedOutputMilli = n × scale × 1000（同一条 ×1000 口径）
 * </pre>
 *
 * <p>★ <b>两个独立实例</b> = 两个 unit（不同 {@code operator} ⇒ 不同容量份额、不同劳动配额），落<b>同一个世界</b>里同一天各自关账；
 * 逐实例读数由"单实例世界"与"双实例世界"两次真实结算交叉钉住（{@code 3×n×1000} 与 {@code 5×n×1000}）。
 */
class D031P1AuthoritySwitchTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);

  /** 被切换的产业（无引用 → 改值 → 才建实例）。 */
  private static final IndustryId CRAFT = IndustryHexKeys.id("craft", 0, 0);

  /** 新版本定义（同 base kind、version=2）：只许新建，不许篡改旧实例。 */
  private static final IndustryId CRAFT_V2 = IndustryHexKeys.id("craft_v2", 0, 0);

  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final RegimeId HANDICRAFT = new RegimeId("handicraft");

  private static final ActorRef OPERATOR_A = new ActorRef(ActorKind.ORGANIZATION, "shop-a@0_0");
  private static final ActorRef OPERATOR_B = new ActorRef(ActorKind.ORGANIZATION, "shop-b@0_0");

  private static final ProductionUnitId UNIT_A = ProductionUnitId.idOf(CRAFT, OPERATOR_A);
  private static final ProductionUnitId UNIT_B = ProductionUnitId.idOf(CRAFT, OPERATOR_B);

  private static final CohortKey VIEW_A =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final CohortKey VIEW_B =
      new CohortKey(HEX, ResidenceKind.URBAN, SocialClassId.POOR_PEASANT);
  private static final HouseholdId HOUSE_A = HouseholdIds.ofLegacy(VIEW_A);
  private static final HouseholdId HOUSE_B = HouseholdIds.ofLegacy(VIEW_B);

  private static final PeopleLotId LOT_A = new PeopleLotId("rural:0_0:MALE:1");
  private static final PeopleLotId LOT_B = new PeopleLotId("urban:0_0:MALE:1");
  private static final LaborAllocationId ALLOC_A = new LaborAllocationId("alloc-v3-a");
  private static final LaborAllocationId ALLOC_B = new LaborAllocationId("alloc-v3-b");

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  // ── ① 命令面：无引用 ⇒ 7→11 允许；被引用 ⇒ 拒绝；新版本另 ID；同值重放 no-op ────────────────

  @Test
  void unreferencedDefinitionIsEditedInPlaceAndTheConcreteReadingFollowsIt() {
    // ① 创建：产出 7（此刻没有任何 unit / assetShare / relation 引用它）。
    ProductionData empty = ProductionData.empty();
    HandlerOutcome created = upsert(empty, emptyEconomy(), payload(CRAFT, 7L));
    assertThat(created).as("无引用定义上的创建必须成功").isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData afterCreate = apply(empty, created);
    assertThat(afterCreate.industry(CRAFT).orElseThrow().outputPerUnit())
        .as("创建后的产出逐值 = 7")
        .containsEntry(CLOTH, 7L);
    assertThat(afterCreate.industries()).as("定义表非空").containsOnlyKeys(CRAFT);

    // ② 仍无引用 ⇒ 原地改 7→11 允许（不是"新建一个 id"）。
    HandlerOutcome edited = upsert(afterCreate, emptyEconomy(), payload(CRAFT, 11L));
    assertThat(edited).as("无引用 ⇒ 允许原地全量替换").isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData afterEdit = apply(afterCreate, edited);
    assertThat(afterEdit.industry(CRAFT).orElseThrow().outputPerUnit())
        .as("★ 原地改后的产出逐值 = 11（同 id、不是新 id）")
        .containsEntry(CLOTH, 11L);
    assertThat(afterEdit.industries()).as("原地改不新增 id").containsOnlyKeys(CRAFT);

    // ③ 同值重放 = 幂等 no-op（空变更集 ⇒ 不落 revision）。
    HandlerOutcome replay = upsert(afterEdit, emptyEconomy(), payload(CRAFT, 11L));
    assertThat(replay).isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(((ProductionChangeSet) ((HandlerOutcome.Applied) replay).changeSet()).isEmpty())
        .as("★ 同值重放必须 no-op：变更集为空、零状态变化")
        .isTrue();
    assertThat(apply(afterEdit, replay)).as("no-op 后状态逐值不变").isEqualTo(afterEdit);
  }

  @Test
  void referencedDefinitionInPlaceEditIsRejectedWithNamedCountsAndZeroStateChange() {
    // ★★ D-032 T5（a 桶夹具欠账）：两个 unit 必须落进 production 权威实例表 ⇒ 引用计数才是判据里的 units=2。
    ProductionData definitions = withUnits(nineAndEleven(11L), UNIT_A, UNIT_B);
    EconomyData world = twoUnitWorld();

    HandlerOutcome rejected = upsert(definitions, world, payload(CRAFT, 13L));

    assertThat(rejected).as("★ 已被引用的定义再改 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    String reason = ((HandlerOutcome.Rejected) rejected).reason();
    assertThat(reason)
        .as("拒绝理由带引用总数与逐类计数（可核对，不是一句'不行'）")
        .contains("拒绝原地修改被引用的产业模板")
        .contains("id=craft@0_0")
        .contains("有 6 个引用")
        .contains("units=2")
        .contains("assetShares=2")
        .contains("relations=2")
        .contains("craft_v2@0_0");
  }

  @Test
  void sameValueReplayOnAReferencedDefinitionStaysANoopInsteadOfBeingRejected() {
    // ★★ D-032 T5（a 桶夹具欠账）：同前（units 落表）；另 payload 须带 acceptedRightKinds —— 夹具 craft(...) 声明
    //   了 {OWNED}，而缺键的载荷解析出的是空集 ⇒ "逐值相同"这句话当时**不成立**（差在这一格，不是守卫的锅）。
    ProductionData definitions = withUnits(nineAndEleven(11L), UNIT_A, UNIT_B);
    EconomyData world = twoUnitWorld();

    HandlerOutcome replay = upsert(definitions, world, payload(CRAFT, 11L));

    assertThat(replay).as("逐值相同的重放不是'改' ⇒ 不该被引用守卫拒").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(((ProductionChangeSet) ((HandlerOutcome.Applied) replay).changeSet()).isEmpty())
        .as("同值重放 = 空变更集")
        .isTrue();
    assertThat(apply(definitions, replay)).isEqualTo(definitions);
  }

  @Test
  void newVersionIdIsAcceptedAndNeverTouchesTheOldDefinition() {
    ProductionData definitions = nineAndEleven(11L);
    EconomyData world = twoUnitWorld();

    HandlerOutcome added = upsert(definitions, world, payload(CRAFT_V2, 13L));

    assertThat(added).as("★ 新版本 id（craft_v2@0_0）允许新建").isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData after = apply(definitions, added);
    assertThat(after.industry(CRAFT_V2).orElseThrow().outputPerUnit())
        .as("新版本定义自己有 13")
        .containsEntry(CLOTH, 13L);
    assertThat(after.industry(CRAFT).orElseThrow().outputPerUnit())
        .as("★★ 新版本定义不篡改旧定义：craft@0_0 仍是 11")
        .containsEntry(CLOTH, 11L);
    assertThat(after.industries()).as("两张定义并存").containsOnlyKeys(CRAFT, CRAFT_V2);
  }

  @Test
  void versionRegressionAndSameVersionCollisionAreNamedRejections() {
    ProductionData definitions = nineAndEleven(11L);
    EconomyData world = twoUnitWorld();

    // 同 base kind 的 version=1 已被 craft@0_0 占用 ⇒ 换一个 kind 名但同版本的 id 是合法的；
    // 这里用的是"新 id 声称自己也是 version 1"的撞号面：craft 无后缀即 version 1 ⇒ 用一个显式 _v1 后缀的 id 撞号。
    HandlerOutcome collision =
        upsert(definitions, world, payload(IndustryHexKeys.id("craft_v1", 0, 0), 5L));
    assertThat(collision).as("★ 同版本重复 id ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) collision).reason())
        .contains("拒绝同版本重复 id")
        .contains("craft@0_0")
        .contains("craft_v2@0_0");

    // 空白格：另一格没有任何经济状态 ⇒ 创建产业被拒（不是"凭空造"）。
    HandlerOutcome blankHex =
        upsert(definitions, world, payload(IndustryHexKeys.id("craft", 1, 0), 5L));
    assertThat(blankHex).as("★ 空白格不得凭空创建产业").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) blankHex).reason())
        .contains("还没有已激活的经济状态")
        .contains("1_0");
  }

  // ── ② 真实生产：两个独立实例都读新定义（逐实例手算的非零读数） ────────────────────────────

  @Test
  void realHarvestReadsTheNewDefinitionForBothIndependentInstances() {
    ProductionData newDefinition = nineAndEleven(11L);
    ProductionData oldDefinition = nineAndEleven(7L);

    // A 实例：3 台作坊 + 3,000 千分劳动 ⇒ scale = min(3, 3) = 3 ⇒ 3 × n × 1000。
    // ★★ D-032 T5（a 桶夹具欠账）：被推的那一天必须**有实例**（唯一权威在 production）⇒ 逐例把对应 unit 落表。
    long grossA11 =
        grossCloth(
            withUnits(newDefinition, UNIT_A),
            oneUnitWorld(UNIT_A, HOUSE_A, LOT_A, ALLOC_A, 3L, 3_000L));
    long grossA7 =
        grossCloth(
            withUnits(oldDefinition, UNIT_A),
            oneUnitWorld(UNIT_A, HOUSE_A, LOT_A, ALLOC_A, 3L, 3_000L));
    assertThat(grossA11).as("A 实例读 11 ⇒ 3 × 11 × 1000").isEqualTo(33_000L);
    assertThat(grossA7).as("A 实例的对照：同一世界读 7 ⇒ 3 × 7 × 1000").isEqualTo(21_000L);

    // B 实例：2 台作坊 + 2,000 千分劳动 ⇒ scale = min(2, 2) = 2 ⇒ 2 × n × 1000。
    long grossB11 =
        grossCloth(
            withUnits(newDefinition, UNIT_B),
            oneUnitWorld(UNIT_B, HOUSE_B, LOT_B, ALLOC_B, 2L, 2_000L));
    long grossB7 =
        grossCloth(
            withUnits(oldDefinition, UNIT_B),
            oneUnitWorld(UNIT_B, HOUSE_B, LOT_B, ALLOC_B, 2L, 2_000L));
    assertThat(grossB11).as("B 实例读 11 ⇒ 2 × 11 × 1000").isEqualTo(22_000L);
    assertThat(grossB7).as("B 实例的对照：同一世界读 7 ⇒ 2 × 7 × 1000").isEqualTo(14_000L);

    // 两个独立实例同世界同日各自关账：毛产 = (3 + 2) × n × 1000（逐实例的读数在账上相加）。
    long both11 = grossCloth(withUnits(newDefinition, UNIT_A, UNIT_B), twoUnitWorld());
    assertThat(both11).as("★ 两个独立实例同一天各自关账 ⇒ 5 × 11 × 1000 = 55,000（非零、具体）").isEqualTo(55_000L);
    assertThat(grossCloth(withUnits(oldDefinition, UNIT_A, UNIT_B), twoUnitWorld()))
        .as("同一双实例世界读 7 ⇒ 5 × 7 × 1000 = 35,000")
        .isEqualTo(35_000L);
  }

  @Test
  void bothInstancesAreIndependentSoTheAggregateIsTheSumOfTheirOwnScales() {
    // 反证"两个实例"不是同一实例的两次读：把 B 的作坊数改成 5 ⇒ 合计 (3+5) × 11 × 1000。
    EconomyData world = twoUnitWorldWithQuantities(3L, 5L, 3_000L, 5_000L);
    long gross = grossCloth(withUnits(nineAndEleven(11L), UNIT_A, UNIT_B), world);
    assertThat(gross).as("★ 逐实例规模各自受自己的产能约束：(3 + 5) × 11 × 1000 = 88,000").isEqualTo(88_000L);
  }

  // ── ③ 预期利润：同一条新定义（具体非零读数，手算） ──────────────────────────────────────

  @Test
  void expectedProfitReadsTheNewDefinitionWithAHandComputedConcreteNumber() {
    ProductionData definitions =
        ProductionData.empty().withModes(DefaultProductionModes.modes()).withIndustry(craft(11L));
    EconomyData base = profitWorld(3L, 10_000L);
    MarketDemandBook.Book demand = demandBook(4_000L);
    MarketTable market = market();
    Map<MarketZoneId, MarketTable> markets = Map.of(marketIdOf(HEX), market);
    MarketTopology topology = MarketTopology.singleHex(markets, Map.of());
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(HOUSE_A, HEX, Map.of(CLOTH, 100L), Map.of(), Map.of(), Map.of());

    ExpectedProfitBook.Prospect p =
        ExpectedProfitBook.prospect(
            EconomyFixtures.dayView(base, definitions),
            HOUSE_A,
            DefaultProductionModes.HANDICRAFT_WORKSHOP,
            HEX,
            definitions.industry(CRAFT).orElseThrow(),
            market,
            demand,
            base.assetShares(),
            Set.of(),
            accounts,
            definitions.instances(),
            base.relations(),
            topology,
            0L,
            // ★ T9（R1 签名）：物理量唯一来源 = production 试算；runtime 与 dayView 用同一定义切片。
            EconomyFixtures.runtimeOf(definitions));

    assertThat(p.feasible()).as("产能/劳动/需求都充足 ⇒ 可行").isTrue();
    assertThat(p.feasibleScale()).as("scale = min(产能 3, 劳动 10,000 ÷ 1,000 = 10) = 3").isEqualTo(3L);
    assertThat(p.plannedOutputMilli())
        .as("★★ 预期利润读新定义：11 × 3 × 1000 = 33,000 毫布（与真实生产同一条 ×1000 口径）")
        .containsEntry(CLOTH, 33_000L);
    assertThat(p.laborNeedMilli())
        .as("laborNeed = laborPerUnit × scale × cycleDays = 1,000 × 3 × 1 = 3,000")
        .isEqualTo(3_000L);
    assertThat(p.netPerLaborScaled()).as("具体非零读数（不是'有变化'）").isNotZero();

    // 同一世界把定义换成生产 7 ⇒ 预期产出按同一条式子变成 21,000（对照，非'只断不同'）。
    ProductionData oldDefinitions =
        ProductionData.empty().withModes(DefaultProductionModes.modes()).withIndustry(craft(7L));
    ExpectedProfitBook.Prospect control =
        ExpectedProfitBook.prospect(
            EconomyFixtures.dayView(base, oldDefinitions),
            HOUSE_A,
            DefaultProductionModes.HANDICRAFT_WORKSHOP,
            HEX,
            oldDefinitions.industry(CRAFT).orElseThrow(),
            market,
            demand,
            base.assetShares(),
            Set.of(),
            accounts,
            definitions.instances(),
            base.relations(),
            topology,
            0L,
            // ★ 对照组：runtime 必须与 dayView 同用 oldDefinitions（产 7），否则读的是另一份定义。
            EconomyFixtures.runtimeOf(oldDefinitions));
    assertThat(control.plannedOutputMilli())
        .as("对照组：读 7 ⇒ 7 × 3 × 1000 = 21,000")
        .containsEntry(CLOTH, 21_000L);
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  /** 一张 craft 定义：{@code cycleDays=1}、WORKSHOP 1/规模、laborPerUnit=1000、产出 = 参数。 */
  private static io.mosire.simos.production.contract.IndustryDefinitionView craft(long output) {
    return io.mosire.simos.economy.testing.IndustryViews.industry(
        CRAFT,
        "作坊（V3 夹具）",
        HANDICRAFT,
        1L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(CLOTH, output),
        Map.of(),
        List.of(
            new io.mosire.simos.production.contract.ClassSlot(
                SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new io.mosire.simos.production.contract.AllocationRule.Split(500, 500));
  }

  /** craft@0_0 的产出 = {@code output} 的定义状态。 */
  private static ProductionData nineAndEleven(long output) {
    return ProductionData.empty().withIndustry(craft(output));
  }

  /**
   * ★★ D-032 T5（a 桶夹具欠账）：实例的唯一权威在 production（I1）⇒ 本用例的"两个 unit"必须**落进权威实例表**， 否则：① 引用计数读成 {@code
   * units=0}（拒绝理由里的逐类计数与判据不符）；② 真日循环无步可执行 ⇒ 毛产恒 0。
   *
   * <p>旧稿在 {@link #twoUnitWorldWithQuantities} 里造过同一形状的 {@code units} 局部表却从不落表（迁移期残留），
   * 本助手就是它的落表形态：id / operator 与份额 {@link #stake}、配额 {@link #commitment}、关系 {@link #relation}
   * 用的是**同一个 {@link ProductionUnitId}**。
   */
  private static ProductionData withUnits(ProductionData definitions, ProductionUnitId... units) {
    ProductionData out = definitions;
    for (ProductionUnitId id : units) {
      out = out.withInstance(unit(id, id.equals(UNIT_A) ? OPERATOR_A : OPERATOR_B));
    }
    return out;
  }

  /**
   * {@code production.UpsertIndustry} 的正式载荷（字段口径照 {@code ProductionDefinitionPayloads}）。
   *
   * <p>★ 测试只做"把 7 换成 11"这一件事 ⇒ 其余字段逐字不变。
   */
  private static String payload(IndustryId id, long output) {
    return "{\"id\":\""
        + id.value()
        + "\",\"name\":\"作坊（V3 夹具）\",\"regime\":\"handicraft\",\"cycleDays\":1,"
        + "\"capacityPerUnit\":{\"WORKSHOP\":1},\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,"
        + "\"laborPerUnit\":1000,\"outputPerUnit\":{\"cloth\":"
        + output
        + "},\"moneyOutputPerUnit\":{},\"cycleInputPerUnit\":{},"
        // ★★ D-032 T5：夹具 craft(...) 声明 acceptedRightKinds={OWNED}；缺这一格 ⇒ 解析结果与夹具**不逐值相同**，
        //   于是"同值重放"当场变成"被引用原地改"（判据没错、夹具没对齐）。本行补上后两边逐值一致。
        + "\"acceptedRightKinds\":[\"OWNED\"],"
        + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
        + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":500,\"laborWeightPerMille\":500}}";
  }

  /** 走正式 handler（真 {@code production.UpsertIndustry}），返回它的结局。 */
  private static HandlerOutcome upsert(
      ProductionData definitions, EconomyData world, String payload) {
    return new UpsertIndustryHandler(STATE_PORT).handle(state(definitions, world), payload);
  }

  /**
   * ★★ D-032 T1：`UpsertIndustryHandler` 现在**构造期**注入 {@link ProductionConsumerPort}（唯一生产实现住组合根
   * {@code simos-app/.../AppProductionConsumerPort}）。本用例只需要产业那两问，故这里按**契约口径**从 {@code state}
   * 现算（不引入测试专用的第二套口径）：
   *
   * <ul>
   *   <li>激活 = economy 家户行落在该格 ∪ production 在该格有实例；
   *   <li>引用计数 = production 实例数 / economy 份额数 / economy 关系数（关系经其实例的 industry 命中）。
   * </ul>
   *
   * <p>★ 本用例只走 `production.UpsertIndustry` ⇒ {@code modeReferences} 不被调用，给空报告（该方法的真口径由 production 侧
   * mode 用例覆盖）。
   */
  private static final ProductionConsumerPort STATE_PORT =
      new ProductionConsumerPort() {

        @Override
        public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
          if (state.modules().get("economy") instanceof EconomySnapshot economy) {
            for (HouseholdEconomy row : economy.data().classes().values()) {
              String key = IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r());
              if (key.equals(hexKey)) {
                return true;
              }
            }
          }
          if (state.modules().get("production") instanceof ProductionSnapshot production) {
            for (ProductionProcess unit : production.state().instances().values()) {
              if (IndustryHexKeys.hexKeyOf(unit.industry()).filter(hexKey::equals).isPresent()) {
                return true;
              }
            }
          }
          return false;
        }

        @Override
        public IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id) {
          int units = 0;
          Map<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> instances =
              Map.of();
          if (state.modules().get("production") instanceof ProductionSnapshot production) {
            instances = production.state().instances();
            for (ProductionProcess unit : instances.values()) {
              if (unit.industry().equals(id)) {
                units++;
              }
            }
          }
          int shares = 0;
          int relations = 0;
          if (state.modules().get("economy") instanceof EconomySnapshot economy) {
            for (OwnershipStake stake : economy.data().assetShares().values()) {
              if (stake.industry().equals(id)) {
                shares++;
              }
            }
            for (io.mosire.simos.economy.api.id.ProductionUnitId unitId :
                economy.data().relations().keySet()) {
              ProductionProcess unit = instances.get(unitId);
              if (unit != null && unit.industry().equals(id)) {
                relations++;
              }
            }
          }
          return new IndustryReferenceCounts(units, shares, relations);
        }

        @Override
        public ModeReferenceReport modeReferences(
            SimulationState state, io.mosire.simos.economy.api.id.ProductionModeId id) {
          return new ModeReferenceReport(List.of(), List.of(), List.of(), List.of(), List.of());
        }
      };

  private static ProductionData apply(ProductionData base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return ProductionChangeSet.apply(
        (ProductionChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static SimulationState state(ProductionData definitions, EconomyData world) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("production", new ProductionSnapshot(REF, T0, definitions));
    modules.put("economy", new EconomySnapshot(REF, T0, world));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  /**
   * "无引用"的那一侧：格已有已激活的经济状态（两行家户 ⇒ {@code isHexEconomicallyActive} 为真）， 但<b>没有任何</b> unit /
   * assetShare / relation 指名 {@code craft@0_0}。
   */
  private static EconomyData emptyEconomy() {
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withHouseholdEconomies(
            Map.of(HOUSE_A, row(HOUSE_A, VIEW_A), HOUSE_B, row(HOUSE_B, VIEW_B)));
  }

  /** 双实例世界：两个 unit（不同 operator ⇒ 各自容量份额）、两个家户、两条劳动配额。 */
  private static EconomyData twoUnitWorld() {
    return twoUnitWorldWithQuantities(3L, 2L, 3_000L, 2_000L);
  }

  private static EconomyData twoUnitWorldWithQuantities(
      long workshopsA, long workshopsB, long laborA, long laborB) {
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    units.put(UNIT_A, unit(UNIT_A, OPERATOR_A));
    units.put(UNIT_B, unit(UNIT_B, OPERATOR_B));
    Map<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    OwnershipStake shareA = stake(OPERATOR_A, workshopsA);
    OwnershipStake shareB = stake(OPERATOR_B, workshopsB);
    shares.put(shareA.id(), shareA);
    shares.put(shareB.id(), shareB);
    Map<LaborAllocationId, HouseholdLaborCommitment> commitments = new LinkedHashMap<>();
    commitments.put(ALLOC_A, commitment(ALLOC_A, LOT_A, HOUSE_A, OPERATOR_A, laborA));
    commitments.put(ALLOC_B, commitment(ALLOC_B, LOT_B, HOUSE_B, OPERATOR_B, laborB));
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    relations.put(UNIT_A, relation(UNIT_A, OPERATOR_A));
    relations.put(UNIT_B, relation(UNIT_B, OPERATOR_B));
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withRelations(relations)
        .withOwnershipStakes(shares)
        .withHouseholdEconomies(
            Map.of(HOUSE_A, row(HOUSE_A, VIEW_A), HOUSE_B, row(HOUSE_B, VIEW_B)))
        .withLaborCommitments(commitments);
  }

  private static EconomyData oneUnitWorld(
      ProductionUnitId unitId,
      HouseholdId household,
      PeopleLotId lot,
      LaborAllocationId allocation,
      long workshops,
      long laborMilli) {
    ActorRef operator = unitId.equals(UNIT_A) ? OPERATOR_A : OPERATOR_B;
    CohortKey view = household.equals(HOUSE_A) ? VIEW_A : VIEW_B;
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withOwnershipStakes(Map.of(stake(operator, workshops).id(), stake(operator, workshops)))
        .withHouseholdEconomies(Map.of(household, row(household, view)))
        .withLaborCommitments(
            Map.of(allocation, commitment(allocation, lot, household, operator, laborMilli)));
  }

  /** 预期利润夹具：一格、一 unit（OPERATOR_A）、3 台作坊、有价表与需求簿。 */
  private static EconomyData profitWorld(long workshops, long laborMilli) {
    OwnershipStake share = stake(OPERATOR_A, workshops);
    var position =
        DefaultProductionModes.positionId(
                DefaultProductionModes.HANDICRAFT_WORKSHOP,
                DefaultProductionModes.ROLE_WORKSHOP_OWNER)
            .orElseThrow();
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(Map.of(HOUSE_A, row(HOUSE_A, VIEW_A)))
        .withClassMemberships(
            Map.of(
                HOUSE_A,
                new HouseholdClassMembership(HOUSE_A, position, position, Map.of(), 0L, 0L, "v3")))
        .withOwnershipStakes(Map.of(share.id(), share))
        .withLaborCommitments(
            Map.of(ALLOC_A, commitment(ALLOC_A, LOT_A, HOUSE_A, OPERATOR_A, laborMilli)))
        .withMarketTables(EconomyFixtures.hexMarkets(Map.of(HEX, market())));
  }

  /** 一条空补偿规则的生产关系（残余归 operator）—— 只为把"被引用"的第三条表填上。 */
  private static ProductionRules relation(ProductionUnitId id, ActorRef operator) {
    return new ProductionRules(id, operator, new Payee.ToActor(operator), List.of(), operator);
  }

  private static ProductionProcess unit(ProductionUnitId id, ActorRef operator) {
    return new ProductionProcess(
        id,
        CRAFT,
        ProductionLocation.ofPlan(CRAFT).orElseThrow(),
        operator,
        "mode:handicraft_workshop",
        0L,
        Map.of(),
        Map.of());
  }

  private static OwnershipStake stake(ActorRef operator, long workshops) {
    var id =
        OwnershipStake.idOf(
            CRAFT,
            AssetKind.WORKSHOP,
            operator,
            operator,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"),
            0L);
    return new OwnershipStake(
        id,
        CRAFT,
        AssetKind.WORKSHOP,
        operator,
        operator,
        workshops,
        AssetRightKind.OWNED,
        new ProductionLocation("0_0"));
  }

  private static HouseholdLaborCommitment commitment(
      LaborAllocationId allocation,
      PeopleLotId lot,
      HouseholdId household,
      ActorRef operator,
      long laborMilli) {
    return new HouseholdLaborCommitment(
        allocation,
        lot,
        household,
        operator,
        ProductionUnitId.idOf(CRAFT, operator).value(),
        laborMilli,
        1L,
        LaborCommitmentKind.PRODUCTION,
        Optional.of(ProductionRoleId.parse("operator")));
  }

  private static HouseholdEconomy row(HouseholdId household, CohortKey view) {
    return new HouseholdEconomy(household, view, 10L, 10_000L, 1000, 0L, Map.of(), Map.of(), 0L);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "d031-v3-authority-switch",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  private static MarketTable market() {
    return MarketTable.singleHex(SILVER, HEX, Map.of(CLOTH, 10L));
  }

  private static MarketZoneId marketIdOf(HexCoord hex) {
    return new MarketZoneId("hex:" + IndustryHexKeys.hexKey(hex.q(), hex.r()));
  }

  private static MarketDemandBook.Book demandBook(long addressable) {
    MarketDemandBook.Demand demand =
        new MarketDemandBook.Demand(HEX, CLOTH, 0L, 0L, 0L, 0L, 0L, 0L, addressable, List.of("v3"));
    return new MarketDemandBook.Book(Map.of(HEX, Map.of(CLOTH, demand)), 0L, 10L);
  }

  /** 真日循环跑一天，读本产业本商品的毛产（毫）。 */
  private static long grossCloth(ProductionData definitions, EconomyData world) {
    AccountSession accounts = AccountSession.empty();
    for (HouseholdEconomy row : world.classes().values()) {
      accounts.registerHousehold(
          row.id(), row.view().hex(), Map.of(CLOTH, 1_000_000L), Map.of(), Map.of(), Map.of());
    }
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            world,
            definitions,
            runtimeOf(definitions),
            accounts,
            MarketTopology.singleHex(world.marketTables(), world.marketZones()));
    try {
      ProductionLedger ledger = stepper.step(1L);
      return ledger.gross().getOrDefault(CRAFT, Map.of()).getOrDefault(CLOTH, 0L);
    } finally {
      stepper.close();
    }
  }

  /** ★★ D-032 B2b/B3：会话/日推进器必须显式拿到 production 运行时（{@code runtime=null} 的旧构造器已删）。 */
  private static io.mosire.simos.production.contract.ProductionRuntime runtimeOf(
      io.mosire.simos.production.ProductionData definitions) {
    return new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
  }
}
