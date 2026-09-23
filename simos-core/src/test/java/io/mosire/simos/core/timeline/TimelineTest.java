package io.mosire.simos.core.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Timeline} 的行为与护栏用例：行映射（含创世 NULL parent / 可空 calendarLabel）、派生查询（spec §3.3 逐条）、 {@code
 * hasCheckpoint} 的 C19 三项判定、{@code fork} 的分岔语义（C13）与冲突不写、changeset 落盘的类型信息护栏（裁定 21）。
 *
 * <p>★ head 的用例里**必须有两条长度不同的分支**（m2 的判别力条件）：COUNT 与 MAX 的分叉依赖分支间行数不重合。
 */
class TimelineTest {

  /** 玩具变更集（test 侧，R1 不计）：证明 changeset 落盘真的带了类型信息、能读回（裁定 21 的护栏）。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** C19 用例的小周期：N=4，不必构造 100 行就能压到第①项（同 Task 8 Step 4 的可测性理由）。 */
  private static final long N = 4;

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;

  @BeforeEach
  void openTimeline() {
    store = SqliteStore.open(tempDir.resolve("timeline.db"));
    timeline = new Timeline(store, N);
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /** 创世行 + 带 label 的子行逐列往返：parent 空/满、calendarLabel 空/满，两侧都要走到（实现会在缺省处漂移）。 */
  @Test
  void rowsRoundTripEveryColumn() {
    SimosTimestamp labeled = new SimosTimestamp(480L, Optional.of("第20日"));
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), labeled, "c2");

    RevisionRow genesis = timeline.row(ref("main", 1)).orElseThrow();
    assertThat(genesis.parent()).isEmpty();
    assertThat(genesis.timestamp()).isEqualTo(SimosTimestamp.of(0L));

    assertThat(timeline.row(ref("main", 2)))
        .contains(
            new RevisionRow(
                new BranchId("main"),
                new RevisionId(2),
                Optional.of(ref("main", 1)),
                labeled,
                "cmd-c2",
                "c2",
                "player:local",
                "core.AdvanceTime",
                Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  /** head = MAX(revision)（spec §3.3）；两条**长度不同**的分支 + 未知分支三分叉（m2 的判别力条件）。 */
  @Test
  void headIsMaxRevisionPerBranch() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2");
    append(ref("main", 3), Optional.of(ref("main", 2)), SimosTimestamp.of(20L), "c3");
    forkFrom(ref("main", 3), "b2", "cf-b2");

    assertThat(timeline.head(branch("main"))).contains(new RevisionId(3));
    assertThat(timeline.head(branch("b2"))).contains(new RevisionId(1));
    assertThat(timeline.head(branch("ghost"))).isEmpty();
  }

  /** 分支清单：DISTINCT，恰两支，字典序（SQL 里 ORDER BY branch）。 */
  @Test
  void branchesListsEveryBranchOnce() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    forkFrom(ref("main", 1), "b2", "cf-b2");

    assertThat(timeline.branches()).containsExactly(branch("b2"), branch("main"));
  }

  /** 父坐标：创世 ⇒ 空；分岔行 ⇒ 源 head；不存在的行 ⇒ 空。 */
  @Test
  void parentPointsAtForkSourceAndGenesisHasNone() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    forkFrom(ref("main", 1), "b2", "cf-b2");

