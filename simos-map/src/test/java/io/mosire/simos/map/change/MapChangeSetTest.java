package io.mosire.simos.map.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 变更集：**组件与 {@code GameMap} 的 7 个可变更组件一一对应**（第 8 个 {@code spec} 有意不进）。
 *
 * <p>每条变体的语义、{@code between} 与 {@code apply} 的往返、以及"只改了一条边"这类**边界**都在这里钉住。 反射式的逐组件枚举（`MapChangeSet`
 * ↔ `GameMap` 的双向对应）在 Task 7，本文件只做**非反射**的那一半。
 */
class MapChangeSetTest {

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  private static final HexCoord H_C = new HexCoord(-3, 2);

  private static final HexCoord H_D = new HexCoord(2, -4);

  /** 只出现在 target 里（"新增"那一侧）。 */
  private static final HexCoord H_E = new HexCoord(7, -1);

  private static final EdgeRef EDGE_AB = new EdgeRef(H_A, H_B);

  private static final EdgeRef EDGE_BC = new EdgeRef(H_B, H_C);

  private static final EdgeRef EDGE_AC = new EdgeRef(H_A, H_C);

  private static final EdgeRef EDGE_BD = new EdgeRef(H_B, H_D);

  /** 只出现在 target 里（"新增"那一侧）。 */
  private static final EdgeRef EDGE_DE = new EdgeRef(H_D, H_E);

  private static final long SEED = 42L;

  // ── 夹具：每个组件都非空，且键序**刻意都不是字典序** ─────────────────────────────

  private static Map<HexCoord, HexCell> linkedHexes() {
    Map<HexCoord, HexCell> m = new LinkedHashMap<>();
    m.put(H_A, new HexCell("mountains", 0.70));
    m.put(H_B, new HexCell("plains", 0.35));
    m.put(H_C, new HexCell("ocean", 0.10));
    m.put(H_D, new HexCell("desert", 0.50));
    return m;
  }

  private static Map<RegionId, Region> linkedRegions() {
    Map<RegionId, Region> m = new LinkedHashMap<>();
    m.put(new RegionId("r2"), region("r2", H_A));
    m.put(new RegionId("r10"), region("r10", H_B));
    m.put(new RegionId("r1"), region("r1", H_C));
    m.put(new RegionId("r5"), region("r5", H_D));
    return m;
  }

  private static Map<CityId, City> linkedCities() {
    Map<CityId, City> m = new LinkedHashMap<>();
    m.put(new CityId("c9"), city("c9", H_A));
    m.put(new CityId("c1"), city("c1", H_B));
    m.put(new CityId("c5"), city("c5", H_C));
    m.put(new CityId("c3"), city("c3", H_D));
    return m;
  }

  private static Map<String, TerrainType> linkedTerrainTypes() {
    Map<String, TerrainType> m = new LinkedHashMap<>();
    for (String key : new String[] {"plains", "ocean", "mountains", "desert"}) {
      m.put(key, TerrainCatalog.of(key));
    }
    return m;
  }

  private static Map<PathwayId, Pathway> linkedPathways() {
    Map<PathwayId, Pathway> m = new LinkedHashMap<>();
    m.put(new PathwayId("p3"), pathway("p3", EDGE_AB, 2));
    m.put(new PathwayId("p1"), pathway("p1", EDGE_BC, 3));
    m.put(new PathwayId("p2"), pathway("p2", EDGE_AC, 4));
    m.put(new PathwayId("p7"), pathway("p7", EDGE_BD, 5));
    return m;
  }

  private static Map<String, PathwayGroup> linkedPathwayGroups() {
    Map<String, PathwayGroup> m = new LinkedHashMap<>();
    m.put("road", group("road", "#8B7355"));
    m.put("river", group("river", "#3295D2"));
    m.put("rail", group("rail", "#606060"));
    m.put("sea", group("sea", "#1F5FA0"));
    return m;
  }

