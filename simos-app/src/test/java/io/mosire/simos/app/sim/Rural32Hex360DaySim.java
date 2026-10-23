package io.mosire.simos.app.sim;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.app.world.ArableSupplySnapshots;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.Rural32HexWorld;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.WageSettlementReadout;
import io.mosire.simos.economy.time.WageSettlementReadoutFeed;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Property;

/**
 * ★★ <b>D-032 P6a2：32 hex / 1 市场区 / 1 地方政府世界的 360 tick 数据表</b>。
 *
 * <p>父设计锚点：{@code docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md} §6.3。 世界由
 * P6a1 的 {@link Rural32HexWorld#state(String)} 提供；本类只用真 {@link Shell} + 真 {@code bootstrapGenesis}
 * 落盘，再逐日 {@code advanceDays(1)} 共 360 tick。
 *
 * <p>★ <b>本类只观测，不解读</b>：每 tick 输出固定列顺序的一行，拒绝/异常也写 {@code status=REJECTED} 后尽量继续。 拿不到的列写空/0，口径见
 * {@code .superpowers/sdd/2026-10-19-d032-p6a2-360tick-table/impl-ledger.md}。
 *
 * <p>★ 不进 surefire（类名 {@code *Sim}）；跑法：{@code tools/multitick-sim.sh rural-32hex-360d}。
 */
public final class Rural32Hex360DaySim {

  /** 推进 tick 数。 */
  public static final int TICKS = 360;

  private static final BranchId MAIN = new BranchId("main");

  private static final String MAP_ID = Rural32HexWorld.MAP_ID;

  private static final CommodityId GRAIN = new CommodityId(EconomySeeder.COMMODITY_GRAIN);
  private static final CommodityId FIBER = new CommodityId(EconomySeeder.COMMODITY_FIBER);
  private static final CommodityId TOOL = new CommodityId(EconomySeeder.COMMODITY_TOOL);

  /** 固定列顺序（用户 2026-10-19 指定，不得改动）。 */
  private static final List<String> COLUMNS =
      List.of(
          "tick",
          "marketDay",
          "revision",
          "status",
          "population",
          "households",
          "births",
          "deaths",
          "moneyTotalMilli",
          "grainStockMilli",
          "fiberStockMilli",
          "toolStockMilli",
          "landTotalMilliMu",
          "landPriceMilliPerMu",
          "landTradedMilliMu",
          // ★ D-032 RA1b2c1（RA5a）：LAND 抵债已标量化 ⇒ 列名/口径由“块数”改为“千分亩数量”。
          "landDebtTransferredMilliMu",
          "productionGrainMilli",
          "productionFiberMilli",
          "seedInputMilli",
          "marketTrades",
          "marketUnfilled",
          "grainPriceMilli",
          "fiberPriceMilli",
          "wageDueMilli",
          "wagePaidMilli",
          "wageArrearsMilli",
          "wageDebtOutstandingMilli",
          "debtPrincipalMilli",
          "debtContractCount",
          "productionLaborMilli",
          "hiredLaborMilli",
          "unemployedLaborMilli",
          "note");

  private Rural32Hex360DaySim() {}

