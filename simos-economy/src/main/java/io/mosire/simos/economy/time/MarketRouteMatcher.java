package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_MAX_TRANSPORT_ROUNDS;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_TRANSPORT_LOSS_PER_MILLE;
import static io.mosire.simos.economy.time.MarketMatcher.landedCostOf;
import static io.mosire.simos.economy.time.MarketMatcher.procurementBuyRuns;
import static io.mosire.simos.economy.time.MarketMatcher.procurementOrderedBuys;
import static io.mosire.simos.economy.time.MarketMatcher.servingOrder;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteAccumulator;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketMoneyBook.buyPartsByPriority;
import static io.mosire.simos.economy.time.MarketMoneyBook.worstBuyerUnitPrice;
import static io.mosire.simos.economy.time.MarketPairingBook.pairUp;
import static io.mosire.simos.economy.time.MarketPortBook.logPortRouteExhausted;
import static io.mosire.simos.economy.time.MarketPortBook.moveCostOf;
import static io.mosire.simos.economy.time.MarketPortBook.recordPortBlockedDemand;
import static io.mosire.simos.economy.time.MarketReadoutBook.remainingOf;
import static io.mosire.simos.economy.time.MarketReadoutBook.remainingOfSells;
import static io.mosire.simos.economy.time.MarketShipmentBook.commodityFreightBaseMilli;
import static io.mosire.simos.economy.time.MarketShipmentBook.freightUnitMilli;
import static io.mosire.simos.economy.time.MarketShipmentBook.haulServiceAt;
import static io.mosire.simos.economy.time.MarketShipmentBook.plannedCarrierCostPerMille;
import static io.mosire.simos.economy.time.MarketShipmentBook.servicePriceAt;
import static io.mosire.simos.economy.time.MarketShipmentBook.unitPriceOf;
import static io.mosire.simos.economy.time.MarketTradeMath.chargeAffordableQuantity;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.List;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketRouteMatcher {

  private MarketRouteMatcher() {}

  /**
   * 一条具体的 sellerHex → buyerHex 路线：判价/时限/地形，再在有限轮内分运力。
   *
   * <p>★★ <b>P-T1a：口岸闸是这条车道上的第二个"量上限"</b>（第一个是每窗口运力 {@code capacityPerWindow}）—— {@code
   * portTransitCapMilli} 由 {@link #buildPortBudgets} 给出的<b>区对共享预算</b>传入（{@link
   * PortThrottle#NO_GATE_MILLI} = 没有口岸面）。 ★ 上限为 0 ⇒
   * 本轮这一对买卖<b>一笔都不成交</b>（"被拦下的量不进候选集"），但<b>买卖槽位一个都不删</b>： 买方还可能走别的车道、卖方本区买家仍看得见它（设计书 §10.3）。
   *
   * @return 本条车道从口岸闸预算里<b>真正用掉</b>的量（毫商品；无闸 ⇒ 0）——调用方据此扣共享预算（与运力/配额同一口径）
   */
  static long matchRoute(
      MatchContext ctx,
      HexCoord buyerHex,
      HexCoord sellerHex,
      CommodityId commodity,
      List<BuySlot> rawBuys,
      List<SellSlot> rawSells,
      long portTransitCapMilli) {
    long distance = ctx.topology.travelTicks(sellerHex, buyerHex);
    long moveCost = moveCostOf(ctx, buyerHex);
    if (moveCost >= TerrainType.IMPASSABLE_MOVE_COST) {
      for (BuySlot buy : rawBuys) {
        buy.blocked = MarketUnfilledReason.NO_ROUTE;
      }
      return 0L;
    }
    long capacityPerWindow =
        Math.max(
            1L,
            MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW
                * MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX
                / Math.max(1L, distance));
    long costPerUnit = distance * moveCost;
    long travelTicks = Math.max(1L, distance);
    // ★★ P4/P6：费率唯一来源 = 拓扑的 road/radial/基础费公式；P6 的两个商人调整量先从拓扑的只读函数取出
    //   （默认入口恒 0，旧行为逐值不变），再显式传给同一条 TransportTariff.perMille 算式。P9/P7 在组合根按
    //   MerchantPolicy.cityDiscountForLane/ruralPenaltyForLane 汇总后注入这两个函数。
    //   注意费率与上面的 costPerUnit（距离 × moveCost）是两个独立的数。
    long cityDiscountPerMille = ctx.topology.cityDiscountPerMilleBetween(sellerHex, buyerHex);
    long ruralPenaltyPerMille = ctx.topology.ruralPenaltyPerMilleBetween(sellerHex, buyerHex);
    // ★★ 2026-10-09 纠正：**费率里没有商品维**（F 批那个"商品系数乘整条费率"的入参已撤销）—— 商品维只出现在
    //   下面的基础费（读当刻状态表，GM 可改；缺键 ⇒ 现行硬编码分档 ⇒ 未设表的世界逐值等于改动前，I-F1）。
    long commodityBaseMilli = commodityFreightBaseMilli(ctx.topology, commodity);
    long freightRatePerMille =
        ctx.topology.freightPerMilleBetween(
            sellerHex, buyerHex, cityDiscountPerMille, ruralPenaltyPerMille);
    String routeKey = LaneUnservedBook.laneKey(sellerHex, buyerHex, commodity);
    RouteAccumulator acc =
        ctx.routes.computeIfAbsent(
            routeKey,
            ignored ->
                new RouteAccumulator(
                    sellerHex, buyerHex, commodity, capacityPerWindow, costPerUnit));

    // ★★ P-T1a：本条车道的口岸闸余量（毫商品；NO_GATE_MILLI = 没有口岸面 ⇒ min 恒等 ⇒ 逐值不变）。
    long portLeft = portTransitCapMilli;
    boolean portLogged = false;
    for (long round = 0; round < MARKET_MAX_TRANSPORT_ROUNDS; round++) {
      List<BuySlot> buys = new ArrayList<>();
      for (BuySlot buy : rawBuys) {
        if (buy.remaining > 0) {
          buys.add(buy);
        }
      }
      List<SellSlot> sells = new ArrayList<>();
      for (SellSlot sell : rawSells) {
        if (sell.remaining > 0) {
          sells.add(sell);
        }
      }
      if (buys.isEmpty() || sells.isEmpty()) {
        break;
      }
      long arrivalTick = ctx.round.day + travelTicks + round;
      long unitPrice = unitPriceOf(ctx, sells, commodity);
      // ★★ 2026-10-09：单位运费与货款价格解耦（商品种类 × 路线费率 × 默认承运成本）；撮合前的可负担量按它预判，
      //    真正的逐商号承运成本差异在 executeTrade/carrierChargeSplit 里按选中商号现算。
      // ★★ A2：发货格的服务成市 ⇒ 单位运费改由**服务牌价**给出（同一算式骨架，只换第三个因子）——
      //    预判（可负担量/总价上限）与结算读的是同一个 route.freightPerUnit ⇒ 两者不可能漂开。
      boolean haulService = haulServiceAt(ctx, sellerHex);
      long servicePriceMilli = servicePriceAt(ctx, sellerHex);
      long freightPerUnit =
          haulService
              ? HaulService.unitFreightMilli(
                  ctx.carrierPool.workPerGoodPerMilleOf(commodityBaseMilli, freightRatePerMille),
                  servicePriceMilli)
              : freightUnitMilli(
                  commodityBaseMilli, freightRatePerMille, plannedCarrierCostPerMille(ctx));
      RouteContext route =
          new RouteContext(
              sellerHex,
              buyerHex,
              commodity,
              unitPrice,
              freightRatePerMille,
              freightPerUnit,
              travelTicks,
              costPerUnit,
              capacityPerWindow,
              MARKET_TRANSPORT_LOSS_PER_MILLE,
              arrivalTick,
              false,
              haulService,
              servicePriceMilli);
      long[] weights = new long[buys.size()];
      long demand = 0L;
      long supply = 0L;
      // ★★ 3c（计划 §2.1：matchRoute 的"同一路线卖方同币"假设必须放宽）：本条路线的**单价所属币**
      //   不再由一只代表槽位（原 {@code sells.get(0)}）给出 —— 逐卖方槽位读出各自那张价表的计价币后折算
      //   （唯一读取口 = worstBuyerUnitPrice）。"这个单价属于哪个币"因此逐槽位可读、不留歧义。
      for (int i = 0; i < buys.size(); i++) {
        BuySlot buy = buys.get(i);
        if (buy.order.latestArrivalTick() < arrivalTick) {
          if (buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.LOGISTICS_TIME;
          }
          weights[i] = 0L;
          continue;
        }
        long buyerUnitPrice = worstBuyerUnitPrice(ctx, buy, sells, unitPrice);
        if (buyerUnitPrice < 0L || buy.limitInPayCurrency < buyerUnitPrice) {
          // ★ E：限价用**买方支付币**的口径比（说不出这种钱的价 ⇒ 也算限价不过）；归因是市场性理由。
          //   ★★ P-T5b：右式是**买方支付币**的单价（3c 的折算结果），故左边的限价也必须折成同一个币
          //     （BuySlot.limitInPayCurrency；同币时与 order.maxLandedPrice() 逐值相同 ⇒ 旧世界逐值不变）。
          if (buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
          }
          weights[i] = 0L;
          continue;
        }
        long affordable = chargeAffordableQuantity(ctx, buy, route);
        weights[i] = Math.min(buy.remaining, affordable);
        demand += weights[i];
      }
      for (SellSlot sell : sells) {
        supply += sell.remaining;
      }
      acc.demandMilli = Math.max(acc.demandMilli, demand);
      acc.supplyMilli = Math.max(acc.supplyMilli, supply);
      if (demand <= 0L || supply <= 0L) {
        break;
      }
      // ★★ S3：本路线按 unitCostEstimate + freightPerUnit 升序分档；每档在剩余运力内按同一条 ProportionalSplit 配给。
      //   ★★ P-T1d：服务序与"档"的切法见 matchGroup 的同款注释（缺省 ⇒ 与改前逐值相同）。
      List<BuySlot> matchBuys = procurementOrderedBuys(ctx, buys);
      List<List<BuySlot>> buyRuns = procurementBuyRuns(matchBuys);
      List<SellSlot> ordered = new ArrayList<>(sells);
      ordered.sort(servingOrder(ctx, route));
      long capacityLeft = capacityPerWindow;
      int tierStart = 0;
      // ★★ P-T1a：口岸闸（portLeft）是除运力（capacityLeft）之外的第二个量上限 —— 两者取小者；
      //   portLeft = 0 时这一轮一笔都不配（"被拦下的量不进候选集"），但槽位与后续轮的判断不变。
      while (tierStart < ordered.size() && capacityLeft > 0L && portLeft > 0L) {
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
        // ★ 空档防御：本档没有任何**还有剩余**的卖方（理论上不可达：ordered 只装本轮开始时 remaining>0 的槽位，
        //   而每档只消耗自己那几只槽位）⇒ 与改前同一条路：本档配不出量、进下一档，**不**提前 break
        //   （否则"取不到单价"会被误读成"这条路线到此为止"）。
        if (tier.isEmpty()) {
          tierStart = tierEnd;
          continue;
        }
        // 需求按**当前剩余**重算（前一层已成交的不再计入；与 matchGroup 的逐层语义同源）。
        //   ★ P-T1d：逐"买档"再重算一次并逐段配给（缺省只有一段 ⇒ 与改前逐值相同）。
        for (List<BuySlot> run : buyRuns) {
          long[] tierBuyWeights = new long[run.size()];
          long tierDemand = 0L;
          for (int i = 0; i < run.size(); i++) {
            // ★★ 3c：可负担量同样按**买方支付币**的单价折算（同一口径；逐卖方槽位读出单价所属币）。
            //   ★★ W2：改按**现金腿（运费 + 税）**折算 —— 货款不封顶（进债务）。
            long tierBuyerPrice = worstBuyerUnitPrice(ctx, run.get(i), tier, unitPrice);
            long affordable =
                tierBuyerPrice < 0L ? 0L : chargeAffordableQuantity(ctx, run.get(i), route);
            tierBuyWeights[i] = Math.min(run.get(i).remaining, affordable);
            tierDemand += tierBuyWeights[i];
          }
          if (tierDemand <= 0L) {
            continue; // 这一段没有可付需求 ⇒ 看下一段（缺省只有一段 ⇒ 与改前的"直接 break"逐值等价）
          }
          long tierSupply = 0L;
          long[] sellWeights = new long[tier.size()];
          for (int i = 0; i < tier.size(); i++) {
            sellWeights[i] = tier.get(i).remaining;
            tierSupply += sellWeights[i];
          }
          if (tierSupply <= 0L) {
            break; // 这一档已被前面的买段吃光 ⇒ 后面的买段也卖不动
          }
          // ★★ P-T1c：区级配额已删（设计书 §16）⇒ 跨区撮合只受运力（{@code capacityLeft}）与口岸闸（{@code portLeft}）约束。
          long matched =
              Math.min(tierDemand, Math.min(tierSupply, Math.min(capacityLeft, portLeft)));
          if (matched > 0L) {
            // ★★ W4/W4-fix2：买侧按结算序依次吃满（跨区与区内同一口径，见 buyPartsByPriority）；卖侧仍按权重比例。
            long[] buyParts = buyPartsByPriority(tierBuyWeights);
            long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, tierSupply);
            // ★★ 2026-10-09：路线窗口容量按**真正落账**的量扣 —— 承运商每周期运力不足时 pairUp 只会
            //   成交可承运的部分，若这里仍按 matched 扣，窗口容量会被高估、后续买方被误判"没运力"。
            //   ★ 配额同样按真正落账的量逐笔扣（唯一扣减点 = executeTrade）。
            long executed = pairUp(ctx, run, buyParts, tier, sellParts, unitPrice, route);
            acc.used = Math.addExact(acc.used, executed);
            capacityLeft -= executed;
            portLeft -= executed; // ★ P-T1a：口岸闸按真正落账的量扣（与运力同一口径）
          }
          if (capacityLeft <= 0L || portLeft <= 0L) {
            break; // 预算用尽 ⇒ 本档后面的买段也配不出量
          }
        }
        tierStart = tierEnd;
      }
      // ★★ P-T1a：口岸闸用尽且还有"愿意且买得起"的缺口/余货 ⇒ 具名归因（被拦下的量不落状态、不进账）。
      //   ★ 条件里的 demand > 0 是刻意的：没有合格需求时，卖方的剩余不该被记成"口岸拦的"（真因是没人要/限价）。
      if (portLeft <= 0L
          && demand > 0L
          && (remainingOf(buys) > 0L || remainingOfSells(sells) > 0L)) {
        for (BuySlot buy : buys) {
          if (buy.remaining > 0L && buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.PORT_THROTTLED;
          }
        }
        for (SellSlot sell : sells) {
          if (sell.remaining > 0L) {
            sell.portBlocked = true;
          }
        }
        // ★★ W2-fix：把"被口岸拦下的**买家侧需求量**"按 买方区 × 商品 记成**日志读数**（见 portBlockedMilliByZoneCommodity）。
        //   ★ 上限 = 本 lane 配给前"愿意且买得起"的合计（{@code demand}）——闸拦下的不可能超过真的想买的那份；
        //     再按本轮仍未满足的量封顶，免得把"已经成交的那部分"也算成被拦（那是幅度错，与 V-20 同族）。
        //   ★ 它只作读数：被拦下的量**本来就在 D 里**（D 取挂单全额），回加进 D 就是重复计数（v3.5 明令不新增口岸项）。
        //   ★ 与下面的运力截断**互斥**：那一支的判据是 {@code capacityLeft <= 0}，本支是 {@code portLeft <= 0}
        //     —— 同一份未服务量不会同时进两项，{@code MARKET_DEMAND_COMPOSITION} 的两个"其中"因此不重叠。
        recordPortBlockedDemand(ctx, buyerHex, commodity, Math.min(demand, remainingOf(buys)));
        if (!portLogged) {
          portLogged = true;
          logPortRouteExhausted(
              ctx, commodity, sellerHex, buyerHex, rawBuys.size(), rawSells.size());
        }
      }
      if (capacityLeft <= 0L && (remainingOf(buys) > 0L || remainingOfSells(sells) > 0L)) {
        acc.bottleneck = true;
        // ★★ D-1b（净额记账）：路线窗口预算用尽也是"这条 lane 运力不够"的**同一个事实** ⇒ 走同一份"已记"状态，
        //   且与另两处同口径：记的是**车道级** min(买侧余量合计, 卖侧余量合计) = 这条 lane 真正没运走的量，
        //   不是各侧自己的余量（后者会让余量大的一侧把"本侧装不下、也没人要"的那份也剔出统计 —— 与 D-1 同一个幅度错）。
        long recorded =
            ctx.laneUnserved.claimLane(
                LaneUnservedBook.laneKey(sellerHex, buyerHex, commodity),
                Math.min(remainingOf(buys), remainingOfSells(sells)));
        for (BuySlot buy : buys) {
          if (buy.remaining > 0L && buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
            // ★★ F-2（2026-10-10）：本落点是**路线窗口容量**用尽（有货、有路、有运力池，窗口吃不下）
            //   ⇒ 具名"运力不足"这一支，**不是**"没有服务卖"（两档不许混）。
            buy.logisticsDetail = MerchantCapacityPool.LogisticsBlock.CAPACITY_EXHAUSTED.detail();
          }
          // ★★ M-A1（V-20）：本车道路线窗口容量用尽 ⇒ 未服务的余量记成"被运力截断"，不进自适应定价的统计。
          if (buy.remaining > 0L && recorded > 0L) {
            buy.capacityTruncatedMilli = Math.addExact(buy.capacityTruncatedMilli, recorded);
          }
        }
        // ★★ 2026-10-09：卖方剩余同样具名（路线每窗口运力用尽）—— 不让它落进 OUTCOMPETED 的误档。
        for (SellSlot sell : sells) {
          if (sell.remaining > 0L) {
            sell.capacityBlocked = true;
            sell.logisticsDetail = MerchantCapacityPool.LogisticsBlock.CAPACITY_EXHAUSTED.detail();
            if (recorded > 0L) {
              sell.capacityTruncatedMilli = Math.addExact(sell.capacityTruncatedMilli, recorded);
            }
          }
        }
      }
    }
    if (MARKET.isDebugEnabled()) {
      // ★★ E：跨区路线的逐条读数（"异币为什么没成交"的第二现场：这条路线上到底有没有量）。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_CROSS_REGION_ROUTE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "commodity",
                  commodity.value(),
                  "sellerHex",
                  sellerHex,
                  "buyerHex",
                  buyerHex,
                  "buys",
                  rawBuys.size(),
                  "sells",
                  rawSells.size(),
                  "used",
                  acc.used,
                  "demand",
                  acc.demandMilli,
                  "supply",
                  acc.supplyMilli,
                  "bottleneck",
                  acc.bottleneck,
                  // ★ P-T1a：本车道用掉的口岸闸预算（毫商品；无闸 ⇒ 0）——"被拦下"的读数在轮级 INFO 里。
                  "portUsedMilli",
                  portTransitCapMilli == PortThrottle.NO_GATE_MILLI
                      ? 0L
                      : portTransitCapMilli - portLeft));
    }
    // ★★ P-T1a：本车道真正用掉的口岸闸预算（无闸 ⇒ 0；由调用方从区对共享预算里扣）。
    return portTransitCapMilli == PortThrottle.NO_GATE_MILLI ? 0L : portTransitCapMilli - portLeft;
  }

  /**
   * ★★ <b>区内跨格（同市场区、不同 hex）也走运输职能</b>（2026-10-09 立、2026-10-10 M-A1 改绑）—— 构造一条"即时结算、但要付运费"的
   * 合成路线：{@code immediate = true}（不走 ShipmentBatch/在途），货款与运费仍在成交日结清。
   *
   * <p>★ M-A1：与跨区**同一条**判据 —— 发货格（{@code sell.hex}）必须有运力（该格有"选了跑商的家户"）； 没有 ⇒ 本方法根本不被调用（调用点具名拦下
   * {@code LOGISTICS_CAPACITY}）。费率与跨区同源（{@link MarketTopology#freightPerMilleBetween} + {@link
   * #freightUnitMilli}），路线窗口容量取出厂值 （真正的硬约束是逐 hex 运力池，由 {@link MerchantCapacityPool#select} 扣）。
   */
  static RouteContext intraRegionFreightRoute(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    CommodityId commodity = buy.order.commodity();
    long distance = Math.max(1L, ctx.topology.travelTicks(sell.hex, buy.hex));
    long moveCost = Math.max(1L, moveCostOf(ctx, buy.hex));
    // ★★ 区内跨格与跨区**同源**：费率不带商品维（距离/辐射/道路），商品维只走基础费（读同一张状态表，
    //   缺键 ⇒ 现行硬编码分档 ⇒ 逐值不变）；区别只在 immediate=true（不走在途）。
    long rate = ctx.topology.freightPerMilleBetween(sell.hex, buy.hex);
    // ★★ A2：与上面条同源 —— 发货格服务成市 ⇒ 单位运费由服务牌价给出（预判与结算同一个数）。
    boolean haulService = haulServiceAt(ctx, sell.hex);
    long servicePriceMilli = servicePriceAt(ctx, sell.hex);
    long commodityBaseMilli = commodityFreightBaseMilli(ctx.topology, commodity);
    long freightPerUnit =
        haulService
            ? HaulService.unitFreightMilli(
                ctx.carrierPool.workPerGoodPerMilleOf(commodityBaseMilli, rate), servicePriceMilli)
            : freightUnitMilli(commodityBaseMilli, rate, plannedCarrierCostPerMille(ctx));
    return new RouteContext(
        sell.hex,
        buy.hex,
        commodity,
        unitPrice,
        rate,
        freightPerUnit,
        distance,
        Math.multiplyExact(distance, moveCost),
        MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW,
        MARKET_TRANSPORT_LOSS_PER_MILLE,
        ctx.round.day,
        true,
        haulService,
        servicePriceMilli);
  }
}
