package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketOrderPlanner.HexPlan;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketOrderPlanner.allocateQuantity;
import static io.mosire.simos.economy.time.MarketOrderPlanner.amountInPayCurrency;
import static io.mosire.simos.economy.time.MarketOrderPlanner.confirmedIncoming;
import static io.mosire.simos.economy.time.MarketOrderPlanner.desiredQuantity;
import static io.mosire.simos.economy.time.MarketOrderPlanner.logPayCurrencyUnvalued;
import static io.mosire.simos.economy.time.MarketOrderPlanner.orderCurrencyFor;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.householdProductionCostOf;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketSettlement.HouseholdMarketRole;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketGovernmentOrderBook {

  private MarketGovernmentOrderBook() {}

  /**
   * ★★ <b>R1：本轮"政府授权挂单"的成交累计</b>（{@code mandateId → 毫商品}）。
   *
   * <p>★★ <b>归属为什么无歧义</b>：国库户是"只按授权下单"的家户 ⇒ 它在本轮的**全部**订单都来自授权表；
   * 而"同一政府同一商品同一方向至多一条生效授权"由命令边界（{@code economy.AuthorizeGovernmentMarketOrder}）与 {@link
   * GovernmentMarketMandatePlan#of} 的 fail-closed 守卫共同保证 ⇒ 一笔成交只可能属于那一条授权。★ 这里仍按 {@code (household,
   * commodity, side)} 解析而不是按槽位标记： 订单记录形状（{@code BuyOrder}/{@code SellOrder}）是本批**不动**的既有契约。
   */
  static Map<MarketMandateId, Long> collectGovMandateFills(MarketRound round, MatchContext ctx) {
    Map<MarketMandateId, Long> fills = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      if (buy.buyer.household() == null
          || round.householdMarketRole(buy.buyer.household())
              != HouseholdMarketRole.AUTHORIZATION_ONLY) {
        continue;
      }
      long filled = Math.max(0L, buy.order.quantity() - buy.remaining);
      if (filled <= 0L) {
        continue;
      }
      accumulateMandateFill(
          round,
          fills,
          buy.buyer.household(),
          buy.order.commodity(),
          GovernmentMarketMandate.Side.BUY,
          filled);
    }
    for (SellSlot sell : ctx.sells) {
      if (sell.seller.household() == null
          || round.householdMarketRole(sell.seller.household())
              != HouseholdMarketRole.AUTHORIZATION_ONLY) {
        continue;
      }
      long filled = Math.max(0L, sell.order.sellable() - sell.remaining);
      if (filled <= 0L) {
        continue;
      }
      accumulateMandateFill(
          round,
          fills,
          sell.seller.household(),
          sell.order.commodity(),
          GovernmentMarketMandate.Side.SELL,
          filled);
    }
    return fills;
  }

  /** 把一笔成交记到那条唯一的生效授权上；一条以上 / 一条都没有 ⇒ 契约故障（fail-closed，绝不静默记到错的那条）。 */
  static void accumulateMandateFill(
      MarketRound round,
      Map<MarketMandateId, Long> fills,
      HouseholdId household,
      CommodityId commodity,
      GovernmentMarketMandate.Side side,
      long filled) {
    GovernmentMarketMandate target = null;
    for (GovernmentMarketMandate mandate : round.govMandates().liveFor(household, commodity)) {
      if (mandate.side() != side) {
        continue;
      }
      if (target != null) {
        EventLog.channel(MARKET)
            .error(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "household",
                    household.value(),
                    "commodity",
                    commodity.value(),
                    "side",
                    side.name(),
                    "reason",
                    "more-than-one-live-authorization-for-same-commodity-and-side",
                    "first",
                    target.id().value(),
                    "second",
                    mandate.id().value()));
        throw new IllegalStateException(
            "同一国库户同一商品同一方向存在多条生效授权（成交量无法归属，契约故障）: household="
                + household.value()
                + " commodity="
                + commodity.value()
                + " side="
                + side
                + " first="
                + target.id().value()
                + " second="
                + mandate.id().value());
      }
      target = mandate;
    }
    if (target == null) {
      // 有订单却没有生效授权 ⇒ 计划与订单漂开了（契约故障，不静默吞掉成交量）。
      EventLog.channel(MARKET)
          .error(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_CONTRACT",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "household",
                  household.value(),
                  "commodity",
                  commodity.value(),
                  "side",
                  side.name(),
                  "reason",
                  "filled-order-without-live-authorization",
                  "filledMilli",
                  filled));
      throw new IllegalStateException(
          "政府授权成交找不到对应授权（计划与订单漂开）: household="
              + household.value()
              + " commodity="
              + commodity.value()
              + " side="
              + side);
    }
    fills.merge(target.id(), filled, Math::addExact);
  }

  /**
   * ★★ <b>R1：政府国库户的"只按授权下单"分支</b>（约束设计书 §4.5 G8 / 不变量 I-P6）。
   *
   * <p>它<b>不</b>执行自动订单算式，只把 {@link GovernmentMarketMandatePlan} 里当天生效的授权折成订单：
   *
   * <ul>
   *   <li>{@code BUY}（收购/压价）：量 = {@code min(剩余授权量, 按参考价买得起的量)}，限价 = {@code min(授权限价, 市场买方限价)} ——
   *       授权只能比市场更严，<b>不能</b>突破市场自身的价格纪律；★ 买盘<b>不走信用</b>（授权 ≠ 加杠杆，见 {@code deferralPossible} 的
   *       auth-only 守卫）；
   *   <li>{@code SELL}（抛售）：量 = {@code min(剩余授权量, 可卖余量)}，底价 = {@code max(授权限价, 市场卖方底价)}。
   * </ul>
   *
   * <p>★★ <b>成交价仍由市场按参考价裁定</b>（区内 = 本格参考价 / 跨区 = 卖方格参考价）：授权<b>不改价</b>、 不改成本、不豁免任何撮合规则 ——
   * 它是"挂单"，不是"政策"。
   *
   * <p>★★ <b>同时记一条 DEBUG：被压住的自动订单量</b>（{@code wouldBeBuyMilli}/{@code wouldBeSellMilli}）。
   * 这是"政府不是无意识买家/卖家"这条判据的现场证据：0 人口的国库户持有税收实收的粮，按自动算式 {@code wouldBeSellMilli > 0} ⇒
   * 若没有本分支，它会当场清仓（Z7b 的根因）。★ 这里的复算只服务日志，<b>不</b>参与任何写路径。
   */
  static void planGovMandateOrders(
      MarketRound round,
      HexPlan plan,
      Participant participant,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      long reference,
      long bid,
      long ask,
      long deadline,
      List<BuyOrder> buys,
      List<SellOrder> sells,
      MarketPayChoice payChoice) {
    HouseholdId household = participant.household();
    // ★★ W2-fix3（fail-closed）：本段以**家户**为键（{@code govMandates().liveFor(household, commodity)}），
    //   没有家户就没有一条可执行的授权 ⇒ 具名跳过、**先判空再解引用**。改前方法体自己不判空，而唯一解引用
    //   household 的地方（下面的 household.value()）落在 MARKET.isDebugEnabled() 块内 ⇒ 同一份世界
    //   "开 DEBUG = NPE / 关 DEBUG = 静默不生成"，行为取决于日志开关。现在两支都是"零订单 + 具名理由"
    //   （来路与逐条证据见 logGovMandateNoHousehold 的注）。
    if (household == null) {
      logGovMandateNoHousehold(round, participant, hex, commodity);
      return;
    }
    long stock = stockOf(round, participant, commodity);
    long frozen = frozenGoodsOf(round, participant, commodity);
    long available = Math.max(0L, stock - frozen);
    // ★★ 3c：授权单与自动单走**同一条缺省口径**（见 orderCurrencyFor 的注）。
    //   ★★ P-T5b：国库户**不进**币种选择面（{@link MarketPayChoice} 与 FX 段同一条排除口径）⇒ 它的授权单仍按
    //     本格计价币（逐值不变）；这里仍走同一条"按买方支付币折算"的算式，保持全仓只有一个拼写点。
    CurrencyId orderCurrency = orderCurrencyFor(payChoice, participant, market);
    long budget = spendableMoneyOf(round, participant, orderCurrency);
    // ★★ P-T5b：可负担量同样按买方支付币折算（说不出价 ⇒ 具名 + 本户今日的授权单整段不生成）。
    long referenceInPay = amountInPayCurrency(payChoice, market, hex, orderCurrency, reference);
    if (referenceInPay < 0L) {
      logPayCurrencyUnvalued(round, participant, hex, market, commodity, orderCurrency, reference);
      return;
    }
    long cashAffordable =
        referenceInPay == 0L
            ? Long.MAX_VALUE
            : budget * EconomySettlement.MILLI_PER_GRAIN / referenceInPay;
    int mandateBuys = 0;
    int mandateSells = 0;
    for (GovernmentMarketMandate mandate : round.govMandates().liveFor(household, commodity)) {
      if (mandate.side() == GovernmentMarketMandate.Side.BUY) {
        long quantity = Math.min(mandate.remainingMilli(), cashAffordable);
        if (quantity <= 0L) {
          // ★ 业务拒绝（授权买不起）⇒ DEBUG 具名（§一.9：关键判据写"为什么"）。
          EventLog.channel(MARKET)
              .debug(
                  LogEvent.of(
                      "GOV_MARKET_MANDATE_ORDER_SKIPPED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      household.value(),
                      "mandate",
                      mandate.id().value(),
                      "side",
                      "BUY",
                      "commodity",
                      commodity.value(),
                      "reason",
                      "no-spendable-money",
                      "remainingMilli",
                      mandate.remainingMilli(),
                      "spendableMilli",
                      budget));
          continue;
        }
        long limit = Math.min(mandate.limitPriceMilli(), ask);
        buys.add(
            new BuyOrder(
                participant.actor(),
                hex,
                commodity,
                quantity,
                limit,
                deadline,
                new Budget(budget, orderCurrency),
                orderCurrency));
        mandateBuys++;
        if (MARKET.isTraceEnabled()) {
          EventLog.channel(MARKET)
              .trace(
                  LogEvent.of(
                      "GOV_MARKET_MANDATE_BUY_ORDER",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      household.value(),
                      "mandate",
                      mandate.id().value(),
                      "government",
                      mandate.government().value(),
                      "commodity",
                      commodity.value(),
                      "quantityMilli",
                      quantity,
                      "limitPriceMilli",
                      limit,
                      "mandateLimitPriceMilli",
                      mandate.limitPriceMilli(),
                      "marketAskMilli",
                      ask,
                      "remainingMilli",
                      mandate.remainingMilli(),
                      "spendableMilli",
                      budget,
                      "hex",
                      hex.toString()));
        }
      } else {
        long sellable = Math.min(mandate.remainingMilli(), available);
        if (sellable <= 0L) {
          EventLog.channel(MARKET)
              .debug(
                  LogEvent.of(
                      "GOV_MARKET_MANDATE_ORDER_SKIPPED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      household.value(),
                      "mandate",
                      mandate.id().value(),
                      "side",
                      "SELL",
                      "commodity",
                      commodity.value(),
                      "reason",
                      "no-sellable-stock",
                      "remainingMilli",
                      mandate.remainingMilli(),
                      "availableMilli",
                      available));
          continue;
        }
        long floor = Math.max(mandate.limitPriceMilli(), bid);
        sells.add(
            new SellOrder(
                participant.actor(), hex, commodity, sellable, floor, round.day, orderCurrency));
        mandateSells++;
        if (MARKET.isTraceEnabled()) {
          EventLog.channel(MARKET)
              .trace(
                  LogEvent.of(
                      "GOV_MARKET_MANDATE_SELL_ORDER",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      household.value(),
                      "mandate",
                      mandate.id().value(),
                      "government",
                      mandate.government().value(),
                      "commodity",
                      commodity.value(),
                      "quantityMilli",
                      sellable,
                      "limitPriceMilli",
                      floor,
                      "mandateLimitPriceMilli",
                      mandate.limitPriceMilli(),
                      "marketBidMilli",
                      bid,
                      "remainingMilli",
                      mandate.remainingMilli(),
                      "availableMilli",
                      available,
                      "hex",
                      hex.toString()));
        }
      }
    }
    if (MARKET.isDebugEnabled()) {
      // ★★ 被压住的自动订单（同一算式的复算；只为证据，不参与写路径）。
      // ★★ W2-fix3：本方法开头已经"家户为 null ⇒ 具名跳过 + return"（见 logGovMandateNoHousehold）⇒
      //   participant.household 走到这里恒非 null：原来的 `participant.household != null ? productionCostHere
      // :
      //   necessary` 与 `participant.household != null && round.deferredPaymentsEnabled()` 两条空支都是死代码
      //   （SpotBugs RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE），随守卫一并删去；**可达路径上逐值不变**。
      long demandTarget = 0L;
      for (long part :
          plan.demandParts
              .getOrDefault(participant.actor(), Map.of())
              .getOrDefault(commodity, List.of())) {
        demandTarget = Math.addExact(demandTarget, part);
      }
      // ★★ W2（S-10）：与真实订单路径**同一个量**（生产花费 = 必要投入 + 口粮前瞻）—— 这条 DEBUG 是
      //   "被授权压住的自动订单"的证据，口径漂开就会给出错的反事实读数。
      long productionCostHere =
          householdProductionCostOf(round, participant).getOrDefault(commodity, 0L);
      long retention = Math.addExact(productionCostHere, demandTarget);
      long wouldBeSell = Math.max(0L, stock - frozen - retention);
      long incoming = confirmedIncoming(round, participant.actor(), commodity, deadline);
      long baseTarget = productionCostHere;
      long wouldBeBuy =
          round.deferredPaymentsEnabled()
              ? desiredQuantity(
                  baseTarget,
                  plan.demandParts
                      .getOrDefault(participant.actor(), Map.of())
                      .getOrDefault(commodity, List.of()),
                  available,
                  incoming)
              : allocateQuantity(
                  baseTarget,
                  plan.demandParts
                      .getOrDefault(participant.actor(), Map.of())
                      .getOrDefault(commodity, List.of()),
                  available,
                  incoming,
                  cashAffordable);
      long arbitrageQuantity = 0L;
      MarketArbitragePlan.Instruction instruction =
          round.arbitrage().instructionFor(household, commodity).orElse(null);
      if (instruction != null && instruction.direction() == HouseholdActivity.Direction.BUY) {
        arbitrageQuantity = instruction.quantityMilli();
      }
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "GOV_MARKET_AUTO_ORDERS_SUPPRESSED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "household",
                  household.value(),
                  "commodity",
                  commodity.value(),
                  "hex",
                  hex.toString(),
                  "authorizationOnly",
                  true,
                  "autoBuyOrders",
                  0,
                  "autoSellOrders",
                  0,
                  "mandateBuyOrders",
                  mandateBuys,
                  "mandateSellOrders",
                  mandateSells,
                  "wouldBeBuyMilli",
                  wouldBeBuy,
                  "wouldBeSellMilli",
                  wouldBeSell,
                  "arbitrageBuyMilli",
                  arbitrageQuantity));
    }
  }

  /**
   * ★★ W2-fix3（fail-closed）：<b>"只按授权下单"这一段拿不到家户 ⇒ 具名跳过（DEBUG），不静默、不解引用</b>。
   *
   * <p><b>household 什么时候会是 null（调用链来路）</b>：本方法第一行取 {@code HouseholdId household =
   * participant.household}，而 {@code Participant.household} 是<b>可空字段</b> —— {@link Participant}
   * 的紧凑构造器只对 {@code actor}/{@code units} 做 {@code requireNonNull}（{@link Participant}，本文件
   * :1150-1157），全类多处按可空读它 （{@code :176} 家户经济视图、{@code :2597} 市场排除户、{@code :2633} 需求段、{@code
   * :2672}/{@code :2696} 目标量与信用需求、 {@code :2792} 支付币、{@code :3951} 信用轮、{@code :9075} 债务清算、{@code
   * :9495}+ 计划表）， 连 {@link #logPayCurrencyUnvalued} 都直接兜了空串（{@code :2855}）。
   *
   * <p><b>今天走不走得到</b>：走不到 —— {@link #participantsFor} 现存三处构造点（本文件 :9326/:9465/:9476）传的都是
   * 解析出来的家户键，且本方法唯一调用点在本文件 {@code :2607-2608}，那里有一道 {@code participant.household != null} 守卫。 ⇒
   * 本条守卫是<b>纵深防御</b>，不是"改了一条现有路径"：它消掉的是"一旦那道守卫被放宽 / 多点一个调用点，同一份世界里 '开 DEBUG = NPE、关 DEBUG =
   * 静默不生成'（行为取决于日志开关）"这种不确定性；现在两支都收敛成"零订单 + 具名理由"。
   *
   * <p><b>为什么是 DEBUG 而不是 INFO</b>：它是"这一段为什么不成立"的判据（§一.9：理由归 DEBUG），与同方法的 {@code
   * GOV_MARKET_MANDATE_ORDER_SKIPPED}、{@link #logPayCurrencyUnvalued} 的 {@code
   * MARKET_PAY_CURRENCY_UNVALUED} 同一档（后者成文写了同款理由，本文件 {@code :2832-2834}）；轮级的"谁在按授权下单"由 {@code
   * GovernmentMarketMandatePlan} 的 INFO 承担。
   */
  static void logGovMandateNoHousehold(
      MarketRound round, Participant participant, HexCoord hex, CommodityId commodity) {
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响订单生成
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "GOV_MARKET_MANDATE_PLAN_SKIPPED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "actor",
                participant.actor().id(),
                "hex",
                hex,
                "commodity",
                commodity.value(),
                "reason",
                "no-household",
                "why",
                "授权单以家户为键（GovernmentMarketMandatePlan.liveFor(household, commodity)）⇒ 没有家户就没有可执行的授权，本格本商品的授权单整段不生成"));
  }
}
