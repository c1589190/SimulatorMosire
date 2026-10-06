package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
 * economy 日志验收（all-module-logging-rollout 计划 §3/§4.2/§4.4/§9）：
 *
 * <ul>
 *   <li>{@code economy.RegisterHousehold} 成功面产出真实 INFO（事件名 + 关键字段 + {@code origin=} + {@code
 *       originKind=}；命令面按用户 2026-10-23 裁定归 {@code system}）；
 *   <li>开关有效：把 economy 根 logger 调到 WARN ⇒ INFO 消失，调回 INFO ⇒ 又出现（照 {@code SocialLoggingTest} 重挂
 *       appender）；
 *   <li>TICK 类事件带 {@code day}（day=1 的 DAY_START/DAY_END）；
 *   <li>具名失败面：economy 模块<b>没有 INFO 拒绝事件</b> —— 两个具名拒绝 {@code HOUSEHOLD_NATURAL_NEEDS_REJECTED} /
 *       {@code HOUSEHOLD_POPULATION_DELTA_REJECTED} 都是 fail-closed 的 ERROR（裁定 §4.2「被拒绝肯定走
 *       INFO」在本模块的既有 ERROR 面**不降级**）。本测试用 {@link EconomyDayStepper} 低成本触发它们，断言仍是 ERROR；
 *   <li>既有 WARN（{@code GOV_DEBT_ISSUE_SKIPPED}）不降级。
 * </ul>
 *
 * <p>★ 装置住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl；这里只借 app 的类路径，被验对象仍是 {@code
 * io.mosire.simos.economy} 及其子 logger。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：log4j2 没有配置时 root level 是 ERROR，只挂 appender 不抬
 * level 会收到空集；空集上"不含某串"会假绿（core 的 {@code CommandBusLoggingTest} 已记过这个坑）。
 */
class EconomyLoggingTest {

  private static final String APPENDER_NAME = "economy-logging-capture";