  /** 独立跑法入口：{@code caseDir} 下写 CSV/MD 两份同内容表。 */
  public static void run(Path caseDir) throws IOException {
    Files.createDirectories(caseDir);
    Path storeDir = caseDir.resolve("store");
    Files.createDirectories(storeDir);

    List<Map<String, String>> rows = new ArrayList<>(TICKS);
    try (Shell shell = startShell(storeDir);
        Capture settlementLog = new Capture(EconomyLog.SETTLEMENT_LOGGER_NAME);
        Capture marketLog = new Capture(EconomyLog.MARKET_LOGGER_NAME);
        // ★ D-032 RA1b2c1（RA5a）：债务土地腿的标量读数在 DEBT INFO 事件里
        //   （landTransfers + landTransferredQuantityMilliMu），不再是旧的“块数差”。
        Capture debtLog = new Capture(EconomyLog.DEBT_LOGGER_NAME)) {
      shell.coreSimos().bootstrapGenesis(Rural32HexWorld.state(MAP_ID));
      SimulationState state = replay(shell);
      Map<String, String> previousLandOwnership = landOwnership(state);
      Set<String> previousDebtIds = debtIds(state);
      boolean printedFailure = false;

      for (int attempt = 1; attempt <= TICKS; attempt++) {
        settlementLog.clear();
        marketLog.clear();
        debtLog.clear();
        long beforeHead = head(shell);
        long beforeTick = tickOf(state);
        String status = "OK";
        String failureNote = "";
        try {
          shell.advanceAndDrain(
              new AdvanceTime(
                  "cmd-d032-32hex-360d-" + attempt,
                  "corr-d032-32hex-360d-" + attempt,
                  "d032-32hex-360d",
                  MAIN,
                  new RevisionId(beforeHead),
                  new TimeRange(
                      SimosTimestamp.of(beforeTick), Optional.of(SimosTimestamp.of(attempt)))));
          state = replay(shell);
          if (head(shell) <= beforeHead || tickOf(state) != attempt) {
            status = "REJECTED";
            failureNote = "ADVANCE_NOT_COMMITTED actualTick=" + tickOf(state);
          }
        } catch (RuntimeException failure) {
          status = "REJECTED";
          failureNote = named("ADVANCE_EXCEPTION", safeText(failure));
          if (!printedFailure) {
            failure.printStackTrace(System.err);
            printedFailure = true;
          }
          state = replay(shell);
        }

        long tick = tickOf(state);
        Map<String, String> info = lastTickChain(settlementLog.messages());
        Map<String, String> executed =
            lastEventFields(settlementLog.messages(), "D032_P3_EXECUTED");
        MarketReport market = marketReport(tick, attempt);
        List<WageSettlementReadout> readouts = wageReadouts(tick, attempt);
        Map<String, String> landTrade = landTrade(marketLog.messages());
        Map<String, String> debtLandRound =
            lastEventFields(debtLog.messages(), "DEBT_FIRST_M2_ROUND");
        Map<String, String> currentLandOwnership = landOwnership(state);
        Set<String> currentDebtIds = debtIds(state);
        Map<String, String> row =
            buildRow(
                attempt,
                status,
                failureNote,
                state,
                info,
                executed,
                market,
                readouts,
                landTrade,
                debtLandRound,
                previousLandOwnership,
                currentLandOwnership,
                previousDebtIds,
                currentDebtIds);
        rows.add(row);
        previousLandOwnership = currentLandOwnership;
        previousDebtIds = currentDebtIds;
      }
    }

    Path csv = caseDir.resolve("rural-32hex-360d.csv");
    Path md = caseDir.resolve("rural-32hex-360d.md");
    writeCsv(csv, rows);
    writeMarkdown(md, rows);
    System.out.println("[d032-32hex-360d] rows=" + rows.size());
    System.out.println("[d032-32hex-360d] csv=" + csv.toAbsolutePath());
    System.out.println("[d032-32hex-360d] md=" + md.toAbsolutePath());
  }

  private static Shell startShell(Path storeDir) {
    ShellConfig defaults = ShellConfig.defaults(storeDir).withPorts(0, 0);
    ShellConfig config =
        new ShellConfig(
            storeDir,
            // ★ 每 tick 一份 checkpoint：本 sim 每 tick 都 replay 读状态；间隔 100 会让 360 tick 退化成
            //   反复重放上百个巨型变更集（实测 day 42 后单次 replay 已 >1 分钟）。
            1,
            defaults.guiPort(),
            defaults.mcpPort(),
            defaults.mcpPath(),
            defaults.mcpInitiator(),
            MAP_ID,
            defaults.bindAddress(),
            defaults.openingSnapshot(),
            defaults.worldId());
    return Shell.start(config);
  }

