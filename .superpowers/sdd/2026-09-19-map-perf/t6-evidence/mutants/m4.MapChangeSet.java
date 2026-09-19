package io.mosire.simos.map.change;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;
import java.util.function.Function;

/**
 * 地图状态的变更集。**组件与 {@link GameMap} 的 record 组件一一对应。**
 *
 * <p>铁律 5：变更集从完整状态类型派生。GSimulator 的 {@code MapDiff} 是**手工对着 MapData 维护**的， 后果是 6 个字段漂移出去且零守卫 ——
 * {@code terrainBlocks}/{@code terrainTypes}/{@code pathwayGroups}/{@code edges}，加 {@code
 * gridSize}/{@code hexOrientation}（见 spec §7.3）；其中**前四个**是"该进变更集而没进"的那一类。 本类型由 {@link
 * RoundTripComponentsTest} 的**反射枚举**把守 —— 新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **8 个组件**（{@code GameMap} 是 9 个）：{@code spec} **不进变更集**（它是生成输入、不是可变更状态）， 这个不对称是**有意的**，见
 * {@link #apply(MapChangeSet, GameMap)}。{@code RegionIndex} 与 {@code terrainAt} 的 hex → 块反查同理不进
 * （它们是派生件），而 {@code Region.boundary()}/{@code TerrainBlock.boundary()} 是各自值的**组件**， 故随 {@code
 * regions}/{@code terrainBlocks} 整体比对，**不为它们单开组件**。
 *
 * <p>★ **P1 之后 {@code hexes} 只承载高度**：改地形**不再**动 {@code hexes}，而是进 {@code terrainBlocks} （{@code
 * RegionRandomizer} 即此例——高度不变 ⇒ {@code hexes} 是 {@code Unchanged}）。
 *
 * <p>★ **key 的规范串一律取 {@code toString()}**（R-48-f）：{@code HexCoord} 的 {@code "q_r"}、 {@code EdgeRef}
 * 的 {@code "a|b"}、{@link BlockId} 的 {@code "terrain@q_r"}、三个 ID 的裸值 —— 各 key 类型都备齐了 "裸值 {@code
 * toString()} + {@code static parse}"，{@link #apply} 侧用对应的 {@code parse} 还原。 {@code terrainTypes} 与
 * {@code pathwayGroups} 的 key **本来就是 {@code String}**（R-48-j） ⇒ 那两处的 {@code keyOf} 是恒等、 {@code
 * parse} 也是恒等，**不要**给它们写一个凭空的 parse。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十）：该接口已收窄为**标记接口**（原 {@code baseRevision()}
 * 版本戳已删），故实现它不带来任何新义务——只是把"这是变更集"这件事落进类型系统。
 */
public record MapChangeSet(
    FieldDelta<HexCell> hexes,
    FieldDelta<TerrainBlock> terrainBlocks,
    FieldDelta<Region> regions,
    FieldDelta<City> cities,
    FieldDelta<TerrainType> terrainTypes,
    FieldDelta<Pathway> pathways,
    FieldDelta<PathwayGroup> pathwayGroups,
    FieldDelta<EdgeTags> edges)
    implements ChangeSet {

  /** {@code terrainTypes}/{@code pathwayGroups} 的 key 是 String ⇒ 解析这一步是恒等（R-48-j）。 */
  private static final Function<String, String> STRING_KEY = Function.identity();

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static MapChangeSet between(GameMap base, GameMap target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new MapChangeSet(
        FieldDelta.diff(base.hexes(), target.hexes()),
        new FieldDelta.Unchanged<>(), // MUTANT m4：between 不装 terrainBlocks
        FieldDelta.diff(base.regions(), target.regions()),
        FieldDelta.diff(base.cities(), target.cities()),
        FieldDelta.diff(base.terrainTypes(), target.terrainTypes()),
        FieldDelta.diff(base.pathways(), target.pathways()),
        FieldDelta.diff(base.pathwayGroups(), target.pathwayGroups()),
        FieldDelta.diff(base.edges(), target.edges()));
  }

  /**
   * 逐组件重建。铁律 5 的原文。
   *
   * <p>★ **R-48-e：8 个组件逐一从变更集重建，`spec` 从 `base` 原样带过来。** 变更集里没有 `spec`（它是生成输入、不是可变更状态），所以**只有它**取自
   * base —— 这一条要写进 Javadoc，否则后人会以为 `spec` 是漏掉的。 ★ **不得对 `spec` 写任何 null 兜底**（如 `cs.spec() != null ?
   * … : base.spec()`）： `GameMap.spec` 从 Task 5 起就非 null，兜底是**为不存在的世界写的代码**，且会掩盖真的漏传。
   *
   * <p>★ 四条变体各一路（见 {@link FieldDelta}）：{@code Patch} ⇒ **先删后增**的两路，**递归复用** {@code Remove}/{@code
   * Upsert} 那两路（不重实现）；{@code Unchanged} ⇒ base 的那一份**原样**（连键序都不动）； {@code Upsert} ⇒ 在 base
   * 的那一份上**新增或覆盖**，base 的键序不变、新键按 Upsert 的序追加； {@code Remove} ⇒ 从 base 的那一份上删。**只有新出现的 key 需要
   * {@code parse}**：已在 base 里的 key 直接复用原对象（这正是 R-48-f 那五个 key 类型的三件套被用到的地方）。
   *
   * <p>★ 重建出的图**过一遍构造期的分割不变式**：一个自洽的变更集必得出自洽的图，不一致会当场抛而不是静默。
   */
  public static GameMap apply(MapChangeSet cs, GameMap base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new GameMap(
        FieldDelta.rebuild(base.hexes(), cs.hexes(), HexCoord::parse),
        FieldDelta.rebuild(base.terrainBlocks(), cs.terrainBlocks(), BlockId::parse),
        FieldDelta.rebuild(base.regions(), cs.regions(), RegionId::parse),
        FieldDelta.rebuild(base.cities(), cs.cities(), CityId::parse),
        FieldDelta.rebuild(base.terrainTypes(), cs.terrainTypes(), STRING_KEY),
        FieldDelta.rebuild(base.pathways(), cs.pathways(), PathwayId::parse),
        FieldDelta.rebuild(base.pathwayGroups(), cs.pathwayGroups(), STRING_KEY),
        FieldDelta.rebuild(base.edges(), cs.edges(), EdgeRef::parse),
        base.spec());
  }

  /** 是否所有组件都未变。**逐组件问一遍** —— 少问任何一个，"只改了一条边"就会产生空变更集。 */
  public boolean isEmpty() {
    return !(hexes.changed()
        || terrainBlocks.changed()
        || regions.changed()
        || cities.changed()
        || terrainTypes.changed()
        || pathways.changed()
        || pathwayGroups.changed()
        || edges.changed());
  }
}
