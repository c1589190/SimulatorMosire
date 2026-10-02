package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 设计稿 §5.1/§5.2 与 §十判据 3：太阳黄经公式、24 节气边界日、边界日的节气归属与前后关系。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>{@link SolarLongitude#longitude} 忘了把 g 从角度转弧度、或系数/归一化写错 ⇒ {@link
 *       #solarLongitudeStaysInRangeAndHitsJ2000} 与全部冻结边界日红；
 *   <li>{@link SolarTerms#boundaryDayNumber} 的半日取整（{@code f >= 0.5}）方向反了 ⇒ {@link
 *       #frozenBoundaryAnchors} 的 2024/1445 锚点红；
 *   <li>{@link SolarTerms#termOf} 边界日不归新节气（仍返回前一个）⇒ {@link
 *       #allTwentyFourTermsResolveOn2024And1445Rounds} 红；
 *   <li>{@code previousBoundaryDayNumber}/{@code nextBoundaryDayNumber} 差一天 ⇒ 同一条用例的 {@code
 *       previous@day} / {@code next@day-1} 断言红；
 *   <li>{@link SolarTerm} 枚举里任一项的 longitude 或枚举序错 ⇒ {@link
 *       #termLongitudesAreFrozenFifteenDegreeMultiples} 红。
 * </ul>
 */
class SolarTermsTest {

  /** 判据 3：2024 二分二至/立春与 1445 立春/立夏/立秋/白露的冻结边界日（dayNumber = JDN 整数）。 */
  @Test
  void frozenBoundaryAnchors() {
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.LICHUN, 2460345L)).isEqualTo(2460345L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.CHUNFEN, 2460390L)).isEqualTo(2460390L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.XIAZHI, 2460482L)).isEqualTo(2460482L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.QIUFEN, 2460576L)).isEqualTo(2460576L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.DONGZHI, 2460666L)).isEqualTo(2460666L);

    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.LICHUN, 2248869L)).isEqualTo(2248869L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.LIXIA, 2248960L)).isEqualTo(2248960L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.LIQIU, 2249055L)).isEqualTo(2249055L);
    assertThat(SolarTerms.boundaryDayNumber(SolarTerm.BAILU, 2249086L)).isEqualTo(2249086L);

    // 边界日当天的节气归属与前后边界查询是同一套半日取整口径的依据。
    assertThat(SolarTerms.termOf(2460345L)).isEqualTo(SolarTerm.LICHUN);
    assertThat(SolarTerms.termOf(2248869L)).isEqualTo(SolarTerm.LICHUN);
    assertThat(SolarTerms.termOf(2248960L)).isEqualTo(SolarTerm.LIXIA);
    assertThat(SolarTerms.termOf(2249086L)).isEqualTo(SolarTerm.BAILU);
  }

  /**
   * 判据 3：24 个节气逐个在 2024 与 1445 两轮做同一组不变式——对每个 term 先由附近日号求边界日，再断言 {@code termOf(边界日) ==
   * term}、{@code previousBoundaryDayNumber(term, 边界日) == 边界日}、 {@code nextBoundaryDayNumber(term,
   * 边界日 - 1) == 边界日}。
   *
   * <p>附近日号由“立春锚点 + 黄经差 × 365.2422/360”估值（不调用生产二分），命中同一个太阳年内、离目标边界 ≤5 天，避免因跨年而测到相邻年份的边界还误以为在测本轮。
   */
  @Test
  void allTwentyFourTermsResolveOn2024And1445Rounds() {
    long[] lichunAnchors = {2460345L, 2248869L};
    int checked = 0;
    for (long lichun : lichunAnchors) {
      for (SolarTerm term : SolarTerm.values()) {
        long nearDay = nearDayFor(term, lichun);
        long day = SolarTerms.boundaryDayNumber(term, nearDay);
        assertThat(Math.abs(day - nearDay))
            .as("%s 的边界日在附近日 ±5 内（near=%d, day=%d）", term, nearDay, day)
            .isLessThanOrEqualTo(5L);
        assertThat(SolarTerms.termOf(day)).as("%s 边界日 %d 应归新节气", term, day).isEqualTo(term);
        assertThat(SolarTerms.previousBoundaryDayNumber(term, day))
            .as("%s 的 previous(%d)", term, day)
            .isEqualTo(day);
        assertThat(SolarTerms.nextBoundaryDayNumber(term, day - 1L))
            .as("%s 的 next(%d)", term, day - 1L)
            .isEqualTo(day);
        checked++;
      }
    }
    assertThat(checked).isEqualTo(48);
  }

  /** 判据 3：黄经输出恒在 [0,360)；J2000（JDN 2451545）≈280.38° 是公式的独立冻结锚。 */
  @Test
  void solarLongitudeStaysInRangeAndHitsJ2000() {
    long[] samples = {-1000000L, 0L, 2248845L, 2451545L, 2460345L, 10000000L};
    for (long day : samples) {
      assertThat(SolarLongitude.longitude(day))
          .as("day=%d", day)
          .isGreaterThanOrEqualTo(0.0)
          .isLessThan(360.0);
    }
    assertThat(SolarLongitude.longitude(2451545L)).isCloseTo(280.38, within(0.01));
  }

  /** 判据 3：枚举序 = 黄经序，第 i 项 longitude == i×15，范围 [0,360)。 */
  @Test
  void termLongitudesAreFrozenFifteenDegreeMultiples() {
    SolarTerm[] terms = SolarTerm.values();
    assertThat(terms).hasSize(24);
    for (int i = 0; i < terms.length; i++) {
      assertThat(terms[i].longitude()).as("index %d", i).isEqualTo(i * 15.0);
      assertThat(terms[i].longitude()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
    }
    assertThat(terms[0]).isEqualTo(SolarTerm.CHUNFEN);
    assertThat(terms[21]).isEqualTo(SolarTerm.LICHUN);
    assertThat(SolarTerm.LICHUN.chineseName()).isEqualTo("立春");
  }

  /**
   * 判据 3 的日期标签：边界日必须经 {@link JulianCalendar#dateOf} 出儒略历标签。1445 立春 = JDN 2248869 = 儒略
   * 1445-01-25。2024 立春的真实跨日在 JDN 2460345（现实世界格里高利 2024-02-04），但儒略历标签是 <b>2024-01-22</b>；这里用 {@link
   * LocalDate} 只作外部格里高利轴，不把它当儒略历换算。
   */
  @Test
  void boundaryLabelsUseJulianCalendar() {
    JulianCalendar julian = JulianCalendar.INSTANCE;
    assertThat(julian.dateOf(2248869L)).isEqualTo(new CalendarDate(1445, 1, 25));
    assertThat(julian.dateOf(2460345L)).isEqualTo(new CalendarDate(2024, 1, 22));
    assertThat(LocalDate.ofEpochDay(2460345L - 2440588L)).isEqualTo(LocalDate.of(2024, 2, 4));
  }

  /** 立春锚点 + 目标黄经相对立春的圆周差 × 平均太阳年长/360。 */
  private static long nearDayFor(SolarTerm term, long lichunDay) {
    double offset = (term.longitude() - SolarTerm.LICHUN.longitude() + 360.0) % 360.0;
    return lichunDay + Math.round(offset / 360.0 * 365.2422);
  }
}
