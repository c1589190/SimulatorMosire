package io.mosire.simos.map.hex;

/**
 * 格角顶点。**整数标签，全格唯一** —— 同一个顶点由相邻三格中任一格算得的值必须完全相同，与算法路径无关。
 *
 * <p>★ {@code (u, w)} 是**标签，不是坐标**：{@code u = x / (size·√3/2)}、{@code w = y / (size/2)}
 * 是两个**不同**的缩放系数， 故它不是平面点的相似变换，**不得拿它算距离或角度**。它只承诺两件事：① 同一顶点的标签唯一；② 有全序（{@link #compareTo}），可用于规范化。
 *
 * <p>★ 取代 GSimulator 的 {@code cornerKey(x, y) = Math.round(x*1000) + "_" + Math.round(y*1000)} ——
 * 那是**拿浮点舍入当身份**：同一个顶点由不同格中心算出时可能落在 {@code .5} 两侧而**对不上键**，环就断了。整数标签没有这个问题。
 *
 * <p>顶点与边的对应见 {@link #at(HexCoord, int)}；本类型放在 {@code hex} 包（与 {@link HexCoord}/{@link
 * HexDirection} 同族的**格几何原语**），不放在 {@code region} 包 —— 将来的等高线渲染也要用它。
 */
public record HexVertex(int u, int w) implements Comparable<HexVertex> {

  /**
   * 第 i 个顶点相对**格中心标签** {@code (2q + r, 3r)} 的偏移，取自 {@code 60°i − 30°} 的角（{@code cos} 乘 {@code
   * 2/√3}、{@code sin} 乘 {@code 2}，各向异性地缩放成整数）。
   *
   * <p>★ 两条性质由 {@code HexVertexTest} 钉住：单格六个顶点**互不相同**；相邻两格**恰好共享 2 个**顶点。
   */
  private static final int[] U = {1, 1, 0, -1, -1, 0};

  private static final int[] W = {-1, 1, 2, 1, -1, -2};

  /**
   * 格 {@code hex} 的第 {@code corner} 个顶点，{@code corner ∈ [0, 6)}。
   *
   * <p>★ **边与顶点的对应**：{@link HexDirection} 的第 {@code d} 条边，两端点恰是第 {@code d} 与第 {@code (d+1)%6} 个顶点
   * —— 即"这条边的两个端点"与"本格与 {@code hex.neighbor(d)} 共享的那两个顶点"是同一对。六个方向已逐项对表。
   *
   * <p>{@code corner} 越界由数组下标自然抛出（{@link ArrayIndexOutOfBoundsException}），不另设守卫——它不是静默路径。
   */
  public static HexVertex at(HexCoord hex, int corner) {
    return new HexVertex(2 * hex.q() + hex.r() + U[corner], 3 * hex.r() + W[corner]);
  }

  /** 先比 {@code u} 再比 {@code w} 的字典序（与 {@link HexCoord#compareTo} 同形）。 */
  @Override
  public int compareTo(HexVertex o) {
    int c = Integer.compare(u, o.u);
    return c != 0 ? c : Integer.compare(w, o.w);
  }
}
