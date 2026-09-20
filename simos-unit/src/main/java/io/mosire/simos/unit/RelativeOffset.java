package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * 相对父的轴向偏移（spec §一.3）：hex 轴向坐标的逐分量差。
 *
 * <p>★ **无范围约束**（P2）：它是"相对父的站位"，**不强制落在地图内**——父位在边界、子偏移越界是合法组合，v1 只要求叠加结果是合法 {@link
 * HexCoord}。地图内强制会与这一组合冲突而无收益，故不做。
 */
public record RelativeOffset(int dq, int dr) {

  /** 叠加到一个 hex 上（轴向坐标逐分量相加）。 */
  public HexCoord appliedTo(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    return new HexCoord(hex.q() + dq, hex.r() + dr);
  }
}
