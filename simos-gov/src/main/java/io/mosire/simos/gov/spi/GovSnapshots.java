package io.mosire.simos.gov.spi;

import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 gov 切片的唯一入口（gov 命令 handler 共用）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code EconomySnapshots} / {@code UnitSnapshots} 同口径）：缺 {@code gov}
 * 切片、或切片不是 {@link GovSnapshot}，都抛 {@link IllegalStateException}——它们是装配的错，不是命令/载荷的错， 不走 {@code
 * Rejected} 路径（handler 在边界把 {@code IllegalStateException} 记 ERROR 后原样上抛）。
 */
final class GovSnapshots {

  private GovSnapshots() {}

  static GovSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (!(snapshot instanceof GovSnapshot govSnapshot)) {
      throw new IllegalStateException(
          "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
    }
    return govSnapshot;
  }
}
