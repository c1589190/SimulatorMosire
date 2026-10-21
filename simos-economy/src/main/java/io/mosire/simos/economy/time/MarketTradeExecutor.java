package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.TRANSPORT_LOSS_ACCOUNT;
import static io.mosire.simos.economy.time.MarketFreezeBook.DEFERRED_PAYMENT_TERMS;
import static io.mosire.simos.economy.time.MarketFreezeBook.refreshBuyFrozen;
import static io.mosire.simos.economy.time.MarketFreezeBook.refreshSellFrozen;
import static io.mosire.simos.economy.time.MarketFreezeBook.releaseBuyFrozenSum;
import static io.mosire.simos.economy.time.MarketFreezeBook.releaseSellFrozenSum;
import static io.mosire.simos.economy.time.MarketMatchSupport.FillIntent;
import static io.mosire.simos.economy.time.MarketMatcher.actorKeyOf;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketModelBook.ShipmentBuilder;
import static io.mosire.simos.economy.time.MarketModelBook.ShipmentKey;
import static io.mosire.simos.economy.time.MarketMoneyBook.LEG_CASH;
import static io.mosire.simos.economy.time.MarketMoneyBook.logForeignCurrencyFill;
import static io.mosire.simos.economy.time.MarketMoneyBook.settlementUnitPrice;
import static io.mosire.simos.economy.time.MarketRosterBook.ceilDiv;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.reduceUnmet;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;
import static io.mosire.simos.economy.time.MarketShipmentBook.FreightCharge;
import static io.mosire.simos.economy.time.MarketShipmentBook.carrierChargeSplit;
import static io.mosire.simos.economy.time.MarketShipmentBook.commodityFreightBaseMilli;
import static io.mosire.simos.economy.time.MarketShipmentBook.deductBuyerLossNoTransfer;
import static io.mosire.simos.economy.time.MarketShipmentBook.deliverHaulService;
import static io.mosire.simos.economy.time.MarketShipmentBook.freightOf;
import static io.mosire.simos.economy.time.MarketShipmentBook.loadInTransit;
import static io.mosire.simos.economy.time.MarketShipmentBook.logLogisticsBusinessRejection;
import static io.mosire.simos.economy.time.MarketShipmentBook.logisticsBlockDetail;
import static io.mosire.simos.economy.time.MarketShipmentBook.markCapacityBlocked;
import static io.mosire.simos.economy.time.MarketShipmentBook.settleHaulRuns;
import static io.mosire.simos.economy.time.MarketTaxPlanner.logFreightCurrencyUnvalued;
import static io.mosire.simos.economy.time.MarketTaxPlanner.logTaxCharged;
import static io.mosire.simos.economy.time.MarketTaxPlanner.taxReasonOf;
import static io.mosire.simos.economy.time.MarketTaxPlanner.taxesFor;
import static io.mosire.simos.economy.time.MarketTradeMath.deferralPossible;
import static io.mosire.simos.economy.time.MarketTradeMath.inTransit;
import static io.mosire.simos.economy.time.MarketTradeMath.payableMoneyOf;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketTradeExecutor {

  private MarketTradeExecutor() {}

  /**
   * ★★ <b>落一笔成交</b>（区内即时 / 跨区在途）。
   *
   * <p>★★ <b>2026-10-09 承运硬约束</b>：跨区成交<b>先选承运、再落账</b>；商号/路线可承运量不足 ⇒ 成交数量收缩到实际可承运量 （{@code
   * executed}），未承运部分不发货、不免费成交，并以 {@link MarketUnfilledReason#LOGISTICS_CAPACITY} 具名留在
   * 买卖槽位的剩余里。完全没有可承运量 ⇒ 本笔成交量为 0（不改任何余额、不铸货腿/钱腿）。
   *
   * <p>★★ <b>运费与货款解耦</b>：货款腿只由 {@code unitPrice} 决定（0 价 ⇒ 0），运费腿由 {@code route.freightPerUnit}（商品种类
   * × 路线费率 × 承运成本）决定 —— 0 价免费交易仍要付运费。
   *
   * @return 实际成交量（毫商品）；0 = 本笔没有成交（调用方不得再减槽位剩余）
   */
  static long executeTrade(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      RouteContext route) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    // ★★★ DEF-2（2026-10-20）：S3 生成的 sell intent 到 S7-A 执行时可能已 stale —— S4 消费/生产、S6 工资等
    //   已经改过库存/冻结，旧卖单的剩余可能超过当刻可卖量。这里必须在**任何账户/冻结/账本写入之前**按当刻
    //   卖方可卖量封顶本笔成交（也早于 settlementUnitPrice / route / 运费 / 税 / 货腿 / 钱腿）：
    //   ★ DEF-2b 起 commitFreezes 已先按 (seller, commodity) 轴把冻结与 remaining 封顶；本段保留为**第二道防线**
    //     （撮合中途库存还可能被同轮其他成交改变）。
    //   available = max(0, stock − frozen + 本卖单自己的冻结剩余)
    //   ★ frozen 里包含本卖单还冻结着的那一份；它正是本笔可释放的货，故加回 ownSellReservation。
    //     其他冻结（别的卖单/必要投入等）绝不能被当成可卖量。
    long stock = stockOf(round, sell.seller, commodity);
    long frozen = frozenGoodsOf(round, sell.seller, commodity);
    long ownSellReservation = sell.frozenRemaining;
    long available = Math.max(0L, stock - frozen + ownSellReservation);
    long capped = Math.min(quantity, available);
    if (capped <= 0L) {
      sell.blocked = MarketUnfilledReason.ALL_RESERVED;
      logSellStockExhausted(
          ctx, buy, sell, commodity, quantity, stock, frozen, ownSellReservation, available);
      return 0L;
    }
    if (capped < quantity) {
      logSellStockShrunk(
          ctx,
          buy,
          sell,
          commodity,
          quantity,
          capped,
          stock,
          frozen,
          ownSellReservation,
          available);
      quantity = capped;
    }
    // ★★ E（§3.1）：**本笔收什么钱、按什么价** —— 唯一拼写点 {@link #settlementUnitPrice}（三条腿共用）。
    //   同币 ⇒ 返回原单价（判据恒成立 ⇒ 逐值退回改前）；异币 ⇒ 按"钱的价"折算买方币单价并复核判据
    //   （买方付出金额 × 我对该币的估值 ≥ 我的保留价 × 数量）；不划算 ⇒ 返回 0、账户一字未动，
    //   归因走既有的市场性理由（不是"币种不符"）。
    long buyerUnitPrice = settlementUnitPrice(ctx, buy, sell, quantity, unitPrice, LEG_CASH, true);
    if (buyerUnitPrice < 0L) {
      return 0L;
    }
    // ★ 2026-10-09：route 非 null 且 immediate = 区内跨格（有商号承运，货款/运费当日结清、没有 ShipmentBatch）；
    //   route 非 null 且 !immediate = 跨区在途；route == null = 同 hex 即时（零运费）。
    boolean inTransit = route != null && !route.immediate();
    HexCoord location = inTransit ? route.from() : sell.hex;

    // ── ① 承运选择（跨格才需要）：容量不足时把成交收缩到实际可承运量 ─────────────────────────
    long executed = quantity;
    List<FreightCharge> freightCharges = new ArrayList<>();
    long freight = 0L;
    long uncollectedFreight = 0L;
    // ★★ M-C：本笔的承运分配（route != null 才有）—— 工具消耗 / 劳动成本 / 免运费读数的唯一凭据。
    MerchantCapacityPool.CarrierAllocation allocation = null;
    // ★★ P-T5b：本笔的**单位运费（毫买方支付币 / 商品单位）**—— 全方法（以及 Fill 的读数）共用这一个折算值。
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit());
    if (route != null) {
      // ★★★ D-032 C7b2：活动运输计划路径不得回退旧 CARRIER_FEE/旧生产性运力；跨 hex route 必须已由
      //   plan 的本地容量判为 service 路径。否则具名契约故障（fail-closed，不成交、不动账）。
      if (ctx.transportRoundPlan != null && !route.haulService()) {
        throw new IllegalStateException(
            "活动运输计划路径出现旧 CARRIER_FEE route（契约故障）: day="
                + round.day
                + " from="
                + route.from()
                + " to="
                + route.to()
                + " commodity="
                + route.commodity().value());
      }
      // ★★ P-T5b：单位运费从**本格计价币**折成**买方支付币**（运费腿铸在 buy.currency 上，见 P-T4 的
      //   Fill.freightCurrency ≡ paymentCurrency）—— 与 chargesAtMost 走同一个 payAmountOf（唯一拼写点）
      //   ⇒ "判得起"与"真的扣"不可能漂开。
      //   ★ 说不出这种钱的价（-1）⇒ 本笔不成交（不铸腿、不动账）：绝不把本格计价币的运费当支付币的运费。
      if (unitFreight < 0L) {
        logFreightCurrencyUnvalued(ctx, buy, sell, route);
        return 0L;
      }
      // ★★ M-A2：本 lane 的**运力需求口径**（数量 × 距离，沿用既有运费算式；承运成本项取 0）——报价口径下
      //   按它扣提供者的运力预算，也按它把"该家户这一份需求要多少运力"记进本轮需求簿（K-A/K-B/K-5）。
      //   缺省口径（无报价）⇒ 1:1（M-A1 的毫商品口径）⇒ 逐值不变。
      long workPerGoodPerMille =
          ctx.carrierPool.workPerGoodPerMilleOf(
              commodityFreightBaseMilli(ctx.topology, route.commodity()),
              route.freightRatePerMille());
      // ★★★ D-032 C7b2：活动运输计划路径先按本地 lane 的**承诺 work** 封顶成交数量；
      //   没有该 lane/承诺为 0/不足一个整商品 ⇒ 不成交、不建任何债、不计价。plan 没有该 lane 同样 fail-closed。
      io.mosire.simos.economy.model.TransportDemandKey transportLaneKey = null;
      if (ctx.transportRoundPlan != null) {
        transportLaneKey = ctx.transportDemandKeyFor(route.from(), route.to(), route.commodity());
        Long laneRemainingWork = ctx.transportLaneRemainingWork(transportLaneKey);
        long maxGoodsByLane =
            laneRemainingWork == null || laneRemainingWork <= 0L
                ? 0L
                : CapacityDemand.maxGoodsFor(laneRemainingWork, workPerGoodPerMille);
        if (maxGoodsByLane <= 0L) {
          ctx.capacityDemands.record(
              buy.buyer.household(),
              buy.hex,
              buy.regionId,
              route.commodity(),
              route.from(),
              quantity,
              0L,
              workPerGoodPerMille);
          markCapacityBlocked(
              ctx,
              buy,
              sell,
              route.from(),
              route.to(),
              route.commodity(),
              quantity,
              MerchantCapacityPool.LogisticsBlock.CAPACITY_EXHAUSTED.detail());
          EventLog.channel(MARKET)
              .info(
                  LogEvent.of(
                      "TRANSPORT_LANE_REJECTED",
                      EconomyLogSource.ECONOMY_ORGANIZATION,
                      "day",
                      ctx.round.day,
                      "commodity",
                      route.commodity().value(),
                      "fromHex",
                      route.from(),
                      "toHex",
                      route.to(),
                      "buyer",
                      buy.buyer.actor().id(),
                      "requestedMilli",
                      quantity,
                      "laneRemainingWorkMilli",
                      laneRemainingWork == null ? Long.valueOf(0L) : laneRemainingWork,
                      "workPerGoodPerMille",
                      workPerGoodPerMille,
                      "reason",
                      MarketUnfilledReason.LOGISTICS_CAPACITY.name()));
          return 0L;
        }
        if (maxGoodsByLane < quantity) {
          quantity = maxGoodsByLane;
          buyerUnitPrice = settlementUnitPrice(ctx, buy, sell, quantity, unitPrice, LEG_CASH, true);
          if (buyerUnitPrice < 0L) {
            return 0L;
          }
        }
      }
      // ★★ M-A1/M-A2/M-C：跨格承运的唯一判据 —— 按**发货格**的逐 hex 运力池分配（报价口径 = 从最低限价起买；
      //   缺省口径 = 提供方市场议价权序）；分配多少才走多少，一点运力都没有 ⇒ 本笔不成交（绝不发"免费"的跨格货）。
      //   ★ M-C：**缺工具 ⇒ 该次跑商不成立**（H-5）也落在 select 里（具名 tool-short），因此"分不到"同样收缩成交。
      //   CARRIER_FEE 收款人 = 提供运力的**家户**（纯商号免运费，见 carrierChargeSplit）。
      // ★★ 2026-10-10（池侧读数去重）：把**请求身份**一并交给池 —— 它与 D-1b 槽侧认领（{@link LaneUnservedBook#claimPair}）
      //   **同键同拼写**（车道键 + 买槽序 > 卖槽序）。★ 它只被池侧 {@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的净额字段用：
      //   本笔的承运分配、成交量、价格、账目一字不动（池那边也只写它自己的观察簿，不碰任何判据）。
      String requestKey =
          LaneUnservedBook.pairKey(
              LaneUnservedBook.laneKey(route.from(), route.to(), route.commodity()),
              buy.orderIndex,
              sell.orderIndex);
      allocation =
          ctx.carrierPool.select(
              route.from(), route.to(), quantity, workPerGoodPerMille, requestKey);
      long allocated = allocation.allocatedMilli();
      if (allocated <= 0L) {
        // ★★ M-A2：全被拦下的那部分同样是"这一份需求要运力但没买到" ⇒ 记进需求簿（供 K-4 的缺口归因）。
        ctx.capacityDemands.record(
            buy.buyer.household(),
            buy.hex,
            buy.regionId,
            route.commodity(),
            route.from(),
            quantity,
            0L,
            workPerGoodPerMille);
        // ★★ F-2（2026-10-10）：业务拒绝（"没有服务卖" / "缺提供商"）⇒ INFO 具名行（§一.9）；
        //   运行期三支（半径/亚单位/耗尽）只进池侧 DEBUG/TRACE 读数。★ 无牌价 ⇒ 归因退回"运力不足"。
        String blockedDetail = logisticsBlockDetail(ctx, route.from(), allocation);
        markCapacityBlocked(
            ctx, buy, sell, route.from(), route.to(), route.commodity(), quantity, blockedDetail);
        logLogisticsBusinessRejection(ctx, buy, route, allocation, blockedDetail, quantity);
        return 0L;
      }
      executed = Math.min(quantity, allocated);
      if (transportLaneKey != null) {
        long allocatedWork = 0L;
        for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
          allocatedWork = Math.addExact(allocatedWork, choice.consumedWorkMilli());
        }
        ctx.consumeTransportLaneWork(transportLaneKey, allocatedWork);
      }
      ctx.capacityDemands.record(
          buy.buyer.household(),
          buy.hex,
          buy.regionId,
          route.commodity(),
          route.from(),
          quantity,
          executed,
          workPerGoodPerMille);
      if (executed < quantity) {
        String blockedDetail = logisticsBlockDetail(ctx, route.from(), allocation);
        markCapacityBlocked(
            ctx,
            buy,
            sell,
            route.from(),
            route.to(),
            route.commodity(),
            quantity - executed,
            blockedDetail);
        logLogisticsBusinessRejection(
            ctx, buy, route, allocation, blockedDetail, quantity - executed);
        // 应收而未收的名义运费：被运力截断的那部分没有收款人，读数具名、不静默变 0。
        uncollectedFreight = freightOf(quantity - executed, unitFreight);
      }
      freightCharges =
          carrierChargeSplit(
              allocation,
              buy,
              route,
              commodityFreightBaseMilli(ctx.topology, route.commodity()),
              // ★★ M-C：货主是不是纯商号（H-A/H-G 的另一半：自运自货）—— 判据的唯一拼写点在 MerchantIdentity。
              ctx.pureMerchantHouseholds.contains(buy.buyer.household()));
      for (FreightCharge charge : freightCharges) {
        freight = Math.addExact(freight, charge.amountMilli());
      }
      // ★★ A2：服务成市 ⇒ **先交付服务、再动任何账**（I-H2：卖出多少服务就得有多少货；买不到 ⇒ 该笔不成交）。
      //   ★ 位置：decisive —— 它必须早于货腿/钱腿（下面 ②③ 步）；被 fail-closed 挡下时本方法直接返回 0，
      //     库里一个字节都还没动（冻结/成交/在途都在后面），因此"本笔不成交"是完整的。
      if (route.haulService()) {
        long delivered = deliverHaulService(ctx, route, allocation);
        if (delivered < 0L) {
          ctx.haulServiceDeliveryFaults++;
          return 0L; // 具名 ERROR 已在交付方法里发过；本笔不成交（不发货、不铸腿、不动账）
        }
      }
    }

    long payment =
        ceilDiv(Math.multiplyExact(executed, buyerUnitPrice), EconomySettlement.MILLI_PER_GRAIN);
    if (!buy.currency.equals(sell.market.numeraire()) && payment > 0L) {
      logForeignCurrencyFill(
          ctx, buy, sell, commodity, executed, unitPrice, buyerUnitPrice, payment);
    }
    // ★★ P-T1b：三层税（出口税 / 进口税 / 区内市场税）—— **唯一**的计税点（可负担预判走同一个方法 ⇒
    //   两处不可能漂开）。税基 = 本笔货款（只对货值，运费不计）；买方多付，卖方仍收原价（下面 payment 腿一字不改）。
    List<MarketTaxBook.Charge> taxCharges =
        taxesFor(ctx, buy, sell, commodity, executed, buyerUnitPrice, inTransit, true);
    long taxTotal = 0L;
    for (MarketTaxBook.Charge charge : taxCharges) {
      taxTotal = Math.addExact(taxTotal, charge.amountMilli());
    }
    // ★★★ A3（2026-10-10）：这里原有三笔**平行利润读数**的记账（差价收入两腿 + 三层税腿 → MerchantProfitBook）——
    //   {@code MerchantProfitBook} 已整族退役（T-H3）：收益走**标准企业利润** {@code EnterpriseProfitBook}，
    //   它直接读同一批 {@code ProductionLedger} 的转移腿（货款 = MARKET_TRADE 钱腿、税 = 既有税腿），
    //   不在这里另记一份"影子账"（影子账会与账本漂开，且不属于任何组织的真实利润）。
    // ★★ D-027：单 hex 贸易成本只在**同一市场区**的区内即时成交上逐笔计量（跨区在途走 route.lossPerMille，
    //   口径不变）。第一版只表达为实物损耗：同格 = 0、跨格 = HexTradeCost 的具名公式并夹在 quantity 内。
    long lossMilli =
        !inTransit && !sell.hex.equals(buy.hex)
            ? Math.min(
                executed,
                Math.multiplyExact(
                        executed, ctx.hexTradeCost.lossPerMilleBetween(sell.hex, buy.hex))
                    / 1000L)
            : 0L;
    // ★★★ A3：本笔损耗的价值折算（原"利润读数损耗腿"）已随 {@code MerchantProfitBook} 退役 —— 实物损耗本身照旧
    //   记进 {@code ProductionLedger.losses}（{@code EnterpriseProfitBook} 按 industry 分摊读它），不另记影子账。

    // ② 卖方把已冻结的那一份放出来，再走唯一 applier（货腿：卖方 → 买方）。
    long sellRelease = Math.min(executed, sell.frozenRemaining);
    sell.frozenRemaining -= sellRelease;
    releaseSellFrozenSum(ctx, sell, sellRelease);
    refreshSellFrozen(ctx, sell);
    Transfer goodsLeg =
        round.ledger.mint(
            sell.seller.actor(),
            buy.buyer.actor(),
            location,
            Map.of(commodity, executed),
            Map.of(),
            TransferReason.MARKET_TRADE);
    EconomySettlement.applyTransfer(
        round.householdGoods,
        round.householdMoney,
        round.householdFrozenGoods,
        round.householdFrozenMoney,
        round.householdOfActor,
        goodsLeg);

    // ★★ D-027 守恒实现口径（唯一写口 + 非换手落点，spec §3.2）：上面那条货腿是**毛量** sell → buy；
    //   跨格即时成交的损耗在这里由**买方侧**做一次「货物离开账户但未换手」的扣减（与下面 loadInTransit 把
    //   货物移进在途批次同属非换手落点，区别只是在途日后会反向落回、损耗不再回来）。于是卖方毛量出、
    //   买方净量入，ledger.losses[TRANSPORT_LOSS_ACCOUNT] 是这条损耗的唯一凭据 ⇒ Σ余额 + losses 守恒。
    //   ★ 不能把货腿写成 quantity - lossMilli 再记一笔 loss：那等于卖方少出的那一份里省下损耗、ledger 又
    //     凭空多记一份，实测 Σ余额 + losses = 初始 + loss。
    //   ★ 买方未满足需求/回冲仍按净量 quantity - lossMilli（见下面 route == null 分支），不随货腿改回毛量。
    //   ★ 本块只在 route == null && sell.hex != buy.hex 且 lossMilli > 0 时执行（跨区在途的 lossMilli 恒 0），
    //     不触碰 loadInTransit / shipment / 到货损耗 / 承运费。损耗不产生货币运费/CARRIER_FEE
    //     （第一版单区内 costMilliPerUnit 恒 0），Fill.lossMilli 仍只表达这笔实物损耗读数。
    if (lossMilli > 0L) {
      deductBuyerLossNoTransfer(ctx, buy, lossMilli);
      round.ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, commodity, lossMilli);
    }

    if (inTransit) {
      // ★ 装载在途：货权已归买方（上面那条货腿），但货**不在目的地的余额里** —— 把它从买方的会话余额移进
      //   ShipmentBatch（到货日再反向落回）。这不是第二次换手，是在途资产的唯一落点。
      loadInTransit(ctx, buy, executed);
    }

    // ③ 买方把冻结的货款（+运费 +税）放出来，再货款 → 卖方、运费 → 承运人、三层税 → 各政府国库。
    //   ★★ P-T1b：`total` 含税 ⇒ 冻结的释放量与现金腿判据（{@link #chargesAtMost}）同口径；**卖方那一腿
    //     （payment）一个字不改** ⇒ "买方多付、卖方仍收原价" 是结构性的，不是两处对齐出来的。
    // ★★★ W2（2026-10-11）：**现金先付、不足的部分全额记为债务**（用户 0–8 第 6–7 条）：
    //   cash       = 本单此刻能动用的现金（钱包 − 冻结，再受订单预算余额封顶）
    //   charges    = 运费 + 三层税（收款人是承运家户与政府国库户，**不走债务通道**，见 chargesAtMost）
    //   paidCash   = min(货款, max(0, cash − charges))        ← 货款优先吃剩下的现金
    //   deferred   = 货款 − paidCash                          ← ★ 记为对该**卖方**的债务（同一本 debtContracts）
    //   ★ pairUp 已按 {@link #exactChargeAffordableUpTo} 保证 charges ≤ cash ⇒ 下面不会把账扣成负。
    long cashAvailable = payableMoneyOf(ctx, buy);
    if (taxTotal > cashAvailable) {
      throw new IllegalStateException(
          "税费现金腿超过可付额（pairUp 与 executeTrade 的现金判据漂开）: day="
              + round.day
              + " commodity="
              + commodity.value()
              + " taxMilli="
              + taxTotal
              + " cashAvailableMilli="
              + cashAvailable);
    }
    // ★★★ D-032 C7b2：活动运输计划路径允许**运费现金不足部分成为普通货币债**（债权人 = 实际承运户）。
    //   货款的现金优先级不变：先税、再运费、最后货款；未付货款仍记卖方债。旧路径没有 plan ⇒ 若出现未付运费
    //   即契约故障（pairUp 结构上应已封顶），绝不把旧 CARRIER_FEE 路径静默改成赊账。
    long freightCashBudget = Math.max(0L, cashAvailable - taxTotal);
    long paidFreight = 0L;
    long transportFreightDeferred = 0L;
    List<FreightCharge> settledFreightCharges = new ArrayList<>();
    for (FreightCharge charge : freightCharges) {
      long paid = Math.min(charge.amountMilli(), freightCashBudget);
      freightCashBudget -= paid;
      paidFreight = Math.addExact(paidFreight, paid);
      if (paid > 0L) {
        settledFreightCharges.add(
            new FreightCharge(
                charge.carrierActor(),
                paid,
                charge.serviceMilli(),
                charge.household(),
                charge.hex(),
                charge.pureMerchant()));
      }
      long unpaid = charge.amountMilli() - paid;
      if (unpaid > 0L) {
        if (ctx.transportRoundPlan == null) {
          throw new IllegalStateException(
              "旧 CARRIER_FEE 路径出现未付运费（契约故障）: day="
                  + round.day
                  + " commodity="
                  + commodity.value()
                  + " carrier="
                  + charge.household()
                  + " unpaidMilli="
                  + unpaid);
        }
        transportFreightDeferred = Math.addExact(transportFreightDeferred, unpaid);
        if (!ctx.recordFillIntents) {
          recordDeferredFreightDebt(
              ctx, buy, charge.household(), unpaid, charge.serviceMilli(), commodity.value());
        }
      }
    }
    freight = paidFreight;
    freightCharges = List.copyOf(settledFreightCharges);
    long paidCash = Math.min(payment, freightCashBudget);
    long deferred = payment - paidCash;
    if (deferred > 0L && !deferralPossible(ctx, buy, sell)) {
      // ★ fail-closed 契约断言：本笔没有赊购能力（没有债务账 / 任一端不是家户 / 国库户授权买盘）时，
      //   {@code pairUp} 走的是旧口径 {@code exactTotalCostAffordableUpTo} ⇒ 未付价款**结构性**为 0。
      //   走到这里说明两道判据漂开了 —— 具名抛，绝不铸一笔没有地方记账的欠款（也不静默少付卖方）。
      throw new IllegalStateException(
          "无赊购能力的成交出现了未付价款（pairUp 的现金封顶与 executeTrade 的拆分漂开）: day="
              + round.day
              + " commodity="
              + commodity.value()
              + " buyer="
              + buy.buyer.actor()
              + " seller="
              + sell.seller.actor()
              + " paymentMilli="
              + payment
              + " paidCashMilli="
              + paidCash
              + " deferredMilli="
              + deferred);
    }
    long total = Math.addExact(Math.addExact(paidCash, freight), taxTotal);
    if (transportFreightDeferred > 0L && ctx.recordFillIntents) {
      // worker 只产意向；真实运费债由协调器回放时落（serial plan 路径通常不会到 worker）。
      ctx.transportFreightDeferredMilli =
          Math.addExact(ctx.transportFreightDeferredMilli, transportFreightDeferred);
    }
    // ★★★ A3：本钱占用腿（微毫）已随 {@code MerchantProfitBook} 退役（并行利润读数，T-H3）。
    long buyRelease = Math.min(total, buy.frozenRemaining);
    buy.frozenRemaining -= buyRelease;
    releaseBuyFrozenSum(ctx, buy, buyRelease);
    refreshBuyFrozen(ctx, buy);
    if (paidCash > 0L) {
      Transfer moneyLeg =
          round.ledger.mint(
              buy.buyer.actor(),
              sell.seller.actor(),
              location,
              Map.of(),
              Map.of(buy.currency, paidCash),
              TransferReason.MARKET_TRADE);
      EconomySettlement.applyTransfer(
          round.householdGoods,
          round.householdMoney,
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          moneyLeg);
    }
    // ★★★ W2 并行内核纪律：**worker 副本只产意向、不写共享状态** —— 本区的区内成交由 R2 的 worker 在
    //   账户副本上先算一遍（{@code ctx.recordFillIntents = true}），协调器随后按区序用**同一条**
    //   {@code executeTrade} 回放（{@link #replayRegionOutcome}）。{@code ctx.debts} 是**共享**的债务工作副本
    //   ⇒ 若在 worker 里也 upsert，同一笔赊购会被记两次（且构成数据竞争）。故台账只在回放侧落。
    if (deferred > 0L && !ctx.recordFillIntents) {
      recordDeferredPaymentDebt(ctx, buy, sell, commodity, executed, payment, paidCash, deferred);
    }
    // ★ P11.3：逐条实际承运条目分别铸运费腿；freightPaidByCurrency 只累加真实铸出的金额（Σ = 实际可收运费，
    //   自承运条目已在 carrierChargeSplit 里剔除，因此不会出现"买方 → 买方"的自转移）。
    // ★★ A2（I-H5 不双重记账）：**服务成市的 lane 上 CARRIER_FEE 腿停铸** —— 同一笔运费只走"运输服务成交"这一条：
    //   服务货已在 {@link #deliverHaulService} 里从卖方消耗掉（账户减 + 损耗账加），这里铸的是它的**钱腿**，
    //   理由码取既有的 {@code MARKET_TRADE}（= 服务商品的成交腿），不再是 {@code CARRIER_FEE} 那条私有腿。
    //   ★ 两条腿**结构上互斥**（同一个三元表达式选一个理由码，一条 lane 只走一条）⇒ 不可能双记。
    //   ★ 读数面（freightPaidByCurrency / freightUncollectedByCurrency / 利润读数的运费两腿）**原样保留**为只读，
    //     所以"改前能核的账"改后仍能核（设计书 §8 Q-A3 的默认：CARRIER_FEE 降为只读读数）。
    //   ★ 不成市的 lane（缺省世界）⇒ 理由码仍是 CARRIER_FEE、判据与金额一字未改 ⇒ 逐值退回改前（I-H3 第一条腿）。
    for (FreightCharge charge : freightCharges) {
      Transfer freightLeg =
          round.ledger.mint(
              buy.buyer.actor(),
              charge.carrierActor(),
              route.to(),
              Map.of(),
              Map.of(buy.currency, charge.amountMilli()),
              MarketSettlement.freightTransferReason(route.haulService()));
      EconomySettlement.applyTransfer(
          round.householdGoods,
          round.householdMoney,
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          freightLeg);
      //   ★★ P-T4：逐条按**它的币**记账（运费腿就铸在 buy.currency 上）—— 禁跨币相加。
      ctx.freightPaidByCurrency.merge(buy.currency, charge.amountMilli(), Math::addExact);
      // ★★ M-A1：提供者侧的运费实收读数（每轮算、不落状态；冻结项 4「收款方 = 提供运力的家户」的可核证据）。
      ctx.carrierPool.recordFee(charge.household(), buy.currency, charge.amountMilli());
      // ★★ M-C：利润读数的两条运费腿 —— 买方**运费支出**（逐币）与承运方**运费收入**（逐币；只对顺便跑商计入利润）。
      // ★★★ A3：运费的两条**平行利润腿**（买方支出 / 承运方收入 → MerchantProfitBook）已退役 ——
      //   服务成交的钱腿 reason = MARKET_TRADE（见下面那一行的三元表达式）⇒ 承运方的收入逐笔进
      //   {@code EnterpriseProfitBook} 的 revenue（按卖方组织归集），无需第二本账。
      if (route.haulService()) {
        // ★★ A2：服务成交的轮级读数（只作日志/读数 —— 与上面的运费读数**同源同额**，不另记一份事实）。
        //   服务量本身在 {@link #deliverHaulService} 里累加（它覆盖全部条目，含免运费条目）。
        //   ★★ A5：金额不再另记一份（逐币总额由上面 recordFee 的**逐户**实收按币求和得到，见 ctx 字段注）；
        //     这里只累加"铸了几条钱腿" —— 它与交付量是两个量，交付量在 deliverHaulService 里累加。
        ctx.haulServiceTrades++;
      }
      // ★★ M-A1（§一.9：TRACE = 逐笔运费）：付款人 → 提供运力的家户、金额、币种、lane。
      //   ★★ A2：服务成市时事件名换成运输服务成交（同一条钱腿、同一个付款人/收款人；只是它现在表达的是"买了多少服务"）。
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    MarketSettlement.freightTransferEventName(route.haulService()),
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "commodity",
                    commodity.value(),
                    "from",
                    buy.buyer.actor().id(),
                    "toHousehold",
                    charge.household().value(),
                    "carrierHex",
                    charge.hex(),
                    "fromHex",
                    route.from(),
                    "toHex",
                    route.to(),
                    "amountMilli",
                    charge.amountMilli(),
                    "currency",
                    buy.currency.value(),
                    // ★★ A2：这一条腿买到的**服务量**（毫服务 = 毫商品·程）与它的单价（毫钱/商品单位）——
                    //   "数量 × 单位运费 ≈ 金额"因此逐笔可核（差额只来自两端各自的向上取整）。
                    "serviceMilli",
                    charge.serviceMilli(),
                    "unitFreightMilli",
                    route.freightPerUnit(),
                    "servicePriceMilli",
                    route.servicePriceMilli()));
      }
    }
    //   ★★ P-T4：未收运费同样按币分列（键 = 本笔买方的支付币：名义运费就按这种钱的量纲算出来）。
    //   ★ 0 不落键（"没有未收"与"未收 0"分得开；否则每笔成交都会给它的币插一条 0）。
    if (uncollectedFreight > 0L) {
      ctx.freightUncollectedByCurrency.merge(buy.currency, uncollectedFreight, Math::addExact);
    }
    // ★★ M-C：把本笔**每一条跑商**落地 —— ① 一次性烧掉工具（H-1：从商品账扣 + 记损耗账）② 记劳动成本、
    //   工具消耗、（纯商号条目的）被免运费与逐条 TRACE（H-A/H-F 的可见证据）。**只读 + 商品账扣减**：
    //   它不铸钱腿、不改撮合判据，守恒式仍由"账户减 + 损耗加"两条腿守住。
    if (allocation != null) {
      settleHaulRuns(
          ctx, buy, route, allocation, ctx.pureMerchantHouseholds.contains(buy.buyer.household()));
    }
    // ★★ P-T1b：三层税的**钱腿**（逐层逐收款政府一条；钱铸在买方支付币上）—— 唯一写口 {@code applyTransfer}。
    //   ★ 层的顺序 = 调用方给的规范序（出口 → 进口 / 区内一条）⇒ 逐值可复现（I7）；0 额条目根本不在表里。
    //   ★ 买方 = 国库本身时那一份已在 {@link MarketTaxBook#assess} 里剔除（自转移不是发生额）。
    for (MarketTaxBook.Charge charge : taxCharges) {
      Transfer taxLeg =
          round.ledger.mint(
              buy.buyer.actor(),
              charge.treasury(),
              location,
              Map.of(),
              Map.of(charge.currency(), charge.amountMilli()),
              taxReasonOf(charge.layer()));
      EconomySettlement.applyTransfer(
          round.householdGoods,
          round.householdMoney,
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          taxLeg);
      //   ★★ 读口（P-T4 形状 + I-C10）：层 / 金额 / 币种 / 收款政府逐项列出，并按币分列（禁跨币求和）。
      ctx.taxItems.add(
          new MarketReport.TaxItem(
              charge.layer(),
              charge.governmentId(),
              charge.currency(),
              charge.amountMilli(),
              commodity,
              charge.zone()));
      logTaxCharged(ctx, buy, sell, commodity, charge, executed, payment);
    }
    buy.spentMilli += total;
    if (ctx.recordFillIntents) {
      // ★ worker 的区内意向：全局槽位下标 + 唯一标识（区/商品/买卖方 canonical 串），协调器按区序回放。
      ctx.fillIntents.add(
          new FillIntent(
              buy.orderIndex,
              sell.orderIndex,
              executed,
              unitPrice,
              sell.region.node().nodeId(),
              commodity,
              actorKeyOf(sell.seller.actor()),
              actorKeyOf(buy.buyer.actor())));
    }

    if (!inTransit) {
      // 区内即时（同 hex 零运费 / 同区跨格有商号运费）：买到的**净量**冲减当日未满足需求（封顶 = 已记的缺口；只对家户）。
      // ★ D-027：买方按毛量付款、收到毛量 − 损耗 ⇒ 冲减的也是净量（否则缺口会被高估成"买到没损耗"）。
      if (buy.buyer.household() != null) {
        reduceUnmet(round.unmetToday, buy.buyer.household(), commodity, executed - lossMilli);
      }
      ctx.immediateFills++;
      long tariffPerUnit = ctx.tariffPerUnitOf(sell.regionId, commodity);
      MarketReport.Fill fill =
          new MarketReport.Fill(
              sell.hex,
              buy.hex,
              commodity,
              sell.seller.actor(),
              buy.buyer.actor(),
              executed,
              // ★★ P-T4：单价币 = 卖方格计价币；实付币 = 买方支付币（货款与运费两条钱腿都铸在它上面）。
              sell.market.numeraire(),
              unitPrice,
              buy.currency,
              // ★★★ W2：这一栏是**实付货款**（现金腿），不是名义货价 —— 未付的那一份记为债务、不是货币流水
              //   （统计簿的成交额因此只数真钱；债务由 debtContracts 单独记）。
              paidCash,
              // ★★ P-T5b：单位运费按**买方支付币**报（与 freightCurrency ≡ paymentCurrency 同币；换算见上）。
              //   ★★ M-C：**实收为 0 时报 0**（免运费 H-A / 没有可收条目）—— 与下面在途路径的
              //   `reportedFreightPerUnit`（由实收额反解）同一个口径：这一列是"实收运费"的读数，
              //   不是"计划运费"。不改它 ⇒ 免运费条目的 landedUnitPriceMilli 会把没付的运费算进去。
              freight > 0L ? unitFreight : 0L,
              freight,
              round.day,
              true,
              "",
              lossMilli);
      ctx.fills.add(fill);
      if (tariffPerUnit > 0L) {
        // ★★ D-027 的**读数**照旧（毫卖方计价币 / 商品单位 → `MarketReport.regulatedTariffByCurrency()`）；
        //   P-T1b 起同一笔的"真收"另在 {@link #executeTrade} 的税腿处落账（层/金额/币种/收款政府，
        //   见 {@code ctx.taxItems}）—— 两者是同一件事的"费率读数 / 真收账目"两面，读账以 taxItems 为准。
        ctx.tariffByFill.put(fill, tariffPerUnit);
      }
      return executed;
    }

    // ④ 跨区：合并到同 (from,to,commodity,arrival) 的在途批次，并保留逐票损耗归属。
    ShipmentKey key = new ShipmentKey(route.from(), route.to(), commodity, route.arrivalTick());
    ShipmentBuilder batch =
        ctx.shipments.computeIfAbsent(key, ignored -> new ShipmentBuilder(route));
    batch.quantity += executed;
    batch.allocations.add(
        new ShipmentAllocation(
            sell.seller.actor(),
            buy.buyer.actor(),
            buy.order.deliverTo(),
            executed,
            LossBearer.BUYER));
    String shipmentId = batch.shipmentId;
    if (shipmentId == null) {
      shipmentId = "sh-" + round.day + "-" + (ctx.shipmentSequence++);
      batch.shipmentId = shipmentId;
    }
    round.shipments.put(
        new ShipmentId(shipmentId),
        new ShipmentBatch(
            batch.route.toRoute(),
            commodity,
            round.day,
            route.arrivalTick(),
            batch.quantity,
            batch.allocations));
    ctx.scheduledLossMilli += Math.multiplyExact(executed, route.lossPerMille()) / 1000L;
    ctx.crossRegionFills++;
    // 读数里的单位运费按**本票实收**折算（不是 route 的指示费率）：L3 的到货价 = 单价 + 这一栏。
    long reportedFreightPerUnit =
        freight > 0L
            ? ceilDiv(Math.multiplyExact(freight, EconomySettlement.MILLI_PER_GRAIN), executed)
            : 0L;
    ctx.fills.add(
        new MarketReport.Fill(
            route.from(),
            route.to(),
            commodity,
            sell.seller.actor(),
            buy.buyer.actor(),
            executed,
            // ★★ P-T4：单价币 = 卖方格计价币；实付币 = 买方支付币（货款与运费两条钱腿都铸在它上面）。
            sell.market.numeraire(),
            unitPrice,
            buy.currency,
            // ★★★ W2：同区内即时分支 —— 这一栏是**实付货款**（现金腿）；未付部分进债务。
            paidCash,
            reportedFreightPerUnit,
            freight,
            route.arrivalTick(),
            false,
            shipmentId,
            // ★ M2.7：逐票预排损耗与到货日的扣减公式逐字同源（deliverShipments 也是 quantity × lossPerMille ÷ 1000）。
            Math.multiplyExact(executed, route.lossPerMille()) / 1000L));
    return executed;
  }

  /**
   * ★★★ <b>DEF-2：执行时按当刻库存封顶的具名 INFO（本卖单自己的冻结不误当不可卖）。</b>
   *
   * <p>只在 stale 卖单真的被顶到 0 时发：业务拒绝 = INFO（§一.9）。
   */
  private static void logSellStockExhausted(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long requestedQuantity,
      long stock,
      long frozen,
      long ownSellReservation,
      long available) {
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_SELL_STOCK_EXHAUSTED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "seller",
                sell.seller.actor().id(),
                "buyer",
                buy.buyer.actor().id(),
                "hex",
                sell.hex.toString(),
                "requestedMilli",
                requestedQuantity,
                "stockMilli",
                stock,
                "frozenMilli",
                frozen,
                "ownSellReservationMilli",
                ownSellReservation,
                "availableMilli",
                available,
                "reason",
                "stale-sell-intent-has-no-available-stock-at-execution"));
  }

  /**
   * ★★★ <b>DEF-2：执行时按当刻库存把本笔成交收缩后的具名 INFO。</b>
   *
   * <p>未成交部分自然留在槽位剩余里；本方法之后所有价格/运费/税/成交/退还都用 capped quantity。
   */
  private static void logSellStockShrunk(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long requestedQuantity,
      long cappedQuantity,
      long stock,
      long frozen,
      long ownSellReservation,
      long available) {
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_SELL_STOCK_SHRUNK",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "seller",
                sell.seller.actor().id(),
                "buyer",
                buy.buyer.actor().id(),
                "hex",
                sell.hex.toString(),
                "requestedMilli",
                requestedQuantity,
                "cappedMilli",
                cappedQuantity,
                "stockMilli",
                stock,
                "frozenMilli",
                frozen,
                "ownSellReservationMilli",
                ownSellReservation,
                "availableMilli",
                available,
                "reason",
                "stale-sell-intent-capped-at-execution-time"));
  }

  /**
   * ★★★ <b>RA1b2c2：一笔标量土地成交的唯一执行写口</b>（与商品成交同住一个 {@code clearOncePerCycle}）。
   *
   * <pre>
   * ① 前置复核：买卖双方解析到已登记活家户且非同一户；卖方在该 hex 有可转移标量持有
   * ② 成交量 = min(请求上限, 卖方当前持有)；现金不足且不能赊购时再收缩到现金可支付的最大数量
   * ③ 价款 = floor(quantityMilliMu × unitPricePerMu ÷ 1000)（与 P2e 抵债估值同一个算式；0 价 ⇒ 0）
   * ④ 现金腿：min(价款, spendableMoneyOf(买方, 支付币)) —— 后者已扣掉本轮**商品**冻结/成交 ⇒ 商品 + 土地共用同一份预算，不双付；
   *    现金腿走既有唯一写口 {@code AccountTransferSettlement.applyTransfer}
   * ⑤ 赊购腿（仅当现金不足）：买卖双方都是家户且有普通货币债通道 ⇒ 复用既有 {@code DebtContractBook.upsert} +
   *    {@link MarketFreezeBook#DEFERRED_PAYMENT_TERMS}（与商品未付价款记债同一本债账、同一条条款）；否则成交量已收缩到现金可付，
   *    绝不静默免单/伪造成交
   * ⑥ 产权腿：只走 {@link io.mosire.simos.economy.land.LandLedgerState#transfer}（内部 {@code LandBook.apply} 唯一守恒校验）——
   *    不进入 HouseholdGoods、不生成 Pledge、无运费、不征土地税
   * ⑦ 标记：{@code round.markLandAcquired((hex, buyer), quantity)}（M3/M6/M7 按数量排除本轮新取得）
   * </pre>
   *
   * @return 实际成交数量（&gt; 0）；{@code 0} = 未成交（账户与产权一字未动，具名原因已发 INFO）
   */
  static long executeLandTrade(
      MatchContext ctx,
      LandOrder buy,
      LandOrder sell,
      HexCoord hex,
      long requestedQuantity,
      long unitPricePerMu) {
    MarketRound round = ctx.round;
    if (requestedQuantity <= 0L) {
      return 0L;
    }
    if (ctx.landLedger == null) {
      throw new IllegalStateException(
          "land trade requires a scalar LandLedgerState in MatchContext (contract fault)");
    }
    if (!buy.hex().equals(hex) || !sell.hex().equals(hex)) {
      throw new IllegalStateException(
          "land trade hex mismatch (contract fault): tradeHex="
              + hex
              + " buyHex="
              + buy.hex()
              + " sellHex="
              + sell.hex());
    }
    HouseholdId buyer = round.householdOfActor.get(buy.actor());
    HouseholdId seller = round.householdOfActor.get(sell.actor());
    if (buyer == null
        || seller == null
        || !round.householdEconomies.containsKey(buyer)
        || !round.householdEconomies.containsKey(seller)) {
      MarketLandBook.logRejected(
          ctx,
          buy,
          MarketLandBook.LandRejectReason.ACTOR_NOT_HOUSEHOLD,
          "buyerHousehold="
              + (buyer == null ? "absent" : buyer.value())
              + ";sellerHousehold="
              + (seller == null ? "absent" : seller.value()));
      return 0L;
    }
    if (buyer.equals(seller)) {
      MarketLandBook.logRejected(
          ctx,
          buy,
          MarketLandBook.LandRejectReason.SELF_TRADE,
          "same-household-on-both-sides=" + buyer.value());
      return 0L;
    }
    long sellerHolding = ctx.landLedger.holdingOf(hex, seller);
    if (sellerHolding <= 0L) {
      MarketLandBook.logRejected(
          ctx,
          sell,
          MarketLandBook.LandRejectReason.NO_LAND_HOLDING,
          "seller=" + seller.value() + ";hex=" + hex);
      return 0L;
    }
    long quantity = Math.min(requestedQuantity, sellerHolding);
    long payment = MarketLandBook.landValueMilli(quantity, unitPricePerMu);
    long cashAvailable = spendableMoneyOf(round, buyer, buy.payWith());
    boolean deferredPossible =
        ctx.deferredPaymentsEnabled()
            && round.householdMarketRole(buyer)
                != MarketSettlement.HouseholdMarketRole.AUTHORIZATION_ONLY;
    long paidCash;
    long deferred;
    if (deferredPossible) {
      paidCash = Math.min(payment, cashAvailable);
      deferred = payment - paidCash;
    } else {
      long affordable =
          MarketLandBook.cashAffordableQuantity(cashAvailable, unitPricePerMu, quantity);
      if (affordable <= 0L) {
        MarketLandBook.logRejected(
            ctx,
            buy,
            MarketLandBook.LandRejectReason.CASH_ONLY_UNPAYABLE,
            "paymentMilli="
                + payment
                + ";cashAvailableMilli="
                + cashAvailable
                + ";deferralEnabled=false");
        return 0L;
      }
      if (affordable < quantity) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "LAND_TRADE_CASH_LIMITED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "hex",
                    hex.q() + "," + hex.r(),
                    "buyer",
                    buyer.value(),
                    "requestedQuantityMilliMu",
                    quantity,
                    "executedQuantityMilliMu",
                    affordable,
                    "cashAvailableMilli",
                    cashAvailable,
                    "reason",
                    "no-deferral-channel-quantity-shrunk-to-cash-affordable"));
      }
      quantity = affordable;
      payment = MarketLandBook.landValueMilli(quantity, unitPricePerMu);
      paidCash = payment;
      deferred = 0L;
    }
    MarketZoneId zoneId = MarketLandBook.zoneIdOf(ctx, hex);
    if (zoneId == null) { // 不应到达：撮合工作集已要求有区；具名抛而不是静默落一块没有市场的产权
      throw new IllegalStateException("土地成交缺少市场区（撮合工作集与执行漂开）: hex=" + hex);
    }
    // ⑥ 产权腿（先做纯账本校验/转移：失败时现金/债务腿一字未动）。
    ctx.landLedger = ctx.landLedger.transfer(new LandTransfer(hex, seller, buyer, quantity));
    // ④ 现金腿：既有唯一账户写口。
    if (paidCash > 0L) {
      Transfer moneyLeg =
          round.ledger.mint(
              buy.actor(),
              sell.actor(),
              hex,
              Map.of(),
              Map.of(buy.payWith(), paidCash),
              TransferReason.MARKET_TRADE);
      EconomySettlement.applyTransfer(
          round.householdGoods,
          round.householdMoney,
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          moneyLeg);
    }
    // ⑦ 本轮取得标记（按 (hex, buyer) 数量累加；不落盘）。
    round.markLandAcquired(MarketLandBook.acquisitionKey(hex, buyer), quantity);
    // ⑤ 赊购腿：同一本债账、同一条普通货币债条款。
    if (deferred > 0L) {
      DebtUnit.Money unit = new DebtUnit.Money(buy.payWith());
      DebtContract contract =
          DebtContractBook.upsert(
              ctx.debts,
              buyer,
              seller,
              unit,
              DEFERRED_PAYMENT_TERMS,
              DebtSource.ORDINARY_LOAN,
              deferred,
              round.day,
              ctx.deferredPaymentConfig == null
                  ? null
                  : OptionalLong.of(ctx.deferredPaymentConfig.dueCycle()));
      ctx.landDeferredMilli = Math.addExact(ctx.landDeferredMilli, deferred);
      ctx.landDeferredFills++;
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "LAND_TRADE_DEFERRED",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  round.day,
                  "hex",
                  hex.q() + "," + hex.r(),
                  "buyer",
                  buyer.value(),
                  "seller",
                  seller.value(),
                  "currency",
                  buy.payWith().value(),
                  "paymentMilli",
                  payment,
                  "paidCashMilli",
                  paidCash,
                  "deferredMilli",
                  deferred,
                  "contract",
                  contract.id().value(),
                  "outstandingMilli",
                  contract.principal(),
                  "reason",
                  "unpaid-land-price-becomes-ordinary-money-debt-to-seller"));
    }
    MarketLandBook.LandFill fill =
        new MarketLandBook.LandFill(
            round.day,
            zoneId,
            hex,
            buy.actor(),
            sell.actor(),
            quantity,
            unitPricePerMu,
            buy.payWith(),
            payment,
            paidCash,
            deferred);
    ctx.landFills.add(fill);
    // §一.9：INFO = 发生了什么（逐笔成交）；DEBUG = 为什么（预算/现金的判据）；TRACE = 逐笔明细。
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "LAND_TRADE_SETTLED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "market",
                zoneId.value(),
                "hex",
                hex.q() + "," + hex.r(),
                "buyer",
                buyer.value(),
                "seller",
                seller.value(),
                "quantityMilliMu",
                quantity,
                "landPricePerMu",
                unitPricePerMu,
                "currency",
                buy.payWith().value(),
                "paymentMilli",
                payment,
                "paidCashMilli",
                paidCash,
                "deferredMilli",
                deferred,
                "reason",
                "scalar-land-title-transfer-in-the-same-market-round"));
    if (MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "LAND_TRADE_WHY",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "hex",
                  hex.q() + "," + hex.r(),
                  "cashAvailableMilli",
                  cashAvailable,
                  "deferralPossible",
                  deferredPossible,
                  "paidCashMilli",
                  paidCash,
                  "deferredMilli",
                  deferred,
                  "priceTaker",
                  buy.limitPricePerMu().isEmpty(),
                  "why",
                  "commodity-orders-were-frozen-and-settled-first-land-only-uses-the-remaining-spendable-budget"));
    }
    if (EconomyLog.trace().isTraceEnabled()) {
      EventLog.channel(EconomyLog.trace())
          .trace(
              LogEvent.of(
                  "LAND_TRADE_LEG",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "hex",
                  hex.q() + "," + hex.r(),
                  "fromOwner",
                  sell.actor().id(),
                  "toOwner",
                  buy.actor().id(),
                  "quantityMilliMu",
                  quantity,
                  "unitPricePerMu",
                  unitPricePerMu,
                  "paymentMilli",
                  payment,
                  "paidCashMilli",
                  paidCash,
                  "deferredMilli",
                  deferred,
                  "ownerOperatorChangedOnly",
                  true,
                  "goodsLeg",
                  false));
    }
    return quantity;
  }

  /**
   * ★★★ <b>D-032 C7b2：运费未付部分记普通货币债</b>（0‰，债权人 = 实际承运家户）。
   *
   * <p>只在活动运输计划路径、且该 carrier 的运费腿真的有一部分未付时调用；无本地容量时整笔不成交，不会到这里。 不得记货债、不得把买方余额扣成负、不得把未付运费算进 {@code
   * freightPaidByCurrency}。
   */
  private static void recordDeferredFreightDebt(
      MatchContext ctx,
      BuySlot buy,
      HouseholdId carrier,
      long amountMilli,
      long serviceWorkMilli,
      String commodityValue) {
    if (amountMilli <= 0L) {
      return;
    }
    HouseholdId debtor = buy.buyer.household();
    if (debtor == null || carrier == null || ctx.debts == null) {
      throw new IllegalStateException(
          "运输运费欠款无法记账（需要双方家户与债务工作副本）: day="
              + ctx.round.day
              + " commodity="
              + commodityValue
              + " debtor="
              + debtor
              + " carrier="
              + carrier
              + " debtsPresent="
              + (ctx.debts != null)
              + " unpaidFreightMilli="
              + amountMilli);
    }
    DebtUnit.Money unit = new DebtUnit.Money(buy.currency);
    DebtContract contract =
        DebtContractBook.upsert(
            ctx.debts,
            debtor,
            carrier,
            unit,
            DebtTerms.ordinaryZeroInterestDefault(),
            DebtSource.ORDINARY_LOAN,
            amountMilli,
            ctx.round.day,
            ctx.deferredPaymentConfig == null
                ? null
                : OptionalLong.of(ctx.deferredPaymentConfig.dueCycle()));
    ctx.transportFreightDeferredMilli =
        Math.addExact(ctx.transportFreightDeferredMilli, amountMilli);
    ctx.transportFreightDeferredFills++;
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "TRANSPORT_FREIGHT_DEBT",
                EconomyLogSource.ECONOMY_DEBT,
                "day",
                ctx.round.day,
                "debtor",
                debtor.value(),
                "carrier",
                carrier.value(),
                "currency",
                buy.currency.value(),
                "unpaidFreightMilli",
                amountMilli,
                "serviceWorkMilli",
                serviceWorkMilli,
                "commodity",
                commodityValue,
                "contract",
                contract.id().value(),
                "outstandingMilli",
                contract.principal(),
                "reason",
                "unpaid-transport-freight-becomes-ordinary-zero-interest-money-debt"));
  }

  /**
   * ★★★ <b>W2（2026-10-11）：把一笔成交的**未付价款全额记为债务**</b> —— 债务式清算的唯一写口。
   *
   * <pre>
   * 债务人 = 买方家户（{@code buy.buyer.household}）
   * 债权人 = 卖方家户（{@code sell.seller.household}）
   * 单位   = 买方支付币（{@code DebtUnit.Money(buy.currency)}）—— 与现金腿同一种钱，不做任何折算
   * 本金   = 未付价款（{@code payment − paidCash}）
   * 条款   = {@link #DEFERRED_PAYMENT_TERMS}（出厂借款利率、legacy 维；货币腿的借款合同另用逐轮清算利率）
   * 落点   = {@code ctx.debts}（= {@code EconomyData.debtContracts} 的当日工作副本）—— ★ <b>同一本债账</b>：
   *          约束设计书 §4.1 的 D-023 §3「不做分别核算、合同只有一个未偿额」；不存在第二本债务表。
   * </pre>
   *
   * <p>★★ <b>为什么不另立条款</b>：合同身份 = {@code (债务人, 债权人, 单位, 条款)}（{@code DebtContractId.idOf}）—— 用同一份条款 ⇒
   * 同一对家户之间的"赊购"与"借款"合并成**一个未偿额**，正是 D-023 §3 要的形状。若另立一份 条款，同一对债权人就会同时挂两条合同，读者要自己相加才知道"这家户欠那家户多少"。
   *
   * <p>★★ <b>只有家户能进这本账</b>：{@code DebtContract} 的两端是 {@link HouseholdId} ⇒ 经营者/单位户的买盘 结构上进不了这里（W2
   * 的债务式清算因此只覆盖家户买方，见账本"偏离记录"）。
   *
   * <p>★ <b>与"卖家可借商品"（D-030 §1–§3）的关系</b>：那条通道一字未改（{@code creditForBuy} 仍在现金撮合之后跑）；
   * 本方法是<b>卖方直接给予的赊账</b>（无需任何放贷人），两者写进同一本账、合成同一类合同。
   */
  static void recordDeferredPaymentDebt(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long executed,
      long payment,
      long paidCash,
      long deferred) {
    HouseholdId debtor = buy.buyer.household();
    HouseholdId creditor = sell.seller.household();
    if (debtor == null || creditor == null || ctx.debts == null) {
      // ★ 契约故障（不是"跳过"）：走到这里说明现金-债务拆分的上游判据与债务账的存在性漂开了。
      //   静默把未付价款吞掉 = 凭空免债（与"静默付 0"同族的失败），故具名抛。
      throw new IllegalStateException(
          "未付价款无法记账（债务式清算要求买卖双方都是家户且债务工作副本在场）: day="
              + ctx.round.day
              + " commodity="
              + commodity.value()
              + " debtor="
              + debtor
              + " creditorHousehold="
              + creditor
              + " debtsPresent="
              + (ctx.debts != null)
              + " deferredMilli="
              + deferred);
    }
    DebtUnit.Money unit = new DebtUnit.Money(buy.currency);
    DebtContract contract =
        DebtContractBook.upsert(
            ctx.debts,
            debtor,
            creditor,
            unit,
            DEFERRED_PAYMENT_TERMS,
            DebtSource.ORDINARY_LOAN,
            deferred,
            ctx.round.day,
            ctx.deferredPaymentConfig == null
                ? null
                : OptionalLong.of(ctx.deferredPaymentConfig.dueCycle()));
    ctx.deferredPaymentMilli = Math.addExact(ctx.deferredPaymentMilli, deferred);
    ctx.deferredPaymentFills++;
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_DEFERRED_PAYMENT",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "debtor",
                debtor.value(),
                "creditor",
                creditor.value(),
                "currency",
                buy.currency.value(),
                "quantityMilli",
                executed,
                "paymentMilli",
                payment,
                "paidCashMilli",
                paidCash,
                "deferredMilli",
                deferred,
                "contract",
                contract.id().value(),
                "outstandingMilli",
                contract.principal(),
                "reason",
                "unpaid-goods-price-becomes-debt-to-seller"));
    if (MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_DEFERRED_PAYMENT_WHY",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "commodity",
                  commodity.value(),
                  "debtor",
                  debtor.value(),
                  "creditor",
                  creditor.value(),
                  "cashAvailableMilli",
                  payableMoneyOf(ctx, buy),
                  "paidCashMilli",
                  paidCash,
                  "deferredMilli",
                  deferred,
                  "why",
                  "demand-enters-matching-in-full-unpaid-price-is-recorded-as-debt-user-0-8-item-6-7"));
    }
    if (EconomyLog.trace().isTraceEnabled()) {
      EventLog.channel(EconomyLog.trace())
          .trace(
              LogEvent.of(
                  "MARKET_DEFERRED_PAYMENT_LEG",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  ctx.round.day,
                  "debtor",
                  debtor.value(),
                  "creditor",
                  creditor.value(),
                  "unit",
                  unit.key(),
                  "amountMilli",
                  deferred,
                  "contractId",
                  contract.id().value(),
                  "outstandingAfterMilli",
                  contract.principal()));
    }
  }
}
