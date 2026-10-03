package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P4 运输费率纯模型</b>：把探针 {@code ProbeEconomy.transportPerMille} 的“基础 + 每 hex + 辐射 − 道路折扣”逐值钉住。
 *
 * <p>对照：{@code TransportTeamProbeTest} 的 T1 场景（{@code base=50‰ / perHex=50‰}，2 hex、30 粮、粮价 100）——
 * 费率 150‰、运费 450。本类断言的是**同一公式的显式构造**，不重跑探针。
 */
class TransportTariffP4Test {

  /** T1 的探针口径：粮价（钱/粮），用来把“货款价值 × 费率”换成运费。 */
  private static final long T1_GRAIN_PRICE = 100L;

  @Test
  void perMilleImplementsBasePerHexRadialAndRoadFormula() {
    TransportTariff tariff = new TransportTariff(5L, 5L);

    assertThat(tariff.perMille(2L, 3L, 1L, 0L, 0L)).as("5 + 5×2 + 20×3 − 50×1 = 25").isEqualTo(25L);
    assertThat(tariff.perMille(0L, 0L, 0L, 0L, 0L)).as("裸基础费").isEqualTo(5L);
    assertThat(tariff.perMille(1L, 0L, 0L, 0L, 0L)).as("基础 + 每 hex").isEqualTo(10L);
    assertThat(tariff.perMille(0L, 1L, 0L, 0L, 0L)).as("辐射 20‰/hex").isEqualTo(25L);
    assertThat(tariff.perMille(0L, 0L, 1L, 0L, 0L)).as("道路 50‰/级").isZero();
    assertThat(tariff.perMille(3L, 0L, 0L, 100L, 0L)).as("城市折扣吃满时下限 0（不出现负费率）").isZero();
    assertThat(tariff.perMille(0L, 0L, 0L, 0L, 17L)).as("农村惩罚是加项").isEqualTo(22L);
  }

  @Test
  void probeDefaultsIsTheSevenHexCityParameterPair() {
    TransportTariff defaults = TransportTariff.probeDefaults();

    assertThat(defaults.basePerMille()).as("7HEX2 探针 baseTransportPerMille").isEqualTo(5L);
    assertThat(defaults.perHexPerMille()).as("7HEX2 探针 perHexTransportPerMille").isEqualTo(5L);
    assertThat(TransportTariff.RADIAL_COST_PER_HEX_PER_MILLE).as("辐射常量").isEqualTo(20L);
    assertThat(TransportTariff.ROAD_DISCOUNT_PER_LEVEL_PER_MILLE).as("道路折扣常量").isEqualTo(50L);
  }

  @Test
  void negativeInputsAreRejectedByName() {
    assertThatThrownBy(() -> new TransportTariff(-1L, 5L))
        .as("负 base 不得静默接受")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("basePerMille");
    assertThatThrownBy(() -> new TransportTariff(5L, -1L))
        .as("负 perHex 不得静默接受")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("perHexPerMille");

    TransportTariff tariff = new TransportTariff(5L, 5L);
    assertThatThrownBy(() -> tariff.perMille(-1L, 0L, 0L, 0L, 0L))
        .as("负距离不得静默取绝对/归零")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distanceHex");
    assertThatThrownBy(() -> tariff.perMille(0L, -1L, 0L, 0L, 0L))
        .as("负辐射距离不得静默归零")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radialDistanceHex");
    assertThatThrownBy(() -> tariff.perMille(0L, 0L, -1L, 0L, 0L))
        .as("负道路等级不得静默归零")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roadBottleneckLevel");
  }

  @Test
  void t1CarrierLaneReproducesTheProbeFeeOf450() {
    // T1：H0→H2 距离 2；探针参数 base=50/hex=50；30 粮、粮价 100。
    TransportTariff t1 = new TransportTariff(50L, 50L);
    long ratePerMille = t1.perMille(2L, 0L, 0L, 0L, 0L);
    assertThat(ratePerMille).as("50 + 50×2 = 150‰").isEqualTo(150L);

    long goodsValue = 30L * T1_GRAIN_PRICE;
    long fee = goodsValue * ratePerMille / 1000L;
    assertThat(goodsValue).as("30 粮 × 100 钱/粮").isEqualTo(3000L);
    assertThat(fee)
        .as("与 TransportTeamProbeTest 的 lastFeeEarned=450 同源：3000 × 150‰")
        .isEqualTo(450L);
  }
}
