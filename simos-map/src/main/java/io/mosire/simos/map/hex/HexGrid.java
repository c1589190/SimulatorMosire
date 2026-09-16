package io.mosire.simos.map.hex;

import java.util.HashSet;
import java.util.Set;

/**
 * 六边形格容器。格的身份是 {@link HexCoord}，不是字符串键。
 *
 * <p>★ 没有 {@code gridSize} 字段：GSimulator 那个字段只做构造期范围校验、不参与取格，且到处写死 30 而真实生成半径是 80 —— 范围由本类的 {@code
 * minQ()/maxQ()/minR()/maxR()} 导出。
 *
 * <p>本类当前只承载**坐标集合**（纯几何：范围导出、半径内枚举）；格的内容由 {@code map} 包的 {@code HexCell} 落地后 另行补入，届时本类改为持有 {@code
 * Map<HexCoord, HexCell>}。
 */
public final class HexGrid {

  private final Set<HexCoord> cells;

  private HexGrid(Set<HexCoord> cells) {
    this.cells = Set.copyOf(cells);
  }

  /** 由坐标集合建网格。构造期做**防御性拷贝**，此后改动入参集合不再影响本网格。 */
  public static HexGrid of(Set<HexCoord> cells) {
    return new HexGrid(cells);
  }

  /** 最小 q。**空网格抛 {@link IllegalStateException}**——没有格的网格不存在坐标范围。 */
  public int minQ() {
    return cells.stream().mapToInt(HexCoord::q).min().orElseThrow(HexGrid::emptyGrid);
  }

  /** 最大 q。空网格抛 {@link IllegalStateException}。 */
  public int maxQ() {
    return cells.stream().mapToInt(HexCoord::q).max().orElseThrow(HexGrid::emptyGrid);
  }

  /** 最小 r。空网格抛 {@link IllegalStateException}。 */
  public int minR() {
    return cells.stream().mapToInt(HexCoord::r).min().orElseThrow(HexGrid::emptyGrid);
  }

  /** 最大 r。空网格抛 {@link IllegalStateException}。 */
  public int maxR() {
    return cells.stream().mapToInt(HexCoord::r).max().orElseThrow(HexGrid::emptyGrid);
  }

  /** 是否含该格。 */
  public boolean contains(HexCoord c) {
    return cells.contains(c);
  }

  /** 全部格。返回的集合**不可变**（构造期已拷贝，此处的返回不再需要第二份拷贝）。 */
  public Set<HexCoord> cells() {
    return cells;
  }

  /**
   * 以 center 为中心、半径 radius 的六边形球内全部坐标（含 center）。
   *
   * <p>cube 坐标下即枚举满足 {@code |dq| <= radius}、{@code |dr| <= radius}、{@code |ds| <= radius}（其中 {@code
   * ds = -dq - dr}）的全部偏移，逐格加到 center 上。**radius 为负时返回空集**。
   */
  public static Set<HexCoord> withinRadius(HexCoord center, int radius) {
    Set<HexCoord> ball = new HashSet<>();
    for (int dq = -radius; dq <= radius; dq++) {
      int from = Math.max(-radius, -dq - radius);
      int to = Math.min(radius, -dq + radius);
      for (int dr = from; dr <= to; dr++) {
        ball.add(new HexCoord(center.q() + dq, center.r() + dr));
      }
    }
    return Set.copyOf(ball);
  }

  private static IllegalStateException emptyGrid() {
    return new IllegalStateException("空网格没有坐标范围");
  }
}
