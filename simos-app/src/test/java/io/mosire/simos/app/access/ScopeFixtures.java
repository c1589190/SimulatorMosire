package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.testing.WorldSlices;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationAffiliations;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 范围函数用例的共享夹具（Task 3/4）：造**合成**小世界（{@code map} / {@code unit} / {@code gov} / {@code army} / {@code ga}
 * 五片）。
 *
 * <p>★ **不借用生产代码当期望值**：这里的区域 tag、hex 集合、视野半径都是**字面量**，用例那边逐字断言—— 拿被测实现（或它的同源工具）生成期望值等于自己印证自己。
 *
 * <p>★★ <b>2026-10-13 收尾测试第 2 层</b>：{@link Affiliated} 把"决策人本体"与"它在世界表里的归属行"绑成一个值 —— 归属归模块之后（乙收尾），
 * <b>只产裸决策人 = 谁都够不着</b>，可见性断言会退化成恒真式；本文件从形状上让"只给一半"写不出来。
 */
public final class ScopeFixtures {

  public static final String MAP_ID = "demo";
  public static final BranchId MAIN = new BranchId("main");
  public static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private ScopeFixtures() {}

  // ── 世界装配 ────────────────────────────────────────────────────────────────────────

  /**
   * 只关心 {@code map}/{@code unit}/{@code ga} 的用例：{@code gov}/{@code army} 给空片、无归属。
   *
   * <p>★ 要"有归属"的决策人一律走 {@link #state(GameMap, UnitState, WorldSlices, Affiliated...)}。
   */
  public static SimulationState state(GameMap map, UnitState units, GaState sd) {
    return state(map, units, WorldSlices.gaOnly(sd));
  }

