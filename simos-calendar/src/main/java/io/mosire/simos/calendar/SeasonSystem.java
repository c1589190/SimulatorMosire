package io.mosire.simos.calendar;

import io.mosire.simos.map.hex.HexCoord;

/**
 * 季节查询契约（设计稿 §5.4）：日号 + 六角坐标 ⇒ 季节状态。
 *
 * <p>season API 直接收 {@link HexCoord}（D-019 用户裁定），实现按格子的南北轴 {@code r} 判带； 本批不读 {@code GameMap}
 * 地形/高度。
 */
public interface SeasonSystem {

  /**
   * 某日某格的季节。
   *
   * @param dayNumber JDN 整数（民用日）
   * @param at 格子坐标（非空；读 {@code at.r()} 判纬度带）
   * @return 该格该日的季节状态
   */
  SeasonState seasonOf(long dayNumber, HexCoord at);
}
