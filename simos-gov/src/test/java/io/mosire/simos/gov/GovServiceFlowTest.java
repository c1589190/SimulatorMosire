package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.UnitId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link GovServiceFlow} 的 Z3b 逐值判据（设计书 §4.2/§8、z3b §5/§10.1-6）：
 *
 * <ul>
 *   <li><b>四条流量恒等式</b>：产出 == 有效劳动；消费 == min(产出, 需求)；失效 == 产出 − 消费；全部 ≥0；
 *   <li>需求 &gt; 供给 ⇒ 有效=承诺、消费=有效、失效 0；需求 0 + 供给 &gt;0 ⇒ 产出全失效；超编开方逐值；
 *   <li>{@link GovServiceFlow#of} 从<b>同一份</b> {@link GovEfficiency.Efficiency} 派生（不在第二处重算有效劳动）；
 *   <li>构造期负向：负数、恒等式不符、tick 为负、gov 为 null、{@code of} 的 efficiency 为 null。
 * </ul>
 *
 * <p>★ 进程内承载 {@code GovServiceFlowFeed}（同 tick 可读 / 换 tick {@code unavailable} / 重启即失）在 {@code
 * simos-app}，不属于本模块（由 app 侧测试覆盖）；本类只钉 gov 模块拥有的流量 record。
 */
class GovServiceFlowTest {

  private static final UnitId GOV = new UnitId("gov-1");
  private static final long TICK = 7L;
  private static final long QUOTA = 16_000L;

  /**
   * canonical 12 个劳动量（单位：毫）按 record 组件序：secCommitted, papCommitted, secEffective, papEffective,
   * secDemand, papDemand, secOutput, papOutput, secConsumed, papConsumed, secExpired, papExpired。
   */
  private static final long[] VALID_LABOR = {
    16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 0L, 0L
  };

  private static final String[] LABOR_FIELDS = {
    "securityCommittedLaborMilli",
    "paperworkCommittedLaborMilli",
    "securityEffectiveLaborMilli",
    "paperworkEffectiveLaborMilli",
    "securityDemandLaborMilli",
    "paperworkDemandLaborMilli",
    "securityServiceOutputMilli",
    "paperworkServiceOutputMilli",
    "securityConsumedByEfficiencyMilli",
    "paperworkConsumedByEfficiencyMilli",
    "securityExpiredUnusedMilli",
    "paperworkExpiredUnusedMilli"
  };

  @Test
  void ofDerivesOutputConsumptionAndExpiryFromOneEfficiencyResult() {
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            500L, 1_000L, 0L, 500L, 500L, 1_000L, 8_000L, 16_000L, 16_000L, 16_000L);

    GovServiceFlow flow = GovServiceFlow.of(GOV, TICK, 8_000L, 16_000L, efficiency);

    assertThat(flow.gov()).isEqualTo(GOV);
    assertThat(flow.tick()).isEqualTo(TICK);
    assertThat(flow.securityCommittedLaborMilli()).as("承诺 = 调用方给的桥输出").isEqualTo(8_000L);
    assertThat(flow.paperworkCommittedLaborMilli()).isEqualTo(16_000L);
    assertThat(flow.securityEffectiveLaborMilli()).as("产出 == 有效劳动（V1 1:1）").isEqualTo(8_000L);
    assertThat(flow.paperworkEffectiveLaborMilli()).isEqualTo(16_000L);
    assertThat(flow.securityDemandLaborMilli()).isEqualTo(16_000L);
    assertThat(flow.paperworkDemandLaborMilli()).isEqualTo(16_000L);
    assertThat(flow.securityServiceOutputMilli()).isEqualTo(8_000L);
    assertThat(flow.paperworkServiceOutputMilli()).isEqualTo(16_000L);
    assertThat(flow.securityConsumedByEfficiencyMilli()).as("min(8000, 16000)").isEqualTo(8_000L);
    assertThat(flow.paperworkConsumedByEfficiencyMilli()).isEqualTo(16_000L);
    assertThat(flow.securityExpiredUnusedMilli()).as("产出 − 消费").isZero();
    assertThat(flow.paperworkExpiredUnusedMilli()).isZero();
  }

  @Test
  void demandZeroWithPositiveSupplyExpiresEverything() {
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            1_000L, 1_000L, 0L, 1_000L, 1_000L, 1_000L, QUOTA, QUOTA, 0L, 0L);

    GovServiceFlow flow = GovServiceFlow.of(GOV, TICK, QUOTA, QUOTA, efficiency);

    assertThat(flow.securityServiceOutputMilli()).isEqualTo(QUOTA);
    assertThat(flow.securityConsumedByEfficiencyMilli()).as("需求 0 ⇒ 消费 0").isZero();
    assertThat(flow.securityExpiredUnusedMilli()).as("未用即失效：产出全额过期").isEqualTo(QUOTA);
    assertThat(flow.paperworkConsumedByEfficiencyMilli()).isZero();
    assertThat(flow.paperworkExpiredUnusedMilli()).isEqualTo(QUOTA);
  }

  @Test
  void supernumerarySquareRootOutputSplitsIntoConsumedAndExpired() {
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            formation(),
            Map.of(),
            new GovAdministrationPlan(
                QUOTA,
                QUOTA,
                GovAdministrationPlan.DEFAULT_POST_TIERS,
                1_000L,
                1_000L,
                1_000L,
                1_000L,
                1L),
            2L * QUOTA,
            QUOTA,
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            QUOTA);

    GovServiceFlow flow = GovServiceFlow.of(GOV, TICK, 2L * QUOTA, QUOTA, efficiency);

    assertThat(flow.securityEffectiveLaborMilli()).as("超编开方后有效劳动 = 2 个当量").isEqualTo(2L * QUOTA);
    assertThat(flow.securityDemandLaborMilli()).isEqualTo(QUOTA);
    assertThat(flow.securityServiceOutputMilli()).isEqualTo(2L * QUOTA);
    assertThat(flow.securityConsumedByEfficiencyMilli()).as("min(32000, 16000)").isEqualTo(QUOTA);
    assertThat(flow.securityExpiredUnusedMilli()).as("32000 − 16000").isEqualTo(QUOTA);
    assertThat(flow.paperworkConsumedByEfficiencyMilli()).isEqualTo(QUOTA);
    assertThat(flow.paperworkExpiredUnusedMilli()).isZero();
  }

  @Test
  void laborIdentityViolationsThrowNamingTheField() {
    long[] outputMismatch = VALID_LABOR.clone();
    outputMismatch[6] = 15_999L;
    assertThatThrownBy(() -> flow(outputMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityServiceOutputMilli");
    long[] paperOutputMismatch = VALID_LABOR.clone();
    paperOutputMismatch[7] = 15_999L;
    assertThatThrownBy(() -> flow(paperOutputMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkServiceOutputMilli");

    long[] consumedMismatch = VALID_LABOR.clone();
    consumedMismatch[8] = 15_999L;
    assertThatThrownBy(() -> flow(consumedMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityConsumedByEfficiencyMilli");
    long[] paperConsumedMismatch = VALID_LABOR.clone();
    paperConsumedMismatch[9] = 15_999L;
    assertThatThrownBy(() -> flow(paperConsumedMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkConsumedByEfficiencyMilli");

    long[] expiredMismatch = VALID_LABOR.clone();
    expiredMismatch[10] = 1L;
    assertThatThrownBy(() -> flow(expiredMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityExpiredUnusedMilli");
    long[] paperExpiredMismatch = VALID_LABOR.clone();
    paperExpiredMismatch[11] = 1L;
    assertThatThrownBy(() -> flow(paperExpiredMismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkExpiredUnusedMilli");
  }

  @Test
  void negativeLaborFieldsAndNegativeTickThrow() {
    for (int i = 0; i < VALID_LABOR.length; i++) {
      long[] values = VALID_LABOR.clone();
      values[i] = -1L;
      assertThatThrownBy(() -> flow(values))
          .as("第 %d 个劳动量为负必须具名拒", i)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(LABOR_FIELDS[i]);
    }

    assertThatThrownBy(
            () ->
                new GovServiceFlow(
                    GOV, -1L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L,
                    16_000L, 16_000L, 16_000L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick 必须 ≥ 0");
  }

  @Test
  void nullGovAndNullEfficiencyThrow() {
    assertThatThrownBy(
            () ->
                new GovServiceFlow(
                    null, TICK, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L, 16_000L,
                    16_000L, 16_000L, 16_000L, 0L, 0L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> GovServiceFlow.of(GOV, TICK, 16_000L, 16_000L, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void ofRejectsNegativeCommittedLaborThroughTheRecordGuard() {
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            1_000L, 1_000L, 0L, 1_000L, 1_000L, 1_000L, QUOTA, QUOTA, QUOTA, QUOTA);

    assertThatThrownBy(() -> GovServiceFlow.of(GOV, TICK, -1L, QUOTA, efficiency))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCommittedLaborMilli");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 按 canonical 劳动量序构造（恒等式由被测构造器自判）。 */
  private static GovServiceFlow flow(long[] values) {
    return new GovServiceFlow(
        GOV,
        TICK,
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6],
        values[7],
        values[8],
        values[9],
        values[10],
        values[11]);
  }

  private static GovernmentFormation formation() {
    return new GovernmentFormation(
        Map.of(),
        Map.of(),
        new OfficePolicy(0L, 0L, 0L, 0L, Map.of()),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        Map.of());
  }
}
