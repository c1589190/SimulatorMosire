package io.mosire.simos.map.block;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionBoundary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权威地形块（P1，用户裁定）：一个**同地形连通分量**的一条记录。
 *
 * <p>★ **地形是权威块、高度仍逐格**（spec §7.1）：地形是粗粒度、hex 间通用、仅 map 模块维护的层级数据；高度是精确到单 hex
 * 的连续值、且**不是地形的函数**（{@code RegionRandomizer} 改地形保留 height）。故本类型只承载地形与"哪些格"，不含高度。
 *
 * <p>★ **边界是派生纯函数，但作为组件落盘并校验**（与 {@link io.mosire.simos.map.region.Region} 同形制）：为什么复用 {@link
 * RegionBoundary} 而不新造一个"带洞多边形"类型——见下。
 *
 * <h2>为什么复用 {@link RegionBoundary}</h2>
 *
 * <p>块的边界要求正是"外环 + 洞环"：一个同地形连通分量内部可以有**非同地形的飞地**，飞地的边界就是洞。{@link RegionBoundary#of(Set)}
 * 的算法是"逐格逐边，邻居不在集合里 ⇒ 该边暴露；再按顶点走环"，它**天然为每个暴露边的连通分量产出一条环**—— 外轮廓是一条、每个飞地是一条。所以它**已经**是"带洞多边形"，且：
 *
 * <ul>
 *   <li>它是 {@link HexCoord} 集合的**规范纯函数**（旋到最小顶点、取字典序较小的绕向），迭代序无关；
 *   <li>顶点是整数标签 {@link io.mosire.simos.map.hex.HexVertex}，不是浮点——没有"同一顶点对不上键"的病。
 * </ul>
 *
 * <p>新造一个平行类型只会复制这两条性质（而复制正是本项目最贵的教训），故**复用**。{@code rings} 的顺序是各自首顶点的字典序 （不是"外环在前"）——消费端按 {@code
 * evenodd} 填充，顺序无几何含义。
 *
 * <p>★ **确定性**：{@link #hexes()} 存的是**按 {@link HexCoord} 自然序排好的不可变集合**（不是 {@code Set.copyOf} 的散列序），故
 * {@link #toString()} 逐字节可复现；{@code boundary} 已规范；两边合起来 ⇒ 同一 hex 集合必得逐字节相同的块。
 *
 * @param terrain 地形 key（{@code GameMap.terrainTypes} 的键）；**空白即抛**
 * @param hexes 块内全部格，**不可为空**；存为自然序不可变集合
 * @param boundary 带洞边界；**必须等于由 {@code hexes} 重算的结果**，否则构造期抛
 */
public record TerrainBlock(String terrain, Set<HexCoord> hexes, RegionBoundary boundary) {

  public TerrainBlock {
    if (terrain == null || terrain.isBlank()) {
      throw new IllegalArgumentException("terrain 不得为空白");
    }
    if (hexes == null || hexes.isEmpty()) {
      throw new IllegalArgumentException("TerrainBlock 的 hexes 不得为空: " + terrain);
    }
    // ★ 自然序 + LinkedHashSet：迭代序（因而 toString 的字节）只由集合内容决定，跨 JVM 稳。
    List<HexCoord> sorted = new ArrayList<>(hexes);
    sorted.sort(Comparator.naturalOrder());
    hexes = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    if (boundary == null) {
      throw new IllegalArgumentException("boundary 不得为 null");
    }
    // ★ 与 Region 同形的钉子：边界必须与 hexes 一致，漂移在构造期就不可能存在。
    RegionBoundary recomputed = RegionBoundary.of(hexes);
    if (!recomputed.equals(boundary)) {
      throw new IllegalArgumentException(
          "boundary 与 hexes 不一致：hexes 重算得 " + recomputed + "，传入的是 " + boundary);
    }
  }

  /** ★ **正常代码走这个工厂**：边界由 hexes 算出来，不手写。直接调构造器只在反序列化（边界已由存档给出、需要被校验）时才合理。 */
  public static TerrainBlock of(String terrain, Set<HexCoord> hexes) {
    return new TerrainBlock(terrain, hexes, RegionBoundary.of(hexes));
  }

  /** 本块的身份（{@link BlockId#of} 的确定性派生）。 */
  public BlockId id() {
    return BlockId.of(terrain, hexes);
  }
}