    assertThat(timeline.parent(ref("main", 1))).isEmpty();
    assertThat(timeline.parent(ref("b2", 1))).contains(ref("main", 1));
    assertThat(timeline.parent(ref("b2", 9))).isEmpty();
  }

  /** 父链跨分岔一路走到创世，次序 = 从起点到创世；幽灵起点 ⇒ 空清单。 */
  @Test
  void chainToGenesisWalksAcrossTheFork() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2");
    forkFrom(ref("main", 2), "b2", "cf-b2");
    append(ref("b2", 2), Optional.of(ref("b2", 1)), SimosTimestamp.of(30L), "c4");

    assertThat(timeline.chainToGenesis(ref("b2", 2)))
        .containsExactly(ref("b2", 2), ref("b2", 1), ref("main", 2), ref("main", 1));
    assertThat(timeline.chainToGenesis(ref("main", 1))).containsExactly(ref("main", 1));
    assertThat(timeline.chainToGenesis(ref("b2", 9))).isEmpty();
  }

  /** 某 correlationId 的落盘（判据二）：跨分支都能捞到，按 (branch, revision) 序；未知 ⇒ 空。 */
  @Test
  void byCorrelationFindsRowsAcrossBranches() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "shared");
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "other");
    forkFrom(ref("main", 2), "b2", "shared");

    List<RevisionRow> hits = timeline.byCorrelation("shared");
    assertThat(hits).hasSize(2);
    // ORDER BY branch, revision：字典序 b2 < main，分岔行在前
    assertThat(hits.get(0).branch()).isEqualTo(branch("b2"));
    assertThat(hits.get(1).branch()).isEqualTo(branch("main"));
    assertThat(timeline.byCorrelation("ghost")).isEmpty();
  }

  /** M7 T1：{@code listRevisions} 按分支隔离、revision 升序、parent 与 fork 一致（R3 的库侧护栏）。 */
  @Test
  void listRevisionsIsPerBranchAndAscending() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2");
    forkFrom(ref("main", 2), "b2", "cf-b2");

    List<RevisionRow> mainRows = timeline.listRevisions(branch("main"));
    assertThat(mainRows).extracting(row -> row.branch().value()).containsExactly("main", "main");
    assertThat(mainRows).extracting(row -> row.revision().value()).containsExactly(1L, 2L);

    List<RevisionRow> b2Rows = timeline.listRevisions(branch("b2"));
    assertThat(b2Rows).extracting(row -> row.branch().value()).containsExactly("b2");
    assertThat(b2Rows).extracting(row -> row.revision().value()).containsExactly(1L);
    assertThat(b2Rows.get(0).parent()).contains(ref("main", 2));

    assertThat(timeline.listRevisions(branch("ghost"))).isEmpty();
  }

  /** 分岔语义（C13）：新行 (b2,1)、parent=源 head、变更集=空集、tick 继承父、命令类型钉为 core.ForkBranch。 */
  @Test
  void forkWritesFirstRevisionInheritingTimestampAndEmptyChangeSet() {
    SimosTimestamp labeled = new SimosTimestamp(480L, Optional.of("第20日"));
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), labeled, "c2");

    Optional<StateRef> forked =
        timeline.fork(
            branch("main"), new RevisionId(2), branch("b2"), "cmd-f", "cf-f", "player:local");

    assertThat(forked).contains(ref("b2", 1));
    RevisionRow first = timeline.row(ref("b2", 1)).orElseThrow();
    assertThat(first.parent()).contains(ref("main", 2));
    assertThat(first.timestamp()).isEqualTo(labeled);
    assertThat(first.commandType()).isEqualTo(Timeline.FORK_COMMAND_TYPE);
    assertThat(first.commandId()).isEqualTo("cmd-f");
    assertThat(first.correlationId()).isEqualTo("cf-f");
    assertThat(first.initiator()).isEqualTo("player:local");
    assertThat(first.changesetJson()).isEqualTo(Timeline.changeSetJson(WorldChangeSet.empty()));
    // 语义断言而非字节断言（裁定 12）：落盘的确实是"空的全模块变更集"
    assertThat(Timeline.readChangeSet(first.changesetJson())).isEqualTo(WorldChangeSet.empty());
    assertThat(timeline.head(branch("b2"))).contains(new RevisionId(1));
    assertThat(timeline.branches()).containsExactly(branch("b2"), branch("main"));
  }

  /** 冲突不写（spec §3.4 ①）：head ≠ expected ⇒ 空返回、零行落库、分支清单不变。 */
  @Test
  void forkRefusesWhenHeadMovedOn() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2");

    Optional<StateRef> forked =
        timeline.fork(
            branch("main"), new RevisionId(1), branch("b2"), "cmd-f", "cf-f", "player:local");

    assertThat(forked).isEmpty();
    assertThat(timeline.branches()).containsExactly(branch("main"));
    assertThat(timeline.row(ref("b2", 1))).isEmpty();
  }

  /** 未知源分支同样不写。 */
  @Test
  void forkRefusesUnknownSource() {
    Optional<StateRef> forked =
        timeline.fork(
            branch("ghost"), new RevisionId(1), branch("b2"), "cmd-f", "cf-f", "player:local");

    assertThat(forked).isEmpty();
    assertThat(timeline.branches()).isEmpty();
  }

  /** C19 三项判定逐项 + 反例（N=4）：反例里 (b2,1) 这类"是 revision 1 但不是 (main,1)"的行必须为假。 */
  @Test
  void hasCheckpointFollowsTheThreeCriteria() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1"); // ③ 创世
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2");
    append(ref("main", 3), Optional.of(ref("main", 2)), SimosTimestamp.of(20L), "c3");
    // 先分岔再补 main:4：让 ②（(main,3) 是分岔 parent）与 ①（4 % 4 == 0）各自独立命中
    forkFrom(ref("main", 3), "b2", "cf-b2"); // ② (main,3) 是 b2 的 revision 1 的 parent
    append(
        ref("main", 4), Optional.of(ref("main", 3)), SimosTimestamp.of(30L), "c4"); // ① 4 % 4 == 0

    assertThat(timeline.hasCheckpoint(ref("main", 1))).isTrue(); // ③
    assertThat(timeline.hasCheckpoint(ref("main", 3))).isTrue(); // ②
    assertThat(timeline.hasCheckpoint(ref("main", 4))).isTrue(); // ①
    assertThat(timeline.hasCheckpoint(ref("b2", 1))).isFalse(); // 反例：revision 1 但不是 (main,1)
    assertThat(timeline.hasCheckpoint(ref("main", 2))).isFalse(); // 反例：三项全不中
    // 另一处分岔再开：分岔点本身（(b2,1)）在成为 parent 后按 ② 变真
    timeline.fork(branch("b2"), new RevisionId(1), branch("b3"), "cmd-g", "cf-g", "player:local");
    assertThat(timeline.hasCheckpoint(ref("b2", 1))).isTrue();
  }

  /** 裁定 21 的护栏：带值的变更集经唯一落盘点写、经同一读口读，必须逐值相等（mixin 拔掉 ⇒ 读侧死在缺 @class）。 */
  @Test
  void changeSetJsonCarriesTypeInfoAndRoundTrips() {
    WorldChangeSet withValue = new WorldChangeSet(Map.of("toy", new ToyChangeSet(7)));

    WorldChangeSet back = Timeline.readChangeSet(Timeline.changeSetJson(withValue));

    assertThat(back).isEqualTo(withValue);
    assertThat(back.modules()).containsEntry("toy", new ToyChangeSet(7));
    assertThat(Timeline.readChangeSet(Timeline.changeSetJson(WorldChangeSet.empty())))
        .isEqualTo(WorldChangeSet.empty());
  }

  /**
   * ★ **裁定 39 的护栏**（Task 8 实测，2026-09-18）：{@code changeset_json} 这一列的往返**必须用真的模块变更集验**。
   *
   * <p>上一条护栏的替身 {@link ToyChangeSet} 只有 {@code v()} 一个组件，而三个真变更集都多一个**派生判断** {@code public boolean
   * isEmpty()}——Jackson 把内省出的属性 {@code empty} **写进字节**，严格读回随即抛 {@code
   * UnrecognizedPropertyException}。 于是「非空变更集落盘后能读回」这句结论**只在替身上成立**：判别力差的就是**那一个方法**。 （当场取证：{@code
   * task-8-evidence/probe-changeset-wire-BEFORE.log}，{@code map}/{@code unit} 双双 {@code
   * HAS_EMPTY_PROPERTY=true} 且读回必抛。）
   *
   * <p>故本用例**头一件事是把那个差当场量出来**（形态 1：夹具的先决条件自证）——不钉住它，本条随时可能退化成上一条的翻版。
   *
   * <p>★ 修法在**共享层**（util 的 {@code SimosObjectMapper}）而不在本类：{@code Timeline} 那台 mapper 按 ADR-1
   * 看不见任何领域类型， 装不上三个模块 codec 各自注册的 mixin——这是"三处各自都对、中间没有装配点"，只能由底座给一条与领域类型无关的规则。
   */
  @Test
  void changeSetJsonRoundTripsRealModuleChangeSetsNotJustStandIns() {
    // ── 前提自证：替身与真身差的正是 isEmpty ──
    assertThatThrownBy(() -> ToyChangeSet.class.getMethod("isEmpty"))
        .as("前提：替身 ToyChangeSet 必须**没有** isEmpty，否则它俩的差别不在这个轴上，本条与上一条同义")
        .isInstanceOf(NoSuchMethodException.class);
    for (Class<?> real : List.of(MapChangeSet.class, SocialChangeSet.class, UnitChangeSet.class)) {
      assertThatCode(() -> real.getMethod("isEmpty"))
          .as("前提：%s 必须有 isEmpty（正是替身缺的那一个方法）", real.getSimpleName())
          .doesNotThrowAnyException();
    }

    // ── 真身往返：写侧不得写出 empty，读侧必须逐值读回 ──
    WorldChangeSet real =
        new WorldChangeSet(
            Map.of(
                "map", MapChangeSet.between(GameMap.empty(), oneHexMap()),
                "social",
                    SocialChangeSet.between(new SocialData(Map.of(), Map.of()), onePopulation()),
                "unit", UnitChangeSet.between(UnitState.empty(), oneUnitState())));

    String json = Timeline.changeSetJson(real);

    assertThat(json).as("写侧不得把派生判断 empty 写进线格式（它是判断不是状态，裁定 39）").doesNotContain("\"empty\"");
    assertThat(Timeline.readChangeSet(json)).as("三个真模块变更集必须逐值往返——上一条替身查不出的正是这里").isEqualTo(real);
  }

  /** 越界写不留残行（R2 经 Timeline 的路径）：parent 行不存在 ⇒ 库拒绝 + 回滚，以"事务失败"上抛。 */
  @Test
  void appendRejectsRowWhoseParentDoesNotExist() {
    assertThatThrownBy(
            () -> append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(10L), "c2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事务失败，已回滚");
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /** 主键冲突（同 (branch, revision) 二次落盘）同样由库拒绝。 */
  @Test
  void appendRejectsDuplicatePrimaryKey() {
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1");
    assertThatThrownBy(() -> append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L), "c1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事务失败，已回滚");
    assertThat(timeline.branches()).containsExactly(branch("main"));
  }

  /** C19 的周期 N 是构造参数，必须 ≥ 1（0 会让第①项变成除零）。 */
  @Test
  void checkpointIntervalMustBeAtLeastOne() {
    assertThatThrownBy(() -> new Timeline(store, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpointInterval");
  }

  // ---- 夹具 ----

  // ── 裁定 39 的夹具：三个**真**模块变更集各一份非空值（不是替身） ──

  private static GameMap oneHexMap() {
    HexCoord at = new HexCoord(1, 1);
    return new GameMap(
        Map.of(at, new HexCell(0.35)),
        TerrainBlocks.uniform(java.util.Set.of(at), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SocialData onePopulation() {
    SimosTimestamp t0 = SimosTimestamp.of(0);
    return new SocialData(
        Map.of(
            new HexCoord(1, 1),
            new PopulationSeries(
                new Segment<>(t0, 10000L),
                new SegmentedSeries<>(List.of(new Segment<>(t0, 0.02)), List.of(), null),
                List.of())),
        Map.of());
  }

  private static UnitState oneUnitState() {
    SimosTimestamp t0 = SimosTimestamp.of(0);
    return UnitState.empty()
        .withUnits(
            Map.of(
                new UnitId("u-1"),
                new Unit(
                    new UnitId("u-1"),
                    "单位 u-1",
                    new SegmentedSeries<>(
                        List.of(new Segment<>(t0, Optional.<UnitId>empty())), List.of(), null),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(t0, Optional.of(new HexCoord(1, 1)))),
                        List.of(),
                        null),
                    500,
                    Map.of("旗帜", 3),
                    2,
                    1000,
                    Optional.empty())));
  }

  private static BranchId branch(String name) {
    return new BranchId(name);
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(branch(branch), new RevisionId(revision));
  }

  /** 按给定坐标落一行（创世形态 = parent 空）；时间戳的 tick 取 revision 坐标本值，命令类型钉为推进。 */
  private void append(
      StateRef target, Optional<StateRef> parent, SimosTimestamp timestamp, String correlationId) {
    timeline.appendRevision(
        new RevisionRow(
            target.branch(),
            target.revision(),
            parent,
            timestamp,
            "cmd-" + correlationId,
            correlationId,
            "player:local",
            "core.AdvanceTime",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  /** 从 {@code forkPoint} 分出 {@code newBranch}，断言成功（分岔失败在此当场炸，不静默滑过）。 */
  private void forkFrom(StateRef forkPoint, String newBranch, String correlationId) {
    assertThat(
            timeline.fork(
                forkPoint.branch(),
                forkPoint.revision(),
                branch(newBranch),
                "cmd-" + correlationId,
                correlationId,
                "player:local"))
        .isPresent();
  }
}
