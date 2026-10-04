package io.mosire.simos.calendar;

import io.mosire.simos.util.time.YearFraction;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 世界绑定的历法时钟：{@code CalendarSystem} + tick 0 锚点（JDN）。
 *
 * <p>映射恒为 {@code dayNumberOfTick(tick) = epochDayNumber + tick}、{@code tickOfDayNumber(day) = day −
 * epochDayNumber}（1 tick = 1 天，设计稿 §二）。换世界/换历法只换锚点 + {@code CalendarSystem}，tick 映射不变。
 *
 * <p>缺省世界锚点见 {@link CalendarDefaults#EPOCH_DATE}（儒略 1445-01-01 = JDN 2248845）。
 */
public final class CalendarClock {

  private static final Logger LOG = CalendarLog.clock();

  private final CalendarSystem system;
  private final CalendarDate epochDate;
  private final long epochDayNumber;

  private CalendarClock(CalendarSystem system, CalendarDate epochDate, long epochDayNumber) {
    this.system = system;
    this.epochDate = epochDate;
    this.epochDayNumber = epochDayNumber;
  }

  /**
   * 绑定历法与锚点；构造期立即算出 {@code epochDayNumber = system.dayNumberOf(epochDate)}（非法锚点当场抛）。
   *
   * @throws IllegalArgumentException 锚点在该历法下非法（如 2 月 30 日）
   */
  public static CalendarClock of(CalendarSystem system, CalendarDate epochDate) {
    Objects.requireNonNull(system, "system");
    Objects.requireNonNull(epochDate, "epochDate");
    CalendarClock clock = new CalendarClock(system, epochDate, system.dayNumberOf(epochDate));
    LOG.debug(
        "event=CALENDAR_CLOCK_BOUND system={} epoch={} epochDayNumber={}",
        system.id(),
        epochDate,
        clock.epochDayNumber());
    return clock;
  }

  /** 缺省世界：{@link JulianCalendar#INSTANCE} + {@link CalendarDefaults#EPOCH_DATE}。 */
  public static CalendarClock julianDefault() {
    return of(JulianCalendar.INSTANCE, CalendarDefaults.EPOCH_DATE);
  }

  /** 绑定的历法系统。 */
  public CalendarSystem system() {
    return system;
  }

  /** tick 0 的 JDN 整数。 */
  public long epochDayNumber() {
    return epochDayNumber;
  }

  /** tick 0 的历法日期（与 {@link #of(CalendarSystem, CalendarDate)} 传入的一致）。 */
  public CalendarDate epochDate() {
    return epochDate;
  }

  /** tick → 历法日期：{@code system.dateOf(epochDayNumber + tick)}。 */
  public CalendarDate dateOfTick(long tick) {
    return system.dateOf(epochDayNumber + tick);
  }

  /** tick → JDN 整数：{@code epochDayNumber + tick}。 */
  public long dayNumberOfTick(long tick) {
    return epochDayNumber + tick;
  }

  /** JDN 整数 → tick：{@code dayNumber − epochDayNumber}。 */
  public long tickOfDayNumber(long dayNumber) {
    return dayNumber - epochDayNumber;
  }

  /** 该 tick 所属历法年的天数（365/366）。 */
  public int daysInYearAtTick(long tick) {
    return system.daysInYear(dateOfTick(tick).year());
  }

  /**
   * 半开区间 {@code [fromTick, toTick)} 跨过的「历法年分数」的<b>精确有理数</b>。
   *
   * <p>实现：从 {@code dateOfTick(fromTick).year()} 到 {@code dateOfTick(toTick).year()} 逐历法年，
   * 把区间与该年日号范围 {@code [yearStartDay, yearStartDay + daysInYear(year))} 的重叠天数作为分子、 该年天数作为分母，用 {@link
   * YearFraction#add(YearFraction)} 精确累加；整年 = {@link YearFraction#ONE}， 闰年窗口用 366 作分母。例：跨平年整年 =
   * 365/365（约简为 1/1）；只跨平年最后一天 = 1/365。
   *
   * <p>区间是半开的：{@code toTick} 当天不计入。{@code fromTick == toTick} ⇒ {@link YearFraction#ZERO}。
   *
   * @throws IllegalArgumentException {@code toTick < fromTick}
   */
  public YearFraction yearFraction(long fromTick, long toTick) {
    if (toTick < fromTick) {
      throw new IllegalArgumentException("toTick 不得小于 fromTick：" + fromTick + " → " + toTick);
    }
    if (fromTick == toTick) {
      return YearFraction.ZERO;
    }
    long fromDayNumber = dayNumberOfTick(fromTick);
    long toDayNumberExclusive = dayNumberOfTick(toTick);
    long firstYear = system.dateOf(fromDayNumber).year();
    long lastYear = system.dateOf(toDayNumberExclusive).year();

    YearFraction total = YearFraction.ZERO;
    for (long year = firstYear; year <= lastYear; year++) {
      long yearStartDay = system.dayNumberOf(new CalendarDate(year, 1, 1));
      int daysInYear = system.daysInYear(year);
      long yearEndDayExclusive = yearStartDay + daysInYear;
      long overlapStart = Math.max(fromDayNumber, yearStartDay);
      long overlapEnd = Math.min(toDayNumberExclusive, yearEndDayExclusive);
      if (overlapEnd > overlapStart) {
        total = total.add(new YearFraction(overlapEnd - overlapStart, daysInYear));
      }
    }
    return total;
  }
}
