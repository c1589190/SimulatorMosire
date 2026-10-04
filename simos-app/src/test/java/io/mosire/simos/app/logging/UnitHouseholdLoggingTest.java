package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.testing.UnitHouseholdWorldFixture;
import io.mosire.simos.app.tools.write.UnitAssignHouseholdTool;
import io.mosire.simos.app.tools.write.UnitDetachHouseholdTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>S3a 正式验收：unit/social 家户日志</b>（S3a spec §6/§7；任务书 B8）。
 *
 * <p>用 log4j2 appender 同时捕获 {@code io.mosire.simos.unit} 与 {@code io.mosire.simos.social}：
 *
 * <ul>
 *   <li>{@code UNIT_HOUSEHOLDS_SET} / {@code UNIT_HOUSEHOLD_ASSIGN} / {@code UNIT_HOUSEHOLD_DETACH} 及
 *       unit/household/reason 关键字段；
 *   <li>assign/detach 同批的 Social 侧 {@code HOUSEHOLD_LOCATION_SET}（from/to）；
 *   <li>{@code simos.unit.logLevel} / {@code simos.unit.traceLevel} 升降级真的改变输出（TRACE 明细出现/消失）。
 * </ul>
 *
 * <p>★ 装置住 app 模块（只有 app 测试类路径有 log4j-core + log4j-slf4j2-impl）；{@link #logLinesAreActuallyCaptured}
 * 是前提断言——空集上的"不含某串"会假绿。
 */
class UnitHouseholdLoggingTest {

  private static final String APPENDER_NAME = "unit-household-logging-capture";
  private static final HouseholdId HH_A = HouseholdId.parse("hh-a");

  @TempDir Path tempDir;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig unitConfig;
  private LoggerConfig unitTraceConfig;
  private LoggerConfig socialConfig;
  private LoggerConfig socialTraceConfig;
  private Level originalUnitLevel;
  private Level originalUnitTraceLevel;
  private Level originalSocialLevel;
  private Level originalSocialTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    unitConfig = configuration.getLoggerConfig(UnitLog.ROOT_LOGGER_NAME);
    unitTraceConfig = configuration.getLoggerConfig(UnitLog.TRACE_LOGGER_NAME);
    socialConfig = configuration.getLoggerConfig(SocialLog.ROOT_LOGGER_NAME);
    socialTraceConfig = configuration.getLoggerConfig(SocialLog.TRACE_LOGGER_NAME);
    originalUnitLevel = unitConfig.getLevel();
    originalUnitTraceLevel = unitTraceConfig.getLevel();
    originalSocialLevel = socialConfig.getLevel();
    originalSocialTraceLevel = socialTraceConfig.getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppenders();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(UnitLog.ROOT_LOGGER_NAME, originalUnitLevel);
    Configurator.setLevel(UnitLog.TRACE_LOGGER_NAME, originalUnitTraceLevel);
    Configurator.setLevel(SocialLog.ROOT_LOGGER_NAME, originalSocialLevel);
    Configurator.setLevel(SocialLog.TRACE_LOGGER_NAME, originalSocialTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(UnitLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.getLoggerConfig(SocialLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void log4jConfigReadsUnitLevelProperties() {
    assertThat(unitConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.unit 建一条显式 LoggerConfig")
        .isEqualTo(UnitLog.ROOT_LOGGER_NAME);
    assertThat(unitConfig.getLevel().toString())
        .as("默认/覆盖级别必须来自 simos.unit.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.unit.logLevel", "INFO"));
    assertThat(unitTraceConfig.getLevel().toString())
        .as("TRACE 明细级别必须来自 simos.unit.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.unit.traceLevel", "INFO"));
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setUnitLevels(Level.INFO, Level.INFO);
    setSocialLevels(Level.INFO, Level.INFO);
    appender.clear();
    runHandler();

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void assignAndDetachCaptureUnitAndSocialEventsWithTheirFields() {
    CoreSimos core = UnitHouseholdWorldFixture.core(tempDir, govState(), social());
    try {
      QueryService query = UnitHouseholdWorldFixture.query(core);
      UnitAssignHouseholdTool assign = new UnitAssignHouseholdTool(core, query, "agent:log-test");
      UnitDetachHouseholdTool detach = new UnitDetachHouseholdTool(core, query, "agent:log-test");
      setUnitLevels(Level.INFO, Level.INFO);
      setSocialLevels(Level.INFO, Level.INFO);
      appender.clear();

      long head = UnitHouseholdWorldFixture.head(core);
      assertThat(
              assign
                  .execute(
                      context(
                          assign,
                          Map.of(
                              "householdId", HH_A.value(),
                              "unitId", UnitHouseholdWorldFixture.GOV.value(),
                              "reason", "编入",
                              "expectedRevision", head,
                              "preview", false)))
                  .success())
          .isTrue();
      long afterAssign = UnitHouseholdWorldFixture.head(core);
      assertThat(
              detach
                  .execute(
                      context(
                          detach,
                          Map.of(
                              "householdId", HH_A.value(),
                              "unitId", UnitHouseholdWorldFixture.GOV.value(),
                              "hex", Map.of("q", 1, "r", 2),
                              "reason", "移出",
                              "expectedRevision", afterAssign,
                              "preview", false)))
                  .success())
          .isTrue();

      List<String> lines = appender.messages();
      assertEvent(lines, "UNIT_HOUSEHOLDS_SET", "unit=u-gov", "households=[hh-a]", "reason=编入");
      assertEvent(
          lines,
          "UNIT_HOUSEHOLD_ASSIGN",
          "unit=u-gov",
          "household=hh-a",
          "location=UNIT:u-gov",
          "reason=编入");
      assertEvent(lines, "HOUSEHOLD_LOCATION_SET", "id=hh-a", "from=HEX:1_1", "to=UNIT:u-gov");
      assertEvent(
          lines,
          "UNIT_HOUSEHOLD_DETACH",
          "unit=u-gov",
          "household=hh-a",
          "location=HEX:1_2",
          "reason=移出");
      assertEvent(lines, "HOUSEHOLD_LOCATION_SET", "id=hh-a", "from=UNIT:u-gov", "to=HEX:1_2");
    } finally {
      core.close();
    }
  }

  @Test
  void unitLogLevelAndTraceLevelSwitchesAreHonoured() {
    setUnitLevels(Level.WARN, Level.INFO);
    appender.clear();
    runHandler();
    assertThat(appender.messages())
        .as("simos.unit.logLevel=WARN ⇒ INFO 生命周期事件必须消失")
        .noneSatisfy(line -> assertThat(line).contains("event=UNIT_HOUSEHOLDS_SET"));

    setUnitLevels(Level.INFO, Level.INFO);
    appender.clear();
    runHandler();
    assertEvent(appender.messages(), "UNIT_HOUSEHOLDS_SET", "unit=u-gov", "reason=切级");
    assertThat(appender.messages())
        .as("traceLevel=INFO ⇒ 逐项明细不得出现")
        .noneSatisfy(line -> assertThat(line).contains("event=UNIT_HOUSEHOLD_SET_ITEM"));

    setUnitLevels(Level.INFO, Level.TRACE);
    appender.clear();
    runHandler();
    assertEvent(appender.messages(), "UNIT_HOUSEHOLD_SET_ITEM", "unit=u-gov", "household=hh-a");

    setUnitLevels(Level.INFO, Level.INFO);
    appender.clear();
    runHandler();
    assertThat(appender.messages())
        .as("traceLevel 降回 INFO ⇒ TRACE 明细消失（不是只多不少）")
        .noneSatisfy(line -> assertThat(line).contains("event=UNIT_HOUSEHOLD_SET_ITEM"));
    assertEvent(appender.messages(), "UNIT_HOUSEHOLDS_SET", "reason=切级");
  }

  // ── 装置 ──────────────────────────────────────────────────────────────

  private void runHandler() {
    UnitState units = govState();
    SimulationState state =
        new SimulationState(
            new StateMeta(UnitHouseholdWorldFixture.ref(1), UnitHouseholdWorldFixture.T0),
            Map.of(
                "unit", new UnitSnapshot(UnitHouseholdWorldFixture.ref(1), UnitHouseholdWorldFixture.T0, units)),
            InMemoryInfoSystem.empty());
    var outcome =
        new SetUnitHouseholdsHandler()
            .handle(
                state,
                "{\"unitId\":\"u-gov\",\"households\":[\"hh-a\"],\"reason\":\"切级\"}");
    assertThat(outcome).isInstanceOf(io.mosire.simos.util.spi.HandlerOutcome.Applied.class);
  }

  private void setUnitLevels(Level root, Level trace) {
    Configurator.setLevel(UnitLog.ROOT_LOGGER_NAME, root);
    Configurator.setLevel(UnitLog.TRACE_LOGGER_NAME, trace);
    attachAppenders();
    context.updateLoggers();
  }

  private void setSocialLevels(Level root, Level trace) {
    Configurator.setLevel(SocialLog.ROOT_LOGGER_NAME, root);
    Configurator.setLevel(SocialLog.TRACE_LOGGER_NAME, trace);
    attachAppenders();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppenders() {
    for (String name : List.of(UnitLog.ROOT_LOGGER_NAME, SocialLog.ROOT_LOGGER_NAME)) {
      LoggerConfig current = configuration.getLoggerConfig(name);
      if (!current.getAppenders().containsKey(APPENDER_NAME)) {
        current.addAppender(appender, null, null);
      }
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

  private static UnitState govState() {
    return new UnitState(
        Map.of(
            UnitHouseholdWorldFixture.GOV,
            UnitHouseholdWorldFixture.govUnit(List.of(), List.of(), Optional.empty())));
  }

  private static SocialData social() {
    SocialData social =
        HouseholdBook.create(
            SocialData.empty(),
            HH_A,
            new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11),
            new HouseholdProfile("甲户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    return HouseholdBook.addMembers(
        social, HH_A, PeopleLotId.parse("lot-a"), Sex.MALE, 7L, 0L, 0L, "seed");
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
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
