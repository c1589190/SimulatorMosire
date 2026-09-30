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
 * {@link GovOfficeState} 的构造期不变量（阶段 10a，计划 §2.2 / §3）：键值非 null、数值非负、四个 per-mille 的边界
 * 1000/1000/1100/100、六表保序不可变、{@code empty()} 的“尚无读数”语义。逐值钉边界，越界一律抛。
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
    assertThatThrownBy(() -> office(Map.of(), Map.of(), nullValue, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键与值都不得为 null");

    assertThatThrownBy(() -> office(Map.of(), Map.of(), Map.of(GRAIN, -1L), 0, 0, 0, 0))
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
  void perMilleBoundariesAreAcceptedAtTheirUpperLimits() {
    GovOfficeState atLimit =
        new GovOfficeState(
            U1, 0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 1000L, 1000L, 1100L,
            100L);

    assertThat(atLimit.securityCoveragePerMille()).isEqualTo(1000L);
    assertThat(atLimit.paperworkCoveragePerMille()).isEqualTo(1000L);
    assertThat(atLimit.efficiencyPerMille()).isEqualTo(1100L);
    assertThat(atLimit.bonusPerMille()).isEqualTo(100L);
  }

  @Test
  void perMilleValuesOverTheirUpperLimitsThrow() {
    assertThatThrownBy(() -> office(1001L, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCoveragePerMille")
        .hasMessageContaining("[0,1000]");
    assertThatThrownBy(() -> office(0, 1001L, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkCoveragePerMille")
        .hasMessageContaining("[0,1000]");
    assertThatThrownBy(() -> office(0, 0, 1101L, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efficiencyPerMille")
        .hasMessageContaining("[0,1100]");
    assertThatThrownBy(() -> office(0, 0, 0, 101L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bonusPerMille")
        .hasMessageContaining("[0,100]");
  }

  @Test
  void perMilleNegativeValuesThrow() {
    assertThatThrownBy(() -> office(-1L, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCoveragePerMille");
    assertThatThrownBy(() -> office(0, -1L, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkCoveragePerMille");
    assertThatThrownBy(() -> office(0, 0, -1L, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efficiencyPerMille");
    assertThatThrownBy(() -> office(0, 0, 0, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bonusPerMille");
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
            0,
            0,
            0,
            0);

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

  /** 六表全空、四个 per-mille 由参数给的便捷构造。 */
  private static GovOfficeState office(
      long securityCoverage, long paperworkCoverage, long efficiency, long bonus) {
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
        bonus);
  }

  /** 只替换商品缺口表的构造（用于 null/负值判据）。 */
  private static GovOfficeState office(
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
