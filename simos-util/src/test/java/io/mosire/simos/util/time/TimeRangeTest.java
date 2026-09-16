package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec §十-D5：有效区间左闭右开；`to` 缺省 = 无上界。 */
class TimeRangeTest {

  @Test
  void intervalIsHalfOpen() {
    TimeRange range = new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(20)));
    assertThat(range.contains(SimosTimestamp.of(9))).isFalse();
    assertThat(range.contains(SimosTimestamp.of(10))).isTrue(); // 左闭
    assertThat(range.contains(SimosTimestamp.of(19))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(20))).isFalse(); // 右开
  }

  @Test
  void absentToMeansUnbounded() {
    TimeRange range = TimeRange.since(SimosTimestamp.of(10));
    assertThat(range.contains(SimosTimestamp.of(10))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(1_000_000))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(9))).isFalse();
  }

  @Test
  void emptyOrInvertedIntervalIsRejected() {
    assertThatThrownBy(
            () -> new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(10))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(5))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullPartsAreRejected() {
    // `to` 的 null ≠ `Optional.empty()`：前者是"忘了写"，后者才是"无上界"，不得混同。
    assertThatNullPointerException().isThrownBy(() -> new TimeRange(null, Optional.empty()));
    // 第二条必须钉消息：删掉 requireNonNull(to) 后，下一行 to.ifPresent(...) 照样抛 NPE
    // （类型无从判别），只有 NPE 的消息能证明这条护栏在场（house style 同身份包的 M4 处置）。
    assertThatNullPointerException()
        .isThrownBy(() -> new TimeRange(SimosTimestamp.of(1), null))
        .withMessage("to");
  }
}