  private static Map<EdgeRef, EdgeTags> linkedEdges() {
    Map<EdgeRef, EdgeTags> m = new LinkedHashMap<>();
    m.put(EDGE_AB, tags("road"));
    m.put(EDGE_BC, tags("river"));
    m.put(EDGE_AC, tags("rail"));
    m.put(EDGE_BD, tags("sea"));
    return m;
  }

  private static Region region(String id, HexCoord... hexes) {
    return Region.of(new RegionId(id), "区域 " + id, Set.of(hexes), RegionMeta.empty());
  }

  private static City city(String id, HexCoord at) {
    return new City(new CityId(id), "城 " + id, at, new RegionId("r1"), Map.of("population", 1000));
  }

  private static Pathway pathway(String id, EdgeRef edge, int width) {
    return new Pathway(new PathwayId(id), "线 " + id, "road", List.of(edge), Map.of("width", width));
  }

  private static PathwayGroup group(String id, String color) {
    return new PathwayGroup(id, "组 " + id, color, null, true, Map.of());
  }

  private static EdgeTags tags(String groupId) {
    return new EdgeTags(Map.of(groupId, Map.of("width", 2)));
  }

  /** 8 个组件全非空的地图：逐组件比较的判别力全靠它。 */
  private static GameMap richMap() {
    return new GameMap(
        linkedHexes(),
        linkedRegions(),
        linkedCities(),
        linkedTerrainTypes(),
        linkedPathways(),
        linkedPathwayGroups(),
        linkedEdges(),
        GenerationSpec.defaults(SEED));
  }

  // ── target 侧：**每个组件都只做一种变化**（增/改 或 纯删），故 between 不会遇到"又增又删" ──

  private static Map<HexCoord, HexCell> targetHexes() {
    Map<HexCoord, HexCell> m = new LinkedHashMap<>(linkedHexes());
    m.put(H_A, new HexCell("desert", 0.90)); // 同 key 不同 value
    m.put(H_E, new HexCell("plains", 0.20)); // 新 key
    return m;
  }

  private static Map<RegionId, Region> targetRegions() {
    Map<RegionId, Region> m = new LinkedHashMap<>(linkedRegions());
    m.put(new RegionId("r2"), region("r2", H_A).withName("改名后的 r2"));
    m.put(new RegionId("r9"), region("r9", H_E));
    return m;
  }

  /** 纯删（一个新增都没有）⇒ 整个组件走 {@code Remove}。 */
  private static Map<CityId, City> targetCities() {
    Map<CityId, City> m = new LinkedHashMap<>(linkedCities());
    m.remove(new CityId("c3"));
    return m;
  }

  /** 同 key 换了一个 {@code TerrainType}（R-48-i 要的那条差异）。 */
  private static Map<String, TerrainType> targetTerrainTypes() {
    Map<String, TerrainType> m = new LinkedHashMap<>(linkedTerrainTypes());
    m.put("plains", new TerrainType("plains", "平原·改", "#123456", 0.30, 0.45, 3, 1, 0, 1, null));
    return m;
  }

  private static Map<PathwayId, Pathway> targetPathways() {
    Map<PathwayId, Pathway> m = new LinkedHashMap<>(linkedPathways());
    m.put(new PathwayId("p3"), pathway("p3", EDGE_AB, 9));
    m.put(new PathwayId("p9"), pathway("p9", EDGE_DE, 1));
    return m;
  }

  private static Map<String, PathwayGroup> targetPathwayGroups() {
    Map<String, PathwayGroup> m = new LinkedHashMap<>(linkedPathwayGroups());
    m.put("road", group("road", "#010203"));
    m.put("canal", group("canal", "#040506"));
    return m;
  }

  private static Map<EdgeRef, EdgeTags> targetEdges() {
    Map<EdgeRef, EdgeTags> m = new LinkedHashMap<>(linkedEdges());
    m.put(EDGE_AB, tags("river")); // 同 key 不同 value
    m.put(EDGE_DE, tags("canal")); // 新 key
    return m;
  }

