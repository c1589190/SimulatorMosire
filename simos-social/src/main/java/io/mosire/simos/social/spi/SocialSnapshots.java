package io.mosire.simos.social.spi;

import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 social 切片的唯一入口（本包命令 handler 共用）。
 *
 * <p>★ **装配故障当场炸**（与 {@code MapSnapshots} / {@code UnitSnapshots} / {@code SocialResolver} 同口径）：缺
 * {@code social} 切片、或切片不是 {@link SocialSnapshot}，都抛 {@link
 * IllegalStateException}——它们是装配的错，不是命令/载荷的错， 不走 {@code Rejected} 路径。
 */
final class SocialSnapshots {

  private SocialSnapshots() {}

  static SocialSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot;
  }
}
