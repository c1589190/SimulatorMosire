package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★★ <b>D-032 P3e：M3 市场意向的唯一职责类</b>（自 {@link ProductionPlanBridge} 机械抽取）。
 *
 * <p>本类只把 M3 的物理缺口/剩余转成 {@link MarketIntent}：不生成市场订单、不写库存/货币/土地、不碰撮合器。
 */
final class MarketIntentBook {

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
      for (CommodityId commodity : sortedCommodities(buyNeeds.get(household).keySet())) {
        // ★ RF1：只保留"未定价 ⇒ 不交易"这一条门；"明确 0 价"合法，且不再用 ask/现金/预算截断需求。
        if (!market.hasPrice(commodity)) {
          continue;
        }
        // ★★★ RF1：quantity 就是真实缺口全额 —— 不由 ask、现金余额、剩余预算或 maxSpend 折回。
        long quantity = buyNeeds.get(household).get(commodity);
        if (quantity <= 0L) {
          continue;
        }
        MarketIntent intent =
            new MarketIntent(
                MarketIntentKind.COMMODITY,
                MarketIntentSide.BUY,
                HouseholdActors.of(household),
                row.view().hex(),
                java.util.Optional.of(commodity),
                quantity,
                // ★ 价格接受者：BUY 形状不允许带限价。
                OptionalLong.empty(),
                currency,
                0,
                java.util.Optional.empty());
        intents.add(intent);
        logBuyIntent(ctx, household, intent);
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
                sellSurplus.get(household).get(commodity),
                // ★ SELL 侧最低价语义不变（0 = 明确免费交易）。
                OptionalLong.of(market.bidPriceOf(commodity)),
                currency,
                1,
                java.util.Optional.empty()));
      }
    }
    // ★★★ RA1b2c2 priority 2：标量土地意向（按 (actor, LAND, hex, side) 合并的 BUY/SELL）。需求来自生产位置的
    //   probe 土地缺口（按 landHoldings 数量），供给来自本轮未使用的自有土地；本类只追加意向，不改订单/撮合结构
    //   （公共入口仍只有 MarketMatcher.matchAll 一次调用）。无供给/无现金都不阻止挂单：未满足只记 unmet，不伪造。
    intents.addAll(MarketLandIntentBook.landIntents(ctx, chosenFacts));
    return intents;
  }

  /**
   * ★★★ <b>RC8c：S7-A 商品买卖意向 = S4 后当前库存 + 同一 {@link DailyDemandBill}。</b>
   *
   * <pre>
   * 买（每户每商品一条，按 (actor, commodity, BUY) 合并）：
   *   need = Σ BASE_SUBSISTENCE/PRODUCTION_DAILY_EXTRA 行 × MARKET_RESTOCK_INTERVAL_DAYS
   *        + Σ PRODUCTION_INPUT 行 max(0, quantity − providedFromStock)     // 不乘 5、已提供不再买
   *   marketPurchaseNeed = max(0, need − currentGoods)                       // 不以现金/库存余额做挂单门
   * 卖（每户每商品一条，按 (actor, commodity, SELL) 合并）：
   *   reserve = need（同一账单的保留项）
   *   actualSurplus = max(0, currentGoods − reserve)                          // 只用 S4/S5 后现货，不卖未来产出
   * </pre>
   *
   * <p>★ 商品只来自账单 + <b>S4 后的当前库存</b>；不再读 S3 的 {@code expectedProductionPlan.marketIntents()}。★ LAND
   * 仍只追加 {@link MarketLandIntentBook} 的标量意向（读本轮实际位置事实，价格/供给公式一字不改）。
   */
  static List<MarketIntent> dailyDemandBillMarketIntents(SettlementDayContext ctx) {
    long restockDays = MARKET_RESTOCK_INTERVAL_DAYS;
    List<MarketIntent> intents = new ArrayList<>();
    int buyIntents = 0;
    int sellIntents = 0;
    for (HouseholdId household : sortedHouseholds(ctx.dailyDemandBills.keySet())) {
      HouseholdEconomy row = ctx.householdEconomies.get(household);
      if (row == null) {
        continue;
      }
      MarketTable market = ProductionPlanBridge.marketOf(ctx, household);
      if (market == null) {
        continue;
      }
      CurrencyId currency = ProductionPlanBridge.payCurrencyOf(market);
      if (currency == null) {
        continue;
      }
      DailyDemandBill bill = ctx.dailyDemandBills.get(household);
      Set<CommodityId> commodities = new LinkedHashSet<>();
      if (bill != null) {
        commodities.addAll(bill.commodities());
      }
      // ★ 卖侧必须覆盖"本户持有、但账单里没有需求行"的商品（保留项为 0 ⇒ 全部是实际盈余）。
      commodities.addAll(ctx.householdGoods.getOrDefault(household, Map.of()).keySet());
      for (CommodityId commodity : sortedCommodities(commodities)) {
        long currentGoods = ProductionPlanBridge.goodsOf(ctx, household, commodity);
        long reserve = bill == null ? 0L : bill.marketReserveFor(commodity, restockDays);
        long gap = bill == null ? 0L : bill.marketGapFor(commodity, restockDays, currentGoods);
        if (bill != null) {
          // ★ 账单读数先落：即使本格没有牌价（不交易），marketPurchaseNeed 仍应可读。
          bill.applyMarketPurchaseNeed(commodity, gap, restockDays);
        }
        if (!market.hasPrice(commodity)) {
          continue;
        }
        // ★ 运输服务不进账单商品订单簿（与本轮 planner 的既有一致）：成市格的服务需求由其他商品购买派生，
        //   供给由运力池在跨格成交那一刻现卖；挂成普通买/卖会与冻结/交付腿冲突（RC8a 边界）。
        if (HaulService.HAUL_COMMODITY.equals(commodity) && HaulService.pricedAt(market)) {
          continue;
        }
        ActorRef actor = HouseholdActors.of(household);
        if (gap > 0L) {
          intents.add(
              new MarketIntent(
                  MarketIntentKind.COMMODITY,
                  MarketIntentSide.BUY,
                  actor,
                  row.view().hex(),
                  java.util.Optional.of(commodity),
                  gap,
                  // ★ 价格接受者：BUY 形状不允许带限价。
                  OptionalLong.empty(),
                  currency,
                  0,
                  java.util.Optional.empty()));
          buyIntents++;
        }
        long actualSurplus = Math.max(0L, currentGoods - reserve);
        if (actualSurplus > 0L) {
          intents.add(
              new MarketIntent(
                  MarketIntentKind.COMMODITY,
                  MarketIntentSide.SELL,
                  actor,
                  row.view().hex(),
                  java.util.Optional.of(commodity),
                  actualSurplus,
                  // ★ SELL 侧最低价语义不变（0 = 明确免费交易）。
                  OptionalLong.of(market.bidPriceOf(commodity)),
                  currency,
                  1,
                  java.util.Optional.empty()));
          sellIntents++;
        }
      }
    }
    // ★ LAND：仍是既有 MarketLandIntentBook 的标量语义；只有位置事实来源改成 M6 实际编排。
    intents.addAll(MarketLandIntentBook.landIntents(ctx, ctx.actualPositionFacts));
    if (MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_DAILY_DEMAND_BILL_INTENTS",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "households",
                  ctx.dailyDemandBills.size(),
                  "commodityBuys",
                  buyIntents,
                  "commoditySells",
                  sellIntents,
                  "landIntents",
                  intents.size() - buyIntents - sellIntents,
                  "reason",
                  "s7a-commodity-orders-from-s4-daily-demand-bill-and-post-s4-stock"));
    }
    return intents;
  }

  /**
   * ★ RF1：每个家户 BUY 意向必须可 grep 到"谁、买什么/买哪块地、多少、为什么是价格接受者"。
   *
   * <p>★ 日志失败不得影响结算（EventLog 内部吞异常；本方法只读已定稿的 intent 字段）。
   */
  private static void logBuyIntent(
      SettlementDayContext ctx, HouseholdId household, MarketIntent intent) {
    if (!MARKET.isDebugEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_INTENT_BUY",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.day,
                "actor",
                intent.actor().id(),
                "household",
                household.value(),
                "kind",
                intent.kind(),
                "commodity",
                intent.commodity().map(CommodityId::value).orElse("-"),
                "hex",
                intent.location().q() + "," + intent.location().r(),
                "quantity",
                intent.quantity(),
                "priceTaker",
                true,
                "currency",
                intent.payWith().value(),
                "sourceUnit",
                intent.sourceUnit().map(unit -> unit.value()).orElse("-"),
                "reason",
                "household-demand-quantity-from-real-gap-not-price-or-cash"));
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
