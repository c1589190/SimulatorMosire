package io.mosire.simos.map.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** spec §3.3：地图范围由 hex 集合导出（`gridSize` 已删），几何只有一份实现。 */
class HexGridTest {

  @Test
  void boundsDerivedFromCells() {
    HexGrid empty = HexGrid.of(Set.of());
    assertThatThrownBy(empty::minQ).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(empty::maxQ).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(empty::minR).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(empty::maxR).isInstanceOf(IllegalStateException.class);

    HexGrid grid = HexGrid.of(Set.of(new HexCoord(0, 0), new HexCoord(3, -2), new HexCoord(-1, 5)));
    assertThat(grid.minQ()).isEqualTo(-1);
    assertThat(grid.maxQ()).isEqualTo(3);
    assertThat(grid.minR()).isEqualTo(-2);
    assertThat(grid.maxR()).isEqualTo(5);
    assertThat(grid.contains(new HexCoord(-1, 5))).isTrue();
    assertThat(grid.contains(new HexCoord(5, -1))).isFalse();
    assertThat(grid.cells())
        .containsExactlyInAnyOrder(new HexCoord(0, 0), new HexCoord(3, -2), new HexCoord(-1, 5));
  }

  @Test
  void cellsWithinRadiusIsClosedBall() {
    HexCoord center = new HexCoord(2, -3);
    assertThat(HexGrid.withinRadius(center, 0)).containsExactly(center);
    assertThat(HexGrid.withinRadius(center, 1)).hasSize(7);
    assertThat(HexGrid.withinRadius(center, 2)).hasSize(19);
    assertThat(HexGrid.withinRadius(center, -1)).isEmpty();
  }

  @Test
  void cellsWithinRadiusAllInRange() {
    HexCoord center = new HexCoord(-4, 1);
    for (int radius = 0; radius <= 5; radius++) {
      Set<HexCoord> ball = HexGrid.withinRadius(center, radius);
      assertThat(ball).hasSize(3 * radius * radius + 3 * radius + 1);
      for (HexCoord c : ball) {
        assertThat(c.distanceTo(center)).as("半径 %d 内的 %s", radius, c).isLessThanOrEqualTo(radius);
      }
      assertThat(ball).contains(center);
    }
  }

  /** 构造期的 `Set.copyOf` 是一道真防线：删掉它，本用例立刻红（网格会跟着入参集合一起变）。 */
  @Test
  void mutatingTheSourceSetDoesNotAffectTheGrid() {
    Set<HexCoord> source = new HashSet<>();
    source.add(new HexCoord(0, 0));
    HexGrid grid = HexGrid.of(source);

    source.add(new HexCoord(9, 9));
    source.remove(new HexCoord(0, 0));

    assertThat(grid.cells()).containsExactly(new HexCoord(0, 0));
    assertThat(grid.contains(new HexCoord(0, 0))).isTrue();
    assertThat(grid.contains(new HexCoord(9, 9))).isFalse();
  }

  /**
   * 两处返回的集合都必须是不可变的（构造期拷贝 + `Set.copyOf`），否则调用方能反手改掉网格状态。
   *
   * <p>入参刻意用**可变**集合：若用 `Set.of(...)`（本就不可变），`cells()` 那一条会继承入参的属性而恒成立， 就算把构造期的 `Set.copyOf`
   * 删掉也照样绿——那正是"装饰性断言"。
   */
  @Test
  void returnedSetsAreUnmodifiable() {
    HexGrid grid = HexGrid.of(new HashSet<>(Set.of(new HexCoord(0, 0), new HexCoord(1, 1))));
    assertThat(grid.cells()).isUnmodifiable();
    assertThat(HexGrid.withinRadius(new HexCoord(0, 0), 1)).isUnmodifiable();
  }
}
