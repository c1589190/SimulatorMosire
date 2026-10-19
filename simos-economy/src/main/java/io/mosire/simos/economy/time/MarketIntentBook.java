package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★★ <b>D-032 P3e：M3 市场意向的唯一职责类</b>（自 {@link ProductionPlanBridge} 机械抽取）。
 *
 * <p>本类只把 M3 的物理缺口/剩余转成 {@link MarketIntent}：不生成市场订单、不写库存/货币/土地、不碰撮合器。
 */
final class MarketIntentBook {

  /** 毫价格 / 整单位 的换算分母（土地为毫价格 / 整亩，见 {@link MarketTable}）。 */
  private static final long MILLI_PER_UNIT = 1000L;

  private MarketIntentBook() {}

  /**
   * 把 M3 的物理缺口/剩余转成 {@link MarketIntent}。
   *
   * <ul>
   *   <li>生产投入缺口 → COMMODITY BUY priority=0；
   *   <li>基础消费缺口 → COMMODITY BUY priority=0；
   *   <li>可售剩余 → COMMODITY SELL priority=1；
   *   <li>本轮不用的自有 LAND 份额 → LAND SELL priority=2（没有可交易块就不伪造）。
   * </ul>
   */
  static List<MarketIntent> marketIntents(
      SettlementDayContext ctx,
      List<ProductionPlanBridge.PositionFacts> chosenFacts,
      Map<HouseholdId, Map<CommodityId, Long>> inputDemandByOrganizer,
      Map<HouseholdId, Map<CommodityId, Long>> outputSupplyByOrganizer) {
    Map<HouseholdId, MarketTable> markets = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> buyNeeds = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> sellSurplus = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : inputDemandByOrganizer.entrySet()) {
      HouseholdId household = entry.getKey();
      MarketTable market = ProductionPlanBridge.marketOf(ctx, household);
      if (market == null) {
        continue;
      }
      markets.put(household, market);
      for (Map.Entry<CommodityId, Long> need : entry.getValue().entrySet()) {
        long gap =
            Math.max(
                0L, need.getValue() - ProductionPlanBridge.goodsOf(ctx, household, need.getKey()));
        if (gap > 0L) {
          buyNeeds
              .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
              .merge(need.getKey(), gap, Math::addExact);
        }
      }
    }
    // ★ P3e：基础消费按家户逐户生成，与生产周期 cycleDaysByHousehold 完全解耦。
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry :
        ctx.householdEconomies.entrySet()) {
      HouseholdId household = householdEntry.getKey();
      HouseholdEconomy row = householdEntry.getValue();
      MarketTable market =
          markets.computeIfAbsent(household, key -> ProductionPlanBridge.marketOf(ctx, key));
      if (row == null || market == null) {
        continue;
      }
      // ★ P3e：基础消费只准备到下一市场轮（5 tick），不得用生产周期 cycleDays（Seeder=120）。
      //   生产投入仍由 probe 的本周期需求给出，两者不混用。
      long days = MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;
      for (Map.Entry<CommodityId, Long> need : row.naturalNeeds().entrySet()) {
        long period = Math.multiplyExact(need.getValue(), days);
        long gap =
            Math.max(0L, period - ProductionPlanBridge.goodsOf(ctx, household, need.getKey()));
        if (gap > 0L) {
          buyNeeds
              .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
              .merge(need.getKey(), gap, Math::addExact);
        }
      }
    }
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry :
        outputSupplyByOrganizer.entrySet()) {
      HouseholdId household = entry.getKey();
      MarketTable market =
          markets.computeIfAbsent(household, key -> ProductionPlanBridge.marketOf(ctx, key));
      HouseholdEconomy row = ctx.householdEconomies.get(household);
      if (market == null || row == null) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> output : entry.getValue().entrySet()) {
        long ownNeed =
            Math.multiplyExact(
                row.naturalNeeds().getOrDefault(output.getKey(), 0L),
                MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS);
        long surplus = Math.max(0L, output.getValue() - ownNeed);
        // 市场只能卖**当刻在手**的货：可售剩余必须以“当刻库存 − 本户基础消费”封顶，
        // 不得把未来产出当成现货卖单（那会冻结/扣成负库存）。
        long onHand = ProductionPlanBridge.goodsOf(ctx, household, output.getKey());
        long sellable = Math.min(surplus, Math.max(0L, onHand - ownNeed));
        if (sellable > 0L) {
          sellSurplus
              .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
              .merge(output.getKey(), sellable, Math::addExact);
        }
      }
    }
    List<MarketIntent> intents = new ArrayList<>();
    // priority 0：生产投入 + 基础消费缺口（同一 priority 内按 商品 id 稳定序）
    for (HouseholdId household : sortedHouseholds(buyNeeds.keySet())) {
      HouseholdEconomy row = ctx.householdEconomies.get(household);
      MarketTable market = markets.get(household);
      CurrencyId currency = ProductionPlanBridge.payCurrencyOf(market);
      if (row == null || currency == null) {
        continue;
      }
      long remainingBudget = ProductionPlanBridge.moneyOf(ctx, household, currency);
      for (CommodityId commodity : sortedCommodities(buyNeeds.get(household).keySet())) {
        if (!market.hasPrice(commodity) || remainingBudget <= 0L) {
          continue;
        }
        long quantity = buyNeeds.get(household).get(commodity);
        long ask = market.askPriceOf(commodity);
        long maxSpend =
            Math.min(remainingBudget, Math.multiplyExact(quantity, ask) / MILLI_PER_UNIT);
        if (maxSpend <= 0L) {
          continue;
        }
        intents.add(
            new MarketIntent(
                MarketIntentKind.COMMODITY,
                MarketIntentSide.BUY,
                HouseholdActors.of(household),
                row.view().hex(),
                java.util.Optional.of(commodity),
                java.util.Optional.empty(),
                quantity,
                ask,
                currency,
                maxSpend,
                0,
                java.util.Optional.empty()));
        remainingBudget = Math.subtractExact(remainingBudget, maxSpend);
      }
    }
    // priority 1：可售剩余
    for (HouseholdId household : sortedHouseholds(sellSurplus.keySet())) {
      MarketTable market = markets.get(household);
      CurrencyId currency = ProductionPlanBridge.payCurrencyOf(market);
      if (currency == null) {
        continue;
      }
      for (CommodityId commodity : sortedCommodities(sellSurplus.get(household).keySet())) {
        if (!market.hasPrice(commodity)) {
          continue;
        }
        intents.add(
            new MarketIntent(
                MarketIntentKind.COMMODITY,
                MarketIntentSide.SELL,
                HouseholdActors.of(household),
                ctx.householdEconomies.get(household).view().hex(),
                java.util.Optional.of(commodity),
                java.util.Optional.empty(),
                sellSurplus.get(household).get(commodity),
                market.bidPriceOf(commodity),
                currency,
                0L,
                1,
                java.util.Optional.empty()));
      }
    }
    // ★★★ P3d priority 2：土地意向（整块 BUY/SELL）。需求来自生产位置的 probe 土地需求（缺口），
    //   供给来自本轮未使用的自有 LAND 块；本类只追加意向，不改订单/撮合结构（公共入口仍只有
    //   MarketMatcher.matchAll 一次调用）。没有可卖块/可负担块 ⇒ 只是未满足需求，不伪造。
    intents.addAll(MarketLandIntentBook.landIntents(ctx, chosenFacts));
    return intents;
  }

  static List<HouseholdId> sortedHouseholds(Set<HouseholdId> households) {
    List<HouseholdId> sorted = new ArrayList<>(households);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  static List<CommodityId> sortedCommodities(Set<CommodityId> commodities) {
    List<CommodityId> sorted = new ArrayList<>(commodities);
    sorted.sort(Comparator.comparing(CommodityId::value));
    return sorted;
  }
}
