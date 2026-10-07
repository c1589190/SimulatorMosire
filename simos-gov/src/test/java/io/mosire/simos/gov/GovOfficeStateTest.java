package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.unit.UnitId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link GovOfficeState} 的构造期不变量（阶段 10a + Z2 C3）：
 *
 * <ul>
 *   <li>键值非 null、数值非负；<b>六个 per-mille 字段全部没有上限</b>（旧 1000/1000/1100/100 上界按 C3 拆除）；
 *   <li>两维最终效率 >1000 必须被接受（用户 2026-10-23「都不封顶」），旧边界 1001/1101/101 现在是合法读数；
 *   <li>六表保序不可变；{@code empty()} 的“尚无读数”语义（0 不是“需求为零 ⇒ 全覆盖”）。
 * </ul>
 */
class GovOfficeStateTest {

  private static final UnitId U1 = new UnitId("gov-1");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  @Test
  void emptyFactoriesHaveEmptyTablesAndZeroPerMille() {
    GovOfficeState empty = GovOfficeState.empty(U1, 42L);

    assertThat(empty.unitId()).isEqualTo(U1);
    assertThat(empty.tick()).isEqualTo(42L);
    assertThat(empty.lastAssessedGoods()).isEmpty();
    assertThat(empty.lastPaidGoods()).isEmpty();
    assertThat(empty.lastShortfallGoods()).isEmpty();
    assertThat(empty.lastAssessedMoney()).isEmpty();
    assertThat(empty.lastPaidMoney()).isEmpty();
    assertThat(empty.lastShortfallMoney()).isEmpty();
    assertThat(empty.securityCoveragePerMille()).as("0 = 尚无读数，不是『需求为零 ⇒ 全覆盖』").isZero();
    assertThat(empty.paperworkCoveragePerMille()).isZero();
    assertThat(empty.efficiencyPerMille()).isZero();
    assertThat(empty.bonusPerMille()).isZero();
    assertThat(empty.securityEfficiencyPerMille()).isZero();
    assertThat(empty.paperworkEfficiencyPerMille()).isZero();
  }

