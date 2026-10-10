package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>W1（2026-10-10）：市场轮统计簿的只读查询面</b> —— 按 <b>(市场, 商品)</b> 读最后一轮 <b>D / S / 成交量 / 均价 /
 * 未满足量</b>、按<b>市场</b>读本轮与本/上周期<b>成交额</b>（{@code EconomyData.marketRoundStats}）。
 *
 * <p>★★ <b>W2-fix7 / D4：成交额读口分两栏</b> —— {@code …Turnover} = <b>名义货值</b>（全额货款，含赊购部分）、 {@code
 * …CashTurnover} = <b>实付现金</b>（只数真钱）。改前只有"实付现金"一栏却叫"成交额"，在几乎全赊购的世界里 把它读成市场规模会差两个数量级（验收报告 §3 D4）——
 * 两栏并存，谁也不冒充谁。
 *
 * <p>★★ <b>三个窗口的语义（读口不再解释第二遍）</b>：{@link #lastRound} = 最近一轮明细；{@link #cycleToDate} = 本周期累计； {@link
 * #previousCycle} = 上周期累计（关账轮轮转 ⇒ **上周期快照**，W4 的市场区排序量）。★ 本账是**持久**的（随 {@code EconomyData} 进
 * Codec/ChangeSet）⇒ 重启后仍在（"进程内、重启即失"是改动前 {@code MarketReport} 的老毛病）。
 *
 * <p>★ <b>本类不写状态、不落盘</b>：全是纯函数，不认识 codec/变更集，也不发日志（读口不做副作用）。
 */
public final class MarketRoundStatsBook {

  private MarketRoundStatsBook() {}

  /** 市场 id 规范序的全部市场（读数与日志的迭代序是内容的纯函数）。 */
  public static List<MarketZoneId> marketIds(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    return MarketTables.canonicalIds(view.marketTables());
  }

  /** 某个市场的统计簿（没有过任何一轮 ⇒ 空）。 */
  public static Optional<MarketRoundStats> of(EconomyDayView view, MarketZoneId marketId) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(marketId, "marketId");
    return Optional.ofNullable(view.marketRoundStats().get(marketId));
  }

  /** ★★ <b>最后一轮</b>：某市场某商品的读数（没有过任何一轮 / 该商品本轮没出现 ⇒ 空）。 */
  public static Optional<CommodityRound> lastRound(
      EconomyDayView view, MarketZoneId marketId, CommodityId commodity) {
    return of(view, marketId).map(stats -> stats.lastRound().get(commodity));
  }

  /** ★ 本周期累计：某市场某商品（语义见类注）。 */
  public static Optional<CommodityRound> cycleToDate(
      EconomyDayView view, MarketZoneId marketId, CommodityId commodity) {
    return of(view, marketId).map(stats -> stats.cycleToDate().get(commodity));
  }

  /** ★ 上周期累计：某市场某商品（W4 的"上周期快照"读口）。 */
  public static Optional<CommodityRound> previousCycle(
      EconomyDayView view, MarketZoneId marketId, CommodityId commodity) {
    return of(view, marketId).map(stats -> stats.previousCycle().get(commodity));
  }

  /** ★ 最后一轮的绝对日（没有过任何一轮 ⇒ 空）。 */
  public static OptionalLong lastRoundDay(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream().mapToLong(MarketRoundStats::lastRoundDay).findFirst();
  }

  /**
   * ★★ <b>逐市场"最后一轮"成交额</b>（毫；口径见 {@link MarketRoundStats} 的类注）—— <b>名义货值</b> （全额货款，含赊购部分）。★ W2-fix7
   * / D4 起本读口的语义由"实付现金"改为"名义货值"； 现金口径在 {@link #lastRoundCashTurnover}。
   */
  public static OptionalLong lastRoundTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream()
        .mapToLong(MarketRoundStats::lastRoundTurnoverMilli)
        .findFirst();
  }

  /** ★ W2-fix7 / D4：逐市场"最后一轮"**实付现金**（毫；只数真钱，赊购部分不进这一栏）。 */
  public static OptionalLong lastRoundCashTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream()
        .mapToLong(MarketRoundStats::lastRoundCashTurnoverMilli)
        .findFirst();
  }

  /** ★ 本周期累计成交额（**名义货值**；毫；W4 的"上周期成交额"排序量在 {@link #previousCycleTurnover}）。 */
  public static OptionalLong cycleTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream().mapToLong(MarketRoundStats::cycleTurnoverMilli).findFirst();
  }

  /** ★ W2-fix7 / D4：本周期累计**实付现金**（毫）。 */
  public static OptionalLong cycleCashTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream()
        .mapToLong(MarketRoundStats::cycleCashTurnoverMilli)
        .findFirst();
  }

  /**
   * ★★ <b>上周期累计成交额</b>（毫）：W4 的市场区排序量（S-4/N-1 的防自指输入）。
   *
   * <p>★ W2-fix7 / D4 起它是<b>名义货值</b>（全额货款，含赊购部分）—— 改前只数现金时，几乎全赊购的世界里 排序会退化成"谁付过现金"； 现金口径在 {@link
   * #previousCycleCashTurnover}。
   */
  public static OptionalLong previousCycleTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream()
        .mapToLong(MarketRoundStats::previousCycleTurnoverMilli)
        .findFirst();
  }

  /** ★ W2-fix7 / D4：上周期累计**实付现金**（毫）。 */
  public static OptionalLong previousCycleCashTurnover(EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).stream()
        .mapToLong(MarketRoundStats::previousCycleCashTurnoverMilli)
        .findFirst();
  }

  /** ★ 一个市场的"最后一轮"整张明细（按商品 id 规范序）。 */
  public static Map<CommodityId, CommodityRound> lastRoundDetail(
      EconomyDayView view, MarketZoneId marketId) {
    return of(view, marketId).map(MarketRoundStats::lastRound).orElseGet(Map::of);
  }

  /** ★ 逐个市场一行人类可读摘要（日志/探针用；§一.9 的日志纪律：只输出具名计数）。 */
  public static List<String> describe(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    if (view.marketTables().isEmpty()) {
      return List.of();
    }
    List<MarketZoneId> ids = new ArrayList<>(view.marketTables().keySet());
    ids.sort(Comparator.comparing(MarketZoneId::value));
    List<String> lines = new ArrayList<>(ids.size());
    for (MarketZoneId marketId : ids) {
      MarketTable table = view.marketTables().get(marketId);
      MarketRoundStats stats = view.marketRoundStats().get(marketId);
      lines.add(
          marketId.value()
              + "("
              + table.numeraire().value()
              + "/"
              + table.hexes().size()
              + "格) "
              + (stats == null ? "无统计" : stats.describe()));
    }
    return List.copyOf(lines);
  }

  /** ★ 全部市场"最后一轮成交额（名义货值）"的一览（市场 id 规范序；只读派生，不落盘）。 */
  public static Map<MarketZoneId, Long> lastRoundTurnoverByMarket(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    Map<MarketZoneId, Long> byMarket = new LinkedHashMap<>();
    for (MarketZoneId marketId : marketIds(view)) {
      byMarket.put(marketId, lastRoundTurnover(view, marketId).orElse(0L));
    }
    return java.util.Collections.unmodifiableMap(byMarket);
  }

  /** ★ W2-fix7 / D4：全部市场"最后一轮实付现金"的一览（市场 id 规范序；只读派生，不落盘）。 */
  public static Map<MarketZoneId, Long> lastRoundCashTurnoverByMarket(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    Map<MarketZoneId, Long> byMarket = new LinkedHashMap<>();
    for (MarketZoneId marketId : marketIds(view)) {
      byMarket.put(marketId, lastRoundCashTurnover(view, marketId).orElse(0L));
    }
    return java.util.Collections.unmodifiableMap(byMarket);
  }
}
