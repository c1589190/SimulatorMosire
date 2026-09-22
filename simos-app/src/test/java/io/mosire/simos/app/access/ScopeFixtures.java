package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 范围函数用例的共享夹具（Task 3/4）：造**合成**小世界（map / unit / sd 三切片）。
 *
 * <p>★ **不借用生产代码当期望值**：这里的区域 tag、hex 集合、视野半径都是**字面量**，用例那边逐字断言—— 拿被测实现（或它的同源工具）生成期望值等于自己印证自己。
 */
public final class ScopeFixtures {

  public static final String MAP_ID = "demo";
  public static final BranchId MAIN = new BranchId("main");
  public static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private ScopeFixtures() {}

  // ── 世界装配 ────────────────────────────────────────────────────────────────────────

  public static SimulationState state(GameMap map, UnitState units, SdState sd) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T0),
        Map.of(
            "map", new MapSnapshot(ref, T0, map),
            "unit", new UnitSnapshot(ref, T0, units),
            "sd", new SdSnapshot(ref, T0, sd)),
        InMemoryInfoSystem.empty());
  }

  /** 以 (1,1) 为中心、半径 2 的**全格**世界（19 格），另可叠加区域。 */
  public static GameMap mapOf(Region... regions) {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : new TreeSet<>(HexGrid.withinRadius(new HexCoord(1, 1), 2))) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> byId = new LinkedHashMap<>();
    for (Region region : regions) {
      byId.put(region.id(), region);
    }
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        byId,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 一个区域：tag 为 {@code nation:<nationId>}（用既有的 {@link NationTag}，不手拼）。 */
  public static Region nationRegion(String regionId, String nationId, HexCoord... hexes) {
    return Region.of(
        new RegionId(regionId),
        "区域 " + regionId,
        Set.of(hexes),
        new RegionMeta(null, NationTag.tagFor(new NationId(nationId)), null, null));
  }

  /** 一个区域：tag 为**别的**东西（不是任何国家）或**为 null**（空元数据是合法状态）。 */
  public static Region taggedRegion(String regionId, String tag, HexCoord... hexes) {
    return Region.of(
        new RegionId(regionId),
        "区域 " + regionId,
        Set.of(hexes),
        new RegionMeta(null, tag, null, null));
  }

  // ── 单位 ────────────────────────────────────────────────────────────────────────────

  /** 一个**自身带位置**的单位（9 参兼容构造器 ⇒ 视野半径取缺省；要非缺省值用 {@link #unitWithVision}）。 */
  public static Unit unit(String unitId, HexCoord position) {
    return new Unit(
        new UnitId(unitId),
        "单位 " + unitId,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /** 一个**自身带位置**的单位 + 显式视野半径（13 参形态 ⇒ 半径补齐到规范 14 参）。 */
  public static Unit unitWithVision(String unitId, HexCoord position, int visionRadius) {
    return withVision(unit(unitId, position), visionRadius);
  }

  /** 一个**自身无位置**、跟随父单位的单位（{@code effectivePosition} 会取到父的位置）。 */
  public static Unit attachedChild(String unitId, String parentId) {
    return new Unit(
        new UnitId(unitId),
        "单位 " + unitId,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.of(new UnitId(parentId)))), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<HexCoord>empty())), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /** 一个**自身无位置且未跟随任何人**的单位（"不知道在哪"）。 */
  public static Unit positionlessUnit(String unitId) {
    return new Unit(
        new UnitId(unitId),
        "单位 " + unitId,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<HexCoord>empty())), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /**
   * 换视野半径：**走规范 14 参形态**（9 参兼容构造器只给缺省值，用它等于拿不到非缺省半径）。
   *
   * <p>★ 逐分量带过（本仓"拷贝点丢字段"是最贵的教训形态）——这里只有半径一处**故意**改。
   */
  public static Unit withVision(Unit unit, int visionRadius) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        visionRadius);
  }

  public static UnitState units(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  /** 一条根 + 一串兄弟（{@code @Test} 方法把 varargs 参数数组直接转手时用，避免 varargs 套 varargs）。 */
  public static UnitState units(Unit root, Unit[] others) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    byId.put(root.id(), root);
    for (Unit unit : others) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  // ── 决策人与军队 ────────────────────────────────────────────────────────────────────

  /** 国家决策人（{@code affiliation = Nation(nationId)}）。 */
  public static DecisionMaker nationDm(String dmId, String nationId) {
    return maker(dmId, new Affiliation.Nation(new NationId(nationId)));
  }

  /** 军队决策人（{@code affiliation = Army(armyId)}）。 */
  public static DecisionMaker armyDm(String dmId, String armyId) {
    return maker(dmId, new Affiliation.Army(new ArmyId(armyId)));
  }

  /**
   * 带 GM **额外限制**的决策人（T9）：同一个归属再叠一份 {@code accessLimit} ⇒ 校验"只能收紧、不能放大"。
   *
   * <p>★ 缺省（{@link #maker}）取 {@link AccessLimit#empty()} = **不收紧**——范围函数说什么就是什么。旧夹具这里是 {@code
   * ViewScope.empty()}（deny-all），名字像、语义**相反**，照抄会把每个决策人配成瞎子。
   */
  public static DecisionMaker nationDmWithLimit(String dmId, String nationId, AccessLimit limit) {
    return new DecisionMaker(
        new DecisionMakerId(dmId),
        new Affiliation.Nation(new NationId(nationId)),
        Set.of(),
        limit,
        1);
  }

  private static DecisionMaker maker(String dmId, Affiliation affiliation) {
    // ★ accessLimit 与本轮的范围函数**无关**（范围函数只读 affiliation）——它是 GM 的**额外限制**，
    // 这里取"不收紧"；范围函数本身的判别力由配置了真限制/不同归属的用例覆盖。
    return new DecisionMaker(
        new DecisionMakerId(dmId), affiliation, Set.of(), AccessLimit.empty(), 1);
  }

  /**
   * sd 切片：一个国家 + 一条军队归属（军队 → 单位根）。
   *
   * <p>★ 国家不是装饰：{@code SdState} 的不变量 2（引用完整性）会拒掉"军队指向不存在的国家"， 故夹具必须**先把国家放进切片**（顺序也重要：先 {@code
   * withNations} 再 {@code withArmies}）。
   */
  public static SdState sdWithArmy(String armyId, String nationId, String rootUnitId) {
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    ArmyId id = new ArmyId(armyId);
    armies.put(id, new Army(id, new NationId(nationId), new UnitId(rootUnitId), "军队 " + armyId));
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    NationId nation = new NationId(nationId);
    nations.put(nation, new Nation(nation, "国家 " + nationId, new RegionId("701"), 0));
    return SdState.empty().withNations(nations).withArmies(armies);
  }

  // ── 断言助手 ────────────────────────────────────────────────────────────────────────

  /** {@code map} 命名空间的前缀，**排序后**返回（{@code ResourceScope} 内部不保序）。 */
  public static List<String> prefixes(ResourceScopeMap scopes) {
    return new ArrayList<>(new TreeSet<>(scopes.declaredScope("map").prefixes()));
  }
}
