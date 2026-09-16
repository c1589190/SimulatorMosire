### Task 4: 时间基础（`SimosTimestamp` / `TimeRange`）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/SimosTimestampTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/TimeRangeTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `SimosTimestamp(long tick, Optional<String> calendarLabel)` + `of(long)` / `of(long, String)` / `plus(long)`；`TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to)` + `since(SimosTimestamp)` / `contains(SimosTimestamp)`

**计划期新增的两条细则**（spec §五 只写了 `tick` 是第一序与"左闭右开"，未写这两条，Task 11 回填 spec）：

1. `TimeRange.to` 必须**严格晚于** `from`（左闭右开区间不得为空），否则构造期抛 `IllegalArgumentException`。
2. `equals` 含 `calendarLabel`，`compareTo` 只看 `tick`——两者口径不同是**有意**的（§十一 禁手写 `equals`）。判"同刻"一律用 `compareTo == 0`。

- [ ] **Step 1: 写失败测试**

`SimosTimestampTest.java`：

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
  void sortingWorks() {
    assertThat(
            Stream.of(SimosTimestamp.of(3), SimosTimestamp.of(1), SimosTimestamp.of(2))
                .sorted()
                .map(SimosTimestamp::tick)
                .collect(java.util.stream.Collectors.toList()))
        .containsExactly(1L, 2L, 3L);
  }
}
```

`TimeRangeTest.java`：

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
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
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test`
Expected: 编译失败——`cannot find symbol: class SimosTimestamp`

- [ ] **Step 3: 实现两个 record**

`SimosTimestamp.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 模拟时间戳：`tick` 是第一序，`calendarLabel` 仅作展示（spec §五）。时间戳与版本正交
 * （`RevisionId` 管数据版本），两把尺子互不换算。
 *
 * <p><b>排序与相等的口径不同，这是有意的</b>：{@link #compareTo} 只看 `tick`，而 record 的
 * {@link #equals} 含全部组件。判"同刻"一律用 {@code compareTo == 0}，不要用 `equals`。
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
```

`TimeRange.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 有效区间：**左闭右开** `[from, to)`；`to` 缺省表示无上界（spec §十-D5）。
 *
 * <p>`to` 必须严格晚于 `from`——空区间是配置错误，不给它静默存在的机会。
 */
public record TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to) {

  public TimeRange {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    to.ifPresent(
        end -> {
          if (end.compareTo(from) <= 0) {
            throw new IllegalArgumentException(
                "TimeRange 的 to 必须晚于 from（左闭右开区间不得为空）：" + from + " .. " + end);
          }
        });
  }

  /** 自 `from` 起、无上界的区间。 */
  public static TimeRange since(SimosTimestamp from) {
    return new TimeRange(from, Optional.empty());
  }

  /** `t` 是否落在 `[from, to)` 内（同刻判定用 {@code compareTo}，见 {@link SimosTimestamp}）。 */
  public boolean contains(SimosTimestamp t) {
    if (t.compareTo(from) < 0) {
      return false;
    }
    return to.map(end -> t.compareTo(end) < 0).orElse(true);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/time/ simos-util/src/test/java/io/mosire/simos/util/time/
git diff --cached --stat
git commit -m "feat(util): SimosTimestamp 与 TimeRange（M1 Task 4）"
```

---

