package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * 相对父的轴向偏移（spec §一.3）：hex 轴向坐标的逐分量差。
 *
 * <p>★ **无范围约束**（P2）：它是"相对父的站位"，**不强制落在地图内**——父位在边界、子偏移越界是合法组合，v1 只要求叠加结果是合法 {@link
 * HexCoord}。地图内强制会与这一组合冲突而无收益，故不做。
 *
 * <p>★ **但溢出必须拒绝，不得静默回绕**（T10-a / T3-L1）：P2 说的是"偏移本身不设范围"，不是"叠加可以溢出 int"——{@code dq = 2147483647} 时
 * `q + dq` 会回绕成负数、单位"瞬移"到地图另一头。故 {@link #appliedTo} 用精确加法，溢出即抛可读的 {@link
 * IllegalArgumentException}。这条对 **codec 反序列化路径同样成立**：只堵 handler 层会让旧档/手工载荷绕开它。
 */
public record RelativeOffset(int dq, int dr) {

  /** 叠加到一个 hex 上（轴向坐标逐分量相加）；溢出 int ⇒ 抛（不静默回绕）。 */
  public HexCoord appliedTo(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    return new HexCoord(hex.q() + dq, hex.r() + dr);
  }
}
