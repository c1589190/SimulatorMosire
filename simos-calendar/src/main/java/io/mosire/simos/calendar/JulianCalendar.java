package io.mosire.simos.calendar;

import java.util.Objects;

/**
 * 儒略历（全期）实现：4 年一闰、<b>无世纪例外</b>——1500/1600/1700/1900/2000 都是闰年。
 *
 * <p>本类只做纯整数换算（正反算都不调用 {@link java.time.LocalDate}），日轴约定为 <b>JDN 整数</b>。 {@code year} 用 long
 * 承载，除法一律 {@link Math#floorDiv(long, long)}（防负数年份向零截断出错）。 公式与实测锚点写在这里，供 C7 测试代理当 oracle：
 *
 * <ul>
 *   <li><b>正算</b>（CalendarDate → JDN）：{@code a = (14 - month) / 12; y = year + 4800 - a; m = month
 *       + 12a - 3; JDN = day + (153m + 2)/5 + 365y + y/4 - 32083}（整除）。
 *   <li><b>反算</b>（JDN → CalendarDate）：{@code c = J + 32082; d = (4c + 3)/1461; e = c - 1461d/4; m =
 *       (5e + 2)/153; day = e - (153m + 2)/5 + 1; month = m + 3 - 12(m/10); year = d - 4800 +
 *       m/10}。
 *   <li><b>锚点</b>：儒略 {@code 1445-01-01 = JDN 2248845}；{@code 2248845 + 120 = 1445-05-01}； 儒略 {@code
 *       2000-01-01 = JDN 2451558}（格里高利 2000-01-01 = 2451545，二者相差 13 天）； 儒略 {@code 1582-10-04 = JDN
 *       2299160}、{@code 1582-10-15 = JDN 2299171}（儒略历无 10 天跳变，两日相邻）。
 * </ul>
 */
public final class JulianCalendar implements CalendarSystem {

  /** 历法标识。 */
  public static final String ID = "julian";

  /** 无状态单例。 */
  public static final JulianCalendar INSTANCE = new JulianCalendar();

  private JulianCalendar() {}

  @Override
  public String id() {
    return ID;
  }

  /**
   * 反算：JDN 整数 → 儒略历日期。
   *
   * <p>{@code c = J + 32082; d = (4c + 3)/1461; e = c - 1461d/4; m = (5e + 2)/153; day = e - (153m
   * + 2)/5 + 1; month = m + 3 - 12(m/10); year = d - 4800 + m/10}（全部 floorDiv）。
   */
  @Override
  public CalendarDate dateOf(long dayNumber) {
    long c = dayNumber + 32082;
    long d = Math.floorDiv(4 * c + 3, 1461);
    long e = c - Math.floorDiv(1461 * d, 4);
    long m = Math.floorDiv(5 * e + 2, 153);
    long day = e - Math.floorDiv(153 * m + 2, 5) + 1;
    long month = m + 3 - 12 * Math.floorDiv(m, 10);
    long year = d - 4800 + Math.floorDiv(m, 10);
    return new CalendarDate(year, Math.toIntExact(month), Math.toIntExact(day));
  }

  /**
   * 正算：儒略历日期 → JDN 整数。
   *
   * <p>{@code a = (14 - month)/12; y = year + 4800 - a; m = month + 12a - 3; JDN = day + (153m +
   * 2)/5 + 365y + y/4 - 32083}（全部 floorDiv）。
   *
   * <p>这里才做月内日校验（{@link CalendarDate} 只能保证 day ∈ [1,31]）：2 月 30 日、4 月 31 日等当场抛。
   *
   * @throws IllegalArgumentException 月/日非法
   */
  @Override
  public long dayNumberOf(CalendarDate date) {
    Objects.requireNonNull(date, "date");
    int maxDay = daysInMonth(date.year(), date.month()); // month 非法在这里也当场抛
    if (date.day() > maxDay) {
      throw new IllegalArgumentException(
          "非法的 " + date.year() + "-" + date.month() + "-" + date.day() + "：该月只有 " + maxDay + " 天");
    }
    long a = Math.floorDiv(14L - date.month(), 12);
    long y = date.year() + 4800 - a;
    long m = date.month() + 12 * a - 3;
    return date.day() + Math.floorDiv(153 * m + 2, 5) + 365 * y + Math.floorDiv(y, 4) - 32083;
  }

  /** 儒略历闰年：{@code year % 4 == 0}，无世纪例外（1500/1700/1900 也是闰年）。 */
  @Override
  public boolean isLeapYear(long year) {
    return Math.floorMod(year, 4) == 0;
  }

  /**
   * 该年该月的天数；2 月按 {@code year % 4 == 0 ? 29 : 28}。
   *
   * @throws IllegalArgumentException month 不在 [1,12]
   */
  @Override
  public int daysInMonth(long year, int month) {
    return switch (month) {
      case 1, 3, 5, 7, 8, 10, 12 -> 31;
      case 4, 6, 9, 11 -> 30;
      case 2 -> isLeapYear(year) ? 29 : 28;
      default -> throw new IllegalArgumentException("month 必须在 [1,12]：" + month);
    };
  }

  /** 该历法年的天数；闰年 366、平年 365。 */
  @Override
  public int daysInYear(long year) {
    return isLeapYear(year) ? 366 : 365;
  }

  /**
   * 一年中的第几天（1..365/366）= 当日 JDN − 该年 1 月 1 日 JDN + 1。
   *
   * @throws IllegalArgumentException 月/日非法
   */
  @Override
  public int dayOfYear(CalendarDate date) {
    Objects.requireNonNull(date, "date");
    long firstDayOfYear = dayNumberOf(new CalendarDate(date.year(), 1, 1));
    return Math.toIntExact(dayNumberOf(date) - firstDayOfYear + 1);
  }
}