  /** 不存在的经济家户行：两条具名拒绝路的 fail-closed 触发钥匙。 */
  private static final HouseholdId MISSING_HOUSEHOLD = HouseholdId.parse("hh-log-missing");

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig economyConfig;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    economyConfig = configuration.getLoggerConfig(EconomyLog.ROOT_LOGGER_NAME);
    originalRootLevel = economyConfig.getLevel();
    originalTraceLevel = configuration.getLoggerConfig(EconomyLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(EconomyLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(EconomyLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void log4jConfigReadsTheEconomyLevelProperties() {
    assertThat(economyConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.economy 建一条显式 LoggerConfig")
        .isEqualTo(EconomyLog.ROOT_LOGGER_NAME);
    assertThat(economyConfig.getLevel().toString())
        .as("默认/覆盖级别必须来自 simos.economy.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.economy.logLevel", "INFO"));
    assertThat(originalTraceLevel.toString())
        .as("TRACE 明细级别必须来自 simos.economy.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.economy.traceLevel", "INFO"));
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setEconomyLevel(Level.INFO);

    HandlerOutcome outcome = registerHousehold("hh-log-capture");

    assertThat(outcome).as("夹具前提：登记必须真的成功").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  /**
   * §3 第 1 条 / §4.4：成功写口必须有一条真实 INFO，带事件名、关键字段与 {@code origin}/{@code originKind}。
   *
   * <p>判别力：删掉 {@code EconomyRegisterHouseholdHandler} 的 HOUSEHOLD_REGISTERED 行、把级别改成 DEBUG/WARN、或把
   * {@code EconomyLogSource.ECONOMY_COMMAND} 换掉/把 kind 改掉，这里逐项当场红。
   */
  @Test
  void householdRegisteredInfoCarriesEventFieldsAndSystemCommandOrigin() {
    setEconomyLevel(Level.INFO);
    appender.clear();

    HandlerOutcome outcome = registerHousehold("hh-log");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    assertLevelEvent(
        appender.messages(),
        "INFO",
        "HOUSEHOLD_REGISTERED",
        "household=hh-log",
        "q=0",
        "r=0",
        "residence=urban",
        "stratum=landless_laborer",
        "participationPerMille=0",
        "created=true",
        "reasonLength=4",
        "origin=economy-command",
        "originKind=system");
  }

  /**
   * §4.4 开关：{@code Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, WARN)} ⇒ INFO 消失；调回 INFO ⇒
   * 回来。
   *
   * <p>判别力：INFO 行必须前后都出现、中间必须消失；若 logger 名写错（开关打在无关名字上）或 appender 没重挂，这里红。
   */
  @Test
  void configuratorRaisesToWarnThenBackToInfoChangesWhatIsCaptured() {
    setEconomyLevel(Level.INFO);
    registerHousehold("hh-log-switch-before");
    assertLevelEvent(
        appender.messages(), "INFO", "HOUSEHOLD_REGISTERED", "household=hh-log-switch-before");

    appender.clear();
    setEconomyLevel(Level.WARN);
    registerHousehold("hh-log-switch-during");
    assertThat(appender.messages())
        .as("WARN 档下 INFO 必须消失；实得 %s", appender.messages())
        .noneSatisfy(line -> assertThat(line).contains("event=HOUSEHOLD_REGISTERED"));

    appender.clear();
    setEconomyLevel(Level.INFO);
    registerHousehold("hh-log-switch-after");
    assertLevelEvent(
        appender.messages(), "INFO", "HOUSEHOLD_REGISTERED", "household=hh-log-switch-after");
  }

  /**
   * §4.4：TICK 类事件必带 {@code day}。用「已激活的空经济」公开入口 {@link EconomySettlement#settle(EconomyData, long,
   * long)} 推一天——它内部逐日走 DAY_START/DAY_END，成本极低（不需要 EconomyDayStepper 的账户会话夹具）。
   *
   * <p>判别力：把 DAY_START 的 {@code day} 字段删掉、或把 {@code EconomyLogSource.ECONOMY_SETTLEMENT} 换成非 TICK
   * 项，这里红。
   */
  @Test
  void tickLifecycleEventsCarryDayAndTickOrigin() {
    setEconomyLevel(Level.INFO);
    appender.clear();

    EconomySettlement.settle(activatedEmptyEconomy(), 0L, 1L);

    assertLevelEvent(
        appender.messages(),
        "INFO",
        "DAY_START",
        "day=1",
        "rows=0",
        "population=0",
        "origin=economy-settlement",
        "originKind=tick");
    assertLevelEvent(
        appender.messages(),
        "INFO",
        "DAY_END",
        "day=1",
        "origin=economy-settlement",
        "originKind=tick");
  }

  /**
   * 具名拒绝：economy 模块<b>没有 INFO 拒绝事件</b>（用户 2026-10-23「被拒绝肯定走 INFO」的裁定在本模块的 fail-closed
   * 面上如实记为例外）。两条具名拒绝都是 ERROR，且这里用低成本公开入口真的触发它们，断言「仍是 ERROR、不降级」：
   *
   * <ul>
   *   <li>{@code HOUSEHOLD_POPULATION_DELTA_REJECTED}：{@link
   *       EconomyDayStepper#applyHouseholdPopulationDeltas(Map)} 的未知家户行；
   *   <li>{@code HOUSEHOLD_NATURAL_NEEDS_REJECTED}：{@link
   *       EconomyDayStepper#updateNaturalNeeds(Map)} 的未知家户行。
   * </ul>
   *
   * <p>两个方法在日志之后抛具名异常（fail-closed），本测试一并钉住这个"先记 ERROR、再拒"的次序。
   *
   * <p>判别力：把任一条 ERROR 降成 INFO/WARN、或去掉事件名/origin，这里当场红。
   */
  @Test
  void namedFailClosedRejectionsStayAtErrorAndAreNotDowngraded() {
    setEconomyLevel(Level.INFO);
    EconomyDayStepper stepper = new EconomyDayStepper(EconomyData.empty(), AccountSession.empty());

    appender.clear();
    assertThatThrownBy(() -> stepper.applyHouseholdPopulationDeltas(Map.of(MISSING_HOUSEHOLD, 1L)))
        .as("未知家户行的逐户人口变化必须 fail-closed")
        .isInstanceOf(IllegalStateException.class);
    assertLevelEvent(
        appender.messages(),
        "ERROR",
        "HOUSEHOLD_POPULATION_DELTA_REJECTED",
        "reason=unknown-household-row",
        "household=" + MISSING_HOUSEHOLD,
        "origin=economy-population-write",
        "originKind=system");

    appender.clear();
    assertThatThrownBy(() -> stepper.updateNaturalNeeds(Map.of(MISSING_HOUSEHOLD, Map.of())))
        .as("未知家户行的自然需求注入必须 fail-closed")
        .isInstanceOf(IllegalStateException.class);
    assertLevelEvent(
        appender.messages(),
        "ERROR",
        "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
        "reason=unknown-household-row",
        "household=" + MISSING_HOUSEHOLD,
        "origin=economy-population-write",
        "originKind=system");
  }

  /**
   * §4.2「既有 WARN 一律不降级」：{@code GOV_DEBT_ISSUE_SKIPPED}（政府无可发行币种但发债目标 &gt; 0）仍是 WARN。
   *
   * <p>夹具成本极低：一个只有 {@code Government} 一行的已激活经济 + 公开入口 {@code settle(0,1)}，发债在周期开始日（day=1） 走短路支路，记一条
   * WARN 后跳过，不需要账户/家户/产业。
   */
  @Test
  void existingWarnEventIsNotDowngraded() {
    setEconomyLevel(Level.INFO);
    appender.clear();

    Government government =
        new Government(
            new GovernmentId("gov-log"),
            "world",
            new ActorRef(ActorKind.GOVERNMENT, "gov-log"),
            Set.of(),
            0L,
            100L);
    EconomySettlement.settle(
        activatedEmptyEconomy().withGovernments(Map.of(government.id(), government)), 0L, 1L);

    assertLevelEvent(
        appender.messages(),
        "WARN",
        "GOV_DEBT_ISSUE_SKIPPED",
        "day=1",
        "government=gov-log",
        "reason=no-issuable-currency",
        "target=100",
        "origin=economy-debt",
        "originKind=tick");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /**
   * 真 handler + 真 {@link SimulationState}（只有 economy 切片；形态照 economy 模块的 EconomySeedHandlerTest）。
   */
  private static HandlerOutcome registerHousehold(String householdId) {
    String payload = "{\"household\":\"" + householdId + "\",\"q\":0,\"r\":0,\"reason\":\"seed\"}";
    return new EconomyRegisterHouseholdHandler().handle(state(EconomyData.empty()), payload);
  }

  /** 已激活的最小经济：二十八张空表 + 真 EconomyMeta（day=1 才算"经济在跑"）。 */
  private static EconomyData activatedEmptyEconomy() {
    return EconomyData.empty()
        .withMeta(
            Optional.of(
                new EconomyMeta(
                    "Map1",
                    0L,
                    OptionalLong.empty(),
                    EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2,
                    Optional.empty())));
  }

  private static SimulationState state(EconomyData data) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp t0 = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, t0),
        Map.of("economy", new io.mosire.simos.economy.EconomySnapshot(ref, t0, data)),
        InMemoryInfoSystem.empty());
  }

  private void setEconomyLevel(Level level) {
    Configurator.setLevel(EconomyLog.ROOT_LOGGER_NAME, level);
    Configurator.setLevel(
        EconomyLog.TRACE_LOGGER_NAME, level == Level.TRACE ? Level.TRACE : Level.INFO);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(EconomyLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  /** 断言"级别 + 事件名 + 全部片段"落在同一行上（级别不进片段 ⇒ 降级会被抓到）。 */
  private static void assertLevelEvent(
      List<String> lines, String level, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s event=%s（实得 %s）", level, event, lines)
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
