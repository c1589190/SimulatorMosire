package io.mosire.simos.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.info.InfoEntry;
import io.mosire.simos.util.info.InfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Replay} 的两条核心判据（R4 / R5）与它的行为边界。
 *
 * <p>★★ **本夹具的做法（决定了这套用例有没有判别力）**：世界由夹具**独立演进**——每一步的变更集由「改前 / 改后」算出 （各模块 codec 的 {@code
 * between}），而"改后"是**直接构造**的状态对象。于是 {@code truths} 里的每一份状态都是**真值**， 与重放的实现路径无关。所有断言都是「重放的结果 ==
 * 这份真值」，而**不是**「重放跟自己比」。
 *
 * <p>★ 顺带的收获：这条对拍同时把**铁律 5**（{@code apply(changeSet, base)} 必须逐字段重建出 target）在**跨模块、跨分支、
 * 多步累积**的场合又验了一遍——单模块的往返用例覆盖不到"连着施加五步之后还对不对"。
 *
 * <p>★ **链的形状**（N = 4）：
 *
 * <pre>
 * (main,1) 创世，空变更集，世界由创世 checkpoint 承载      ← C19 第③项：必有 checkpoint
 * (main,2) map +H11
 * (main,3) social +H11
 * (main,4) unit +u-1                                    ← 4 % N == 0：必有 checkpoint
 * (main,5) map +H22
 * (main,6) social +H22                                  ← 分岔点：C19 第②项必有 checkpoint
 *   └─ (b2,1) 分岔行，空变更集，时刻继承父（tick 6）
 *      (b2,2) unit +u-2
 *      (b2,3) map +H33
 * </pre>
 *
 * ★ 三份真值 checkpoint 落在 (main,1)/(main,4)/(main,6)——**恰好是 C19 说的三个"应当有"的坐标**，也恰好是 R5 赖以成立的那三处。
 */
class ReplayTest {

