package io.mosire.simos.unit.move;

import static io.mosire.simos.unit.move.MoveFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/** 毫 MP 成本：判据二的两条成本数（12500 / 32500）+ 不可通行 + 越界 + 调用方 bug。 */
class TerrainMovementCostTest {

  private final MovementCost cost = TerrainMovementCost.INSTANCE;

  @Test
  void stepCostsMatchTheFrozenFixture() {
    GameMap map = map(STEEP_65);
    assertThat(cost.costMillis(H11, H12, unit(), map))
        .as("25 × 1000 = 25000 毫，按 ‰500 缩放 ⇒ floor((25000×500+500)/1000) = 12500")
        .hasValue(12500L);
    assertThat(cost.costMillis(H12, H13, unit(), map))
        .as("65 × 1000 = 65000 毫，按 ‰500 缩放 ⇒ 32500")
        .hasValue(32500L);
  }

  @Test
  void impassableTerrainHasNoCost() {
    assertThat(cost.costMillis(H12, H13, unit(), map(IMPASSABLE_999))).isEmpty();
  }

  @Test
  void hexOutsideTheMapHasNoCost() {
    assertThat(cost.costMillis(H11, new HexCoord(9, 9), unit(), map(STEEP_65))).isEmpty();
  }

  @Test
  void nonAdjacentStepsAreACallerBug() {
    assertThatThrownBy(() -> cost.costMillis(H11, H13, unit(), map(STEEP_65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cost.costMillis(H11, H11, unit(), map(STEEP_65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownTerrainKeyThrows() {
    GameMap map = map(STEEP_65);
    GameMap broken =
        new GameMap(
            map.hexes(),
            map.regions(),
            map.cities(),
            java.util.Map.of(),
            map.pathways(),
            map.pathwayGroups(),
            map.edges(),
            map.spec());
    assertThatThrownBy(() -> cost.costMillis(H11, H12, unit(), broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minStepCostIsTheCheapestTraversableTerrainScaled() {
    assertThat(cost.minStepCostMillis(unit(), map(STEEP_65)))
        .as("最便宜的可通行地形是 25 ⇒ 12500；999 那条不算")
        .isEqualTo(12500L);
  }

  @Test
  void allImpassableMapHasZeroLowerBound() {
    assertThat(cost.minStepCostMillis(unit(), allImpassableMap()))
        .as("三个格全是 999 ⇒ 无可通行格 ⇒ 0（合法下界，spec §4.3 第 6 条）")
        .isEqualTo(0L);
  }
}
