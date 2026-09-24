package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ **判据二的冻结夹具**（M3 spec §4.5）：三格直线走廊，两条夹具地形（25 / 65）+ 一条不可通行（999）。
 *
 * <p>地形**不在** {@code TerrainCatalog} 里——夹具自建 {@code TerrainType}（spec §4.3 末段）： {@code
 * GameMap.terrainTypes} 本就是任意词表，判据因此与地形词表解耦。
 *
 * <p>★ {@code TerrainType} 的形参序以**源码**为准（key, name, color, minHeight, maxHeight, food, gold, stone,
 * moveCost, description）。
 */
final class MoveFixture {

  static final SimosTimestamp T0 = SimosTimestamp.of(0);
  static final HexCoord H11 = new HexCoord(1, 1);
  static final HexCoord H12 = new HexCoord(1, 2);
  static final HexCoord H13 = new HexCoord(1, 3);
  static final UnitId U_F82A = new UnitId("u-f82a");

  static final TerrainType FLAT_25 = terrain("flat25", 25);
  static final TerrainType STEEP_65 = terrain("steep65", 65);
  static final TerrainType IMPASSABLE_999 = terrain("impassable999", 999);

  private MoveFixture() {}

  private static TerrainType terrain(String key, int moveCost) {
    return new TerrainType(key, key, "#336699", 0.0, 1.0, 0, 0, 0, moveCost, "夹具地形");
  }

  /** {@code [1,3]} 的地形可换成 {@code IMPASSABLE_999}（"中途变不可通行"那条用例）。 */
  static GameMap map(TerrainType hex13Terrain) {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    terrain.put(H11, FLAT_25.key());
    terrain.put(H12, FLAT_25.key());
    terrain.put(H13, hex13Terrain.key());
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(FLAT_25.key(), FLAT_25);
    terrainTypes.put(STEEP_65.key(), STEEP_65);
    terrainTypes.put(IMPASSABLE_999.key(), IMPASSABLE_999);
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrain),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 三个格全是 {@code IMPASSABLE_999}：判"**无可通行格 ⇒ 下界返回 0**"（spec §4.3 第 6 条）的输入。 */
  static GameMap allImpassableMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(FLAT_25.key(), FLAT_25);
    terrainTypes.put(STEEP_65.key(), STEEP_65);
    terrainTypes.put(IMPASSABLE_999.key(), IMPASSABLE_999);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), IMPASSABLE_999.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /**
   * 判据二的单位：speed = 2 **MP/小时**、mobility ‰500、位置 {@code [1,1]}、无在途路线。
   *
   * <p>★ 日制（2026-09-24 裁定：1 tick = 1 天）：速度单位是 MP/小时，**一天预算 = speed × 1000 × 24 = 48000 毫 MP**
   * （本夹具的速度仅被路径/成本用例用作机动性载体；行走节奏的用例在 {@code UnitMovesTest} 里自取速度）。
   */
  static Unit unit() {
    return new Unit(
        U_F82A,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }
}
