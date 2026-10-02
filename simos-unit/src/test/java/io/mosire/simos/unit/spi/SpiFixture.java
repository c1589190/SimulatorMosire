package io.mosire.simos.unit.spi;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * spi 包两个测试共用的夹具：三格直线走廊 {@code [1,1]→[1,2]→[1,3]} + 一个单位（{@code u-1}，"第一连"，位于 {@code [1,1]}）。
 * 成本不在夹具里钉死——{@code MovementCost} 是装配注入的，用例各给各的（形态 4 的"原样转交"正要靠它）。
 */
final class SpiFixture {

  static final SimosTimestamp T0 = SimosTimestamp.of(0);
  static final HexCoord H11 = new HexCoord(1, 1);
  static final HexCoord H12 = new HexCoord(1, 2);
  static final HexCoord H13 = new HexCoord(1, 3);
  static final UnitId U1 = new UnitId("u-1");
  static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  /** GameMap 没有 id（挂起项），mapId 由装配提供——测试里装配说它叫 Map1。 */
  static final String MAP_ID = "Map1";

  private SpiFixture() {}

  /** 走廊路线：waypoints 首尾、path 逐格相邻（构造期校验的形态）。 */
  static Route corridor() {
    return new Route(List.of(H11, H13), List.of(H11, H12, H13));
  }

  /** T0 出发、speed = 2 **MP/小时**、mobility ‰500 的在途行程（日制：一天预算 = 2×1000×24 = 48000 毫 MP）。 */
  static Movement inFlight() {
    return new Movement(corridor(), T0, 2, 500);
  }

  static Unit unitWithMovement(Optional<Movement> movement) {
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
        movement);
  }

  static UnitState unitState(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  static GameMap map() {
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

  static SimulationState state(GameMap map, UnitState unitState) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of("unit", new UnitSnapshot(REF, T0, unitState), "map", new MapSnapshot(REF, T0, map)),
        InMemoryInfoSystem.empty());
  }

  /** 只装一个切片的残缺世界：装配故障用例的输入。 */
  static SimulationState singleModuleState(String namespace, Snapshot snapshot) {
    return new SimulationState(
        new StateMeta(REF, T0), Map.of(namespace, snapshot), InMemoryInfoSystem.empty());
  }
}
