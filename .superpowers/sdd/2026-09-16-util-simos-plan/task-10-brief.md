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
    // 断言必须钉到**版本戳守卫自己的文案**，不能钉 "baseRevision"：盖错戳时 `apply` 会照用那个错戳，
    // 于是兜底的往返破裂消息也会抛 AssertionError，而它拼进去的 `ToyChangeSet` 是 record，
    // toString 形如 `ToyChangeSet[baseRevision=999, ...]` —— **同样含 "baseRevision"**。
    // 钉 "baseRevision" 的话，删掉版本戳守卫本用例照样绿，等于空转护栏（T7 的 "t" 同一形态）。
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base,
                    target,
                    (b, t) ->
                        new ToyChangeSet(new RevisionId(999), t.timestamp(), t.alpha(), t.beta()),
                    ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("必须相对它被施加的 base");
  }

  @Test
  void aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages() {
    // G13 自证：两条 `requireNonNull` 各自都要有能证明它会响的用例。
    // 断言用**完整短语**而非单词或单字符——单字符 needle 会被 JDK 21 的热心 NPE 消息偶然满足（T7 教训）。
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, (ToySnapshot b, ToySnapshot t) -> null, ToySnapshot::apply))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("diff 返回 null");
    // 删掉 apply 的守卫不会抛 NPE 而是走到 `target.equals(null)` 为假、抛 AssertionError 并指错方向——
    // 故这里同时钉住异常类型与消息，两者任一被改都会转红。
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, ToySnapshot::diff, (ToyChangeSet c, ToySnapshot b) -> null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("apply 返回 null");
    // `diff 返回 null` 这条守卫在**两个公开方法里各写了一遍**，上面那条只盖住了 `assertRoundTrip` 的那一份：
    // 删掉 `assertSnapshotRoundTrip` 里的那份，全套用例照样绿——这正是本里程碑刚清理过的
    // "同一个 `FacetRegistry` 里 `facetNames()` 钉住了、`queryAll()` 漏了"的同一形态（**同文件内的不对称即是证据**）。
    // 删掉这份守卫的实现会走到 `changeSet.baseRevision()`，抛的是热心 NPE（消息里是 `changeSet`），
    // 不含 "diff 返回 null"，故本断言转红。
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base, target, (ToySnapshot b, ToySnapshot t) -> null, ToySnapshot::apply))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("diff 返回 null");
  }

  @Test
  void aBrokenRoundTripReportsAllThreeStates() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    // **控制器修正（T10 评审判 I-1，详见下方"控制器修正说明 ②"）**：原写的
    // `.hasMessageContaining("target")` 是**空转的**，`base` 那一行则**完全没有 needle**。
    ToyChangeSet broken =
        new ToyChangeSet(base.ref().revision(), target.timestamp(), target.alpha(), base.beta());
    // apply(broken, base) 的预期结果：ref 取 base 的下一版（1+1=2）、timestamp/alpha 取变更集、
    // namespace/beta 沿袭 base ⇒ (ref(2), of(1), "toy", 5, 2)。它与 target **只差 beta 一个字段**。
    ToySnapshot expectedActual = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 2);
    assertThatThrownBy(
            () -> RoundTripAssertions.assertRoundTrip(base, target, (b, t) -> broken, ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        // 钉**整条 dump 行**（标签 + 该行的对象），而不是裸的字段名/词：
        // 这样删掉实现里任何一行、或把那行拼错对象，对应 needle 必然消失。
        .hasMessageContaining("  base      = " + base)
        .hasMessageContaining("  target    = " + target)
        .hasMessageContaining("  actual    = " + expectedActual)
        .hasMessageContaining("  changeSet = " + broken)
        // spec §9.2 要求报文含"一句定位提示"（评审判 M-1：删掉提示行原本无人察觉）。
        .hasMessageContaining("这正是 L1 事故的形态");
  }

  @Test
  void theFrameworkDoesNotRequireSnapshotImplementations() {
    // spec §9.2 给 `assertRoundTrip` 的 `S` **不设上界**——它要能服务 `SimulationState` 这类非快照类型。
    // G13（评审判 M-2）：这条性质写在实现的 Javadoc 里，此前**没有任何用例守它**——给 `S` 加回
    // `extends Snapshot` 上界，编译照样通过、5 条用例全绿。本用例用一个**刻意不实现 `Snapshot`** 的
    // record 调用它；一旦有人加上界，这里**编译失败**（响亮的红）。
    PlainState base = new PlainState(1);
    PlainState target = new PlainState(2);
    assertThatCode(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, PlainState::diff, PlainState::apply))
        .doesNotThrowAnyException();
  }

  /** 刻意**不**实现 `Snapshot`：证明框架对 `S` 真的没有上界。 */
  record PlainState(long value) {

    static PlainChangeSet diff(PlainState base, PlainState target) {
      return new PlainChangeSet(new RevisionId(target.value()));
    }

    static PlainState apply(PlainChangeSet changeSet, PlainState base) {
      return new PlainState(changeSet.baseRevision().value());
    }
  }

  record PlainChangeSet(RevisionId baseRevision) implements ChangeSet {}

  /** 玩具快照：证明框架不关心 `S` 是什么，只要它是 record 并实现 `Snapshot`。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static ToyChangeSet diff(ToySnapshot base, ToySnapshot target) {
      return new ToyChangeSet(
          base.ref().revision(), target.timestamp(), target.alpha(), target.beta());
    }

    static ToySnapshot apply(ToyChangeSet changeSet, ToySnapshot base) {
      // **控制器修正（计划草图此处有误，不要照抄计划 :2765-2781）**：计划原文写
      // `new StateRef(base.ref().branch(), changeSet.baseRevision())`——`baseRevision()` 是 **base 的**版本，
      // 于是 apply 产出的 ref 恒等于 base 的 ref，`target = ref(2)` **永远不可达**，
      // 本条用例（Step 5 期望 PASS 的那条）**必然抛 AssertionError**。
      // 已用 JVM 实证（最小等价物实跑）：target.rev=2、applied.rev=1、equals=false。
      // 改为产出**下一个**版本：既让 `ref` 真正参与往返（把它改回 base 的版本，本用例立刻转红，
      // 这就是 ref 那半边的 G13 自证），也与铁律 2"一 Command → 一 ChangeSet → 一 Revision"一致。
      return new ToySnapshot(
          new StateRef(base.ref().branch(), new RevisionId(changeSet.baseRevision().value() + 1)),
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
    // **控制器修正（T10 评审判 I-2，详见下方"控制器修正说明 ③"）**：原写的 `beta=9` / `beta=2`
    // 两条 needle **不具备注释所声称的"定位到 beta"的能力**——报文里 base 行与 target 行是
    // **无条件打印**的，两条 needle 分别被它们满足。评审实测 W1（把漂移从 beta 挪到 alpha）下
    // 本用例**全绿**：断言在"红得不是 beta"的世界里没有转红。
    // 改为钉 **actual 那一整行**。apply 的预期结果：ref 取 base 的下一版（1+1=2）、timestamp/alpha
    // 取变更集、namespace/beta 沿袭 base ⇒ `(ref(2), of(1), "toy", 5, 2)`，它与 target `(…,5,9)`
    // **只差 beta**——"差异出在 beta"因此由构造本身钉死，而不是由子串碰巧满足。
    DriftingSnapshot expectedActual = new DriftingSnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 2);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, DriftingSnapshot::diff, DriftingSnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("  actual    = " + expectedActual);
  }

  /** 故意漂移的玩具快照：`beta` 在快照里有、在变更集里没有——L1 事故的最小重演。 */
  record DriftingSnapshot(
      StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static DriftingChangeSet diff(DriftingSnapshot base, DriftingSnapshot target) {
      return new DriftingChangeSet(base.ref().revision(), target.timestamp(), target.alpha()); // 漏了 beta
    }

    static DriftingSnapshot apply(DriftingChangeSet changeSet, DriftingSnapshot base) {
      // 同 `ToySnapshot.apply` 的控制器修正：产出**下一个**版本，不要照抄计划的 `changeSet.baseRevision()`。
      // 这条对本用例尤其要紧：照抄的话 applied 的 ref 会比 target 低一版，本用例**照样红**，
      // 但红的理由里平白多了一个"ref 不匹配"——"抓到了 beta 漂移"这件事就不再被证明。
      // 这正是"红了还要问为什么红"：红必须红在被保护的那一行上。
      return new DriftingSnapshot(
          new StateRef(base.ref().branch(), new RevisionId(changeSet.baseRevision().value() + 1)),
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
Expected: PASS（**6 + 1 个用例**：`RoundTripAssertionsTest` 6 条 + `RoundTripAssertionsDriftTest` 1 条）。
**注意计数变动过两次，别照旧数**：控制器初稿写 4；T10 评审判出 2 条 Important 后新增
`theFrameworkDoesNotRequireSnapshotImplementations` 成 5；**本轮限域复审判出 Important-1 后再新增
`aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught` 成 6**（见"控制器修正说明 ⑥"）。
**⇒ 本次修复使模块用例数 155 → 156**（fix round 1 之后是 155；`149` 是 fix round 1 **之前**的旧基线，别把它当本次的起点）。

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/verify/ simos-util/src/test/java/io/mosire/simos/util/verify/
git diff --cached --stat          # 必须逐行扫过再提交（纪律：绝不 git add -A）
git commit -m "fix(util): 补快照入口的往返判别力（T10 复审 Important-1）" \
           -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```
**★ 第二段 trailer 是必须的**（本仓当前约定的提交信息结尾）。实测本分支 32 条提交里 26 条有、6 条没有；
控制器已裁定**不追溯改写**那 6 条，**但本任务起的提交一律要带**。

---

## 控制器修正说明（T10 评审判后追加；上面代码块已就地改齐）

**背景**：`ec2f10d` 的实现在**结构上**是对的（五条守卫经删式变异 5/5 承重，评审 A 判定"符合"），但评审判出 **2 条 Important + 3 条 Minor**，**全部是"断言自称的判别力 > 实际判别力"**。这**又是控制器写的文本**——两处缺陷都在控制器四次修订简报时亲手写下，实现者只是逐字转写。**故本轮的修法是改简报、让实现者重写那几条断言**，不是"实现者做错了"。

**① 为什么不是空转**：`RoundTripAssertions` 本体一行不动。核心不变量（往返破裂必须响）经删式变异证明是承重的。
**★ 这句原先写得过宽，本轮限域复审把它证伪了一半，见 ⑥。** 准确的说法是：
**通用入口 `assertRoundTrip` 的那一份 checkApplied 是承重的（删了会红）；快照入口 `assertSnapshotRoundTrip`
自带的那一份当时不承重（删了全绿）**。我把"跑了一个入口"写成了"核心不变量是承重的"这个全称结论。
**这是乙族的又一种形态：不是没跑，是跑了一半就把局部实测写成了全称。**
（与 ⑤ 那次"完全没跑就下结论"同族不同型——故 ⑥ 单开一条，不并入 ⑤。）

**② I-1：`aBrokenRoundTripReportsAllThreeStates` 没钉住它名字承诺的三态。**
- 评审实测 **P1**：删掉实现的 `+ "  base      = " + base` → **5 条用例全绿**（`base` 那份 dump **零护栏**）。
- 评审实测 **P2**：删掉 `+ "\n  target    = " + target` → 本用例**仍绿**。原因：needle 是 `hasMessageContaining("target")`，而报文**首行** `往返不变式破裂：apply(diff(base, target), base) 与 target 不等。` 里**本来就有 "target" 字样**——**这条断言在任何情况下都满足**。
- **控制器静态复核确认**（读 impl:47-61 与 Test:99-100 原文，与评审独立同结论）：首行确实无条件含 `target`；`base` 确实无 needle。
- **修法**：钉**整条 dump 行**（`"  base      = " + base` 等四行 + 提示行）。**注意 `StateRef`/`SimosTimestamp` 都是 record**，其 toString 是 `StateRef[branch=BranchId[value=main], revision=RevisionId[value=2]]` 这类形状——**不要凭想象写 `ref=main@2` 之类的简写**（控制器起草时差点这么写，读源码后改正）。

**③ I-2：漂移用例的 needle 对"差异出在哪个字段"没有定位力，注释的断言过强。**
- 评审实测 **W1**：把漂移从 `beta` 挪到 `alpha` → 往返照样破裂，报文 base `alpha=1,beta=2` / target `alpha=5,beta=9` / actual `alpha=1,beta=9`，两条 needle **分别被无条件打印的 base 行与 target 行满足** → 用例**全绿**。
- **缓解事实（一并记录，别丢）**：本用例的**核心判据是承重的**——不漏字段时 `assertThatThrownBy` 自己会红。所以它**不是空转护栏**，只是**自称的判别力高于实际**。
- 评审另实测 **W6/V4**：**若没有 `+ 1` 那条修正，本用例在"根本没漏字段"的世界里也全绿**——即零判别力。⇒ **`+ 1` 是让"抓得住漂移"这件事真正被证明的那一行**，比控制器原先说的"红得更好看"更要紧。
- **修法**：钉 actual 整行。
- **这条要紧的原因**：**M2~M4 的 `XxxDriftTest` 会照抄这个模板。**

**④ M-1 / M-2 / M-3（顺带修）**
- **M-1**：`changeSet` 行与提示行**实测删掉无人察觉**（P4/P5）。已在 ② 的 needle 组里一并纳入。
- **M-2**：`assertRoundTrip` 的 `S` **无上界**这条性质（写在 impl:23-24 的 Javadoc 里）**没有任何用例守**——评审实测 S1：给 `S` 加回 `extends Snapshot` 上界后**编译通过、5 条全绿**。**归属简报层面的缺口**（简报的 `ToySnapshot` 注释自己把该性质限定成"record 并实现 `Snapshot`"），实现者无过错。新增 `theFrameworkDoesNotRequireSnapshotImplementations` + 两个刻意不实现 `Snapshot` 的小 record。
- **M-3**：实现者报告里"`cannot find symbol` 共 **16** 处…`DriftTest` 多处"**计数不准**，评审实测是 **8 处**（`RoundTripAssertionsTest` 7 处：23/28/43/61/69/80/92；`DriftTest` **1** 处：26）。行号与"报 `variable` 而非 `class`"这一关键现象均属实，只是总数与措辞不准。**改报告即可，不动代码。**

**⑤ 不在本轮范围的（评审"备注"节，控制器同意不计入）**
- `diff`/`apply` 返回 null 时抛 `NullPointerException` 而非 `AssertionError`，与 spec §9.2 字面有张力；但**简报是本任务的唯一权威**且明写用 `requireNonNull`，实现无过错。**记为 M2~M4 使用者的注意事项**：若用 `catch (AssertionError)` 捕往返失败，null 返回路径会漏网。
- `aMisStampedChangeSetIsRejected` 与守卫文案强耦合——**这是简报的有意设计**，不构成缺陷。
- `RoundTripAssertions` 无实例化用例（私有构造器）——spec 未要求。
- **残留边界——控制器原先在这里写的结论是错的，已实测推翻并更正（乙族第 6 例）**。
  原文写的是：「`actual` 与 `changeSet` 两份 dump 都含 `alpha=5, beta=2]`，若把源码的
  `+ applied` 误写成 `+ changeSet`，本用例抓不住。**不修，记账。**」
  **这句是没跑过的推断，且是错的。** 错在把**子串层面**的观察套到了**整行** needle 上：
  needle 是 `"  actual    = " + expectedActual`，它在 `alpha=5, beta=2]` 之前就先要求
  `  actual    = ToySnapshot[`；误写成 `+ changeSet` 后该行渲染成 `  actual    = ToyChangeSet[`，
  **前缀对得上、类名对不上**，needle 必然落空。
  **实测（`13b96a8` 的三份文件，worktree 外 `/tmp/t10resid`，junit-platform-console 1.11.4）**：
  基线 6/6 绿；六种"对象/标签互换"变异**全部转红**——
  `actual` 行对象换成 `changeSet` → **红 2**（`aBrokenRoundTripReportsAllThreeStates`、
  `aChangeSetThatDropsAFieldMustBeCaught`）；换成 `target` → 红 2；换成 `base` → 红 2；
  `changeSet` 行对象换成 `applied` → 红 1；两行对象对调 → 红 2；两个标签对调 → 红 2。
  **⇒ 在本轮我能构造的互换变异里，没有找到残留边界。** 这不等于"不存在边界"（未穷尽），
  但原文那句"抓不住"**已被证伪**，不得再作为记账保留。原文保留在此仅为记录。
  **教训与乙族同源**：写"某条护栏抓不住 X"和写"某条护栏抓得住 X"是同一种断言，
  两边都要当场跑过才能写。**"我推过"不是"我验过"。**

**⑥ 限域复审 Important-1：快照入口的核心不变量是装饰（本轮唯一的再修项）**

**事实**：`RoundTripAssertions.java` 里 `assertSnapshotRoundTrip` 在版本戳守卫之后，
**自己又写了一遍** `checkApplied(base, target, changeSet, apply);`（`impl:42`）——
与通用入口 `impl:26` 的那一份是**两份独立的调用点**。删掉 `impl:42` ⇒ **6 条用例全绿**（零红）。
即：**快照入口的往返破裂无人守**，铁律 5 在该入口上整个落空。
**两次独立实测，哈希逐字吻合**：canon `ced4054b` → 变异 `23c77149`（控制器一次、限域复审者一次）。

**为什么现有 6 条盖不住**：`assertSnapshotRoundTrip` 的三处调用点分别是
（a）**往返正确**（`aCorrectRoundTripPasses`）、（b）**被版本戳守卫先拦下**（`aMisStampedChangeSetIsRejected`）、
（c）**只到 `diff 返回 null` 守卫**（`aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages`）——
**没有一条**是"版本戳正确 **且** 往返破裂"，而漂移自证走的是**通用入口**。
**⇒ 这正是本文件 `:73-77` 那条注释自己点名过的形态**（"`diff 返回 null` 这条守卫在两个公开方法里各写了一遍，
上面那条只盖住了 `assertRoundTrip` 的那一份……**同文件内的不对称即是证据**"）——
**同一条教训写完 20 行之后，在另一个守卫上原样重演了一次。**

**最小修复：新增一条用例**（加在 `RoundTripAssertionsTest`，`theFrameworkDoesNotRequireSnapshotImplementations` 之前）：

```java
  @Test
  void aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    // 版本戳**正确**（= base 的版本），只有 beta 漏了 —— 版本戳守卫不会再替我们拦下，
    // 于是唯一能响的就是 `assertSnapshotRoundTrip` 自己的那次 checkApplied（impl:42）。
    ToyChangeSet stamped =
        new ToyChangeSet(base.ref().revision(), target.timestamp(), target.alpha(), base.beta());
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base, target, (b, t) -> stamped, ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        // 钉 `checkApplied` 独有的措辞（版本戳守卫的报文不含它），
        // 否则"因错误的原因转红"——即被版本戳守卫拦下——也会通过。
        .hasMessageContaining("往返不变式破裂");
  }
```

**控制器已预跑（`/tmp/t10f2`，取 `13b96a8` 的三份文件，junit-platform-console `--details=tree`）**：

| 探针 | 结果 |
|---|---|
| 加上本用例、impl 未动 | **7 绿 / 0 红** |
| **删 `impl:42`** | **恰好 1 红**，且就是 `aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught` ⇒ 它是该行的**唯一**守卫 |
| needle 换成版本戳守卫的措辞 | 红 ⇒ needle 真在钉 `checkApplied` 的报文，不是"随便一个 `AssertionError`" |
| 把桩的版本戳改错（**守卫换人**） | 新用例红 ⇒ 它分得清"是哪条守卫响的"，排除了"因错误的原因转红" |

**★ 控制器更正（2026-09-16，限域复审者实测指出；上表第 4 行的因果挂反了）**：
上表第 4 行原文写「把桩的版本戳改错（守卫换人）→ 新用例红 ⇒ 它分得清『是哪条守卫响的』，**排除了『因错误的原因转红』**」。
**实测：那一行红的理由恰恰就是「错误的原因」**——版本戳守卫接管后抛出的是它自己的报文（`变更集必须相对它被施加的 base…`），新用例的 needle（`往返不变式破裂`）落空，于是红。**它是「因错误的原因转红」的一个实例，不是对它的排除。**
真正排除「因错误原因转红」的是**第 3 行**（needle 换成版本戳守卫的措辞 → 红），它证明 needle 确实在钉 `checkApplied` 的报文；复审者另贴了原始报文，其中堆栈直接点名 `assertSnapshotRoundTrip(RoundTripAssertions.java:42)` → `checkApplied(:49)`。
**复审者另加了一条更强的双向探针**（本简报原先没有）：**删 `impl:26`（通用入口那一份 `checkApplied`）→ 新用例仍绿，红的是另外 3 条**（`aBrokenRoundTripReportsAllThreeStates` / `aChangeSetThatDropsAFieldMustBeCaught` / `aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages`）。加上「删 `impl:42` → 只红新用例」，**「新用例专门钉 `impl:42`」这一结论才双向成立。**
——**留个记录**：这正是本里程碑反复出现的形态——**跑是跑了，但把产物按印象归到了另一行**（同 `but did not` 那一处，见下）。**探针的红/绿要连「它为什么是那个颜色」一起记。**

**验收口径（G13，必须自证）**：改完后
① `./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test` → **7 条全绿**（6 + 1）；
② **删掉 `impl:42` → 必须转红，且只红 `aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught` 这一条**；
③ 跑完把 `impl` 还原，`git diff --stat simos-util/src/main/` 应为空（**本轮的修复只碰测试文件**）。
report 里请附上 ② 的原始输出（红名 + 完整报文）。
**★ 更正（2026-09-16）**：本句原先写「红名 + `but did not` 那行」，**那是错的**——该变异下**什么都不抛**，`assertThatThrownBy` 在它自身「是否有 throwable」那一关就失败，报文全文是 `Expecting code to raise a throwable.`，`but did not` 出现 **0** 次。`but did not` 是 AssertJ 在「**有** throwable、但**消息**不含 needle」时才打的措辞（即上面第 3 行那种形态）。

**⑦ 顺带更正 ④ 里 M-3 的时效**（复审 Minor-1，**改报告即可，不动代码**）：
`cannot find symbol` 的「8 处」是**在 `ec2f10d` 上**的实测值（Maven 每条打印两次，分布 `{2: 8}`），
该值对那次评审成立；但**在 `13b96a8` 上已是 9 个位置 / 18 行**
（`RoundTripAssertionsTest` 的 23/28/43/61/69/80/**99**/**122** + `DriftTest` 的 **35**）。
写报告时请**带上"哪个提交"这个限定**，别把某一提交的计数写成当前状态。
