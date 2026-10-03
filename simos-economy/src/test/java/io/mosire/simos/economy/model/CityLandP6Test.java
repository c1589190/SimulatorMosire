package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P6 城市承载/扩建与城区占地辐射纯模型</b>：把探针 {@code ProbeEconomy.CityState/updateCitiesAndRuralMerchants}
 * 的“压力 → 慢速扩建 → 城区比例”与 {@code cityBuiltAreaMu} 的辐射分配逐值钉住。
 *
 * <p>对拍参数取 R-CITY 报告 §2 的 19 格终局自检：{@code capacity=10→370}、{@code used=7549}、 {@code
 * expansions=18}、{@code builtAreaPerMille=360}（{@code arableC=2999 / arableR0=3019}）。
 */
class CityLandP6Test {

  /** 探针 {@code Hex.arableMu}（每个格 3100 亩）——辐射公式的分母。 */
  private static final long ARABLE_MU = 3_100L;

  /** 7HEX2 的 19 个格：中心 C + 内环 6 + 外环 12（顺序无关，只为算半径 4 的权重和）。 */
  private static final HexCoord CENTER = new HexCoord(0, 0);

  private static final List<HexCoord> SEVEN_HEX_CITY_CELLS =
      List.of(
          CENTER,
          new HexCoord(1, 0),
          new HexCoord(0, 1),
          new HexCoord(-1, 1),
          new HexCoord(-1, 0),
          new HexCoord(0, -1),
          new HexCoord(1, -1),
          new HexCoord(2, 0),
          new HexCoord(1, 1),
          new HexCoord(0, 2),
          new HexCoord(-1, 2),
          new HexCoord(-2, 2),
          new HexCoord(-2, 1),
          new HexCoord(-2, 0),
          new HexCoord(-1, -1),
          new HexCoord(0, -2),
          new HexCoord(1, -2),
          new HexCoord(2, -2),
          new HexCoord(2, -1));

  @Test
  void slowExpansionReachesCapacity370AndBuiltArea360After18Expansions() {
    CityLand city = new CityLand(10L, 7_549L, 0L, 0L, 0L, 0L, 4L, 0L, 0L);

    assertThat(city.advance().capacity()).as("压力不足 200 步时只进进度、不扩建").isEqualTo(10L);
    for (int i = 1; i < 200; i++) {
      city = city.advance();
    }
    assertThat(city.capacity()).as("第 199 步仍未触发扩建").isEqualTo(10L);
    assertThat(city.expansionProgress()).as("扩建进度 199").isEqualTo(199L);
    city = city.advance();
    assertThat(city.capacity()).as("第 200 步触发第一次扩建：10 + 20").isEqualTo(30L);
    assertThat(city.expansionCount()).as("第一次扩建").isEqualTo(1L);
    assertThat(city.builtAreaPerMille()).as("城区比例 +20‰").isEqualTo(20L);

    // 从第 201 步补满到 18 次扩建：3600 步 = 18 × 200。
    for (int i = 200; i < 18 * 200; i++) {
      city = city.advance();
    }
    assertThat(city.usedCapacity()).as("已占承载不被扩建改写").isEqualTo(7_549L);
    assertThat(city.capacity()).as("10 + 18×20 = 370（R-CITY §2）").isEqualTo(370L);
    assertThat(city.expansionCount()).as("18 次扩建").isEqualTo(18L);
    assertThat(city.expansionProgress()).as("每次扩建后进度清零").isZero();
    assertThat(city.builtAreaPerMille()).as("18×20‰ = 360‰").isEqualTo(360L);
  }

  @Test
  void zeroCapacityNeverExpandsSpontaneously() {
    CityLand uninitialized = new CityLand(0L, 7_549L, 199L, 0L, 0L, 0L, 4L, 0L, 0L);

    assertThat(uninitialized.advance()).as("capacity=0 = 尚未初始化，不凭空扩建").isEqualTo(uninitialized);
  }

