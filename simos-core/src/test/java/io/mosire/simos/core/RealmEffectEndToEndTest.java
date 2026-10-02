package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.AdvanceTime;
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
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.MovementState;
import io.mosire.simos.unit.move.MovementStatus;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
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
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 判据四 / R16 的**端到端**验收（计划 Task 16 Step 3）：一个由真 {@link CoreSimos} 装配的世界里，单位带着在途 {@link
 * Movement}，经过一次真的 {@link AdvanceTime} 推进后——**位置真的变了、{@code movement} 真的清了，且该状态能从新 revision 重放出来**。
 *
 * <p>★★ **与 {@code CoreSimosTest} / {@code BranchingEndToEndTest} 的分工**（不重复它们）：那两个类用的全是替身 codec /
 * handler / participant（Core 的装配护栏与分岔机制面）。本类**向上扩一层**：领域侧用**真的** {@link MapCodec} + {@link
 * UnitCodec} + {@link UnitTimeParticipant}（Task 16 Step 1/2 已落地），推进走真 {@code TimeAdvance}， 状态经真
 * {@code Replay} 从真 checkpoint + 真 revision 行重建。
 *
 * <p>★ **判据四的①是逐值断言，不是"没报错"**：`position` 真的到了 {@code [1,3]}`、`movement` 真的是 {@code
 * empty}。而**前提自证**（形态 1）写在推进之前——创世重放出来的单位**本来就有**在途 {@code movement}、位置在 {@code [1,1]}；否则"movement
 * 清了"是一句空话。
 *
 * <p>★ **判据四的②是"对拍"**：重放出的 {@code (main,2)} 必须等于一个**完全不经 DB、不经被测参与者**独立重建的期望状态。 差分走 **M3 的纯函数
 * {@link UnitMoves#evaluate} + spec §9.1 的抵达规则**（手工追加 {@code position} 段、 清空 {@code
 * movement}）——**不用 {@code UnitTimeParticipant} 自己**，否则"期望"与"被测"同源，等于自证。
 *
 * <p>★ 夹具算术与 {@code simos-unit} 的 {@code SpiFixture} 同口径（**不能** import 领域模块的测试类）： 走廊 {@code
 * [1,1]→[1,2]→[1,3]}；单位 {@code u-1} 在 {@code [1,1]} 出发、{@code speedAtDeparture=3}、{@code
 * mobility=500}；每段成本 1500 毫 MP。推进 {@code T0 → T0+1}（**恰好一天**，日制裁定）⇒ 预算 3000 付清两段（3000）⇒ **ARRIVED 在
 * {@code [1,3]}**（×24 的日预算落地后只会更宽，结论不变）。
 *
 * <p>★ 夹具与 {@code ReplayTest}/{@code CoreSimosTest} 同法：先独立打开 store 种创世行 {@code (main,1)} + 写创世
 * checkpoint（含 {@code map} 与 {@code unit} 两个切片），关掉它，**然后**才构造 {@link CoreSimos}。
 */
class RealmEffectEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  /** 推进一天后的时刻（日制裁定：一次 AdvanceTime 恰好一天，多日再不许一次提交）。 */
  private static final SimosTimestamp T1 = T0.plus(1);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");

  /** {@code GameMap} 没有 id（M2/M3 挂起项），mapId 由装配提供——本世界说它叫 {@code Map1}。 */
  private static final String MAP_ID = "Map1";

  /** 走廊每段的固定成本（毫 MP）。预算 = {@code speedAtDeparture × 1000 × Δ刻} = 3000·Δ（speed=3）；日制 ×24 后只会更宽。 */
  private static final long EDGE_MILLIS = 1500L;

  /** 推进的 correlationId：判据二的链路 key（本类不复用判据二，只用它定位提案事件）。 */
  private static final String CORR = "corr-advance";

  /** C19 的周期 N：取 4 ⇒ {@code (main,2)} 不命中（2 % 4 != 0），重放真的走"创世 checkpoint + 施加变更集"。 */
  private static final int CHECKPOINT_INTERVAL = 4;

  /** {@link CoreConfig#mapper()} 当前无消费者，但也不许传 null（与 {@code CoreSimosTest} 同口径）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  /**
   * 判据四 ①②：真参与者把单位推进到 {@code [1,3]} 并清空 {@code movement}，且 {@code (main,2)} 的重放结果等于独立重建的期望。
   *
   * <p>同时钉住三件事：① 单位切片带**新** ref/timestamp（C28）；② {@code map} 切片**原封不动**（重放是增量的——没被变更集提到的 模块不推进坐标）；③
   * 再重放一次结果相同（稳定）。
   */
  @Test
  void realAdvanceMovesUnitAndReplayRebuildsItFromTheNewRevision() {
    seedGenesis();

    SimulationState advanced;
    SimulationState expected;
    SimulationState genesis;
    try (CoreSimos core = wiredCore()) {
      // ── ★ 前提自证（形态 1）：推进前，世界里的单位真的有在途 movement、位置在 [1,1] ──────────────
      //    在"重放出的创世状态"上断言——不是在我自己构造的内存对象上（那证明不了 checkpoint 往返）。
      genesis = core.replay(ref("main", 1));
      Unit genesisUnit = unitOf(genesis);
      assertThat(genesisUnit.movement())
          .as("前提：创世单位真的有在途 Movement——否则「movement 清了」是空话")
          .contains(inFlight());
      assertThat(genesisUnit.position().valueAt(T0)).as("前提：创世位置是 [1,1]").contains(H11);

      // ── 一次真的推进（真 route + 真 store + 真 participant）────────────────────────────────
      CommandResult result = core.submit(advance(1L));

      assertThat(result)
          .as("推进应提交到 (main,2)")
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      // ── ① 逐值：位置真的变了、movement 真的清了 ──────────────────────────────────────────
      advanced = core.replay(ref("main", 2));
      Unit movedUnit = unitOf(advanced);
      assertThat(movedUnit.position().valueAt(T1))
          .as("① 已抵达 ⇒ position 段写入抵达点 [1,3]")
          .contains(H13);
      assertThat(movedUnit.movement()).as("① 已抵达 ⇒ movement 真的清了").isEmpty();
      // ★ 判别力对照：位置确实从 [1,1] 走到了别的格（不是"看起来动了"）
      assertThat(movedUnit.position().valueAt(T0)).as("① 起点段仍是 [1,1]（历史不改写）").contains(H11);

      // ── ② 差分对拍：独立重建期望状态（M3 纯函数 + spec §9.1 规则，不经 DB、不经被测参与者）──────
      GameMap genesisMap = mapOf(genesis);
      MovementState materialized = UnitMoves.evaluate(genesisUnit, T1, genesisMap, cost());
      assertThat(materialized.status())
          .as("差分前提：M3 纯函数判为 ARRIVED（否则期望状态建错，红点会离题）")
          .isEqualTo(MovementStatus.ARRIVED);
      assertThat(materialized.currentHex()).as("M3 纯函数给出的抵达点").isEqualTo(H13);
      assertThat(materialized.nextHex()).as("ARRIVED 不得带下一格").isEmpty();

      Unit expectedUnit = appendArrivalSegment(genesisUnit, T1, materialized.currentHex());
      UnitState expectedUnits = new UnitState(new LinkedHashMap<>(Map.of(U1, expectedUnit)));
      expected =
          new SimulationState(
              new StateMeta(ref("main", 2), T1),
              Map.of(
                  "map", new MapSnapshot(ref("main", 1), T0, genesisMap),
                  "unit", new UnitSnapshot(ref("main", 2), T1, expectedUnits)),
              InMemoryInfoSystem.empty());

      assertThat(advanced).as("② 重放结果必须等于独立重建的期望状态（推进结果与重放结果的 equals 对拍）").isEqualTo(expected);

      // ── C28：单位切片带新坐标；map 切片原封不动（重放的增量语义）────────────────────────────
      UnitSnapshot unitSlice = (UnitSnapshot) advanced.module("unit").orElseThrow();
      assertThat(unitSlice.ref()).as("单位切片带新 ref（C28）").isEqualTo(ref("main", 2));
      assertThat(unitSlice.timestamp()).as("单位切片带新时刻（C28）").isEqualTo(T1);

      MapSnapshot mapSlice = (MapSnapshot) advanced.module("map").orElseThrow();
      assertThat(mapSlice.ref()).as("map 没被任何提案改动 ⇒ 保留创世坐标（重放是增量的）").isEqualTo(ref("main", 1));
      assertThat(mapSlice.timestamp()).as("map 的模拟时刻不因推进而前进").isEqualTo(T0);
      assertThat(mapSlice).as("map 切片本身与创世一字不差").isEqualTo(genesis.module("map").orElseThrow());

      // ── ③ 再重放一次，结果稳定（checkpoint + 变更集重建是确定性的）────────────────────────────
      assertThat(core.replay(ref("main", 2))).as("再重放一次必须相等").isEqualTo(advanced);
    }
  }

  // ★★ 2026-09-30 用户裁定：{@code simos.module.proposal} 只留内存、不再落盘 ⇒
  //   原 "proposalEventCarriesTheRealParticipantsReadWriteSets"（R10 在真实参与者上的观测点）随之删除。
  //   reads/writes 仍在推进内存里供 TimeProposalResolver 判冲突，但事后无可观测面 ⇒
  //   该变异靶子（writes 恒空）在本文件里不再有咬点；要恢复观测需另立内存钩子。
  //   世界状态/重放/分支/冲突判定均不受影响（同文件其余用例继续覆盖）。

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 装配一台真门面：真 map/unit codec + 真 participant（每段 1500 毫 MP）。 */
  private CoreSimos wiredCore() {
    return new CoreSimos(new CoreConfig(tempDir, CHECKPOINT_INTERVAL, MAPPER))
        .register(new MapCodec())
        .register(new UnitCodec())
        .register(new UnitTimeParticipant(cost(), MAP_ID));
  }

  /**
   * 种创世：独立打开 store 落一行 {@code (main,1)}（parent 空、变更集空），再写**含 map 与 unit 两个切片**的创世 checkpoint。
   *
   * <p>★ map 切片虽不被推进改动，但必须在场：participant 要从 {@code state.module("map")} 取 {@link GameMap}（spec
   * §9.1），且重放解码 checkpoint 需要 map 的 codec。
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
                "unit", new UnitSnapshot(ref("main", 1), T0, units)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(genesis, List.of(new MapCodec(), new UnitCodec())));
  }

  /** 走廊路线：waypoints 首尾、path 逐格相邻（构造期校验的形态）。 */
  private static Route corridor() {
    return new Route(List.of(H11, H13), List.of(H11, H12, H13));
  }

  /** T0 出发、speed = 3 MP/刻、mobility ‰500 的在途行程（3×1000×1 ≥ 1500+1500 ⇒ 一天内抵达）。 */
  private static Movement inFlight() {
    return new Movement(corridor(), T0, 3, 500);
  }

  /** 一个有在途行程的单位（其余字段照 {@code SpiFixture}）。 */
  private static Unit unitWithMovement(Movement movement) {
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
        Optional.of(movement));
  }

  /**
   * spec §9.1 的抵达规则（**独立于被测参与者**）：往 {@code position} 追加一条 {@code from = at} 的段，清空 {@code
   * movement}，其余字段原样带过。这一段是本类差分的核心——只用 M3 纯函数算抵达点，重建规则照 spec 手写。
   */
  private static Unit appendArrivalSegment(Unit base, SimosTimestamp at, HexCoord arrival) {
    List<Segment<Optional<HexCoord>>> segments = new ArrayList<>(base.position().segments());
    segments.add(new Segment<>(at, Optional.of(arrival)));
    SegmentedSeries<Optional<HexCoord>> position =
        new SegmentedSeries<>(segments, base.position().events(), base.position().addition());
    return new Unit(
        base.id(),
        base.name(),
        base.parent(),
        position,
        base.manpower(),
        base.equipment(),
        base.speed(),
        base.mobilityPerMille(),
        Optional.empty());
  }

  /** 三格直线走廊（照 {@code SpiFixture} 的地形与种子，使 MapCodec 往返无歧义）。 */
  private static GameMap corridorMap() {
    TerrainType flat = new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(flat.key(), flat);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), flat.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 每段 1500 毫 MP 的固定成本（{@code minStepCostMillis} 与推进无关，给一个合法下界即可）。 */
  private static MovementCost cost() {
    return new FixedEdgeCost(EDGE_MILLIS);
  }

  private static final class FixedEdgeCost implements MovementCost {

    private final long millis;

    FixedEdgeCost(long millis) {
      this.millis = millis;
    }

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return OptionalLong.of(millis);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  private static AdvanceTime advance(long expectedRevision) {
    return new AdvanceTime(
        "cmd-advance",
        CORR,
        "player:local",
        main(),
        new RevisionId(expectedRevision),
        new TimeRange(T0, Optional.of(T1)));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 取状态里 {@code u-1} 的单位（单位切片必须存在，否则是夹具故障）。 */
  private static Unit unitOf(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state().units().get(U1);
  }

  private static GameMap mapOf(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow(() -> new AssertionError("状态里没有 map 切片")))
        .map();
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
