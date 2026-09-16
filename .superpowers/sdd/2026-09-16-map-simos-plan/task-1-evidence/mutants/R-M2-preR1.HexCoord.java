package io.mosire.simos.map.hex;

import java.util.List;

/**
 * 轴向坐标（axial）。**六边形格的唯一身份**。
 *
 * <p>cube 第三轴恒由 {@code s = -q - r} 导出，不存储。
 *
 * <p>{@code "q_r"} 字符串只是**序列化形式**，不是身份：{@link #toString()} 与 {@link #parse(String)} 是它的唯一两份 实现，且只在
 * JSON 边界使用（铁律 1——地址是定位方式，ID 是身份）。
 */
public record HexCoord(int q, int r) implements Comparable<HexCoord> {

  /** cube 第三轴。恒等式 {@code q + r + s == 0}。 */
  public int s() {
    return -q - r;
  }

  /**
   * 到另一格的六边形距离（cube 曼哈顿距离的一半）。
   *
   * <p>GSimulator 有 4 份代数恒等的实现（Java 3 + JS 1），此处合并为唯一一份。
   */
  public int distanceTo(HexCoord other) {
    return (Math.abs(q - other.q) + Math.abs(r - other.r) + Math.abs(s() - other.s())) / 2;
  }

  /** 沿一个方向走一步。 */
  public HexCoord neighbor(HexDirection d) {
    return new HexCoord(q + d.dq(), r + d.dr());
  }

  /** 六条邻格，**枚举序**（即边序号序）。 */
  public List<HexCoord> neighbors() {
    return HexDirection.ALL.reversed().stream().map(this::neighbor).toList();
  }

  @Override
  public int compareTo(HexCoord o) {
    int c = Integer.compare(q, o.q);
    return c != 0 ? c : Integer.compare(r, o.r);
  }

  /** 规范字符串形式，**只在 JSON 边界使用**（身份是类型，不是这个串）。 */
  @Override
  public String toString() {
    return q + "_" + r;
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * <p>非法输入一律抛 {@link IllegalArgumentException}，但**两种来源不同**：{@code null} 与形态不符（空串、无分隔符
   * 或分隔符在首尾）由本方法的**显式判空/判形**抛出；而 {@code "a_b"} 这类**数字字段写错**的情况，是本方法交给 {@link
   * Integer#parseInt(String)} 抛出的 {@link NumberFormatException} 来满足的——后者是 {@link
   * IllegalArgumentException} 的**子类**，**没有**任何一行显式检查在挡它。
   */
  public static HexCoord parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("非法坐标串: null");
    }
    int i = text.indexOf('_');
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法坐标串: " + text);
    }
    return new HexCoord(
        Integer.parseInt(text.substring(0, i)), Integer.parseInt(text.substring(i + 1)));
  }
}
