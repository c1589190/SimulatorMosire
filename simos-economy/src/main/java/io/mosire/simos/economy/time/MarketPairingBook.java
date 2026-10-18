package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketMoneyBook.LEG_CASH;
import static io.mosire.simos.economy.time.MarketMoneyBook.settlementUnitPrice;
import static io.mosire.simos.economy.time.MarketRouteMatcher.intraRegionFreightRoute;
import static io.mosire.simos.economy.time.MarketShipmentBook.blockLaneWithoutCapacity;
import static io.mosire.simos.economy.time.MarketTradeExecutor.executeTrade;
import static io.mosire.simos.economy.time.MarketTradeMath.deferralPossible;
import static io.mosire.simos.economy.time.MarketTradeMath.exactChargeAffordableUpTo;
import static io.mosire.simos.economy.time.MarketTradeMath.exactTotalCostAffordableUpTo;
import static io.mosire.simos.economy.time.MarketTradeMath.payableMoneyOf;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.List;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketPairingBook {

  private MarketPairingBook() {}

  /**
   * 一批买卖按比例配对落账（区内即时或跨区在途）。
   *
   * <p>★★ <b>逐笔复核买方的剩余可付</b>（缺陷 A 的修法）：预分配的 {@code buyParts} 只是**上限**；每一小笔真正落账前， 都按当前账（{@code
   * spendable + 本单剩余冻结}、订单预算余额）重新算一次"这一笔最多买多少"，并取小。
   * 货款/运费每个小笔各自向上取整，因此整单按总价反解出的数量不保证逐笔加起来付得起；只有把每笔的实际付款累进 {@link BuySlot#spentMilli}（{@code
   * executeTrade} 写）再递推复核，任何成交序列下累计付款才不会越过后端的冻结/余额守卫。
   *
   * <p>★★ <b>W4-fix2：{@code buyParts} 是"取用权"而不是"预先切好的量"</b> —— 买侧份额 = 各买槽自己的可付需求 （见 {@link
   * #buyPartsByPriority}），<b>按 {@code buyers} 的列表序（= 结算序）在此依次取用</b>：先到者先吃满， 吃不到（lane
   * 不通）或吃不下的余量<b>留给下一个买槽</b>。总量仍由卖侧份额（{@code Σ sellParts = matched}）封顶 ⇒ 所有 lane
   * 都通时与"预先切分"逐值相同（同一个贪心，只是分配发生在取用时刻）。
   */
  static long pairUp(
      MatchContext ctx,
      List<BuySlot> buyers,
      long[] buyParts,
      List<SellSlot> sellers,
      long[] sellParts,
      long price,
      RouteContext route) {
    long executedTotal = 0L;
    int sellerIndex = 0;
    long sellLeft = sellers.isEmpty() ? 0L : sellParts[0];
    // ★★ W4-fix3（fail-closed 修 NP_LOAD_OF_KNOWN_NULL_VALUE）：路线种类读数**在判空之前**一次算好（具名）。
    //   {@code route == null} 在本方法里是合法取值（没有预建运费路线 —— 同格即时腿），故**不拒、不降级、不改判据**；
    //   但它一旦被下面那条 {@code route == null} 判过，再把 {@code route} 传出去就是"已知为 null 仍被加载/传递"
    //   （SpotBugs 报的 `[line 5694]` 就是那次调用 —— 恰在该判空分支内）。判空拼写点收口到 routeKindOf，
    //   读数本身结构上非 null。
    // ★★ W4-fix4（读数如实）：{@code routeKindOf(null)} 由 {@code "same-hex"} 改名为 {@code "no-route"}
    //   —— "调用方没给预建路线"不等于"同格"，一个词兼两义会打出假读数；而"跨格 + 发货格无承运运力"那一支
    //   **能确知真相**，故不借泛化读数、本处具名 {@code "cross-hex-no-carrier-capacity"}。
    //   字段名仍是 {@code route}，事件名/级别不变，判据零改。
    final String routeKind = routeKindOf(route);
    // ★★ W4-fix2 诊断（§一.9：关键判据 DEBUG + 具名理由）：本轮**本该**能落账多少、实际落了多少、卡在哪一条。
    //   ★ plannedTotal = 买侧取用权合计（= 各槽可付需求之和）；offeredTotal = 卖侧份额合计（= matched，真正的量上限）。
    long plannedTotal = 0L;
    for (long part : buyParts) {
      plannedTotal = Math.addExact(plannedTotal, Math.max(0L, part));
    }
    long offeredTotal = 0L;
    for (long part : sellParts) {
      offeredTotal = Math.addExact(offeredTotal, Math.max(0L, part));
    }
    long selfMatchSkips = 0L;
    long currencySkips = 0L;
    String stopReason = "all-offered-quantity-served";
    for (int i = 0; i < buyers.size(); i++) {
      long need = buyParts[i];
      BuySlot buy = buyers.get(i);
      while (need > 0L) {
        while (sellerIndex < sellers.size() && sellLeft <= 0L) {
          sellerIndex++;
          if (sellerIndex < sellers.size()) {
            sellLeft = sellParts[sellerIndex];
          }
        }
        if (sellerIndex >= sellers.size()) {
          stopReason = "all-sellers-consumed-or-skipped";
          logPairingShortfall(
              ctx,
              buyers,
              buyParts,
              sellParts,
              sellers,
              routeKind,
              plannedTotal,
              offeredTotal,
              executedTotal,
              selfMatchSkips,
              currencySkips,
              sellerIndex,
              stopReason);
          return executedTotal;
        }
        SellSlot sell = sellers.get(sellerIndex);
        if (sell.seller.actor().equals(buy.buyer.actor())) {
          // ★★ 2026-10-08 自配对守卫（形制照 FxSettlement.matchBook 的 `bid.owner.equals(ask.owner)` 跳过）：
          //   同一个主体既在卖方槽又在买方槽时，撮合会把它配给自己 —— 那不是一笔发生额，`Transfer` 的两端
          //   不得相等（fail-closed 契约，本处**不放宽**，只是不再去撞它）。
          //   ★ 为什么会同户双挂（机制结论见 .superpowers/sdd/2026-10-08-stage2-selfmatch-fix/fix-ledger.md）：
          //     ① 卖单余量 = 库存 − 冻结 − 必要投入 − 生活保留(35 天) − 需求目标（ordersFor）；
          //     ② 买单量 = 目标缺口（家户的 baseTarget = 生活保留）**加**套利买盘（本文件 ordersFor 的
          //        `quantity = addExact(quantity, arbitrageQuantity)`），而套利决定来自**劳动阶段**的当日
          //        快照（LaborQueueSettlement.buildArbitrageSnapshots），当日收获/产出落在这两个阶段之间 ⇒
          //        同一户可以"劳动时缺布（保留价 ≥ 市价 1.1 倍 ⇒ 挂套利买盘）"而"开市时布有余量（⇒ 挂卖单）"；
          //        目标缺口那一项此时恒为 0（卖单余量 > 0 ⇒ 可用 > 生活保留），故同户的买腿只可能来自套利买盘。
          //   ★ 取舍 = **跳过**（不是把槽位打成 blocked）：本笔不成交、市场继续 —— 本买方改从**下一个卖方**
          //     取货；`sellerIndex` 只前进不回退 ⇒ 被跳过的这一份卖单余量在**本轮的现金撮合里**不再被取用
          //     （随后的信用轮仍可能把它卖给别的主体 —— 那里另有同户守卫）。该卖槽若最终没卖完，
          //     `remaining > 0` ⇒ 落进未成交读数（collectUnfilled 按既有档位归因）。后果：这一份供给本轮
          //     可能卖不掉，但既不铸自转移，也不会让整轮 400。★ 不置 noMoney/blocked：钱与需求都没问题，
          //     下一轮照常挂单。
          //   ★ 记录（§一.9）：业务拒绝 = INFO（具名：家户 / 商品 / 两格 / 量）；理由 = DEBUG。
          long skipped = Math.min(need, sellLeft);
          EventLog.channel(MARKET)
              .info(
                  LogEvent.of(
                      "MARKET_SELF_MATCH_SKIPPED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.round.day,
                      "household",
                      buy.buyer.actor().id(),
                      "commodity",
                      buy.order.commodity().value(),
                      "buyerHex",
                      buy.hex,
                      "sellerHex",
                      sell.hex,
                      "skippedQuantityMilli",
                      skipped,
                      "reason",
                      "same-owner-buy-and-sell-in-one-round"));
          if (MARKET.isDebugEnabled()) {
            EventLog.channel(MARKET)
                .debug(
                    LogEvent.of(
                        "MARKET_SELF_MATCH_SKIPPED_WHY",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        ctx.round.day,
                        "household",
                        buy.buyer.actor().id(),
                        "commodity",
                        buy.order.commodity().value(),
                        "buyRemainingMilli",
                        buy.remaining,
                        "sellRemainingMilli",
                        sell.remaining,
                        "sellAllocatedMilli",
                        sellLeft,
                        "reason",
                        "sellable-residual-plus-buy-leg-demand-gap-zero-arbitrage-overlay"));
          }
          sellerIndex++;
          sellLeft = sellerIndex < sellers.size() ? sellParts[sellerIndex] : 0L;
          selfMatchSkips++;
          continue;
        }
        long quantity = Math.min(need, sellLeft);
        // ★★ E（§3.1）：本笔在**买方支付币**下的单价 —— 异币要按"钱的价"折算（同币 ⇒ 原价）。
        //   ★ 为什么必须在配给之前算：可负担量与"N 笔各 ceil 一毫"的封顶都按它折算；用卖方币价预判会在异币
        //     成交上高估可买量、进而算出买方付不起的货款（负余额）。
        //   ★ 不划算（说不出这种钱的价 / 折回本币不够保留价）⇒ **跳过这一家卖方**看下一家（不是打死整个买方）：
        //     同 hex 的别家卖方可能收的是他能付的钱。归因由 executeTrade 侧的同一拼写点落账。
        long buyerUnitPrice = settlementUnitPrice(ctx, buy, sell, quantity, price, LEG_CASH, true);
        if (buyerUnitPrice < 0L) {
          sellerIndex++;
          sellLeft = sellerIndex < sellers.size() ? sellParts[sellerIndex] : 0L;
          currencySkips++;
          continue;
        }
        // ★★ M-A1：跨格（同市场区、不同 hex）的成交必须由**发货格**的运力池承运 ⇒ 合成一条"即时但有运费"的路线；
        //   同 hex 仍走零运费即时成交（route == null，不受运力影响）。
        //   ★ 发货格**没有**运力（没有"选了跑商的家户"）⇒ 这条路线根本不建：本笔具名拦下（LOGISTICS_CAPACITY），
        //     货不走、"被截断量"记进两个槽位（V-20）。把判据放在候选生成处（而不是只靠 executeTrade）是**必需**的：
        //     并行回放路径不认 executed=0（见 RegionClone/prepareRegion 的注释）。
        RouteContext effectiveRoute = route;
        if (route == null && !buy.hex.equals(sell.hex)) {
          // ★★★ W7（2026-10-11）：**候选生成处的运力闸改成全池口径**（用户裁定：运力提供全市场区共享）。
          //   改前是 {@code !carrierPool.hasCapacityAt(sell.hex)}（只看**发货格**）⇒ 发货格没有跑商家户时这条跨格
          //   车道**根本不建**，"需求"连一次求值机会都没有（调查报告 §2.5：sw 18 格 / tp 36 格的货因此发不出去）。
          //   现在只在全池**一件可交付服务货都没有**时才在候选生成处拦下；池里还有货 ⇒ 放行进 executeTrade，
          //   由 {@code MerchantCapacityPool.select} 按**全局序**分配（够不着的承运方在那里具名归因）。
          if (!ctx.carrierPool.hasCapacityAt(sell.hex)
              && ctx.carrierPool.totalCapacityMilli() <= 0L) {
            blockLaneWithoutCapacity(
                ctx, List.of(buy), List.of(sell), sell.hex, buy.hex, buy.order.commodity());
            stopReason = "seller-hex-without-carrier-capacity";
            // ★★ W4-fix4：这一支**能确知真相**（上面刚判过 route == null 且 !buy.hex.equals(sell.hex) ⇒ 跨格），
            //   故用具名读数，不用 routeKindOf(null) 那条泛化值（它只说"没给预建路线"，说不出跨格与运力）。
            logPairingShortfall(
                ctx,
                buyers,
                buyParts,
                sellParts,
                sellers,
                "cross-hex-no-carrier-capacity",
                plannedTotal,
                offeredTotal,
                executedTotal,
                selfMatchSkips,
                currencySkips,
                sellerIndex,
                stopReason);
            break;
          }
          effectiveRoute = intraRegionFreightRoute(ctx, buy, sell, price);
        }
        boolean freeTicket =
            price <= 0L && (effectiveRoute == null || effectiveRoute.freightPerUnit() <= 0L);
        // ★★★ W2（2026-10-11）：**取消支付能力硬约束** —— 成交数量不再按"付得起多少货款"折回。
        //   改动前这里是两道封顶：① {@code affordableQuantity}（按"可花 + 冻结"反解买得起的量）、
        //   ② {@code exactAffordableUpTo}（按"货款 + 运费 + 税 ≤ 可付"逐笔精确封顶）。
        //   W2 起货款可以**全额进债务**（用户 0–8 第 6 条「一股脑全拿下，先不管有没有能力支付…然后把价格全部记为债务」）
        //   ⇒ 数量只受 需求 / 卖方剩余 / 运力 / 口岸 约束。
        //   ★ <b>唯一保留的现金约束 = 现金腿（运费 + 税）</b>：它们的收款人是承运家户与政府国库户，不是卖方，
        //     不属于"价款记债"这条通道（判据与理由见 {@link #chargesAtMost}）。同格无税 ⇒ 该约束恒成立。
        //   ★★ <b>没有赊购能力的世界仍按旧口径</b>（{@link #deferralPossible}：没有债务工作副本 / 卖方不是家户 /
        //     国库户的授权买盘）⇒ 那种成交仍按"货款 + 运费 + 税 ≤ 可付"封顶，绝不铸出一笔无法记账的欠款。
        long payable = payableMoneyOf(ctx, buy);
        long quantityByCash =
            deferralPossible(ctx, buy, sell)
                ? exactChargeAffordableUpTo(
                    ctx,
                    buy,
                    sell,
                    buy.order.commodity(),
                    quantity,
                    payable,
                    buyerUnitPrice,
                    effectiveRoute)
                : exactTotalCostAffordableUpTo(
                    ctx,
                    buy,
                    sell,
                    buy.order.commodity(),
                    quantity,
                    payable,
                    buyerUnitPrice,
                    effectiveRoute);
        if (quantityByCash <= 0L) {
          // ★ 具名改义：{@code NO_BUDGET} 不再表示"买不起货"，只表示"现金付不出这一笔的运费/税"
          //   （或：本笔没有赊购能力、而现金付不出整笔）。
          //   ★ 不置 {@code noMoney}：那会让这个买方在本轮剩余的所有路线上被跳过 —— 而它仍可能在其他
          //     （同格 / 更近的）卖方那里成交（那些 lane 的现金腿可能是 0）。
          //   ★ 完全免费（0 价 + 0 运费 + 0 税）时判据恒真 ⇒ 这一支结构上到不了。
          if (!freeTicket && buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.NO_BUDGET;
          }
          stopReason = "cash-leg-of-freight-or-tax-unaffordable";
          break;
        }
        quantity = quantityByCash;
        long executed = executeTrade(ctx, buy, sell, quantity, price, effectiveRoute);
        if (executed <= 0L) {
          // ★★ 承运运力不足 ⇒ 本笔不成交（executeTrade 已把买卖槽位与路线标成 LOGISTICS_CAPACITY）。
          //   不置 noMoney（钱不是瓶颈），也不扣槽位剩余 —— 剩余留给下一轮/下一窗口。
          stopReason = "execute-trade-returned-zero";
          break;
        }
        executedTotal = Math.addExact(executedTotal, executed);
        buy.remaining -= executed;
        sell.remaining -= executed;
        need -= executed;
        sellLeft -= executed;
      }
    }
    logPairingShortfall(
        ctx,
        buyers,
        buyParts,
        sellParts,
        sellers,
        routeKind,
        plannedTotal,
        offeredTotal,
        executedTotal,
        selfMatchSkips,
        currencySkips,
        sellerIndex,
        stopReason);
    return executedTotal;
  }

  /**
   * ★★ <b>W4-fix3：路线种类的唯一拼写点</b>（喂给 {@link #logPairingShortfall} 的具名读数）。
   *
   * <p>{@code route == null} 是<b>合法取值</b>：本轮<b>没有预建运费路线</b>（同格即时腿就是这种形态）。W4-fix3 曾把它具名成 {@code
   * "same-hex"}，但那把一个词同时用于"没给预建路线"与"同格" ⇒ 跨格、而发货格又无承运运力时同样 {@code route == null}，
   * 打出来却是"同格"，<b>读数不准</b>。W4-fix4 裁定改为如实具名 {@code "no-route"}。于是调用方（尤其 {@link #pairUp} 里"已判为
   * null"的那条分支内的发射点）<b>不必</b>再加载那个已知为 null 的引用 —— 那正是 SpotBugs {@code NP_LOAD_OF_KNOWN_NULL_VALUE}
   * 的由来（已知 null 仍被加载/传递）。判据、成交序列、日志字段名（仍是 {@code route}）与另两个取值（{@code intra-region-cross-hex} /
   * {@code cross-region}）与改动前逐字相同。
   */
  static String routeKindOf(RouteContext route) {
    if (route == null) {
      return "no-route";
    }
    return route.immediate() ? "intra-region-cross-hex" : "cross-region";
  }

  /**
   * ★★ <b>W4-fix2：一次配给的"没落账量"具名读数</b>（§一.9：关键判据 DEBUG + 具名理由）。
   *
   * <p>配给（{@link #buyPartsByPriority} / {@link ProportionalSplit}）给出的是<b>上限</b>，真正落账还要过自配对、币种、运力、
   * 现金腿四关 ⇒ 两者不等时这里给出一条"差在哪一关、差多少"。它只读、不落状态、不改任何判据。
   */
  static void logPairingShortfall(
      MatchContext ctx,
      List<BuySlot> buyers,
      long[] buyParts,
      long[] sellParts,
      List<SellSlot> sellers,
      String routeKind,
      long plannedTotal,
      long offeredTotal,
      long executedTotal,
      long selfMatchSkips,
      long currencySkips,
      int sellerIndex,
      String stopReason) {
    long ceiling = Math.min(plannedTotal, offeredTotal);
    if (!MARKET.isDebugEnabled() || executedTotal >= ceiling || buyers.isEmpty()) {
      return;
    }
    BuySlot firstBuy = buyers.get(0);
    SellSlot firstSell = sellers.isEmpty() ? null : sellers.get(0);
    // ★ 卡点读数：指针停在哪一条卖槽上，以及**那个格的买方里有多少人这一轮根本没分到份额**
    //   （"没份额 ⇒ 进不了内层循环 ⇒ 永远吃不到这条卖槽"是把配给量整批丢掉的结构性原因）。
    SellSlot stalled =
        sellers.isEmpty()
            ? null
            : sellers.get(Math.min(Math.max(sellerIndex, 0), sellers.size() - 1));
    long buyersInStalledHex = 0L;
    long buyersInStalledHexWithZeroPart = 0L;
    if (stalled != null) {
      for (int i = 0; i < buyers.size(); i++) {
        if (!buyers.get(i).hex.equals(stalled.hex)) {
          continue;
        }
        buyersInStalledHex++;
        if (buyParts[i] <= 0L) {
          buyersInStalledHexWithZeroPart++;
        }
      }
    }
    long buyersWithZeroPart = 0L;
    for (long part : buyParts) {
      if (part <= 0L) {
        buyersWithZeroPart++;
      }
    }
    // ★ 同格买槽里"有可付需求却没取用权"的条数 —— W4 初版的预分配形态下这>0 就是"整批被丢掉"的直接原因
    //   （取用权就是可付需求 ⇒ 本形态下结构上恒 0；留作读数，回归复发时当场可见）。
    long buyersInStalledHexWithDemandButNoPart = 0L;
    if (stalled != null) {
      for (int i = 0; i < buyers.size(); i++) {
        if (buyers.get(i).hex.equals(stalled.hex) && buyParts[i] <= 0L) {
          buyersInStalledHexWithDemandButNoPart++;
        }
      }
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_PAIRING_SHORTFALL",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "route",
                routeKind,
                "commodity",
                firstBuy.order.commodity().value(),
                "region",
                firstBuy.regionId,
                "buyers",
                buyers.size(),
                "sellers",
                sellers.size(),
                "buyTakingRightsMilli",
                plannedTotal,
                "sellOfferedMilli",
                offeredTotal,
                "executedMilli",
                executedTotal,
                "lostMilli",
                ceiling - executedTotal,
                "topBuyerAllocatedMilli",
                Math.max(0L, buyParts[0]),
                "topBuyerHousehold",
                firstBuy.buyer.household().value(),
                "topBuyerHex",
                firstBuy.hex.q() + "," + firstBuy.hex.r(),
                "topBuyerCurrency",
                firstBuy.currency.value(),
                "topBuyerRemainingMilli",
                firstBuy.remaining,
                "topBuyerBlocked",
                firstBuy.blocked == null ? "-" : firstBuy.blocked.name(),
                "firstSellerHousehold",
                firstSell == null ? "-" : firstSell.seller.household().value(),
                "firstSellerHex",
                firstSell == null ? "-" : firstSell.hex.q() + "," + firstSell.hex.r(),
                "stalledSellerIndex",
                stalled == null ? -1 : sellerIndex,
                "stalledSellerHex",
                stalled == null ? "-" : stalled.hex.q() + "," + stalled.hex.r(),
                "stalledSellerAllocatedMilli",
                stalled == null || sellerIndex < 0 || sellerIndex >= sellParts.length
                    ? -1L
                    : sellParts[sellerIndex],
                "buyersInStalledSellerHex",
                buyersInStalledHex,
                "buyersInStalledSellerHexWithZeroPart",
                buyersInStalledHexWithZeroPart,
                "buyersInStalledSellerHexWithDemandButNoTakingRight",
                buyersInStalledHexWithDemandButNoPart,
                "buyersWithZeroPart",
                buyersWithZeroPart,
                "selfMatchSkips",
                selfMatchSkips,
                "currencySkips",
                currencySkips,
                "stopReason",
                stopReason));
  }
}
