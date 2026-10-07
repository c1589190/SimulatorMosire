package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z7b / run7 D4：逐腿预算账本（requested/authorized/paid/shortfall + PARTIAL/SKIPPED）</b>（设计书
 * §3.2、§8-D4； z7b 台账 §2.4）。
 *
 * <p>装置 = 真 {@link PeriodicHouseholdAdjustmentExecutor#applyBudgeted}（生产唯一执行体）与真 {@link
 * AccountSession}； day122 场景取自 run6 台账：payer 粮 0 / 银 3453、payee 请求粮 300 + 银 30、预算只授权银腿。
 *
 * <ul>
 *   <li>PARTIAL：原始请求四条腿读数齐全；银 30 落账、粮 300 进 shortfall；账户只动授权/可用交集；
 *   <li>全零授权：仍是 due 的一条，SKIPPED 且 gap 具名 {@code budget-authorized-zero}（不是提前消失）；
 *   <li>计数不变量 {@code executed + partial + skipped == due}；
 *   <li>缺额只告警：不自动补款/改计划，payer 余额只减实付。
 * </ul>
 */
class Z7D4BudgetLedgerTest {

  private static final HouseholdId PAYER = HouseholdId.parse("hh-gov-u-1");
  private static final HouseholdId PAYEE = HouseholdId.parse("hh-unit-u-1-a");
  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final long DAY = 122L;

