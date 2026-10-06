package io.mosire.simos.core.advance;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreLog;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>core 推进面日志契约</b>（计划 §3/§4.2/§4.4/§9）：{@link TimeAdvance} 的 INFO/DEBUG/TRACE 三档到底发不发、
 * 字段全不全、开关是否有效。
 *
 * <p>本类守四类契约、共六条用例（逐条对应判据，不做"有日志就算数"的粗断言）：
 *
 * <ol>
 *   <li>{@link #logLinesAreActuallyCaptured()} 是<b>前提断言</b>——log4j2 无配置时 root 是 ERROR，只挂 appender
 *       不抬 level 会收到空集；空集上任何"不含 X"的断言都会假绿（{@code CommandBusLoggingTest} 已记过这个坑）。
 *   <li>至少一条真实 INFO：成功推进的 {@code ADVANCE_END}——事件名 + {@code day}/{@code branch}/{@code revision}
 *       等关键字段 + {@code origin=tick-advance} + {@code originKind=tick}（§4.4：TICK 类必带 day）。
 *   <li>DEBUG 阶段行（{@code ADVANCE_START}/{@code PROPOSE}/{@code RESOLVE}/{@code VALIDATE}/{@code
 *       COMMIT}）只在 {@link CoreLog#ADVANCE_LOGGER_NAME} 调到 DEBUG 时出现，调回 INFO 后整体消失。
 *   <li>逐提案 TRACE（{@code ADVANCE_PROPOSAL}）只在 {@link CoreLog#TRACE_LOGGER_NAME} 调到 TRACE 时出现，调回
 *       INFO 后消失； 同时用"同一轮 DEBUG 行仍在"证明消失不是因为装置没在收。
 *   <li>两条 INFO 结局另成用例：具名拒绝 {@code ADVANCE_REJECTED} 带 reason（§4.2"被拒绝一律 INFO"）、冲突 {@code
 *       ADVANCE_CONFLICTED} 报真实 head 而不是调用方期望值。
 * </ol>
 *
 * <p>★ 装置形态照 {@code CommandBusLoggingTest}/{@code SocialLoggingTest}：真 log4j-core 采集（core 测试类路径有
 * log4j-slf4j2-impl + log4j-core，均 test scope），{@code Configurator.setLevel} 改级后把 appender
 * <b>重挂</b>到新 LoggerConfig，{@code @AfterEach} 还原原级别并摘除 appender。 ★ 夹具最小：一条创世 revision + 一个 toy 参与者
 * + 对应 codec，推进 0→1 天（照 {@code TimeAdvanceTest} 的装法，但只保留日志契约需要的那几件）。
 */
class CoreAdvanceLoggingTest {

  /** 采集 appender 的名字（全局注册名；@AfterEach 按它摘除）。 */
  private static final String APPENDER_NAME = "core-advance-logging-capture";

  /** 推进面来源：§4.4 的来源表项 {@code CoreLogSource.TICK_ADVANCE} 渲染出的两个字段。 */
  private static final String EVENT_ORIGIN = "origin=tick-advance";

  private static final String EVENT_KIND = "originKind=tick";

  /** checkpoint 周期 4：目标 revision 2 不命中 ⇒ 本用例只观测推进面，不被 checkpoint 的 store 行混入。 */
  private static final long CHECKPOINT_INTERVAL = 4L;

  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  private SqliteStore store;
  private TimeAdvance route;
  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalAdvanceLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installFixtureAndCapture() {
    store = SqliteStore.open(tempDir.resolve("advance-logging.db"));
    Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
    seedGenesis(timeline);
    route =
        new TimeAdvance(
            timeline,
            CoreAdvanceLoggingTest::stateAt,
            new CheckpointStore(tempDir),
            List.of(new ToyCodec("alpha")),
            List.of(new ToyParticipant("alpha")));

    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    // ★ 记下原级别：本模块测试类路径没有 log4j2.xml，未建显式 LoggerConfig 时这里拿到的是 root（默认 ERROR），
    //   @AfterEach 必须还原，否则 DEBUG/TRACE 会漏给同 JVM 的其它用例。
    originalAdvanceLevel = configuration.getLoggerConfig(CoreLog.ADVANCE_LOGGER_NAME).getLevel();
    originalTraceLevel = configuration.getLoggerConfig(CoreLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    context.updateLoggers();
  }

  @AfterEach
  void removeCaptureAndRestoreLevels() {
    if (context != null) {
      Configurator.setLevel(CoreLog.ADVANCE_LOGGER_NAME, originalAdvanceLevel);
      Configurator.setLevel(CoreLog.TRACE_LOGGER_NAME, originalTraceLevel);
      context.updateLoggers();
      for (String name : List.of(CoreLog.ADVANCE_LOGGER_NAME, CoreLog.TRACE_LOGGER_NAME)) {
        configuration.getLoggerConfig(name).removeAppender(APPENDER_NAME);
      }
      configuration.removeAppender(APPENDER_NAME);
      context.updateLoggers();
      appender.stop();
    }
    if (store != null) {
      store.close();
    }
  }

  /**
   * ★★ <b>前提断言</b>：装置真的在收日志。没有这一条，后面"不含某串"式否定断言可能在空捕获上假绿。
   *
   * <p>不满足于"非空"：同时要求看见真实成功推进的 INFO 结局，避免用噪声行凑数。
   */
  @Test
  void logLinesAreActuallyCaptured() {
    tune(Level.DEBUG, Level.INFO);

    CommandResult result = runAdvance("cmd-capture", "corr-capture", 1L, 0L, 1L);

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
    assertThat(linesAt(Level.INFO, "ADVANCE_END")).as("前提断言必须看见真实推进的 INFO 结局，不能只靠噪声行凑数").hasSize(1);
  }

  /**
   * INFO 档：成功推进必须有一条 {@code ADVANCE_END}，且带事件名、关键字段与 §4.4 的 {@code origin}/{@code originKind}； TICK
   * 类必带 {@code day}。
   *
   * <p>★ 判别力：夹具确定读数 {@code from=0/to=1/day=1/revision=2/participants=1}；实现漏字段、把 kind 标成
   * interaction、或把这条 INFO 降成 DEBUG（只剩空捕获）都会当场红。
   */
  @Test
  void advanceEndIsRealInfoEventWithTickOriginAndDay() {
    tune(Level.INFO, Level.INFO);

    CommandResult result = runAdvance("cmd-end", "corr-end", 1L, 0L, 1L);

    assertThat(result).as("夹具必须先跑出成功推进，否则下面的日志断言只是空跑").isInstanceOf(CommandResult.Committed.class);
    assertThat(appender.messages())
        .as("INFO 下推进面只应有生命周期结局这一条，实得 %s", appender.messages())
        .hasSize(1);
    assertThat(onlyLineAt(Level.INFO, "ADVANCE_END"))
        .contains(EVENT_ORIGIN, EVENT_KIND)
        .contains("day=1")
        .contains("commandId=cmd-end")
        .contains("correlationId=corr-end")
        .contains("branch=main")
        .contains("revision=2")
        .contains("from=0")
        .contains("to=1")
        .contains("spanDays=1")
        .contains("participants=1")
        .contains("participantOrder=[alpha]");
  }

  /**
   * DEBUG/INFO 开关（用 {@link CoreLog#ADVANCE_LOGGER_NAME} 验）：DEBUG 时六阶段边界行齐全且带来源字段；调回 INFO 后同一路径
   * <b>一条 DEBUG 都不剩</b>，但 INFO 结局仍在（证明不是装置瞎了）。
   */
  @Test
  void advancePhaseDebugLinesAppearOnlyWhileAdvanceLoggerIsAtDebug() {
    tune(Level.DEBUG, Level.INFO);
    assertThat(runAdvance("cmd-debug", "corr-debug", 1L, 0L, 1L))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(onlyLineAt(Level.DEBUG, "ADVANCE_START"))
        .contains(EVENT_ORIGIN, EVENT_KIND)
        .contains("day=1")
        .contains("commandId=cmd-debug")
        .contains("from=0")
        .contains("to=1")
        .contains("spanDays=1")
        .contains("participants=1")
        .contains("participantOrder=[alpha]");
    assertThat(onlyLineAt(Level.DEBUG, "ADVANCE_PROPOSE")).contains("proposals=1");
    assertThat(onlyLineAt(Level.DEBUG, "ADVANCE_RESOLVE"))
        .contains("blocked=false", "readWriteWarnings=0");
    assertThat(onlyLineAt(Level.DEBUG, "ADVANCE_VALIDATE")).contains("modules=1", "ok=true");
    assertThat(onlyLineAt(Level.DEBUG, "ADVANCE_COMMIT"))
        .contains("branch=main", "revision=2", "events=4");
    assertThat(linesAt(Level.INFO, "ADVANCE_END")).hasSize(1);

    appender.clear();
    tune(Level.INFO, Level.INFO);
    assertThat(runAdvance("cmd-info", "corr-info", 2L, 1L, 2L))
        .as("第二次推进：head 已是 main@2，loader 给出 tick=1 ⇒ 1→2 天")
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(appender.messages())
        .as("★ INFO 下 DEBUG 阶段行必须整体消失，且仍有 INFO 结局证明装置在收。实得 %s", appender.messages())
        .hasSize(1)
        .allSatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|event=ADVANCE_END ")
                    .contains("day=2")
                    .contains("revision=3"));
  }

  /**
   * TRACE/INFO 开关（用 {@link CoreLog#TRACE_LOGGER_NAME} 验）：TRACE 时逐提案明细 {@code ADVANCE_PROPOSAL}
   * 出现且字段 齐全；调回 INFO 后消失——同时断言同一轮 DEBUG 阶段行仍在，排除"装置没在收"这条假绿路径。
   */
  @Test
  void advanceProposalTraceAppearsOnlyWhileTraceLoggerIsAtTrace() {
    tune(Level.DEBUG, Level.TRACE);
    assertThat(runAdvance("cmd-trace", "corr-trace", 1L, 0L, 1L))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(linesAt(Level.TRACE, "ADVANCE_PROPOSAL"))
        .as("TRACE 打开后逐提案明细必须出现；实得 %s", appender.messages())
        .hasSize(1);
    assertThat(onlyLineAt(Level.TRACE, "ADVANCE_PROPOSAL"))
        .contains(EVENT_ORIGIN, EVENT_KIND)
        .contains("day=1")
        .contains("participant=alpha")
        .contains("modules=1")
        .contains("reads=0")
        .contains("writes=0");
    assertThat(linesAt(Level.DEBUG, "ADVANCE_PROPOSE"))
        .as("前提：同一轮 DEBUG 也开着，证明采集装置真的在工作")
        .hasSize(1);

    appender.clear();
    tune(Level.DEBUG, Level.INFO);
    assertThat(runAdvance("cmd-no-trace", "corr-no-trace", 2L, 1L, 2L))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(linesAt(Level.TRACE, "ADVANCE_PROPOSAL"))
        .as("★ 调回 INFO 后逐提案 TRACE 必须消失；实得 %s", appender.messages())
        .isEmpty();
    assertThat(appender.messages())
        .as("消失不能是整套装置没在收")
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).doesNotContain("event=ADVANCE_PROPOSAL"));
  }

  /**
   * 具名拒绝是 INFO（计划 §4.2：被拒绝一律 INFO，不降级），且带来源字段与可诊断的 reason。
   *
   * <p>用"缺上界"触发 ④ 第 0 项（不查库、不碰状态），{@code TimeRange} 本身也构造不出空区间；判别力：这条若降成 DEBUG/WARN 或丢 origin，本用例红。
   */
  @Test
  void rejectedAdvanceIsInfoWithOriginAndReason() {
    tune(Level.INFO, Level.INFO);

    CommandResult result = runAdvanceWithoutUpperBound("cmd-rejected", "corr-rejected", 1L, 0L);

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(appender.messages()).as("INFO 下拒绝结局只应有这一条，实得 %s", appender.messages()).hasSize(1);
    assertThat(onlyLineAt(Level.INFO, "ADVANCE_REJECTED"))
        .contains(EVENT_ORIGIN, EVENT_KIND)
        .contains("day=0")
        .contains("commandId=cmd-rejected")
        .contains("correlationId=corr-rejected")
        .contains("caller=player:local")
        .contains("reason=推进必须有上界");
  }

  /** 冲突是 INFO，且报的是<b>真实 head</b>（main@1）而不是调用方的期望 revision（9）——印期望值就失去诊断价值。 */
  @Test
  void conflictedAdvanceIsInfoAndReportsTheRealHead() {
    tune(Level.INFO, Level.INFO);

    CommandResult result = runAdvance("cmd-conflict", "corr-conflict", 9L, 0L, 1L);

    assertThat(result).isInstanceOf(CommandResult.Conflict.class);
    assertThat(onlyLineAt(Level.INFO, "ADVANCE_CONFLICTED"))
        .contains(EVENT_ORIGIN, EVENT_KIND)
        .contains("day=0")
        .contains("commandId=cmd-conflict")
        .contains("correlationId=corr-conflict")
        .contains("headBranch=main")
        .contains("headRevision=1")
        .doesNotContain("headRevision=9");
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────

  /** 改级别后把采集 appender 重挂到新 LoggerConfig（{@code Configurator.setLevel} 会新建/替换 config）。 */
  private void tune(Level advanceLevel, Level traceLevel) {
    Configurator.setLevel(CoreLog.ADVANCE_LOGGER_NAME, advanceLevel);
    Configurator.setLevel(CoreLog.TRACE_LOGGER_NAME, traceLevel);
    attach(CoreLog.ADVANCE_LOGGER_NAME);
    attach(CoreLog.TRACE_LOGGER_NAME);
    context.updateLoggers();
  }

  private void attach(String loggerName) {
    LoggerConfig current = configuration.getLoggerConfig(loggerName);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  /** 每条捕获行形如 {@code LEVEL|event=NAME k=v ...}；这里按"级别 + 事件名 + 空格"精确取行。 */
  private List<String> linesAt(Level level, String event) {
    String prefix = level.name() + "|event=" + event + " ";
    return appender.messages().stream().filter(line -> line.startsWith(prefix)).toList();
  }

  private String onlyLineAt(Level level, String event) {
    List<String> hits = linesAt(level, event);
    assertThat(hits).as("event=%s 在 %s 档应恰有一条，实得 %s", event, level, hits).hasSize(1);
    return hits.get(0);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  private CommandResult runAdvance(
      String commandId, String correlationId, long expectedRevision, long fromTick, long toTick) {
    return route.run(
        new AdvanceTime(
            commandId,
            correlationId,
            "player:local",
            MAIN,
            new RevisionId(expectedRevision),
            new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)))));
  }

  private CommandResult runAdvanceWithoutUpperBound(
      String commandId, String correlationId, long expectedRevision, long fromTick) {
    return route.run(
        new AdvanceTime(
            commandId,
            correlationId,
            "player:local",
            MAIN,
            new RevisionId(expectedRevision),
            TimeRange.since(SimosTimestamp.of(fromTick))));
  }

  /** 建时间线并种一行创世 {@code (main, 1)}（tick=0）——head 存在是推进的前提。 */
  private static void seedGenesis(Timeline timeline) {
    timeline.appendRevision(
        new RevisionRow(
            MAIN,
            new RevisionId(1L),
            Optional.empty(),
            SimosTimestamp.of(0L),
            "cmd-seed",
            "corr-seed",
            "player:local",
            "core.AdvanceTime",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  /** 坐标 → 状态：本夹具里 revision r 的世界日 = r − 1（r=1 ⇒ tick 0；推进一次后 r=2 ⇒ tick 1）。 */
  private static SimulationState stateAt(StateRef ref) {
    long tick = ref.revision().value() - 1L;
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(tick)),
        Map.of("alpha", new ToySnapshot(ref, SimosTimestamp.of(tick), "alpha", 0)),
        InMemoryInfoSystem.empty());
  }

  /** 玩具变更集（test 侧，主源码实现者计数不受影响）。 */
  private record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具快照：把 {@code ref}/{@code timestamp} 真带在身上，好让 Validate 第 4 项可过。 */
  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int v)
      implements Snapshot {}

  /** 单切片参与者：默认 {@code simulateWorld} 会把它包成单模块提案（TRACE 行里的 participant=alpha）。 */
  private record ToyParticipant(String namespace) implements TimeParticipant {

    @Override
    public TimeProposal simulate(SimulationState state, TimeRange range) {
      return new TimeProposal(namespace, new ToyChangeSet(1), Set.of(), Set.of());
    }
  }

  /** 玩具 codec：只实现 Validate 会走到的那几件；本用例 checkpoint 不命中，故 snapshot 编解码不会被执行。 */
  private record ToyCodec(String namespace) implements ModuleCodec {

    @Override
    public ChangeSet decodeChangeSet(String json) {
      return new ToyChangeSet(Integer.parseInt(json));
    }

    @Override
    public String encodeChangeSet(ChangeSet changeSet) {
      return Integer.toString(((ToyChangeSet) changeSet).v());
    }

    @Override
    public Snapshot decodeSnapshot(String json) {
      throw new UnsupportedOperationException("本用例不读 checkpoint");
    }

    @Override
    public String encodeSnapshot(Snapshot snapshot) {
      throw new UnsupportedOperationException("本用例不写 checkpoint");
    }

    @Override
    public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
      ToySnapshot before = (ToySnapshot) base;
      return new ToySnapshot(
          newMeta.ref(),
          newMeta.timestamp(),
          namespace,
          before.v() + ((ToyChangeSet) changeSet).v());
    }
  }

  /** 采集 appender：记 {@code LEVEL|message}，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> collected = Collections.synchronizedList(new ArrayList<>());

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      collected.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      synchronized (collected) {
        return List.copyOf(collected);
      }
    }

    private void clear() {
      collected.clear();
    }
  }
}
