package io.mosire.simos.sd.testing;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A4~A6 的共享世界夹具：一个带**国家 tag 区域**与**未 tag 区域**的 map、一个根单位、三层切片。 */
public final class SdWorlds {

  public static final SimosTimestamp T0 = SimosTimestamp.of(0);

  public static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  public static final RegionId TAGGED_REGION = new RegionId("r1");

  public static final RegionId UNTAGGED_REGION = new RegionId("r2");

  public static final UnitId ROOT_UNIT = new UnitId("u-1");

  public static final HexCoord HEX = new HexCoord(0, 0);

  private SdWorlds() {}

  public static SimulationState world(SdState sd) {
    return world(sd, map(), units());
  }

  /**
   * **世界 tick 显式给定**的形态（2026-09-23 新增）。
   *
   * <p>★ 为什么需要它：{@code sd.IssueDirective} 的载荷自带 {@code tick}，而**令不得记在未来**是命令期硬校验 （{@code tick >
   * 世界当前 tick} ⇒ 拒）⇒ 想测"另一 tick 再出令"这类**合法**场景，夹具的世界就必须先走到那个 tick，
   * 否则测到的是另一条拒绝理由（而**断言照样可能过**——这正是它必须显式给的原因）。
   */
  public static SimulationState world(SdState sd, long tick) {
    return world(sd, map(), units(), tick);
  }

  public static SimulationState world(SdState sd, GameMap map, UnitState units) {
    return world(sd, map, units, 0L);
  }

  public static SimulationState world(SdState sd, GameMap map, UnitState units, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new SimulationState(
        new StateMeta(REF, at),
        Map.of(
            "sd", new SdSnapshot(REF, at, sd),
            "map", new MapSnapshot(REF, at, map),
            "unit", new UnitSnapshot(REF, at, units)),
        InMemoryInfoSystem.empty());
  }

  public static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(HEX, new HexCell(0.5));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        TAGGED_REGION,
        Region.of(
            TAGGED_REGION, "国家区域", Set.of(HEX), new RegionMeta(null, "nation:n1", null, null)));
    regions.put(
        UNTAGGED_REGION, Region.of(UNTAGGED_REGION, "无标签区域", Set.of(HEX), RegionMeta.empty()));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  public static UnitState units() {
    Unit unit =
        new Unit(
            ROOT_UNIT,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(HEX))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty());
    return new UnitState(new LinkedHashMap<>(Map.of(ROOT_UNIT, unit)));
  }

  /** 该区域应带的国家 tag 字面量（供断言的判别力自证）。 */
  public static String nationTagLiteral() {
    return NationTag.PREFIX + "n1";
  }
}
