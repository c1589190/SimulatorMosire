package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 economy 切片的唯一入口（本包命令 handler 共用）。
 *
 * <p>★ **装配故障当场炸**（与 {@code SocialSnapshots} / {@code EconomyResolver} 同口径）：缺 {@code economy}
 * 切片、或切片不是 {@link EconomySnapshot}，都抛 {@link IllegalStateException}——它们是装配的错，不是命令/载荷的错， 不走 {@code
 * Rejected} 路径。
 */
final class EconomySnapshots {

  private EconomySnapshots() {}

  static EconomySnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot;
  }
}
