package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z7b / run7 D2：国库户与单位户退出商品市场</b>（设计书 §3.1、§8-D2；run6 day50 根因的负向复现）。
 *
 * <p>装置：{@link MarketSettlementFixtures} 的**真** {@code MarketSettlement.MarketRound} 与唯一订单生成器
 * {@code planOrders}（与 {@code clearOncePerCycle} 同一条 {@code ordersFor}）。旧 18 参 round（无排除集）= 改动前口径；
 * 新 22 参 round（排除集）= 生产口径。
 *
 * <ul>
 *   <li>0 人口国库户粮 271,393（run6 day50 读数）：旧口径被当可卖余量 ⇒ 一条 271,393 卖单；修复口径 0；
 *   <li>{@code hh-unit:*} 单位户经"经营者解析"路径也不得重新入市（第一层排家户 + 第二层排 unit 的经营者）；
 *   <li>正对照：正常经济户的买单在两种口径下**逐值不变**（证明排除不是把整个市场关掉）。
 * </ul>
 */
class Z7MarketExclusionTest {

  private static final io.mosire.simos.map.hex.HexCoord H1 = MarketSettlementFixtures.H1;
  private static final io.mosire.simos.economy.api.id.CommodityId GRAIN =
      MarketSettlementFixtures.GRAIN;

  private static final HouseholdId TREASURY = HouseholdId.parse("hh-gov-gov-central");
  private static final HouseholdId UNIT_HOUSE = HouseholdId.parse("hh-unit:gov-central");
  private static final HouseholdId FARM = HouseholdId.parse("hh-0_0-normal-farm");

  @Test
  void treasuryAndUnitHouseholdsGenerateNoOrdersWhileNormalHouseholdIsUnchanged() {
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, 1L)
            // 0 人口国库户：run6 day50 中央国库粮 271,393（不得再被当卖家清仓）。
            .household(TREASURY, H1, 0L, 271_393L, 0L)
            // 单位户：带一个 operator=自己的 unit，专门覆盖 participantsFor 的"经营者解析"第二层排除。
            .knownCostGrainSeller(UNIT_HOUSE, H1, 0L, 50_000L, 0L)
            // 正常经济户（20 人）：有粮 1,000、银 100,000 ⇒ 正常买单，作为正对照。
            .household(FARM, H1, 20L, 1_000L, 100_000L)
            .build();

    MarketSettlementFixtures.Round legacy =
        MarketSettlementFixtures.round(world, MarketRegulation.none());
    MarketSettlementFixtures.Round fixed =
        MarketSettlementFixtures.round(
            world, MarketRegulation.none(), Set.of(TREASURY, UNIT_HOUSE));

    MarketSettlement.PlannedOrders legacyOrders =
        MarketSettlement.planOrders(legacy.round(), H1, world.markets().get(H1), GRAIN);
    MarketSettlement.PlannedOrders fixedOrders =
        MarketSettlement.planOrders(fixed.round(), H1, world.markets().get(H1), GRAIN);

    assertThat(sellableOf(legacyOrders.sells(), TREASURY))
        .as("旧口径复现 run6 D2：0 人口国库户粮 271,393 全程被当 sellable")
        .isEqualTo(271_393L);
    assertThat(sellableOf(fixedOrders.sells(), TREASURY)).as("修复口径：国库户买卖都不生成").isZero();
    assertThat(sellableOf(legacyOrders.sells(), UNIT_HOUSE))
        .as("旧口径：单位户（及其 operator unit）入市")
        .isEqualTo(50_000L);
    assertThat(sellableOf(fixedOrders.sells(), UNIT_HOUSE))
        .as("修复口径：单位户经家户层与经营者层双层排除，绝不重新入市")
        .isZero();

    long legacyFarmBuy = buyQuantityOf(legacyOrders.buys(), FARM);
    long fixedFarmBuy = buyQuantityOf(fixedOrders.buys(), FARM);
    assertThat(legacyFarmBuy).as("正对照：正常户确实在买（不是市场整体为空）").isPositive();
    assertThat(fixedFarmBuy).as("正对照：正常户订单逐值不变").isEqualTo(legacyFarmBuy);
  }

  private static long sellableOf(List<SellOrder> sells, HouseholdId household) {
    return sells.stream()
        .filter(order -> order.supplier().equals(HouseholdActors.of(household)))
        .mapToLong(SellOrder::sellable)
        .sum();
  }

  private static long buyQuantityOf(List<BuyOrder> buys, HouseholdId household) {
    return buys.stream()
        .filter(order -> order.requester().equals(HouseholdActors.of(household)))
        .mapToLong(BuyOrder::quantity)
        .sum();
  }
}
