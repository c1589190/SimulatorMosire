package io.mosire.simos.unit;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 在途行程（M3 spec §4.5）。
 *
 * <p>★ **出发时刻的速度与机动性在此冻结**：在途行程不得因参数变更而"时间反演"（速度翻倍让昨天已走的 路程突然变长）。地图变化**仍实时生效**——那正是 {@code
 * MovementStatus.NEED_REPLAN} 的来源。
 *
 * <p>★ **{@code speedAtDeparture} 的单位是 MP/小时**（2026-09-24 日制裁定）。日制下推进一刻 = 一天，故 **一天的行程预算 = 本值 × 24
 * 小时** = {@code speedAtDeparture × 1000 × 24} 毫 MP（计算见 {@link
 * io.mosire.simos.unit.move.UnitMoves}）；{@code Movement} 只负责把出发时的速度值原样冻住。
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
