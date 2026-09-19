package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 区域操作面（M8 spec §二，S4 的"语义化命令"）：**规则放领域模块**，Core 只转发信封（ADR-1 / 铁律 4）。
 *
 * <p>★ **每个操作都是纯函数**（只读 {@code base}，产出走 {@link MapChangeSet}）；变更集**唯一**的生产路径是 {@link
 * MapChangeSet#between(GameMap, GameMap)}——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉）。
 *
 * <p>★★ **重叠一律允许**（M8-U1，用户原话：「hex 只是地形块，应当兼容多种从属」）：一个 hex 可同时属于多个区域，
 * **不存在"重叠时谁赢"**。本类**没有任何"与已有区域相交就拒绝"的校验**，也**不裁剪** hex 集合——重叠是正常状态。
 *
 * <p>★ **边界自洽**：区域一律经 {@link Region#of} 构造（它由 {@code hexes} 算出 {@code boundary}），**不手造 boundary
 * 塞进构造器**——{@code Region} 的紧凑构造器会重算并比对，不等即抛（U2 的钉子）。故"漂移"在构造期就不可能存在。
 *
 * <p>★ **存在性**：{@code Create} 对已存在 id **拒绝**（不静默覆盖）；{@code Update}/{@code Delete} 对不存在的 id
 * **拒绝**（不做静默幂等）。{@link RegionId} **由调用方给**（Q3：自动生成会让"同一操作两次不同"，破坏可复现与可断言）。
 *
 * <p>★ **逐组件独立性**：三个操作都只换 {@code regions} 组件（{@link GameMap#withRegions}），故 {@code hexes}（高度）与
 * {@code terrainBlocks} 恒 {@code Unchanged}——由 {@link MapChangeSet#between} 逐组件比较保证。
 */
public final class RegionOperations {

  private RegionOperations() {}

  /**
   * 新建区域。{@code regionId} 已存在 ⇒ 拒绝（不静默覆盖）；{@code hexes} 不得为空、每格都必须在图上。
   *
   * @param base 现图（只读）
   * @param id 区域身份；**调用方给**，不得与已有区域重复
   * @param name 区域名；不得为空白（{@link Region} 构造器校验）
   * @param hexes 区域内容；**不得为空**，每格必须在 {@code base.hexes()} 里
   * @param meta 元数据；{@code null} ⇒ {@link RegionMeta#empty()}（{@link Region} 构造器兜底）
   * @return 只有 {@code regions} 非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 重复 id / 空 hexes / 图外 hex
   */
  public static MapChangeSet createRegion(
      GameMap base, RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(hexes, "hexes");
    if (base.regions().containsKey(id)) {
      throw new IllegalArgumentException("区域已存在: " + id);
    }
    requireNonEmptyHexesInMap(base, hexes);
    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    for (Region other : base.regions().values()) {
      if (!java.util.Collections.disjoint(other.hexes(), hexes)) {
        throw new IllegalArgumentException("区域与已有区域重叠: " + other.id());
      }
    }
    next.put(id, Region.of(id, name, hexes, meta));
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * 改区域。目标不存在 ⇒ 拒绝；{@code hexes} 与 {@code meta} **至少给一个**（都给就都改）。给 {@code hexes} 时不得为空、 每格都必须在图上；改
   * hexes ⇒ **重算边界**（经 {@link Region#of}，不手造）。
   *
   * <p>★ 改成与别的区域重叠**不报错**（M8-U1）。
   *
   * @param base 现图（只读）
   * @param id 目标区域身份
   * @param hexes 新内容；{@code null} ⇒ 不改内容
   * @param meta 新元数据；{@code null} ⇒ 不改元数据
   * @return 只有 {@code regions} 非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 目标不存在 / 二者皆未给 / 空 hexes / 图外 hex
   */
  public static MapChangeSet updateRegion(
      GameMap base, RegionId id, Set<HexCoord> hexes, RegionMeta meta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    if (hexes == null && meta == null) {
      throw new IllegalArgumentException("map.UpdateRegion 必须至少给 hexes 与 meta 之一");
    }
    Region existing = base.regions().get(id);
    if (existing == null) {
      throw new IllegalArgumentException("区域不存在: " + id);
    }
    if (hexes != null) {
      requireNonEmptyHexesInMap(base, hexes);
    }
    Set<HexCoord> nextHexes = hexes == null ? existing.hexes() : hexes;
    RegionMeta nextMeta = meta == null ? existing.meta() : meta;
    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    // put 已存在的 key 不改 LinkedHashMap 的插入序（区域名下的位置稳定）。
    next.put(id, Region.of(id, existing.name(), nextHexes, nextMeta));
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * 删区域。目标不存在 ⇒ 拒绝（**不做静默幂等**）。删除后该区域从 {@code regions} 消失，{@link GameMap#regionIndex()} 里每个 hex
   * 的从属**少一个**（若只属它 ⇒ 变成无从属），**不悬空**。
   *
   * @param base 现图（只读）
   * @param id 目标区域身份
   * @return 只有 {@code regions} 非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 目标不存在
   */
  public static MapChangeSet deleteRegion(GameMap base, RegionId id) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    if (!base.regions().containsKey(id)) {
      throw new IllegalArgumentException("区域不存在: " + id);
    }
    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    next.remove(id);
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * hexes 非空 + 每格在图上。**按自然序遍历**报第一个坏格（确定性错误消息，不取 Set 迭代序）。
   *
   * <p>★ **只校验"在不在图上"**，绝不校验"与别的区域是否相交"（M8-U1：重叠是正常状态，不报错、不裁剪）。
   */
  private static void requireNonEmptyHexesInMap(GameMap base, Set<HexCoord> hexes) {
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("hexes 不得为空：一个区域至少要有一格");
    }
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(Comparator.naturalOrder());
    for (HexCoord hex : ordered) {
      if (!base.hexes().containsKey(hex)) {
        throw new IllegalArgumentException("hex 不在图上: " + hex);
      }
    }
  }
}
