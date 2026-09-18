package io.mosire.simos.unit.spi;

import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 unit 切片的唯一入口（本包两个 SPI 类共用）。
 *
 * <p>★ **装配故障当场炸**（与 {@code MapResolver} 同口径）：缺 {@code unit} 切片、或切片不是 {@link UnitSnapshot}， 都抛
 * {@link IllegalStateException}——它们是装配的错，不是命令/提案的错，不走 {@code Rejected} 路径。
 */
final class UnitSnapshots {

  private UnitSnapshots() {}

  static UnitSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalStateException("state 里没有 unit 切片（装配故障）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot;
  }
}
