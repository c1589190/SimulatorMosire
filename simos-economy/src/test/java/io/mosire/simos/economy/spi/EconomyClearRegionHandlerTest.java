package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 T1 重写（测试 owner）：{@code economy.ClearRegion} 的「三段清区」里的 <b>economy 段</b></b>。
 *
 * <p>★★ <b>为什么重写</b>：本类原先钉的是 {@code outputQuantityOverrides} / {@code productionEfficiency} /
 * {@code units} 三个分量在 economy 变更集里的按格删除。这三个分量**已随迁移整体迁出 economy**（I1：实例/产出覆盖/效率的唯一权威在
 * production；实现账本 B2b/B2c/B5）⇒ {@code EconomyData} 与 {@code EconomyChangeSet} 里**语法上不存在**这三个读口，
 * 旧断言点无法编译、也无法"逐值"锚定。⇒ 按 §14.3 第 3 条**改写（不是放宽）**：
 *
 * <ul>
 *   <li>economy 段仍拥有的行（本类取 {@code relations} —— 指向被清实例的行由 economy 摘）必须按格删、未清格**逐值不变**；
 *   <li>被清实例的**定位**仍由 production 的当刻投影给（本类把 {@code production} 切片放进 {@link SimulationState} ⇒
 *       走真路径，不是打桩）；
 *   <li>★ 实例自身的退役与定义删除（另两段）归 {@code production.ClearRegionDefinitions} ⇒ 本类不重复断言它的行为； 三段同批的判据见 T2 的
 *       N19（production 侧清区用例 + app 侧批序用例）。
 * </ul>
 *
 * <p>本类自足：只借 map 切片的 {@link Region} 类型做定位（ClearRegion 的既有前置），不搭真 map。
 */
class EconomyClearRegionHandlerTest {

  private static final HexCoord TARGET = new HexCoord(0, 0);
  private static final HexCoord KEPT = new HexCoord(1, 0);
  private static final IndustryId TARGET_FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId KEPT_FARM = IndustryHexKeys.id("farm", 1, 0);
  private static final ActorRef TARGET_OPERATOR =
      new ActorRef(ActorKind.ORGANIZATION, TARGET_FARM.value());
  private static final ActorRef KEPT_OPERATOR =
      new ActorRef(ActorKind.ORGANIZATION, KEPT_FARM.value());
  private static final ProductionUnitId TARGET_UNIT =
      ProductionUnitId.idOf(TARGET_FARM, TARGET_OPERATOR);
  private static final ProductionUnitId KEPT_UNIT = ProductionUnitId.idOf(KEPT_FARM, KEPT_OPERATOR);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final RegionId REGION = new RegionId("701");

  @Test
  void clearRegionPrunesTheTargetHexRowsAndLeavesOtherHexesIntact() {
    EconomyData base = twoHexEconomy();
    SimulationState state = stateOf(base, twoHexProduction());

    HandlerOutcome outcome =
        new EconomyClearRegionHandler().handle(state, "{\"regionId\":\"" + REGION.value() + "\"}");

    assertThat(outcome).as("区域存在 ⇒ 不是拒绝").isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.relations().changed()).as("指向被清实例的关系行被摘 ⇒ 必须进变更集（否则 Core 不落盘）").isTrue();

    EconomyData after = EconomyChangeSet.apply(changeSet, base);

    assertThat(after.relations()).as("目标格的关系行整条删除").containsOnlyKeys(KEPT_UNIT);
    assertThat(after.relations().get(KEPT_UNIT))
        .as("未清格的关系行逐值不变（不是'空表即通过'）")
        .isEqualTo(base.relations().get(KEPT_UNIT));
  }

  @Test
  void clearRegionWithoutTheTargetHexKeepsTheTargetRowsIntact() {
    EconomyData base = twoHexEconomy();
    SimulationState state = stateOf(base, twoHexProduction());

    HandlerOutcome outcome =
        new EconomyClearRegionHandler().handle(state, "{\"regionId\":\"999\"}");

    assertThat(outcome).as("区域不存在 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(base.relations().keySet())
        .as("前置：夹具两格各有一行（拒绝路径的判别力落在'它们都还在'上）")
        .containsExactlyInAnyOrder(TARGET_UNIT, KEPT_UNIT);
  }

  private static SimulationState stateOf(EconomyData base, ProductionData production) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(10);
    EconomySnapshot economySnapshot = new EconomySnapshot(ref, at, base);
    MapSnapshot mapSnapshot =
        new MapSnapshot(ref, at, GameMap.empty().withRegions(Map.of(REGION, region(TARGET))));
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(ref, at, production);
    return new SimulationState(
        new StateMeta(ref, at),
        Map.<String, Snapshot>of(
            "economy", economySnapshot, "map", mapSnapshot, "production", productionSnapshot),
        InMemoryInfoSystem.empty());
  }

  private static Region region(HexCoord... hexes) {
    return Region.of(REGION, "区域 701", Set.of(hexes), RegionMeta.empty());
  }

  /**
   * economy 段的两格夹具：两格各一条{@link ProductionRules 关系行}（键 = 实例 id），值刻意不同 ⇒ "逐值不变"有判别力。
   *
   * <p>★ 实例表与产业定义**不在**这里（它们住在 {@link #twoHexProduction()}）。
   */
  private static EconomyData twoHexEconomy() {
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withRelations(
            Map.of(
                TARGET_UNIT, rules(TARGET_UNIT, TARGET_OPERATOR),
                KEPT_UNIT, rules(KEPT_UNIT, KEPT_OPERATOR)));
  }

  /** production 段的两格夹具：两格各一产业 + 各一实例（清区命令据此定位"被清实例"）。 */
  private static ProductionData twoHexProduction() {
    return ProductionData.empty()
        .withIndustry(industry(TARGET_FARM))
        .withIndustry(industry(KEPT_FARM))
        .withInstance(unit(TARGET_UNIT, TARGET_FARM, TARGET_OPERATOR))
        .withInstance(unit(KEPT_UNIT, KEPT_FARM, KEPT_OPERATOR));
  }

  private static ProductionRules rules(ProductionUnitId id, ActorRef operator) {
    return new ProductionRules(id, operator, new Payee.ToActor(operator), List.of(), operator);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "clear-region-test",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  /** 一个最小产业：产出键只有 grain。 */
  private static IndustryDefinitionView industry(IndustryId id) {
    return IndustryViews.industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(),
        0L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new AllocationRule.Split(1000, 0));
  }

  private static ProductionProcess unit(
      ProductionUnitId id, IndustryId industryId, ActorRef operator) {
    return new ProductionProcess(
        id,
        industryId,
        ProductionLocation.ofPlan(industryId).orElseThrow(),
        operator,
        industryId.value(),
        0L,
        Map.of(),
        Map.of());
  }
}
