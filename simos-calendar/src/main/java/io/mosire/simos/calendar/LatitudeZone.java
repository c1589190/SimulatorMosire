package io.mosire.simos.calendar;

/**
 * 纬度分带（设计稿 §5.3）：北温带 / 赤道附近 / 南温带。
 *
 * <p>分带数值不预设（D-018 补裁：只给接口、GM 配置）；未配置时 {@link LatitudeBands#zoneOf(long)} 恒返回 {@link
 * #NORTH_TEMPERATE}，由读口标注 {@code zoneSource: fallback}（不假装已分带）。
 */
public enum LatitudeZone {
  /** 北温带。 */
  NORTH_TEMPERATE,
  /** 赤道附近（热带）。 */
  TROPICS,
  /** 南温带。 */
  SOUTH_TEMPERATE
}
