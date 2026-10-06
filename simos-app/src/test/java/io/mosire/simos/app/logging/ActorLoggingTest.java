package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.actor.ops.AccountOperations;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
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
 * ★★ L4/§9 actor 日志验收（计划 {@code 2026-10-23-all-module-logging-rollout} §3/§4.2/§4.4/§9）：
 *
 * <ul>
 *   <li>{@link ActorSeedHandler} 成功与具名拒绝至少各一条 INFO，带 {@code event/origin/originKind} 与关键字段；
 *   <li>具名拒绝按用户 2026-10-23 裁定走 INFO（「被拒绝肯定走 INFO」）；
 *   <li>{@link ActorLog#ROOT_LOGGER_NAME} 与 {@link ActorLog#TRACE_LOGGER_NAME} 的开关真的改变捕获内容。
 * </ul>
 *
 * <p>★ 装置住 app：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl；夹具照 actor 模块的 {@code
 * ActorSeedHandlerTest}（真 {@link SimulationState} + 只有 actor 切片，不打 DB）。
 *
 * <p>★ {@link #logLinesAreActuallyCaptured} 是前提断言：log4j2 没有配置时 root level 是 ERROR， 只挂 appender 不抬
 * level 会收到空集，空集上的「不含某串」会假绿。
 *
 * <p>★ TICK/day 判据：actor 模块 {@code src/main} 没有 {@code TimeParticipant}、来源表也没有 {@code
 * originKind=tick} 的发射点 （命令面事件一律 {@code originKind=system}，不带 {@code day}）⇒ 本测试不伪造 TICK
 * 行；该项在报告中如实记为「未触发」。
 */
class ActorLoggingTest {

  private static final String APPENDER_NAME = "actor-logging-capture";
  private static final ActorSeedHandler HANDLER = new ActorSeedHandler();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HouseholdId TRACE_FROM = HouseholdId.parse("hh-trace-from");
  private static final HouseholdId TRACE_TO = HouseholdId.parse("hh-trace-to");
  private static final CommodityId GRAIN = CommodityId.parse("grain");
  private static final CurrencyId SILVER = CurrencyId.parse("silver");

  /** 一格的最小合法 entry：两个主体 + 一本账（照 {@code ActorSeedHandlerTest}）。 */
  private static final String ENTRY_0 =
      "{\"q\":0,\"r\":0,"
          + "\"actors\":[{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\",\"label\":\"农业组织者\"},"
          + "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"}],"
          + "\"goods\":[{\"household\":\"hh-house-0_0\",\"balances\":{\"grain\":2241000,\"fiber\":0}}]}";

  private static final String PAYLOAD = payload(ENTRY_0);

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalRootLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalRootLevel = configuration.getLoggerConfig(ActorLog.ROOT_LOGGER_NAME).getLevel();
    originalTraceLevel = configuration.getLoggerConfig(ActorLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(ActorLog.ROOT_LOGGER_NAME, originalRootLevel);
    Configurator.setLevel(ActorLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(ActorLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  /** log4j2.xml 的 actor 开关真的接在 {@code simos.actor.logLevel/traceLevel} 上（缺显式 LoggerConfig 即红）。 */
  @Test
  void log4jConfigReadsTheActorLevelProperties() {
    LoggerConfig actorConfig = configuration.getLoggerConfig(ActorLog.ROOT_LOGGER_NAME);
    assertThat(actorConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.actor 建一条显式 LoggerConfig")
        .isEqualTo(ActorLog.ROOT_LOGGER_NAME);
    assertThat(actorConfig.getLevel().toString())
        .as("默认/覆盖级别必须来自 simos.actor.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.actor.logLevel", "INFO"));
    assertThat(originalTraceLevel.toString())
        .as("TRACE 明细级别必须来自 simos.actor.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.actor.traceLevel", "INFO"));
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setActorLevel(Level.INFO, Level.INFO);
    seedFirst();

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void seedSuccessIsLoggedAsInfoWithEventFieldsAndOrigin() {
    setActorLevel(Level.INFO, Level.INFO);

    ActorData seeded = seedFirst();

    assertThat(seeded.meta().orElseThrow().activatedDay()).as("夹具真的落了盘").isEqualTo(7L);
    assertInfoEvent(
        appender.messages(),
        "ACTOR_SEEDED",
        "first=true",
        "actors=2",
        "accounts=1",
        "origin=actor-seed",
        "originKind=system");
  }

  @Test
  void duplicateSeedIsLoggedAsInfoRejectionWithReasonAndHousehold() {
    setActorLevel(Level.INFO, Level.INFO);
    ActorData first = seedFirst();
    appender.clear();

    HandlerOutcome outcome = HANDLER.handle(state(first, T7), PAYLOAD);

    assertThat(outcome).as("重复格必须具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("hh-house-0_0");
    assertInfoEvent(
        appender.messages(),
        "ACTOR_SEED_REJECTED",
        "origin=actor-seed",
        "originKind=system",
        "reason=household-account-exists",
        "household=hh-house-0_0");
  }

  @Test
  void malformedPayloadRejectionIsInfoWithNonEmptyReason() {
    setActorLevel(Level.INFO, Level.INFO);
    appender.clear();

    HandlerOutcome outcome = HANDLER.handle(state(ActorData.empty(), T7), "not json");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(appender.messages())
        .as("实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|")
                    .contains("event=ACTOR_SEED_REJECTED")
                    .contains("origin=actor-seed")
                    .contains("originKind=system")
                    .containsPattern("reason=\\S+")
                    .doesNotContain("not json"));
  }

  /** ★ actor 根级别 WARN ⇒ INFO 事件消失；调回 INFO ⇒ 回来。 */
  @Test
  void actorLevelSwitchHidesThenRevealsSeedInfo() {
    setActorLevel(Level.INFO, Level.INFO);
    seedFirst();
    assertInfoEvent(appender.messages(), "ACTOR_SEEDED", "origin=actor-seed");

    appender.clear();
    setActorLevel(Level.WARN, Level.INFO);
    seedFirst();
    assertThat(appender.messages())
        .as("actor 根级别 WARN ⇒ INFO 事件必须消失；实得 %s", appender.messages())
        .noneMatch(line -> line.contains("event=ACTOR_SEEDED"));

    appender.clear();
    setActorLevel(Level.INFO, Level.INFO);
    seedFirst();
    assertInfoEvent(appender.messages(), "ACTOR_SEEDED", "origin=actor-seed", "originKind=system");
  }

  /** ★ {@code .trace} 子 logger：TRACE 时逐笔腿出现，调回 INFO 后消失，而 INFO 写口行仍在。 */
  @Test
  void traceLoggerShowsLegDetailAndInfoLevelClosesItAgain() {
    setActorLevel(Level.INFO, Level.TRACE);
    appender.clear();

    ActorData after =
        AccountOperations.transfer(
            accountFixture(), TRACE_FROM, TRACE_TO, Map.of(GRAIN, 40L), Map.of(SILVER, 7L));

    HouseholdAccountKey fromKey = new HouseholdAccountKey(TRACE_FROM);
    assertThat(after.accounts().get(fromKey).balances()).containsEntry(GRAIN, 960L);
    assertThat(after.accounts().get(fromKey).money()).containsEntry(SILVER, 43L);

    assertInfoEvent(
        appender.messages(),
        "ACTOR_ACCOUNTS_TRANSFERRED",
        "from=hh-trace-from",
        "to=hh-trace-to",
        "goods=1",
        "money=1",
        "origin=actor-account",
        "originKind=system");
    assertEvent(
        appender.messages(),
        "ACTOR_TRANSFER_GOODS",
        "from=hh-trace-from",
        "commodity=grain",
        "amount=40",
        "origin=actor-account",
        "originKind=system");
    assertEvent(
        appender.messages(),
        "ACTOR_TRANSFER_MONEY",
        "from=hh-trace-from",
        "currency=silver",
        "amount=7",
        "origin=actor-account",
        "originKind=system");

    appender.clear();
    setActorLevel(Level.INFO, Level.INFO);
    AccountOperations.transfer(
        accountFixture(), TRACE_FROM, TRACE_TO, Map.of(GRAIN, 40L), Map.of(SILVER, 7L));

    assertThat(appender.messages())
        .as("TRACE 调回 INFO ⇒ 逐笔明细必须消失；实得 %s", appender.messages())
        .noneMatch(
            line ->
                line.contains("event=ACTOR_TRANSFER_GOODS")
                    || line.contains("event=ACTOR_TRANSFER_MONEY"));
    assertInfoEvent(appender.messages(), "ACTOR_ACCOUNTS_TRANSFERRED", "origin=actor-account");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private void setActorLevel(Level root, Level trace) {
    Configurator.setLevel(ActorLog.ROOT_LOGGER_NAME, root);
    Configurator.setLevel(ActorLog.TRACE_LOGGER_NAME, trace);
    attachAppender();
    context.updateLoggers();
  }

  /** {@code Configurator.setLevel} 会替换 LoggerConfig ⇒ 每次改级别后把采集 appender 重新挂上。 */
  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(ActorLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static ActorData seedFirst() {
    HandlerOutcome outcome = HANDLER.handle(state(ActorData.empty(), T7), PAYLOAD);
    assertThat(outcome).as("合法首播载荷必须 Applied").isInstanceOf(HandlerOutcome.Applied.class);
    return ActorChangeSet.apply(
        (ActorChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), ActorData.empty());
  }

  private static ActorData accountFixture() {
    HouseholdAccountKey fromKey = new HouseholdAccountKey(TRACE_FROM);
    return ActorData.empty()
        .withInventory(new HouseholdInventory(fromKey, Map.of(GRAIN, 1000L), Map.of(SILVER, 50L)));
  }

  private static SimulationState state(ActorData data, SimosTimestamp timestamp) {
    return new SimulationState(
        new StateMeta(REF, timestamp),
        Map.of("actor", new ActorSnapshot(REF, timestamp, data)),
        InMemoryInfoSystem.empty());
  }

  private static String payload(String entriesJson) {
    return "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[" + entriesJson + "]}";
  }

  private static void assertInfoEvent(List<String> lines, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 INFO event=%s（实得 %s）", event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).startsWith("INFO|").contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
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
