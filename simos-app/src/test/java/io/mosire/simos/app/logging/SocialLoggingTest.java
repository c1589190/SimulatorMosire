package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.population.AgeBracket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * S2 social 日志验收（架构 §6/§7 第 6 条；计划 §阶段 S4）：
 *
 * <ul>
 *   <li>{@link HouseholdBook} 与 {@code settleVitalEvents} 至少产出架构点名的 13 个 {@code event=} 名，
 *       且每条带架构要求的关键 {@code key=value}；
 *   <li>DEBUG / TRACE 明细只在级别打开时出现；{@code Configurator} 把 social logger 调回 INFO 后消失。
 * </ul>
 *
 * <p>★ 装置住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl（social 模块自身没有，
 * 见依赖树）；这里只借 app 的类路径，被验对象仍是 {@code io.mosire.simos.social} 及其子 logger。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：log4j2 没有配置时 root level 是 ERROR，只挂 appender
 * 不抬 level 会收到空集；空集上"不含某串"会假绿（core 的 {@code CommandBusLoggingTest} 已记过这个坑）。
 */
class SocialLoggingTest {

  private static final String APPENDER_NAME = "social-logging-capture";
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final long YEAR = 365L;

  private static final HouseholdId HEX_HOUSEHOLD = HouseholdId.parse("hh-log-hex");
  private static final HouseholdId UNIT_HOUSEHOLD = HouseholdId.parse("hh-log-unit");
  private static final PeopleLotId MALE = PeopleLotId.parse("log-male");
  private static final PeopleLotId FEMALE = PeopleLotId.parse("log-female");

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig socialConfig;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    socialConfig = configuration.getLoggerConfig(SocialLog.ROOT_LOGGER_NAME);
    originalRootLevel = socialConfig.getLevel();
    originalTraceLevel =
        configuration.getLoggerConfig(SocialLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(SocialLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(SocialLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(SocialLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void log4jConfigReadsTheSocialLevelProperties() {
    assertThat(socialConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.social 建一条显式 LoggerConfig")
        .isEqualTo(SocialLog.ROOT_LOGGER_NAME);
    assertThat(socialConfig.getLevel().toString())
        .as("默认/覆盖级别必须来自 simos.social.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.social.logLevel", "INFO"));
    assertThat(originalTraceLevel.toString())
        .as("TRACE 明细级别必须来自 simos.social.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.social.traceLevel", "INFO"));
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setSocialLevel(Level.DEBUG);
    HouseholdBook.create(
        SocialData.empty(),
        HEX_HOUSEHOLD,
        new HouseholdLocation.Hex(H00),
        new HouseholdProfile("甲", null, Map.of()),
        new HouseholdVitalRates(List.of()));

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void lifecycleAndPopulationEventsCarryTheArchitectureNamesAndFields() {
    setSocialLevel(Level.DEBUG);

    SocialData data = runLifecycleFixture();

    List<String> lines = appender.messages();
    assertEvent(lines, "HOUSEHOLD_CREATED", "id=" + HEX_HOUSEHOLD, "location=HEX:0_0");
    assertEvent(lines, "HOUSEHOLD_LOCATION_SET", "id=" + HEX_HOUSEHOLD, "from=HEX:0_0", "to=UNIT:u-1");
    assertEvent(lines, "HOUSEHOLD_PROFILE_SET", "id=" + HEX_HOUSEHOLD, "name=改名户");
    assertEvent(lines, "HOUSEHOLD_MEMBER_ADD", "id=" + HEX_HOUSEHOLD, "lot=" + MALE, "count=100", "sex=MALE", "ageAnchor=" + (20L * YEAR));
    assertEvent(lines, "HOUSEHOLD_MEMBER_REMOVE", "id=" + HEX_HOUSEHOLD, "lot=" + MALE, "count=40", "remaining=60");
    assertEvent(lines, "HOUSEHOLD_MEMBER_TRANSFER", "from=" + HEX_HOUSEHOLD, "to=" + UNIT_HOUSEHOLD, "lot=" + FEMALE, "count=50", "mode=SPLIT");
    assertEvent(lines, "HOUSEHOLD_RATE_SET", "id=" + HEX_HOUSEHOLD, "rates=2");
    assertEvent(lines, "POPULATION_BIRTH", "household=" + HEX_HOUSEHOLD, "sex=", "ageBracket=0-14", "count=", "day=0");
    assertEvent(lines, "POPULATION_DEATH", "household=" + HEX_HOUSEHOLD, "sex=MALE", "ageBracket=15-59", "count=5", "day=0");
    assertEvent(lines, "POPULATION_TRANSFER_IN", "to=" + UNIT_HOUSEHOLD, "lot=" + FEMALE, "count=50");
    assertEvent(lines, "POPULATION_TRANSFER_OUT", "from=" + HEX_HOUSEHOLD, "lot=" + FEMALE, "count=50");
    assertEvent(lines, "GM_POPULATION_ADJUST", "delta=");
    assertEvent(lines, "POPULATION_CONSERVATION_CHECK", "ok=true", "households=", "lots=", "population=");
    assertThat(data.populationEvents()).as("夹具必须真的落过事件，否则上面的日志断言可能只是空跑").isNotEmpty();
  }

  @Test
  void configuratorRaisesToTraceThenBackToInfoChangesWhatIsCaptured() {
    setSocialLevel(Level.TRACE);

    SocialData data =
        HouseholdBook.create(
            SocialData.empty(),
            HEX_HOUSEHOLD,
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("甲", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    HouseholdBook.addMembers(data, HEX_HOUSEHOLD, MALE, Sex.MALE, 10L, 20L * YEAR, 0L, "seed");

    assertThat(appender.messages())
        .as("TRACE 打开后逐批次明细必须出现")
        .anySatisfy(line -> assertThat(line).contains("event=HOUSEHOLD_CREATED_DETAIL"));

    appender.clear();
    setSocialLevel(Level.INFO);
    SocialData second =
        HouseholdBook.create(
            SocialData.empty(),
            HouseholdId.parse("hh-log-second"),
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("乙", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    HouseholdBook.addMembers(second, HouseholdId.parse("hh-log-second"), FEMALE, Sex.FEMALE, 10L, 20L * YEAR, 0L, "seed");

    assertThat(appender.messages())
        .as("改回 INFO 后 TRACE 明细不得再出现；实得 %s", appender.messages())
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).doesNotContain("_DETAIL"));
  }

  private SocialData runLifecycleFixture() {
    SocialData data =
        HouseholdBook.create(
            SocialData.empty(),
            HEX_HOUSEHOLD,
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("甲户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    data =
        HouseholdBook.create(
            data,
            UNIT_HOUSEHOLD,
            new HouseholdLocation.Unit("u-1"),
            new HouseholdProfile("乙户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    data = HouseholdBook.setLocation(data, HEX_HOUSEHOLD, new HouseholdLocation.Unit("u-1"), "调防");
    data = HouseholdBook.setProfile(data, HEX_HOUSEHOLD, new HouseholdProfile("改名户", null, Map.of()), "改名");
    data = HouseholdBook.addMembers(data, HEX_HOUSEHOLD, MALE, Sex.MALE, 100L, 20L * YEAR, 0L, "增人");
    data = HouseholdBook.addMembers(data, HEX_HOUSEHOLD, FEMALE, Sex.FEMALE, 100L, 30L * YEAR, 0L, "增人");
    data = HouseholdBook.removeMembers(data, HEX_HOUSEHOLD, MALE, 40L, "抽丁");
    data = HouseholdBook.transferMembers(data, HEX_HOUSEHOLD, UNIT_HOUSEHOLD, FEMALE, 50L, "调防");
    data =
        HouseholdBook.setVitalRates(
            data,
            HEX_HOUSEHOLD,
            new HouseholdVitalRates(
                List.of(
                    new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 100L),
                    new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 100L, 0L))),
            "率表");
    data =
        HouseholdBook.adjustPopulation(
            data, HEX_HOUSEHOLD, Sex.MALE, AgeBracket.ADULT.key(), -10L, "GM 抽人");
    return HouseholdBook.settleVitalEvents(data, 0L);
  }

  private void setSocialLevel(Level level) {
    Configurator.setLevel(SocialLog.ROOT_LOGGER_NAME, level);
    Configurator.setLevel(SocialLog.TRACE_LOGGER_NAME, level == Level.TRACE ? Level.TRACE : Level.INFO);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(SocialLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static void assertEvent(List<String> lines, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 event=%s（实得 %s）", event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
  }

  /** 采集 appender：只记 message，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> messages = new ArrayList<>();

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      return List.copyOf(messages);
    }

    private void clear() {
      messages.clear();
    }
  }
}
