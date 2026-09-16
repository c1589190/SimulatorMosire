package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §六：三个协议接口保持最小——不用万能父类，ChangeSet/Command 都是单方法接口。 */
class SnapshotProtocolTest {

  /** 玩具快照：证明模块快照只需实现三个方法即可接入协议。 */
  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}

  @Test
  void toySnapshotImplementsTheThreeProtocolMethods() {
    ToySnapshot snapshot =
        new ToySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(2)), SimosTimestamp.of(1), "toy", 42);
    assertThat(snapshot.ref().revision()).isEqualTo(new RevisionId(2));
    assertThat(snapshot.timestamp().tick()).isEqualTo(1);
    assertThat(snapshot.namespace()).isEqualTo("toy");
    assertThat(snapshot.alpha()).isEqualTo(42);
  }

  @Test
  void changeSetAndCommandExposeTheirStamps() {
    ChangeSet changeSet = () -> new RevisionId(2);
    Command command = () -> new RevisionId(2);
    assertThat(changeSet.baseRevision()).isEqualTo(new RevisionId(2));
    assertThat(command.expectedRevision()).isEqualTo(new RevisionId(2));
  }
}
