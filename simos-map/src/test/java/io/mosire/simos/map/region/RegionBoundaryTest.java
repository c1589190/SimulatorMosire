package io.mosire.simos.map.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 闭环边界：环是**格角顶点**（裁定 R-3a）、顶点度恒为 2（R-3e）、规范化落在紧凑构造器里（R-3d）。 */
class RegionBoundaryTest {

  @Test
  void singleHexRingHasSixVertices() {
    RegionBoundary boundary = RegionBoundary.of(Set.of(new HexCoord(0, 0)));

    assertThat(boundary.rings()).hasSize(1);
    // 规范形式：以字典序最小的顶点 (-1,-1) 开头，绕向取正反两个序列里字典序较小者
    assertThat(boundary.rings().getFirst())
        .containsExactly(
            new HexVertex(-1, -1),
            new HexVertex(-1, 1),
            new HexVertex(0, 2),
            new HexVertex(1, 1),
            new HexVertex(1, -1),
            new HexVertex(0, -2));
  }

  @Test
  void emptyHexSetHasNoRings() {
    assertThat(RegionBoundary.of(Set.of()).rings()).isEmpty();
  }

  @Test
  void twoAdjacentHexesShareOneRing() {
    HexCoord a = new HexCoord(0, 0);
    HexCoord b = new HexCoord(1, 0);
    RegionBoundary boundary = RegionBoundary.of(Set.of(a, b));

    assertThat(boundary.rings()).hasSize(1);
    // 12 条边里共享的那 1 条成了内部边，故 10 个顶点（不是 12、也不是 11）
    List<HexVertex> ring = boundary.rings().getFirst();
    assertThat(ring).hasSize(10);
    assertThat(ring).containsExactlyInAnyOrderElementsOf(union(verticesOf(a), verticesOf(b)));
  }

  @Test
  void nonContiguousHexesGiveMultipleRings() {
    HexCoord a = new HexCoord(0, 0);
    HexCoord b = new HexCoord(4, 4);
    RegionBoundary boundary = RegionBoundary.of(Set.of(a, b));

    assertThat(boundary.rings()).hasSize(2);
    assertThat(boundary.rings().stream().map(Set::copyOf).toList())
        .containsExactlyInAnyOrder(verticesOf(a), verticesOf(b));
  }

  /** ★ 拿**存储的那份**比重算值。写成"算两次比两次"只证明确定性，不证明存下来的那份是对的。 */
  @Test
  void storedBoundaryEqualsRecomputed() {
    Region region = Region.of(new RegionId("r1"), "北境", cluster(), RegionMeta.empty());

    assertThat(region.boundary()).isEqualTo(RegionBoundary.of(region.hexes()));
  }

