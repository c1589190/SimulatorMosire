package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.LandProductionSettlementTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.production.ProductionLotDayExecutor;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 L2b2a：ProductionClaim + LandProductionSettlement 的独立验收</b>（plan §3.1）。
 *
 * <p>判据（全部走真实 {@link InventoryLotViewBuilder} + 真实 {@link ProductionOpportunityBook#from}；只用手工 plan
 * / land / actor 夹具）：
 *
 * <ul>
 *   <li><b>A</b>：三 lot 10/3/1 + 真实 staged plan，A/B InProduction、C IDLE；按 claim 顺序扣 operator 库存并写回
 *       Map；
 *   <li><b>B/HARVEST</b>：末段 harvest 产出入 operator 库存、Map 生产属性清空但 OWNER/FERTILITY 保留、nextState=Idle；
 *   <li><b>C/拒绝</b>：库存不足 / owner mismatch / plan missing / opportunity missing / operator inventory
 *       missing 各具名； 重复 lot 整次 fail-closed；transport_capacity_service 只进 serviceOutputs；
 *   <li><b>D/冻结</b>：available = balance − frozen，不是 balance；
 *   <li><b>E/多 claim</b>：同一 operator 的 claim 顺序看到前序扣/加后的余额；
 *   <li><b>F/不可变</b>：settle 返回新 map/actor、入参不变、结果 map/list 不可变。
 * </ul>
 *
 * <p>★ 生产代码只读：若行为与 plan §3.1 不符，保留失败并在 impl-ledger 记 BLOCKED，不改生产代码。
 */
class LandProductionSettlementTest {

  private static final LandComponentId LOT_A = id("arable@0_0:0");
  private static final LandComponentId LOT_B = id("arable@0_0:1");
  private static final LandComponentId LOT_C = id("arable@0_0:2");

  // ── A. 三 lot 10/3/1 + 真实 plan ───────────────────────────────────────────

  @Test
  void settlesThreeLotsAndWritesProgressAndCycleInputBackToMap() {
    LandComponent a =
        component(
            LOT_A,
            10_000L,
            inProductionAttrs(
                H_A.value(), 80L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 80L)));
    LandComponent b =
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
                Map.of(GRAIN, 120L)));
    LandComponent c = component(LOT_C, 1_000L, idleAttrs(H_C.value(), 60L));
    GameMap base = mapWith(Map.of(H0, arableLand(a, b, c)));
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlementTestFixtures.World world =
        world(
            actorData(
                inventory(H_A, Map.of(GRAIN, 1_000L)),
                inventory(H_B, Map.of(GRAIN, 1_000L)),
                inventory(H_C, Map.of(GRAIN, 1_000L))),
            base,
            grainPlan());

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(
                claim("c-a", LOT_A, GRAIN_PLAN, H_A, H_A),
                claim("c-b", LOT_B, GRAIN_PLAN, H_B, H_B),
                claim("c-c", LOT_C, GRAIN_PLAN, H_C, H_C)));

    assertThat(result.rejections()).as("三条 claim 都应成功").isEmpty();
    assertThat(result.receipts()).hasSize(3);
    assertThat(result.receipts())
        .extracting(receipt -> receipt.status())
        .containsOnly(ProductionLotDayExecutor.Status.CONTINUE);
    assertThat(result.receipts())
        .extracting(receipt -> receipt.claim().claimId())
        .containsExactly("c-a", "c-b", "c-c");

    // 逐 claim 实际消耗
    assertThat(result.receipts().get(0).consumedInputs())
        .as("A scale=10，本 tick tickInput grain1 ⇒ 消耗 10")
        .containsExactlyEntriesOf(Map.of(GRAIN, 10L));
    assertThat(result.receipts().get(1).consumedInputs())
        .as("B scale=3，本 tick tickInput grain2 ⇒ 消耗 6")
        .containsExactlyEntriesOf(Map.of(GRAIN, 6L));
    assertThat(result.receipts().get(2).consumedInputs())
        .as("C 从 IDLE 新开：start grain100×1 + tick grain1×1 = 101")
        .containsExactlyEntriesOf(Map.of(GRAIN, 101L));

    // operator 库存逐户剩余
    assertThat(balance(result.actor(), H_A, GRAIN)).isEqualTo(990L);
    assertThat(balance(result.actor(), H_B, GRAIN)).isEqualTo(994L);
    assertThat(balance(result.actor(), H_C, GRAIN)).isEqualTo(899L);

    // 原图不改，目标 hex 总量不变
    assertThat(snapshot(base)).isEqualTo(before);
    assertThat(totalArableMilliMu(result.map(), H0)).isEqualTo(14_000L);

    // A：stage1 progress9/cycle9，cycle_input grain 80+10=90
    LandComponent rawA = componentOf(result.map(), H0, LOT_A);
    assertThat(rawA.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(80L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(9L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(90L));

    // B：stage2 progress28/cycle38，cycle_input grain 120+6=126
    LandComponent rawB = componentOf(result.map(), H0, LOT_B);
    assertThat(rawB.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_B.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(70L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage2"))
        .containsEntry(LandAttributeKey.PROGRESS, number(28L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(38L))
        .containsEntry(cycleInputKey(GRAIN), number(126L));

    // C：从 IDLE 新开 stage1 progress1/cycle1/startPaid true，cycle_input grain101
    LandComponent rawC = componentOf(result.map(), H0, LOT_C);
    assertThat(rawC.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_C.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(60L))
        .containsEntry(LandAttributeKey.CROP, text(GRAIN_PLAN.value()))
        .containsEntry(LandAttributeKey.STAGE, text("stage1"))
        .containsEntry(LandAttributeKey.PROGRESS, number(1L))
        .containsEntry(LandAttributeKey.CYCLE_PROGRESS, number(1L))
        .containsEntry(LandAttributeKey.START_INPUT_PAID, bool(true))
        .containsEntry(cycleInputKey(GRAIN), number(101L));

    // Builder 读回逐字段一致
    InventoryLotView readBack =
        InventoryLotViewBuilder.build(result.actor(), world.economy(), result.map());
    assertThat(readBack.require(InventoryLotId.forLand(LOT_A)).state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.stageId()).isEqualTo("stage1");
              assertThat(state.stageProgressDays()).isEqualTo(9L);
              assertThat(state.cycleProgressDays()).isEqualTo(9L);
              assertThat(state.startInputPaid()).isTrue();
              assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 90L));
            });
    assertThat(readBack.require(InventoryLotId.forLand(LOT_B)).state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.stageId()).isEqualTo("stage2");
              assertThat(state.stageProgressDays()).isEqualTo(28L);
              assertThat(state.cycleProgressDays()).isEqualTo(38L);
              assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 126L));
            });
    assertThat(readBack.require(InventoryLotId.forLand(LOT_C)).state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.stageId()).isEqualTo("stage1");
              assertThat(state.stageProgressDays()).isEqualTo(1L);
              assertThat(state.cycleProgressDays()).isEqualTo(1L);
              assertThat(state.startInputPaid()).isTrue();
              assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 101L));
            });
  }

  // ── B. HARVEST ────────────────────────────────────────────────────────────

  @Test
  void harvestAddsOutputsAndClearsMapProductionAttributesKeepingOwnerAndFertility() {
    LandComponent a =
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
    GameMap base = mapWith(Map.of(H0, arableLand(a)));

    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 6L))), base, grainPlan());

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("harvest", LOT_A, GRAIN_PLAN, H_A, H_A)));

    assertThat(result.rejections()).isEmpty();
    assertThat(result.receipts()).hasSize(1);
    LandProductionSettlement.Receipt receipt = result.receipts().get(0);
    assertThat(receipt.status()).isEqualTo(ProductionLotDayExecutor.Status.HARVESTED);
    assertThat(receipt.consumedInputs())
        .as("scale=3，stage2 tickInput grain2 ⇒ 消耗 6")
        .containsExactlyEntriesOf(Map.of(GRAIN, 6L));
    assertThat(receipt.outputs())
        .as("outputPerUnit × scale × 1000")
        .containsEntry(GRAIN, 3_000_000L)
        .containsEntry(FIBER, 15_000L);
    assertThat(receipt.nextState()).isEqualTo(LotState.IDLE);

    // 库存：投入先扣、产出后加
    assertThat(balance(result.actor(), H_A, GRAIN)).isEqualTo(3_000_000L);
    assertThat(balance(result.actor(), H_A, FIBER)).isEqualTo(15_000L);

    // Map：生产属性全清，OWNER/FERTILITY 保留
    LandComponent cleared = componentOf(result.map(), H0, LOT_A);
    assertThat(cleared.quantityMilliMu()).isEqualTo(3_000L);
    assertThat(cleared.attributes())
        .containsEntry(LandAttributeKey.OWNER, text(H_A.value()))
        .containsEntry(LandAttributeKey.FERTILITY, number(55L));
    assertThat(cleared.attributes())
        .doesNotContainKeys(
            LandAttributeKey.CROP,
            LandAttributeKey.STAGE,
            LandAttributeKey.PROGRESS,
            LandAttributeKey.CYCLE_PROGRESS,
            LandAttributeKey.START_INPUT_PAID);
    assertThat(cleared.attributes().keySet())
        .noneMatch(
            key -> key.wireValue().startsWith(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX));

    InventoryLotView readBack =
        InventoryLotViewBuilder.build(result.actor(), world.economy(), result.map());
    assertThat(readBack.require(InventoryLotId.forLand(LOT_A)).state()).isEqualTo(LotState.IDLE);
    assertThat(readBack.require(InventoryLotId.forLand(LOT_A)).owner()).isEqualTo(H_A);
  }

  // ── C. 拒绝 ───────────────────────────────────────────────────────────────

  @Test
  void insufficientOperatorStockRejectsShortfallAndLeavesMapAndActorUntouched() {
    LandComponent a =
        component(
            LOT_A,
            10_000L,
            inProductionAttrs(
                H_A.value(), 80L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 80L)));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 9L))), base, grainPlan());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("short", LOT_A, GRAIN_PLAN, H_A, H_A)));

    assertThat(result.receipts()).isEmpty();
    assertThat(result.rejections()).hasSize(1);
    assertThat(result.rejections().get(0).reason()).isEqualTo("stage-input-shortfall");
    assertThat(result.map()).as("拒绝不写 Map，返回入参原图").isSameAs(world.map());
    assertThat(result.actor()).as("拒绝不扣库存，返回入参 actor").isSameAs(world.actor());
    assertThat(balance(result.actor(), H_A, GRAIN)).isEqualTo(9L);
    assertThat(snapshot(world.map())).isEqualTo(before);
  }

  @Test
  void ownerMismatchRejectedNamed() {
    LandComponent a = component(LOT_A, 10_000L, idleAttrs(H_A.value(), 80L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("owner-mismatch", LOT_A, GRAIN_PLAN, H_B, H_A)));

    assertRejectedWithoutMutation(world, before, result, "production-claim-owner-mismatch");
  }

  @Test
  void planMissingRejectedNamed() {
    LandComponent a = component(LOT_A, 10_000L, idleAttrs(H_A.value(), 80L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());
    IndustryId missingPlan = new IndustryId("missing_plan");
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("plan-missing", LOT_A, missingPlan, H_A, H_A)));

    assertRejectedWithoutMutation(world, before, result, "production-claim-plan-missing");
  }

  @Test
  void opportunityMissingRejectedNamed() {
    LandComponent a = component(LOT_A, 10_000L, idleAttrs(H_A.value(), 80L));
    LandComponent b = component(LOT_B, 10_000L, idleAttrs(H_A.value(), 70L));
    GameMap base = mapWith(Map.of(H0, arableLand(a, b)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());

    // 真实 from：只从“仅有 LOT_A”的视图派生机会簿，所以 LOT_B 没有机会。
    GameMap subset = mapWith(Map.of(H0, arableLand(a)));
    InventoryLotView subsetView = view(world.actor(), world.economy(), subset);
    ProductionOpportunityBook restricted = opportunities(subsetView, world.plans());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            restricted,
            world.plans(),
            List.of(claim("opportunity-missing", LOT_B, GRAIN_PLAN, H_A, H_A)));

    assertRejectedWithoutMutation(world, before, result, "production-claim-opportunity-missing");
  }

  @Test
  void operatorInventoryMissingRejectedNamed() {
    LandComponent a = component(LOT_A, 10_000L, idleAttrs(H_A.value(), 80L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("operator-missing", LOT_A, GRAIN_PLAN, H_A, H_NO_ACCOUNT)));

    assertRejectedWithoutMutation(
        world, before, result, "production-claim-operator-inventory-missing");
  }

  @Test
  void duplicateLotClaimsThrowWholeBatchWithoutReturningHalfResult() {
    LandComponent a = component(LOT_A, 10_000L, idleAttrs(H_A.value(), 80L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    assertThatThrownBy(
            () ->
                LandProductionSettlement.settle(
                    world.map(),
                    world.actor(),
                    world.view(),
                    world.opportunities(),
                    world.plans(),
                    List.of(
                        claim("dup-1", LOT_A, GRAIN_PLAN, H_A, H_A),
                        claim("dup-2", LOT_A, GRAIN_PLAN, H_A, H_A))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("production-claim-duplicate-lot");

    // 整次抛：入参 map/actor 逐字段不变
    assertThat(snapshot(world.map())).isEqualTo(before);
    assertThat(balance(world.actor(), H_A, GRAIN)).isEqualTo(1_000L);
  }

  @Test
  void transportCapacityServiceStaysInServiceOutputsAndOutOfOperatorInventory() {
    LandComponent a = component(LOT_A, 3_000L, idleAttrs(H_A.value(), 80L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 100L))), base, transportPlan());

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("transport", LOT_A, TRANSPORT_PLAN, H_A, H_A)));

    assertThat(result.rejections()).isEmpty();
    assertThat(result.receipts()).hasSize(1);
    ProductionLotDayExecutor.Status status = result.receipts().get(0).status();
    assertThat(status).isEqualTo(ProductionLotDayExecutor.Status.HARVESTED);

    CommodityId serviceKey = CaravanTransportTemplate.SERVICE_OUTPUT;
    assertThat(result.receipts().get(0).outputs())
        .containsEntry(serviceKey, 3_000_000L)
        .containsEntry(GRAIN, 21_000L);
    assertThat(result.serviceOutputs())
        .as("服务键只聚合到 Result.serviceOutputs")
        .containsExactlyEntriesOf(Map.of(serviceKey, 3_000_000L));

    // 普通 grain 产出进 operator 库存；服务键不进入余额表
    assertThat(balance(result.actor(), H_A, GRAIN)).isEqualTo(21_100L);
    assertThat(
            result
                .actor()
                .accounts()
                .get(new io.mosire.simos.actor.model.HouseholdAccountKey(H_A))
                .balances())
        .doesNotContainKey(serviceKey);

    assertThat(componentOf(result.map(), H0, LOT_A).attributes())
        .doesNotContainKeys(LandAttributeKey.CROP, LandAttributeKey.STAGE);
  }

  // ── D. 余额冻结 ───────────────────────────────────────────────────────────

  @Test
  void frozenBalanceReducesAvailableInputsInsteadOfUsingGrossBalance() {
    LandComponent a =
        component(
            LOT_A,
            10_000L,
            inProductionAttrs(
                H_A.value(), 80L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 80L)));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(
            actorData(inventory(H_A, Map.of(GRAIN, 100L), Map.of(GRAIN, 95L))), base, grainPlan());
    List<LandProductionSettlementTestFixtures.ComponentSnapshot> before = snapshot(base);

    // 本 tick 需要 grain10：balance=100 足够，但 available=100−95=5 不足 ⇒ 必须拒绝
    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("frozen", LOT_A, GRAIN_PLAN, H_A, H_A)));

    assertThat(result.receipts()).isEmpty();
    assertThat(result.rejections()).hasSize(1);
    assertThat(result.rejections().get(0).reason()).isEqualTo("stage-input-shortfall");
    assertThat(result.map()).isSameAs(world.map());
    assertThat(result.actor()).isSameAs(world.actor());
    assertThat(balance(result.actor(), H_A, GRAIN)).isEqualTo(100L);
    assertThat(frozen(result.actor(), H_A, GRAIN)).isEqualTo(95L);
    assertThat(snapshot(world.map())).isEqualTo(before);
  }

  // ── E. 多 claim 同 operator ───────────────────────────────────────────────

  @Test
  void laterClaimSeesEarlierClaimDeductionOnSameOperator() {
    // 两个 lot 都处于 stage1 progress8、startPaid=true：scale 10 需 grain10，scale 3 需 grain3。
    // 初始 12：第一 claim 扣 10 后只剩 2 ⇒ 第二 claim 必须拒绝（若拿 claim 前的 12 就会误判成功）。
    LandProductionSettlementTestFixtures.World world = twoDeductionLotsWorld(12L);

    LandProductionSettlement.Result rejected =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(
                claim("deduct-1", LOT_A, GRAIN_PLAN, H_A, H_A),
                claim("deduct-2", LOT_B, GRAIN_PLAN, H_A, H_A)));

    assertThat(rejected.receipts()).hasSize(1);
    assertThat(rejected.rejections()).hasSize(1);
    assertThat(rejected.rejections().get(0).claim().claimId()).isEqualTo("deduct-2");
    assertThat(rejected.rejections().get(0).reason()).isEqualTo("stage-input-shortfall");
    assertThat(rejected.receipts().get(0).consumedInputs())
        .containsExactlyEntriesOf(Map.of(GRAIN, 10L));
    assertThat(balance(rejected.actor(), H_A, GRAIN)).isEqualTo(2L);

    // 初始 13：两条都够，最终 13−10−3=0，证明第二 claim 看到的是第一 claim 扣后的余额。
    LandProductionSettlementTestFixtures.World enough = twoDeductionLotsWorld(13L);
    LandProductionSettlement.Result both =
        LandProductionSettlement.settle(
            enough.map(),
            enough.actor(),
            enough.view(),
            enough.opportunities(),
            enough.plans(),
            List.of(
                claim("deduct-1", LOT_A, GRAIN_PLAN, H_A, H_A),
                claim("deduct-2", LOT_B, GRAIN_PLAN, H_A, H_A)));

    assertThat(both.rejections()).isEmpty();
    assertThat(both.receipts()).hasSize(2);
    assertThat(both.receipts().get(1).consumedInputs()).containsExactlyEntriesOf(Map.of(GRAIN, 3L));
    assertThat(balance(both.actor(), H_A, GRAIN)).isZero();
    assertThat(componentOf(both.map(), H0, LOT_B).attributes().get(LandAttributeKey.PROGRESS))
        .isEqualTo(number(9L));
  }

  @Test
  void laterClaimSeesEarlierHarvestOutputAddedToSameOperator() {
    // LOT_A：stage2 89/99，scale3 harvest ⇒ 先扣 grain6，再产 grain3,000,000 / fiber15,000。
    // LOT_B：stage1 progress8，scale10 需 grain10；初始只有 6，若看不到前序产出必拒绝。
    LandComponent a =
        component(
            LOT_A,
            3_000L,
            inProductionAttrs(
                H_A.value(),
                90L,
                GRAIN_PLAN.value(),
                "stage2",
                89L,
                99L,
                true,
                Map.of(GRAIN, 300L)));
    LandComponent b =
        component(
            LOT_B,
            10_000L,
            inProductionAttrs(
                H_A.value(), 80L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 80L)));
    GameMap base = mapWith(Map.of(H0, arableLand(a, b)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 6L))), base, grainPlan());

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(
                claim("harvest-first", LOT_A, GRAIN_PLAN, H_A, H_A),
                claim("consume-produced", LOT_B, GRAIN_PLAN, H_A, H_A)));

    assertThat(result.rejections()).isEmpty();
    assertThat(result.receipts()).hasSize(2);
    assertThat(result.receipts().get(0).status())
        .isEqualTo(ProductionLotDayExecutor.Status.HARVESTED);
    assertThat(result.receipts().get(1).consumedInputs())
        .containsExactlyEntriesOf(Map.of(GRAIN, 10L));
    assertThat(balance(result.actor(), H_A, GRAIN))
        .as("3,000,000 产出 − 第二 claim 10")
        .isEqualTo(2_999_990L);
    assertThat(balance(result.actor(), H_A, FIBER)).isEqualTo(15_000L);
    assertThat(componentOf(result.map(), H0, LOT_A).attributes())
        .doesNotContainKey(LandAttributeKey.CROP);
  }

  // ── F. 不可变性 ───────────────────────────────────────────────────────────

  @Test
  void settleReturnsNewMapAndActorAndLeavesInputsAndResultCollectionsImmutable() {
    LandComponent a = component(LOT_A, 1_000L, idleAttrs(H_A.value(), 60L));
    GameMap base = mapWith(Map.of(H0, arableLand(a)));
    LandProductionSettlementTestFixtures.World world =
        world(actorData(inventory(H_A, Map.of(GRAIN, 1_000L))), base, grainPlan());
    var mapBefore = snapshot(base);
    var actorAccountsBefore = world.actor().accounts();

    LandProductionSettlement.Result result =
        LandProductionSettlement.settle(
            world.map(),
            world.actor(),
            world.view(),
            world.opportunities(),
            world.plans(),
            List.of(claim("immutable", LOT_A, GRAIN_PLAN, H_A, H_A)));

    assertThat(result.receipts()).hasSize(1);
    assertThat(result.map()).as("成功路径返回新 map").isNotSameAs(base);
    assertThat(result.actor()).as("成功路径返回新 actor").isNotSameAs(world.actor());
    assertThat(snapshot(base)).as("入参 map 不变").isEqualTo(mapBefore);
    assertThat(world.actor().accounts()).as("入参 actor 不变").isSameAs(actorAccountsBefore);
    assertThat(balance(world.actor(), H_A, GRAIN)).isEqualTo(1_000L);

    assertThatThrownBy(() -> result.receipts().add(result.receipts().get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.rejections().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.serviceOutputs().put(GRAIN, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.receipts().get(0).consumedInputs().put(GRAIN, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.receipts().get(0).outputs().put(GRAIN, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> world.view().lots().add(world.view().lots().get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── 私有工具 ──────────────────────────────────────────────────────────────

  private static LandProductionSettlementTestFixtures.World twoDeductionLotsWorld(long grain) {
    LandComponent a =
        component(
            LOT_A,
            10_000L,
            inProductionAttrs(
                H_A.value(), 80L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 80L)));
    LandComponent b =
        component(
            LOT_B,
            3_000L,
            inProductionAttrs(
                H_A.value(), 70L, GRAIN_PLAN.value(), "stage1", 8L, 8L, true, Map.of(GRAIN, 30L)));
    return world(
        actorData(inventory(H_A, Map.of(GRAIN, grain))),
        mapWith(Map.of(H0, arableLand(a, b))),
        grainPlan());
  }

  private static void assertRejectedWithoutMutation(
      LandProductionSettlementTestFixtures.World world,
      List<LandProductionSettlementTestFixtures.ComponentSnapshot> before,
      LandProductionSettlement.Result result,
      String reason) {
    assertThat(result.receipts()).isEmpty();
    assertThat(result.rejections()).hasSize(1);
    assertThat(result.rejections().get(0).reason()).isEqualTo(reason);
    assertThat(result.map()).isSameAs(world.map());
    assertThat(result.actor()).isSameAs(world.actor());
    assertThat(snapshot(world.map())).isEqualTo(before);
    assertThat(balance(world.actor(), H_A, GRAIN)).isEqualTo(1_000L);
  }

  private static LandAttributeKey cycleInputKey(CommodityId commodity) {
    return new LandAttributeKey(
        LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + commodity.value());
  }
}
