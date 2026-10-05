package io.mosire.simos.economy.time;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>P2-B：最近一次"家户劳动利润率排队"结果的进程内投递点</b>（读侧接缝；不进 {@code EconomyData}、不落盘）。
 *
 * <p>★ <b>为什么需要它</b>：排队结果（{@link LaborQueueBook.Plan}）住在一次日结算的局部变量里，结算结束就丢；
 * 而“这家户这几小时为什么给这个 mode”是读口/诊断要回答的问题。投递点是同一形状的既有接缝（照 {@code MarketReportFeed}），
 * 只把最近一日的报告留到读侧，重启即失、只在同一 tick 内可信、一份报告覆盖一个 map。
 *
 * <p><b>边界（与 MarketReportFeed 逐条同款）</b>：
 *
 * <ol>
 *   <li><b>重启即失</b>：进程内 {@link ConcurrentHashMap}，不进变更集、不落盘；
 *   <li><b>只在同一 tick 内可信</b>：{@link #last(String, long)} 要求读数 tick 与投递 tick 逐值相同；
 *   <li><b>一份报告覆盖一个 map</b>（键 = mapId）。
 * </ol>
 */
public final class LaborAllocationFeed {

  private static final Map<String, Entry> BY_MAP = new ConcurrentHashMap<>();

  private LaborAllocationFeed() {}

  /** 投递一份报告（旧档/mode 未接线的世界不投递 ⇒ 读侧报"读不到"，不是"分配了 0"）。 */
  public static void publish(String mapId, LaborQueueReport report) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("LaborAllocationFeed.mapId 不得为空白");
    }
    Objects.requireNonNull(report, "report");
    BY_MAP.put(mapId, new Entry(report));
  }

  /** 读最近一次报告：只在读数的 day 与投递的 day 逐值相同时返回（见类注的"同一 tick 内可信"）。 */
  public static Optional<LaborQueueReport> last(String mapId, long day) {
    if (mapId == null || mapId.isBlank()) {
      return Optional.empty();
    }
    Entry entry = BY_MAP.get(mapId);
    if (entry == null || entry.report.day() != day) {
      return Optional.empty();
    }
    return Optional.of(entry.report);
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  private record Entry(LaborQueueReport report) {}
}
