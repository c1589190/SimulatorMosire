package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.ChannelAdmission;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.spi.CreateDecisionMakerHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * L4 sd 日志契约验收（计划 2026-10-23 全模块日志 §3/§4.2/§4.4/§9）：
 *
 * <ul>
 *   <li>至少一条真实 INFO 成功事件带事件名 + 关键字段 + {@code origin=} + 小写 {@code originKind=}；
 *   <li>具名拒绝（{@link ChannelAdmission}）必须是 {@code INFO|} 行，带 reason 与来源；
 *   <li>{@link SdLog#ROOT_LOGGER_NAME} 调到 WARN 后 INFO 消失、调回 INFO 后回来；
 *   <li>{@link SdLog#TRACE_LOGGER_NAME} 关闭时逐条 {@code SD_EFFECT_FIRED} 不出现、打开后出现；
 *   <li>TICK 类事件（{@code SD_ADVANCE_START/DAY/END}）带 {@code day} 与 {@code origin=sd-tick
 *       originKind=tick}。
 * </ul>
 *
 * <p>★ 装置住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl（sd 模块自身没有，见依赖树）；这里只借 app
 * 的类路径，被验对象仍是 {@code io.mosire.simos.sd} 及其子 logger。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：log4j2 没有配置时 root level 是 ERROR，只挂 appender 不抬
 * level 会收到空集；空集上"不含某串"会假绿（core 的 {@code CommandBusLoggingTest} 已记过这个坑）。
 */
class SdLoggingTest {

  private static final String APPENDER_NAME = "sd-logging-capture";
  private static final String MAP_ID = "Map1";
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final EffectId E1 = new EffectId("e1");
  private static final NationId N1 = new NationId("n1");
  private static final SdTimeParticipant PARTICIPANT = new SdTimeParticipant(MAP_ID);

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalRootLevel = configuration.getLoggerConfig(SdLog.ROOT_LOGGER_NAME).getLevel();
    originalTraceLevel = configuration.getLoggerConfig(SdLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(SdLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(SdLog.TRACE_LOGGER_NAME, originalTraceLevel);
    attachAppender();
    context.updateLoggers();
    configuration.getLoggerConfig(SdLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  /** 前提断言：装置真的能收到 sd 的日志行，否则其余"不包含"断言全部是空集假绿。 */
  @Test
  void logLinesAreActuallyCaptured() {
    setSdLevel(Level.INFO);
    appender.clear();
    rejectGhostActor();

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  /**
   * 真 handler 的成功 INFO：{@code CreateDecisionMakerHandler} 在 sd 切片里成功落一个决策人， 事件名 + 关键字段 + {@code
   * origin=sd-nation originKind=system} 必须齐全。
   *
   * <p>判别力：事件名写错、缺 origin/originKind、把命令面标成 interaction、字段值算错，这里当场红。
   */
  @Test
  void successEventCarriesNameFieldsOriginAndOriginKind() {
    setSdLevel(Level.INFO);
    appender.clear();

    HandlerOutcome outcome =
        new CreateDecisionMakerHandler()
            .handle(
                sdWorld(nationBase()),
                "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                    + "\"allowedTools\":[\"sd.SubmitVerdict\"],\"cadence\":3}");

    assertThat(outcome)
        .as("夹具必须真的走到成功路径，否则后面的日志断言可能只是空跑。实得 %s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_DECISION_MAKER_CREATED",
        "origin=sd-nation",
        "originKind=system",
        "id=dm9",
        "cadence=3",
        "allowedTools=1",
        "decisionMakers=1");
  }

  /**
   * 具名拒绝：用户 2026-10-23 裁定「被拒绝肯定走 INFO」。两条 {@link ChannelAdmission} 拒绝路径都必须出 {@code INFO|} 行， 带
   * reason、actor/commandType 与来源。
   *
   * <p>判别力：把拒绝行降到 DEBUG/WARN、去掉 origin 或漏掉 reason，这里当场红。
   */
  @Test
  void namedRejectionsAreLoggedAsInfoWithOriginAndReason() {
    setSdLevel(Level.INFO);

    appender.clear();
    rejectGhostActor();
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_CHANNEL_ADMISSION_REJECTED",
        "origin=sd-decision",
        "originKind=system",
        "actor=ghost",
        "commandType=-",
        "reason=actor-not-representable");

    appender.clear();
    assertThatThrownBy(() -> ChannelAdmission.requireLandingPoint("simos.command.submit"))
        .isInstanceOf(IllegalArgumentException.class);
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_CHANNEL_ADMISSION_REJECTED",
        "origin=sd-decision",
        "originKind=system",
        "actor=-",
        "commandType=simos.command.submit",
        "reason=landing-point-not-allowed");
  }

  /** 根开关：{@code SdLog.ROOT_LOGGER_NAME} 调到 WARN ⇒ INFO 消失，调回 INFO ⇒ 同一事件回来。 */
  @Test
  void rootLevelSwitchTogglesInfoEvents() {
    setSdLevel(Level.INFO);
    appender.clear();
    rejectGhostActor();
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_CHANNEL_ADMISSION_REJECTED",
        "reason=actor-not-representable");

    setSdLevel(Level.WARN);
    appender.clear();
    rejectGhostActor();
    assertThat(appender.messages())
        .as("WARN 时 INFO 拒绝行必须消失；实得 %s", appender.messages())
        .noneMatch(line -> line.contains("event=SD_CHANNEL_ADMISSION_REJECTED"));

    setSdLevel(Level.INFO);
    appender.clear();
    rejectGhostActor();
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_CHANNEL_ADMISSION_REJECTED",
        "reason=actor-not-representable");
  }

  /**
   * TRACE 开关：{@code SdLog.TRACE_LOGGER_NAME} 关闭时逐条 effect 明细不出现；打开后同一推进出 {@code TRACE|} 的 {@code
   * SD_EFFECT_FIRED}。两次都断言 INFO 的 {@code SD_ADVANCE_START}，保证夹具真的推进过。
   */
  @Test
  void traceLoggerSwitchTogglesEffectFiredDetail() {
    SdState base = effectState(new Trigger.AtOrAfterTick(1));
    setSdLevel(Level.INFO);
    setTraceLevel(Level.INFO);

    appender.clear();
    PARTICIPANT.simulate(tickWorld(base), range(0, 1));
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_ADVANCE_START",
        "origin=sd-tick",
        "originKind=tick",
        "day=0");
    assertThat(appender.messages())
        .as("TRACE 关闭时逐条 effect 明细不得出现；实得 %s", appender.messages())
        .noneMatch(line -> line.contains("event=SD_EFFECT_FIRED"));

    setTraceLevel(Level.TRACE);
    appender.clear();
    PARTICIPANT.simulate(tickWorld(base), range(0, 1));
    assertEvent(
        appender.messages(),
        "TRACE|",
        "SD_EFFECT_FIRED",
        "origin=sd-tick",
        "originKind=tick",
        "day=1",
        "id=e1");
  }

  /**
   * TICK 事件：{@code SdTimeParticipant} 推 0→2 天，START/DAY/END 都必须带 {@code day} 且来源是 {@code
   * origin=sd-tick originKind=tick}（DEBUG 的 DAY 行一并证明判据级明细）。
   */
  @Test
  void tickAdvanceEventsCarryDayAndTickOrigin() {
    setSdLevel(Level.DEBUG);
    appender.clear();

    SdState base = effectState(new Trigger.AtOrAfterTick(1));
    PARTICIPANT.simulate(tickWorld(base), range(0, 2));

    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_ADVANCE_START",
        "origin=sd-tick",
        "originKind=tick",
        "day=0",
        "from=0",
        "to=2",
        "days=2",
        "effects=1");
    assertEvent(
        appender.messages(),
        "DEBUG|",
        "SD_ADVANCE_DAY",
        "origin=sd-tick",
        "originKind=tick",
        "day=1",
        "effectsEvaluated=1",
        "effectsFired=1");
    assertEvent(
        appender.messages(),
        "INFO|",
        "SD_ADVANCE_END",
        "origin=sd-tick",
        "originKind=tick",
        "day=2",
        "from=0",
        "to=2",
        "days=2",
        "firedEffects=1");
  }

  private void rejectGhostActor() {
    assertThatThrownBy(
            () ->
                ChannelAdmission.requireRepresentable(
                    Set.of(new ActorId("dm-a")), new ActorId("ghost")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void setSdLevel(Level level) {
    Configurator.setLevel(SdLog.ROOT_LOGGER_NAME, level);
    attachAppender();
    context.updateLoggers();
  }

  private void setTraceLevel(Level level) {
    Configurator.setLevel(SdLog.TRACE_LOGGER_NAME, level);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(SdLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static SimulationState sdWorld(SdState sd) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of("sd", new SdSnapshot(REF, T0, sd)),
        InMemoryInfoSystem.empty());
  }

  /** sd 时间参与者只读 sd + unit 两个切片；空 unit 表即可（effect 触发条件不引用单位）。 */
  private static SimulationState tickWorld(SdState sd) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of(
            "sd", new SdSnapshot(REF, T0, sd),
            "unit", new UnitSnapshot(REF, T0, UnitState.empty())),
        InMemoryInfoSystem.empty());
  }

  private static SdState nationBase() {
    Nation nation = new Nation(N1, "甲国", new RegionId("r1"), 5);
    return SdState.empty().withNations(Map.of(N1, nation));
  }

  private static SdState effectState(Trigger trigger) {
    Effect effect =
        new Effect(
            E1,
            EffectKind.SCHEDULED,
            trigger,
            new Action.PutInfo(Address.parse("map:Map1"), "k", "v"),
            EffectStatus.PLANNED,
            0L);
    return SdState.empty().withEffects(Map.of(E1, effect));
  }

  private static TimeRange range(long fromTick, long toTick) {
    return new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)));
  }

  private static void assertEvent(
      List<String> lines, String levelPrefix, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s 且含 event=%s 与 %s（实得 %s）", levelPrefix, event, List.of(fragments), lines)
        .anyMatch(
            line -> {
              if (!line.startsWith(levelPrefix) || !line.contains("event=" + event)) {
                return false;
              }
              for (String fragment : fragments) {
                if (!line.contains(fragment)) {
                  return false;
                }
              }
              return true;
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
