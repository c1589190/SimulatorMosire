package io.mosire.simos.util.time;

import java.util.Objects;

/** 一段阶跃常量：自 `from` 起取值 `value`，直到下一段的 `from`（左闭右开，spec §七）。 */
public record Segment<T>(SimosTimestamp from, T value) {

  public Segment {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(value, "value");
  }
}
