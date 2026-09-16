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
        // 钉**两侧的具体值**，不只钉字段名：报文拼的是 target 与 actual 的整份 record toString，
        // 只要报文里出现过 "beta" 字样（两侧的 toString 都带着它），只写 `hasMessageContaining("beta")`
        // 对"差异出在哪个字段"**零判别力**——ref 版本对不上时它照样满足。
        .hasMessageContaining("beta=9") // target 的 beta
        .hasMessageContaining("beta=2"); // actual（沿袭 base）的 beta —— 两者必须同时出现，才证明红的是 beta
  }

  /** 故意漂移的玩具快照：`beta` 在快照里有、在变更集里没有——L1 事故的最小重演。 */
  record DriftingSnapshot(
      StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static DriftingChangeSet diff(DriftingSnapshot base, DriftingSnapshot target) {
      return new DriftingChangeSet(
          base.ref().revision(), target.timestamp(), target.alpha()); // 漏了 beta
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
