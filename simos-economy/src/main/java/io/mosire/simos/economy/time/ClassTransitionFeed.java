package io.mosire.simos.economy.time;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * ★★ <b>关账日阶层写回的进程内审计投递点</b>—— 把 {@link ClassTransition} 交给读口（{@code ApiViews}）。
 *
 * <p>★★ <b>为什么需要它</b>：写回只改 {@code HouseholdEconomy.view.stratum}；写完之后状态里只剩"新阶层"，从 {@code SimulationState}
 * 反查不到"从哪一档跳过来"。没有这份审计，读口只能看到"现在是什么"，看不到"变化发生了"。
 *
 * <p>★★ <b>如实边界</b>：进程内、不落盘、重启即失；按 mapId 分开，按关账日（绝对 world day）取 {@code floorEntry(readTick)} ——
 * 读到的是"读这一刻已经发生过的最近一次关账"的写回。写回本身（{@code HouseholdEconomy.view}）在快照里持久，重放/换进程后阶层的当前值仍可读，只是"从哪跳来"的审计读不到。
 *
 * <p>★ <b>为什么用 ConcurrentSkipListMap + floorEntry 而不是像 MarketReportFeed 那样要求 tick 严格相等</b>：一次
 * {@code simos.advance} 可能跨过多个关账日（例如 30→360 一跳），严格相等会让中间关账日的写回审计全部消失；floorEntry 读的是"截至当前 tick
 * 最近一次关账"， 既不会读到未来、也保留跨日推进的可观察性。每个 map 只留最近 {@value #MAX_CLOSE_DAYS_PER_MAP} 个关账日，避免进程内无限累积。
 */
public final class ClassTransitionFeed {

  /** 每个 map 保留的最近关账日数（超出的最旧项在 publish 时淘汰）。 */
  public static final int MAX_CLOSE_DAYS_PER_MAP = 8;

  private static final Map<String, NavigableMap<Long, List<ClassTransition>>> BY_MAP =
      new ConcurrentHashMap<>();

  private ClassTransitionFeed() {}

  /**
   * 投递某个关账日的全部写回（无写回也要投递空表：读口才知道"这次关账没有变化"，而不是"没读到"）。
   *
   * @param mapId 世界 id；不得为空白
   * @param day 关账日（绝对 world day）；不得为负
   * @param transitions 当次关账的写回；每一项的 {@code day} 必须等于 {@code day}
   */
  public static void publish(String mapId, long day, List<ClassTransition> transitions) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("ClassTransitionFeed.mapId 不得为空白");
    }
    if (day < 0L) {
      throw new IllegalArgumentException("ClassTransitionFeed.day 不得为负: " + day);
    }
    Objects.requireNonNull(transitions, "transitions");
    for (ClassTransition transition : transitions) {
      Objects.requireNonNull(transition, "transitions 不得含 null");
      if (transition.day() != day) {
        throw new IllegalArgumentException(
            "ClassTransitionFeed 的每条写回 day 必须与投递 day 相同: " + transition + " / " + day);
      }
    }
    NavigableMap<Long, List<ClassTransition>> byDay =
        BY_MAP.computeIfAbsent(mapId, ignored -> new ConcurrentSkipListMap<>());
    byDay.put(day, List.copyOf(transitions));
    while (byDay.size() > MAX_CLOSE_DAYS_PER_MAP) {
      byDay.remove(byDay.firstKey());
    }
  }

  /**
   * 读"截至读数 tick 已经发生过的最近一次关账"的写回审计。
   *
   * @param mapId 世界 id
   * @param tick 当前 state 的世界日
   * @return 最近一次不晚于 {@code tick} 的关账写回；没有投递 / {@code tick < 0} ⇒ {@link Optional#empty()}
   */
  public static Optional<Snapshot> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank() || tick < 0L) {
      return Optional.empty();
    }
    NavigableMap<Long, List<ClassTransition>> byDay = BY_MAP.get(mapId);
    if (byDay == null) {
      return Optional.empty();
    }
    Map.Entry<Long, List<ClassTransition>> entry = byDay.floorEntry(tick);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(new Snapshot(entry.getKey(), entry.getValue()));
  }

  /** 清掉一个 map 的投递（服务收工 / 测试夹具用；不是业务路径）。 */
  public static void clear(String mapId) {
    if (mapId != null) {
      BY_MAP.remove(mapId);
    }
  }

  /** 一个关账日的写回快照（{@code day} = 关账日；{@code transitions} 保序不可变）。 */
  public record Snapshot(long day, List<ClassTransition> transitions) {

    public Snapshot {
      if (day < 0L) {
        throw new IllegalArgumentException("ClassTransitionFeed.Snapshot.day 不得为负: " + day);
      }
      Objects.requireNonNull(transitions, "transitions");
      transitions = List.copyOf(transitions);
    }
  }
}