  @Test
  void radiatedBuiltAreaMatchesTheFiftyFiveWeightNineteenCellSelfCheck() {
    // 半径 4 的 19 格等权自检：权重和 = 5×1（d0）+ 4×4（d1）+ 3×8（d2）+ 2×4（d3）+ 1×2（d4）= 55。
    // 距离用探针 ProbeEconomy.hexDistance 的曼哈顿口径（|Δq|+|Δr|）——报告自检参数 55 就是按它算的。
    long weightSum = 0L;
    for (HexCoord cell : SEVEN_HEX_CITY_CELLS) {
      weightSum += CityLand.builtAreaWeight(4L, probeHexDistance(CENTER, cell));
    }
    assertThat(weightSum).as("19 格等权的半径 4 权重和（R-CITY 自检参数）").isEqualTo(55L);

    CityLand city = new CityLand(370L, 7_549L, 0L, 18L, 360L, 0L, 4L, 0L, 7_534_716L);
    assertThat(city.builtAreaTotalMu(ARABLE_MU)).as("3100 × 360‰ = 1116").isEqualTo(1_116L);
    assertThat(city.radiatedBuiltAreaMu(ARABLE_MU, 0L, weightSum))
        .as("C 本格分摊：1116×5/55 = 101")
        .isEqualTo(101L);
    assertThat(city.radiatedBuiltAreaMu(ARABLE_MU, 1L, weightSum))
        .as("R0（距离 1）分摊：1116×4/55 = 81")
        .isEqualTo(81L);
    assertThat(CityLand.radiatedBuiltAreaMu(1_116L, 4L, 0L, weightSum))
        .as("静态重载（总占地直接给）逐值相同")
        .isEqualTo(101L);
    assertThat(CityLand.radiatedBuiltAreaMu(1_116L, 4L, 5L, weightSum)).as("距离超出半径 ⇒ 0").isZero();

    CityLand centerShare = city.withBuiltAreaMu(city.radiatedBuiltAreaMu(ARABLE_MU, 0L, weightSum));
    CityLand r0Share = city.withBuiltAreaMu(city.radiatedBuiltAreaMu(ARABLE_MU, 1L, weightSum));
    assertThat(centerShare.availableArableMu(ARABLE_MU))
        .as("arableC = 3100 − 101 = 2999（R-CITY §2）")
        .isEqualTo(2_999L);
    assertThat(r0Share.availableArableMu(ARABLE_MU))
        .as("arableR0 = 3100 − 81 = 3019（R-CITY §2）")
        .isEqualTo(3_019L);
  }

  @Test
  void availableArableClampsAtZeroAndRejectsNegativeArable() {
    CityLand city = new CityLand(370L, 7_549L, 0L, 18L, 360L, 101L, 4L, 0L, 0L);

    assertThat(city.availableArableMu(100L)).as("城区占地超过可耕地 ⇒ 归零（不出现负可耕地）").isZero();
    assertThat(city.availableArableMu(101L)).as("恰好扣完 ⇒ 0").isZero();
    assertThat(city.availableArableMu(102L)).as("只扣本格分摊的 101").isEqualTo(1L);
    assertThatThrownBy(() -> city.availableArableMu(-1L))
        .as("负可耕地是坏数据，不得静默归零")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("availableArableMu");
    assertThatThrownBy(() -> new CityLand(0L, 0L, 0L, 0L, 0L, -1L, 0L, 0L, 0L))
        .as("负 builtAreaMu 构造期拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("builtAreaMu");
  }

  /** 探针 {@code ProbeEconomy.hexDistance} 的只读口径（{@code |Δq|+|Δr|}，不是 {@link HexCoord#distanceTo}）。 */
  private static long probeHexDistance(HexCoord left, HexCoord right) {
    return Math.abs((long) left.q() - right.q()) + Math.abs((long) left.r() - right.r());
  }
}
