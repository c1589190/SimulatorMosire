package io.mosire.simos.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionBoundary;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionIndex;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 地图状态根：**8 个组件**，删掉死字段与缓存，逐组件替换，派生件不进组件。 */
class GameMapTest {

  /** 组件名与**序**的冻结字面量 —— 与 record 声明序逐项对表。 */
  private static final List<String> COMPONENT_NAMES =
      List.of(
          "hexes",
          "regions",
          "cities",
          "terrainTypes",
          "pathways",
          "pathwayGroups",
          "edges",
          "spec");

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  private static final HexCoord H_C = new HexCoord(-3, 2);

  /**
   * ★ **第 4 个格不是装饰**：3 键的夹具对 {@code Map.copyOf} 的假绿率，30 次独立 JVM 启动实测 **2/30 ~ 12/30** （7%~40%，最高那列是
   * {@code terrainTypes}），4 键**全为 0/30**（7 个 map 逐个量过，见 {@code
   * task-5-evidence/order-probe/}）。键太少时这条保序钉子**可能钉不住**。
   */
  private static final HexCoord H_D = new HexCoord(2, -4);

  private static final EdgeRef EDGE_AB = new EdgeRef(H_A, H_B);

  private static final EdgeRef EDGE_BC = new EdgeRef(H_B, H_C);

  private static final EdgeRef EDGE_AC = new EdgeRef(H_A, H_C);

  private static final EdgeRef EDGE_CD = new EdgeRef(H_C, H_D);

