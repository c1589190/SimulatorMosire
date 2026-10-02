package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.calendar.CalendarAge;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.JulianCalendar;
import org.junit.jupiter.api.Test;

/**
 * {@link AgeBracket}：**年龄档边界的唯一定义处**（R1.5）。
 *
 * <p>★ 判别力：这几条把"全仓只有一套边"钉成事实 —— 经济侧的创世折算（{@code EconomySeeder.ageBracketOf}）与读口（年龄结构）都从这里取边，
 * 改本枚举的上界**必须**同时让本类与 {@code PopulationSeederTest}（代表年龄落在自己那一档）一起红，而不是一边悄悄改、另一边照旧。
 *
 * <p>★★ **C4b 起 15/60 是整历法年**（设计稿 §六.2）：边界用例按**生日当天 / 生日前一天**构造，逐日年龄 → 整岁的换算由 {@link CalendarAge}
 * 现算；不再出现 {@code 15×365} 这类固定天数字面量。
 */
class AgeBracketTest {

  private static final JulianCalendar CALENDAR = JulianCalendar.INSTANCE;

  /** 儒略历 {@code year-month-day} 的 JDN（本类所有边界都从真实日历反算，不手算闰日）。 */
  private static long dayOf(int year, int month, int day) {
    return CALENDAR.dayNumberOf(new CalendarDate(year, month, day));
  }

  @Test
  void vocabularyIsTheThreeD4BracketsInOrder() {
    assertThat(AgeBracket.values())
        .as("词表序 = 0-14 → 15-59 → 60+（与经济侧的占比表、代表年龄表三处同序）")
        .containsExactly(AgeBracket.CHILD, AgeBracket.ADULT, AgeBracket.ELDER);
    assertThat(AgeBracket.CHILD.key()).isEqualTo("0-14");
    assertThat(AgeBracket.ADULT.key()).isEqualTo("15-59");
    assertThat(AgeBracket.ELDER.key()).isEqualTo("60+");
  }

