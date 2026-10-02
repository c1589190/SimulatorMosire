package io.mosire.simos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/**
 * 设计稿 §5.2/§5.3/§5.4 与 §十判据 3：分带判定（含边界等号/取反轴）、未配置 fallback、两套季界可切、热带模型。
 *
 * <p><b>判别力</b>（改坏生产代码的哪一行会红）：
 *
 * <ul>
 *   <li>未配置时擅自返回热带/南半球、或访问器不抛 ⇒ {@link #unconfiguredBandsFallBackToGlobalNorthernFourSeasons} 红；
 *   <li>{@link LatitudeBands#zoneOf} 的 {@code <=}/{@code >=} 改成严格不等 ⇒ {@link
 *       #bandBoundariesAreInclusive} 红；
 *   <li>{@code northIsNegative=false} 忘了取反 ⇒ 同一条用例翻转两轴红；
 *   <li>南半球季界忘了整体 +180°（枚举序号 +12）⇒ {@link #configuredBandsSwitchNorthTropicsSouth} 南温带条红；
 *   <li>热带窗口误用北半球四季/相位反了 ⇒ 同一条用例热带/南热带条红；
 *   <li>SOLAR_TERM 与 ASTRONOMICAL 两族起界节气索引混用 ⇒ {@link
 *       #solarTermAndAstronomicalBoundariesAreSwitchable} 红；
 *   <li>{@link SeasonSettings} 的 {@code 0 ≤ start < end ≤ 360} 校验漏项 ⇒ {@link
 *       #seasonSettingsRejectInvalidWindows} 红。
 * </ul>
 *
 * <p>日号 2248965 = 默认 tick 120 = 儒略 1445-05-01，λ≈49.35°。
 */
class SeasonSystemTest {

  private static final long DAY_2248965 = 2248965L;
  private static final HexCoord ORIGIN = new HexCoord(0, 0);

  /**
   * 判据 3 的降级口径：未配置分带 = 全球北半球四季 + fallback；任意 r（含极值）都判 NORTH_TEMPERATE； 三个数值访问器没有可诚实返回的带宽 ⇒
   * IllegalStateException。
   */
  @Test
  void unconfiguredBandsFallBackToGlobalNorthernFourSeasons() {
    LatitudeBands bands = LatitudeBands.unconfigured();
    ZonedSeasonSystem system = new ZonedSeasonSystem(bands, SeasonSettings.defaults());

    int[] extremeR = {0, -140, 140, Integer.MIN_VALUE, Integer.MAX_VALUE};
    for (int r : extremeR) {
      assertThat(bands.zoneOf((long) r))
          .as("未配置 zoneOf(r=%d)", r)
          .isEqualTo(LatitudeZone.NORTH_TEMPERATE);
      SeasonState state = system.seasonOf(DAY_2248965, new HexCoord(0, r));
      assertThat(state.phase()).as("fallback r=%d", r).isEqualTo(TemperateSeason.SUMMER);
      assertThat(state.dayOfSeason()).as("fallback r=%d", r).isEqualTo(6);
      assertThat(state.daysInSeason()).as("fallback r=%d", r).isEqualTo(95);
      assertThat(state.progress()).as("fallback r=%d", r).isEqualTo(5 / 95.0);
    }

    assertThatThrownBy(bands::northMax).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(bands::southMin).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(bands::northIsNegative).isInstanceOf(IllegalStateException.class);
  }

  /**
   * 判据 3：配置 {@code of(-40,40,true)} 后，r=-100 北温带夏、r=+100 南温带冬、r=0 热带默认雨季； 具体
   * dayOfSeason/daysInSeason/progress 见类注实测值。
   */
  @Test
  void configuredBandsSwitchNorthTropicsSouth() {
    LatitudeBands bands = LatitudeBands.of(-40L, 40L, true);
    ZonedSeasonSystem system = new ZonedSeasonSystem(bands, SeasonSettings.defaults());

    SeasonState north = system.seasonOf(DAY_2248965, new HexCoord(0, -100));
    assertThat(north.phase()).isEqualTo(TemperateSeason.SUMMER);
    assertThat(north.dayOfSeason()).isEqualTo(6);
    assertThat(north.daysInSeason()).isEqualTo(95);
    assertThat(north.progress()).isEqualTo(5 / 95.0);

    SeasonState south = system.seasonOf(DAY_2248965, new HexCoord(0, 100));
    assertThat(south.phase()).isEqualTo(TemperateSeason.WINTER);
    assertThat(south.dayOfSeason()).isEqualTo(6);
    assertThat(south.daysInSeason()).isEqualTo(95);

    SeasonState tropics = system.seasonOf(DAY_2248965, ORIGIN);
    assertThat(tropics.phase()).isEqualTo(TropicalSeason.RAINY);
    assertThat(tropics.dayOfSeason()).isEqualTo(6);
    assertThat(tropics.daysInSeason()).isEqualTo(126);
    assertThat(tropics.progress()).isEqualTo(5 / 126.0);

    // 赤道/北热带同相；南热带窗口 +180° 后同日号落在旱季。
    assertThat(system.seasonOf(DAY_2248965, new HexCoord(0, -1)).phase())
        .isEqualTo(TropicalSeason.RAINY);
    assertThat(system.seasonOf(DAY_2248965, new HexCoord(0, 1)).phase())
        .isEqualTo(TropicalSeason.DRY);
  }

