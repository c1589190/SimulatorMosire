package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/** 整个世界状态的坐标：版本坐标 + 模拟时刻（spec §五）。 */
public record StateMeta(StateRef ref, SimosTimestamp timestamp) {

  public StateMeta {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
