package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
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
import java.util.Objects;
import java.util.Optional;

/**
 * 走廊世界（测试夹具，M5 T9b）：一个三格沙漠走廊 + 一个单位 + 一条人口序列的最小世界，供用例钉住可复现的数字 （走廊三格 / 单位位置 / 逐边成本 1500 / 人口 15000）。
 *
 * <p>★ **确定性**：无随机、无时钟——同一个 {@link #state(String)} 调用逐字段产出同一状态，因此其值可复现、 可被用例写成字面量。
 *
 * <p>★ **只用公开的模块/util API 构造**（不碰 Core 的存储面）：三格走廊（desert，逐边成本见 {@link #UNIT} 的 mobility 注释）+ 一个单位
 * {@code u-1} 在 {@code [1,1]} + {@code [1,1]} 上一条人口序列。
 *
 * <p>★ 它**不是**空库初始化种入的世界——那是 {@link RichWorld}；本类只是用例的确定性夹具。
 */
public final class CorridorWorld {

  /** 走廊世界的时刻（tick=5 ⇒ {@code [1,1]} 人口 = 15000，见 {@link #populationSeries()}）。 */
  public static final SimosTimestamp AT = SimosTimestamp.of(5);

  /** 走廊三格（单位在首格；desert 的 moveCost=3，单位 mobility=500‰ ⇒ 逐边 1500 毫 MP）。 */
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  /** 走廊世界的单位 id（GUI/MCP 的示例命令都以它为对象）。 */
  private static final UnitId UNIT = new UnitId("u-1");

  private static final BranchId MAIN = new BranchId("main");

  /** 走廊起点段（也是人口 anchor 的起点）；一切时态序列在它之前向前恒定延拓。 */
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private CorridorWorld() {}

  /**
   * 组装走廊世界的创世状态：坐标固定 {@code (main, 1)}、时刻 {@link #AT}，含 map/unit/social 三切片 （恰好覆盖 app 装配的三个 codec）。
   *
   * <p>★ {@code mapId} 只做**非空白校验**：{@code GameMap} 没有 id 字段（M2 遗留），状态里无处存它； app 层的 mapId 仍由 {@link
   * io.mosire.simos.app.ShellConfig} 与 GUI 路由持有。留这个形参是为与 GUI 的 mapId 同一口径（将来 {@code GameMap} 补 id
   * 时这里就是落点）。
   *
   * @param mapId 本世界的 map 称谓（非空白）
   * @throws NullPointerException {@code mapId} 为 null
   * @throws IllegalArgumentException {@code mapId} 为空白
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }
    StateRef at = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(at, AT),
        Map.of(
            "map", new MapSnapshot(at, AT, corridorMap()),
            "unit", new UnitSnapshot(at, AT, unitState()),
            "social", new SocialSnapshot(at, AT, socialData()),
            "sd", new SdSnapshot(at, AT, SdState.empty()),
            // ★ R2a：命令总线要求切片在场（缺 economy 时 slice() 会响亮失败）⇒ 空/未激活的 economy 切片。
            "economy", new EconomySnapshot(at, AT, EconomyData.empty()),
            // ★ S1 阶段 2：同款——actor 切片缺席时 slice("actor") 会响亮失败 ⇒ 补空/未激活切片。
            "actor", new ActorSnapshot(at, AT, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }

  /** 三格沙漠走廊：{@code [1,1] → [1,2] → [1,3]}。 */
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
        PathwayGroup.defaults(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 一个单位 {@code u-1}「第一连」：100 人、{@code [1,1]}、mobility 500‰（desert 逐边 1500 毫 MP）。 */
  private static UnitState unitState() {
    Unit unit =
        new Unit(
            UNIT,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of("步枪", 50),
            2,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
            Optional.empty(),
            // ★ 创建（不是拷贝）：视野半径取缺省 1 圈（spec §4.1 / 用户裁定⑤）。
            Unit.DEFAULT_VISION_RADIUS);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(UNIT, unit);
    return new UnitState(units);
  }

  /** {@code [1,1]} 上一条人口序列：anchor 10000 @0、10%/tick，t=5 ⇒ 10000 + round(10000×0.1×5) = 15000。 */
  private static SocialData socialData() {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(H11, populationSeries());
    return new SocialData(populations, Map.of(), Map.of());
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.10)), List.of(), null),
        List.of());
  }
}
