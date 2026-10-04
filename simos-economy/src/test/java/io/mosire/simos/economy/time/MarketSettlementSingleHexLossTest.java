package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.time.MarketSettlementFixtures.Builder;
import io.mosire.simos.economy.time.MarketSettlementFixtures.Round;
import io.mosire.simos.economy.time.MarketSettlementFixtures.World;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-027 单 hex 贸易成本的结算判据（设计 §7.2）</b>：
 *
 * <ol>
 *   <li>同格成交 {@code lossMilli = 0}；跨格即时成交 {@code lossMilli > 0} 且可读；
 *   <li>损耗真的从卖方毛量里消失：卖方毛量出、买方净量入，{@code Σ余额 + losses[market-transport]} 守恒；
 *   <li>买方按<b>毛量</b>付款（单价仍是区内参考价）；
 *   <li>单区内不产生货币运费 / CARRIER_FEE（{@code freightPaid/freightMilli} 恒 0）。
 * </ol>
 *
 * <p>夹具走真实 {@code MarketSettlement.clearOncePerCycle} + 唯一写口，不复制撮合算式。
 */
class MarketSettlementSingleHexLossTest {

  private static final HouseholdId SELLER = HouseholdId.parse("hh-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-buyer");

  @Test
  void sameHexFillHasZeroLossAndConservesExactly() {
    World world =
        MarketSettlementFixtures.builder()
            .market(MarketSettlementFixtures.H1, 10L)
            .household(SELLER, MarketSettlementFixtures.H1, 1L, 200_000L, 0L)
            .household(BUYER, MarketSettlementFixtures.H1, 1L, 0L, 1_000_000L)
            .build();
    Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(outcome.report().fills()).as("同格应有且只有一笔即时成交").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.immediate()).as("区内即时").isTrue();
    assertThat(fill.from()).as("同格成交 from == to").isEqualTo(fill.to());
    assertThat(fill.lossMilli()).as("★ 同格成交 lossMilli = 0").isZero();
    assertThat(outcome.report().immediateCrossHexFills()).as("同格不计跨 hex").isZero();
    assertThat(outcome.report().immediateCrossHexLossMilli()).as("同格损耗读数 = 0").isZero();

