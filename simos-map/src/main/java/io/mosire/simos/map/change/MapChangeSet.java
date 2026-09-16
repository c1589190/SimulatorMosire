package io.mosire.simos.map.change;

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
import io.mosire.simos.map.terrain.TerrainType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 地图状态的变更集。**组件与 {@link GameMap} 的 record 组件一一对应。**
 *
 * <p>铁律 5：变更集从完整状态类型派生。GSimulator 的 {@code MapDiff} 是**手工对着 MapData 维护**的， 后果是 6 个组件漂移出去且零守卫。本类型由
 * {@code MapChangeSetTest} 的**反射枚举**把守 —— 新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **7 个组件**（{@code GameMap} 是 8 个）：{@code spec} **不进变更集**（它是生成输入、不是可变更状态）， 这个不对称是**有意的**，见
 * {@link #apply(MapChangeSet, GameMap)}。{@code RegionIndex} 同理不进 （它是 {@code regions} 的纯函数），而 {@code
 * Region.boundary()} 是 {@code Region} 的**组件**， 故随 {@code regions} 的值整体比对，**不为它单开组件**。
 *
 * <p>★ **key 的规范串一律取 {@code toString()}**（R-48-f）：{@code HexCoord} 的 {@code "q_r"}、 {@code EdgeRef}
 * 的 {@code "a|b"}、三个 ID 的裸值 —— 五个 key 类型各自都备齐了 "裸值 {@code toString()} + {@code static
 * parse}"，{@link #apply} 侧用对应的 {@code parse} 还原。 {@code terrainTypes} 与 {@code pathwayGroups} 的 key
 * **本来就是 {@code String}**（R-48-j） ⇒ 那两处的 {@code keyOf} 是恒等、{@code parse} 也是恒等，**不要**给它们写一个凭空的
 * parse。
 */
