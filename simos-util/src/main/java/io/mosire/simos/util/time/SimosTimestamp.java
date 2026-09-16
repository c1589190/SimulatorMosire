package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 模拟时间戳：`tick` 是第一序，`calendarLabel` 仅作展示（spec §五）。时间戳与版本正交 （`RevisionId` 管数据版本），两把尺子互不换算。
 *
 * <p><b>排序与相等的口径不同，这是有意的</b>：{@link #compareTo} 只看 `tick`，而 record 的 {@link #equals} 含全部组件。判"同刻"一律用
 * {@code compareTo == 0}，不要用 `equals`。
 *
 * <p>没有 setter：推进只经 {@link #plus(long)}（总纲 §4.9）。
 */
public record SimosTimestamp(long tick, Optional<String> calendarLabel)
    implements Comparable<SimosTimestamp> {

  public SimosTimestamp {
    Objects.requireNonNull(calendarLabel, "calendarLabel");
  }

  public static SimosTimestamp of(long tick) {
    return new SimosTimestamp(tick, Optional.empty());
  }

  public static SimosTimestamp of(long tick, String calendarLabel) {
    return new SimosTimestamp(tick, Optional.of(calendarLabel));
  }

  /** 推进（负值即回拨），保留 label。 */
  public SimosTimestamp plus(long delta) {
    return new SimosTimestamp(tick + delta, calendarLabel);
  }

  @Override
  public int compareTo(SimosTimestamp other) {
    return Long.compare(tick, other.tick);
  }
}
