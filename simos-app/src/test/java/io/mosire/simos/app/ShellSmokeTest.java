package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
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
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Shell} 的冒烟验收（计划 T1 Step 5）。
 *
 * <p>★ **四件事各一条**，覆盖 T1 交付的装配门面：
 *
 * <ol>
 *   <li>{@link #renameThroughTheShellCommitsAndReplays}：真 {@code MapCodec}/{@code
 *       SocialCodec}/{@code UnitCodec} + 真 {@code RenameUnitHandler} 经 {@link Shell} 的 {@link
 *       CoreSimos} 提交一条 {@code unit.RenameUnit} ⇒ {@code Committed}，且重放出的**名字逐字等于载荷**（不是"没报错"）。
 *   <li>{@link #advanceThroughTheShellMovesTheUnitAndReplays}：真 {@code UnitTimeParticipant}（注入
 *       {@code TerrainMovementCost}）把带在途移动的单位从 {@code [1,1]} 推到 {@code [1,3]}（走廊每段 1500 毫 MP；日制裁定
 *       下一天预算 = {@code speed × 1000 × 24 × Δ天} = {@code 2 × 1000 × 24 = 48000} 毫 MP，付清两段共 3000） ⇒
 *       {@code Committed}，位置与 {@code movement} **逐值断言**。
 *   <li>{@link #branchesAndHeadAreUsableThroughTheShell}：{@code branches()/head()} 经壳可用（T2 的只读面接上）。
 *   <li>{@link #closeReleasesTheStoreAndIsIdempotent}：{@code close()} 幂等、库文件在位。
 * </ol>
 *
 * <p>★ **夹具与 M4 的 {@code RealmEffectEndToEndTest} 同法**：先**独立**打开 {@code SqliteStore} 种创世行 {@code
 * (main,1)} + 写含 {@code map}/{@code unit} 两切片的创世 checkpoint（用真 codec），关掉它，**然后**才起 {@link Shell}。
 * 唯一的领域差异是**地形**：M4 那个用例注入了固定成本的替身 {@code MovementCost}，而壳注入了真的 {@code TerrainMovementCost} ⇒ 走廊用
 * {@link TerrainCatalog} 的 {@code desert}（{@code moveCost = 3}），配 {@code mobilityPerMille = 500}
 * 恰好得到每段 {@code scale(3×1000, 500) = 1500} 毫 MP。
 */
class ShellSmokeTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  /** ★ 日制裁定：一次 {@code AdvanceTime} 恰好一天 ⇒ 终点只能是 {@code T0 + 1}（旧口径的 {@code T2} 多日区间已不合法）。 */
  private static final SimosTimestamp T1 = T0.plus(1);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");

  /** C19 的周期 N：取 100 ⇒ {@code (main,2)} 不命中，重放真的走"创世 checkpoint + 施加变更集"。 */
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final String INITIATOR = "player:test";

  @TempDir Path tempDir;

  // ── (a) 改名全链 ────────────────────────────────────────────────────────────────────

  @Test
  void renameThroughTheShellCommitsAndReplays() {
    seedGenesis();
    try (Shell shell = Shell.start(shellConfig())) {
      assertThat(shell.registeredModuleCount())
          .as(
              "壳应注册 map/social/unit/sd/economy/actor/gov 七个 codec"
                  + "（R2a 起第 5 个是 EconomyCodec，S1 阶段 2 起第 6 个是 ActorCodec，"
                  + "阶段 10a 起第 7 个是 gov.codec.GovCodec）")
          .isEqualTo(7);

      CommandResult result =
          shell.coreSimos().submit(rename(1L, "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}"));

      assertThat(result)
          .as("改名应提交到 (main,2)")
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      Unit renamed = unitOf(shell.coreSimos().replay(ref("main", 2)));
      assertThat(renamed.name()).as("重放出的名字逐字等于载荷").isEqualTo("改名后的第一连");
      assertThat(renamed.position().valueAt(T0)).as("改名不动位置（仍在 [1,1]）").contains(H11);
      assertThat(renamed.movement()).as("改名不动在途行程").contains(inFlight());

      // ★ 判别力对照：创世那一份名字没被改写（历史不改写）
      assertThat(unitOf(shell.coreSimos().replay(ref("main", 1))).name())
          .as("起点段仍是原名")
          .isEqualTo("第一连");
    }
  }

  // ── (b) 推进全链 ────────────────────────────────────────────────────────────────────

  @Test
  void advanceThroughTheShellMovesTheUnitAndReplays() {
    seedGenesis();
    try (Shell shell = Shell.start(shellConfig())) {
      // ★ 前提自证（形态 1）：在**重放出的**创世状态上，单位真的有在途 Movement、位置在 [1,1]
      Unit genesis = unitOf(shell.coreSimos().replay(ref("main", 1)));
      assertThat(genesis.movement())
          .as("前提：创世单位真的有在途 Movement——否则「movement 清了」是空话")
          .contains(inFlight());
      assertThat(genesis.position().valueAt(T0)).as("前提：创世位置是 [1,1]").contains(H11);

      CommandResult result = shell.coreSimos().submit(advance(1L));
      assertThat(result)
          .as("推进应提交到 (main,2)")
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      Unit advanced = unitOf(shell.coreSimos().replay(ref("main", 2)));
      assertThat(advanced.position().valueAt(T1))
          .as("① 已抵达 ⇒ position 段写入抵达点 [1,3]（日制一天预算 2×1000×24 = 48000，付清两段 1500+1500 = 3000）")
          .contains(H13);
      assertThat(advanced.movement()).as("① 已抵达 ⇒ movement 真的清了").isEmpty();
      assertThat(advanced.position().valueAt(T0)).as("① 起点段仍是 [1,1]（历史不改写）").contains(H11);
    }
  }

  // ── (c) 只读分支面经壳可用 ───────────────────────────────────────────────────────────

  @Test
  void branchesAndHeadAreUsableThroughTheShell() {
    seedGenesis();
    try (Shell shell = Shell.start(shellConfig())) {
      assertThat(shell.coreSimos().branches()).as("创世后应看到 main").containsExactly(main());
      assertThat(shell.coreSimos().head(main())).contains(new RevisionId(1));
      assertThat(shell.coreSimos().head(new BranchId("nope"))).as("不存在的分支 ⇒ 空").isEmpty();
    }
  }

  // ── (d) 关闭幂等 ────────────────────────────────────────────────────────────────────

  @Test
  void closeReleasesTheStoreAndIsIdempotent() {
    seedGenesis();
    Shell shell = Shell.start(shellConfig());
    shell.close();

    assertThat(Files.exists(dbFile())).as("close() 之后库文件仍在磁盘上").isTrue();

    // 第二次 close 不得炸（SqliteStore.close 幂等，CoreSimos.close 只是转交）
    shell.close();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 端口全 0（随机端口；T1 尚未起监听，此处置 0 也是"支持 port=0"的显式表达）。 */
  private ShellConfig shellConfig() {
    return ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
  }

  /**
   * 种创世：独立打开 store 落一行 {@code (main,1)}（parent 空、变更集空），再写**含 map 与 unit 两个切片**的创世 checkpoint。
   *
   * <p>★ map 切片必须在场：真 {@code UnitTimeParticipant} 要从 {@code state.module("map")} 取 {@link GameMap}
   * 交给 {@code TerrainMovementCost}；且重放解码需要 map 的 codec。
   */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
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
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unitWithMovement(inFlight()))));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T0),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T0, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T0, units),
                "sd", new SdSnapshot(ref("main", 1), T0, SdState.empty()),
                // ★ R3a：日推进要求 economy 切片在场（§6.6）；本夹具未播种（meta 空）⇒ 参与者交不变提案。
                "economy", new EconomySnapshot(ref("main", 1), T0, EconomyData.empty()),
                // ★ T5：actor 切片也必须在场（产权落账口要求它 —— 缺席 ⇒ 协调器当场抛）。
                "actor", new ActorSnapshot(ref("main", 1), T0, ActorData.empty()),
                // ★ R1：social 也成了时间参与者（T6）⇒ 它同样要求切片在场（缺席是装配故障，不是"无事"）。
                "social", new SocialSnapshot(ref("main", 1), T0, SocialData.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec(),
                    new SocialCodec(),
                    new ActorCodec())));
  }

  /** 三格直线走廊，地形取 {@code desert}（{@code moveCost = 3}）⇒ 配合 mobility ‰500 每段恰 1500 毫 MP。 */
  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 走廊路线：waypoints 首尾、path 逐格相邻。 */
  private static Route corridor() {
    return new Route(List.of(H11, H13), List.of(H11, H12, H13));
  }

  /** T0 出发、speed = 2 MP/小时、mobility ‰500 的在途行程（日制：一天预算 = 2 × 1000 × 24 = 48000 毫 MP）。 */
  private static Movement inFlight() {
    return new Movement(corridor(), T0, 2, 500);
  }

  /** 一个有在途行程的单位（其余字段照 M3 的 {@code SpiFixture}）。 */
  private static Unit unitWithMovement(Movement movement) {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.of(movement));
  }

  private static CommandEnvelope rename(long expectedRevision, String payloadJson) {
    return new CommandEnvelope(
        "cmd-rename",
        "corr-rename",
        INITIATOR,
        main(),
        new RevisionId(expectedRevision),
        "unit.RenameUnit",
        payloadJson);
  }

  /** ★ 日制裁定：{@code to} 必须 = {@code from + 1}（一次推进恰好一天），故只推一天 T0 → T1。 */
  private static AdvanceTime advance(long expectedRevision) {
    return new AdvanceTime(
        "cmd-advance",
        "corr-advance",
        INITIATOR,
        main(),
        new RevisionId(expectedRevision),
        new TimeRange(T0, Optional.of(T1)));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static Unit unitOf(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state().units().get(U1);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
