package io.mosire.simos.map.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ★ **R2（M3）**：不可通行的判据只有一处 —— {@link TerrainType#IMPASSABLE_MOVE_COST}。
 *
 * <p>UnitSimos 的成本函数要判"这格能不能走"，判据必须与词表里的海洋取值**同源**：各写各的 999 就会在下一次调词表时静默分叉（改了一处、另一处还按老数走）。
 */
class ImpassableSentinelTest {

  @Test
  void oceanUsesTheImpassableSentinel() {
    assertThat(TerrainCatalog.of("ocean").moveCost())
        .as("海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）")
        .isEqualTo(TerrainType.IMPASSABLE_MOVE_COST);
  }

  @Test
  void everyOtherTerrainIsBelowTheSentinel() {
    assertThat(TerrainCatalog.KEYS)
        .filteredOn(key -> !"ocean".equals(key)) // 海洋取的就是哨兵本身，本条管其余项
        .as("除海洋外每一项的 moveCost 都必须严格小于哨兵——哨兵不参与任何算术")
        .allSatisfy(
            key ->
                assertThat(TerrainCatalog.of(key).moveCost())
                    .as("地形 %s 的 moveCost", key)
                    .isLessThanOrEqualTo(TerrainType.IMPASSABLE_MOVE_COST - 1));
  }
}