public record MapChangeSet(
    FieldDelta<HexCell> hexes,
    FieldDelta<Region> regions,
    FieldDelta<City> cities,
    FieldDelta<TerrainType> terrainTypes,
    FieldDelta<Pathway> pathways,
    FieldDelta<PathwayGroup> pathwayGroups,
    FieldDelta<EdgeTags> edges) {

  /** {@code terrainTypes}/{@code pathwayGroups} 的 key 是 String ⇒ 解析这一步是恒等（R-48-j）。 */
  private static final Function<String, String> STRING_KEY = Function.identity();

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static MapChangeSet between(GameMap base, GameMap target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new MapChangeSet(
        diff(base.hexes(), target.hexes(), "hexes"),
        diff(base.regions(), target.regions(), "regions"),
        diff(base.cities(), target.cities(), "cities"),
        diff(base.terrainTypes(), target.terrainTypes(), "terrainTypes"),
        diff(base.pathways(), target.pathways(), "pathways"),
        diff(base.pathwayGroups(), target.pathwayGroups(), "pathwayGroups"),
        diff(base.edges(), target.edges(), "edges"));
  }

  /**
   * 逐组件重建。铁律 5 的原文。
   *
   * <p>★ **R-48-e：7 个组件逐一从变更集重建，`spec` 从 `base` 原样带过来。** 变更集里没有 `spec`（它是生成输入、不是可变更状态），所以**只有它**取自
   * base —— 这一条要写进 Javadoc，否则后人会以为 `spec` 是漏掉的。 ★ **不得对 `spec` 写任何 null 兜底**（如 `cs.spec() != null ?
   * … : base.spec()`）： `GameMap.spec` 从 Task 5 起就非 null，兜底是**为不存在的世界写的代码**，且会掩盖真的漏传。
   *
   * <p>★ 三条变体各一路（见 {@link FieldDelta}）：{@code Unchanged} ⇒ base 的那一份**原样**（连键序都不动）； {@code Upsert}
   * ⇒ 在 base 的那一份上**新增或覆盖**，base 的键序不变、新键按 Upsert 的序追加； {@code Remove} ⇒ 从 base 的那一份上删。**只有新出现的 key
   * 需要 {@code parse}**：已在 base 里的 key 直接复用原对象（这正是 R-48-f 那五个 key 类型的三件套被用到的地方）。
   */
  public static GameMap apply(MapChangeSet cs, GameMap base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new GameMap(
        rebuild(base.hexes(), cs.hexes(), HexCoord::parse),
        rebuild(base.regions(), cs.regions(), RegionId::parse),
        rebuild(base.cities(), cs.cities(), CityId::parse),
        rebuild(base.terrainTypes(), cs.terrainTypes(), STRING_KEY),
        rebuild(base.pathways(), cs.pathways(), PathwayId::parse),
        rebuild(base.pathwayGroups(), cs.pathwayGroups(), STRING_KEY),
        rebuild(base.edges(), cs.edges(), EdgeRef::parse),
        base.spec());
  }

  /** 是否所有组件都未变。**逐组件问一遍** —— 少问任何一个，"只改了一条边"就会产生空变更集。 */
  public boolean isEmpty() {
    return !(hexes.changed()
        || regions.changed()
        || cities.changed()
        || terrainTypes.changed()
        || pathways.changed()
        || pathwayGroups.changed()
        || edges.changed());
  }

  /**
   * 一个组件的差异。**顺着 {@code target} 的迭代序读**，故 upsert 的键序 = target 的序（保序不可变是前提）。
   *
   * <p>★ **同时有"增"与"删"时当场抛**（{@code FieldDelta} 的一条组件只能表达一种）。抛而**不丢弃** ——
   * 丢哪一侧都是静默的数据损失，正是本任务要根除的那类病。
   */
  private static <K, V> FieldDelta<V> diff(Map<K, V> base, Map<K, V> target, String component) {
    Map<String, V> upserts = new LinkedHashMap<>();
    Set<String> removals = new LinkedHashSet<>();
    for (Map.Entry<K, V> entry : target.entrySet()) {
      if (!entry.getValue().equals(base.get(entry.getKey()))) {
        // 同 key 不同 value 与"新增的 key"走同一条：base.get 缺席即 null，equals 必为 false。
        upserts.put(entry.getKey().toString(), entry.getValue());
      }
    }
    for (K key : base.keySet()) {
      if (!target.containsKey(key)) {
        removals.add(key.toString());
      }
    }
    if (upserts.isEmpty() && removals.isEmpty()) {
      return new FieldDelta.Unchanged<>();
    }
    if (upserts.isEmpty()) {
      return new FieldDelta.Remove<>(removals);
    }
    if (removals.isEmpty()) {
      return new FieldDelta.Upsert<>(upserts);
    }
    throw new UnsupportedOperationException(
        component
            + " 同时有新增/覆盖与删除，FieldDelta 的一条组件表达不了（见 FieldDelta 的类注释）："
            + " upserts="
            + upserts.keySet()
            + ", removals="
            + removals
            + "。请拆成两条变更集先后 apply。");
  }

  /** 一个组件的重建。三条变体各一路，见 {@link #apply(MapChangeSet, GameMap)}。 */
  private static <K, V> Map<K, V> rebuild(
      Map<K, V> base, FieldDelta<V> delta, Function<String, K> parse) {
    if (!delta.changed()) {
      return base;
    }
    if (delta instanceof FieldDelta.Remove<V> remove) {
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        if (!remove.keys().contains(entry.getKey().toString())) {
          out.put(entry.getKey(), entry.getValue());
        }
      }
      return out;
    }
    if (delta instanceof FieldDelta.Upsert<V> upsert) {
      Map<String, V> entries = upsert.entries();
      Set<String> fromBase = new LinkedHashSet<>();
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        String key = entry.getKey().toString();
        fromBase.add(key);
        out.put(entry.getKey(), entries.containsKey(key) ? entries.get(key) : entry.getValue());
      }
      for (Map.Entry<String, V> entry : entries.entrySet()) {
        if (!fromBase.contains(entry.getKey())) {
          out.put(parse.apply(entry.getKey()), entry.getValue());
        }
      }
      return out;
    }
    // 三条变体已穷尽；走到这里说明 FieldDelta 新增了变体而这里没跟上 —— 与铁律 5 同源的漂移，必须响。
    throw new IllegalStateException("未知的 FieldDelta 变体: " + delta.getClass());
  }
}
