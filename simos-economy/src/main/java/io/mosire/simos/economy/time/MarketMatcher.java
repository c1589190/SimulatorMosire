package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketFreezeBook.buyFrozenAxis;
import static io.mosire.simos.economy.time.MarketFreezeBook.sellFrozenAxis;
import static io.mosire.simos.economy.time.MarketIndexBook.MarketIndexes;
import static io.mosire.simos.economy.time.MarketMatchSupport.BuySlotState;
import static io.mosire.simos.economy.time.MarketMatchSupport.FillIntent;
import static io.mosire.simos.economy.time.MarketMatchSupport.RegionOutcome;
import static io.mosire.simos.economy.time.MarketMatchSupport.SellSlotState;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketMoneyBook.buyPartsByPriority;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketPairingBook.pairUp;
import static io.mosire.simos.economy.time.MarketPortBook.matchAcrossRegions;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;
import static io.mosire.simos.economy.time.MarketTradeExecutor.executeTrade;
import static io.mosire.simos.economy.time.MarketTradeMath.chargeAffordableQuantity;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketMatcher {

  private MarketMatcher() {}

  /**
   * ★★★ <b>D-032 P3d：统一撮合入口（唯一 public 入口；{@code MarketRoundRunner.clearOncePerCycle} 只调它一次）</b>。
   *
   * <p>商品与土地在同一份 {@link MarketRound} 订单簿、同一轮冻结生命周期里撮合，按订单变体分派：
   *
   * <pre>
   * ① 区内商品撮合（既有算法/数值不变）
   * ② 跨区商品撮合（既有算法/数值不变）
   * ③ 土地私有分支（{@link #matchLand} 只在入口内部被调用，不是第二个 runner 级 matcher）
   * </pre>
   *
   * <p>★ 商品先于土地：土地买单只能使用商品冻结后的剩余可花额（{@code spendableMoneyOf}），同一买方同一币种不会双付。 ★
   * 土地分支的整块/区级价/纯库存财富买方序全部沿用既有实现，本入口只改“调用结构”不改数值。
   */
  static void matchAll(MatchContext ctx, EconomyParallelism parallelism, MarketIndexes indexes) {
    matchWithinRegions(ctx, parallelism, indexes);
    matchAcrossRegions(ctx, indexes);
    // ★ 土地是同一本订单簿里的订单变体：作为统一入口内部的分支运行（公共入口/生命周期仍只有一个）。
    matchLand(ctx);
  }

  // ── 土地私有分支（P2f/P3d：同一入口内部的订单变体分派；不是第二个 matcher）──────────────────

  /**
   * ★★★ <b>P2f/P3d：土地撮合 —— 统一撮合入口内部的私有分支</b>。
   *
   * <p>★★ <b>位置（P3d 字面收口）</b>：<b>只</b>由 {@link #matchAll} 在<b>商品区内撮合 + 跨区撮合之后、 冻结释放之前</b>调用；{@code
   * MarketRoundRunner.clearOncePerCycle} 不再直接调用任何土地 matcher（runner 只有一次 matcher 调用 = {@code
   * matchAll}）。商品订单先冻结/成交，土地买单因此只能使用 {@code spendableMoneyOf} 里<b>剩余的可花额</b>（余额 −
   * 商品冻结），同一买方同一币种不可能双付。
   *
   * <p>★ 商品与土地仍是<b>同一本</b> {@code MarketRound} 订单簿里的订单变体：公共入口/生命周期只有一个；
   * 本方法是该入口内部按订单变体分派出来的土地私有分支，不是第二个市场阶段/第二个 matcher。
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
   * MarketTradeExecutor#executeLandTrade}（现金腿走 {@code
   * AccountTransferSettlement.applyTransfer}，产权腿只走 {@code OwnershipStakeBook.transferLandBlock}）。
   *
   * <p>★ <b>地价</b>：只对 {@code hasLandPrice()} 的市场，按本轮<b>订单簿的 D/S</b>（D = 买单数量之和、S = 卖单数量之和）用现有
   * {@link MarketPricer#adaptiveNextPrice} 同口径算下一轮价；结果写入 {@code MarketRound.nextLandPrices}，轮末由
   * {@code SettlementDayMarketPipeline} 写回。empty 市场不生成订单、不改价。
   */
  private static void matchLand(MatchContext ctx) {
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

  // ── 区内撮合（按市场区并行算意向；协调器按拓扑区序回放）────────────────────────────────

  /**
   * 区内逐 (region, commodity) 撮合：参考价 = **集散节点格的市场价**（M2.6 的"区价"）；买方限价 = ask、卖方底价 = bid，
   * 两侧限价在这里按参考价过滤（正常时 ask ≥ 参考价 ≥ bid ⇒ 全部通过；成员格价表不同时由限价真的把单挡下）。
   *
   * <p>★★ <b>并行形态</b>（见 {@code clearOncePerCycle} 的 R2 注释）：按市场区把"同区同商品池"分给 worker；worker 在
   * <b>本区账户副本</b>上用同一条 {@code matchGroup} 算出 {@code FillIntent}，不碰共享账户表；协调器收集后按 {@code
   * topology.regions()} 的拓扑区序逐个回放（每笔仍走 {@code executeTrade} → 唯一写口 {@code applyTransfer}），
   * 冻结写只发生在协调器阶段（M8）。回放序 = 拓扑区序 × 区内商品序 × 槽位 canonical 序，与线程到达序无关。
   */
  static void matchWithinRegions(
      MatchContext ctx, EconomyParallelism parallelism, MarketIndexes indexes) {
    List<MarketRegion> regions = new ArrayList<>();
    // ★★ W4：区遍历序 = 结算序（上周期名义成交额降序 → 区 id 升序），不是拓扑声明序。
    for (MarketRegion region : ctx.orderedRegions) {
      if (ctx.tableOf(region) == null) {
        continue; // 串行路径的同款跳过：锚格没有市场表 ⇒ 这个区没有可交易报价
      }
      String regionId = region.node().nodeId();
      if (!indexes.buysByRegionCommodity.containsKey(regionId)
          && !indexes.sellsByRegionCommodity.containsKey(regionId)) {
        continue; // 本区没有买卖单 ⇒ 撮合无事可做（省一次空副本）
      }
      regions.add(region);
    }
    if (regions.isEmpty()) {
      return;
    }
    // ★★ M-A1：有运力池（= 有"选了跑商的家户"）时区内跨格也要由它们承运 —— 运力是**逐 hex 每轮一份**的硬约束，
    //   分散在并行 worker 里各自持副本会超发。⇒ 有运力池的世界改成协调器单线程按拓扑区序直接撮合
    //   （与 RegionClone.run 的区内序逐字同源），跨格/区内共用一个真实运力池。
    //   ★ 池空的世界继续走并行：那里**没有任何格有运力** ⇒ 跨格路线在候选生成处就不建（pairUp 的具名拦下），
    //     worker 与协调器看到的是同一个"全空"事实 ⇒ 1/4/8 线程逐值相同（I7/N7）。
    if (!ctx.carrierPool.isEmpty()) {
      matchWithinRegionsSerial(ctx, regions, indexes);
      return;
    }
    // ★★ worker 不能读 AccountSession 的活视图（owner 守卫在第一次 get 就抛）⇒ 本区账户副本必须在**协调器线程**
    //   先建好；ExecutorService.submit 的 happens-before 把建好的副本安全发布给 worker（worker 只改自己的副本）。
    Map<String, RegionClone> clonesById = new LinkedHashMap<>();
    for (MarketRegion region : regions) {
      RegionClone clone = prepareRegion(region, ctx, indexes);
      clonesById.put(clone.regionId, clone);
    }
    List<String> regionIds = new ArrayList<>(clonesById.keySet());
    PartitionPlan plan =
        PartitionPlan.of(SettlementStage.LOCAL_MARKET, regionIds, parallelism.partitionCount());
    List<List<RegionOutcome>> outcomesByPartition =
        SettlementExecutor.execute(
            plan,
            partition -> {
              List<RegionOutcome> outcomes = new ArrayList<>(partition.size());
              for (String regionId : partition.canonicalKeys()) {
                outcomes.add(clonesById.get(regionId).run(indexes));
              }
              return outcomes;
            },
            parallelism.poolOrNull());
    Map<String, RegionOutcome> outcomesByRegion = new LinkedHashMap<>();
    for (List<RegionOutcome> partitionOutcomes : outcomesByPartition) {
      for (RegionOutcome outcome : partitionOutcomes) {
        outcomesByRegion.put(outcome.regionId(), outcome);
      }
    }
    for (MarketRegion region : regions) {
      RegionOutcome outcome = outcomesByRegion.get(region.node().nodeId());
      if (outcome != null) {
        replayRegionOutcome(ctx, outcome);
        // ★★ P-T1c：区级配额已删（设计书 §16）⇒ 不再有"worker 配额余量并回协调器"这一步；
        //   区内撮合按账号因果链回放，不需要额外的跨 worker 共享预算表。
      }
    }
  }

  /**
   * ★★ <b>有运力池时的区内串行撮合</b>（2026-10-09 立、2026-10-10 M-A1 改绑）：与 {@code RegionClone.run} 同一区内顺序 （拓扑区序
   * × 商品序 × 槽位插入序），但直接在协调器 {@code ctx} 上成交 —— 区内跨格运费因此与跨区运费共用同一个 {@link MerchantCapacityPool}，逐 hex
   * 运力不会被各 worker 副本重复发放。
   */
  static void matchWithinRegionsSerial(
      MatchContext ctx, List<MarketRegion> regions, MarketIndexes indexes) {
    for (MarketRegion region : regions) {
      String regionId = region.node().nodeId();
      MarketTable anchorMarket = ctx.tableOf(region);
      if (anchorMarket == null) {
        continue;
      }
      Map<CommodityId, List<BuySlot>> buysByCommodity =
          indexes.buysByRegionCommodity.getOrDefault(regionId, Map.of());
      Map<CommodityId, List<SellSlot>> sellsByCommodity =
          indexes.sellsByRegionCommodity.getOrDefault(regionId, Map.of());
      for (CommodityId commodity : indexes.commodities) {
        if (!anchorMarket.hasPrice(commodity)) {
          continue;
        }
        long price = anchorMarket.priceOf(commodity);
        List<BuySlot> buys = activeBuys(buysByCommodity.get(commodity), price, ctx.round.day);
        if (buys.isEmpty()) {
          continue;
        }
        List<SellSlot> sells = activeSells(sellsByCommodity.get(commodity), price, ctx.round.day);
        if (sells.isEmpty()) {
          continue;
        }
        matchGroup(ctx, buys, sells, price, null);
      }
    }
  }

  /**
   * <b>协调器线程</b>里建立一个区的私有副本：买卖单槽位复制 + 本区参与者账户表复制 + 本地账本/冻结轴累计。
   *
   * <p>★★ <b>为什么必须在协调器线程建</b>：账户表是 {@link AccountSession} 的活视图，owner 守卫在 worker 线程第一次读时就抛 ——
   * 这正是"worker 只准拿不可变快照/意向缓冲"的结构化边界。建好的副本经 {@code ExecutorService.submit} 的 happens-before 安全发布给
   * worker；worker 之后只改自己的副本。
   *
   * <p>★ <b>为什么副本只含本区参与者</b>：账户键 {@code (actor, location)} 的 actor 只属于一个格、一个区；本区成交的付方/收方都是本区
   * participant ⇒ 副本足以让本区内的因果链（付款 → 余额 → 下一笔可付）逐值复现。跨区成交不在 worker 里做（R3 的 P3）。
   */
  static RegionClone prepareRegion(MarketRegion region, MatchContext ctx, MarketIndexes indexes) {
    String regionId = region.node().nodeId();
    Map<CommodityId, List<BuySlot>> localBuysByCommodity = new LinkedHashMap<>();
    List<BuySlot> localBuys = new ArrayList<>();
    for (Map.Entry<CommodityId, List<BuySlot>> entry :
        indexes.buysByRegionCommodity.getOrDefault(regionId, Map.of()).entrySet()) {
      List<BuySlot> copies = new ArrayList<>(entry.getValue().size());
      for (BuySlot buy : entry.getValue()) {
        BuySlot copy = new BuySlot(buy);
        copies.add(copy);
        localBuys.add(copy);
      }
      localBuysByCommodity.put(entry.getKey(), copies);
    }
    Map<CommodityId, List<SellSlot>> localSellsByCommodity = new LinkedHashMap<>();
    List<SellSlot> localSells = new ArrayList<>();
    for (Map.Entry<CommodityId, List<SellSlot>> entry :
        indexes.sellsByRegionCommodity.getOrDefault(regionId, Map.of()).entrySet()) {
      List<SellSlot> copies = new ArrayList<>(entry.getValue().size());
      for (SellSlot sell : entry.getValue()) {
        SellSlot copy = new SellSlot(sell);
        copies.add(copy);
        localSells.add(copy);
      }
      localSellsByCommodity.put(entry.getKey(), copies);
    }
    Map<ActorRef, Participant> regionParticipants = new LinkedHashMap<>();
    for (BuySlot buy : localBuys) {
      regionParticipants.putIfAbsent(buy.buyer.actor(), buy.buyer);
    }
    for (SellSlot sell : localSells) {
      regionParticipants.putIfAbsent(sell.seller.actor(), sell.seller);
    }
    // 八张账户表的本区副本：外层表复制（worker 只改自己的副本），内层表只读换新（与 applyTransfer 的写法逐字相容）。
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney = new LinkedHashMap<>();
    Map<HouseholdId, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    for (Participant participant : regionParticipants.values()) {
      // ★★ P2-A §13.3：参与者恒为家户（participantsFor 已把经营者角色解析到家户；解析不到的聚合主体
      //   不进入市场——具名缺口，见那里的注释）。
      HouseholdId household = participant.household();
      householdGoods.put(household, copyBalances(ctx.round.householdGoods, household));
      householdMoney.put(household, copyBalances(ctx.round.householdMoney, household));
      householdFrozenGoods.put(household, copyBalances(ctx.round.householdFrozenGoods, household));
      householdFrozenMoney.put(household, copyBalances(ctx.round.householdFrozenMoney, household));
      Map<CommodityId, Long> recorded = ctx.round.unmetToday.get(household);
      if (recorded != null) {
        unmetToday.put(household, new LinkedHashMap<>(recorded));
      }
    }
    // ★★ 2026-10-08：改走克隆的唯一拼写点（旧版在这里手抄 20 个构造参数 ⇒ 同样会静默丢掉后来新增的字段）。
    //   ★ 本地账本：worker 铸造的转移只服务于本地 applyTransfer，交回后丢弃；协调器回放时在全局累加器上重铸。
    MarketRound localRound =
        ctx.round.copyForWorker(
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            unmetToday,
            new ProductionLedger.Accumulator(ctx.round.day));
    MatchContext local =
        new MatchContext(
            localRound,
            ctx.marketTables,
            ctx.topology,
            // ★★ M-A1：worker 副本不持真实运力池（它是逐笔扣减的可变对象、只允许协调器单线程触碰）。
            //   ★ 为什么是安全的：只有池**空**的世界才走并行路径 ⇒ 协调器与 worker 看到的是同一个"全空"事实。
            MerchantCapacityPool.empty(),
            // ★★ D-027：worker 只读本区适用的调控（配额在本区副本上扣，回放后并回协调器）。
            ctx.regulationFor(regionId),
            // ★★ E：**协调器那一份**"钱的价"（含全部家户的流通币种）—— 副本自己现算会漂开，
            //   而"认不认得出某种钱"必须两边同一个答案（否则回放对不上，具名抛）。
            ctx.currencyValuation(),
            // ★★ W4：worker 副本照抄**协调器那一份**结算序快照（盘前构造、不可变）——
            //   副本自己现算会在"快照该在哪一刻取"上漂开（那正是"禁自指"要挡的），也不可复现。
            ctx.ordering);
    // ★★ M-C：worker 副本照抄同一份纯商号集合（它只跑 route == null 的区内同格意向 ⇒ 判据不可达，但两份上下文保持一致）。
    local.pureMerchantHouseholds = ctx.pureMerchantHouseholds;
    // ★★ W2：worker 副本照抄同一份区表（自适应定价在协调器跑；这里保持两份上下文一致，同 M-C 的理由）。
    local.marketZones = ctx.marketZones;
    local.buys.addAll(localBuys);
    local.sells.addAll(localSells);
    local.recordFillIntents = true;
    for (BuySlot buy : localBuys) {
      local.buyFrozenSums.merge(buyFrozenAxis(buy), buy.frozenRemaining, Long::sum);
    }
    for (SellSlot sell : localSells) {
      local.sellFrozenSums.merge(sellFrozenAxis(sell), sell.frozenRemaining, Long::sum);
    }
    return new RegionClone(
        regionId,
        localBuysByCommodity,
        localSellsByCommodity,
        localBuys,
        localSells,
        local,
        ctx.tableOf(region),
        ctx.round.day);
  }

  /**
   * 一个区的 worker 私有工作集：协调器建好副本后交给 worker，worker 只在本对象内跑同一条 {@code matchGroup}，交回不可变结果。
   *
   * <p>★ 本类<b>不</b>持有 {@link AccountSession}、{@link MarketRound} 的活账户视图或任何共享可写表 ——
   * 线程安全靠"每区一个实例、实例只被一个 worker 触碰"的结构保证。
   */
  static final class RegionClone {

    final String regionId;
    final Map<CommodityId, List<BuySlot>> buysByCommodity;
    final Map<CommodityId, List<SellSlot>> sellsByCommodity;
    final List<BuySlot> allBuys;
    final List<SellSlot> allSells;
    final MatchContext local;
    final MarketTable anchorMarket;
    final long day;

    RegionClone(
        String regionId,
        Map<CommodityId, List<BuySlot>> buysByCommodity,
        Map<CommodityId, List<SellSlot>> sellsByCommodity,
        List<BuySlot> allBuys,
        List<SellSlot> allSells,
        MatchContext local,
        MarketTable anchorMarket,
        long day) {
      this.regionId = regionId;
      this.buysByCommodity = buysByCommodity;
      this.sellsByCommodity = sellsByCommodity;
      this.allBuys = allBuys;
      this.allSells = allSells;
      this.local = local;
      this.anchorMarket = anchorMarket;
      this.day = day;
    }

    /** worker 入口：按商品序跑区内撮合，交回意向 + 槽位终态（不读共享账户表）。 */
    RegionOutcome run(MarketIndexes indexes) {
      if (anchorMarket != null) {
        for (CommodityId commodity : indexes.commodities) {
          // ★★ P-T1c：区内参考价 = 卖方格价表的固定报价（与 ordersFor 同一条口径）—— 区级覆盖已删。
          //    ★ 2026-10-09：有定价行（含明确 0 价免费）都进撮合；只有"从未定价"才跳过。
          if (!anchorMarket.hasPrice(commodity)) {
            continue;
          }
          long price = anchorMarket.priceOf(commodity);
          List<BuySlot> buys = activeBuys(buysByCommodity.get(commodity), price, day);
          if (buys.isEmpty()) {
            continue;
          }
          List<SellSlot> sells = activeSells(sellsByCommodity.get(commodity), price, day);
          if (sells.isEmpty()) {
            continue;
          }
          matchGroup(local, buys, sells, price, null);
        }
      }
      List<BuySlotState> buyStates = new ArrayList<>(allBuys.size());
      for (BuySlot buy : allBuys) {
        buyStates.add(
            new BuySlotState(
                buy.orderIndex,
                buy.remaining,
                buy.frozenRemaining,
                buy.spentMilli,
                buy.noMoney,
                buy.blocked,
                buy.capacityTruncatedMilli));
      }
      List<SellSlotState> sellStates = new ArrayList<>(allSells.size());
      for (SellSlot sell : allSells) {
        sellStates.add(
            new SellSlotState(
                sell.orderIndex,
                sell.remaining,
                sell.frozenRemaining,
                sell.blocked,
                sell.capacityBlocked,
                sell.capacityTruncatedMilli));
      }
      return new RegionOutcome(
          regionId, List.copyOf(local.fillIntents), buyStates, sellStates, local.round.unmetToday);
    }
  }

  /** 买方槽位的动态过滤（与串行扫描里的条件逐字相同；桶内相对序不变 ⇒ ProportionalSplit 下标序不变）。 */
  static List<BuySlot> activeBuys(List<BuySlot> source, long price, long day) {
    if (source == null) {
      return List.of();
    }
    List<BuySlot> result = new ArrayList<>();
    for (BuySlot buy : source) {
      if (buy.remaining > 0
          && !buy.noMoney
          && buy.order.maxLandedPrice() >= price
          && buy.order.latestArrivalTick() >= day) {
        result.add(buy);
      }
    }
    return result;
  }

  /** 卖方槽位的动态过滤（与串行扫描里的条件逐字相同）。 */
  static List<SellSlot> activeSells(List<SellSlot> source, long price, long day) {
    if (source == null) {
      return List.of();
    }
    List<SellSlot> result = new ArrayList<>();
    for (SellSlot sell : source) {
      if (sell.remaining > 0
          && sell.order.minPrice() <= price
          && sell.order.availableFromTick() <= day) {
        result.add(sell);
      }
    }
    return result;
  }

  /**
   * ★★ <b>按拓扑区序回放一个区的意向</b>：每笔走与 worker 内逐字同一条 {@code executeTrade}（唯一写口），随后校验并抄回槽位终态。
   *
   * <p>★ <b>为什么校验会抛</b>：回放只按 worker 已算好的序列执行；{@code remaining}/{@code frozenRemaining}/{@code
   * spentMilli} 若与 worker 不一致，说明分区副本或回放序漂开了 —— 这正是"不许按到达序兜底"要判死的形态，宁抛不静默改数。
   */
  static void replayRegionOutcome(MatchContext ctx, RegionOutcome outcome) {
    for (FillIntent fill : outcome.fills()) {
      BuySlot buy = ctx.buys.get(fill.buyIndex());
      SellSlot sell = ctx.sells.get(fill.sellIndex());
      // ★ canonical 键自检：worker 的意向必须落在它声明的 (区, 商品, 卖方, 买方) 槽位上；回放序仍是
      //   topology 区序 × worker 成交序（内容的纯函数），不按 (seller,buyer) 重排 —— 重排会改变同一区内
      //   已由串行基准确定的逐笔顺序与冻结释放中间态。
      if (!fill.regionId().equals(sell.region.node().nodeId())
          || !fill.commodity().equals(sell.order.commodity())
          || !fill.sellerKey().equals(actorKeyOf(sell.seller.actor()))
          || !fill.buyerKey().equals(actorKeyOf(buy.buyer.actor()))) {
        throw new IllegalStateException(
            "FillIntent 的 canonical 键与回放槽位不一致（分区/拼接漂开）：买槽="
                + fill.buyIndex()
                + " 卖槽="
                + fill.sellIndex());
      }
      executeTrade(ctx, buy, sell, fill.quantity(), fill.unitPrice(), null);
      buy.remaining -= fill.quantity();
      sell.remaining -= fill.quantity();
    }
    for (BuySlotState state : outcome.buyStates()) {
      BuySlot buy = ctx.buys.get(state.orderIndex());
      if (buy.remaining != state.remaining()
          || buy.frozenRemaining != state.frozenRemaining()
          || buy.spentMilli != state.spentMilli()) {
        throw new IllegalStateException(
            "区内市场回放与 worker 分区计算漂开（拒绝按到达序静默兜底）：买槽=" + state.orderIndex());
      }
      buy.noMoney = state.noMoney();
      buy.blocked = state.blocked();
      buy.capacityTruncatedMilli = state.capacityTruncatedMilli();
    }
    for (SellSlotState state : outcome.sellStates()) {
      SellSlot sell = ctx.sells.get(state.orderIndex());
      if (sell.remaining != state.remaining() || sell.frozenRemaining != state.frozenRemaining()) {
        throw new IllegalStateException(
            "区内市场回放与 worker 分区计算漂开（拒绝按到达序静默兜底）：卖槽=" + state.orderIndex());
      }
      // ★★ E：拒因（市场性理由）也是槽位状态的一部分 —— 不带回来，读数会把"卖方说不出这个价"误报成
      //   NO_BUYER/OUTCOMPETED（归因失真，N1 的判据就看不见了）。
      sell.blocked = state.blocked();
      // ★★ M-A1：被运力截断量同样带回（V-20 的并行路径落点）。
      sell.capacityBlocked = state.capacityBlocked();
      sell.capacityTruncatedMilli = state.capacityTruncatedMilli();
    }
    ctx.round.unmetToday.putAll(copyUnmet(outcome.unmetToday()));
  }

  /** 深拷贝 {@code unmetToday} 的一层两层表（worker 交出的读取物与共享表脱钩）。 */
  static Map<HouseholdId, Map<CommodityId, Long>> copyUnmet(
      Map<HouseholdId, Map<CommodityId, Long>> source) {
    Map<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : source.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  /** ★ 审计/稳定键用的主体 canonical 串（只进意向记录，不参与任何判定）。 */
  static String actorKeyOf(ActorRef actor) {
    return actor.kind() + ":" + actor.id();
  }

  /** 复制一张账户表里某一主体的内层余额表（内层表只读；改值一律由 applyTransfer 换新表）。 */
  static <K, I> Map<I, Long> copyBalances(Map<K, Map<I, Long>> table, K key) {
    return new LinkedHashMap<>(table.getOrDefault(key, Map.of()));
  }

  /**
   * ★★ <b>一个区内分组（已是同一 region × commodity）：按成本从低到高分档配给</b>—— 同成本层内仍走 {@link
   * ProportionalSplit#byDenominator}（溢出已由 S0 修复），成交价仍由参考价决定。
   *
   * <p>★★ <b>S3 成本排序（计划 §S3.1）</b>：卖方先按 {@code unitCostEstimate + freightPerUnit} 升序、同成本按 {@code
   * (hex, actor)} canonical 升序；买方剩余需求优先分配给<b>最低成本层</b>，该层吃满才轮到下一层。 同层内"按可用量与需求比例分配"的语义逐字保留（现有
   * {@code ProportionalSplit}）。
   *
   * <p>★ <b>成本只改"谁先被选"</b>：{@code price} 一路不变，成交单价与限价过滤都不看成本（计划明文）。
   *
   * <p>★★ <b>P-T1d：顺序来自"服务序"（卖侧 = 成本序或置顶名次，买侧 = 列表序或置顶名次）</b>， 而"档"=
   * <b>相邻且同成本、同置顶状态</b>的连续段；买侧按"档"（相邻同置顶状态的连续段）逐段配给。 <b>没有</b>政府要求管控的世界：卖侧服务序 == 成本序、买侧只有一段 ==
   * 改前的唯一一组 ⇒ 逐值不变（I-C2）。
   */
  static void matchGroup(
      MatchContext ctx, List<BuySlot> buys, List<SellSlot> sells, long price, RouteContext route) {
    if (buys.isEmpty() || sells.isEmpty()) {
      return;
    }
    List<BuySlot> orderedBuys = procurementOrderedBuys(ctx, buys);
    List<List<BuySlot>> buyRuns = procurementBuyRuns(orderedBuys);
    List<SellSlot> ordered = new ArrayList<>(sells);
    ordered.sort(servingOrder(ctx, route));
    int tierStart = 0;
    while (tierStart < ordered.size()) {
      long tierCost = landedCostOf(ordered.get(tierStart), route);
      boolean tierOvertook = ordered.get(tierStart).procurementOvertook;
      int tierEnd = tierStart + 1;
      while (tierEnd < ordered.size()
          && landedCostOf(ordered.get(tierEnd), route) == tierCost
          && ordered.get(tierEnd).procurementOvertook == tierOvertook) {
        tierEnd++;
      }
      List<SellSlot> tier = new ArrayList<>(tierEnd - tierStart);
      for (int i = tierStart; i < tierEnd; i++) {
        SellSlot sell = ordered.get(i);
        if (sell.remaining > 0L) {
          tier.add(sell);
        }
      }
      // ★ 需求按**当前剩余**重算：上一层吃掉的量不再计入（"最低成本层优先"因此是逐层的，不是一次性预分配）。
      //   ★ P-T1d：逐"买档"再重算一次（缺省只有一段 ⇒ 与改前逐值相同）。
      for (List<BuySlot> run : buyRuns) {
        long[] weights = new long[run.size()];
        long demand = 0L;
        for (int i = 0; i < run.size(); i++) {
          // ★★ P-T5b：本档的价格 `price` 是**本格计价币**的参考价，而 chargeAffordableQuantity 收的是**买方支付币**单价
          //   （3c 起其余三处调用点传的都是折过的单价）⇒ 这里补上同一次折算（同币 ⇒ 原样，逐值不变）。
          //   ★ 说不出这种钱的价（-1）⇒ 本档买不起（不得让它落进"完全免费"那条分支）。
          // ★★ W2：需求权重改按**现金腿（运费 + 税）**折算 —— 货款不再封顶（它可以进债务）。
          long buyerPrice = run.get(i).payAmountOf(price);
          long affordable = buyerPrice < 0L ? 0L : chargeAffordableQuantity(ctx, run.get(i), route);
          weights[i] = Math.min(run.get(i).remaining, affordable);
          demand += weights[i];
        }
        if (demand <= 0L) {
          continue; // 这一段没有可付需求 ⇒ 看下一段（缺省只有一段 ⇒ 与改前的"直接返回"逐值等价：后面的层也不再成交）
        }
        long supply = 0L;
        long[] sellWeights = new long[tier.size()];
        for (int i = 0; i < tier.size(); i++) {
          sellWeights[i] = tier.get(i).remaining;
          supply += sellWeights[i];
        }
        if (supply <= 0L) {
          break; // 这一档已被前面的买段吃光 ⇒ 后面的买段也卖不动
        }
        long matched = Math.min(demand, supply);
        if (matched > 0L) {
          // ★★ W4/W4-fix2：买侧按结算序依次吃满（份额 = 各槽自己的可付需求，由 pairUp 按列表序取用；
          //   见 buyPartsByPriority）；卖侧保持按权重比例（同档成本无差别）。
          long[] buyParts = buyPartsByPriority(weights);
          long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, supply);
          pairUp(ctx, run, buyParts, tier, sellParts, price, route);
        }
      }
      tierStart = tierEnd;
    }
  }

  /**
   * ★ P-T1d：<b>卖侧服务序</b>—— 没有政府要求管控市场时 = 既有 {@link #costOrder(RouteContext)}（逐值不变）； 有管控时 = 置顶 pass
   * 写定的全序名次（成本序 + 政府单的推进）。
   */
  static Comparator<SellSlot> servingOrder(MatchContext ctx, RouteContext route) {
    if (!ctx.round.procurementPriority().isActive()) {
      return costOrder(route);
    }
    return Comparator.comparingLong(sell -> sell.procurementRank);
  }

  /**
   * ★ P-T1d：<b>买侧服务序</b>—— 没有政府要求管控市场时<b>原样返回同一张表</b>（不复制、不排序 ⇒ 逐值不变）； 有管控时按置顶名次稳定排序（名次是全序 ⇒ 结果唯一）。
   */
  static List<BuySlot> procurementOrderedBuys(MatchContext ctx, List<BuySlot> buys) {
    if (!ctx.round.procurementPriority().isActive()) {
      return buys;
    }
    List<BuySlot> ordered = new ArrayList<>(buys);
    ordered.sort(Comparator.comparingLong(buy -> buy.procurementRank));
    return ordered;
  }

  /**
   * ★ P-T1d：<b>买侧"档"</b> = 相邻且 {@link BuySlot#procurementOvertook} 相同的连续段。
   *
   * <p>★ 为什么需要它：需求侧原本是**唯一一组**按权重比例分配（{@link ProportionalSplit}），"排在前面"只有在
   * <b>分组</b>上才看得见（同一组内人人按比例拿，与次序无关）⇒ 被置顶的政府单自成一段、先配给，剩下才是众家户那一组。 ★ 缺省（没有管控）⇒ 全部 {@code false} ⇒
   * 恰好一段 ⇒ 与改前**逐值相同**。
   */
  static List<List<BuySlot>> procurementBuyRuns(List<BuySlot> orderedBuys) {
    List<List<BuySlot>> runs = new ArrayList<>();
    int start = 0;
    while (start < orderedBuys.size()) {
      boolean overtook = orderedBuys.get(start).procurementOvertook;
      int end = start + 1;
      while (end < orderedBuys.size() && orderedBuys.get(end).procurementOvertook == overtook) {
        end++;
      }
      runs.add(new ArrayList<>(orderedBuys.subList(start, end)));
      start = end;
    }
    return runs;
  }

  /** 卖方成本排序（{@code unitCostEstimate + freightPerUnit} 升序；同成本按 canonical key 升序）。 */
  static Comparator<SellSlot> costOrder(RouteContext route) {
    return (left, right) -> {
      int byCost = Long.compare(landedCostOf(left, route), landedCostOf(right, route));
      if (byCost != 0) {
        return byCost;
      }
      return left.costTieBreak.compareTo(right.costTieBreak);
    };
  }

  /**
   * 一条卖槽的到货成本（同一刻度；{@link ProducerCostBook#landedCostMilli} 是唯一算式）。 ★ 成本未知（无配方）⇒ 排到已知成本之后（{@link
   * Long#MAX_VALUE}/2，不参与 {@code addExact} 的真相加）。
   */
  static long landedCostOf(SellSlot sell, RouteContext route) {
    if (!sell.costEstimate.costKnown()) {
      return Long.MAX_VALUE / 2L;
    }
    long freight = route == null ? 0L : route.freightPerUnit();
    return ProducerCostBook.landedCostMilli(sell.costEstimate.unitCostEstimateMilli(), freight);
  }
}
