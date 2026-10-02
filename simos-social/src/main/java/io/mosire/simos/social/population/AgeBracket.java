package io.mosire.simos.social.population;

import io.mosire.simos.calendar.CalendarAge;
import io.mosire.simos.calendar.CalendarSystem;

/**
 * ★★ **年龄档**（第三阶段设计稿 §三）：{@code 0-14 / 15-59 / 60+} 三档，D4 preset 的原口径。
 *
 * <p>★★ **它是"查询期的聚合档"，不是运行时模型**（设计稿 §三 原文：「年龄段只在查询时聚合（读口/GM 面板按需算 0-14/15-59/60+）；
 * 任何"档间转移"都不需要——因为没有档」）。批次身上只有**逐日精度**的年龄（{@link PopulationGroup#ageDaysAt(long)} 现算）， 落哪一档由 {@link
 * #of(CalendarSystem, long, long)} 在查询的那一刻算 —— 故本枚举**不得**被写进 {@link PopulationGroup}（那会让档位变成第二份真相）。
 *
 * <p>★★ **本枚举是年龄档边界的唯一定义处**（"不要另写一套边"）：{@link #UPPER_BOUNDS} 是全仓唯一的字面量， 经济侧的创世 preset（{@code
 * EconomySeeder.ageBracketOf}）**转调**这里 ——否则"批次按一套边造、劳动按另一套边折算"会被两张表悄悄漂开（本仓最忌"注释声称一致、其实不一致"）。 ★ 词表序
 * = {@code 0-14 → 15-59 → 60+} = 经济侧 {@code AGE_SHARE_PER_MILLE { 350, 550, 100}} 的序 = {@link
 * #UPPER_BOUNDS} 的序，三处**同序**是硬约定。
 *
 * <p>★★ **15/60 是整历法年**（设计稿 §六.2；C4b 起）：上界字面量就是 {@code {15, 60}}，不再乘固定 365 —— 闰年不影响"第几个生日"，
 * 逐日年龄到整岁的换算走 {@link CalendarAge}（2/29 出生的生日惯例一并委托它：平年 2/28 未过、3/1 已过 ⇒ 平年 3/1 长一岁）。
 *
 * <p>★ **{@link #key()} 就是它的稳定拼写**：读口（GUI / MCP）按它发键。它进 JSON、是给人看的那个"档名"，故不做大小写转换。
 */
public enum AgeBracket {

  /** 未成年（0-14 岁）。 */
  CHILD("0-14"),

  /** 青壮年（15-59 岁）。 */
  ADULT("15-59"),

  /** 老年（60 岁及以上）。 */
  ELDER("60+");

  /**
   * 各档的**上界**（整历法年，不含；{@code 15 岁}、{@code 60 岁}）：序与词表同，**末档无上界**故表长 = 词表长 − 1。
   *
   * <p>★ 单位是**整历法年**，不是固定 365 天的"年"：15 岁 = 第 15 个生日（2/29 出生惯例见 {@link CalendarAge}）， 由 {@link
   * #of(CalendarSystem, long, long)} 把逐日年龄换算成整岁后再比对上界。
   */
  private static final long[] UPPER_BOUNDS = {15L, 60L};

  private final String key;

  AgeBracket(String key) {
    this.key = key;
  }

  /** 档名（读口发出去的键）：{@code 0-14} / {@code 15-59} / {@code 60+}。 */
  public String key() {
    return key;
  }

  /**
   * 某个**整历法年**的年龄落在哪一档：依次与上界比，超出全部上界 ⇒ 末档（{@link #ELDER}，年龄没有上界）。
   *
   * @throws IllegalArgumentException {@code ageYears < 0}（负年龄是坏数据，不静默归档）
   */
  public static AgeBracket ofYears(long ageYears) {
    if (ageYears < 0L) {
      throw new IllegalArgumentException("ageYears 不得为负: " + ageYears);
    }
    for (int bracket = 0; bracket < UPPER_BOUNDS.length; bracket++) {
      if (ageYears < UPPER_BOUNDS[bracket]) {
        return values()[bracket];
      }
    }
    return ELDER;
  }

  /**
   * 某个**逐日精度**的年龄（天）落在哪一档：先按历法把出生日到当前日的年龄换算成**整历法年**， 再交给 {@link #ofYears(long)} 比对 15/60 上界。
   *
   * <p>★ 换算固定走 {@code birthDayNumber = currentDayNumber − ageDays} ⇒ {@link
   * CalendarAge#ageInYears(CalendarSystem, long, long)}；**2/29 出生的生日惯例完全委托 {@code CalendarAge}**（平年
   * 2/28 未过、3/1 已过 ⇒ 平年 3/1 长一岁），本枚举不另写一套闰日规则。
   *
   * @param system 历法系统（逐日年龄 → 整历法年的换算口径）
   * @param currentDayNumber 当前日 JDN 整数（{@code CalendarClock.dayNumberOfTick(nowTick)} 是 tick→JDN
   *     的唯一换算入口）
   * @param ageDays 出生日到当前日的天数（不得为负）
   * @throws IllegalArgumentException {@code ageDays < 0}（负年龄是坏数据，不静默归档）
   */
  public static AgeBracket of(CalendarSystem system, long currentDayNumber, long ageDays) {
    if (system == null) {
      throw new IllegalArgumentException("system 不得为 null");
    }
    if (ageDays < 0L) {
      throw new IllegalArgumentException("ageDays 不得为负: " + ageDays);
    }
    long birthDayNumber = Math.subtractExact(currentDayNumber, ageDays);
    long ageYears = CalendarAge.ageInYears(system, birthDayNumber, currentDayNumber);
    return ofYears(ageYears);
  }

  /**
   * 各档上界的**副本**（整历法年，不含；序与词表同，不含无上界的末档）——给需要"按档迭代"的调用方（经济侧的创世折算）。
   *
   * <p>★ **每次新造一份**：数组是可变对象，共享它等于对外开一个改参数的后门（{@code SpotBugs} 实测报过同族问题）。
   */
  public static long[] boundedMaxExclusiveYears() {
    return UPPER_BOUNDS.clone();
  }
}
