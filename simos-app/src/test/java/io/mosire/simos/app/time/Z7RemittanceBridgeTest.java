package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.govpolicy.GovBudgetLine;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovOfficialSalaryRule;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z7c remittance 桥的行为契约</b>（设计书 §4、§8-闭环；z7c 台账 §6/§8）。
 *
 * <p>装置 = 真 {@code GovZ6WorldFixture.smallWorldState}（gov-province.superiorGov = gov-central）+ 真
 * {@link AccountSession} + 真 {@link GovRemittanceBridge#settle}（包内可见）。周期 = 仓库既有产业关账事实（{@code
 * cycleClosed=true} 的那一天），桥不自己推周期。
 *
 * <ul>
 *   <li>rate=500 足额：{@code due = floor(周期实收 × 500/1000)} 粮/银各自；源扣 == 目标加、两库总额不变；关账后累计清零；
 *   <li>rate=0（抗税）：账户零动、无转移、累计仍清零（不跨周期重复计）；
 *   <li>省库不足 ⇒ 部分支付 + 具名 {@code ADMIN_REMITTANCE_SHORTFALL} + 守恒；不自动注资/调率；
 *   <li>关账后下一日重新累计（不把上一周期实收重复计）；
 *   <li>缺省中性：2 参 {@link GovBudgetPolicy} / 只有 {@code offices} 的 {@link GovState} ⇒ rate=0 / 全零读数； ★
 *       2026-10-12 追加裁定 12 起政策权威 = {@code EconomyData.governments}（没有政府记录 ⇒ {@code
 *       governmentOrDefault} 给中性预算 = rate 0‰）。
 * </ul>
 */
class Z7RemittanceBridgeTest {

  private static final UnitId PROVINCE = UnitId.parse("gov-province");
  private static final UnitId CENTRAL = UnitId.parse("gov-central");
  private static final HouseholdId PROVINCE_TREASURY = GovernmentHouseholds.of(PROVINCE.value());
  private static final HouseholdId CENTRAL_TREASURY = GovernmentHouseholds.of(CENTRAL.value());

  @Test
  void rateFiveHundredPaysFullAmountAndConservesBothTreasuries() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    UnitState units = GovZ6WorldFixture.unitSlice(base);
    World world = worldWithProvinceRate(base, 500L);
    AccountSession accounts = accounts(5_000_000L, 1_000_000L, 0L, 0L);
    long grainTotalBefore = totalGrain(accounts);
    long silverTotalBefore = totalSilver(accounts);

    GovRemittanceBridge.Outcome outcome = advanceToClose(world, units, accounts, 100_000L, 10_000L);

    GovRemittanceState state = outcome.nextGov().remittanceStateOrDefault(PROVINCE);
    assertThat(state.lastCycleCloseDay()).isEqualTo(30L);
    assertThat(state.cycleGrainCollectedMilli()).as("关账后累计清零").isZero();
    assertThat(state.cycleSilverCollectedMilli()).isZero();
    assertThat(state.lastDueGrainMilli()).as("30 天 × 100,000 × 500‰").isEqualTo(1_500_000L);
    assertThat(state.lastPaidGrainMilli()).isEqualTo(1_500_000L);
    assertThat(state.lastShortfallGrainMilli()).isZero();
    assertThat(state.lastDueSilverMilli()).isEqualTo(150_000L);
    assertThat(state.lastPaidSilverMilli()).isEqualTo(150_000L);
    assertThat(state.lastShortfallSilverMilli()).isZero();
    assertThat(outcome.alerts()).as("足额 ⇒ 无缺口告警").isEmpty();

