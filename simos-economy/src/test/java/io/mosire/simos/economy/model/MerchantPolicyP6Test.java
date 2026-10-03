package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P6 商人政策纯模型</b>：层级数值、城市折扣随距离衰减（BOSS radius2 d1 ⇒ 33‰）、盈利/亏损容量步进与 上下限、农村惩罚累积封顶、{@code
 * servesLane} 的通配/显式 lane 语义。
 *
 * <p>逐值对照探针 {@code ProbeEconomy.MerchantTier}/{@code transportPerMille}/ {@code
 * updateCitiesAndRuralMerchants}；不重跑探针。
 */
class MerchantPolicyP6Test {

  private static final HexCoord C = new HexCoord(0, 0);
  private static final HexCoord R0 = new HexCoord(1, 0);
  private static final HexCoord R1 = new HexCoord(0, 1);
  private static final HexCoord FAR = new HexCoord(2, 0);

  @Test
  void threeTiersMatchTheProbeNumbers() {
    assertThat(MerchantPolicy.MerchantTier.PORTER.districtUse()).as("脚夫城区当量").isEqualTo(1);
    assertThat(MerchantPolicy.MerchantTier.PORTER.transportDiscountPerMille())
        .as("脚夫城市折扣上限")
        .isEqualTo(10L);
    assertThat(MerchantPolicy.MerchantTier.SELF_EMPLOYED.districtUse()).as("个体户城区当量").isEqualTo(2);
    assertThat(MerchantPolicy.MerchantTier.SELF_EMPLOYED.transportDiscountPerMille())
        .as("个体户城市折扣上限")
        .isEqualTo(25L);
    assertThat(MerchantPolicy.MerchantTier.BOSS.districtUse()).as("老板城区当量").isEqualTo(4);
    assertThat(MerchantPolicy.MerchantTier.BOSS.transportDiscountPerMille())
        .as("老板城市折扣上限")
        .isEqualTo(50L);

    assertThat(
            new MerchantPolicy(MerchantPolicy.MerchantTier.PORTER, C, false, 10L, 1L)
                .upkeepPerRound())
        .as("脚夫每轮固定开销 = 1×100")
        .isEqualTo(100L);
    assertThat(
            new MerchantPolicy(MerchantPolicy.MerchantTier.BOSS, C, true, 20_000L, 4L)
                .upkeepPerRound())
        .as("老板每轮固定开销 = 4×100")
        .isEqualTo(400L);
  }

  @Test
  void cityDiscountDecaysWithDistanceAndRespectsRadius() {
    MerchantPolicy boss =
        new MerchantPolicy(MerchantPolicy.MerchantTier.BOSS, C, true, 20_000L, 2L);

    assertThat(boss.cityDiscountPerMille(0L)).as("d0：decay=1000‰ ⇒ 满 50").isEqualTo(50L);
    assertThat(boss.cityDiscountPerMille(1L))
        .as("BOSS radius2 d1：decay=⌊2000/3⌋=666 ⇒ 50×666‰=33（R-CITY 报告口径）")
        .isEqualTo(33L);
    assertThat(boss.cityDiscountPerMille(2L)).as("d2：decay=⌊1000/3⌋=333 ⇒ 16").isEqualTo(16L);
    assertThat(boss.cityDiscountPerMille(3L)).as("半径外 ⇒ 0").isZero();

    assertThat(
            new MerchantPolicy(MerchantPolicy.MerchantTier.PORTER, C, true, 10L, 2L)
                .cityDiscountPerMille(1L))
        .as("脚夫 d1：10×666‰ = 6")
        .isEqualTo(6L);
    assertThat(
            new MerchantPolicy(MerchantPolicy.MerchantTier.SELF_EMPLOYED, C, true, 10L, 2L)
                .cityDiscountPerMille(1L))
        .as("个体户 d1：25×666‰ = 16")
        .isEqualTo(16L);
    assertThat(
            new MerchantPolicy(MerchantPolicy.MerchantTier.BOSS, C, false, 10L, 2L)
                .cityDiscountPerMille(1L))
        .as("农村商人不吃城市折扣")
        .isZero();
  }

