package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CoreSimos} 的装配护栏（计划 Task 13；spec §1.2 / C19 / C24 / C28）。
 *
 * <p>★★ **本用例偿还两条挂了很久的欠账**（台账"给下游的硬接缝"）：
 *
 * <ol>
 *   <li>「{@code Replay} 从未在**真由 {@code CommandBus} 写出来的** revision 上跑过」（Task 8 §5 / Task 9 §5）——
 *       {@code envelopeBranchCommitsAndReplaysThroughTheRealWiring} 走的是**真 store + 真 CommandBus + 真
 *       Replay**，重放的是总线亲手落下的那一行。
 *   <li>「{@code StateLoader} 的真实装配 `replay::replay` 从未在真状态上跑过」（Task 9 §5）——本类把 {@code this::load}
 *       真正接进 {@link io.mosire.simos.core.command.CommandBus} 与 {@link
 *       io.mosire.simos.core.advance.TimeAdvance}，上面两条用例都会经过它。
 * </ol>
 *
 * <p>★ **夹具与 {@code ReplayTest} 同法**：先**独立**打开 {@code SqliteStore} 种下创世行 {@code (main,1)} + 写创世
 * checkpoint 文件，关掉它，**然后**才构造 {@link CoreSimos}（本类**不提供"创世 API"**——计划没要求它，也没有消费者）。
 *
 * <p>★ 玩具 codec / handler / participant 都是替身：Core 的装配**不认识领域类型**（ADR-1），用真模块只会让用例依赖三个模块 的编译期。真
 * codec 的装配归 Task 16。
 */
class CoreSimosTest {

  /** 玩具变更集（test 侧，R1 的"恰 4 个实现者"只数 main 源码）。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具快照：把 {@code ref}/{@code timestamp} 带在身上，好让"坐标有没有被填对"可观测。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int v)
      implements Snapshot {}

  /** 把玩具快照落成 {@code namespace|v|branch|revision|tick} 的 codec——够写 checkpoint、也够重放读回。 */
  private static final class ToyCodec implements ModuleCodec {

    private final String namespace;

    ToyCodec(String namespace) {
      this.namespace = namespace;
    }

    @Override
    public String namespace() {
      return namespace;
    }

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
      String[] parts = json.split("\\|");
      return new ToySnapshot(
          new StateRef(new BranchId(parts[2]), new RevisionId(Long.parseLong(parts[3]))),
          SimosTimestamp.of(Long.parseLong(parts[4])),
          parts[0],
          Integer.parseInt(parts[1]));
    }

