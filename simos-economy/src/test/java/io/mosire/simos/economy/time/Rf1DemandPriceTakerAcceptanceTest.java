package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RF1 验收：家户需求是数量型价格接受者 + M3 同键自动订单替换</b>。
 *
 * <p>判据出处：{@code docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md} §2.1/§4.4、 {@code
 * docs/superpowers/plans/2026-10-20-d032-rf1-demand-price-taker-taskbook.md} §7。
 *
 * <p>★ <b>不按实现反推</b>：每条断言都先写"用户/设计要的世界长什么样"，再用真 {@link MarketSettlement#clearOncePerCycle} 与真
 * {@link MarketOrderPlanner} 跑出来核对；本类不复制任何数量/债务算式，只在测试里用公开读数与账本事实对账。
 */
class Rf1DemandPriceTakerAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CommodityId CLOTH = MarketSettlementFixtures.CLOTH;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;

  private static final HouseholdId SELLER = HouseholdId.parse("hh-rf1-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-rf1-buyer");
  private static final HouseholdId TRADER = HouseholdId.parse("hh-rf1-trader");

  /** 家户一整个市场轮的目标（= 1 人 5 tick 口粮），与夹具 {@link MarketSettlementFixtures#lifeReserveGrain} 同源。 */
  private static final long DEMAND = MarketSettlementFixtures.lifeReserveGrain(1L);

  @Test
  void zeroAskPriceStillGeneratesTheFullGapAndTradesAtZeroPrice() {
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, 0L) // ★ 明确 0 价（不是"未定价"）
            .household(
                SELLER,
                H1,
                1L,
                Map.of(GRAIN, 200_000L),
                Map.of(),
                Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
            .household(
                BUYER,
                H1,
                1L,
                Map.of(),
                Map.of(SILVER, 1_000_000L),
                Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
            .build();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    MarketReport.BuyerOutcome buyer = buyerOutcome(outcome, BUYER, GRAIN);
    assertThat(buyer.orderedQty())
        .as("★ ask=0 时需求仍按真实缺口全额挂出（旧口径 maxSpend=0 ⇒ 需求直接不生成）")
        .isEqualTo(DEMAND);
    assertThat(outcome.report().fills()).as("0 价是合法成交（不等于'没有价'）").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.quantity()).as("0 价也不截断数量").isEqualTo(DEMAND);
    assertThat(fill.goodsPaymentMilli()).as("0 价 ⇒ 货款腿为 0").isZero();
  }

  @Test
  void arbitraryHighPriceStillFillsForAPriceTakerAndUnpaidPriceBecomesOrdinaryDebt() {
    long highPrice = 1_000_000L; // 远高于任何"预算可买量"；价格接受者仍必须成交
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, highPrice)
            .household(
                SELLER,
                H1,
                1L,
                Map.of(GRAIN, 200_000L),
                Map.of(),
                Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
            .household(
                BUYER,
                H1,
                1L,
                Map.of(),
                Map.of(SILVER, 0L), // 现金 0：只能走普通货币债
                Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
            .build();
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketSettlementFixtures.Round deferred =
        new MarketSettlementFixtures.Round(
            world, round.round().withDeferredPayments(9L, debts), round.ledger());

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, deferred);

    MarketReport.BuyerOutcome buyer = buyerOutcome(outcome, BUYER, GRAIN);
    assertThat(buyer.orderedQty()).as("★ 现金 0 / 任意高价 ⇒ 挂单数量仍是完整缺口（不由现金或价格折回）").isEqualTo(DEMAND);
    assertThat(outcome.report().fills()).as("价格接受者成交（市场报多高都接受）").hasSize(1);
    assertThat(outcome.report().fills().get(0).quantity()).isEqualTo(DEMAND);
    assertThat(outcome.report().fills().get(0).goodsPaymentMilli())
        .as("现金 0 ⇒ 实付现金 0；差额走普通货币债")
        .isZero();
    assertThat(debts).as("未付价款全额成为普通货币债").hasSize(1);
    DebtContract debt = debts.values().iterator().next();
    assertThat(debt.debtor()).isEqualTo(BUYER);
    assertThat(debt.creditor()).isEqualTo(SELLER);
    assertThat(debt.source()).isEqualTo(DebtSource.ORDINARY_LOAN);
    assertThat(debt.unit()).isEqualTo(new DebtUnit.Money(SILVER));
    long expectedPrincipal = Math.ceilDiv(DEMAND * highPrice, 1_000L);
    assertThat(debt.principal())
        .as("债务本金 = ⌈数量 × 成交单价 ÷ 1000⌉（与价格接受者无关，成交后照算）")
        .isEqualTo(expectedPrincipal);
  }

  @Test
  void noSupplyOnlyRecordsUnmetAndNeverCreatesDebt() {
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, 10L)
            // ★ 只有买方，没有任何卖方/供给。
            .household(
                BUYER,
                H1,
                1L,
                Map.of(),
                Map.of(SILVER, 1_000_000L),
                Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
            .build();
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketSettlementFixtures.Round deferred =
        new MarketSettlementFixtures.Round(
            world, round.round().withDeferredPayments(9L, debts), round.ledger());

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, deferred);

    assertThat(outcome.report().fills()).as("无供给 ⇒ 零成交").isEmpty();
    MarketReport.BuyerOutcome buyer = buyerOutcome(outcome, BUYER, GRAIN);
    assertThat(buyer.orderedQty()).as("需求仍全额挂出（无供给不是'需求消失'）").isEqualTo(DEMAND);
    assertThat(outcome.report().unfilled())
        .as("★ 无供给只记 unmet：买方侧未成交条目保留完整缺口")
        .anyMatch(
            unfilled ->
                unfilled.buyerSide()
                    && unfilled.commodity().equals(GRAIN)
                    && unfilled.quantity() == DEMAND
                    && unfilled.actor().equals(HouseholdActors.of(BUYER)));
    assertThat(debts).as("★ 无供给 ⇒ 不记债（只有真实成交后的未付价款才成债）").isEmpty();
    assertThat(outcome.report().unfilled())
        .as("无供给不得被报成 NO_BUDGET 等'钱的问题'")
        .noneMatch(unfilled -> unfilled.reason() == MarketUnfilledReason.NO_BUDGET);
  }

  @Test
  void injectedClothBuyReplacesOnlyTheSameActorCommoditySideAutoLeg() {
    // 该户：粮库存足够 ⇒ 自动腿是 grain SELL；同时有 cloth 生活需求 ⇒ 自动腿还有 cloth BUY。
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, SILVER, Map.of(GRAIN, 10L, CLOTH, 20L))
            .household(
                TRADER,
                H1,
                1L,
                Map.of(GRAIN, 500_000L),
                Map.of(SILVER, 1_000_000L),
                Map.of(
                    GRAIN,
                    MarketSettlementFixtures.dailyRationGrain(1L),
                    CLOTH,
                    MarketSettlementFixtures.dailyRationGrain(1L)))
            .build();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    MarketIntent clothBuy =
        new MarketIntent(
            MarketIntentKind.COMMODITY,
            MarketIntentSide.BUY,
            HouseholdActors.of(TRADER),
            H1,
            Optional.of(CLOTH),
            5_000L,
            OptionalLong.empty(), // ★ BUY 形状不允许带限价（价格接受者）
            SILVER,
            0,
            Optional.empty());
    round.round().addInjectedMarketIntent(clothBuy);

    String traderKey = "HOUSEHOLD:" + HouseholdActors.idOf(TRADER);
    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MARKET_LOGGER_NAME)) {
      MarketSettlementFixtures.settle(world, round);
      String replaced =
          log.lines().stream()
              .filter(line -> line.contains("event=D032_P3_MARKET_INTENT_REPLACED "))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("没有 D032_P3_MARKET_INTENT_REPLACED 事件: " + log.lines()));
      assertThat(replaced)
          .as("★ 只删除同键 (actor, cloth, BUY) 的自动腿")
          .contains("removedKeys=[" + traderKey + "|cloth|BUY]");
      assertThat(replaced)
          .as("★ 同一 actor 的 grain SELL 自动腿必须保留（旧口径会 actor 级整删）")
          .contains(traderKey + "|grain|SELL");
      assertThat(replaced)
          .as("★ 未被 M3 意向指名的 grain BUY 不得被误删/伪造")
          .doesNotContain(traderKey + "|grain|BUY");
    }
  }

  private static MarketReport.BuyerOutcome buyerOutcome(
      MarketSettlement.MarketOutcome outcome, HouseholdId household, CommodityId commodity) {
    return outcome.report().buyerOutcomes().stream()
        .filter(o -> o.household().isPresent() && o.household().get().equals(household))
        .filter(o -> o.commodity().equals(commodity))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("没有买方读数 household=" + household + " commodity=" + commodity));
  }
}
