package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
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
 * unit 扩容的**端到端判据**（T10 / spec §八 #1/#5/#6/#7/#8/#12/#15）：真 {@link Shell}（真 {@code CommandBus} + 真
 * {@code UnitTimeParticipant} + 真 Sqlite store）上，逐条命令走 {@code Command → ChangeSet → Revision}，再
 * {@code replay} 回读**数字**。
 *
 * <p>★ 与 {@code McpCoverageTest} 的分工：那个类证"30 条 type 经 MCP 都可提交且 head 前进"（可达性）；本类证"若干条命令的**语义结果**
 * 逐值正确"（数值）。两者都在真装配上跑，互不替代。
 *
 * <p>★ 夹具（4 单位 + 三格走廊）：{@code u-1@(1,1)}（战损/稀疏路线/回归的载体）、{@code u-2@(1,2)}、{@code u-3@(1,1)}（与 u-1
 * 同格，合体正例）、{@code u-4@(1,3)}；且创世即带编制树 {@code u-3→u-2}、{@code u-4→u-3}（子树迁移判据的载体）。 命令信封的 {@code at}
 * 继承创世时刻 {@code T7}（裁定 35）。
 */
class UnitExtensionEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final UnitId U3 = new UnitId("u-3");
  private static final UnitId U4 = new UnitId("u-4");

  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "player:local";

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
    shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                INITIATOR,
                base.mapId(),
                base.bindAddress()));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ────────────────────────────── 判据 ──────────────────────────────

  /** ★ 判据 #1（多属）：同一 unit 同时出现在 2 条命令链 ⇒ 两条都在、`commandChains.size() == 2`。 */
  @Test
  void oneUnitCanBelongToTwoCommandChains() {
    assertThat(
            submit(
                "unit.CreateCommandChain",
                "{\"chainId\":\"c-1\",\"name\":\"链一\",\"commander\":\"u-2\",\"members\":[\"u-2\",\"u-1\"]}",
                1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));
    assertThat(
            submit(
                "unit.CreateCommandChain",
                "{\"chainId\":\"c-2\",\"name\":\"链二\",\"commander\":\"u-3\",\"members\":[\"u-3\",\"u-1\"]}",
                2))
        .isEqualTo(new CommandResult.Committed(ref("main", 3)));

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 3)));
    assertThat(state.commandChains()).as("两条链都在（多属不是单属）").hasSize(2);
    assertThat(state.commandChains().get(new CommandChainId("c-1")).members()).contains(U1);
    assertThat(state.commandChains().get(new CommandChainId("c-2")).members()).contains(U1);

    // 重放是纯函数：同坐标两次回读逐值相同（链组件真的进了快照与变更集）。
    assertThat(shell.coreSimos().replay(ref("main", 3)))
        .isEqualTo(shell.coreSimos().replay(ref("main", 3)));
  }

  /** ★ 判据 #5（同格合体）：不同格 ⇒ 拒；同格 + MOVING ⇒ 过。 */
  @Test
  void mergeFormationRequiresTheSameHex() {
    // u-2@(1,2) 与 u-1@(1,1) 不同格 ⇒ 拒，且不留 revision。
    CommandResult differentHex =
        submit("unit.MergeFormation", "{\"childId\":\"u-2\",\"parentId\":\"u-1\"}", 1);
    assertThat(differentHex).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) differentHex).reason()).contains("不同格");
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("被拒 ⇒ head 不动")
        .isEqualTo(1L);

    // u-3@(1,1) 与 u-1@(1,1) 同格 + MOVING ⇒ 过。
    assertThat(submit("unit.MergeFormation", "{\"childId\":\"u-3\",\"parentId\":\"u-1\"}", 1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));
    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 2)));
    assertThat(state.units().get(U3).parent().valueAt(T7)).contains(U1);
    assertThat(state.units().get(U3).attached().valueAt(T7)).as("合体即 attach").isTrue();
  }

  /** ★ 判据 #6（子树迁移整体性）：`ReparentSubtree` 后**每个后代**的 parent 段都被追加（不只 root）。 */
  @Test
  void reparentSubtreeAppendsAParentSegmentToEveryDescendant() {
    // 创世树：u-3 → u-2、u-4 → u-3；把整棵以 u-3 为根的子树迁到 u-1 下。
    assertThat(submit("unit.ReparentSubtree", "{\"rootId\":\"u-3\",\"parent\":\"u-1\"}", 1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 2)));
    assertThat(state.units().get(U3).parent().valueAt(T7)).as("root 换父").contains(U1);
    assertThat(state.units().get(U4).parent().valueAt(T7)).as("后代父值不变（仍是 u-3）").contains(U3);
    assertThat(state.units().get(U3).parent().segments()).as("root 的 parent 追加了一段").hasSize(2);
    assertThat(state.units().get(U4).parent().segments())
        .as("★ 后代也追加了一段（只改 root 的实现会在这里红）")
        .hasSize(2);
  }

  /** ★ 判据 #7（稀疏路点）：非相邻 waypoints 展开成**逐格** path。 */
  @Test
  void planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath() {
    // u-1@(1,1)；waypoints [(1,1),(1,3)] 非相邻 ⇒ 展开成 [(1,1),(1,2),(1,3)]。
    assertThat(
            submit(
                "unit.PlanSparseRoute",
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}",
                1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 2)));
    assertThat(state.units().get(U1).movement()).isPresent();
    List<HexCoord> path = state.units().get(U1).movement().orElseThrow().route().path();
    assertThat(path).as("稀疏展开成逐格简单路径（长度 3，非 2）").containsExactly(H11, H12, H13);
    assertThat(state.units().get(U1).movement().orElseThrow().route().waypoints())
        .as("waypoints 保持稀疏（首尾两格）")
        .containsExactly(H11, H13);
  }

  /** ★ 判据 #8（回归随动）：目标移动后，回归行程的终点指**目标当前**位置，不冻结在旧 hex。 */
  @Test
  void rejoinEndpointFollowsTheTargetsCurrentPosition() {
    assertThat(submit("unit.SetRejoinTarget", "{\"id\":\"u-1\",\"target\":\"u-2\"}", 1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));
    // 目标 u-2 从 (1,2) 移到 (1,3)。
    assertThat(submit("unit.PlaceAt", "{\"id\":\"u-2\",\"hex\":{\"q\":1,\"r\":3}}", 2))
        .isEqualTo(new CommandResult.Committed(ref("main", 3)));
    // 推进一步：participant 第二趟现算回归路线。
    assertThat(
            shell
                .coreSimos()
                .submit(
                    new AdvanceTime(
                        "cmd-advance",
                        "corr-advance",
                        INITIATOR,
                        main(),
                        new RevisionId(3),
                        new TimeRange(T7, Optional.of(T10)))))
        .isEqualTo(new CommandResult.Committed(ref("main", 4)));

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 4)));
    Unit u1 = state.units().get(U1);
    assertThat(u1.movement()).as("回归轨道装载了一条行程").isPresent();
    List<HexCoord> path = u1.movement().orElseThrow().route().path();
    assertThat(path).as("终点指目标的**当前**格 (1,3)，不是旧格 (1,2)").containsExactly(H11, H12, H13);
    assertThat(path.get(path.size() - 1)).as("★ 终点 = 目标当前格（冻结旧 hex 的实现会指 H12）").isEqualTo(H13);
    assertThat(u1.position().valueAt(T10)).as("回归不瞬移：只写 movement，不碰 position").contains(H11);
  }

  /** ★ 判据 #12 + #15（战损 delta / 时间线恢复）：`100 + (−30) = 70`，且回退到战损前 revision 取回战前值。 */
  @Test
  void casualtiesSubtractIncrementallyAndRollBackToThePreBattleValue() {
    assertThat(
            submit(
                "unit.ApplyCasualties",
                "{\"id\":\"u-1\",\"personnel\":-30,\"equipment\":{\"步枪\":-10}}",
                1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));

    Unit after = unitSlice(shell.coreSimos().replay(ref("main", 2))).units().get(U1);
    assertThat(after.member()).as("★ 100 + (−30) = 70（把 Δ 当绝对值会得 30）").isEqualTo(70);
    assertThat(after.equipment())
        .as("装备双轨：提及键扣 10、未提及键原样")
        .containsExactlyInAnyOrderEntriesOf(Map.of("步枪", 40, "炮", 4));

    Unit before = unitSlice(shell.coreSimos().replay(ref("main", 1))).units().get(U1);
    assertThat(before.member()).as("★ 回退到战损前 ⇒ 100（历史若被覆写，这里读到 70）").isEqualTo(100);
    assertThat(before.equipment()).containsExactlyInAnyOrderEntriesOf(Map.of("步枪", 50, "炮", 4));
  }

  // ────────────────────────────── 助手与夹具 ──────────────────────────────

  private CommandResult submit(String type, String payloadJson, long expectedRevision) {
    return shell
        .coreSimos()
        .submit(
            new CommandEnvelope(
                "cmd-" + type + "-" + expectedRevision,
                "corr-" + type + "-" + expectedRevision,
                INITIATOR,
                main(),
                new RevisionId(expectedRevision),
                type,
                payloadJson));
  }

  private static UnitState unitSlice(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state();
  }

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units =
        new UnitState(
            new LinkedHashMap<>(
                Map.of(
                    U1, genesisUnit(U1, "第一连", H11, Optional.empty()),
                    U2, genesisUnit(U2, "第二连", H12, Optional.empty()),
                    U3, genesisUnit(U3, "第三连", H11, Optional.of(U2)),
                    U4, genesisUnit(U4, "第四连", H13, Optional.of(U3)))));
    SocialData social = new SocialData(new LinkedHashMap<>(), Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 创世单位（canonical 13 参）：MOVING、attached=true、无偏移、无回归意图；u-1 带"步枪/炮"两键（装备双轨判据的载体）。 */
  private static Unit genesisUnit(
      UnitId id, String name, HexCoord position, Optional<UnitId> parent) {
    Map<String, Integer> equipment = id.equals(U1) ? Map.of("步枪", 50, "炮", 4) : Map.of("步枪", 50);
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent)), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        100,
        equipment,
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty());
  }

  private static GameMap corridorMap() {
    TerrainType plains = TerrainCatalog.of("plains");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(plains.key(), plains);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), plains.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private Path dbFile() {
    return tempDir.resolve(io.mosire.simos.core.CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
