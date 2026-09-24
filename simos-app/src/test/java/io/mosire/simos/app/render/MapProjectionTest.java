package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 六角投影与视野取格：<b>与前端 {@code hexgeom.js} 同型</b>是这个类的存在理由——两套投影会让"用户看到的"与"模型看到的"不是同一
 * 个世界。用例把三件事钉死：取格数（R=1/2/3 ⇒ 7/19/37）、距离、顶点位置。
 */
class MapProjectionTest {

  @Test
  void hexesWithinCountsGrowAsThreeRPlusThreeRPlusOne() {
    HexCoord center = new HexCoord(0, 0);
    assertThat(MapProjection.hexesWithin(center, 1)).hasSize(7);
    assertThat(MapProjection.hexesWithin(center, 2)).hasSize(19);
    assertThat(MapProjection.hexesWithin(center, 3)).hasSize(37);
    // 全部落在半径内，且包含中心
    List<HexCoord> ring = MapProjection.hexesWithin(new HexCoord(-5, 7), 2);
    assertThat(ring)
        .allSatisfy(
            coord ->
                assertThat(MapProjection.distance(coord, new HexCoord(-5, 7)))
                    .isLessThanOrEqualTo(2));
    assertThat(ring).contains(new HexCoord(-5, 7));
  }

  @Test
  void distanceIsAxialCubeDistance() {
    assertThat(MapProjection.distance(new HexCoord(0, 0), new HexCoord(0, 0))).isZero();
    assertThat(MapProjection.distance(new HexCoord(0, 0), new HexCoord(1, 0))).isEqualTo(1);
    assertThat(MapProjection.distance(new HexCoord(0, 0), new HexCoord(1, -1))).isEqualTo(1);
    assertThat(MapProjection.distance(new HexCoord(0, 0), new HexCoord(2, -1))).isEqualTo(2);
    assertThat(MapProjection.distance(new HexCoord(3, -5), new HexCoord(-2, 4))).isEqualTo(9);
  }

  @Test
  void centerProjectionMatchesThePointyTopFormula() {
    double size = 10.0;
    // 原点格在原点；东邻 x = √3*size；东南邻（r+1）y = 1.5*size
    assertThat(MapProjection.centerX(new HexCoord(0, 0), size)).isZero();
    assertThat(MapProjection.centerY(new HexCoord(0, 0), size)).isZero();
    assertThat(MapProjection.centerX(new HexCoord(1, 0), size))
        .isCloseTo(Math.sqrt(3) * size, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(MapProjection.centerY(new HexCoord(0, 1), size)).isEqualTo(1.5 * size);
  }

  @Test
  void cornersAreSixPointsAtTheGivenRadiusStartingFromMinusThirtyDegrees() {
    double size = 8.0;
    double[][] corners = MapProjection.corners(size);
    assertThat(corners.length).isEqualTo(6);
    for (double[] point : corners) {
      assertThat(Math.hypot(point[0], point[1]))
          .isCloseTo(size, org.assertj.core.data.Offset.offset(1e-9));
    }
    assertThat(corners[0][0])
        .isCloseTo(size * Math.cos(Math.toRadians(-30)), org.assertj.core.data.Offset.offset(1e-9));
    assertThat(corners[0][1])
        .isCloseTo(size * Math.sin(Math.toRadians(-30)), org.assertj.core.data.Offset.offset(1e-9));
  }

  /**
   * 边的配对：东邻（dir 0）共享的是<b>右侧</b>那条边——两个端点的中点必须落在中心右侧。 判别性：把 {@code edgeCornerIndexes} 的配对写错一位（如把东邻配成
   * 1/2），中点会跑到右下，本用例必红。
   */
  @Test
  void eastNeighborSharesTheRightmostEdge() {
    double size = 10.0;
    double[][] corners = MapProjection.corners(size);
    int[] edge = MapProjection.edgeCornerIndexes(0);
    double midX = (corners[edge[0]][0] + corners[edge[1]][0]) / 2.0;
    double midY = (corners[edge[0]][1] + corners[edge[1]][1]) / 2.0;
    assertThat(midX).isGreaterThan(0.0);
    assertThat(midY).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    // 六个方向各自成对且互不相同
    assertThat(edge).hasSize(2).doesNotHaveDuplicates();
    assertThatThrownBy(() -> MapProjection.edgeCornerIndexes(6))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