    long sellerBefore = 200_000L;
    long buyerBefore = 0L;
    long loss =
        ledgerLoss(
            round.ledger().toLedger(), MarketSettlement.TRANSPORT_LOSS_ACCOUNT, MarketSettlementFixtures.GRAIN);
    assertThat(loss).as("同格不记运输损耗").isZero();
    assertThat(world.grainOf(SELLER) + world.grainOf(BUYER)).isEqualTo(sellerBefore + buyerBefore);
  }

  @Test
  void crossHexImmediateFillMovesGrossOutAndNetInWithConservedLoss() {
    long sellerBefore = 200_000L;
    long buyerBefore = 0L;
    long buyerSilverBefore = 1_000_000L;
    World world =
        MarketSettlementFixtures.builder()
            .market(MarketSettlementFixtures.H1, 10L)
            .market(MarketSettlementFixtures.H2, 10L)
            .household(SELLER, MarketSettlementFixtures.H1, 1L, sellerBefore, 0L)
            .household(BUYER, MarketSettlementFixtures.H2, 1L, buyerBefore, buyerSilverBefore)
            .build();
    Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(outcome.report().fills()).as("跨 hex 应有且只有一笔即时成交").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.immediate()).as("区内即时（不是跨区在途）").isTrue();
    assertThat(fill.lossMilli())
        .as("★ 跨格损耗必须可见且 > 0（同格/损耗被去掉时这条先红）")
        .isPositive();
    assertThat(fill.from()).isEqualTo(MarketSettlementFixtures.H1);
    assertThat(fill.to()).isEqualTo(MarketSettlementFixtures.H2);
    assertThat(fill.quantity())
        .as("成交毛量 = 买方生活保留缺口 = 1 人 35 天的口粮")
        .isEqualTo(MarketSettlementFixtures.lifeReserveGrain(1L));
    assertThat(fill.lossMilli())
        .as("★ 距离 1 × moveCost 1 × 2‰ = 2‰，向下取整")
        .isEqualTo(fill.quantity() * 2L * 1L / 1000L);
    assertThat(fill.unitPriceMilli()).as("成交单价仍是区内参考价，不被损耗加价").isEqualTo(10L);
    assertThat(world.markets().get(MarketSettlementFixtures.H1).priceOf(MarketSettlementFixtures.GRAIN))
        .as("★ §7.2.4：单 hex 损耗不写进 Market.prices 参考价")
        .isEqualTo(10L);

    long sellerAfter = world.grainOf(SELLER);
    long buyerAfter = world.grainOf(BUYER);
    long loss =
        ledgerLoss(
            round.ledger().toLedger(), MarketSettlement.TRANSPORT_LOSS_ACCOUNT, MarketSettlementFixtures.GRAIN);

    // 诊断读数：这条 println 在守恒断言失败时留在 surefire stdout 里，便于把“卖方按净量出”的证据钉死。
    System.out.println(
        "[D027-LOSS] quantity="
            + fill.quantity()
            + " lossMilli="
            + fill.lossMilli()
            + " payment="
            + fill.goodsPaymentMilli()
            + " seller="
            + sellerBefore
            + "->"
            + sellerAfter
            + "(delta="
            + (sellerBefore - sellerAfter)
            + ", 期望毛量="
            + fill.quantity()
            + ") buyer="
            + buyerBefore
            + "->"
            + buyerAfter
            + "(delta="
            + (buyerAfter - buyerBefore)
            + ", 期望净量="
            + (fill.quantity() - fill.lossMilli())
            + ") ledgerLoss="
            + loss
            + " conserved="
            + (sellerAfter + buyerAfter + loss)
            + " initial="
            + (sellerBefore + buyerBefore));

    assertThat(loss).as("损耗进 market-transport 损耗账户").isEqualTo(fill.lossMilli());
    assertThat(sellerAfter + buyerAfter + loss)
        .as("★ Σ余额 + losses[market-transport] 守恒")
        .isEqualTo(sellerBefore + buyerBefore);
    assertThat(sellerBefore - sellerAfter)
        .as("★ 卖方毛量出：卖方少的是 fill.quantity()，不是净量")
        .isEqualTo(fill.quantity());
    assertThat(buyerAfter - buyerBefore)
        .as("★ 买方净量入")
        .isEqualTo(fill.quantity() - fill.lossMilli());

    assertThat(fill.goodsPaymentMilli())
        .as("★ 买方按毛量付款：⌈毛量 × 单价 ÷ 1000⌉")
        .isEqualTo(ceilDiv(fill.quantity() * fill.unitPriceMilli(), 1000L));
    assertThat(world.silverOf(BUYER)).as("买方货币 = 初始 − 货款").isEqualTo(buyerSilverBefore - fill.goodsPaymentMilli());
    assertThat(world.silverOf(SELLER)).as("卖方货币 = 初始 + 货款").isEqualTo(fill.goodsPaymentMilli());
    assertThat(outcome.report().immediateCrossHexFills()).as("跨 hex 即时成交笔数").isEqualTo(1L);
    assertThat(outcome.report().immediateCrossHexLossMilli())
        .as("跨 hex 即时损耗合计")
        .isEqualTo(fill.lossMilli());

    assertNoCarrierOrFreight(round);
  }

  @Test
  void singleRegionNeverMintsFreightOrCarrierFee() {
    World world =
        MarketSettlementFixtures.builder()
            .market(MarketSettlementFixtures.H1, 10L)
            .market(MarketSettlementFixtures.H2, 10L)
            .household(SELLER, MarketSettlementFixtures.H1, 1L, 200_000L, 0L)
            .household(BUYER, MarketSettlementFixtures.H2, 1L, 0L, 1_000_000L)
            .build();
    Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(outcome.report().freightPaidMilli()).as("★ 单区 freightPaid = 0").isZero();
    assertThat(outcome.report().freightUncollectedMilli()).as("★ 单区 freightUncollected = 0").isZero();
    assertThat(outcome.report().scheduledLossMilli()).as("单区没有在途预排损耗").isZero();
    assertThat(outcome.report().routes()).as("单区没有跨区路线").isEmpty();
    for (MarketReport.Fill fill : outcome.report().fills()) {
      assertThat(fill.freightMilli()).as("★ Fill.freightMilli = 0").isZero();
      assertThat(fill.freightPerUnitMilli()).as("★ Fill.freightPerUnitMilli = 0").isZero();
      assertThat(fill.shipmentId()).as("区内即时没有 shipmentId").isEmpty();
    }
    assertNoCarrierOrFreight(round);
  }

  private static void assertNoCarrierOrFreight(Round round) {
    assertThat(
            round.ledger().toLedger().transfers().stream()
                .map(Transfer::reason)
                .filter(reason -> reason == TransferReason.CARRIER_FEE)
                .collect(Collectors.toList()))
        .as("★ 单区不得铸 CARRIER_FEE")
        .isEmpty();
  }

  private static long ledgerLoss(
      ProductionLedger ledger,
      io.mosire.simos.economy.api.id.IndustryId account,
      io.mosire.simos.economy.api.id.CommodityId commodity) {
    Map<io.mosire.simos.economy.api.id.CommodityId, Long> line =
        ledger.losses().getOrDefault(account, Map.of());
    return line.getOrDefault(commodity, 0L);
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }
}
