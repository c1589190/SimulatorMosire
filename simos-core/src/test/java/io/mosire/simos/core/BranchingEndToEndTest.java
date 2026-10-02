package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 判据一（R8）的**端到端**验收（计划 Task 14）：一个真由 {@link CoreSimos} 装配的世界里，从 {@code (main, r)} 分岔出的 {@code b2} 与
 * {@code main} 各自推进，且**逐值**证明互不影响。
 *
 * <p>★★ **与 {@code CoreSimosTest} 的分工**（不重复它）：那个类验的是 R8 的**机制面**——分岔行字段、分岔点 checkpoint、 {@code
 * expectedRevision} 天然隔开两侧；用的全是替身 codec / handler。本类**向上扩一层**：
 *
 * <ul>
 *   <li>领域侧用**真的** {@link UnitCodec} + {@link RenameUnitHandler}（Task 16 Step 1/2 已落地，无替身、无取代说明）；
 *   <li>两侧都**真的各推一格**（真 {@code unit.RenameUnit} 信封），再各自重放，断言**逐值**；
 *   <li>判据一③ 的"一字不变"落成**值断言**（head revision + 重放状态 {@code equals} + 单位各字段逐个），**不是"看它没报错"**。
 * </ul>
 *
 * <p>★ **判据一③ 的 head 断言写在 try 内、且在重放 {@code (b2,2)} 之前**：这不是风格，是**为了隔离 m1 的咬点**。m1（{@code
 * Timeline.head} 忽略分支）会让 {@code b2} 的那条改名**当场冲突**（它的 expected 是 1，而"全局 MAX"已是 main 的号）， 于是 {@code
 * (b2,2)} 根本不存在——若先重放它，变异轮会以一次**异常**收场，而我们要的是**断言**变红（形态 1：红点必须落在被保护的那一行）。 故先钉 head，让 m1 红在"③ b2 的
 * head"这一条上。
 *
 * <p>★ 夹具与 {@code ReplayTest}/{@code CoreSimosTest} 同法：先**独立**打开 store 种创世行 {@code (main,1)} + 写创世
 * checkpoint，关掉它，**然后**才构造 {@link CoreSimos}。行级读取（判据一②）在 {@code core.close()} 之后用一棵独立 store。
 */
class BranchingEndToEndTest {

  /** 创世模拟时刻；改名不移动时钟（裁定 35），故全程 tick 恒为 T0。 */
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final UnitId U1 = new UnitId("u-1");

  /** 单位位置：只是"另一个单位字段"，用来证明重放整棵树而非只搬名字。 */
  private static final HexCoord H11 = new HexCoord(1, 1);

  /** {@link RenameUnitHandler} 冻结的命令类型（spec §9.2）。 */
  private static final String RENAME_TYPE = "unit.RenameUnit";