  /** 7 个可变更组件全都与 {@link #richMap()} 不同，**spec 一字不动**（roundtrip 的前提）。 */
  private static GameMap targetMap() {
    return richMap()
        .withHexes(targetHexes())
        .withRegions(targetRegions())
        .withCities(targetCities())
        .withTerrainTypes(targetTerrainTypes())
        .withPathways(targetPathways())
        .withPathwayGroups(targetPathwayGroups())
        .withEdges(targetEdges());
  }

  /** 取值用具的取用口：不是对应的变体就直接断失败（不静默返回坏值）。 */
  private static <T> Map<String, T> upsertEntries(FieldDelta<T> delta) {
    assertThat(delta).isInstanceOf(FieldDelta.Upsert.class);
    return ((FieldDelta.Upsert<T>) delta).entries();
  }

  private static <T> Set<String> removeKeys(FieldDelta<T> delta) {
    assertThat(delta).isInstanceOf(FieldDelta.Remove.class);
    return ((FieldDelta.Remove<T>) delta).keys();
  }

  // ── 逐组件比较 ────────────────────────────────────────────────────────────────

  @Test
  void betweenIdenticalIsAllUnchanged() {
    GameMap base = richMap();

    MapChangeSet cs = MapChangeSet.between(base, base);

    assertThat(cs).isNotNull(); // ★ "全 Unchanged"，**不是 null**、也不是空对象
    assertThat(cs.hexes()).isEqualTo(new FieldDelta.Unchanged<HexCell>());
    assertThat(cs.regions()).isEqualTo(new FieldDelta.Unchanged<Region>());
    assertThat(cs.cities()).isEqualTo(new FieldDelta.Unchanged<City>());
    assertThat(cs.terrainTypes()).isEqualTo(new FieldDelta.Unchanged<TerrainType>());
    assertThat(cs.pathways()).isEqualTo(new FieldDelta.Unchanged<Pathway>());
    assertThat(cs.pathwayGroups()).isEqualTo(new FieldDelta.Unchanged<PathwayGroup>());
    assertThat(cs.edges()).isEqualTo(new FieldDelta.Unchanged<EdgeTags>());
    assertThat(cs.isEmpty()).isTrue();
  }

  @Test
  void betweenDetectsAddedHex() {
    GameMap base = richMap();
    Map<HexCoord, HexCell> added = new LinkedHashMap<>(linkedHexes());
    added.put(H_E, new HexCell("plains", 0.20));

    Map<String, HexCell> entries =
        upsertEntries(MapChangeSet.between(base, base.withHexes(added)).hexes());

    // ★ 增量：只带**新 key**，base 里没动的四个键一概不进（全量替换的写法会在这里红）
    assertThat(entries).containsOnlyKeys("7_-1");
    assertThat(entries.get("7_-1")).isEqualTo(new HexCell("plains", 0.20));
  }

  @Test
  void betweenDetectsRemovedHex() {
    GameMap base = richMap();
    Map<HexCoord, HexCell> shrunk = new LinkedHashMap<>(linkedHexes());
    shrunk.remove(H_D);

    MapChangeSet cs = MapChangeSet.between(base, base.withHexes(shrunk));

    assertThat(removeKeys(cs.hexes())).containsExactly("2_-4");
    assertThat(cs.regions()).isEqualTo(new FieldDelta.Unchanged<Region>());
  }

  @Test
  void betweenDetectsChangedHexValue() {
    GameMap base = richMap();
    Map<HexCoord, HexCell> changed = new LinkedHashMap<>(linkedHexes());
    changed.put(H_A, new HexCell("desert", 0.90));

    MapChangeSet cs = MapChangeSet.between(base, base.withHexes(changed));

    // ★ 同 key 不同 value ⇒ Upsert（**不是** Unchanged）：只在 key 集上比对会漏掉这一整类编辑
    Map<String, HexCell> entries = upsertEntries(cs.hexes());
    assertThat(entries).containsOnlyKeys("5_5");
    assertThat(entries.get("5_5")).isEqualTo(new HexCell("desert", 0.90));
    assertThat(cs.regions()).isEqualTo(new FieldDelta.Unchanged<Region>());
  }

