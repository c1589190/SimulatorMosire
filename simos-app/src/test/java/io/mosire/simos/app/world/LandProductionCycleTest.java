package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
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
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 L2b2b：LAND continuation 单推进步接线的独立验收</b>（plan §3.3）。
 *
 * <p>判据（全部走真实 {@link SimulationState}、真实 {@link InventoryLotViewBuilder#buildLandOnly} 与真实
 * opportunity/settlement 链路）：
 *
 * <ul>
 *   <li><b>A/连续推进</b>：10/3/1 三 lot 中 A/B InProduction、C IDLE；连续两次 run 各只推进一天，A 的 stage1
 *       8→9→(满)stage2 0，B 的 stage2 27→28→29，C 不自动启动；Map 生产属性、actor 扣料与 production 切片逐字段读数正确；
 *   <li><b>B/无变化退化</b>：只有 IDLE lot、任一必需切片缺席、legacy stages 为空且 lot 在产 ⇒ 原样返回同一 state；
 *   <li><b>C/service fail-closed</b>：transport 的 1 日 HARVEST 聚合出 service key 但 state 缺 economy 切片 ⇒
 *       具名抛错 {@code land-production-service-economy-missing}，不静默写库存；service sink 成功路径见 {@link
 *       LandProductionServiceSinkTest}；
 *   <li><b>D/边界</b>：只给 InProduction lot 生成 claim，一次 run 只推进一个 land day。
 * </ul>
 *
 * <p>★ 生产代码只读：若行为与 plan §3.3 不符，保留失败并在 impl-ledger 记 BLOCKED，不改生产代码。
 */
class LandProductionCycleTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final LandComponentId LOT_A = id("arable@0_0:0");
  private static final LandComponentId LOT_B = id("arable@0_0:1");
  private static final LandComponentId LOT_C = id("arable@0_0:2");

  // ── A. 真实 SimulationState：连续两个单推进步 ─────────────────────────────

  @Test
  void advancesOnlyInProductionLotsOneDayPerRunAndKeepsProductionSliceFieldwiseUnchanged() {
    GameMap baseMap =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))),
                    component(
                        LOT_B,
                        3_000L,
                        inProductionAttrs(
                            H_B.value(),
                            70L,
                            GRAIN_PLAN.value(),
                            "stage2",
                            27L,
                            37L,
                            false,
                            Map.of(GRAIN, 120L))),
                    component(LOT_C, 1_000L, idleAttrs(H_C.value(), 60L)))));
    ActorData baseActor =
        actorData(
            inventory(H_A, Map.of(GRAIN, 1_000L)),
            inventory(H_B, Map.of(GRAIN, 1_000L)),
            inventory(H_C, Map.of(GRAIN, 1_000L)));
    ProductionData baseProduction = ProductionData.empty().withIndustry(grainPlan());

    MapSnapshot mapSnapshot = new MapSnapshot(REF, T0, baseMap);
    ActorSnapshot actorSnapshot = new ActorSnapshot(REF, T0, baseActor);
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(REF, T0, baseProduction);
    SimulationState state = state(mapSnapshot, actorSnapshot, productionSnapshot);

    // ── 第一个 land day：tick=1 ───────────────────────────────────────────
    SimulationState day1 = LandProductionCycle.run(state, 1L);

    assertThat(day1).as("有成功推进时必须返回新 state").isNotSameAs(state);
    assertThat(day1.meta()).isSameAs(state.meta());
    assertThat(day1.info()).isSameAs(state.info());
    assertThat(day1.modules().keySet())
        .as("只有 map/actor 被替换；production 切片仍在")
        .containsExactlyInAnyOrder("map", "actor", "production");

    assertThat(state.module("map").orElseThrow()).isSameAs(mapSnapshot);
    assertThat(state.module("actor").orElseThrow()).isSameAs(actorSnapshot);
    assertThat(state.module("production").orElseThrow()).isSameAs(productionSnapshot);
    assertThat(day1.module("map").orElseThrow()).isNotSameAs(mapSnapshot);
    assertThat(day1.module("actor").orElseThrow()).isNotSameAs(actorSnapshot);
    assertThat(day1.module("production").orElseThrow())
        .as("production 模块不得被 land cycle 改写")
        .isSameAs(productionSnapshot);

    ProductionData day1Production = productionDataOf(day1);
    assertProductionDataFieldwiseEqualTo(baseProduction, day1Production);

    // A：stage1 8→9，cycle 8→9；只扣当前 tick 的 grain1×scale10=10。
    LandComponent rawA = componentOf(mapOf(day1), H0, LOT_A);
    assertThat(rawA.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(80L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(90L));
    assertThat(balance(actorOf(day1), H_A, GRAIN)).isEqualTo(990L);

    // B：stage2 27→28，cycle 37→38；只扣当前 tick 的 grain2×scale3=6。
    LandComponent rawB = componentOf(mapOf(day1), H0, LOT_B);
    assertThat(rawB.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_B.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(70L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(28L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(38L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(126L));
    assertThat(balance(actorOf(day1), H_B, GRAIN)).isEqualTo(994L);

    // C：IDLE lot 无 claim、不自动启动；属性与 actor 余额逐值不动。
    LandComponent rawC = componentOf(mapOf(day1), H0, LOT_C);
    assertThat(rawC.attributes()).isEqualTo(idleAttrs(H_C.value(), 60L));
    assertThat(lotStateOf(day1, LOT_C)).isEqualTo(LotState.IDLE);
    assertThat(balance(actorOf(day1), H_C, GRAIN)).isEqualTo(1_000L);

    // ── 第二个 land day：tick=2，证明不是一次性 ───────────────────────────
    SimulationState day2 = LandProductionCycle.run(day1, 2L);

    assertThat(day2).isNotSameAs(day1);
    assertThat(day2.module("production").orElseThrow())
        .as("第二个 land day 仍不得改 production 模块")
        .isSameAs(productionSnapshot);
    assertProductionDataFieldwiseEqualTo(baseProduction, productionDataOf(day2));

    // A：stage1 当天满 10 日 ⇒ 恰推进到 stage2 progress0/cycle10；cycle_input 累计 100。
    LandComponent rawA2 = componentOf(mapOf(day2), H0, LOT_A);
    assertThat(rawA2.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(80L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(0L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(10L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(false))
        .containsEntry(cycleInputKey(GRAIN), number(100L));
    assertThat(balance(actorOf(day2), H_A, GRAIN)).isEqualTo(980L);

    // B：stage2 28→29，cycle 38→39；再扣 grain6。
    LandComponent rawB2 = componentOf(mapOf(day2), H0, LOT_B);
    assertThat(rawB2.attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(29L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(39L))
        .containsEntry(cycleInputKey(GRAIN), number(132L));
    assertThat(balance(actorOf(day2), H_B, GRAIN)).isEqualTo(988L);

    // C：两个 land day 后仍是 IDLE，未被自动启动。
    assertThat(componentOf(mapOf(day2), H0, LOT_C).attributes())
        .isEqualTo(idleAttrs(H_C.value(), 60L));
    assertThat(lotStateOf(day2, LOT_C)).isEqualTo(LotState.IDLE);
    assertThat(balance(actorOf(day2), H_C, GRAIN)).isEqualTo(1_000L);
  }

  // ── A. L2e 双权威冲突 fail-closed ─────────────────────────────────────────

  @Test
  void
      inProductionLotWithLegacyProcessForSamePlanAndLocationFailsClosedNamedAndLeavesStateFieldwiseUnchanged() {
    GameMap map =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))),
                    component(
                        LOT_B,
                        3_000L,
                        inProductionAttrs(
                            H_B.value(),
                            70L,
                            GRAIN_PLAN.value(),
                            "stage2",
                            27L,
                            37L,
                            false,
                            Map.of(GRAIN, 120L))),
                    component(LOT_C, 1_000L, idleAttrs(H_C.value(), 60L)))));
    ActorData baseActor =
        actorData(
            inventory(H_A, Map.of(GRAIN, 1_000L)),
            inventory(H_B, Map.of(GRAIN, 1_000L)),
            inventory(H_C, Map.of(GRAIN, 1_000L)));
    ProductionData baseProduction =
        ProductionData.empty()
            .withIndustry(grainPlan())
            .withInstance(legacyProcess("legacy-unit-a", GRAIN_PLAN, H0));

    MapSnapshot mapSnapshot = new MapSnapshot(REF, T0, map);
    ActorSnapshot actorSnapshot = new ActorSnapshot(REF, T0, baseActor);
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(REF, T0, baseProduction);
    SimulationState state = state(mapSnapshot, actorSnapshot, productionSnapshot);
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> mapBefore = snapshot(map);

    assertThatThrownBy(() -> LandProductionCycle.run(state, 1L))
        .as("LAND lot 在产且同 plan+location 仍有 legacy 实例 ⇒ 双权威必须具名 fail-closed")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-production-legacy-process-authority-conflict")
        .hasMessageContaining("lot=" + InventoryLotId.forLand(LOT_A).value())
        .hasMessageContaining("location=" + H0)
        .hasMessageContaining("plan=" + GRAIN_PLAN.value())
        .hasMessageContaining("unit=legacy-unit-a");

    assertThat(state.module("map").orElseThrow()).isSameAs(mapSnapshot);
    assertThat(state.module("actor").orElseThrow()).isSameAs(actorSnapshot);
    assertThat(state.module("production").orElseThrow()).isSameAs(productionSnapshot);
    assertThat(snapshot(mapOf(state))).as("失败前 map 逐字段不变").isEqualTo(mapBefore);
    assertThat(actorOf(state)).as("失败前 actor 逐字段不变").isSameAs(baseActor);
    assertProductionDataFieldwiseEqualTo(baseProduction, productionDataOf(state));
    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(1_000L);
  }

  // ── B. L2e 不误伤：不同 location / 不同 plan / 仅 IDLE ─────────────────────

  @Test
  void samePlanLegacyProcessAtDifferentLocationDoesNotBlockInProductionLotAdvance() {
    GameMap map =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))))));
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 1_000L)));
    ProductionData production =
        ProductionData.empty()
            .withIndustry(grainPlan())
            .withInstance(legacyProcess("legacy-unit-away", GRAIN_PLAN, H1));
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(REF, T0, production);
    SimulationState state =
        state(new MapSnapshot(REF, T0, map), new ActorSnapshot(REF, T0, actor), productionSnapshot);

    SimulationState day1 = LandProductionCycle.run(state, 1L);

    assertThat(day1).as("同 plan 但 legacy 实例在别处 ⇒ 不误伤").isNotSameAs(state);
    assertThat(componentOf(mapOf(day1), H0, LOT_A).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(cycleInputKey(GRAIN), number(90L));
    assertThat(balance(actorOf(day1), H_A, GRAIN)).isEqualTo(990L);
    assertThat(day1.module("production").orElseThrow()).isSameAs(productionSnapshot);
  }

  @Test
  void differentPlanLegacyProcessAtSameLocationDoesNotBlockInProductionLotAdvance() {
    IndustryId otherPlan = new IndustryId("other_farm");
    GameMap map =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))))));
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 1_000L)));
    ProductionData production =
        ProductionData.empty()
            .withIndustry(grainPlan())
            .withInstance(legacyProcess("legacy-unit-other", otherPlan, H0));
    ProductionSnapshot productionSnapshot = new ProductionSnapshot(REF, T0, production);
    SimulationState state =
        state(new MapSnapshot(REF, T0, map), new ActorSnapshot(REF, T0, actor), productionSnapshot);

    SimulationState day1 = LandProductionCycle.run(state, 1L);

    assertThat(day1).as("同 location 但 legacy 实例是别的 plan ⇒ 不误伤").isNotSameAs(state);
    assertThat(componentOf(mapOf(day1), H0, LOT_A).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(cycleInputKey(GRAIN), number(90L));
    assertThat(balance(actorOf(day1), H_A, GRAIN)).isEqualTo(990L);
    assertThat(day1.module("production").orElseThrow()).isSameAs(productionSnapshot);
  }

  @Test
  void idleOnlyLotWithMatchingLegacyProcessReturnsSameStateWithoutClaims() {
    GameMap map =
        mapWith(Map.of(H0, arableLand(3L, component(LOT_C, 1_000L, idleAttrs(H_C.value(), 60L)))));
    ActorData actor = actorData(inventory(H_C, Map.of(GRAIN, 1_000L)));
    ProductionData production =
        ProductionData.empty()
            .withIndustry(grainPlan())
            .withInstance(legacyProcess("legacy-unit-a", GRAIN_PLAN, H0));
    SimulationState state =
        state(
            new MapSnapshot(REF, T0, map),
            new ActorSnapshot(REF, T0, actor),
            new ProductionSnapshot(REF, T0, production));

    assertThat(LandProductionCycle.run(state, 1L))
        .as("只有 IDLE lot ⇒ claims 为空，不得因 legacy 实例误报冲突或自动启动")
        .isSameAs(state);
    assertThat(lotStateOf(state, LOT_C)).isEqualTo(LotState.IDLE);
    assertThat(balance(actorOf(state), H_C, GRAIN)).isEqualTo(1_000L);
  }

  // ── B. 无变化退化 ─────────────────────────────────────────────────────────

  @Test
  void idleOnlyLandReturnsSameStateWithoutOpportunityOrClaim() {
    GameMap map =
        mapWith(Map.of(H0, arableLand(3L, component(LOT_C, 1_000L, idleAttrs(H_C.value(), 60L)))));
    SimulationState state =
        state(
            new MapSnapshot(REF, T0, map),
            new ActorSnapshot(REF, T0, actorData(inventory(H_C, Map.of(GRAIN, 1_000L)))),
            new ProductionSnapshot(REF, T0, ProductionData.empty().withIndustry(grainPlan())));

    assertThat(LandProductionCycle.run(state, 1L)).isSameAs(state);
  }

  @Test
  void anyMissingRequiredSliceReturnsSameState() {
    GameMap map =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))))));
    MapSnapshot mapSnapshot = new MapSnapshot(REF, T0, map);
    ActorSnapshot actorSnapshot =
        new ActorSnapshot(REF, T0, actorData(inventory(H_A, Map.of(GRAIN, 1_000L))));
    ProductionSnapshot productionSnapshot =
        new ProductionSnapshot(REF, T0, ProductionData.empty().withIndustry(grainPlan()));

    SimulationState withoutMap = state(actorSnapshot, productionSnapshot);
    SimulationState withoutActor = state(mapSnapshot, productionSnapshot);
    SimulationState withoutProduction = state(mapSnapshot, actorSnapshot);

    assertThat(LandProductionCycle.run(withoutMap, 1L)).isSameAs(withoutMap);
    assertThat(LandProductionCycle.run(withoutActor, 1L)).isSameAs(withoutActor);
    assertThat(LandProductionCycle.run(withoutProduction, 1L)).isSameAs(withoutProduction);
  }

  @Test
  void legacyStagesEmptyWithInProductionLotReturnsSameState() {
    IndustryDefinitionView legacyPlan = stagedPlan(GRAIN_PLAN, 100L, List.of());
    GameMap map =
        mapWith(
            Map.of(
                H0,
                arableLand(
                    component(
                        LOT_A,
                        10_000L,
                        inProductionAttrs(
                            H_A.value(),
                            80L,
                            GRAIN_PLAN.value(),
                            "stage1",
                            8L,
                            8L,
                            true,
                            Map.of(GRAIN, 80L))))));
    SimulationState state =
        state(
            new MapSnapshot(REF, T0, map),
            new ActorSnapshot(REF, T0, actorData(inventory(H_A, Map.of(GRAIN, 1_000L)))),
            new ProductionSnapshot(REF, T0, ProductionData.empty().withIndustry(legacyPlan)));

    assertThat(LandProductionCycle.run(state, 1L))
        .as("legacy stages 为空 ⇒ 无机会/claim，不得清状态或扣料")
        .isSameAs(state);
    assertThat(lotStateOf(state, LOT_A)).isInstanceOf(LotState.InProduction.class);
    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(1_000L);
  }

  // ── C. serviceOutput fail-closed：缺 economy 切片 ─────────────────────────

  @Test
  void serviceOutputWithoutEconomySliceFailsClosedNamedAndDoesNotMutateInputState() {
    LandComponent carrier =
        component(
            LOT_A,
            1_000L,
            inProductionAttrs(
                H_A.value(), 55L, TRANSPORT_PLAN.value(), "haul", 0L, 0L, true, Map.of()));
    GameMap map = mapWith(Map.of(H0, arableLand(carrier)));
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 42L)));
    SimulationState state =
        state(
            new MapSnapshot(REF, T0, map),
            new ActorSnapshot(REF, T0, actor),
            new ProductionSnapshot(REF, T0, ProductionData.empty().withIndustry(transportPlan())));
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> mapBefore = snapshot(map);

    assertThatThrownBy(() -> LandProductionCycle.run(state, 1L))
        .as("L2c：service 产出需要 economy 切片；缺失必须具名 fail-closed")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-production-service-economy-missing");

    assertThat(snapshot(mapOf(state))).isEqualTo(mapBefore);
    assertThat(componentOf(mapOf(state), H0, LOT_A).attributes())
        .containsEntry(LandAttributeKey.STAGE, text("haul"))
        .containsEntry(LandAttributeKey.PROGRESS, number(0L));
    assertThat(actorOf(state).accounts()).isSameAs(actor.accounts());
    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(42L);
    assertThat(
            actorOf(state).accounts().values().stream()
                .anyMatch(
                    inventory ->
                        inventory.balances().containsKey(CaravanTransportTemplate.SERVICE_OUTPUT)))
        .isFalse();
  }

  // ── 私有工具 ──────────────────────────────────────────────────────────────

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

  private static void assertProductionDataFieldwiseEqualTo(
      ProductionData expected, ProductionData actual) {
    assertThat(actual.industries()).isEqualTo(expected.industries());
    assertThat(actual.modes()).isEqualTo(expected.modes());
    assertThat(actual.instances()).isEqualTo(expected.instances());
    assertThat(actual.experienceTracks()).isEqualTo(expected.experienceTracks());
    assertThat(actual.householdExperience()).isEqualTo(expected.householdExperience());
    assertThat(actual.unlocks()).isEqualTo(expected.unlocks());
    assertThat(actual.efficiency()).isEqualTo(expected.efficiency());
    assertThat(actual.outputQuantityOverrides()).isEqualTo(expected.outputQuantityOverrides());
  }

  private static LotState lotStateOf(SimulationState state, LandComponentId lot) {
    return InventoryLotViewBuilder.buildLandOnly(mapOf(state))
        .require(InventoryLotId.forLand(lot))
        .state();
  }

  private static LandAttributeKey cycleInputKey(CommodityId commodity) {
    return new LandAttributeKey(
        LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + commodity.value());
  }
}
