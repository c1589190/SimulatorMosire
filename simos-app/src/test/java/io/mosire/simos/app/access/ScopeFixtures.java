package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.testing.WorldSlices;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
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
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.DecisionMakerId;
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

  /** 只关心 {@code map}/{@code unit}/{@code ga} 的用例：{@code gov}/{@code army} 给空片。 */
  public static SimulationState state(GameMap map, UnitState units, GaState sd) {
    return state(map, units, WorldSlices.gaOnly(sd));
  }

  /**
   * ★★ <b>2026-10-13 收尾测试第 1 层（机械修复）</b>：把三片一起装进世界 —— 乙收尾起国家住 {@code gov}、军队住 {@code army}、 决策人住
   * {@code ga}（原先 {@code sd} 一片装三者）。
   *
   * <p>★ 切片键 = {@code gov} / {@code army} / {@code ga}，与各自的 {@code XxxSnapshot.namespace()}
   * 同字面（{@code SimulationState} 构造期会校验，键写错当场抛——不会静默少一片）。
   */
  public static SimulationState state(GameMap map, UnitState units, WorldSlices slices) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T0),
        Map.of(
            "map", new MapSnapshot(ref, T0, map),
            "unit", new UnitSnapshot(ref, T0, units),
            "gov", new GovSnapshot(ref, T0, slices.gov()),
            "army", new ArmySnapshot(ref, T0, slices.army()),
            "ga", new GaSnapshot(ref, T0, slices.ga())),
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
        List.of(new CompositionEntry("步枪", 50)),
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
        List.of(new CompositionEntry("步枪", 50)),
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
        List.of(new CompositionEntry("步枪", 50)),
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

  /**
   * 国家决策人。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 1 层（机械修复）</b>：{@code DecisionMaker} 自乙收尾起<b>不再带归属字段</b>（归属改住各模块的表：
   * {@code GovState.nationAffiliations()} / {@code ArmyData.armyAffiliations()}）⇒ 本方法只产<b>裸决策人</b>。
   *
   * <p>★ <b>已知红点（登记给第 2 层，本层不改断言）</b>：本夹具<b>尚未</b>把归属行写进切片（决策人 id 由用例稍后决定，写下标要重排用例）， 故经本方法造的决策人在
   * {@code DecisionScopeFunctions} 下<b>一律 deny-all</b> ⇒ 依赖归属的可见性断言在夹具重写前必红。
   */
  public static DecisionMaker nationDm(String dmId, String nationId) {
    return maker(dmId);
  }

  /** 军队决策人（同上：只产裸决策人，归属行待第 2 层写进 {@code ArmyData.armyAffiliations()}）。 */
  public static DecisionMaker armyDm(String dmId, String armyId) {
    return maker(dmId);
  }

  /**
   * 带 GM **额外限制**的决策人（T9）：同一个归属再叠一份 {@code accessLimit} ⇒ 校验"只能收紧、不能放大"。
   *
   * <p>★ 缺省（{@link #maker}）取 {@link AccessLimit#empty()} = **不收紧**——范围函数说什么就是什么。旧夹具这里是 {@code
   * ViewScope.empty()}（deny-all），名字像、语义**相反**，照抄会把每个决策人配成瞎子。
   */
  public static DecisionMaker nationDmWithLimit(String dmId, String nationId, AccessLimit limit) {
    return new DecisionMaker(new DecisionMakerId(dmId), Set.of(), limit, 1);
  }

  private static DecisionMaker maker(String dmId) {
    // ★ accessLimit 与本轮的范围函数**无关**——它是 GM 的**额外限制**，这里取"不收紧"。
    return new DecisionMaker(new DecisionMakerId(dmId), Set.of(), AccessLimit.empty(), 1);
  }

  /**
   * 一个国家（住 {@code gov} 片）+ 一条军队（住 {@code army} 片，指向单位根）。
   *
   * <p>★ 国家不是装饰：军队指向的国家必须存在（{@code Army} 的引用完整性）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 1 层（机械修复）</b>：乙收尾起 {@code nations} 住 {@code gov}、{@code armies} 住
   * {@code army}（都不再住 {@code GaState}）⇒ 本方法改返回三切片载体 {@link WorldSlices}。★ <b>归属行（{@code
   * nationAffiliations}/{@code armyAffiliations}）本层未写</b>——决策人 id 由调用方稍后决定，见 {@link #nationDm}
   * 的已知红点。
   */
  public static WorldSlices sdWithArmy(String armyId, String nationId, String rootUnitId) {
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    ArmyId id = new ArmyId(armyId);
    armies.put(id, new Army(id, Optional.empty(), new UnitId(rootUnitId), "军队 " + armyId));
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    NationId nation = new NationId(nationId);
    nations.put(nation, new Nation(nation, "国家 " + nationId, new RegionId("701"), 0));
    return WorldSlices.of(
        new GovState(Map.of(), Map.of(), nations, Map.of(), Map.of(), Map.of(), Map.of()),
        ArmyData.empty().withArmies(armies),
        GaState.empty());
  }

  // ── 断言助手 ────────────────────────────────────────────────────────────────────────

  /** {@code map} 命名空间的前缀，**排序后**返回（{@code ResourceScope} 内部不保序）。 */
  public static List<String> prefixes(ResourceScopeMap scopes) {
    return new ArrayList<>(new TreeSet<>(scopes.declaredScope("map").prefixes()));
  }
}
