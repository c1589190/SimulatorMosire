package io.mosire.simos.map;

/**
 * 一个六边形格。
 *
 * <p>★ **有 {@code height}** —— GSimulator 的等价类没有，海拔只活在内存 LRU 里、问到就丢（fallback 写死 0/0.5，{@code
 * .height()} 全仓零调用点）。总纲 §5.1 的"自动河流：根据地形海拔"在没有落盘海拔的前提下**做不了**。
 *
 * <p>★ **没有任何连通性字段** —— GSimulator 的 {@code edgeTags}/{@code riverMask} 是 L2 的第二份存储（Java
 * 侧只读不写，只有前端写，且前端一存就把所有边的 props 抹平）。主存储是 {@code GameMap.edges}。
 *
 * <p>★ {@code terrain} 存的是**key**（{@code GameMap.terrainTypes} 的键），**不是** {@code TerrainType} 实例 ——
 * 10 个字段的类型定义只存一份，格上不复制（L9 的形态）。
 *
 * @param terrain 地形类型的 key；**空白即抛**
 * @param height 海拔，**归一到 {@code [0,1]}**
 */
public record HexCell(String terrain, double height) {

  public HexCell {
    if (terrain == null || terrain.isBlank()) {
      throw new IllegalArgumentException("terrain 不得为空白");
    }
    // ★ **必须排在范围校验之前**：NaN 与 [0,1] 的比较全是 false，调换顺序会让 NaN 漏过去。
    if (!Double.isFinite(height)) {
      throw new IllegalArgumentException("height 必须是有限数: " + height);
    }
    if (height < 0.0 || height > 1.0) {
      throw new IllegalArgumentException("height 必须在 [0,1]: " + height);
    }
  }
}
