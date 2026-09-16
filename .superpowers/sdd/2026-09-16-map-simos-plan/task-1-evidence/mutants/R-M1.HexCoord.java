package io.mosire.simos.map.hex;

import java.util.List;

/**
 * 轴向坐标（axial）。**六边形格的唯一身份**。
 *
 * <p>cube 第三轴恒由 {@code s = -q - r} 导出，不存储。
 *
 * <p>{@code "q_r"} 字符串只是**序列化形式**，不是身份：{@link #toString()} 与 {@link #parse(String)} 是它的唯一两份实现，且只在
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

  /**
   * 把 cube 空间里的分数坐标取整为**最近的**格（spec §3.4 的"一份取整"）。
   *
   * <p>**不是逐轴四舍五入**：{@code (0.5, 0.5)} 逐轴取整得 {@code (1, 1)}，而它距该点整 1 格；真正的最近格是 {@code (1, 0)} 与
   * {@code (0, 1)}，距离都是 0.5。做法是先在 cube 三轴上各自取整，再把**偏差最大**的那一轴改写成"另两轴之和的相反数"——修正后三轴之和恒为 0，且各轴偏差都不超过
   * 0.5。
   *
   * <p>最近格**可能并列**（{@code (0.5, 0.5)} 就有两个），此时取哪一侧**不定义**：spec 未规定，且全模块只有这一份取整实现，不存在跨实现对表的场景。
   *
   * <p>{@code NaN} 与无穷**抛 {@link IllegalArgumentException}**——{@code Math.round(NaN)} 是 0，静默产出
   * {@code (0, 0)} 是把错误藏起来，与 {@link HexGrid#withinRadius} 对负半径的口径一致：宁抛不静默。**超出 {@code int}
   * 范围的输入行为不定义**（本项目坐标量级到不了）。
   */
  public static HexCoord round(double q, double r) {
    if (!Double.isFinite(q) || !Double.isFinite(r)) {
      throw new IllegalArgumentException("坐标必须有限: " + q + ", " + r);
    }
    double s = -q - r;
    int rq = (int) Math.round(q);
    int rr = (int) Math.round(r);
    int rs = (int) Math.round(s);
    double dq = Math.abs(rq - q);
    double dr = Math.abs(rr - r);
    double ds = Math.abs(rs - s);
    if (dq > dr && dq > ds) {
      rq = -rr - rs;
    } else if (dr > ds) {
      rr = -rq - rs;
    }
    // 其余情形（s 轴偏差最大或并列）**故意不写 else**：该分支要修正的是 s 轴，而 s 是导出的、不参与返回，
    // 于是 `rs = -rq - rr;` 对返回值毫无影响——SpotBugs 会当场判它 DLS_DEAD_LOCAL_STORE。若在此改动 q/r 反而错。
    return new HexCoord(rq, rr);
  }

  /** 沿一个方向走一步。 */
  public HexCoord neighbor(HexDirection d) {
    return new HexCoord(q + d.dq(), r + d.dr());
  }

  /** 六条邻格，**枚举序**（即边序号序）。 */
  public List<HexCoord> neighbors() {
    return HexDirection.ALL.stream().map(this::neighbor).toList();
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
    return new HexCoord(
        Integer.parseInt(text.substring(0, i)), Integer.parseInt(text.substring(i + 1)));
  }
}
