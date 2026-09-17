package io.mosire.simos.unit;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 在途行程（M3 spec §4.5）。
 *
 * <p>★ **出发时刻的速度与机动性在此冻结**：在途行程不得因参数变更而"时间反演"（速度翻倍让昨天已走的 路程突然变长）。地图变化**仍实时生效**——那正是 {@code
 * MovementStatus.NEED_REPLAN} 的来源。
 */
public record Movement(
    Route route, SimosTimestamp departedAt, int speedAtDeparture, int mobilityAtDeparture) {

  public Movement {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(departedAt, "departedAt");
    if (speedAtDeparture < 1) {
      throw new IllegalArgumentException("speedAtDeparture 必须 ≥ 1: " + speedAtDeparture);
    }
    if (mobilityAtDeparture < 1) {
      throw new IllegalArgumentException("mobilityAtDeparture 必须 ≥ 1: " + mobilityAtDeparture);
    }
  }
}