  /** 与 {@code CoreSimosTest} 同一口径：{@code CoreConfig.mapper} 无消费者，但也不许传 null。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  /**
   * 判据一全链：{@code main} 先改一次名 → 从 {@code (main,2)} 分岔 {@code b2} → 两侧各推一次真改名 → 回到 {@code main} 再推一次。
   *
   * <p>断言三件事：① 两侧各自推进互不影响（逐值）；② {@code b2} 的父链指回 {@code (main,2)}（行级、独立 store 读）；③ {@code main}
   * 再推进后 {@code b2} 的 head 与重放状态**一字不变**（逐值）。另加一条端到端口径：跨分支的过期 {@code expectedRevision} ⇒ {@code
   * Conflict} 报该分支**自己的**真 head。
   */
  @Test
  void forkEndToEndWithRealRenameCommands() {
    seedGenesis();

    CommandResult mainFirst;
    CommandResult fork;
    CommandResult mainAfterFork;
    CommandResult b2AfterFork;
    RevisionId b2HeadBefore;
    SimulationState b2StateBefore;
    CommandResult b2StaleBefore;
    CommandResult mainLast;
    RevisionId b2HeadAfter;
    SimulationState b2StateAfter;
    SimulationState mainStateAfter;
    CommandResult b2StaleAfter;

    try (CoreSimos core = new CoreSimos(new CoreConfig(tempDir, 4, MAPPER))) {
      // ★ 真 codec + 真 handler（Task 16 Step 1/2 已落地）——本任务没有替身、没有取代说明。
      core.register(new UnitCodec());
      core.register(new RenameUnitHandler());

      // 1. main 上至少一次真改名（(main,1) → (main,2)）
      mainFirst = core.submit(rename("cmd-1", main(), 1, "main 一改"));

      // 2. 从 (main,2) 分岔出 b2（ForkBranch 不变世界，只加一条边；C13）
      fork =
          core.submit(
              new ForkBranch(
                  "cmd-fork",
                  "corr-fork",
                  "player:local",
                  main(),
                  new RevisionId(2),
                  branch("b2")));

      // 3. 两侧各推一次真改名（不同新名）
      mainAfterFork = core.submit(rename("cmd-2", main(), 2, "main 二改"));
      b2AfterFork = core.submit(rename("cmd-3", branch("b2"), 1, "b2 一改"));

      // 4. 捕获 b2 的 head 与状态（main 最后一次推进之前）
      b2HeadBefore = isolatedHead("b2");
      // ★ 条件捕获：m1 下 b2 的改名冲突、(b2,2) 不存在——这里不制造异常，让 head 断言去红（见类注）
      b2StateBefore =
          b2AfterFork instanceof CommandResult.Committed committed
              ? core.replay(committed.ref())
              : null;

      // 端到端口径：跨分支的过期 expectedRevision ⇒ Conflict 报 b2 自己的真 head
      b2StaleBefore = core.submit(rename("cmd-stale-1", branch("b2"), 1, "绝不该生效"));

      // 5. 回到 main 再推一次（(main,3) → (main,4)）
      mainLast = core.submit(rename("cmd-4", main(), 3, "main 三改"));
      b2HeadAfter = isolatedHead("b2");

      // ★★ 判据一③ 的 head 断言（m1 的咬点）：main 再推一次后，b2 的 head 必须还是 2（一字不变）
      assertThat(b2HeadBefore).as("③ b2 在分岔后自己推进一格，head 必须是 2").isEqualTo(new RevisionId(2));
      assertThat(b2HeadAfter).as("③ main 再推进一次，b2 的 head 必须还是同一个数（一字不变）").isEqualTo(b2HeadBefore);

      // 6. 取 b2 与 main 的最终重放状态（head 断言已过，才敢重放 (b2,2)）
      b2StateAfter = core.replay(ref("b2", 2));
      mainStateAfter = core.replay(ref("main", 4));
      b2StaleAfter = core.submit(rename("cmd-stale-2", branch("b2"), 1, "绝不该生效"));
    }

    // ── ① 两侧各自推进互不影响（逐值，不是"没报错"）──────────────────────────────────────
    assertThat(mainFirst).isEqualTo(new CommandResult.Committed(ref("main", 2)));
    assertThat(fork)
        .as("分岔产生新分支的 revision 1（C13）")
        .isEqualTo(new CommandResult.Committed(ref("b2", 1)));
    assertThat(mainAfterFork).isEqualTo(new CommandResult.Committed(ref("main", 3)));
    assertThat(b2AfterFork).isEqualTo(new CommandResult.Committed(ref("b2", 2)));
    assertThat(mainLast).isEqualTo(new CommandResult.Committed(ref("main", 4)));

    UnitSnapshot mainUnit = unitOf(mainStateAfter);
    UnitSnapshot b2Unit = unitOf(b2StateAfter);
    assertThat(mainUnit.state().units().get(U1).name())
        .as("① main 的重放状态只看见 main 自己的改名")
        .isEqualTo("main 三改");
    assertThat(b2Unit.state().units().get(U1).name())
        .as("① b2 的重放状态只看见 b2 自己的改名")
        .isEqualTo("b2 一改");
    assertThat(mainUnit.state().units().get(U1).name())
        .as("① 两侧的名字必须真的不同（否则 isEqualTo 可能恒真）")
        .isNotEqualTo(b2Unit.state().units().get(U1).name());
    assertThat(mainStateAfter.meta())
        .as("① main 的外层坐标取 revisions 表")
        .isEqualTo(new StateMeta(ref("main", 4), T0));

    // ── ③ main 再推进后，b2 的 head 与状态一字不变（逐值）────────────────────────────────
    assertThat(b2StateAfter).as("③ b2 重放出的完整状态与 main 推进前一字不差（equals 快照）").isEqualTo(b2StateBefore);
    assertThat(b2StateAfter.meta()).as("③ b2 的外层坐标不变").isEqualTo(new StateMeta(ref("b2", 2), T0));
    assertThat(b2Unit.ref()).as("③ b2 切片的 ref 是 b2 自己的坐标").isEqualTo(ref("b2", 2));
    assertThat(b2Unit.timestamp()).as("③ 改名不移动时钟（裁定 35）").isEqualTo(T0);

    Unit b2Renamed = b2Unit.state().units().get(U1);
    assertThat(b2Renamed.id()).isEqualTo(U1);
    assertThat(b2Renamed.name()).isEqualTo("b2 一改");
    assertThat(b2Renamed.manpower())
        .as("③ 其余单位字段也被逐值钉住")
        .containsExactly(new CompositionEntry("步兵", 100));
    assertThat(b2Renamed.equipment())
        .containsExactly(new CompositionEntry("步枪", 50));
    assertThat(b2Renamed.speed()).isEqualTo(2);
    assertThat(b2Renamed.mobilityPerMille()).isEqualTo(500);
    assertThat(b2Renamed.movement()).isEmpty();
    assertThat(b2Renamed.position().valueAt(T0)).as("③ 位置序列不因 main 推进而变").contains(H11);

    // 端到端：过期 expectedRevision 报的是**该分支自己的**真 head（两条线各算各的）
    assertThat(b2StaleBefore).isEqualTo(new CommandResult.Conflict(ref("b2", 2)));
    assertThat(b2StaleAfter).isEqualTo(new CommandResult.Conflict(ref("b2", 2)));

    // ── ② b2 的父链指回 (main,2)（行级，独立 store + Timeline 读）────────────────────────
    RevisionRow b2First = row(ref("b2", 1));
    assertThat(b2First.parent()).as("② (b2,1) 的 parent 指回源 head").contains(ref("main", 2));
    assertThat(b2First.revision()).isEqualTo(new RevisionId(1));
    assertThat(b2First.commandType()).isEqualTo(Timeline.FORK_COMMAND_TYPE);
    assertThat(b2First.timestamp()).as("② 分岔行继承父 tick（C13）").isEqualTo(T0);
    assertThat(b2First.changesetJson())
        .as("② 分岔不改变世界，只增加一条边（C13）")
        .isEqualTo(Timeline.changeSetJson(WorldChangeSet.empty()));

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      List<StateRef> chain = new Timeline(store, 4L).chainToGenesis(ref("b2", 2));
      assertThat(chain)
          .as("② 父链从 b2 的 head 一路指回创世，且跨分支")
          .containsExactly(ref("b2", 2), ref("b2", 1), ref("main", 2), ref("main", 1));
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 种创世：独立打开 store 落一行 {@code (main,1)}（parent 空、变更集空），写创世 checkpoint 文件，再关掉它。
   *
   * <p>★ 世界只含 {@code unit} 一个切片——{@code RenameUnitHandler} 只需要它（不需要 map），故不引入无关模块。
   */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, 4L)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T0,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(Map.of(U1, unit()));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T0),
            Map.of("unit", new UnitSnapshot(ref("main", 1), T0, units)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(ref("main", 1), CheckpointEncoder.encode(genesis, List.of(new UnitCodec())));
  }

  /** 创世单位：一个普通单位（改名只碰 name，其余字段用来证明重放搬的是整棵树）。 */
  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  /**
   * 读 head 的机制面：**core 仍开着**时用一棵独立 store 读（WAL + busy_timeout 允许）。
   *
   * <p>★ 不用 {@code core.submit} 的 {@code Conflict} 当读 head 的途径：Conflict 会落事件行、污染被测物；head 就该直接读。
   */
  private RevisionId isolatedHead(String name) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return new Timeline(store, 4L)
          .head(branch(name))
          .orElseThrow(() -> new AssertionError("期望存在的分支 head: " + name));
    }
  }

  /** 读一行的机制面：用一棵独立 store 读（与门面持有的连接各走各的 WAL 读）。 */
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

  private static UnitSnapshot unitOf(SimulationState state) {
    return (UnitSnapshot)
        state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
  }

  /**
   * 真 {@code unit.RenameUnit} 信封：载荷形态是本模块的私事（C26），这里是 {@code {"id":…,"name":…}}。
   *
   * <p>★ 名字里的空格**原样保留**（{@code RenameUnitHandler} 不做任何规范化），故断言可以直接比对这一串字面量。
   */
  private static CommandEnvelope rename(
      String commandId, BranchId branch, long expectedRevision, String name) {
    return new CommandEnvelope(
        commandId,
        "corr-" + commandId,
        "player:local",
        branch,
        new RevisionId(expectedRevision),
        RENAME_TYPE,
        "{\"id\":\"u-1\",\"name\":\"" + name + "\"}");
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