  /**
   * ★ **本任务最容易漏的一条**：U2 之后 {@code boundary} 是 {@code Region} 的组件、{@code Region.equals} 逐组件比较，而
   * {@code hexes} 是 {@code Set.copyOf}（不保序）—— 若 {@code of} 顺着迭代序走，内容相同的两个 Region 会不相等。
   *
   * <p>入参刻意取**两个迭代序相反的集合**，并在断言前先证明这两份入参的迭代序**确实不同**：否则本用例在两种实现下都恒绿，等于空转。 集合本身不连通（一簇 + 一格）⇒
   * 顺带压住"环表的顺序"也随迭代序变。
   */
  @Test
  void boundaryIsIndependentOfInputSetIterationOrder() {
    List<HexCoord> hexes =
        List.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1), new HexCoord(5, 5));
    Set<HexCoord> forward = new LinkedHashSet<>(hexes);
    List<HexCoord> backwards = new ArrayList<>(hexes);
    Collections.reverse(backwards);
    Set<HexCoord> backward = new LinkedHashSet<>(backwards);

    assertThat(List.copyOf(forward)).as("两份入参的迭代序必须真的不同").isNotEqualTo(List.copyOf(backward));
    assertThat(RegionBoundary.of(forward).rings()).hasSize(2);

    assertThat(RegionBoundary.of(backward)).isEqualTo(RegionBoundary.of(forward));
  }

  /**
   * 紧凑构造器的三条规范化：**起点**旋到最小顶点、**绕向**取字典序较小者、**环表**按首顶点排序。
   *
   * <p>入参用"合法但非规范"的环（旋转过的、反向的、换过序的）—— 反序列化进来的存档正是这种形态，它们必须被就地归一，否则两个内容相同的 Region 会因为存档写法不同而不相等。
   */
  @Test
  void compactConstructorNormalizesStartDirectionAndRingOrder() {
    List<HexVertex> ringA = RegionBoundary.of(Set.of(new HexCoord(0, 0))).rings().getFirst();
    List<HexVertex> ringB = RegionBoundary.of(Set.of(new HexCoord(6, -3))).rings().getFirst();
    RegionBoundary canonical = new RegionBoundary(List.of(ringA, ringB));

    assertThat(new RegionBoundary(List.of(ringB, ringA))).as("环表换序").isEqualTo(canonical);
    assertThat(new RegionBoundary(List.of(rotated(ringA, 2), rotated(ringB, 3))))
        .as("环起点不同")
        .isEqualTo(canonical);
    assertThat(new RegionBoundary(List.of(reversed(ringA), reversed(ringB))))
        .as("绕向相反")
        .isEqualTo(canonical);
  }

  /**
   * ★ R-3e 的护栏：顶点的度 ≠ 2 必须抛，不许"取第一个未访问邻居"糊过去。
   *
   * <p>★ **触发它只能靠人造邻接图**：从 hex 集合出发造不出度 ≠ 2 的顶点 —— 这正是 R-3e 的推导（每个顶点恰有 3 格 3 边， 暴露边数 = {@code
   * k(3−k)}）；本类的其余用例在合法输入上从未触发这条守卫，是对该推导的旁证。故这里走包内可见的 {@link RegionBoundary#walkRings}。
   */
  @Test
  void walkRingsRejectsVertexDegreeOtherThanTwo() {
    HexVertex hub = new HexVertex(0, 0);
    HexVertex a = new HexVertex(1, -1);
    HexVertex b = new HexVertex(1, 1);
    HexVertex c = new HexVertex(0, 2);

    Map<HexVertex, List<HexVertex>> branching = new HashMap<>();
    branching.put(hub, List.of(a, b, c)); // 度 3：岔路口
    branching.put(a, List.of(hub, b));
    branching.put(b, List.of(a, c));
    branching.put(c, List.of(b, hub));
    assertThatThrownBy(() -> RegionBoundary.walkRings(branching))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("的度为 3");

    Map<HexVertex, List<HexVertex>> dangling = new HashMap<>();
    dangling.put(hub, new ArrayList<>(List.of(a))); // 度 1：断口
    dangling.put(a, new ArrayList<>(List.of(hub)));
    assertThatThrownBy(() -> RegionBoundary.walkRings(dangling))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("的度为 1");
  }

  private static Set<HexCoord> cluster() {
    return Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));
  }

  private static Set<HexVertex> verticesOf(HexCoord hex) {
    Set<HexVertex> vertices = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      vertices.add(HexVertex.at(hex, i));
    }
    return vertices;
  }

  private static Set<HexVertex> union(Set<HexVertex> a, Set<HexVertex> b) {
    Set<HexVertex> all = new HashSet<>(a);
    all.addAll(b);
    return all;
  }

  /** 环旋 {@code steps} 格（保持环序，只换起点）。 */
  private static List<HexVertex> rotated(List<HexVertex> ring, int steps) {
    List<HexVertex> result = new ArrayList<>(ring.size());
    for (int i = 0; i < ring.size(); i++) {
      result.add(ring.get((steps + i) % ring.size()));
    }
    return result;
  }

  /** 环反向（保持同一个起点）。 */
  private static List<HexVertex> reversed(List<HexVertex> ring) {
    List<HexVertex> result = new ArrayList<>(ring);
    Collections.reverse(result);
    return result;
  }
}
