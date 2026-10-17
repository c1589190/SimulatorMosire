package io.mosire.simos.culture.spi;

import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 culture 切片的唯一入口（本包 handler 共用）。形制照 {@code ProductionSnapshots} /
 * {@code GovSnapshots}。
 *
 * <p>★ <b>装配故障当场炸</b>：缺切片、或切片类型不对，都抛 {@link IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 */
final class CultureSnapshots {

  private CultureSnapshots() {}

  static CultureData of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("culture")
            .orElseThrow(() -> new IllegalStateException("state 里没有 culture 切片（装配故障）"));
    if (!(snapshot instanceof CultureSnapshot cultureSnapshot)) {
      throw new IllegalStateException(
          "state 的 culture 切片不是 CultureSnapshot: " + snapshot.getClass().getName());
    }
    return cultureSnapshot.state();
  }
}
