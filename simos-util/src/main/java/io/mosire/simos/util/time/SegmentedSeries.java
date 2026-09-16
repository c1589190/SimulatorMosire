package io.mosire.simos.util.time;

import java.util.List;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 通用实现：分段常量 + 离散事件（spec §七）。
 *
 * <p>四条时间语义：段边界左闭右开；anchor 之前向前恒定延拓；同刻先切段再施加事件；同刻多事件按插入序。 `ADD` 的算术由调用方以 {@link BinaryOperator}
 * 注入——Util 不把 `T` 限制成数字。
 *
 * <p><b>是 record，不是 `final class`</b>：`TemporalSeries` 是状态类型，往返断言（铁律 5）要拿它当判据， 故 `equals` 一律由
 * record 提供、禁手写（spec §十一）。代价是 `addition` 按**身份**比较——函数没有结构相等。
 */
public record SegmentedSeries<T>(
    List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition)
    implements TemporalSeries<T> {

  /**
   * 紧凑构造器即校验点：record 的规范构造器是公开的，绕过 {@link #of} 直接 {@code new} 也受同一套守卫约束。
   *
   * @throws IllegalArgumentException 段为空、段未严格升序、事件时刻回退，或含 `ADD` 事件却未给 `addition`
   */
  public SegmentedSeries {
    Objects.requireNonNull(segments, "segments");
    Objects.requireNonNull(events, "events");
    segments = List.copyOf(segments);
    events = List.copyOf(events);
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("序列至少一个段（第一段即 anchor）");
    }
    requireStrictlyAscending(segments);
    requireNonDecreasing(events);
    if (addition == null && events.stream().anyMatch(e -> e.mode() == EventMode.ADD)) {
      throw new IllegalArgumentException("含 ADD 事件的序列必须在构造期提供 addition（spec §七）");
    }
  }

  /**
   * @param segments 至少一段（第一段即 anchor），按 `from` 严格升序
   * @param events 按 `at` 非递减；同刻多事件按给定顺序施加
   * @param addition `ADD` 的加法；序列含 `ADD` 事件时**不得为 null**
   */
  public static <T> SegmentedSeries<T> of(
      List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition) {
    return new SegmentedSeries<>(segments, events, addition);
  }

  @Override
  public T valueAt(SimosTimestamp t) {
    Objects.requireNonNull(t, "t");
    T value = baseValueAt(t);
    for (Event<T> event : events) {
      if (event.at().compareTo(t) <= 0) {
        value =
            event.mode() == EventMode.ADD ? addition.apply(value, event.value()) : event.value();
      }
    }
    return value;
  }

  // `segments()` / `events()` 由 record 自动生成，已满足 TemporalSeries 的契约，无需手写覆盖
  // （手写覆盖在这里只会是重复代码，且会给 SpotBugs/评审增加无谓的读数）。

  /** 段值：`t` 早于第一段则为第一段的值（向前恒定延拓）。 */
  private T baseValueAt(SimosTimestamp t) {
    T value = segments.get(0).value();
    for (Segment<T> segment : segments) {
      if (segment.from().compareTo(t) > 0) {
        break;
      }
      value = segment.value(); // t == from 即新段生效（左闭右开）
    }
    return value;
  }

  private static <T> void requireStrictlyAscending(List<Segment<T>> segments) {
    for (int i = 1; i < segments.size(); i++) {
      if (segments.get(i).from().compareTo(segments.get(i - 1).from()) <= 0) {
        throw new IllegalArgumentException(
            "段必须按 from 严格升序（同刻两段无法判定谁生效）："
                + segments.get(i - 1).from()
                + " → "
                + segments.get(i).from());
      }
    }
  }

  private static <T> void requireNonDecreasing(List<Event<T>> events) {
    for (int i = 1; i < events.size(); i++) {
      if (events.get(i).at().compareTo(events.get(i - 1).at()) < 0) {
        throw new IllegalArgumentException(
            "事件必须按 at 非递减给出（同刻多事件才谈得上插入序）：" + events.get(i - 1).at() + " → " + events.get(i).at());
      }
    }
  }
}
