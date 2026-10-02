package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 设计稿 §六.2 与 §十判据 4：年龄 = 已满的整历法年；2/29 出生在平年 2/28 未过、3/1 已过。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>{@link CalendarAge#ageInYears} 用固定 365 天换算 ⇒ {@link #fifteenthBirthdayIsTheBoundary} 与
 *       {@link #sixtiethBirthdayIsTheBoundary} 会因闰年累计差而偏；
 *   <li>生日比较写成 {@code <=} 或按 day-of-year 比较 ⇒ 生日当天/前一天各一条必红；
 *   <li>2/29 惯例写成“钳到 2/28”⇒ 2001-02-28 会误报 1 岁、{@link #leapDayBirthdayGrowsOnMarchFirst} 红；
 *   <li>忘了 {@code current < birth} 护栏 ⇒ {@link #rejectsCurrentBeforeBirth} 红。
 * </ul>
 */
class CalendarAgeTest {

  private static final JulianCalendar JULIAN = JulianCalendar.INSTANCE;

  /** 判据 4：15 岁生日当天 = 15、前一天 = 14；用 2000-01-01 → 2015-01-01 的具体 JDN。 */
  @Test
  void fifteenthBirthdayIsTheBoundary() {
    long birth = 2451558L; // 儒略 2000-01-01
    long dayBefore = 2457036L; // 儒略 2014-12-31
    long birthday = 2457037L; // 儒略 2015-01-01
    assertThat(JULIAN.dateOf(birth)).isEqualTo(new CalendarDate(2000, 1, 1));
    assertThat(JULIAN.dateOf(dayBefore)).isEqualTo(new CalendarDate(2014, 12, 31));
    assertThat(JULIAN.dateOf(birthday)).isEqualTo(new CalendarDate(2015, 1, 1));

    assertThat(CalendarAge.ageInYears(JULIAN, birth, dayBefore)).isEqualTo(14L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, birthday)).isEqualTo(15L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, birthday + 1L)).isEqualTo(15L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, birth)).isZero();
  }

  /** 判据 4：60 岁档同理（2000-01-01 → 2060-01-01；区间含 2000~2059 的 15 个儒略闰年）。 */
  @Test
  void sixtiethBirthdayIsTheBoundary() {
    long birth = 2451558L; // 儒略 2000-01-01
    long dayBefore = 2473472L; // 儒略 2059-12-31
    long birthday = 2473473L; // 儒略 2060-01-01
    assertThat(JULIAN.dateOf(dayBefore)).isEqualTo(new CalendarDate(2059, 12, 31));
    assertThat(JULIAN.dateOf(birthday)).isEqualTo(new CalendarDate(2060, 1, 1));

    assertThat(CalendarAge.ageInYears(JULIAN, birth, dayBefore)).isEqualTo(59L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, birthday)).isEqualTo(60L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, birthday + 1L)).isEqualTo(60L);
  }

  /**
   * 判据 4 的 2/29 惯例：2000-02-29 出生，2001-02-28 仍 0 岁，2001-03-01 才 1 岁；闰年生日当天 4 岁。
   *
   * <p>判别力：把 (2,28) 当成“已过生日”或用 365 天近似，都会让 2001-02-28 那条红。
   */
  @Test
  void leapDayBirthdayGrowsOnMarchFirst() {
    long birth = 2451617L; // 儒略 2000-02-29
    assertThat(JULIAN.dateOf(birth)).isEqualTo(new CalendarDate(2000, 2, 29));
    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2451617L)).isZero();

    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2451982L)).isZero(); // 2001-02-28 未过
    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2451983L)).isEqualTo(1L); // 2001-03-01 已过

    assertThat(JULIAN.dateOf(2453077L)).isEqualTo(new CalendarDate(2004, 2, 28));
    assertThat(JULIAN.dateOf(2453078L)).isEqualTo(new CalendarDate(2004, 2, 29));
    assertThat(JULIAN.dateOf(2453079L)).isEqualTo(new CalendarDate(2004, 3, 1));
    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2453077L)).isEqualTo(3L);
    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2453078L)).isEqualTo(4L); // 生日当天长一岁
    assertThat(CalendarAge.ageInYears(JULIAN, birth, 2453079L)).isEqualTo(4L);
  }

  /** {@code current < birth} 当场抛 IllegalArgumentException（负年龄不是可返回的值）。 */
  @Test
  void rejectsCurrentBeforeBirth() {
    assertThatThrownBy(() -> CalendarAge.ageInYears(JULIAN, 2451983L, 2451982L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CalendarAge.ageInYears(JULIAN, 2451558L, 2451557L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
