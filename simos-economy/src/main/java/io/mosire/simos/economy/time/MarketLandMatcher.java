package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ <b>P2f/P3d：土地撮合 —— 统一撮合入口内部的一个 package-private 土地分支</b>。
 *
 * <p>★★ <b>位置（P3d 字面收口）</b>：<b>只</b>由 {@code MarketMatcher.matchAll} 在<b>商品区内撮合 + 跨区撮合之后、
 * 冻结释放之前</b>调用；{@code MarketRoundRunner.clearOncePerCycle} 不再直接调用本类（runner 只有一次
 * matcher 调用 = {@code matchAll}）。商品订单先冻结/成交，土地买单因此只能使用 {@code spendableMoneyOf}
 * 里<b>剩余的可花额</b>（余额 − 商品冻结），同一买方同一币种不可能双付。
 *
 * <p>★ 商品与土地仍是<b>同一本</b> {@code MarketRound} 订单簿里的订单变体：公共入口/生命周期只有一个；
 * 本类只是该入口内部按订单变体分派出来的土地私有分支，不是第二个市场阶段/第二个 public matcher。
 *
 * <p>★★ <b>成交形状</b>（P2f 最小口径）：
 *
 * <pre>
 * 逐市场区（ctx.orderedRegions 的同一序）→ 逐块（shareId 升序）→ 一张卖单 + 按买方序取第一张能成交的买单
 * 成交价 = 该市场区 MarketTable.landPrice（区级一个价；empty 的市场根本不进工作集）
 * 数量   = 整块（订单已校验 == 份额数量；不拆分、不找零）
 * 买方序 = 纯库存财富降序 → HouseholdId 升序 → 订单稳定键（不使用净值/债务调整）
 * </pre>
 *
 * <p>★ 土地卖单的冻结是 {@link MatchContext#landSellFrozen} 里的瞬态 {@code AssetShareId}
 * 集合（同一块本轮只卖一次），<b>不写</b> actor 的 {@code frozenGoods}、不写第二本库存。成交写口在 {@link
 * MarketTradeExecutor#executeLandTrade}（现金腿走 {@code AccountTransferSettlement.applyTransfer}，产权腿只走
 * {@code OwnershipStakeBook.transferLandBlock}）。
 *
 * <p>★ <b>地价</b>：只对 {@code hasLandPrice()} 的市场，按本轮<b>订单簿的 D/S</b>（D = 买单数量之和、S = 卖单数量之和）用现有 {@link
 * MarketPricer#adaptiveNextPrice} 同口径算下一轮价；结果写入 {@code MarketRound.nextLandPrices}，轮末由 {@code
 * SettlementDayMarketPipeline} 写回。empty 市场不生成订单、不改价。
 */
final class MarketLandMatcher {

  private MarketLandMatcher() {}

  /**
   * ★★ <b>统一撮合入口里的土地分支</b>：校验订单簿 → 逐区逐块撮合 → 生成下一轮地价。
   *
   * <p>★ <b>唯一调用点</b> = {@code MarketMatcher.matchAll} 内部（package-private 子步骤，不是 runner 直接调用的第二个
   * matcher）。只允许在协调器单线程上调用；本方法不建 worker 副本、不并行。
   */
  static void match(MatchContext ctx) {
    List<LandOrder> book = MarketOrderPlanner.planLandOrders(ctx, ctx.round.landOrders());
    updateNextLandPrices(ctx, book);
    if (book.isEmpty()) {
      return;
    }
    // ★ 盘前快照（MarketRoundRunner 在本轮任何商品成交之前构造；null = 没有买单 ⇒ 排序退化为 HouseholdId/稳定键）。
    PureInventoryWealthBook.Snapshot wealth = ctx.landBuyerWealth;
    Map<MarketZoneId, Map<String, List<LandOrder>>> sellsByZoneShare = new LinkedHashMap<>();
    Map<MarketZoneId, Map<String, List<LandOrder>>> buysByZoneShare = new LinkedHashMap<>();
    for (LandOrder order : book) {
      MarketZoneId zoneId = MarketLandBook.zoneIdOf(ctx, order.location());
      if (zoneId == null) {
        continue;
      }
      Map<MarketZoneId, Map<String, List<LandOrder>>> target =
          order.side() == LandOrder.Side.SELL ? sellsByZoneShare : buysByZoneShare;
      target
          .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
          .computeIfAbsent(order.shareId().value(), ignored -> new ArrayList<>())
          .add(order);
    }
    for (MarketRegion region : ctx.orderedRegions) {
      MarketZoneId zoneId = new MarketZoneId(region.node().nodeId());
      MarketTable table = ctx.marketTables.get(zoneId);
      if (table == null || !table.hasLandPrice()) {
        continue; // empty 市场：不生成订单、不改价（工作集里本就不该有它的订单）
      }
      long landPrice = table.landPrice().getAsLong();
      Map<String, List<LandOrder>> sells = sellsByZoneShare.getOrDefault(zoneId, Map.of());
      Map<String, List<LandOrder>> buys = buysByZoneShare.getOrDefault(zoneId, Map.of());
      // 没有卖单的买单一律具名 NO_SELLER（不静默留在簿里）。
      for (Map.Entry<String, List<LandOrder>> entry : buys.entrySet()) {
        if (!sells.containsKey(entry.getKey())) {
          for (LandOrder buy : entry.getValue()) {
            MarketLandBook.logRejected(
                ctx,
                buy,
                MarketLandBook.LandRejectReason.NO_SELLER,
                "zone=" + zoneId.value() + ";share-has-no-sell-order-this-round");
          }
        }
      }
      List<String> shareIds = new ArrayList<>(sells.keySet());
      shareIds.sort(String::compareTo);
      for (String shareKey : shareIds) {
        List<LandOrder> shareSells = sells.get(shareKey);
        LandOrder sell = shareSells.get(0);
        if (!ctx.landSellFrozen.add(sell.shareId())) {
          MarketLandBook.logRejected(
              ctx,
              sell,
              MarketLandBook.LandRejectReason.DUPLICATE_SELL,
              "share-already-frozen-by-an-earlier-match-this-round");
          continue;
        }
        if (landPrice < sell.limitPricePerMu()) {
          MarketLandBook.logRejected(
              ctx,
              sell,
              MarketLandBook.LandRejectReason.PRICE_LIMIT,
              "landPrice=" + landPrice + ";sellerMinPricePerMu=" + sell.limitPricePerMu());
          continue;
        }
        List<LandOrder> candidates = new ArrayList<>(buys.getOrDefault(shareKey, List.of()));
        if (candidates.isEmpty()) {
          MarketLandBook.logRejected(
              ctx,
              sell,
              MarketLandBook.LandRejectReason.NO_BUYER,
              "zone=" + zoneId.value() + ";share-has-no-buy-order-this-round");
          continue;
        }
        candidates.sort(buyerOrder(ctx, wealth));
        OwnershipStake share = ctx.round.assetShares.get(sell.shareId());
        if (!MarketLandBook.tradableLand(share)) {
          MarketLandBook.logRejected(
              ctx,
              sell,
              MarketLandBook.LandRejectReason.SHARE_MISSING,
              "share-disappeared-between-validation-and-match");
          continue;
        }
        boolean filled = false;
        for (LandOrder buy : candidates) {
          if (buy.actor().equals(sell.actor())) {
            MarketLandBook.logRejected(
                ctx, buy, MarketLandBook.LandRejectReason.SELF_TRADE, "same-actor-on-both-sides");
            continue;
          }
          if (landPrice > buy.limitPricePerMu()) {
            MarketLandBook.logRejected(
                ctx,
                buy,
                MarketLandBook.LandRejectReason.PRICE_LIMIT,
                "landPrice=" + landPrice + ";buyerMaxPricePerMu=" + buy.limitPricePerMu());
            continue;
          }
          if (!buy.payWith().equals(sell.payWith())) {
            MarketLandBook.logRejected(
                ctx,
                buy,
                MarketLandBook.LandRejectReason.CURRENCY_MISMATCH,
                "buyPayWith=" + buy.payWith().value() + ";sellPayWith=" + sell.payWith().value());
            continue;
          }
          long payment = MarketLandBook.landValueMilli(share.quantity(), landPrice);
          if (payment > buy.maxBudget()) {
            MarketLandBook.logRejected(
                ctx,
                buy,
                MarketLandBook.LandRejectReason.NO_BUDGET,
                "paymentMilli=" + payment + ";maxBudgetMilli=" + buy.maxBudget());
            continue;
          }
          // 唯一土地成交写口：现金腿 + 可选的普通货币债 + 产权腿（见 executeLandTrade）。
          if (MarketTradeExecutor.executeLandTrade(ctx, buy, sell, share, landPrice)) {
            filled = true;
            break;
          }
        }
        if (!filled) {
          MarketLandBook.logRejected(
              ctx,
              sell,
              MarketLandBook.LandRejectReason.NO_BUYER,
              "no-buyer-could-pay-this-block-at-landPrice=" + landPrice);
        }
      }
    }
  }

  /**
   * ★★ <b>下一轮土地价</b>：只对 {@code hasLandPrice()} 的市场，D/S 取<b>本轮工作集</b>（全额买单 / 全额卖单），用 {@link
   * MarketPricer#adaptiveNextPrice} 的同一算式；值不变的区不写（缺省中性）。写入 {@code MarketRound.nextLandPrices}。
   *
   * <p>★ <b>为什么 D 不扣成交结果</b>：与商品侧的 v3.5 §2.1 同口径 —— D 是"想要的总量"，成交受现金/对手方约束，那些是撮合期事实；
   * 若再扣一遍，缺货压力会被抹掉。★ <b>S = 卖单总量</b>（P2f 不建模"运不出去的土地"：土地不移动）。
   */
  private static void updateNextLandPrices(MatchContext ctx, List<LandOrder> book) {
    if (!MarketConstants.MARKET_ADAPTIVE_PRICING_ENABLED) {
      // ★ 与商品改价同一个总开关：固定报价模式下土地价也一律不动（缺省语义中性）。
      return;
    }
    Map<MarketZoneId, long[]> demandSupply = new LinkedHashMap<>();
    for (LandOrder order : book) {
      MarketZoneId zoneId = MarketLandBook.zoneIdOf(ctx, order.location());
      if (zoneId == null) {
        continue;
      }
      long[] slot =
          demandSupply.computeIfAbsent(
              zoneId,
              ignored -> {
                return new long[2];
              });
      if (order.side() == LandOrder.Side.BUY) {
        slot[0] = Math.addExact(slot[0], order.quantityMilliMu());
      } else {
        slot[1] = Math.addExact(slot[1], order.quantityMilliMu());
      }
    }
    for (MarketRegion region : ctx.orderedRegions) {
      MarketZoneId zoneId = new MarketZoneId(region.node().nodeId());
      MarketTable table = ctx.marketTables.get(zoneId);
      if (table == null || !table.hasLandPrice()) {
        continue; // empty：不生成土地订单、不改价
      }
      long reference = table.landPrice().getAsLong();
      long[] slot = demandSupply.getOrDefault(zoneId, new long[2]);
      long demand = slot[0];
      long supply = slot[1];
      long alphaPerMille = MarketPricer.priceAlphaPerMilleOf(ctx, zoneId);
      long next = MarketPricer.adaptiveNextPrice(reference, demand, supply, alphaPerMille);
      if (next == reference) {
        continue;
      }
      ctx.round.recordNextLandPrice(zoneId, next);
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "LAND_PRICE_UPDATE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "market",
                  zoneId.value(),
                  "landPriceBefore",
                  reference,
                  "landPriceAfter",
                  next,
                  "demandMilliMu",
                  demand,
                  "supplyMilliMu",
                  supply,
                  "alphaPerMille",
                  alphaPerMille,
                  "reason",
                  "land-price-adaptive-next-round-same-formula-as-commodity"));
    }
  }

  /** 买方的稳定全序：纯库存财富降序 → HouseholdId 升序 → 订单稳定键（内容纯函数）。 */
  private static Comparator<LandOrder> buyerOrder(
      MatchContext ctx, PureInventoryWealthBook.Snapshot wealth) {
    return (left, right) -> {
      int byWealth = Long.compare(wealthOf(ctx, wealth, right), wealthOf(ctx, wealth, left));
      if (byWealth != 0) {
        return byWealth;
      }
      int byHousehold = householdKeyOf(ctx, left).compareTo(householdKeyOf(ctx, right));
      if (byHousehold != 0) {
        return byHousehold;
      }
      return left.stableKey().compareTo(right.stableKey());
    };
  }

  private static long wealthOf(
      MatchContext ctx, PureInventoryWealthBook.Snapshot wealth, LandOrder order) {
    if (wealth == null) {
      return 0L;
    }
    HouseholdId household = ctx.round.householdOfActor.get(order.actor());
    return household == null ? 0L : wealth.wealthOf(household);
  }

  private static String householdKeyOf(MatchContext ctx, LandOrder order) {
    HouseholdId household = ctx.round.householdOfActor.get(order.actor());
    return household == null ? "" : household.value();
  }

  /**
   * ★★ <b>轮级土地读数（INFO，每轮一条）</b>：订单数 / 成交笔数 / 赊购 / 本轮冻结块数 / 下一轮地价。空轮（无订单、无成交、无改价）
   * 一条都不发（缺省中性：没有土地订单的世界日志面逐字不变）。
   */
  static void logRoundSummary(MatchContext ctx) {
    if (!MARKET.isInfoEnabled()) {
      return;
    }
    if (ctx.round.landOrders.isEmpty()
        && ctx.landFills.isEmpty()
        && ctx.round.nextLandPrices.isEmpty()) {
      return;
    }
    long tradedMilliMu = 0L;
    long tradedMilli = 0L;
    for (MarketLandBook.LandFill fill : ctx.landFills) {
      tradedMilliMu = Math.addExact(tradedMilliMu, fill.quantityMilliMu());
      tradedMilli = Math.addExact(tradedMilli, fill.paymentMilli());
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_LAND_ROUND",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "landOrders",
                ctx.round.landOrders.size(),
                "landFills",
                ctx.landFills.size(),
                "tradedQuantityMilliMu",
                tradedMilliMu,
                "tradedPaymentMilli",
                tradedMilli,
                "deferredFills",
                ctx.landDeferredFills,
                "deferredMilli",
                ctx.landDeferredMilli,
                "frozenBlocks",
                ctx.landSellFrozen.size(),
                "nextLandPrices",
                ctx.round.nextLandPrices(),
                "reason",
                "land-orders-matched-in-the-same-market-round-as-commodities"));
  }
}
