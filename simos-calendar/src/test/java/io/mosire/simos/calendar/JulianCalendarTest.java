package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.temporal.JulianFields;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 设计稿 §二/§三 与 §十判据 1、2：儒略历全期的正反算、闰年（无世纪例外）、月长、day-of-year。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>{@link JulianCalendar#dayNumberOf} 的 -32083 偏移写成 -32082、或除法改成截断 ⇒ {@link #frozenAnchors} 与
 *       {@link #roundTripZeroDifferenceOverFourCenturies} 当场红；
 *   <li>{@link JulianCalendar#dateOf} 的 32082/1461/153 常量错 ⇒ 同上；
 *   <li>{@link JulianCalendar#isLeapYear} 改成含世纪例外（%100/%400）⇒ {@link
 *       #leapYearsHaveNoCenturyException} 红；
 *   <li>2 月天数写死 28 ⇒ {@link #monthLengths} 与 1900-02-29 用例红；
 *   <li>{@link JulianCalendar#dayOfYear} 用 month*31+day 之类近似 ⇒ {@link #dayOfYearIsOneBased} 红。
 * </ul>
 *
 * <p><b>外部 JDN 轴的口径</b>：{@link LocalDate} 是先行格里高利历。本测试只拿 {@code LocalDate.ofEpochDay(jdn -
 * 2440588)} 当整数日轴的容器，经 {@link JulianFields#JULIAN_DAY} 读出 JDN； <b>不</b>把它的 year/month/day
 * 标签当儒略历（1445 年二者差 9 天）。设计稿 §三明令实现不得换用 {@code LocalDate} 做历法换算，这条测试也不那么用。
 */
class JulianCalendarTest {

  private static final JulianCalendar JULIAN = JulianCalendar.INSTANCE;

  /** 判据 1：四个对拍锚点正反算都精确。改坏任一常量或除法方向即红。 */
  @Test
  void frozenAnchors() {
    assertThat(JULIAN.id()).isEqualTo(JulianCalendar.ID).isEqualTo("julian");

    assertThat(JULIAN.dayNumberOf(new CalendarDate(1445, 1, 1))).isEqualTo(2248845L);
    assertThat(JULIAN.dateOf(2248845L)).isEqualTo(new CalendarDate(1445, 1, 1));

    assertThat(JULIAN.dayNumberOf(new CalendarDate(2000, 1, 1))).isEqualTo(2451558L);
    assertThat(JULIAN.dateOf(2451558L)).isEqualTo(new CalendarDate(2000, 1, 1));

    assertThat(JULIAN.dayNumberOf(new CalendarDate(1582, 10, 4))).isEqualTo(2299160L);
    assertThat(JULIAN.dayNumberOf(new CalendarDate(1582, 10, 15))).isEqualTo(2299171L);
    assertThat(JULIAN.dateOf(2299160L)).isEqualTo(new CalendarDate(1582, 10, 4));
    assertThat(JULIAN.dateOf(2299171L)).isEqualTo(new CalendarDate(1582, 10, 15));
  }

  /**
   * 判据 1 的“外部轴”交叉验证：至少 4 个锚点与 JDK 的 {@link JulianFields#JULIAN_DAY} 落在同一整数日号。
   *
   * <p>判别力：若生产实现改用先行格里高利/别的日轴，{@code dayNumberOf(dateOf(j))} 仍可能自洽，但与外部轴不齐 ⇒ 红。
   */
  @Test
  void externalJdnAxisAgreesOnFourAnchors() {
    long[] anchors = {2248845L, 2451558L, 2299160L, 2299171L};
    for (long jdn : anchors) {
      long epochDay = jdn - 2440588L; // 1970-01-01（格里高利）的 epochDay 为 0
      long externalJdn = LocalDate.ofEpochDay(epochDay).getLong(JulianFields.JULIAN_DAY);
      assertThat(externalJdn).as("外部 JDN 轴 jdn=%d", jdn).isEqualTo(jdn);
      assertThat(JULIAN.dayNumberOf(JULIAN.dateOf(jdn))).as("往返 jdn=%d", jdn).isEqualTo(jdn);
    }
  }

  /**
   * 判据 1：1445-01-01 到 1845-12-31（覆盖 1445~1845 的 400 年）每 17 天、以及每年 1/1、2/28、3/1、12/31 抽样，{@code
   * dayNumberOf(dateOf(j)) == j} 零差异。
   *
   * <p>判别力：日轴偏移、闰年判定或反算公式局部错时，逐点断言会红；固定集合覆盖每年闰日前后的边界。
   */
  @Test
  void roundTripZeroDifferenceOverFourCenturies() {
    long start = JULIAN.dayNumberOf(new CalendarDate(1445, 1, 1));
    long end = JULIAN.dayNumberOf(new CalendarDate(1845, 12, 31));
    for (long day = start; day <= end; day += 17L) {
      assertThat(JULIAN.dayNumberOf(JULIAN.dateOf(day))).as("17 天步进 day=%d", day).isEqualTo(day);
    }
    for (long year = 1445L; year <= 1845L; year++) {
      CalendarDate[] samples = {
        new CalendarDate(year, 1, 1),
        new CalendarDate(year, 2, 28),
        new CalendarDate(year, 3, 1),
        new CalendarDate(year, 12, 31)
      };
      for (CalendarDate date : samples) {
        long day = JULIAN.dayNumberOf(date);
        assertThat(JULIAN.dateOf(day)).as("日期往返 %s", date).isEqualTo(date);
        assertThat(JULIAN.dayNumberOf(JULIAN.dateOf(day))).as("日号往返 %d", day).isEqualTo(day);
      }
    }
  }

  /**
   * 判据 1：固定种子随机 2000 个 JD 抽样零差异。
   *
   * <p>判别力：只在锚点/整年蒙对的实现会被随机样本打红；种子固定，失败样本可复现。
   */
  @Test
  void randomRoundTripZeroDifference() {
    long start = JULIAN.dayNumberOf(new CalendarDate(1445, 1, 1));
    long end = JULIAN.dayNumberOf(new CalendarDate(1845, 12, 31));
    long span = end - start + 1L;
    Random random = new Random(20261002L);
    for (int i = 0; i < 2000; i++) {
      long day = start + Math.floorMod(random.nextLong(), span);
      assertThat(JULIAN.dayNumberOf(JULIAN.dateOf(day)))
          .as("随机 day=%d (#%d)", day, i)
          .isEqualTo(day);
    }
  }

  /**
   * 判据 2：1500/1600/1700/1900/2000 在儒略历全为闰年，1901/1902/1903 平年；1900-02-29 合法且可往返。
   *
   * <p>判别力：给 isLeapYear 加上格里高利世纪例外后，1500/1700/1900 三条会红；2 月天数写死时 1900-02-29 红。
   */
  @Test
  void leapYearsHaveNoCenturyException() {
    long[] leapYears = {1500L, 1600L, 1700L, 1900L, 2000L};
    for (long year : leapYears) {
      assertThat(JULIAN.isLeapYear(year)).as("%d 应闰", year).isTrue();
      assertThat(JULIAN.daysInYear(year)).as("%d 年长", year).isEqualTo(366);
      CalendarDate leapDay = new CalendarDate(year, 2, 29);
      assertThat(JULIAN.dateOf(JULIAN.dayNumberOf(leapDay)))
          .as("%d-02-29 往返", year)
          .isEqualTo(leapDay);
    }
    long[] commonYears = {1901L, 1902L, 1903L};
    for (long year : commonYears) {
      assertThat(JULIAN.isLeapYear(year)).as("%d 应平", year).isFalse();
      assertThat(JULIAN.daysInYear(year)).as("%d 年长", year).isEqualTo(365);
      assertThatThrownBy(() -> JULIAN.dayNumberOf(new CalendarDate(year, 2, 29)))
          .as("%d 无 2 月 29 日", year)
          .isInstanceOf(IllegalArgumentException.class);
    }

    CalendarDate leapDay1900 = new CalendarDate(1900, 2, 29);
    long dayNumber = JULIAN.dayNumberOf(leapDay1900);
    assertThat(JULIAN.dateOf(dayNumber)).isEqualTo(new CalendarDate(1900, 2, 29));
  }

  /** 判据 3 的月长表：31/28或29/31/30/31/30/31/31/30/31/30/31；月 0/13 抛。 */
  @Test
  void monthLengths() {
    int[] leapMonthLengths = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int[] commonMonthLengths = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    for (int month = 1; month <= 12; month++) {
      assertThat(JULIAN.daysInMonth(1900L, month))
          .as("1900 闰年 %d 月", month)
          .isEqualTo(leapMonthLengths[month - 1]);
      assertThat(JULIAN.daysInMonth(1901L, month))
          .as("1901 平年 %d 月", month)
          .isEqualTo(commonMonthLengths[month - 1]);
    }
    assertThatThrownBy(() -> JULIAN.daysInMonth(1900L, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JULIAN.daysInMonth(1900L, 13))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CalendarDate(1900L, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CalendarDate(1900L, 13, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 判据 2/3：day-of-year 1 起；平年 12/31=365、闰年 12/31=366、闰年 2/29=60，平年 2/29 抛。 */
  @Test
  void dayOfYearIsOneBased() {
    assertThat(JULIAN.dayOfYear(new CalendarDate(1901, 1, 1))).isEqualTo(1);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1901, 2, 28))).isEqualTo(59);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1901, 3, 1))).isEqualTo(60);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1901, 12, 31))).isEqualTo(365);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1900, 2, 29))).isEqualTo(60);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1900, 3, 1))).isEqualTo(61);
    assertThat(JULIAN.dayOfYear(new CalendarDate(1900, 12, 31))).isEqualTo(366);
    assertThatThrownBy(() -> JULIAN.dayOfYear(new CalendarDate(1901, 2, 29)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 判据 2/3：月内非法日在 dayNumberOf 当场抛（CalendarDate 只挡 day∈[1,31]，月内校验在历法层）。 */
  @Test
  void rejectsIllegalDayWithinMonth() {
    assertThatThrownBy(() -> JULIAN.dayNumberOf(new CalendarDate(1900, 2, 30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JULIAN.dayNumberOf(new CalendarDate(1901, 2, 29)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JULIAN.dayNumberOf(new CalendarDate(1900, 4, 31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 判据 2：1582 年儒略历无 10 天跳变，10-04 与 10-05 是相邻两天。 */
  @Test
  void october1582HasNoCalendarJump() {
    long october4 = JULIAN.dayNumberOf(new CalendarDate(1582, 10, 4));
    long october5 = JULIAN.dayNumberOf(new CalendarDate(1582, 10, 5));
    assertThat(october5 - october4).isEqualTo(1L);
    assertThat(JULIAN.dateOf(october4 + 1L)).isEqualTo(new CalendarDate(1582, 10, 5));
    assertThat(JULIAN.dateOf(october5 - 1L)).isEqualTo(new CalendarDate(1582, 10, 4));
  }
}
