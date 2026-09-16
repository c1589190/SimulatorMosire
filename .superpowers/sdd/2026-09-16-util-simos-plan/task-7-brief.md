### Task 7: `TemporalSeries` 与 `SegmentedSeries`

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/TemporalSeries.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/Segment.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/Event.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/EventMode.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/SegmentedSeries.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/TemporalSeriesTest.java`

**Interfaces:**
- Consumes: `SimosTimestamp`（Task 4）
- Produces: `TemporalSeries<T>{valueAt(SimosTimestamp), segments(), events()}`；`Segment<T>(SimosTimestamp from, T value)`；`Event<T>(SimosTimestamp at, T value, EventMode mode)`；`EventMode{ADD, SET}`；`SegmentedSeries.of(List<Segment<T>>, List<Event<T>>, BinaryOperator<T>)`

**本节的四条时间语义**（spec §七，逐条可测）：段边界**左闭右开**；anchor 之前**向前恒定延拓**；同刻**先切段、再施加事件**；同刻多事件按**插入序**。**不做插值**——段是阶跃常量。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §七：四条时间语义逐条钉死。 */
class TemporalSeriesTest {

  @Test
  void segmentBoundariesAreHalfOpen() {
    TemporalSeries<Long> series = series(List.of(segment(0, 100L), segment(10, 200L)), List.of());
    assertThat(series.valueAt(SimosTimestamp.of(9))).isEqualTo(100L);
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(200L); // 切换点归新段
    assertThat(series.valueAt(SimosTimestamp.of(1_000))).isEqualTo(200L); // 最后一段延伸到无穷
  }

  @Test
  void beforeTheFirstSegmentTheValueIsExtendedBackwardsConstantly() {
    TemporalSeries<Long> series = series(List.of(segment(10, 5L)), List.of());
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(5L);
    assertThat(series.valueAt(SimosTimestamp.of(0))).isEqualTo(5L);
    assertThat(series.valueAt(SimosTimestamp.of(-99))).isEqualTo(5L);
  }

  @Test
  void atTheSwitchPointTheSegmentIsAppliedBeforeTheEvents() {
    TemporalSeries<Long> series =
        series(List.of(segment(0, 100L), segment(10, 200L)), List.of(event(10, 5L, EventMode.ADD)));
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(205L); // 先切到 200，再加 5
  }

  @Test
  void addAccumulatesAndSetOverrides() {
    TemporalSeries<Long> additive =
        series(
            List.of(segment(0, 100L)),
            List.of(event(3, 5L, EventMode.ADD), event(5, 7L, EventMode.ADD)));
    assertThat(additive.valueAt(SimosTimestamp.of(2))).isEqualTo(100L);
    assertThat(additive.valueAt(SimosTimestamp.of(3))).isEqualTo(105L);
    assertThat(additive.valueAt(SimosTimestamp.of(5))).isEqualTo(112L);

    TemporalSeries<Long> overridden =
        series(List.of(segment(0, 100L)), List.of(event(5, 42L, EventMode.SET)));
    assertThat(overridden.valueAt(SimosTimestamp.of(5))).isEqualTo(42L);
  }

  @Test
  void eventsAtTheSameMomentApplyInInsertionOrder() {
    TemporalSeries<Long> additiveThenSet =
        series(
            List.of(segment(0, 100L)),
            List.of(event(5, 10L, EventMode.ADD), event(5, 0L, EventMode.SET)));
    assertThat(additiveThenSet.valueAt(SimosTimestamp.of(5))).isEqualTo(0L);

    TemporalSeries<Long> setThenAdditive =
        series(
            List.of(segment(0, 100L)),
            List.of(event(5, 0L, EventMode.SET), event(5, 10L, EventMode.ADD)));
    assertThat(setThenAdditive.valueAt(SimosTimestamp.of(5))).isEqualTo(10L);
  }

  @Test
  void futureEventsAreNotApplied() {
    TemporalSeries<Long> series =
        series(List.of(segment(0, 100L)), List.of(event(5, 10L, EventMode.ADD)));
    assertThat(series.valueAt(SimosTimestamp.of(4))).isEqualTo(100L);
    assertThat(series.valueAt(SimosTimestamp.of(5))).isEqualTo(110L);
  }

  @Test
  void addEventsWithoutAdditionFailAtConstruction() {
    assertThatThrownBy(
            () ->
                SegmentedSeries.of(
                    List.of(segment(0, 100L)), List.of(event(5, 10L, EventMode.ADD)), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("addition");
    // 没有 ADD 事件时 addition 可省（SET 不需要算术）
    assertThat(
            SegmentedSeries.of(
                    List.of(segment(0, 100L)), List.of(event(5, 42L, EventMode.SET)), null)
                .valueAt(SimosTimestamp.of(5)))
        .isEqualTo(42L);
  }

  @Test
  void malformedSeriesAreRejectedAtConstruction() {
    assertThatThrownBy(() -> SegmentedSeries.of(List.of(), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SegmentedSeries.of(List.of(segment(10, 1L), segment(10, 2L)), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class); // 同刻两段
    assertThatThrownBy(
            () ->
                SegmentedSeries.of(
                    List.of(segment(0, 1L)),
                    List.of(event(5, 1L, EventMode.SET), event(3, 2L, EventMode.SET)),
                    null))
        .isInstanceOf(IllegalArgumentException.class); // 事件时刻回退
  }

  @Test
  void segmentsAndEventsAreDefensivelyCopied() {
    List<Segment<Long>> mutableSegments = new ArrayList<>(List.of(segment(0, 100L)));
    TemporalSeries<Long> series = SegmentedSeries.of(mutableSegments, List.of(), null);
    mutableSegments.clear();
    assertThat(series.segments()).hasSize(1);
    assertThatThrownBy(() -> series.segments().add(segment(1, 1L)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static TemporalSeries<Long> series(List<Segment<Long>> segments, List<Event<Long>> events) {
    return SegmentedSeries.of(segments, events, Long::sum);
  }

  private static Segment<Long> segment(long from, long value) {
    return new Segment<>(SimosTimestamp.of(from), value);
  }

  private static Event<Long> event(long at, long value, EventMode mode) {
    return new Event<>(SimosTimestamp.of(at), value, mode);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test`