  private static SimulationState replay(Shell shell) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(head(shell))));
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  private static long tickOf(SimulationState state) {
    return state.meta().timestamp().tick();
  }

  private static Map<String, String> buildRow(
      int attempt,
      String status,
      String failureNote,
      SimulationState state,
      Map<String, String> info,
      Map<String, String> executed,
      MarketReport market,
      List<WageSettlementReadout> readouts,
      Map<String, String> landTrade,
      Map<String, String> debtLandRound,
      Map<String, String> previousLandOwnership,
      Map<String, String> currentLandOwnership,
      Set<String> previousDebtIds,
      Set<String> currentDebtIds) {
    SocialData social = social(state);
    ActorData actor = actor(state);
    EconomyData economy = economy(state);
    long tick = tickOf(state);

    Map<String, String> row = new LinkedHashMap<>();
    row.put("tick", Integer.toString(attempt));
    row.put("marketDay", flag(info.get("marketDay")));
    row.put("revision", Long.toString(headRevision(state)));
    row.put("status", status);
    row.put("population", Long.toString(population(social)));
    row.put("households", Integer.toString(social.households().size()));
    row.put("births", Long.toString(populationEvents(social, tick, PopulationEventType.BIRTH)));
    row.put("deaths", Long.toString(populationEvents(social, tick, PopulationEventType.DEATH)));
    row.put("moneyTotalMilli", Long.toString(moneyTotal(actor)));
    row.put("grainStockMilli", Long.toString(commodityTotal(actor, GRAIN)));
    row.put("fiberStockMilli", Long.toString(commodityTotal(actor, FIBER)));
    row.put("toolStockMilli", Long.toString(commodityTotal(actor, TOOL)));
    row.put("landTotalMilliMu", Long.toString(landTotal(state)));
    row.put("landPriceMilliPerMu", landPrice(economy));
    row.put("landTradedMilliMu", landTrade.getOrDefault("quantityMilliMu", "0"));
    int changedLand = changedHoldings(previousLandOwnership, currentLandOwnership);
    int marketLandTrades = (int) parseLongOrZero(landTrade.getOrDefault("trades", "0"));
    // ★ D-032 RA1b2c1：债务土地腿的权威读数 = DEBT INFO 的标量数量（不是“块数差”）。
    row.put(
        "landDebtTransferredMilliMu",
        debtLandRound.getOrDefault("landTransferredQuantityMilliMu", "0"));
    row.put("productionGrainMilli", info.getOrDefault("productionGrainMilli", "0"));
    row.put("productionFiberMilli", info.getOrDefault("productionFiberMilli", "0"));
    row.put("seedInputMilli", info.getOrDefault("seedInputMilli", "0"));
    row.put("marketTrades", Integer.toString(market == null ? 0 : market.fills().size()));
    row.put("marketUnfilled", Integer.toString(market == null ? 0 : market.unfilled().size()));
    row.put("grainPriceMilli", price(economy, GRAIN));
    row.put("fiberPriceMilli", price(economy, FIBER));
    row.put("wageDueMilli", Long.toString(wageSum(readouts, 0)));
    row.put("wagePaidMilli", Long.toString(wageSum(readouts, 1)));
    row.put("wageArrearsMilli", Long.toString(wageSum(readouts, 2)));
    row.put("wageDebtOutstandingMilli", Long.toString(wageDebtOutstanding(economy)));
    row.put("debtPrincipalMilli", Long.toString(debtPrincipal(economy)));
    row.put("debtContractCount", Integer.toString(economy.debtContracts().size()));
    long productionLabor = parseLongOrZero(info.getOrDefault("productionLaborMilli", "0"));
    long hiredLabor = parseLongOrZero(info.getOrDefault("hiredLaborMilli", "0"));
    row.put("productionLaborMilli", Long.toString(productionLabor));
    row.put("hiredLaborMilli", Long.toString(hiredLabor));
    row.put("unemployedLaborMilli", Long.toString(unemployedLabor(economy, productionLabor)));
    row.put(
        "note",
        note(
            status,
            failureNote,
            market,
            readouts,
            landTrade,
            executed,
            previousDebtIds,
            currentDebtIds,
            changedLand,
            marketLandTrades));
    return row;
  }

  private static String note(
      String status,
      String failureNote,
      MarketReport market,
      List<WageSettlementReadout> readouts,
      Map<String, String> landTrade,
      Map<String, String> executed,
      Set<String> previousDebtIds,
      Set<String> currentDebtIds,
      int changedLand,
      int marketLandTrades) {
    List<String> parts = new ArrayList<>();
    if ("REJECTED".equals(status)) {
      parts.add(failureNote.isBlank() ? "ADVANCE_REJECTED" : failureNote);
    }
    if (market != null) {
      parts.add("MARKET_FILLS=" + market.fills().size());
      parts.add("MARKET_UNFILLED=" + market.unfilled().size());
    }
    long arrearsCount = 0L;
    long arrearsMilli = 0L;
    for (WageSettlementReadout readout : readouts) {
      if (readout.arrearsMilli() > 0L) {
        arrearsCount++;
        arrearsMilli = saturatingAdd(arrearsMilli, readout.arrearsMilli());
      }
    }
    if (arrearsCount > 0L) {
      parts.add("WAGE_ARREARS_NEW=" + arrearsCount);
      parts.add("WAGE_ARREARS_MILLI=" + arrearsMilli);
    }
    long landTrades = parseLongOrZero(landTrade.getOrDefault("trades", "0"));
    if (landTrades > 0L) {
      parts.add("LAND_TRADE_COUNT=" + landTrades);
      parts.add("LAND_TRADE_MILLI_MU=" + landTrade.getOrDefault("quantityMilliMu", "0"));
    }
    if (changedLand > 0) {
      parts.add("LAND_HOLDING_CHANGED=" + changedLand);
    }
    if (marketLandTrades > 0) {
      parts.add("LAND_MARKET_TRADES=" + marketLandTrades);
    }
    long productionRejected = parseLongOrZero(executed.getOrDefault("rejected", "0"));
    if (productionRejected > 0L) {
      parts.add("PRODUCTION_REJECTED=" + productionRejected);
    }
    long productionClosed = parseLongOrZero(executed.getOrDefault("closed", "0"));
    if (productionClosed > 0L) {
      parts.add("PRODUCTION_CLOSED=" + productionClosed);
    }
    long newDebts = 0L;
    for (String id : currentDebtIds) {
      if (!previousDebtIds.contains(id)) {
        newDebts++;
      }
    }
    if (newDebts > 0L) {
      parts.add("DEBT_CONTRACTS_NEW=" + newDebts);
    }
    return String.join(";", parts);
  }

  private static long headRevision(SimulationState state) {
    return state.meta().ref().revision().value();
  }

  private static SocialData social(SimulationState state) {
    return ((io.mosire.simos.social.SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  private static ActorData actor(SimulationState state) {
    return ((io.mosire.simos.actor.ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static EconomyData economy(SimulationState state) {
    return ((io.mosire.simos.economy.EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static long population(SocialData social) {
    long total = 0L;
    for (Household household : social.households().values()) {
      for (long members : household.members().values()) {
        total = saturatingAdd(total, members);
      }
    }
    return total;
  }

  private static long populationEvents(SocialData social, long tick, PopulationEventType type) {
    long total = 0L;
    for (HouseholdPopulationEvent event : social.populationEvents().values()) {
      if (event.day() == tick && event.type() == type) {
        total = saturatingAdd(total, event.count());
      }
    }
    return total;
  }

  private static long moneyTotal(ActorData actor) {
    long total = 0L;
    for (HouseholdInventory account : actor.accounts().values()) {
      for (long money : account.money().values()) {
        total = saturatingAdd(total, money);
      }
    }
    return total;
  }

  private static long commodityTotal(ActorData actor, CommodityId commodity) {
    long total = 0L;
    for (HouseholdInventory account : actor.accounts().values()) {
      total = saturatingAdd(total, account.balances().getOrDefault(commodity, 0L));
    }
    return total;
  }

  /** ★ D-032 L4：LAND 总量读唯一权威 = Map arable component（经瞬态聚合账本；economy 不再持久持有 LAND）。 */
  private static long landTotal(SimulationState state) {
    long total = 0L;
    for (LandHolding holding :
        ArableSupplySnapshots.landLedgerFrom(state, "Rural32Hex360DaySim")
            .holdingsOrEmpty()
            .values()) {
      total = saturatingAdd(total, holding.quantityMilliMu());
    }
    return total;
  }

  private static Optional<MarketTable> firstMarket(EconomyData economy) {
    return economy.marketTables().entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.comparing(zone -> zone.value())))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  private static String landPrice(EconomyData economy) {
    Optional<MarketTable> market = firstMarket(economy);
    if (market.isEmpty()) {
      return "";
    }
    OptionalLong price = market.orElseThrow().landPrice();
    return price.isPresent() ? Long.toString(price.getAsLong()) : "";
  }

  private static String price(EconomyData economy, CommodityId commodity) {
    return firstMarket(economy).map(table -> Long.toString(table.priceOf(commodity))).orElse("0");
  }

  private static long wageSum(List<WageSettlementReadout> readouts, int column) {
    long total = 0L;
    for (WageSettlementReadout readout : readouts) {
      total =
          saturatingAdd(
              total,
              switch (column) {
                case 0 -> readout.dueMilli();
                case 1 -> readout.paidMoneyMilli();
                default -> readout.arrearsMilli();
              });
    }
    return total;
  }

  private static long wageDebtOutstanding(EconomyData economy) {
    long total = 0L;
    for (DebtContract contract : economy.debtContracts().values()) {
      if (contract.source() == DebtSource.WAGE_ARREARS) {
        total = saturatingAdd(total, contract.principal());
      }
    }
    return total;
  }

  private static long debtPrincipal(EconomyData economy) {
    long total = 0L;
    for (DebtContract contract : economy.debtContracts().values()) {
      total = saturatingAdd(total, contract.principal());
    }
    return total;
  }

  private static long unemployedLabor(EconomyData economy, long productionLabor) {
    long budget = 0L;
    for (HouseholdEconomy household : economy.classes().values()) {
      budget = saturatingAdd(budget, household.laborMilli());
    }
    return Math.max(0L, budget - productionLabor);
  }

  /**
   * ★ D-032 RA1b2b/RA1b2c1：LAND 持有改成按 {@code (hex, owner, quantityMilliMu)} 的标量视图。
   *
   * <p>键 = {@code LandHoldingKey} 规范串（hex → owner），值 = 千分亩数量。旧实现按 LAND {@code AssetShareId} +
   * owner/operator 记录，已随标量切源删除。
   */
  private static Map<String, String> landOwnership(SimulationState state) {
    Map<String, String> ownership = new LinkedHashMap<>();
    for (LandHolding holding :
        ArableSupplySnapshots.landLedgerFrom(state, "Rural32Hex360DaySim")
            .holdingsOrEmpty()
            .values()) {
      ownership.put(holding.key().toString(), Long.toString(holding.quantityMilliMu()));
    }
    return ownership;
  }

  /** 统计前/后两次标量持有视图中「数量发生变化」的 (hex, owner) 条数（不是旧“块数”）。 */
  private static int changedHoldings(Map<String, String> before, Map<String, String> after) {
    int changed = 0;
    for (Map.Entry<String, String> entry : after.entrySet()) {
      String previous = before.get(entry.getKey());
      if (previous != null && !previous.equals(entry.getValue())) {
        changed++;
      }
    }
    return changed;
  }

  private static Set<String> debtIds(SimulationState state) {
    return new LinkedHashSet<>(
        economy(state).debtContracts().keySet().stream().map(id -> id.value()).toList());
  }

  private static MarketReport marketReport(long tick, int attempt) {
    if (tick != attempt) {
      return null;
    }
    Optional<MarketReport> report = MarketReportFeed.last(MAP_ID, tick);
    return report.filter(value -> value.day() == tick).orElse(null);
  }

  private static List<WageSettlementReadout> wageReadouts(long tick, int attempt) {
    if (tick != attempt) {
      return List.of();
    }
    return WageSettlementReadoutFeed.last(MAP_ID, tick).orElse(List.of());
  }

  private static Map<String, String> lastTickChain(List<String> messages) {
    return lastEventFields(messages, "D032_TICK_CHAIN");
  }

  private static Map<String, String> lastEventFields(List<String> messages, String event) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      String line = messages.get(i);
      if (line.contains("event=" + event + " ")) {
        return parseFields(line);
      }
    }
    return Map.of();
  }

  /** ★ D-032 RA1b2c2：LAND 成交按标量 `quantityMilliMu` 汇总；`trades` 只是成交笔数（不是块数）。 */
  private static Map<String, String> landTrade(List<String> messages) {
    long trades = 0L;
    long quantity = 0L;
    for (String line : messages) {
      if (line.contains("event=LAND_TRADE_SETTLED ")) {
        Map<String, String> fields = parseFields(line);
        trades++;
        quantity = saturatingAdd(quantity, parseLongOrZero(fields.get("quantityMilliMu")));
      }
    }
    Map<String, String> result = new LinkedHashMap<>();
    result.put("trades", Long.toString(trades));
    result.put("quantityMilliMu", Long.toString(quantity));
    return result;
  }

  private static Map<String, String> parseFields(String line) {
    Map<String, String> fields = new LinkedHashMap<>();
    int start = line.indexOf("event=");
    if (start < 0) {
      return fields;
    }
    String body = line.substring(start);
    for (String token : body.split(" ")) {
      int separator = token.indexOf('=');
      if (separator > 0 && separator < token.length() - 1) {
        fields.put(token.substring(0, separator), token.substring(separator + 1));
      }
    }
    return fields;
  }

  private static String flag(String value) {
    return value == null ? "" : value;
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

  private static String named(String event, String detail) {
    return detail == null || detail.isBlank() ? event : event + "=" + detail;
  }

  private static String safeText(Throwable failure) {
    String text = failure.getClass().getSimpleName();
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return text;
    }
    return text + ":" + message.replace('\n', ' ').replace('\r', ' ').replace(';', ',');
  }

  private static long saturatingAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  private static void writeCsv(Path file, List<Map<String, String>> rows) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append(String.join(",", COLUMNS)).append(System.lineSeparator());
    for (Map<String, String> row : rows) {
      List<String> cells = new ArrayList<>(COLUMNS.size());
      for (String column : COLUMNS) {
        cells.add(csvCell(row.getOrDefault(column, "")));
      }
      text.append(String.join(",", cells)).append(System.lineSeparator());
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private static void writeMarkdown(Path file, List<Map<String, String>> rows) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("| ").append(String.join(" | ", COLUMNS)).append(" |").append('\n');
    text.append("|");
    for (int i = 0; i < COLUMNS.size(); i++) {
      text.append("---|");
    }
    text.append('\n');
    for (Map<String, String> row : rows) {
      text.append("|");
      for (String column : COLUMNS) {
        String value = row.getOrDefault(column, "").replace("|", "/").replace('\n', ' ');
        text.append(' ').append(value).append(" |");
      }
      text.append('\n');
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private static String csvCell(String value) {
    if (value.indexOf(',') < 0
        && value.indexOf('"') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  /** 只收日志、不改 logger 级别（JAVA_OPTS 的 DEBUG 由调用方决定；本类不降级也不强行升级）。 */
  private static final class Capture implements AutoCloseable {

    private final LoggerContext context;
    private final AbstractConfiguration configuration;
    private final String loggerName;
    private final String appenderName;
    private final Collecting appender;

    private Capture(String loggerName) {
      this.loggerName = loggerName;
      this.context = (LoggerContext) LogManager.getContext(false);
      this.configuration = (AbstractConfiguration) context.getConfiguration();
      this.appenderName = "d032-capture-" + Integer.toHexString(System.identityHashCode(this));
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
