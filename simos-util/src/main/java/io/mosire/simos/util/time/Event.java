package io.mosire.simos.util.time;

import java.util.Objects;

/** 一个离散跳变：时刻 `at`、值 `value`、方式 `mode`（spec §七）。 */
public record Event<T>(SimosTimestamp at, T value, EventMode mode) {

  public Event {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(mode, "mode");
  }
}
