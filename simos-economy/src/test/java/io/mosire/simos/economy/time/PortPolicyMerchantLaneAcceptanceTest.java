package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5.3 的 J1 / J3 判据（政策 → 商户行为端到端）</b>
 * （`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §5.3 / §2.2 / §2.4）：
 *
 * <pre>
 * J1  某区对该类设限 ⇒ 该 lane 上**根本不出现**该商品的候选（具名 PORT_THROTTLED），**不是「利润变差」**
 * J3  无政策世界 ⇒ 商户侧读数逐值等于改前（I-C2 的商户侧对应物）：政策门控族零输出 + 运力/利润读数一致
 * </pre>
 *
 * <p>★ <b>"商户侧"在本计划的现行冻结口径下是什么</b>（§2.4 M0/M0c/M2、V-19/V-24：商号行已退役 ⇒ 运力 = 派生量）： 可读的商户面 = ① 逐 hex
 * <b>运力池</b>（总量 / 轮末余量）② 逐 lane 的 <b>路线读数</b>（{@code MarketReport.routes()}： 运力窗口、用量、瓶颈标志）③
 * 逐卖方/买方的报价与成交读数。商号利润簿（{@code MerchantProfitBook}）是 {@code MatchContext}
 * 内部的逐轮瞬态、**不进任何公开读数**，本类不虚报它（见账本"没做的"一节）。
 *
 * <p>★ <b>夹具</b>：zone-a（H1：卖方 + 承运家户）、zone-b（H2：买方）；运力充足（运力 ≫ 过境量）。 政策只注入**商品管制力**这一面（未注入 = 改前行为）。
 */
class PortPolicyMerchantLaneAcceptanceTest {

  private static final String ZONE_A = "zone-a";
  private static final String ZONE_B = "zone-b";

  private static final HouseholdId SELLER = HouseholdId.parse("hh-lane-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-lane-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-lane-carrier");

  private static final long SELLER_GRAIN = 200_000L;
  private static final long BUYER_SILVER = 10_000_000L;
  private static final long CARRIER_LABOR = 10_000_000L;

  private static MarketSettlementFixtures.World twoZoneWorld() {
    return MarketSettlementFixtures.builder()
        .region(ZONE_A, MarketSettlementFixtures.H1)
        .region(ZONE_B, MarketSettlementFixtures.H2)
        .market(MarketSettlementFixtures.H1, 10L)
        .market(MarketSettlementFixtures.H2, 10L)
        .household(SELLER, MarketSettlementFixtures.H1, 1L, SELLER_GRAIN, 0L)
        .household(BUYER, MarketSettlementFixtures.H2, 1L, 0L, BUYER_SILVER)
        .carrier(CARRIER, MarketSettlementFixtures.H1, CARRIER_LABOR, 10_000L)
        .build();
  }

  /** ★ zone-a 对 grain 的**出口**管制力 1000‰ ⇒ 开放度 0（完全禁出）；另一侧不设 ⇒ 开放度 1000‰。 */
  private static PortEnforcementInput exitClosedForGrain() {
    return new PortEnforcementInput(
        Map.of(),
        Map.of(
            ZONE_A,
            Map.of(
                MarketSettlementFixtures.GRAIN, new PortEnforcementInput.Directional(0L, 1_000L))));
  }

  /** ★ 显式"两侧全开"（管制力 0 ⇒ 开放度 1000‰）× 两个区 ⇒ 政策表非空、但一个数都不该变。 */
  private static PortEnforcementInput bothSidesFullyOpen() {
    return new PortEnforcementInput(
        Map.of(),
        Map.of(
            ZONE_A,
            Map.of(MarketSettlementFixtures.GRAIN, PortEnforcementInput.Directional.NONE),
            ZONE_B,
            Map.of(MarketSettlementFixtures.GRAIN, PortEnforcementInput.Directional.NONE)));
  }

  private static MarketSettlement.MarketOutcome settle(
      MarketSettlementFixtures.World world,
      MerchantCapacityPool pool,
      PortEnforcementInput enforcement) {
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    MarketSettlementFixtures.Round injected =
        MarketSettlementFixtures.withPortInputs(round, enforcement, null, null);
    return MarketSettlementFixtures.settleWithPool(world, injected, pool);
  }

  /** 本轮的 lane 读数（H1 → H2 × grain）；政策把它整条拦下时仍然有读数，但用量为 0。 */
  private static List<MarketReport.RouteUsage> grainLanes(MarketSettlement.MarketOutcome outcome) {
    return outcome.report().routes().stream()
        .filter(r -> r.commodity().equals(MarketSettlementFixtures.GRAIN))
        .filter(r -> r.from().equals(MarketSettlementFixtures.H1))
        .filter(r -> r.to().equals(MarketSettlementFixtures.H2))
        .toList();
  }

  // ── J1：政策 ⇒ 该 lane 根本不出现候选（具名 PORT_THROTTLED） ───────────────────────────

  /**
   * ★★ <b>J1</b>：把 zone-a 的 grain 出口完全关掉 ⇒ 同一 lane 上**一笔候选都不成交**、运力**一分未用**、 归因是具名的 {@code
   * PORT_THROTTLED} —— 而不是"价格/成本排序变差"（卖方报价与成本读数逐值不变）。
   *
   * <p>★ "不是利润变差"的判据形态：卖方仍然挂着同样的量、同样的价、同样的成本档（供给面一个数没动）， 但这条 lane 的**用量 =
   * 0**；同时运力池的余量保持满额（若被"低价抢单"式地吃掉，这里就会减少）。
   */
  @Test
  void aZoneRestrictionRemovesTheLaneCandidatesInsteadOfWorseningProfit() {
    // ── 基准：无政策 ⇒ 这条 lane 真的在运货、真的吃运力 ────────────────────────────────
    MarketSettlementFixtures.World baselineWorld = twoZoneWorld();
    MerchantCapacityPool baselinePool = baselineWorld.carrierPool();
    long capacity = baselinePool.totalCapacityAt(MarketSettlementFixtures.H1);
    MarketSettlement.MarketOutcome baseline = settle(baselineWorld, baselinePool, null);

    assertThat(baseline.report().fills()).as("基准：跨区成交发生").hasSize(1);
    long shipped = baseline.report().fills().get(0).quantity();
    assertThat(grainLanes(baseline)).as("基准：lane 读数就是这一条，且真的被用过").hasSize(1);
    assertThat(grainLanes(baseline).get(0).used()).as("lane 用量 = 成交毛量").isEqualTo(shipped);
    assertThat(baselinePool.remainingCapacityAt(MarketSettlementFixtures.H1))
        .as("★ 基准轮真的占了运力（否则后面的「一分未用」不可区分）")
        .isLessThan(capacity);
    long baselineOffered = baseline.report().sellerOutcomes().get(0).offeredQty();
    long baselineUnitPrice = baseline.report().sellerOutcomes().get(0).unitPriceMilli();

    // ── 政策：区 A 对 grain 禁出 ⇒ 同一 lane 零候选 ─────────────────────────────────
    MarketSettlementFixtures.World gatedWorld = twoZoneWorld();
    MerchantCapacityPool gatedPool = gatedWorld.carrierPool();
    MarketSettlement.MarketOutcome gated = settle(gatedWorld, gatedPool, exitClosedForGrain());

    assertThat(gated.report().fills()).as("★ J1：被管住的那类根本不成候选 ⇒ 零成交").isEmpty();
    assertThat(gated.report().unfilledReasonCounts())
        .as("★ 具名 PORT_THROTTLED（不是静默、不是「没货」）")
        .containsKey(MarketUnfilledReason.PORT_THROTTLED);
    assertThat(gated.report().unfilledReasonCounts())
        .as("★ 与运力不足互不冒充（本世界运力充足）")
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);
    assertThat(gated.report().sellerOutcomes().get(0).unfilledReason())
        .as("★ 卖方侧也具名 PORT_THROTTLED（两侧都点名）")
        .contains(MarketUnfilledReason.PORT_THROTTLED);

    assertThat(grainLanes(gated)).as("lane 读数仍在（不删卖单本身 §10.3），但…").hasSize(1);
    assertThat(grainLanes(gated).get(0).used()).as("★ …用量 = 0：该 lane 上一个候选都没成交").isZero();
    assertThat(grainLanes(gated).get(0).bottleneck()).as("★ 不是「运力不够」（瓶颈标志 false）").isFalse();
    assertThat(gatedPool.remainingCapacityAt(MarketSettlementFixtures.H1))
        .as("★ 运力一分未用 —— 政策不是在「利润」这一层筛选，而是在候选生成处就把这条路断掉")
        .isEqualTo(capacity);

    // ★ 供给面逐值不变 ⇒ 不是"利润变差"。
    assertThat(gated.report().sellerOutcomes().get(0).offeredQty())
        .as("★ 卖方仍挂同样的量（政策没动供给面）")
        .isEqualTo(baselineOffered);
    assertThat(gated.report().sellerOutcomes().get(0).unitPriceMilli())
        .as("★ 卖方报价逐值不变（不是「被更便宜的挤掉」）")
        .isEqualTo(baselineUnitPrice);
    assertThat(gatedWorld.grainOf(BUYER)).as("买方一粒粮都没到（货真的没动）").isZero();
    assertThat(gatedWorld.grainOf(SELLER)).as("卖方一粒粮都没少").isEqualTo(SELLER_GRAIN);
  }

  // ── J3：无政策世界 ⇒ 商户侧逐值不变（政策门控族零输出） ───────────────────────────────

  /**
   * ★★ <b>J3</b>：把"显式两侧全开"的政策面注入同一个世界 ⇒ 商户侧读数**逐值等于**根本不注入的那一轮；
   * 且两轮的政策门控族读数都是**零输出**（无归因、无税项、无按币税表）。
   *
   * <p>★ 逐值对照的口径（全部来自公开读数）：成交（量/单价/货款/运费/损耗/币种）、lane（窗口/用量/供需/瓶颈）、 运力池（总量/余量）、卖方与买方读数、价格表（本轮改价记录）。
   */
  @Test
  void anUnrestrictedWorldLeavesEveryMerchantSideReadingValueIdentical() {
    MarketSettlementFixtures.World noPolicyWorld = twoZoneWorld();
    MerchantCapacityPool noPolicyPool = noPolicyWorld.carrierPool();
    MarketSettlement.MarketOutcome noPolicy = settle(noPolicyWorld, noPolicyPool, null);

    MarketSettlementFixtures.World openPolicyWorld = twoZoneWorld();
    MerchantCapacityPool openPolicyPool = openPolicyWorld.carrierPool();
    MarketSettlement.MarketOutcome openPolicy =
        settle(openPolicyWorld, openPolicyPool, bothSidesFullyOpen());

    // ── ① 政策门控族零输出（两侧都不设限/不设税 ⇒ 一条都不许出现） ────────────────────────
    for (MarketSettlement.MarketOutcome outcome : List.of(noPolicy, openPolicy)) {
      assertThat(outcome.report().taxItems()).as("零税项（未设税 ⇒ I-C2）").isEmpty();
      assertThat(outcome.report().taxByCurrency()).as("按币税表零输出").isEmpty();
      assertThat(outcome.report().taxByLayerGovernmentCurrency()).as("三层税读数零输出").isEmpty();
      assertThat(outcome.report().regulatedTariffByCurrency()).as("区内调控税读数零输出").isEmpty();
      assertThat(outcome.report().unfilledReasonCounts())
          .as("政策门控族归因零输出（PORT_THROTTLED / CURRENCY_NOT_ACCEPTED 都不许出现）")
          .doesNotContainKey(MarketUnfilledReason.PORT_THROTTLED)
          .doesNotContainKey(MarketUnfilledReason.CURRENCY_NOT_ACCEPTED);
    }

    // ── ② 商户侧：成交逐值 ────────────────────────────────────────────────────────
    assertThat(noPolicy.report().fills()).as("基准有成交（否则对照无意义）").hasSize(1);
    assertThat(openPolicy.report().fills()).as("显式全开 ⇒ 仍然成交").hasSize(1);
    MarketReport.Fill a = noPolicy.report().fills().get(0);
    MarketReport.Fill b = openPolicy.report().fills().get(0);
    assertThat(b.quantity()).isEqualTo(a.quantity());
    assertThat(b.unitPriceMilli()).isEqualTo(a.unitPriceMilli());
    assertThat(b.goodsPaymentMilli()).isEqualTo(a.goodsPaymentMilli());
    assertThat(b.freightMilli()).isEqualTo(a.freightMilli());
    assertThat(b.lossMilli()).isEqualTo(a.lossMilli());
    assertThat(b.paymentCurrency()).isEqualTo(a.paymentCurrency());
    assertThat(b.unitCurrency()).isEqualTo(a.unitCurrency());
    assertThat(openPolicyWorld.silverOf(BUYER)).isEqualTo(noPolicyWorld.silverOf(BUYER));
    assertThat(openPolicyWorld.silverOf(SELLER)).isEqualTo(noPolicyWorld.silverOf(SELLER));
    assertThat(openPolicyWorld.silverOf(CARRIER)).isEqualTo(noPolicyWorld.silverOf(CARRIER));
    assertThat(openPolicyWorld.grainOf(BUYER)).isEqualTo(noPolicyWorld.grainOf(BUYER));

    // ── ③ 商户侧：lane 读数 + 运力池读数逐值 ────────────────────────────────────────
    assertThat(grainLanes(openPolicy)).hasSize(grainLanes(noPolicy).size());
    MarketReport.RouteUsage laneA = grainLanes(noPolicy).get(0);
    MarketReport.RouteUsage laneB = grainLanes(openPolicy).get(0);
    assertThat(laneB.capacityPerWindow()).isEqualTo(laneA.capacityPerWindow());
    assertThat(laneB.costPerUnit()).isEqualTo(laneA.costPerUnit());
    assertThat(laneB.used()).as("★ 运力用量逐值（商户侧读数）").isEqualTo(laneA.used());
    assertThat(laneB.demandMilli()).isEqualTo(laneA.demandMilli());
    assertThat(laneB.supplyMilli()).isEqualTo(laneA.supplyMilli());
    assertThat(laneB.bottleneck()).isEqualTo(laneA.bottleneck());
    assertThat(openPolicyPool.totalCapacityAt(MarketSettlementFixtures.H1))
        .isEqualTo(noPolicyPool.totalCapacityAt(MarketSettlementFixtures.H1));
    assertThat(openPolicyPool.remainingCapacityAt(MarketSettlementFixtures.H1))
        .as("★ 轮末运力余量逐值（商户侧读数）")
        .isEqualTo(noPolicyPool.remainingCapacityAt(MarketSettlementFixtures.H1));
    assertThat(openPolicyPool.toolBlockedRuns()).isEqualTo(noPolicyPool.toolBlockedRuns());

    // ── ④ 商户侧：卖方/买方读数 + 价格表逐值 ────────────────────────────────────────
    assertThat(openPolicy.report().sellerOutcomes())
        .hasSameSizeAs(noPolicy.report().sellerOutcomes());
    MarketReport.SellerOutcome sellerA = noPolicy.report().sellerOutcomes().get(0);
    MarketReport.SellerOutcome sellerB = openPolicy.report().sellerOutcomes().get(0);
    assertThat(sellerB.offeredQty()).isEqualTo(sellerA.offeredQty());
    assertThat(sellerB.filledQty()).isEqualTo(sellerA.filledQty());
    assertThat(sellerB.unfilledQty()).isEqualTo(sellerA.unfilledQty());
    assertThat(sellerB.unitPriceMilli()).isEqualTo(sellerA.unitPriceMilli());
    assertThat(sellerB.costRank()).isEqualTo(sellerA.costRank());
    assertThat(sellerB.unitCostEstimateMilli()).isEqualTo(sellerA.unitCostEstimateMilli());
    assertThat(openPolicy.report().unfilledReasonCounts())
        .as("未成交归因分布逐值（= 不含政策门控族）")
        .isEqualTo(noPolicy.report().unfilledReasonCounts());
    assertThat(openPolicy.report().priceUpdates()).isEqualTo(noPolicy.report().priceUpdates());
    assertThat(openPolicy.markets().get(MarketSettlementFixtures.H1).prices())
        .as("下一轮价格表逐值")
        .isEqualTo(noPolicy.markets().get(MarketSettlementFixtures.H1).prices());
  }
}
