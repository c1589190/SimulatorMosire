package io.mosire.simos.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.CoreLog;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CheckpointStore} 的行为与护栏用例：信封 JSON 原样往返 + 路径形态（spec §3.5）、缺失回退（C18——空返回 + WARNING，绝不抛）、 构造期
 * storeDir 存在性检查、分支名的文件名安全校验（R17）、{@code hasCheckpoint}（Task 6）与磁盘文件存在性的**逐条一致** （R3，跨件一致性）。
 */
class CheckpointStoreTest {

  /** C19 用例的小周期（同 TimelineTest 的可测性理由）：N=4，不必构造 100 行。 */
  private static final long N = 4;

  @TempDir Path tempDir;

  private CheckpointStore checkpoints;

  @BeforeEach
  void openCheckpoints() {
    checkpoints = new CheckpointStore(tempDir);
  }

  /** 信封 JSON 原样往返 + 路径钉为 {@code <storeDir>/checkpoints/<branch>/<revision>.json}；写侧建齐缺失的父目录。 */
  @Test
  void writeThenReadRoundTripsTheEnvelopeVerbatimAtTheSpecPath() {
    StateRef ref = ref("main", 1);
    String envelopeJson =
        Envelope.encode(
                new StateMeta(ref, SimosTimestamp.of(0L)),
                Map.of("map", "{\"regions\":[]}"),
                "{\"kind\":\"info\"}")
            .toString();

    checkpoints.write(ref, envelopeJson);

    Path expected = tempDir.resolve("checkpoints").resolve("main").resolve("1.json");
    assertThat(Files.exists(expected)).as("按 spec §3.5 的路径落盘").isTrue();
    // ★ 逐字节往返（本类承诺的是搬运保真，不是信封内容解释）
    assertThat(checkpoints.read(ref)).contains(envelopeJson);
    assertThat(Envelope.decode(checkpoints.read(ref).orElseThrow()).meta().ref()).isEqualTo(ref);
  }

