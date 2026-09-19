package io.mosire.simos.map;

/**
 * 一个六边形格**此时此地的海拔**。
 *
 * <p>★ **只剩 {@code height}**（P1，用户裁定）：地形已升为 {@link io.mosire.simos.map.block.TerrainBlock} 权威块
 * （粗粒度、hex 间通用、仅 map 模块维护）；高度是精确到单 hex 的连续值，且**不是地形的函数**（{@code RegionRandomizer} 改地形保留
 * height），故仍逐格存。取某格地形走 {@link GameMap#terrainAt(io.mosire.simos.map.hex.HexCoord)}， **不要**再往本类型上加回
 * terrain。
 *
 * <p>★ **没有任何连通性字段** —— GSimulator 的 {@code edgeTags}/{@code riverMask} 是 L2 的第二份存储（Java
 * 侧只读不写，只有前端写，且前端一存就把所有边的 props 抹平）。主存储是 {@code GameMap.edges}。
 *
 * @param height 海拔，**归一到 {@code [0,1]}**
 */
public record HexCell(double height) {

  public HexCell {
    // ★ **必须排在范围校验之前**：NaN 与 [0,1] 的比较全是 false，调换顺序会让 NaN 漏过去。
    if (!Double.isFinite(height)) {
      throw new IllegalArgumentException("height 必须是有限数: " + height);
    }
    if (height < 0.0 || height > 1.0) {
      throw new IllegalArgumentException("height 必须在 [0,1]: " + height);
    }
  }
}
