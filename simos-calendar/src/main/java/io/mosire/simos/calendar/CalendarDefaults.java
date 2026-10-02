package io.mosire.simos.calendar;

/**
 * 缺省历法常量——D-018 补裁要求的「默认历法/气候计算算法」的唯一代码口径。
 *
 * <p>历法 = 儒略历（全期），tick 0 锚点 = 儒略 1445-01-01（JDN 2248845）。
 *
 * <p>★ C3（2026-10-02 已落地）：季节缺省也以本类为唯一代码口径—— 季界 {@link #SEASON_BOUNDARY}（24 节气 SOLAR_TERM）、热带 {@link
 * #TROPICAL_MODEL} （雨季/旱季 RAINY_DRY，窗口 {@code [}{@link #TROPICAL_RAINY_START_LONGITUDE} , {@link
 * #TROPICAL_RAINY_END_LONGITUDE}{@code )}）、坐标轴 {@link #NORTH_IS_NEGATIVE} （负 r =
 * 北）。分带数值<b>不预设</b>（只给 {@link LatitudeBands} 接口、GM 配置）：未配置 ⇒ 全球北半球四季 + {@code zoneSource:
 * fallback}，不假装已分带。
 *
 * <p>本类常量 = 设计稿 §九「默认历法/气候计算算法」；<b>改任何缺省值必须另开 D 条目</b>，不许静默改。
 */
public final class CalendarDefaults {

  /** 缺省历法标识：儒略历。 */
  public static final String CALENDAR_ID = "julian";

  /** tick 0 锚点：儒略 1445-01-01 = JDN 2248845（JDN 由 {@link JulianCalendar#dayNumberOf} 正算）。 */
  public static final CalendarDate EPOCH_DATE = new CalendarDate(1445, 1, 1);

  /** 缺省季界：24 节气（D-018 补裁；设计稿 §九）。天文二分二至只作可切换实现。 */
  public static final SeasonBoundary SEASON_BOUNDARY = SeasonBoundary.SOLAR_TERM;

  /** 缺省热带季节模型：雨季/旱季（D-018 补裁）。 */
  public static final TropicalModel TROPICAL_MODEL = TropicalModel.RAINY_DRY;

  /** 缺省雨季窗口起点黄经：45°（立夏，含）；半开区间 {@code [start,end)}。 */
  public static final double TROPICAL_RAINY_START_LONGITUDE = 45.0;

  /** 缺省雨季窗口终点黄经：165°（白露，不含）；{@code [45°,165°)} 约 120 天。 */
  public static final double TROPICAL_RAINY_END_LONGITUDE = 165.0;

  /** 地图 r 轴约定：true = 负 r 为北（D-019 实现说明；hexToPixel.y = 1.5r）；世界范围 r∈[-140,140]。 */
  public static final boolean NORTH_IS_NEGATIVE = true;

  private CalendarDefaults() {}
}
