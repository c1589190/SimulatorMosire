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
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.app.time.EconomyDayFeed;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.spi.EconomyAddDemandHandler;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
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
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>真实 12hex 生产运行时「1 tick 追踪」</b>（诊断/读账，不是新验收）。
 *
 * <p>它做三件事，全部走真实程序路径（{@code CoreSimos} + 真实 {@link WorldgenInitializeTool} production-runtime +
 * 真实 {@code AdvanceTime} 时间参与者），不手搭 {@code EconomyData}：
 *
 * <ol>
 *   <li>把 {@code io.mosire.simos.economy.trace} 调到 DEBUG，跑 <b>0→1 一天</b>，让经济引擎自己按阶段打印"先干什么后干什么"；
 *   <li>从 {@link EconomyDayFeed} 取出这一天的真实 {@link ProductionLedger}，按<b>铸造序</b>打印每一笔转移（reason / 两端 /
 *       hex / 货 / 钱）；
 *   <li>对比 tick0 与 tick1 的 actor 商品/货币总账，给出守恒读数。
 * </ol>
 *
 * <p>这是"引擎有没有日志/顺序到底是什么样"的核对夹具；它不断言经济行为，只有程序路径失败（seed/submit/replay 失败）才红。
 */
class RealTwelveOneTickTraceTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String INITIATOR = "agent:one-tick-trace";
  private static final String MAP_ID = RealTwelveHexWorld.MAP_ID;
  private static final CommodityId GRAIN = new CommodityId("grain");

  @TempDir Path tempDir;

  @Test
  void realTwelveHexOneTickTrace() throws Exception {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.DEBUG);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.TRACE);
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("one-tick-store")))) {
      EconomyDayFeed.clear(MAP_ID);
      MarketReportFeed.clear(MAP_ID);
      long head = seed(core, tempDir);
      SimulationState atZero = core.replay(ref(head));
      EconomyData economyAtZero = CompactThreeNationsWorld.economyOf(atZero);
      printState("tick0", atZero, economyAtZero, Optional.empty());
      printTotals("tick0", actorOf(atZero));

      System.out.println("[TRACE-1T] ==== 开始真实推进 0 -> 1（CoreSimos.submit(AdvanceTime)）====");
      head = advance(core, head, 0L, 1L, "one-tick");
      SimulationState atOne = core.replay(ref(head));
      EconomyData economyAtOne = CompactThreeNationsWorld.economyOf(atOne);
      Optional<ProductionLedger> ledger = EconomyDayFeed.last(MAP_ID, 1L);
      Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, 1L);
      printState("tick1", atOne, economyAtOne, ledger);
      printLedger(ledger);
      printMarket(report);
      printFlows(economyAtOne);
      printDebts(economyAtOne);
      printTotals("tick1", actorOf(atOne));
      System.out.println("[TRACE-1T] ==== 1 tick 追踪结束 ====");
    } finally {
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.INFO);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
      restoreChangeSetMapper(original);
    }
  }

  /**
   * 0→5 天：第 5 天是 {@code MARKET_RESTOCK_INTERVAL_DAYS} 的例行开市日 ⇒ 用真实日志验证市场路径
   * （MARKET_ROUND_START / MARKET / MARKET_FILL / MARKET_SELLER_OUTCOME / MARKET_BUYER_OUTCOME / 信用成交/未成交）。
   */
  @Test
  void realTwelveHexFiveTickMarketTrace() throws Exception {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.DEBUG);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.TRACE);
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("five-tick-store")))) {
      EconomyDayFeed.clear(MAP_ID);
      MarketReportFeed.clear(MAP_ID);
      long head = seed(core, tempDir);
      System.out.println("[TRACE-5T] ==== 开始真实推进 0 -> 5（覆盖第 5 天例行开市）====");
      head = advance(core, head, 0L, 5L, "five-tick");
      SimulationState atFive = core.replay(ref(head));
      EconomyData economyAtFive = CompactThreeNationsWorld.economyOf(atFive);
      printState("tick5", atFive, economyAtFive, EconomyDayFeed.last(MAP_ID, 5L));
      printLedger(EconomyDayFeed.last(MAP_ID, 5L));
      printMarket(MarketReportFeed.last(MAP_ID, 5L));
      printFlows(economyAtFive);
      printDebts(economyAtFive);
      printTotals("tick5", actorOf(atFive));
      System.out.println("[TRACE-5T] ==== 5 tick 追踪结束 ====");
    } finally {
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.INFO);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
      restoreChangeSetMapper(original);
    }
  }

  /**
   * 跑到第 3600 天，再带 DEBUG/TRACE 跑 3600→3605，打印第 3603/3605 个市场轮的买卖槽数量明细 —— 回答
   * "后段到底有没有挂单、挂了没人买还是压根没挂、买卖两侧各被什么挡住"。
   */
  @Test
  void realTwelveHexLateMarketTrace() throws Exception {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.WARN);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.WARN);
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("late-market-store")))) {
      EconomyDayFeed.clear(MAP_ID);
      MarketReportFeed.clear(MAP_ID);
      long head = seed(core, tempDir);
      long tick = 0L;
      int segment = 0;
      while (tick < 3600L) {
        long to = Math.min(tick + 120L, 3600L);
        segment++;
        head = advance(core, head, tick, to, "late-warm-" + segment);
        tick = to;
      }
      System.out.println("[TRACE-LATE] warm-up 到 tick=3600，开 DEBUG/TRACE 跑 3600->3605");
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.DEBUG);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.TRACE);
      head = advance(core, head, 3600L, 3605L, "late-market");
      SimulationState atEnd = core.replay(ref(head));
      EconomyData economy = CompactThreeNationsWorld.economyOf(atEnd);
      printState("tick3605", atEnd, economy, EconomyDayFeed.last(MAP_ID, 3605L));
      printMarketDetail(MarketReportFeed.last(MAP_ID, 3603L), 3603L);
      printMarketDetail(MarketReportFeed.last(MAP_ID, 3605L), 3605L);
      printDebts(economy);
      System.out.println("[TRACE-LATE] ==== late market 追踪结束 ====");
    } finally {
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.INFO);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
      restoreChangeSetMapper(original);
    }
  }

  /**
   * 0→1000 tick 真实路径（8 个 120 天周期），经济日志开到 DEBUG（逐笔 TRACE 不开）—— 取消借款人借债上限后，
   * 观察同格借粮、市场信用、债务、人口与未满足需求的真实变化。
   */
  @Test
  void realTwelveHexThousandTickDebug() throws Exception {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.DEBUG);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("thousand-tick-store")))) {
      EconomyDayFeed.clear(MAP_ID);
      MarketReportFeed.clear(MAP_ID);
      long head = seed(core, tempDir);
      long tick = 0L;
      int segment = 0;
      while (tick < 1000L) {
        long to = Math.min(tick + 120L, 1000L);
        segment++;
        head = advance(core, head, tick, to, "thousand-" + segment);
        tick = to;
      }
      SimulationState atEnd = core.replay(ref(head));
      EconomyData economy = CompactThreeNationsWorld.economyOf(atEnd);
      printState("tick1000", atEnd, economy, EconomyDayFeed.last(MAP_ID, 1000L));
      printLedger(EconomyDayFeed.last(MAP_ID, 1000L));
      printDebts(economy);
      printTotals("tick1000", actorOf(atEnd));
      System.out.println("[TRACE-1000] ==== 1000 tick 追踪结束 ====");
    } finally {
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.INFO);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
      restoreChangeSetMapper(original);
    }
  }

  /**
   * ★★ 2026-10-07 GOV 非生产家户试点：0→1000 tick 真实路径。
   *
   * <p>与 {@link #realTwelveHexThousandTickDebug()} 同一条 12hex 路径，只换
   * {@code economyProfile=production-runtime-government}：seed 追加一个 population=0、slot=official 的 GOV 家户；
   * 测试通过 GM 命令 {@code economy.AddDemand} 给它注入粮/布需求；周期开始日政府按
   * {@link EconomySeeder#GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI} 自行铸币；现金不足的部分走市场信用形成政府债务。
   */
  @Test
  void realTwelveHexGovernmentThousandTickDebug() throws Exception {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.DEBUG);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
    ObjectMapper original = installChangeSetMapperEconomyMetaMixin();
    try (CoreSimos core =
        shellAlikeCore(Files.createDirectories(tempDir.resolve("gov-thousand-tick-store")))) {
      EconomyDayFeed.clear(MAP_ID);
      MarketReportFeed.clear(MAP_ID);
      long head = seed(core, tempDir, "production-runtime-government");
      SimulationState seeded = core.replay(ref(head));
      EconomyData seededEconomy = CompactThreeNationsWorld.economyOf(seeded);
      Government government =
          seededEconomy.governments().values().stream()
              .filter(candidate -> candidate.seignioragePerCycle() > 0L)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("GOV profile seed 没有带铸币政策的政府"));
      HouseholdId governmentHousehold = HouseholdActors.householdOf(government.treasury());
      ClassRow governmentRow = seededEconomy.classes().get(governmentHousehold);
      if (governmentRow == null) {
        throw new IllegalStateException("GOV 家户行不存在: " + governmentHousehold);
      }
      HexCoord governmentHex = governmentRow.view().hex();
      System.out.println(
          "[TRACE-GOV][SEED] government="
              + government.id()
              + " household="
              + governmentHousehold
              + " hex="
              + governmentHex
              + " population="
              + governmentRow.population()
              + " seignioragePerCycle="
              + government.seignioragePerCycle()
              + " debtIssuePerCycle="
              + government.debtIssuePerCycle()
              + " treasury="
              + government.treasury());
      printPrices(seededEconomy, "gov-tick0");
      // GM 可配置需求：每轮市场挂出粮 500,000 毫粮、布 20,000 毫布（HOUSEHOLD 范围，TOTAL 口径）。
      head = addGovernmentDemand(core, head, governmentHousehold, "grain", 500_000L, 1);
      head = addGovernmentDemand(core, head, governmentHousehold, "cloth", 20_000L, 2);
      long tick = 0L;
      int segment = 0;
      while (tick < 1000L) {
        long to = Math.min(tick + 120L, 1000L);
        segment++;
        head = advance(core, head, tick, to, "gov-thousand-" + segment);
        tick = to;
      }
      SimulationState atEnd = core.replay(ref(head));
      EconomyData economy = CompactThreeNationsWorld.economyOf(atEnd);
      printState("gov-tick1000", atEnd, economy, EconomyDayFeed.last(MAP_ID, 1000L));
      printPrices(economy, "gov-tick1000");
      printPriceUpdates(MarketReportFeed.last(MAP_ID, 1000L));
      printMarketDetail(MarketReportFeed.last(MAP_ID, 1000L), 1000L);
      printLedger(EconomyDayFeed.last(MAP_ID, 1000L));
      printDebts(economy);
      printGovernment(economy, actorOf(atEnd), government, governmentHousehold, governmentHex);
      printTotals("gov-tick1000", actorOf(atEnd));
      System.out.println("[TRACE-GOV] ==== GOV 1000 tick 追踪结束 ====");
    } finally {
      Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, Level.INFO);
      Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, Level.INFO);
      restoreChangeSetMapper(original);
    }
  }

  // ── 状态/账本读法 ───────────────────────────────────────────────────────────────────

  private static void printState(
      String tag,
      SimulationState state,
      EconomyData economy,
      Optional<ProductionLedger> ledger) {
    long population = 0L;
    Map<ProductionModeId, Long> modes = new LinkedHashMap<>();
    Map<String, Long> byHex = new TreeMap<>();
    for (ClassRow row : economy.classes().values()) {
      population += row.population();
      byHex.merge(row.view().hex().q() + "," + row.view().hex().r(), row.population(), Long::sum);
    }
    for (ClassStanding standing : economy.classStandings().values()) {
      ClassPosition position = economy.classPositions().get(standing.currentPositionId());
      ClassRow row = economy.classes().get(standing.householdId());
      if (position != null && row != null) {
        modes.merge(position.modeId(), row.population(), Long::sum);
      }
    }
    System.out.println(
        "[TRACE-1T]["
            + tag
            + "][STATE] households="
            + economy.classes().size()
            + " population="
            + population
            + " markets="
            + economy.markets().size()
            + " debtContracts="
            + economy.debtContracts().size()
            + " units="
            + economy.units().size()
            + " modes="
            + modes
            + " economyMeta.lastClosedCycle="
            + economy
                .meta()
                .map(EconomyMeta::lastClosedCycle)
                .orElse(java.util.OptionalLong.empty())
                .orElse(-1L)
            + " ledgerPresent="
            + ledger.isPresent()
            + " byHexPop="
            + byHex);
  }

  /** 逐商品的价格分布（自适应价打开后各市场区价格会分化/上涨；固定价模式下应逐格相同）。 */
  private static void printPrices(EconomyData economy, String tag) {
    Map<CommodityId, List<Long>> byCommodity =
        new TreeMap<>(Comparator.comparing(CommodityId::value));
    for (Market market : economy.markets().values()) {
      for (Map.Entry<CommodityId, Long> price : market.prices().entrySet()) {
        byCommodity.computeIfAbsent(price.getKey(), ignored -> new ArrayList<>()).add(price.getValue());
      }
    }
    List<String> lines = new ArrayList<>();
    for (Map.Entry<CommodityId, List<Long>> entry : byCommodity.entrySet()) {
      List<Long> values = entry.getValue();
      Collections.sort(values);
      lines.add(
          entry.getKey().value()
              + "[min="
              + values.get(0)
              + ",max="
              + values.get(values.size() - 1)
              + ",distinct="
              + values.stream().distinct().count()
              + "]");
    }
    System.out.println("[TRACE-1T][" + tag + "][PRICES] " + lines);
  }

  private static void printLedger(Optional<ProductionLedger> optional) {
    if (optional.isEmpty()) {
      System.out.println("[TRACE-1T][LEDGER] 当天没有投递 ProductionLedger（推进路径没接 feed？）");
      return;
    }
    ProductionLedger ledger = optional.get();
    Map<TransferReason, Long> transferCounts = new TreeMap<>();
    Map<TransferReason, Long> transferGoods = new TreeMap<>();
    Map<TransferReason, Long> transferMoney = new TreeMap<>();
    for (Transfer transfer : ledger.transfers()) {
      transferCounts.merge(transfer.reason(), 1L, Long::sum);
      long goods = 0L;
      for (long quantity : transfer.goods().values()) {
        goods += quantity;
      }
      long money = 0L;
      for (long quantity : transfer.money().values()) {
        money += quantity;
      }
      transferGoods.merge(transfer.reason(), goods, Long::sum);
      transferMoney.merge(transfer.reason(), money, Long::sum);
    }
    System.out.println(
        "[TRACE-1T][LEDGER] hasOutput="
            + ledger.hasOutput()
            + " gross="
            + ledger.gross()
            + " losses="
            + ledger.losses()
            + " inputs="
            + ledger.inputs()
            + " outputAccruals="
            + ledger.outputAccruals().size()
            + " transfers="
            + ledger.transfers().size()
            + " ruleSettlements="
            + ledger.ruleSettlements().size()
            + " debtCapitalizations="
            + ledger.debtCapitalizations().size()
            + " unresolvedDebtCapitalizations="
            + ledger.unresolvedDebtCapitalizations().size()
            + " debtRepaymentSkips="
            + ledger.debtRepaymentSkips().size()
            + " liquidationAudits="
            + ledger.liquidationAudits().size());
    System.out.println(
        "[TRACE-1T][LEDGER][TRANSFERS-BY-REASON] count="
            + transferCounts
            + " goodsMilli="
            + transferGoods
            + " moneyMilli="
            + transferMoney);
    int shown = 0;
    for (Transfer transfer : ledger.transfers()) {
      if (shown++ >= 60) {
        System.out.println("[TRACE-1T][LEDGER][TRANSFER] ...（只打印前 60 笔，共 " + ledger.transfers().size() + " 笔）");
        break;
      }
      System.out.println(
          "[TRACE-1T][LEDGER][TRANSFER] "
              + transfer.id().value()
              + " reason="
              + transfer.reason()
              + " "
              + transfer.from().id()
              + " -> "
              + transfer.to().id()
              + " @"
              + transfer.location().q()
              + ","
              + transfer.location().r()
              + " goods="
              + transfer.goods()
              + " money="
              + transfer.money());
    }
  }

  /** 最近一轮市场报告里的逐 (区, 商品) 改价记录（自适应模式下才有）。 */
  private static void printPriceUpdates(Optional<MarketReport> optional) {
    optional.ifPresent(
        report ->
            System.out.println(
                "[TRACE-1T][PRICE-UPDATES] day="
                    + report.day()
                    + " mode="
                    + report.priceMode()
                    + " updates="
                    + report.priceUpdates()));
  }

  private static void printMarket(Optional<MarketReport> optional) {
    if (optional.isEmpty() || optional.get().day() != 1L) {
      System.out.println("[TRACE-1T][MARKET] tick=1 没有属于当天的市场报告（0→1 不是开市日，符合 trigger：day%5 或关账日）");
      return;
    }
    MarketReport report = optional.get();
    System.out.println(
        "[TRACE-1T][MARKET] day="
            + report.day()
            + " trigger="
            + report.trigger()
            + " fills="
            + report.fills().size()
            + " unfilled="
            + report.unfilled().size()
            + " reasons="
            + report.unfilledReasonCounts()
            + " creditFills="
            + report.creditFills().size()
            + " immediateCrossHex="
            + report.immediateCrossHexFills()
            + " immediateCrossHexLossMilli="
            + report.immediateCrossHexLossMilli()
            + " scheduledLossMilli="
            + report.scheduledLossMilli()
            + " regulatedTariffMilli="
            + report.regulatedTariffMilli());
  }

  /** 后段市场明细：挂了多少、成了多少、没成多少、买卖两侧各自的原因与数量。 */
  private static void printMarketDetail(Optional<MarketReport> optional, long day) {
    if (optional.isEmpty() || optional.get().day() != day) {
      System.out.println(
          "[TRACE-LATE][MARKET-DETAIL] day="
              + day
              + " 没有当天报告: reportDay="
              + optional.map(MarketReport::day).orElse(-1L));
      return;
    }
    MarketReport report = optional.get();
    Map<CommodityId, long[]> sellers = new LinkedHashMap<>();
    for (MarketReport.SellerOutcome seller : report.sellerOutcomes()) {
      long[] value = sellers.computeIfAbsent(seller.commodity(), ignored -> new long[4]);
      value[0] += seller.offeredQty();
      value[1] += seller.filledQty();
      value[2] += seller.unfilledQty();
      value[3]++;
    }
    Map<CommodityId, long[]> buyers = new LinkedHashMap<>();
    for (MarketReport.BuyerOutcome buyer : report.buyerOutcomes()) {
      long[] value = buyers.computeIfAbsent(buyer.commodity(), ignored -> new long[6]);
      value[0] += buyer.gapQty();
      value[1] += buyer.desiredQty();
      value[2] += buyer.orderedQty();
      value[3] += buyer.filledQty();
      value[4] += buyer.affordableQty();
      value[5]++;
    }
    Map<MarketUnfilledReason, Long> sellReasons = new TreeMap<>();
    Map<MarketUnfilledReason, Long> buyReasons = new TreeMap<>();
    long sellUnfilledQty = 0L;
    long buyUnfilledQty = 0L;
    for (MarketReport.Unfilled unfilled : report.unfilled()) {
      if (unfilled.buyerSide()) {
        buyReasons.merge(unfilled.reason(), 1L, Long::sum);
        buyUnfilledQty += unfilled.quantity();
      } else {
        sellReasons.merge(unfilled.reason(), 1L, Long::sum);
        sellUnfilledQty += unfilled.quantity();
      }
    }
    System.out.println(
        "[TRACE-LATE][MARKET-DETAIL] day="
            + day
            + " trigger="
            + report.trigger()
            + " fills="
            + report.fills().size()
            + " creditFills="
            + report.creditFills().size()
            + " sellerSlots="
            + report.sellerOutcomes().size()
            + " buyerSlots="
            + report.buyerOutcomes().size()
            + " sellUnfilledSlots="
            + sellReasons.values().stream().mapToLong(Long::longValue).sum()
            + " sellUnfilledQty="
            + sellUnfilledQty
            + " buyUnfilledSlots="
            + buyReasons.values().stream().mapToLong(Long::longValue).sum()
            + " buyUnfilledQty="
            + buyUnfilledQty);
    for (Map.Entry<CommodityId, long[]> entry : sellers.entrySet()) {
      long[] value = entry.getValue();
      System.out.println(
          "[TRACE-LATE][SELLER-AGG] day="
              + day
              + " commodity="
              + entry.getKey().value()
              + " slots="
              + value[3]
              + " offered="
              + value[0]
              + " filled="
              + value[1]
              + " unfilled="
              + value[2]);
    }
    for (Map.Entry<CommodityId, long[]> entry : buyers.entrySet()) {
      long[] value = entry.getValue();
      System.out.println(
          "[TRACE-LATE][BUYER-AGG] day="
              + day
              + " commodity="
              + entry.getKey().value()
              + " slots="
              + value[5]
              + " gap="
              + value[0]
              + " desired="
              + value[1]
              + " ordered="
              + value[2]
              + " filled="
              + value[3]
              + " affordable="
              + value[4]);
    }
    System.out.println("[TRACE-LATE][SELL-REASONS] day=" + day + " " + sellReasons);
    System.out.println("[TRACE-LATE][BUY-REASONS] day=" + day + " " + buyReasons);
    int shownSellers = 0;
    for (MarketReport.SellerOutcome seller : report.sellerOutcomes()) {
      if (shownSellers++ >= 8) {
        break;
      }
      System.out.println(
          "[TRACE-LATE][SELLER] day="
              + day
              + " actor="
              + seller.actor().id()
              + " commodity="
              + seller.commodity().value()
              + " hex="
              + seller.hex().q()
              + ","
              + seller.hex().r()
              + " offered="
              + seller.offeredQty()
              + " filled="
              + seller.filledQty()
              + " unfilled="
              + seller.unfilledQty()
              + " unitPrice="
              + seller.unitPriceMilli()
              + " unitCostEstimate="
              + seller.unitCostEstimateMilli()
              + " costKnown="
              + seller.costKnown()
              + " priceMissing="
              + seller.priceMissing()
              + " reason="
              + seller.unfilledReason().map(Enum::name).orElse("-"));
    }
    int shownBuyers = 0;
    for (MarketReport.BuyerOutcome buyer : report.buyerOutcomes()) {
      if (shownBuyers++ >= 8) {
        break;
      }
      System.out.println(
          "[TRACE-LATE][BUYER] day="
              + day
              + " actor="
              + buyer.actor().id()
              + " commodity="
              + buyer.commodity().value()
              + " hex="
              + buyer.hex().q()
              + ","
              + buyer.hex().r()
              + " stockOnHand="
              + buyer.stockOnHandMilli()
              + " stockCoverDays="
              + buyer.stockCoverDays()
              + " gap="
              + buyer.gapQty()
              + " desired="
              + buyer.desiredQty()
              + " spendableMoney="
              + buyer.spendableMoneyMilli()
              + " affordable="
              + buyer.affordableQty()
              + " ordered="
              + buyer.orderedQty()
              + " filled="
              + buyer.filledQty()
              + " reason="
              + buyer.unfilledReason().map(Enum::name).orElse("-"));
    }
    for (MarketReport.CreditFill credit : report.creditFills()) {
      System.out.println(
          "[TRACE-LATE][CREDIT] day="
              + day
              + " commodity="
              + credit.commodity().value()
              + " borrower="
              + credit.borrower().id()
              + " lenderOrSeller="
              + credit.lenderOrSeller().id()
              + " quantity="
              + credit.quantityMilli()
              + " unit="
              + credit.unit().key()
              + " dueCycle="
              + credit.dueCycle());
    }
  }

  private static void printFlows(EconomyData economy) {
    int shown = 0;
    for (Map.Entry<HouseholdId, FlowRow> entry : economy.flows().entrySet()) {
      FlowRow flow = entry.getValue();
      boolean active =
          flow.newBorrowing() > 0L
              || flow.repaid() > 0L
              || flow.interestDue() > 0L
              || flow.births() > 0L
              || flow.deaths() > 0L
              || !flow.income().isEmpty()
              || !flow.unmetNeed().isEmpty();
      if (!active) {
        continue;
      }
      if (shown++ >= 30) {
        System.out.println("[TRACE-1T][FLOW] ...（只打印前 30 个有发生额的户；总户数 " + economy.flows().size() + "）");
        break;
      }
      System.out.println(
          "[TRACE-1T][FLOW] "
              + entry.getKey().value()
              + " income="
              + flow.income()
              + " consumed="
              + flow.consumed()
              + " unmet="
              + flow.unmetNeed()
              + " newBorrowing="
              + flow.newBorrowing()
              + " repaid="
              + flow.repaid()
              + " interestDue="
              + flow.interestDue()
              + " births="
              + flow.births()
              + " deaths="
              + flow.deaths());
    }
  }

  private static void printDebts(EconomyData economy) {
    long moneyPrincipal = 0L;
    long grainPrincipal = 0L;
    long otherPrincipal = 0L;
    Map<String, Long> byCreditor = new TreeMap<>();
    for (DebtContract debt : economy.debtContracts().values()) {
      if (debt.unit() instanceof DebtUnit.Money) {
        moneyPrincipal += debt.principal();
      } else if (debt.unit() instanceof DebtUnit.Commodity commodity
          && commodity.commodity().equals(GRAIN)) {
        grainPrincipal += debt.principal();
      } else {
        otherPrincipal += debt.principal();
      }
      byCreditor.merge(debt.creditor().value(), debt.principal(), Long::sum);
    }
    System.out.println(
        "[TRACE-1T][DEBT] contracts="
            + economy.debtContracts().size()
            + " moneyPrincipal="
            + moneyPrincipal
            + " grainPrincipal="
            + grainPrincipal
            + " otherPrincipal="
            + otherPrincipal
            + " topCreditors="
            + byCreditor.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10L)
                .toList());
    int shown = 0;
    for (DebtContract debt : economy.debtContracts().values()) {
      if (shown++ >= 10) {
        break;
      }
      System.out.println(
          "[TRACE-1T][DEBT][CONTRACT] "
              + debt.id().value()
              + " debtor="
              + debt.debtor().value()
              + " creditor="
              + debt.creditor().value()
              + " unit="
              + debt.unit().key()
              + " principal="
              + debt.principal()
              + " ratePerMille="
              + debt.terms().interestRatePerMillePerCycle()
              + " dueCycle="
              + debt.dueCycle()
              + " status="
              + debt.status());
    }
  }

  /** GOV 家户试点读数：铸币审计、国库账户余额、政府作为债务人的全部合同。 */
  private static void printGovernment(
      EconomyData economy,
      ActorData actor,
      Government government,
      HouseholdId governmentHousehold,
      HexCoord governmentHex) {
    long initialEndowment = 0L;
    long fiscalIssue = 0L;
    long withdrawal = 0L;
    int issuanceRecords = 0;
    for (MoneyIssuanceRecord record : economy.moneyIssuances().values()) {
      if (!record.governmentId().equals(government.id())) {
        continue;
      }
      issuanceRecords++;
      if (record.kind() == MoneyIssuanceKind.INITIAL_ENDOWMENT) {
        initialEndowment += record.amount();
      } else if (record.kind() == MoneyIssuanceKind.FISCAL_ISSUE) {
        fiscalIssue += record.amount();
      } else if (record.kind() == MoneyIssuanceKind.WITHDRAWAL) {
        withdrawal += record.amount();
      }
    }
    System.out.println(
        "[TRACE-GOV][ISSUANCE] government="
            + government.id()
            + " seignioragePerCycle="
            + government.seignioragePerCycle()
            + " debtIssuePerCycle="
            + government.debtIssuePerCycle()
            + " records="
            + issuanceRecords
            + " initialEndowment="
            + initialEndowment
            + " fiscalIssue="
            + fiscalIssue
            + " withdrawal="
            + withdrawal);
    GoodsAccountKey accountKey =
        new GoodsAccountKey(governmentHousehold);
    GoodsAccount account = actor.accounts().get(accountKey);
    if (account == null) {
      System.out.println("[TRACE-GOV][ACCOUNT] 缺失: " + accountKey);
    } else {
      System.out.println(
          "[TRACE-GOV][ACCOUNT] key="
              + accountKey
              + " money="
              + account.money()
              + " goods="
              + account.balances()
              + " frozenMoney="
              + account.frozenMoney()
              + " frozenGoods="
              + account.frozenBalances());
    }
    long govDebt = 0L;
    long govCredit = 0L;
    int govDebtContracts = 0;
    Map<String, Long> debtByUnit = new TreeMap<>();
    Map<HouseholdId, Long> debtByCreditor = new TreeMap<>(Comparator.comparing(HouseholdId::value));
    for (DebtContract debt : economy.debtContracts().values()) {
      if (debt.debtor().equals(governmentHousehold)) {
        govDebt = Math.addExact(govDebt, debt.principal());
        govDebtContracts++;
        debtByUnit.merge(debt.unit().key(), debt.principal(), Long::sum);
        debtByCreditor.merge(debt.creditor(), debt.principal(), Long::sum);
      }
      if (debt.creditor().equals(governmentHousehold)) {
        govCredit = Math.addExact(govCredit, debt.principal());
      }
    }
    System.out.println(
        "[TRACE-GOV][DEBT] contractsAsDebtor="
            + govDebtContracts
            + " principalAsDebtor="
            + govDebt
            + " principalAsCreditor="
            + govCredit
            + " byUnit="
            + debtByUnit
            + " topCreditors="
            + debtByCreditor.entrySet().stream()
                .sorted(Map.Entry.<HouseholdId, Long>comparingByValue().reversed())
                .limit(10L)
                .toList());
  }

  private static void printTotals(String tag, ActorData actor) {
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    Map<CommodityId, Long> frozenGoods = new LinkedHashMap<>();
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    long accounts = 0L;
    for (GoodsAccount account : actor.accounts().values()) {
      accounts++;
      for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
        money.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      for (Map.Entry<CommodityId, Long> entry : account.balances().entrySet()) {
        goods.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      for (Map.Entry<CommodityId, Long> entry : account.frozenBalances().entrySet()) {
        frozenGoods.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      for (Map.Entry<CurrencyId, Long> entry : account.frozenMoney().entrySet()) {
        frozenMoney.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    System.out.println(
        "[TRACE-1T]["
            + tag
            + "][ACTOR-TOTALS] accounts="
            + accounts
            + " money="
            + money
            + " goods="
            + goods
            + " frozenGoods="
            + frozenGoods
            + " frozenMoney="
            + frozenMoney);
  }

  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot = state.module("actor").orElseThrow();
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException("actor 模块不是 ActorSnapshot: " + snapshot.getClass());
    }
    return actorSnapshot.data();
  }

  // ── 装配（与 RealTwelveHexProductionRuntime3650Test 同源）─────────────────────────────

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

  private static long seed(CoreSimos core, Path dir) throws Exception {
    return seed(core, dir, "production-runtime");
  }

  private static long seed(CoreSimos core, Path dir, String economyProfile) throws Exception {
    core.bootstrapGenesis(RealTwelveHexWorld.state(MAP_ID));
    Path config = RealTwelveHexWorld.writeConfig(dir);
    AgentTool tool = new WorldgenInitializeTool(core, INITIATOR, MAP_ID, config);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("nation", RealTwelveHexWorld.REGION.value());
    args.put("dryRun", false);
    args.put("army", false);
    args.put("economyProfile", economyProfile);
    ToolResult result = tool.execute(context(tool, args));
    JsonNode summary = SimosObjectMapper.create().readTree(result.message());
    System.out.println("[TRACE-1T][SEED] success=" + result.success() + " summary=" + summary);
    if (!result.success()) {
      throw new IllegalStateException("worldgen.initialize 失败: " + result.message());
    }
    return summary.path("revision").asLong();
  }

  /**
   * ★ 用 GM 命令 {@code economy.AddDemand} 给 GOV 家户追加一条 {@code HOUSEHOLD} 范围、{@code RECURRING/TOTAL} 需求。
   *
   * <p>每次提交一条 revision，返回新的 head。需求落在 {@code EconomyData.demands} 后，市场订单路径与普通家户同一份实现。
   */
  private static long addGovernmentDemand(
      CoreSimos core,
      long head,
      HouseholdId governmentHousehold,
      String commodity,
      long quantityPerCycle,
      int priority) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("scope", "HOUSEHOLD");
    payload.put("household", governmentHousehold.value());
    payload.put("commodity", commodity);
    payload.put("kind", "RECURRING");
    payload.put("unit", "TOTAL");
    payload.put("quantityPerCycle", quantityPerCycle);
    payload.put("priority", priority);
    payload.put("source", "gov-policy");
    String commandId = "gov-demand-" + commodity;
    CommandResult result =
        core.submit(
            new CommandEnvelope(
                commandId,
                commandId,
                INITIATOR,
                MAIN,
                new RevisionId(head),
                "economy.AddDemand",
                ToolSupport.json(payload)));
    if (!(result instanceof CommandResult.Committed)) {
      throw new IllegalStateException(
          "GOV 需求命令未提交: commodity=" + commodity + " result=" + result);
    }
    return head + 1L;
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
      throw new IllegalStateException("AdvanceTime 未提交: " + from + " -> " + to + " result=" + result);
    }
    return head + 1L;
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
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
            new EconomyAddDemandHandler(),
            new ActorSeedHandler(),
            new UpdateRegionHandler(),
            new CreateNationHandler(),
            new CreateUnitHandler(),
            new CreateCommandChainHandler(),
            new CreateArmyHandler())) {
      core.register(handler);
    }
    core.register(
        new UnitTimeParticipant(TerrainMovementCost.INSTANCE, RealTwelveHexWorld.MAP_ID));
    core.register(new SdTimeParticipant(RealTwelveHexWorld.MAP_ID));
    core.register(new PopulationEconomyTimeParticipant(RealTwelveHexWorld.MAP_ID));
    return core;
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }
}
