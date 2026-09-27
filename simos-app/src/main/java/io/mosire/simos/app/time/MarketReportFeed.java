package io.mosire.simos.app.time;

import io.mosire.simos.economy.time.MarketReport;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>进程内的"最近一轮市场报告"投递点（M2.7 的读侧接缝）</b>—— 把 {@code EconomyDayStepper} 会话里那份 {@link MarketReport}
 * 交给读口（GUI / MCP），<b>不进 {@code EconomyData}、不落盘</b>。
 *
 * <p>★★ <b>为什么需要它</b>：{@code lastMarketReport} 住在一次推进创建的 {@code EconomyDayStepper} 里，推进结束就被丢；
 * 而逐格读口（{@code ApiViews.economyHex}）只能看见 {@code SimulationState}（快照）—— 没有这个投递点，读口永远拿不到
 * 成交量/到货价/运费/损耗/未成交原因/未利用运力（M2.7 的一半读数）。
 *
 * <p>★★ <b>如实边界（读侧必须照写的三条）</b>：
 *
 * <ol>
 *   <li><b>重启即失</b>：它是 JVM 进程内的 {@link ConcurrentHashMap}，不落盘、不进变更集；重启 / 换进程后读口只能报 "报告读不到"（具名，绝不填
 *       0）；
 *   <li><b>只在同一 tick 内可信</b>：{@link #last(String, long)} 要求读数的 tick 与投递时的 tick 逐值相同 —— 从旧 revision
 *       分叉/回读旧档时，进程里那份报告属于另一条时间线，宁可不给；
 *   <li><b>一份报告覆盖一个 map</b>：键是 {@code mapId}（一个 JVM 里可能有多个世界/夹具）。
 * </ol>
 *
 * <p>★ 投递时机：两个经济推进参与者在每个世界日 {@code stepper.step(day)} 之后投递 {@code stepper.lastMarketReport()} ——
 * 没开市的日子投递的是"最近一轮"的报告（{@code day} 更新到当天），因此读口在同一 tick 内总能拿到"最近一轮"， 而不是"只有开市当天才有"。
 */
public final class MarketReportFeed {

  /** 键 = mapId；值 = 最近一次投递（含投递时的世界日）。 */
  private static final Map<String, Entry> BY_MAP = new ConcurrentHashMap<>();

  private MarketReportFeed() {}

  /**
   * 投递一份报告（可为空 = 这个世界至今没有开过市）。
   *
   * @param mapId 世界 id；不得为空白
   * @param report 最近一轮报告；{@link Optional#empty()} = 没有（或明确清空）
   * @param tick 投递时的世界日（读侧必须逐值相同才认）
   */
  public static void publish(String mapId, Optional<MarketReport> report, long tick) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("MarketReportFeed.mapId 不得为空白");
    }
    Objects.requireNonNull(report, "report");
    BY_MAP.put(mapId, new Entry(report, tick));
  }

  /**
   * 读最近一轮报告：<b>只在读数 tick 与投递 tick 逐值相同时返回</b>（见类注的"同一 tick 内可信"）。
   *
   * @param mapId 世界 id
   * @param tick 当前 state 的世界日
   * @return 最近一轮报告；没有 / tick 不匹配 ⇒ {@link Optional#empty()}（读口把它报成"读不到"，不是 0）
   */
  public static Optional<MarketReport> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank()) {
      return Optional.empty();
    }
    Entry entry = BY_MAP.get(mapId);
    if (entry == null || entry.tick != tick) {
      return Optional.empty();
    }
    return entry.report;
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  private record Entry(Optional<MarketReport> report, long tick) {}
}
