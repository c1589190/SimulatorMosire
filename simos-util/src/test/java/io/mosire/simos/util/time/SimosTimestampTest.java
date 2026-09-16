package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** spec §五：tick 是第一序、plus 保留 label、类型本身不提供复写入口。 */
class SimosTimestampTest {

  @Test
  void orderingUsesTickOnly() {
    assertThat(SimosTimestamp.of(3)).isEqualByComparingTo(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(2)).isLessThan(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(0, "第 0 日")).isGreaterThan(SimosTimestamp.of(-1));
  }

  @Test
  void plusAdvancesAndRewindsWhileKeepingTheLabel() {
    SimosTimestamp t = SimosTimestamp.of(10, "第 10 日");
    assertThat(t.plus(5)).isEqualTo(SimosTimestamp.of(15, "第 10 日"));
    assertThat(t.plus(-7)).isEqualTo(SimosTimestamp.of(3, "第 10 日"));
    assertThat(t).isEqualTo(SimosTimestamp.of(10, "第 10 日")); // 原实例不变
  }

  @Test
  void equalityStillIncludesTheLabelWhereasOrderingDoesNot() {
    // 计划期新增细则 2：同 tick 的两种写法排序相等但不 equals，判"同刻"用 compareTo == 0。
    assertThat(SimosTimestamp.of(3)).isEqualByComparingTo(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(3)).isNotEqualTo(SimosTimestamp.of(3, "第 3 日"));
  }

  @Test
  void nullCalendarLabelIsRejected() {
    // label 是 Optional 而不是裸引用：null 表示"忘了写"，必须在构造期点名拒绝。
    assertThatNullPointerException().isThrownBy(() -> new SimosTimestamp(1, null));
  }

  @Test
  void sortingWorks() {
    assertThat(
            Stream.of(SimosTimestamp.of(3), SimosTimestamp.of(1), SimosTimestamp.of(2))
                .sorted()
                .map(SimosTimestamp::tick)
                .collect(java.util.stream.Collectors.toList()))
        .containsExactly(1L, 2L, 3L);
  }
}
