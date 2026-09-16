package io.mosire.simos.map.hex;

import java.util.List;

/**
 * 六条边的方向。**索引即边序号，全模块唯一。**
 *
 * <p>序为顺时针 {@code E, SE, SW, W, NW, NE}，与 GSimulator 的 {@code riverMask} 位序一致。
 *
 * <p>GSimulator 全仓有 8 份方向表（6 Java + 2 JS），归为两种互逆索引序，根因是两张主表都是 package-private、不导出
 * API，消费方只能复制。此处合并为唯一一份。
 */
public enum HexDirection {
  E(1, 0),
  SE(0, 1),
  SW(-1, 1),
  W(-1, 0),
  NW(0, -1),
  NE(1, -1);

  private final int dq;
  private final int dr;

  HexDirection(int dq, int dr) {
    this.dq = dq;
    this.dr = dr;
  }

  public int dq() {
    return dq;
  }

  public int dr() {
    return dr;
  }

  /** 反向边。由枚举序保证（恰隔 3 项），不是巧合。 */
  public HexDirection opposite() {
    return ALL.get((ordinal() + 3) % 6);
  }

  /** 顺时针下一方向。 */
  public HexDirection next() {
    return ALL.get((ordinal() + 5) % 6);
  }

  /** 逆时针下一方向。 */
  public HexDirection prev() {
    return ALL.get((ordinal() + 1) % 6);
  }

  /** 按枚举序的全部方向。 */
  public static final List<HexDirection> ALL = List.of(values());
}
