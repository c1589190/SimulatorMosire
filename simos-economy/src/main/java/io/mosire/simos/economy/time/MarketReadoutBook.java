package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET_BUY_DEADLINE_DAYS;
import static io.mosire.simos.economy.time.MarketIndexBook.MarketIndexes;
import static io.mosire.simos.economy.time.MarketIndexBook.hexKeyOf;
import static io.mosire.simos.economy.time.MarketMatcher.actorKeyOf;
import static io.mosire.simos.economy.time.MarketMatcher.costOrder;
import static io.mosire.simos.economy.time.MarketMatcher.landedCostOf;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketPortBook.moveCostOf;
import static io.mosire.simos.economy.time.MarketPricer.AdaptivePrices;
import static io.mosire.simos.economy.time.MarketPricer.THOUSAND;
import static io.mosire.simos.economy.time.MarketPricer.marketIdAt;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.householdLifeReserveOf;
import static io.mosire.simos.economy.time.MarketRosterBook.householdProductionCostOf;
import static io.mosire.simos.economy.time.MarketRosterBook.necessaryInputsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketRosterBook.unitForSeller;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketReadoutBook {

  private MarketReadoutBook() {}

  /**
   * ★★ <b>W1（2026-10-10）：本轮市场轮统计读数</b>（逐市场 · 逐商品 D / S / 成交量 / 均价 / 未满足量 + 逐市场成交额）。
   *
   * <pre>
   * D / S        自适应定价那一步的累加器（D = 全额想要量，一个量都不扣；S = 剔掉被运力截断的部分 —— 这条**不对称**是 v3.5 §2.1 的刻意口径）
   * 成交量 / 均价 逐笔成交（{@code report.fills}）按**卖方所在市场**归属；均价 = Σ(量×单价)/Σ量（向下取整；0 成交 ⇒ 0）
   * 未满足量      未成交原因表（{@code report.unfilled}）的**买方侧**按**买方所在市场**归属
   * 成交额        Σ 货款（毫；按卖方市场归属，单位 = 该笔的支付币 —— 世界无汇率，不折算）
   * </pre>
   *
   * <p>★★ <b>它不改变任何行为</b>：全部输入都是本轮已经算出来的读数（订单累加器 / 成交流水 / 未成交表），本方法只做 归属与求和，不写状态、不改价、不参与撮合。★
   * 返回的是**单轮形态**的 {@link MarketRoundStats}（{@code lastRound} 段有值、 两段累计为空）：周期累计与窗口轮转由 {@code
   * EconomySettlement} 在状态侧折叠（那是"什么时候记一轮"的知识，不是本类的）。
   */
  static Map<MarketZoneId, MarketRoundStats> roundStatsOf(
      MatchContext ctx, AdaptivePrices adapted) {
    Map<MarketZoneId, Map<CommodityId, CommodityRound>> readings = new LinkedHashMap<>();
    // ① D/S（自适应累加器；键空间从"拓扑区对象"翻译成"市场 id"）
    for (Map.Entry<MarketZoneId, Map<CommodityId, long[]>> entry :
        adapted.quantities().entrySet()) {
      Map<CommodityId, CommodityRound> per =
          readings.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
      for (Map.Entry<CommodityId, long[]> quantity : entry.getValue().entrySet()) {
        per.put(
            quantity.getKey(),
            new CommodityRound(quantity.getValue()[0], quantity.getValue()[1], 0L, 0L, 0L));
      }
    }
    // ② 成交量 / 均价 / 成交额（逐笔成交按卖方市场归属）
    Map<MarketZoneId, Map<CommodityId, long[]>> tradedQuantity = new LinkedHashMap<>();
    Map<MarketZoneId, Map<CommodityId, java.math.BigInteger>> tradedValue = new LinkedHashMap<>();
    // ★★★ W2-fix7 / D4（控制方裁定 v3.10 §7）：**两栏成交额**
    //   turnoverMilli     = 名义货值 = ⌊Σ(量 × 单价) / 1000⌋（全额货款，含赊购部分）—— 就是下面这张 tradedValue
    //   cashTurnoverMilli = 实付现金 = Σ goodsPaymentMilli（改前 turnover 的口径，逐值保留在这一栏）
    Map<MarketZoneId, Long> cashTurnover = new LinkedHashMap<>();
    for (MarketReport.Fill fill : ctx.fills) {
      MarketZoneId marketId = marketIdAt(ctx, fill.from());
      if (marketId == null) {
        continue;
      }
      tradedQuantity
          .computeIfAbsent(marketId, ignored -> new LinkedHashMap<>())
          .merge(
              fill.commodity(),
              new long[] {fill.quantity()},
              (left, right) -> {
                left[0] = Math.addExact(left[0], right[0]);
                return left;
              });
      tradedValue
          .computeIfAbsent(marketId, ignored -> new LinkedHashMap<>())
          .merge(
              fill.commodity(),
              java.math.BigInteger.valueOf(fill.quantity())
                  .multiply(java.math.BigInteger.valueOf(fill.unitPriceMilli())),
              java.math.BigInteger::add);
      cashTurnover.merge(marketId, fill.goodsPaymentMilli(), Math::addExact);
    }
    // ★ 名义货值按**市场一次取整**（不是逐笔取整）：`⌊Σ(量×单价)/1000⌋` —— 与上面均价用的同一份 BigInteger
    //   中间量同源（量×价的毫²量纲 ÷ 1000 = 毫）。★ 为什么不用逐笔的 `paymentForQuantity`（那是 ⌈…⌉、且异币成交
    //   时取的是买方价）：本栏是**统计读数**（"这个市场做了多大的生意"），逐笔取整会把 1 毫级成交放大成整笔货款；
    //   逐笔真收/真欠在成交那一刻的账上（转移凭据 + debtContracts），本栏不冒充它。
    Map<MarketZoneId, Long> turnover = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, Map<CommodityId, java.math.BigInteger>> entry :
        tradedValue.entrySet()) {
      java.math.BigInteger total = java.math.BigInteger.ZERO;
      for (java.math.BigInteger value : entry.getValue().values()) {
        total = total.add(value);
      }
      turnover.put(entry.getKey(), total.divide(THOUSAND).longValueExact());
    }
    for (Map.Entry<MarketZoneId, Map<CommodityId, long[]>> entry : tradedQuantity.entrySet()) {
      Map<CommodityId, CommodityRound> per =
          readings.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
      for (Map.Entry<CommodityId, long[]> quantity : entry.getValue().entrySet()) {
        long traded = quantity.getValue()[0];
        java.math.BigInteger value =
            tradedValue.getOrDefault(entry.getKey(), Map.of()).get(quantity.getKey());
        long average =
            traded == 0L || value == null
                ? 0L
                : value.divide(java.math.BigInteger.valueOf(traded)).longValueExact();
        CommodityRound existing = per.getOrDefault(quantity.getKey(), CommodityRound.ZERO);
        per.put(
            quantity.getKey(),
            new CommodityRound(
                existing.demandMilli(),
                existing.supplyMilli(),
                traded,
                average,
                existing.unmetMilli()));
      }
    }
    // ③ 未满足量（买方侧未成交量，按买方市场归属）
    for (MarketReport.Unfilled unfilled : ctx.unfilled) {
      if (!unfilled.buyerSide()) {
        continue;
      }
      MarketZoneId marketId = marketIdAt(ctx, unfilled.hex());
      if (marketId == null) {
        continue;
      }
      Map<CommodityId, CommodityRound> per =
          readings.computeIfAbsent(marketId, ignored -> new LinkedHashMap<>());
      CommodityRound existing = per.getOrDefault(unfilled.commodity(), CommodityRound.ZERO);
      per.put(
          unfilled.commodity(),
          new CommodityRound(
              existing.demandMilli(),
              existing.supplyMilli(),
              existing.tradedMilli(),
              existing.averagePriceMilli(),
              Math.addExact(existing.unmetMilli(), unfilled.quantity())));
    }
    Map<MarketZoneId, MarketRoundStats> stats = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, Map<CommodityId, CommodityRound>> entry : readings.entrySet()) {
      stats.put(
          entry.getKey(),
          new MarketRoundStats(
              ctx.round.day,
              entry.getValue(),
              // ★ W2-fix7 / D4：名义货值（全额货款，含赊购）+ 实付现金，两栏各归各的（见 readRoundStats 的口径注）
              turnover.getOrDefault(entry.getKey(), 0L),
              cashTurnover.getOrDefault(entry.getKey(), 0L),
              Map.of(),
              0L,
              0L,
              Map.of(),
              0L,
              0L));
    }
    return java.util.Collections.unmodifiableMap(stats);
  }

  // ── 未成交原因 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>S3：逐买方 / 逐卖方原因 + 逐槽位只读结果</b>（计划 §S3.1）。
   *
   * <p>★★ <b>卖方原因不再由全局 {@code anyBuy} 决定</b>：逐条卖方剩余按"同商品、同/邻区是否存在合格买方 （预算、限价、到货时限、路线可达）"分档；买方的
   * {@code NO_BUDGET} 只挂在该买方自己的槽位上（见下）。
   */
  static void collectUnfilled(MatchContext ctx, MarketIndexes indexes) {
    Map<CommodityId, Long> sellRemainingByCommodity = new LinkedHashMap<>();
    Map<CommodityId, Long> minSellerPriceByCommodity = new LinkedHashMap<>();
    for (SellSlot sell : ctx.sells) {
      CommodityId commodity = sell.order.commodity();
      sellRemainingByCommodity.merge(commodity, Math.max(0L, sell.remaining), Long::sum);
      minSellerPriceByCommodity.merge(commodity, sell.order.minPrice(), Math::min);
    }
    // ── 买方：逐槽位（NO_BUDGET 只挂在该买方上）────────────────────────────────────────
    for (BuySlot buy : ctx.buys) {
      if (buy.remaining <= 0L) {
        continue;
      }
      long totalSellRemaining = sellRemainingByCommodity.getOrDefault(buy.order.commodity(), 0L);
      long minSellerPrice =
          minSellerPriceByCommodity.getOrDefault(buy.order.commodity(), Long.MAX_VALUE);
      MarketUnfilledReason reason = buy.blocked;
      // ★★ W2（2026-10-11）：**"没钱"不再是买方未成交的理由** —— 家户的需求全额进撮合、未付价款进债务
      //   （见 {@code executeTrade} / {@code recordDeferredPaymentDebt}）⇒ 这里不再因"现金 ≤ 0"就把原因
      //   写成 NO_BUDGET。{@code NO_BUDGET} 只剩一个具名改义后的含义：**现金付不出这一笔的运费/税**
      //   （由 {@code pairUp} 的 {@code chargesAtMost} 置位，见那里）。
      //   ★★★ W2-fix4（2026-10-11）：这里原有第二层"信用归因"（{@code creditUnfilledReason}：可借池剩多少 ⇒
      //   NO_LENDABLE_GOODS / NO_LENDABLE_MONEY / NO_CREDIT_LIMIT）—— **随信用撮合一并退役**。那两个
      //   枚举常量仍留在 {@code MarketUnfilledReason}（历史 revision 的未成交档里还有这些字面量，解析必须继续认），
      //   但**本轮结算不再产生它们**：归因回到"真的没有供给 / 没有合格买方 / 价格 / 路线 / 算法残余"这几档。
      if (reason == MarketUnfilledReason.NO_BUDGET) {
        reason = null;
      }
      if (reason == null) {
        if (totalSellRemaining <= 0L) {
          reason =
              inputShortfallNear(ctx, buy, indexes)
                  ? MarketUnfilledReason.INPUT_SHORTFALL
                  : classifyNoSupply(ctx, buy, indexes);
        } else if (!hasSupplyNear(buy, indexes)) {
          reason = MarketUnfilledReason.NO_ADJACENT_SUPPLY;
        } else if (minSellerPrice != Long.MAX_VALUE
            // ★ RF1：价格接受者需求不会被价格上限挡下。
            && buy.order.maxLandedPrice().isPresent()
            && buy.order.maxLandedPrice().getAsLong() < minSellerPrice) {
          reason = MarketUnfilledReason.PRICE_LIMIT;
        } else {
          reason = MarketUnfilledReason.ALGORITHM_UNCOVERED;
        }
      }
      ctx.unfilled.add(
          new MarketReport.Unfilled(
              buy.buyer.actor(),
              true,
              buy.order.commodity(),
              buy.remaining,
              reason,
              buy.hex,
              // ★★ F-2（2026-10-10）：卡在运力时带上具名归因档（"没有服务卖" vs "运力不足"）——
              //   reason 仍是既有的 LOGISTICS_CAPACITY（读数面兼容），本栏是**可分辨的那一半**。
              buy.logisticsDetail));
    }
    // ── 卖方：逐条按"合格买方是否存在"分档（不再 anyBuy ⇒ NO_BUDGET 的全局写法）────────────
    Map<SellSlot, MarketUnfilledReason> sellerReasons = new LinkedHashMap<>();
    for (SellSlot sell : ctx.sells) {
      if (sell.remaining <= 0L) {
        continue;
      }
      MarketUnfilledReason reason = sellerReason(ctx, sell, indexes);
      sellerReasons.put(sell, reason);
      ctx.unfilled.add(
          new MarketReport.Unfilled(
              sell.seller.actor(),
              false,
              sell.order.commodity(),
              sell.remaining,
              reason,
              sell.hex,
              sell.logisticsDetail));
    }
    collectSellerOutcomes(ctx, indexes, sellerReasons);
    collectBuyerOutcomes(ctx, indexes);
  }

  /** 一个卖方槽位的合格买方扫描结果（逐条判定；{@code anyBuy} 只作证据，不直接决定档位）。 */
  record BuyEligibility(
      boolean anyBuy,
      boolean anyAffordable,
      boolean budgetBlocked,
      boolean priceBlocked,
      boolean routeBlocked,
      boolean timeBlocked,
      boolean hasAdjacent) {}

  /**
   * ★★ <b>S3 的卖方原因分档</b>（优先级 = 计划 §S3.1 的表）：
   *
   * <ol>
   *   <li>可自用 ⇒ {@code UNSOLD_SELF_USABLE}（卖不掉不等于无法再生产）；
   *   <li>状态机判定的真无法再生产 ⇒ {@code CANNOT_REPRODUCE}；
   *   <li>存在合格买方但自己没被选中 ⇒ {@code OUTCOMPETED}（有更便宜的卖方成交）或兜底；
   *   <li>有买方但都被预算/限价/时限/路线挡下 ⇒ 相应档位（{@code NO_BUDGET} 是"这些买方都没钱"的**逐条**结论）；
   *   <li>根本没有同商品买方 ⇒ {@code NO_BUYER}。
   * </ol>
   */
  static MarketUnfilledReason sellerReason(MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
    // ★★ P-T1a：跨区口岸闸（法律规定层）是本槽剩余的直接原因时，优先具名 —— 它比"被谁挤掉"更外层，
    //    也**不是**物流问题（有货有路有运力，只是政策不许过），两档混起来会把"该改政策"读成"该加运力"。
    if (sell.portBlocked) {
      return MarketUnfilledReason.PORT_THROTTLED;
    }
    // ★★ 2026-10-09：承运容量（商号每周期运力 / 路线每窗口容量）是本槽剩余的直接原因时，优先具名物流瓶颈，
    //    不让它掉进 OUTCOMPETED/ALGORITHM_UNCOVERED 掩盖过去。
    if (sell.capacityBlocked) {
      return MarketUnfilledReason.LOGISTICS_CAPACITY;
    }
    // ★★ E（§3.1）：成交时的"钱的价"裁决（说不出这种钱的价 / 折回本币不够保留价）给卖方留的是**市场性理由**
    //   （{@link MarketUnfilledReason#PRICE_LIMIT}）—— 它优先于"被谁挤掉"这类泛化归因，否则 N1 的归因会被
    //   OUTCOMPETED 掩盖。★ A2a 的 CURRENCY_MISMATCH 硬拒已退役，本档不再由本结算产生。
    if (sell.blocked != null) {
      return sell.blocked;
    }
    if (sellerSelfUsable(ctx, sell)) {
      return MarketUnfilledReason.UNSOLD_SELF_USABLE;
    }
    if (sellerCannotReproduce(ctx, sell)) {
      return MarketUnfilledReason.CANNOT_REPRODUCE;
    }
    BuyEligibility eligible = scanEligibleBuyers(ctx, sell, indexes);
    if (eligible.anyAffordable()) {
      return outcompetedBy(ctx, sell, indexes).actors() > 0L
          ? MarketUnfilledReason.OUTCOMPETED
          : MarketUnfilledReason.ALGORITHM_UNCOVERED;
    }
    if (!eligible.anyBuy()) {
      return MarketUnfilledReason.NO_BUYER;
    }
    if (eligible.budgetBlocked()) {
      return MarketUnfilledReason.NO_BUDGET;
    }
    if (eligible.priceBlocked()) {
      return MarketUnfilledReason.PRICE_LIMIT;
    }
    if (eligible.routeBlocked()) {
      return MarketUnfilledReason.NO_ROUTE;
    }
    if (eligible.timeBlocked()) {
      return MarketUnfilledReason.LOGISTICS_TIME;
    }
    return eligible.hasAdjacent()
        ? MarketUnfilledReason.ALGORITHM_UNCOVERED
        : MarketUnfilledReason.NO_ADJACENT_SUPPLY;
  }

  /** 扫描同区 + 直接邻接区的同商品买方：预算/限价/时限/路线逐条判（"合格"= 四关全过）。 */
  static BuyEligibility scanEligibleBuyers(MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
    CommodityId commodity = sell.order.commodity();
    String regionId = sell.region.node().nodeId();
    boolean anyBuy = false;
    boolean anyAffordable = false;
    boolean budgetBlocked = false;
    boolean priceBlocked = false;
    boolean routeBlocked = false;
    boolean timeBlocked = false;
    boolean hasAdjacent = false;
    List<String> regionIds = new ArrayList<>();
    regionIds.add(regionId);
    Set<String> adjacent = indexes.adjacentRegionIds.getOrDefault(regionId, Set.of());
    hasAdjacent = !adjacent.isEmpty();
    regionIds.addAll(adjacent);
    for (String candidateRegion : regionIds) {
      List<BuySlot> regionBuys =
          indexes
              .buysByRegionCommodity
              .getOrDefault(candidateRegion, Map.of())
              .getOrDefault(commodity, List.of());
      for (BuySlot buy : regionBuys) {
        if (buy.remaining <= 0L) {
          continue;
        }
        anyBuy = true;
        // ★★★ W2（2026-10-11，具名改义）：**"没钱"不再是买方买不到货的原因** —— 家户的需求全额进撮合、
        //   未付价款进债务 ⇒ 这里不再用"可花额 ≤ 0"把卖方剩余归因成 {@code NO_BUDGET}（那是旧口径的
        //   "这些买方都没钱"）。{@code noMoney} 这个槽位标记保留（W2 起 {@code pairUp} 只在**运费/税**
        //   付不出时置位，不再因"买不起货"置位）—— 于是卖方剩余的归因落到真实原因（限价/路线/时限/无买方）。
        if (buy.noMoney) {
          budgetBlocked = true;
          continue;
        }
        boolean sameRegion = candidateRegion.equals(regionId);
        if (!sameRegion) {
          HexCoord buyerHex = buy.hex;
          if (moveCostOf(ctx, buyerHex) >= TerrainType.IMPASSABLE_MOVE_COST) {
            routeBlocked = true;
            continue;
          }
          long travel = Math.max(1L, ctx.topology.travelTicks(sell.hex, buyerHex));
          if (buy.order.latestArrivalTick() < ctx.round.day + travel) {
            timeBlocked = true;
            continue;
          }
        }
        // ★ RF1：价格接受者需求没有价格上限 ⇒ 它从不构成卖方的 PRICE_LIMIT 原因。
        if (buy.order.maxLandedPrice().isPresent()
            && buy.order.maxLandedPrice().getAsLong() < sell.order.minPrice()) {
          priceBlocked = true;
          continue;
        }
        anyAffordable = true;
      }
    }
    return new BuyEligibility(
        anyBuy, anyAffordable, budgetBlocked, priceBlocked, routeBlocked, timeBlocked, hasAdjacent);
  }

  /** 更便宜（同商品、同/邻区；按 {@code unitCostEstimate} 比）且真的卖掉的卖方家数与数量。 */
  static Outcompeted outcompetedBy(MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
    CommodityId commodity = sell.order.commodity();
    String regionId = sell.region.node().nodeId();
    Set<String> adjacent = indexes.adjacentRegionIds.getOrDefault(regionId, Set.of());
    long ownCost = landedCostOf(sell, null);
    Set<String> cheaperActors = new LinkedHashSet<>();
    long cheaperFilled = 0L;
    List<String> regionIds = new ArrayList<>();
    regionIds.add(regionId);
    regionIds.addAll(adjacent);
    for (String candidateRegion : regionIds) {
      for (SellSlot other :
          indexes
              .sellsByRegionCommodity
              .getOrDefault(candidateRegion, Map.of())
              .getOrDefault(commodity, List.of())) {
        if (other == sell || landedCostOf(other, null) >= ownCost) {
          continue;
        }
        long filled = Math.max(0L, other.order.sellable() - other.remaining);
        if (filled > 0L) {
          cheaperActors.add(other.seller.actor().kind() + ":" + other.seller.actor().id());
          cheaperFilled += filled;
        }
      }
    }
    return new Outcompeted(cheaperActors.size(), cheaperFilled);
  }

  record Outcompeted(long actors, long qty) {}

  /** 买方"没有供给"时是否其实是生产侧投入不足：本格有产该商品、有产能、但本周期投入没凑齐的 unit。 ★ 只查买方所在格（第一版口径；邻接格留待跨区协调那一轮，不为假想需要造扫描）。 */
  static boolean inputShortfallNear(MatchContext ctx, BuySlot buy, MarketIndexes indexes) {
    String hexKey = hexKeyOf(buy.hex);
    // ★ R4-B.3a-perf：参与者按 hex 的索引已在 MarketIndexes 里建好（旧实现每次现扫全部参与者）。
    for (Participant participant : indexes.participantsByHex.getOrDefault(hexKey, List.of())) {
      for (ProductionUnitId id : participant.units()) {
        ProductionProcess unit = ctx.round.units.get(id);
        IndustryDefinitionView industry =
            unit == null ? null : ctx.round.industries.get(unit.industry());
        if (unit == null
            || industry == null
            || !industry.outputPerUnit().containsKey(buy.order.commodity())) {
          continue;
        }
        long scale =
            ProductionProcessBook.plannedCapacityScaleOf(
                unit, industry, ctx.round.index, ctx.round.operatorConditions.get(id));
        if (scale <= 0L) {
          continue;
        }
        for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
          if (entry.getValue() <= 0L) {
            continue;
          }
          long required = entry.getValue() * scale;
          long used = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
          if (used < required) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** 卖方的未卖余量是否可用于自身再生产（下一周期投入 / 家庭生活保留）。 */
  static boolean sellerSelfUsable(MatchContext ctx, SellSlot sell) {
    CommodityId commodity = sell.order.commodity();
    if (necessaryInputsOf(ctx.round, sell.seller).getOrDefault(commodity, 0L) > 0L) {
      return true;
    }
    if (sell.seller.household() != null) {
      HouseholdEconomy householdEconomy = ctx.round.householdEconomies.get(sell.seller.household());
      return householdEconomy != null
          && householdLifeReserveOf(householdEconomy).getOrDefault(commodity, 0L) > 0L;
    }
    return false;
  }

  /** 经营者状态机判定"真无法再生产"：{@code INDEBTED} 及以后（ACTIVE/TRIALING/CONTRACTING 不在此列）。 */
  static boolean sellerCannotReproduce(MatchContext ctx, SellSlot sell) {
    ProductionProcess unit = unitForSeller(ctx.round, sell.seller, sell.order.commodity());
    if (unit == null) {
      return false;
    }
    OperatorCondition condition = ctx.round.operatorConditions.get(unit.id());
    if (condition == null) {
      return false;
    }
    return switch (condition.status()) {
      case INDEBTED, SUSPENDED, EXITING, EXITED, ABANDONED -> true;
      default -> false;
    };
  }

  /** 逐卖方槽位的只读结果（成本排名 / 成交 / 未成交原因 / 被谁挤掉）。 */
  static void collectSellerOutcomes(
      MatchContext ctx, MarketIndexes indexes, Map<SellSlot, MarketUnfilledReason> sellerReasons) {
    Map<SellSlot, Integer> ranks = sellerCostRanks(ctx);
    for (SellSlot sell : ctx.sells) {
      CommodityId commodity = sell.order.commodity();
      long offered = sell.order.sellable();
      long filled = Math.max(0L, offered - sell.remaining);
      long unfilled = Math.max(0L, sell.remaining);
      MarketUnfilledReason reason = sellerReasons.get(sell);
      Outcompeted outcompeted =
          reason == MarketUnfilledReason.OUTCOMPETED
              ? outcompetedBy(ctx, sell, indexes)
              : new Outcompeted(0L, 0L);
      ProductionProcess unit = unitForSeller(ctx.round, sell.seller, commodity);
      ctx.sellerOutcomes.add(
          new MarketReport.SellerOutcome(
              ctx.round.day,
              sell.seller.actor(),
              unit == null ? Optional.empty() : Optional.of(unit.id()),
              sell.hex,
              commodity,
              offered,
              filled,
              unfilled,
              sell.market.priceOf(commodity),
              sell.costEstimate.unitCostEstimateMilli(),
              0L,
              bestAcceptedLandedPrice(ctx, sell),
              ranks.getOrDefault(sell, 0),
              Optional.ofNullable(reason),
              outcompeted.actors(),
              reason == MarketUnfilledReason.OUTCOMPETED ? unfilled : 0L,
              sell.costEstimate.priceMissing(),
              sell.costEstimate.costKnown()));
    }
  }

  /** 同商品同区的到货成本名次（0 起；成本未知排已知之后；同成本按 canonical key）。 */
  static Map<SellSlot, Integer> sellerCostRanks(MatchContext ctx) {
    Map<String, List<SellSlot>> groups = new LinkedHashMap<>();
    for (SellSlot sell : ctx.sells) {
      String key = sell.region.node().nodeId() + "#" + sell.order.commodity().value();
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sell);
    }
    Map<SellSlot, Integer> ranks = new LinkedHashMap<>();
    for (List<SellSlot> group : groups.values()) {
      group.sort(costOrder(null));
      for (int i = 0; i < group.size(); i++) {
        ranks.put(group.get(i), i);
      }
    }
    return ranks;
  }

  /**
   * 本卖方的商品成交里最低的一笔到货价（单价 + 单位运费；无成交 ⇒ empty）。
   *
   * <p>★★ <b>P-T4：不把两种钱相加</b> —— 单价是<b>卖方币</b>、单位运费是<b>买方币</b>，异币成交时"单价 + 单位运费" 没有定义；{@link
   * MarketReport.Fill#landedUnitPriceMilli()} 返回 empty 的成交（单价币 ≠ 运费币）<b>具名排除</b>、 不参与最低价。单币世界两栏恒同币
   * ⇒ 逐值不变。
   */
  static OptionalLong bestAcceptedLandedPrice(MatchContext ctx, SellSlot sell) {
    long best = Long.MAX_VALUE;
    for (MarketReport.Fill fill : ctx.fills) {
      if (!fill.commodity().equals(sell.order.commodity())
          || !fill.seller().equals(sell.seller.actor())) {
        continue;
      }
      OptionalLong landed = fill.landedUnitPriceMilli();
      if (landed.isPresent() && landed.getAsLong() < best) {
        best = landed.getAsLong();
      }
    }
    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }

  /** 逐买方槽位的只读结果（库存/覆盖/缺口/预算/下单/成交/原因；含"库存已足"的 {@code STOCK_SUFFICIENT}）。 */
  static void collectBuyerOutcomes(MatchContext ctx, MarketIndexes indexes) {
    // ★ P1.2 同款：到货量按 (buyer, commodity) 预索引一次（逐槽位现扫 shipments 会把它乘进 participants×commodities）。
    long deadline = ctx.round.day + MARKET_BUY_DEADLINE_DAYS;
    Map<String, Long> incomingByActorCommodity = new LinkedHashMap<>();
    for (ShipmentBatch batch : ctx.round.shipments.values()) {
      if (batch.arrivalTick() > deadline) {
        continue;
      }
      for (ShipmentAllocation allocation : batch.allocations()) {
        incomingByActorCommodity.merge(
            actorKeyOf(allocation.buyer()) + "#" + batch.commodity().value(),
            allocation.quantity(),
            Long::sum);
      }
    }
    Map<String, List<BuySlot>> buysByActorCommodity = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      CommodityId commodity = buy.order.commodity();
      buysByActorCommodity
          .computeIfAbsent(
              actorKeyOf(buy.buyer.actor()) + "#" + commodity.value(), ignored -> new ArrayList<>())
          .add(buy);
    }
    // ★★ R4-E2：读口 desired 必须与订单生成同源 —— 有效需求目标也进"目标量"（否则报告会说"缺口 0"
    //   而买单非 0）。量与 planFor 走同一段 DemandTargets 逻辑，不另算一份。
    Map<String, Map<HouseholdId, Map<CommodityId, Long>>> demandTargetsByHex =
        DemandTargets.totalsByHex(
            ctx.round.householdDemands(), ctx.round.householdEconomies, ctx.round.day);
    for (Participant participant : ctx.participants.values()) {
      HexCoord hex = ctx.participantHex.get(participant.actor());
      if (hex == null) {
        continue;
      }
      MarketTable market = ctx.tableAt(hex);
      if (market == null) {
        continue;
      }
      for (CommodityId commodity : indexes.commodities) {
        long reference = market.priceOf(commodity);
        if (!market.hasPrice(commodity)) {
          continue; // 从未定价 ⇒ 不交易（也不谈"买不起"）
        }
        long desired;
        if (participant.household() == null) {
          desired = necessaryInputsOf(ctx.round, participant).getOrDefault(commodity, 0L);
        } else {
          long demandTarget =
              demandTargetsByHex
                  .getOrDefault(hexKeyOf(hex), Map.of())
                  .getOrDefault(participant.household(), Map.of())
                  .getOrDefault(commodity, 0L);
          // ★★★ W2（S-10）：目标量 = **生产花费（内含口粮）** + 有效需求目标 —— 与订单路径
          //   （{@code ordersFor} 的 {@code baseTarget}）**同一个量**。于是下面那个 {@code gap}
          //   = "生产花费未满足量"（W3 的缺口/满足率读的就是它），不需要第二个算式。
          desired =
              Math.addExact(productionCostOfParticipant(ctx, participant, commodity), demandTarget);
        }
        List<BuySlot> slots =
            buysByActorCommodity.getOrDefault(
                actorKeyOf(participant.actor()) + "#" + commodity.value(), List.of());
        if (desired <= 0L && slots.isEmpty()) {
          continue;
        }
        long stock = stockOf(ctx.round, participant, commodity);
        long frozen = frozenGoodsOf(ctx.round, participant, commodity);
        long onHand = Math.max(0L, stock - frozen);
        long incoming =
            incomingByActorCommodity.getOrDefault(
                actorKeyOf(participant.actor()) + "#" + commodity.value(), 0L);
        long gap = Math.max(0L, desired - onHand - incoming);
        // ★★ 3c（预算口径一致）：诊断口径的可花额也按**订单声明的支付币**读 —— 有订单就从订单读（它可能不等本格计价币），
        //   一个订单都没有时才退回本格计价币（没有订单 ⇒ 世界上没有"用哪种钱"的决策）。混合币取最大者：
        //   禁跨币求和（I-C10），且这一项只是"为什么不买"的上界读数，逐笔真判在撮合那一侧（{@code buy.currency}）。
        long budget = diagnosedBudgetOf(ctx, participant, market, slots);
        // ★★ 0 价免费交易：货款买得起量无上限（数量受缺口约束）；非 0 价才按"钱 ÷ 价"折算。
        long affordable =
            reference == 0L
                ? Long.MAX_VALUE
                : budget * EconomySettlement.MILLI_PER_GRAIN / reference;
        long ordered = 0L;
        long filled = 0L;
        MarketUnfilledReason slotReason = null;
        for (BuySlot buy : slots) {
          ordered += buy.order.quantity();
          filled += buy.order.quantity() - buy.remaining;
          if (buy.remaining > 0L && slotReason == null && buy.blocked != null) {
            slotReason = buy.blocked;
          }
        }
        OptionalLong coverDays = OptionalLong.empty();
        if (participant.household() != null) {
          HouseholdEconomy householdEconomy =
              ctx.round.householdEconomies.get(participant.household());
          if (householdEconomy != null) {
            long daily = householdEconomy.naturalNeeds().getOrDefault(commodity, 0L);
            if (daily > 0L) {
              coverDays = OptionalLong.of(onHand / daily);
            }
          }
        }
        Optional<MarketUnfilledReason> reason;
        // ★★ W2（2026-10-11）：家户买方走债务式清算 ⇒ **"预算为 0"不再是它不下单/买不到的原因**
        //   （需求全额下单、未付价款进债务）。故这一层的 {@code NO_BUDGET} 只对**经营者**（现金封顶的买盘）
        //   保留；家户一律按"供不上 / 限价 / 算法未覆盖"归因 —— 否则读数会把"没有供给"误报成"没钱"。
        boolean householdDebtClearing = participant.household() != null;
        if (gap <= 0L) {
          reason = Optional.of(MarketUnfilledReason.STOCK_SUFFICIENT);
        } else if (ordered <= 0L) {
          reason =
              Optional.of(
                  !householdDebtClearing && (budget <= 0L || affordable <= 0L)
                      ? MarketUnfilledReason.NO_BUDGET
                      : MarketUnfilledReason.ALGORITHM_UNCOVERED);
        } else if (filled >= ordered) {
          reason = Optional.empty();
        } else if (slotReason != null) {
          reason = Optional.of(slotReason);
        } else {
          reason =
              Optional.of(
                  !householdDebtClearing && budget <= 0L
                      ? MarketUnfilledReason.NO_BUDGET
                      : MarketUnfilledReason.ALGORITHM_UNCOVERED);
        }
        ctx.buyerOutcomes.add(
            new MarketReport.BuyerOutcome(
                ctx.round.day,
                participant.actor(),
                Optional.ofNullable(participant.household()),
                hex,
                commodity,
                onHand,
                coverDays,
                gap,
                desired,
                budget,
                affordable,
                ordered,
                filled,
                reason));
      }
    }
  }

  /**
   * ★★ <b>3c：诊断口径的"该买方可花额" —— 唯一读取口</b>（"为什么不买"的读数与归因用它，不写任何账）。
   *
   * <pre>
   * 有订单 ⇒ max(逐订单按其**支付币**读出的可花额)   // 可能不等本格计价币；混合币取最大者（禁跨币求和，I-C10）
   * 无订单 ⇒ 本格计价币的可花额                     // 没有订单 ⇒ 没有"用哪种钱"的决策
   * </pre>
   *
   * <p>★★ <b>为什么不是 {@code market.numeraire()} 一条路</b>：订单可选币之后，"这个买方的钱够不够"要按<b>它要付的那种钱</b>回答
   * ——否则一个持铜户会被按它没有的银回答"买得起"（或反之）。★ 这里取最大者是<b>上界读数</b>的取向（诊断只回答"账面是否为空"）， 逐笔真判在撮合那一侧（{@code
   * payableMoneyOf}/{@code chargeAffordableQuantity}，都按 {@code buy.currency}）。
   *
   * <p>★ <b>缺省语义中性（I-C2）</b>：本批订单币恒等于本格计价币 ⇒ 与改前逐值相同。
   */
  static long diagnosedBudgetOf(
      MatchContext ctx, Participant participant, MarketTable market, List<BuySlot> slots) {
    long budget = 0L;
    for (BuySlot buy : slots) {
      budget = Math.max(budget, spendableMoneyOf(ctx.round, participant, buy.currency));
    }
    return slots.isEmpty() ? spendableMoneyOf(ctx.round, participant, market.numeraire()) : budget;
  }

  /**
   * ★★★ <b>W2（S-10）：本主体在某商品上的“生产花费（内含口粮）”——唯一读口</b>。
   *
   * <p>它就是 {@link #householdProductionCostOf} 的单商品投影；经营者（{@code household == null}）走 {@code
   * necessaryInputsOf}（经营者没有口粮这条账）。★ W3 的"逐户缺口/满足率"读的量 = 本值 − 可用 − 在途 （= {@code
   * MarketReport.BuyerOutcome.gap}，见 {@code collectBuyerOutcomes}）。
   */
  static long productionCostOfParticipant(
      MatchContext ctx, Participant participant, CommodityId commodity) {
    return householdProductionCostOf(ctx.round, participant).getOrDefault(commodity, 0L);
  }

  /**
   * 买方的区里/直接邻接供应区里，还有同商品的剩余卖单吗（"看不见的供给"与"没有供给"分开）。
   *
   * <p>★ P1.2：用 {@code sellsByRegionCommodity} + {@code adjacentRegionIds} 直取，不再扫全部卖单、也不再逐对调几何判断。
   */
  static boolean hasSupplyNear(BuySlot buy, MarketIndexes indexes) {
    CommodityId commodity = buy.order.commodity();
    String regionId = buy.region.node().nodeId();
    if (hasActiveSellInRegion(indexes, regionId, commodity)) {
      return true;
    }
    for (String adjacent : indexes.adjacentRegionIds.getOrDefault(regionId, Set.of())) {
      if (hasActiveSellInRegion(indexes, adjacent, commodity)) {
        return true;
      }
    }
    return false;
  }

  /** 某个区的某商品桶里还有剩余卖单吗（只读；桶内相对序不影响布尔结果）。 */
  static boolean hasActiveSellInRegion(
      MarketIndexes indexes, String regionId, CommodityId commodity) {
    for (SellSlot sell :
        indexes
            .sellsByRegionCommodity
            .getOrDefault(regionId, Map.of())
            .getOrDefault(commodity, List.of())) {
      if (sell.remaining > 0L) {
        return true;
      }
    }
    return false;
  }

  /**
   * 没有可卖余量时的进一步归因：全部被保留 ⇒ {@code ALL_RESERVED}；根本没有邻区 ⇒ {@code NO_ADJACENT_SUPPLY}。
   *
   * <p>★ P1.2：参与者按 hex 预索引（{@link MarketIndexes#participantsByHex}），邻接只查 {@code adjacentRegionIds}。
   */
  static MarketUnfilledReason classifyNoSupply(
      MatchContext ctx, BuySlot buy, MarketIndexes indexes) {
    for (Participant participant :
        indexes.participantsByHex.getOrDefault(hexKeyOf(buy.hex), List.of())) {
      long held = stockOf(ctx.round, participant, buy.order.commodity());
      long frozen = frozenGoodsOf(ctx.round, participant, buy.order.commodity());
      if (held - frozen > 0L) {
        return MarketUnfilledReason.ALL_RESERVED;
      }
    }
    boolean anyAdjacent =
        !indexes.adjacentRegionIds.getOrDefault(buy.region.node().nodeId(), Set.of()).isEmpty();
    return anyAdjacent ? MarketUnfilledReason.NO_SELLER : MarketUnfilledReason.NO_ADJACENT_SUPPLY;
  }

  static long remainingOf(List<BuySlot> buys) {
    long sum = 0L;
    for (BuySlot buy : buys) {
      sum += Math.max(0L, buy.remaining);
    }
    return sum;
  }

  static long remainingOfSells(List<SellSlot> sells) {
    long sum = 0L;
    for (SellSlot sell : sells) {
      sum += Math.max(0L, sell.remaining);
    }
    return sum;
  }
}
