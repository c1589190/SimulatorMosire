### Task 10: 往返不变式框架（`verify` 包，M1 的硬判据）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsDriftTest.java`

**Interfaces:**
- Consumes: `Snapshot` / `ChangeSet` / `RevisionId`（Task 6）
- Produces: `RoundTripAssertions.assertRoundTrip(S base, S target, BiFunction<S,S,C> diff, BiFunction<C,S,S> apply)`；`RoundTripAssertions.assertSnapshotRoundTrip(...)`（同形，`S extends Snapshot`）

**工具必须在 main 源码里**（M2~M4 都要用），因此**不能依赖 JUnit**——失败以 `AssertionError` 抛出。

- [ ] **Step 1: 写失败测试（框架本身）**

```java
package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §九：正常往返通过、盖错版本戳抛错、破裂时给得出三份 toString。 */
class RoundTripAssertionsTest {

  @Test
  void aCorrectRoundTripPasses() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatCode(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, ToySnapshot::diff, ToySnapshot::apply))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base, target, ToySnapshot::diff, ToySnapshot::apply))
        .doesNotThrowAnyException();
  }

  @Test
  void aMisStampedChangeSetIsRejected() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base,
                    target,
                    (b, t) -> new ToyChangeSet(new RevisionId(999), t.timestamp(), t.alpha(), t.beta()),
                    ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("baseRevision");
  }

  @Test
  void aBrokenRoundTripReportsAllThreeStates() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base,
                    target,
                    (b, t) ->
                        new ToyChangeSet(b.ref().revision(), t.timestamp(), t.alpha(), b.beta()),
                    ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("target")
        .hasMessageContaining("actual");
  }

  /** 玩具快照：证明框架不关心 `S` 是什么，只要它是 record 并实现 `Snapshot`。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static ToyChangeSet diff(ToySnapshot base, ToySnapshot target) {
      return new ToyChangeSet(
          base.ref().revision(), target.timestamp(), target.alpha(), target.beta());
    }

    static ToySnapshot apply(ToyChangeSet changeSet, ToySnapshot base) {
      return new ToySnapshot(
          new StateRef(base.ref().branch(), changeSet.baseRevision()),
          changeSet.timestamp(),
          base.namespace(),
          changeSet.alpha(),
          changeSet.beta());
    }
  }

  record ToyChangeSet(RevisionId baseRevision, SimosTimestamp timestamp, int alpha, int beta)
      implements ChangeSet {}

  private static StateRef ref(long revision) {
    return new StateRef(new BranchId("main"), new RevisionId(revision));
  }
}
```

- [ ] **Step 2: 写失败测试（**护栏自证**，spec §9.3 + G13）**

```java
package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/**
 * G13（护栏必须自证）：**故意漏字段的变更集必须让往返断言响**。
 *
 * <p>这条路一旦断，往返护栏就只是装饰——L1 事故的四个漂移字段正是死在"没有用例证明它抓得住"上。
 */
class RoundTripAssertionsDriftTest {

  @Test
  void aChangeSetThatDropsAFieldMustBeCaught() {
    DriftingSnapshot base = new DriftingSnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    DriftingSnapshot target = new DriftingSnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, DriftingSnapshot::diff, DriftingSnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("beta");
  }

  /** 故意漂移的玩具快照：`beta` 在快照里有、在变更集里没有——L1 事故的最小重演。 */
  record DriftingSnapshot(
      StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static DriftingChangeSet diff(DriftingSnapshot base, DriftingSnapshot target) {
      return new DriftingChangeSet(base.ref().revision(), target.timestamp(), target.alpha()); // 漏了 beta
    }

    static DriftingSnapshot apply(DriftingChangeSet changeSet, DriftingSnapshot base) {
      return new DriftingSnapshot(
          new StateRef(base.ref().branch(), changeSet.baseRevision()),
          changeSet.timestamp(),
          base.namespace(),
          changeSet.alpha(),
          base.beta()); // beta 只能沿袭 base —— 这正是漂移的形态
    }
  }

  record DriftingChangeSet(RevisionId baseRevision, SimosTimestamp timestamp, int alpha)
      implements ChangeSet {}

  private static StateRef ref(long revision) {
    return new StateRef(new BranchId("main"), new RevisionId(revision));
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test`
Expected: 编译失败——`cannot find symbol: class RoundTripAssertions`

- [ ] **Step 4: 实现 `RoundTripAssertions`**

```java
package io.mosire.simos.util.verify;

import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 往返不变式断言（铁律 5 / spec §九）。
 *
 * <p>为什么是测试而不是编译期：状态 record 加字段会让 `apply` 的全参重建**编译失败**（覆盖得了），
 * 但 `ChangeSet` 的字段清单漏字段**不会**编译失败——L1 事故里 `MapData` 加字段根本不会让 `MapDiff` 红。
 * 漏掉的那一半由 record 的 `equals()` 兜住：只要断言写成
 * `apply(diff(base, target), base).equals(target)`，任何漏在 ChangeSet/diff/apply 里的字段都会让测试红，
 * 不需要反射，也不随字段增长而失效。
 *
 * <p>本类位于 **main** 源码（M2~M4 都要用），因此不依赖 JUnit：失败以 {@link AssertionError} 抛出。
 */
public final class RoundTripAssertions {

  private RoundTripAssertions() {}

  /** 通用：任何状态类型（模块快照或整个 `SimulationState`）。 */
  public static <S, C extends ChangeSet> void assertRoundTrip(
      S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply) {
    C changeSet = Objects.requireNonNull(diff.apply(base, target), "diff 返回 null");
    checkApplied(base, target, changeSet, apply);
  }

  /** 快照专用：额外要求变更集**相对于它被施加的那个 base**——防止 diff 盖错版本戳。 */
  public static <S extends Snapshot, C extends ChangeSet> void assertSnapshotRoundTrip(
      S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply) {
    C changeSet = Objects.requireNonNull(diff.apply(base, target), "diff 返回 null");
    RevisionId declared = changeSet.baseRevision();
    RevisionId actual = base.ref().revision();
    if (!actual.equals(declared)) {
      throw new AssertionError(
          "变更集必须相对它被施加的 base：changeSet.baseRevision()="
              + declared
              + "，base.ref().revision()="
              + actual);
    }
    checkApplied(base, target, changeSet, apply);
  }

  private static <S, C extends ChangeSet> void checkApplied(
      S base, S target, C changeSet, BiFunction<C, S, S> apply) {
    S applied = Objects.requireNonNull(apply.apply(changeSet, base), "apply 返回 null");
    if (!target.equals(applied)) {
      throw new AssertionError(
          "往返不变式破裂：apply(diff(base, target), base) 与 target 不等。\n"
              + "  base      = "
              + base
              + "\n  target    = "
              + target
              + "\n  actual    = "
              + applied
              + "\n  changeSet = "
              + changeSet
              + "\n提示：ChangeSet（或 diff/apply 本身）漏了 target 比 base 多出来的字段——这正是 L1 事故的形态。");
    }
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test`
Expected: PASS（3 + 1 个用例；漂移用例证明护栏真的会响）

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/verify/ simos-util/src/test/java/io/mosire/simos/util/verify/
git diff --cached --stat
git commit -m "feat(util): 往返不变式框架 + 漂移自证（M1 Task 10）"
```

---

