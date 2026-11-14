package io.mosire.simos.map.generate;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.List;

/**
 * 地图环绕拓扑。{@code wrapX=true} 表示同一纬度上 {@code q} 轴按 {@code [qMin, qMin + qWidth)} 周期闭合（圆柱面）；{@code
 * wrapX=false} 是普通平面地图。
 *
 * <p>这里只描述“左右”环绕，不描述极点/球面：极点在圆柱两端仍是边界。真正的 Goldberg 球面六角化 会产生 12 个五边形缺陷，不属于本类型。
 *
 * @param wrapX 是否启用左右环绕
 * @param qMin 第一列的轴向 q
 * @param qWidth 周期宽度（列数）；{@code wrapX=false} 时必须为 0
 */
public record MapWrap(boolean wrapX, int qMin, int qWidth) {

  public MapWrap {
    if (wrapX) {
      if (qWidth < 1) {
        throw new IllegalArgumentException("wrapX=true 时 qWidth 必须 >= 1: " + qWidth);
      }
    } else if (qWidth != 0) {
      throw new IllegalArgumentException("wrapX=false 时 qWidth 必须为 0: " + qWidth);
    }
  }

  /** 无环绕（普通平面地图）。 */
  public static MapWrap none() {
    return new MapWrap(false, 0, 0);
  }

  /** 该行（pointy-top 的 r）的周期起点：q 轴因行错位而平移 floor(r/2)。 */
  public int qMinAt(int r) {
    return qMin - Math.floorDiv(r, 2);
  }

  /** 把 q 规范到该行周期内；无环绕时原样返回。 */
  public int canonicalQ(int q, int r) {
    if (!wrapX) {
      return q;
    }
    int delta = Math.floorMod(q - qMinAt(r), qWidth);
    return qMinAt(r) + delta;
  }

  /** 规范格坐标；无环绕时原样返回。 */
  public HexCoord canonical(HexCoord coord) {
    if (!wrapX) {
      return coord;
    }
    return new HexCoord(canonicalQ(coord.q(), coord.r()), coord.r());
  }

  /** 六邻（bounded 为 false 时跨接缝进入另一侧）。 */
  public List<HexCoord> neighbors(HexCoord coord) {
    List<HexCoord> out = new ArrayList<>(6);
    for (HexCoord n : coord.neighbors()) {
      out.add(canonical(n));
    }
    return List.copyOf(out);
  }

  /** 两个格在圆柱上的最短六角距离（跨接缝取最小 q 平移）。 */
  public int distance(HexCoord a, HexCoord b) {
    if (!wrapX) {
      return a.distanceTo(b);
    }
    int best = Integer.MAX_VALUE;
    for (int shift = -qWidth; shift <= qWidth; shift += qWidth) {
      int candidate = a.distanceTo(new HexCoord(b.q() + shift, b.r()));
      if (candidate < best) {
        best = candidate;
      }
    }
    return best;
  }
}
