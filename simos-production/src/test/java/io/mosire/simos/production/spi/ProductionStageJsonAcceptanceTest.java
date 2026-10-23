package io.mosire.simos.production.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T3：无 MCP 的 JSON 分阶段验收（真实 {@link ProductionRuntimeEngine}）</b>。
 *
 * <p>验收口径见 {@code
 * docs/superpowers/plans/2026-10-22-d032-unified-lot-inventory-market-implementation.md} §8.1b /
 * {@code docs/superpowers/specs/2026-10-22-d032-unified-lot-inventory-market-design.md} §10.1：
 *
 * <ul>
 *   <li><b>农业 JSON A</b>：两阶段 grain 计划（sow 1 天大宗开始投入 → grow 4 天低耗、HARVEST 产 grain）， 用 {@code
 *       ProductionData} 立实例并跑 <b>180 tick</b>；断言无异常、周期关账 36 次、grossOutput grain 非零；
 *   <li><b>农业 JSON B</b>：180 tick 后换一份 JSON（改 grow 的 tickInput、新增 finish 阶段、改 cycleDays/stages），
 *       {@code ProductionData.withIndustry(newPlan)} + 同一实例造新 engine，再跑 14 tick；断言进入 finish 段、 修改后的
 *       tick 消耗与最终产出在 stageState/receipt 中可见；
 *   <li><b>运力 JSON C</b>：prep 1 天大宗开始投入 → haul 4 天无大耗、HARVEST 产 {@code
 *       transport_capacity_service}；跑 5 tick，断言第 1 tick 大耗、后 4 tick 不重复、关账产出含服务键；
 *   <li><b>legacy guard</b>：{@code plan.stages} 为空时单周期 receipt 行为不变。
 * </ul>
 *
 * <p>★ 所有阶段定义都来自 JSON 文本块（经 {@link ProductionPayloads#parse} + {@link
 * ProductionDefinitionPayloads#industry(com.fasterxml.jackson.databind.JsonNode)}），不直接 new 阶段绕过解析。
 * ★ 本类只读生产代码；若引擎在 staged path 暴露缺陷，保留失败并在 impl-ledger 记 BLOCKED。
 */
class ProductionStageJsonAcceptanceTest {

  // ── 农业 A/B 的规模与劳动（perUnit × grantedScale 即当日需求）────────────────────────────────
  private static final long AGRI_GRANTED_SCALE = 10L;
  private static final long AGRI_LABOR_MILLI_PER_TICK = 10L;
  private static final Map<AssetKind, Long> AGRI_ASSETS = Map.of(AssetKind.LAND, 100L);

  // ── 运力 C：以 SMALL_TOOL 为产能锚（perUnit=1，占用 1 ⇒ 规模 1）──────────────────────────────
  private static final long TRANSPORT_GRANTED_SCALE = 1L;
  private static final Map<AssetKind, Long> TRANSPORT_ASSETS = Map.of(AssetKind.SMALL_TOOL, 1L);

  private static final String LOCATION = "0_0";

  // ── JSON A：农业两阶段（sow 大宗开始投入 → grow 低耗 HARVEST）────────────────────────────────
  private static final String AGRICULTURE_JSON_A =
      """
      {
        "id": "grain_farm@0_0",
        "name": "分阶段谷物农场 A",
        "regime": "tenant",
        "cycleDays": 5,
        "capacityPerUnit": {"LAND": 1},
        "laborPerUnit": 1,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "sow",
            "durationDays": 1,
            "startInputPerUnit": {"grain": 200},
            "tickInputPerUnit": {},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "grow",
            "durationDays": 4,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 10},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 1000}
          }
        ]
      }
      """;

  // ── JSON B：改 grow tickInput 10→20、新增 finish 2 天、grow 改 NEXT_STAGE、cycleDays 5→7 ──────
  private static final String AGRICULTURE_JSON_B =
      """
      {
        "id": "grain_farm@0_0",
        "name": "分阶段谷物农场 B",
        "regime": "tenant",
        "cycleDays": 7,
        "capacityPerUnit": {"LAND": 1},
        "laborPerUnit": 1,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "sow",
            "durationDays": 1,
            "startInputPerUnit": {"grain": 200},
            "tickInputPerUnit": {},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "grow",
            "durationDays": 4,
            "startInputPerUnit": {},
            "tickInputPerUnit": {"grain": 20},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "finish",
            "durationDays": 2,
            "startInputPerUnit": {},
            "tickInputPerUnit": {},
            "endAction": "HARVEST",
            "outputPerUnit": {"grain": 2000}
          }
        ]
      }
      """;

  // ── JSON C：运力两阶段（prep 1 天大宗开始投入 → haul 4 天无大耗，HARVEST 产服务）──────────────
  private static final String TRANSPORT_JSON_C =
      """
      {
        "id": "caravan_transport@0_0",
        "name": "分阶段商队运力 C",
        "regime": "tenant",
        "cycleDays": 5,
        "capacityPerUnit": {"SMALL_TOOL": 1},
        "laborPerUnit": 0,
        "outputPerUnit": {},
        "cycleInputPerUnit": {},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0},
        "stages": [
          {
            "stageId": "prep",
            "durationDays": 1,
            "startInputPerUnit": {"grain": 500},
            "tickInputPerUnit": {},
            "endAction": "NEXT_STAGE",
            "outputPerUnit": {}
          },
          {
            "stageId": "haul",
            "durationDays": 4,
            "startInputPerUnit": {},
            "tickInputPerUnit": {},
            "endAction": "HARVEST",
            "outputPerUnit": {"transport_capacity_service": 1000}
          }
        ]
      }
      """;

  // ── legacy guard：无 stages，沿用单周期配方与产出 ─────────────────────────────────────────────
  private static final String LEGACY_JSON =
      """
      {
        "id": "legacy_farm@0_0",
        "name": "legacy 单周期农场",
        "regime": "tenant",
        "cycleDays": 1,
        "capacityPerUnit": {"LAND": 1},
        "laborPerUnit": 1,
        "outputPerUnit": {"grain": 1},
        "cycleInputPerUnit": {"LAND": {"grain": 1}},
        "slots": [{"id": "poor_peasant", "name": "贫农", "laborParticipationPerMille": 1000}],
        "allocation": {"@class": "split", "meansWeightPerMille": 1000, "laborWeightPerMille": 0}
      }
      """;

  // ── A：农业 JSON A 跑 180 tick ──────────────────────────────────────────────────────────────

  @Test
  void agricultureJsonARuns180TicksWithThirtySixCycleClosesAndNonZeroGrain() {
    IndustryDefinitionView planA = StagedProductionTestFixtures.parseIndustry(AGRICULTURE_JSON_A);
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(StagedProductionTestFixtures.world(planA, LOCATION));

    // 先确认 JSON 真的解析出了两阶段（不是空表走 legacy）。
    assertThat(planA.stages()).extracting(s -> s.stageId()).containsExactly("sow", "grow");
    assertThat(planA.cycleDays()).isEqualTo(5L);

    int cycleCloses = 0;
    long totalGrossGrainMilli = 0L;
    long firstCycleGrossMilli = -1L;
    long firstTickConsumedGrain = -1L;
    long secondTickConsumedGrain = -1L;

    for (int tick = 1; tick <= 180; tick++) {
      ProductionReceipt receipt = executeStagedTick(engine, planA, AGRI_GRANTED_SCALE, AGRI_ASSETS);
      if (tick == 1) {
        assertThat(receipt.cycleClosed()).as("tick 1 = sow，不关账").isFalse();
        firstTickConsumedGrain =
            receipt.consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L);
      } else if (tick == 2) {
        assertThat(receipt.cycleClosed()).as("tick 2 = grow 第一天，不关账").isFalse();
        secondTickConsumedGrain =
            receipt.consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L);
      }
      if (receipt.cycleClosed()) {
        cycleCloses++;
        long gross = receipt.grossOutput().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L);
        assertThat(gross).as("第 %s 个关账周期必须有 grain 毛产", cycleCloses).isPositive();
        totalGrossGrainMilli += gross;
        if (firstCycleGrossMilli < 0L) {
          firstCycleGrossMilli = gross;
        }
      } else {
        assertThat(receipt.grossOutput()).as("未关账 ⇒ grossOutput 为空").isEmpty();
      }
    }

    ProductionProcess after180 = engine.units().get(StagedProductionTestFixtures.UNIT);
    // 5 天/周期 ⇒ 180 / 5 = 36 个整周期，正好回到下一周期起点。
    assertThat(cycleCloses).as("180 tick / 5 天周期 ⇒ 关账 36 次").isEqualTo(36);
    assertThat(firstTickConsumedGrain).as("sow 第一天的大宗开始投入 = 200 × 规模 10").isEqualTo(2_000L);
    assertThat(secondTickConsumedGrain).as("grow 每天的低耗 = 10 × 规模 10").isEqualTo(100L);
    assertThat(firstCycleGrossMilli).as("中性效率 + 规模 10 ⇒ 10 × 1000 × 1000").isEqualTo(10_000_000L);
    assertThat(totalGrossGrainMilli).as("36 个周期累计毛产").isEqualTo(360_000_000L);
    assertThat(after180.progressDays()).as("180 tick 后正好是下一周期起点").isZero();
    assertThat(after180.stageState()).as("关账清空 stageState").isEmpty();

    System.out.println(
        "[T3-A] cycleCloses="
            + cycleCloses
            + " firstTickGrain="
            + firstTickConsumedGrain
            + " secondTickGrain="
            + secondTickConsumedGrain
            + " firstCycleGross="
            + firstCycleGrossMilli
            + " totalGross="
            + totalGrossGrainMilli
            + " after180Progress="
            + after180.progressDays()
            + " after180StageState="
            + after180.stageState());
  }

  // ── B：180 tick 后换 JSON B（改数值 + 新增 finish）并继续跑 14 tick ───────────────────────────

  @Test
  void jsonBChangesGrowTickInputAndAddsFinishStageAfter180TicksOfA() {
    IndustryDefinitionView planA = StagedProductionTestFixtures.parseIndustry(AGRICULTURE_JSON_A);
    ProductionRuntimeEngine engineA =
        new ProductionRuntimeEngine(StagedProductionTestFixtures.world(planA, LOCATION));
    for (int tick = 1; tick <= 180; tick++) {
      executeStagedTick(engineA, planA, AGRI_GRANTED_SCALE, AGRI_ASSETS);
    }
    ProductionData afterA = engineA.state();

    IndustryDefinitionView planB = StagedProductionTestFixtures.parseIndustry(AGRICULTURE_JSON_B);
    assertThat(planB.stages())
        .extracting(s -> s.stageId())
        .containsExactly("sow", "grow", "finish");
    assertThat(planB.cycleDays()).as("1 + 4 + 2").isEqualTo(7L);
    assertThat(planB.stages().get(1).tickInputPerUnit().get(StagedProductionTestFixtures.GRAIN))
        .as("JSON B 把 grow 的 tickInput 从 10 改成 20")
        .isEqualTo(20L);
    assertThat(planB.stages().get(2).outputPerUnit().get(StagedProductionTestFixtures.GRAIN))
        .as("新增 finish 段的产出 = 2000")
        .isEqualTo(2000L);

    // ★ 同一 instances：只换方案定义（模拟"测试侧改 JSON 后替换该产业定义"）。
    ProductionData baseB = afterA.withIndustry(planB);
    ProductionRuntimeEngine engineB = new ProductionRuntimeEngine(baseB);
    assertThat(engineB.units())
        .as("同一实例被带进新 engine")
        .containsKey(StagedProductionTestFixtures.UNIT);

    List<String> stageAfterEachTick = new ArrayList<>();
    List<ProductionReceipt> receipts = new ArrayList<>();
    for (int tick = 1; tick <= 14; tick++) {
      receipts.add(executeStagedTick(engineB, planB, AGRI_GRANTED_SCALE, AGRI_ASSETS));
      stageAfterEachTick.add(StagedProductionTestFixtures.stageIdOf(engineB));
    }

    // 第 1 周期：sow(1) → grow(4) → finish(2)，第 5 tick 后进入 finish，第 7 tick 关账。
    assertThat(stageAfterEachTick.get(4)).as("grow 结束后进入新增的 finish 阶段").isEqualTo("finish");
    assertThat(stageAfterEachTick.get(5)).as("finish 第 1 天仍在 finish").isEqualTo("finish");
    assertThat(stageAfterEachTick).contains("finish");

    // 修改后的 grow tick 消耗可见：20 × 规模 10 = 200（JSON A 是 10 × 10 = 100）。
    assertThat(
            receipts.get(1).consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("JSON B 的 grow tick 消耗 = 20 × 10")
        .isEqualTo(200L);

    ProductionReceipt firstHarvest = receipts.get(6);
    assertThat(firstHarvest.cycleClosed()).as("第 7 tick（1+4+2）关账").isTrue();
    assertThat(firstHarvest.scale()).as("中性效率 + 规模 10").isEqualTo(10L);
    assertThat(firstHarvest.consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("整周期投入 = (sow 200 + grow 20×4) × 10")
        .isEqualTo((200L + 20L * 4L) * AGRI_GRANTED_SCALE);
    assertThat(firstHarvest.grossOutput().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("★ 最终产出按新增 finish 段的 2000：规模 10 × 2000 × 1000")
        .isEqualTo(10L * 2000L * 1000L);

    ProductionReceipt secondHarvest = receipts.get(13);
    assertThat(secondHarvest.cycleClosed()).as("第二个 B 周期也在第 14 tick 关账").isTrue();
    assertThat(secondHarvest.grossOutput().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("第二个周期产出同口径")
        .isEqualTo(10L * 2000L * 1000L);

    System.out.println(
        "[T3-B] stages="
            + stageAfterEachTick
            + " growTickConsumed="
            + receipts.get(1).consumedInputs()
            + " harvestScale="
            + firstHarvest.scale()
            + " harvestConsumed="
            + firstHarvest.consumedInputs()
            + " harvestGross="
            + firstHarvest.grossOutput());
  }

  // ── C：运力 JSON C：1 tick 大宗准备 + 4 tick 无大耗，HARVEST 产服务键 ─────────────────────────

  @Test
  void caravanTransportJsonCSpendsLargePrepOnceThenHarvestsTransportCapacityService() {
    IndustryDefinitionView planC = StagedProductionTestFixtures.parseIndustry(TRANSPORT_JSON_C);
    assertThat(planC.stages()).extracting(s -> s.stageId()).containsExactly("prep", "haul");
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(StagedProductionTestFixtures.world(planC, LOCATION));

    List<ProductionReceipt> receipts = new ArrayList<>();
    List<String> stageAfterEachTick = new ArrayList<>();
    for (int tick = 1; tick <= 5; tick++) {
      receipts.add(
          executeStagedTick(
              engine, planC, TRANSPORT_GRANTED_SCALE, TRANSPORT_ASSETS, /* laborMilli= */ 0L));
      stageAfterEachTick.add(StagedProductionTestFixtures.stageIdOf(engine));
    }

    // 第 1 tick：prep 消耗大宗开始投入 500 × 规模 5 = 2500，随后进入 haul。
    assertThat(receipts.get(0).cycleClosed()).as("prep 只有 1 天，不关账").isFalse();
    assertThat(
            receipts.get(0).consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("prep 第 1 tick 大物资消耗 = 500 × 1")
        .isEqualTo(500L);
    assertThat(stageAfterEachTick.get(0)).as("prep 结束后进入 haul").isEqualTo("haul");

    // 第 2–4 tick：haul 无 start/tick 投入 ⇒ 不重复大消耗。
    for (int index = 1; index <= 3; index++) {
      assertThat(receipts.get(index).consumedInputs())
          .as("haul 第 %s 天不重复大宗消耗", index)
          .doesNotContainKey(StagedProductionTestFixtures.GRAIN);
    }

    // 第 5 tick：haul 4 天走完 ⇒ HARVEST，累计投入仍只有一次的 2500。
    ProductionReceipt harvest = receipts.get(4);
    assertThat(harvest.cycleClosed()).isTrue();
    assertThat(harvest.consumedInputs().getOrDefault(StagedProductionTestFixtures.GRAIN, 0L))
        .as("整周期累计仍是一次 prep 的 500（没有重复大消耗）")
        .isEqualTo(500L);
    assertThat(harvest.scale()).as("SMALL_TOOL 占用 1 ÷ 每单位 1").isEqualTo(1L);
    assertThat(harvest.grossOutput())
        .as("最终 HARVEST 产出含运力服务键")
        .containsKey(StagedProductionTestFixtures.TRANSPORT_CAPACITY_SERVICE);
    assertThat(
            harvest
                .grossOutput()
                .getOrDefault(StagedProductionTestFixtures.TRANSPORT_CAPACITY_SERVICE, 0L))
        .as("服务产出 = 规模 1 × 1000 × 1000")
        .isEqualTo(1L * 1000L * 1000L);

    System.out.println(
        "[T3-C] stages="
            + stageAfterEachTick
            + " tick1Consumed="
            + receipts.get(0).consumedInputs()
            + " ticks2to4Consumed="
            + List.of(
                receipts.get(1).consumedInputs(),
                receipts.get(2).consumedInputs(),
                receipts.get(3).consumedInputs())
            + " harvestConsumed="
            + harvest.consumedInputs()
            + " harvestGross="
            + harvest.grossOutput());
  }

  // ── legacy guard：stages 空 ⇒ 单周期 receipt 行为不变 ───────────────────────────────────────

  @Test
  void legacyPlanWithEmptyStagesKeepsSingleCycleReceiptBehaviour() {
    IndustryDefinitionView legacy = StagedProductionTestFixtures.parseIndustry(LEGACY_JSON);
    assertThat(legacy.stages()).as("缺 stages 键 ⇒ legacy 空表").isEmpty();
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(StagedProductionTestFixtures.world(legacy, LOCATION));

    ProductionReceipt receipt =
        executeLegacyTick(
            engine,
            /* grantedScale= */ 10L,
            Map.of(AssetKind.LAND, 10L),
            Map.of(StagedProductionTestFixtures.GRAIN, 10L),
            /* laborMilli= */ 10L);

    assertThat(receipt.cycleClosed()).as("1 天 legacy 周期 ⇒ 本次关账").isTrue();
    assertThat(receipt.scale()).as("劳动/资产/原料三路都给 10 ⇒ 规模 10").isEqualTo(10L);
    assertThat(receipt.consumedInputs()).containsEntry(StagedProductionTestFixtures.GRAIN, 10L);
    assertThat(receipt.grossOutput())
        .containsEntry(StagedProductionTestFixtures.GRAIN, 10L * 1L * 1000L);
    ProductionProcess after = engine.units().get(StagedProductionTestFixtures.UNIT);
    assertThat(after.progressDays()).isZero();
    assertThat(after.stageState()).as("legacy 路径不写阶段状态").isEmpty();

    System.out.println(
        "[T3-legacy] closed="
            + receipt.cycleClosed()
            + " scale="
            + receipt.scale()
            + " consumed="
            + receipt.consumedInputs()
            + " gross="
            + receipt.grossOutput());
  }

  // ── 执行助手 ───────────────────────────────────────────────────────────────────────────────

  /** 按当前方案/阶段状态供料并执行一个生产日；业务拒绝直接让测试失败（验收要求"无异常"）。 */
  private static ProductionReceipt executeStagedTick(
      ProductionRuntimeEngine engine,
      IndustryDefinitionView plan,
      long grantedScale,
      Map<AssetKind, Long> assetOccupancy) {
    return executeStagedTick(engine, plan, grantedScale, assetOccupancy, AGRI_LABOR_MILLI_PER_TICK);
  }

  private static ProductionReceipt executeStagedTick(
      ProductionRuntimeEngine engine,
      IndustryDefinitionView plan,
      long grantedScale,
      Map<AssetKind, Long> assetOccupancy,
      long laborMilli) {
    ProductionProcess before = engine.units().get(StagedProductionTestFixtures.UNIT);
    Map<CommodityId, Long> inputs =
        StagedProductionTestFixtures.dailyInputs(plan, before, grantedScale);
    ProductionExecutionOutcome outcome =
        engine.execute(
            StagedProductionTestFixtures.executionRequest(
                grantedScale, assetOccupancy, Map.of(), laborMilli, inputs));
    assertThat(outcome)
        .as(
            "staged 执行必须成功（业务拒绝 = 验收失败）: plan=%s stage=%s inputs=%s",
            plan.id().value(), StagedProductionTestFixtures.stageIdOf(engine), inputs)
        .isInstanceOf(ProductionExecutionOutcome.Executed.class);
    return ((ProductionExecutionOutcome.Executed) outcome).receipt();
  }

  private static ProductionReceipt executeLegacyTick(
      ProductionRuntimeEngine engine,
      long grantedScale,
      Map<AssetKind, Long> assetOccupancy,
      Map<CommodityId, Long> finalInputs,
      long laborMilli) {
    ProductionExecutionOutcome outcome =
        engine.execute(
            StagedProductionTestFixtures.executionRequest(
                grantedScale, assetOccupancy, Map.of(), laborMilli, finalInputs));
    assertThat(outcome).isInstanceOf(ProductionExecutionOutcome.Executed.class);
    return ((ProductionExecutionOutcome.Executed) outcome).receipt();
  }
}
