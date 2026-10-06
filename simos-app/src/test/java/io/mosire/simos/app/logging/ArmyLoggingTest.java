package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.army.spi.RecordCombatHandler;
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
 * army 模块日志验收（计划 §3 / §4.2 / §4.4 / §9）：
 *
 * <ul>
 *   <li>{@link RecordCombatHandler} 的成功写口至少一条真实 INFO（事件名 + 关键字段 + {@code origin=}/{@code
 *       originKind=}）；
 *   <li>{@code legacy-losses-field} / {@code combat-id-exists} / {@code tick-in-future} 三条具名拒绝一律
 *       INFO，带 {@code reason=} 与模块来源；未来 tick 拒绝还带 {@code tick=}/{@code worldTick=}（§4.4 TICK 字段口径）；
 *   <li>{@code Configurator} 把 army 根 logger 调到 WARN ⇒ INFO 消失、调回 INFO 回来；
 *   <li>逐条战损 TRACE（{@code .trace} 子 logger）只在 TRACE 打开时出现，调回 INFO 消失。
 * </ul>
 *
 * <p>★ 装置住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl（army 模块自身没有）；这里只借 app 的类路径，被验对象仍是
 * {@code io.mosire.simos.army} 及其子 logger。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured()} 是前提断言：log4j2 没有配置时 root level 是 ERROR，只挂 appender 不抬
 * level 会收到空集；空集上"不含某串"会假绿（Social 的 {@code SocialLoggingTest} 已记过这个坑）。
 *
 * <p>★ 夹具是"真 {@link SimulationState} + 只有 army 切片"，照 {@code ArmyFixtures.world} 的最小构造内联（army 无
 * test-jar，不能 import 它的测试夹具）。
 */
class ArmyLoggingTest {

