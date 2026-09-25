package io.mosire.simos.app.render;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 渲染测试的合成世界：中心 (0,0) 的 7 格小地图（模拟器格 vs 海洋）、一个跨三格的区域、一个人口序列、一座城、一支军。
 *
 * <p>数值刻意取"可肉眼核对"的：地形色写死成三个常量，人口 1000/5000/250000 分属不同对数档。
 */
final class RenderFixtures {

  static final SimosTimestamp T0 = SimosTimestamp.of(0);
  static final HexCoord CENTER = new HexCoord(0, 0);
  static final HexCoord EAST = new HexCoord(1, 0);
  static final HexCoord SOUTH_EAST = new HexCoord(0, 1);

  static final String PLAINS_COLOR = "#7BA05B";
  static final String DESERT_COLOR = "#D9C27A";
  static final String OCEAN_COLOR = "#2E5C8A";
  static final String REGION_COLOR = "#FF0000";

  private RenderFixtures() {}

  /** 7 格地图：中心平原、东邻沙漠、其余海洋；区域"测试区"覆盖中心+东邻+东南邻。 */
  static GameMap smallMap() {
    List<HexCoord> coords = MapProjection.hexesWithin(CENTER, 1);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    for (HexCoord coord : coords) {
      hexes.put(coord, new HexCell(0.5));
      terrain.put(coord, "ocean");
    }
    terrain.put(CENTER, "plains");
    terrain.put(EAST, "desert");

    GameMap base =
        new GameMap(
            hexes,
            io.mosire.simos.map.block.TerrainBlocks.split(terrain),
            Map.of(),
            Map.of(),
            terrainTypes(),
            Map.of(),
            Map.of(),
            Map.of(),
            io.mosire.simos.map.generate.GenerationSpec.defaults(0L));
    // 区域用 Region 记录直建（RegionBoundary 允许空环：渲染不走边界环，只用归属）
    Region region =
        Region.of(
            new RegionId("r-test"),
            "测试区",
            Set.of(CENTER, EAST, SOUTH_EAST),
            new RegionMeta(REGION_COLOR, "nation:测试国", "", ""));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(region.id(), region);
    return base.withRegions(regions);
  }

  private static Map<String, TerrainType> terrainTypes() {
    Map<String, TerrainType> types = new LinkedHashMap<>();
    types.put("plains", type("plains", "平原", PLAINS_COLOR));
    types.put("desert", type("desert", "沙漠", DESERT_COLOR));
    types.put("ocean", type("ocean", "海洋", OCEAN_COLOR));
    return types;
  }

  private static TerrainType type(String key, String name, String color) {
    return new TerrainType(key, name, color, 0.0, 1.0, 1, 0, 0, 1, "");
  }

  /** 人口（农村）：中心 1000 / 东邻 5000 / 东南邻 250000；一座城"石堡"在东邻。 */
  static SocialData smallSocial() {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(CENTER, series(1000L));
    populations.put(EAST, series(5000L));
    populations.put(SOUTH_EAST, series(250_000L));
    Map<io.mosire.simos.map.CityId, SocialCity> cities = new LinkedHashMap<>();
    cities.put(
        io.mosire.simos.map.CityId.parse("c-1_0"),
        new SocialCity(
            io.mosire.simos.map.CityId.parse("c-1_0"),
            "石堡",
            EAST,
            Optional.of(new RegionId("r-test")),
            Map.of()));
    return new SocialData(populations, cities, Map.of());
  }

  /** 恒定人口序列：anchor = 值、增长速率恒 0（{@code valueAt} 任意时刻都等于该值）。 */
  static PopulationSeries series(long value) {
    return new PopulationSeries(
        new Segment<>(T0, value),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  /** 一支军"一军"在东南邻。 */
  static io.mosire.simos.unit.UnitState smallUnits() {
    io.mosire.simos.unit.Unit unit =
        new io.mosire.simos.unit.Unit(
            new io.mosire.simos.unit.UnitId("u-test"),
            "一军",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<io.mosire.simos.unit.UnitId>empty())),
                List.of(),
                null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.of(SOUTH_EAST))), List.of(), null),
            300,
            Map.of(),
            2,
            1000,
            Optional.empty());
    return new io.mosire.simos.unit.UnitState(Map.of(unit.id(), unit));
  }

  /** 一支军在视野外（(9,9)）——用来钉"看不见的不画"。 */
  static io.mosire.simos.unit.UnitState farAwayUnits() {
    io.mosire.simos.unit.Unit unit =
        new io.mosire.simos.unit.Unit(
            new io.mosire.simos.unit.UnitId("u-far"),
            "远军",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<io.mosire.simos.unit.UnitId>empty())),
                List.of(),
                null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.of(new HexCoord(9, 9)))), List.of(), null),
            100,
            Map.of(),
            2,
            1000,
            Optional.empty());
    return new io.mosire.simos.unit.UnitState(Map.of(unit.id(), unit));
  }
}
