package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.provisioning.SocialProvisioning;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link GovEfficiency} 的 Z2/Z3b 新公式逐值判据（设计书 §3、z2 台账 §4、z3b 台账 §10.2）：
 *
 * <ul>
 *   <li><b>黄金手算例 1</b>（计划 200/100 人当量、供给 220/110 人当量、k=1、四修正 1000‰）⇒ 满足率 1020/1030、总效率 1050；
 *   <li><b>超编开方</b>在“岗位当量”上做、{@code k} 可调（k=1 ⇒ 1020、k=4 ⇒ 1040）；
 *   <li><b>无需求</b> ⇒ 该维满足率 1000‰；<b>无供给</b> ⇒ 该维最终效率 0（即使该维需求为 0），总效率 0；
 *   <li><b>全不封顶</b>：满足率/维效率/总效率都允 >1000，无任何截断（用户 2026-10-23「都不封顶」）；
 *   <li><b>书写序取整</b>：需求/供给修正按左结合两次 {@code floorDiv}，不合并分母（z2 §4 例 6）；
 *   <li><b>契约故障</b>：负供给/负动态修正/非正定额/乘法溢出 ⇒ 具名 ERROR + {@code IllegalStateException}（fail-closed）。
 * </ul>
 *
 * <p>★ 旧口径的 {@code min} 合成、超编加成（+10%⇒50、+20%⇒66）与 1000/1100/100 上限全部随 C3 删除；本类不再保留旧桥 helper，全部走
 * canonical 10 参 {@code of(...)} 或显式构造 {@code Efficiency}。
 */
class GovEfficiencyTest {

  private static final HexCoord H1 = new HexCoord(1, 1);

  /** C8 标准劳动系数（岗位定额）：本例固定 16,000 毫小时/tick（z2 §4 冻结手算值）。 */
  private static final long QUOTA = 16_000L;

  private static final GovernmentFormation FORMATION = formation();

  // ── §3 公式：黄金手算 ──────────────────────────────────────────────────────────────

  @Test
  void goldenExampleOneFromZ2LedgerMatchesFieldByField() {
    GovAdministrationPlan plan = neutralPlan(3_200_000L, 1_600_000L);

    GovEfficiency.Efficiency efficiency = compute(plan, 3_520_000L, 1_760_000L);

    assertThat(efficiency)
        .as("治安 20 个超额当量 ⇒ ⌊√20⌋=4 ⇒ 3264000/3200000=1020‰；公文 ⌊√10⌋=3 ⇒ 1030‰；总 1050‰")
        .isEqualTo(
            new GovEfficiency.Efficiency(
                1_020L,
                1_030L,
                0L,
                1_050L,
                1_020L,
                1_030L,
                3_264_000L,
                1_648_000L,
                3_200_000L,
                1_600_000L));
  }

  @Test
  void supernumerarySquareRootScalesWithK() {
    GovAdministrationPlan k1 = plan(1_600_000L, 0L, 1_000L, 1_000L, 1_000L, 1_000L, 1L);
    GovAdministrationPlan k4 = plan(1_600_000L, 0L, 1_000L, 1_000L, 1_000L, 1_000L, 4L);

    assertThat(compute(k1, 1_680_000L, QUOTA).securityCoveragePerMille())
        .as("k=1：超额 5 个当量 ⇒ ⌊√5⌋=2 ⇒ 1632000/1600000=1020‰")
        .isEqualTo(1_020L);
    assertThat(compute(k4, 1_680_000L, QUOTA).securityCoveragePerMille())
        .as("k=4：超额 20 个当量 ⇒ ⌊√20⌋=4 ⇒ 1664000/1600000=1040‰")
        .isEqualTo(1_040L);
  }

  @Test
  void supplyExactlyEqualToDemandGivesFullCoverageAndNoBonus() {
    GovEfficiency.Efficiency efficiency = compute(neutralPlan(QUOTA, QUOTA), QUOTA, QUOTA);

    assertThat(efficiency)
        .as("满编（供给 == 需求）⇒ 两维满足率/维效率 1000‰、总 1000‰、legacy bonus 恒 0")
        .isEqualTo(
            new GovEfficiency.Efficiency(
                1_000L, 1_000L, 0L, 1_000L, 1_000L, 1_000L, QUOTA, QUOTA, QUOTA, QUOTA));
  }

