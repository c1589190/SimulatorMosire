package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §六 / §十：三个协议接口保持最小——不用万能父类；`ChangeSet` 是标记接口（M4 收窄），`Command` 仍是单方法接口。 */
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

  /** R18：`ChangeSet` 收窄为标记接口后，这两个协议接口的形状被钉住——不许有人偷偷加回抽象方法。 */
  @Test
  void changeSetIsAMarkerAndCommandIsStillASingleMethodInterface() {
    // ★ 数的是 getDeclaredMethods()（本接口自己声明的），不是 getMethods()——后者会把 Object 的
    // 公共方法也算进来，计数不稳。这类"计数型"断言必须写清数的是什么。
    assertThat(ChangeSet.class.getDeclaredMethods())
        .as("ChangeSet 必须是标记接口（U 裁定 / spec §十）：版本戳归 Revision 层，不归变更集")
        .hasSize(0);
    assertThat(Command.class.getDeclaredMethods())
        .as("Command 必须仍是 SAM——信封 record 靠访问器天然满足它，多一个抽象方法就破坏这条")
        .hasSize(1);
    assertThat(Command.class.getDeclaredMethods()[0].getName()).isEqualTo("expectedRevision");
  }
}