    @Override
    public String encodeSnapshot(Snapshot snapshot) {
      ToySnapshot toy = (ToySnapshot) snapshot;
      return String.join(
          "|",
          toy.namespace(),
          Integer.toString(toy.v()),
          toy.ref().branch().value(),
          Long.toString(toy.ref().revision().value()),
          Long.toString(toy.timestamp().tick()));
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

  /** 固定 namespace + 固定增量的 handler（信封支用）。 */
  private static final class ToyHandler implements CommandHandler {

    private final String type;
    private final int delta;

    ToyHandler(String type, int delta) {
      this.type = type;
      this.delta = delta;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public HandlerOutcome handle(SimulationState state, String payloadJson) {
      return new HandlerOutcome.Applied(new ToyChangeSet(delta));
    }
  }

  /** 每次推进 +1 的参与者（推进支用）；读写集为空 ⇒ 与谁都不冲突。 */
  private static final class ToyParticipant implements TimeParticipant {

    private final String namespace;

    ToyParticipant(String namespace) {
      this.namespace = namespace;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public TimeProposal simulate(SimulationState state, TimeRange range) {
      return new TimeProposal(namespace, new ToyChangeSet(1), Set.of(), Set.of());
    }
  }

  private static final ToyCodec TOY_CODEC = new ToyCodec("toy");

  /** {@link CoreConfig#mapper()} 当前**无消费者**（见其 Javadoc）——这里仍给一台真的，免得把"没有消费者"误读成"可以传 null"。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  // ── 1. 信封支端到端 + 真 Replay 装配 ──────────────────────────────────────────────────

  /**
   * ★★ **信封支端到端**：注册替身 handler/codec → {@code submit(CommandEnvelope)} → {@code Committed} → 用
   * {@code core.replay(新坐标)} 重放，且**逐字段等于独立构造的期望值**。
   *
   * <p>★★★★ 同时断言**没写多余的 checkpoint**：(main,2) 不是 4 的倍数、不是分岔点、不是创世 ⇒ C19 谓词为假 ⇒ 磁盘上不许 有它的文件（m4
   * 打的就是这条）。
   */
  @Test
  void envelopeBranchCommitsAndReplaysThroughTheRealWiring() {
    seedGenesis(5L, 0);
    try (CoreSimos core = core(4L)) {
      core.register(TOY_CODEC);
      core.register(new ToyHandler("toy.Do", 3));

      CommandResult result = core.submit(envelope("cmd-e1", main(), 1L, "toy.Do"));

      assertThat(result)
          .as("信封支应提交到 (main,2)")
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      SimulationState replayed = core.replay(ref("main", 2));
      SimulationState expected =
          new SimulationState(
              new StateMeta(ref("main", 2), SimosTimestamp.of(5L)),
              Map.of("toy", new ToySnapshot(ref("main", 2), SimosTimestamp.of(5L), "toy", 3)),
              InMemoryInfoSystem.empty());
      assertThat(replayed)
          .as("真 CommandBus 写下的 revision 必须能被真 Replay 重建（信封支端到端）")
          .isEqualTo(expected);

      // ★ 判别力对照：相邻坐标必须真的不同，否则上面的 isEqualTo 可能恒真
      assertThat(core.replay(ref("main", 1))).isNotEqualTo(replayed);

      // ★ C19 谓词为假 ⇒ 绝不写文件（多写会让 R3 的"判定与磁盘逐条一致"在反方向破）
      assertThat(checkpointFile(ref("main", 2)))
          .as("(main,2) 不命中 C19，不该有 checkpoint 文件")
          .doesNotExist();
    }
  }

  /**
   * ★★ **信封支的正向：命中 C19 第①项时**必须写** checkpoint，且它写出的档能被 Replay 读回**（spec §3.4；C24）。
   *
   * <p>N = 2 ⇒ 信封提交到 {@code (main,2)} 时 {@code 2 % N == 0} 命中。删掉创世档后仍能重放 ⇒ 读的正是信封支写出的 {@code
   * (main,2)} 档。这条补上了"只验了不写、没验会写"的缺口（m5 打的就是它）。
   */
  @Test
  void envelopeBranchWritesACheckpointWhenTheIntervalHits() {
    seedGenesis(0L, 0);
    try (CoreSimos core = core(2L)) {
      core.register(TOY_CODEC);
      core.register(new ToyHandler("toy.Do", 1));

      assertThat(core.submit(envelope("cmd-e1", main(), 1L, "toy.Do")))
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));
      assertThat(checkpointFile(ref("main", 2)))
          .as("(main,2) 命中 C19 第①项（2 % N == 0）⇒ 信封支必须写它")
          .isRegularFile();

      SimulationState expected =
          new SimulationState(
              new StateMeta(ref("main", 2), SimosTimestamp.of(0L)),
              Map.of("toy", new ToySnapshot(ref("main", 2), SimosTimestamp.of(0L), "toy", 1)),
              InMemoryInfoSystem.empty());
      assertThat(core.replay(ref("main", 2))).isEqualTo(expected);

      // ★ 删掉创世档：仍能重放 ⇒ 读的一定是信封支写出的 (main,2) 档
      deleteCheckpoint(ref("main", 1));
      assertThat(core.replay(ref("main", 2)))
          .as("创世档已删，仍能重放 ⇒ 信封支写出的 (main,2) checkpoint 真的被读了")
          .isEqualTo(expected);
    }
  }

  // ── 2. 推进支：checkpoint 写出后被 Replay 读回（端到端）─────────────────────────────────

  /**
   * ★★ **推进支的 checkpoint 往返**（兑现 task-12-report §5 第 2 条）：N=4，推进到 {@code (main,4)}（命中 C19 第①项） ⇒
   * 文件存在；{@code core.replay((main,4))} 逐字段等于期望。
   *
   * <p>★★ **证明"读回"而不是"碰巧对"**：删掉**创世** checkpoint 之后再重放——若实现没读 (main,4) 那份档，就只能一路退回创世， 而创世已被删 ⇒
   * 必失败。它照样通过，说明 (main,4) 的档**真的被读了**。
   *
   * <p>★ 反向对照：连 (main,4) 也删掉 ⇒ 无档可退 ⇒ 必须抛，**不许**静默返回空世界。
   */
  @Test
  void advanceTimeWritesACheckpointThatReplayReadsBack() {
    seedGenesis(0L, 0);
    try (CoreSimos core = core(4L)) {
      core.register(TOY_CODEC);
      core.register(new ToyParticipant("toy"));

      assertThat(core.submit(advance("cmd-a1", 1L, 0L, 5L)))
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));
      assertThat(core.submit(advance("cmd-a2", 2L, 5L, 6L)))
          .isEqualTo(new CommandResult.Committed(ref("main", 3)));
      assertThat(core.submit(advance("cmd-a3", 3L, 6L, 7L)))
          .isEqualTo(new CommandResult.Committed(ref("main", 4)));

      assertThat(checkpointFile(ref("main", 4)))
          .as("(main,4) 命中 C19 第①项（4 % N == 0），TimeAdvance 的 ⑥ 必须写出它")
          .isRegularFile();

      SimulationState expected =
          new SimulationState(
              new StateMeta(ref("main", 4), SimosTimestamp.of(7L)),
              Map.of("toy", new ToySnapshot(ref("main", 4), SimosTimestamp.of(7L), "toy", 3)),
              InMemoryInfoSystem.empty());
      assertThat(core.replay(ref("main", 4)))
          .as("推进终点必须能从它自己的 checkpoint 重放出来")
          .isEqualTo(expected);

      // ★ 删掉创世档：还能重放 ⇒ 读的一定是 (main,4) 那份档（否则只剩创世一条路，而它已被删）
      deleteCheckpoint(ref("main", 1));
      assertThat(core.replay(ref("main", 4)))
          .as("创世档已删，仍能重放 ⇒ (main,4) 的 checkpoint 真的被读了")
          .isEqualTo(expected);

      // ★ 反向对照：连 (main,4) 也删掉 ⇒ 无档可退，必须抛
      deleteCheckpoint(ref("main", 4));
      assertThatThrownBy(() -> core.replay(ref("main", 4)))
          .as("C18 的回退到头：无任何可用 checkpoint ⇒ 不许静默返回空世界")
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ── 3. R8 前半：分岔的机制面 ─────────────────────────────────────────────────────────

  /**
   * ★★ **R8 前半（分岔的机制面）**：从 {@code (main,2)} 分岔出 {@code b2} ⇒
   *
   * <ol>
   *   <li>{@code (b2,1)} 存在、parent 指回 {@code (main,2)}、变更集为空（C13）、tick 继承父；
   *   <li>★ **分岔点 {@code (main,2)} 的 checkpoint 文件存在**（C19 第②项）——**m1 的咬点**；
   *   <li>两侧各推各的、{@code expectedRevision} 天然把两者隔开：用过期的期望值再发 ⇒ 各自的 {@code Conflict}， 报各自的真 head。
   * </ol>
   *
   * <p>★ 行级断言在 {@code core.close()} **之后**用一棵独立的 store 读——避免与门面持有的连接抢 WAL。
   */
  @Test
  void forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint() {
    seedGenesis(5L, 0);
    CommandResult forkResult;
    CommandResult mainAdvance;
    CommandResult b2Advance;
    CommandResult b2Conflict;
    CommandResult mainConflict;
    SimulationState b2Before;
    try (CoreSimos core = core(4L)) {
      core.register(TOY_CODEC);
      core.register(new ToyHandler("toy.Do", 1));

      assertThat(core.submit(envelope("cmd-e1", main(), 1L, "toy.Do")))
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      forkResult =
          core.submit(
              new ForkBranch(
                  "cmd-fork",
                  "corr-fork",
                  "player:local",
                  main(),
                  new RevisionId(2),
                  branch("b2")));

      b2Before = core.replay(ref("b2", 1));

      // 两侧各推一格
      mainAdvance = core.submit(envelope("cmd-e2", main(), 2L, "toy.Do"));
      b2Advance = core.submit(envelope("cmd-e3", branch("b2"), 1L, "toy.Do"));

      // 两侧各用过期的期望值再发一次
      b2Conflict = core.submit(envelope("cmd-e4", branch("b2"), 1L, "toy.Do"));
      mainConflict = core.submit(envelope("cmd-e5", main(), 2L, "toy.Do"));

      // main 推进不改变 b2 的既有坐标（revision 行 append-only）
      assertThat(core.replay(ref("b2", 1))).isEqualTo(b2Before);
    }

    assertThat(forkResult)
        .as("分岔产生的是新分支的 revision 1（C13）")
        .isEqualTo(new CommandResult.Committed(ref("b2", 1)));
    assertThat(mainAdvance).isEqualTo(new CommandResult.Committed(ref("main", 3)));
    assertThat(b2Advance).isEqualTo(new CommandResult.Committed(ref("b2", 2)));
    assertThat(b2Conflict)
        .as("b2 的 expectedRevision 过期 ⇒ 报 b2 的真 head")
        .isEqualTo(new CommandResult.Conflict(ref("b2", 2)));
    assertThat(mainConflict)
        .as("main 的 expectedRevision 过期 ⇒ 报 main 的真 head（两条线各算各的）")
        .isEqualTo(new CommandResult.Conflict(ref("main", 3)));

    // ★ m1 的咬点：分岔点必须有 checkpoint（C19 第②项，spec §3.4 ④）
    assertThat(checkpointFile(ref("main", 2)))
        .as("分岔点 (main,2) 必须有一份 checkpoint（C19 第②项：重放上界 ≤ N 跨分支的保证）")
        .isRegularFile();

    RevisionRow forkRow = row(ref("b2", 1));
    assertThat(forkRow.parent()).as("b2 的父链指回源 head").contains(ref("main", 2));
    assertThat(forkRow.changesetJson())
        .as("分岔不改变世界，只增加一条边（C13）")
        .isEqualTo(Timeline.changeSetJson(WorldChangeSet.empty()));
    assertThat(forkRow.timestamp()).as("模拟时刻继承父（C13）").isEqualTo(SimosTimestamp.of(5L));
    assertThat(forkRow.revision()).isEqualTo(new RevisionId(1));
  }

  // ── 4. 封存护栏 ─────────────────────────────────────────────────────────────────────

  /**
   * ★ **封存规则**：第一次 {@code submit}/{@code replay} 之后，三类 {@code register(...)} 一律抛 {@link
   * IllegalStateException}——**绝不静默忽略**（静默会让一次装配错误表现为"这个 handler 永远不生效"，且不报错）。
   */
  @Test
  void registeringAfterTheFirstUseThrows() {
    seedGenesis(0L, 0);
    try (CoreSimos core = core(4L)) {
      core.register(TOY_CODEC);
      core.register(new ToyHandler("toy.Do", 1));
      core.register(new ToyParticipant("toy"));

      // 首次使用 = 封存
      assertThat(core.replay(ref("main", 1))).isNotNull();

      assertThatThrownBy(() -> core.register(new ToyCodec("other")))
          .as("封存后注册 codec：必须抛，不得静默忽略")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("封存");
      assertThatThrownBy(() -> core.register(new ToyHandler("other.Do", 1)))
          .as("封存后注册 handler：必须抛")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("封存");
      assertThatThrownBy(() -> core.register(new ToyParticipant("other")))
          .as("封存后注册 participant：必须抛")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("封存");
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 同一个 {@code storeDir} 上的门面；{@code interval} 即 C19 的 N。 */
  private CoreSimos core(long interval) {
    return new CoreSimos(new CoreConfig(tempDir, (int) interval, MAPPER));
  }

  /**
   * 种创世：独立打开含同名库文件的 store，落一行 {@code (main,1)}（parent 空、变更集空），再写创世 checkpoint 文件，然后关掉它。
   *
   * <p>★ **不把"创世 API"加进 {@link CoreSimos}**——计划没要求，也没有消费者；夹具自己承担这一份。
   */
  private void seedGenesis(long tick, int value) {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, 4L)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  SimosTimestamp.of(tick),
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), SimosTimestamp.of(tick)),
            Map.of("toy", new ToySnapshot(ref("main", 1), SimosTimestamp.of(tick), "toy", value)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(ref("main", 1), CheckpointEncoder.encode(genesis, List.of(TOY_CODEC)));
  }

  /** 读一行的机制面：**关库后**用一棵独立 store 读（不与门面持有的连接抢 WAL）。 */
  private RevisionRow row(StateRef at) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return new Timeline(store, 4L)
          .row(at)
          .orElseThrow(() -> new AssertionError("期望存在的 revision 行: " + at));
    }
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private Path checkpointFile(StateRef at) {
    return tempDir
        .resolve("checkpoints")
        .resolve(at.branch().value())
        .resolve(at.revision().value() + ".json");
  }

  /** 删档并**先自证它本来就在**——否则"删除"是空操作，后面的断言会假绿（形态 1）。 */
  private void deleteCheckpoint(StateRef at) {
    Path file = checkpointFile(at);
    assertThat(Files.isRegularFile(file)).as("夹具前提：%s 的 checkpoint 必须本来就在，否则「删除」是空操作", at).isTrue();
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 checkpoint 失败: " + file, e);
    }
  }

  private static CommandEnvelope envelope(
      String commandId, BranchId branch, long expectedRevision, String type) {
    return new CommandEnvelope(
        commandId,
        "corr-" + commandId,
        "player:local",
        branch,
        new RevisionId(expectedRevision),
        type,
        "{}");
  }

  private static AdvanceTime advance(
      String commandId, long expectedRevision, long fromTick, long toTick) {
    return new AdvanceTime(
        commandId,
        "corr-" + commandId,
        "player:local",
        main(),
        new RevisionId(expectedRevision),
        new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick))));
  }

  private static BranchId main() {
    return branch("main");
  }

  private static BranchId branch(String name) {
    return new BranchId(name);
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(branch(branch), new RevisionId(revision));
  }
}