  /** ★ **R-48-i**：三个 {@code betweenDetects*} 若全是 hexes，删掉 {@code terrainTypes} 的比较它们照样绿。 */
  @Test
  void betweenDetectsChangedTerrainType() {
    GameMap base = richMap();
    GameMap target = base.withTerrainTypes(targetTerrainTypes());

    MapChangeSet cs = MapChangeSet.between(base, target);

    Map<String, TerrainType> entries = upsertEntries(cs.terrainTypes());
    assertThat(entries).containsOnlyKeys("plains");
    assertThat(entries.get("plains"))
        .isEqualTo(new TerrainType("plains", "平原·改", "#123456", 0.30, 0.45, 3, 1, 0, 1, null))
        .isNotEqualTo(TerrainCatalog.of("plains"));
    assertThat(cs.hexes()).isEqualTo(new FieldDelta.Unchanged<HexCell>());
    assertThat(cs.isEmpty()).isFalse();
  }

  /**
   * ★ **7 个组件逐个钉住**：改一个组件 ⇒ **只有它**不是 {@code Unchanged}，其余六个一律 {@code Unchanged}， 且这一条单组件变更能独立往返。
   *
   * <p>这正是 R-48-i 要的覆盖面：brief 只要求 hexes 与 terrainTypes 两条，而"某个组件整个漂移出去" 可以发生在任意一个组件上。**反射版**的对应关系在
   * Task 7，这里以 7 条显式调用钉住每个 {@code diff} 调用点。
   */
  @Test
  void everyComponentIsComparedIndependently() {
    GameMap base = richMap();

    assertOnlyComponentChanged(base, base.withHexes(targetHexes()), "hexes");
    assertOnlyComponentChanged(base, base.withRegions(targetRegions()), "regions");
    assertOnlyComponentChanged(base, base.withCities(targetCities()), "cities");
    assertOnlyComponentChanged(base, base.withTerrainTypes(targetTerrainTypes()), "terrainTypes");
    assertOnlyComponentChanged(base, base.withPathways(targetPathways()), "pathways");
    assertOnlyComponentChanged(
        base, base.withPathwayGroups(targetPathwayGroups()), "pathwayGroups");
    assertOnlyComponentChanged(base, base.withEdges(targetEdges()), "edges");
  }

  private static void assertOnlyComponentChanged(GameMap base, GameMap target, String name) {
    MapChangeSet cs = MapChangeSet.between(base, target);
    Map<String, FieldDelta<?>> byName = new LinkedHashMap<>();
    byName.put("hexes", cs.hexes());
    byName.put("regions", cs.regions());
    byName.put("cities", cs.cities());
    byName.put("terrainTypes", cs.terrainTypes());
    byName.put("pathways", cs.pathways());
    byName.put("pathwayGroups", cs.pathwayGroups());
    byName.put("edges", cs.edges());

    for (Map.Entry<String, FieldDelta<?>> entry : byName.entrySet()) {
      if (entry.getKey().equals(name)) {
        assertThat(entry.getValue())
            .as("%s 被改了 ⇒ 它必须不是 Unchanged", name)
            .isNotInstanceOf(FieldDelta.Unchanged.class);
      } else {
        assertThat(entry.getValue())
            .as("只改 %s ⇒ %s 必须原样不动", name, entry.getKey())
            .isInstanceOf(FieldDelta.Unchanged.class);
      }
    }
    assertThat(cs.isEmpty()).as("只改 %s ⇒ 变更集非空", name).isFalse();
    assertThat(MapChangeSet.apply(cs, base)).as("%s 的单组件往返", name).isEqualTo(target);
  }

  // ── 往返（铁律 5 的原文） ──────────────────────────────────────────────────────