  @Test
  void unitIdNullAndNegativeTickThrow() {
    assertThatThrownBy(
            () ->
                new GovOfficeState(
                    null, 0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0,
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unitId 不得为 null");

    assertThatThrownBy(
            () ->
                new GovOfficeState(
                    U1, -1L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0,
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick 必须 ≥ 0");
  }

  @Test
  void sixTablesMustBeNonNull() {
    assertThatThrownBy(
            () ->
                new GovOfficeState(
                    U1, 0L, null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lastAssessedGoods 不得为 null");
    assertThatThrownBy(
            () ->
                new GovOfficeState(
                    U1, 0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lastShortfallMoney 不得为 null");
  }

  @Test
  void tableNullKeysOrValuesOrNegativeValuesThrow() {
    Map<CommodityId, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(GRAIN, null);
    assertThatThrownBy(() -> oldOffice(Map.of(), Map.of(), nullValue, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键与值都不得为 null");

    assertThatThrownBy(() -> oldOffice(Map.of(), Map.of(), Map.of(GRAIN, -1L), 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("值必须 ≥ 0")
        .hasMessageContaining("grain=-1");

    Map<CurrencyId, Long> negativeMoney = new LinkedHashMap<>();
    negativeMoney.put(SILVER, -5L);
    assertThatThrownBy(
            () ->
                new GovOfficeState(
                    U1,
                    0L,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    negativeMoney,
                    Map.of(),
                    0,
                    0,
                    0,
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("值必须 ≥ 0")
        .hasMessageContaining("silver=-5");
  }

  @Test
  void perMilleValuesAboveEveryOldUpperLimitAreAccepted() {
    GovOfficeState aboveOldCaps = office(1_001L, 1_002L, 1_101L, 101L, 1_103L, 1_104L);

    assertThat(aboveOldCaps.securityCoveragePerMille()).as("旧上界 1000 已拆：1001 合法").isEqualTo(1_001L);
    assertThat(aboveOldCaps.paperworkCoveragePerMille()).isEqualTo(1_002L);
    assertThat(aboveOldCaps.efficiencyPerMille()).as("旧上界 1100 已拆：1101 合法").isEqualTo(1_101L);
    assertThat(aboveOldCaps.bonusPerMille())
        .as("旧上界 100 已拆：101 合法（legacy 字段也只判 ≥0）")
        .isEqualTo(101L);
    assertThat(aboveOldCaps.securityEfficiencyPerMille()).isEqualTo(1_103L);
    assertThat(aboveOldCaps.paperworkEfficiencyPerMille()).isEqualTo(1_104L);

    GovOfficeState exactlyOldCaps = office(1_000L, 1_000L, 1_100L, 100L, 1_000L, 1_000L);
    assertThat(exactlyOldCaps.securityCoveragePerMille()).isEqualTo(1_000L);
    assertThat(exactlyOldCaps.paperworkCoveragePerMille()).isEqualTo(1_000L);
    assertThat(exactlyOldCaps.efficiencyPerMille()).isEqualTo(1_100L);
    assertThat(exactlyOldCaps.bonusPerMille()).isEqualTo(100L);
  }

  @Test
  void twoDimensionEfficiencyReadingsAboveOneThousandAreAccepted() {
    GovOfficeState uncapped = office(2_000L, 3_000L, 30_000L, 0L, 5_000L, 6_000L);

    assertThat(uncapped.securityEfficiencyPerMille())
        .as("维效率 5000‰ 必须接受（C3 全不封顶）")
        .isEqualTo(5_000L);
    assertThat(uncapped.paperworkEfficiencyPerMille()).isEqualTo(6_000L);
    assertThat(uncapped.efficiencyPerMille()).isEqualTo(30_000L);
    assertThat(uncapped.securityCoveragePerMille()).isEqualTo(2_000L);
    assertThat(uncapped.paperworkCoveragePerMille()).isEqualTo(3_000L);
  }

  @Test
  void perMilleNegativeValuesThrow() {
    assertThatThrownBy(() -> office(-1L, 0L, 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCoveragePerMille");
    assertThatThrownBy(() -> office(0L, -1L, 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkCoveragePerMille");
    assertThatThrownBy(() -> office(0L, 0L, -1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efficiencyPerMille");
    assertThatThrownBy(() -> office(0L, 0L, 0L, -1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bonusPerMille");
    assertThatThrownBy(() -> office(0L, 0L, 0L, 0L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityEfficiencyPerMille");
    assertThatThrownBy(() -> office(0L, 0L, 0L, 0L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkEfficiencyPerMille");
  }

  @Test
  void tablesPreserveInsertionOrderAndAreImmutableCopies() {
    Map<CommodityId, Long> assessedGoods = new LinkedHashMap<>();
    assessedGoods.put(CLOTH, 2L);
    assessedGoods.put(GRAIN, 5L);
    Map<CurrencyId, Long> assessedMoney = new LinkedHashMap<>();
    assessedMoney.put(SILVER, 9L);

    GovOfficeState state =
        new GovOfficeState(
            U1,
            0L,
            assessedGoods,
            Map.of(),
            Map.of(),
            assessedMoney,
            Map.of(),
            Map.of(),
            0L,
            0L,
            0L,
            0L,
            0L,
            0L);

    assessedGoods.clear();
    assessedGoods.put(GRAIN, 100L);
    assessedMoney.clear();

    assertThat(new ArrayList<>(state.lastAssessedGoods().keySet()))
        .as("保序：迭代序 = 构造时的插入序（cloth 先于 grain），与之后改动的输入无关")
        .containsExactly(CLOTH, GRAIN);
    assertThat(state.lastAssessedGoods()).containsEntry(CLOTH, 2L).containsEntry(GRAIN, 5L);
    assertThat(state.lastAssessedMoney()).containsEntry(SILVER, 9L);
    assertThatThrownBy(() -> state.lastAssessedGoods().put(GRAIN, 1L))
        .as("冻结：外部不得改内部表")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> state.lastAssessedMoney().put(SILVER, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void legacyTwelveArgConstructorZeroFillsTwoDimensionEfficiency() {
    GovOfficeState legacy =
        new GovOfficeState(
            U1, 7L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 1_000L, 1_000L,
            1_100L, 100L);

    assertThat(legacy.securityCoveragePerMille()).isEqualTo(1_000L);
    assertThat(legacy.paperworkCoveragePerMille()).isEqualTo(1_000L);
    assertThat(legacy.efficiencyPerMille()).isEqualTo(1_100L);
    assertThat(legacy.bonusPerMille()).isEqualTo(100L);
    assertThat(legacy.securityEfficiencyPerMille())
        .as("旧 12 参构造器没有分维读数 ⇒ 默认 0（不是把总效率误当维效率）")
        .isZero();
    assertThat(legacy.paperworkEfficiencyPerMille()).isZero();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** canonical 14 参构造：六表全空，六个 per-mille 由参数给。 */
  private static GovOfficeState office(
      long securityCoverage,
      long paperworkCoverage,
      long efficiency,
      long bonus,
      long securityEfficiency,
      long paperworkEfficiency) {
    return new GovOfficeState(
        U1,
        0L,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        securityCoverage,
        paperworkCoverage,
        efficiency,
        bonus,
        securityEfficiency,
        paperworkEfficiency);
  }

  /** 旧 12 参构造：只给旧四个 per-mille（用于表守卫与兼容口径用例）。 */
  private static GovOfficeState oldOffice(
      Map<CommodityId, Long> assessedGoods,
      Map<CommodityId, Long> paidGoods,
      Map<CommodityId, Long> shortfallGoods,
      long securityCoverage,
      long paperworkCoverage,
      long efficiency,
      long bonus) {
    return new GovOfficeState(
        U1,
        0L,
        assessedGoods,
        paidGoods,
        shortfallGoods,
        Map.of(),
        Map.of(),
        Map.of(),
        securityCoverage,
        paperworkCoverage,
        efficiency,
        bonus);
  }
}
