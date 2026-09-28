package io.mosire.simos.app.time;

import io.mosire.simos.economy.time.ProductionLedger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★ <b>进程内的"最近一天结算账本"投递点（S3 的读侧接缝）</b>—— 与 {@link MarketReportFeed} 同款、同边界： {@code
 * ProductionLedger} 是**当日瞬态**（{@code EconomyDayStepper.step} 交回、协调器落账后即丢）， 而读口（GUI/MCP）只能看见 {@code
 * SimulationState} 快照 ⇒ 没有这个投递点，租/工资欠款与逐规则欠额读数永远拿不到。
 *
 * <p>★★ <b>如实边界（与 MarketReportFeed 逐条同义）</b>：不落盘、重启即失；只在同一 tick 内可信；一份覆盖一个 map。
 * 读不到时由读口报"读不到"（具名），<b>绝不填 0</b>。
 */
public final class EconomyDayFeed {

  /** 键 = mapId；值 = 最近一次投递（含投递时的世界日）。 */
  private static final Map<String, Entry> BY_MAP = new ConcurrentHashMap<>();

  private EconomyDayFeed() {}

  /** 投递某天结账后的账本（可为空 = 这个世界还没结算过）。 */
  public static void publish(String mapId, Optional<ProductionLedger> ledger, long tick) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("EconomyDayFeed.mapId 不得为空白");
    }
    Objects.requireNonNull(ledger, "ledger");
    BY_MAP.put(mapId, new Entry(ledger, tick));
  }

  /** 读最近一天的账本：只在读数 tick 与投递 tick 逐值相同时返回（分叉/回读旧档时宁可不给）。 */
  public static Optional<ProductionLedger> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank()) {
      return Optional.empty();
    }
    Entry entry = BY_MAP.get(mapId);
    if (entry == null || entry.tick != tick) {
      return Optional.empty();
    }
    return entry.ledger;
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  private record Entry(Optional<ProductionLedger> ledger, long tick) {}
}
