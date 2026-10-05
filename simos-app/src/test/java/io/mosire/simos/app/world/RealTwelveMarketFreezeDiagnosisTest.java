package io.mosire.simos.app.world;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.app.time.EconomyDayFeed;
import io.mosire.simos.app.time.MarketReadoutAssembly;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.MarketReadout;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>诊断（非验收）：真实 12hex 生产运行时 3650 tick 的成交量塌缩到 0</b>。
 *
 * <p>本类只打印证据、不做验收断言。三条互不相同的路径：
 *
 * <ol>
 *   <li>{@link #segmentedBoundaries()}：与生产测试<b>逐字同一条</b>分段推进（每 120 天一段），在关账边界读取当天的 {@link
 *       MarketReport}、逐买方/逐卖方槽位结果、账户分布与同一条 {@code planOrders} 的纯订单读数；
 *   <li>{@link #dailyFillCurve()}：把 0→240 改成<b>逐日推进</b>，看成交曲线在哪一天塌到 0（用来回答"是不是边界采样假象"， 并与分段路径的同
 *       tick 读数对照）；
 *   <li>{@link #counterfactualMoneyAt1200()}：把 tick=1200 的真实状态重放成新世界的 genesis（测试侧，不动 main），
 *       对照组原样续跑到 1205；变异组只给"有缺口且可花货币为 0"的家户补上创世口径的银（每人 12 毫银 + 缺口货款）， 看 1205 的成交是否恢复 ——
 *       用来判"钱是不是那个唯一卡死的约束"。
 * </ol>
 *
 * <p>所有读数都来自 {@code core.replay} 的真实状态 + 进程内真实报告；没有手搭 EconomyData、没有直接调 {@code MarketSettlement}
 * 包内方法（{@code planOrders} 经 {@link MarketReadoutAssembly} 公开读口调用，见其内部注释）。
 */
class RealTwelveMarketFreezeDiagnosisTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:market-freeze-diag";
  private static final String MAP_ID = RealTwelveHexWorld.MAP_ID;
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final long SEGMENT_DAYS = 120L;
  private static final long TICKS = 3650L;

  private static final long[] DETAIL_TICKS = {0L, 120L, 600L, 1200L, 2400L, 3650L};

  @TempDir Path tempDir;

  // ── 路径 1：分段推进（与生产测试同路径）────────────────────────────────────────────────

  @Test
  void segmentedBoundaries() throws Exception {
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-seg-store")))) {
      long head = seed(core, tempDir);
      SimulationState atZero = core.replay(ref(head));
      System.out.println("[MF][PATH1] 分段推进（每 120 天一段），与生产测试同路径");
      printBoundary(0L, atZero, Optional.empty(), true);

      long tick = 0L;
      long segment = 0L;
      while (tick < TICKS) {
        long from = tick;
        long to = Math.min(tick + SEGMENT_DAYS, TICKS);
        segment++;
        head = advance(core, head, from, to, "mf-seg-" + segment);
        tick = to;
        SimulationState state = core.replay(ref(head));
        Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, tick);
        System.out.println(
            "[MF][ADVANCE] segment=" + segment + " " + from + "->" + to + " head=" + head);
        printBoundary(tick, state, report, isDetailTick(tick));
      }
    } finally {
      restoreChangeSetMapper(original);
    }
  }

  // ── 路径 2：逐日推进 0→240，取逐日成交曲线 ────────────────────────────────────────────

  @Test
  void dailyFillCurve() throws Exception {
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-daily-store")))) {
      long head = seed(core, tempDir);
      System.out.println("[MF][PATH2] 逐日推进 0->240（对照边界采样）");
      Map<Long, Long> fillsByMarketDay = new TreeMap<>();
      for (long day = 1L; day <= 240L; day++) {
        head = advance(core, head, day - 1L, day, "mf-daily-" + day);
        Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, day);
        boolean today = report.isPresent() && report.get().day() == day;
        if (today) {
          fillsByMarketDay.put(day, (long) report.get().fills().size());
        }
        if (day <= 12L || day % 5L == 0L || !today) {
          System.out.println(
              "[MF][DAILY] day="
                  + day
                  + " reportPresent="
                  + report.isPresent()
                  + " reportDay="
                  + report.map(MarketReport::day).orElse(-1L)
                  + " today="
                  + today
                  + " trigger="
                  + report.map(value -> value.trigger().name()).orElse("-")
                  + " fills="
                  + report.map(value -> value.fills().size()).orElse(-1)
                  + " unfilled="
                  + report.map(value -> value.unfilled().size()).orElse(-1));
        }
        if (day == 120L || day == 240L) {
          printBoundary(day, core.replay(ref(head)), report, true);
        }
      }
      System.out.println("[MF][DAILY-SUMMARY] marketDayFills=" + fillsByMarketDay);
    } finally {
      restoreChangeSetMapper(original);
    }
  }

  // ── 路径 2b：长段内逐日成交捕获（真实分段路径，不改推进方式）────────────────────────────

  /**
   * ★★ 关键对照：生产路径是"一次 AdvanceTime 跑 120 天"，报告投递点只保留最后一天的报告 ⇒ 边界采样只能看到关账日。 本测试在 {@code
   * core.submit(AdvanceTime(from→to))} 于主线程运行期间，用一个只读线程轮询公开读口 {@link MarketReportFeed#last(String,
   * long)}，把段内每一天的报告按 tick 收下来 —— <b>推进方式一个字不改</b>， 因此这是分段路径的真实段内序列。
   */
  @Test
  void intraSegmentCapture() throws Exception {
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-capture-store")))) {
      long head = seed(core, tempDir);
      Capture first = captureSegment(core, head, 0L, 120L, "mf-cap-1");
      head = first.head();
      // 120 -> 1200 用生产路径的分段推进（不捕获）。
      long tick = 120L;
      long segment = 1L;
      while (tick < 1200L) {
        long from = tick;
        long to = Math.min(tick + SEGMENT_DAYS, 1200L);
        segment++;
        head = advance(core, head, from, to, "mf-cap-mid-" + segment);
        tick = to;
      }
      Capture late = captureSegment(core, head, 1200L, 1320L, "mf-cap-2");
      head = late.head();
      // 1320 -> 3600 分段推进。
      tick = 1320L;
      while (tick < 3600L) {
        long from = tick;
        long to = Math.min(tick + SEGMENT_DAYS, 3600L);
        segment++;
        head = advance(core, head, from, to, "mf-cap-mid2-" + segment);
        tick = to;
      }
      Capture last = captureSegment(core, head, 3600L, 3650L, "mf-cap-3");
      head = last.head();
      printBoundary(3650L, core.replay(ref(head)), MarketReportFeed.last(MAP_ID, 3650L), true);

      System.out.println("[MF][CAPTURE-SUMMARY] first=" + first.fillsByDay());
      System.out.println("[MF][CAPTURE-SUMMARY] late=" + late.fillsByDay());
      System.out.println("[MF][CAPTURE-SUMMARY] last=" + last.fillsByDay());
    } finally {
      restoreChangeSetMapper(original);
    }
  }

  // ── 路径 3：tick=1200 的对照/补钱变异 ────────────────────────────────────────────────

  @Test
  void counterfactualMoneyAt1200() throws Exception {
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-base-store")))) {
      long head = seed(core, tempDir);
      long tick = 0L;
      long segment = 0L;
      while (tick < 1200L) {
        long from = tick;
        long to = Math.min(tick + SEGMENT_DAYS, 1200L);
        segment++;
        head = advance(core, head, from, to, "mf-cf-base-" + segment);
        tick = to;
      }
      SimulationState frozen = core.replay(ref(head));
      Optional<MarketReport> base = MarketReportFeed.last(MAP_ID, 1200L);
      System.out.println("[MF][CF][BASE] tick=1200 head=" + head);
      printBoundary(1200L, frozen, base, true);

      ActorData frozenActor = CompactThreeNationsWorld.actorOf(frozen);
      EconomyData frozenEconomy = CompactThreeNationsWorld.economyOf(frozen);
      Map<HouseholdId, Long> zeroSilver = beneficiariesOf(base, frozenEconomy, frozenActor);
      Map<HouseholdId, Long> allGap = allGapBeneficiariesOf(base, frozenEconomy);
      long injectTotal = zeroSilver.values().stream().mapToLong(Long::longValue).sum();
      long allGapTotal = allGap.values().stream().mapToLong(Long::longValue).sum();
      System.out.println(
          "[MF][CF][TARGETS] zeroSilverGapHouseholds="
              + zeroSilver.size()
              + " zeroSilverGapMilli="
              + injectTotal
              + " allGapHouseholds="
              + allGap.size()
              + " allGapMilli="
              + allGapTotal);

      // 对照组：状态原样重启到 1205（排除"重启本身"这个变量）。
      try (CoreSimos control =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-control-store")))) {
        control.bootstrapGenesis(restamp(frozen, 1200L, null));
        long controlHead = advance(control, 1L, 1200L, 1205L, "mf-cf-control");
        Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, 1205L);
        System.out.println("[MF][CF][CONTROL] tick=1200->1205 head=" + controlHead + " injected=0");
        printBoundary(1205L, control.replay(ref(controlHead)), report, true);
      }

      // 变异组 A：只给"有粮缺口且可花银 == 0"的家户补回创世口径的银（测试侧状态注入，不进 main）。
      ActorData injectedZero = injectSilver(frozenActor, frozenEconomy, zeroSilver);
      try (CoreSimos variant =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-variant-a-store")))) {
        variant.bootstrapGenesis(restamp(frozen, 1200L, injectedZero));
        long variantHead = advance(variant, 1L, 1200L, 1205L, "mf-cf-variant-a");
        Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, 1205L);
        System.out.println(
            "[MF][CF][VARIANT-A] tick=1200->1205 head="
                + variantHead
                + " injectedHouseholds="
                + zeroSilver.size()
                + " injectedSilverMilli="
                + injectTotal);
        printBoundary(1205L, variant.replay(ref(variantHead)), report, true);
      }

      // 变异组 B：给**全部**有粮缺口的家户补钱（含那批"有钱但只有 1~2 毫"的户），并跑完整的一个 120 天段，
      //   与同长度的对照段比较"下一个关账日"是否还冻结。
      ActorData injectedAll = injectSilver(frozenActor, frozenEconomy, allGap);
      try (CoreSimos controlLong =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-control-long-store")))) {
        controlLong.bootstrapGenesis(restamp(frozen, 1200L, null));
        Capture controlCapture =
            captureSegment(controlLong, 1L, 1200L, 1320L, "mf-cf-control-long");
        System.out.println(
            "[MF][CF][CONTROL-LONG] tick=1200->1320 head="
                + controlCapture.head()
                + " injected=0 fillsByDay="
                + controlCapture.fillsByDay());
        printBoundary(
            1320L,
            controlLong.replay(ref(controlCapture.head())),
            MarketReportFeed.last(MAP_ID, 1320L),
            true);
      }
      try (CoreSimos variantLong =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-variant-b-store")))) {
        variantLong.bootstrapGenesis(restamp(frozen, 1200L, injectedAll));
        Capture variantCapture = captureSegment(variantLong, 1L, 1200L, 1320L, "mf-cf-variant-b");
        System.out.println(
            "[MF][CF][VARIANT-B] tick=1200->1320 head="
                + variantCapture.head()
                + " injectedHouseholds="
                + allGap.size()
                + " injectedSilverMilli="
                + allGapTotal
                + " fillsByDay="
                + variantCapture.fillsByDay());
        printBoundary(
            1320L,
            variantLong.replay(ref(variantCapture.head())),
            MarketReportFeed.last(MAP_ID, 1320L),
            true);
      }
      // 变异组 D（阈值探针）：每个缺口家户只补 10 毫银（远小于创世口径 ≈214），看"钱包不再被 2 毫边距清零"
      //   是否足以让市场重新出现成交 —— 用来区分"完全没钱"与"钱小到撮合当 0"。
      Map<HouseholdId, Long> minimal = new LinkedHashMap<>();
      for (HouseholdId household : allGap.keySet()) {
        minimal.put(household, 10L);
      }
      ActorData injectedMinimal = injectSilver(frozenActor, frozenEconomy, minimal);
      try (CoreSimos variantMin =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-variant-d-store")))) {
        variantMin.bootstrapGenesis(restamp(frozen, 1200L, injectedMinimal));
        Capture minimalCapture = captureSegment(variantMin, 1L, 1200L, 1250L, "mf-cf-variant-d");
        System.out.println(
            "[MF][CF][VARIANT-D] tick=1200->1250 head="
                + minimalCapture.head()
                + " injectedHouseholds="
                + minimal.size()
                + " injectedSilverMilli="
                + (10L * minimal.size())
                + " fillsByDay="
                + minimalCapture.fillsByDay());
      }

      // 变异组 E（守恒对照）：不增发货币，只把**同一个** 3,432 毫银从银最多的账户转给 16 个缺口户
      //   （世界银总量仍 = 46,800）—— 用来区分"钱不够"与"钱在别人手里"。
      long[] redistributionStats = new long[2];
      ActorData redistributed =
          redistributeSilver(frozenActor, frozenEconomy, allGap, redistributionStats);
      try (CoreSimos variantRedistribute =
          shellAlikeCore(Files.createDirectories(tempDir.resolve("mf-cf-variant-e-store")))) {
        variantRedistribute.bootstrapGenesis(restamp(frozen, 1200L, redistributed));
        Capture redistributeCapture =
            captureSegment(variantRedistribute, 1L, 1200L, 1320L, "mf-cf-variant-e");
        System.out.println(
            "[MF][CF][VARIANT-E] tick=1200->1320 head="
                + redistributeCapture.head()
                + " movedMilli="
                + redistributionStats[1]
                + " donorAccounts="
                + redistributionStats[0]
                + " fillsByDay="
                + redistributeCapture.fillsByDay());
        printBoundary(
            1320L,
            variantRedistribute.replay(ref(redistributeCapture.head())),
            MarketReportFeed.last(MAP_ID, 1320L),
            true);
      }
    } finally {
      restoreChangeSetMapper(original);
    }
  }

  private static boolean isDetailTick(long tick) {
    for (long value : DETAIL_TICKS) {
      if (value == tick) {
        return true;
      }
    }
    return false;
  }

  private static void printBoundary(
      long tick, SimulationState state, Optional<MarketReport> report, boolean detail) {
    EconomyData economy = CompactThreeNationsWorld.economyOf(state);
    ActorData actor = CompactThreeNationsWorld.actorOf(state);
    long population = 0L;
    for (ClassRow row : economy.classes().values()) {
      population += row.population();
    }
    long unmetNeed = 0L;
    for (FlowRow flow : economy.flows().values()) {
      for (long value : flow.unmetNeed().values()) {
        unmetNeed += value;
      }
    }
    System.out.println(
        "[MF][BOUNDARY] tick="
            + tick
            + " population="
            + population
            + " households="
            + economy.classes().size()
            + " markets="
            + economy.markets().size()
            + " unmetNeedCycleMilli="
            + unmetNeed
            + " detail="
            + detail);
    printReport(tick, report);
    if (detail) {
      printOutcomeAggregates(tick, report);
      printGrainBuyerDetails(tick, report);
      printGapHouseholdOrders(tick, report, economy);
      printPlanOrders(tick, state, economy);
      printAccounts(tick, economy, actor, report);
    } else {
      printShortAggregates(tick, report);
    }
  }

  /**
   * ★ 逐户打印"有粮缺口"的买方槽位：{@code spendableMoneyMilli} 与撮合里的 {@code MARKET_MONEY_ROUNDING_MARGIN_MILLI =
   * 2} 同刻度 —— 这一栏直接回答"有钱但钱小到撮合当 0"。
   */
  private static void printGrainBuyerDetails(long tick, Optional<MarketReport> maybe) {
    if (maybe.isEmpty()) {
      return;
    }
    MarketReport report = maybe.get();
    int gapOutcomes = 0;
    long spendableLe2 = 0L;
    long spendable3To10 = 0L;
    long spendableGt10 = 0L;
    for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
      if (!GRAIN.equals(outcome.commodity()) || outcome.gapQty() <= 0L) {
        continue;
      }
      gapOutcomes++;
      long spendable = outcome.spendableMoneyMilli();
      if (spendable <= 2L) {
        spendableLe2++;
      } else if (spendable <= 10L) {
        spendable3To10++;
      } else {
        spendableGt10++;
      }
      System.out.println(
          "[MF][GRAIN-BUYER] tick="
              + tick
              + " household="
              + outcome.household().map(HouseholdId::value).orElse("-")
              + " hex="
              + outcome.hex()
              + " gapQty="
              + outcome.gapQty()
              + " desired="
              + outcome.desiredQty()
              + " stock="
              + outcome.stockOnHandMilli()
              + " spendableMoneyMilli="
              + spendable
              + " affordableQty="
              + outcome.affordableQty()
              + " orderedQty="
              + outcome.orderedQty()
              + " filledQty="
              + outcome.filledQty()
              + " reason="
              + outcome.unfilledReason().map(Enum::name).orElse("-"));
    }
    System.out.println(
        "[MF][GRAIN-BUYER-BUCKETS] tick="
            + tick
            + " gapOutcomes="
            + gapOutcomes
            + " spendableLe2="
            + spendableLe2
            + " spendable3To10="
            + spendable3To10
            + " spendableGt10="
            + spendableGt10);
  }

  private static void printReport(long tick, Optional<MarketReport> maybe) {
    if (maybe.isEmpty()) {
      System.out.println("[MF][REPORT] tick=" + tick + " present=false");
      return;
    }
    MarketReport report = maybe.get();
    System.out.println(
        "[MF][REPORT] tick="
            + tick
            + " present=true reportDay="
            + report.day()
            + " trigger="
            + report.trigger()
            + " dayIsToday="
            + (report.day() == tick)
            + " fills="
            + report.fills().size()
            + " immediateFills="
            + report.immediateFills()
            + " crossRegionFills="
            + report.crossRegionFills()
            + " immediateCrossHexFills="
            + report.immediateCrossHexFills()
            + " carrierPresent="
            + report.carrierPresent()
            + " priceMode="
            + report.priceMode()
            + " unfilled="
            + report.unfilled().size()
            + " unfilledReasonCounts="
            + report.unfilledReasonCounts());
    Map<MarketUnfilledReason, long[]> buy = new LinkedHashMap<>();
    Map<MarketUnfilledReason, long[]> sell = new LinkedHashMap<>();
    for (MarketReport.Unfilled item : report.unfilled()) {
      Map<MarketUnfilledReason, long[]> target = item.buyerSide() ? buy : sell;
      long[] slot = target.computeIfAbsent(item.reason(), ignored -> new long[2]);
      slot[0]++;
      slot[1] += item.quantity();
    }
    System.out.println(
        "[MF][UNFILLED] tick="
            + tick
            + " buySide[count,qty]="
            + renderReasonQty(buy)
            + " sellSide[count,qty]="
            + renderReasonQty(sell));
  }

  private static void printShortAggregates(long tick, Optional<MarketReport> maybe) {
    if (maybe.isEmpty()) {
      System.out.println("[MF][SHORT] tick=" + tick + " reportAbsent");
      return;
    }
    MarketReport report = maybe.get();
    long buyerOutcomes = 0L;
    long buyerGap = 0L;
    long buyerOrderedQty = 0L;
    for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
      buyerOutcomes++;
      if (outcome.gapQty() > 0L) {
        buyerGap++;
      }
      buyerOrderedQty += outcome.orderedQty();
    }
    long sellerOffered = 0L;
    long sellerFilled = 0L;
    long sellerZero = 0L;
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      if (outcome.offeredQty() > 0L) {
        sellerOffered++;
        if (outcome.filledQty() == 0L) {
          sellerZero++;
        }
      }
      sellerFilled += outcome.filledQty();
    }
    System.out.println(
        "[MF][SHORT] tick="
            + tick
            + " fills="
            + report.fills().size()
            + " buyerOutcomes="
            + buyerOutcomes
            + " buyerGapSlots="
            + buyerGap
            + " buyerOrderedQty="
            + buyerOrderedQty
            + " sellerOfferedSlots="
            + sellerOffered
            + " sellerOfferedZeroFilled="
            + sellerZero
            + " sellerFilledQty="
            + sellerFilled);
  }

  // ── 逐买方 / 逐卖方槽位聚合 ─────────────────────────────────────────────────────────

  private static void printOutcomeAggregates(long tick, Optional<MarketReport> maybe) {
    if (maybe.isEmpty()) {
      System.out.println("[MF][OUTCOMES] tick=" + tick + " reportAbsent");
      return;
    }
    MarketReport report = maybe.get();
    printBuyerAggregate(tick, "ALL", report, null);
    printBuyerAggregate(tick, "GRAIN", report, GRAIN);
    printSellerAggregate(tick, "ALL", report, null);
    printSellerAggregate(tick, "GRAIN", report, GRAIN);
  }

  private static void printBuyerAggregate(
      long tick, String scope, MarketReport report, CommodityId filter) {
    long outcomes = 0L;
    long gapSlots = 0L;
    long moneySlots = 0L;
    long orderedSlots = 0L;
    long sumGap = 0L;
    long sumDesired = 0L;
    long sumSpendable = 0L;
    long sumAffordable = 0L;
    long sumOrdered = 0L;
    long sumFilled = 0L;
    Set<HouseholdId> gapHouseholds = new LinkedHashSet<>();
    Set<HouseholdId> moneyHouseholds = new LinkedHashSet<>();
    Set<HouseholdId> orderedHouseholds = new LinkedHashSet<>();
    Map<MarketUnfilledReason, long[]> byReason = new LinkedHashMap<>();
    for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
      if (filter != null && !filter.equals(outcome.commodity())) {
        continue;
      }
      outcomes++;
      if (outcome.gapQty() > 0L) {
        gapSlots++;
        outcome.household().ifPresent(gapHouseholds::add);
      }
      if (outcome.spendableMoneyMilli() > 0L) {
        moneySlots++;
        outcome.household().ifPresent(moneyHouseholds::add);
      }
      if (outcome.orderedQty() > 0L) {
        orderedSlots++;
        outcome.household().ifPresent(orderedHouseholds::add);
      }
      sumGap += outcome.gapQty();
      sumDesired += outcome.desiredQty();
      sumSpendable += outcome.spendableMoneyMilli();
      sumAffordable += outcome.affordableQty();
      sumOrdered += outcome.orderedQty();
      sumFilled += outcome.filledQty();
      MarketUnfilledReason reason = outcome.unfilledReason().orElse(null);
      if (reason != null) {
        long[] slot = byReason.computeIfAbsent(reason, ignored -> new long[2]);
        slot[0]++;
        slot[1] += outcome.gapQty();
      }
    }
    System.out.println(
        "[MF][BUYER] tick="
            + tick
            + " scope="
            + scope
            + " outcomes="
            + outcomes
            + " gapSlots="
            + gapSlots
            + " gapHouseholds="
            + gapHouseholds.size()
            + " moneySlots="
            + moneySlots
            + " moneyHouseholds="
            + moneyHouseholds.size()
            + " orderedSlots="
            + orderedSlots
            + " orderedHouseholds="
            + orderedHouseholds.size()
            + " sumGapQty="
            + sumGap
            + " sumDesiredQty="
            + sumDesired
            + " sumSpendableMoneyMilli="
            + sumSpendable
            + " sumAffordableQty="
            + sumAffordable
            + " sumOrderedQty="
            + sumOrdered
            + " sumFilledQty="
            + sumFilled
            + " byReason[count,gapQty]="
            + renderReasonQty(byReason));
  }

  /**
   * ★★ 逐"有粮缺口"家户打印它在**全部商品**上的买单：同一只钱包要同时下粮/布/纤维等单时，冻结会按比例拆薄 （{@code commitFreezes} 的
   * (buyer,currency) 轴）—— 这一栏把"钱包 ÷ 订单数"与 2 毫边距直接摆在同一条线上。
   */
  private static void printGapHouseholdOrders(
      long tick, Optional<MarketReport> maybe, EconomyData economy) {
    if (maybe.isEmpty()) {
      return;
    }
    MarketReport report = maybe.get();
    Map<HouseholdId, List<MarketReport.BuyerOutcome>> byHousehold = new LinkedHashMap<>();
    Set<HouseholdId> gapHouseholds = new LinkedHashSet<>();
    for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
      if (outcome.household().isEmpty()) {
        continue;
      }
      HouseholdId household = outcome.household().orElseThrow();
      byHousehold.computeIfAbsent(household, ignored -> new ArrayList<>()).add(outcome);
      if (GRAIN.equals(outcome.commodity()) && outcome.gapQty() > 0L) {
        gapHouseholds.add(household);
      }
    }
    for (HouseholdId household : gapHouseholds) {
      List<MarketReport.BuyerOutcome> outcomes = byHousehold.getOrDefault(household, List.of());
      long wallet = 0L;
      long requestedCostAtAsk = 0L;
      int orderedCommodities = 0;
      StringBuilder orders = new StringBuilder();
      for (MarketReport.BuyerOutcome outcome : outcomes) {
        wallet = outcome.spendableMoneyMilli();
        if (outcome.orderedQty() <= 0L) {
          continue;
        }
        orderedCommodities++;
        var market = economy.markets().get(outcome.hex());
        long ask = market == null ? 0L : market.askPriceOf(outcome.commodity());
        long cost = ask <= 0L ? 0L : outcome.orderedQty() * ask / 1000L;
        requestedCostAtAsk += cost;
        if (orders.length() > 0) {
          orders.append(',');
        }
        orders
            .append(outcome.commodity().value())
            .append("=")
            .append(outcome.orderedQty())
            .append("@ask")
            .append(ask)
            .append("(cost")
            .append(cost)
            .append(")");
      }
      System.out.println(
          "[MF][GAP-HH] tick="
              + tick
              + " household="
              + household.value()
              + " walletSpendableMilli="
              + wallet
              + " orderedCommodities="
              + orderedCommodities
              + " requestedCostAtAskMilli="
              + requestedCostAtAsk
              + " orders={"
              + orders
              + "}");
    }
  }

  private static void printSellerAggregate(
      long tick, String scope, MarketReport report, CommodityId filter) {
    long outcomes = 0L;
    long offeredSlots = 0L;
    long offeredZeroFilledSlots = 0L;
    long sumOffered = 0L;
    long sumFilled = 0L;
    long sumUnfilled = 0L;
    Set<ActorRef> offeredActors = new LinkedHashSet<>();
    Set<ActorRef> offeredZeroFilledActors = new LinkedHashSet<>();
    Map<MarketUnfilledReason, long[]> byReason = new LinkedHashMap<>();
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      if (filter != null && !filter.equals(outcome.commodity())) {
        continue;
      }
      outcomes++;
      sumOffered += outcome.offeredQty();
      sumFilled += outcome.filledQty();
      sumUnfilled += outcome.unfilledQty();
      if (outcome.offeredQty() > 0L) {
        offeredSlots++;
        offeredActors.add(outcome.actor());
        if (outcome.filledQty() == 0L) {
          offeredZeroFilledSlots++;
          offeredZeroFilledActors.add(outcome.actor());
        }
      }
      MarketUnfilledReason reason = outcome.unfilledReason().orElse(null);
      if (reason != null) {
        long[] slot = byReason.computeIfAbsent(reason, ignored -> new long[2]);
        slot[0]++;
        slot[1] += outcome.unfilledQty();
      }
    }
    System.out.println(
        "[MF][SELLER] tick="
            + tick
            + " scope="
            + scope
            + " outcomes="
            + outcomes
            + " offeredSlots="
            + offeredSlots
            + " offeredActors="
            + offeredActors.size()
            + " offeredZeroFilledSlots="
            + offeredZeroFilledSlots
            + " offeredZeroFilledActors="
            + offeredZeroFilledActors.size()
            + " sumOfferedQty="
            + sumOffered
            + " sumFilledQty="
            + sumFilled
            + " sumUnfilledQty="
            + sumUnfilled
            + " byReason[count,unfilledQty]="
            + renderReasonQty(byReason));
  }

  // ── planOrders 纯订单读数（经 MarketReadoutAssembly 公开读口）──────────────────────────

  private static void printPlanOrders(long tick, SimulationState state, EconomyData economy) {
    if (economy.markets().isEmpty()) {
      System.out.println("[MF][PLANORDERS] tick=" + tick + " marketsEmpty=true");
      return;
    }
    HexCoord focus = economy.markets().keySet().iterator().next();
    try {
      MarketReadoutAssembly.MarketReadoutContext context =
          MarketReadoutAssembly.contextFor(state, focus);
      System.out.println(
          "[MF][PLANORDERS-HEAD] tick="
              + tick
              + " focus="
              + focus
              + " readoutPresent="
              + context.readout().isPresent()
              + " reportPresent="
              + context.report().isPresent()
              + " unavailable="
              + context.readoutUnavailable());
      context
          .readout()
          .ifPresent(
              readout -> {
                for (MarketReadout.RegionReadout region : readout.regions()) {
                  for (MarketReadout.CommodityReadout commodity : region.commodities()) {
                    if (!commodity.commodity().equals(GRAIN)
                        && commodity.supplyMilli() == 0L
                        && commodity.effectiveDemandMilli() == 0L) {
                      continue;
                    }
                    String match =
                        commodity
                            .match()
                            .map(
                                value ->
                                    "tradedMilli="
                                        + value.tradedMilli()
                                        + " buyOutcomes="
                                        + value.buyerOutcomeCount()
                                        + " buyStockSufficient="
                                        + value.buyerStockSufficientCount()
                                        + " buyNoBudget="
                                        + value.buyerNoBudgetCount()
                                        + " buyGapMilli="
                                        + value.buyerGapMilli()
                                        + " sellOutcomes="
                                        + value.sellerOutcomeCount()
                                        + " sellSelfUsableQtyMilli="
                                        + value.sellerSelfUsableQtyMilli()
                                        + " sellOutcompeted="
                                        + value.sellerOutcompetedCount()
                                        + "/"
                                        + value.sellerOutcompetedQtyMilli()
                                        + " unfilledBuyReasonCounts="
                                        + value.unfilledBuyCounts()
                                        + " unfilledSellReasonCounts="
                                        + value.unfilledSellCounts())
                            .orElse("matchAbsent");
                    System.out.println(
                        "[MF][PLANORDERS] tick="
                            + tick
                            + " region="
                            + region.regionId()
                            + " commodity="
                            + commodity.commodity().value()
                            + " referencePriceMilli="
                            + commodity.referencePriceMilli()
                            + " supplyMilli="
                            + commodity.supplyMilli()
                            + " effectiveDemandMilli="
                            + commodity.effectiveDemandMilli()
                            + " naturalNeedMilli="
                            + commodity.naturalNeedMilli()
                            + " naturalNeedWindow="
                            + commodity.naturalNeedWindow()
                            + " cannotAffordMilli="
                            + commodity.needsButCannotAffordMilli()
                            + " cannotAffordHouseholds="
                            + commodity.needsButCannotAffordHouseholds()
                            + " "
                            + match);
                  }
                }
              });
    } catch (RuntimeException e) {
      System.out.println("[MF][PLANORDERS][ERROR] tick=" + tick + " " + e);
    }
  }

  // ── 账户分布 ────────────────────────────────────────────────────────────────────────

  private record AccountRow(
      String owner,
      String kind,
      String location,
      String residence,
      String stratum,
      String mode,
      long silver,
      long grain) {}

  private static void printAccounts(
      long tick, EconomyData economy, ActorData actor, Optional<MarketReport> report) {
    AccountSession session;
    try {
      session = OwnershipBooks.loadAccountSession(economy, actor);
    } catch (RuntimeException e) {
      System.out.println("[MF][ACCOUNTS][ERROR] tick=" + tick + " loadAccountSession: " + e);
      return;
    }
    List<AccountRow> rows = new ArrayList<>();
    long totalSilver = 0L;
    long totalGrain = 0L;
    Map<CurrencyId, Long> totalByCurrency = new LinkedHashMap<>();
    TreeMap<String, long[]> byResidence = new TreeMap<>();
    TreeMap<String, long[]> byStratum = new TreeMap<>();
    TreeMap<String, long[]> byMode = new TreeMap<>();
    Map<HouseholdId, long[]> householdMoneyGrain = new LinkedHashMap<>();
    for (GoodsAccount account : actor.accounts().values()) {
      // ★ P2-A §13.3：账户主体只有家户，键 = HouseholdId（不再带格）⇒ 原先的 owner/kind 分支整体退役，
      //   位置从 economy 的 ClassRow.view().hex() 派生。
      HouseholdId household = account.key().household();
      long silver = account.money().getOrDefault(SILVER, 0L);
      long grain = account.balances().getOrDefault(GRAIN, 0L);
      totalSilver += silver;
      totalGrain += grain;
      for (Map.Entry<CurrencyId, Long> money : account.money().entrySet()) {
        totalByCurrency.merge(money.getKey(), money.getValue(), Long::sum);
      }
      ClassRow row = economy.classes().get(household);
      String residence = row == null ? "-" : row.view().residence().value();
      String stratum = row == null ? "-" : row.view().stratum().value();
      String mode = "-";
      ClassStanding standing = economy.classStandings().get(household);
      if (standing != null) {
        ClassPosition position = economy.classPositions().get(standing.currentPositionId());
        if (position != null) {
          mode = position.modeId().value();
        }
      }
      long population = row == null ? 0L : row.population();
      householdMoneyGrain.put(household, new long[] {silver, grain});
      long[] residenceSlot = byResidence.computeIfAbsent(residence, ignored -> new long[4]);
      residenceSlot[0]++;
      residenceSlot[1] += population;
      residenceSlot[2] += silver;
      residenceSlot[3] += grain;
      long[] stratumSlot = byStratum.computeIfAbsent(stratum, ignored -> new long[4]);
      stratumSlot[0]++;
      stratumSlot[2] += silver;
      stratumSlot[3] += grain;
      long[] modeSlot = byMode.computeIfAbsent(mode, ignored -> new long[4]);
      modeSlot[0]++;
      modeSlot[2] += silver;
      modeSlot[3] += grain;
      rows.add(
          new AccountRow(
              "HOUSEHOLD:" + household.value(),
              "HOUSEHOLD",
              row == null ? "-" : row.view().hex().toString(),
              residence,
              stratum,
              mode,
              silver,
              grain));
    }

    long householdSilver = 0L;
    long householdGrain = 0L;
    long householdSilverZero = 0L;
    long householdGrainZero = 0L;
    for (ClassRow row : economy.classes().values()) {
      long silver =
          session.householdMoney().getOrDefault(row.id(), Map.of()).getOrDefault(SILVER, 0L);
      long grain =
          session.householdGoods().getOrDefault(row.id(), Map.of()).getOrDefault(GRAIN, 0L);
      householdSilver += silver;
      householdGrain += grain;
      if (silver == 0L) {
        householdSilverZero++;
      }
      if (grain == 0L) {
        householdGrainZero++;
      }
    }

    Map<HouseholdId, Long> gapByHousehold = new LinkedHashMap<>();
    if (report.isPresent()) {
      for (MarketReport.BuyerOutcome outcome : report.get().buyerOutcomes()) {
        if (outcome.commodity().equals(GRAIN)
            && outcome.gapQty() > 0L
            && outcome.household().isPresent()) {
          gapByHousehold.merge(outcome.household().orElseThrow(), outcome.gapQty(), Long::sum);
        }
      }
    }
    long gapNoSilverHouseholds = 0L;
    long gapNoSilverAndGrainBelowGap = 0L;
    long gapNoSilverSum = 0L;
    long gapWithSilverHouseholds = 0L;
    long gapWithSilverSum = 0L;
    for (Map.Entry<HouseholdId, Long> entry : gapByHousehold.entrySet()) {
      long[] moneyGrain = householdMoneyGrain.get(entry.getKey());
      long silver = moneyGrain == null ? 0L : moneyGrain[0];
      long grain = moneyGrain == null ? 0L : moneyGrain[1];
      if (silver == 0L) {
        gapNoSilverHouseholds++;
        gapNoSilverSum += entry.getValue();
        if (grain < entry.getValue()) {
          gapNoSilverAndGrainBelowGap++;
        }
      } else {
        gapWithSilverHouseholds++;
        gapWithSilverSum += entry.getValue();
      }
    }

    System.out.println(
        "[MF][ACCOUNTS] tick="
            + tick
            + " accounts="
            + actor.accounts().size()
            + " totalSilver="
            + totalSilver
            + " totalGrain="
            + totalGrain
            + " householdSilver="
            + householdSilver
            + " householdGrain="
            + householdGrain
            + " operatorSilver="
            + (totalSilver - householdSilver)
            + " operatorGrain="
            + (totalGrain - householdGrain)
            + " households="
            + economy.classes().size()
            + " householdSilverZero="
            + householdSilverZero
            + " householdGrainZero="
            + householdGrainZero
            + " gapGrainHouseholds="
            + gapByHousehold.size()
            + " gapNoSilverHouseholds="
            + gapNoSilverHouseholds
            + " gapNoSilverMilli="
            + gapNoSilverSum
            + " gapNoSilverAndGrainBelowGap="
            + gapNoSilverAndGrainBelowGap
            + " gapWithSilverHouseholds="
            + gapWithSilverHouseholds
            + " gapWithSilverMilli="
            + gapWithSilverSum
            + " totalByCurrency="
            + totalByCurrency);
    System.out.println(
        "[MF][ACCT-BY-RESIDENCE][households,pop,silver,grain] " + renderSlots(byResidence));
    System.out.println(
        "[MF][ACCT-BY-STRATUM][households,pop,silver,grain] " + renderSlots(byStratum));
    System.out.println("[MF][ACCT-BY-MODE][households,pop,silver,grain] " + renderSlots(byMode));

    rows.sort(
        Comparator.comparingLong(AccountRow::silver).reversed().thenComparing(AccountRow::owner));
    System.out.println("[MF][TOP-SILVER] " + renderTop(rows, true));
    rows.sort(
        Comparator.comparingLong(AccountRow::grain).reversed().thenComparing(AccountRow::owner));
    System.out.println("[MF][TOP-GRAIN] " + renderTop(rows, false));
  }

  private static String renderTop(List<AccountRow> rows, boolean bySilver) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < rows.size() && out.size() < 8; i++) {
      AccountRow row = rows.get(i);
      out.add(
          row.owner()
              + "@"
              + row.location()
              + "{res="
              + row.residence()
              + ",stratum="
              + row.stratum()
              + ",mode="
              + row.mode()
              + ",silver="
              + row.silver()
              + ",grain="
              + row.grain()
              + "}");
    }
    return out.toString();
  }

  // ── tick=1200 的补钱变异 ────────────────────────────────────────────────────────────

  /**
   * 变异组名单：tick=1200 报告中"粮缺口 &gt; 0 且可花银 = 0"的家户；补额 = max(每人 12 毫银的创世口径, 缺口货款 + 12)， 缺口货款按挂牌粮价 1
   * 毫银/单位、1 单位 = 1000 毫粮折算（{@code gapQty / 1000 + 12}）。
   */
  private static Map<HouseholdId, Long> beneficiariesOf(
      Optional<MarketReport> report, EconomyData economy, ActorData actor) {
    Map<HouseholdId, Long> injections = new LinkedHashMap<>();
    if (report.isPresent()) {
      for (MarketReport.BuyerOutcome outcome : report.get().buyerOutcomes()) {
        if (!outcome.commodity().equals(GRAIN)
            || outcome.gapQty() <= 0L
            || outcome.spendableMoneyMilli() > 0L
            || outcome.household().isEmpty()) {
          continue;
        }
        HouseholdId household = outcome.household().orElseThrow();
        ClassRow row = economy.classes().get(household);
        long population = row == null ? 0L : row.population();
        if (population <= 0L) {
          continue;
        }
        long amount = Math.max(12L * population, outcome.gapQty() / 1000L + 12L);
        injections.merge(household, amount, Math::max);
      }
    }
    if (injections.isEmpty()) {
      AccountSession session;
      try {
        session = OwnershipBooks.loadAccountSession(economy, actor);
      } catch (RuntimeException e) {
        return injections;
      }
      for (ClassRow row : economy.classes().values()) {
        if (row.population() <= 0L) {
          continue;
        }
        long silver =
            session.householdMoney().getOrDefault(row.id(), Map.of()).getOrDefault(SILVER, 0L);
        long grain =
            session.householdGoods().getOrDefault(row.id(), Map.of()).getOrDefault(GRAIN, 0L);
        long need = row.naturalNeeds().getOrDefault(GRAIN, 0L);
        if (silver == 0L && grain < need) {
          injections.put(row.id(), 12L * row.population());
        }
      }
    }
    return injections;
  }

  /** 全部有粮缺口的家户（不论可花银多少）；补额口径同上，用于回答"钱是否唯一约束"。 */
  private static Map<HouseholdId, Long> allGapBeneficiariesOf(
      Optional<MarketReport> report, EconomyData economy) {
    Map<HouseholdId, Long> injections = new LinkedHashMap<>();
    if (report.isEmpty()) {
      return injections;
    }
    for (MarketReport.BuyerOutcome outcome : report.get().buyerOutcomes()) {
      if (!outcome.commodity().equals(GRAIN)
          || outcome.gapQty() <= 0L
          || outcome.household().isEmpty()) {
        continue;
      }
      HouseholdId household = outcome.household().orElseThrow();
      ClassRow row = economy.classes().get(household);
      long population = row == null ? 0L : row.population();
      if (population <= 0L) {
        continue;
      }
      long amount = Math.max(12L * population, outcome.gapQty() / 1000L + 12L);
      injections.merge(household, amount, Math::max);
    }
    return injections;
  }

  private static ActorData injectSilver(
      ActorData actor, EconomyData economy, Map<HouseholdId, Long> injections) {
    if (injections.isEmpty()) {
      return actor;
    }
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(actor.accounts());
    int missing = 0;
    int applied = 0;
    for (Map.Entry<HouseholdId, Long> entry : injections.entrySet()) {
      ClassRow row = economy.classes().get(entry.getKey());
      if (row == null || entry.getValue() <= 0L) {
        continue;
      }
      GoodsAccountKey key = OwnershipBooks.accountKeyOf(entry.getKey());
      GoodsAccount account = accounts.get(key);
      if (account == null) {
        missing++;
        continue;
      }
      Map<CurrencyId, Long> money = new LinkedHashMap<>(account.money());
      money.merge(SILVER, entry.getValue(), Long::sum);
      accounts.put(
          key,
          new GoodsAccount(
              account.key(),
              account.balances(),
              money,
              account.frozenBalances(),
              account.frozenMoney()));
      applied++;
    }
    System.out.println(
        "[MF][CF][INJECT] requestedHouseholds="
            + injections.size()
            + " applied="
            + applied
            + " missingAccounts="
            + missing);
    return actor.withAccounts(accounts);
  }

  /**
   * ★★ <b>守恒版对照</b>：把 {@code targets} 需要的银从"银最多的账户"按降序抽出来（抽到够为止、不抽成负数）， 再等额加到目标家户账上 ——
   * 世界银总量逐值不变。用来把"货币存量不足"与"货币分布错误"分开。
   */
  private static ActorData redistributeSilver(
      ActorData actor, EconomyData economy, Map<HouseholdId, Long> targets, long[] statsOut) {
    long needed = targets.values().stream().mapToLong(Long::longValue).sum();
    Set<GoodsAccountKey> targetKeys = new LinkedHashSet<>();
    for (HouseholdId household : targets.keySet()) {
      ClassRow row = economy.classes().get(household);
      if (row != null) {
        targetKeys.add(OwnershipBooks.accountKeyOf(household));
      }
    }
    List<GoodsAccount> donors = new ArrayList<>();
    for (GoodsAccount account : actor.accounts().values()) {
      if (!targetKeys.contains(account.key()) && account.money().getOrDefault(SILVER, 0L) > 0L) {
        donors.add(account);
      }
    }
    donors.sort(
        Comparator.comparingLong((GoodsAccount account) -> account.money().getOrDefault(SILVER, 0L))
            .reversed());
    Map<GoodsAccountKey, Long> delta = new LinkedHashMap<>();
    long remaining = needed;
    long donorsUsed = 0L;
    for (GoodsAccount account : donors) {
      if (remaining <= 0L) {
        break;
      }
      long silver = account.money().getOrDefault(SILVER, 0L);
      long take = Math.min(silver, remaining);
      delta.merge(account.key(), -take, Long::sum);
      remaining -= take;
      donorsUsed++;
    }
    for (Map.Entry<HouseholdId, Long> entry : targets.entrySet()) {
      ClassRow row = economy.classes().get(entry.getKey());
      if (row != null && entry.getValue() > 0L) {
        delta.merge(
            OwnershipBooks.accountKeyOf(entry.getKey()),
            entry.getValue(),
            Long::sum);
      }
    }
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(actor.accounts());
    for (Map.Entry<GoodsAccountKey, Long> entry : delta.entrySet()) {
      GoodsAccount account = accounts.get(entry.getKey());
      if (account == null) {
        continue;
      }
      long after = account.money().getOrDefault(SILVER, 0L) + entry.getValue();
      if (after < 0L) {
        throw new IllegalStateException("再分配把账户银抽成负数: " + account.key() + " -> " + after);
      }
      Map<CurrencyId, Long> money = new LinkedHashMap<>(account.money());
      if (after == 0L) {
        money.remove(SILVER);
      } else {
        money.put(SILVER, after);
      }
      accounts.put(
          account.key(),
          new GoodsAccount(
              account.key(),
              account.balances(),
              money,
              account.frozenBalances(),
              account.frozenMoney()));
    }
    System.out.println(
        "[MF][CF][REDISTRIBUTE] requestedMilli="
            + needed
            + " movedMilli="
            + (needed - remaining)
            + " donorAccounts="
            + donorsUsed
            + " shortfallMilli="
            + remaining);
    statsOut[0] = donorsUsed;
    statsOut[1] = needed - remaining;
    return actor.withAccounts(accounts);
  }

  // ── 状态重放成新 genesis（测试侧；只用于对照/变异）────────────────────────────────────

  private static SimulationState restamp(
      SimulationState state, long tick, ActorData actorOverride) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    SimosTimestamp timestamp = SimosTimestamp.of(tick);
    StateMeta meta = new StateMeta(ref, timestamp);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (Map.Entry<String, Snapshot> entry : state.modules().entrySet()) {
      Snapshot snapshot = entry.getValue();
      Snapshot next;
      if (snapshot instanceof MapSnapshot value) {
        next = new MapSnapshot(ref, timestamp, value.map());
      } else if (snapshot instanceof SocialSnapshot value) {
        next = new SocialSnapshot(ref, timestamp, value.data());
      } else if (snapshot instanceof UnitSnapshot value) {
        next = new UnitSnapshot(ref, timestamp, value.state());
      } else if (snapshot instanceof SdSnapshot value) {
        next = new SdSnapshot(ref, timestamp, value.state());
      } else if (snapshot instanceof EconomySnapshot value) {
        next = new EconomySnapshot(ref, timestamp, value.data());
      } else if (snapshot instanceof ActorSnapshot value) {
        next =
            new ActorSnapshot(ref, timestamp, actorOverride == null ? value.data() : actorOverride);
      } else {
        throw new IllegalStateException("restamp 未覆盖的模块: " + snapshot.namespace());
      }
      modules.put(entry.getKey(), next);
    }
    return new SimulationState(meta, modules, state.info());
  }

  // ── 段内报告捕获（只读轮询公开读口；不改推进方式）──────────────────────────────────────

  private record FeedSnapshot(long tick, Optional<MarketReport> report) {}

  private record Capture(long head, Map<Long, Long> fillsByDay) {}

  private static Capture captureSegment(CoreSimos core, long head, long from, long to, String tag)
      throws InterruptedException {
    // ★ 捕获线程读的是进程内静态 feed；先清掉上一轮运行留下的 tick（否则段内会被旧条目冒名顶替）。
    MarketReportFeed.clear(MAP_ID);
    FeedCapture capture = new FeedCapture(from, to);
    long newHead = advance(core, head, from, to, tag);
    Map<Long, FeedSnapshot> snapshots = capture.stop();
    Map<Long, Long> fillsByDay = new TreeMap<>();
    for (Map.Entry<Long, FeedSnapshot> entry : snapshots.entrySet()) {
      FeedSnapshot snapshot = entry.getValue();
      if (snapshot.report().isEmpty()) {
        continue;
      }
      MarketReport report = snapshot.report().get();
      if (report.day() != entry.getKey()) {
        continue; // 没开市的日子投递的是上一轮的报告：只统计"报告就是当天"的轮次。
      }
      fillsByDay.put(entry.getKey(), (long) report.fills().size());
      System.out.println(
          "[MF][CAPTURE-DAY] segment="
              + tag
              + " tick="
              + entry.getKey()
              + " reportDay="
              + report.day()
              + " trigger="
              + report.trigger()
              + " fills="
              + report.fills().size()
              + " unfilledReasonCounts="
              + report.unfilledReasonCounts());
    }
    return new Capture(newHead, fillsByDay);
  }

  /** 只读轮询线程：用公开 {@link MarketReportFeed#last} 窗口扫描，按 tick 收当天投递的报告。 */
  private static final class FeedCapture {
    private final long to;
    private final java.util.concurrent.ConcurrentHashMap<Long, FeedSnapshot> captured =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    private FeedCapture(long from, long to) {
      this.to = to;
      this.thread = new Thread(() -> loop(from), "mf-feed-capture");
      this.thread.setDaemon(true);
      this.thread.start();
    }

    private void loop(long from) {
      long cursor = from;
      while (running) {
        long start = cursor + 1L;
        long found = -1L;
        long end = Math.min(start + 64L, to);
        for (long candidate = start; candidate <= end; candidate++) {
          Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, candidate);
          if (report.isPresent()) {
            captured.put(candidate, new FeedSnapshot(candidate, report));
            found = candidate;
          }
        }
        if (found >= 0L) {
          cursor = Math.max(cursor, found);
        } else {
          Thread.onSpinWait();
        }
      }
    }

    private Map<Long, FeedSnapshot> stop() throws InterruptedException {
      running = false;
      thread.join(2_000L);
      return new TreeMap<>(captured);
    }
  }

  // ── 装配与推进 ───────────────────────────────────────────────────────────────────────

  private static long seed(CoreSimos core, Path dir) throws Exception {
    MarketReportFeed.clear(MAP_ID);
    EconomyDayFeed.clear(MAP_ID);
    core.bootstrapGenesis(RealTwelveHexWorld.state(MAP_ID));
    Path config = RealTwelveHexWorld.writeConfig(dir);
    AgentTool tool = new WorldgenInitializeTool(core, INITIATOR, MAP_ID, config);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("nation", RealTwelveHexWorld.REGION.value());
    args.put("dryRun", false);
    args.put("army", false);
    args.put("economyProfile", "production-runtime");
    ToolResult result = tool.execute(context(tool, args));
    JsonNode summary = SimosObjectMapper.create().readTree(result.message());
    long revision = summary.path("revision").asLong();
    System.out.println(
        "[MF][SEED] success="
            + result.success()
            + " revision="
            + revision
            + " totalPopulation="
            + summary.path("totalPopulation").asLong()
            + " cityCount="
            + summary.path("cityCount").asInt()
            + " hexCount="
            + summary.path("hexCount").asInt());
    return revision;
  }

  private static long advance(CoreSimos core, long head, long from, long to, String tag) {
    CommandResult result =
        core.submit(
            new AdvanceTime(
                tag,
                tag,
                INITIATOR,
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    if (!(result instanceof CommandResult.Committed)) {
      throw new IllegalStateException("AdvanceTime 未提交: " + from + "->" + to + " result=" + result);
    }
    return head + 1L;
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }

  // ── 打印辅助 ────────────────────────────────────────────────────────────────────────

  private static String renderReasonQty(Map<MarketUnfilledReason, long[]> map) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<MarketUnfilledReason, long[]> entry : map.entrySet()) {
      out.put(entry.getKey().name(), entry.getValue()[0] + "/" + entry.getValue()[1]);
    }
    return out.toString();
  }

  private static String renderSlots(Map<String, long[]> map) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> entry : map.entrySet()) {
      long[] value = entry.getValue();
      out.put(
          entry.getKey(),
          "households="
              + value[0]
              + ",pop="
              + value[1]
              + ",silver="
              + value[2]
              + ",grain="
              + value[3]);
    }
    return out.toString();
  }

  // ── 装配（与 RealTwelveHexProductionRuntime3650Test 同源；测试侧线格式补丁）──────────────

  abstract static class EconomyMetaDerivedPropertyMixin {
    @JsonIgnore
    abstract boolean isCurrentRuntimeVersion();
  }

  private static ObjectMapper installChangeSetMapperEconomyMetaMixin()
      throws ReflectiveOperationException {
    Field field = Timeline.class.getDeclaredField("CHANGESET_MAPPER");
    field.setAccessible(true);
    ObjectMapper mapper = (ObjectMapper) field.get(null);
    mapper.addMixIn(EconomyMeta.class, EconomyMetaDerivedPropertyMixin.class);
    return mapper;
  }

  private static void restoreChangeSetMapper(ObjectMapper original)
      throws ReflectiveOperationException {
    original.addMixIn(EconomyMeta.class, null);
  }

  private static CoreSimos shellAlikeCore(Path storeDir) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            new ActorCodec())) {
      core.register(codec);
    }
    for (CommandHandler handler :
        List.of(
            new SetPopulationHandler(),
            new CreateCityHandler(),
            new SeedGroupsHandler(),
            new EconomySeedHandler(),
            new ActorSeedHandler(),
            new UpdateRegionHandler(),
            new CreateNationHandler(),
            new CreateUnitHandler(),
            new CreateCommandChainHandler(),
            new CreateArmyHandler())) {
      core.register(handler);
    }
    core.register(new UnitTimeParticipant(TerrainMovementCost.INSTANCE, RealTwelveHexWorld.MAP_ID));
    core.register(new SdTimeParticipant(RealTwelveHexWorld.MAP_ID));
    core.register(new PopulationEconomyTimeParticipant(RealTwelveHexWorld.MAP_ID));
    return core;
  }
}