  @Test
  void oneSidedUnderstaffingIsTheProductOfBothDimensionEfficiencies() {
    // 治安 150/200 人当量 ⇒ 满足率 750‰；公文满编 1000‰；总效率 = 750×1000/1000 = 750‰（不是 min 合成，而是乘积；
    // 本例两值同值，另加 uncapped 用例证明乘积口径）。
    GovEfficiency.Efficiency efficiency =
        compute(neutralPlan(3_200_000L, 1_600_000L), 2_400_000L, 1_600_000L);

    assertThat(efficiency)
        .isEqualTo(
            new GovEfficiency.Efficiency(
                750L,
                1_000L,
                0L,
                750L,
                750L,
                1_000L,
                2_400_000L,
                1_600_000L,
                3_200_000L,
                1_600_000L));
  }

  @Test
  void zeroDemandIsFullSatisfactionWhenSupplyIsPositive() {
    GovEfficiency.Efficiency efficiency = compute(neutralPlan(0L, 0L), QUOTA, QUOTA);

    assertThat(efficiency)
        .as("两维需求 0、供给各 1 个当量 ⇒ 满足率/维效率各 1000‰、总 1000‰")
        .isEqualTo(
            new GovEfficiency.Efficiency(
                1_000L, 1_000L, 0L, 1_000L, 1_000L, 1_000L, QUOTA, QUOTA, 0L, 0L));
  }

  @Test
  void zeroSupplyForcesZeroEfficiencyEvenWhenThatDimensionHasNoDemand() {
    // 治安：计划 0（需求 0）但供给 0；公文：正常满编。形式满足率治安=1000‰，但最终效率必须被压回 0。
    GovEfficiency.Efficiency efficiency = compute(neutralPlan(0L, 1_600_000L), 0L, 1_600_000L);

    assertThat(efficiency.securityCoveragePerMille()).as("需求 0 ⇒ 形式满足率 1000‰").isEqualTo(1_000L);
    assertThat(efficiency.securityEfficiencyPerMille()).as("供给 0 ⇒ 该维效率压回 0").isZero();
    assertThat(efficiency.paperworkCoveragePerMille()).isEqualTo(1_000L);
    assertThat(efficiency.paperworkEfficiencyPerMille()).isEqualTo(1_000L);
    assertThat(efficiency.efficiencyPerMille()).as("任一维效率 0 ⇒ 总效率 0").isZero();
    assertThat(GovEfficiency.anySupplyZero(0L, 1_600_000L)).isTrue();
    assertThat(GovEfficiency.anySupplyZero(1L, 1L)).isFalse();
  }

  @Test
  void bothDimensionsWithoutSupplyAreZero() {
    GovEfficiency.Efficiency efficiency = compute(neutralPlan(1_600_000L, 1_600_000L), 0L, 0L);

    assertThat(efficiency)
        .as("两维供给 0 ⇒ 满足率/维效率/总全 0，需求劳动原样保留")
        .isEqualTo(
            new GovEfficiency.Efficiency(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1_600_000L, 1_600_000L));
    assertThat(GovEfficiency.anySupplyZero(0L, 0L)).isTrue();
  }

  @Test
  void uncappedModifiersCanProduceEfficiencyFarAboveOneThousand() {
    // z2 §4 例 4：治安满足率 2000‰ × 供给静态 2000‰ × 动态 2000‰ ⇒ 8000‰；公文 1000‰ × 1000‰ × 3000‰ ⇒
    // 3000‰；总 24000‰（无任何钳制）。
    GovEfficiency.Efficiency efficiency =
        compute(
            plan(QUOTA, QUOTA, 2_000L, 1_000L, 1_000L, 1_000L, 1L),
            2L * QUOTA,
            QUOTA,
            2_000L,
            3_000L,
            1_000L,
            1_000L);

    assertThat(efficiency)
        .isEqualTo(
            new GovEfficiency.Efficiency(
                2_000L, 1_000L, 0L, 24_000L, 8_000L, 3_000L, 2L * QUOTA, QUOTA, QUOTA, QUOTA));
  }

