package io.mosire.simos.map.hex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link HexVertex#at} 的偏移表（控制器裁定 R-3b / R-3f：落码前先钉住推导，不靠"看着对"）。
 *
 * <p>两条性质**各自有判别力**：单格六顶点互不相同 ⇒ 表里没有重复的角；相邻两格恰好共享 2 个顶点 ⇒ 表与 {@link HexDirection} 的枚举序对得上（错一项就会共享到
 * 1 个或 3 个）。
 */
class HexVertexTest {

  @Test
  void singleHexHasSixDistinctVerticesAndEachNeighborSharesExactlyTwo() {
    HexCoord origin = new HexCoord(0, 0);
    Set<HexVertex> own = verticesOf(origin);
    assertThat(own).hasSize(6);

    // 六个方向**逐一**验，不只验 E：表里任一项错位都会在这里现形
    for (HexDirection d : HexDirection.ALL) {
      assertThat(verticesOf(origin.neighbor(d))).as("方向 %s 的邻格顶点数", d).hasSize(6);
      assertThat(intersection(own, verticesOf(origin.neighbor(d)))).as("方向 %s 共享的顶点", d).hasSize(2);
    }
  }

  /**
   * 第 {@code d} 条边的两个端点，必须**恰是**本格与 {@code neighbor(d)} 共享的那两个顶点 —— 这是"边序号 ↔ 顶点序号"的对应， {@code
   * RegionBoundary.of} 直接靠它把暴露边接成环。
   */
  @Test
  void edgeEndpointsAreTheVerticesSharedWithTheNeighbor() {
    HexCoord hex = new HexCoord(3, -2); // 刻意不用原点，免得"原点恰好也对"蒙混过去
    Set<HexVertex> own = verticesOf(hex);

    for (HexDirection d : HexDirection.ALL) {
      HexVertex from = HexVertex.at(hex, d.ordinal());
      HexVertex to = HexVertex.at(hex, (d.ordinal() + 1) % 6);
      assertThat(List.of(from, to))
          .as("方向 %s 的边端点", d)
          .containsExactlyInAnyOrderElementsOf(intersection(own, verticesOf(hex.neighbor(d))));
    }
  }

  private static Set<HexVertex> verticesOf(HexCoord hex) {
    Set<HexVertex> vertices = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      vertices.add(HexVertex.at(hex, i));
    }
    return vertices;
  }

  private static Set<HexVertex> intersection(Set<HexVertex> a, Set<HexVertex> b) {
    Set<HexVertex> shared = new HashSet<>(a);
    shared.retainAll(b);
    return shared;
  }
}
