package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.time.TransportCapacityBatchBook;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageEndAction;
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
 * ★★ <b>D-032 L2c：LAND HARVEST 的服务产出 sink 独立验收</b>（plan §4）。
 *
 * <p>判据（全部走真实 {@link LandProductionCycle#run}、真实 {@link InventoryLotViewBuilder#buildLandOnly} 与真实
 * settlement/executor 链路）：
 *
 * <ul>
 *   <li><b>A/服务产出入库与批次</b>：transport 的 1 日 HARVEST lot（service=1000/grain=7）不再抛 unsupported；
 *       capacity 按 {@code outputMilli / 1000} 折成 {@code capacityWorkMilli} 并写入 {@code (operator,
 *       homeHex, marketRoundId)} 批次；service 键不进入 operator 库存，普通 grain 产出进库存； map lot 收获后 Idle 且
 *       OWNER/FERTILITY 保留；
 *   <li><b>B/无 service 退化</b>：普通 grain plan 的 HARVEST 只替换 map/actor，economy 切片原样（批次表不变）；
 *   <li><b>C/失败语义</b>：operator 户不在 economy.classes、homeHex 无市场区、同键批次 zone 漂移各具名 fail-closed， 原
 *       state 逐值不变；
 *   <li><b>不可达分支</b>：{@code land-production-service-output-not-integral} 在公开入口上不可达 —— executor 的产出
 *       恒为 {@code outputPerUnit × scale × 1000}（{@link
 *       io.mosire.simos.production.ProductionLotDayExecutor}）， 故 service outputMilli 必为 1000
 *       的整数倍；本类用 {@code outputPerUnit=1} 的探针证明这一点，不伪造该状态，只在 impl-ledger 记录。
 * </ul>
 *
 * <p>★ 生产代码只读：若行为与 plan §4 不符，保留失败并在 impl-ledger 记 BLOCKED，不改生产代码。
 */
class LandProductionServiceSinkTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final LandComponentId LOT_A = id("arable@0_0:0");

  private static final CurrencyId SILVER = new CurrencyId("silver");

  /** 派生市场的规范序：{@code a-zone} 先于 {@code z-zone}，因此 marketIdAt 命中前者。 */
  private static final MarketZoneId ZONE = new MarketZoneId("a-zone");

  private static final MarketZoneId OTHER_ZONE = new MarketZoneId("z-zone");
  private static final CommodityId SERVICE_OUTPUT = CaravanTransportTemplate.SERVICE_OUTPUT;

  // ── A. 服务产出：capacity batch + 普通产出入库 ────────────────────────────

  @Test
  void transportServiceHarvestAccumulatesCapacityBatchAndKeepsServiceKeyOutOfInventory() {
    long tick = 1L;
    long marketRoundId = TransportCapacityBatchBook.marketRoundIdOf(tick);
    LandComponent carrier =
        component(
            LOT_A,
            3_000L,
            inProductionAttrs(
                H_A.value(), 55L, TRANSPORT_PLAN.value(), "haul", 0L, 0L, true, Map.of()));
    GameMap baseMap = mapWith(Map.of(H0, arableLand(carrier)));
    ActorData baseActor = actorData(inventory(H_A, Map.of(GRAIN, 5L)));
    EconomyData baseEconomy =
        economyFor(baseActor)
            .withMarketTables(Map.of(ZONE, MarketTable.singleHex(SILVER, H0, Map.of(GRAIN, 1L))));
    SimulationState state =
        state(
            baseMap, baseActor, ProductionData.empty().withIndustry(transportPlan()), baseEconomy);

    SimulationState day = LandProductionCycle.run(state, tick);

    assertThat(day).as("service 产出必须被 sink，而不是抛 unsupported").isNotSameAs(state);
    assertThat(day.modules().keySet())
        .containsExactlyInAnyOrder("map", "actor", "production", "economy");
    assertThat(day.module("production").orElseThrow())
        .as("production 模块不得被 land cycle 改写")
        .isSameAs(state.module("production").orElseThrow());

    // ── 批次键/值 ─────────────────────────────────────────────────────────
    EconomySnapshot dayEconomySnapshot = (EconomySnapshot) day.module("economy").orElseThrow();
    assertThat(dayEconomySnapshot)
        .as("有 service 产出时必须替换 economy 切片")
        .isNotSameAs(state.module("economy").orElseThrow());
    EconomyData dayEconomy = dayEconomySnapshot.data();

    TransportCapacityBatchKey expectedKey = new TransportCapacityBatchKey(H_A, H0, marketRoundId);
    assertThat(dayEconomy.transportCapacityBatches())
        .as("operator=lot.owner、homeHex=operator.view().hex()、marketRoundId=marketRoundIdOf(tick)")
        .containsOnlyKeys(expectedKey);
    TransportCapacityBatch batch = dayEconomy.transportCapacityBatches().get(expectedKey);
    assertThat(batch.capacityWorkMilli())
        .as("service outputMilli 3,000,000 / 1000 = 3,000（scale=3）")
        .isEqualTo(3_000L);
    assertThat(batch.marketZone())
        .isEqualTo(MarketTables.marketIdAt(dayEconomy, H0).orElseThrow())
        .isEqualTo(ZONE);

    // ── 库存：普通 grain 进 operator，service 键不进任何库存 ───────────────
    assertThat(balance(actorOf(day), H_A, GRAIN))
        .as("初始 5 + 普通产出 7×3×1000 = 21,005")
        .isEqualTo(21_005L);
    assertThat(actorOf(day).accounts().values())
        .allSatisfy(
            inventory -> assertThat(inventory.balances()).doesNotContainKey(SERVICE_OUTPUT));
    assertThat(
            actorOf(day).accounts().values().stream()
                .anyMatch(inventory -> inventory.balances().containsKey(SERVICE_OUTPUT)))
        .as("transport_capacity_service 是内部服务键，不得进入 operator 库存")
        .isFalse();

    // ── Map：收获后 Idle、OWNER/FERTILITY 保留、生产属性清空 ──────────────
    assertThat(lotStateOf(day, LOT_A)).isEqualTo(LotState.IDLE);
    LandComponent harvested = componentOf(mapOf(day), H0, LOT_A);
    assertThat(harvested.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(55L))
        .doesNotContainKeys(
            LandAttributeKey.CROP,
            LandAttributeKey.STAGE,
            LandAttributeKey.PROGRESS,
            LandAttributeKey.CYCLE_PROGRESS,
            LandAttributeKey.START_INPUT_PAID);
    assertThat(harvested.attributes().keySet())
        .noneMatch(
            key -> key.wireValue().startsWith(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX));

    // ── 入参状态完全不变 ─────────────────────────────────────────────────
    assertThat(balance(baseActor, H_A, GRAIN)).isEqualTo(5L);
    assertThat(((EconomySnapshot) state.module("economy").orElseThrow()).data())
        .isSameAs(baseEconomy);
    assertThat(baseEconomy.transportCapacityBatches()).isEmpty();
  }

  // ── B. 无 service：economy 切片不变 ───────────────────────────────────────

  @Test
  void grainHarvestWithoutServiceLeavesEconomySliceUntouchedAndMutatesOnlyMapAndActor() {
    LandComponent carrier =
        component(
            LOT_A,
            3_000L,
            inProductionAttrs(
                H_A.value(),
                55L,
                GRAIN_PLAN.value(),
                "stage2",
                89L,
                99L,
                true,
                Map.of(GRAIN, 300L)));
    GameMap baseMap = mapWith(Map.of(H0, arableLand(carrier)));
    ActorData baseActor = actorData(inventory(H_A, Map.of(GRAIN, 6L)));
    EconomyData baseEconomy = economyFor(baseActor);
    SimulationState state =
        state(baseMap, baseActor, ProductionData.empty().withIndustry(grainPlan()), baseEconomy);

    SimulationState day = LandProductionCycle.run(state, 1L);

    assertThat(day).isNotSameAs(state);
    assertThat(day.module("map").orElseThrow()).isNotSameAs(state.module("map").orElseThrow());
    assertThat(day.module("actor").orElseThrow()).isNotSameAs(state.module("actor").orElseThrow());
    assertThat(day.module("production").orElseThrow())
        .isSameAs(state.module("production").orElseThrow());
    assertThat(day.module("economy").orElseThrow())
        .as("无 service 产出时不得读取/改写 economy 切片")
        .isSameAs(state.module("economy").orElseThrow());
    assertThat(economyOf(day).transportCapacityBatches()).isEmpty();

    // 普通产出入 operator 库存：初始 6 − 投入 6 + grain 3,000,000 = 3,000,000。
    assertThat(balance(actorOf(day), H_A, GRAIN)).isEqualTo(3_000_000L);
    assertThat(balance(actorOf(day), H_A, FIBER)).isEqualTo(15_000L);
    assertThat(lotStateOf(day, LOT_A)).isEqualTo(LotState.IDLE);
    assertThat(componentOf(mapOf(day), H0, LOT_A).attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(55L))
        .doesNotContainKey(LandAttributeKey.CROP);
  }

  // ── C. 失败语义（fail-closed、原 state 不变） ─────────────────────────────

  @Test
  void operatorHouseholdMissingFromEconomyClassesThrowsNamedAndLeavesStateUnchanged() {
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 5L)));
    // economy.classes 里只有 H_B；operator H_A 缺失。
    EconomyData economyWithoutOperator = economyData(Map.of(H_B, H0));
    SimulationState state =
        state(
            serviceMap(),
            actor,
            ProductionData.empty().withIndustry(transportPlan()),
            economyWithoutOperator);

    assertThatThrownBy(() -> LandProductionCycle.run(state, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-production-service-operator-household-missing");

    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(5L);
    assertThat(lotStateOf(state, LOT_A)).isInstanceOf(LotState.InProduction.class);
    assertThat(economyOf(state).transportCapacityBatches()).isEmpty();
  }

  @Test
  void missingMarketZoneForHomeHexThrowsNamedAndLeavesStateUnchanged() {
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 5L)));
    SimulationState state =
        state(
            serviceMap(),
            actor,
            ProductionData.empty().withIndustry(transportPlan()),
            economyFor(actor));

    assertThatThrownBy(() -> LandProductionCycle.run(state, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-production-service-market-zone-missing");

    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(5L);
    assertThat(lotStateOf(state, LOT_A)).isInstanceOf(LotState.InProduction.class);
    assertThat(economyOf(state).transportCapacityBatches()).isEmpty();
  }

  @Test
  void existingBatchWithDifferentMarketZoneThrowsNamedAndLeavesStateUnchanged() {
    long tick = 1L;
    long marketRoundId = TransportCapacityBatchBook.marketRoundIdOf(tick);
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 5L)));

    MarketTable zoneTable = MarketTable.singleHex(SILVER, H0, Map.of(GRAIN, 1L));
    TransportCapacityBatchKey key = new TransportCapacityBatchKey(H_A, H0, marketRoundId);
    TransportCapacityBatch existing = new TransportCapacityBatch(OTHER_ZONE, 123L);
    // 两个派生市场都覆盖 H0：规范序 a-zone 先命中，批次却记在 z-zone ⇒ 必须具名拒绝。
    EconomyData economy =
        economyFor(actor)
            .withMarketTables(Map.of(ZONE, zoneTable, OTHER_ZONE, zoneTable))
            .withTransportCapacityBatches(Map.of(key, existing));
    SimulationState state =
        state(serviceMap(), actor, ProductionData.empty().withIndustry(transportPlan()), economy);

    assertThatThrownBy(() -> LandProductionCycle.run(state, tick))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-production-service-market-zone-mismatch");

    assertThat(balance(actorOf(state), H_A, GRAIN)).isEqualTo(5L);
    assertThat(lotStateOf(state, LOT_A)).isInstanceOf(LotState.InProduction.class);
    assertThat(economyOf(state).transportCapacityBatches())
        .containsExactlyEntriesOf(Map.of(key, existing));
  }

  @Test
  void
      serviceOutputPerUnitOneStillYieldsWholeCapacityBecauseExecutorOutputsMilliAreAlwaysThousands() {
    // plan 提示可用 outputPerUnit=1 触发“非整千”；实测 executor 恒 outputPerUnit×scale×1000，
    // 故 outputMilli=3,000、capacityWorkMilli=3 —— 非整千分支在公开入口不可达（见类注/impl-ledger）。
    IndustryDefinitionView perUnitOneServicePlan =
        stagedPlan(
            TRANSPORT_PLAN,
            1L,
            List.of(
                new ProductionStageDefinition(
                    "haul",
                    1L,
                    Map.of(),
                    Map.of(),
                    ProductionStageEndAction.HARVEST,
                    Map.of(SERVICE_OUTPUT, 1L))));
    LandComponent carrier =
        component(
            LOT_A,
            3_000L,
            inProductionAttrs(
                H_A.value(), 55L, TRANSPORT_PLAN.value(), "haul", 0L, 0L, true, Map.of()));
    ActorData actor = actorData(inventory(H_A, Map.of(GRAIN, 5L)));
    EconomyData economy =
        economyFor(actor)
            .withMarketTables(Map.of(ZONE, MarketTable.singleHex(SILVER, H0, Map.of(GRAIN, 1L))));
    SimulationState state =
        state(
            mapWith(Map.of(H0, arableLand(carrier))),
            actor,
            ProductionData.empty().withIndustry(perUnitOneServicePlan),
            economy);

    SimulationState day = LandProductionCycle.run(state, 1L);

    TransportCapacityBatchKey key =
        new TransportCapacityBatchKey(H_A, H0, TransportCapacityBatchBook.marketRoundIdOf(1L));
    assertThat(economyOf(day).transportCapacityBatches()).containsOnlyKeys(key);
    assertThat(economyOf(day).transportCapacityBatches().get(key).capacityWorkMilli())
        .as("service outputPerUnit=1、scale=3 ⇒ outputMilli=3,000 ⇒ capacity=3（整千）")
        .isEqualTo(3L);
  }

  // ── 私有工具 ──────────────────────────────────────────────────────────────

  private static GameMap serviceMap() {
    return mapWith(
        Map.of(
            H0,
            arableLand(
                component(
                    LOT_A,
                    3_000L,
                    inProductionAttrs(
                        H_A.value(),
                        55L,
                        TRANSPORT_PLAN.value(),
                        "haul",
                        0L,
                        0L,
                        true,
                        Map.of())))));
  }

  private static SimulationState state(
      GameMap map, ActorData actor, ProductionData production, EconomyData economy) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(REF, T0, map));
    modules.put("actor", new ActorSnapshot(REF, T0, actor));
    modules.put("production", new ProductionSnapshot(REF, T0, production));
    modules.put("economy", new EconomySnapshot(REF, T0, economy));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static GameMap mapOf(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow()).map();
  }

  private static ActorData actorOf(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static LotState lotStateOf(SimulationState state, LandComponentId lot) {
    return InventoryLotViewBuilder.buildLandOnly(mapOf(state))
        .require(InventoryLotId.forLand(lot))
        .state();
  }
}
