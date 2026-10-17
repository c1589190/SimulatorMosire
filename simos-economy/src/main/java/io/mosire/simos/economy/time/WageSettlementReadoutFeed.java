package io.mosire.simos.economy.time;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>D-032 W4：最近一日 W3 工资结算读数的进程内只读投递点</b>。
 *
 * <p>{@link WageSettlementReadout} 是日结算的局部产物；GUI/MCP 读口只能看见 {@code SimulationState}。本类型与 {@code
 * MarketReportFeed} 同形：<b>不落盘、不进变更集、重启即失</b>，且要求读数的 tick 与投递 tick 逐值相同。
 *
 * <p>★★ <b>不重算</b>：列表原样来自 {@link WageSettlement.Result#readouts()}，本类不构造第二份支付/抵物/债读数。
 */
public final class WageSettlementReadoutFeed {

  private static final Map<String, Entry> BY_MAP = new ConcurrentHashMap<>();

  private WageSettlementReadoutFeed() {}

  /** 投递某一日的 W3 读数（空列表 = 该日没有工资义务，是合法读数）。 */
  public static void publish(String mapId, long day, List<WageSettlementReadout> readouts) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("WageSettlementReadoutFeed.mapId 不得为空白");
    }
    if (day < 0L) {
      throw new IllegalArgumentException("WageSettlementReadoutFeed.day 不得为负: " + day);
    }
    Objects.requireNonNull(readouts, "readouts（无工资义务给空表）");
    BY_MAP.put(mapId, new Entry(day, List.copyOf(readouts)));
  }

  /** 最近一日读数：只在读口 tick 与投递 tick 逐值相同时返回。 */
  public static Optional<List<WageSettlementReadout>> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank()) {
      return Optional.empty();
    }
    Entry entry = BY_MAP.get(mapId);
    if (entry == null || entry.day != tick) {
      return Optional.empty();
    }
    return Optional.of(entry.readouts);
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  private record Entry(long day, List<WageSettlementReadout> readouts) {}
}
