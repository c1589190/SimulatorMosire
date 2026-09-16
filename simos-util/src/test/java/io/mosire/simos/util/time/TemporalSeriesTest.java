package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
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
    // 断言钉到**消息**而非只钉异常类型：三条守卫的消息各不相同，
    // 只断类型的话把三条守卫互换实现也照绿（T6 先例）。
    assertThatThrownBy(() -> SegmentedSeries.of(List.of(), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("anchor");
    assertThatThrownBy(
            () -> SegmentedSeries.of(List.of(segment(10, 1L), segment(10, 2L)), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("严格升序"); // 同刻两段
    assertThatThrownBy(
            () ->
                SegmentedSeries.of(
                    List.of(segment(0, 1L)),
                    List.of(event(5, 1L, EventMode.SET), event(3, 2L, EventMode.SET)),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非递减"); // 事件时刻回退
  }

  @Test
  void segmentsAndEventsAreDefensivelyCopied() {
    List<Segment<Long>> mutableSegments = new ArrayList<>(List.of(segment(0, 100L)));
    List<Event<Long>> mutableEvents = new ArrayList<>(List.of(event(5, 10L, EventMode.ADD)));
    TemporalSeries<Long> series = SegmentedSeries.of(mutableSegments, mutableEvents, ADDITION);
    mutableSegments.clear();
    mutableEvents.clear();
    // 两半都测：调用方的可变对象被隔离 + 取出来的列表不可改。
    assertThat(series.segments()).hasSize(1);
    assertThat(series.events()).hasSize(1);
    assertThat(series.valueAt(SimosTimestamp.of(5))).isEqualTo(110L); // clear 后行为不变
    assertThatThrownBy(() -> series.segments().add(segment(1, 1L)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> series.events().add(event(6, 1L, EventMode.ADD)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullArgumentsAreRejectedWithFieldLevelMessages() {
    // 字段级消息：`requireNonNull` 的字段串若写错位，只断异常类型的写法不会转红。
    assertThatThrownBy(() -> SegmentedSeries.of(null, List.of(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("segments");
    assertThatThrownBy(() -> SegmentedSeries.of(List.of(segment(0, 1L)), null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("events");
    assertThatThrownBy(() -> new Segment<Long>(null, 1L))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("from");
    assertThatThrownBy(() -> new Segment<>(SimosTimestamp.of(0), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("value");
    assertThatThrownBy(() -> new Event<Long>(null, 1L, EventMode.SET))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("at");
    assertThatThrownBy(() -> new Event<>(SimosTimestamp.of(0), null, EventMode.SET))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("value");
    assertThatThrownBy(() -> new Event<Long>(SimosTimestamp.of(0), 1L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mode");
    assertThatThrownBy(() -> series(List.of(segment(0, 1L)), List.of()).valueAt(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("t");
  }

  @Test
  void aSharedAdditionInstanceMakesStructurallyIdenticalSeriesEqual() {
    // spec §七：`addition` 是普通组件，record 的 `equals` 对函数只能按**身份**比较。
    // 用同一个共享实例构建的两个同构序列必须相等——M2 起的往返断言拿这个当判据（铁律 5）。
    List<Segment<Long>> segments = List.of(segment(0, 100L));
    List<Event<Long>> events = List.of(event(5, 10L, EventMode.ADD));
    assertThat(SegmentedSeries.of(segments, events, ADDITION))
        .isEqualTo(SegmentedSeries.of(segments, events, ADDITION));
  }

  @Test
  void twoEquivalentButDistinctLambdasMakeSeriesUnequal() {
    // G13 自证：这条**故意**把陷阱钉住——它证明 `equals` 真的在比较 `addition`。
    // 若有人把 `equals` 改成忽略 `addition`（本项目最贵教训 MapDiff 的形态），本用例立刻转红。
    BinaryOperator<Long> first = capturingAdder();
    BinaryOperator<Long> second = capturingAdder();
    assertThat(first).isNotSameAs(second); // 先自证前提：两个实例确实不同
    List<Segment<Long>> segments = List.of(segment(0, 100L));
    List<Event<Long>> events = List.of(event(5, 10L, EventMode.ADD));
    assertThat(SegmentedSeries.of(segments, events, first))
        .isNotEqualTo(SegmentedSeries.of(segments, events, second));
  }

  @Test
  void setOnlySeriesWithNullAdditionStillCompareByValue() {
    // `addition` 为 null 的 SET-only 序列不受身份比较影响（spec §七）。
    assertThat(
            SegmentedSeries.of(List.of(segment(0, 1L)), List.of(event(5, 2L, EventMode.SET)), null))
        .isEqualTo(
            SegmentedSeries.of(
                List.of(segment(0, 1L)), List.of(event(5, 2L, EventMode.SET)), null));
  }

  /**
   * 捕获局部变量的 lambda 每次求值都产生**新实例**（JLS §15.27.2），故两次调用必得两个不同实例—— 不依赖 JIT 对非捕获 lambda
   * 的按调用点缓存（那会让同一调用点的两次求值返回同一实例，前提落空）。
   */
  private static BinaryOperator<Long> capturingAdder() {
    long zero = 0L;
    return (a, b) -> a + b + zero;
  }

  /** 共享的加法实例：spec §七 要求含 `ADD` 的序列一律用模块级 `static final` 常量。 */
  private static final BinaryOperator<Long> ADDITION = Long::sum;

  private static TemporalSeries<Long> series(
      List<Segment<Long>> segments, List<Event<Long>> events) {
    return SegmentedSeries.of(segments, events, ADDITION);
  }

  private static Segment<Long> segment(long from, long value) {
    return new Segment<>(SimosTimestamp.of(from), value);
  }

  private static Event<Long> event(long at, long value, EventMode mode) {
    return new Event<>(SimosTimestamp.of(at), value, mode);
  }
}
