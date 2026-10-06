package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarLog;
import io.mosire.simos.calendar.LatitudeBands;
import io.mosire.simos.calendar.SeasonSettings;
import io.mosire.simos.calendar.SeasonState;
import io.mosire.simos.calendar.TemperateSeason;
import io.mosire.simos.calendar.ZonedSeasonSystem;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L4 calendar 日志验收（计划 §9 判据 2/3；模板 {@code SocialLoggingTest}）。
 *
 * <p>被验对象是 {@code io.mosire.simos.calendar} 门面下的四条真实事件：
 *
 * <ul>
 *   <li>INFO {@code CALENDAR_CLOCK_BOUND}：{@link CalendarClock#julianDefault()}；
 *   <li>INFO {@code CALENDAR_SEASON_CONFIGURED}：{@code new ZonedSeasonSystem(...)}；
 *   <li>TRACE {@code CALENDAR_SEASON_QUERIED}：{@code seasonOf(...)}（默认关，须把 .trace logger 调到 TRACE）；
 *   <li>DEBUG {@code CALENDAR_SEASON_BOUNDARY}：季界当天（{@code dayOfSeason == 1}）。
 * </ul>
 *
 * <p>★ 装置住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl；这里只借类路径， 被验对象仍是 {@code
 * io.mosire.simos.calendar} 及其子 logger。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：log4j2 没配置时 root level 是 ERROR，只挂 appender 不抬
 * level 会收到空集；空集上"不含某事件"的断言会假绿，故每个"级别关闭 ⇒ 事件不出现"的用例都同时发一条 INFO 正对照（{@code
 * CALENDAR_CLOCK_BOUND}）证明采集装置仍然有效。
 *
 * <p>★ 具名拒绝：经 {@code git grep} 核 calendar 的 {@code src/main} 只有上述四条事件（没有 handler、没有
 * rejected/拒绝类发射点）⇒ <b>日历模块没有具名拒绝事件</b>；因此这里不编造"拒绝事件不存在"这种恒真断言，如实报告。
 *
 * <p>★ 判别力：把任一条事件的 origin/originKind 去掉、关键字段值写错、把 {@code CALENDAR_SEASON_QUERIED} 的 {@code
 * isTraceEnabled()} 守卫去掉、或把 {@code CALENDAR_SEASON_BOUNDARY} 的 {@code dayOfSeason == 1}
 * 判据/级别改坏，对应用例当场红。
 */
class CalendarLoggingTest {

  private static final String APPENDER_NAME = "calendar-logging-capture";
  private static final String PROBE_MESSAGE = "CALENDAR_LOGGING_TEST_PROBE";
  private static final HexCoord H00 = new HexCoord(0, 0);

  /** 已知默认 tick 120 = 日号 2248965：默认节气季界下是夏、dayOfSeason=6（不是季界当天）。 */
  private static final long DAY_2248965 = 2248965L;

  /** 季界搜索起点：儒略 1445 立春（2248869）之前一点；循环找第一条 dayOfSeason==1 的日号。 */
  private static final long BOUNDARY_SEARCH_START = 2248850L;

  /** 搜索窗口大于一个太阳年，确保能碰到一个季界。 */
  private static final long BOUNDARY_SEARCH_DAYS = 400L;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalRootLevel = configuration.getLoggerConfig(CalendarLog.ROOT_LOGGER_NAME).getLevel();
    originalTraceLevel = configuration.getLoggerConfig(CalendarLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(CalendarLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(CalendarLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(CalendarLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();

    CalendarClock.julianDefault();

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void clockBindingInfoCarriesEventOriginAndAnchorFields() {
    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();

    CalendarClock clock = CalendarClock.julianDefault();

    assertThat(clock.epochDayNumber()).as("夹具前提：默认锚点 JDN").isEqualTo(2248845L);
    assertEvent(
        appender.messages(),
        Level.INFO,
        "CALENDAR_CLOCK_BOUND",
        "origin=calendar-clock",
        "originKind=system",
        "system=julian",
        "epoch=CalendarDate[year=1445, month=1, day=1]",
        "epochDayNumber=2248845");
  }

  @Test
  void seasonConfigurationInfoCarriesEventOriginAndConfigFields() {
    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();

    new ZonedSeasonSystem(LatitudeBands.unconfigured(), SeasonSettings.defaults());

    assertEvent(
        appender.messages(),
        Level.INFO,
        "CALENDAR_SEASON_CONFIGURED",
        "origin=calendar-season",
        "originKind=system",
        "boundary=SOLAR_TERM",
        "tropicalModel=RAINY_DRY",
        "rainyStart=45.0",
        "rainyEnd=165.0",
        "bandsConfigured=false");

    // 判别力补充：bandsConfigured 必须反映真实构造参数，不能是硬编码 false。
    appender.clear();
    new ZonedSeasonSystem(LatitudeBands.of(-40L, 40L, true), SeasonSettings.defaults());
    assertEvent(
        appender.messages(),
        Level.INFO,
        "CALENDAR_SEASON_CONFIGURED",
        "origin=calendar-season",
        "bandsConfigured=true");
  }

  @Test
  void rootLevelWarnSuppressesInfoAndRestoringInfoBringsItBack() {
    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();
    CalendarClock.julianDefault();
    assertThat(appender.messages())
        .as("前提：INFO 下 CALENDAR_CLOCK_BOUND 必须出现；实得 %s", appender.messages())
        .anySatisfy(line -> assertThat(line).contains("event=CALENDAR_CLOCK_BOUND"));

    setCalendarLevels(Level.WARN, Level.INFO);
    appender.clear();
    CalendarLog.clock().warn(PROBE_MESSAGE);
    CalendarClock.julianDefault();
    assertThat(appender.messages())
        .as("WARN 探针必须出现 ⇒ 采集 appender 在 WARN 级别下仍有效；实得 %s", appender.messages())
        .anySatisfy(line -> assertThat(line).startsWith("WARN|").contains(PROBE_MESSAGE));
    assertNotCaptured("CALENDAR_CLOCK_BOUND");

    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();
    CalendarClock.julianDefault();
    assertThat(appender.messages())
        .as("调回 INFO 后 CALENDAR_CLOCK_BOUND 必须回来；实得 %s", appender.messages())
        .anySatisfy(
            line -> assertThat(line).startsWith("INFO|").contains("event=CALENDAR_CLOCK_BOUND"));
  }

  @Test
  void traceLevelGateControlsSeasonQueriedEvent() {
    ZonedSeasonSystem system =
        new ZonedSeasonSystem(LatitudeBands.unconfigured(), SeasonSettings.defaults());

    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();
    system.seasonOf(DAY_2248965, H00);
    CalendarClock.julianDefault();
    assertCaptureIsAlive();
    assertNotCaptured("CALENDAR_SEASON_QUERIED");

    setCalendarLevels(Level.INFO, Level.TRACE);
    appender.clear();
    SeasonState state = system.seasonOf(DAY_2248965, H00);
    assertThat(state.phase()).as("夹具前提：2248965 在默认节气季界下是夏").isEqualTo(TemperateSeason.SUMMER);
    assertThat(state.dayOfSeason()).as("夹具前提：不是季界当天").isEqualTo(6);
    assertThat(state.daysInSeason()).isEqualTo(95);
    assertEvent(
        appender.messages(),
        Level.TRACE,
        "CALENDAR_SEASON_QUERIED",
        "origin=calendar-trace",
        "originKind=system",
        "dayNumber=2248965",
        "hex=0_0",
        "phase=SUMMER",
        "dayOfSeason=6",
        "daysInSeason=95");

    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();
    system.seasonOf(DAY_2248965, H00);
    CalendarClock.julianDefault();
    assertCaptureIsAlive();
    assertNotCaptured("CALENDAR_SEASON_QUERIED");
  }

  @Test
  void debugBoundaryEventOnlyAppearsAtDebugOrTrace() {
    ZonedSeasonSystem system =
        new ZonedSeasonSystem(LatitudeBands.unconfigured(), SeasonSettings.defaults());
    long boundaryDay = firstDayOfSeasonBoundary(system);

    setCalendarLevels(Level.INFO, Level.INFO);
    appender.clear();
    system.seasonOf(boundaryDay, H00);
    CalendarClock.julianDefault();
    assertCaptureIsAlive();
    assertNotCaptured("CALENDAR_SEASON_BOUNDARY");

    setCalendarLevels(Level.DEBUG, Level.INFO);
    appender.clear();
    SeasonState boundary = system.seasonOf(boundaryDay, H00);
    assertThat(boundary.dayOfSeason()).as("夹具前提：找到的必须是季界当天").isEqualTo(1);
    // ★ 实测：CALENDAR_SEASON_BOUNDARY 只带 dayNumber/hex/phase/daysInSeason，不带 dayOfSeason 字段
    //   （ZonedSeasonSystem 的 debug 行只在这个分支下发）。这里断言真实契约，并另用非季界日反证
    //   dayOfSeason==1 判据确实还在（见下方 DEBUG 下的第二个查询）。
    assertEvent(
        appender.messages(),
        Level.DEBUG,
        "CALENDAR_SEASON_BOUNDARY",
        "origin=calendar-season",
        "originKind=system",
        "dayNumber=" + boundaryDay,
        "hex=0_0",
        "phase=" + boundary.phase(),
        "daysInSeason=" + boundary.daysInSeason());
    assertNotCaptured("CALENDAR_SEASON_QUERIED");

    // DEBUG 打开时，非季界日（dayOfSeason==6）不得发边界事件；先发 INFO 正对照证明采集仍有效。
    appender.clear();
    system.seasonOf(DAY_2248965, H00);
    CalendarClock.julianDefault();
    assertCaptureIsAlive();
    assertNotCaptured("CALENDAR_SEASON_BOUNDARY");

    setCalendarLevels(Level.TRACE, Level.TRACE);
    appender.clear();
    system.seasonOf(boundaryDay, H00);
    assertEvent(
        appender.messages(),
        Level.DEBUG,
        "CALENDAR_SEASON_BOUNDARY",
        "origin=calendar-season",
        "originKind=system",
        "dayNumber=" + boundaryDay,
        "hex=0_0");
    assertEvent(
        appender.messages(),
        Level.TRACE,
        "CALENDAR_SEASON_QUERIED",
        "origin=calendar-trace",
        "originKind=system",
        "dayNumber=" + boundaryDay);
  }

  private void setCalendarLevels(Level rootLevel, Level traceLevel) {
    Configurator.setLevel(CalendarLog.ROOT_LOGGER_NAME, rootLevel);
    Configurator.setLevel(CalendarLog.TRACE_LOGGER_NAME, traceLevel);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 改动 logger 配置后把采集 appender 重新挂上（照 SocialLoggingTest 重挂法）。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(CalendarLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  /** 从 {@link #BOUNDARY_SEARCH_START} 起逐日找第一条 {@code dayOfSeason == 1}；找不到即失败并报告。 */
  private static long firstDayOfSeasonBoundary(ZonedSeasonSystem system) {
    long endExclusive = BOUNDARY_SEARCH_START + BOUNDARY_SEARCH_DAYS;
    for (long day = BOUNDARY_SEARCH_START; day < endExclusive; day++) {
      if (system.seasonOf(day, H00).dayOfSeason() == 1) {
        return day;
      }
    }
    throw new AssertionError(
        "在 ["
            + BOUNDARY_SEARCH_START
            + ", "
            + endExclusive
            + ") 内找不到 dayOfSeason==1 的日号 ⇒ 无法验证 CALENDAR_SEASON_BOUNDARY；请检查"
            + " SeasonSystem 实现");
  }

  /** 正对照：证明"某事件不出现"不是因为采集装置失效（不是恒真断言，发不出 INFO 就红）。 */
  private void assertCaptureIsAlive() {
    assertThat(appender.messages())
        .as("正对照：采集 appender 必须仍能收到 CALENDAR_CLOCK_BOUND；实得 %s", appender.messages())
        .anySatisfy(line -> assertThat(line).contains("event=CALENDAR_CLOCK_BOUND"));
  }

  private void assertNotCaptured(String event) {
    assertThat(appender.messages())
        .as("该级别下不得出现 event=%s；实得 %s", event, appender.messages())
        .noneSatisfy(line -> assertThat(line).contains("event=" + event));
  }

  private static void assertEvent(
      List<String> lines, Level level, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s|...event=%s 且带全部关键字段（实得 %s）", level, event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).startsWith(level + "|");
              assertThat(line).contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
  }

  /** 采集 appender：记 {@code level|message}，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> messages = new ArrayList<>();

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      return List.copyOf(messages);
    }

    private void clear() {
      messages.clear();
    }
  }
}