  @Test
  void cityCapacityMovesFivePerRoundWithCeilingAndFloor() {
    MerchantPolicy boss = new MerchantPolicy(MerchantPolicy.MerchantTier.BOSS, C, true, 10L, 2L);
    assertThat(boss.withProfit(1L).capacityPerRound()).as("盈利 +5").isEqualTo(15L);
    assertThat(boss.withProfit(0L).capacityPerRound()).as("不盈不亏不变").isEqualTo(10L);
    assertThat(boss.withProfit(-1L).capacityPerRound()).as("亏损 −5").isEqualTo(5L);

    MerchantPolicy atCeiling = boss.withCapacityPerRound(100_000L);
    assertThat(atCeiling.withProfit(999L).capacityPerRound())
        .as("运力上限 100000：盈利也不再增容")
        .isEqualTo(100_000L);
    assertThat(boss.withCapacityPerRound(5L).withProfit(-1L).capacityPerRound())
        .as("缩编下限 5：亏损不再缩")
        .isEqualTo(5L);
    assertThat(boss.withProfit(1L).withRoundCapacityReset().remainingCapacity())
        .as("轮初归位 = 用新的 capacityPerRound")
        .isEqualTo(15L);
  }

  @Test
  void ruralPenaltyAccumulatesTwoPerActiveRoundAndCapsAtHundred() {
    MerchantPolicy porter =
        new MerchantPolicy(
            MerchantPolicy.MerchantTier.PORTER, C, false, 10L, 10L, R0, C, 2L, 98L, 3L);

    MerchantPolicy afterOne = porter.withProfit(0L);
    assertThat(afterOne.ruralTradeCostPenaltyPerMille()).as("做了一轮贸易：98+2").isEqualTo(100L);
    assertThat(afterOne.withProfit(0L).ruralTradeCostPenaltyPerMille())
        .as("封顶 100，不再累积")
        .isEqualTo(100L);

    MerchantPolicy idle =
        new MerchantPolicy(
            MerchantPolicy.MerchantTier.PORTER, C, false, 10L, 10L, null, null, 2L, 5L, 0L);
    assertThat(idle.withProfit(0L).ruralTradeCostPenaltyPerMille())
        .as("本轮没承运（lastUnitsMoved=0）⇒ 惩罚不动")
        .isEqualTo(5L);
  }

  @Test
  void servesLaneHonorsWildcardRadiusAndExplicitLane() {
    MerchantPolicy wildcard =
        new MerchantPolicy(MerchantPolicy.MerchantTier.PORTER, C, true, 10L, 1L);
    assertThat(wildcard.servesLane(C, R0)).as("通配：两端都在半径 1 内").isTrue();
    assertThat(wildcard.servesLane(R0, R1)).as("通配：R1 到 C 的距离是 1、R0 也是 1 ⇒ 在半径内").isTrue();
    assertThat(wildcard.servesLane(C, FAR)).as("通配：目标超出半径 ⇒ 不服务").isFalse();

    MerchantPolicy explicit =
        new MerchantPolicy(
            MerchantPolicy.MerchantTier.PORTER, R0, false, 10L, 10L, R0, C, 0L, 0L, 0L);
    assertThat(explicit.servesLane(R0, C)).as("显式 lane 精确匹配，即使 radius=0").isTrue();
    assertThat(explicit.servesLane(C, R0)).as("显式 lane 是单向的（from/to 不对调）").isFalse();

    MerchantPolicy noRadiusNoLane =
        new MerchantPolicy(MerchantPolicy.MerchantTier.PORTER, C, true, 10L, 0L);
    assertThat(noRadiusNoLane.servesLane(C, R0)).as("radius ≤ 0 且无显式 lane ⇒ 不服务").isFalse();

    MerchantPolicy bossLane =
        new MerchantPolicy(MerchantPolicy.MerchantTier.BOSS, C, true, 100L, 2L);
    assertThat(bossLane.cityDiscountForLane(C, FAR))
        .as("折扣用两端里离 home 更远的那端（d=2 ⇒ 16）")
        .isEqualTo(16L);
  }
}
