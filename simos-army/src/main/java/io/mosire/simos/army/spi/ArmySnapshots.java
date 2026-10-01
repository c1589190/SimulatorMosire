package io.mosire.simos.army.spi;

import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 army 切片的唯一入口（本包命令 handler 共用）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code ActorSnapshots} / {@code UnitSnapshots} 同口径）：缺 {@code army} 切片、或切片不是
 * {@link ArmySnapshot}，都抛 {@link IllegalStateException}——它们是<b>装配</b>的错，不是命令/载荷的错，不走 {@code
 * Rejected} 路径（那条路是给"世界数据说了什么"用的）。
 */
final class ArmySnapshots {

  private ArmySnapshots() {}

  static ArmySnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("army")
            .orElseThrow(() -> new IllegalStateException("state 里没有 army 切片（装配故障）"));
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalStateException(
          "state 的 army 切片不是 ArmySnapshot: " + snapshot.getClass().getName());
    }
    return armySnapshot;
  }
}
