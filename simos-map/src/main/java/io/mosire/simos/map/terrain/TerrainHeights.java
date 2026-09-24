package io.mosire.simos.map.terrain;

import java.util.Objects;

/**
 * ★ **涂某地形时写入该格的 height**（用户 2026-09-24 裁定）。
 *
 * <p>由来：用户报「从原有地图导入时高原地形被趋低（高度被压到地形带中点），只好手动补画」，并裁定 ① 手绘地形时 **顺带把该格高度写成该地形的代表值**（否则格详情里的高度与地形对不上），②
 * **沙漠只比平原高 0.005**。
 *
 * <p>★ **与高度带的关系**：缺省取该地形高度带的**中点**——这样手绘出来的高度与导入器 （{@code tools/gsimap_import.py} 的 {@code
 * representative_height}）**同源同值**，两批格不会因来源不同而读出两个数。 沙漠是**唯一**的显式偏离：其带中点是 0.50，而用户口径是「沙漠只比平原高
 * 0.005」⇒ 取 {@code 平原带中点 + 0.005}（= 0.380），即沙漠在地势上**几乎就是平原**（与「沙漠是低地、不是台地」的地理直觉一致）。
 *
 * <p>★ **这不是"高度带"的第二份真相**：带管的是「高度 → 地形」的分类（生成器用），本类管的是「涂地形 → 该写什么高度」
 * （写路径用）。带中点只是缺省值的选择，不是从带推导出的必然结论——沙漠这一项就是反例。
 */
public final class TerrainHeights {

  /** 沙漠的涂色高度相对**平原**涂色高度的抬高量（用户裁定：只高 0.005）。 */
  public static final double DESERT_ABOVE_PLAINS = 0.005;

  private TerrainHeights() {}

  /**
   * 涂该地形时写入的高度。未知 key ⇒ 由 {@link TerrainCatalog#of} 抛（不兜底）。
   *
   * @param key 地形 key（必须在 {@link TerrainCatalog} 词表内）
   */
  public static double paintHeight(String key) {
    Objects.requireNonNull(key, "key");
    return paintHeight(TerrainCatalog.of(key));
  }

  /**
   * 涂该地形时写入的高度：缺省取带中点；**沙漠**取「平原带中点 + {@value #DESERT_ABOVE_PLAINS}」。
   *
   * @param type 地形定义（必须在词表内——本类只对沙漠做特判，其余一律中点）
   */
  public static double paintHeight(TerrainType type) {
    Objects.requireNonNull(type, "type");
    if ("desert".equals(type.key())) {
      return midpoint(TerrainCatalog.of("plains")) + DESERT_ABOVE_PLAINS;
    }
    return midpoint(type);
  }

  private static double midpoint(TerrainType type) {
    return (type.minHeight() + type.maxHeight()) / 2.0;
  }
}
