package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §五：`RevisionId` 只在分支内有意义，跨分支的完整坐标是 `StateRef`。 */
class StateRefTest {

  @Test
  void branchAndRevisionFormTheCoordinate() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(7));
    assertThat(ref.branch().value()).isEqualTo("main");
    assertThat(ref.revision().value()).isEqualTo(7);
    assertThat(ref).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(7)));
  }

  @Test
  void revisionsAreOrdered() {
    assertThat(new RevisionId(7)).isGreaterThan(new RevisionId(6));
    assertThat(new RevisionId(7)).isEqualByComparingTo(new RevisionId(7));
  }

  @Test
  void blankBranchIsRejected() {
    assertThatThrownBy(() -> new BranchId(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stateMetaCarriesRefAndTimestamp() {
    StateMeta meta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(3, "第 3 日"));
    assertThat(meta.ref().revision()).isEqualTo(new RevisionId(1));
    assertThat(meta.timestamp().tick()).isEqualTo(3);
  }

  /** 以下五条是控制器裁决第 1 条补的守卫自证用例：判据**钉字段级消息**，只断异常类型的话串位与被删的守卫都不会转红。 */
  @Test
  void nullBranchIsRejected() {
    assertThatThrownBy(() -> new BranchId(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BranchId.value");
  }

  @Test
  void nullBranchInStateRefIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new StateRef(null, new RevisionId(1)))
        .withMessage("branch");
  }

  @Test
  void nullRevisionInStateRefIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new StateRef(new BranchId("main"), null))
        .withMessage("revision");
  }

  @Test
  void nullRefInStateMetaIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new StateMeta(null, SimosTimestamp.of(1)))
        .withMessage("ref");
  }

  @Test
  void nullTimestampInStateMetaIsRejected() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), null))
        .withMessage("timestamp");
  }
}
