package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * spec §九：正常往返通过、破裂时给得出三份 toString。
 *
 * <p>★ M4（spec §十）：**快照专用入口与其版本戳用例已删**——它守的性质升级为结构不变量（C27，由 revision 行的 parent 指针承担），并有一条全仓 0
 * 处的扫描护栏（R15，本文件末条）钉住"不许复活"。本文件由此**不得再出现那个被删方法的名字**（否则 R15 扫到自己）。
 */
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
    // ★ M4：第三个断言（盖快照专用入口的那份同形守卫）随该入口一并删除——它守的两份重复守卫只剩
    // `assertRoundTrip` 这一份，上面的断言继续钉住它（spec §十 的处置）。
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
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, (b, t) -> broken, ToySnapshot::apply))
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

  /** R15：被删的快照专用入口**不许在别处复活**——它守的性质已升级为结构不变量（C27），留着就是同一事实两个来源。 */
  @Test
  void snapshotRoundTripAssertionIsGoneFromTheWholeRepo() throws IOException {
    // ★ needle 拆成两半拼出来：本文件若写下完整方法名的连续字面量，全仓扫描会扫到本文件自己，用例恒红。
    //   （RepoSourceScan 自身不含这个名字——它只提供扫描能力，不写被扫的串。）
    //   ★ rawContent 声明受检异常，而 lambda（Predicate）传不出去——计划草图此处编不过，
    //   故经下面的 content() 拆包成 UncheckedIOException（执行期校正）。
    String needle = "assertSnapshot" + "RoundTrip";
    List<String> hits =
        RepoSourceScan.javaFilesUnder(".").stream()
            .filter(p -> content(p).contains(needle))
            .map(RepoSourceScan::relative)
            .toList();
    assertThat(hits).as("全仓应为 0 处（spec §十一 R15）").isEmpty();
  }

  private static String content(java.nio.file.Path file) {
    try {
      return RepoSourceScan.rawContent(file);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
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
      // `new StateRef(base.ref().branch(), changeSet.baseRevision())`——`baseRevision()` 是 **base
      // 的**版本，
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
