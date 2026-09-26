package io.mosire.simos.actor.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link ActorSnapshots}：从 {@link SimulationState} 取 actor 切片的唯一入口。
 *
 * <p>★ <b>装配故障当场炸</b>（缺切片 / 切片类型不对），不走 {@code Rejected} 路径 —— 那是装配的错，不是命令或载荷的错。
 */
class ActorSnapshotsTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  /** 正例：取到的那份切片与装配进去的是同一份（`data` 逐值）。 */
  @Test
  void returnsTheActorSlice() {
    ActorData data = ActorData.empty().withMeta(Optional.of(new ActorMeta("Map1", 7, "actor-v1")));

    ActorSnapshot snapshot = ActorSnapshots.of(state("actor", new ActorSnapshot(REF, T7, data)));

    assertThat(snapshot.data()).isSameAs(data);
    assertThat(snapshot.namespace()).isEqualTo("actor");
  }

  /** 缺 actor 切片 ⇒ {@link IllegalStateException}（**装配故障**，且消息里点名它是装配故障）。 */
  @Test
  void aMissingSliceIsAnAssemblyFault() {
    SimulationState withoutActor =
        new SimulationState(new StateMeta(REF, T7), Map.of(), InMemoryInfoSystem.empty());

    assertThatThrownBy(() -> ActorSnapshots.of(withoutActor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配故障");
  }

  /**
   * 键对得上、但类型不对 ⇒ 同样当场炸。
   *
   * <p>★ 判别力：{@code SimulationState} 只校验"modules 的键 == 该快照的 namespace()"，**不校验类型** ⇒
   * 这里那个分支是**可达的**（本用例用一个 namespace 也叫 {@code actor} 的冒牌切片证明它不是死代码）。
   */
  @Test
  void aSliceOfAnotherTypeIsAnAssemblyFault() {
    Snapshot impostor =
        new Snapshot() {
          @Override
          public StateRef ref() {
            return REF;
          }

          @Override
          public SimosTimestamp timestamp() {
            return T7;
          }

          @Override
          public String namespace() {
            return "actor";
          }
        };

    assertThatThrownBy(() -> ActorSnapshots.of(state("actor", impostor)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ActorSnapshot");
  }

  private static SimulationState state(String key, Snapshot snapshot) {
    return new SimulationState(
        new StateMeta(REF, T7), Map.of(key, snapshot), InMemoryInfoSystem.empty());
  }
}
