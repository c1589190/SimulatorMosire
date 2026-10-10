package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 的 T6
 * 判据（订单可选币）</b>：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.1（3c 冻结契约）+
 * §5.1。冻结口径逐条：
 *
 * <pre>
 * ① 订单**不带币**（缺省）⇒ 订单币 = 本格计价币 ⇒ 与改前**逐值相同**（I-C2 的缺省语义中性）
 * ② 订单**指定非本格支付币** ⇒ 钱腿 / 冻结 / 预算**都按该币**（不再强制本格币）
 * ③ 价格尺度不动：单价与参考价仍是**卖方格计价币**（跨币金额经同一份 CurrencyValuation 折算一次）
 * </pre>
 *
 * <p>★ <b>缺省那一半为什么用三个世界对照</b>（①）：光看"单币世界用银付"证明不了缺省语义 —— 要证明"缺省 = 本格计价币"必须让**外币真的存在**：
 *
 * <pre>
 * 世界 A：只有 silver 一个市场（连"另一种币"都不存在）
 * 世界 B：多一个 copper 法定区（grain 在铜区便宜 ⇒ 铜"本该最强"），但买方**不持有**铜 ⇒ 不可比 ⇒ 回落本格币
 * 世界 C：同样多一个 copper 区，买方**持有**铜，但**银更强**（铜区的粮更贵）⇒ 选出来就是本格币
 * ⇒ 三个世界的成交读数、钱腿、冻结币种必须**逐值相同**（世界 A == B == C）
 * </pre>
 *
 * <p>★ ② 的判别力来自"买方两种币的余额刻意不对称"：银很多、铜刚好只够一半 ⇒ 若预算/冻结读的是银（本格币）， 成交会是整笔 2,905；读的是铜 ⇒ 恰好 1,452。用例两侧都断言。
 */
class MarketPayCurrencyAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;
  private static final CurrencyId COPPER = new CurrencyId("copper");

  private static final HouseholdId SELLER = HouseholdId.parse("hh-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-buyer");

  private static final long SELLER_GRAIN = 4_000L;
  private static final long BUYER_SILVER = 1_000_000L;
  private static final long PRICE_MILLI = 10_000L;

  /** 生活保留（买方的目标缺口）= 1 人 1 天口粮 × 35 天窗口 —— 夹具算法与生产路径同源。 */
  private static final long DEMAND = MarketSettlementFixtures.lifeReserveGrain(1L);

  private static Market silverMarket() {
    return new Market(SILVER, new LinkedHashMap<>(Map.of(GRAIN, PRICE_MILLI)));
  }

  private static Market copperMarket(long grainPrice) {
    return new Market(COPPER, new LinkedHashMap<>(Map.of(GRAIN, grainPrice)));
  }

  /** 世界 A：单币世界（没有第二个市场、买方只有银）。 */
  private static MarketSettlementFixtures.World singleCurrencyWorld() {
    return MarketSettlementFixtures.builder()
        .market(H1, silverMarket())
        .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
        .household(BUYER, H1, 1L, 0L, BUYER_SILVER)
        .build();
  }

  /** 世界 B/C：两个市场区（zone-a 银、zone-b 铜）；买方按 {@code buyerMoney} 持币。 */
  private static MarketSettlementFixtures.World twoZoneWorld(
      long copperZoneGrainPrice, Map<CurrencyId, Long> buyerMoney) {
    return MarketSettlementFixtures.builder()
        .region("zone-a", H1)
        .region("zone-b", H2)
        .market(H1, silverMarket())
        .market(H2, copperMarket(copperZoneGrainPrice))
        .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
        .household(
            BUYER,
            H1,
            1L,
            Map.of(GRAIN, 0L),
            buyerMoney,
            Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
        .build();
  }

  private static MarketSettlement.MarketOutcome settle(MarketSettlementFixtures.World world) {
    return MarketSettlementFixtures.settle(
        world,
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets())));
  }

  /** 向上取整（与结算侧"买方币货款"同一条算式口径；此处只是把判据写成算式，不引用私有方法）。 */
  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }

  private static long moneyOf(MarketSettlementFixtures.World world, HouseholdId id, CurrencyId c) {
    return world.money().getOrDefault(id, Map.of()).getOrDefault(c, 0L);
  }

  // ── ① 缺省：订单币 = 本格计价币（三世界逐值对照） ─────────────────────────────────────

  /** ★★ <b>T6 ①</b>：单币世界 / 有外币但不持有 / 持有外币但银更强 —— 三种"没有指定币"的形态**逐值相同**， 且冻结表里的币种就是本格计价币（银）。 */
  @Test
  void anOrderWithoutACurrencyPaysInTheLocalNumeraireAndIsValueIdenticalAcrossThreeWorlds() {
    MarketSettlementFixtures.World single = singleCurrencyWorld();
    MarketSettlement.MarketOutcome a = settle(single);

    MarketSettlementFixtures.World foreignHeld = twoZoneWorld(1_000L, Map.of(SILVER, BUYER_SILVER));
    MarketSettlement.MarketOutcome b = settle(foreignHeld);

    MarketSettlementFixtures.World foreignPreferred =
        twoZoneWorld(20_000L, Map.of(SILVER, BUYER_SILVER, COPPER, 1_000_000L));
    MarketSettlement.MarketOutcome c = settle(foreignPreferred);

    assertThat(a.report().fills()).as("基准：区内成交").hasSize(1);
    assertThat(b.report().fills()).as("★ 有铜区但买方不持铜 ⇒ 不可比 ⇒ 仍按本格币下单").hasSize(1);
    assertThat(c.report().fills()).as("★ 持有铜但银更强 ⇒ 选出来的就是本格币").hasSize(1);

    MarketReport.Fill fillA = a.report().fills().get(0);
    MarketReport.Fill fillB = b.report().fills().get(0);
    MarketReport.Fill fillC = c.report().fills().get(0);
    assertThat(fillA.paymentCurrency()).as("★ 缺省 ⇒ 本格计价币").isEqualTo(SILVER);
    for (MarketReport.Fill fill : new MarketReport.Fill[] {fillB, fillC}) {
      assertThat(fill.paymentCurrency())
          .as("★ 缺省/不可比/选出本格币 ⇒ 逐值等于改前的本格币支付")
          .isEqualTo(fillA.paymentCurrency());
      assertThat(fill.quantity()).isEqualTo(fillA.quantity());
      assertThat(fill.goodsPaymentMilli()).isEqualTo(fillA.goodsPaymentMilli());
      assertThat(fill.freightMilli()).isEqualTo(fillA.freightMilli());
      assertThat(fill.lossMilli()).isEqualTo(fillA.lossMilli());
      assertThat(fill.unitCurrency()).isEqualTo(fillA.unitCurrency());
      assertThat(fill.unitPriceMilli()).isEqualTo(fillA.unitPriceMilli());
    }
    for (MarketSettlementFixtures.World world :
        new MarketSettlementFixtures.World[] {single, foreignHeld, foreignPreferred}) {
      assertThat(moneyOf(world, BUYER, SILVER))
          .as("★ 三个世界的买方银余额逐值相同（钱腿就在本格币上）")
          .isEqualTo(BUYER_SILVER - fillA.goodsPaymentMilli());
      assertThat(moneyOf(world, BUYER, COPPER))
          .as("★ 本格币支付 ⇒ 外币余额一个数不动（各世界按自己初始的铜余额）")
          .isEqualTo(world.money().getOrDefault(BUYER, Map.of()).getOrDefault(COPPER, 0L));
      assertThat(world.frozenMoney().getOrDefault(BUYER, Map.of()).keySet())
          .as("★ 冻结的币种 = 订单币 = 本格计价币（银）")
          .containsExactly(SILVER);
      assertThat(world.frozenMoney().getOrDefault(BUYER, Map.of()).get(SILVER))
          .as("冻结在轮末已释放（这份断言只钉「冻结轴是哪个币」）")
          .isZero();
      assertThat(moneyOf(world, SELLER, SILVER)).isEqualTo(fillA.goodsPaymentMilli());
    }
  }

  // ── ② 指定非本格支付币：钱腿 / 冻结 / 预算都按该币 ────────────────────────────────────

  /**
   * ★★ <b>T6 ②</b>：买方最强持有币是铜（铜区的粮便宜）⇒ 在<b>银计价</b>的本格市场上仍以<b>铜</b>付款。
   *
   * <pre>
   * cost(silver) = 2,905 × 10,000 ÷ 1000 = 29,050 毫银   cost(copper) = 2,905 × 1,000 ÷ 1000 = 2,905 毫铜
   * ⇒ copper 最强（付得最少）
   * 预算 = 买方**铜**可花额 14,525（不是银的 1,000,000）⇒ 挂单量 = ⌊14,525 × 1000 ÷ 10,000⌋ = 1,452
   * 成交：单价仍是卖方格的 10,000 毫银/单位（价格尺度不动），实付 = ⌈1,452 × 10,000 ÷ 1000⌉ = 14,520 毫铜
   * </pre>
   */
  @Test
  void payingInAForeignCurrencyMovesTheMoneyLegFreezeAndBudgetToThatCurrency() {
    long copperBudget = 14_525L;
    MarketSettlementFixtures.World world =
        twoZoneWorld(1_000L, Map.of(SILVER, BUYER_SILVER, COPPER, copperBudget));

    MarketSettlement.MarketOutcome outcome = settle(world);

    assertThat(outcome.report().fills()).as("异币支付照样成交").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.paymentCurrency()).as("★ T6：成交后卖方收到的就是买方指定的币（铜）").isEqualTo(COPPER);
    assertThat(fill.unitCurrency()).as("★ §2.1：价格尺度不动，单价币仍是卖方格计价币（银）").isEqualTo(SILVER);
    assertThat(fill.unitPriceMilli()).as("单价 = 卖方格参考价，不因支付币改变").isEqualTo(PRICE_MILLI);

    long expectedQuantity = copperBudget * 1_000L / PRICE_MILLI; // 铜按面值 1:1 折算（copper 在当地流通）
    assertThat(expectedQuantity).as("★ 本用例的判别力前提：铜预算只够一半（否则与银预算不可区分）").isEqualTo(1_452L);
    assertThat(expectedQuantity).isLessThan(DEMAND);
    assertThat(fill.quantity())
        .as("★ 预算按**买方支付币**（铜）折算 ⇒ 挂单/成交恰好是铜预算买得起的量")
        .isEqualTo(expectedQuantity);
    assertThat(fill.goodsPaymentMilli())
        .as("★ 货款腿的币与额：⌈量 × 单价 ÷ 1000⌉ 毫铜")
        .isEqualTo(ceilDiv(expectedQuantity * PRICE_MILLI, 1_000L));

    // ★ 钱腿真的落在铜上（不是只改读数）。
    assertThat(moneyOf(world, BUYER, COPPER))
        .as("★ 买方按铜付：铜余额 = 原额 − 货款")
        .isEqualTo(copperBudget - fill.goodsPaymentMilli());
    assertThat(moneyOf(world, BUYER, SILVER))
        .as("★ 买方那一大笔银一个数不动（若预算读的是本格币，这里会被扣）")
        .isEqualTo(BUYER_SILVER);
    assertThat(moneyOf(world, SELLER, COPPER))
        .as("★ 卖方收到的就是该币（铜），不是本格币")
        .isEqualTo(fill.goodsPaymentMilli());
    assertThat(moneyOf(world, SELLER, SILVER)).as("卖方一分银都没收到").isZero();

    // ★ 冻结的轴是铜（轮末已释放，但轴的币种留在表里 ⇒ 可判）。
    assertThat(world.frozenMoney().getOrDefault(BUYER, Map.of()).keySet())
        .as("★ 冻结按该币：买冻结轴 = (buyer, 支付币=铜)，表里不许出现银那一行")
        .containsExactly(COPPER);

    // ★ 预算读数也按该币（"为什么不买"的诊断口径与撮合同源）。
    MarketReport.BuyerOutcome buyerOutcome =
        outcome.report().buyerOutcomes().stream()
            .filter(o -> o.household().isPresent() && o.household().get().equals(BUYER))
            .filter(o -> o.commodity().equals(GRAIN))
            .findFirst()
            .orElseThrow();
    // ★ 诊断读数在**结算之后**收集 ⇒ 它的可花额是"付完剩下的铜"（5 毫铜），币种仍是铜而不是银。
    assertThat(buyerOutcome.spendableMoneyMilli())
        .as("★ 诊断读数里的可花额落在**铜**上（= 付完剩下的 5 毫铜），不是银的 1,000,000")
        .isEqualTo(copperBudget - fill.goodsPaymentMilli())
        .isEqualTo(moneyOf(world, BUYER, COPPER));
    assertThat(buyerOutcome.spendableMoneyMilli()).isNotEqualTo(BUYER_SILVER);
    assertThat(buyerOutcome.orderedQty()).as("★ 挂单量按铜预算封顶").isEqualTo(expectedQuantity);
    assertThat(buyerOutcome.filledQty()).as("挂出来的都成交了").isEqualTo(expectedQuantity);

    // ★ 对照组：同一世界、把铜给足 ⇒ 整笔需求都能买（证明上面那个 1,452 是"铜不够"，不是别的原因）。
    MarketSettlementFixtures.World funded =
        twoZoneWorld(1_000L, Map.of(SILVER, BUYER_SILVER, COPPER, BUYER_SILVER));
    MarketSettlement.MarketOutcome fundedOutcome = settle(funded);
    assertThat(fundedOutcome.report().fills().get(0).quantity())
        .as("★ 对照：铜给足 ⇒ 成交到整笔需求 2,905（同一形状，只差铜余额）")
        .isEqualTo(DEMAND);
    assertThat(fundedOutcome.report().fills().get(0).paymentCurrency()).isEqualTo(COPPER);
  }
}
