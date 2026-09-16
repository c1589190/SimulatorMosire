package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * hex → 区域 的反向索引。**派生，不进变更集、不进存档**（与 U2 之后**入存储的** {@code Region.boundary} 不是一回事，别混）。
 *
 * <p>★ 解 L5：GSimulator 有 6 处逐字重复的 {@code for (entry : map.provinces()) if (hexes.contains(key))}
 * 线性扫描（其中一处**不 break**，每次渲染都全扫），故每次 {@code hex:{q}_{r}} 地址解析都全表扫。本类把 {@link #regionOf} 做成 **O(1)**。
 */
public final class RegionIndex {

  private final Map<HexCoord, RegionId> byHex;

  /**
   * ★ 只给测试注入**计数包装层**用（见 {@code RegionIndexTest#regionOfIsConstantTime}）：生产路径是 {@link
   * #of(Collection)}。**故意不做防御性拷贝** —— 拷贝一份就没法数到 {@code get} 的调用次数了。
   */
  RegionIndex(Map<HexCoord, RegionId> byHex) {
    this.byHex = byHex;
  }

  /**
   * 由区域集合建索引。
   *
   * <p>★ 重叠区域的裁决规则：**按 {@link RegionId} 字典序（{@code value()} 的字符串序）处理，先写入者胜** ——{@code putIfAbsent}
   * 不覆盖，故结果与入参的迭代序无关（同一批区域无论什么顺序进来，都得同一份索引）。
   */
  public static RegionIndex of(Collection<Region> regions) {
    List<Region> ordered = new ArrayList<>(regions);
    ordered.sort((a, b) -> a.id().value().compareTo(b.id().value()));
    Map<HexCoord, RegionId> byHex = new HashMap<>();
    for (Region region : ordered) {
      for (HexCoord c : region.hexes()) {
        byHex.putIfAbsent(c, region.id());
      }
    }
    return new RegionIndex(byHex);
  }

  /** 该格所属的区域；**无归属返回 {@code null}**。 */
  public RegionId regionOf(HexCoord c) {
    return byHex.get(c);
  }

  /** 该格是否有归属。独立给出，免得调用方拿 {@code null} 当"没有"（并被迫处理 {@code regionOf} 的返回值）。 */
  public boolean hasRegion(HexCoord c) {
    return byHex.containsKey(c);
  }
}