  @Test
  void applyRebuildsTargetExactly() {
    GameMap base = richMap();
    GameMap target = targetMap();
    // 夹具的前提：只有 spec 之外的东西分叉 —— spec 不进变更集，它分叉会让往返红得毫无意义
    assertThat(target.spec()).isEqualTo(base.spec());

    MapChangeSet cs = MapChangeSet.between(base, target);
    assertThat(cs.isEmpty()).isFalse();
    // 夹具覆盖到三种变体（Upsert 六处、Remove 一处、Unchanged 由 betweenIdenticalIsAllUnchanged 覆盖）
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Upsert.class);

    GameMap applied = MapChangeSet.apply(cs, base);

    assertThat(applied.hexes()).isEqualTo(target.hexes());
    assertThat(applied.regions()).isEqualTo(target.regions());
    assertThat(applied.cities()).isEqualTo(target.cities());
    assertThat(applied.terrainTypes()).isEqualTo(target.terrainTypes());
    assertThat(applied.pathways()).isEqualTo(target.pathways());
    assertThat(applied.pathwayGroups()).isEqualTo(target.pathwayGroups());
    assertThat(applied.edges()).isEqualTo(target.edges());
    assertThat(applied).isEqualTo(target);
  }

  @Test
  void applyOfAllUnchangedReturnsBase() {
    GameMap base = richMap();

    assertThat(MapChangeSet.apply(MapChangeSet.between(base, base), base)).isEqualTo(base);
  }

  /**
   * ★ **R-48-e**：{@code spec} **不进变更集**，{@code apply} 只从 base 取它。
   *
   * <p>夹具刻意让两边的 {@code spec} 分叉：若 {@code apply} 从别处（{@code empty()}、或目标图）取种子， 这里当场红 —— 而"两边 spec
   * 相同"的夹具**看不见**这一条。
   */
  @Test
  void applyTakesSpecFromBase() {
    GameMap base = richMap();
    GameMap target = base.withSpec(GenerationSpec.defaults(99L));

    MapChangeSet cs = MapChangeSet.between(base, target);

    assertThat(cs.isEmpty()).as("spec 不进变更集 ⇒ 只有它不同时，变更集是空的").isTrue();
    assertThat(MapChangeSet.apply(cs, base).spec()).isEqualTo(GenerationSpec.defaults(SEED));
    assertThat(MapChangeSet.apply(cs, base)).isEqualTo(base);
  }

  /** ★ "变为空"是 {@code Remove}，**不是** {@code Unchanged} —— GSimulator 混淆的正是这两者。 */
  @Test
  void componentEmptiedIsRemoveNotUnchanged() {
    GameMap base = richMap();

    MapChangeSet cs = MapChangeSet.between(base, base.withCities(Map.of()));

    assertThat(removeKeys(cs.cities())).containsExactly("c9", "c1", "c5", "c3");
    assertThat(cs.isEmpty()).isFalse();
    assertThat(MapChangeSet.apply(cs, base).cities()).isEmpty();
    assertThat(MapChangeSet.apply(cs, base).hexes()).isEqualTo(base.hexes());
  }

  /**
   * ★ **L1 的第四个成因**：GSimulator 的 {@code MapResolver} 只在 {@code !diff.isEmpty()} 时才调 {@code
   * applyDiff}，而它的 {@code isEmpty()} **排除了 edges** ⇒"只改了一条边"产生空 diff、 **根本不进 apply**。
   *
   * <p>故这里两半都要钉：① 全 Unchanged 的变更集**也能被 apply** 且返回 base； ② **只改 edges ⇒ {@code isEmpty()} 必须为
   * false**，且 apply 后那条边**真的变了**。 少了 ②，"只改一条边"又会变成空变更集 —— 而那正是老仓静默丢边的入口。
   */
  @Test
  void emptyDiffStillEntersApply() {
    GameMap base = richMap();

    // ① 空 diff 也要走 apply，且原样返回 base
    MapChangeSet allUnchanged = MapChangeSet.between(base, base);
    assertThat(allUnchanged.isEmpty()).isTrue();
    assertThat(MapChangeSet.apply(allUnchanged, base)).isEqualTo(base);

    // ② 只改一条边：hexes 等六个组件一字未动
    Map<EdgeRef, EdgeTags> changedEdges = new LinkedHashMap<>(linkedEdges());
    changedEdges.put(EDGE_AB, tags("river"));
    GameMap edgesOnly = base.withEdges(changedEdges);
    MapChangeSet cs = MapChangeSet.between(base, edgesOnly);

    assertThat(cs.edges()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.hexes()).isEqualTo(new FieldDelta.Unchanged<HexCell>());
    assertThat(cs.isEmpty()).as("只改了 edges ⇒ 变更集**不得**为空").isFalse();

    GameMap applied = MapChangeSet.apply(cs, base);
    assertThat(applied.edges().get(EDGE_AB)).isEqualTo(tags("river"));
    assertThat(applied.edges()).isEqualTo(changedEdges);
    assertThat(applied.hexes()).isEqualTo(base.hexes());
  }

  // ── 变体本身的形状 ────────────────────────────────────────────────────────────

  @Test
  void upsertCannotBeEmptyOrRemoveCannotBeEmpty() {
    assertThatThrownBy(() -> new FieldDelta.Upsert<HexCell>(Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Upsert 不得为空");
    assertThatThrownBy(() -> new FieldDelta.Remove<HexCell>(Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Remove 不得为空");
  }

  @Test
  void deltasAreImmutable() {
    Map<String, HexCell> source = new LinkedHashMap<>();
    source.put("5_5", new HexCell("plains", 0.5));
    Set<String> sourceKeys = new LinkedHashSet<>();
    sourceKeys.add("5_5");

    FieldDelta.Upsert<HexCell> upsert = new FieldDelta.Upsert<>(source);
    FieldDelta.Remove<HexCell> remove = new FieldDelta.Remove<>(sourceKeys);

    // ★ 构造后改源，delta 一字不动（是**快照**，不是视图）
    source.put("0_0", new HexCell("ocean", 0.1));
    sourceKeys.add("0_0");
    assertThat(upsert.entries()).containsOnlyKeys("5_5");
    assertThat(remove.keys()).containsExactly("5_5");

    assertThat(upsert.entries()).isUnmodifiable();
    assertThat(remove.keys()).isUnmodifiable();
    assertThatThrownBy(() -> upsert.entries().put("0_0", new HexCell("ocean", 0.1)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> remove.keys().add("0_0"))
        .isInstanceOf(UnsupportedOperationException.class);

    // ★ Patch 的两层容器都继承自上面两个 record ⇒ 同样不可变（它自己不新写一行冻结）
    FieldDelta.Patch<HexCell> patch = new FieldDelta.Patch<>(upsert, remove);
    assertThat(patch.upserts().entries()).isUnmodifiable();
    assertThat(patch.removals().keys()).isUnmodifiable();
    assertThatThrownBy(() -> patch.upserts().entries().put("0_0", new HexCell("ocean", 0.1)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> patch.removals().keys().add("0_0"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * ★ **保序**（控制器对 brief 正文的唯一一处修正：{@code Set.copyOf} 与 {@code Map.copyOf} 的迭代序 **不是内容的纯函数** ⇒
   * 变更集的字节会跨运行漂移）。
   *
   * <p>钉的是**冻结的插入序字面量**，不是"跟源 map 比" —— 后者可能恰好落回插入序而假绿。 键数取 4：少了保序钉子会假绿（Task 5 实测，见 {@code
   * GameMapTest.mapsAreInsertionOrdered}）。
   */
  @Test
  void deltasPreserveInsertionOrder() {
    Map<String, HexCell> entries = new LinkedHashMap<>();
    entries.put("5_5", new HexCell("mountains", 0.7));
    entries.put("7_-1", new HexCell("plains", 0.2));
    entries.put("0_0", new HexCell("plains", 0.35));
    entries.put("2_-4", new HexCell("desert", 0.5));
    assertThat(new FieldDelta.Upsert<>(entries).entries().keySet())
        .containsExactly("5_5", "7_-1", "0_0", "2_-4");

    Set<String> keys = new LinkedHashSet<>();
    keys.add("2_-4");
    keys.add("5_5");
    keys.add("0_0");
    assertThat(new FieldDelta.Remove<HexCell>(keys).keys()).containsExactly("2_-4", "5_5", "0_0");

    // ★ 且 between 顺着 **target** 的迭代序读：把新键排在头里，产物里它也在头里
    Map<HexCoord, HexCell> target = new LinkedHashMap<>();
    target.put(H_E, new HexCell("plains", 0.2)); // 新键排第一
    target.put(H_A, new HexCell("desert", 0.9)); // 同 key 换值排第二
    target.put(H_B, new HexCell("plains", 0.35));
    target.put(H_C, new HexCell("ocean", 0.10));
    target.put(H_D, new HexCell("desert", 0.50));
    assertThat(
            upsertEntries(MapChangeSet.between(richMap(), richMap().withHexes(target)).hexes())
                .keySet())
        .containsExactly("7_-1", "5_5");
  }

  @Test
  void deltasRejectNullKeysAndValues() {
    assertThatThrownBy(() -> new FieldDelta.Upsert<HexCell>(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Upsert.entries 不得为 null");

    Map<String, HexCell> nullValue = new LinkedHashMap<>();
    nullValue.put("5_5", new HexCell("plains", 0.5));
    nullValue.put("0_0", null);
    assertThatThrownBy(() -> new FieldDelta.Upsert<>(nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Upsert.entries 的键与值都不得为 null: 0_0");

    assertThatThrownBy(() -> new FieldDelta.Remove<HexCell>(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Remove.keys 不得为 null");

    Set<String> nullKey = new LinkedHashSet<>();
    nullKey.add("5_5");
    nullKey.add(null);
    assertThatThrownBy(() -> new FieldDelta.Remove<HexCell>(nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Remove.keys 不得含 null");

    // Patch 自己的守卫只有两条 requireNonNull：消息恰是字段名，故必须**精确匹配**（形态 2）
    FieldDelta.Upsert<HexCell> okUpsert =
        new FieldDelta.Upsert<>(Map.of("5_5", new HexCell("plains", 0.5)));
    FieldDelta.Remove<HexCell> okRemove = new FieldDelta.Remove<>(Set.of("5_5"));
    assertThatThrownBy(() -> new FieldDelta.Patch<HexCell>(null, okRemove))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("upserts");
    assertThatThrownBy(() -> new FieldDelta.Patch<HexCell>(okUpsert, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("removals");
  }

  @Test
  void lookupReadsOnlyFromUpsert() {
    HexCell cell = new HexCell("plains", 0.5);

    assertThat(new FieldDelta.Upsert<>(Map.of("5_5", cell)).lookup("5_5")).contains(cell);
    assertThat(new FieldDelta.Upsert<>(Map.of("5_5", cell)).lookup("0_0")).isEmpty();
    // 未变 ⇒ 没有"新值"可给；删除 ⇒ 那条 key 在新的内容里没有值
    assertThat(new FieldDelta.Unchanged<HexCell>().lookup("5_5")).isEmpty();
    assertThat(new FieldDelta.Remove<HexCell>(Set.of("5_5")).lookup("5_5")).isEmpty();
  }

  /** 同一组件**又增又删**：删掉 H_D、加上 H_E。 */
  private static Map<HexCoord, HexCell> mixedHexes() {
    Map<HexCoord, HexCell> m = new LinkedHashMap<>(linkedHexes());
    m.remove(H_D);
    m.put(H_E, new HexCell("plains", 0.20));
    return m;
  }

  /**
   * ★ **同一组件又增又删 ⇒ {@code Patch}**（第四条变体），且**两侧都在** —— 只报一侧就是静默的数据损失。
   *
   * <p>对照：只增一侧是 {@code Upsert}、只删一侧是 {@code Remove}，{@code Patch} 只在**两侧都非空**时出现。
   */
  @Test
  void betweenDetectsAddedAndRemovedHex() {
    GameMap base = richMap();

    MapChangeSet cs = MapChangeSet.between(base, base.withHexes(mixedHexes()));

    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Patch.class);
    FieldDelta.Patch<HexCell> patch = (FieldDelta.Patch<HexCell>) cs.hexes();
    assertThat(patch.upserts().entries()).containsOnlyKeys("7_-1"); // 加的那一侧
    assertThat(patch.removals().keys()).containsExactly("2_-4"); // 删的那一侧
    // lookup 只看 upserts：删除那一侧没有"新值"可给
    assertThat(patch.lookup("7_-1")).contains(new HexCell("plains", 0.20));
    assertThat(patch.lookup("2_-4")).isEmpty();
    assertThat(cs.isEmpty()).isFalse();

    // 单侧输入**不**产出 Patch（Patch 只在两侧都非空时出现）
    Map<HexCoord, HexCell> addedOnly = new LinkedHashMap<>(linkedHexes());
    addedOnly.put(H_E, new HexCell("plains", 0.2));
    Map<HexCoord, HexCell> removedOnly = new LinkedHashMap<>(linkedHexes());
    removedOnly.remove(H_D);
    assertThat(MapChangeSet.between(base, base.withHexes(addedOnly)).hexes())
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(MapChangeSet.between(base, base.withHexes(removedOnly)).hexes())
        .isInstanceOf(FieldDelta.Remove.class);
  }

  /**
   * ★★ **本任务为 `Patch` 这个新面欠的账**：{@code between} 是铁律 5 的派生函数，{@code apply(between(b,t), b)} 必须对
   * **任意** (b,t) 成立 —— 包括"同一组件又增又删"这种。Task 7 的往返用例天生会造出这种对。
   */
  @Test
  void mixedChangeRoundTrips() {
    GameMap base = richMap();
    GameMap target = base.withHexes(mixedHexes());

    MapChangeSet cs = MapChangeSet.between(base, target);

    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Patch.class);
    GameMap applied = MapChangeSet.apply(cs, base);
    assertThat(applied).isEqualTo(target);
    assertThat(applied.hexes()).isEqualTo(target.hexes());
    // ★ **先删后增**的键序：base 的序删掉 H_D，新键 H_E 追加在尾
    assertThat(applied.hexes().keySet()).containsExactly(H_A, H_B, H_C, H_E);
  }

  /** ★ `Patch` 不许**只落一半**：apply 之后被删的键真的没了、被加的键真的在（任一侧丢失都红）。 */
  @Test
  void patchIsNotSilentlyHalfApplied() {
    GameMap base = richMap();

    GameMap applied =
        MapChangeSet.apply(MapChangeSet.between(base, base.withHexes(mixedHexes())), base);

    assertThat(applied.hexes()).doesNotContainKey(H_D); // 删的那一侧落地了
    assertThat(applied.hexes()).containsKey(H_E); // 加的那一侧落地了
    assertThat(applied.hexes().get(H_E)).isEqualTo(new HexCell("plains", 0.20));
    assertThat(applied.hexes()).hasSize(4); // 4 - 1 + 1
    assertThat(applied.hexes().get(H_A)).isEqualTo(new HexCell("mountains", 0.70)); // 没动的键原样
  }

  @Test
  void betweenAndApplyRejectNullInputs() {
    GameMap base = richMap();

    assertThatThrownBy(() -> MapChangeSet.between(null, base))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("base");
    assertThatThrownBy(() -> MapChangeSet.between(base, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("target");
    assertThatThrownBy(() -> MapChangeSet.apply(null, base))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("cs");
    assertThatThrownBy(() -> MapChangeSet.apply(MapChangeSet.between(base, base), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("base");
  }
}
