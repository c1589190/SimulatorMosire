package io.mosire.simos.economy.time;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * ★★ <b>R4-E2b：候选采用评估结果的进程内投递点</b>—— 把 {@link EntryOutcome} 交给读口（{@code ApiViews.economyHex}）。
 *
 * <p>★★ <b>为什么进程内不落盘</b>：进入评估每天都会发生（表非空时），而它的结果只是"这次为什么没采用"的归因；落盘会把它变成第二份状态， 还要牵动
 * ChangeSet/Codec/往返——计划明说"不新增 {@code EconomyData} 组件"。故按 {@code mapId} + 日结算日放在进程内，重启即失（与 {@link
 * ClassTransitionFeed} 同款如实边界）。
 *
 * <p>★ <b>读法</b>：{@link #last(String, long)} 取"截至读数 tick 最近一次日结算日"的结果（{@link
 * NavigableMap#floorEntry}）， 因此跨日推进（30→360 一跳）之后仍能读到最近一次评估；每个 map 只留最近 {@value #MAX_DAYS_PER_MAP}
 * 个日结算日，避免进程内无限累积。
 *
 * <p>★ <b>空表也投递空表</b>：读口才能区分"这个日结算日没有需求/候选可评估"与"还没投递过"。
 */
public final class EntryOutcomeFeed {

  /** 每个 map 保留的最近日结算日数（超出的最旧项在 publish 时淘汰）。 */
  public static final int MAX_DAYS_PER_MAP = 8;

  private static final Map<String, NavigableMap<Long, List<EntryOutcome>>> BY_MAP =
      new ConcurrentHashMap<>();

  private EntryOutcomeFeed() {}

  /**
   * 投递某个日结算日的全部评估结果（没有结果也要投递空表，见类注）。
   *
   * @param mapId 世界 id；不得为空白
   * @param day 日结算日（绝对 world day；≥ 1）
   * @param outcomes 当次评估结果；每一项的 {@code day} 必须等于 {@code day}
   */
  public static void publish(String mapId, long day, List<EntryOutcome> outcomes) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("EntryOutcomeFeed.mapId 不得为空白");
    }
    if (day < 1L) {
      throw new IllegalArgumentException("EntryOutcomeFeed.day 必须 ≥ 1: " + day);
    }
    Objects.requireNonNull(outcomes, "outcomes");
    for (EntryOutcome outcome : outcomes) {
      Objects.requireNonNull(outcome, "outcomes 不得含 null");
      if (outcome.day() != day) {
        throw new IllegalArgumentException(
            "EntryOutcomeFeed 的每条结果 day 必须与投递 day 相同: " + outcome.day() + " / " + day);
      }
    }
    NavigableMap<Long, List<EntryOutcome>> byDay =
        BY_MAP.computeIfAbsent(mapId, ignored -> new ConcurrentSkipListMap<>());
    byDay.put(day, List.copyOf(outcomes));
    while (byDay.size() > MAX_DAYS_PER_MAP) {
      byDay.remove(byDay.firstKey());
    }
  }

  /**
   * 读"截至读数 tick 已经发生过的最近一次日结算"的评估结果。
   *
   * @param mapId 世界 id
   * @param tick 当前 state 的世界日
   * @return 最近一次不晚于 {@code tick} 的评估快照；没有投递 / {@code tick < 1} ⇒ {@link Optional#empty()}
   */
  public static Optional<Snapshot> last(String mapId, long tick) {
    if (mapId == null || mapId.isBlank() || tick < 1L) {
      return Optional.empty();
    }
    NavigableMap<Long, List<EntryOutcome>> byDay = BY_MAP.get(mapId);
    if (byDay == null) {
      return Optional.empty();
    }
    Map.Entry<Long, List<EntryOutcome>> entry = byDay.floorEntry(tick);
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

  /** 一个日结算日的评估快照（{@code day} = 日结算日；{@code outcomes} 保序不可变）。 */
  public record Snapshot(long day, List<EntryOutcome> outcomes) {

    public Snapshot {
      if (day < 1L) {
        throw new IllegalArgumentException("EntryOutcomeFeed.Snapshot.day 必须 ≥ 1: " + day);
      }
      Objects.requireNonNull(outcomes, "outcomes");
      outcomes = List.copyOf(outcomes);
    }
  }
}
