package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.time.MarketSettlementFixtures.Round;
import io.mosire.simos.economy.time.MarketSettlementFixtures.World;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-027 市场总调控判据（设计 §7.3）</b>：
 *
 * <ol>
 *   <li>{@code defaults/defaultsFor/none} 对参考价/bid/ask 逐值等于旧 {@code Market} 口径，默认行为与现状相同；
 *   <li>{@code referencePrices} 覆盖区内所有 hex，缺项回退原价；bid/ask 可单侧非 0 覆盖；
 *   <li>{@code open=false} ⇒ 空报告、无成交、无冻结残留；
 *   <li>配额用尽 ⇒ 买方 {@code REGULATION_QUOTA}，成交量 ≤ 配额；
 *   <li>{@code tariffPerUnit} 只进 {@code regulatedTariffMilli()} 读数，钱货不动。
 * </ol>
 */
class MarketRegulationTest {

  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId CLOTH = MarketSettlementFixtures.CLOTH;
  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;
  private static final HexCoord H3 = MarketSettlementFixtures.H3;
  private static final HouseholdId SELLER = HouseholdId.parse("hh-reg-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-reg-buyer");

  // ── 1. 默认值 = 旧 Market 口径 ───────────────────────────────────────────────────────

  @Test
  void defaultsNoneAndDefaultsForMatchLegacyMarketValues() {
    Market market = market(Map.of(GRAIN, 10L, CLOTH, 33L));

    for (MarketRegulation regulation :
        List.of(
            MarketRegulation.defaults(H1),
            MarketRegulation.none(),
            MarketRegulation.defaultsFor(new LinkedHashMap<>(Map.of(H2, market, H1, market))))) {
      assertThat(regulation.defined())
          .as("默认实例 = 没有真要施加的调控（defined=false）")
          .isFalse();
      assertThat(regulation.referencePrices()).isEmpty();
      assertThat(regulation.quotaPerWindow()).isEmpty();
      assertThat(regulation.tariffPerUnit()).isEmpty();
      assertThat(regulation.bidPerMille()).isZero();
      assertThat(regulation.askPerMille()).isZero();
      assertThat(regulation.open()).isTrue();
      assertThat(regulation.rules()).isEmpty();
      for (CommodityId commodity : List.of(GRAIN, CLOTH, new CommodityId("missing"))) {
        assertThat(regulation.referencePriceOf(market, commodity))
            .as("默认参考价逐值 = Market.priceOf(%s)", commodity)
            .isEqualTo(market.priceOf(commodity));
        assertThat(regulation.bidPriceOf(market, commodity))
            .as("默认 bid 逐值 = Market.bidPriceOf(%s)", commodity)
            .isEqualTo(market.bidPriceOf(commodity));
        assertThat(regulation.askPriceOf(market, commodity))
            .as("默认 ask 逐值 = Market.askPriceOf(%s)", commodity)
            .isEqualTo(market.askPriceOf(commodity));
      }
    }
  }

  @Test
  void defaultsForAnchorsAtCanonicalFirstHexAndEmptyMarketsFallBackToNone() {
    Market market = market(Map.of(GRAIN, 10L));
    LinkedHashMap<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H3, market);
    markets.put(H2, market);
    markets.put(H1, market);

