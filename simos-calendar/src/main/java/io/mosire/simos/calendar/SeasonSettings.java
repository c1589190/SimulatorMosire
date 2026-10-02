package io.mosire.simos.calendar;

import java.util.Objects;

/**
 * 季节系统配置（设计稿 §5.2/§5.4/§九）：季界族 + 热带模型 + 雨季黄经窗口。
 *
 * <p>缺省见 {@link #defaults()} 与 {@link CalendarDefaults}（唯一代码口径）： 季界 {@link
 * CalendarDefaults#SEASON_BOUNDARY SOLAR_TERM}、热带 {@link CalendarDefaults#TROPICAL_MODEL
 * RAINY_DRY}、窗口 {@code [45°,165°)}（立夏→白露，约 120 天）。分带不在这里，由 {@link LatitudeBands} 单独配置。
 *
 * @param boundary 季界族（非空）
 * @param tropicalModel 热带季节模型（非空）
 * @param rainyStartLongitude 雨季窗口起点黄经（度，含）
 * @param rainyEndLongitude 雨季窗口终点黄经（度，不含）
 */
public record SeasonSettings(
    SeasonBoundary boundary,
    TropicalModel tropicalModel,
    double rainyStartLongitude,
    double rainyEndLongitude) {

  /**
   * 构造期校验：两个枚举非空；{@code 0 ≤ start < end ≤ 360}（窗口半开 {@code [start,end)}）。
   *
   * @throws NullPointerException 枚举为 null
   * @throws IllegalArgumentException 黄经窗口越界或次序颠倒
   */
  public SeasonSettings {
    Objects.requireNonNull(boundary, "boundary");
    Objects.requireNonNull(tropicalModel, "tropicalModel");
    if (!(rainyStartLongitude >= 0.0)
        || !(rainyStartLongitude < rainyEndLongitude)
        || !(rainyEndLongitude <= 360.0)) {
      throw new IllegalArgumentException(
          "雨季窗口必须满足 0 ≤ start < end ≤ 360：" + rainyStartLongitude + " / " + rainyEndLongitude);
    }
  }

  /** D-018 补裁的缺省配置：24 节气 + 雨季/旱季 + {@code [45°,165°)}。 */
  public static SeasonSettings defaults() {
    return new SeasonSettings(
        CalendarDefaults.SEASON_BOUNDARY,
        CalendarDefaults.TROPICAL_MODEL,
        CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE,
        CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE);
  }
}
