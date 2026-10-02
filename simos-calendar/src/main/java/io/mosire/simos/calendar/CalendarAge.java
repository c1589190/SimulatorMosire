package io.mosire.simos.calendar;

import java.util.Objects;

/**
 * 年龄换算：整历法年数（设计稿 §六.2）。
 *
 * <p>口径：把 birth/current 两个 JDN 换成 {@link CalendarDate} 后做整年差；若 {@code (current.month, current.day)
 * < (birth.month, birth.day)}（按 (月, 日) 字典序）则年数减一。
 *
 * <p><b>2 月 29 日出生的生日惯例</b>：平年 2 月 28 日视为<b>未过</b>生日，3 月 1 日才长一岁 （{@code (2,28) < (2,29)} ⇒
 * 未过；{@code (3,1) > (2,29)} ⇒ 已过）。这是 (月, 日) 字典序比较的自然结果， 不是把生日钳到 2 月 28 日的“提前庆祝”口径。
 */
public final class CalendarAge {

  private CalendarAge() {}

  /**
   * 整历法年数：{@code currentDayNumber < birthDayNumber} 当场抛。
   *
   * @param system 历法系统
   * @param birthDayNumber 出生日 JDN 整数
   * @param currentDayNumber 当前日 JDN 整数
   * @return 已满的整历法年数
   * @throws IllegalArgumentException {@code currentDayNumber < birthDayNumber}
   */
  public static long ageInYears(CalendarSystem system, long birthDayNumber, long currentDayNumber) {
    Objects.requireNonNull(system, "system");
    if (currentDayNumber < birthDayNumber) {
      throw new IllegalArgumentException(
          "currentDayNumber 不得早于 birthDayNumber：" + birthDayNumber + " → " + currentDayNumber);
    }
    CalendarDate birth = system.dateOf(birthDayNumber);
    CalendarDate current = system.dateOf(currentDayNumber);
    long years = current.year() - birth.year();
    if (current.month() < birth.month()
        || (current.month() == birth.month() && current.day() < birth.day())) {
      years--;
    }
    return years;
  }
}