  /** C18：缺失 ⇒ {@code Optional.empty()}，**绝不抛**；且记下 WARNING（缺文件是可回退的常态，不是错误）。 */
  @Test
  void readMissingReturnsEmptyAndLogsWarning() {
    // log4j2 2.26 实测：Logger facade 的 setLevel 不落在 live LoggerConfig 上（level 仍 ERROR ⇒ 事件在
    // appender 之前就被滤掉）。正确做法是给本类的名字挂一个专属 LoggerConfig，走 Configuration 的正式生命周期。
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    AbstractConfiguration configuration = (AbstractConfiguration) context.getConfiguration();
    // ★ 2026-10-23 L1：logger 从类 logger 移到门面分类 io.mosire.simos.core.store（CoreLog.store()）。
    String loggerName = CoreLog.STORE_LOGGER_NAME;
    CapturingAppender appender = new CapturingAppender();
    appender.start();
    LoggerConfig config = new LoggerConfig(loggerName, Level.WARN, true);
    config.addAppender(appender, Level.WARN, null);
    configuration.addLogger(loggerName, config);
    context.updateLoggers();
    try {
      assertThat(checkpoints.read(ref("main", 1))).isEmpty();

      assertThat(appender.events)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMessage().getFormattedMessage())
                    .as("事件名 + 具名字段 + 来源（§4.4）")
                    .contains("event=CHECKPOINT_MISSING")
                    .contains("origin=checkpoint")
                    .contains("originKind=system")
                    .contains("branch=main")
                    .contains("revision=1");
              });
    } finally {
      configuration.removeLogger(loggerName);
      appender.stop();
      context.updateLoggers();
    }
  }

  /** 构造期对 storeDir 做存在性检查：不存在 ⇒ 抛；存在但不是目录 ⇒ 抛；正常根 ⇒ 构造成功。 */
  @Test
  void constructorRequiresAnExistingDirectory() throws Exception {
    assertThatThrownBy(() -> new CheckpointStore(tempDir.resolve("missing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("storeDir");

    Path file = tempDir.resolve("a-file");
    Files.writeString(file, "x");
    assertThatThrownBy(() -> new CheckpointStore(file))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(new CheckpointStore(tempDir)).isNotNull();
  }

  /**
   * R17：含 {@code /}、{@code \}、{@code ..} 的分支名 ⇒ write/read 都当场抛；拒绝后**没有任何文件写穿到 checkpoints/ 之外**。
   */
  @Test
  void rejectsBranchNamesThatAreNotFilenameSafe() {
    List<String> unsafe = List.of("../evil", "a/b", "a\\b", "..", "main/../x");

    for (String name : unsafe) {
      StateRef evil = new StateRef(new BranchId(name), new RevisionId(1));
      assertThatThrownBy(() -> checkpoints.write(evil, "{}"))
          .as("write 应拒绝不安全的分支名: " + name)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("R17");
      assertThatThrownBy(() -> checkpoints.read(evil))
          .as("read 应拒绝不安全的分支名: " + name)
          .isInstanceOf(IllegalArgumentException.class);
    }

    // "../evil" 若不校验会解析到 <storeDir>/evil——拒绝之后那里必须什么都没有
    assertThat(Files.exists(tempDir.resolve("evil"))).as("不得写穿 checkpoints/ 目录").isFalse();
    assertThat(Files.exists(tempDir.resolve("checkpoints"))).isFalse();
  }

  /**
   * R3（跨件一致性）：按 C19 写完"应当有"的 checkpoint 之后，一批 {@code (b, r)} 上 {@code hasCheckpoint} 的判定与
   * 磁盘文件存在性（以及 read 可得性）**逐条一致**——同时钉住 Task 6 的纯函数与本类的写路径。
   */
  @Test
  void diskFilesMatchHasCheckpointRowByRowAcrossABatch() {
    try (SqliteStore store = SqliteStore.open(tempDir.resolve("timeline.db"))) {
      Timeline timeline = new Timeline(store, N);
      append(timeline, ref("main", 1), Optional.empty()); // ③ 创世
      append(timeline, ref("main", 2), Optional.of(ref("main", 1)));
      append(timeline, ref("main", 3), Optional.of(ref("main", 2)));
      forkFrom(timeline, ref("main", 3), "b2"); // ② (main,3) 成为 b2 revision 1 的 parent
      append(timeline, ref("main", 4), Optional.of(ref("main", 3))); // ① 4 % 4 == 0
      forkFrom(timeline, ref("b2", 1), "b3"); // ② (b2,1) 成为 b3 revision 1 的 parent
      append(timeline, ref("b2", 2), Optional.of(ref("b2", 1)));

      List<StateRef> batch =
          List.of(
              ref("main", 1),
              ref("main", 2),
              ref("main", 3),
              ref("main", 4),
              ref("b2", 1),
              ref("b2", 2),
              ref("b3", 1));

      // 防夹具退化成恒真：这批的判定必须非平凡（四真三假，真值集当场钉住）
      assertThat(batch).filteredOn(timeline::hasCheckpoint).hasSize(4);

      // 按 C19 写完"应当有"的（Task 13 的 C24 调用形态：事务已提交后的独立调用）
      for (StateRef candidate : batch) {
        if (timeline.hasCheckpoint(candidate)) {
          checkpoints.write(candidate, envelopeJsonFor(candidate));
        }
      }

      for (StateRef candidate : batch) {
        boolean has = timeline.hasCheckpoint(candidate);
        Path file =
            tempDir
                .resolve("checkpoints")
                .resolve(candidate.branch().value())
                .resolve(candidate.revision().value() + ".json");
        assertThat(Files.exists(file))
            .as(
                "文件存在性 == hasCheckpoint: "
                    + candidate.branch().value()
                    + "@"
                    + candidate.revision().value())
            .isEqualTo(has);
        assertThat(checkpoints.read(candidate).isPresent())
            .as(
                "read 可得性 == hasCheckpoint: "
                    + candidate.branch().value()
                    + "@"
                    + candidate.revision().value())
            .isEqualTo(has);
      }

      // 真值集逐个钉住（③②①② 各自命中，反例三项全不中）
      assertThat(batch)
          .filteredOn(timeline::hasCheckpoint)
          .containsExactlyInAnyOrder(ref("main", 1), ref("main", 3), ref("main", 4), ref("b2", 1));
    }
  }

  // ---- 夹具 ----

  private static BranchId branch(String name) {
    return new BranchId(name);
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(branch(branch), new RevisionId(revision));
  }

  /** 一份可辨认的假信封（R3 只看存在性，不解释内容）：坐标写进 JSON，肉眼可对账。 */
  private static String envelopeJsonFor(StateRef ref) {
    return "{\"branch\":\""
        + ref.branch().value()
        + "\",\"revision\":"
        + ref.revision().value()
        + "}";
  }

  /** 按给定坐标落一行（同 TimelineTest 的形态）；时间戳 tick 取 revision 坐标本值。 */
  private static void append(Timeline timeline, StateRef target, Optional<StateRef> parent) {
    timeline.appendRevision(
        new RevisionRow(
            target.branch(),
            target.revision(),
            parent,
            SimosTimestamp.of(target.revision().value()),
            "cmd-r" + target.revision().value(),
            "cf-r" + target.revision().value(),
            "player:local",
            "core.AdvanceTime",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  /** 从 {@code forkPoint} 分出 {@code newBranch}，断言成功（分岔失败在此当场炸，不静默滑过）。 */
  private static void forkFrom(Timeline timeline, StateRef forkPoint, String newBranch) {
    assertThat(
            timeline.fork(
                forkPoint.branch(),
                forkPoint.revision(),
                branch(newBranch),
                "cmd-fork-" + newBranch,
                "cf-fork-" + newBranch,
                "player:local"))
        .isPresent();
  }

  /** 抓本类 logger 事件的小 appender（log4j-core 在 test scope）；同步回调，断言即时可读。 */
  private static final class CapturingAppender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();

    CapturingAppender() {
      super("checkpoint-capturing", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }
}