  /**
   * ★ {@code ofYears} 的**四侧逐值**（{@code 14/15/59/60}）：把 {@code <} 写成 {@code ≤}、或把某一档少算一年，都会有一条红。
   * 15/60 是**整历法年**，不是 {@code 15×365/60×365} 天。
   */
  @Test
  void ofYearsUsesThe15And60YearEdgesExclusivelyAtTheUpperBound() {
    assertThat(AgeBracket.ofYears(0L)).isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.ofYears(14L)).as("14 岁 ⇒ 0-14").isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.ofYears(15L)).as("15 岁当天 ⇒ 15-59").isEqualTo(AgeBracket.ADULT);
    assertThat(AgeBracket.ofYears(59L)).as("59 岁 ⇒ 15-59").isEqualTo(AgeBracket.ADULT);
    assertThat(AgeBracket.ofYears(60L)).as("60 岁当天 ⇒ 60+").isEqualTo(AgeBracket.ELDER);
    assertThat(AgeBracket.ofYears(Long.MAX_VALUE))
        .as("年龄没有上界 ⇒ 末档兜底（不抛）")
        .isEqualTo(AgeBracket.ELDER);
  }

  /**
   * ★★ **整历法年边界（生日当天长档、生日前一天不长档）**：15 岁与 60 岁各钉两侧。
   *
   * <p>★ 算式：批次的逐日年龄 {@code ageDays = 当前日 JDN − 出生日 JDN}；{@link #of} 把它交给 {@link CalendarAge}
   * 折成整岁，再与 15/60 比。同一批人**只挪一天**就跨档，且跨档点是**生日**而不是"第 N×365 天"。
   */
  @Test
  void ofUsesTheWholeCalendarYearBirthdayAtThe15And60YearEdges() {
    // 生日取 1985-04-11；15 岁生日当天 = 2000-04-11，前一天 = 2000-04-10。
    long fifteenthBirthday = dayOf(2000, 4, 11);
    long birthOf15 = dayOf(1985, 4, 11);
    long ageDaysAt15 = fifteenthBirthday - birthOf15;
    assertThat(AgeBracket.of(CALENDAR, fifteenthBirthday - 1L, ageDaysAt15 - 1L))
        .as("15 岁生日前一天 ⇒ 0-14（不是第 15×365−1 天）")
        .isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.of(CALENDAR, fifteenthBirthday, ageDaysAt15))
        .as("15 岁生日当天 ⇒ 15-59")
        .isEqualTo(AgeBracket.ADULT);

    // 生日取 1940-04-11；60 岁生日当天 = 2000-04-11，前一天 = 2000-04-10。
    long sixtiethBirthday = dayOf(2000, 4, 11);
    long birthOf60 = dayOf(1940, 4, 11);
    long ageDaysAt60 = sixtiethBirthday - birthOf60;
    assertThat(AgeBracket.of(CALENDAR, sixtiethBirthday - 1L, ageDaysAt60 - 1L))
        .as("60 岁生日前一天 ⇒ 15-59（不是第 60×365−1 天）")
        .isEqualTo(AgeBracket.ADULT);
    assertThat(AgeBracket.of(CALENDAR, sixtiethBirthday, ageDaysAt60))
        .as("60 岁生日当天 ⇒ 60+")
        .isEqualTo(AgeBracket.ELDER);
  }

  /**
   * ★★ **2/29 出生的生日惯例**（设计稿 §六.2）：平年 2/28 视为**未过**生日、3/1 才长一岁。
   *
   * <p>★ 先用 {@link CalendarAge} 在平年 2001 钉住"0 岁 → 1 岁"（2000-02-29 出生），再用档位边界钉住同一条惯例： 2000-02-29
   * 出生的人在**平年 2015** 的 2/28 还是 14 岁（0-14）、3/1 才是 15 岁（15-59）。判别力：把生日钳成"平年 2/28 提前庆祝"⇒ 2/28
   * 那条会长岁（CHILD → ADULT）⇒ 红。
   */
  @Test
  void leapDayBirthdaysAgeUpOnMarchFirstInCommonYears() {
    long leapDayBirth = dayOf(2000, 2, 29);
    long commonYearFeb28 = dayOf(2001, 2, 28);
    long commonYearMar1 = dayOf(2001, 3, 1);

    assertThat(CalendarAge.ageInYears(CALENDAR, leapDayBirth, commonYearFeb28))
        .as("平年 2/28 尚未过生日 ⇒ 0 岁")
        .isZero();
    assertThat(CalendarAge.ageInYears(CALENDAR, leapDayBirth, commonYearMar1))
        .as("平年 3/1 已过生日 ⇒ 1 岁")
        .isEqualTo(1L);

    long feb28InCommonYear = dayOf(2015, 2, 28);
    long mar1InCommonYear = dayOf(2015, 3, 1);
    assertThat(AgeBracket.of(CALENDAR, feb28InCommonYear, feb28InCommonYear - leapDayBirth))
        .as("2000-02-29 出生、平年 2015-02-28 ⇒ 14 岁（未过长岁）")
        .isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.of(CALENDAR, mar1InCommonYear, mar1InCommonYear - leapDayBirth))
        .as("2000-02-29 出生、平年 2015-03-01 ⇒ 15 岁（平年 3/1 长岁）")
        .isEqualTo(AgeBracket.ADULT);
  }

  /** ★ 负年龄是坏数据（{@code ofYears} 与逐日 {@code of} 都 fail-closed，不静默归档）。 */
  @Test
  void rejectsNegativeAges() {
    assertThatThrownBy(() -> AgeBracket.ofYears(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ageYears 不得为负");
    assertThatThrownBy(() -> AgeBracket.of(CALENDAR, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ageDays 不得为负");
  }

  /**
   * ★ {@code boundedMaxExclusiveYears()} 就是**经济侧那张表**的投影：逐值钉住 15 岁 / 60 岁（整历法年）， 且**每次返回新数组**
   * （共享可变数组 = 对外开一个改参数的后门，SpotBugs 实测报过同族问题）。
   */
  @Test
  void boundedMaxExclusiveYearsProjectsTheEdgesWithoutSharingTheArray() {
    long[] edges = AgeBracket.boundedMaxExclusiveYears();
    assertThat(edges).containsExactly(15L, 60L);
    assertThat(edges).as("末档无上界 ⇒ 表长 = 词表长 − 1").hasSize(AgeBracket.values().length - 1);

    long[] second = AgeBracket.boundedMaxExclusiveYears();
    assertThat((Object) second).as("两次调用不是同一个数组").isNotSameAs(edges);

    edges[0] = -1L; // 改坏拿到的副本
    edges[1] = -1L;
    assertThat(AgeBracket.boundedMaxExclusiveYears())
        .as("拿到的数组是副本：改它不许影响下一次调用")
        .containsExactly(15L, 60L);
  }

  /** 三档的键互异且非空（读口按它发 JSON 的键：重名会让两条不同的档合成一条）。 */
  @Test
  void keysAreNonBlankAndDistinct() {
    assertThat(AgeBracket.values())
        .extracting(AgeBracket::key)
        .doesNotContainNull()
        .doesNotHaveDuplicates();
  }
}
