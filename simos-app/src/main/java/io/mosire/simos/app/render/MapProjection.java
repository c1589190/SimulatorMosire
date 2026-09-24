package io.mosire.simos.app.render;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 六角投影与视野取格（pointy-top 轴向坐标）——<b>与前端 `webui/hexgeom.js` 的 {@code hexToPixel}/{@code hexCorners}
 * 同型</b>：
 *
 * <pre>{@code x = size * (√3*q + √3/2*r)；y = size * 1.5*r；顶点角度 = 60°*i - 30°}</pre>
 *
 * <p>★ 两份实现必须同型：服务端渲染的图与前端画布上的地图若是两套投影，"用户在图上看到的"和"模型看到的"就不是同一个世界。 改动其一必须同步另一处（含本文档的这一行）。
 */
public final class MapProjection {

  private static final double SQRT3 = Math.sqrt(3.0);

  private MapProjection() {}

  /** 格中心的世界 x（size = 1 时为"格宽单位"）。 */
  public static double centerX(HexCoord coord, double size) {
    return size * (SQRT3 * coord.q() + (SQRT3 / 2.0) * coord.r());
  }

  /** 格中心的世界 y。 */
  public static double centerY(HexCoord coord, double size) {
    return size * 1.5 * coord.r();
  }

  /** 轴向距离（= 立方坐标距离）。 */
  public static int distance(HexCoord a, HexCoord b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    int dq = a.q() - b.q();
    int dr = a.r() - b.r();
    return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
  }

  /**
   * 中心 + 半径内的全部格（含中心），按 r 再 q 排序——顺序确定，便于测试与逐格绘制。
   *
   * <p>半径是<b>轴向</b>距离：R=1 得 7 格、R=2 得 19 格、R=3 得 37 格。
   */
  public static List<HexCoord> hexesWithin(HexCoord center, int radius) {
    Objects.requireNonNull(center, "center");
    if (radius < 0) {
      throw new IllegalArgumentException("radius 不得为负: " + radius);
    }
    List<HexCoord> out = new ArrayList<>();
    for (int dr = -radius; dr <= radius; dr++) {
      int qLo = Math.max(-radius, -dr - radius);
      int qHi = Math.min(radius, -dr + radius);
      for (int dq = qLo; dq <= qHi; dq++) {
        out.add(new HexCoord(center.q() + dq, center.r() + dr));
      }
    }
    return List.copyOf(out);
  }

  /**
   * pointy-top 六角顶点（相对中心，逆时针/顺时针取决于屏幕 y 轴——与前端同一套公式，故形状一致）。
   *
   * @return {@code [6][2]}：每个顶点 {@code {dx, dy}}
   */
  public static double[][] corners(double size) {
    if (!(size > 0)) {
      throw new IllegalArgumentException("size 必须为正: " + size);
    }
    double[][] points = new double[6][2];
    for (int i = 0; i < 6; i++) {
      double angle = Math.toRadians(60 * i - 30);
      points[i][0] = size * Math.cos(angle);
      points[i][1] = size * Math.sin(angle);
    }
    return points;
  }

  /**
   * 六邻方向（E/NE/NW/W/SW/SE）——与前端 {@code DIR_VECTORS} 同序同值。
   *
   * <p>顺序不是随意排的：{@link #edgeCornerIndexes(int)} 按同一序号给出"与邻格共享的那条边"，两者必须成对改。
   */
  public static final int[][] DIR_VECTORS = {
    {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1},
  };

  /**
   * 与第 {@code dirIndex} 个邻格共享的边，对应的两个顶点下标（见 {@link #corners}）。
   *
   * <p>推导（顶点角度 −30°,30°,90°,150°,210°,270°）：东邻共享右侧边 = 顶点 0/1；东北邻（q+1,r−1）在右上 = 5/0； 西北邻（r−1）在上 =
   * 4/5；西邻 = 3/4；西南邻（q−1,r+1）= 2/3；东南邻（r+1）= 1/2。
   */
  public static int[] edgeCornerIndexes(int dirIndex) {
    if (dirIndex < 0 || dirIndex >= DIR_VECTORS.length) {
      throw new IllegalArgumentException("dirIndex 越界: " + dirIndex);
    }
    int[][] edges = {{0, 1}, {5, 0}, {4, 5}, {3, 4}, {2, 3}, {1, 2}};
    return edges[dirIndex].clone();
  }
}
