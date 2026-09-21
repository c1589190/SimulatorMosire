package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * hex → **所属区域集合** 的反向索引。**派生，不进变更集、不进存档**（与 U2 之后**入存储的** {@code Region.boundary} 不是一回事，别混）。
 *
 * <p>★ **从属是多对多**（M8-U1，用户裁定）：一个 hex 可**同时属于多个区域**——"hex 只是地形块，应当兼容多种从属"， 不存在"重叠时谁赢"。故 {@link
 * #regionOf} 返回**全部**归属区域，按 {@link RegionId} 字典序（{@code value()} 的字符串序）
 * 排列：同一批区域无论以什么顺序进来，都得同一份有序列表（输出可逐字节复现）。
 *
 * <p>★ 解 L5：GSimulator 有 6 处逐字重复的 {@code for (entry : map.provinces()) if (hexes.contains(key))}
 * 线性扫描（其中一处**不 break**，每次渲染都全扫），故每次 {@code hex:{q}_{r}} 地址解析都全表扫。本类把 {@link #regionOf} 做成 **O(1)**。
 *
 * <p>★ **本类不承载层次**（V3）：{@link #regionOf} 的字典序只为可复现；"最顶层区域"（定义序末位）由调用方按 {@code GameMap.regions()}
 * 的插入序派生（见 {@code MapResolver.regionOfHex}）—— **不要**把本类的次序当层次。
 */
public final class RegionIndex {

  private final Map<HexCoord, List<RegionId>> byHex;

  /**
   * ★ 只给测试注入**计数包装层**用（见 {@code RegionIndexGuardTest#L5_regionOfIsIndexedNotScanned}）：生产路径是 {@link
   * #of(Collection)}。**故意不做防御性拷贝** —— 拷贝一份就没法数到 {@code get} 的调用次数了。
   */
  RegionIndex(Map<HexCoord, List<RegionId>> byHex) {
    this.byHex = byHex;
  }

  /**
   * 由区域集合建索引。
   *
   * <p>★ 重叠规则（M8-U1）：**全部保留**，按 {@link RegionId} 字典序排列——区域先按 id 升序处理、逐格**追加**，
   * 故每格的列表天然有序；结果与入参的迭代序无关（同一批区域无论什么顺序进来，都得同一份索引）。每格的列表在建索引时冻结为不可变。
   */
  public static RegionIndex of(Collection<Region> regions) {
    List<Region> ordered = new ArrayList<>(regions);
    ordered.sort(Comparator.comparing(region -> region.id().value()));
    Map<HexCoord, List<RegionId>> byHex = new HashMap<>();
    for (Region region : ordered) {
      for (HexCoord c : region.hexes()) {
        byHex.computeIfAbsent(c, key -> new ArrayList<>()).add(region.id());
      }
    }
    Map<HexCoord, List<RegionId>> frozen = new HashMap<>();
    for (Map.Entry<HexCoord, List<RegionId>> entry : byHex.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return new RegionIndex(frozen);
  }

  /** 该格所属的**全部**区域，按 {@link RegionId} 字典序；**无归属返回空列表**。 */
  public List<RegionId> regionOf(HexCoord c) {
    List<RegionId> owners = byHex.get(c);
    return owners == null ? List.of() : owners;
  }

  /** 该格是否有归属。独立给出，免得调用方拿 {@code null} 当"没有"（并被迫处理 {@code regionOf} 的返回值）。 */
  public boolean hasRegion(HexCoord c) {
    return byHex.containsKey(c);
  }
}
