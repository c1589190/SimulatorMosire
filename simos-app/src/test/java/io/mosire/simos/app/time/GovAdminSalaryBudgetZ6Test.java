package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.StateRunner;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.spi.EconomyGovServiceCommitments;
import io.mosire.simos.economy.spi.EconomyGovUnitUpserts;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.FreezeIntent;
import io.mosire.simos.economy.time.SettlementStage;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovEfficiencyModifier;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovOfficialSalaryRule;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z6a 官吏工资 / 国库预算 / 告警用例（Z3c-1 §9）</b>：工资公式逐值、逐腿 min 与四类缺口 reason、预算默认顺序与
 * min/cap、六类告警正负例、中性预算不自动付、无自动招募/注资/调计划。
 *
 * <p>夹具逐条移植自 Z3c 探针（{@code /tmp/z3c-probe}，探针全绿）；期望值 = 探针实测字面量 + 台账 §2/§3 冻结口径。 {@link
 * GovBudgetExecutionBridge#plan} 是纯规划（不写账户/状态），执行由 {@link
 * PeriodicHouseholdAdjustmentExecutor#applyExplicit} 显式触发——这正是"无自动动作"的判别点。
 */
class GovAdminSalaryBudgetZ6Test {

  private static final UnitId GOV = UnitId.parse("u-1");
  private static final HouseholdId HH_GOV = GovernmentHouseholds.of("u-1");
  private static final HouseholdId HH_UNIT = new HouseholdId("hh-unit-u");
  private static final HouseholdId HH_EXT = new HouseholdId("hh-ext");
  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;
  private static final CommodityId CLOTH = EconomyCommodities.CLOTH;
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final long DAY = 7L;

  // ── ① 工资公式逐值 / rate=0 / 折算 0 / 溢出 ──────────────────────────────────────────

  @Test
  void salaryFormulaIsCommittedLaborTimesRateOverThousand() {
    EconomyData economy = economyWithCommitment(16_000L, HH_UNIT);
    GovState gov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));

    GovSalaryRuleBridge.Report report =
        GovSalaryRuleBridge.deriveReport(gov, unitState(), economy, DAY);
    assertThat(report.rules()).as("16000 承诺 × 10/1000 = 160 粮、× 1/1000 = 16 银").hasSize(1);
    var rule = report.rules().get(0);
    assertThat(rule.id().value()).isEqualTo("gov-salary:u-1:hh-unit-u");
    assertThat(rule.goodsPerCycle().get(GRAIN)).isEqualTo(160L);
    assertThat(rule.moneyPerCycle().get(SILVER)).isEqualTo(16L);
    assertThat(rule.reason()).isEqualTo(DeductionReason.ADMIN_SALARY);
    assertThat(rule.periodDays()).isEqualTo(1L);
    assertThat(rule.startsOnDay()).isEqualTo(1L);
    assertThat(report.committedLaborMilliTotal()).isEqualTo(16_000L);
    assertThat(report.committedHouseholds()).isEqualTo(1);

    // rate = 0/0 ⇒ 明确不发薪：不生成规则、不产生缺口（不是静默 0）。
    GovState zeroRateGov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(0L, 0L)));
    GovSalaryRuleBridge.Report noRule =
        GovSalaryRuleBridge.deriveReport(zeroRateGov, unitState(), economy, DAY);
    assertThat(noRule.rules()).isEmpty();
    assertThat(noRule.zeroAmounts()).isEmpty();
    assertThat(noRule.gaps()).isEmpty();
  }

  @Test
  void salaryBelowOneMilliIsNamedZeroAmountAndOverflowFailsClosed() {
    try (AppLogCapture log = AppLogCapture.appTime()) {
      // rate>0 但 laborMilli×rate/1000 折算到 0 ⇒ 具名 zeroAmounts，不生成规则。
      EconomyData tiny = economyWithCommitment(1L, HH_UNIT);
      GovState gov =
          govState(
              adminPlan(32_000L, 16_000L),
              policy(
                  List.of(line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                  new GovOfficialSalaryRule(10L, 1L)));
      GovSalaryRuleBridge.Report report =
          GovSalaryRuleBridge.deriveReport(gov, unitState(), tiny, DAY);
      assertThat(report.rules()).isEmpty();
      assertThat(report.zeroAmounts())
          .singleElement()
          .asString()
          .contains("household=hh-unit-u")
          .contains("laborMilli=1")
          .contains("reason=rate-times-labor-below-one-milli");

      // 溢出：32000 × Long.MAX_VALUE ⇒ multiplyExact 溢出 ⇒ ERROR GOV_SALARY_CONTRACT_VIOLATION + ISE。
      EconomyData big = economyWithCommitment(32_000L, HH_UNIT);
      GovState hugeRate =
          govState(
              adminPlan(32_000L, 16_000L),
              policy(
                  List.of(line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                  new GovOfficialSalaryRule(Long.MAX_VALUE, Long.MAX_VALUE)));
      log.clear();
      assertThatThrownBy(() -> GovSalaryRuleBridge.deriveReport(hugeRate, unitState(), big, DAY))
          .as("工资桥算术溢出 = 契约故障 ⇒ ISE")
          .isInstanceOf(IllegalStateException.class);
      assertThat(log.hasError("GOV_SALARY_CONTRACT_VIOLATION", "reason=salary-overflow"))
          .as("必须具名 ERROR；实得 %s", log.messages())
          .isTrue();
      assertThatThrownBy(() -> new GovOfficialSalaryRule(-1L, 0L))
          .as("负速率 = 载荷非法 ⇒ 构造期具名拒")
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ── ② 预算：腿序 cap / min 预留 / 默认顺序 ───────────────────────────────────────────

  @Test
  void budgetCapCutsGrainThenClothThenSilverAndReservesLowerPriorityMin() {
    // (a) 腿序：官俸需求 = 粮 3 + 布 10（每人 3 粮/tick；布 3650/365=10/tick），cap=5 ⇒ 粮 3 + 布 2。
    GovernmentFormation legFormation = formationWithUpkeep(3L, 3650L, Map.of());
    UnitState legUnits = unitState(legFormation);
    GovState legGov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(line(GovBudgetCategory.ADMIN_STIPEND, 0L, 5L)),
                new GovOfficialSalaryRule(0L, 0L)));
    AccountSession accounts = accounts(1000L, 1000L, 0L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            legGov,
            legUnits,
            social(),
            economyWithoutCommitment(),
            accounts,
            Map.of(GOV, efficiency(new GovernmentServiceLaborBridge.Supply(0L, 0L))),
            Map.of(GOV, new GovernmentServiceLaborBridge.Supply(0L, 0L)),
            DAY,
            365L);
    GovBudgetExecutionBridge.CategoryAllocation stipend =
        budget.plan(GOV).allocation(GovBudgetCategory.ADMIN_STIPEND);
    assertThat(stipend.requested().grainMilli()).isEqualTo(3L);
    assertThat(stipend.requested().clothMilli()).isEqualTo(10L);
    assertThat(stipend.authorized().grainMilli()).as("固定腿序：先粮").isEqualTo(3L);
    assertThat(stipend.authorized().clothMilli()).as("剩余价值给布 5−3=2").isEqualTo(2L);
    assertThat(stipend.authorized().silverMilli()).isZero();
    assertThat(stipend.capLimitedValue()).isEqualTo(8L); // 请求 13 − 帽 5

    // (b) min 预留（s3 形态，默认顺序 行政俸禄 → … → 工资）：国库只有 100 粮；工资 min=100 必须被留下 ⇒ 官俸授权 0、工资授权 100。
    UnitState units = unitState();
    GovState reserveGov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.MILITARY_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 100L, Long.MAX_VALUE),
                    line(GovBudgetCategory.DEBT_SERVICE, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.OTHER, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));
    GovBudgetExecutionBridge.DayBudget reserveBudget =
        GovBudgetExecutionBridge.plan(
            reserveGov,
            units,
            social(),
            economyWithCommitment(16_000L, HH_UNIT),
            accounts(100L, 0L, 0L),
            Map.of(GOV, efficiency(new GovernmentServiceLaborBridge.Supply(0L, 16_000L))),
            Map.of(GOV, new GovernmentServiceLaborBridge.Supply(0L, 16_000L)),
            DAY,
            365L);
    GovBudgetExecutionBridge.GovBudgetPlan reservePlan = reserveBudget.plan(GOV);
    assertThat(reservePlan.allocation(GovBudgetCategory.ADMIN_STIPEND).authorized().grainMilli())
        .as("低优先级工资的 min=100 先预留 ⇒ 官俸拿 0")
        .isZero();
    assertThat(reservePlan.allocation(GovBudgetCategory.ADMIN_SALARY).authorized().grainMilli())
        .as("工资拿到预留的 100 粮")
        .isEqualTo(100L);
    assertThat(reservePlan.allocation(GovBudgetCategory.ADMIN_SALARY).lowerReserveValue()).isZero();
    assertThat(reservePlan.allocation(GovBudgetCategory.ADMIN_STIPEND).lowerReserveValue())
        .as("官俸这一行给更低优先级预留了 100")
        .isEqualTo(100L);
  }

  // ── ③ 逐腿缺口四类 reason（DEBUG 逐笔读账）─────────────────────────────────────────────

  @Test
  void salaryShortfallReasonsCoverBudgetTreasuryAndAccountCauses() {
    try (AppLogCapture log = AppLogCapture.logger(AppLog.TIME_LOGGER_NAME, Level.DEBUG)) {
      // (a) cap=100：工资请求 160 粮+16 银，实付 100 粮 ⇒ budget-or-treasury-limited
      SalaryRun capped =
          runSalary(accounts(1000L, 0L, 1000L), new GovOfficialSalaryRule(10L, 1L), 100L);
      assertThat(capped.totals().paidGrainMilli()).isEqualTo(100L);
      assertThat(capped.totals().paidSilverMilli()).isZero();
      assertThat(capped.totals().budgetShortfallGrainMilli()).isEqualTo(60L);
      assertThat(capped.totals().budgetShortfallSilverMilli()).isEqualTo(16L);
      assertThat(
              log.has("DEBUG", "GOV_ADMIN_SALARY_SHORTFALL", "reason=budget-or-treasury-limited"))
          .as("逐笔缺口 reason 必须具名；实得 %s", shortfallLines(log))
          .isTrue();

      // (b) cap=0 ⇒ budget-authorized-zero
      log.clear();
      runSalary(accounts(1000L, 0L, 1000L), new GovOfficialSalaryRule(10L, 1L), 0L);
      assertThat(log.has("DEBUG", "GOV_ADMIN_SALARY_SHORTFALL", "reason=budget-authorized-zero"))
          .as("授权为 0 ⇒ budget-authorized-zero；实得 %s", shortfallLines(log))
          .isTrue();

      // (c) 规划时额度足，执行前国库被全额冻结 ⇒ 可用量 0 ⇒ treasury-available-zero
      log.clear();
      AccountSession frozen = accounts(1000L, 0L, 1000L);
      EconomyData economy = economyWithCommitment(16_000L, HH_UNIT);
      GovernmentFormation formation = formationWithTier2();
      UnitState frozenUnits = unitState(formation);
      GovState frozenGov =
          govState(
              adminPlan(32_000L, 16_000L),
              policy(
                  List.of(
                      line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                      line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                  new GovOfficialSalaryRule(10L, 1L)));
      GovernmentServiceLaborBridge.Supply frozenSupply =
          new GovernmentServiceLaborBridge.Supply(0L, 16_000L);
      GovEfficiency.Efficiency frozenEfficiency =
          GovEfficiency.of(
              formation,
              Map.of(),
              adminPlan(32_000L, 16_000L),
              0L,
              16_000L,
              1000L,
              1000L,
              1000L,
              1000L,
              16_000L);
      GovBudgetExecutionBridge.DayBudget frozenBudget =
          GovBudgetExecutionBridge.plan(
              frozenGov,
              frozenUnits,
              social(),
              economy,
              frozen,
              Map.of(GOV, frozenEfficiency),
              Map.of(GOV, frozenSupply),
              DAY,
              365L);
      frozen.commit(
          List.of(
              FreezeIntent.goods(
                  frozen.householdKeyOf(HH_GOV), SettlementStage.CONSUMPTION, 0, 0, GRAIN, 1000L),
              FreezeIntent.money(
                  frozen.householdKeyOf(HH_GOV),
                  SettlementStage.CONSUMPTION,
                  0,
                  1,
                  SILVER,
                  1000L)));
      GovDaily.settle(
          frozenGov,
          frozenUnits,
          DAY,
          365L,
          Map.of(GOV, frozenEfficiency),
          frozenBudget.upkeepOracle(new GovernmentUpkeepOracle(frozen, frozenUnits)));
      PeriodicHouseholdAdjustmentExecutor.Report frozenReport =
          PeriodicHouseholdAdjustmentExecutor.applyExplicit(
              frozenBudget.budgetedRules(), frozen, DAY);
      frozenBudget.logSalaryExecution(frozenReport, DAY);
      assertThat(log.has("DEBUG", "GOV_ADMIN_SALARY_SHORTFALL", "reason=treasury-available-zero"))
          .as("国库可支配 0 ⇒ treasury-available-zero；实得 %s", shortfallLines(log))
          .isTrue();

      // (d) 收款账户缺失 ⇒ *-account-missing
      log.clear();
      AccountSession noPayee = AccountSession.empty();
      noPayee.registerHousehold(
          HH_GOV,
          new HexCoord(0, 0),
          Map.of(GRAIN, 1000L),
          Map.of(SILVER, 1000L),
          Map.of(),
          Map.of());
      runSalary(noPayee, new GovOfficialSalaryRule(10L, 1L), Long.MAX_VALUE);
      assertThat(log.has("DEBUG", "GOV_ADMIN_SALARY_SHORTFALL", "account-missing"))
          .as("缺收款账户 ⇒ 具名 account-missing；实得 %s", shortfallLines(log))
          .isTrue();
    }
  }

  // ── ④ GOV_ADMIN_SALARY_DAY 与 RuleReadout 对账 ────────────────────────────────────────

  @Test
  void govAdminSalaryDayLogReconcilesWithRuleReadout() {
    try (AppLogCapture log = AppLogCapture.appTime()) {
      SalaryRun run =
          runSalary(accounts(1000L, 0L, 1000L), new GovOfficialSalaryRule(10L, 1L), Long.MAX_VALUE);
      assertThat(run.report().rules()).hasSize(1);
      PeriodicHouseholdAdjustmentExecutor.RuleReadout readout = run.report().rules().get(0);
      assertThat(readout.ruleId().value()).isEqualTo("gov-salary:u-1:hh-unit-u");
      assertThat(readout.paidGoods().get(GRAIN)).isEqualTo(160L);
      assertThat(readout.paidMoney().get(SILVER)).isEqualTo(16L);
      assertThat(run.totals().paidGrainMilli()).isEqualTo(160L);
      assertThat(run.totals().paidSilverMilli()).isEqualTo(16L);
      assertThat(run.totals().shortfallGrainMilli()).isZero();
      assertThat(run.totals().shortfallSilverMilli()).isZero();

      assertThat(
              log.hasInfo(
                  "GOV_ADMIN_SALARY_DAY",
                  "unit=u-1",
                  "households=1",
                  "requestedGrain=160",
                  "requestedSilver=16",
                  "paidGrain=160",
                  "paidSilver=16",
                  "shortfallGrain=0",
                  "shortfallSilver=0"))
          .as("工资日 INFO 与 RuleReadout 逐值对账；实得 %s", salaryLines(log))
          .isTrue();
      // 国库/收款户终值（Z7b 俸禄转官吏户后）：国库 1000 − 官俸 166 − 工资 160 = 674 粮；银 1000 − 16 = 984；
      // 官吏户 = 俸禄 166 + 工资 160 = 326 粮、+16 银（旧口径俸禄进 sink，官吏户只 +160）。
      assertThat(run.accounts().householdAccount(HH_GOV).goods().get(GRAIN)).isEqualTo(674L);
      assertThat(run.accounts().householdAccount(HH_GOV).money().get(SILVER)).isEqualTo(984L);
      assertThat(run.accounts().householdAccount(HH_UNIT).goods().get(GRAIN)).isEqualTo(326L);
      assertThat(run.accounts().householdAccount(HH_UNIT).money().get(SILVER)).isEqualTo(16L);
    }
  }

  // ── ⑤ 六类告警：正例 + 负例 ─────────────────────────────────────────────────────────

  @Test
  void healthyScenarioProducesNoAlertsAndNoAdminSignals() {
    EconomyData economy = economyWithCommitment(32_000L, HH_UNIT);
    GovernmentFormation full = formationWithTier3();
    UnitState units = unitState(full);
    GovState gov =
        govState(
            adminPlan(16_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.MILITARY_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.DEBT_SERVICE, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.OTHER, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));
    GovernmentServiceLaborBridge.Supply supply =
        new GovernmentServiceLaborBridge.Supply(16_000L, 16_000L);
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            full,
            Map.of(),
            adminPlan(16_000L, 16_000L),
            16_000L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    AccountSession accounts = accounts(1000L, 1000L, 1000L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            economy,
            accounts,
            Map.of(GOV, efficiency),
            Map.of(GOV, supply),
            DAY,
            365L);
    assertThat(budget.alerts()).as("满覆盖 + 显式计划/预算 + 有承诺 + 国库足 ⇒ 六类告警一条都不该报").isEmpty();

    GovDaily.Outcome outcome =
        GovDaily.settle(
            gov,
            units,
            DAY,
            365L,
            Map.of(GOV, efficiency),
            budget.upkeepOracle(new GovernmentUpkeepOracle(accounts, units)));
    assertThat(outcome.signals()).as("负向：满覆盖不得报 ADMIN_SUPPLY/SECURITY/PAPERWORK").isEmpty();
  }

  @Test
  void planMissingServiceFlowZeroVacancyAndBudgetShortfallAlertsArePositive() {
    // 服务流量 0 + 岗位空缺（供给一维 0）+ 预算 cap 缺口，一次覆盖三类告警。
    EconomyData withCommitment = economyWithCommitment(16_000L, HH_UNIT);
    GovernmentFormation partial = formationWithTier2();
    UnitState units = unitState(partial);
    GovState gov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 0L, 100L)),
                new GovOfficialSalaryRule(10L, 1L)));
    GovernmentServiceLaborBridge.Supply zero = new GovernmentServiceLaborBridge.Supply(0L, 16_000L);
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            partial,
            Map.of(),
            adminPlan(32_000L, 16_000L),
            0L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            withCommitment,
            accounts(1000L, 0L, 1000L),
            Map.of(GOV, efficiency),
            Map.of(GOV, zero),
            DAY,
            365L);
    assertThat(alertKinds(budget)).contains(GovDaily.KIND_ADMIN_VACANCY);
    assertThat(alertKinds(budget))
        .as("工资请求 176 价值、cap=100 ⇒ ADMIN_BUDGET_SHORTFALL")
        .contains(GovDaily.KIND_ADMIN_BUDGET_SHORTFALL);

    // 无承诺 ⇒ 服务流量 0 正例（单独一次规划，因为"有承诺"与"工资缺口"不能同场景）。
    GovBudgetExecutionBridge.DayBudget noCommitmentBudget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            economyWithoutCommitment(),
            accounts(1000L, 0L, 1000L),
            Map.of(GOV, efficiency),
            Map.of(GOV, zero),
            DAY,
            365L);
    assertThat(alertKinds(noCommitmentBudget)).contains(GovDaily.KIND_ADMIN_SERVICE_FLOW_ZERO);

    GovDaily.SignalDraft shortfall =
        budget.alerts().stream()
            .filter(draft -> draft.kind().equals(GovDaily.KIND_ADMIN_BUDGET_SHORTFALL))
            .findFirst()
            .orElseThrow();
    assertThat(shortfall.evidence())
        .containsKeys(
            "requestedValue",
            "authorizedValue",
            "shortfallValue",
            "capLimitedValue",
            "treasuryLimitedValue",
            "floorUnmetValue");
    assertThat(shortfall.evidence().get("shortfallValue"))
        .as("shortfall = requested − authorized")
        .isEqualTo(
            shortfall.evidence().get("requestedValue")
                - shortfall.evidence().get("authorizedValue"));
    assertThat(shortfall.evidence().get("capLimitedValue")).as("cap=100 截掉的 76").isEqualTo(76L);

    // 计划未设（空政策）正例。
    GovBudgetExecutionBridge.DayBudget neutral =
        GovBudgetExecutionBridge.plan(
            govState(adminPlan(32_000L, 16_000L), GovBudgetPolicy.neutral()),
            units,
            social(),
            economyWithoutCommitment(),
            accounts(1000L, 0L, 1000L),
            Map.of(GOV, efficiency),
            Map.of(GOV, zero),
            DAY,
            365L);
    assertThat(alertKinds(neutral)).contains(GovDaily.KIND_ADMIN_PLAN_MISSING);
  }

  @Test
  void adminSupplySecurityAndPaperworkSignalsArePositiveAndNegative() {
    EconomyData economy = economyWithCommitment(32_000L, HH_UNIT);
    GovernmentFormation full = formationWithTier3();
    UnitState units = unitState(full);
    GovState gov =
        govState(
            adminPlan(16_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));
    GovEfficiency.Efficiency efficient =
        GovEfficiency.of(
            full,
            Map.of(),
            adminPlan(16_000L, 16_000L),
            16_000L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovEfficiency.Efficiency zeroCoverage =
        GovEfficiency.of(
            full,
            Map.of(),
            adminPlan(16_000L, 16_000L),
            0L,
            0L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);

    // 正例：国库 0 ⇒ 官俸 assessed 166/paid 0 ⇒ ADMIN_SUPPLY；供给 (0,0) + 需求>0 ⇒ ADMIN_SECURITY/PAPERWORK。
    AccountSession broke = accounts(0L, 0L, 0L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            economy,
            broke,
            Map.of(GOV, zeroCoverage),
            Map.of(GOV, new GovernmentServiceLaborBridge.Supply(0L, 0L)),
            DAY,
            365L);
    GovDaily.Outcome outcome =
        GovDaily.settle(
            gov,
            units,
            DAY,
            365L,
            Map.of(GOV, zeroCoverage),
            budget.upkeepOracle(new GovernmentUpkeepOracle(broke, units)));
    List<String> kinds = new ArrayList<>();
    outcome.signals().forEach(signal -> kinds.add(signal.kind()));
    assertThat(kinds)
        .as("国库不足 ⇒ ADMIN_SUPPLY；供给 0 且需求>0 ⇒ 两维缺口")
        .contains(
            GovDaily.KIND_ADMIN_SUPPLY,
            GovDaily.KIND_ADMIN_SECURITY,
            GovDaily.KIND_ADMIN_PAPERWORK);

    // 负例：国库足 + 满覆盖 ⇒ 三类都不报。
    AccountSession rich = accounts(1000L, 1000L, 1000L);
    GovBudgetExecutionBridge.DayBudget richBudget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            economy,
            rich,
            Map.of(GOV, efficient),
            Map.of(GOV, new GovernmentServiceLaborBridge.Supply(16_000L, 16_000L)),
            DAY,
            365L);
    GovDaily.Outcome richOutcome =
        GovDaily.settle(
            gov,
            units,
            DAY,
            365L,
            Map.of(GOV, efficient),
            richBudget.upkeepOracle(new GovernmentUpkeepOracle(rich, units)));
    assertThat(richOutcome.signals()).isEmpty();
  }

  @Test
  void contractAlertComesFromSelfPayerSalaryGap() {
    // 官吏户挂到 hh-gov（承诺持有者 = 国库自己）⇒ 工资桥自转 gap ⇒ ADMIN_CONTRACT（不自动修复）。
    EconomyData selfPay = economyWithCommitment(16_000L, HH_GOV);
    GovernmentFormation formation = formationWithTier3For(HH_GOV);
    UnitState units = unitState(formation);
    GovState gov =
        govState(
            adminPlan(16_000L, 16_000L),
            policy(
                List.of(line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            selfPay, GOV, formation, adminPlan(16_000L, 16_000L), DAY);
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            formation,
            Map.of(),
            adminPlan(16_000L, 16_000L),
            supply.securityLaborMilli(),
            supply.paperworkLaborMilli(),
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            selfPay,
            accounts(1000L, 0L, 1000L),
            Map.of(GOV, efficiency),
            Map.of(GOV, supply),
            DAY,
            365L);
    assertThat(budget.salaryReport().gaps()).anyMatch(gap -> gap.contains("gap=self-payer"));
    assertThat(alertKinds(budget)).contains(GovDaily.KIND_ADMIN_CONTRACT);
  }

  // ── ⑥ 旧档中性默认：空/全 0/单类未配置 ⇒ 不自动付 + ADMIN_PLAN_MISSING ────────────────

  @Test
  void neutralAllZeroAndUnbudgetedPoliciesRaisePlanMissingWithoutAutoPay() {
    EconomyData economy = economyWithCommitment(16_000L, HH_UNIT);
    UnitState units = unitState();
    GovernmentServiceLaborBridge.Supply supply =
        new GovernmentServiceLaborBridge.Supply(0L, 16_000L);
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            formationWithTier2(),
            Map.of(),
            adminPlan(32_000L, 16_000L),
            0L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);

    record Variant(String label, GovBudgetPolicy policy) {}
    List<Variant> variants =
        List.of(
            new Variant("空政策", GovBudgetPolicy.neutral()),
            new Variant(
                "全 0 政策",
                policy(
                    List.of(
                        line(GovBudgetCategory.ADMIN_STIPEND, 0L, 0L),
                        line(GovBudgetCategory.ADMIN_SALARY, 0L, 0L)),
                    new GovOfficialSalaryRule(10L, 1L))),
            new Variant(
                "单类未配置（有官俸无工资）",
                policy(
                    List.of(line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE)),
                    new GovOfficialSalaryRule(10L, 1L))));

    // 旧档形态：GovState 的 budgetPolicies 表里根本没有该 GOV 的键 ⇒ budgetPolicyOrDefault = neutral()。
    GovState legacyGovState =
        new GovState(
            Map.of(GOV, GovOfficeState.empty(GOV, 0L)),
            Map.of(GOV, adminPlan(32_000L, 16_000L)),
            Map.of());
    AccountSession legacyAccounts = accounts(1000L, 0L, 1000L);
    GovBudgetExecutionBridge.DayBudget legacyBudget =
        GovBudgetExecutionBridge.plan(
            legacyGovState,
            units,
            social(),
            economy,
            legacyAccounts,
            Map.of(GOV, efficiency),
            Map.of(GOV, supply),
            DAY,
            365L);
    assertThat(alertKinds(legacyBudget))
        .as("旧档缺 budgetPolicies ⇒ ADMIN_PLAN_MISSING")
        .contains(GovDaily.KIND_ADMIN_PLAN_MISSING);
    assertThat(legacyBudget.budgetedRules()).as("旧档中性默认 ⇒ 不自动付").isEmpty();

    for (Variant variant : variants) {
      AccountSession accounts = accounts(1000L, 0L, 1000L);
      GovState gov = govState(adminPlan(32_000L, 16_000L), variant.policy());
      GovBudgetExecutionBridge.DayBudget budget =
          GovBudgetExecutionBridge.plan(
              gov,
              units,
              social(),
              economy,
              accounts,
              Map.of(GOV, efficiency),
              Map.of(GOV, supply),
              DAY,
              365L);
      assertThat(alertKinds(budget))
          .as("%s ⇒ ADMIN_PLAN_MISSING", variant.label())
          .contains(GovDaily.KIND_ADMIN_PLAN_MISSING);
      assertThat(budget.budgetedRules()).as("%s ⇒ 不自动付（无预算类别授权）", variant.label()).isEmpty();
      GovDaily.Outcome outcome =
          GovDaily.settle(
              gov,
              units,
              DAY,
              365L,
              Map.of(GOV, efficiency),
              budget.upkeepOracle(new GovernmentUpkeepOracle(accounts, units)));
      long expectedStipendPaid = variant.label().startsWith("单类未配置") ? 166L : 0L;
      assertThat(outcome.dues().get(0).assessed()).as("官俸评估 166").isEqualTo(166L);
      assertThat(outcome.dues().get(0).paid())
          .as("%s ⇒ 未配置类别不自动付（已配置的官俸类除外）", variant.label())
          .isEqualTo(expectedStipendPaid);
      assertThat(outcome.dues().get(0).shortfall()).isEqualTo(166L - expectedStipendPaid);
    }
  }

  // ── ⑦ 无自动招募/注资/调计划（规划只读）────────────────────────────────────────────────

  @Test
  void planningIsReadOnlyAndNeverRecruitsFundsOrReplans() {
    EconomyData economy = economyWithCommitment(16_000L, HH_UNIT);
    UnitState units = unitState();
    SocialData social = social();
    GovState gov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 0L, Long.MAX_VALUE)),
                new GovOfficialSalaryRule(10L, 1L)));
    AccountSession accounts = accounts(1000L, 0L, 1000L);
    long grainBefore = accounts.householdAccount(HH_GOV).goods().get(GRAIN);
    long silverBefore = accounts.householdAccount(HH_GOV).money().get(SILVER);

    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social,
            economy,
            accounts,
            Map.of(GOV, efficiency(new GovernmentServiceLaborBridge.Supply(0L, 16_000L))),
            Map.of(GOV, new GovernmentServiceLaborBridge.Supply(0L, 16_000L)),
            DAY,
            365L);

    // plan = 纯规划：账户/状态/社会三方一字未动，也没有任何招募/注资命令被构造。
    assertThat(accounts.householdAccount(HH_GOV).goods().get(GRAIN)).isEqualTo(grainBefore);
    assertThat(accounts.householdAccount(HH_GOV).money().get(SILVER)).isEqualTo(silverBefore);
    assertThat(economy).isEqualTo(economyWithCommitment(16_000L, HH_UNIT));
    assertThat(social).isEqualTo(social());
    assertThat(units).isEqualTo(unitState());
    assertThat(budget.budgetedRules())
        .allSatisfy(
            rule -> assertThat(rule.id().value()).startsWith(GovSalaryRuleBridge.RULE_ID_PREFIX));
    assertThat(budget.budgetedRules())
        .as("无招募/注资/调计划规则被构造（只有按承诺小时的工资规则）")
        .allSatisfy(
            rule ->
                assertThat(rule.id().value())
                    .doesNotContain("recruit")
                    .doesNotContain("treasury")
                    .doesNotContain("plan"));
  }

  // ── ⑧ 税路径真读「效率不封顶」（BLOCKED-1 修复的端到端判据）────────────────────────────

  @Test
  void taxPathAcceptsEfficiencyAboveOneThousandOneHundred() {
    StateRunner runner = new StateRunner("Map1", GovZ6WorldFixture.smallWorldState("Map1"));
    runner.advance(0L, 1L);
    var state = runner.state();
    var economy = GovZ6WorldFixture.economySlice(state);
    var actor = GovZ6WorldFixture.actorSlice(state);
    UnitState units = GovZ6WorldFixture.unitSlice(state);
    GameMap map = ((MapSnapshot) state.module("map").orElseThrow()).map();
    UnitId province = UnitId.parse("gov-province");

    // 6000‰：超编效率（动态修正 2000/3000 推出的总效率）在税路径**正常参与**：
    // attainable > assessed ⇒ 有符号 adminShortfall < 0（旧 1100 闸会当场 IAE，根本到不了这里）。
    AccountSession at6000 = OwnershipBooks.loadAccountSession(economy, actor);
    JurisdictionDailyTax.Report report6000 =
        JurisdictionDailyTax.collect(
            at6000, economy.classes(), units, map, 1L, Map.of(province, 6000L));
    assertThat(report6000.grain().assessed()).as("省有辖区/有税基").isPositive();
    assertThat(report6000.grain().adminShortfall())
        .as("效率 6000‰ > 1000‰ ⇒ attainable > assessed ⇒ 有符号缺口为负")
        .isNegative();
    assertThat(
            report6000.grain().collected()
                + report6000.grain().adminShortfall()
                + report6000.grain().stockShortfall())
        .as("恒等式 collected + adminShortfall + stockShortfall == assessed")
        .isEqualTo(report6000.grain().assessed());

    // 1000‰ 对照：adminShortfall 不为负、collected ≤ assessed；6000‰ 的征收量不少于 1000‰。
    AccountSession at1000 = OwnershipBooks.loadAccountSession(economy, actor);
    JurisdictionDailyTax.Report report1000 =
        JurisdictionDailyTax.collect(
            at1000, economy.classes(), units, map, 1L, Map.of(province, 1000L));
    assertThat(report1000.grain().adminShortfall()).isNotNegative();
    assertThat(report1000.grain().collected()).isLessThanOrEqualTo(report1000.grain().assessed());
    assertThat(report6000.grain().collected())
        .as("超编效率下征收量 ≥ 基准效率")
        .isGreaterThanOrEqualTo(report1000.grain().collected());
  }

  // ── 夹具（移植自 /tmp/z3c-probe，期望值与探针同源）────────────────────────────────────

  private record SalaryRun(
      AccountSession accounts,
      PeriodicHouseholdAdjustmentExecutor.Report report,
      GovBudgetExecutionBridge.SalaryTotals totals) {}

  private static SalaryRun runSalary(
      AccountSession accounts, GovOfficialSalaryRule rule, long salaryCap) {
    EconomyData economy = economyWithCommitment(16_000L, HH_UNIT);
    GovernmentFormation formation = formationWithTier2();
    UnitState units = unitState(formation);
    GovState gov =
        govState(
            adminPlan(32_000L, 16_000L),
            policy(
                List.of(
                    line(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE),
                    line(GovBudgetCategory.ADMIN_SALARY, 0L, salaryCap)),
                rule));
    GovernmentServiceLaborBridge.Supply supply =
        new GovernmentServiceLaborBridge.Supply(0L, 16_000L);
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(
            formation,
            Map.of(),
            adminPlan(32_000L, 16_000L),
            0L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovBudgetExecutionBridge.DayBudget budget =
        GovBudgetExecutionBridge.plan(
            gov,
            units,
            social(),
            economy,
            accounts,
            Map.of(GOV, efficiency),
            Map.of(GOV, supply),
            DAY,
            365L);
    GovDaily.settle(
        gov,
        units,
        DAY,
        365L,
        Map.of(GOV, efficiency),
        budget.upkeepOracle(new GovernmentUpkeepOracle(accounts, units)));
    PeriodicHouseholdAdjustmentExecutor.Report report =
        PeriodicHouseholdAdjustmentExecutor.applyExplicit(budget.budgetedRules(), accounts, DAY);
    return new SalaryRun(accounts, report, budget.logSalaryExecution(report, DAY));
  }

  private static List<String> alertKinds(GovBudgetExecutionBridge.DayBudget budget) {
    List<String> kinds = new ArrayList<>();
    budget.alerts().forEach(draft -> kinds.add(draft.kind()));
    return kinds;
  }

  private static List<String> shortfallLines(AppLogCapture log) {
    return log.messages().stream()
        .filter(line -> line.contains("GOV_ADMIN_SALARY_SHORTFALL"))
        .toList();
  }

  private static List<String> salaryLines(AppLogCapture log) {
    return log.messages().stream().filter(line -> line.contains("GOV_ADMIN_SALARY_DAY")).toList();
  }

  private static GovState govState(GovAdministrationPlan plan, GovBudgetPolicy policy) {
    return new GovState(
        Map.of(GOV, GovOfficeState.empty(GOV, 0L)), Map.of(GOV, plan), Map.of(GOV, policy));
  }

  private static GovAdministrationPlan adminPlan(long security, long paperwork) {
    return new GovAdministrationPlan(
        security,
        paperwork,
        List.of(
            new GovPostTier("tier-1", 1000, 0),
            new GovPostTier("tier-2", 0, 1000),
            new GovPostTier("tier-3", 500, 500)),
        1000L,
        1000L,
        1000L,
        1000L,
        1L);
  }

  private static GovBudgetPolicy policy(
      List<GovBudgetLine> lines, GovOfficialSalaryRule salaryRule) {
    return new GovBudgetPolicy(lines, salaryRule);
  }

  private static GovBudgetLine line(GovBudgetCategory category, long min, long cap) {
    return new GovBudgetLine(category, min, cap);
  }

  private static GovernmentFormation formationWithUpkeep(
      long grainPerTick, long clothPerCycle, Map<HouseholdId, GovernmentPostOfHousehold> posts) {
    return new GovernmentFormation(
        Map.of(StaffRole.SCRIBE, 1L),
        posts,
        new OfficePolicy(grainPerTick, clothPerCycle, 0L, 0L, Map.of()),
        Optional.empty(),
        GovernmentLevel.PROVINCE,
        Map.of());
  }

  private static GovernmentFormation formationWithTier2() {
    return new GovernmentFormation(
        Map.of(StaffRole.SCRIBE, 2L),
        Map.of(
            HH_UNIT,
            new GovernmentPostOfHousehold(
                HH_UNIT, StaffRole.SCRIBE, GovernmentLevel.PROVINCE, false, "tier-2")),
        new OfficePolicy(83L, 0L, 0L, 0L, Map.of()),
        Optional.empty(),
        GovernmentLevel.PROVINCE,
        Map.of());
  }

  private static GovernmentFormation formationWithTier3() {
    return formationWithTier3For(HH_UNIT);
  }

  private static GovernmentFormation formationWithTier3For(HouseholdId household) {
    return new GovernmentFormation(
        Map.of(StaffRole.POST, 2L),
        Map.of(
            household,
            new GovernmentPostOfHousehold(
                household, StaffRole.POST, GovernmentLevel.PROVINCE, false, "tier-3")),
        new OfficePolicy(83L, 0L, 0L, 0L, Map.of()),
        Optional.empty(),
        GovernmentLevel.PROVINCE,
        Map.of());
  }

  private static GovEfficiency.Efficiency efficiency(GovernmentServiceLaborBridge.Supply supply) {
    return GovEfficiency.of(
        formationWithTier2(),
        Map.of(),
        adminPlan(32_000L, 16_000L),
        supply.securityLaborMilli(),
        supply.paperworkLaborMilli(),
        GovEfficiencyModifier.NEUTRAL_PER_MILLE,
        GovEfficiencyModifier.NEUTRAL_PER_MILLE,
        GovEfficiencyModifier.NEUTRAL_PER_MILLE,
        GovEfficiencyModifier.NEUTRAL_PER_MILLE,
        16_000L);
  }

  private static UnitState unitState() {
    return unitState(formationWithTier2());
  }

  private static UnitState unitState(GovernmentFormation formation) {
    SimosTimestamp t0 = SimosTimestamp.of(0);
    Unit unit =
        new Unit(
            GOV,
            "GOV u-1",
            new SegmentedSeries<>(
                List.of(new Segment<>(t0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(t0, Optional.of(new HexCoord(0, 0)))), List.of(), null),
            List.<CompositionEntry>of(),
            1,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            new SegmentedSeries<>(List.of(new Segment<>(t0, false)), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(t0, Optional.<RelativeOffset>empty())), List.of(), null),
            Optional.empty(),
            1,
            Optional.empty(),
            Optional.of(formation),
            Map.of(),
            List.of(HH_GOV, HH_UNIT));
    return new UnitState(Map.of(unit.id(), unit));
  }

  private static AccountSession accounts(long grain, long cloth, long silver) {
    AccountSession accounts = AccountSession.empty();
    Map<CommodityId, Long> treasuryGoods = new LinkedHashMap<>();
    if (grain > 0L) {
      treasuryGoods.put(GRAIN, grain);
    }
    if (cloth > 0L) {
      treasuryGoods.put(CLOTH, cloth);
    }
    Map<CurrencyId, Long> treasuryMoney = new LinkedHashMap<>();
    if (silver > 0L) {
      treasuryMoney.put(SILVER, silver);
    }
    accounts.registerHousehold(
        HH_GOV, new HexCoord(0, 0), treasuryGoods, treasuryMoney, Map.of(), Map.of());
    accounts.registerHousehold(HH_UNIT, new HexCoord(0, 0), Map.of(), Map.of(), Map.of(), Map.of());
    return accounts;
  }

  private static EconomyData economyWithCommitment(long laborMilli, HouseholdId household) {
    EconomyData base = baseEconomy();
    SocialData social = social();
    EconomyGovUnitUpserts.Projection unit =
        EconomyGovUnitUpserts.project(base, social, upsertPayload());
    EconomyGovServiceCommitments.Projection created =
        EconomyGovServiceCommitments.project(
            unit.projected(), social, commitment(household.value(), laborMilli));
    return created.projected();
  }

  private static EconomyData economyWithoutCommitment() {
    return EconomyGovUnitUpserts.project(baseEconomy(), social(), upsertPayload()).projected();
  }

  private static EconomyData baseEconomy() {
    Industry industry = officeIndustry("office@0_0");
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    GovernmentId id = GovernmentIds.ofUnit("u-1");
    governments.put(id, new Government(id, "u-1", HouseholdActors.of(HH_GOV), Set.of()));
    Map<HouseholdId, HouseholdEconomy> classes = new LinkedHashMap<>();
    classes.put(HH_GOV, householdRow(HH_GOV, 0L, 16_000L, SocialClassId.OFFICIAL));
    classes.put(HH_UNIT, householdRow(HH_UNIT, 10L, 32_000L, SocialClassId.OFFICIAL));
    classes.put(HH_EXT, householdRow(HH_EXT, 5L, 16_000L, SocialClassId.LANDLESS_LABORER));
    EconomyMeta meta = new EconomyMeta("demo", 0L, OptionalLong.empty(), "z6a", Optional.empty());
    return EconomyData.empty()
        .withMeta(Optional.of(meta))
        .withIndustries(Map.of(industry.id(), industry))
        .withHouseholdEconomies(classes)
        .withGovernments(governments);
  }

  private static HouseholdEconomy householdRow(
      HouseholdId id, long population, long laborMilli, SocialClassId stratum) {
    return new HouseholdEconomy(
        id,
        new CohortKey(new HexCoord(0, 0), ResidenceKind.URBAN, stratum),
        population,
        laborMilli,
        1000,
        0L,
        List.of(),
        Map.of(),
        Map.of(),
        0L);
  }

  private static SocialData social() {
    PeopleLotId ruralLot = PeopleLotId.parse("rural:0_0:MALE:1");
    PeopleLotId urbanLot = PeopleLotId.parse("urban:0_0:FEMALE:1");
    Map<HouseholdId, Household> households = new LinkedHashMap<>();
    households.put(HH_GOV, socialHousehold(HH_GOV, "u-1", Map.of(ruralLot, 1L)));
    households.put(HH_UNIT, socialHousehold(HH_UNIT, "u-1", Map.of(ruralLot, 10L)));
    households.put(HH_EXT, socialHousehold(HH_EXT, null, Map.of(urbanLot, 5L)));
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(ruralLot, new PopulationGroup(ruralLot, Sex.MALE, 11L, 30L * 365L, 0L));
    groups.put(urbanLot, new PopulationGroup(urbanLot, Sex.FEMALE, 5L, 30L * 365L, 0L));
    return new SocialData(Map.of(), Map.of(), groups, households, Map.of());
  }

  private static Household socialHousehold(
      HouseholdId id, String unitRef, Map<PeopleLotId, Long> members) {
    HouseholdLocation location =
        unitRef == null
            ? new HouseholdLocation.Hex(new HexCoord(0, 0))
            : new HouseholdLocation.Unit(unitRef);
    return new Household(
        id,
        location,
        new HouseholdProfile("z6a", null, Map.of()),
        members,
        new HouseholdVitalRates(List.of()));
  }

  private static Industry officeIndustry(String id) {
    return new Industry(
        new IndustryId(id),
        "衙署",
        new RegimeId("government_office"),
        30L,
        Map.of(AssetKind.TOOL, 1L),
        Map.of(),
        0L,
        16_000L,
        Map.of(),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.OFFICIAL, "文员", 1000)),
        new AllocationRule.Split(500, 500));
  }

  private static String upsertPayload() {
    return "{\"govUnitId\":\"u-1\",\"industryId\":\"office@0_0\",\"assets\":{\"TOOL\":1},\"reason\":\"z6a\"}";
  }

  private static String commitment(String household, long laborMilli) {
    return "{\"govUnitId\":\"u-1\",\"householdId\":\""
        + household
        + "\",\"laborMilli\":"
        + laborMilli
        + ",\"reason\":\"z6a\"}";
  }
}