    assertThat(grainOf(accounts, PROVINCE_TREASURY)).isEqualTo(3_500_000L);
    assertThat(grainOf(accounts, CENTRAL_TREASURY)).isEqualTo(1_500_000L);
    assertThat(silverOf(accounts, PROVINCE_TREASURY)).isEqualTo(850_000L);
    assertThat(silverOf(accounts, CENTRAL_TREASURY)).isEqualTo(150_000L);
    assertThat(totalGrain(accounts)).as("源扣 == 目标加、两库总额不变").isEqualTo(grainTotalBefore);
    assertThat(totalSilver(accounts)).isEqualTo(silverTotalBefore);
  }

  @Test
  void rateZeroIsTaxResistanceWithNoTransferButStillResetsCycle() {
    try (AppLogCapture log = AppLogCapture.appTime()) {
      SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
      UnitState units = GovZ6WorldFixture.unitSlice(base);
      World world = worldWithProvinceRate(base, 0L);
      AccountSession accounts = accounts(5_000_000L, 1_000_000L, 0L, 0L);

      GovRemittanceBridge.Outcome outcome =
          advanceToClose(world, units, accounts, 100_000L, 10_000L);

      assertThat(grainOf(accounts, PROVINCE_TREASURY)).isEqualTo(5_000_000L);
      assertThat(grainOf(accounts, CENTRAL_TREASURY)).isZero();
      assertThat(silverOf(accounts, PROVINCE_TREASURY)).isEqualTo(1_000_000L);
      assertThat(silverOf(accounts, CENTRAL_TREASURY)).isZero();
      GovRemittanceState state = outcome.nextGov().remittanceStateOrDefault(PROVINCE);
      assertThat(state.lastPaidGrainMilli()).isZero();
      assertThat(state.lastPaidSilverMilli()).isZero();
      assertThat(state.cycleGrainCollectedMilli()).as("抗税也必须清零累计，避免下周期重复计").isZero();
      assertThat(state.lastCycleCloseDay()).isEqualTo(30L);
      assertThat(outcome.alerts()).isEmpty();
      assertThat(log.hasInfo("GOV_REMITTANCE_SKIPPED", "unit=gov-province", "reason=rate-zero"))
          .as("rate=0 必须具名 skip（不是静默 0）；实得 %s", log.messages())
          .isTrue();
    }
  }

  @Test
  void insufficientProvinceTreasuryPaysPartiallyAndRaisesNamedShortfallAlert() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    UnitState units = GovZ6WorldFixture.unitSlice(base);
    World world = worldWithProvinceRate(base, 1_000L);
    AccountSession accounts = accounts(100L, 50L, 0L, 0L);
    long grainTotalBefore = totalGrain(accounts);
    long silverTotalBefore = totalSilver(accounts);

    GovRemittanceBridge.Outcome outcome =
        settleRange(world, units, accounts, 1L, 3L, true, 100_000L, 10_000L);

    GovRemittanceState state = outcome.nextGov().remittanceStateOrDefault(PROVINCE);
    assertThat(state.lastDueGrainMilli()).isEqualTo(300_000L);
    assertThat(state.lastPaidGrainMilli()).as("逐腿 min(due, 可用)").isEqualTo(100L);
    assertThat(state.lastShortfallGrainMilli()).isEqualTo(299_900L);
    assertThat(state.lastDueSilverMilli()).isEqualTo(30_000L);
    assertThat(state.lastPaidSilverMilli()).isEqualTo(50L);
    assertThat(state.lastShortfallSilverMilli()).isEqualTo(29_950L);

    assertThat(outcome.alerts()).as("不足 ⇒ 恰一条缺口告警").hasSize(1);
    assertThat(outcome.alerts().get(0).kind()).isEqualTo(GovDaily.KIND_ADMIN_REMITTANCE_SHORTFALL);

    assertThat(grainOf(accounts, PROVINCE_TREASURY)).as("省库被抽干但不负").isZero();
    assertThat(grainOf(accounts, CENTRAL_TREASURY)).isEqualTo(100L);
    assertThat(silverOf(accounts, PROVINCE_TREASURY)).isZero();
    assertThat(silverOf(accounts, CENTRAL_TREASURY)).isEqualTo(50L);
    assertThat(totalGrain(accounts)).isEqualTo(grainTotalBefore);
    assertThat(totalSilver(accounts)).isEqualTo(silverTotalBefore);
    assertThat(outcome.nextGov().remittanceStateOrDefault(PROVINCE).cycleGrainCollectedMilli())
        .isZero();
  }

  @Test
  void afterCloseNextDayAccumulatesFromZeroNotFromPreviousCycle() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    UnitState units = GovZ6WorldFixture.unitSlice(base);
    World world = worldWithProvinceRate(base, 500L);
    AccountSession accounts = accounts(5_000_000L, 1_000_000L, 0L, 0L);

    GovRemittanceBridge.Outcome closed = advanceToClose(world, units, accounts, 100_000L, 10_000L);
    GovRemittanceBridge.Outcome nextDay =
        GovRemittanceBridge.settle(
            closed.nextGov(),
            units,
            world.economy(),
            Map.of(PROVINCE, 1_000L),
            Map.of(PROVINCE, 20L),
            accounts,
            31L,
            false);

    GovRemittanceState state = nextDay.nextGov().remittanceStateOrDefault(PROVINCE);
    assertThat(state.cycleGrainCollectedMilli()).as("关账日后的新周期只累计当日").isEqualTo(1_000L);
    assertThat(state.cycleSilverCollectedMilli()).isEqualTo(20L);
    assertThat(state.lastPaidGrainMilli()).as("非关账日不动最近关账读数").isEqualTo(1_500_000L);
    assertThat(nextDay.alerts()).isEmpty();
    assertThat(grainOf(accounts, CENTRAL_TREASURY)).as("非关账日不再转移").isEqualTo(1_500_000L);
  }

  @Test
  void legacyPolicyAndStateDefaultToZeroRateAndEmptyState() {
    GovBudgetPolicy legacyPolicy =
        new GovBudgetPolicy(List.<GovBudgetLine>of(), GovOfficialSalaryRule.zero());
    assertThat(legacyPolicy.remittancePerMilleToSuperior()).as("2 参构造器缺字段 = 0‰").isZero();
    assertThat(GovBudgetPolicy.neutral().remittancePerMilleToSuperior()).isZero();

    GovState legacyState = new GovState(Map.of());
    assertThat(legacyState.remittanceStateOrDefault(PROVINCE))
        .as("gov 切片没有该 GOV 键 ⇒ 全零读数")
        .isEqualTo(GovRemittanceState.empty());

    // ★★ 缺省中性的新宿主（§21.4 I-B1）：没有政府记录 ⇒ governmentOrDefault 给中性预算 ⇒ rate = 0‰。
    assertThat(
            EconomyData.empty()
                .governmentOrDefault(PROVINCE.value())
                .budgetPolicy()
                .remittancePerMilleToSuperior())
        .as("没有政府记录 ⇒ 中性预算 = 0‰（不是「静默收 0」，是「没设过」）")
        .isZero();
  }

  // ── 夹具 ──

  /**
   * 一个世界切片对：{@code gov} = GOV 读数（{@code remittanceStates} 仍住 gov 切片，§21.10-1 不随迁）；{@code economy} =
   * 政策权威（★★ 2026-10-12 追加裁定 12：上缴比例住在 {@code EconomyData.governments}）。
   */
  private record World(GovState gov, EconomyData economy) {}

  /** 真小世界 GOV 表 + 省 rate（其余字段沿用创世政策）。 */
  private static World worldWithProvinceRate(SimulationState base, long rate) {
    EconomyData economy = GovZ6WorldFixture.economySlice(base);
    GovernmentId id = GovernmentIds.ofUnit(PROVINCE.value());
    Government province = economy.governments().get(id);
    assertThat(province).as("创世必须已登记省 GOV（政策宿主 = 政府记录）").isNotNull();
    GovBudgetPolicy current = province.budgetPolicy();
    Government updated =
        province.withBudgetPolicy(
            new GovBudgetPolicy(current.orderedCategories(), current.officialSalaryRule(), rate));
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(id, updated);
    return new World(GovZ6WorldFixture.govSlice(base), economy.withGovernments(governments));
  }

  /** 逐日调用桥（1..days），仅最后一天 cycleClosed=true；每天实收 = grainPerDay/silverPerDay。 */
  private static GovRemittanceBridge.Outcome advanceToClose(
      World world, UnitState units, AccountSession accounts, long grainPerDay, long silverPerDay) {
    return settleRange(world, units, accounts, 1L, 30L, true, grainPerDay, silverPerDay);
  }

  private static GovRemittanceBridge.Outcome settleRange(
      World world,
      UnitState units,
      AccountSession accounts,
      long fromDay,
      long toDay,
      boolean closeOnLastDay,
      long grainPerDay,
      long silverPerDay) {
    GovState current = world.gov();
    GovRemittanceBridge.Outcome outcome = null;
    for (long day = fromDay; day <= toDay; day++) {
      boolean close = closeOnLastDay && day == toDay;
      outcome =
          GovRemittanceBridge.settle(
              current,
              units,
              world.economy(),
              Map.of(PROVINCE, grainPerDay, CENTRAL, 0L),
              Map.of(PROVINCE, silverPerDay, CENTRAL, 0L),
              accounts,
              day,
              close);
      current = outcome.nextGov();
    }
    return outcome;
  }

  private static AccountSession accounts(
      long provinceGrain, long provinceSilver, long centralGrain, long centralSilver) {
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        PROVINCE_TREASURY,
        new HexCoord(0, 0),
        provinceGrain > 0L ? Map.of(EconomyCommodities.GRAIN, provinceGrain) : Map.of(),
        provinceSilver > 0L ? Map.of(MoneyVocabulary.SILVER_CURRENCY, provinceSilver) : Map.of(),
        Map.of(),
        Map.of());
    accounts.registerHousehold(
        CENTRAL_TREASURY,
        new HexCoord(0, 0),
        centralGrain > 0L ? Map.of(EconomyCommodities.GRAIN, centralGrain) : Map.of(),
        centralSilver > 0L ? Map.of(MoneyVocabulary.SILVER_CURRENCY, centralSilver) : Map.of(),
        Map.of(),
        Map.of());
    return accounts;
  }

  private static long grainOf(AccountSession accounts, HouseholdId household) {
    return accounts.householdAccount(household).goods().getOrDefault(EconomyCommodities.GRAIN, 0L);
  }

  private static long silverOf(AccountSession accounts, HouseholdId household) {
    return accounts
        .householdAccount(household)
        .money()
        .getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L);
  }

  private static long totalGrain(AccountSession accounts) {
    return grainOf(accounts, PROVINCE_TREASURY) + grainOf(accounts, CENTRAL_TREASURY);
  }

  private static long totalSilver(AccountSession accounts) {
    return silverOf(accounts, PROVINCE_TREASURY) + silverOf(accounts, CENTRAL_TREASURY);
  }
}
