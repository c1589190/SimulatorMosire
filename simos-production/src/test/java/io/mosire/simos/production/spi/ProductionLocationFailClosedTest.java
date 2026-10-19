package io.mosire.simos.production.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 N23a：{@code ProductionLocation.UNSPECIFIED} 在 <b>production 侧构点</b>的 fail-closed
 * 读数</b> （D-032 测试阶段派单 §14.11.5；口径 = §13.8 裁定二第 1/3/4 条 + 实现账本 `B3-§7` 的逐点处置表）。
 *
 * <p>★★ <b>B3-§7 表里属于 production 的构点</b>：
 *
 * <ul>
 *   <li><b>②</b> {@code production.Seed} 的 {@code instances[]}：既没有 {@code location}、plan id 也不带格段 ⇒
 *       <b>具名拒 {@code location-unknown}</b>；
 *   <li><b>⑦</b> 读侧守卫：{@code production.CreateInstance}（具名拒 {@code location-unknown}）、 {@code
 *       production.ClearRegionDefinitions}（地点不可判 ⇒ <b>契约故障</b>，B4 起不再"跳过 + 计数"）。
 * </ul>
 *
 * <p>★ 迁移建档（③）与企业建档（④）两个构点在 economy 侧（{@code ModeMigrationSettlement} / {@code
 * EconomyEnterpriseSettlement}）⇒ 不属 T2a 的模块面（见台账"未做"一节）。
 */
class ProductionLocationFailClosedTest {

