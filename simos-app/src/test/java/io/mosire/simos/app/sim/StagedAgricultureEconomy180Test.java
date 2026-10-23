package io.mosire.simos.app.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.world.Rural32HexWorld;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageEndAction;
import io.mosire.simos.production.contract.ProductionStageState;
import io.mosire.simos.production.spi.ProductionPayloads;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★★ <b>D-032 T4：真实 economy/social 32hex 世界上的 staged 农业 180 tick 验收（无 MCP）</b>。
 *
 * <p>验收口径：implementation plan §8.1c / design spec §10.1。世界来自 {@link
 * Rural32HexWorld#state(String)}；推进走真 {@link Shell#advanceAndDrain(AdvanceTime)}，不是生产引擎 直调、不是
 * logger 假绿。测试只新增测试文件，不改生产代码。
 *
 * <p>测试侧只做三件事：
 *
 * <ol>
 *   <li>在<b>创世状态</b>里把一个确有实例的 grain_farm plan 换成 staged 定义；阶段数值全部来自 JSON 文本块， 同
 *       id/name/regime/capacity/labor/roles/mode/durable 均沿用旧定义；
 *   <li>给该 plan 的 operator 家户补测试粮食（避免第一阶段大投入因创世库存不足而变成“没验到”）；
 *   <li>180 tick 后用 JSON B 生成新 plan，走 {@code ProductionChangeSet.between} + {@code
 *       CoreSimos.submitRestore} 落一条 revision，再跑 20 tick。
 * </ol>
 *
 * <p>所有关键读数取自 {@link ProductionData#instances()}、{@link ActorData#accounts()} 与 {@link
 * SimulationState#meta()}；{@code D032_TICK_CHAIN}（由 {@code ProductionReceipt} 派生）只作补充证据， 不单独作为唯一判据。
 */
class StagedAgricultureEconomy180Test {

  private static final BranchId MAIN = new BranchId("main");
  private static final String MAP_ID = Rural32HexWorld.MAP_ID;
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final long STAGE_A_START_GRAIN_PER_UNIT = 12_000L;
  private static final long STAGE_A_GROW_TICK_GRAIN_PER_UNIT = 1L;
  private static final long STAGE_A_FINAL_GRAIN_PER_UNIT = 48L;
  private static final long STAGE_B_FINISH_TICK_GRAIN_PER_UNIT = 2L;
  private static final long STAGE_B_FINAL_GRAIN_PER_UNIT = 2_000L;
  private static final long TEST_GRAIN_TOP_UP_MILLI = 100_000_000L;
  private static final int PRE_RELOAD_TICKS = 180;
  private static final int POST_RELOAD_TICKS = 20;

  @TempDir Path tempDir;

  @Test
  void stagedAgricultureRuns180TicksThenJsonReloadLandsAsOneRestoreRevisionAndNewStagesRun()
      throws IOException {
    Path evidenceDir = Path.of("target", "t4-staged-agriculture");
    Files.createDirectories(evidenceDir);
    long startedNanos = System.nanoTime();

    ShellConfig config =
        new ShellConfig(
            tempDir.resolve("store"),
            1, // 每 tick checkpoint：逐 tick replay 读 stageState 时不能退化成重放上百条巨型变更集。
            0,
            0,
            ShellConfig.DEFAULT_MCP_PATH,
            ShellConfig.DEFAULT_MCP_INITIATOR,
            MAP_ID,
            ShellConfig.DEFAULT_BIND_ADDRESS,
            ShellConfig.DEFAULT_OPENING_SNAPSHOT,
            ShellConfig.DEFAULT_WORLD_ID);

    List<TickLedgerRow> ledgerRows = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    Set<String> stageIdsSeen = new LinkedHashSet<>();
    List<Long> receiptGrainTicks = new ArrayList<>();
    List<Long> targetGrainIncreaseTicks = new ArrayList<>();
    long targetGrainInjectedAt = -1L;

    try (Shell shell = Shell.start(config);
        LogCapture settlementLog = new LogCapture("io.mosire.simos.economy.settlement")) {
      SimulationState genesis = Rural32HexWorld.state(MAP_ID);
      TargetSelection selection = selectGrainFarmWithInstance(genesis);
      IndustryDefinitionView originalPlan = selection.plan();
      String jsonA = agricultureJsonA(originalPlan.id().value());
      IndustryDefinitionView stagedPlanA = planWithJsonStages(originalPlan, jsonA);
      assertThat(stagedPlanA.stages())
          .extracting(ProductionStageDefinition::stageId)
          .containsExactly("sow", "grow");
      assertThat(stagedPlanA.cycleDays()).isEqualTo(originalPlan.cycleDays());
      assertThat(totalStageDays(stagedPlanA)).isEqualTo(stagedPlanA.cycleDays());
      assertThat(stagedPlanA.cycleInputPerUnit()).isEmpty();
      assertThat(stagedPlanA.dailyInputPerUnit()).isEmpty();
      assertThat(stagedPlanA.outputPerUnit()).isEmpty();

      long grainBeforeInjection = targetGrainMilli(genesis, selection.household());
      genesis = withProduction(genesis, productionOf(genesis).withIndustry(stagedPlanA));
      genesis = topUpTargetGrain(genesis, selection.household(), TEST_GRAIN_TOP_UP_MILLI);
      targetGrainInjectedAt = targetGrainMilli(genesis, selection.household());
      assertThat(targetGrainInjectedAt - grainBeforeInjection).isEqualTo(TEST_GRAIN_TOP_UP_MILLI);

      shell.coreSimos().bootstrapGenesis(genesis);
      SimulationState state = replay(shell);
      assertThat(state.meta().ref().revision().value()).as("创世落盘后 head").isEqualTo(1L);

      long headBefore = head(shell);
      long previousTargetGrain = targetGrainMilli(state, selection.household());
      for (int day = 1; day <= PRE_RELOAD_TICKS; day++) {
        settlementLog.clear();
        long headAtStart = head(shell);
        long tickAtStart = tickOf(state);
        String failure = "";
        try {
          shell.advanceAndDrain(
              new AdvanceTime(
                  "cmd-t4-agri-" + day,
                  "corr-t4-agri-" + day,
                  "t4-agri",
                  MAIN,
                  new RevisionId(headAtStart),
                  new TimeRange(
                      SimosTimestamp.of(tickAtStart),
                      Optional.of(SimosTimestamp.of(tickAtStart + 1L)))));
        } catch (RuntimeException e) {
          failure = safeText(e);
        }
        long headAfter = head(shell);
        state = replay(shell);
        long tickAfter = tickOf(state);
        boolean committed = headAfter > headAtStart && tickAfter == tickAtStart + 1L;
        if (!committed) {
          failures.add(
              "tick="
                  + day
                  + " head="
                  + headAtStart
                  + "->"
                  + headAfter
                  + " tick="
                  + tickAtStart
                  + "->"
                  + tickAfter
                  + " failure="
                  + failure);
        }

        ProductionProcess target = targetProcess(state, selection.unitId());
        String stageId = target.stageState().map(ProductionStageState::stageId).orElse("<none>");
        long stageProgress =
            target.stageState().map(ProductionStageState::stageProgressDays).orElse(-1L);
        long targetGrain = targetGrainMilli(state, selection.household());
        long totalGrain = totalGrainMilli(state);
        Map<String, String> tickChain =
            lastEventFields(settlementLog.messages(), "D032_TICK_CHAIN");
        long receiptGrain = parseLongOrZero(tickChain.get("productionGrainMilli"));
        if (receiptGrain > 0L) {
          receiptGrainTicks.add((long) day);
        }
        if (targetGrain > previousTargetGrain) {
          targetGrainIncreaseTicks.add((long) day);
        }
        if (!"<none>".equals(stageId)) {
          stageIdsSeen.add(stageId);
        }
        ledgerRows.add(
            new TickLedgerRow(
                day,
                committed ? "COMMITTED" : "REJECTED",
                headAtStart,
                headAfter,
                tickAfter,
                stageId,
                stageProgress,
                targetGrain,
                totalGrain,
                receiptGrain,
                parseLongOrZero(tickChain.get("seedInputMilli")),
                failure));
        previousTargetGrain = targetGrain;
        if (day == 1
            || day == 2
            || day == 3
            || day == 120
            || day == 121
            || day == 122
            || day == 180) {
          System.out.println(
              "[T4-A] day="
                  + day
                  + " head="
                  + headAtStart
                  + "->"
                  + headAfter
                  + " tick="
                  + tickAfter
                  + " stage="
                  + stageId
                  + "@"
                  + stageProgress
                  + " targetGrain="
                  + targetGrain
                  + " totalGrain="
                  + totalGrain
                  + " receiptGrain="
                  + receiptGrain);
        }
      }

      long beforeReloadHead = head(shell);
      SimulationState at180 = replay(shell);
      ProductionData productionAt180 = productionOf(at180);
      IndustryDefinitionView planAt180 = productionAt180.industries().get(originalPlan.id());
      assertThat(planAt180).isNotNull();
      ProductionProcess targetAt180 = targetProcess(at180, selection.unitId());

      long targetGrainAt180 = targetGrainMilli(at180, selection.household());

      // ── JSON B：改 grow.tickInput 1→2，新增 finish 1 日 HARVEST；cycleDays 与 stages 总时长仍相等 ──
      String jsonB = agricultureJsonB(originalPlan.id().value());
      IndustryDefinitionView stagedPlanB = planWithJsonStages(planAt180, jsonB);
      assertThat(stagedPlanB.id()).isEqualTo(planAt180.id());
      assertThat(stagedPlanB.stages())
          .extracting(ProductionStageDefinition::stageId)
          .containsExactly("grow", "finish");
      assertThat(stagedPlanB.stages().get(1).tickInputPerUnit().get(GRAIN))
          .isEqualTo(STAGE_B_FINISH_TICK_GRAIN_PER_UNIT);
      assertThat(stagedPlanB.effectiveOutputPerUnit().get(GRAIN))
          .isEqualTo(STAGE_B_FINAL_GRAIN_PER_UNIT);
      assertThat(totalStageDays(stagedPlanB)).isEqualTo(stagedPlanB.cycleDays());
      assertThat(stagedPlanB.cycleInputPerUnit()).isEmpty();
      assertThat(stagedPlanB.dailyInputPerUnit()).isEmpty();
      assertThat(stagedPlanB.outputPerUnit()).isEmpty();

      ProductionData productionTarget = productionAt180.withIndustry(stagedPlanB);
      ProductionChangeSet productionChangeSet =
          ProductionChangeSet.between(productionAt180, productionTarget);
      assertThat(productionChangeSet.industries().changed()).isTrue();
      assertThat(productionChangeSet.isEmpty()).isFalse();

      CommandResult restoreResult =
          shell
              .coreSimos()
              .submitRestore(
                  MAIN,
                  new RevisionId(beforeReloadHead),
                  "t4-json-reload",
                  new WorldChangeSet(Map.of("production", productionChangeSet)));
      assertThat(restoreResult)
          .as("JSON B 必须通过 submitRestore 恰落一条 revision")
          .isInstanceOf(CommandResult.Committed.class);
      long afterReloadHead = head(shell);
      assertThat(afterReloadHead)
          .as("submitRestore 恰好 +1 revision")
          .isEqualTo(beforeReloadHead + 1L);

      SimulationState reloaded = replay(shell);
      IndustryDefinitionView reloadedPlan =
          productionOf(reloaded).industries().get(originalPlan.id());
      assertThat(reloadedPlan).isNotNull();
      assertThat(reloadedPlan.stages())
          .extracting(ProductionStageDefinition::stageId)
          .containsExactly("grow", "finish");
      assertThat(reloadedPlan.stages().get(1).tickInputPerUnit().get(GRAIN))
          .isEqualTo(STAGE_B_FINISH_TICK_GRAIN_PER_UNIT);
      assertThat(reloadedPlan.effectiveOutputPerUnit().get(GRAIN))
          .isEqualTo(STAGE_B_FINAL_GRAIN_PER_UNIT);

      long targetGrainBeforePost = targetGrainMilli(reloaded, selection.household());
      long previousCycleInput =
          targetProcess(reloaded, selection.unitId()).cycleInputUsedMilli().getOrDefault(GRAIN, 0L);
      Set<String> postReloadStageIds = new LinkedHashSet<>();
      List<Long> postReloadGrainIncreaseTicks = new ArrayList<>();
      long targetGrainPrevious = targetGrainBeforePost;
      long newTickInputDelta = -1L;
      long newTickInputDeltaDay = -1L;
      for (int day = PRE_RELOAD_TICKS + 1; day <= PRE_RELOAD_TICKS + POST_RELOAD_TICKS; day++) {
        settlementLog.clear();
        long headAtStart = head(shell);
        long tickAtStart = tickOf(reloaded);
        String failure = "";
        try {
          shell.advanceAndDrain(
              new AdvanceTime(
                  "cmd-t4-agri-" + day,
                  "corr-t4-agri-" + day,
                  "t4-agri",
                  MAIN,
                  new RevisionId(headAtStart),
                  new TimeRange(
                      SimosTimestamp.of(tickAtStart),
                      Optional.of(SimosTimestamp.of(tickAtStart + 1L)))));
        } catch (RuntimeException e) {
          failure = safeText(e);
        }
        long headAfter = head(shell);
        reloaded = replay(shell);
        long tickAfter = tickOf(reloaded);
        boolean committed = headAfter > headAtStart && tickAfter == tickAtStart + 1L;
        if (!committed) {
          failures.add(
              "post-reload tick="
                  + day
                  + " head="
                  + headAtStart
                  + "->"
                  + headAfter
                  + " tick="
                  + tickAtStart
                  + "->"
                  + tickAfter
                  + " failure="
                  + failure);
        }
        ProductionProcess target = targetProcess(reloaded, selection.unitId());
        String stageId = target.stageState().map(ProductionStageState::stageId).orElse("<none>");
        long stageProgress =
            target.stageState().map(ProductionStageState::stageProgressDays).orElse(-1L);
        long targetGrain = targetGrainMilli(reloaded, selection.household());
        long totalGrain = totalGrainMilli(reloaded);
        Map<String, String> tickChain =
            lastEventFields(settlementLog.messages(), "D032_TICK_CHAIN");
        long receiptGrain = parseLongOrZero(tickChain.get("productionGrainMilli"));
        if (!"<none>".equals(stageId)) {
          postReloadStageIds.add(stageId);
        }
        if (targetGrain > targetGrainPrevious) {
          postReloadGrainIncreaseTicks.add((long) day);
        }
        long cycleInputAfter = target.cycleInputUsedMilli().getOrDefault(GRAIN, 0L);
        if (cycleInputAfter > previousCycleInput && newTickInputDelta < 0L) {
          newTickInputDelta = cycleInputAfter - previousCycleInput;
          newTickInputDeltaDay = day;
        }
        previousCycleInput = cycleInputAfter;
        ledgerRows.add(
            new TickLedgerRow(
                day,
                committed ? "COMMITTED" : "REJECTED",
                headAtStart,
                headAfter,
                tickAfter,
                stageId,
                stageProgress,
                targetGrain,
                totalGrain,
                receiptGrain,
                parseLongOrZero(tickChain.get("seedInputMilli")),
                failure));
        targetGrainPrevious = targetGrain;
        if (day <= PRE_RELOAD_TICKS + 5) {
          System.out.println(
              "[T4-B] day="
                  + day
                  + " head="
                  + headAtStart
                  + "->"
                  + headAfter
                  + " stage="
                  + stageId
                  + "@"
                  + stageProgress
                  + " targetGrain="
                  + targetGrain
                  + " receiptGrain="
                  + receiptGrain);
        }
      }

      writeCsv(evidenceDir.resolve("180tick-ledger.csv"), ledgerRows);
      long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
      String summary =
          String.join(
              "\n",
              "D-032 T4 staged agriculture evidence",
              "mapId=" + MAP_ID,
              "targetPlan=" + originalPlan.id().value(),
              "targetUnit=" + selection.unitId().value(),
              "targetHousehold=" + selection.household().value(),
              "testGrainTopUpMilli=" + TEST_GRAIN_TOP_UP_MILLI,
              "grainBeforeInjectionMilli=" + grainBeforeInjection,
              "grainAfterInjectionMilli=" + targetGrainInjectedAt,
              "preReloadTicks=" + PRE_RELOAD_TICKS,
              "preReloadCommitted="
                  + ledgerRows.stream()
                      .filter(row -> row.day() <= PRE_RELOAD_TICKS)
                      .filter(TickLedgerRow::committed)
                      .count(),
              "receiptGrainTickCount=" + receiptGrainTicks.size(),
              "receiptGrainTicks=" + receiptGrainTicks,
              "targetGrainIncreaseTicksIn180=" + targetGrainIncreaseTicks,
              "stageIdsSeen=" + stageIdsSeen,
              "targetGrainAt180Milli=" + targetGrainAt180,
              "targetProgressAt180=" + targetAt180.progressDays(),
              "targetCycleInputAt180=" + targetAt180.cycleInputUsedMilli(),
              "targetRelationAt180=" + economyOf(at180).relations().get(selection.unitId()),
              "targetHouseholdPopulationAt180="
                  + economyOf(at180).classes().getOrDefault(selection.household(), null),
              "jsonBStageIds="
                  + stagedPlanB.stages().stream().map(ProductionStageDefinition::stageId).toList(),
              "jsonBNewFinishTickInputPerUnit=" + STAGE_B_FINISH_TICK_GRAIN_PER_UNIT,
              "jsonBFinalGrainPerUnit=" + STAGE_B_FINAL_GRAIN_PER_UNIT,
              "reloadHead=" + beforeReloadHead + "->" + afterReloadHead,
              "reloadResult=" + restoreResult,
              "postReloadStageIds=" + postReloadStageIds,
              "newTickInputDeltaMilli=" + newTickInputDelta,
              "newTickInputDeltaDay=" + newTickInputDeltaDay,
              "postReloadGrainIncreaseTicks=" + postReloadGrainIncreaseTicks,
              "firstFailure=" + (failures.isEmpty() ? "<none>" : failures.get(0)),
              "failureCount=" + failures.size(),
              "elapsedMillis=" + elapsedMillis);
      Files.writeString(evidenceDir.resolve("summary.txt"), summary + "\n", StandardCharsets.UTF_8);
      System.out.println("[T4-SUMMARY]\n" + summary);

      // ── 最终判据（先落证据再断言；生产集成缺口用首条 failure 具名报告，不吞 180 行）──
      assertThat(failures)
          .withFailMessage(
              "T4 BLOCKED：%s 个 tick/提交失败；first=%s；evidence=%s/180tick-ledger.csv",
              failures.size(),
              failures.isEmpty() ? "<none>" : failures.get(0),
              evidenceDir.toAbsolutePath())
          .isEmpty();
      assertThat(ledgerRows).allMatch(TickLedgerRow::committed);
      assertThat(tickOf(at180)).isEqualTo(PRE_RELOAD_TICKS);
      assertThat(stageIdsSeen)
          .as("目标 unit 从 ProductionProcess.stageState 实际进入过的阶段")
          .contains("sow", "grow");
      // tick 180 已跨过 A 的第一个完整周期（目标 unit 在第二周期是否入选由真实经济决定）；
      // stage 进入判据用前 180 tick 的 stageIdsSeen，不以“180 tick 时恰在周期中”为硬条件。
      assertThat(receiptGrainTicks)
          .as("180 tick 内至少一个 tick 的 ProductionReceipt 毛产 grain > 0")
          .isNotEmpty();
      assertThat(targetGrainIncreaseTicks).as("目标家户库存至少有一个 tick 增加（不是只看日志）").isNotEmpty();
      assertThat(postReloadStageIds)
          .as("JSON B 提交后目标 unit 实际进入新 finish 阶段（20 tick 内）")
          .contains("finish");
      assertThat(newTickInputDelta)
          .as("JSON B 后新 finish 阶段 cycleInputUsedMilli 增量 > 0（新 tickInput 实际被 S4 扣到）")
          .isPositive();
      assertThat(postReloadGrainIncreaseTicks)
          .as("JSON B 的 finish HARVEST 至少让目标家户 grain 库存增加一次")
          .isNotEmpty();
    }
  }

  @Test
  void stagedCaravanTransportPrepThenHaulProducesCapacityBatchWithoutInventoryLeak()
      throws IOException {
    Path evidenceDir = Path.of("target", "t4-staged-transport");
    Files.createDirectories(evidenceDir);

    ShellConfig config =
        new ShellConfig(
            tempDir.resolve("transport-store"),
            1,
            0,
            0,
            ShellConfig.DEFAULT_MCP_PATH,
            ShellConfig.DEFAULT_MCP_INITIATOR,
            MAP_ID,
            ShellConfig.DEFAULT_BIND_ADDRESS,
            ShellConfig.DEFAULT_OPENING_SNAPSHOT,
            ShellConfig.DEFAULT_WORLD_ID);

    List<String> failures = new ArrayList<>();
    List<String> tickRows = new ArrayList<>();
    Set<String> stageIdsSeen = new LinkedHashSet<>();
    List<Map<String, String>> batchEvents = new ArrayList<>();
    List<String> targetProductionRejections = new ArrayList<>();
    long targetGrainInjected;
    long prepConsumedGrainMilli = -1L;

    try (Shell shell = Shell.start(config);
        LogCapture settlementLog = new LogCapture("io.mosire.simos.economy.settlement", true)) {
      SimulationState genesis = Rural32HexWorld.state(MAP_ID);
      TargetSelection selection = selectCaravanWithInstance(genesis);
      IndustryDefinitionView originalPlan = selection.plan();
      IndustryDefinitionView stagedPlan =
          planWithJsonStages(originalPlan, caravanJson(originalPlan.id().value()));
      assertThat(stagedPlan.stages())
          .extracting(ProductionStageDefinition::stageId)
          .containsExactly("prep", "haul");
      assertThat(stagedPlan.cycleDays()).isEqualTo(5L);
      assertThat(totalStageDays(stagedPlan)).isEqualTo(stagedPlan.cycleDays());
      assertThat(stagedPlan.cycleInputPerUnit()).isEmpty();
      assertThat(stagedPlan.dailyInputPerUnit()).isEmpty();
      assertThat(stagedPlan.outputPerUnit()).isEmpty();
      assertThat(stagedPlan.durableCommodityCapacityPerUnit())
          .isEqualTo(originalPlan.durableCommodityCapacityPerUnit());

      genesis = withProduction(genesis, productionOf(genesis).withIndustry(stagedPlan));
      // T4 isolated workaround: mark the existing caravan unit as a continuing unit so
      // actualProducingUnitsOf() includes it despite merchant M6 candidate not selecting it.
      // stageState stays empty (engine initializes prep on next execute); cycleInputUsed stays
      // empty.
      genesis = markCaravanAsContinuing(genesis, selection.unitId());
      ProductionProcess seededTarget = targetProcess(genesis, selection.unitId());
      assertThat(seededTarget.progressDays()).isEqualTo(1L);
      assertThat(seededTarget.stageState()).isEmpty();
      assertThat(seededTarget.cycleInputUsedMilli()).isEmpty();
      long grainBefore = targetGrainMilli(genesis, selection.household());
      genesis = topUpTargetGrain(genesis, selection.household(), 100_000_000L);
      targetGrainInjected = targetGrainMilli(genesis, selection.household()) - grainBefore;
      assertThat(targetGrainInjected).isEqualTo(100_000_000L);

      shell.coreSimos().bootstrapGenesis(genesis);
      SimulationState state = replay(shell);
      for (int day = 1; day <= 5; day++) {
        settlementLog.clear();
        long headAtStart = head(shell);
        long tickAtStart = tickOf(state);
        String failure = "";
        try {
          shell.advanceAndDrain(
              new AdvanceTime(
                  "cmd-t4-transport-" + day,
                  "corr-t4-transport-" + day,
                  "t4-transport",
                  MAIN,
                  new RevisionId(headAtStart),
                  new TimeRange(
                      SimosTimestamp.of(tickAtStart),
                      Optional.of(SimosTimestamp.of(tickAtStart + 1L)))));
        } catch (RuntimeException e) {
          failure = safeText(e);
        }
        long headAfter = head(shell);
        state = replay(shell);
        long tickAfter = tickOf(state);
        boolean committed = headAfter > headAtStart && tickAfter == tickAtStart + 1L;
        if (!committed) {
          failures.add(
              "tick="
                  + day
                  + " head="
                  + headAtStart
                  + "->"
                  + headAfter
                  + " tick="
                  + tickAtStart
                  + "->"
                  + tickAfter
                  + " failure="
                  + failure);
        }
        ProductionProcess target = targetProcess(state, selection.unitId());
        String stageId = target.stageState().map(ProductionStageState::stageId).orElse("<none>");
        long stageProgress =
            target.stageState().map(ProductionStageState::stageProgressDays).orElse(-1L);
        if (!"<none>".equals(stageId)) {
          stageIdsSeen.add(stageId);
        }
        if (day == 1) {
          prepConsumedGrainMilli = target.cycleInputUsedMilli().getOrDefault(GRAIN, 0L);
        }
        List<Map<String, String>> tickBatchEvents =
            allEventFields(settlementLog.messages(), "TRANSPORT_CAPACITY_BATCH_ACCUMULATED");
        batchEvents.addAll(tickBatchEvents);
        for (Map<String, String> rejection :
            allEventFields(settlementLog.messages(), "PRODUCTION_DAY_REJECTED")) {
          if (selection.unitId().value().equals(rejection.get("unit"))) {
            targetProductionRejections.add(
                "day="
                    + day
                    + " reason="
                    + rejection.getOrDefault("reason", "?")
                    + " unit="
                    + rejection.get("unit"));
          }
        }
        String row =
            "day="
                + day
                + " status="
                + (committed ? "COMMITTED" : "REJECTED")
                + " head="
                + headAtStart
                + "->"
                + headAfter
                + " tick="
                + tickAfter
                + " stage="
                + stageId
                + "@"
                + stageProgress
                + " targetGrain="
                + targetGrainMilli(state, selection.household())
                + " batches="
                + tickBatchEvents.size()
                + " productionRejections="
                + targetProductionRejections.size()
                + " failure="
                + failure;
        tickRows.add(row);
        System.out.println("[T4-TRANSPORT] " + row);
      }

      Map<String, String> targetBatchEvent =
          batchEvents.stream()
              .filter(fields -> selection.household().value().equals(fields.get("household")))
              .filter(fields -> parseLongOrZero(fields.get("capacityWorkMilli")) > 0L)
              .findFirst()
              .orElse(null);
      long batchCapacity =
          targetBatchEvent == null
              ? 0L
              : parseLongOrZero(targetBatchEvent.get("capacityWorkMilli"));
      boolean serviceKeyInCommodityVocabulary =
          io.mosire.simos.util.economy.EconomyVocabulary.allCommodityIds()
              .contains(CaravanTransportTemplate.SERVICE_OUTPUT_ID);
      boolean serviceKeyInInventory =
          actorOf(state).accounts().values().stream()
              .anyMatch(inv -> inv.balances().containsKey(CaravanTransportTemplate.SERVICE_OUTPUT));
      boolean serviceKeyInMarketPrices =
          economyOf(state).marketTables().values().stream()
              .anyMatch(
                  table -> table.prices().containsKey(CaravanTransportTemplate.SERVICE_OUTPUT));
      long batchesInFinalState = economyOf(state).transportCapacityBatches().size();
      String summary =
          String.join(
              "\n",
              "D-032 T4 staged caravan transport evidence",
              "mapId=" + MAP_ID,
              "targetPlan=" + originalPlan.id().value(),
              "targetUnit=" + selection.unitId().value(),
              "targetHousehold=" + selection.household().value(),
              "testGrainTopUpMilli=" + targetGrainInjected,
              "stagesSeen=" + stageIdsSeen,
              "batchEventCount=" + batchEvents.size(),
              "targetBatchCapacityWorkMilli=" + batchCapacity,
              "prepConsumedGrainMilliAtDay1=" + prepConsumedGrainMilli,
              "targetBatchMarketRoundId="
                  + (targetBatchEvent == null ? "<none>" : targetBatchEvent.get("marketRoundId")),
              "targetBatchMarketZone="
                  + (targetBatchEvent == null ? "<none>" : targetBatchEvent.get("marketZone")),
              "transportCapacityBatchesInFinalState=" + batchesInFinalState,
              "serviceKeyInCommodityVocabulary=" + serviceKeyInCommodityVocabulary,
              "serviceKeyInInventory=" + serviceKeyInInventory,
              "serviceKeyInMarketPrices=" + serviceKeyInMarketPrices,
              "targetProductionRejectionCount=" + targetProductionRejections.size(),
              "firstTargetProductionRejection="
                  + (targetProductionRejections.isEmpty()
                      ? "<none>"
                      : targetProductionRejections.get(0)),
              "failureCount=" + failures.size());
      Files.writeString(evidenceDir.resolve("summary.txt"), summary + "\n", StandardCharsets.UTF_8);
      Files.writeString(
          evidenceDir.resolve("tick-ledger.txt"),
          String.join("\n", tickRows) + "\n",
          StandardCharsets.UTF_8);
      System.out.println("[T4-TRANSPORT-SUMMARY]\n" + summary);

      assertThat(failures)
          .withFailMessage("T4 transport 5 tick 提交失败: %s; evidence=%s", failures, evidenceDir)
          .isEmpty();
      assertThat(tickOf(state)).isEqualTo(5L);
      assertThat(stageIdsSeen)
          .as(
              "目标 staged caravan 实际进入的阶段；prep 只有 1 日且在同一 tick 内完成，"
                  + "post-tick 状态进入 haul；productionRejections=%s; evidence=%s",
              targetProductionRejections, evidenceDir)
          .contains("haul");
      assertThat(prepConsumedGrainMilli)
          .as("prep 的 startInput grain=500 已被实际扣到目标 unit 的 cycleInputUsedMilli")
          .isPositive();
      assertThat(targetBatchEvent)
          .as(
              "目标家户必须有 TRANSPORT_CAPACITY_BATCH_ACCUMULATED 且 capacityWorkMilli > 0；"
                  + "productionRejections=%s; evidence=%s",
              targetProductionRejections, evidenceDir)
          .isNotNull();
      assertThat(batchCapacity).isPositive();
      assertThat(serviceKeyInCommodityVocabulary).isFalse();
      assertThat(serviceKeyInInventory).isFalse();
      assertThat(serviceKeyInMarketPrices).isFalse();
    }
  }

  // ── JSON A/B：阶段数值只从文本块 JSON 读 ────────────────────────────────────────────────

  private static String agricultureJsonA(String planId) {
    return """
        {
          "id": "%s",
          "cycleDays": 120,
          "stages": [
            {
              "stageId": "sow",
              "durationDays": 2,
              "startInputPerUnit": {"grain": 12000},
              "tickInputPerUnit": {"grain": 1},
              "endAction": "NEXT_STAGE",
              "outputPerUnit": {}
            },
            {
              "stageId": "grow",
              "durationDays": 118,
              "startInputPerUnit": {},
              "tickInputPerUnit": {"grain": 1},
              "endAction": "HARVEST",
              "outputPerUnit": {"grain": 48, "fiber": 6}
            }
          ]
        }
        """
        .formatted(planId);
  }

  private static String agricultureJsonB(String planId) {
    // tick 180 时 A 的第一个完整周期已结清；真实经济下目标 unit 第二周期未必仍被选中。
    // B 从“下一周期起点”重新开始：grow 1 日（无投入，负责让实例进入下一段）+ 新增 finish 2 日 HALVEST 段。
    // finish 的 tickInput 是新的真实扣料靶子（2 毫粮/规模·日）；cycleDays=3 与 stages 总时长严格相等。
    // 预期 tick 181 进入 finish、tick 182 读到新 tickInput 实际扣料、tick 183 真实 HARVEST 产出 2000 grain。
    return """
        {
          "id": "%s",
          "cycleDays": 3,
          "stages": [
            {
              "stageId": "grow",
              "durationDays": 1,
              "startInputPerUnit": {},
              "tickInputPerUnit": {},
              "endAction": "NEXT_STAGE",
              "outputPerUnit": {}
            },
            {
              "stageId": "finish",
              "durationDays": 2,
              "startInputPerUnit": {},
              "tickInputPerUnit": {"grain": 2},
              "endAction": "HARVEST",
              "outputPerUnit": {"grain": 2000}
            }
          ]
        }
        """
        .formatted(planId);
  }

  private static String caravanJson(String planId) {
    return """
        {
          "id": "%s",
          "cycleDays": 5,
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
        """
        .formatted(planId);
  }

  // ── 状态选择、JSON 阶段解析、快照/账户读写 ──────────────────────────────────────────────

  private record TargetSelection(
      IndustryId planId,
      ProductionUnitId unitId,
      HouseholdId household,
      IndustryDefinitionView plan) {}

  private static TargetSelection selectGrainFarmWithInstance(SimulationState state) {
    ProductionData production = productionOf(state);
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry : production.industries().entrySet()) {
      IndustryDefinitionView plan = entry.getValue();
      if (!entry.getKey().value().startsWith("farm@")) {
        continue;
      }
      for (ProductionProcess process : production.instances().values()) {
        if (!process.plan().equals(entry.getKey())) {
          continue;
        }
        if (process.operator().kind() != ActorKind.HOUSEHOLD) {
          throw new IllegalStateException(
              "T4 目标 grain_farm 的 operator 不是 HOUSEHOLD: " + process.operator());
        }
        return new TargetSelection(
            entry.getKey(), process.id(), new HouseholdId(process.operator().id()), plan);
      }
    }
    throw new IllegalStateException("32hex 创世里找不到同时有 plan + instance 的 grain_farm");
  }

  private static TargetSelection selectCaravanWithInstance(SimulationState state) {
    ProductionData production = productionOf(state);
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry : production.industries().entrySet()) {
      if (!entry.getKey().value().startsWith(CaravanTransportTemplate.KIND + "@")) {
        continue;
      }
      IndustryDefinitionView plan = entry.getValue();
      for (ProductionProcess process : production.instances().values()) {
        if (!process.plan().equals(entry.getKey())) {
          continue;
        }
        if (process.operator().kind() != ActorKind.HOUSEHOLD) {
          throw new IllegalStateException(
              "T4 目标 caravan_transport 的 operator 不是 HOUSEHOLD: " + process.operator());
        }
        return new TargetSelection(
            entry.getKey(), process.id(), new HouseholdId(process.operator().id()), plan);
      }
    }
    throw new IllegalStateException("32hex 创世里找不到同时有 plan + instance 的 caravan_transport");
  }

  private static IndustryDefinitionView planWithJsonStages(
      IndustryDefinitionView base, String json) {
    JsonNode root = ProductionPayloads.parse(json);
    if (!base.id().value().equals(root.path("id").asText())) {
      throw new IllegalArgumentException(
          "JSON plan id 与目标不符: " + root.path("id").asText() + " != " + base.id().value());
    }
    long jsonCycleDays = requiredLong(root, "cycleDays");
    if (jsonCycleDays < 1L) {
      throw new IllegalArgumentException("JSON cycleDays 必须 ≥ 1: " + jsonCycleDays);
    }
    List<ProductionStageDefinition> stages = parseStages(root);
    return new IndustryDefinitionView(
        base.id(),
        base.name(),
        base.regime(),
        base.skill(),
        jsonCycleDays,
        base.buildDays(),
        base.capacityPerUnit(),
        Map.of(), // legacy dailyInputPerUnit 清空
        base.dailyLaborPerUnit(),
        base.laborPerUnit(),
        Map.of(), // legacy outputPerUnit 清空
        Map.of(), // legacy cycleInputPerUnit 清空
        base.slots(),
        base.allocation(),
        base.moneyOutputPerUnit(),
        base.experience(),
        base.availability(),
        base.acceptedRightKinds(),
        base.roles(),
        base.roleShareRemainderRule(),
        base.upgradesTo(),
        base.mode(),
        base.durableCommodityCapacityPerUnit(),
        stages);
  }

  private static List<ProductionStageDefinition> parseStages(JsonNode planJson) {
    JsonNode stagesNode = planJson.get("stages");
    if (stagesNode == null || !stagesNode.isArray() || stagesNode.isEmpty()) {
      throw new IllegalArgumentException("JSON 必须给出非空 stages[]: " + planJson);
    }
    List<ProductionStageDefinition> stages = new ArrayList<>();
    for (JsonNode stageNode : stagesNode) {
      stages.add(
          new ProductionStageDefinition(
              requiredText(stageNode, "stageId"),
              requiredLong(stageNode, "durationDays"),
              commodityMap(stageNode.get("startInputPerUnit")),
              commodityMap(stageNode.get("tickInputPerUnit")),
              ProductionStageEndAction.parse(requiredText(stageNode, "endAction")),
              commodityMap(stageNode.get("outputPerUnit"))));
    }
    return stages;
  }

  private static Map<CommodityId, Long> commodityMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("阶段投入/产出表必须是对象: " + node);
    }
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!field.getValue().isIntegralNumber()) {
        throw new IllegalArgumentException("商品数量必须是整数: " + field);
      }
      values.put(new CommodityId(field.getKey()), field.getValue().asLong());
    }
    return values;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("阶段缺非空字符串字段 " + field + ": " + node);
    }
    return value.asText();
  }

  private static long requiredLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber()) {
      throw new IllegalArgumentException("阶段缺整数字段 " + field + ": " + node);
    }
    return value.asLong();
  }

  private static long totalStageDays(IndustryDefinitionView plan) {
    long total = 0L;
    for (ProductionStageDefinition stage : plan.stages()) {
      total = Math.addExact(total, stage.durationDays());
    }
    return total;
  }

  private static SimulationState withProduction(SimulationState state, ProductionData production) {
    return withModule(
        state,
        "production",
        new ProductionSnapshot(state.meta().ref(), state.meta().timestamp(), production));
  }

  private static SimulationState markCaravanAsContinuing(
      SimulationState state, ProductionUnitId unitId) {
    ProductionData production = productionOf(state);
    ProductionProcess process = production.instances().get(unitId);
    if (process == null) {
      throw new IllegalStateException("目标 caravan 实例不存在: " + unitId);
    }
    ProductionProcess continuing = process.withCycleState(1L, Map.of());
    return withProduction(state, production.withInstance(continuing));
  }

  private static SimulationState topUpTargetGrain(
      SimulationState state, HouseholdId household, long amount) {
    ActorData actor = actorOf(state);
    HouseholdAccountKey key = new HouseholdAccountKey(household);
    HouseholdInventory inventory = actor.accounts().get(key);
    if (inventory == null) {
      throw new IllegalStateException("目标家户没有库存账户: " + household);
    }
    Map<CommodityId, Long> balances = new LinkedHashMap<>(inventory.balances());
    balances.merge(GRAIN, amount, Math::addExact);
    HouseholdInventory updated =
        new HouseholdInventory(
            key, balances, inventory.money(), inventory.frozenBalances(), inventory.frozenMoney());
    return withModule(
        state,
        "actor",
        new ActorSnapshot(
            state.meta().ref(), state.meta().timestamp(), actor.withInventory(updated)));
  }

  private static SimulationState withModule(
      SimulationState state, String namespace, Snapshot snapshot) {
    Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
    modules.put(namespace, snapshot);
    return new SimulationState(state.meta(), modules, state.info());
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  private static ActorData actorOf(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static io.mosire.simos.economy.EconomyData economyOf(SimulationState state) {
    return ((io.mosire.simos.economy.EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static ProductionProcess targetProcess(SimulationState state, ProductionUnitId unitId) {
    ProductionProcess process = productionOf(state).instances().get(unitId);
    if (process == null) {
      throw new IllegalStateException("目标生产实例消失: " + unitId);
    }
    return process;
  }

  private static long targetGrainMilli(SimulationState state, HouseholdId household) {
    HouseholdInventory inventory =
        actorOf(state).accounts().get(new HouseholdAccountKey(household));
    if (inventory == null) {
      throw new IllegalStateException("目标家户库存账户消失: " + household);
    }
    return inventory.balances().getOrDefault(GRAIN, 0L);
  }

  private static long totalGrainMilli(SimulationState state) {
    long total = 0L;
    for (HouseholdInventory inventory : actorOf(state).accounts().values()) {
      total = Math.addExact(total, inventory.balances().getOrDefault(GRAIN, 0L));
    }
    return total;
  }

  private static SimulationState replay(Shell shell) {
    long revision = head(shell);
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(revision)));
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  private static long tickOf(SimulationState state) {
    return state.meta().timestamp().tick();
  }

  // ── 日志只作 receipt 的补充证据；断言主体仍是 state ─────────────────────────────────────

  private static Map<String, String> lastEventFields(List<String> messages, String event) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      String line = messages.get(i);
      if (line.contains("event=" + event + " ")) {
        return parseFields(line);
      }
    }
    return Map.of();
  }

  private static List<Map<String, String>> allEventFields(List<String> messages, String event) {
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : messages) {
      if (line.contains("event=" + event + " ")) {
        rows.add(parseFields(line));
      }
    }
    return rows;
  }

  private static Map<String, String> parseFields(String line) {
    Map<String, String> fields = new LinkedHashMap<>();
    int start = line.indexOf("event=");
    if (start < 0) {
      return fields;
    }
    for (String token : line.substring(start).split(" ")) {
      int separator = token.indexOf('=');
      if (separator > 0 && separator < token.length() - 1) {
        fields.put(token.substring(0, separator), token.substring(separator + 1));
      }
    }
    return fields;
  }

  private static long parseLongOrZero(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private static String safeText(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName()
        + (message == null || message.isBlank()
            ? ""
            : ":" + message.replace('\n', ' ').replace('\r', ' ').replace(';', ','));
  }

  private record TickLedgerRow(
      int day,
      String status,
      long headBefore,
      long headAfter,
      long tickAfter,
      String stageId,
      long stageProgressDays,
      long targetGrainMilli,
      long totalGrainMilli,
      long receiptGrainMilli,
      long receiptSeedInputMilli,
      String failure) {
    boolean committed() {
      return "COMMITTED".equals(status);
    }
  }

  private static void writeCsv(Path file, List<TickLedgerRow> rows) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append(
        "day,status,headBefore,headAfter,tickAfter,stageId,stageProgressDays,"
            + "targetGrainMilli,totalGrainMilli,receiptGrainMilli,receiptSeedInputMilli,failure\n");
    for (TickLedgerRow row : rows) {
      text.append(row.day())
          .append(',')
          .append(row.status())
          .append(',')
          .append(row.headBefore())
          .append(',')
          .append(row.headAfter())
          .append(',')
          .append(row.tickAfter())
          .append(',')
          .append(row.stageId())
          .append(',')
          .append(row.stageProgressDays())
          .append(',')
          .append(row.targetGrainMilli())
          .append(',')
          .append(row.totalGrainMilli())
          .append(',')
          .append(row.receiptGrainMilli())
          .append(',')
          .append(row.receiptSeedInputMilli())
          .append(',')
          .append(row.failure().replace(',', ';').replace('\n', ' ').replace('\r', ' '))
          .append('\n');
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** 只收日志、不改 logger 级别；与 32hex 360d sim 同法。 */
  private static final class LogCapture implements AutoCloseable {
    private final LoggerContext context;
    private final AbstractConfiguration configuration;
    private final String loggerName;
    private final String appenderName;
    private final Collecting appender;
    private final Level previousLevel;
    private final boolean levelRaised;

    private LogCapture(String loggerName) {
      this(loggerName, false);
    }

    private LogCapture(String loggerName, boolean debug) {
      this.loggerName = loggerName;
      this.context = (LoggerContext) LogManager.getContext(false);
      this.configuration = (AbstractConfiguration) context.getConfiguration();
      this.previousLevel = configuration.getLoggerConfig(loggerName).getLevel();
      this.levelRaised = debug && !Level.DEBUG.equals(previousLevel);
      if (levelRaised) {
        configuration.getLoggerConfig(loggerName).setLevel(Level.DEBUG);
      }
      this.appenderName = "t4-capture-" + Integer.toHexString(System.identityHashCode(this));
      this.appender = new Collecting(appenderName);
      this.appender.start();
      configuration.addAppender(appender);
      configuration.getLoggerConfig(loggerName).addAppender(appender, null, null);
      context.updateLoggers();
    }

    private List<String> messages() {
      return appender.messages();
    }

    private void clear() {
      appender.clear();
    }

    @Override
    public void close() {
      configuration.getLoggerConfig(loggerName).removeAppender(appenderName);
      configuration.removeAppender(appenderName);
      if (levelRaised) {
        configuration.getLoggerConfig(loggerName).setLevel(previousLevel);
      }
      context.updateLoggers();
      appender.stop();
    }
  }

  private static final class Collecting extends AbstractAppender {
    private final List<String> messages = new ArrayList<>();

    private Collecting(String name) {
      super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      synchronized (messages) {
        messages.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
      }
    }

    private List<String> messages() {
      synchronized (messages) {
        return List.copyOf(messages);
      }
    }

    private void clear() {
      synchronized (messages) {
        messages.clear();
      }
    }
  }
}
