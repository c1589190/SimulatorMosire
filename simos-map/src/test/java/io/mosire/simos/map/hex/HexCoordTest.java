package io.mosire.simos.map.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §3.1 / §3.4：坐标是身份，`"q_r"` 只是序列化形式；距离与取整**各只有一份实现**。 */
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
   * 非法串一律 `IllegalArgumentException`，但**来源与判别力都不同**：`""` 与 `"12"`（无分隔符）命中 `parse` 的显式判形，
   * 且**只有这两条**是判形的判别力来源——删掉判形后它们炸的是 `StringIndexOutOfBoundsException`（`i = -1` 时 `substring(0, -1)`
   * 先于任何 `parseInt` 出手），而**它不 `extends` `IAE`**，断言因此会翻。
   *
   * <p>`"_"`/`"1_"`/`"_2"` **不是**这条判形的判别力来源：它们删掉判形后由 `NumberFormatException` 挡下，而 `NFE` 是 `IAE`
   * 的**子类**，`isInstanceOf(IAE.class)` 根本不翻。`"a_b"` 走的正是这条继承路径——`parse` 的 Javadoc 里写明
   * "没有任何一行显式检查在挡它"。`null` 命中的是显式判空——删掉判空后这里会变成 NPE，故这一行钉得住判空本身。
   */
  @Test
  void parseRejectsMalformed() {
    assertThatThrownBy(() -> HexCoord.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HexCoord.parse("12")).isInstanceOf(IllegalArgumentException.class);
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
   * 与**独立写一遍**的 cube 公式（三轴最大绝对值，`max(|dq|,|dr|,|ds|)`）逐点对拍。`distanceTo` 用的是
   * `(|dq|+|dr|+|ds|)/2`，两种形式代数恒等但写法不同源，故本条钉的是 `distanceTo` 的**组合规则**：漏掉 `/2`、或只用两轴，都会翻。
   *
   * <p>★ 它对"把 `s()` 写成 `q + r`（第三轴整体变号）"**完全免疫**，别指望它抓这个：对拍两侧都调 `s()`，且 `z = x + y` 恒有
   * `max(|x|,|y|,|z|) == (|x|+|y|+|z|)/2`。`s()` 本身另有钉子——`sAxisInvariant` 的 `q + r + s() ==
   * 0`（实测：该变异只有它翻）。
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

  /**
   * ★ `round` 的判别力来源：对分数网格逐点**暴力枚举**候选格求出真正的最近距离，断言 `round` 的结果距输入点的 cube 距离 **等于**该最小值。
   *
   * <p>断言**距离等价**而不是"坐标一致"——最近格会**并列**（`(0.5, 0.5)` 就有两个），按坐标比会在并列处**假红**。朴素的逐轴四舍五入在 `(0.5, 0.5)`
   * 上给距离 1（真正的最近距离是 0.5），故照样必红；漏掉"修正偏差最大那一轴"的任何写法同理。
   *
   * <p>输入取 0.5 的整数倍：这类值在二进制下精确，且 ×2 之后就是**整数** cube 坐标，于是"分数点到格"的距离可以借 {@link
   * HexCoord#distanceTo(HexCoord)} 在放大两倍的格上比较——`distanceTo` 对整体缩放是齐次的，且两侧各轴差值全是偶数，那个 `/2`
   * 给出的正是精确折半的距离（无浮点误差，可用 `isEqualTo` 直接比）。
   */
  @Test
  void roundIsNearestHex() {
    List<HexCoord> candidates = new ArrayList<>();
    for (int cq = -4; cq <= 4; cq++) {
      for (int cr = -4; cr <= 4; cr++) {
        candidates.add(new HexCoord(cq, cr));
      }
    }
    for (double q = -2.0; q <= 2.0; q += 0.5) {
      for (double r = -2.0; r <= 2.0; r += 0.5) {
        HexCoord doubled = new HexCoord((int) Math.round(2 * q), (int) Math.round(2 * r));
        double nearest = Double.MAX_VALUE;
        for (HexCoord c : candidates) {
          nearest = Math.min(nearest, distanceFromDoubled(doubled, c));
        }
        assertThat(distanceFromDoubled(doubled, HexCoord.round(q, r)))
            .as("(%s, %s) 的取整格是否最近", q, r)
            .isEqualTo(nearest);
      }
    }
  }

  /** 分数点到整数格的 cube 距离：两侧各放大两倍后借 `distanceTo` 比较（各轴差值全是偶数，`/2` 精确）。 */
  private static double distanceFromDoubled(HexCoord doubledPoint, HexCoord cell) {
    return doubledPoint.distanceTo(new HexCoord(2 * cell.q(), 2 * cell.r())) / 2.0;
  }

  /** 冻结样例：最近格**不并列**的几组非 0.5 倍数输入，钉住取整结果本身（并列处按裁定不钉哪一侧）。 */
  @Test
  void roundOnFrozenSamples() {
    assertThat(HexCoord.round(0.6, 0.4)).isEqualTo(new HexCoord(1, 0));
    assertThat(HexCoord.round(-0.6, -0.4)).isEqualTo(new HexCoord(-1, 0));
    assertThat(HexCoord.round(2.2, -1.1)).isEqualTo(new HexCoord(2, -1));
  }

  /** `NaN`/±无穷**抛异常**，不静默产出 `(0, 0)`——`Math.round(Double.NaN)` 是 `0`，那是把错误藏起来。 */
  @Test
  void roundRejectsNonFinite() {
    double[] nonFinite = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (double v : nonFinite) {
      assertThatThrownBy(() -> HexCoord.round(v, 0.0))
          .as("q = %s", v)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> HexCoord.round(0.0, v))
          .as("r = %s", v)
          .isInstanceOf(IllegalArgumentException.class);
    }
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

  /**
   * ★ A 序的"序"从坐标侧看的真值锚：邻格**按 A 序逐项冻结**，且对非原点再钉一遍同一张表平移后的形状——序与中心无关。
   *
   * <p>`neighborsAreSixDistinctAtDistance1` 只查 size / 互异 / 距离、**不看顺序**，故 `neighbors()` 加
   * `.reversed()` 的变异体能整轮全绿存活；这里断言的是**绝对坐标**，不是"与某表达式一致"（那与被测实现同源）。
   *
   * <p>末段并入需求书 R1-e 第 3 条：从任一方向起连走 6 次 `next()` 必须回到起点，且沿途按 A 序访问全部 6 个方向——它把 `next()`
   * 的朝向与**同一张冻结表**对上（`next()` 自身的绝对转移另见 `HexDirectionTest.nextAndPrevFollowFrozenCycle`）。
   * 两张表都是冻结的**字面量**，不从 `HexDirection.values()`/`ALL` 读——那是同源。
   */
  @Test
  void neighborsFollowDirectionOrder() {
    int[][] frozenOffsets = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};
    HexDirection[] frozenCycle = {
      HexDirection.E,
      HexDirection.SE,
      HexDirection.SW,
      HexDirection.W,
      HexDirection.NW,
      HexDirection.NE
    };

    HexCoord origin = new HexCoord(0, 0);
    assertThat(origin.neighbors())
        .containsExactly(
            new HexCoord(1, 0),
            new HexCoord(0, 1),
            new HexCoord(-1, 1),
            new HexCoord(-1, 0),
            new HexCoord(0, -1),
            new HexCoord(1, -1));

    assertThat(new HexCoord(2, -3).neighbors())
        .containsExactly(
            new HexCoord(3, -3),
            new HexCoord(2, -2),
            new HexCoord(1, -2),
            new HexCoord(1, -3),
            new HexCoord(2, -4),
            new HexCoord(3, -4));

    for (int start = 0; start < frozenCycle.length; start++) {
      List<HexCoord> walked = new ArrayList<>();
      HexDirection d = frozenCycle[start];
      for (int step = 0; step < 6; step++) {
        walked.add(origin.neighbor(d));
        d = d.next();
      }
      assertThat(d).as("从 %s 连走 6 次 next() 的落点", frozenCycle[start]).isEqualTo(frozenCycle[start]);
      List<HexCoord> expected = new ArrayList<>();
      for (int step = 0; step < 6; step++) {
        int[] offset = frozenOffsets[(start + step) % 6];
        expected.add(new HexCoord(offset[0], offset[1]));
      }
      assertThat(walked).as("从 %s 起的 A 序环", frozenCycle[start]).isEqualTo(expected);
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