    assertThat(MarketRegulation.defaultsFor(markets).anchor())
        .as("★ 锚格 = q,r 升序第一个，与 map 迭代序无关")
        .isEqualTo(H1);
    assertThat(MarketRegulation.defaultsFor(Map.of()).defined())
        .as("空 markets ⇒ none（defined=false，不猜锚格）")
        .isFalse();
    assertThat(MarketRegulation.none().anchor())
        .as("none 的占位锚格是 (0,0)")
        .isEqualTo(new HexCoord(0, 0));
  }

  @Test
  void referencePriceOverridesAllHexesAndMissingItemFallsBack() {
    Market h1Market = market(Map.of(GRAIN, 10L, CLOTH, 7L));
    Market h2Market = market(Map.of(GRAIN, 20L, CLOTH, 8L));
    MarketRegulation regulation =
        new MarketRegulation(
            H1, Map.of(GRAIN, 100L), 0L, 0L, Map.of(), Map.of(), true, List.of());

    assertThat(regulation.referencePriceOf(h1Market, GRAIN))
        .as("H1 的 grain 被区级参考价覆盖")
        .isEqualTo(100L);
    assertThat(regulation.referencePriceOf(h2Market, GRAIN))
        .as("★ H2 同区也必须读到同一个覆盖价（区内一价）")
        .isEqualTo(100L);
    assertThat(regulation.referencePriceOf(h1Market, CLOTH))
        .as("未覆盖商品回退 H1 原价")
        .isEqualTo(7L);
    assertThat(regulation.referencePriceOf(h2Market, CLOTH))
        .as("未覆盖商品回退 H2 原价（各 hex 自己的价）")
        .isEqualTo(8L);
    assertThat(regulation.referencePriceOf(h1Market, new CommodityId("missing")))
        .as("缺价商品回退 0 = 本格不交易，不凭空造价")
        .isZero();
    assertThat(regulation.bidPriceOf(h1Market, GRAIN))
        .as("覆盖价下的 bid = ⌊100 × 990‰⌋ = 99")
        .isEqualTo(99L);
    assertThat(regulation.askPriceOf(h2Market, GRAIN))
        .as("覆盖价下的 ask = ⌈100 × 1010‰⌉ = 101")
        .isEqualTo(101L);
  }

  @Test
  void bidAndAskCanBeOverriddenOneSided() {
    Market market = market(Map.of(GRAIN, 10L));

    MarketRegulation bidOnly =
        new MarketRegulation(H1, Map.of(), 500L, 0L, Map.of(), Map.of(), true, List.of());
    assertThat(bidOnly.bidPriceOf(market, GRAIN))
        .as("★ bid 单侧覆盖：500‰ × 10 = 5")
        .isEqualTo(5L);
    assertThat(bidOnly.askPriceOf(market, GRAIN))
        .as("ask 未覆盖 ⇒ 沿用 Market.ASK_PER_MILLE = 11")
        .isEqualTo(11L);

    MarketRegulation askOnly =
        new MarketRegulation(H1, Map.of(), 0L, 500L, Map.of(), Map.of(), true, List.of());
    assertThat(askOnly.bidPriceOf(market, GRAIN))
        .as("bid 未覆盖 ⇒ 沿用 Market.BID_PER_MILLE = 9")
        .isEqualTo(9L);
    assertThat(askOnly.askPriceOf(market, GRAIN))
        .as("★ ask 单侧覆盖：⌈10 × 500‰⌉ = 5，但必须严格高于 bid ⇒ max(9+1, 5) = 10")
        .isEqualTo(10L);

    MarketRegulation both =
        new MarketRegulation(H1, Map.of(), 800L, 1200L, Map.of(), Map.of(), true, List.of());
    assertThat(both.bidPriceOf(market, GRAIN)).as("bid = 8").isEqualTo(8L);
    assertThat(both.askPriceOf(market, GRAIN)).as("ask = 12").isEqualTo(12L);
    assertThat(both.askPriceOf(market, GRAIN)).isGreaterThan(both.bidPriceOf(market, GRAIN));

    assertThatThrownBy(
            () ->
                new MarketRegulation(
                    H1, Map.of(GRAIN, -1L), 0L, 0L, Map.of(), Map.of(), true, List.of()))
        .as("负参考价 fail-closed")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 2. 闭市 ────────────────────────────────────────────────────────────────────────

  @Test
  void openFalseReturnsEmptyReportAndLeavesNoResidue() {
    World world = simpleCrossHexWorld();
    Map<HouseholdId, Long> grainBefore = snapshotByHousehold(world, true);
    Map<HouseholdId, Long> silverBefore = snapshotByHousehold(world, false);
    MarketRegulation closed =
        new MarketRegulation(H1, Map.of(), 0L, 0L, Map.of(), Map.of(), false, List.of("closed"));
    Round round = MarketSettlementFixtures.round(world, closed);

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(outcome.report().fills()).as("闭市无成交").isEmpty();
    assertThat(outcome.report().unfilled()).as("闭市不产生未成交原因").isEmpty();
    assertThat(outcome.report().routes()).isEmpty();
    assertThat(outcome.report().freightPaidMilli()).isZero();
    assertThat(outcome.report().freightUncollectedMilli()).isZero();
    assertThat(outcome.report().regulatedTariffMilli()).as("闭市税费读数 = 0").isZero();
    assertThat(round.ledger().toLedger().transfers()).as("闭市不铸任何转移").isEmpty();
    assertThat(round.ledger().toLedger().losses()).as("闭市无损耗").isEmpty();
    assertThat(snapshotByHousehold(world, true)).as("闭市冻结前返回 ⇒ 商品余额不动").isEqualTo(grainBefore);
    assertThat(snapshotByHousehold(world, false)).as("闭市冻结前返回 ⇒ 货币余额不动").isEqualTo(silverBefore);
    assertThat(world.frozenGoods().values()).allSatisfy(inner -> assertThat(inner).isEmpty());
    assertThat(world.frozenMoney().values()).allSatisfy(inner -> assertThat(inner).isEmpty());
  }

  // ── 3. 默认无变化（none == defaultsFor == 原路径）────────────────────────────────────

  @Test
  void defaultsForIsValueIdenticalToLegacyNonePath() {
    World legacyWorld = simpleCrossHexWorld();
    Round legacyRound =
        MarketSettlementFixtures.round(legacyWorld, MarketRegulation.none());
    MarketSettlement.MarketOutcome legacy = MarketSettlementFixtures.settle(legacyWorld, legacyRound);

    World defaultWorld = simpleCrossHexWorld();
    Round defaultRound =
        MarketSettlementFixtures.round(
            defaultWorld, MarketRegulation.defaultsFor(defaultWorld.markets()));
    MarketSettlement.MarketOutcome defaults = MarketSettlementFixtures.settle(defaultWorld, defaultRound);

    assertThat(defaults.report()).as("★ 默认 regulation 与旧 none 路径逐值相同（含成交/未成交/运费/税费）")
        .isEqualTo(legacy.report());
    assertThat(defaults.report().fills()).as("默认必须仍开市且有成交（不是空报告）").isNotEmpty();
    assertThat(defaults.report().regulatedTariffMilli()).isZero();
    assertThat(defaults.report().immediateCrossHexFills()).isEqualTo(legacy.report().immediateCrossHexFills());
    assertThat(defaults.report().immediateCrossHexLossMilli())
        .isEqualTo(legacy.report().immediateCrossHexLossMilli());
    assertThat(snapshotByHousehold(defaultWorld, true))
        .as("默认与旧路径的商品终态相同")
        .isEqualTo(snapshotByHousehold(legacyWorld, true));
    assertThat(snapshotByHousehold(defaultWorld, false))
        .as("默认与旧路径的货币终态相同")
        .isEqualTo(snapshotByHousehold(legacyWorld, false));
  }

  // ── 4. 参考价覆盖进入真实成交价 ───────────────────────────────────────────────────────

  @Test
  void referencePriceOverrideBecomesTheRegionTradePrice() {
    World world =
        MarketSettlementFixtures.builder()
            .market(H1, 10L)
            .market(H2, 20L)
            .household(SELLER, H1, 1L, 200_000L, 0L)
            .household(BUYER, H2, 1L, 0L, 1_000_000L)
            .build();
    MarketRegulation regulation =
        new MarketRegulation(
            H1, Map.of(GRAIN, 100L), 0L, 0L, Map.of(), Map.of(), true, List.of());
    Round round = MarketSettlementFixtures.round(world, regulation);

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(outcome.report().fills()).as("有成交").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.from()).isEqualTo(H1);
    assertThat(fill.to()).isEqualTo(H2);
    assertThat(fill.unitPriceMilli())
        .as("★ 区内成交单价 = 区级覆盖参考价（不是 H2 的原价 20）")
        .isEqualTo(100L);
    assertThat(fill.goodsPaymentMilli())
        .as("按毛量 × 覆盖价付款")
        .isEqualTo(ceilDiv(fill.quantity() * 100L, 1000L));
    assertThat(world.silverOf(SELLER)).isEqualTo(fill.goodsPaymentMilli());
    assertThat(world.silverOf(BUYER)).isEqualTo(1_000_000L - fill.goodsPaymentMilli());
  }

  // ── 5. 配额 ────────────────────────────────────────────────────────────────────────

  @Test
  void quotaExhaustionNamesRegulationQuotaAndCapsFilledQuantity() {
    HouseholdId knownSeller = HouseholdId.parse("hh-quota-known-seller");
    HouseholdId otherSeller = HouseholdId.parse("hh-quota-other-seller");
    HouseholdId buyer = HouseholdId.parse("hh-quota-buyer");
    World world =
        MarketSettlementFixtures.builder()
            .market(H1, 10L)
            .market(H2, 10L)
            .market(H3, 10L)
            .knownCostGrainSeller(knownSeller, H1, 1L, 200_000L, 0L)
            .household(otherSeller, H2, 1L, 200_000L, 0L)
            .household(buyer, H3, 1L, 0L, 1_000_000L)
            .build();
    long quota = 1_000L;
    MarketRegulation regulation =
        new MarketRegulation(
            H1, Map.of(), 0L, 0L, Map.of(GRAIN, quota), Map.of(), true, List.of());
    Round round = MarketSettlementFixtures.round(world, regulation);

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    long filled = outcome.report().fills().stream().mapToLong(MarketReport.Fill::quantity).sum();
    assertThat(filled).as("★ 成交量 ≤ 配额").isLessThanOrEqualTo(quota);
    assertThat(filled).as("配额是上限，不是建议：有货有需求时吃满配额").isEqualTo(quota);
    assertThat(outcome.report().unfilled())
        .as("★ 配额用尽必须产生具名 REGULATION_QUOTA 的买方未成交")
        .anySatisfy(
            unfilled -> {
              assertThat(unfilled.buyerSide()).isTrue();
              assertThat(unfilled.commodity()).isEqualTo(GRAIN);
              assertThat(unfilled.reason()).isEqualTo(MarketUnfilledReason.REGULATION_QUOTA);
              assertThat(unfilled.quantity()).isPositive();
            });
    assertThat(outcome.report().unfilledReasonCounts())
        .as("REGULATION_QUOTA 在原因分布中可见")
        .containsKey(MarketUnfilledReason.REGULATION_QUOTA);
    assertThat(world.grainOf(knownSeller)).as("★ 已知成本卖家（第一成本层）先成交").isLessThan(200_000L);
    assertThat(world.grainOf(otherSeller)).as("配额已在第一层用尽 ⇒ 第二成本层一毫未卖").isEqualTo(200_000L);
  }

  // ── 6. 税费读数 ─────────────────────────────────────────────────────────────────────

  @Test
  void tariffOnlyAccumulatesReadingAndNeverMovesGoodsOrMoney() {
    long tariffPerUnit = 7L;

    World defaultWorld = simpleCrossHexWorld();
    Round defaultRound =
        MarketSettlementFixtures.round(defaultWorld, MarketRegulation.defaultsFor(defaultWorld.markets()));
    MarketSettlement.MarketOutcome defaultOutcome =
        MarketSettlementFixtures.settle(defaultWorld, defaultRound);

    World tariffWorld = simpleCrossHexWorld();
    MarketRegulation regulation =
        new MarketRegulation(
            H1, Map.of(), 0L, 0L, Map.of(), Map.of(GRAIN, tariffPerUnit), true, List.of());
    Round tariffRound = MarketSettlementFixtures.round(tariffWorld, regulation);
    MarketSettlement.MarketOutcome tariffOutcome =
        MarketSettlementFixtures.settle(tariffWorld, tariffRound);

    assertThat(tariffOutcome.report().fills()).hasSize(1);
    MarketReport.Fill fill = tariffOutcome.report().fills().get(0);
    long expectedTariff = fill.quantity() * tariffPerUnit / 1000L;
    assertThat(tariffOutcome.report().regulatedTariffMilli())
        .as("★ 税费只进读数：⌊毛量 × 单位税 ÷ 1000⌋")
        .isEqualTo(expectedTariff);
    assertThat(expectedTariff).isPositive();
    assertThat(defaultOutcome.report().regulatedTariffMilli())
        .as("默认无税费 ⇒ 读数 0")
        .isZero();

    // 钱货不动：与默认路径逐值比较（税费没有多扣一分钱、也没有多扣一毫货）。
    assertThat(snapshotByHousehold(tariffWorld, true))
        .as("★ 税费不改变商品终态")
        .isEqualTo(snapshotByHousehold(defaultWorld, true));
    assertThat(snapshotByHousehold(tariffWorld, false))
        .as("★ 税费不改变货币终态（没有多扣一笔钱）")
        .isEqualTo(snapshotByHousehold(defaultWorld, false));

    long moneyLegs =
        tariffRound.ledger().toLedger().transfers().stream()
            .flatMap(transfer -> transfer.money().values().stream())
            .mapToLong(Long::longValue)
            .sum();
    assertThat(moneyLegs)
        .as("★ 唯一货币腿 = 货款，没有税费腿")
        .isEqualTo(fill.goodsPaymentMilli());
    assertThat(tariffRound.ledger().toLedger().transfers())
        .as("税费只记读数，不铸额外转移")
        .allSatisfy(transfer -> assertThat(transfer.reason()).isEqualTo(TransferReason.MARKET_TRADE));
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static World simpleCrossHexWorld() {
    return MarketSettlementFixtures.builder()
        .market(H1, 10L)
        .market(H2, 10L)
        .household(SELLER, H1, 1L, 200_000L, 0L)
        .household(BUYER, H2, 1L, 0L, 1_000_000L)
        .build();
  }

  private static Market market(Map<CommodityId, Long> prices) {
    return new Market(MarketSettlementFixtures.SILVER, new LinkedHashMap<>(prices));
  }

  private static Map<HouseholdId, Long> snapshotByHousehold(World world, boolean goods) {
    Map<HouseholdId, Long> snapshot = new LinkedHashMap<>();
    for (HouseholdId household : world.rows().keySet()) {
      snapshot.put(
          household,
          goods
              ? world.goods().getOrDefault(household, Map.of()).getOrDefault(GRAIN, 0L)
              : world.money().getOrDefault(household, Map.of()).getOrDefault(MarketSettlementFixtures.SILVER, 0L));
    }
    return snapshot;
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }
}
