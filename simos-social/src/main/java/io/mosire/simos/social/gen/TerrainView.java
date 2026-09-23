package io.mosire.simos.social.gen;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;

/**
 * 生成器**唯一认的地形口子** —— 四个只读查询，没有任何"写"或"遍历"的余地。
 *
 * <p>★ **为什么要这层口子**：{@link SettlementGenerator} 是纯函数，纯到"换一个实现就能脱库单测"。真实世界只经 {@link #of(GameMap)}
 * 进来一次（{@link GameMapTerrainView}），测试则可以塞一个几十行的合成视图，不必造 {@code GameMap}（造它要过地形块分割不变式）。
 *
 * <p>★ **越界口径（fail-closed）**：四个方法对**不在图上的格**一律抛 {@link IllegalArgumentException} —— 那是调用方的错，不是"返回 0
 * 悄悄往下走"。生成器内部在探邻格（山口启发式）时**自己**把越界当作不可通行处理， 见 {@code SettlementGenerator}。
 */
public interface TerrainView {

  /** 该格的地形 key，取 {@code TerrainCatalog.KEYS} 里的某一项；未知地形由实现抛。 */
  String terrainKey(HexCoord c);

  /**
   * 该格触到的 **river 边条数**（0 = 不沿河）。
   *
   * <p>★ 一条 {@code EdgeRef} 是河边 ⇔ {@code EdgeTags.byPathway()} 里含 {@code "river"}。{@code EdgeRef}
   * **无向**， 两个端点都算触到 —— 故"条数"是该格作为任一端点的河边数，不是"下游/上游方向数"。
   */
  int riverEdgesAt(HexCoord c);

  /** 该格是否沿海：六邻居里存在地形 key 为 {@code "ocean"} 的格。 */
  boolean coastal(HexCoord c);

  /** 该格的移动成本，取 {@code map.terrainTypes().get(key).moveCost()}（海洋是不可通行哨兵 999）。 */
  int moveCost(HexCoord c);

  /**
   * 唯一实现：吃真 {@link GameMap}。**一次物化、全部 O(1) 查**（799 格 × 每格 O(块数) 的 {@code terrainAt} 是 O(n²)， 具体见
   * {@link GameMapTerrainView}）。
   */
  static TerrainView of(GameMap map) {
    return GameMapTerrainView.of(map);
  }
}