  /**
   * ★★ <b>2026-10-13 收尾测试第 1 层（机械修复）</b>：把三片一起装进世界 —— 乙收尾起国家住 {@code gov}、军队住 {@code army}、 决策人住
   * {@code ga}（原先 {@code sd} 一片装三者）。
   *
   * <p>★ 切片键 = {@code gov} / {@code army} / {@code ga}，与各自的 {@code XxxSnapshot.namespace()}
   * 同字面（{@code SimulationState} 构造期会校验，键写错当场抛——不会静默少一片）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 2 层：本重载把归属行真正写进世界</b>（{@link Affiliated} 的 kind → 对应的表）。 ——
   * 新契约下归属<b>不住在 {@code DecisionMaker} 值上</b>（乙收尾删了那个字段），而住在各模块的表里、<b>按 dmId 索引</b>；
   * 所以"这个决策人归属谁"必须<b>先有 dm、再落表</b>。★ 只产裸决策人 = 经 {@code DecisionScopeFunctions} 一律 deny-all ⇒
   * 可见性断言会退化成"什么都看不见"的恒真式（那正是本层要消灭的失真）。
   */
  public static SimulationState state(
      GameMap map, UnitState units, WorldSlices slices, Affiliated... affiliated) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    WorldSlices withRows = affiliates(slices, affiliated);
    return new SimulationState(
        new StateMeta(ref, T0),
        Map.of(
            "map", new MapSnapshot(ref, T0, map),
            "unit", new UnitSnapshot(ref, T0, units),
            "gov", new GovSnapshot(ref, T0, withRows.gov()),
            "army", new ArmySnapshot(ref, T0, withRows.army()),
            "ga", new GaSnapshot(ref, T0, withRows.ga())),
        InMemoryInfoSystem.empty());
  }

  /**
   * ★★ 把每条归属<b>落进它那个模块的表</b>（{@code nation}/{@code gov} → {@code GovState}；{@code army} →
   * {@code ArmyData}），其余字段原样带过。
   *
   * <p>★ <b>未注册的 kind ⇒ 当场抛</b>（不是静默丢弃）：夹具写错种类名必须响亮失败，否则"有归属"会悄悄变成"没归属"。
   *
   * <p>★ 这里直接写表、<b>不走</b> {@code gov.AssignDecisionMakerNation} 那三条命令：本文件造的是**合成小世界**
   * （不启 {@code Shell}、不落 revision），三条赋权命令自己的落盘/具名拒由它们各自的用例把守。语义上等价——表里那一行的形状与
   * 那三条命令写出来的**逐字相同**（{@code DecisionMakerId → NationId/GovUnitId/ArmyId}）。
   */
  public static WorldSlices affiliates(WorldSlices slices, Affiliated... affiliated) {
    GovState gov = slices.gov();
    ArmyData army = slices.army();
    for (Affiliated one : affiliated) {
      switch (one.kind()) {
        case NationAffiliations.KIND ->
            gov = gov.withNationAffiliations(put(gov.nationAffiliations(), one.id(), new NationId(one.ref())));
        case GovAffiliations.KIND ->
            gov = gov.withGovAffiliations(put(gov.govAffiliations(), one.id(), new UnitId(one.ref())));
        case ArmyAffiliations.KIND ->
            army = army.withArmyAffiliations(put(army.armyAffiliations(), one.id(), new ArmyId(one.ref())));
        default ->
            throw new IllegalArgumentException(
                "未注册的归属种类（夹具写错了？）: " + one.kind() + "，已知 = nation/gov/army");
      }
    }
    return WorldSlices.of(gov, army, slices.ga());
  }

  /** 保序拷贝 + 覆写一个键（夹具里不追求不可变共享，只求语义清楚）。 */
  private static <K, V> Map<K, V> put(Map<K, V> base, K key, V value) {
    Map<K, V> next = new LinkedHashMap<>(base);
    next.put(key, value);
    return next;
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
   * ★★ <b>一个「带归属的决策人」</b>（2026-10-13 收尾测试第 2 层）：裸 {@link DecisionMaker} + 它在<b>世界表</b>里的那一行
   * （{@link #kind()} + {@link #ref()}）。
   *
   * <p>★★ <b>为什么必须成对</b>：2026-10-12 乙收尾（用户裁定「<b>归属肯定归模块</b>」）把 {@code affiliation} 字段从
   * {@code DecisionMaker} 上删掉 ⇒ 归属改住各模块自己的表（{@code GovState.nationAffiliations()} /
   * {@code GovState.govAffiliations()} / {@code ArmyData.armyAffiliations()}），<b>按 dmId 索引</b>。 ⇒ 只造裸决策人
   * = 一个任何人都够不着的决策人（{@code DecisionScopeFunctions} 给四命名空间 deny-all）⇒ 一切"看得见/看不见"的断言都会
   * 退化成"什么都看不见"的恒真式。<b>本类型把那两半绑在一起，让夹具不可能只给一半。</b>
   */
  public record Affiliated(DecisionMaker maker, String kind, String ref) {

    public Affiliated {
      Objects.requireNonNull(maker, "maker");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(ref, "ref");
    }

    /** 决策人 id（表键；与 {@link #maker()} 同源，不另拼）。 */
    public DecisionMakerId id() {
      return maker.id();
    }

    /** 换一份 GM 额外限制，归属行原样、其余字段逐分量带过（"只能收紧、不能放大"的用例用）。 */
    public Affiliated withLimit(AccessLimit limit) {
      return new Affiliated(
          new DecisionMaker(
              maker.id(),
              maker.allowedTools(),
              limit,
              maker.decisionCadenceTicks(),
              maker.providerId(),
              maker.conversationGeneration()),
          kind,
          ref);
    }
  }

  /**
   * 国家决策人（**带归属行**：{@code GovState.nationAffiliations()} 里 {@code dmId → nationId}）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 2 层修复</b>：本方法此前（第 1 层）只产<b>裸决策人</b>，并在类注里把后果登记成"第 2 层的红点"
   * —— 那是**夹具语义失真**：旧归属跟着 {@code DecisionMaker} 值走，新归属住在世界的表里且按 dmId 索引，而 {@code state(...)} 在
   * {@code nationDm(...)} <b>之前</b>求值 ⇒ 夹具拿不到 dm id。本层把两半合成 {@link Affiliated} 并让
   * {@link #state(GameMap, UnitState, WorldSlices, Affiliated...)} 落表 ⇒ 调用点只需<b>重排求值顺序</b>（先建 dm，再建世界）。
   */
  public static Affiliated nationDm(String dmId, String nationId) {
    return new Affiliated(maker(dmId), NationAffiliations.KIND, nationId);
  }

  /** 军队决策人（**带归属行**：{@code ArmyData.armyAffiliations()} 里 {@code dmId → armyId}）。 */
  public static Affiliated armyDm(String dmId, String armyId) {
    return new Affiliated(maker(dmId), ArmyAffiliations.KIND, armyId);
  }

  /** GOV 决策人（**带归属行**：{@code GovState.govAffiliations()} 里 {@code dmId → govUnitId}）。 */
  public static Affiliated govDm(String dmId, String govUnitId) {
    return new Affiliated(maker(dmId), GovAffiliations.KIND, govUnitId);
  }

  /**
   * 带 GM **额外限制**的决策人（T9）：同一个归属再叠一份 {@code accessLimit} ⇒ 校验"只能收紧、不能放大"。
   *
   * <p>★ 缺省（{@link #maker}）取 {@link AccessLimit#empty()} = **不收紧**——范围函数说什么就是什么。旧夹具这里是 {@code
   * ViewScope.empty()}（deny-all），名字像、语义**相反**，照抄会把每个决策人配成瞎子。
   */
  public static Affiliated nationDmWithLimit(String dmId, String nationId, AccessLimit limit) {
    return nationDm(dmId, nationId).withLimit(limit);
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
   * {@code army}（都不再住 {@code GaState}）⇒ 本方法改返回三切片载体 {@link WorldSlices}。★★ <b>第 2 层</b>：归属行由调用方经
   * {@link #affiliates(WorldSlices, Affiliated...)} / {@link #state(GameMap, UnitState, WorldSlices, Affiliated...)}
   * 落表 —— 本方法只负责"国家与军队存在"，<b>不猜</b>谁归属谁。
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
