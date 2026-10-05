package io.mosire.simos.social.provisioning;

/**
 * ★★ <b>需求系数的时间口径</b>（2026-10-09 家户结构修复计划 §3.2 / §3.5）。
 *
 * <p>它回答的是「{@link DemandCoefficient#amountMilli()} 是每人每多久的量」——<b>不是</b>"每人每天多少"。 把分子/分母退化成一个"每人每天
 * 83 毫粮"式的日均值，会在定义上丢掉周期残差（计划 §3.2 明令禁止）； 逐日展开必须由 {@link SocialProvisioning} 与 {@code SocialData}
 * 按本口径做"家户层一次取整"。
 *
 * <p>本枚举只表达口径的<b>种类</b>；具体的周期天数在 {@link DemandCoefficient#cycleDays()} 里：
 *
 * <ul>
 *   <li>{@link #PER_CYCLE_DAYS} ⇒ {@code cycleDays >= 1}；当日份 = 累计(day) − 累计(day−1)， 累计 = {@code
 *       total × day / cycleDays}（只在家户层取整一次）；
 *   <li>{@link #PER_CALENDAR_YEAR} ⇒ {@code cycleDays == 0}（不参与），年长由 {@code
 *       CalendarClock.yearFraction(day, day+1)} 注入（平年 365 / 闰年 366）。
 * </ul>
 *
 * <p>★ <b>枚举名就是它的稳定拼写</b>（线格式由 {@code SocialCodec} 负责；本类型零 Jackson 注解）：
 * 词表只允许新增档，不允许改名——改名等于把已落盘的系数表读死。
 */
public enum DemandPeriod {

  /** 每人每 {@code cycleDays} 天 {@code amountMilli}：粮等按固定周期计量的需求走这一档。 */
  PER_CYCLE_DAYS,

  /** 每人每<b>历法年</b> {@code amountMilli}：布等按年计量的需求走这一档；年长由历法时钟现算。 */
  PER_CALENDAR_YEAR
}