  /** C19 的周期。取 4（不是 100）才不必构造一百行 revision 就压到边界（计划 Step 4）。 */
  private static final long N = 4;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);
  private static final HexCoord H33 = new HexCoord(3, 3);
  private static final HexCoord H44 = new HexCoord(4, 4);

  private static final Address SUBJECT = Address.parse("map:Map1:hex.1_1");
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final MapCodec MAP = new MapCodec();
  private static final SocialCodec SOCIAL = new SocialCodec();
  private static final UnitCodec UNIT = new UnitCodec();
  private static final List<ModuleCodec> CODECS = List.of(MAP, SOCIAL, UNIT);

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private CheckpointStore checkpoints;
  private Replay replay;
  private Replay fromGenesis;

  // ── 世界的真值：三个模块的当前数据 + 它**最后在哪个坐标上变过**（模块快照的 ref/timestamp 来源）──
  private GameMap map;
  private StateMeta mapMeta;
  private SocialData social;
  private StateMeta socialMeta;
  private UnitState unit;
  private StateMeta unitMeta;

  /** info 段非空——它是 util 的类型（裁定 38 的键绑定），走的是 Core 自己的编解码路径，别让它空跑。 */
  private final InfoSystem info =
      InMemoryInfoSystem.empty()
          .put(
              SUBJECT,
              new InfoEntry(
                  "alias",
                  "某个值",
                  TimeRange.since(T0),
                  new SubjectId("map.hex", "h-0001"),
                  Optional.of("备注")));

  /** 每个坐标的**真值状态**（由夹具独立演进得到，不是重放的产物）。 */
  private final Map<StateRef, SimulationState> truths = new LinkedHashMap<>();

  @BeforeEach
  void buildTheChain() {
    store = SqliteStore.open(tempDir.resolve("replay.db"));
    timeline = new Timeline(store, N);
    checkpoints = new CheckpointStore(tempDir);
    replay = new Replay(timeline, checkpoints, CODECS);
    fromGenesis = new Replay(timeline, checkpoints, CODECS, true);

    // ── (main,1) 创世：变更集为空（世界由创世 checkpoint 承载，没有"更早的状态"可施加）──
    map = GameMap.empty();
    social = new SocialData(Map.of(), Map.of());
    unit = UnitState.empty();
    mapMeta = meta(ref("main", 1), 1);
    socialMeta = meta(ref("main", 1), 1);
    unitMeta = meta(ref("main", 1), 1);
    append(ref("main", 1), Optional.empty(), WorldChangeSet.empty(), 1);
    writeTruthCheckpoint(ref("main", 1));

    // ── (main,2)：map 加一格 ──
    GameMap mapBefore = map;
    map = mapOf(Map.of(H11, plains()), Map.of(H11, "plains"));
    mapMeta = meta(ref("main", 2), 2);
    append(
        ref("main", 2),
        Optional.of(ref("main", 1)),
        wcs("map", MapChangeSet.between(mapBefore, map)),
        2);

    // ── (main,3)：social 加一个聚落 ──
    SocialData socialBefore = social;
    social = new SocialData(Map.of(H11, population()), Map.of());
    socialMeta = meta(ref("main", 3), 3);
    append(
        ref("main", 3),
        Optional.of(ref("main", 2)),
        wcs("social", SocialChangeSet.between(socialBefore, social)),
        3);

    // ── (main,4)：unit 加一个单位（4 % N == 0 ⇒ C19 第①项：必有 checkpoint）──
    UnitState unitBefore = unit;
    unit = UnitState.empty().withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11)));
    unitMeta = meta(ref("main", 4), 4);
    append(
        ref("main", 4),
        Optional.of(ref("main", 3)),
        wcs("unit", UnitChangeSet.between(unitBefore, unit)),
        4);
    writeTruthCheckpoint(ref("main", 4));

    // ── (main,5)：map 再加一格 ──
    mapBefore = map;
    map = mapOf(Map.of(H11, plains(), H22, hills()), Map.of(H11, "plains", H22, "hills"));
    mapMeta = meta(ref("main", 5), 5);
    append(
        ref("main", 5),
        Optional.of(ref("main", 4)),
        wcs("map", MapChangeSet.between(mapBefore, map)),
        5);

    // ── (main,6)：social 再加一个聚落 ⇒ 它成了分岔点（C19 第②项：必有 checkpoint）──
    socialBefore = social;
    social = new SocialData(Map.of(H11, population(), H22, population()), Map.of());
    socialMeta = meta(ref("main", 6), 6);
    append(
        ref("main", 6),
        Optional.of(ref("main", 5)),
        wcs("social", SocialChangeSet.between(socialBefore, social)),
        6);
    writeTruthCheckpoint(ref("main", 6));

    // ── 分岔 (main,6) → b2 ──
    assertThat(
            timeline.fork(
                branch("main"),
                new RevisionId(6),
                branch("b2"),
                "cmd-fork",
                "corr-fork",
                "player:local"))
        .as("夹具前提：head(main) 必须正好是 6，否则分岔根本没发生")
        .contains(ref("b2", 1));
    // 分岔行：空变更集（C13）、时刻继承父（tick 6）⇒ 模块的坐标一概不动，只是外层坐标换了
    recordTruth(ref("b2", 1), 6);

    // ── (b2,2)：unit 加第二个单位 ──
    unitBefore = unit;
    unit =
        UnitState.empty()
            .withUnits(
                Map.of(
                    new UnitId("u-1"),
                    oneUnit("u-1", H11),
                    new UnitId("u-2"),
                    oneUnit("u-2", H22)));
    unitMeta = meta(ref("b2", 2), 7);
    append(
        ref("b2", 2),
        Optional.of(ref("b2", 1)),
        wcs("unit", UnitChangeSet.between(unitBefore, unit)),
        7);

    // ── (b2,3)：map 再加一格 ──
    mapBefore = map;
    map =
        mapOf(
            Map.of(H11, plains(), H22, hills(), H33, plains()),
            Map.of(H11, "plains", H22, "hills", H33, "plains"));
    mapMeta = meta(ref("b2", 3), 8);
    append(
        ref("b2", 3),
        Optional.of(ref("b2", 2)),
        wcs("map", MapChangeSet.between(mapBefore, map)),
        8);
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /**
   * ★ **最强的一条**：重放必须逐字段重建出**夹具独立演进**的真值（不是"自己跟自己比"）。
   *
   * <p>覆盖了三种起点：target 自己有 checkpoint（0 步）、中途有 checkpoint、只有创世有 checkpoint；也覆盖了跨分支 （{@code (b2,*)}
   * 的父链穿过分岔点回到 {@code main}）。
   */
  @Test
  void replayRebuildsTheIndependentlyConstructedTruthAtEveryCoordinate() {
    for (StateRef target : everyCoordinate()) {
      assertThat(replay.replay(target).state())
          .as("重放 %s 必须逐字段重建出真值（含「没提名的模块保持原样」与模块快照各自的坐标）", target)
          .isEqualTo(truths.get(target));
    }
  }

  /**
   * **R4**：同一 {@code (b, r)}，「从最近 checkpoint 重放」与「从创世全量重放」结果 {@code equals}。
   *
   * <p>checkpoint 是纯优化（C18）⇒ 它**必须**与全量重放等价。不等价就说明 checkpoint 写错了、或重放路径选错了。
   *
   * <p>★ 末两行是**判别力对照**（形态 1）：若两条路径走的是同一段代码、同样的步数，这条用例就是"自己跟自己比"。
   */
  @Test
  void replayFromTheNearestCheckpointEqualsReplayFromGenesis() {
    for (StateRef target : everyCoordinate()) {
      assertThat(replay.replay(target).state())
          .as("R4：从最近 checkpoint 重放到 %s 必须等于从创世全量重放（C18：checkpoint 不改变结果）", target)
          .isEqualTo(fromGenesis.replay(target).state());
    }

    // ★ 夹具判别力：两条路径必须真的走**不同**的步数，否则上面那圈的相等是同一段代码自己跟自己比
    assertThat(replay.replay(ref("b2", 3)).applyCount())
        .as("判别力：生产路径 3 步 vs 全量路径 8 步，二者必须不同")
        .isEqualTo(3);
    assertThat(fromGenesis.replay(ref("b2", 3)).applyCount())
        .as("判别力：全量路径必须从创世一路重放（3 + 5 = 8 步）")
        .isEqualTo(8);
  }

  /**
   * **R5**：任取一个 target，走到 checkpoint 前的 {@code apply} 次数 ≤ N。
   *
   * <p>★ 用的是**跨分支**的 target（{@code (b2,3)}）——这一条才真正验到 **C19 第②项**（分岔点必有 checkpoint）。 分岔点若不强制作
   * checkpoint，父链会一路退回 {@code (main,4)}，步数变成 5 &gt; N。
   */
  @Test
  void replayStaysWithinTheCheckpointIntervalEvenAcrossAFork() {
    Replay.ReplayResult acrossFork = replay.replay(ref("b2", 3));

    assertThat(acrossFork.applyCount())
        .as("R5：跨分支的 target 也必须 ≤ N = %s（靠 C19 第②项「分岔点强制一次 checkpoint」）", N)
        .isLessThanOrEqualTo((int) N);
    assertThat(acrossFork.applyCount())
        .as("且步数必须是算得出来的那个：3 = (b2,3)(b2,2)(b2,1)，起点是分岔点 (main,6) 的 checkpoint")
        .isEqualTo(3);
  }

  /**
   * **C18 的回退真的会走**：那个"应当有"的 checkpoint 文件被删掉之后，重放要接着往创世方向找，且**结果不变**。
   *
   * <p>★ 这条同时钉住了实现期校正 ①（类注）：spec §6.4 伪码的循环条件只看「**应当**有 checkpoint」（{@code hasCheckpoint}），
   * 照抄的话到这里就会停在 {@code (main,6)} 然后去读一个不存在的文件。实现按**文件可用性**回退，这条用例才活着。
   *
   * <p>★ 删的是**两份**（分岔点 {@code (main,6)} 与周期点 {@code (main,4)}）：只删一份时，回退一步就落在另一份上， 量不出"一路退到创世"这条路。
   *
   * <p>★ 也**如实记下 R5 的边界**：劣化形态的步数**会**超过 N（这里 8 &gt; 4）。R5 成立的前提正是「C19 说应当有的 checkpoint
   * 确实都在」——checkpoint 是纯优化（C18），优化没了，代价就是多施加几步。
   */
  @Test
  void replayFallsBackToAnEarlierCheckpointWhenTheNearestFileIsMissing() {
    deleteCheckpoint(ref("main", 6));
    deleteCheckpoint(ref("main", 4));

    Replay.ReplayResult result = replay.replay(ref("b2", 3));

    assertThat(result.applyCount()).as("C18：两份 checkpoint 都不可读 ⇒ 只能一路回退到创世，走 8 步").isEqualTo(8);
    assertThat(result.applyCount())
        .as("★ R5 的边界：劣化形态**会**超过 N = %s，别把 R5 读成无条件成立", N)
        .isGreaterThan((int) N);
    assertThat(result.state())
        .as("回退路径的结果必须仍然等于真值——这正是「checkpoint 缺失不回退失败」的全部意义")
        .isEqualTo(truths.get(ref("b2", 3)));
  }

  /** target 自己就有可读 checkpoint ⇒ 一步都不施加（{@code applyCount} 为 0，而不是"施加了 0 次变更"的含糊说法）。 */
  @Test
  void replayOfACoordinateWithItsOwnCheckpointAppliesNothing() {
    Replay.ReplayResult result = replay.replay(ref("main", 4));

    assertThat(result.applyCount()).as("(main,4) 自己就是 checkpoint 坐标").isZero();
    assertThat(result.state()).as("0 步也不许改变结果").isEqualTo(truths.get(ref("main", 4)));
  }

  /**
   * {@code applyCount} 数的是**变更集个数**（重放了几步），**不是** codec 的调用次数——这条把报告里写死的语义钉住， 免得下游（R5
   * 的判据）按"模块调用数"去理解它。
   */
  @Test
  void applyCountCountsRevisionsNotCodecCalls() {
    // 一行同时改 map 与 social：一条变更集、两个模块
    GameMap mapBefore = map;
    SocialData socialBefore = social;
    map =
        mapOf(
            Map.of(H11, plains(), H22, hills(), H33, plains(), H44, plains()),
            Map.of(H11, "plains", H22, "hills", H33, "plains", H44, "plains"));
    social =
        new SocialData(Map.of(H11, population(), H22, population(), H44, population()), Map.of());
    append(
        ref("main", 7),
        Optional.of(ref("main", 6)),
        new WorldChangeSet(
            Map.of(
                "map", MapChangeSet.between(mapBefore, map),
                "social", SocialChangeSet.between(socialBefore, social))),
        7);

    assertThat(replay.replay(ref("main", 7)).applyCount())
        .as("一条变更集改了 map 与 social 两个模块 ⇒ 步数仍是 1，不是 2")
        .isEqualTo(1);
  }

  /** 坐标不在时间线上：**必须当场炸**，不许重放出一个空状态把错误咽下去。 */
  @Test
  void replayRejectsACoordinateThatIsNotOnTheTimeline() {
    assertThatThrownBy(() -> replay.replay(ref("main", 99)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("main@99");
  }

  /** 连创世都没有 checkpoint ⇒ 无路可退，不能静默返回一个空世界。 */
  @Test
  void replayFailsWhenEvenTheGenesisCheckpointIsMissing() {
    deleteCheckpoint(ref("main", 1));

    assertThatThrownBy(() -> replay.replay(ref("main", 2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("main@1");
  }

  /** 变更集里的 namespace 没有装配 codec ⇒ 当场炸（装配缺项不是"这个模块不用重放"）。 */
  @Test
  void aChangeSetNamespaceWithoutACodecIsRejected() {
    append(
        ref("main", 7),
        Optional.of(ref("main", 6)),
        wcs(
            "ghost",
            MapChangeSet.between(
                GameMap.empty(), mapOf(Map.of(H44, plains()), Map.of(H44, "plains")))),
        7);

    assertThatThrownBy(() -> replay.replay(ref("main", 7)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  /** 构造期就拒绝重复 namespace：静默覆盖会让"路由到哪个 codec"取决于集合迭代序（同裁定 37 对注册表的处置）。 */
  @Test
  void duplicateNamespaceIsRejectedAtConstruction() {
    assertThatThrownBy(() -> new Replay(timeline, checkpoints, List.of(MAP, new MapCodec())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }

  /** 判别力对照（形态 1）：相邻两个坐标的状态必须**不同**，否则上面那些 {@code isEqualTo} 可能恒真。 */
  @Test
  void adjacentCoordinatesReplayToDifferentStates() {
    assertThat(replay.replay(ref("main", 5)).state())
        .as("(main,5) 与 (main,6) 之间 social 变了 ⇒ 状态必须不等，否则相等断言无判别力")
        .isNotEqualTo(replay.replay(ref("main", 6)).state());
    assertThat(replay.replay(ref("b2", 2)).state())
        .as("(b2,2) 与 (b2,3) 之间 map 变了 ⇒ 状态必须不等")
        .isNotEqualTo(replay.replay(ref("b2", 3)).state());
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 全部坐标（覆盖三种起点与跨分支）。 */
  private static List<StateRef> everyCoordinate() {
    return List.of(
        ref("main", 1),
        ref("main", 2),
        ref("main", 3),
        ref("main", 4),
        ref("main", 5),
        ref("main", 6),
        ref("b2", 1),
        ref("b2", 2),
        ref("b2", 3));
  }

  /** 落一行，并把**该坐标的真值**记下来（真值取的是此刻各模块的数据与各自的坐标）。 */
  private void append(StateRef at, Optional<StateRef> parent, WorldChangeSet changeset, long tick) {
    timeline.appendRevision(
        new RevisionRow(
            at.branch(),
            at.revision(),
            parent,
            SimosTimestamp.of(tick),
            "cmd-" + at.branch().value() + "-" + at.revision().value(),
            "corr",
            "player:local",
            "core.AdvanceTime",
            Timeline.changeSetJson(changeset)));
    recordTruth(at, tick);
  }

  private void recordTruth(StateRef at, long tick) {
    truths.put(
        at,
        new SimulationState(
            new StateMeta(at, SimosTimestamp.of(tick)),
            Map.of(
                "map", new MapSnapshot(mapMeta.ref(), mapMeta.timestamp(), map),
                "social", new SocialSnapshot(socialMeta.ref(), socialMeta.timestamp(), social),
                "unit", new UnitSnapshot(unitMeta.ref(), unitMeta.timestamp(), unit)),
            info));
  }

  /** 把真值状态编成信封落盘——**与 Task 13 的写侧同形**（Core 自己在信封层碰 info，模块载荷交 codec）。 */
  private void writeTruthCheckpoint(StateRef at) {
    checkpoints.write(at, envelopeOf(truths.get(at)));
  }

  private static String envelopeOf(SimulationState state) {
    Map<String, String> modules = new LinkedHashMap<>();
    modules.put("map", MAP.encodeSnapshot(state.module("map").orElseThrow()));
    modules.put("social", SOCIAL.encodeSnapshot(state.module("social").orElseThrow()));
    modules.put("unit", UNIT.encodeSnapshot(state.module("unit").orElseThrow()));
    try {
      return Envelope.encode(
              state.meta(), modules, SimosObjectMapper.create().writeValueAsString(state.info()))
          .toString();
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("夹具拼信封失败（info 段序列化）", e);
    }
  }

  /**
   * 删掉一个 checkpoint 文件。
   *
   * <p>★ **先自证夹具前提**：文件必须本来就在——不然"删除"是空操作，用例会**假绿**（形态 1：夹具的先决条件当场自证）。
   */
  private void deleteCheckpoint(StateRef at) {
    Path file =
        tempDir
            .resolve("checkpoints")
            .resolve(at.branch().value())
            .resolve(at.revision().value() + ".json");
    assertThat(Files.isRegularFile(file))
        .as("夹具前提：%s 的 checkpoint 必须本来就在，否则「删除」是空操作，用例会假绿", at)
        .isTrue();
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 checkpoint 失败: " + file, e);
    }
  }

  private static WorldChangeSet wcs(String namespace, ChangeSet changeSet) {
    return new WorldChangeSet(Map.of(namespace, changeSet));
  }

  private static StateMeta meta(StateRef at, long tick) {
    return new StateMeta(at, SimosTimestamp.of(tick));
  }

  private static BranchId branch(String name) {
    return new BranchId(name);
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(branch(branch), new RevisionId(revision));
  }

  private static HexCell plains() {
    return new HexCell(0.35);
  }

  private static HexCell hills() {
    return new HexCell(0.9);
  }

  /** 单地形（{@code plains}）的图：hexes 与 terrainBlocks 原子构造（P1 分割不变式）。 */
  private static GameMap mapOf(Map<HexCoord, HexCell> hexes, Map<HexCoord, String> terrain) {
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrain),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static PopulationSeries population() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.02)), List.of(), null),
        List.of());
  }

  private static Unit oneUnit(String id, HexCoord at) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
        500,
        Map.of("旗帜", 3),
        2,
        1000,
        Optional.empty());
  }
}
