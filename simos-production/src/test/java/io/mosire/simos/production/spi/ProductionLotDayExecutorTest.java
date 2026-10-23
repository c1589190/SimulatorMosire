package io.mosire.simos.production.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.production.ProductionLotDayExecutor;
import io.mosire.simos.production.ProductionLotDayExecutor.DayRequest;
import io.mosire.simos.production.ProductionLotDayExecutor.Result;
import io.mosire.simos.production.ProductionLotDayExecutor.Status;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionContractFault;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 L2b1：LAND carrier lot 纯日执行内核的独立测试</b>（plan §3.0）。
 *
 * <p>判据（全部走真实 JSON stages 方案 → {@link StagedProductionTestFixtures#parseIndustry(String)}，不手写
 * {@code new ProductionStageDefinition}）：
 *
 * <ul>
 *   <li><b>A/B/C CONTINUE</b>：本 tick 只按当前 stage 的 tickInput × scale 扣料；start 只在「阶段第一天且未付」时叠加；
 *       cycleInputUsed 逐日累加；
 *   <li><b>STAGE_ADVANCED</b>：阶段满 ⇒ 下一段 progress=0、startInputPaid=false，仍在同一周期累加投入；
 *   <li><b>HARVEST</b>：末段满 ⇒ outputs = outputPerUnit × scale × 1000，nextState=IDLE；
 *   <li><b>具名拒绝</b>：投入不足 / 方案错配（先于 stage 解析）/ legacy 空 stages / 未知 stage / progress 越界 / Complete
 *       state / LAND capacity 缺失或 scale≤0；拒绝时 nextState 原样、consumed/outputs 空；
 *   <li><b>算术溢出</b>：fail-closed 抛 {@link ProductionContractFault} {@code
 *       production-lot-day-arithmetic-overflow}；
 *   <li><b>不可变性</b>：DayRequest/Result 构造期拷贝并冻结 map；构造期拒绝 null/负值（可达项）。
 * </ul>
 *
 * <p>★ 生产代码只读：若执行器行为与 plan §3.0 不符，保留失败并在 impl-ledger 记 BLOCKED，不改生产代码。
 */
class ProductionLotDayExecutorTest {

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId FIBER = new CommodityId("fiber");

  private static final InventoryLotId LOT =
      InventoryLotId.forLand(new LandComponentId("arable@0_0:0"));

  // capacityPerUnit LAND=1000 ⇒ A(10000)=scale10、B(3000)=scale3、C(1000)=scale1。
  // stage1(sow): 10d, start grain100/unit, tick grain1/unit/day, NEXT_STAGE。
  // stage2(grow): 90d, tick grain2/unit/day, HARVEST 产 grain1000 + fiber5 /unit。
  private static final String STAGED_JSON =
      """
      {
        "id": "grain_farm@0_0",
        "name": "D-032 L2b1 分阶段谷物农场",
        "regime": "tenant",
        "cycleDays": 100,
        "capacityPerUnit": {"LAND": 1000},
        "laborPerUnit": 10,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "stage1",
            "durationDays": 10,
            "startInputPerUnit": {"grain": 100},
            "tickInputPerUnit": {"grain": 1},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "stage2",
            "durationDays": 90,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 2},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1000, "fiber": 5}
          }
        ]
      }
      """;

  // 只有 SMALL_TOOL capacity —— 对 LAND lot 而言 LAND capacity 缺失。
  private static final String NON_LAND_JSON =
      """
      {
        "id": "toolshop@0_0",
        "name": "无 LAND 容量方案",
        "regime": "tenant",
        "cycleDays": 1,
        "capacityPerUnit": {"SMALL_TOOL": 1},
        "laborPerUnit": 0,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "only",
            "durationDays": 1,
            "startInputPerUnit": {},
            "tickInputPerUnit": {},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1}
          }
        ]
      }
      """;

  // stages 缺键 = legacy 单周期；L2b1 明确不支持。
  private static final String LEGACY_JSON =
      """
      {
        "id": "legacy_farm@0_0",
        "name": "legacy 单周期方案",
        "regime": "tenant",
        "cycleDays": 1,
        "capacityPerUnit": {"LAND": 1000},
        "laborPerUnit": 1,
        "outputPerUnit": {"grain": 1},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0}
      }
      """;

  // 极大 lot 数量 × tickInput ⇒ 故意触发 Math.multiplyExact 溢出（capacity=1 ⇒ scale=Long.MAX）。
  private static final String OVERFLOW_JSON =
      """
      {
        "id": "overflow_farm@0_0",
        "name": "溢出农场",
        "regime": "tenant",
        "cycleDays": 10,
        "capacityPerUnit": {"LAND": 1},
        "laborPerUnit": 0,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "only",
            "durationDays": 10,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 2},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1}
          }
        ]
      }
      """;

  // 末段 endAction=NEXT_STAGE：非法方案，用于证明某些执行器分支结构不可达。
  private static final String LAST_STAGE_NEXT_JSON =
      """
      {
        "id": "broken_farm@0_0",
        "name": "末段 NEXT_STAGE 非法方案",
        "regime": "tenant",
        "cycleDays": 10,
        "capacityPerUnit": {"LAND": 1000},
        "laborPerUnit": 0,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "only",
            "durationDays": 10,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 1},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          }
        ]
      }
      """;

  private static final IndustryDefinitionView PLAN =
      StagedProductionTestFixtures.parseIndustry(STAGED_JSON);
  private static final String PLAN_ID = PLAN.id().value();

  // ── A. CONTINUE ────────────────────────────────────────────────────────────────────────────

  @Test
  void continueFromStageOneChargesOnlyCurrentTickAndAccumulatesCycleInput() {
    LotState state = inProduction(PLAN_ID, "stage1", 8L, 8L, true, Map.of(GRAIN, 123L));

    Result result =
        ProductionLotDayExecutor.executeDay(request(state, 10_000L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.CONTINUE);
    assertThat(result.reason()).isEmpty();
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 10L));
    assertThat(result.outputs()).isEmpty();

    LotState.InProduction next = inProductionOf(result.nextState());
    assertThat(next.planId()).isEqualTo(PLAN_ID);
    assertThat(next.stageId()).isEqualTo("stage1");
    assertThat(next.stageProgressDays()).isEqualTo(9L);
    assertThat(next.cycleProgressDays()).isEqualTo(9L);
    assertThat(next.startInputPaid()).isTrue();
    assertThat(next.cycleInputUsedMilli()).containsExactly(Map.entry(GRAIN, 133L));

    // 成功结果 map 冻结（L2b1 不返回可写视图）。
    assertThatThrownBy(() -> result.consumedInputs().put(GRAIN, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void continueFromStageTwoChargesCurrentTickOnlyNotStartOrFutureStages() {
    LotState state = inProduction(PLAN_ID, "stage2", 27L, 37L, false, Map.of(GRAIN, 456L));

    Result result = ProductionLotDayExecutor.executeDay(request(state, 3_000L, Map.of(GRAIN, 6L)));

    assertThat(result.status()).isEqualTo(Status.CONTINUE);
    assertThat(result.reason()).isEmpty();
    assertThat(result.productionScale()).isEqualTo(3L);
    // stage2 tickInput grain2 × scale3 = 6；不重收 stage1 的 start/tick。
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 6L));
    assertThat(result.outputs()).isEmpty();

    LotState.InProduction next = inProductionOf(result.nextState());
    assertThat(next.stageId()).isEqualTo("stage2");
    assertThat(next.stageProgressDays()).isEqualTo(28L);
    assertThat(next.cycleProgressDays()).isEqualTo(38L);
    assertThat(next.cycleInputUsedMilli()).containsExactly(Map.entry(GRAIN, 462L));
  }

  @Test
  void idleLotPaysStartPlusFirstTickAndStartsStageOne() {
    Result result =
        ProductionLotDayExecutor.executeDay(request(LotState.idle(), 1_000L, Map.of(GRAIN, 101L)));

    assertThat(result.status()).isEqualTo(Status.CONTINUE);
    assertThat(result.reason()).isEmpty();
    assertThat(result.productionScale()).isEqualTo(1L);
    // start grain100 × 1 + tick grain1 × 1 = 101。
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 101L));
    assertThat(result.outputs()).isEmpty();

    LotState.InProduction next = inProductionOf(result.nextState());
    assertThat(next.planId()).isEqualTo(PLAN_ID);
    assertThat(next.stageId()).isEqualTo("stage1");
    assertThat(next.stageProgressDays()).isEqualTo(1L);
    assertThat(next.cycleProgressDays()).isEqualTo(1L);
    assertThat(next.startInputPaid()).isTrue();
    assertThat(next.cycleInputUsedMilli()).containsExactly(Map.entry(GRAIN, 101L));
  }

  // ── B. STAGE_ADVANCED ─────────────────────────────────────────────────────────────────────

  @Test
  void stageEndAdvancesToNextStageWithoutOutputsAndResetsStartInputPaid() {
    LotState state = inProduction(PLAN_ID, "stage1", 9L, 9L, true, Map.of(GRAIN, 123L));

    Result result =
        ProductionLotDayExecutor.executeDay(request(state, 10_000L, Map.of(GRAIN, 10L)));

    assertThat(result.status()).isEqualTo(Status.STAGE_ADVANCED);
    assertThat(result.reason()).isEmpty();
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 10L));
    assertThat(result.outputs()).isEmpty();

    LotState.InProduction next = inProductionOf(result.nextState());
    assertThat(next.planId()).isEqualTo(PLAN_ID);
    assertThat(next.stageId()).isEqualTo("stage2");
    assertThat(next.stageProgressDays()).isZero();
    assertThat(next.cycleProgressDays()).isEqualTo(10L);
    assertThat(next.startInputPaid()).isFalse();
    assertThat(next.cycleInputUsedMilli()).containsExactly(Map.entry(GRAIN, 133L));
  }

  // ── C. HARVEST ────────────────────────────────────────────────────────────────────────────

  @Test
  void finalStageEndHarvestsScaledOutputsAndReturnsIdle() {
    LotState state = inProduction(PLAN_ID, "stage2", 89L, 99L, false, Map.of(GRAIN, 456L));

    Result result = ProductionLotDayExecutor.executeDay(request(state, 3_000L, Map.of(GRAIN, 6L)));

    assertThat(result.status()).isEqualTo(Status.HARVESTED);
    assertThat(result.reason()).isEmpty();
    assertThat(result.productionScale()).isEqualTo(3L);
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 6L));
    // HARVEST 产出 = outputPerUnit × scale × 1000：grain 1000×3×1000、fiber 5×3×1000。
    assertThat(result.outputs())
        .containsExactly(Map.entry(GRAIN, 3_000_000L), Map.entry(FIBER, 15_000L));
    assertThat(result.nextState()).isEqualTo(LotState.idle());

    assertThatThrownBy(() -> result.outputs().put(GRAIN, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── D. 具名拒绝 / 边界 ────────────────────────────────────────────────────────────────────

  @Test
  void inputShortfallRejectsWithoutChangingStateOrInputs() {
    LotState state = inProduction(PLAN_ID, "stage1", 8L, 8L, true, Map.of(GRAIN, 123L));
    Map<CommodityId, Long> available = new LinkedHashMap<>();
    available.put(GRAIN, 9L);
    DayRequest request = request(state, 10_000L, available);

    Result result = ProductionLotDayExecutor.executeDay(request);

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("stage-input-shortfall");
    assertThat(result.reason()).isNotBlank();
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.nextState()).isSameAs(state);
    assertThat(result.nextState()).isEqualTo(state);
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
    // 原可用投入（调用方可变 map 与请求内冻结副本）都未被改写。
    assertThat(available).containsExactly(Map.entry(GRAIN, 9L));
    assertThat(request.availableInputs()).containsExactly(Map.entry(GRAIN, 9L));
  }

  @Test
  void planMismatchIsRejectedBeforeUnknownStageResolution() {
    // stageId 也是未知的；若先解析 stage 就会得到 stage-unknown —— 本用例锁定 plan-mismatch 优先。
    LotState state = inProduction("other_plan@0_0", "stage9", 0L, 0L, true, Map.of());

    Result result =
        ProductionLotDayExecutor.executeDay(request(state, 10_000L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("production-lot-plan-mismatch");
    assertThat(result.reason()).isNotBlank();
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.nextState()).isEqualTo(state);
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void legacyPlanWithEmptyStagesIsRejectedNamed() {
    IndustryDefinitionView legacy = StagedProductionTestFixtures.parseIndustry(LEGACY_JSON);

    Result result =
        ProductionLotDayExecutor.executeDay(
            new DayRequest(LOT, legacy, LotState.idle(), 1_000L, Map.of()));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("land-plan-legacy-unsupported");
    assertThat(result.reason()).isNotBlank();
    assertThat(result.productionScale()).isZero();
    assertThat(result.nextState()).isEqualTo(LotState.idle());
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void unknownStageIsRejectedNamed() {
    LotState state = inProduction(PLAN_ID, "stage9", 0L, 0L, true, Map.of());

    Result result =
        ProductionLotDayExecutor.executeDay(request(state, 10_000L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("production-lot-stage-unknown");
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.nextState()).isEqualTo(state);
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void stageProgressBeyondDurationIsRejectedNamed() {
    // stage1 duration=10；progress=11 越界。
    LotState state = inProduction(PLAN_ID, "stage1", 11L, 11L, true, Map.of());

    Result result =
        ProductionLotDayExecutor.executeDay(request(state, 10_000L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("production-lot-stage-progress-out-of-range");
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.nextState()).isEqualTo(state);
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void completeLotStateIsRejectedNamed() {
    Result result =
        ProductionLotDayExecutor.executeDay(
            request(LotState.complete(), 10_000L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("production-lot-state-invalid");
    assertThat(result.productionScale()).isEqualTo(10L);
    assertThat(result.nextState()).isEqualTo(LotState.complete());
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void missingLandCapacityIsRejectedNamed() {
    IndustryDefinitionView nonLand = StagedProductionTestFixtures.parseIndustry(NON_LAND_JSON);

    Result result =
        ProductionLotDayExecutor.executeDay(
            new DayRequest(LOT, nonLand, LotState.idle(), 1_000L, Map.of()));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("land-production-scale-zero");
    assertThat(result.productionScale()).isZero();
    assertThat(result.nextState()).isEqualTo(LotState.idle());
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  @Test
  void lotQuantityBelowLandCapacityYieldsZeroScaleRejection() {
    // 999 / 1000 = 0 ⇒ scale ≤ 0。
    Result result =
        ProductionLotDayExecutor.executeDay(request(LotState.idle(), 999L, Map.of(GRAIN, 1_000L)));

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.reason()).isEqualTo("land-production-scale-zero");
    assertThat(result.productionScale()).isZero();
    assertThat(result.nextState()).isEqualTo(LotState.idle());
    assertThat(result.consumedInputs()).isEmpty();
    assertThat(result.outputs()).isEmpty();
  }

  /**
   * ★ 结构不可达：执行器的 {@code production-lot-next-stage-missing} 分支只在「当前 stage 是最后一段且 endAction=
   * NEXT_STAGE」时触发，而 {@link IndustryDefinitionView} 构造期强制「最后一段必须是 HARVEST」。 故本测试锁定该不变量由方案构造期
   * 守，执行器分支在任何合法方案上都无法到达（impl-ledger 记「结构不可达」）。
   */
  @Test
  void lastStageNextStageBranchIsStructurallyUnreachableBecausePlanInvariantRejectsIt() {
    assertThatThrownBy(() -> StagedProductionTestFixtures.parseIndustry(LAST_STAGE_NEXT_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最后一项 endAction 必须是 HARVEST");
  }

  @Test
  void arithmeticOverflowThrowsProductionContractFault() {
    IndustryDefinitionView overflowPlan = StagedProductionTestFixtures.parseIndustry(OVERFLOW_JSON);
    DayRequest request =
        new DayRequest(LOT, overflowPlan, LotState.idle(), Long.MAX_VALUE, Map.of());

    assertThatThrownBy(() -> ProductionLotDayExecutor.executeDay(request))
        .isInstanceOfSatisfying(
            ProductionContractFault.class,
            fault ->
                assertThat(fault.reason()).isEqualTo("production-lot-day-arithmetic-overflow"));
  }

  // ── E. 不可变性 / 构造期校验 ──────────────────────────────────────────────────────────────

  @Test
  void dayRequestRejectsNullAndNegativeArgumentsAtConstruction() {
    assertThatThrownBy(() -> new DayRequest(null, PLAN, LotState.idle(), 1_000L, Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DayRequest(LOT, null, LotState.idle(), 1_000L, Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, null, 1_000L, Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lotQuantityMilli");
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), -1L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lotQuantityMilli");
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), 1_000L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("availableInputs");

    Map<CommodityId, Long> negative = new LinkedHashMap<>();
    negative.put(GRAIN, -1L);
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), 1_000L, negative))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");

    Map<CommodityId, Long> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 1L);
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), 1_000L, nullKey))
        .isInstanceOf(IllegalArgumentException.class);

    Map<CommodityId, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(GRAIN, null);
    assertThatThrownBy(() -> new DayRequest(LOT, PLAN, LotState.idle(), 1_000L, nullValue))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultRejectsNullAndNegativeArgumentsAtConstruction() {
    assertThatThrownBy(
            () -> new Result(null, Status.CONTINUE, "", LotState.idle(), Map.of(), Map.of(), 1L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Result(LOT, null, "", LotState.idle(), Map.of(), Map.of(), 1L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new Result(LOT, Status.CONTINUE, null, LotState.idle(), Map.of(), Map.of(), 1L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Result(LOT, Status.CONTINUE, "", null, Map.of(), Map.of(), 1L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new Result(LOT, Status.CONTINUE, "", LotState.idle(), null, Map.of(), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("consumedInputs");
    assertThatThrownBy(
            () -> new Result(LOT, Status.CONTINUE, "", LotState.idle(), Map.of(), null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outputs");

    Map<CommodityId, Long> negativeConsumed = new LinkedHashMap<>();
    negativeConsumed.put(GRAIN, -1L);
    assertThatThrownBy(
            () ->
                new Result(
                    LOT, Status.CONTINUE, "", LotState.idle(), negativeConsumed, Map.of(), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");

    Map<CommodityId, Long> negativeOutputs = new LinkedHashMap<>();
    negativeOutputs.put(FIBER, -2L);
    assertThatThrownBy(
            () ->
                new Result(
                    LOT, Status.HARVESTED, "", LotState.idle(), Map.of(), negativeOutputs, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");
  }

  @Test
  void requestAndResultMapsAreDefensivelyCopiedAndImmutable() {
    Map<CommodityId, Long> input = new LinkedHashMap<>();
    input.put(GRAIN, 500L);
    DayRequest request = request(LotState.idle(), 1_000L, input);
    input.put(GRAIN, 999L);
    assertThat(request.availableInputs()).containsExactly(Map.entry(GRAIN, 500L));
    assertThatThrownBy(() -> request.availableInputs().put(GRAIN, 1L))
        .isInstanceOf(UnsupportedOperationException.class);

    Map<CommodityId, Long> consumed = new LinkedHashMap<>();
    consumed.put(GRAIN, 1L);
    Map<CommodityId, Long> outputs = new LinkedHashMap<>();
    outputs.put(FIBER, 2L);
    Result result = new Result(LOT, Status.CONTINUE, "", LotState.idle(), consumed, outputs, 1L);
    consumed.put(GRAIN, 100L);
    outputs.put(FIBER, 200L);
    assertThat(result.consumedInputs()).containsExactly(Map.entry(GRAIN, 1L));
    assertThat(result.outputs()).containsExactly(Map.entry(FIBER, 2L));
    assertThatThrownBy(() -> result.consumedInputs().put(GRAIN, 9L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.outputs().put(FIBER, 9L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private static DayRequest request(
      LotState state, long lotQuantityMilli, Map<CommodityId, Long> availableInputs) {
    return new DayRequest(LOT, PLAN, state, lotQuantityMilli, availableInputs);
  }

  private static LotState inProduction(
      String planId,
      String stageId,
      long stageProgressDays,
      long cycleProgressDays,
      boolean startInputPaid,
      Map<CommodityId, Long> cycleInputUsedMilli) {
    return new LotState.InProduction(
        planId,
        stageId,
        stageProgressDays,
        cycleProgressDays,
        Map.of(),
        startInputPaid,
        cycleInputUsedMilli);
  }

  private static LotState.InProduction inProductionOf(LotState state) {
    assertThat(state).isInstanceOf(LotState.InProduction.class);
    return (LotState.InProduction) state;
  }
}
