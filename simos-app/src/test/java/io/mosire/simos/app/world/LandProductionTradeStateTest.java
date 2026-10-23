package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.GRAIN;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.GRAIN_PLAN;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.H0;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.actorData;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.arableLand;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.balance;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.bool;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.component;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.grainPlan;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.id;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.idleAttrs;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.inProductionAttrs;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.inventory;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.mapWith;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.number;
import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.app.inventory.InventoryTransferContext;
import io.mosire.simos.app.inventory.InventoryTransferPortImpl;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.InventoryTransferId;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferReason;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
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
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 L2d：土地交易保留生产状态 / 新 owner 下一轮可继续组织的独立验收</b>（plan §5）。
 *
 * <p>判据全部走真实链路：真实 {@link InventoryTransferPortImpl#plan} + {@link
 * io.mosire.simos.app.inventory.LandLotTransferAdapter}、真实 {@link MapChangeSet#apply}、真实 {@link
 * InventoryLotViewBuilder}、真实 {@link LandProductionCycle#run(SimulationState,
 * long)}。测试只新增文件、不改任何生产/测试代码。
 *
 * <ul>
 *   <li><b>A/部分卖地保留状态</b>：10,000 毫亩 H1 在产 lot 卖 4,000 给 H2；两边保留 CROP/STAGE/PROGRESS/CYCLE_PROGRESS/
 *       START_INPUT_PAID/cycle_input:grain、OWNER 正确、FERTILITY 保留、数量守恒 10,000，读回两行 InProduction
 *       state 逐字段一致；
 *   <li><b>B/新 owner 下一轮继续</b>：成交后 H1 6,000 lot 按 scale 6、H2 4,000 lot 按 scale 4 各扣各料并推进；H2 从
 *       progress8→9；旧 owner 不再拥有 4,000；production 切片逐字段不变、state 无 claim 记录（本 tick claim 只由当前 lots
 *       生成，下一 tick 再生成）；
 *   <li><b>C/全 IDLE 卖出仍 IDLE</b>：无生产属性土地转移后仍 IDLE，不伪造 stage；
 *   <li><b>D/交易不改 stage/progress 的负向守卫</b>：任一非 OWNER 属性（含 stage/progress/自定义属性）在两侧逐项相等； 若实现改动
 *       state，本测试必须变红。
 * </ul>
 *
 * <p>★ 本批预计不需要生产代码；若行为与 plan §5 不符，保留失败并在 impl-ledger 记 BLOCKED，不改生产代码。
 */
class LandProductionTradeStateTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final String MAP_ID = "l2d-trade-state";

  private static final HouseholdId H1 = new HouseholdId("l2d-h1");
  private static final HouseholdId H2 = new HouseholdId("l2d-h2");
  private static final LandComponentId LAND_LOT = id("arable@0_0:0");

  // ── A. 部分卖地保留生产状态 ──────────────────────────────────────────────

  @Test
  void partialTradeCopiesProductionStateAndConservesQuantityAcrossBothOwners() {
    GameMap baseMap = inProductionLandMap();
    InventoryTransferPlan plan = planPartialTrade(baseMap, 4_000L, H2);

    // plan 不改 base：面积与属性逐值不变。
    assertThat(totalArable(baseMap)).isEqualTo(10_000L);
    assertThat(componentOwnedBy(baseMap, H1).attributes())
        .isEqualTo(
            inProductionAttrs(
                H1.value(), 700L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 123L)));

    GameMap traded = applyTrade(baseMap, plan);

    LandTypeArea area = traded.hexes().get(H0).land().byType().get(LandTypeId.ARABLE);
    assertThat(area.components()).as("部分转移拆成两个 component").hasSize(2);

    LandComponent h1 = componentOwnedBy(traded, H1);
    LandComponent h2 = componentOwnedBy(traded, H2);
    assertThat(h1.quantityMilliMu()).as("H1 剩余 6,000").isEqualTo(6_000L);
    assertThat(h2.quantityMilliMu()).as("H2 得到 4,000").isEqualTo(4_000L);
    assertThat(totalArable(traded)).as("数量守恒").isEqualTo(10_000L);

    // 两侧都保留逐字段相同的生产属性，OWNER 各自正确，FERTILITY=700 保留。
    assertCarriedProductionAttributes(h1, H1);
    assertCarriedProductionAttributes(h2, H2);

    assertThat(plan.receipt().statePreserved()).isTrue();
    assertThat(plan.receipt().targetLotIds()).contains(InventoryLotId.forLand(h2.id()));

    // 用真实 InventoryLotViewBuilder 读回两行：都是 InProduction，state 逐字段一致。
    InventoryLotView view = InventoryLotViewBuilder.buildLandOnly(traded);
    assertThat(view.lots()).as("读回两行 LAND lot").hasSize(2);
    InventoryLot lot1 = singleLotOf(view, H1);
    InventoryLot lot2 = singleLotOf(view, H2);
    assertThat(lot1.quantityMilli()).isEqualTo(6_000L);
    assertThat(lot2.quantityMilli()).isEqualTo(4_000L);
    assertThat(lot1.state()).isInstanceOf(LotState.InProduction.class);
    assertThat(lot2.state()).isInstanceOf(LotState.InProduction.class);
    assertThat(lot2.state()).as("state 逐字段一致").isEqualTo(lot1.state());

    LotState.InProduction state = (LotState.InProduction) lot1.state();
    assertThat(state.planId()).isEqualTo(GRAIN_PLAN.value());
    assertThat(state.stageId()).isEqualTo("stage1");
    assertThat(state.stageProgressDays()).isEqualTo(8L);
    assertThat(state.cycleProgressDays()).isEqualTo(8L);
    assertThat(state.startInputPaid()).isTrue();
    assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 123L));
    assertThat(lot1.quantityMilli() + lot2.quantityMilli()).isEqualTo(10_000L);
  }

  // ── B. 新 owner 下一轮继续组织 ────────────────────────────────────────────

  @Test
  void newOwnerContinuesFromCarriedStateAndOnlyItsOwnLotIsCharged() {
    GameMap baseMap = inProductionLandMap();
    GameMap traded = applyTrade(baseMap, planPartialTrade(baseMap, 4_000L, H2));

    ActorData baseActor =
        actorData(inventory(H1, Map.of(GRAIN, 1_000L)), inventory(H2, Map.of(GRAIN, 1_000L)));
    ProductionData baseProduction = ProductionData.empty().withIndustry(grainPlan());
    MapSnapshot mapSnapshot = new MapSnapshot(REF, T0, traded);
    ActorSnapshot actorSnapshot = new ActorSnapshot(REF, T0, baseActor);
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(REF, T0, baseProduction);
    SimulationState state = state(mapSnapshot, actorSnapshot, productionSnapshot);

    SimulationState day1 = LandProductionCycle.run(state, 1L);

    assertThat(day1).as("有成功推进时必须返回新 state").isNotSameAs(state);
    assertThat(day1.module("production").orElseThrow())
        .as("land cycle 不得改 production 切片")
        .isSameAs(productionSnapshot);
    assertThat(day1.modules().keySet())
        .as("state 只有 map/actor/production；claim/opportunity 只是本 tick 内存态")
        .containsExactlyInAnyOrder("map", "actor", "production");
    assertThat(productionDataOf(day1).instances()).as("无持久 claim/instance 记录").isEmpty();

    // H1 6,000 lot：scale 6，扣 grain6，stage1 8→9，cycle_input 123→129。
    LandComponent h1 = componentOwnedBy(mapOf(day1), H1);
    assertThat(h1.quantityMilliMu()).as("H1 面积不被 H2 lot 污染").isEqualTo(6_000L);
    assertThat(h1.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H1.value()))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(129L));
    assertThat(balance(actorOf(day1), H1, GRAIN)).as("H1 只按 scale6 扣 6").isEqualTo(994L);

    // H2 4,000 lot：scale 4，扣 grain4，stage1 8→9，cycle_input 123→127。
    LandComponent h2 = componentOwnedBy(mapOf(day1), H2);
    assertThat(h2.quantityMilliMu()).isEqualTo(4_000L);
    assertThat(h2.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H2.value()))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(127L));
    assertThat(balance(actorOf(day1), H2, GRAIN)).as("H2 只按 scale4 扣 4").isEqualTo(996L);

    // 旧 owner 不再拥有 4,000 面积：H1 名下只有 6,000 一行。
    InventoryLotView afterDay1 = InventoryLotViewBuilder.buildLandOnly(mapOf(day1));
    InventoryLot h1Lot = singleLotOf(afterDay1, H1);
    assertThat(h1Lot.quantityMilli()).isEqualTo(6_000L);
    assertThat(h1Lot.state()).isInstanceOf(LotState.InProduction.class);
    assertThat(((LotState.InProduction) h1Lot.state()).stageProgressDays()).isEqualTo(9L);
    InventoryLot h2Lot = singleLotOf(afterDay1, H2);
    assertThat(h2Lot.quantityMilli()).isEqualTo(4_000L);
    assertThat(((LotState.InProduction) h2Lot.state()).stageProgressDays()).isEqualTo(9L);
    assertThat(h1Lot.quantityMilli() + h2Lot.quantityMilli()).isEqualTo(10_000L);

    // 下一 tick 的 claim 只由当前 lots 重新生成：两个 lot 各再推进恰好一天（stage1 满 10 ⇒ stage2 progress0）。
    SimulationState day2 = LandProductionCycle.run(day1, 2L);
    assertThat(day2.module("production").orElseThrow()).isSameAs(productionSnapshot);
    assertThat(componentOwnedBy(mapOf(day2), H1).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(0L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(10L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(false))
        .containsEntry(cycleInputKey(GRAIN), number(135L));
    assertThat(componentOwnedBy(mapOf(day2), H2).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(0L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(10L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(false))
        .containsEntry(cycleInputKey(GRAIN), number(131L));
    assertThat(balance(actorOf(day2), H1, GRAIN)).isEqualTo(988L);
    assertThat(balance(actorOf(day2), H2, GRAIN)).isEqualTo(992L);
  }

  // ── C. 全 IDLE 卖出仍 IDLE ────────────────────────────────────────────────

  @Test
  void fullyIdleLandTradeStaysIdleWithoutFabricatedStage() {
    GameMap baseMap =
        mapWith(
            Map.of(H0, arableLand(1L, component(LAND_LOT, 10_000L, idleAttrs(H1.value(), 620L)))));
    GameMap traded = applyTrade(baseMap, planPartialTrade(baseMap, 4_000L, H2));

    LandComponent h1 = componentOwnedBy(traded, H1);
    LandComponent h2 = componentOwnedBy(traded, H2);
    assertThat(h1.quantityMilliMu()).isEqualTo(6_000L);
    assertThat(h2.quantityMilliMu()).isEqualTo(4_000L);
    assertThat(totalArable(traded)).isEqualTo(10_000L);

    // 只有 OWNER/FERTILITY；不伪造 CROP/STAGE/PROGRESS/...。
    assertThat(h1.attributes())
        .containsOnlyKeys(LandAttributeKey.OWNER, LandAttributeKey.FERTILITY)
        .containsEntry(LandAttributeKey.OWNER, text(H1.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(620L));
    assertThat(h2.attributes())
        .containsOnlyKeys(LandAttributeKey.OWNER, LandAttributeKey.FERTILITY)
        .containsEntry(LandAttributeKey.OWNER, text(H2.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(620L));
    assertThat(h2.attributes())
        .doesNotContainKeys(
            LandAttributeKey.CROP,
            LandAttributeKey.STAGE,
            LandAttributeKey.PROGRESS,
            LandAttributeKey.CYCLE_PROGRESS,
            LandAttributeKey.START_INPUT_PAID);

    InventoryLotView view = InventoryLotViewBuilder.buildLandOnly(traded);
    assertThat(view.lots()).hasSize(2);
    assertThat(singleLotOf(view, H1).state()).isEqualTo(LotState.IDLE);
    assertThat(singleLotOf(view, H2).state()).isEqualTo(LotState.IDLE);
  }

  // ── D. 交易不改 stage/progress 的负向守卫 ────────────────────────────────

  @Test
  void tradeDoesNotMutateAnyNonOwnerAttributeOnEitherSide() {
    Map<LandAttributeKey, LandAttributeValue> original =
        new LinkedHashMap<>(
            inProductionAttrs(
                H1.value(), 700L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 123L)));
    original.put(new LandAttributeKey("custom_note"), text("kept"));
    GameMap baseMap = mapWith(Map.of(H0, arableLand(1L, component(LAND_LOT, 10_000L, original))));

    GameMap traded = applyTrade(baseMap, planPartialTrade(baseMap, 4_000L, H2));

    Map<LandAttributeKey, LandAttributeValue> expectedNonOwner = withoutOwner(original);
    assertThat(withoutOwner(componentOwnedBy(traded, H1).attributes()))
        .as("H1 侧除 OWNER 外逐属性不变")
        .isEqualTo(expectedNonOwner);
    assertThat(withoutOwner(componentOwnedBy(traded, H2).attributes()))
        .as("H2 侧复制且除 OWNER 外逐属性不变")
        .isEqualTo(expectedNonOwner);

    // 显式 stage/progress/cycle_input 守卫：任一被重置/改写，本断言即红。
    assertThat(componentOwnedBy(traded, H1).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(8L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(8L))
        .containsEntry(cycleInputKey(GRAIN), number(123L))
        .containsEntry(new LandAttributeKey("custom_note"), text("kept"));
    assertThat(componentOwnedBy(traded, H2).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(8L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(8L))
        .containsEntry(cycleInputKey(GRAIN), number(123L))
        .containsEntry(new LandAttributeKey("custom_note"), text("kept"));

    // base 不被 plan/apply 反向修改。
    assertThat(componentOwnedBy(baseMap, H1).attributes()).isEqualTo(original);
  }

  // ── 夹具 / 工具 ──────────────────────────────────────────────────────────

  /**
   * 10,000 毫亩、H1 所有、stage1
   * progress8/cycle8、startInputPaid=true、cycle_input:grain=123、FERTILITY=700。
   */
  private static GameMap inProductionLandMap() {
    return mapWith(
        Map.of(
            H0,
            arableLand(
                1L,
                component(
                    LAND_LOT,
                    10_000L,
                    inProductionAttrs(
                        H1.value(),
                        700L,
                        GRAIN_PLAN.value(),
                        "stage1",
                        8L,
                        8L,
                        true,
                        Map.of(GRAIN, 123L))))));
  }

  private static InventoryTransferPlan planPartialTrade(
      GameMap baseMap, long quantityMilli, HouseholdId targetOwner) {
    InventoryTransferContext context =
        new InventoryTransferContext(
            MAP_ID,
            ActorData.empty(),
            baseMap,
            EconomyData.empty(),
            InventoryLotViewBuilder.buildLandOnly(baseMap));
    return InventoryTransferPortImpl.standard(context)
        .plan(
            new InventoryTransferRequest(
                new InventoryTransferId("l2d-trade-" + quantityMilli),
                InventoryLotId.forLand(LAND_LOT),
                quantityMilli,
                targetOwner,
                Optional.of(H0),
                InventoryTransferReason.MARKET_TRADE));
  }

  private static GameMap applyTrade(GameMap baseMap, InventoryTransferPlan plan) {
    return MapChangeSet.apply(
        (MapChangeSet) plan.moduleChanges().get(InventoryTransferPlan.MAP_MODULE_KEY), baseMap);
  }

  private static void assertCarriedProductionAttributes(
      LandComponent component, HouseholdId owner) {
    assertThat(component.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(owner.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(700L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(8L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(8L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(123L));
  }

  private static LandComponent componentOwnedBy(GameMap map, HouseholdId owner) {
    LandTypeArea area = map.hexes().get(H0).land().byType().get(LandTypeId.ARABLE);
    LandComponent found = null;
    for (LandComponent component : area.components()) {
      if (!LandComponentOperations.ownerOf(component).equals(owner.value())) {
        continue;
      }
      if (found != null) {
        throw new AssertionError("owner 名下不止一个 ARABLE component: " + owner.value());
      }
      found = component;
    }
    if (found == null) {
      throw new AssertionError("owner 名下没有 ARABLE component: " + owner.value());
    }
    return found;
  }

  private static InventoryLot singleLotOf(InventoryLotView view, HouseholdId owner) {
    List<InventoryLot> lots = view.byOwner(owner);
    assertThat(lots).as("owner " + owner.value() + " 的 LAND lot 行数").hasSize(1);
    return lots.get(0);
  }

  private static long totalArable(GameMap map) {
    return map.hexes().get(H0).land().totalMilliMu(LandTypeId.ARABLE);
  }

  private static Map<LandAttributeKey, LandAttributeValue> withoutOwner(
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    Map<LandAttributeKey, LandAttributeValue> result = new LinkedHashMap<>(attributes);
    result.remove(LandAttributeKey.OWNER);
    return result;
  }

  private static LandAttributeKey cycleInputKey(CommodityId commodity) {
    return new LandAttributeKey(
        LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + commodity.value());
  }

  private static SimulationState state(Snapshot... snapshots) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (Snapshot snapshot : snapshots) {
      modules.put(snapshot.namespace(), snapshot);
    }
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static GameMap mapOf(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow()).map();
  }

  private static ActorData actorOf(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static ProductionData productionDataOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }
}