  /** 判据 3：分界线含等号；{@code northIsNegative=false} 时原始轴取反后再判。 */
  @Test
  void bandBoundariesAreInclusive() {
    LatitudeBands bands = LatitudeBands.of(-40L, 40L, true);
    assertThat(bands.zoneOf(-40L)).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThat(bands.zoneOf(40L)).isEqualTo(LatitudeZone.SOUTH_TEMPERATE);
    assertThat(bands.zoneOf(-39L)).isEqualTo(LatitudeZone.TROPICS);
    assertThat(bands.zoneOf(39L)).isEqualTo(LatitudeZone.TROPICS);
    assertThat(bands.zoneOf(new HexCoord(0, -40))).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThat(bands.zoneOf(new HexCoord(0, 40))).isEqualTo(LatitudeZone.SOUTH_TEMPERATE);

    LatitudeBands flipped = LatitudeBands.of(-40L, 40L, false);
    assertThat(flipped.zoneOf(40L)).isEqualTo(LatitudeZone.NORTH_TEMPERATE);
    assertThat(flipped.zoneOf(-40L)).isEqualTo(LatitudeZone.SOUTH_TEMPERATE);
    assertThat(flipped.zoneOf(0L)).isEqualTo(LatitudeZone.TROPICS);
  }

  /**
   * 判据 3：同一天号 2248965（HexCoord(0,0)，未配置分带）在 SOLAR_TERM 下 = 夏、ASTRONOMICAL 下 = 春 （春分 2248914 → 夏至
   * 2249007，daysInSeason=93、dayOfSeason=52）；并各给一个 ASTRONOMICAL 夏/冬日号示例。
   */
  @Test
  void solarTermAndAstronomicalBoundariesAreSwitchable() {
    ZonedSeasonSystem solarTerm =
        new ZonedSeasonSystem(LatitudeBands.unconfigured(), SeasonSettings.defaults());
    assertThat(solarTerm.seasonOf(DAY_2248965, ORIGIN).phase()).isEqualTo(TemperateSeason.SUMMER);

    ZonedSeasonSystem astro =
        new ZonedSeasonSystem(
            LatitudeBands.unconfigured(),
            defaultWindow(SeasonBoundary.ASTRONOMICAL, TropicalModel.RAINY_DRY));

    SeasonState spring = astro.seasonOf(DAY_2248965, ORIGIN);
    assertThat(spring.phase()).isEqualTo(TemperateSeason.SPRING);
    assertThat(spring.dayOfSeason()).isEqualTo(52);
    assertThat(spring.daysInSeason()).isEqualTo(93);
    assertThat(spring.progress()).isEqualTo(51 / 93.0);

    // 2024 夏至（2460482）/冬至（2460666）当天都是各自新季节的第 1 天。
    SeasonState summer = astro.seasonOf(2460482L, ORIGIN);
    assertThat(summer.phase()).isEqualTo(TemperateSeason.SUMMER);
    assertThat(summer.dayOfSeason()).isEqualTo(1);
    assertThat(summer.daysInSeason()).isEqualTo(94);

    SeasonState winter = astro.seasonOf(2460666L, ORIGIN);
    assertThat(winter.phase()).isEqualTo(TemperateSeason.WINTER);
    assertThat(winter.dayOfSeason()).isEqualTo(1);
    assertThat(winter.daysInSeason()).isEqualTo(89);
  }

  /** 判据 §九/配置校验：{@code 0 ≤ start < end ≤ 360}，start≥end、负 start、end>360 都当场抛。 */
  @Test
  void seasonSettingsRejectInvalidWindows() {
    assertThatThrownBy(() -> window(100.0, 100.0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> window(200.0, 100.0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> window(-1.0, 100.0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> window(0.0, 361.0)).isInstanceOf(IllegalArgumentException.class);

    // 合法闭端点 360 不抛（半开窗口 [0,360)）。
    assertThat(window(0.0, 360.0).rainyEndLongitude()).isEqualTo(360.0);
  }

  /** 判据 §5.4：{@code TEMPERATE_LIKE} 下热带也走北半球四季（day 2248965 = 夏）。 */
  @Test
  void temperateLikeTropicsUseNorthernFourSeasons() {
    ZonedSeasonSystem system =
        new ZonedSeasonSystem(
            LatitudeBands.of(-40L, 40L, true),
            defaultWindow(SeasonBoundary.SOLAR_TERM, TropicalModel.TEMPERATE_LIKE));

    SeasonState state = system.seasonOf(DAY_2248965, ORIGIN);
    assertThat(state.phase()).isEqualTo(TemperateSeason.SUMMER);
    assertThat(state.dayOfSeason()).isEqualTo(6);
    assertThat(state.daysInSeason()).isEqualTo(95);
    assertThat(state.progress()).isEqualTo(5 / 95.0);
  }

  /** SOLAR_TERM + RAINY_DRY + 指定窗口；非法窗口的四个用例由 {@link #window} 收紧到单一构造参数组合。 */
  private static SeasonSettings window(double start, double end) {
    return new SeasonSettings(SeasonBoundary.SOLAR_TERM, TropicalModel.RAINY_DRY, start, end);
  }

  /** 指定季界/热带模型 + §九缺省雨窗端点。 */
  private static SeasonSettings defaultWindow(SeasonBoundary boundary, TropicalModel model) {
    return new SeasonSettings(
        boundary,
        model,
        CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE,
        CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE);
  }
}