  @Test
  void modifiersUseLeftToRightFlooringExactlyAsTheFrozenFormula() {
    // z2 §4 例 6：P=3、需求静态 900‰、需求动态 900‰ ⇒ floor(floor(3×900/1000)×900/1000)=1（合并分母会得 2）。
    GovEfficiency.Efficiency efficiency =
        compute(
            plan(3L, 0L, 1_000L, 1_000L, 900L, 900L, 1L), 1L, QUOTA, 1_000L, 1_000L, 900L, 900L);

    assertThat(efficiency.securityDemandLaborMilli())
        .as("左结合两次 floorDiv ⇒ 需求劳动 = 1；若合并成 ×900×900/1,000,000 会得 2")
        .isEqualTo(1L);
    assertThat(efficiency.securityCoveragePerMille())
        .as("供给 1 ≤ 需求 1 ⇒ 满足率 1000‰（合并分母的错误口径会是 500‰）")
        .isEqualTo(1_000L);
    assertThat(efficiency.efficiencyPerMille()).isEqualTo(1_000L);
  }

  @Test
  void suggestedDemandTableIsAdvisoryAndDoesNotChangeTheFormula() {
    GovAdministrationPlan plan = neutralPlan(3_200_000L, 1_600_000L);

    GovEfficiency.Efficiency withoutSuggestions = compute(plan, 3_520_000L, 1_760_000L);
    GovEfficiency.Efficiency withSuggestions =
        GovEfficiency.of(
            FORMATION,
            demand(999L, 888L),
            plan,
            3_520_000L,
            1_760_000L,
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            QUOTA);

    assertThat(withSuggestions)
        .as("建议值只进 DEBUG 汇总；公式只用计划 P_d（z2 §3：公式永远用计划值）")
        .isEqualTo(withoutSuggestions);
  }

  // ── 契约故障 ───────────────────────────────────────────────────────────────────────

