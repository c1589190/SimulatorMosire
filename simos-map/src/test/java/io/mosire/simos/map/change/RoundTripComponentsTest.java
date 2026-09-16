package io.mosire.simos.map.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
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
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ **铁律 5 的机械化落地。**
 *
 * <p>不靠纪律：本测试**反射枚举 {@link GameMap} 的全部 record
 * 组件**，逐组件制造差异，断言该差异真的进了变更集且能往返。**新增状态组件若忘了进变更集，本测试自动红。**
 *
 * <p>GSimulator 的教训：{@code MapDiff} 手工对着 {@code MapData} 维护 ⇒ 四个组件（{@code terrainBlocks}/{@code
 * terrainTypes}/{@code pathwayGroups}/{@code edges}）漂移出去，既无编译期也无测试期护栏，于是**对非 root 节点写连通性会静默丢失**。
 *
 * <p>★ **循环体里为什么是三条断言、各自管什么**（少任何一条都会留下一类看不见的漂移，**不要当成冗余删掉**）：
 *
 * <ul>
 *   <li>① {@code cs.isEmpty()} 为 false —— **"新组件没有任何变更集组件覆盖它"的正面钉子**。它**不依赖**下面那个
 *       name→访问器的映射，故对新组件同样打得响（新组件在变更集里根本没有对应项，映射无从谈起）。
 *   <li>② {@code changedOf(cs, name)} 为 true —— 管"组件在 {@code GameMap} 里还在、却从 {@code MapChangeSet} 的
 *       对应关系里掉了出去"（此时按名取得到访问器，取出来的那份必须是"变了"）。
 *   <li>③ 往返 —— 铁律 5 的原文：{@code apply(between(base, target), base)} 必须逐字段重建出 {@code target}。
 * </ul>
 *
 * <p>★ **两个 {@code switch} 的 {@code default} 都必须抛**（{@link #mutate} 与 {@link #changedOf}），**不许**写
 * {@code default -> base} / {@code default -> true} 之类的温和兜底：那会让"新增组件"这个场景**恰好**在新字段上失效，而新字段正是
 * GSimulator 出事的那个场景（护栏变成了装饰）。两个 {@code switch} 分开写、不合并 —— 加字段的人必须两处都来读。
 *
 * <p>★ **豁免集为什么必须有、且必须被单独钉死**：{@code GameMap} 有 **8** 个组件，{@code MapChangeSet} **有意**只有 **7**
 * 个（{@code spec} 是生成输入、不是可变更状态，见 {@link MapChangeSet#apply}）。若本测试无条件遍历全部 8 个，{@code spec}
 * 那一轮**必然**断言失败；若为它写一个 {@code if (name.equals("spec")) continue}，**豁免口就成了一个洞** ——
 * 以后任何人"加字段忘了进变更集"，都能靠往这个 {@code if} 里再加一个名字糊过去。故豁免写成**一个被 {@link #theExclusionListIsExactlySpec}
 * 单独钉死的集合**：加名字是一次显式动作，在 diff 里现形。
 *
 * <p>★ **U2 的连锁**（免得后人以为是被漏掉的）：{@code RegionBoundary} 是 {@link Region} 的**组件** ⇒ {@code
 * MapChangeSet} **不需要**为边界新开组件（{@code regions} 整个 {@code Region} 值被比对，而 {@code Region.equals} 逐组件含
 * {@code boundary}）。⇒ **组件数仍是 8 vs 7、{@code spec} 仍是唯一豁免项。**
 *
 * <p>★ 反射枚举的**方向性**：{@link #everyChangeSetComponentCorrespondsToAGameMapComponent} 只保证"变更集的每个组件在
 * {@code GameMap} 里有同名者"，**挡不住**"两边同时多出一个同名的第 8 个组件"（那种漂移两边对称、反方向全绿），故另有 {@link
 * #changeSetHasExactlySevenComponents} 与 {@link #specIsDeliberatelyExcludedFromTheChangeSet} 兜住。
 */
class RoundTripComponentsTest {

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  private static final EdgeRef EDGE_AB = new EdgeRef(H_A, H_B);

  /** ★ 唯一的豁免集合。**加一项就是一次有意的决定，会在 diff 里现形。** */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of("spec");

  /** ★ **本任务的核心用例。** 逐组件：{@code empty()} → 只在该组件上与它不同的 target → 变更集 → 三条断言（见类注释）。 */
  @Test
  void everyGameMapComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : GameMap.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue; // ★ 唯一的豁免口，且 EXCLUDED 本身被 theExclusionListIsExactlySpec 钉死
      }
      String name = rc.getName();
      GameMap base = GameMap.empty();
      GameMap target = mutate(base, name);
      MapChangeSet cs = MapChangeSet.between(base, target);

      // ① 新组件在变更集里没有任何对应项 ⇒ 变更集整个是空的（这一条不依赖 name→访问器的映射）
      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      // ② 组件还在 GameMap 里、却从变更集的对应关系里掉了出去
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      // ③ 铁律 5 的原文：往返
      assertThat(MapChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /** ★ 钉死豁免集本身 —— 否则"加字段忘了改"的补救方式会变成"往豁免集里塞一项"，护栏就出现了一个正好等于新字段大小的洞。 */
  @Test
  void theExclusionListIsExactlySpec() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).containsExactly("spec");
  }

  /**
   * 反方向：变更集的每个组件都必须在 {@code GameMap} 里有同名的 record 组件。
   *
   * <p>★ 它**不是**上面那条循环的逆否 —— 两条合起来才是"两边组件集相同"。
   */
  @Test
  void everyChangeSetComponentCorrespondsToAGameMapComponent() {
    assertThat(componentNames(MapChangeSet.class))
        .as("变更集的每个组件都必须在 GameMap 里有同名的 record 组件")
        .isSubsetOf(componentNames(GameMap.class));
  }

  /**
   * ★ **R-48-g**（spec §9.1b 的 U2 守卫明文要求）：反射断言 {@code MapChangeSet} 的组件数恰为 **7**。
   *
   * <p>与 {@link #everyChangeSetComponentCorrespondsToAGameMapComponent} **不重复**：那条只保证"变更集的组件在
   * {@code GameMap} 里有同名者"，挡不住"两边**同时**多出一个同名的第 8 个组件"。
   */
  @Test
  void changeSetHasExactlySevenComponents() {
    assertThat(MapChangeSet.class.getRecordComponents())
        .as("变更集的组件数必须是 7（GameMap 是 8，差的那一个是有意排除的 spec）")
        .hasSize(7);
  }

  /**
   * ★ 显式钉住那个有意的 **8 vs 7** 不对称：{@code GameMap} 有 {@code spec}，{@code MapChangeSet} 没有。
   *
   * <p>最后一条断言是它的要害：**变更集组件 ∪ 豁免集必须恰好等于 {@code GameMap} 的全部组件** —— 多一项 = 有组件被"豁免"掉了（正是 V6 那类糊法），少一项
   * = 有组件谁都没管。
   */
  @Test
  void specIsDeliberatelyExcludedFromTheChangeSet() {
    assertThat(componentNames(GameMap.class)).as("spec 是 GameMap 的组件").contains("spec");
    assertThat(componentNames(MapChangeSet.class))
        .as("spec 有意不进变更集（它是生成输入、不是可变更状态）")
        .doesNotContain("spec");

    Set<String> covered = new LinkedHashSet<>(componentNames(MapChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 GameMap 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(GameMap.class));
  }

  /**
   * 造一个"只在 {@code name} 这个组件上与 {@code base} 不同"的图。
   *
   * <p>★ **{@code default} 必须抛**：新增组件时这个 {@code switch} 会当场响，**这正是想要的** —— 它迫使加字段的人来读这个测试。
   */
  private static GameMap mutate(GameMap base, String name) {
    return switch (name) {
      case "hexes" -> base.withHexes(oneHex());
      case "regions" -> base.withRegions(oneRegion());
      case "cities" -> base.withCities(oneCity());
      case "terrainTypes" -> base.withTerrainTypes(oneTerrainType());
      case "pathways" -> base.withPathways(onePathway());
      case "pathwayGroups" -> base.withPathwayGroups(onePathwayGroup());
      case "edges" -> base.withEdges(oneEdge());
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /**
   * 按组件名取出变更集里对应的那一份差异，问它"变了没有"。
   *
   * <p>★ **{@code default} 必须抛**（同 {@link #mutate}）：兜底会让"组件从变更集掉出去"变成**假绿**。
   */
  private static boolean changedOf(MapChangeSet cs, String name) {
    FieldDelta<?> delta =
        switch (name) {
          case "hexes" -> cs.hexes();
          case "regions" -> cs.regions();
          case "cities" -> cs.cities();
          case "terrainTypes" -> cs.terrainTypes();
          case "pathways" -> cs.pathways();
          case "pathwayGroups" -> cs.pathwayGroups();
          case "edges" -> cs.edges();
          default -> throw new IllegalStateException("未登记的组件: " + name);
        };
    return delta.changed();
  }

  /** 某个 record 类型的组件名，**按声明序**。 */
  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具：每个组件一份**非空**的合法取值（单键 {@code Map.of()} ⇒ 键序天然固定，不碰 copyOf/HashMap）──

  private static Map<HexCoord, HexCell> oneHex() {
    return Map.of(H_A, new HexCell("plains", 0.35));
  }

  private static Map<RegionId, Region> oneRegion() {
    return Map.of(
        new RegionId("r1"),
        Region.of(new RegionId("r1"), "区域 r1", Set.of(H_A), RegionMeta.empty()));
  }

  private static Map<CityId, City> oneCity() {
    return Map.of(
        new CityId("c1"),
        new City(new CityId("c1"), "城 c1", H_A, new RegionId("r1"), Map.of("population", 1000)));
  }

  private static Map<String, TerrainType> oneTerrainType() {
    return Map.of("plains", TerrainCatalog.of("plains"));
  }

  private static Map<PathwayId, Pathway> onePathway() {
    return Map.of(
        new PathwayId("p1"),
        new Pathway(new PathwayId("p1"), "线 p1", "road", List.of(EDGE_AB), Map.of("width", 2)));
  }

  private static Map<String, PathwayGroup> onePathwayGroup() {
    return Map.of("road", new PathwayGroup("road", "组 road", "#8B7355", null, true, Map.of()));
  }

  private static Map<EdgeRef, EdgeTags> oneEdge() {
    return Map.of(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2))));
  }
}
