package io.mosire.simos.app.time;

import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.CalendarDefaults;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.calendar.LatitudeBands;
import io.mosire.simos.calendar.SeasonBoundary;
import io.mosire.simos.calendar.SeasonSettings;
import io.mosire.simos.calendar.TropicalModel;
import java.util.Locale;
import java.util.Objects;

/**
 * 世界历法与气候配置（设计稿 §七 JSON 形状的不可变值类型；D-018 补裁 / D-019）。
 *
 * <p><b>口径</b>：{@code epoch} 是 tick 0 的儒略历 y-m-d（{@link CalendarDate}，月/日 1 起）；{@code
 * seasonBoundary}/{@code tropicalModel}/雨季窗口组成 {@link SeasonSettings}；{@code northMax}/{@code
 * southMin} 是纬度分带（北负约定，负数为北），<b>不预设缺省值</b>——两个都为 null = 未配置，降级为“全球北半球四季 +
 * zoneSource=fallback”（D-018 补裁）。
 *
 * <p><b>来源标记</b>：{@link Source#STORE} = 该部分来自已落盘的 {@code store_meta.calendar}；{@link
 * Source#DEFAULT} = 缺省算法（{@link CalendarDefaults}）；{@link Source#FALLBACK} = 分带未配置的明确降级。 三者的组合在
 * compact 构造器里强制自洽：分带未配置 ⇒ {@code zoneSource} 必须是 fallback，分带已配置 ⇒ 不得是 fallback（不允许“未配置却标 store”）。
 *
 * <p><b>fail-closed</b>：全部校验在 compact 构造器里完成，非法配置当场抛，绝不静默回落；{@code epoch} 的月内合法性 （2/30、4/31 等）由
 * {@link JulianCalendar#dayNumberOf(CalendarDate)} 当场算一次触发。
 */
