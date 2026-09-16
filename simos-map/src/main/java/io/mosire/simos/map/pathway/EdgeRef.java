package io.mosire.simos.map.pathway;

import io.mosire.simos.map.hex.HexCoord;

/**
 * 无向边。**身份就是那两格**，不需要 ID。
 *
 * <p>★ 构造期完成规范排序 ⇒ {@code (a,b)} 与 {@code (b,a)} 恒为同一对象。GSimulator 有一份 {@code edgeKey} 手写逻辑的**4
 * 份实现**（Java 2 + JS 1 + 1 处内联），其中前端那份的注释自陈 <i>"Must stay in sync with MapData.edgeKey() in
 * Java"</i> —— 靠注释维系的同步不是同步。此处由类型系统保证：{@link #a()} 恒为字典序较小的那格。
 *
 * <p>★ 规范序按 {@link HexCoord#compareTo}（**先 q 后 r**），**不是**按 {@link #toString()} 的字符串序（老仓 {@code
 * MapData.edgeKey} 走的是后者）。两者**不等价**，故**不要**拿字符串比较去复现规范序。
 */
public record EdgeRef(HexCoord a, HexCoord b) implements Comparable<EdgeRef> {

  public EdgeRef {
    if (a == null || b == null) {
      throw new IllegalArgumentException("边的端点不得为 null");
    }
    if (a.equals(b)) {
      throw new IllegalArgumentException("边不能自环: " + a);
    }
    if (a.compareTo(b) > 0) {
      HexCoord t = a;
      a = b;
      b = t;
    }
  }

  /**
   * 规范字符串形式。★ **两种用途**：① 它是变更集里 {@code edges} 组件的 **key**（Task 6 的 {@code
   * MapChangeSet.between/apply} 用 {@code keyOf = toString()} 比对与还原）；② JSON 边界。
   *
   * <p>串里的两段**必然**是规范序（构造期已换序），故它与 {@link #parse} 构成往返。
   */
  @Override
  public String toString() {
    return a + "|" + b;
  }

  /**
   * 解析 {@link #toString()} 的产物。★ 往返的另一半（R-48-f）：没有它，变更集里的 key 就还原不回来。
   *
   * <p>**段数不为 2 即抛**（宁抛不静默）：{@code null}、空串、分隔符在首尾、多一段少一段，一律 {@link IllegalArgumentException}。每段交给
   * {@link HexCoord#parse} —— 于是 {@code "1_2|x_y"} 由 {@code NumberFormatException}（IAE
   * 的子类）挡下，{@code "1_2|1_2"} 则由构造器的自环校验挡下：**乱序输入也会被规范成同一个对象**。
   */
  public static EdgeRef parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("非法边串: null");
    }
    int i = text.indexOf('|');
    if (i <= 0 || i == text.length() - 1 || text.indexOf('|', i + 1) >= 0) {
      throw new IllegalArgumentException("非法边串: " + text);
    }
    return new EdgeRef(HexCoord.parse(text.substring(0, i)), HexCoord.parse(text.substring(i + 1)));
  }

  @Override
  public int compareTo(EdgeRef o) {
    int c = a.compareTo(o.a);
    return c != 0 ? c : b.compareTo(o.b);
  }
}
