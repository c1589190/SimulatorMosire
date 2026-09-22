package io.mosire.simos.app.access;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionIndex;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.spi.NationTag;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * **本国的邻国**（spec §3.4，判据 **J5**）：与本国任一区域**六角邻接**的、属于**其他国家**的区域 ⇒ 取那些国家。
 *
 * <p>口径逐条：
 *
 * <ol>
 *   <li>**本国不算邻国**——邻接判定落在别人身上时，本国的区域会被扫到（自己贴着自己），必须显式排除；
 *   <li>**邻接是逐 hex 的六邻**（{@code HexCoord.neighbors()}，即 {@code HexDirection.ALL} 那六条边），
 *       <b>不是</b>"距离近"或包围盒相交——隔一环的国家不算邻国（{@code NeighborNationsTest} 里 BEL 那一格就是它的靶子）；
 *   <li>**只有 {@code nation:} tag 算国家**：{@code supply}、{@code null}（空元数据是合法状态）这些都不进结果 ——
 *       它们贴着本国也是"有邻接、无邻国"。
 * </ol>
 *
 * <p>★ **本国用逐字相等判，不用 {@code startsWith}**：{@code "nation:FRAX"} **不是** {@code "nation:FRA"}， 前缀匹配会把
 * FRAX 读成本国 ⇒ 它从结果里<b>消失</b>（而不是多出来）。与 {@link NationScope} 的 tag 比较同一口径。
 *
 * <p>★ **穷尽扫"本国的格"**：遍历的是 {@code region.hexes()}（区域自己的格集），**不是** {@code map.hexes()}—— 真档 59223
 * 格、98 区域，区域侧只有本国那几块要扫。
 *
 * <p>★ **有序返回**（理由同 {@link HexOwner}）；本国一块区域都没有 ⇒ **空集**（fail-closed 的退化形态，不是异常）。
 */
public final class NeighborNations {

  private NeighborNations() {}

  /**
   * 与给定国家相邻的**其他国家**集合。
   *
   * @param map 地图（区域、格、反向索引的事实来源）
   * @param nationId 本国
   * @return **有序**的邻国 id 集合（**不含本国**）；本国没有区域时为**空集**
   */
  public static Set<String> of(GameMap map, NationId nationId) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(nationId, "nationId");
    String ownTag = NationTag.tagFor(nationId);
    // ★ 派生件每次重算（GameMap.regionIndex 的 javadoc），故**建一次、全程复用**：逐邻格重建等于 O(格数 × 全图)。
    RegionIndex index = map.regionIndex();
    Set<String> neighbors = new TreeSet<>();
    for (Region region : map.regions().values()) {
      if (!ownTag.equals(region.meta().tag())) {
        continue; // ★ 逐字相等：startsWith 会把 nation:FRAX 读成本国
      }
      for (HexCoord coord : region.hexes()) {
        for (HexCoord adjacent : coord.neighbors()) {
          for (String nation : HexOwner.nationsOf(map, index, adjacent)) {
            if (!nation.equals(nationId.value())) {
              neighbors.add(nation); // ★ 本国自己贴着自己，必须排除
            }
          }
        }
      }
    }
    return Collections.unmodifiableSet(neighbors);
  }
}
