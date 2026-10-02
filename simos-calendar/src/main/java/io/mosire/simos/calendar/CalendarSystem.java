package io.mosire.simos.calendar;

/**
 * 历法系统：连续日轴（JDN 整数）与「某历法下的年/月/日」之间的纯换算契约。
 *
 * <p>{@code dayNumber} = <b>JDN 整数</b>（与该民用日对应），历法无关的连续日轴；{@code year} 用天文年编号 （可 ≤ 0），月/日从 1
 * 起。实现必须是纯计算、无状态；<b>不得</b>换用 {@link java.time.LocalDate} （它是先行格里高利历，1445 年与儒略历差 9 天，见设计稿 §三）。
 *
 * <p>接口按“多种历法”设计：公元/儒略只是第一种实现（D-018 裁定 3）。
 */
public interface CalendarSystem {

  /** 历法标识（儒略历固定为 {@code "julian"}）。 */
  String id();

  /**
   * JDN 整数 → 该历法下的年月日。
   *
   * @param dayNumber JDN 整数
   * @return 对应的日历日期（月/日 1 起）
   */
  CalendarDate dateOf(long dayNumber);

  /**
   * CalendarDate → JDN 整数（{@link #dateOf(long)} 的逆变换）。
   *
   * @param date 日历日期
   * @return 对应的 JDN 整数
   * @throws IllegalArgumentException 月/日非法（含“2 月 30 日”这类月内越界）
   */
  long dayNumberOf(CalendarDate date);

  /** 该历法年是否闰年（儒略历：4 年一闰、无世纪例外）。 */
  boolean isLeapYear(long year);

  /**
   * 该年该月的天数。
   *
   * @throws IllegalArgumentException month 不在 [1,12]
   */
  int daysInMonth(long year, int month);

  /** 该历法年的天数（365 或 366）。 */
  int daysInYear(long year);

  /**
   * 一年中的第几天（1..365/366）。
   *
   * @throws IllegalArgumentException 月/日非法（含“2 月 30 日”这类月内越界）
   */
  int dayOfYear(CalendarDate date);
}