  private static final EdgeRef EDGE_BD = new EdgeRef(H_B, H_D);

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
    m.put(new PathwayId("p3"), pathway("p3", EDGE_AB));
    m.put(new PathwayId("p1"), pathway("p1", EDGE_BC));
    m.put(new PathwayId("p2"), pathway("p2", EDGE_AC));
    m.put(new PathwayId("p7"), pathway("p7", EDGE_CD));
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
    m.put(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2))));
    m.put(EDGE_BC, new EdgeTags(Map.of("river", Map.of("depth", 3))));
    m.put(EDGE_AC, new EdgeTags(Map.of("rail", Map.of("gauge", 1435))));
    m.put(EDGE_BD, new EdgeTags(Map.of("sea", Map.of("depth", 9))));
    return m;
  }

  private static Region region(String id, HexCoord... hexes) {
    return Region.of(new RegionId(id), "区域 " + id, Set.of(hexes), RegionMeta.empty());
  }

  private static City city(String id, HexCoord at) {
    return new City(new CityId(id), "城 " + id, at, new RegionId("r1"), Map.of("population", 1000));
  }

  private static Pathway pathway(String id, EdgeRef edge) {
    return new Pathway(new PathwayId(id), "线 " + id, "road", List.of(edge), Map.of("width", 2));
  }

  private static PathwayGroup group(String id, String color) {
    return new PathwayGroup(id, "组 " + id, color, null, true, Map.of());
  }

  /** 八个组件全非空的地图：逐组件替换的判别力全靠它。 */
  private static GameMap richMap() {
    return new GameMap(
        linkedHexes(),
        linkedRegions(),
        linkedCities(),
        linkedTerrainTypes(),
        linkedPathways(),
        linkedPathwayGroups(),
        linkedEdges(),
        GenerationSpec.defaults(42L));
  }

  // ── 空图 ──────────────────────────────────────────────────────────────────────

  @Test
  void emptyIsNotNullAndComponentsAreEmpty() {
    GameMap m = GameMap.empty();

    assertThat(m).isNotNull();
    assertThat(m.hexes()).isEmpty();
    assertThat(m.regions()).isEmpty();
    assertThat(m.cities()).isEmpty();
    assertThat(m.terrainTypes()).isEmpty();
    assertThat(m.pathways()).isEmpty();
    assertThat(m.pathwayGroups()).isEmpty();
    assertThat(m.edges()).isEmpty();
  }

  /** ★ R-48-e：{@code spec} **从不 null**；空图的种子是**规范值 0**（空图没有生成历史），不是"没有种子"。 */
  @Test
  void emptyCarriesTheZeroSeedSpec() {
    assertThat(GameMap.empty().spec()).isEqualTo(GenerationSpec.defaults(0L));
    assertThat(GameMap.empty().spec().seed()).isZero();
  }

  /**
   * {@code defaults} **收种子、不吞种子**：种子进得去、出得来，且两个种子给出两个不相等的 spec。
   *
   * <p>★ 参数面已在 **Task 8** 落定（本任务写的是骨架期的 {@code defaults(7L) == new GenerationSpec(7L)}， 那时全部组件只有
   * {@code seed}，只能那么写）。那句话的**真意** —— "只让 seed 变，其余是规范默认值" —— 现在由 {@code
   * GenerationSpecTest#defaultsCarryOnlyTheSeed} 以**逐组件**的形态钉住（更强：它看得见全部 9 个组件）。
   */
  @Test
  void generationSpecDefaultsCarryTheSeed() {
    assertThat(GenerationSpec.defaults(7L).seed()).isEqualTo(7L);
    assertThat(GenerationSpec.defaults(7L)).isNotEqualTo(GenerationSpec.defaults(8L));
  }

  // ── ★ 钉字段清单（L2 / L8 / 死字段） ───────────────────────────────────────────

  @Test
  void componentCountIsExactlyEight() {
    assertThat(GameMap.class.getRecordComponents()).hasSize(8);
  }

  /** ★ **比"数量是 8"强**：数量对而名字换了也拦得住。Task 6 的变更集与 Task 7 的反射枚举都按键名走，故这里钉的是名与序的**冻结字面量**。 */
  @Test
  void componentNamesAreFrozenList() {
    assertThat(componentNames()).containsExactlyElementsOf(COMPONENT_NAMES);
  }

  /** ★ 两个**恒定的死值**：{@code gridSize} 恒 30（与真实半径 80 矛盾、不参与取格）、{@code hexOrientation} 恒 false。 */
  @Test
  void noGridSizeNoHexOrientation() {
    assertThat(componentNames()).doesNotContain("gridSize", "hexOrientation");
  }

  /** ★ 两个**废弃 record** 与两份**非状态**：语义分别由 {@code pathways} 承载、由 Command 历史与重算缓存承载。 */
  @Test
  void noRiversNoRoadsNoTerrainBlocksNoCompressedRegions() {
    assertThat(componentNames())
        .doesNotContain("rivers", "roads", "terrainBlocks", "compressedRegions");
  }

  // ── ★ 顺序 / 不可变 ───────────────────────────────────────────────────────────

  /**
   * ★ **落盘序稳定**：与**冻结的插入序字面量**逐项比，**不写成"跟源 map 比"** —— {@code Map.copyOf} 的迭代序按 JVM
   * 加盐，写成"跟源比"时它可能恰好落回插入序而**假绿**。
   *
   * <p>★ 夹具每表 4 键也是这个道理：3 键时 "落回插入序" 的假绿率实测 **2/30 ~ 12/30**（30 次 JVM 启动，见 {@code
   * task-5-evidence/order-probe/}），4 键实测 0/30。**钉子要真能响，键就不能太少。**
   */
  @Test
  void mapsAreInsertionOrdered() {
    GameMap m = richMap();

    assertThat(m.hexes().keySet()).containsExactly(H_A, H_B, H_C, H_D);
    assertThat(m.regions().keySet())
        .containsExactly(
            new RegionId("r2"), new RegionId("r10"), new RegionId("r1"), new RegionId("r5"));
    assertThat(m.cities().keySet())
        .containsExactly(new CityId("c9"), new CityId("c1"), new CityId("c5"), new CityId("c3"));
    assertThat(m.terrainTypes().keySet()).containsExactly("plains", "ocean", "mountains", "desert");
    assertThat(m.pathways().keySet())
        .containsExactly(
            new PathwayId("p3"), new PathwayId("p1"), new PathwayId("p2"), new PathwayId("p7"));
    assertThat(m.pathwayGroups().keySet()).containsExactly("road", "river", "rail", "sea");
    assertThat(m.edges().keySet()).containsExactly(EDGE_AB, EDGE_BC, EDGE_AC, EDGE_BD);

    // ★ 走 empty() 的 with 链（Task 7 的往返用例正是这么起手的）也必须保序
    GameMap chained = GameMap.empty().withHexes(linkedHexes());
    assertThat(chained.hexes().keySet()).containsExactly(H_A, H_B, H_C, H_D);
    assertThat(chained.regions()).isEmpty();
  }

  @Test
  void mapsAreImmutable() {
    GameMap m = richMap();

    assertThat(m.hexes()).isUnmodifiable();
    assertThat(m.regions()).isUnmodifiable();
    assertThat(m.cities()).isUnmodifiable();
    assertThat(m.terrainTypes()).isUnmodifiable();
    assertThat(m.pathways()).isUnmodifiable();
    assertThat(m.pathwayGroups()).isUnmodifiable();
    assertThat(m.edges()).isUnmodifiable();

    assertThatThrownBy(() -> m.hexes().put(new HexCoord(9, 9), new HexCell("plains", 0.5)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> m.regions().put(new RegionId("r9"), region("r9", new HexCoord(9, 9))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> m.cities().put(new CityId("c9"), city("c9", new HexCoord(9, 9))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> m.terrainTypes().put("x", TerrainCatalog.of("ocean")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> m.pathways().put(new PathwayId("p9"), pathway("p9", EDGE_AB)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> m.pathwayGroups().put("x", group("x", "#000000")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                m.edges()
                    .put(
                        new EdgeRef(new HexCoord(9, 9), new HexCoord(9, 8)),
                        new EdgeTags(Map.of())))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 构造期守卫：7 个 map 与 {@code spec} 都不得为 null（宁抛不静默，**不静默兜底成空表**）。 */
  @Test
  void constructorRejectsNullComponents() {
    GameMap ok = GameMap.empty();

    assertThatThrownBy(
            () ->
                new GameMap(
                    null,
                    ok.regions(),
                    ok.cities(),
                    ok.terrainTypes(),
                    ok.pathways(),
                    ok.pathwayGroups(),
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    null,
                    ok.cities(),
                    ok.terrainTypes(),
                    ok.pathways(),
                    ok.pathwayGroups(),
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("regions 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    null,
                    ok.terrainTypes(),
                    ok.pathways(),
                    ok.pathwayGroups(),
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("cities 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    ok.cities(),
                    null,
                    ok.pathways(),
                    ok.pathwayGroups(),
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("terrainTypes 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    ok.cities(),
                    ok.terrainTypes(),
                    null,
                    ok.pathwayGroups(),
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pathways 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    ok.cities(),
                    ok.terrainTypes(),
                    ok.pathways(),
                    null,
                    ok.edges(),
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pathwayGroups 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    ok.cities(),
                    ok.terrainTypes(),
                    ok.pathways(),
                    ok.pathwayGroups(),
                    null,
                    ok.spec()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("edges 不得为 null");
    assertThatThrownBy(
            () ->
                new GameMap(
                    ok.hexes(),
                    ok.regions(),
                    ok.cities(),
                    ok.terrainTypes(),
                    ok.pathways(),
                    ok.pathwayGroups(),
                    ok.edges(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spec 不得为 null");
  }

  // ── ★ 逐组件替换 ──────────────────────────────────────────────────────────────

  /** ★ **只动一个组件**：8 个 with 方法逐个验，其余 7 个必须原样等于原值，**且键序不变**（{@code Map.equals} 不看序，单靠它会把"顺手重排"放过）。 */
  @Test
  void withMethodsPreserveOtherComponents() {
    GameMap base = richMap();

    assertOnlyComponentChanged(
        base, base.withHexes(Map.of(new HexCoord(9, 9), new HexCell("plains", 0.5))), 0);
    assertOnlyComponentChanged(
        base, base.withRegions(Map.of(new RegionId("r7"), region("r7", new HexCoord(7, 7)))), 1);
    assertOnlyComponentChanged(
        base, base.withCities(Map.of(new CityId("c7"), city("c7", new HexCoord(7, 7)))), 2);
    assertOnlyComponentChanged(
        base, base.withTerrainTypes(Map.of("ocean", TerrainCatalog.of("ocean"))), 3);
    assertOnlyComponentChanged(
        base,
        base.withPathways(
            Map.of(
                new PathwayId("p7"),
                pathway("p7", new EdgeRef(new HexCoord(7, 7), new HexCoord(8, 8))))),
        4);
    assertOnlyComponentChanged(
        base, base.withPathwayGroups(Map.of("river", group("river", "#3295D2"))), 5);
    assertOnlyComponentChanged(
        base,
        base.withEdges(
            Map.of(
                new EdgeRef(new HexCoord(7, 7), new HexCoord(8, 8)),
                new EdgeTags(Map.of("river", Map.of("depth", 1))))),
        6);
    assertOnlyComponentChanged(base, base.withSpec(GenerationSpec.defaults(7L)), 7);
  }

  // ── ★ 派生件不进组件（U2 的两处相反地位） ──────────────────────────────────────

  /** ★ {@code RegionIndex} 是**派生**的：不进组件、不进变革集、不进存档。 */
  @Test
  void regionIndexIsDerivedNotStored() {
    assertThat(componentTypes()).doesNotContain(RegionIndex.class);

    GameMap m = richMap();
    assertThat(m.regionIndex().regionOf(H_A)).containsExactly(new RegionId("r2"));
    assertThat(m.regionIndex().hasRegion(new HexCoord(99, 99))).isFalse();
  }

  /** ★ M8 T1 重建逻辑：从 {@code regions} 重算出的索引 == 逐区域暴力扫描（多从属，含字典序）。 */
  @Test
  void regionIndexRebuildsFromRegionsWithMultiOwnership() {
    GameMap overlapping =
        richMap()
            .withRegions(
                Map.of(
                    new RegionId("r10"), region("r10", H_A, H_B),
                    new RegionId("r2"), region("r2", H_A),
                    new RegionId("r7"), region("r7", H_A, H_B, H_C)));

    RegionIndex index = overlapping.regionIndex();
    for (HexCoord hex : overlapping.hexes().keySet()) {
      List<RegionId> brute =
          overlapping.regions().values().stream()
              .filter(r -> r.hexes().contains(hex))
              .map(Region::id)
              .sorted(Comparator.comparing(RegionId::value))
              .toList();
      assertThat(index.regionOf(hex)).as("hex %s 的归属与暴力扫描一致", hex).isEqualTo(brute);
    }
    assertThat(index.regionOf(H_A))
        .containsExactly(new RegionId("r10"), new RegionId("r2"), new RegionId("r7"));
    assertThat(index.regionOf(H_D)).isEmpty();
  }

  /**
   * ★ **边界不派生、它是 {@code Region} 的组件**（U2）：取边界的路只有 {@code regions().get(id).boundary()} 这一条 ——
   * {@code boundaryOf} 那类"同一概念的第二条路"已被删掉（它与索引的地位正好相反，别混）。
   */
  @Test
  void regionsCarryTheirBoundary() {
    Region r = region("r1", new HexCoord(0, 0), new HexCoord(1, 0));
    GameMap m = GameMap.empty().withRegions(Map.of(r.id(), r));

    Region back = m.regions().get(r.id());
    assertThat(back.boundary()).isNotNull();
    assertThat(back.boundary()).isEqualTo(RegionBoundary.of(back.hexes()));
    assertThat(back.boundary().rings()).isNotEmpty();
  }

  // ── ★ R-48-f：CityId 的三件套（缺一，Task 6 的往返就断） ────────────────────────

  /** ★ 冻结字面量。写成"两个实例的 toString 相等"是自证循环，钉不住 record 的默认实现。 */
  @Test
  void cityIdToStringIsBareValue() {
    assertThat(new CityId("c1").toString()).isEqualTo("c1");
    assertThat(new CityId("城-第 3 区").toString()).isEqualTo("城-第 3 区");
  }

  @Test
  void cityIdParseRoundTripsFrozenLiteral() {
    assertThat(CityId.parse("c1")).isEqualTo(new CityId("c1"));
    assertThat(CityId.parse(new CityId("c1").toString())).isEqualTo(new CityId("c1"));
  }

  @Test
  void cityIdParseRejectsBlank() {
    for (String blank : new String[] {"", "  "}) {
      assertThatThrownBy(() -> CityId.parse(blank))
          .as("空白串 %s", blank)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不得为空白");
    }
    assertThatThrownBy(() -> CityId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CityId(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CityId("")).isInstanceOf(IllegalArgumentException.class);
  }

  // ── 工具 ─────────────────────────────────────────────────────────────────────

  private static List<String> componentNames() {
    return Arrays.stream(GameMap.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
  }

  private static List<Class<?>> componentTypes() {
    return Arrays.stream(GameMap.class.getRecordComponents())
        .map(RecordComponent::getType)
        .toList();
  }

  /** 8 个组件的**声明序**快照。组件永不为 null（构造期已守卫），故 {@code List.of} 收得下。 */
  private static List<Object> components(GameMap m) {
    return List.of(
        m.hexes(),
        m.regions(),
        m.cities(),
        m.terrainTypes(),
        m.pathways(),
        m.pathwayGroups(),
        m.edges(),
        m.spec());
  }

  /** 只有第 {@code changedIndex} 个组件可以变，其余 7 个逐项等于原值且键序不变。 */
  private static void assertOnlyComponentChanged(GameMap base, GameMap mutated, int changedIndex) {
    List<Object> before = components(base);
    List<Object> after = components(mutated);
    String changed = COMPONENT_NAMES.get(changedIndex);

    for (int i = 0; i < before.size(); i++) {
      if (i == changedIndex) {
        assertThat(after.get(i)).as("with 链应当改掉 %s", changed).isNotEqualTo(before.get(i));
        continue;
      }
      String other = COMPONENT_NAMES.get(i);
      assertThat(after.get(i)).as("改 %s 时不该动 %s", changed, other).isEqualTo(before.get(i));
      if (before.get(i) instanceof Map<?, ?> b && after.get(i) instanceof Map<?, ?> a) {
        assertThat(new ArrayList<>(a.keySet()))
            .as("改 %s 时不该重排 %s 的键序", changed, other)
            .isEqualTo(new ArrayList<>(b.keySet()));
      }
    }
  }
}