  private static final String APPENDER_NAME = "army-logging-capture";
  private static final RecordCombatHandler HANDLER = new RecordCombatHandler();
  private static final long WORLD_TICK = 7L;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig armyConfig;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    armyConfig = configuration.getLoggerConfig(ArmyLog.ROOT_LOGGER_NAME);
    originalRootLevel = armyConfig.getLevel();
    originalTraceLevel = configuration.getLoggerConfig(ArmyLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(ArmyLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(ArmyLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(ArmyLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    ArmyData next = applyRecorded(ArmyData.empty(), WORLD_TICK, payload("c-capture", ""));

    assertThat(next.combats())
        .as("夹具必须先真的写进去一条记录，否则下面的捕获断言可能只是空跑")
        .containsKey(new CombatRecordId("c-capture"));
    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
    assertThat(appender.messages())
        .as("默认 INFO 档至少要抓到一条 INFO| 行；实得 %s", appender.messages())
        .anySatisfy(line -> assertThat(line).startsWith("INFO|"));
  }

  @Test
  void log4jConfigReadsTheArmyLevelProperties() {
    assertThat(armyConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.army 建一条显式 LoggerConfig")
        .isEqualTo(ArmyLog.ROOT_LOGGER_NAME);
    assertThat(armyConfig.getLevel().toString())
        .as("默认/覆盖级别必须来自 simos.army.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.army.logLevel", "INFO"));
    assertThat(originalTraceLevel.toString())
        .as("TRACE 明细级别必须来自 simos.army.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.army.traceLevel", "INFO"));
  }

  /** ★ §4.2：成功写口是 INFO，带事件名 + 关键字段 + origin/originKind；战斗是结算类事实，必有 tick。 */
  @Test
  void recordedCombatIsLoggedAsInfoWithKeysOriginAndTick() {
    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    applyRecorded(ArmyData.empty(), WORLD_TICK, payload("c-1", ""));

    assertInfoEvent(
        appender.messages(),
        "ARMY_COMBAT_RECORDED",
        "combat=c-1",
        "kind=野战",
        "tick=" + WORLD_TICK,
        "hex=3_4",
        "participants=2",
        "stages=1",
        "origin=army-combat",
        "originKind=system");
  }

  /** ★ 用户 2026-10-23：「被拒绝肯定走 INFO」——旧 D1 的 {@code losses} 字段必须 INFO + 具名 reason。 */
  @Test
  void legacyLossesFieldRejectionIsInfoWithReason() {
    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    HandlerOutcome outcome =
        HANDLER.handle(
            state(ArmyData.empty(), WORLD_TICK), payload("c-legacy", ",\"losses\":{\"士兵\":-1}"));

    assertThat(outcome)
        .as("旧 losses 字段必须具名拒，不得静默丢、不得抛到命令边界之外")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertInfoEvent(
        appender.messages(),
        "ARMY_COMBAT_RECORD_REJECTED",
        "type=army.RecordCombat",
        "reason=legacy-losses-field",
        "origin=army-combat",
        "originKind=system");
  }

  /** ★ 具名拒绝：同 id 已存在 ⇒ INFO，reason 点名 combat-id-exists 与稳定 id。 */
  @Test
  void duplicateCombatIdRejectionIsInfoWithReason() {
    setLevels(Level.INFO, Level.INFO);
    ArmyData first = applyRecorded(ArmyData.empty(), WORLD_TICK, payload("c-dup", ""));
    appender.clear();

    HandlerOutcome second = HANDLER.handle(state(first, WORLD_TICK), payload("c-dup", ""));

    assertThat(second).as("记录 id 是一次性身份，不得覆盖").isInstanceOf(HandlerOutcome.Rejected.class);
    assertInfoEvent(
        appender.messages(),
        "ARMY_COMBAT_RECORD_REJECTED",
        "reason=combat-id-exists",
        "combat=c-dup",
        "origin=army-combat",
        "originKind=system");
  }

  /** ★ §4.4：带 tick 的事实必须把 tick/worldTick 写进行里，才能回答"这条为什么没记上"。 */
  @Test
  void futureTickRejectionIsInfoWithTickAndWorldTick() {
    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    HandlerOutcome outcome =
        HANDLER.handle(state(ArmyData.empty(), WORLD_TICK), payload("c-future", ",\"tick\":8"));

    assertThat(outcome).as("未来 tick 必须具名拒").isInstanceOf(HandlerOutcome.Rejected.class);
    assertInfoEvent(
        appender.messages(),
        "ARMY_COMBAT_RECORD_REJECTED",
        "reason=tick-in-future",
        "combat=c-future",
        "tick=8",
        "worldTick=" + WORLD_TICK,
        "origin=army-combat",
        "originKind=system");
  }

  /** ★ §9.3：根 logger 开关有效——WARN 时 INFO 消失，调回 INFO 后恢复。 */
  @Test
  void configuratorSwitchesArmyInfoOffAndOn() {
    setLevels(Level.WARN, Level.INFO);
    appender.clear();

    ArmyData muted = applyRecorded(ArmyData.empty(), WORLD_TICK, payload("c-muted", ""));

    assertThat(muted.combats())
        .as("handler 在 WARN 档仍必须真的执行（只静日志，不改行为）")
        .containsKey(new CombatRecordId("c-muted"));
    assertThat(appender.messages())
        .as("army 根 logger 调到 WARN 后 INFO 事件必须被过滤；实得 %s", appender.messages())
        .isEmpty();

    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    applyRecorded(ArmyData.empty(), WORLD_TICK, payload("c-visible", ""));

    assertInfoEvent(appender.messages(), "ARMY_COMBAT_RECORDED", "combat=c-visible");
  }

  /** ★ §9.3：逐条战损 TRACE 只在 {@code .trace} 子 logger 打开时出现，调回 INFO 消失。 */
  @Test
  void traceLossesAppearOnlyWhileTraceLoggerIsEnabled() {
    setLevels(Level.INFO, Level.TRACE);
    appender.clear();

    applyRecorded(ArmyData.empty(), WORLD_TICK, lossPayload("c-trace"));

    assertTraceEvent(
        appender.messages(),
        "ARMY_COMBAT_LOSS_MANPOWER",
        "combat=c-trace",
        "stage=s1",
        "outcome=o1",
        "unit=u-1",
        "type=士兵",
        "amount=-30",
        "origin=army-combat",
        "originKind=system");
    assertTraceEvent(
        appender.messages(),
        "ARMY_COMBAT_LOSS_EQUIPMENT",
        "combat=c-trace",
        "stage=s1",
        "outcome=o1",
        "unit=u-1",
        "type=步枪",
        "amount=-5");

    setLevels(Level.INFO, Level.INFO);
    appender.clear();

    applyRecorded(ArmyData.empty(), WORLD_TICK, lossPayload("c-trace-off"));

    assertInfoEvent(appender.messages(), "ARMY_COMBAT_RECORDED", "combat=c-trace-off");
    assertThat(appender.messages())
        .as("TRACE 调回 INFO 后逐条战损不得再出现；实得 %s", appender.messages())
        .allSatisfy(line -> assertThat(line).doesNotContain("ARMY_COMBAT_LOSS_"));
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 合法载荷：id/kind/hex/participants/text 必填，{@code extraFields} 以逗号开头拼进同一层。 */
  private static String payload(String id, String extraFields) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\"野战\",\"hex\":{\"q\":3,\"r\":4},"
        + "\"participants\":[\"u-1\",\"u-2\"],\"text\":\"记录级过程\""
        + extraFields
        + "}";
  }

  /** 带一个含 losses 的 initialStage：RecordCombatHandler 成功路径会逐条发 TRACE 战损。 */
  private static String lossPayload(String id) {
    return payload(
        id,
        ",\"initialStage\":{\"id\":\"s1\",\"name\":\"接触\","
            + "\"participants\":[\"u-1\",\"u-2\"],\"text\":\"阶段过程\","
            + "\"outcomes\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":60,"
            + "\"losses\":[{\"unit\":\"u-1\","
            + "\"manpower\":[{\"type\":\"士兵\",\"amount\":-30}],"
            + "\"equipment\":[{\"type\":\"步枪\",\"amount\":-5}]}]}]}");
  }

  /** 真 {@link SimulationState} + 只有 army 切片（不打 DB），形态照 army 模块的 {@code ArmyFixtures.world}。 */
  private static SimulationState state(ArmyData data, long tick) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new SimulationState(
        new StateMeta(ref, at),
        Map.of("army", new ArmySnapshot(ref, at, data)),
        InMemoryInfoSystem.empty());
  }

  private static ArmyData applyRecorded(ArmyData base, long tick, String payloadJson) {
    HandlerOutcome outcome = HANDLER.handle(state(base, tick), payloadJson);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    ArmyChangeSet changeSet = (ArmyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return ArmyChangeSet.apply(changeSet, base);
  }

  private void setLevels(Level root, Level trace) {
    Configurator.setLevel(ArmyLog.ROOT_LOGGER_NAME, root);
    Configurator.setLevel(ArmyLog.TRACE_LOGGER_NAME, trace);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(ArmyLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static void assertInfoEvent(List<String> lines, String event, String... fragments) {
    assertEvent(lines, "INFO", event, fragments);
  }

  private static void assertTraceEvent(List<String> lines, String event, String... fragments) {
    assertEvent(lines, "TRACE", event, fragments);
  }

  private static void assertEvent(
      List<String> lines, String level, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s| event=%s（实得 %s）", level, event, lines)
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