  @Test
  void negativeInputsAndNonPositiveQuotaAreNamedContractFailures() {
    GovAdministrationPlan plan = neutralPlan(QUOTA, QUOTA);

    assertThatThrownBy(() -> compute(plan, -1L, QUOTA))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gov 行政效率公式契约故障")
        .hasMessageContaining("negative-securitySupplyLaborMilli");
    assertThatThrownBy(() -> compute(plan, QUOTA, -1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative-paperworkSupplyLaborMilli");
    assertThatThrownBy(() -> compute(plan, QUOTA, QUOTA, -1L, 1_000L, 1_000L, 1_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative-securitySupplyDynamicModifierPerMille");
    assertThatThrownBy(() -> compute(plan, QUOTA, QUOTA, 1_000L, -1L, 1_000L, 1_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative-paperworkSupplyDynamicModifierPerMille");
    assertThatThrownBy(() -> compute(plan, QUOTA, QUOTA, 1_000L, 1_000L, -1L, 1_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative-securityDemandDynamicModifierPerMille");
    assertThatThrownBy(() -> compute(plan, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, -1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative-paperworkDemandDynamicModifierPerMille");
    assertThatThrownBy(
            () ->
                GovEfficiency.of(
                    FORMATION, Map.of(), plan, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, 1_000L, 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("standard-labor-coefficient-non-positive");
  }

  @Test
  void arithmeticOverflowInDemandSupplyAndTotalProductFailsClosed() {
    // ① 需求修正乘法溢出：P=Long.MAX_VALUE × 1000‰。
    assertThatThrownBy(
            () ->
                compute(plan(Long.MAX_VALUE, 0L, 1_000L, 1_000L, 1_000L, 1_000L, 1L), QUOTA, QUOTA))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("arithmetic-overflow");

    // ② 供给修正乘法溢出：满足率 1000‰ × 静态 Long.MAX_VALUE。
    assertThatThrownBy(
            () ->
                compute(
                    neutralPlan(QUOTA, QUOTA),
                    QUOTA,
                    QUOTA,
                    Long.MAX_VALUE,
                    1_000L,
                    1_000L,
                    1_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("arithmetic-overflow");

    // ③ 总效率两维相乘溢出：两维效率各 ~4×10^9（不封顶合法值）⇒ 乘积超过 Long.MAX_VALUE。
    assertThatThrownBy(
            () ->
                compute(
                    neutralPlan(QUOTA, QUOTA),
                    QUOTA,
                    QUOTA,
                    4_000_001_000L,
                    4_000_000_000L,
                    1_000L,
                    1_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("arithmetic-overflow");
  }

  @Test
  void integerSqrtIsExactAtBoundaries() {
    assertThat(GovEfficiency.integerSqrt(0L)).isZero();
    assertThat(GovEfficiency.integerSqrt(1L)).isEqualTo(1L);
    assertThat(GovEfficiency.integerSqrt(3L)).isEqualTo(1L);
    assertThat(GovEfficiency.integerSqrt(4L)).isEqualTo(2L);
    assertThat(GovEfficiency.integerSqrt(Long.MAX_VALUE))
        .as("⌊√Long.MAX_VALUE⌋ = 3037000499（3037000500² 已越界）")
        .isEqualTo(3_037_000_499L);
    assertThatThrownBy(() -> GovEfficiency.integerSqrt(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须 ≥ 0");
  }

  @Test
  void nullFormationDemandOrPlanAreRejected() {
    GovAdministrationPlan plan = neutralPlan(QUOTA, QUOTA);

    assertThatThrownBy(
            () ->
                GovEfficiency.of(
                    null, Map.of(), plan, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, 1_000L, QUOTA))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("governmentFormation 不得为 null");
    assertThatThrownBy(
            () ->
                GovEfficiency.of(
                    FORMATION, null, plan, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, 1_000L, QUOTA))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("demand 不得为 null");
    assertThatThrownBy(
            () ->
                GovEfficiency.of(
                    FORMATION, Map.of(), null, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, 1_000L, QUOTA))
        .isInstanceOf(NullPointerException.class);

    Map<HexCoord, GovDemand.HexDemand> malformed = new LinkedHashMap<>();
    malformed.put(H1, null);
    assertThatThrownBy(
            () ->
                GovEfficiency.of(
                    FORMATION, malformed, plan, QUOTA, QUOTA, 1_000L, 1_000L, 1_000L, 1_000L,
                    QUOTA))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("demand 的键与值都不得为 null");
  }

  // ── Efficiency 读数形状（C3 全不封顶；旧构造器兼容） ───────────────────────────────

  @Test
  void efficiencyRecordAcceptsValuesAboveEveryOldCap() {
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            1_001L, 1_002L, 103L, 1_000_000L, 1_101L, 1_202L, 9_999L, 10_001L, 10_002L, 10_003L);

    assertThat(efficiency.securityCoveragePerMille()).isEqualTo(1_001L);
    assertThat(efficiency.paperworkCoveragePerMille()).isEqualTo(1_002L);
    assertThat(efficiency.bonusPerMille()).as("legacy 字段保留但只判 ≥0（C3 拆了 0..100 上限）").isEqualTo(103L);
    assertThat(efficiency.efficiencyPerMille()).isEqualTo(1_000_000L);
    assertThat(efficiency.securityEfficiencyPerMille()).isEqualTo(1_101L);
    assertThat(efficiency.paperworkEfficiencyPerMille()).isEqualTo(1_202L);
    assertThat(efficiency.securityEffectiveLaborMilli()).isEqualTo(9_999L);
    assertThat(efficiency.paperworkEffectiveLaborMilli()).isEqualTo(10_001L);
    assertThat(efficiency.securityDemandLaborMilli()).isEqualTo(10_002L);
    assertThat(efficiency.paperworkDemandLaborMilli()).isEqualTo(10_003L);
  }

  @Test
  void efficiencyRecordRejectsNegativeFields() {
    long[] base = {1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L};
    String[] names = {
      "securityCoveragePerMille",
      "paperworkCoveragePerMille",
      "bonusPerMille",
      "efficiencyPerMille",
      "securityEfficiencyPerMille",
      "paperworkEfficiencyPerMille",
      "securityEffectiveLaborMilli",
      "paperworkEffectiveLaborMilli",
      "securityDemandLaborMilli",
      "paperworkDemandLaborMilli"
    };
    for (int i = 0; i < base.length; i++) {
      long[] values = base.clone();
      values[i] = -1L;
      assertThatThrownBy(
              () ->
                  new GovEfficiency.Efficiency(
                      values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                      values[7], values[8], values[9]))
          .as("第 %d 个字段为负必须具名拒", i)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(names[i]);
    }
  }

  @Test
  void legacyConstructorsZeroFillTheZ3bFields() {
    GovEfficiency.Efficiency four = new GovEfficiency.Efficiency(1_000L, 1_000L, 0L, 1_000L);

    assertThat(four.securityEfficiencyPerMille()).isZero();
    assertThat(four.paperworkEfficiencyPerMille()).isZero();
    assertThat(four.securityEffectiveLaborMilli()).isZero();
    assertThat(four.paperworkEffectiveLaborMilli()).isZero();
    assertThat(four.securityDemandLaborMilli()).isZero();
    assertThat(four.paperworkDemandLaborMilli()).isZero();

    GovEfficiency.Efficiency six =
        new GovEfficiency.Efficiency(1_000L, 1_000L, 0L, 1_000L, 1_100L, 1_200L);

    assertThat(six.securityEfficiencyPerMille()).isEqualTo(1_100L);
    assertThat(six.paperworkEfficiencyPerMille()).isEqualTo(1_200L);
    assertThat(six.securityEffectiveLaborMilli()).isZero();
    assertThat(six.paperworkEffectiveLaborMilli()).isZero();
    assertThat(six.securityDemandLaborMilli()).isZero();
    assertThat(six.paperworkDemandLaborMilli()).isZero();
  }

  @Test
  void c8StandardLaborCoefficientUsedByTheGoldenExampleIsSixteenThousand() {
    assertThat(SocialProvisioning.defaults().standardLaborMilliHoursPerTick())
        .as("C8 唯一权威的标准劳动系数 = 16,000 毫小时/tick（z2 §4 手算例的定额来源）")
        .isEqualTo(QUOTA);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static GovEfficiency.Efficiency compute(
      GovAdministrationPlan plan, long securitySupplyLaborMilli, long paperworkSupplyLaborMilli) {
    return compute(
        plan, securitySupplyLaborMilli, paperworkSupplyLaborMilli, 1_000L, 1_000L, 1_000L, 1_000L);
  }

  private static GovEfficiency.Efficiency compute(
      GovAdministrationPlan plan,
      long securitySupplyLaborMilli,
      long paperworkSupplyLaborMilli,
      long securitySupplyDynamicModifierPerMille,
      long paperworkSupplyDynamicModifierPerMille,
      long securityDemandDynamicModifierPerMille,
      long paperworkDemandDynamicModifierPerMille) {
    return GovEfficiency.of(
        FORMATION,
        Map.of(),
        plan,
        securitySupplyLaborMilli,
        paperworkSupplyLaborMilli,
        securitySupplyDynamicModifierPerMille,
        paperworkSupplyDynamicModifierPerMille,
        securityDemandDynamicModifierPerMille,
        paperworkDemandDynamicModifierPerMille,
        QUOTA);
  }

  /** 四修正 1000‰、k=1 的计划（只给两维计划量）。 */
  private static GovAdministrationPlan neutralPlan(long securityPlanned, long paperworkPlanned) {
    return plan(securityPlanned, paperworkPlanned, 1_000L, 1_000L, 1_000L, 1_000L, 1L);
  }

  private static GovAdministrationPlan plan(
      long securityPlanned,
      long paperworkPlanned,
      long securitySupplyStaticModifierPerMille,
      long paperworkSupplyStaticModifierPerMille,
      long securityDemandStaticModifierPerMille,
      long paperworkDemandStaticModifierPerMille,
      long supernumerarySqrtCoefficient) {
    return new GovAdministrationPlan(
        securityPlanned,
        paperworkPlanned,
        GovAdministrationPlan.DEFAULT_POST_TIERS,
        securitySupplyStaticModifierPerMille,
        paperworkSupplyStaticModifierPerMille,
        securityDemandStaticModifierPerMille,
        paperworkDemandStaticModifierPerMille,
        supernumerarySqrtCoefficient);
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

  private static Map<HexCoord, GovDemand.HexDemand> demand(long security, long paperwork) {
    Map<HexCoord, GovDemand.HexDemand> demand = new LinkedHashMap<>();
    demand.put(H1, new GovDemand.HexDemand(security, paperwork));
    return demand;
  }
}
