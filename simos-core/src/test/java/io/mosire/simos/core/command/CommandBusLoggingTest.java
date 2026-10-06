package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreLog;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * spec §7.3 的**日志守卫**：关键路径必须"另发 SLF4J 日志（'人类可读日志，便于直接 debug，不需查库'）"， 本任务管其中四项——**命令接收 / 拒绝 / 冲突 /
 * 提交**（"推进起止"归 Task 12，"checkpoint 写入与缺失回退"归 Task 7/8）。
 *
 * <p>★★ **这套装置存在的理由，以及它差点写成假的**：测试类路径上有 log4j2 绑定（{@code log4j-slf4j2-impl} + {@code log4j-core}，均
 * test scope），故可以真捕获。但**探针实测**：没有配置文件时 log4j2 的 root level 是 {@code ERROR}，只挂 appender、不抬 {@code
 * LoggerConfig} 的 level，捕获结果是**空的**。 ⇒ 若本类只写"日志里不含明文"这一条断言，它会在**空捕获**上**假绿**（一条都没收到，"没有明文"当然成立）。 故
 * {@link #logLinesAreActuallyCaptured()} 是**前提断言**，先证明装置真的在收，后面每一条才有意义。
 *
 * <p>★ 两件被守的事：
 *
 * <ul>
 *   <li><b>四条日志真的发得出</b>——§7.3 是规范条文，没人守的话删掉两个 {@code LOG.info} 不会红。
 *   <li><b>载荷明文绝不进日志</b>——与 §8.1"不记明文"同一口径。日志会被 grep、被贴进 issue、被采集走，
 *       在这里落明文等于把事件表那边省下的明文从后门漏一遍。本用例在载荷里埋一个**哨兵串**，逐行断言它不出现。
 * </ul>
 */
class CommandBusLoggingTest {

  /** 埋进载荷的哨兵：**这个词一旦出现在任何一条日志里，本用例就红**。 */
  private static final String CANARY = "PLAINTEXT-CANARY-7f3a";

  private static final SimulationState STUB_STATE =
      new SimulationState(
          new StateMeta(
              new StateRef(new BranchId("stub"), new RevisionId(1)), SimosTimestamp.of(7L)),
          Map.of(),
          InMemoryInfoSystem.empty());

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig loggerConfig;
  private Level originalLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    store = SqliteStore.open(tempDir.resolve("logging.db"));
    timeline = new Timeline(store, 4L);
    timeline.appendRevision(
        new RevisionRow(
            new BranchId("main"),
            new RevisionId(1),
            Optional.empty(),
            SimosTimestamp.of(1L),
            "cmd-seed",
            "corr-seed",
            "player:local",
            "core.AdvanceTime",
            Timeline.changeSetJson(WorldChangeSet.empty())));

    context = (LoggerContext) LogManager.getContext(false);
    // ★ 收窄到 AbstractConfiguration：`removeAppender` 只在它上面，不在 Configuration 接口上（javap 实测）
    configuration = (AbstractConfiguration) context.getConfiguration();
    // ★ 2026-10-23 L1：logger 从类 logger 移到门面分类 io.mosire.simos.core.command（CoreLog.command()）。
    loggerConfig = configuration.getLoggerConfig(CoreLog.COMMAND_LOGGER_NAME);
    originalLevel = loggerConfig.getLevel(); // ★ 记下来，拆装置时还原（否则 DEBUG 会漏给同 JVM 的其它用例）
    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    loggerConfig.addAppender(appender, Level.DEBUG, null);
    // ★ 只挂 appender 不抬 level ⇒ 一条都收不到（探针实测，见类注）
    loggerConfig.setLevel(Level.DEBUG);
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    loggerConfig.removeAppender(appender.getName());
    loggerConfig.setLevel(originalLevel);
    configuration.removeAppender(appender.getName());
    context.updateLoggers();
    appender.stop();
    store.close();
  }

  /** ★★ **前提断言**：装置真的在收日志。**没有这一条，本类其余每一条都可能是在空捕获上假绿** （尤其"不含明文"那类否定式断言——空集合恒满足）。 */
  @Test
  void logLinesAreActuallyCaptured() {
    busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)))
        .submit(envelope("cmd-x", "corr-x", 1L));

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  /**
   * §7.3 的四项**逐项**：一次提交、一次被拒、一次冲突 ⇒ 3× 接收 + 各 1 条结局。
   *
   * <p>★ 逐项断言而不是"有日志就算数"：§7.3 点名了四项，少一项就是少一个可诊断的现场。
   */
  @Test
  void allFourLifecycleLinesAreEmitted() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submit(envelope("cmd-a", "corr-a", 1L)); // 提交
    bus.submit(unknownTypeEnvelope("cmd-b", "corr-b", 2L)); // 被拒（类型未注册）
    bus.submit(envelope("cmd-c", "corr-c", 9L)); // 冲突（真 head 是 1，期望 9）

    List<String> lines = appender.messages();
    assertThat(lines).as("实得 %s", lines).hasSize(6);
    assertThat(countStartingWith(lines, "event=COMMAND_RECEIVED")).as("三条命令各一条接收日志").isEqualTo(3);
    assertThat(countStartingWith(lines, "event=COMMAND_COMMITTED")).isEqualTo(1);
    assertThat(countStartingWith(lines, "event=COMMAND_REJECTED")).isEqualTo(1);
    assertThat(countStartingWith(lines, "event=COMMAND_CONFLICTED")).isEqualTo(1);
  }

  /**
   * ★ L1/§4.4：每条事件必须带 {@code origin=} 与 {@code originKind=}（小写三档），且命令入口 = interaction、批执行 = tick。
   *
   * <p>不只看"字段存在"：{@code origin=} 后面若为空（{@code origin= originKind=}）也算没做完，故断言具体来源 id 与 kind 的配对。
   */
  @Test
  void everyLineCarriesOriginAndOriginKindFromTheModuleSourceTable() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submit(envelope("cmd-a", "corr-a", 1L));
    bus.submit(unknownTypeEnvelope("cmd-b", "corr-b", 2L));

    assertThat(appender.messages())
        .as("实得 %s", appender.messages())
        .allSatisfy(
            line -> {
              assertThat(line).contains("origin=");
              assertThat(line).contains("originKind=");
              assertThat(line).doesNotContain("origin= ").doesNotContain("originKind= ");
            });
    assertThat(linesStartingWith("event=COMMAND_RECEIVED"))
        .as("两次提交 ⇒ 两条接收日志，均按工作性质记 interaction")
        .hasSize(2)
        .allSatisfy(
            line ->
                assertThat(line)
                    .contains("origin=command-entry")
                    .contains("originKind=interaction"));
    assertThat(onlyLineStartingWith("event=COMMAND_REJECTED"))
        .as("被拒结局同样来自命令入口层")
        .contains("origin=command-entry")
        .contains("originKind=interaction");
  }

  /**
   * ★ TICK 类事件必带 {@code day}：批执行面（算法推进）用 batch 入口触发，断言批日志带 day 且 kind=tick。
   *
   * <p>判别力：{@code STUB_STATE.meta().timestamp().tick()} = 7L（本类夹具的确定读数）；把它写成常量 0 或漏字段当场红。
   *
   * <p>用未知类型的整批（不落 revision）触发：跑得到 {@code COMMAND_BATCH_SUBMIT}（入口 interaction）与 {@code
   * COMMAND_BATCH_REJECTED}（执行面 tick），不需要为夹具装配 ModuleCodec；同时守"被拒一律 INFO"（用户 2026-10-23 原话）。
   */
  @Test
  void batchTickLinesCarryDayAndTickOriginKind() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submitBatch(List.of(unknownTypeEnvelope("cmd-batch", "corr-batch", 1L)));

    assertThat(onlyLineStartingWith("event=COMMAND_BATCH_SUBMIT"))
        .as("批入口与单条信封同属命令入口层（interaction）")
        .contains("origin=command-entry")
        .contains("originKind=interaction");
    String rejected = onlyLineStartingWith("event=COMMAND_BATCH_REJECTED");
    assertThat(rejected)
        .as("批执行面是 TICK 算法，且必须带 day=7；被拒绝事件必须是 INFO（不是 DEBUG/WARN）")
        .startsWith("INFO|")
        .contains("origin=batch-execution")
        .contains("originKind=tick")
        .contains("day=7");
  }

  /**
   * ★ **密钥纪律 / §8.1"不记明文"**：载荷里的哨兵串**不出现在任何一条日志里**。
   *
   * <p>断言的是**全部**捕获行（不止 CommandBus 自己那几条）：只要哨兵冒出来，无论谁打的，都是明文外泄。
   */
  @Test
  void noLogLineCarriesThePayloadPlaintext() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submit(canaryEnvelope("cmd-canary", "corr-canary", 1L));

    assertThat(appender.messages()).as("前提：先得真收到日志").isNotEmpty();
    assertThat(appender.messages())
        .as("§8.1 / 密钥纪律：载荷明文绝不进日志")
        .allSatisfy(line -> assertThat(line).doesNotContain(CANARY));
  }

  /**
   * ★ **冲突日志报的是"真实 head"，不是"期望值"**——这是那条日志存在的**全部意义**：调用方（或看日志的人） 拿到真 head
   * 才能决定重试还是分岔。若它印的是期望值，读日志的人只会看到"你想去 9、我没答应"，查不出到底在哪。
   *
   * <p>判别力靠**两个值不同**：种子在 {@code main@1}，信封期望 {@code 9}。断言必须见 {@code 真实head=main@1} 且**不见**
   * {@code @9}——若实现改成印期望值，后半句当场红。
   */
  @Test
  void conflictLineReportsTheRealHeadNotTheExpectedOne() {
    busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)))
        .submit(envelope("cmd-c", "corr-c", 9L));

    String conflictLine = onlyLineStartingWith("event=COMMAND_CONFLICTED");
    assertThat(conflictLine)
        .as("真实 head 是种子那行 main@1")
        .contains("headBranch=main")
        .contains("headRevision=1");
    assertThat(conflictLine).as("★ 印期望值就失去诊断价值").doesNotContain("headRevision=9");
  }

  /** 提交日志报**新坐标**；被拒日志报**原因**——各自带上"下一步该看什么"。 */
  @Test
  void commitAndRejectLinesCarryTheirOwnActionableDetail() {
    busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)))
        .submit(envelope("cmd-ok", "corr-ok", 1L));
    assertThat(onlyLineStartingWith("event=COMMAND_COMMITTED"))
        .as("种子在 main@1 ⇒ 提交后是 main@2")
        .contains("branch=main")
        .contains("revision=2");

    CommandBus rejecting = busReturning(new HandlerOutcome.Rejected("领域侧不答应"));
    rejecting.submit(envelope("cmd-no", "corr-no", 2L));
    assertThat(onlyLineStartingWith("event=COMMAND_REJECTED")).contains("reason=领域侧不答应");
  }

  /**
   * 每条日志都带 {@code correlationId}：这是 §7.3"**不需查库**"那句的落点—— 拿着日志里的链路号，一条 SQL 就能把那行事件与 revision 捞出来。
   */
  @Test
  void everyLineCarriesTheCorrelationIdSoYouNeedNotQueryTheDatabase() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submit(envelope("cmd-a", "corr-a", 1L));
    bus.submit(unknownTypeEnvelope("cmd-b", "corr-b", 2L));
    bus.submit(envelope("cmd-c", "corr-c", 9L));

    assertThat(appender.messages())
        .allSatisfy(line -> assertThat(line).contains("correlationId=corr-"));
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────

  /** 把 log4j2 的格式化结果收进一个清单。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> collected = Collections.synchronizedList(new ArrayList<>());

    private CollectingAppender() {
      super("CommandBusLoggingTest-collector", null, null, false, Property.EMPTY_ARRAY);
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
  }

  private List<String> linesStartingWith(String prefix) {
    return appender.messages().stream().filter(line -> line.contains("|" + prefix)).toList();
  }

  private long countStartingWith(List<String> lines, String prefix) {
    return lines.stream().filter(line -> line.contains("|" + prefix)).count();
  }

  private String onlyLineStartingWith(String prefix) {
    List<String> hits = linesStartingWith(prefix);
    assertThat(hits).as("前缀 %s 应恰有一条，实得 %s", prefix, hits).hasSize(1);
    return hits.get(0);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  record ToyChangeSet(int v) implements ChangeSet {}

  private CommandBus busReturning(HandlerOutcome outcome) {
    CommandHandler handler =
        new CommandHandler() {
          @Override
          public String type() {
            return "unit.RenameUnit";
          }

          @Override
          public HandlerOutcome handle(SimulationState state, String payloadJson) {
            return outcome;
          }
        };
    return new CommandBus(
        timeline,
        new CommandRegistry(List.of(handler)),
        cmd -> {
          throw new IllegalStateException("本用例不该走到推进支");
        },
        ref -> STUB_STATE);
  }

  private static CommandEnvelope envelope(String commandId, String correlationId, long expected) {
    return new CommandEnvelope(
        commandId,
        correlationId,
        "player:local",
        new BranchId("main"),
        new RevisionId(expected),
        "unit.RenameUnit",
        "{}");
  }

  private static CommandEnvelope unknownTypeEnvelope(
      String commandId, String correlationId, long expected) {
    return new CommandEnvelope(
        commandId,
        correlationId,
        "player:local",
        new BranchId("main"),
        new RevisionId(expected),
        "unit.NotRegistered",
        "{}");
  }

  private static CommandEnvelope canaryEnvelope(
      String commandId, String correlationId, long expected) {
    return new CommandEnvelope(
        commandId,
        correlationId,
        "player:local",
        new BranchId("main"),
        new RevisionId(expected),
        "unit.RenameUnit",
        "{\"marker\":\"" + CANARY + "\"}");
  }
}
