package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 判据一（M3 spec §3.6 的冻结手算表）+ 构造期校验（R4）。
 *
 * <p>★ 每个期望值都是从 spec 的表里**抄**来的，不是跑出来的：跑出来对不上 ⇒ 实现错了。
 */
class PopulationSeriesTest {

  /** 种子例：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries seed() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  @Test
  void seedExampleMatchesTheHandComputedTable() {
    assertThat(seed().valueAt(SimosTimestamp.of(53)))
        .as("[0,20)=+4000→14000；[20,45)=+3500→17500；t=45 −800→16700；[45,53)=+1336")
        .isEqualTo(18036L);
  }

  @Test
  void segmentBoundaryAndEventAtTheSameTick() {
    PopulationSeries series =
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 1000L),
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(SimosTimestamp.of(0), 0.1),
                    new Segment<>(SimosTimestamp.of(10), 0.2)),
                List.of(),
                null),
            List.of(new Event<>(SimosTimestamp.of(10), 100L, EventMode.ADD)));

    assertThat(series.valueAt(SimosTimestamp.of(20)))
        .as("t=10 必须先切段（速率 0.2 生效）再施事件：2100 + round(2100×0.2×10) = 6300")
        .isEqualTo(6300L);
  }

  @Test
  void multipleEventsAtTheSameTickFollowInsertionOrder() {
    PopulationSeries series =
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 1000L),
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(SimosTimestamp.of(0), 0.1),
                    new Segment<>(SimosTimestamp.of(10), 0.2)),
                List.of(),
                null),
            List.of(
                new Event<>(SimosTimestamp.of(10), 100L, EventMode.ADD),
                new Event<>(SimosTimestamp.of(10), 5000L, EventMode.SET)));

    assertThat(series.valueAt(SimosTimestamp.of(20)))
        .as("同刻按插入序：[5000 + round(5000×0.2×10)] = 15000（换成 SET 在前就得到另一个数）")
        .isEqualTo(15000L);
  }

  @Test
  void beforeAnchorIsConstant() {
    assertThat(seed().valueAt(SimosTimestamp.of(-5)))
        .as("anchor 之前向前恒定延拓：不增长、不施事件")
        .isEqualTo(10000L);
  }

  @Test
  void segmentsIsExactlyTheAnchor() {
    assertThat(seed().segments()).as("唯一的分段常量声明就是 anchor（spec 偏离 1）").hasSize(1);
    assertThat(seed().segments().get(0).from()).isEqualTo(SimosTimestamp.of(0));
  }

  // ── R4：构造期校验 ────────────────────────────────────────────────

  @Test
  void growthFirstSegmentStartsAfterAnchorThrows() {
    assertThatThrownBy(
            () ->
                new PopulationSeries(
                    new Segment<>(SimosTimestamp.of(5), 1000L),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(SimosTimestamp.of(10), 0.02)), List.of(), null),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("growth");
  }

  @Test
  void growthCarryingEventsIsRejected() {
    assertThatThrownBy(
            () ->
                new PopulationSeries(
                    new Segment<>(SimosTimestamp.of(0), 1000L),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(SimosTimestamp.of(0), 0.02)),
                        List.of(new Event<>(SimosTimestamp.of(3), 0.05, EventMode.SET)),
                        null),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("growth");
  }

  @Test
  void eventsMustBeNonDecreasing() {
    PopulationSeries base = seed();
    assertThatThrownBy(() -> base.withEvent(new Event<>(SimosTimestamp.of(1), 1L, EventMode.ADD)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非递减");
  }

  @Test
  void nullComponentsAreRejected() {
    assertThatThrownBy(() -> new PopulationSeries(null, seed().growth(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationSeries(seed().anchor(), null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationSeries(seed().anchor(), seed().growth(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withGrowthSegmentAndWithEventReturnNewValues() {
    PopulationSeries longer = seed().withGrowthSegment(SimosTimestamp.of(100), 0.5);
    assertThat(longer.growth().segments()).hasSize(4);
    assertThat(seed().growth().segments()).as("record 值语义：旧值不变").hasSize(3);
    assertThat(seed().valueAt(SimosTimestamp.of(0))).isEqualTo(10000L);

    PopulationSeries withEvent =
        seed().withEvent(new Event<>(SimosTimestamp.of(46), 100L, EventMode.ADD));
    assertThat(withEvent.events()).hasSize(2);
    assertThat(seed().events()).hasSize(1);
  }
}
