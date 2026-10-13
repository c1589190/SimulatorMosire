package io.mosire.simos.army.spi;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
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
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 急停协议（2026-10-13）army 侧判据的共享夹具：一条 {@code q} 轴走廊 + 每天恰走一格的军。
 *
 * <p>★★ <b>为什么必须"每天恰走一格"</b>：{@code ArmyTimeParticipant} 的判据是<b>天</b>粒度的，而本仓的移动模型里 一天能走很多格（日预算 =
 * {@code speed × 1000 × 24 × Δtick}，单格成本 = {@code moveCost × mobilityPerMille}）—— 用真 {@code
 * TerrainMovementCost} 造不出"第 5 天在某格、第 6 天离开"的形态（走一格就出图了）。 故这里<b>注入</b>一份成本替身： {@code speed=1} ⇒ 日预算
 * 24000 毫 MP，单格成本也 24000 ⇒ <b>每天恰一格</b>，于是"第 N 天在哪"完全可算。
 *
 * <p>★ 这与 {@code ArmyTimeParticipant} 的装配口径一致：{@code MovementCost} 本来就是构造期注入的（app 传 {@code
 * TerrainMovementCost.INSTANCE}，测试可以传替身）。
 */
final class EmergencyStopFixture {

  static final SimosTimestamp T0 = SimosTimestamp.of(0);
  static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private EmergencyStopFixture() {}

  // ── I5 判据面（无世界状态）────────────────────────────────────────────────────────

  /** 军队记录：{@code contactWatch} 是 GM 的注册位（四参兼容构造器 = false）。 */
  static Army army(String armyId, String rootUnitId, boolean contactWatch) {
    return new Army(
        new ArmyId(armyId), Optional.empty(), new UnitId(rootUnitId), "军 " + armyId, contactWatch);
  }

  /** 保序的军队表（判据内部按 id 字典序重排 ⇒ 结果与插入序无关，本夹具故意可乱序）。 */
  static Map<ArmyId, Army> armies(Army... values) {
    Map<ArmyId, Army> byId = new LinkedHashMap<>();
    for (Army value : values) {
      byId.put(value.id(), value);
    }
    return byId;
  }

  /** 保序的位置表：若个军都在同一格。 */
  static Map<ArmyId, HexCoord> at(HexCoord hex, ArmyId... ids) {
    Map<ArmyId, HexCoord> byId = new LinkedHashMap<>();
    for (ArmyId id : ids) {
      byId.put(id, hex);
    }
    return byId;
  }

  // ── 推进面（真 SimulationState）────────────────────────────────────────────────────

  /** 每天恰走一格的成本替身（日预算 = 1 × 1000 × 24 = 24000 毫 MP）。 */
  static MovementCost oneHexPerDay() {
    return new MovementCost() {
      @Override
      public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
        return OptionalLong.of(24000L);
      }

      @Override
      public long minStepCostMillis(Unit unit, GameMap map) {
        return 1;
      }
    };
  }

  /** {@code q} 轴走廊 {@code (0,0) … (maxQ,0)}（r 恒 0）。 */
  static GameMap corridor(int maxQ) {
    TerrainType flat = new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "急停夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (int q = 0; q <= maxQ; q++) {
      hexes.put(new HexCoord(q, 0), new HexCell(0.5));
    }
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

  /**
   * 沿 {@code q} 轴从 {@code (fromQ,0)} 走到 {@code (toQ,0)} 的单位：{@code speed=1}、{@code mobility=1000}、
   * 出发时刻 {@code T0} ⇒ 第 {@code d} 天恰在 {@code q = fromQ ± d}。
   */
  static Unit mover(String unitId, int fromQ, int toQ) {
    if (fromQ == toQ) {
      throw new IllegalArgumentException("夹具要求真的在走（fromQ != toQ）");
    }
    List<HexCoord> path = new ArrayList<>();
    int step = toQ > fromQ ? 1 : -1;
    for (int q = fromQ; ; q += step) {
      path.add(new HexCoord(q, 0));
      if (q == toQ) {
        break;
      }
    }
    Movement movement =
        new Movement(
            new Route(List.of(new HexCoord(fromQ, 0), new HexCoord(toQ, 0)), path), T0, 1, 1000);
    return new Unit(
        new UnitId(unitId),
        "单位 " + unitId,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.of(new HexCoord(fromQ, 0)))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        1,
        1000,
        Optional.of(movement));
  }

  /** 静止单位：无在途行程 ⇒ 判据走 {@code position} 时态序列那条路（{@code UnitState.effectivePosition}）。 */
  static Unit still(String unitId, int q) {
    return new Unit(
        new UnitId(unitId),
        "单位 " + unitId,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.of(new HexCoord(q, 0)))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        1,
        1000,
        Optional.empty());
  }

  static UnitState units(Unit... values) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit value : values) {
      byId.put(value.id(), value);
    }
    return new UnitState(byId);
  }

  static ArmyData data(Army... values) {
    Map<ArmyId, Army> byId = new LinkedHashMap<>();
    for (Army value : values) {
      byId.put(value.id(), value);
    }
    return new ArmyData(byId, Map.of(), Map.of());
  }

  /** 三个切片齐全的真 {@link SimulationState}（army + unit + map，与 Shell 装配同形）。 */
  static SimulationState world(GameMap map, ArmyData armies, UnitState units, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("army", new ArmySnapshot(REF, at, armies));
    modules.put("unit", new UnitSnapshot(REF, at, units));
    modules.put("map", new MapSnapshot(REF, at, map));
    return worldOf(modules, tick);
  }

  /** 任意切片组合的世界：装配故障用例要能把"缺哪个切片"单独拧出来（缺一个、缺两个各一条判据）。 */
  static SimulationState worldOf(Map<String, Snapshot> modules, long tick) {
    return new SimulationState(
        new StateMeta(REF, SimosTimestamp.of(tick)), modules, InMemoryInfoSystem.empty());
  }

  static Snapshot armySlice(ArmyData armies, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new ArmySnapshot(REF, at, armies);
  }

  static Snapshot unitSlice(UnitState units, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new UnitSnapshot(REF, at, units);
  }

  static Snapshot mapSlice(GameMap map, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new MapSnapshot(REF, at, map);
  }

  /** 只装一个切片的残缺世界：装配故障用例的输入。 */
  static SimulationState singleModuleState(String namespace, Snapshot snapshot, long tick) {
    return worldOf(Map.of(namespace, snapshot), tick);
  }
}
