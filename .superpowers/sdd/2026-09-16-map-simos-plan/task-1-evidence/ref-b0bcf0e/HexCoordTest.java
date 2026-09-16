package io.mosire.simos.map.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §3.1 / §3.4：坐标是身份，`"q_r"` 只是序列化形式；距离只有一份实现。 */
class HexCoordTest {

  /** 冻结样例：`(q, r)` → 规范串。这是 JSON 边界的形式，不能随手改。 */
  @Test
  void toStringAndParseRoundTrip() {
    int[][] samples = {{0, 0}, {0, -1}, {-3, 7}};
    String[] frozen = {"0_0", "0_-1", "-3_7"};
    for (int i = 0; i < samples.length; i++) {
      HexCoord c = new HexCoord(samples[i][0], samples[i][1]);
      assertThat(c.toString()).as("冻结串 %d", i).isEqualTo(frozen[i]);
      assertThat(HexCoord.parse(c.toString())).as("往返 %d", i).isEqualTo(c);
      assertThat(HexCoord.parse(frozen[i])).as("按冻结串解析 %d", i).isEqualTo(c);
    }
    for (int q = -20; q <= 20; q++) {
      for (int r = -20; r <= 20; r++) {
        HexCoord c = new HexCoord(q, r);
        assertThat(HexCoord.parse(c.toString())).isEqualTo(c);
      }
    }
  }

  /**
   * 非法串一律 `IllegalArgumentException`，但**来源不同**：前五种里 `""`/`"_"`/`"1_"`/`"_2"` 命中的是 `parse`
   * 的显式判形，`"a_b"` 命中的是 `Integer.parseInt` 抛出的 `NumberFormatException`（`IAE` 的子类）； `null`
   * 命中的是显式判空——删掉判空后这里会变成 NPE，故这一行钉得住判空本身。
   */
  @Test
  void parseRejectsMalformed() {
    assertThatThrownBy(() -> HexCoord.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse("_")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse("1_")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse("_2")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse("a_b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sAxisInvariant() {
    for (int q = -20; q <= 20; q++) {
      for (int r = -20; r <= 20; r++) {
        HexCoord c = new HexCoord(q, r);
        assertThat(q + r + c.s()).as("%s 的 cube 恒等式", c).isZero();
      }
    }
  }

  @Test
  void distanceIsSymmetricAndZeroOnSelf() {
    for (int q = -6; q <= 6; q++) {
      for (int r = -6; r <= 6; r++) {
        HexCoord a = new HexCoord(q, r);
        assertThat(a.distanceTo(a)).as("%s 到自身", a).isZero();
        for (int q2 = -6; q2 <= 6; q2++) {
          for (int r2 = -6; r2 <= 6; r2++) {
            HexCoord b = new HexCoord(q2, r2);
            assertThat(a.distanceTo(b)).as("%s → %s", a, b).isEqualTo(b.distanceTo(a));
          }
        }
      }
    }
  }

  /**
   * 与**独立写一遍**的 cube 公式（三轴最大绝对值，`max(|dq|,|dr|,|ds|)`）逐点对拍。 `distanceTo` 用的是
   * `(|dq|+|dr|+|ds|)/2`，两种形式代数恒等但写法不同源——同源错误（比如把 `s()` 写成 `q + r`）因此会被抓住。
   */
  @Test
  void distanceMatchesCubeFormula() {
    for (int q = -6; q <= 6; q++) {
      for (int r = -6; r <= 6; r++) {
        HexCoord a = new HexCoord(q, r);
        for (int q2 = -6; q2 <= 6; q2++) {
          for (int r2 = -6; r2 <= 6; r2++) {
            HexCoord b = new HexCoord(q2, r2);
            assertThat(a.distanceTo(b)).as("%s → %s", a, b).isEqualTo(cubeDistanceByMax(a, b));
          }
        }
      }
    }
  }

  @Test
  void distanceOnKnownPairs() {
    HexCoord origin = new HexCoord(0, 0);
    assertThat(origin.distanceTo(new HexCoord(1, 0))).isEqualTo(1);
    assertThat(origin.distanceTo(new HexCoord(2, -1))).isEqualTo(2);
    assertThat(origin.distanceTo(new HexCoord(-3, 3))).isEqualTo(3);
  }

  @Test
  void neighborIsInvolutive() {
    for (int q = -3; q <= 3; q++) {
      for (int r = -3; r <= 3; r++) {
        HexCoord a = new HexCoord(q, r);
        for (HexDirection d : HexDirection.ALL) {
          assertThat(a.neighbor(d).neighbor(d.opposite())).as("%s 沿 %s 往返", a, d).isEqualTo(a);
        }
      }
    }
  }

  @Test
  void neighborsAreSixDistinctAtDistance1() {
    HexCoord center = new HexCoord(2, -3);
    List<HexCoord> neighbors = center.neighbors();
    assertThat(neighbors).hasSize(6);
    assertThat(neighbors).doesNotHaveDuplicates();
    for (HexCoord n : neighbors) {
      assertThat(n.distanceTo(center)).as("%s 到中心", n).isEqualTo(1);
    }
  }

  @Test
  void compareToIsTotalOrder() {
    List<HexCoord> sample =
        List.of(
            new HexCoord(1, -1),
            new HexCoord(0, 0),
            new HexCoord(1, -5),
            new HexCoord(-2, 0),
            new HexCoord(1, 3),
            new HexCoord(-2, -4));
    for (HexCoord a : sample) {
      assertThat(a.compareTo(a)).as("%s 与自身", a).isZero();
      for (HexCoord b : sample) {
        int lexicographic =
            a.q() != b.q() ? Integer.compare(a.q(), b.q()) : Integer.compare(a.r(), b.r());
        assertThat(Integer.signum(a.compareTo(b))).as("%s 与 %s", a, b).isEqualTo(lexicographic);
      }
    }
    List<HexCoord> byNaturalOrder = new ArrayList<>(sample);
    Collections.sort(byNaturalOrder);
    List<HexCoord> byLexicographic = new ArrayList<>(sample);
    byLexicographic.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    assertThat(byNaturalOrder).isEqualTo(byLexicographic);
  }

  /** 独立于 `HexCoord.distanceTo` 的另一份写法：cube 三轴最大绝对值（cube 坐标下 x+y+z==0）。 */
  private static int cubeDistanceByMax(HexCoord a, HexCoord b) {
    int dq = a.q() - b.q();
    int dr = a.r() - b.r();
    int ds = a.s() - b.s();
    return Math.max(Math.abs(dq), Math.max(Math.abs(dr), Math.abs(ds)));
  }
}
