package io.mosire.simos.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 一个格：只剩海拔（P1 之后地形进权威块）、**没有任何连通性字段**（L2 的第二份存储不许回来）。 */
class HexCellTest {

  /**
   * ★ **非有限海拔一律抛，且抛的是"有限数"那条守卫**（消息断言不是为了好看：{@code +∞} 在**调换过顺序**的实现里会落进范围校验、抛出的消息同样含 {@code
   * height}，只有精确到子串才分得出是哪一条守卫响的；而 {@code NaN} 在那种实现里**根本不抛**）。
   */
  @Test
  void rejectsNonFiniteHeight() {
    for (double bad :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThatThrownBy(() -> new HexCell(bad))
          .as("非有限海拔 %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("有限数");
    }
  }

  @Test
  void rejectsOutOfRangeHeight() {
    for (double bad : new double[] {-0.001, 1.001, -1.0, 2.0}) {
      assertThatThrownBy(() -> new HexCell(bad))
          .as("越界海拔 %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[0,1]");
    }
    // 两端**含**：归一化后 0 与 1 都是合法值
    assertThat(new HexCell(0.0).height()).isZero();
    assertThat(new HexCell(1.0).height()).isEqualTo(1.0);
  }

  /**
   * ★ **L2 的钉子**：组件清单恰为 {@code [height]} —— 既无连通性字段，也**不含 terrain**（P1 把它搬进了权威块）。
   *
   * <p>老仓的 {@code HexCell} 是 8 组件，其中 {@code edgeTags}（方向 → 组名表）与 {@code riverMask} 是连通性的**第二份存储**
   * ——Java 侧只读不写、只有前端写，两份之间又没有转换代码，于是前端一存就把所有边的 props 抹平。主存储只有 {@code GameMap.edges}。
   */
  @Test
  void hasNoConnectivityFieldAndNoTerrain() {
    assertThat(HexCell.class.getRecordComponents()).hasSize(1);
    assertThat(componentNames()).containsExactly("height");
    assertThat(componentNames()).doesNotContain("terrain", "edgeTags", "riverMask");
  }

  @Test
  void equalityIsComponentwise() {
    HexCell base = new HexCell(0.5);

    assertThat(new HexCell(0.5)).isEqualTo(base).hasSameHashCodeAs(base);
    assertThat(new HexCell(0.5001)).isNotEqualTo(base);
  }

  private static List<String> componentNames() {
    return Arrays.stream(HexCell.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
  }
}
