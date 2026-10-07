package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.unit.UnitId;
import org.junit.jupiter.api.Test;

/**
 * {@link GovEfficiencyModifier} 的构造期判据（Z3b §3/§10.1-5）：
 *
 * <ul>
 *   <li>中性 = 四个维度各 1000‰；
 *   <li><b>全不封顶</b>：0 与任意大值都合法（用户 2026-10-23「都不封顶」）；
 *   <li>fail-closed：gov 为 null、四值任一为负、source/reason 空白 ⇒ 具名拒。
 * </ul>
 *
 * <p>★ 进程内注入/消费/清空（未知 GOV 拒、多日只影响首日、{@code !govActive} 清空）在 {@code simos-app} 参与者， 不属于本模块（由 app
 * 侧测试覆盖）。
 */
class GovEfficiencyModifierTest {

  private static final UnitId GOV = new UnitId("gov-1");

  @Test
  void neutralFillsAllFourDimensionsWithOneThousand() {
    GovEfficiencyModifier neutral = GovEfficiencyModifier.neutral(GOV, "weather-bridge", "neutral");

    assertThat(neutral.gov()).isEqualTo(GOV);
    assertThat(neutral.securitySupplyPerMille()).isEqualTo(1_000L);
    assertThat(neutral.paperworkSupplyPerMille()).isEqualTo(1_000L);
    assertThat(neutral.securityDemandPerMille()).isEqualTo(1_000L);
    assertThat(neutral.paperworkDemandPerMille()).isEqualTo(1_000L);
    assertThat(neutral.source()).isEqualTo("weather-bridge");
    assertThat(neutral.reason()).isEqualTo("neutral");
  }

  @Test
  void zeroAndUncappedValuesAreAccepted() {
    GovEfficiencyModifier modifier =
        new GovEfficiencyModifier(
            GOV, 0L, 2_000L, 3_000L, Long.MAX_VALUE, "weather-bridge", "extreme");

    assertThat(modifier.securitySupplyPerMille()).isZero();
    assertThat(modifier.paperworkSupplyPerMille()).isEqualTo(2_000L);
    assertThat(modifier.securityDemandPerMille()).isEqualTo(3_000L);
    assertThat(modifier.paperworkDemandPerMille()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void negativeDimensionsAndBlankAuditFieldsThrow() {
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, -1L, 1_000L, 1_000L, 1_000L, "src", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securitySupplyPerMille");
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, 1_000L, -1L, 1_000L, 1_000L, "src", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkSupplyPerMille");
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, 1_000L, 1_000L, -1L, 1_000L, "src", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityDemandPerMille");
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, 1_000L, 1_000L, 1_000L, -1L, "src", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkDemandPerMille");

    assertThatThrownBy(
            () -> new GovEfficiencyModifier(null, 1_000L, 1_000L, 1_000L, 1_000L, "src", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gov 不得为 null");
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, 1_000L, 1_000L, 1_000L, 1_000L, " ", "why"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source 不得为空白");
    assertThatThrownBy(
            () -> new GovEfficiencyModifier(GOV, 1_000L, 1_000L, 1_000L, 1_000L, "src", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason 不得为空白");
  }

  @Test
  void neutralMakesDefensiveCopyOfNothingButKeepsRecordValueSemantics() {
    GovEfficiencyModifier first = GovEfficiencyModifier.neutral(GOV, "src", "why");
    GovEfficiencyModifier second = GovEfficiencyModifier.neutral(GOV, "src", "why");

    assertThat(first).as("同值 record 逐字段相等").isEqualTo(second);
    assertThat(first).hasSameHashCodeAs(second);
  }
}
