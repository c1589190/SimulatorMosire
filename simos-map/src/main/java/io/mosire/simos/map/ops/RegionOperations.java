package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
   * ★★ <b>合并多个区域进目标区域</b>（P1.2 区划语义）：目标保留身份/名称/元数据，内容取并集；源区域整条删除。
   *
   * <p>★ <b>只改 map 自己的 {@code regions}</b>：jurisdiction / 城市 region / 税率 / 编制等跟随重算由 app 组合根用 {@code
   * CommandBus.submitBatch} 协调（铁律 3/4：map 不认识它们）。
   *
   * @param target 目标区域（保留身份；必须已存在）
   * @param sources 被并入的区域（非空、必须都存在、不得含 target）
   * @throws IllegalArgumentException 目标/源不存在、sources 为空或含 target、id 重复
   */
  public static MapChangeSet mergeRegions(GameMap base, RegionId target, Set<RegionId> sources) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(sources, "sources");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("map.MergeRegions 的 sourceRegionIds 不得为空");
    }
    Region targetRegion = requireRegion(base, target);
    LinkedHashSet<HexCoord> merged = new LinkedHashSet<>(targetRegion.hexes());
    for (RegionId sourceId : sources) {
      Objects.requireNonNull(sourceId, "sources 的元素");
      if (sourceId.equals(target)) {
        throw new IllegalArgumentException("map.MergeRegions 的源区域不得包含目标区域自身: " + target);
      }
      Region source = requireRegion(base, sourceId);
      merged.addAll(source.hexes());
    }
    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    next.put(target, targetRegion.withHexes(Set.copyOf(merged)));
    for (RegionId sourceId : sources) {
      next.remove(sourceId);
    }
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * ★★ <b>拆分一个区域</b>（P1.2 区划语义）：{@code parts} 是新的区域；{@code keepSource=false} 时源区域内容必须恰好被 parts
   * 覆盖（源被删除），{@code keepSource=true} 时 parts 是源的真子集、源保留剩余格（身份/名称/元数据不变）。
   *
   * <p>★ 新区域 id 由调用方给（可复现）；新区域的 {@code boundary} 一律经 {@link Region#of} 从 hexes 重算。
   *
   * @throws IllegalArgumentException 源不存在 / parts 为空 / 新 id 已存在或重复 / hexes 不在源内 / parts 相交 /
   *     keepSource 与覆盖关系矛盾
   */
  public static MapChangeSet splitRegion(
      GameMap base, RegionId sourceId, List<RegionPart> parts, boolean keepSource) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(parts, "parts");
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("map.SplitRegion 的 parts 不得为空");
    }
    Region source = requireRegion(base, sourceId);
    Set<RegionId> newIds = new LinkedHashSet<>();
    LinkedHashSet<HexCoord> covered = new LinkedHashSet<>();
    for (RegionPart part : parts) {
      Objects.requireNonNull(part, "parts 的元素");
      if (part.id().equals(sourceId)) {
        throw new IllegalArgumentException("map.SplitRegion 的新区域 id 不得等于源区域: " + sourceId);
      }
      if (base.regions().containsKey(part.id())) {
        throw new IllegalArgumentException("map.SplitRegion 的新区域 id 已存在: " + part.id());
      }
      if (!newIds.add(part.id())) {
        throw new IllegalArgumentException("map.SplitRegion 的新区域 id 重复: " + part.id());
      }
      if (part.hexes().isEmpty()) {
        throw new IllegalArgumentException("map.SplitRegion 的每个 part 至少要有一格: " + part.id());
      }
      for (HexCoord hex : part.hexes()) {
        if (!base.hexes().containsKey(hex)) {
          throw new IllegalArgumentException("hex 不在图上: " + hex);
        }
        if (!source.hexes().contains(hex)) {
          throw new IllegalArgumentException("hex 不在源区域 " + sourceId + " 内: " + hex);
        }
        if (!covered.add(hex)) {
          throw new IllegalArgumentException(
              "map.SplitRegion 的 parts 相交：hex " + hex + " 出现在多个 part 里");
        }
      }
    }
    if (keepSource) {
      if (covered.isEmpty()) {
        throw new IllegalArgumentException("map.SplitRegion 且 keepSource=true 时 parts 至少覆盖一格");
      }
      Set<HexCoord> residual = new LinkedHashSet<>(source.hexes());
      residual.removeAll(covered);
      if (residual.isEmpty()) {
        throw new IllegalArgumentException(
            "map.SplitRegion 且 keepSource=true 时剩余为空；请用 keepSource=false 让源区域被 parts 取代");
      }
      Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
      next.put(sourceId, source.withHexes(Set.copyOf(residual)));
      for (RegionPart part : parts) {
        next.put(part.id(), Region.of(part.id(), part.name(), part.hexes(), part.meta()));
      }
      return MapChangeSet.between(base, base.withRegions(next));
    }
    if (!covered.equals(source.hexes())) {
      throw new IllegalArgumentException(
          "map.SplitRegion 且 keepSource=false 时 parts 必须恰好覆盖源区域全部 "
              + source.hexes().size()
              + " 格（实际覆盖 "
              + covered.size()
              + " 格）");
    }
    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    next.remove(sourceId);
    for (RegionPart part : parts) {
      next.put(part.id(), Region.of(part.id(), part.name(), part.hexes(), part.meta()));
    }
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * ★★ <b>把指定 hex 从若干源区域划给目标区域</b>（P1.2 区划语义）：目标与源都必须已存在；每个 hex 至少属于一个源； 任何源被划空 ⇒ 拒（空区域没有语义，请用
   * Merge/Split 显式处理）。
   *
   * <p>★ 重叠是 M8-U1 允许的正常状态；本命令只做"从这些源删、往目标加"的显式语义，不猜重叠里的"真正归属"。
   *
   * @throws IllegalArgumentException 目标/源不存在 / sources 为空或含 target / hex 不在图上 / hex 不在任何源里 / 某源会被划空
   */
  public static MapChangeSet reassignHexes(
      GameMap base, RegionId target, Set<RegionId> sources, Set<HexCoord> hexes) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(hexes, "hexes");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("map.ReassignHexes 的 fromRegionIds 不得为空");
    }
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("map.ReassignHexes 的 hexes 不得为空");
    }
    Region targetRegion = requireRegion(base, target);
    for (RegionId sourceId : sources) {
      Objects.requireNonNull(sourceId, "sources 的元素");
      if (sourceId.equals(target)) {
        throw new IllegalArgumentException("map.ReassignHexes 的源区域不得包含目标区域自身: " + target);
      }
      requireRegion(base, sourceId);
    }
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(Comparator.naturalOrder());
    for (HexCoord hex : ordered) {
      if (!base.hexes().containsKey(hex)) {
        throw new IllegalArgumentException("hex 不在图上: " + hex);
      }
      boolean owned = false;
      for (RegionId sourceId : sources) {
        if (base.regions().get(sourceId).hexes().contains(hex)) {
          owned = true;
          break;
        }
      }
      if (!owned) {
        throw new IllegalArgumentException("hex " + hex + " 不属于任何源区域: " + sources);
      }
    }

    Map<RegionId, Region> next = new LinkedHashMap<>(base.regions());
    for (RegionId sourceId : sources) {
      Region source = next.get(sourceId);
      LinkedHashSet<HexCoord> remaining = new LinkedHashSet<>(source.hexes());
      remaining.removeAll(hexes);
      if (remaining.isEmpty()) {
        throw new IllegalArgumentException(
            "源区域 "
                + sourceId
                + " 会被 map.ReassignHexes 划空；请改用 map.MergeRegions / map.SplitRegion / map.DeleteRegion");
      }
      if (remaining.size() != source.hexes().size()) {
        next.put(sourceId, source.withHexes(Set.copyOf(remaining)));
      }
    }
    LinkedHashSet<HexCoord> targetHexes = new LinkedHashSet<>(targetRegion.hexes());
    targetHexes.addAll(hexes);
    next.put(target, targetRegion.withHexes(Set.copyOf(targetHexes)));
    return MapChangeSet.between(base, base.withRegions(next));
  }

  /**
   * 一个拆分出去的新区域（{@code map.SplitRegion} 的 part 形状）。
   *
   * <p>构造期冻结 hexes（保序 {@link LinkedHashSet} + 不可变包装），与 {@link Region} 的落盘序口径一致。
   */
  public record RegionPart(RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) {

    public RegionPart {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(hexes, "hexes");
      hexes =
          Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(hexes, "hexes")));
    }
  }

  /** 区域必须存在（不存在 ⇒ 抛；不做静默创建）。 */
  private static Region requireRegion(GameMap base, RegionId id) {
    Region region = base.regions().get(id);
    if (region == null) {
      throw new IllegalArgumentException("区域不存在: " + id);
    }
    return region;
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
