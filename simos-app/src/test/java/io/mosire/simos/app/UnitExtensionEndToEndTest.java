package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.gui.ApiViews;
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

  /** ★ 日制裁定：一次推进恰好一天 ⇒ 回归用例只推 T7 → T8（旧口径的 T10 多日区间已不合法）。 */
  private static final SimosTimestamp T8 = SimosTimestamp.of(8);

  private static final SimosTimestamp T30 = SimosTimestamp.of(30);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final UnitId U3 = new UnitId("u-3");
  private static final UnitId U4 = new UnitId("u-4");
  private static final UnitId U5 = new UnitId("u-5");

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

    // ★ 编制 v2：合体把 child 的**整支**带回来 ⇒ 整支都要同格。u-3 的下属 u-4 在 (1,3) ⇒ 先把它挪到 (1,1)。
    assertThat(submit("unit.PlaceAt", "{\"id\":\"u-4\",\"hex\":{\"q\":1,\"r\":1}}", 1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));

    // u-3@(1,1) 与 u-1@(1,1) 同格 + MOVING ⇒ 过。
    assertThat(submit("unit.MergeFormation", "{\"childId\":\"u-3\",\"parentId\":\"u-1\"}", 2))
        .isEqualTo(new CommandResult.Committed(ref("main", 3)));
    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 3)));
    assertThat(state.units().get(U3).parent().valueAt(T7)).contains(U1);
    assertThat(state.units().get(U3).attached().valueAt(T7)).as("合体即 attach").isTrue();
  }

  /**
   * ★★ 判据（**编制 v2 的核心，真装配 + 真 {@code AdvanceTime}**）：**顶层一动，整支一起到同一格**。
   *
   * <p>装置：`u-1`（顶层，`H11`）带成员 `u-5`（同格、`attached=true`）。给 `u-1` 一条 `H11 → H12 → H13` 的路线， 推进到 `T30`
   * ⇒ 顶层抵达 `H13`，**成员 `u-5` 也被搬到 `H13`**（它自己的 `position` 段一起更新），且成员**没有自己的行程**。
   *
   * <p>★ 这条取代了旧的"跟随"用例：旧语义靠 `effectivePosition` 向父取位（成员始终无自身位置）；v2 取消跟随，
   * 改为推进时**显式**把整支搬到同一格——所以这里断言的是**成员自己的位置**变了，而不是某个查询的副作用。
   *
   * <p>★ 判别力：把推进器里的"整支一起搬"那段去掉 ⇒ 成员的 `position` 停在 `H11` ⇒ 本条红。
   */
  @Test
  void advancingTheTopCarriesTheWholeFormationToTheSameHex() {
    UnitState genesis = unitSlice(shell.coreSimos().replay(ref("main", 1)));
    assertThat(genesis.units().get(U5).position().valueAt(T7)).as("创世：u-5 与 u-1 同格").contains(H11);
    assertThat(genesis.units().get(U5).attached().valueAt(T7)).as("u-5 是 u-1 那一支的成员").isTrue();
    assertThat(genesis.formationRoot(U5, T7)).contains(U1);

    // u-1 沿走廊走：H11 → H12 → H13。
    assertThat(
            submit(
                "unit.PlanRoute",
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}",
                1))
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));

    // ★ 日制裁定：一次推进恰好一天 ⇒ T7 → T30 要连续提交 23 次单日推进（revision 2 → 25，时间戳逐个 +1）。
    long advanced = advanceDays(2, T7.tick(), 23);

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", advanced)));
    assertThat(state.effectivePosition(U1, T30)).as("顶层已抵达 H13").contains(H13);
    assertThat(state.effectivePosition(U5, T30)).as("★ 整支一起到同一格：成员也被搬到 H13").contains(H13);
    assertThat(state.units().get(U5).movement()).as("★ 被带着走的成员没有自己的行程（它不自己走）").isEmpty();
    assertThat(state.units().get(U5).attached().valueAt(T30)).as("它仍是那一支的成员").isTrue();

    // ★★ 读口（GUI 与 MCP 同源的那一份）：**成员那一行报的"整支"是从顶层量的**，不是它自己的子树。
    //   否则"轻骑兵"会显示成一支 1 个单位的编队——读的人（模型/界面）会据此误判。
    SimulationState simAtT30 = shell.coreSimos().replay(ref("main", advanced));
    Map<String, Object> memberView =
        ApiViews.unit(state.units().get(U5), state, T30, ApiViews.gameMap(simAtT30));
    assertThat(memberView.get("formationRootId")).isEqualTo("u-1");
    assertThat(memberView.get("formationSize")).as("从顶层量 ⇒ 2（u-1 与 u-5），不是 u-5 自己的 1").isEqualTo(2);
    assertThat(memberView.get("attached")).isEqualTo(true);
  }

  /**
   * ★★ 判据（**成员不许自己走**，真装配 + 命令面）：给成员 `u-5` 下路线 ⇒ 被拒，理由**点名顶层**并给出两条出路。
   *
   * <p>★ 判别力：去掉 `requireTopOfFormation` 闸门 ⇒ 这条命令会被提交、head 前进 ⇒ 本用例红。
   */
  @Test
  void planningARouteForAMemberIsRejectedWithTheTopNamed() {
    CommandResult rejected =
        submit(
            "unit.PlanRoute",
            "{\"id\":\"u-5\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}",
            1);

    assertThat(rejected).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) rejected).reason())
        .as("理由点名顶层 + 指路（拆分/脱离）")
        .contains("顶层")
        .contains("u-1")
        .contains("unit.SplitFormation");
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("被拒 ⇒ 不留 revision")
        .isEqualTo(1L);
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
    // 推进一步（★ 日制裁定：一次推进恰好一天，T7 → T8）：participant 第二趟现算回归路线。
    //   ★ 只推一天是**判据本身的要求**：回归路线在**本刻**装载（departedAt = 推进终点），若连续推多日，第 2 天就会真的
    //     走完 H11→H13 而把 position 落到 H13——"回归不瞬移"这条只能在装载它的那一刻断言。
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
                        new TimeRange(T7, Optional.of(T8)))))
        .isEqualTo(new CommandResult.Committed(ref("main", 4)));

    UnitState state = unitSlice(shell.coreSimos().replay(ref("main", 4)));
    Unit u1 = state.units().get(U1);
    assertThat(u1.movement()).as("回归轨道装载了一条行程").isPresent();
    List<HexCoord> path = u1.movement().orElseThrow().route().path();
    assertThat(path).as("终点指目标的**当前**格 (1,3)，不是旧格 (1,2)").containsExactly(H11, H12, H13);
    assertThat(path.get(path.size() - 1)).as("★ 终点 = 目标当前格（冻结旧 hex 的实现会指 H12）").isEqualTo(H13);
    assertThat(u1.position().valueAt(T8)).as("回归不瞬移：只写 movement，不碰 position").contains(H11);
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

  /**
   * 快进 {@code days} 天（★ 日制裁定：一次推进**恰好一天** ⇒ "快进 N 天" = 连续提交 N 次单日推进，每次用上一次返回的 新 {@code
   * expectedRevision}；revision 号逐个 +1、时间戳逐个 +1）。返回推进后的 head。
   */
  private long advanceDays(long expectedRevision, long fromTick, int days) {
    long head = expectedRevision;
    for (int i = 0; i < days; i++) {
      long from = fromTick + i;
      CommandResult result =
          shell
              .coreSimos()
              .submit(
                  new AdvanceTime(
                      "cmd-advance-" + from,
                      "corr-advance-" + from,
                      INITIATOR,
                      main(),
                      new RevisionId(head),
                      new TimeRange(
                          SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(from + 1)))));
      assertThat(result)
          .as("推进第 %d 天（tick %d → %d）", i + 1, from, from + 1)
          .isEqualTo(new CommandResult.Committed(ref("main", head + 1)));
      head++;
    }
    return head;
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
                    U4, genesisUnit(U4, "第四连", H13, Optional.of(U3)),
                    // ★ 编制 v2：u-5 是 u-1 那一支的成员，带自己的位置（与 u-1 同格）——"整支一起搬"的载体。
                    U5, memberUnit(U5, "第五连（随行）", U1, H11))));
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

  /** ★ 跟随单位（本次改动）：**无自身位置**、`parent` 给定、`attached=true` ⇒ 有效位置永远取父的当前格（父动子随）。 */
  /**
   * ★ 编制 v2（2026-09-24）：u-5 是 u-1 那一支的**成员**，**带自己的位置**（与 u-1 同格 `H11`）。
   *
   * <p>★ 旧夹具让它"无自身位置 ⇒ 向父取位"（跟随）；跟随取消后那种单位**不在图上**，也就没法验"整支一起搬"， 故这里给它自己的位置。
   */
  private static Unit memberUnit(UnitId id, String name, UnitId parent, HexCoord position) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(parent))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        100,
        Map.of("步枪", 50),
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
