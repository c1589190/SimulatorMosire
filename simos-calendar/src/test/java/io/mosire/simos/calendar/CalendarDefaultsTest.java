package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 设计稿 §七/§九 与 §十判据 5：默认历法/气候算法的唯一代码口径 {@link CalendarDefaults}，逐项与 §九 表相等。
 *
 * <p><b>判别力</b>：任一默认值被静默改掉（历法 id、锚点、季界族、热带模型、雨窗端点、南北轴符号）⇒ 对应断言红； {@code
 * LatitudeBands.unconfigured()} 若偷偷给带宽或返回其它 zone ⇒ fallback 用例红； {@link SeasonSettings#defaults} 若与
 * {@link CalendarDefaults} 漂移 ⇒ 镜像用例红。
 */
class CalendarDefaultsTest {

  /** §九逐项：儒略历、1445-01-01、SOLAR_TERM、RAINY_DRY、[45°,165°)、负 r = 北。 */
  @Test
  void calendarDefaultsMatchDesignSectionNine() {
    assertThat(CalendarDefaults.CALENDAR_ID).isEqualTo("julian");
    assertThat(CalendarDefaults.EPOCH_DATE).isEqualTo(new CalendarDate(1445, 1, 1));
    assertThat(CalendarDefaults.SEASON_BOUNDARY).isEqualTo(SeasonBoundary.SOLAR_TERM);
    assertThat(CalendarDefaults.TROPICAL_MODEL).isEqualTo(TropicalModel.RAINY_DRY);
    assertThat(CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE).isEqualTo(45.0);
    assertThat(CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE).isEqualTo(165.0);
    assertThat(CalendarDefaults.NORTH_IS_NEGATIVE).isTrue();
  }

  /** §5.3 降级：分带缺省不预设；未配置时 configured()==false、zoneOf 恒北温带、数值访问器抛。 */
  @Test
  void unconfiguredBandsAreExplicitFallback() {
    LatitudeBands bands = LatitudeBands.unconfigured();
    assertThat(bands.configured()).isFalse();
    assertThat(bands.zoneOf(-140L)).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThat(bands.zoneOf(0L)).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThat(bands.zoneOf(140L)).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThatThrownBy(bands::northMax).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(bands::southMin).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(bands::northIsNegative).isInstanceOf(IllegalStateException.class);
  }

  /** §九：{@code SeasonSettings.defaults()} 的每一项都必须等于 {@link CalendarDefaults} 的同项。 */
  @Test
  void seasonSettingsDefaultsMirrorCalendarDefaults() {
    SeasonSettings defaults = SeasonSettings.defaults();
    assertThat(defaults.boundary()).isEqualTo(CalendarDefaults.SEASON_BOUNDARY);
    assertThat(defaults.tropicalModel()).isEqualTo(CalendarDefaults.TROPICAL_MODEL);
    assertThat(defaults.rainyStartLongitude())
        .isEqualTo(CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE);
    assertThat(defaults.rainyEndLongitude())
        .isEqualTo(CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE);
    assertThat(defaults).isEqualTo(SeasonSettings.defaults());
  }

  /** §二/判据 1：默认时钟锚点 = JDN 2248845，且由 {@link CalendarDefaults#EPOCH_DATE} 算出。 */
  @Test
  void defaultClockAnchorIsJulian1445() {
    CalendarClock clock = CalendarClock.julianDefault();
    assertThat(clock.epochDayNumber()).isEqualTo(2248845L);
    assertThat(clock.epochDate()).isEqualTo(CalendarDefaults.EPOCH_DATE);
    assertThat(clock.system().id()).isEqualTo(CalendarDefaults.CALENDAR_ID);
  }
}
