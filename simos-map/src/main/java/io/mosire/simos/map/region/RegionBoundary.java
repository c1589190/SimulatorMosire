package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexDirection;
import io.mosire.simos.map.hex.HexVertex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 区域的闭环边界。**是 {@link Region} 的组件（用户裁决 U2），同时是由 hexes 唯一确定的纯函数。**
 *
 * <p>★ 两个性质都不可少：**入存储**解决持久化（存档必须自带边界，否则每个读档方都要重新实现一遍推导 —— 那正是 GSimulator 的 {@code edgeKey}
 * 四份副本那类病）；**可重算**解决漂移（{@code Region} 的构造器重算一遍比对，不等即抛）。
 *
 * <p>★ **环的元素是格角顶点 {@link HexVertex}，不是格** —— 单格的边界就是它的六条边，6 个顶点（派单书里 {@code List<List<HexCoord>>}
 * 的写法与"单格 6 个顶点"自相矛盾，按控制器裁定 R-3a 取顶点）。
 *
 * <p>★ **每条环不重复首顶点**（存 6 个点，不是 7 个）：GSimulator 的注释写"closed polygon (first == last)"，那是渲染侧的 canvas
 * 约定；此处存的是**闭环的顶点序**，消费端要闭合只需在末尾补回首顶点。**这是一处有意的形态偏离**，改用它对表时别以为丢了东西。
 *
 * <p>★ **规范性**：{@link #of} 的结果必须**只由集合内容决定**，与入参 Set 的迭代序无关。 {@code Region.hexes} 是 {@code
 * Set.copyOf}（不保序），而 {@code Region.equals} 逐组件比较 —— 若本类型顺着迭代序走，两个内容相同的 Region 会得到不同的 {@code
 * boundary}，于是 {@code equals} 为假，**而它们本该相等**。规范化全部落在紧凑构造器里（幂等）：先按 {@link HexVertex#compareTo}
 * 定序，再定环的起点与绕行方向，二者都取规范值。
 *
 * @param rings 每一条闭环。外环 + 可能的内环（洞），**环表本身也按规范序**（各自的首顶点字典序）。
 */
public record RegionBoundary(List<List<HexVertex>> rings) {

  /**
   * ★ 边界顶点的**度恒为 2**（R-3e 的推导）：六角格每个顶点恰有 3 格、3 条边；设这 3 格中有 k 格属于本区域，则暴露边数 = {@code k(3−k)}，k = 1 或
   * 2 时都得 2，k = 0 或 3 时得 0。故**不存在岔路口**，走环无需转向规则。
   */
  private static final int VERTEX_DEGREE = 2;

  public RegionBoundary {
    List<List<HexVertex>> canonical = new ArrayList<>(rings.size());
    for (List<HexVertex> ring : rings) {
      canonical.add(canonicalRing(ring));
    }
    // 环表按各自的首顶点字典序（各环此刻都已旋到自己的最小顶点开头，故取首顶点即可）
    canonical.sort((a, b) -> a.getFirst().compareTo(b.getFirst()));
    rings = List.copyOf(canonical);
  }

  /**
   * 从 hex 集合计算边界。**纯函数**：同集合必得同结果，与迭代序无关。
   *
   * <p>★ 取 {@code Set<HexCoord>} 而**不是** {@code Region} —— U2 之后 {@code Region} 的构造需要 {@code
   * RegionBoundary}，若本方法收 {@code Region} 就成死循环。
   *
   * <p>算法：逐格逐边，**邻居不在集合里 ⇒ 该边暴露**，取其两个端点成段；再按顶点建邻接图、走环。
   *
   * <p>★ **不沿用 GSimulator 的 {@code size < 3} 短路**（那句 {@code if (hexSet == null || hexSet.size() <
   * 3) return List.of();} 是**渲染期的多边形下限**，不是几何事实）：空集 → 无环；单格 → 1 条环、6 个顶点；相邻两格 → 1 条环、10 个顶点；N
   * 个互不相邻的单格 → N 条环、各 6 个顶点。**这是有意的行为偏离**。
   */
  public static RegionBoundary of(Set<HexCoord> hexes) {
    Map<HexVertex, List<HexVertex>> adjacency = new HashMap<>();
    for (HexCoord hex : hexes) {
      for (HexDirection d : HexDirection.ALL) {
        if (hexes.contains(hex.neighbor(d))) {
          continue; // 邻居也在集合里 ⇒ 该边是内部边，不暴露
        }
        HexVertex from = HexVertex.at(hex, d.ordinal());
        HexVertex to = HexVertex.at(hex, (d.ordinal() + 1) % 6);
        adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        adjacency.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
      }
    }
    return new RegionBoundary(walkRings(adjacency));
  }

  /**
   * 把暴露边的邻接图走成闭环。**每个连通分量恰是一条环**（度恒为 2）。
   *
   * <p>★ 度 ≠ 2 时**抛异常，不静默**：宁可在构造期炸，也不要"取第一个未访问邻居"糊过去而产出一条断的、或自交的环。对合法输入这条守卫不会响 —— 它响就说明"度恒为
   * 2"的推导错了。**它是包内可见的**，因为人造一个度 ≠ 2 的邻接图是唯一能让它响的办法（{@code of} 从 hex 集合出发 造不出这种图）。
   */
  static List<List<HexVertex>> walkRings(Map<HexVertex, List<HexVertex>> adjacency) {
    for (Map.Entry<HexVertex, List<HexVertex>> entry : adjacency.entrySet()) {
      if (entry.getValue().size() != VERTEX_DEGREE) {
        throw new IllegalStateException(
            "边界顶点 " + entry.getKey() + " 的度为 " + entry.getValue().size() + "，应为 " + VERTEX_DEGREE);
      }
    }
    List<List<HexVertex>> rings = new ArrayList<>();
    Set<HexVertex> visited = new HashSet<>();
    for (HexVertex start : adjacency.keySet()) {
      if (!visited.contains(start)) {
        rings.add(walkOneRing(adjacency, start, visited));
      }
    }
    return rings;
  }

  /** 从 {@code start} 沿唯一的前进方向走一圈回到 {@code start}（度的守卫已保证每个顶点恰有两个邻点）。 */
  private static List<HexVertex> walkOneRing(
      Map<HexVertex, List<HexVertex>> adjacency, HexVertex start, Set<HexVertex> visited) {
    List<HexVertex> ring = new ArrayList<>();
    HexVertex previous = null;
    HexVertex current = start;
    do {
      ring.add(current);
      visited.add(current);
      List<HexVertex> neighbors = adjacency.get(current);
      HexVertex next = neighbors.get(0).equals(previous) ? neighbors.get(1) : neighbors.get(0);
      previous = current;
      current = next;
    } while (!current.equals(start));
    return ring;
  }

  /**
   * 一条环的规范形式：先旋到字典序最小的顶点开头，再与其**反向序列**比、取字典序较小者。
   *
   * <p>★ 两步缺一不可：**排序只解决"从哪个顶点开始扫"，不解决"环从哪个顶点开始"**。反向序列以同一个最小顶点开头（{@code [v0, vn-1,…, v1]}）， 故两者可比。
   *
   * <p>★ 绕行方向**没有几何含义**：消费端按 {@code evenodd} 填充（老仓如此），绕向不影响结果，**别把它说成"顺时针"**。
   * 它只是用来把同一条环的两种写法归一。规范化**不移动顶点、不改变环的内容**，故是内容保持的。
   */
  private static List<HexVertex> canonicalRing(List<HexVertex> ring) {
    List<HexVertex> rotated = rotateToSmallest(ring);
    List<HexVertex> reversed = new ArrayList<>(rotated.size());
    reversed.add(rotated.getFirst());
    for (int i = rotated.size() - 1; i > 0; i--) {
      reversed.add(rotated.get(i));
    }
    List<HexVertex> canonical = compareSequences(rotated, reversed) <= 0 ? rotated : reversed;
    return List.copyOf(canonical);
  }

  /** 旋到字典序最小的顶点开头；已在开头则原样返回（不拷贝，调用方只读）。 */
  private static List<HexVertex> rotateToSmallest(List<HexVertex> ring) {
    int smallest = 0;
    for (int i = 1; i < ring.size(); i++) {
      if (ring.get(i).compareTo(ring.get(smallest)) < 0) {
        smallest = i;
      }
    }
    if (smallest == 0) {
      return ring;
    }
    List<HexVertex> rotated = new ArrayList<>(ring.size());
    for (int i = 0; i < ring.size(); i++) {
      rotated.add(ring.get((smallest + i) % ring.size()));
    }
    return rotated;
  }

  /** 顶点序列的字典序比较。**不能用 {@link List#equals}** —— 那是逐元素判等（"相同"），不是"谁在前"。 */
  private static int compareSequences(List<HexVertex> a, List<HexVertex> b) {
    int n = Math.min(a.size(), b.size());
    for (int i = 0; i < n; i++) {
      int c = a.get(i).compareTo(b.get(i));
      if (c != 0) {
        return c;
      }
    }
    return Integer.compare(a.size(), b.size());
  }
}
