package io.mosire.simos.map.pathway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 无向边：**身份就是那两格**，规范序在构造期完成（spec §5.1 的四份手写实现合并成一份）。 */
class EdgeRefTest {

  private static HexCoord c(int q, int r) {
    return new HexCoord(q, r);
  }

  @Test
  void orderIsCanonical() {
    assertThat(new EdgeRef(c(3, 4), c(-1, 0))).isEqualTo(new EdgeRef(c(-1, 0), c(3, 4)));
    assertThat(new EdgeRef(c(3, 4), c(-1, 0))).hasSameHashCodeAs(new EdgeRef(c(-1, 0), c(3, 4)));
    // 两个方向都拿得到同一对象 ⇒ 记规范序的那一格恒为 a()
    assertThat(new EdgeRef(c(3, 4), c(-1, 0)).a()).isEqualTo(c(-1, 0));
    assertThat(new EdgeRef(c(3, 4), c(-1, 0)).b()).isEqualTo(c(3, 4));
  }

  /**
   * ★ 结构性质：**不论怎么构造**，{@code a.compareTo(b) <= 0} 恒成立 —— 这正是"两种方向是同一对象"的来源。
   *
   * <p>用 7×7 网格逐对穷举（两个方向各构造一次），不是挑一个样例看一眼；顺带钉住**声明序** {@code (a, b)}：Task 6 的 {@code FieldDelta} 与
   * JSON 边界都按它来。
   */
  @Test
  void canonicalFormTouchesASortedFields() {
    assertThat(EdgeRef.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("a", "b");

    for (int q1 = -3; q1 <= 3; q1++) {
      for (int r1 = -3; r1 <= 3; r1++) {
        for (int q2 = -3; q2 <= 3; q2++) {
          for (int r2 = -3; r2 <= 3; r2++) {
            HexCoord p = c(q1, r1);
            HexCoord s = c(q2, r2);
            if (p.equals(s)) {
              continue;
            }
            EdgeRef fwd = new EdgeRef(p, s);
            EdgeRef rev = new EdgeRef(s, p);
            assertThat(fwd.a().compareTo(fwd.b())).as("规范序 %s", fwd).isLessThanOrEqualTo(0);
            assertThat(fwd).as("同一无向边的两个方向 %s / %s", p, s).isEqualTo(rev);
          }
        }
      }
    }
  }

  @Test
  void selfLoopIsRejected() {
    assertThatThrownBy(() -> new EdgeRef(c(2, 2), c(2, 2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("自环");
  }

  @Test
  void nullEndpointIsRejected() {
    assertThatThrownBy(() -> new EdgeRef(null, c(0, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
    assertThatThrownBy(() -> new EdgeRef(c(0, 0), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
  }

  /** 同一无向边的两个构造方向，串必须**逐字相同**（否则拿它当变更集 key 会分裂成两个键）。 */
  @Test
  void toStringIsStable() {
    assertThat(new EdgeRef(c(3, 4), c(-1, 0)).toString())
        .isEqualTo(new EdgeRef(c(-1, 0), c(3, 4)).toString());
  }

  /**
   * ★ **冻结串**（R-48-f）：规范序**在前**，不是入参序 —— {@link HexCoord#compareTo} 先 q 后 r，{@code -1 < 3} 故 {@code
   * (-1,0)} 在前。{@code toStringIsStable} 只比两个方向，**不算冻结**，光有它不达标。
   */
  @Test
  void toStringMatchesFrozenLiteral() {
    assertThat(new EdgeRef(c(3, 4), c(-1, 0)).toString()).isEqualTo("-1_0|3_4");
    assertThat(new EdgeRef(c(-1, 0), c(3, 4)).toString()).isEqualTo("-1_0|3_4");

    // ★ 规范序是**坐标序**，不是字符串序：按串序 "10_0" 会排在 "2_0" 之前，坐标序把 (2,0) 排在 (10,0) 之前。
    //   老仓 MapData.edgeKey 走的正是串序 —— 这条断言把两种口径的分叉钉在明面上。
    assertThat(new EdgeRef(c(10, 0), c(2, 0)).toString()).isEqualTo("2_0|10_0");
  }

  /** ★ 往返（R-48-f）：变更集拿 {@code toString()} 当 String key，apply 侧靠 {@link EdgeRef#parse} 还原。 */
  @Test
  void parseRoundTripsFrozenLiteral() {
    assertThat(EdgeRef.parse("-1_0|3_4")).isEqualTo(new EdgeRef(c(3, 4), c(-1, 0)));
    assertThat(EdgeRef.parse(new EdgeRef(c(3, 4), c(-1, 0)).toString()))
        .isEqualTo(new EdgeRef(c(-1, 0), c(3, 4)));

    // 乱序输入也收敛到同一对象（构造期的规范序在解析路径上同样生效）
    assertThat(EdgeRef.parse("3_4|-1_0")).isEqualTo(EdgeRef.parse("-1_0|3_4"));

    // 段数不为 2 的串一律抛（宁抛不静默）
    for (String bad : new String[] {"", "1_2", "|1_2", "1_2|", "1_2|3_4|5_6", "-1_0|3_4|"}) {
      assertThatThrownBy(() -> EdgeRef.parse(bad))
          .as("非法边串 %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> EdgeRef.parse(null)).isInstanceOf(IllegalArgumentException.class);
    // 单段形态不合法（`HexCoord.parse` 的判形）与数字字段写错（`NumberFormatException`，IAE 的子类）
    assertThatThrownBy(() -> EdgeRef.parse("1|2")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EdgeRef.parse("a_b|c_d")).isInstanceOf(IllegalArgumentException.class);
    // 自环串由构造器挡下 —— 往返的两端共用同一批守卫
    assertThatThrownBy(() -> EdgeRef.parse("1_2|1_2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("自环");
  }

  @Test
  void compareToIsTotalOrder() {
    List<EdgeRef> edges = new ArrayList<>();
    edges.add(new EdgeRef(c(3, 4), c(-1, 0)));
    edges.add(new EdgeRef(c(0, 0), c(1, 0)));
    edges.add(new EdgeRef(c(0, 0), c(0, 1)));
    edges.add(new EdgeRef(c(-1, 0), c(3, 4)));
    edges.add(new EdgeRef(c(0, 0), c(1, 0)));

    // 反对称：逐对比较，符号相反（相等时为 0）
    for (EdgeRef x : edges) {
      for (EdgeRef y : edges) {
        assertThat(Integer.signum(x.compareTo(y))).isEqualTo(-Integer.signum(y.compareTo(x)));
      }
    }
    List<EdgeRef> sorted = new ArrayList<>(edges);
    sorted.sort(Comparator.naturalOrder());
    for (int i = 0; i + 1 < sorted.size(); i++) {
      assertThat(sorted.get(i).compareTo(sorted.get(i + 1))).isLessThanOrEqualTo(0);
    }
    // 首键相同则比次键：(0,0) 打头的两条按次键 (0,1) < (1,0)（先 q 后 r）
    assertThat(sorted)
        .extracting(EdgeRef::toString)
        .containsExactly("-1_0|3_4", "-1_0|3_4", "0_0|0_1", "0_0|1_0", "0_0|1_0");
  }
}