public record CalendarConfig(
    int version,
    String calendar,
    CalendarDate epoch,
    SeasonBoundary seasonBoundary,
    TropicalModel tropicalModel,
    double tropicalRainyStartLongitude,
    double tropicalRainyEndLongitude,
    boolean northIsNegative,
    Long northMax,
    Long southMin,
    Source calendarSource,
    Source seasonSource,
    Source zoneSource) {

  /** 当前唯一认的配置版本。 */
  public static final int VERSION_1 = 1;

  /** 配置来源标记（设计稿 §七：缺省与已存配置必须可辨，不得静默换算法）。 */
  public enum Source {
    /** 来自 {@code store_meta.calendar} 的已落盘配置。 */
    STORE("store"),
    /** 来自 {@link CalendarDefaults} 的缺省算法（未落盘）。 */
    DEFAULT("default"),
    /** 分带未配置的明确降级（全球北半球四季）。 */
    FALLBACK("fallback");

    private final String key;

    Source(String key) {
      this.key = key;
    }

    /** 小写串（读口/JSON 展示用）。 */
    public String key() {
      return key;
    }
  }

  /**
   * 全部校验集中在这里（见类注）；非法值一律 {@link IllegalArgumentException}（语义校验），null 枚举/锚点走 {@link
   * Objects#requireNonNull}。
   */
  public CalendarConfig {
    if (version != VERSION_1) {
      throw new IllegalArgumentException("version 只认 " + VERSION_1 + "：" + version);
    }
    if (calendar == null || calendar.isBlank() || !JulianCalendar.ID.equals(calendar)) {
      throw new IllegalArgumentException(
          "calendar 必须是非空历法 id 且只认 \"" + JulianCalendar.ID + "\"：" + calendar);
    }
    Objects.requireNonNull(epoch, "epoch");
    // 当场正算一次：触发月内合法性校验（2/30、4/31 等由 JulianCalendar 抛 IAE）。
    JulianCalendar.INSTANCE.dayNumberOf(epoch);
    Objects.requireNonNull(seasonBoundary, "seasonBoundary");
    Objects.requireNonNull(tropicalModel, "tropicalModel");
    if (!(tropicalRainyStartLongitude >= 0.0)
        || !(tropicalRainyStartLongitude < tropicalRainyEndLongitude)
        || !(tropicalRainyEndLongitude <= 360.0)) {
      throw new IllegalArgumentException(
          "雨季窗口必须满足 0 ≤ start < end ≤ 360："
              + tropicalRainyStartLongitude
              + " / "
              + tropicalRainyEndLongitude);
    }
    if ((northMax == null) != (southMin == null)) {
      throw new IllegalArgumentException(
          "northMax/southMin 必须成对配置（要么都给、要么都给 null）：northMax="
              + northMax
              + "，southMin="
              + southMin);
    }
    if (northMax != null && northMax >= southMin) {
      throw new IllegalArgumentException(
          "northMax 必须小于 southMin（按北负约定）：" + northMax + " / " + southMin);
    }
    Objects.requireNonNull(calendarSource, "calendarSource");
    Objects.requireNonNull(seasonSource, "seasonSource");
    Objects.requireNonNull(zoneSource, "zoneSource");
    if (northMax == null && zoneSource != Source.FALLBACK) {
      throw new IllegalArgumentException(
          "分带未配置（northMax/southMin 都是 null）时 zoneSource 必须是 fallback，不得标 " + zoneSource.key());
    }
    if (northMax != null && zoneSource == Source.FALLBACK) {
      throw new IllegalArgumentException(
          "分带已配置（northMax/southMin 非 null）时 zoneSource 不得是 fallback");
    }
  }

  /**
   * D-018 补裁的缺省配置（= 设计稿 §九表）：儒略 1445-01-01 锚点、24 节气季界、雨季/旱季 {@code [45°,165°)}、 分带未配置；来源 = {@code
   * DEFAULT / DEFAULT / FALLBACK}。改缺省值必须另开 D 条目。
   */
  public static CalendarConfig defaults() {
    return new CalendarConfig(
        VERSION_1,
        CalendarDefaults.CALENDAR_ID,
        CalendarDefaults.EPOCH_DATE,
        CalendarDefaults.SEASON_BOUNDARY,
        CalendarDefaults.TROPICAL_MODEL,
        CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE,
        CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE,
        CalendarDefaults.NORTH_IS_NEGATIVE,
        null,
        null,
        Source.DEFAULT,
        Source.DEFAULT,
        Source.FALLBACK);
  }

  /** 纬度分带是否已配置（两个界限都非 null；构造期已保证成对）。 */
  public boolean bandsConfigured() {
    return northMax != null;
  }

  /** 锚点的 {@code yyyy-MM-dd} 文本（JSON/info 输出用；年份 4 位，负年保留负号）。 */
  public String epochText() {
    return String.format(Locale.ROOT, "%04d-%02d-%02d", epoch.year(), epoch.month(), epoch.day());
  }

  /** 换三个来源标记（数值字段与校验完全复用；来源组合仍由 compact 构造器强制自洽）。 */
  public CalendarConfig withSources(Source calendarSource, Source seasonSource, Source zoneSource) {
    return new CalendarConfig(
        version,
        calendar,
        epoch,
        seasonBoundary,
        tropicalModel,
        tropicalRainyStartLongitude,
        tropicalRainyEndLongitude,
        northIsNegative,
        northMax,
        southMin,
        calendarSource,
        seasonSource,
        zoneSource);
  }

  /** 本配置对应的季节设置（季界族 + 热带模型 + 雨季黄经窗口）。 */
  public SeasonSettings seasonSettings() {
    return new SeasonSettings(
        seasonBoundary, tropicalModel, tropicalRainyStartLongitude, tropicalRainyEndLongitude);
  }

  /**
   * 本配置对应的分带：已配置 ⇒ {@link LatitudeBands#of}；未配置 ⇒ {@link LatitudeBands#unconfigured()}（fallback）。
   */
  public LatitudeBands bands() {
    return bandsConfigured()
        ? LatitudeBands.of(northMax, southMin, northIsNegative)
        : LatitudeBands.unconfigured();
  }
}
