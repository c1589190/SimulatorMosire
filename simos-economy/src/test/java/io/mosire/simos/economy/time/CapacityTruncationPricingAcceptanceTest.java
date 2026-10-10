package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §6.2 的 V-20 判据（冻结口径）+ §2.4
 * M2</b>：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`
 *
 * <pre>
 * V-20  "超出不计入价格表"必须发生在**喂给自适应定价的那份供需统计**上，而不是只改读数：
 *        先按运力截断跨格量，再算本格 demand/supply；被截断的部分**不提价、不压价**（两侧都剔）
 * 定价  z = clamp((demand − supply) / max(demand + supply, ε), −1, 1)；p_next = max(0, round(p × (1 + α·z)))
 * </pre>
 *
 * <p>★ <b>夹具（单区两格：卖方/承运在 H1、唯一的买方在 H2）</b>：跨格需求只有一条，量的算式因此是判据级的 （不依赖任何撮合内部实现）：
 *
 * <pre>
 * 卖方挂单      S = 4,000 毫粮           买方缺口（生活保留）D = 2,905 毫粮（1 人 × 83 毫/天 × 35 天）
 * 参考价 p = 10,000 毫银/单位，α = 50‰
 *
 * 无运力：跨格量 min(D, S) = 2,905 被整条截断 ⇒ demand 侧剔 2,905、supply 侧剔 2,905
 *        ⇒ (demand, supply) = (0, 1,095) ⇒ z = −1 ⇒ p_next = 9,500
 * 有运力（运力 ≥ D）：一分钱没截断 ⇒ (demand, supply) = (2,905, 4,000) ⇒ z = −0.1586 ⇒ p_next = 9,921
 * ★ 两案的差恰好是"截断部分有没有进统计"：若把截断量算进去，无运力那一案会得到 9,921（= 有运力那一案）
 * </pre>
 *
 * <p>★ 价格表读两处（同一事实的两个读口，必须一致）：{@code report().priceUpdates()} 与 {@code outcome.markets()}（成员格同改）。
 */
class CapacityTruncationPricingAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final HouseholdId SELLER = HouseholdId.parse("hh-pricing-seller");
  private static final HouseholdId CROSS_BUYER = HouseholdId.parse("hh-pricing-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-pricing-carrier");

  private static final long SELLER_GRAIN = 4_000L;
  private static final long BUYER_SILVER = 1_000_000L;
  private static final long PRICE_MILLI = 10_000L;

  /** 跨格需求 = 买方的生活保留缺口（夹具算法与生产路径同源：当日需求 × 35 天）。 */
  private static final long CROSS_DEMAND = MarketSettlementFixtures.lifeReserveGrain(1L);

  /**
   * @param carrierLabor 承运户劳动投入；{@code < 0} ⇒ 世界里<b>没有</b>跑商家户（= 发货格零运力，跨格车道根本不建）
   */
  private static MarketSettlementFixtures.World world(long carrierLabor) {
    MarketSettlementFixtures.Builder builder =
        MarketSettlementFixtures.builder()
            .market(H1, PRICE_MILLI)
            .market(H2, PRICE_MILLI)
            .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
            .household(CROSS_BUYER, H2, 1L, 0L, BUYER_SILVER);
    if (carrierLabor >= 0L) {
      builder.carrier(CARRIER, H1, carrierLabor, MerchantHaul.TOOL_MILLI_PER_HAUL);
    }
    return builder.build();
  }

  private static MarketSettlement.MarketOutcome settle(MarketSettlementFixtures.World world) {
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    return MarketSettlementFixtures.settleWithPool(world, round, world.carrierPool());
  }

  private static long nextPriceOf(MarketSettlement.MarketOutcome outcome, HexCoord hex) {
    return outcome.markets().get(hex).priceOf(MarketSettlementFixtures.GRAIN);
  }

  private static long updateNextOf(MarketSettlement.MarketOutcome outcome) {
    return outcome.report().priceUpdates().stream()
        .filter(u -> u.commodity().equals(MarketSettlementFixtures.GRAIN))
        .findFirst()
        .orElseThrow()
        .nextPriceMilli();
  }

  /**
   * ★★ <b>V-20 主判据：有运力 vs 无运力两案的价格表逐值对照</b> —— 每案的价格表必须等于"用<b>剔除截断量之后</b>的 demand/supply
   * 算出来的那个数"，且都**不等于**把截断量算进去会得到的那个数。
   */
  @Test
  void truncatedCrossHexQuantityNeverEntersTheDemandSupplyStatistics() {
    // ── 案 1：发货格没有运力 ⇒ 跨格量整条截断（两侧都剔） ─────────────────────────────
    MarketSettlementFixtures.World noCapacity = world(-1L);
    MarketSettlement.MarketOutcome noCapacityOutcome = settle(noCapacity);

    assertThat(noCapacityOutcome.report().fills()).as("无运力 ⇒ 一笔都不成交（不是「少卖一点」）").isEmpty();
    assertThat(noCapacityOutcome.report().unfilledReasonCounts())
        .as("具名 LOGISTICS_CAPACITY（不是口岸/币种/预算）")
        .containsKey(MarketUnfilledReason.LOGISTICS_CAPACITY);
    assertThat(noCapacityOutcome.report().sellerOutcomes())
        .as("卖方挂单读数（本用例两侧量的算式都从读数取，不从代码推）")
        .hasSize(1);
    long offered = noCapacityOutcome.report().sellerOutcomes().get(0).offeredQty();
    long demand = noCapacityOutcome.report().buyerOutcomes().get(0).orderedQty();
    assertThat(offered).as("卖方挂单量（判据算式里的 S）").isEqualTo(SELLER_GRAIN);
    assertThat(demand).as("买方缺口（判据算式里的 D；本夹具它的算式 = 生活保留 35 天）").isEqualTo(CROSS_DEMAND);
    assertThat(demand).as("★ 有缺口才可能截断（否则本用例测不到东西）").isPositive();
    assertThat(offered).as("★ 供给足够（截断与「供给不足」可区分）").isGreaterThan(demand);

    long truncated = demand; // 跨格量 = min(demand, supply) = demand（供给足够）⇒ 两侧各剔这么多
    long expectedNoCapacity =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, 0L, offered - truncated);
    assertThat(expectedNoCapacity)
        .as("★ 剔除后的口径下：需求侧剔光、供给侧剔掉「本该运走的那份」 ⇒ z = −1 ⇒ 降 α 一档")
        .isEqualTo(9_500L);

    assertThat(nextPriceOf(noCapacityOutcome, H1))
        .as("★ V-20：无运力那一案的价格表 = 用剔除后的 demand/supply 算出的值")
        .isEqualTo(expectedNoCapacity);
    assertThat(updateNextOf(noCapacityOutcome)).as("两个读口一致（成员格同改）").isEqualTo(expectedNoCapacity);
    assertThat(nextPriceOf(noCapacityOutcome, H2)).as("区内成员格同改").isEqualTo(expectedNoCapacity);
    assertThat(noCapacityOutcome.report().priceUpdates().get(0).previousPriceMilli())
        .as("改价记录带改前值")
        .isEqualTo(PRICE_MILLI);

    // ★ 反例（判别力所在）：把截断量算进去会得到另一个数 —— 若实现"只改读数、不改统计"，本断言当场红。
    long naiveNext = MarketSettlement.adaptiveNextPrice(PRICE_MILLI, demand, offered);
    assertThat(naiveNext).as("★ 把跨格需求算进统计会得到的值（= 有运力那一案的值）").isEqualTo(9_921L);
    assertThat(nextPriceOf(noCapacityOutcome, H1))
        .as("★「截断部分不提价」：它若进统计，价格会被推到 9,921（更高的那一档）")
        .isNotEqualTo(naiveNext);

    // ── 案 2：有运力（运力 ≥ D）⇒ 一分钱没截断 ⇒ 统计 = 原始订单 ────────────────────────
    MarketSettlementFixtures.World withCapacity = world(10_000L);
    MarketSettlement.MarketOutcome withCapacityOutcome = settle(withCapacity);

    assertThat(withCapacityOutcome.report().fills()).as("有运力 ⇒ 跨格成交发生").hasSize(1);
    assertThat(withCapacityOutcome.report().fills().get(0).quantity())
        .as("★ 有运力那一案把整笔需求运走 ⇒ 无截断（两案只差「运力够不够」这一个事实）")
        .isEqualTo(demand);
    assertThat(withCapacityOutcome.report().unfilledReasonCounts())
        .as("★ 有运力 ⇒ 不许报成运力不足")
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);

    long expectedWithCapacity = MarketSettlement.adaptiveNextPrice(PRICE_MILLI, demand, offered);
    assertThat(expectedWithCapacity).isEqualTo(naiveNext);
    assertThat(nextPriceOf(withCapacityOutcome, H1))
        .as("★ V-20 对照案：没截断 ⇒ 统计就是订单本身（价格 = 9,921）")
        .isEqualTo(expectedWithCapacity);
    assertThat(nextPriceOf(withCapacityOutcome, H1))
        .as("★ 两案的价格表**逐值不同** ⇒ 本用例真的在测「截断有没有进价格表」")
        .isNotEqualTo(nextPriceOf(noCapacityOutcome, H1));
  }

  /**
   * ★★ <b>V-20 的「两侧都剔」（同一案内可分辨）</b>：世界里另有一个<b>同格的</b>买方（不需要运力、真的成交）， 于是「需求侧剔了多少」不再把 z 顶到
   * −1，供给侧的剔除因此<b>在价格上可见</b>。
   *
   * <pre>
   * 卖方 S（H1）挂 10,000；本地买方 L（H1）缺口 2,905（同格成交，不受运力影响）
   * 跨格买方 X（H2）缺口 2,905；发货格 H1 无运力 ⇒ X 的跨格需求整条不进统计
   * ⇒ (demand, supply) = (L 的 2,905, S 的 10,000 − 本该运走的 2,905 = 7,095) ⇒ z = −0.419 ⇒ 9,790
   *   只剔需求不剔供给 ⇒ (2,905, 10,000) ⇒ 9,725
   *   只剔供给不剔需求 ⇒ (5,810, 7,095) ⇒ 9,950
   *   两边都不剔（只改读数不改统计） ⇒ (5,810, 10,000) ⇒ 另一个数
   * ⇒ 四个数互不相等 ⇒「两侧都剔」在同一案内可分辨
   * </pre>
   */
  @Test
  void bothTheDemandAndTheSupplySideExcludeTheTruncatedPart() {
    HouseholdId localBuyer = HouseholdId.parse("hh-pricing-local-buyer");
    long sellerGrain = 10_000L;
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, PRICE_MILLI)
            .market(H2, PRICE_MILLI)
            .household(SELLER, H1, 0L, sellerGrain, 0L)
            .household(localBuyer, H1, 1L, 0L, BUYER_SILVER)
            .household(CROSS_BUYER, H2, 1L, 0L, BUYER_SILVER)
            .build();
    MarketSettlement.MarketOutcome outcome = settle(world);

    long offered = outcome.report().sellerOutcomes().get(0).offeredQty();
    long localFilled =
        outcome.report().fills().stream()
            .filter(f -> f.to().equals(H1))
            .mapToLong(MarketReport.Fill::quantity)
            .sum();
    long crossOrdered =
        outcome.report().buyerOutcomes().stream()
            .filter(o -> o.hex().equals(H2))
            .mapToLong(MarketReport.BuyerOutcome::orderedQty)
            .sum();
    assertThat(offered).as("卖方挂单量（S）").isEqualTo(sellerGrain);
    assertThat(localFilled).as("★ 同格买方被完整服务（它的需求不是「被截断的部分」⇒ 必须留在统计里）").isEqualTo(CROSS_DEMAND);
    assertThat(crossOrdered).as("★ 跨格买方缺口（= 本该运走的那份 = 供给侧要剔掉的量）").isEqualTo(CROSS_DEMAND);
    assertThat(outcome.report().fills()).as("跨格那笔一笔都不成交（同格那笔照常）").hasSize(1);

    long correct =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, localFilled, offered - crossOrdered);
    long demandExcludedOnly = MarketSettlement.adaptiveNextPrice(PRICE_MILLI, localFilled, offered);
    long supplyExcludedOnly =
        MarketSettlement.adaptiveNextPrice(
            PRICE_MILLI, localFilled + crossOrdered, offered - crossOrdered);
    long naive =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, localFilled + crossOrdered, offered);
    assertThat(java.util.List.of(correct, demandExcludedOnly, supplyExcludedOnly, naive))
        .as("★ 四个候选值互不相等 ⇒「两侧都剔」可分辨（否则本用例是等价存活）")
        .doesNotHaveDuplicates();

    assertThat(nextPriceOf(outcome, H1))
        .as("★ V-20：价格表 = 两侧都剔掉被截断的那一份之后算出的值")
        .isEqualTo(correct)
        .isNotEqualTo(demandExcludedOnly)
        .isNotEqualTo(supplyExcludedOnly)
        .isNotEqualTo(naive);
  }
}