Expected: 编译失败——`cannot find symbol: class TemporalSeries`

- [ ] **Step 3: 实现协议与数据载体**

`TemporalSeries.java`：

```java
package io.mosire.simos.util.time;

import java.util.List;

/**
 * 时态序列：分段常量 + 离散事件（总纲 §4.7）。
 *
 * <p>**不做插值**：段内恒定，只在段边界或事件处跳变。连续变化（如人口那种积分型序列）由
 * SocialSimos 自己实现本接口，本模块只保证四条时间语义被它继承（spec §七）。
 */
public interface TemporalSeries<T> {

  /** 即时计算，不物化中间点。 */
  T valueAt(SimosTimestamp t);

  /** 分段常量，按 `from` 严格升序。 */
  List<Segment<T>> segments();

  /** 离散跳变，按插入序（同刻多事件即"插入序"）。 */
  List<Event<T>> events();
}
```

`Segment.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;

/** 一段阶跃常量：自 `from` 起取值 `value`，直到下一段的 `from`（左闭右开，spec §七）。 */
public record Segment<T>(SimosTimestamp from, T value) {

  public Segment {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(value, "value");
  }
}
```

`Event.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;

/** 一个离散跳变：时刻 `at`、值 `value`、方式 `mode`（spec §七）。 */
public record Event<T>(SimosTimestamp at, T value, EventMode mode) {

  public Event {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(mode, "mode");
  }
}
```

`EventMode.java`：

```java
package io.mosire.simos.util.time;

/** 事件施加方式：`ADD` 需调用方注入加法，`SET` 直接覆盖（spec §七）。 */
public enum EventMode {
  ADD,
  SET
}
```

- [ ] **Step 4: 实现 `SegmentedSeries`**

```java
package io.mosire.simos.util.time;

import java.util.List;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 通用实现：分段常量 + 离散事件（spec §七）。
 *
 * <p>四条时间语义：段边界左闭右开；anchor 之前向前恒定延拓；同刻先切段再施加事件；同刻多事件按插入序。
 * `ADD` 的算术由调用方以 {@link BinaryOperator} 注入——Util 不把 `T` 限制成数字。
 */
public final class SegmentedSeries<T> implements TemporalSeries<T> {

  private final List<Segment<T>> segments;
  private final List<Event<T>> events;
  private final BinaryOperator<T> addition;

  private SegmentedSeries(
      List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition) {
    this.segments = segments;
    this.events = events;
    this.addition = addition;
  }

  /**
   * @param segments 至少一段（第一段即 anchor），按 `from` 严格升序
   * @param events 按 `at` 非递减；同刻多事件按给定顺序施加
   * @param addition `ADD` 的加法；序列含 `ADD` 事件时**不得为 null**
   */
  public static <T> SegmentedSeries<T> of(
      List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition) {
    Objects.requireNonNull(segments, "segments");
    Objects.requireNonNull(events, "events");
    List<Segment<T>> copiedSegments = List.copyOf(segments);
    List<Event<T>> copiedEvents = List.copyOf(events);
    if (copiedSegments.isEmpty()) {
      throw new IllegalArgumentException("序列至少一个段（第一段即 anchor）");
    }
    requireStrictlyAscending(copiedSegments);
    requireNonDecreasing(copiedEvents);
    if (addition == null && copiedEvents.stream().anyMatch(e -> e.mode() == EventMode.ADD)) {
      throw new IllegalArgumentException("含 ADD 事件的序列必须在构造期提供 addition（spec §七）");
    }
    return new SegmentedSeries<>(copiedSegments, copiedEvents, addition);
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

  @Override
  public List<Segment<T>> segments() {
    return segments;
  }

  @Override
  public List<Event<T>> events() {
    return events;
  }

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
            "事件必须按 at 非递减给出（同刻多事件才谈得上插入序）："
                + events.get(i - 1).at()
                + " → "
                + events.get(i).at());
      }
    }
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test`
Expected: PASS（9 个用例）

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/time/ simos-util/src/test/java/io/mosire/simos/util/time/
git diff --cached --stat
git commit -m "feat(util): TemporalSeries 与 SegmentedSeries，四条时间语义可测（M1 Task 7）"
```

---

