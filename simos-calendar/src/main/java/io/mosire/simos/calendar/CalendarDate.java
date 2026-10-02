package io.mosire.simos.calendar;

/**
 * 某历法下的一天：{@code year}/{@code month}/{@code day}，月、日从 1 起。
 *
 * <p>{@code year} 用天文年编号（可为 0 与负数，不做「公元前/公元」换算）；显示层再决定怎么出“公元前/公元”。 世界实际只用 1444+，但换算本身不设“无 0
 * 年”阻断（设计稿 §二）。
 *
 * <p>构造期只校验月 ∈ [1,12]、日 ∈ [1,31]——<b>不在这里查当月天数</b>，因为那需要 {@link CalendarSystem} （闰年 2 月、大小月）；「2 月
 * 30 日」这类月内非法值由 {@link CalendarSystem#dayNumberOf(CalendarDate)} 与 {@link
 * CalendarSystem#daysInMonth(long, int)} 当场抛 IllegalArgumentException。
 */
public record CalendarDate(long year, int month, int day) {

  /**
   * @throws IllegalArgumentException month 不在 [1,12]，或 day 不在 [1,31]
   */
  public CalendarDate {
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("month 必须在 [1,12]：" + month);
    }
    if (day < 1 || day > 31) {
      throw new IllegalArgumentException("day 必须在 [1,31]：" + day);
    }
  }
}
