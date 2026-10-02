package io.mosire.simos.calendar;

/**
 * 热带（赤道附近）的季节模型（D-018 补裁：缺省 {@link #RAINY_DRY}）。
 *
 * <ul>
 *   <li>{@link #RAINY_DRY}：按太阳黄经窗口分雨季/旱季；缺省窗口 {@code [45°,165°)} （立夏→白露，约 120 天），南热带窗口移相 180°。
 *   <li>{@link #TEMPERATE_LIKE}：热带也按所选 {@link SeasonBoundary} 给北半球同名四季 （季节弱化的近似；本批不接地形/高度）。
 * </ul>
 */
public enum TropicalModel {
  /** 雨季/旱季（缺省）。 */
  RAINY_DRY,
  /** 类温带四季。 */
  TEMPERATE_LIKE
}
