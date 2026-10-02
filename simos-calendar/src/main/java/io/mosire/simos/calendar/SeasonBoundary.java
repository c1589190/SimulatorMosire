package io.mosire.simos.calendar;

/**
 * 季界算法族（D-018 补裁：缺省 {@link #SOLAR_TERM}）。
 *
 * <ul>
 *   <li>{@link #SOLAR_TERM}：24 节气季界。北半球春=立春(315°)→立夏(45°)、夏=立夏→立秋(135°)、
 *       秋=立秋→立冬(225°)、冬=立冬→立春；南半球整体移相 180°。
 *   <li>{@link #ASTRONOMICAL}：二分二至季界。北半球春=春分(0°)→夏至(90°)、夏=夏至→秋分(180°)、
 *       秋=秋分→冬至(270°)、冬=冬至→春分；南半球整体移相 180°。
 * </ul>
 */
public enum SeasonBoundary {
  /** 24 节气季界（缺省）。 */
  SOLAR_TERM,
  /** 天文季界（二分二至）。 */
  ASTRONOMICAL
}