  private static final IndustryId FARM_NO_HEX = new IndustryId("farm");
  private static final IndustryId FARM_HEX = IndustryHexKeys.id("farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-farm-1");
  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1L));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "h-1");

  // ── ② 播种构点：解不出地点 ⇒ 具名拒（并给正值对照证明不是恒拒）──────────────────────────

  @Test
  void seedRejectsAnInstanceWithoutALocationAndWithoutAPlanHex() {
    ProductionData base = ProductionData.empty(); // 显式"空世界"：还没有任何定义/实例
    SimulationState state = state(base);
    String withoutLocation = seedPayload("farm", "");

    HandlerOutcome rejected = new ProductionSeedHandler().handle(state, withoutLocation);

    assertThat(rejected).as("★ ② 播种：地点解不出 ⇒ 具名业务拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) rejected).reason())
        .contains("location-unknown")
        .contains("unit-farm-1")
        .contains("plan=farm");

    // 正值对照：同一条实例节点带上真实地点 ⇒ 能落，且落的是"0_0"（证明上面那条不是恒拒）。
    HandlerOutcome applied =
        new ProductionSeedHandler().handle(state, seedPayload("farm", ",\"location\":\"0_0\""));
    assertThat(applied).isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData after = apply(base, applied);
    assertThat(after.instances()).containsOnlyKeys(UNIT);
    assertThat(after.instances().get(UNIT).location()).isEqualTo(new ProductionLocation("0_0"));
    assertThat(after.instances().get(UNIT).hexKey()).as("实例地点可判 ⇒ 清区/按格索引都能用").contains("0_0");
  }

  // ── ⑦a 创建构点：解不出地点 ⇒ 具名拒；解得出来 ⇒ 派生 ────────────────────────────────────

  @Test
  void createInstanceRejectsAnInstanceWithoutALocationAndWithoutAPlanHex() {
    ProductionData base = ProductionData.empty().withIndustry(plan(FARM_NO_HEX));
    SimulationState state = state(base);

    HandlerOutcome rejected = new CreateInstanceHandler().handle(state, createPayload("farm"));

    assertThat(rejected).as("★ ⑦a 创建：两者都没有 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) rejected).reason())
        .contains("location-unknown")
        .contains("不写 UNSPECIFIED 占位");
    // ★ 裁定二十七：base 是显式空世界且不可变 ⇒ "仍然没有实例"是结构性事实；判别力在下一段的正值对照。
    assertThat(base.instances()).as("结构性事实：入参 base 不可变（拒绝路径没有写句柄）").isEmpty();

    // 方案 id 自带格段 ⇒ 按既有生成约定派生地点（同一构点的"解得出来"那一侧）。
    ProductionData hexBase = ProductionData.empty().withIndustry(plan(FARM_HEX));
    HandlerOutcome derived =
        new CreateInstanceHandler().handle(state(hexBase), createPayload("farm@0_0"));

    assertThat(derived).isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData after = apply(hexBase, derived);
    assertThat(after.instances()).containsOnlyKeys(UNIT);
    assertThat(after.instances().get(UNIT).location()).isEqualTo(new ProductionLocation("0_0"));
  }

  // ── ⑦b 读侧守卫：地点判不出来 ⇒ 契约故障（不是静默跳过）──────────────────────────────────

  @Test
  void clearRegionTreatsAnUnresolvableInstanceLocationAsAContractFault() {
    // 状态自相矛盾的实例：显式地点是 UNSPECIFIED 占位、plan id 也不带格段 ⇒ hexKey() 判不出来。
    ProductionProcess placeholdered =
        new ProductionProcess(
            UNIT,
            FARM_NO_HEX,
            ProductionLocation.UNSPECIFIED,
            OPERATOR,
            "farm",
            0L,
            Map.of(),
            Map.of());
    ProductionData inconsistent =
        ProductionData.empty().withIndustry(plan(FARM_NO_HEX)).withInstance(placeholdered);

    assertThat(placeholdered.hexKey()).as("先证明这条实例真的判不出格（否则本判据空转）").isEmpty();

    assertThatThrownBy(
            () ->
                new ClearRegionDefinitionsHandler(new FakePort())
                    .handle(state(inconsistent), "{\"hexes\":[\"0_0\"]}"))
        .as("★★ ⑦b 读侧守卫：地点不可判 = 状态自相矛盾 ⇒ 契约故障当场抛（B4 起不再静默跳过）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("实例地点不可判")
        .hasMessageContaining("契约故障")
        .hasMessageContaining("unit-farm-1");
  }

  // ── 裁定三十第 3 条：占位地点载荷 ⇒ 构点具名拒；读侧那道仍然要兜得住 ──────────────────────

  @Test
  void
      aPlaceholderLocationPayloadIsRejectedByNameAtTheSeedConstructionPointWhileTheReadSideStillHolds() {
    // ★★ 判据（§14.13.2 裁定三十第 3 条：原"现状读数"改为修复后判据）：载荷**显式**给 "?"
    //   （= UNSPECIFIED 占位）⇒ 播种构点必须**具名拒 location-unknown**（§14.12.3 裁定二十九：
    //   构点必须 fail-closed —— 只靠读侧防御等于允许坏数据进权威状态，那正是"静默降级"的形态）。
    //   现场（B7 修复后）：ProductionSeedHandler.java:166-179 在 parse 之后加
    //   `!location.isSpecified() ⇒ 具名拒 location-unknown`。
    //   ★ 本用例若改回旧断言（期望 Applied + 占位实例落进状态）⇒ 当场红：这正是判别力所在。
    ProductionData base = ProductionData.empty(); // 显式"空世界"：还没有任何定义/实例
    SimulationState state = state(base);

    HandlerOutcome rejected =
        new ProductionSeedHandler().handle(state, seedPayload("farm", ",\"location\":\"?\""));

    assertThat(rejected)
        .as("★ 占位地点载荷 ⇒ 具名业务拒绝（不得写进权威候选态）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) rejected).reason())
        .as("★ 具名理由逐值：location-unknown + 出事的实例/方案 + 未落定的占位值")
        .contains("location-unknown")
        .contains("unit-farm-1")
        .contains("plan=farm")
        .contains("\"?\"");
    // ★ 裁定二十七：base 是显式空世界且**不可变** ⇒ "仍然没有实例"是结构性事实（拒绝路径没有写句柄）；
    //   判别力由上面那条具名拒绝 + 上一条用例里 "0_0" 的正值对照共同承担。
    assertThat(base.instances()).as("结构性事实：入参 base 不可变").isEmpty();

    // ★ 保留（§14.13.2 第 3 条的"保留 :179-185"）：读侧那道守卫**仍然**要兜得住 ——
    //   §14.12.3 裁定二十九原文是"两道都要"（构点拒 + 读侧防御）。这里手工构造出
    //   "占位实例已经在状态里"那份自相矛盾状态（构点既然拒了，它只能来自旧档或手搭夹具），再进清区。
    ProductionProcess placeholdered =
        new ProductionProcess(
            UNIT,
            FARM_NO_HEX,
            ProductionLocation.UNSPECIFIED,
            OPERATOR,
            "farm",
            0L,
            Map.of(),
            Map.of());
    ProductionData inconsistent =
        ProductionData.empty().withIndustry(plan(FARM_NO_HEX)).withInstance(placeholdered);
    assertThat(placeholdered.hexKey()).as("先证明这条实例真的判不出格（否则本判据空转）").isEmpty();

    assertThatThrownBy(
            () ->
                new ClearRegionDefinitionsHandler(new FakePort())
                    .handle(state(inconsistent), "{\"hexes\":[\"0_0\"]}"))
        .as("★ 读侧（ClearRegionDefinitions）仍然 fail-closed：构点 + 读侧两道都要")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("实例地点不可判")
        .hasMessageContaining("契约故障")
        .hasMessageContaining("unit-farm-1");
  }

  // ── 夹具（真实数据；"空世界"只在名字与注释写明的那一处）──────────────────────────────

  /** 播种载荷：一条产业定义 + 一条实例节点（{@code locationExtra} 为空 ⇒ 不写 location 键）。 */
  private static String seedPayload(String planId, String locationExtra) {
    return "{\"industries\":["
        + industryJson(planId)
        + "],\"instances\":[{\"id\":\"unit-farm-1\",\"plan\":\""
        + planId
        + "\",\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"h-1\"},\"modeKey\":\"farm\""
        + locationExtra
        + "}]}";
  }

  /** 创建载荷（不带 location）。 */
  private static String createPayload(String planId) {
    return "{\"plan\":\""
        + planId
        + "\",\"unit\":\"unit-farm-1\","
        + "\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"h-1\"},\"modeKey\":\"farm\"}";
  }

  private static SimulationState state(ProductionData data) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of("production", new ProductionSnapshot(REF, T0, data)),
        InMemoryInfoSystem.empty());
  }

  private static ProductionData apply(ProductionData base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return ProductionChangeSet.apply(
        (ProductionChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  /** 一份最小但完整的产业定义（18 组件；id 可带格段也可不带）。 */
  private static IndustryDefinitionView plan(IndustryId id) {
    return new IndustryDefinitionView(
        id,
        "产业 " + id.value(),
        new RegimeId("tenant"),
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1L,
        Map.of(GRAIN, 1L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  /**
   * 方案定义载荷（形状与 {@code IndustryDefinitionView} 的 18 组件一一对应；与 {@code
   * D031P1DefinitionCommandNegativeTest#industryJson} 同款，id 换成不带格段的形态）。
   */
  private static String industryJson(String id) {
    return "{\"id\":\""
        + id
        + "\",\"name\":\"产业 "
        + id
        + "\",\"regime\":\"tenant\",\"cycleDays\":1,\"buildDays\":0,"
        + "\"capacityPerUnit\":{\"WORKSHOP\":1},"
        + "\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,\"laborPerUnit\":1,\"outputPerUnit\":{\"grain\":1},"
        + "\"moneyOutputPerUnit\":{},\"cycleInputPerUnit\":{},"
        + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
        + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":1000,\"laborWeightPerMille\":0},"
        + "\"experience\":{\"effectScope\":\"total\",\"unlockAtPrimaryExperience\":0},"
        + "\"availability\":[],\"acceptedRightKinds\":[\"OWNED\"]}";
  }

  /** 跨片端口替身：本判据不查 economy 引用，一律回"零引用"。 */
  private static final class FakePort implements ProductionConsumerPort {

    @Override
    public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
      return true;
    }

    @Override
    public IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id) {
      return new IndustryReferenceCounts(0, 0, 0);
    }

    @Override
    public ModeReferenceReport modeReferences(SimulationState state, ProductionModeId id) {
      return new ModeReferenceReport(List.of(), List.of(), List.of(), List.of(), List.of());
    }
  }
}
