package io.mosire.simos.calendar;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 按纬度分带 + 季界族 + 热带模型的季节系统（设计稿 §5；D-018 补裁/D-019）。
 *
 * <p><b>分派表</b>（{@link LatitudeBands#zoneOf(HexCoord)} 先判带）：
 *
 * <pre>
 *   NORTH_TEMPERATE           → 北半球四季（按 boundary）
 *   SOUTH_TEMPERATE           → 南半球四季 = 北半球相位 +180°（季界节气索引 +12 项）
 *   TROPICS + RAINY_DRY       → 雨季/旱季（黄经窗口；南热带窗口 +180°）
 *   TROPICS + TEMPERATE_LIKE  → 北半球四季（热带季节减弱近似，本批不接地形/高度）
 *   分带未配置（fallback）      → 全球 NORTH_TEMPERATE（北半球四季），由读口标 zoneSource: fallback
 * </pre>
 *
 * <p><b>SOLAR_TERM 季界</b>（北半球起界节气 = 节气枚举 {@link SolarTerm}，括号内为黄经）：
 *
 * <pre>
 *   春 = 立春(315°) → 立夏(45°)      夏 = 立夏(45°)  → 立秋(135°)
 *   秋 = 立秋(135°) → 立冬(225°)     冬 = 立冬(225°) → 立春(315°)
 *   南半球 = 各起界节气 +12 项（+180°）：立秋 → 立冬 → 立春 → 立夏
 * </pre>
 *
 * <p><b>ASTRONOMICAL 季界</b>：
 *
 * <pre>
 *   春 = 春分(0°) → 夏至(90°)        夏 = 夏至(90°)  → 秋分(180°)
 *   秋 = 秋分(180°) → 冬至(270°)     冬 = 冬至(270°) → 春分(0°)
 *   南半球 = +12 项（+180°）：秋分 → 冬至 → 春分 → 夏至
 * </pre>
 *
 * <p><b>热带雨旱</b>：黄经 λ ∈ {@code [rainyStart, rainyEnd)} ⇒ {@link TropicalSeason#RAINY}，否则 {@link
 * TropicalSeason#DRY}；南热带（北负约定下 signed r &gt; 0）窗口两端各 +180° 并归一化，赤道 {@code r = 0} 与北热带同相。季内
 * dayOfSeason/daysInSeason/progress 由对应黄经的边界日算： 起界 = 最近一次 ≤ day 的跨越日，终界 = 第一次 &gt; day 的跨越日；季界当天
 * dayOfSeason=1、progress=0。 配置为整圆窗口（如 {@code [0°,360°)}）时恒为雨季，季长 = 一个太阳年。
 *
 * <p>默认口径下（tick 120 = 日号 2248965、λ≈49.35°、分带未配置）：<b>夏</b>，dayOfSeason=6、
 * daysInSeason=95、progress=5/95。热带雨季窗口 {@code [45°,165°)} 在 1445 起于 2248960、终于 2249086 ⇒
 * dayOfSeason=6、daysInSeason=126、progress=5/126。
 *
 * <p>本类纯计算、无状态；所有季界复用 {@link SolarTerms}，不复制黄经公式与节气表。
 */
public final class ZonedSeasonSystem implements SeasonSystem {

  private static final Logger LOG = CalendarLog.season();

  private static final Logger TRACE = CalendarLog.trace();

  /** SOLAR_TERM 北半球四季起界（春/夏/秋/冬）的节气枚举序：立春/立夏/立秋/立冬。 */
  private static final int[] SOLAR_TERM_NORTH_START_TERM_INDICES = {21, 3, 9, 15};

  /** SOLAR_TERM 南半球 = 北半球 +12 项（+180°）：立秋/立冬/立春/立夏。 */
  private static final int[] SOLAR_TERM_SOUTH_START_TERM_INDICES = {9, 15, 21, 3};

  /** ASTRONOMICAL 北半球四季起界（春/夏/秋/冬）：春分/夏至/秋分/冬至。 */
  private static final int[] ASTRONOMICAL_NORTH_START_TERM_INDICES = {0, 6, 12, 18};

  /** ASTRONOMICAL 南半球 = 北半球 +12 项（+180°）：秋分/冬至/春分/夏至。 */
  private static final int[] ASTRONOMICAL_SOUTH_START_TERM_INDICES = {12, 18, 0, 6};

  /** 南半球相位 / 南热带雨季窗口的移相角（度）。 */
  private static final double HALF_TURN_DEGREES = 180.0;

  private final LatitudeBands bands;
  private final SeasonSettings settings;

  /**
   * @param bands 分带配置（非空；{@link LatitudeBands#unconfigured()} 表示全球北半球四季 fallback）
   * @param settings 季界/热带配置（非空）
   */
  public ZonedSeasonSystem(LatitudeBands bands, SeasonSettings settings) {
    this.bands = Objects.requireNonNull(bands, "bands");
    this.settings = Objects.requireNonNull(settings, "settings");
    // ★ 2026-10-23 L3：季节系统配置是装配期生命周期事件（INFO），配置档全量落日志 ⇒ 配置漂移可查。
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "CALENDAR_SEASON_CONFIGURED",
                CalendarLogSource.CAL_SEASON,
                "boundary",
                settings.boundary(),
                "tropicalModel",
                settings.tropicalModel(),
                "rainyStart",
                settings.rainyStartLongitude(),
                "rainyEnd",
                settings.rainyEndLongitude(),
                "bandsConfigured",
                bands.configured()));
  }

  @Override
  public SeasonState seasonOf(long dayNumber, HexCoord at) {
    Objects.requireNonNull(at, "at");
    SeasonState state =
        switch (bands.zoneOf(at)) {
          case NORTH_TEMPERATE -> fourSeasons(dayNumber, false);
          case SOUTH_TEMPERATE -> fourSeasons(dayNumber, true);
          case TROPICS ->
              settings.tropicalModel() == TropicalModel.RAINY_DRY
                  ? rainyDry(dayNumber, at.r())
                  // 类温带模型按 D-018 补裁决的口径：热带也给北半球四季。
                  : fourSeasons(dayNumber, false);
        };
    logQuery(dayNumber, at, state);
    return state;
  }

  /** 季节查询的可观测性：逐次走 TRACE（默认关）；季界当天（{@code dayOfSeason == 1}）另发一条 DEBUG 边界事件。 */
  private static void logQuery(long dayNumber, HexCoord at, SeasonState state) {
    if (TRACE.isTraceEnabled()) {
      EventLog.channel(TRACE)
          .trace(
              LogEvent.of(
                  "CALENDAR_SEASON_QUERIED",
                  CalendarLogSource.CAL_TRACE,
                  "dayNumber",
                  dayNumber,
                  "hex",
                  at,
                  "phase",
                  state.phase(),
                  "dayOfSeason",
                  state.dayOfSeason(),
                  "daysInSeason",
                  state.daysInSeason()));
    }
    if (LOG.isDebugEnabled() && state.dayOfSeason() == 1) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "CALENDAR_SEASON_BOUNDARY",
                  CalendarLogSource.CAL_SEASON,
                  "dayNumber",
                  dayNumber,
                  "hex",
                  at,
                  "phase",
                  state.phase(),
                  "dayOfSeason",
                  state.dayOfSeason(),
                  "daysInSeason",
                  state.daysInSeason()));
    }
  }

  /**
   * 北/南半球四季：找出四个起界节气中最近一次 ≤ day 的那一个即为当前季，季长到下一次季界为止。
   *
   * <p>用“四个起界里最近的一个”而不是 {@code termOf} 判季：季节相位由四个季界日决定，而 {@code termOf}
   * 只回答“当天是哪一个节气”；按边界日判才能保证季界当天属于新季节（dayOfSeason=1）。
   */
  private SeasonState fourSeasons(long dayNumber, boolean southernHemisphere) {
    int[] startTermIndices = selectStartTermIndices(southernHemisphere);
    int phaseIndex = 0;
    long seasonStartDay = Long.MIN_VALUE;
    for (int i = 0; i < startTermIndices.length; i++) {
      SolarTerm startTerm = SolarTerm.values()[startTermIndices[i]];
      long startDay = SolarTerms.previousBoundaryDayNumber(startTerm, dayNumber);
      if (startDay > seasonStartDay) {
        seasonStartDay = startDay;
        phaseIndex = i;
      }
    }
    int endTermIndex = startTermIndices[(phaseIndex + 1) % startTermIndices.length];
    SolarTerm endTerm = SolarTerm.values()[endTermIndex];
    long nextSeasonStartDay = SolarTerms.nextBoundaryDayNumber(endTerm, dayNumber);
    return stateOf(
        TemperateSeason.values()[phaseIndex], dayNumber, seasonStartDay, nextSeasonStartDay);
  }

  /** 选北/南半球 + SOLAR_TERM/ASTRONOMICAL 对应的四个起界节气枚举序。 */
  private int[] selectStartTermIndices(boolean southernHemisphere) {
    if (settings.boundary() == SeasonBoundary.SOLAR_TERM) {
      return southernHemisphere
          ? SOLAR_TERM_SOUTH_START_TERM_INDICES
          : SOLAR_TERM_NORTH_START_TERM_INDICES;
    }
    return southernHemisphere
        ? ASTRONOMICAL_SOUTH_START_TERM_INDICES
        : ASTRONOMICAL_NORTH_START_TERM_INDICES;
  }

  /** 热带雨旱：先定当前相位（最近一次“雨季开始”晚于最近一次“雨季结束” ⇒ 雨季）， 再用对应起/终黄经的边界日算季内进度。 */
  private SeasonState rainyDry(long dayNumber, int northSouthCoord) {
    // 先归一化：配置 [0°,360°) 的两端都归一到 0°，才能识别“整圆窗口”这一退化形态。
    double startLongitude = SolarLongitude.normalize360(settings.rainyStartLongitude());
    double endLongitude = SolarLongitude.normalize360(settings.rainyEndLongitude());
    if (bands.signedCoordinate(northSouthCoord) > 0L) {
      // 南热带（北负约定：signed > 0）：窗口整体 +180°，两端各自归一化；跨越日查询内部会再归一化。
      startLongitude = SolarLongitude.normalize360(startLongitude + HALF_TURN_DEGREES);
      endLongitude = SolarLongitude.normalize360(endLongitude + HALF_TURN_DEGREES);
    }
    if (Double.compare(startLongitude, endLongitude) == 0) {
      // 整圆窗口（配置 [0°,360°) 或移相后退化）：恒雨季；用一个太阳年做季长。
      long seasonStartDay =
          SolarTerms.previousBoundaryDayNumberForLongitude(startLongitude, dayNumber);
      long nextSeasonStartDay =
          SolarTerms.nextBoundaryDayNumberForLongitude(startLongitude, dayNumber);
      return stateOf(TropicalSeason.RAINY, dayNumber, seasonStartDay, nextSeasonStartDay);
    }
    long lastRainyStart =
        SolarTerms.previousBoundaryDayNumberForLongitude(startLongitude, dayNumber);
    long lastRainyEnd = SolarTerms.previousBoundaryDayNumberForLongitude(endLongitude, dayNumber);
    if (lastRainyStart > lastRainyEnd) {
      long rainyEndDay = SolarTerms.nextBoundaryDayNumberForLongitude(endLongitude, dayNumber);
      return stateOf(TropicalSeason.RAINY, dayNumber, lastRainyStart, rainyEndDay);
    }
    long dryEndDay = SolarTerms.nextBoundaryDayNumberForLongitude(startLongitude, dayNumber);
    return stateOf(TropicalSeason.DRY, dayNumber, lastRainyEnd, dryEndDay);
  }

  /**
   * 统一口径：{@code dayOfSeason = dayNumber − seasonStartDay + 1}， {@code daysInSeason =
   * nextSeasonStartDay − seasonStartDay}， {@code progress = (dayOfSeason−1) / daysInSeason ∈
   * [0,1)}。
   */
  private static SeasonState stateOf(
      ClimatePhase phase, long dayNumber, long seasonStartDay, long nextSeasonStartDay) {
    int daysInSeason = Math.toIntExact(nextSeasonStartDay - seasonStartDay);
    int dayOfSeason = Math.toIntExact(dayNumber - seasonStartDay + 1L);
    double progress = (dayOfSeason - 1) / (double) daysInSeason;
    return new SeasonState(phase, dayOfSeason, daysInSeason, progress);
  }
}
