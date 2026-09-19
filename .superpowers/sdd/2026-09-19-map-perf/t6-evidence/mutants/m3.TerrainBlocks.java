package io.mosire.simos.map.block;

import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 地形块的**切分**与**分割不变式**校验（P1/P6）。
 *
 * <p>算法：同地形**六邻域连通分量** BFS。逐格按 {@link HexCoord} 自然序起头，把"尚未归属、且与起点同地形"的极大连通块整块收走 （邻居在图外或地形不同即止）。★
 * **P6：全部建块，无最小尺寸阈值、无"剩余散格"通道**——每一格恰属一块。
 *
 * <p>★ **确定性**：起点遍历按自然序、块表存 {@link TreeMap}（{@link BlockId} 全序），同一输入必得逐字节相同的块表与 {@link BlockId} 集合。
 */
public final class TerrainBlocks {

  /** MUTANT m3：自增序号（非确定性 BlockId）。 */
  private static final java.util.concurrent.atomic.AtomicInteger MUTANT_COUNTER =
      new java.util.concurrent.atomic.AtomicInteger();

  private TerrainBlocks() {}

  /**
   * 把一个 hex → 地形 key 的映射切成权威块。
   *
   * <p>★ 图外的六邻格（{@code terrain.get(nb) == null}）不是同地形 ⇒ 不并入（故块边界就是图纸边界）。
   *
   * @param terrainByHex 每一格的地形 key；**键与值都不得为 null**
   * @return 按 {@link BlockId} 全序排列的块表（保序不可变）
   */
  public static Map<BlockId, TerrainBlock> split(Map<HexCoord, String> terrainByHex) {
    Objects.requireNonNull(terrainByHex, "terrainByHex");
    List<HexCoord> ordered = new ArrayList<>(terrainByHex.keySet());
    ordered.sort(Comparator.naturalOrder());
    Set<HexCoord> visited = new LinkedHashSet<>();
    Map<BlockId, TerrainBlock> blocks = new TreeMap<>();
    for (HexCoord start : ordered) {
      if (visited.contains(start)) {
        continue;
      }
      String terrain = terrainByHex.get(start);
      Objects.requireNonNull(terrain, "terrain@" + start);
      Set<HexCoord> component = new LinkedHashSet<>();
      Deque<HexCoord> queue = new ArrayDeque<>();
      queue.add(start);
      visited.add(start);
      while (!queue.isEmpty()) {
        HexCoord at = queue.poll();
        component.add(at);
        for (HexCoord neighbor : at.neighbors()) {
          if (visited.contains(neighbor)) {
            continue;
          }
          if (!terrain.equals(terrainByHex.get(neighbor))) {
            continue; // 图外（null）或异地形：边界
          }
          visited.add(neighbor);
          queue.add(neighbor);
        }
      }
      blocks.put(
          new BlockId(terrain, new HexCoord(MUTANT_COUNTER.getAndIncrement(), 999)),
          TerrainBlock.of(terrain, component)); // MUTANT m3：BlockId 用自增序号（不确定）
    }
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
  }

  /** 单一地形的便捷切分（夹具与演示世界用）：{@code hexes} 全是同一 {@code terrain}。 */
  public static Map<BlockId, TerrainBlock> uniform(Set<HexCoord> hexes, String terrain) {
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(terrain, "terrain");
    Map<HexCoord, String> byHex = new LinkedHashMap<>();
    for (HexCoord hex : hexes) {
      byHex.put(hex, terrain);
    }
    return split(byHex);
  }

  /**
   * ★★ **分割不变式**（P1 的核心护栏）：所有块的 {@code hexes} **并集 == {@code hexes} 的键集**、且**两两不交**（每一格恰属一块）。
   *
   * <p>失败消息**精确到出问题的那个 hex**（否则用例只能弱断言）：
   *
   * <ul>
   *   <li>有格无主（少了一块）⇒ {@code "hex <h> 不属于任何地形块…"}；
   *   <li>一格属两块（重叠）⇒ {@code "hex <h> 同时属于地形块 <a> 与 <b>…"}；
   *   <li>块键与块内容不符（如 id 被换成序号）⇒ {@code "地形块键 <k> 与块内容不符…"}。
   * </ul>
   *
   * @throws IllegalArgumentException 任一不变式被破坏
   */
  public static void requirePartition(
      Map<HexCoord, HexCell> hexes, Map<BlockId, TerrainBlock> blocks) {
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(blocks, "blocks");
    Map<HexCoord, BlockId> owner = new LinkedHashMap<>();
    for (Map.Entry<BlockId, TerrainBlock> entry : blocks.entrySet()) {
      BlockId key = entry.getKey();
      TerrainBlock block = entry.getValue();
      BlockId derived = BlockId.of(block.terrain(), block.hexes());
      if (!derived.equals(key)) {
        throw new IllegalArgumentException("地形块键 " + key + " 与块内容不符（内容派生得 " + derived + "）");
      }
      for (HexCoord hex : block.hexes()) {
        if (!hexes.containsKey(hex)) {
          throw new IllegalArgumentException(
              "地形块 " + key + " 含图外 hex：" + hex + " 不在 hexes 里（分割不变式被破坏）");
        }
        BlockId prior = owner.putIfAbsent(hex, key);
        if (prior != null) {
          throw new IllegalArgumentException(
              "hex " + hex + " 同时属于地形块 " + prior + " 与 " + key + "（分割不变式要求两两不交）");
        }
      }
    }
    for (HexCoord hex : hexes.keySet()) {
      if (!owner.containsKey(hex)) {
        throw new IllegalArgumentException("hex " + hex + " 不属于任何地形块（分割不变式要求并集覆盖全部 hex）");
      }
    }
  }
}
