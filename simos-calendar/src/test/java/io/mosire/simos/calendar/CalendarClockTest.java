package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.YearFraction;
import org.junit.jupiter.api.Test;

/**
 * 设计稿 §二/§六 与 §十判据 1、4：tick ↔ JDN ↔ 儒略日期的锚点映射、按历法年分段的精确年分数。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>{@link CalendarClock#dayNumberOfTick} 写成 {@code epoch - tick} 或忘了加锚点 ⇒ {@link
 *       #tick120MapsToJulianMayFirst} 红；
 *   <li>{@link CalendarClock#yearFraction} 用固定 365 天做分母、漏掉半开区间右端、或没有逐历年分段 ⇒ {@link
 *       #yearFractionWholeYearsAndPartialYears} 与 {@link
 *       #yearFractionCrossesDifferentCalendarYears} 红；
 *   <li>{@code toTick < fromTick} 未抛 ⇒ {@link #yearFractionRejectsInvertedRange} 红；
 *   <li>{@code CalendarClock.of} 未在构造期用 {@code system.dayNumberOf(epochDate)} 定 epoch ⇒ {@link
 *       #customAnchorBindsEpochAndTickZero} 红。
 * </ul>
 */
class CalendarClockTest {

  private static final CalendarClock CLOCK = CalendarClock.julianDefault();

  /** 判据 1：默认 tick 0 = 儒略 1445-01-01 = JDN 2248845；tick 120 = 1445-05-01、JDN 2248965。 */
  @Test
  void tick120MapsToJulianMayFirst() {
    assertThat(CLOCK.system()).isSameAs(JulianCalendar.INSTANCE);
    assertThat(CLOCK.epochDayNumber()).isEqualTo(2248845L);
    assertThat(CLOCK.epochDate()).isEqualTo(new CalendarDate(1445, 1, 1));

    assertThat(CLOCK.dayNumberOfTick(120)).isEqualTo(2248965L);
    assertThat(CLOCK.dateOfTick(120)).isEqualTo(new CalendarDate(1445, 5, 1));
    assertThat(CLOCK.tickOfDayNumber(2248965L)).isEqualTo(120L);
    assertThat(CLOCK.tickOfDayNumber(CLOCK.dayNumberOfTick(-777L))).isEqualTo(-777L);
  }

  /** 判据 4：整平年 = 365/365 = ONE；整闰年 = 366/366 = ONE；单日/部分年按所在历年分母。 */
  @Test
  void yearFractionWholeYearsAndPartialYears() {
    // (0,365) = 儒略 1445 平年整年（tick 0 → 1446-01-01），365/365 约简为 ONE。
    assertThat(CLOCK.yearFraction(0L, 365L)).isEqualTo(YearFraction.ONE);
    // (-366,0) = 儒略 1444 闰年整年（1444-01-01 → 1445-01-01），366/366 约简为 ONE。
    assertThat(CLOCK.yearFraction(-366L, 0L)).isEqualTo(YearFraction.ONE);

    // tick -1 = 1444-12-31（闰年最后一天）⇒ 1/366；这是“闰年窗口用 366 做分母”的直接判别点。
    assertThat(CLOCK.dateOfTick(-1L)).isEqualTo(new CalendarDate(1444, 12, 31));
    assertThat(CLOCK.yearFraction(-1L, 0L)).isEqualTo(new YearFraction(1L, 366L));
    // 闰年整年减一天：1444-01-01 → 1444-12-31 之前（tick -366 → -1）= 365/366。
    assertThat(CLOCK.dateOfTick(-366L)).isEqualTo(new CalendarDate(1444, 1, 1));
    assertThat(CLOCK.yearFraction(-366L, -1L)).isEqualTo(new YearFraction(365L, 366L));

    // 1445-01-01 起 364 天 = 到 1445-12-31 之前，全部落在平年 ⇒ 364/365（不是 365/366）。
    assertThat(CLOCK.yearFraction(0L, 364L)).isEqualTo(new YearFraction(364L, 365L));
    assertThat(CLOCK.yearFraction(0L, 0L)).isEqualTo(YearFraction.ZERO);
  }

  /**
   * 判据 4：跨历年逐段求和，整年/闰年各自用自己的分母。
   *
   * <p>{@code (364,366)} 的半开区间 = tick 364（1445-12-31，平年 1/365）+ tick 365（1446-01-01，平年 1/365） =
   * 2/365；{@code (-367,-365)} = tick -367（1443-12-31，平年 1/365）+ tick -366（1444-01-01，闰年 1/366） =
   * 1/365 + 1/366 = 731/133590。两段分母不同，钉住实现确实逐历年分段而不是区头年一算到底。
   */
  @Test
  void yearFractionCrossesDifferentCalendarYears() {
    assertThat(CLOCK.dateOfTick(364L)).isEqualTo(new CalendarDate(1445, 12, 31));
    assertThat(CLOCK.dateOfTick(365L)).isEqualTo(new CalendarDate(1446, 1, 1));
    assertThat(CLOCK.dateOfTick(366L)).isEqualTo(new CalendarDate(1446, 1, 2));
    assertThat(CLOCK.yearFraction(364L, 366L)).isEqualTo(new YearFraction(2L, 365L));

    assertThat(CLOCK.dateOfTick(-367L)).isEqualTo(new CalendarDate(1443, 12, 31));
    assertThat(CLOCK.dateOfTick(-366L)).isEqualTo(new CalendarDate(1444, 1, 1));
    assertThat(CLOCK.dateOfTick(-365L)).isEqualTo(new CalendarDate(1444, 1, 2));
    assertThat(CLOCK.yearFraction(-367L, -365L)).isEqualTo(new YearFraction(731L, 133590L));
  }

  /** 判据 4：daysInYearAtTick 平/闰各一；区间倒置抛 IllegalArgumentException。 */
  @Test
  void yearFractionRejectsInvertedRange() {
    assertThat(CLOCK.daysInYearAtTick(0L)).isEqualTo(365); // 1445 平年
    assertThat(CLOCK.daysInYearAtTick(-1L)).isEqualTo(366); // 1444 闰年
    assertThat(CLOCK.daysInYearAtTick(365L)).isEqualTo(365); // 1446 平年
    assertThatThrownBy(() -> CLOCK.yearFraction(1L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 非默认锚点：Julian 2000-01-01 = JDN 2451558；tick 0 就是该日，映射不变。 */
  @Test
  void customAnchorBindsEpochAndTickZero() {
    CalendarClock clock = CalendarClock.of(JulianCalendar.INSTANCE, new CalendarDate(2000, 1, 1));
    assertThat(clock.epochDayNumber()).isEqualTo(2451558L);
    assertThat(clock.epochDate()).isEqualTo(new CalendarDate(2000, 1, 1));
    assertThat(clock.dateOfTick(0L)).isEqualTo(new CalendarDate(2000, 1, 1));
    assertThat(clock.dateOfTick(1L)).isEqualTo(new CalendarDate(2000, 1, 2));
    assertThat(clock.dayNumberOfTick(-1L)).isEqualTo(2451557L);
    assertThat(clock.tickOfDayNumber(2451558L)).isZero();
    // 2000 是儒略闰年：tick 0 → 2001-01-01 恰好 366 天 = ONE。
    assertThat(clock.yearFraction(0L, 366L)).isEqualTo(YearFraction.ONE);
  }
}
