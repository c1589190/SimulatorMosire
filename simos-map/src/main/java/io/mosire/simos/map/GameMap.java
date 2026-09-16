package io.mosire.simos.map;

import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionIndex;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地图状态。**本项目的完整状态类型** —— 铁律 5 的"变更集从完整状态类型派生"指的就是它：Task 6 的 {@code MapChangeSet} 逐组件从它派生，Task 7
 * 的往返用反射逐组件枚举它（漏一个组件，那条用例自动红）。
 *
 * <p>与 GSimulator 的 {@code MapData}（12 组件）逐条对照：
 *
 * <ul>
 *   <li>删 {@code gridSize} —— 恒 30 的死值，不参与取格，与真实半径 80 矛盾
 *   <li>删 {@code hexOrientation} —— 恒 false、无读取分支，而实际公式是 pointy-top
 *   <li>删 {@code rivers}/{@code roads} —— 两个已废弃 record，语义由 {@link Pathway} 承载
 *   <li>删 {@code terrainBlocks} —— 编辑 Command 的历史，不是状态
 *   <li>删 {@code compressedRegions} —— 渲染缓存，可随时重算
 *   <li>加 {@code pathways} —— 取代废弃的 rivers/roads
 *   <li>加 {@code spec} —— 落盘 seed 与全部生成参数（L7）
 * </ul>
 *
 * <p>★ **7 个 Map 一律保序不可变**（{@link Collections#unmodifiableMap} 套 {@link LinkedHashMap}，**不是**
 * {@code Map.copyOf}）：老仓用 {@code Map.copyOf} 冻结，迭代序按哈希表散开，同一份数据因此会产出不同字节，字节级往返不成立。逐个键值查 null
 * 也是**故意保留老仓的行为**（{@code Map.copyOf} 本就拒 null），本类型只把**顺序**那一项换掉。
 *
 * <p>★ **两个派生件都不进组件、不进变更集**：{@link #regionIndex()}（算出来的）与 {@code Region.boundary()}（U2 之后**是**
 * {@code Region} 的组件，故"取边界"只有 {@code regions().get(id).boundary()} 这一条路，**没有** {@code
 * boundaryOf}）。两者地位相反，别混。
 *
 * @param hexes 格 → 格内容
 * @param regions 区域 id → 区域（**含它的边界**，U2）
 * @param cities 城市 id → 城市
 * @param terrainTypes 地形 key → 类型定义（唯一的 {@code TerrainCatalog}）
 * @param pathways 线 id → 线
 * @param pathwayGroups 组 id → 组定义
 * @param edges 边 → 边上的标注（**唯一主存储**，L2）
 * @param spec 生成参数；**从不 null**（R-48-e）
 */
public record GameMap(
    Map<HexCoord, HexCell> hexes,
    Map<RegionId, Region> regions,
    Map<CityId, City> cities,
    Map<String, TerrainType> terrainTypes,
    Map<PathwayId, Pathway> pathways,
    Map<String, PathwayGroup> pathwayGroups,
    Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec) {

  public GameMap {
    // ★ 冻结那一步**写在赋值处**，不藏进 helper：SpotBugs 只认它**看得见**的 Collections.unmodifiableMap，
    //   藏在私有方法里时判不出字段不可变，给 7 条 EI_EXPOSE_REP（实测见 task-5-evidence/gate-clean-verify.txt）。
    //   `copyOf` 只负责拷贝与逐个键值查 null，冻结仍由这里做 —— 与 EdgeTags/Pathway/PathwayGroup 同形制。
    hexes = Collections.unmodifiableMap(copyOf(hexes, "hexes"));
    regions = Collections.unmodifiableMap(copyOf(regions, "regions"));
    cities = Collections.unmodifiableMap(copyOf(cities, "cities"));
    terrainTypes = Collections.unmodifiableMap(copyOf(terrainTypes, "terrainTypes"));
    pathways = Collections.unmodifiableMap(copyOf(pathways, "pathways"));
    pathwayGroups = Collections.unmodifiableMap(copyOf(pathwayGroups, "pathwayGroups"));
    edges = Collections.unmodifiableMap(copyOf(edges, "edges"));
    // ★ R-48-e：不留"临时可空"。空图的种子是 GenerationSpec.defaults(0L)，**不是** null。
    if (spec == null) {
      throw new IllegalArgumentException("spec 不得为 null：空图的种子用 GenerationSpec.defaults(0L)");
    }
  }

  /**
   * 空图。**所有 Map 都用保序不可变包装**（{@code Map.copyOf} 会打乱顺序）—— 这里是"空"的规范起点，Task 7 的往返用例即从这里起手。
   *
   * <p>★ 种子 {@code 0L} 是**"空图的种子"**，不是"没有种子"：空图没有生成历史，故取规范值。不要为了"看起来诚实"改成 null。
   */
  public static GameMap empty() {
    return new GameMap(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /**
   * 逐组件替换。**8 个 with 方法** —— 取代 GSimulator 的 12 参数构造复制（L8：主源码 17 处、全仓 36 处）。
   *
   * <p>★ 每个方法**只动自己那一个组件**：其余 7 个原样带过去（连键序都不变）。这正是 {@code withMethodsPreserveOtherComponents} 盯的事。
   */
  public GameMap withHexes(Map<HexCoord, HexCell> v) {
    return new GameMap(v, regions, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withRegions(Map<RegionId, Region> v) {
    return new GameMap(hexes, v, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withCities(Map<CityId, City> v) {
    return new GameMap(hexes, regions, v, terrainTypes, pathways, pathwayGroups, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withTerrainTypes(Map<String, TerrainType> v) {
    return new GameMap(hexes, regions, cities, v, pathways, pathwayGroups, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withPathways(Map<PathwayId, Pathway> v) {
    return new GameMap(hexes, regions, cities, terrainTypes, v, pathwayGroups, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withPathwayGroups(Map<String, PathwayGroup> v) {
    return new GameMap(hexes, regions, cities, terrainTypes, pathways, v, edges, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withEdges(Map<EdgeRef, EdgeTags> v) {
    return new GameMap(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, v, spec);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。 */
  public GameMap withSpec(GenerationSpec v) {
    return new GameMap(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, v);
  }

  /**
   * 派生：归属反向索引。**不进组件、不进变更集、不进存档**（与 U2 之后**入存储的** {@code Region.boundary} 不是一回事，别混）。
   *
   * <p>每次调用**重算**：它是 {@code regions} 的纯函数，缓存就是第二份可漂移的副本（GSimulator 的 {@code compressedRegions}
   * 正是那个形态）。
   */
  public RegionIndex regionIndex() {
    return RegionIndex.of(regions.values());
  }

  /**
   * 拷一份并逐个键值查 null（**冻结由构造器在赋值处做**，见构造器里的说明）。
   *
   * <p>★ {@link LinkedHashMap} 而不是 {@code HashMap}/{@code Map.copyOf} —— 迭代序 =
   * 插入序是落盘序的前提；只拷贝不改序的那一步在这里，冻结那一步在上面。
   */
  private static <K, V> Map<K, V> copyOf(Map<K, V> source, String name) {
    if (source == null) {
      throw new IllegalArgumentException(name + " 不得为 null");
    }
    Map<K, V> copy = new LinkedHashMap<>();
    for (Map.Entry<K, V> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(name + " 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
