package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W4（D-032 D3 §2.5.1/§8.4 P41/P46）Social 下一 tick 生存投影与 fail-closed 判据</b>。
 *
 * <ul>
 *   <li>缺下一 tick 需求 ⇒ {@code survival-need-missing}（不是“无需求”空表）；
 *   <li>缺劳动预算 ⇒ {@code survival-income-projection-missing}（不是 0）；
 *   <li>陈旧需求/负预算 ⇒ {@code survival-need-stale}/{@code survival-input-invalid}；
 *   <li>缺市场事实/缺价 ⇒ 具名 fail-closed 或覆盖率下降，<b>不得</b>按 0 价/1:1 记满；
 *   <li>{@code idleEligible} 只是资格，{@code idleChosen} 必须由策略选择单独给出。
 * </ul>
 */
class W4SurvivalFactsFailClosedTest {

  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-w4-survival");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final SubsistenceNeedKey NEED_KEY = new SubsistenceNeedKey("food");

  @Test
  void unavailableProjectionNamesMissingNeedAndBudgetInsteadOfPretendingEmpty() {
    SurvivalSocialFacts unavailable = SurvivalSocialFacts.unavailable();

    assertThat(unavailable.available()).isFalse();
    assertThatThrownBy(() -> unavailable.requireNeed(HOUSEHOLD))
        .isInstanceOf(SurvivalOutlookException.class)
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-need-missing"));
    assertThatThrownBy(() -> unavailable.requireLaborBudgetMilli(HOUSEHOLD))
        .isInstanceOf(SurvivalOutlookException.class)
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-income-projection-missing"));
  }

  @Test
  void explicitEmptyAvailableProjectionIsNotTheUnavailableSentinel() {
    SurvivalSocialFacts emptyButAvailable = SurvivalSocialFacts.of(5L, Map.of(), Map.of());

    assertThat(emptyButAvailable.available()).as("Social 明确给出“无需求/无劳动”是可用投影，不是不可用哨兵").isTrue();
    assertThat(emptyButAvailable.nextTickNeeds()).isEmpty();
    assertThat(emptyButAvailable.nextTickLaborBudgetMilli()).isEmpty();
    assertThatThrownBy(() -> emptyButAvailable.requireNeed(HOUSEHOLD))
        .as("可用投影里缺该户需求仍是具名失败，不得静默当空需求")
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-need-missing"));
    assertThatThrownBy(() -> emptyButAvailable.requireLaborBudgetMilli(HOUSEHOLD))
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-income-projection-missing"));
  }

  @Test
  void staleNeedAndNegativeBudgetFailClosedAtConstruction() {
    NextTickSubsistenceNeed stale = new NextTickSubsistenceNeed(4L, List.of(needLine(GRAIN, 10L)));
    assertThatThrownBy(() -> SurvivalSocialFacts.of(5L, Map.of(HOUSEHOLD, stale), Map.of()))
        .isInstanceOf(SurvivalOutlookException.class)
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-need-stale"));

    assertThatThrownBy(
            () ->
                SurvivalSocialFacts.of(
                    5L,
                    Map.of(HOUSEHOLD, new NextTickSubsistenceNeed(5L, List.of())),
                    Map.of(HOUSEHOLD, -1L)))
        .isInstanceOf(SurvivalOutlookException.class)
        .satisfies(
            failure ->
                assertThat(((SurvivalOutlookException) failure).reason())
                    .isEqualTo("survival-input-invalid"));
  }

  @Test
  void missingMarketFactsFailClosedWithNamedReason() {
    NextTickSubsistenceNeed need = new NextTickSubsistenceNeed(1L, List.of(needLine(GRAIN, 10L)));
    assertThatThrownBy(
            () ->
                new SurvivalOutlookRequest(
                    HOUSEHOLD,
                    need,
                    new SurvivalResourceFacts(Map.of(), Map.of()),
                    1_000L,
                    List.of(),
                    null,
                    new ExistingClaims(List.of(), Set.of()),
                    SurvivalOutlookEvaluator.DEFAULT_MAX_COMBINATIONS))
        .as("W4/MARKET：缺当地可达事实不得当空表继续算")
        .hasMessageContaining("survival-market-facts-missing");
  }

