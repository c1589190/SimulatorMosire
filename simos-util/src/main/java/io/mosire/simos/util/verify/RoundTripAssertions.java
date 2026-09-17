package io.mosire.simos.util.verify;

import io.mosire.simos.util.state.ChangeSet;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 往返不变式断言（铁律 5 / spec §九）。
 *
 * <p>为什么是测试而不是编译期：状态 record 加字段会让 `apply` 的全参重建**编译失败**（覆盖得了）， 但 `ChangeSet` 的字段清单漏字段**不会**编译失败——L1
 * 事故里 `MapData` 加字段根本不会让 `MapDiff` 红。 漏掉的那一半由 record 的 `equals()` 兜住：只要断言写成 `apply(diff(base,
 * target), base).equals(target)`，任何漏在 ChangeSet/diff/apply 里的字段都会让测试红， 不需要反射，也不随字段增长而失效。
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
