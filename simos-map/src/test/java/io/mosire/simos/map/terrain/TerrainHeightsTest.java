package io.mosire.simos.map.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * ★ {@link TerrainHeights}：涂色高度（用户 2026-09-24 裁定——**沙漠只比平原高 0.005**）。
 *
 * <p>判别点：沙漠那一项**必须**等于「平原带中点 + 0.005」，而不是它自己带的中点（0.50）——把实现改回"一律带中点"，本类当场红。
 */
class TerrainHeightsTest {

  /**
   * 沙漠 = 平原带中点 + 0.005（★ 本类的主判据；用差值断言，不写死 0.38 以免复述实现）。
   *
   * <p>★ 差值用**容差**断言：0.375 + 0.005 在 IEEE 双精度下是 0.3800000000000000044（打印仍是 {@code 0.38}）， 拿 {@code
   * isEqualTo(0.005)} 去卡是在卡浮点表示，不是卡口径。
   */
  @Test
  void desertSitsJustAbovePlains() {
    double plains = TerrainHeights.paintHeight("plains");
    double desert = TerrainHeights.paintHeight("desert");

    assertThat(desert - plains).as("沙漠只比平原高 0.005").isCloseTo(0.005, within(1e-12));
    assertThat(desert)
        .as("沙漠**不是**取自己的带中点（0.50）")
        .isNotEqualTo(
            (TerrainCatalog.of("desert").minHeight() + TerrainCatalog.of("desert").maxHeight())
                / 2.0);
  }

  /** 其余地形一律取**自己的带中点**（与导入器 {@code representative_height} 同值）。 */
  @Test
  void everyOtherTerrainUsesItsOwnBandMidpoint() {
    for (String key : TerrainCatalog.KEYS) {
      if ("desert".equals(key)) {
        continue;
      }
      TerrainType type = TerrainCatalog.of(key);
      assertThat(TerrainHeights.paintHeight(key))
          .as("%s 取带中点", key)
          .isEqualTo((type.minHeight() + type.maxHeight()) / 2.0);
    }
  }

  /** 7 类都有涂色高度，且都落在 [0,1]（{@code HexCell} 的构造期硬约束）。 */
  @Test
  void everyCatalogKeyHasAHeightInsideTheUnitRange() {
    assertThat(TerrainCatalog.KEYS).as("前置：词表非空").isNotEmpty();
    for (String key : TerrainCatalog.KEYS) {
      assertThat(TerrainHeights.paintHeight(key)).as("%s 的涂色高度在 [0,1]", key).isBetween(0.0, 1.0);
    }
  }

  /** 词表外 / null ⇒ 抛，不兜底（与 {@code TerrainCatalog.of} 同口径）。 */
  @Test
  void rejectingUnknownKeys() {
    assertThatThrownBy(() -> TerrainHeights.paintHeight("forest"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未知地形类型: forest");
    assertThatThrownBy(() -> TerrainHeights.paintHeight((String) null))
        .isInstanceOf(NullPointerException.class);
  }
}
