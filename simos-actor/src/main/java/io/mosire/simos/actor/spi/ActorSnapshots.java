package io.mosire.simos.actor.spi;

import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 actor 切片的唯一入口（本包命令 handler 共用）。
 *
 * <p>★ <b>装配故障当场炸</b>（与 {@code EconomySnapshots} / {@code SocialSnapshots} 同口径）：缺 {@code actor}
 * 切片、或切片不是 {@link ActorSnapshot}，都抛 {@link IllegalStateException}——它们是**装配**的错，不是命令/载荷的错， 不走 {@code
 * Rejected} 路径（那条路是给"世界数据说了什么"用的）。
 */
final class ActorSnapshots {

  private ActorSnapshots() {}

  static ActorSnapshot of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("actor")
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（装配故障）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot;
  }
}