  /**
   * P46：缺价/不可达/0 价不得按 0 价或 1:1 记满。
   *
   * <pre>
   * 需求 10；当前库存 4；货币 10；grain 不可达 ⇒ coverage = 4/10 = 400‰，idleEligible=true
   * </pre>
   */
  @Test
  void unreachablePriceDoesNotPretendCoverageIsFull() {
    NextTickSubsistenceNeed need = new NextTickSubsistenceNeed(1L, List.of(needLine(GRAIN, 10L)));
    SurvivalOutlookRequest request =
        new SurvivalOutlookRequest(
            HOUSEHOLD,
            need,
            new SurvivalResourceFacts(Map.of(GRAIN, 4L), Map.of(SILVER, 10L)),
            1_000L,
            List.of(),
            new LocalReachabilityFacts(
                Map.of(GRAIN, new LocalCommodityReachability(SILVER, false, 0L, 0L))),
            new ExistingClaims(List.of(), Set.of()),
            SurvivalOutlookEvaluator.DEFAULT_MAX_COMBINATIONS);

    SurvivalOutlook outlook = SurvivalOutlookEvaluator.standard().evaluate(request);

    assertThat(outlook.bestAchievableCoverage().bottleneckCoveragePerMille())
        .as("4/10 = 400‰；缺价不得按 0 元或 1:1 记满")
        .isEqualTo(400L);
    assertThat(outlook.idleEligible()).isTrue();
    assertThat(outlook.reason())
        .as("无任何可行替代 ⇒ 具名 no-feasible-livelihood；仍只置 idleEligible，不置 idleChosen")
        .isEqualTo(SurvivalOutlookReason.NO_FEASIBLE_LIVELIHOOD);
    SurvivalCoverageLine line = outlook.bestAchievableCoverage().provenance().get(NEED_KEY);
    assertThat(line.fromInventoryMilli()).isEqualTo(4L);
    assertThat(line.fromMoneyMilli()).as("不可达 ⇒ 货币腿一分不计").isZero();
  }

  /**
   * U23-b / D3-I20：`idleEligible` 是资格读数，`idleChosen` 必须由策略选择单独给出 —— 同一 outlook 在两档策略下
   * 可以一个选择闲置、一个照常工作。
   */
  @Test
  void idleEligibleAndIdleChosenAreSeparatedByThePolicy() {
    NextTickSubsistenceNeed need =
        new NextTickSubsistenceNeed(1L, List.of(needLine(GRAIN, 10_000L)));
    SurvivalOutlookRequest request =
        new SurvivalOutlookRequest(
            HOUSEHOLD,
            need,
            new SurvivalResourceFacts(Map.of(), Map.of()),
            1_000L,
            List.of(
                new LivelihoodAlternative(
                    new LivelihoodAlternativeId("wage-work"),
                    1_000L,
                    Set.of(new ResourceClaimKey("labor:1")),
                    1L,
                    Map.of(SILVER, 1_000L),
                    Map.of(),
                    Map.of())),
            new LocalReachabilityFacts(
                Map.of(GRAIN, new LocalCommodityReachability(SILVER, true, 1_000L, 10_000L))),
            new ExistingClaims(List.of(), Set.of()),
            SurvivalOutlookEvaluator.DEFAULT_MAX_COMBINATIONS);
    SurvivalOutlook outlook = SurvivalOutlookEvaluator.standard().evaluate(request);

    assertThat(outlook.idleEligible()).as("预计收入只能覆盖 10,000 需求中的 1,000（100‰）⇒ 生存不足 ⇒ 资格成立").isTrue();
    assertThat(outlook.reason()).isEqualTo(SurvivalOutlookReason.SUBSISTENCE_SHORTFALL);

    ProductionUnitId unit = new ProductionUnitId("unit-w4-idle");
    ProductionRoleId role = ProductionRoleId.parse("hired-hand");
    LaborQueueBook.Offer positiveOffer =
        new LaborQueueBook.Offer(
            unit,
            role,
            Optional.empty(),
            "rank-w4",
            1_000L,
            true,
            2_000L,
            0L,
            2_000L,
            1_000L,
            2_000_000L,
            "");

    LaborQueueBook.Plan preferIdle =
        LaborQueueBook.plan(
            HOUSEHOLD,
            1_000L,
            0L,
            List.of(positiveOffer),
            IdleOption.of(outlook, SurvivalIdlePolicy.PREFER_IDLE_WHEN_SUBSISTENCE_UNMET));
    assertThat(preferIdle.idleChosen()).as("默认策略：资格成立 ⇒ 选择 idle；资格与选择是两件事").isTrue();
    assertThat(preferIdle.decisions())
        .extracting(LaborQueueBook.Decision::outcome)
        .containsExactly("IDLE_FOREGONE_POSITIVE");

    LaborQueueBook.Plan bestEffort =
        LaborQueueBook.plan(
            HOUSEHOLD,
            1_000L,
            0L,
            List.of(positiveOffer),
            IdleOption.of(outlook, SurvivalIdlePolicy.BEST_EFFORT_WORK));
    assertThat(bestEffort.idleChosen())
        .as("BEST_EFFORT：正收益工作先做 ⇒ 同一 outlook 下 idleChosen=false")
        .isFalse();
    assertThat(bestEffort.decisions())
        .extracting(LaborQueueBook.Decision::outcome)
        .containsExactly("GRANTED");
    assertThat(bestEffort.allocatedLaborMilli()).isEqualTo(1_000L);
  }

  private static SubsistenceNeedLine needLine(CommodityId commodity, long requiredMilli) {
    return new SubsistenceNeedLine(NEED_KEY, commodity, requiredMilli);
  }
}
