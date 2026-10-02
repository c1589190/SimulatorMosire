package io.mosire.simos.calendar;

/**
 * 缺省历法常量——D-018 补裁要求的「默认历法/气候计算算法」的唯一代码口径。
 *
 * <p>本阶段（C2）只放历法部分：历法 = 儒略历（全期），tick 0 锚点 = 儒略 1445-01-01（JDN 2248845）。
 *
 * <p>★ C3 会把季节缺省（季界 {@code SOLAR_TERM}、热带 {@code RAINY_DRY} 窗口 {@code [45°,165°)}、 分带未配置 ⇒ 全球北半球四季
 * + {@code zoneSource: fallback}）加进本类；改任何缺省值必须另开 D 条目， 不许静默改（设计稿 §九）。
 */
public final class CalendarDefaults {

  /** 缺省历法标识：儒略历。 */
  public static final String CALENDAR_ID = "julian";

  /** tick 0 锚点：儒略 1445-01-01 = JDN 2248845（JDN 由 {@link JulianCalendar#dayNumberOf} 正算）。 */
  public static final CalendarDate EPOCH_DATE = new CalendarDate(1445, 1, 1);

  private CalendarDefaults() {}
}