  @Test
  void day122PartialKeepsRequestedAndRecordsEveryLegReading() {
    AccountSession accounts = accounts(0L, 3_453L);
    HouseholdPeriodicAdjustment rule = adjustment("army-pay:unit-capital-guard", 300L, 30L);
    PeriodicHouseholdAdjustmentExecutor.BudgetedRule budgeted =
        new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(rule, Map.of(), Map.of(SILVER, 30L));

    PeriodicHouseholdAdjustmentExecutor.Report report;
    try (io.mosire.simos.app.testing.AppLogCapture log =
        io.mosire.simos.app.testing.AppLogCapture.appTime()) {
      report = PeriodicHouseholdAdjustmentExecutor.applyBudgeted(List.of(budgeted), accounts, DAY);
      assertThat(
              log.hasInfo(
                  "PERIODIC_ADJUSTMENT_RULE",
                  "status=PARTIAL",
                  "gap=partial-payment",
                  "requestedGoods={grain=300}",
                  "shortfallGoods={grain=300}"))
          .as("逐腿账本必须落 INFO（四组读数同形）；实得 %s", log.messages())
          .isTrue();
      assertThat(log.hasInfo("PERIODIC_ADJUSTMENT_DAY", "due=1", "partial=1", "executed=0"))
          .as("当天汇总计数与 PARTIAL 实付同源")
          .isTrue();
    }

    assertThat(report.due()).isEqualTo(1);
    assertThat(report.partial()).as("至少一腿实付 > 0 且原始腿有缺口 ⇒ PARTIAL").isEqualTo(1);
    assertThat(report.executed()).isZero();
    assertThat(report.skipped()).isZero();

    PeriodicHouseholdAdjustmentExecutor.RuleReadout readout = report.rules().get(0);
    assertThat(readout.status())
        .isEqualTo(PeriodicHouseholdAdjustmentExecutor.RuleReadout.Status.PARTIAL);
    assertThat(readout.gap()).isEqualTo("partial-payment");
    assertThat(readout.requestedGoods()).containsExactly(Map.entry(GRAIN, 300L));
    assertThat(readout.requestedMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(readout.authorizedGoods()).as("粮腿授权 0（原始请求仍在 requestedGoods）").isEmpty();
    assertThat(readout.authorizedMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(readout.paidGoods()).isEmpty();
    assertThat(readout.paidMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(readout.shortfallGoods()).containsExactly(Map.entry(GRAIN, 300L));
    assertThat(readout.shortfallMoney()).isEmpty();

    assertThat(report.paidMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(report.shortfallGoods()).containsExactly(Map.entry(GRAIN, 300L));
    assertThat(accounts.householdAccount(PAYER).money().get(SILVER))
        .as("缺额只告警：payer 只减实付 30，不自动补")
        .isEqualTo(3_423L);
    assertThat(accounts.householdAccount(PAYEE).money().get(SILVER)).isEqualTo(30L);
  }

  @Test
  void allZeroAuthorizationIsStillDueAndSkippedByName() {
    AccountSession accounts = accounts(1_000L, 1_000L);
    HouseholdPeriodicAdjustment rule = adjustment("gov-upkeep:u-1", 300L, 30L);
    PeriodicHouseholdAdjustmentExecutor.BudgetedRule budgeted =
        new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(rule, Map.of(), Map.of());

    PeriodicHouseholdAdjustmentExecutor.Report report =
        PeriodicHouseholdAdjustmentExecutor.applyBudgeted(List.of(budgeted), accounts, DAY);

    assertThat(report.due()).as("全零授权也照常进入执行器（不再在预算桥提前消失）").isEqualTo(1);
    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.executed()).isZero();
    assertThat(report.partial()).isZero();

    PeriodicHouseholdAdjustmentExecutor.RuleReadout readout = report.rules().get(0);
    assertThat(readout.status())
        .isEqualTo(PeriodicHouseholdAdjustmentExecutor.RuleReadout.Status.SKIPPED);
    assertThat(readout.gap()).isEqualTo("budget-authorized-zero");
    assertThat(readout.requestedGoods()).containsExactly(Map.entry(GRAIN, 300L));
    assertThat(readout.requestedMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(readout.authorizedGoods()).isEmpty();
    assertThat(readout.authorizedMoney()).isEmpty();
    assertThat(readout.paidGoods()).isEmpty();
    assertThat(readout.paidMoney()).isEmpty();
    assertThat(readout.shortfallGoods()).containsExactly(Map.entry(GRAIN, 300L));
    assertThat(readout.shortfallMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(report.gaps()).containsExactly("gov-upkeep:u-1: budget-authorized-zero");

    assertThat(accounts.householdAccount(PAYER).goods().get(GRAIN)).isEqualTo(1_000L);
    assertThat(accounts.householdAccount(PAYER).money().get(SILVER)).isEqualTo(1_000L);
  }

  @Test
  void mixedStatusesSatisfyExecutedPlusPartialPlusSkippedEqualsDue() {
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        HouseholdId.parse("hh-p1"),
        new HexCoord(0, 0),
        Map.of(GRAIN, 1_000L),
        Map.of(SILVER, 1_000L),
        Map.of(),
        Map.of());
    accounts.registerHousehold(
        HouseholdId.parse("hh-p2"),
        new HexCoord(0, 0),
        Map.of(GRAIN, 1_000L),
        Map.of(),
        Map.of(),
        Map.of());
    accounts.registerHousehold(
        HouseholdId.parse("hh-p3"),
        new HexCoord(0, 0),
        Map.of(GRAIN, 1_000L),
        Map.of(),
        Map.of(),
        Map.of());
    accounts.registerHousehold(PAYEE, new HexCoord(0, 0), Map.of(), Map.of(), Map.of(), Map.of());

    HouseholdPeriodicAdjustment executed = payer("z7-executed", "hh-p1", 300L, 30L);
    HouseholdPeriodicAdjustment partial = payer("z7-partial", "hh-p2", 300L, 0L);
    HouseholdPeriodicAdjustment skipped = payer("z7-skipped", "hh-p3", 300L, 0L);

    PeriodicHouseholdAdjustmentExecutor.Report report =
        PeriodicHouseholdAdjustmentExecutor.applyBudgeted(
            List.of(
                new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(
                    executed, Map.of(GRAIN, 300L), Map.of(SILVER, 30L)),
                new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(
                    partial, Map.of(GRAIN, 100L), Map.of()),
                new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(skipped, Map.of(), Map.of())),
            accounts,
            DAY);

    assertThat(report.due()).isEqualTo(3);
    assertThat(report.executed()).isEqualTo(1);
    assertThat(report.partial()).isEqualTo(1);
    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.executed() + report.partial() + report.skipped())
        .as("计数不变量")
        .isEqualTo(report.due());
    assertThat(report.rules())
        .extracting(readout -> readout.status())
        .containsExactly(
            PeriodicHouseholdAdjustmentExecutor.RuleReadout.Status.EXECUTED,
            PeriodicHouseholdAdjustmentExecutor.RuleReadout.Status.PARTIAL,
            PeriodicHouseholdAdjustmentExecutor.RuleReadout.Status.SKIPPED);
    assertThat(report.paidGoods())
        .as("PARTIAL 的实付也进当日汇总（旧实现会漏）")
        .containsExactly(Map.entry(GRAIN, 400L));
    assertThat(report.paidMoney()).containsExactly(Map.entry(SILVER, 30L));
    assertThat(report.shortfallGoods())
        .as("SKIPPED 也按原始请求记全缺口 ⇒ 200（partial）+ 300（skipped）= 500")
        .containsExactly(Map.entry(GRAIN, 500L));
    assertThat(report.gaps()).containsExactly("z7-skipped: budget-authorized-zero");
  }

  private static HouseholdPeriodicAdjustment payer(
      String id, String payerId, long grain, long silver) {
    Map<CommodityId, Long> goods = grain > 0L ? Map.of(GRAIN, grain) : Map.of();
    Map<CurrencyId, Long> money = silver > 0L ? Map.of(SILVER, silver) : Map.of();
    return new HouseholdPeriodicAdjustment(
        new PeriodicHouseholdAdjustmentId(id),
        HouseholdId.parse(payerId),
        Optional.of(PAYEE),
        goods,
        money,
        DeductionReason.ADMIN_SALARY,
        1L,
        0L,
        0L,
        OptionalLong.empty(),
        "z7e1-test");
  }

  private static HouseholdPeriodicAdjustment adjustment(String id, long grain, long silver) {
    return new HouseholdPeriodicAdjustment(
        new PeriodicHouseholdAdjustmentId(id),
        PAYER,
        Optional.of(PAYEE),
        Map.of(GRAIN, grain),
        Map.of(SILVER, silver),
        DeductionReason.MILITARY_SALARY,
        1L,
        0L,
        0L,
        OptionalLong.empty(),
        "z7e1-test");
  }

  private static AccountSession accounts(long payerGrain, long payerSilver) {
    AccountSession accounts = AccountSession.empty();
    Map<CommodityId, Long> goods = payerGrain > 0L ? Map.of(GRAIN, payerGrain) : Map.of();
    Map<CurrencyId, Long> money = payerSilver > 0L ? Map.of(SILVER, payerSilver) : Map.of();
    accounts.registerHousehold(PAYER, new HexCoord(0, 0), goods, money, Map.of(), Map.of());
    accounts.registerHousehold(PAYEE, new HexCoord(0, 0), Map.of(), Map.of(), Map.of(), Map.of());
    return accounts;
  }
}
