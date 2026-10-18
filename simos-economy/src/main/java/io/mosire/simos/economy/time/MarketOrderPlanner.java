package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_BUY_DEADLINE_DAYS;
import static io.mosire.simos.economy.time.MarketGovernmentOrderBook.planGovMandateOrders;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketMoneyBook.buyerUnitPriceFor;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.householdProductionCostOf;
import static io.mosire.simos.economy.time.MarketRosterBook.planFor;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketSettlement.HouseholdMarketRole;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;
import static io.mosire.simos.economy.time.MarketSettlement.PlannedOrders;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketOrderPlanner {

  private MarketOrderPlanner() {}

  /** 一个参与主体：家户（{@code household != null}）或经营者（{@code household == null}）。 */
  record Participant(
      ActorRef actor, HouseholdId household, CohortKey view, List<ProductionUnitId> units) {
    Participant {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(units, "units");
      units = List.copyOf(units);
    }
  }

  /**
   * ★★ <b>R2：一个 hex 的并行订单构建产物</b>（参与者 + 买卖槽，均按原串行遍历序）。
   *
   * <p>worker 在分区内构建、协调器按 {@code (q,r)} 升序拼回 ⇒ 与串行版本的插入序逐字相同（见 {@code clearOncePerCycle}
   * 的并行入口注释）。字段都只在本次调用内流转，不进状态、不共享。
   */
  record HexOrderPlan(
      HexCoord hex, List<Participant> participants, List<BuySlot> buys, List<SellSlot> sells) {}

  /** 一个格的预计算：参与者 + 逐主体的必要生产投入 / 生活保留（按商品）。 */
  static final class HexPlan {
    final List<Participant> participants;
    final Map<ActorRef, Map<CommodityId, Long>> necessaryInputs;
    final Map<ActorRef, Map<CommodityId, Long>> lifeReserves;

    /** ★★ R4-E2：逐家户、逐商品的**有序**需求分段（list 序 = 预算优先级序；经营者恒为空表）。目标总量 = 各段之和。 */
    final Map<ActorRef, Map<CommodityId, List<Long>>> demandParts;

    HexPlan(
        List<Participant> participants,
        Map<ActorRef, Map<CommodityId, Long>> necessaryInputs,
        Map<ActorRef, Map<CommodityId, Long>> lifeReserves,
        Map<ActorRef, Map<CommodityId, List<Long>>> demandParts) {
      this.participants = participants;
      this.necessaryInputs = necessaryInputs;
      this.lifeReserves = lifeReserves;
      this.demandParts = demandParts;
    }
  }

  /**
   * ★★ <b>纯订单生成</b>（包内可见；给测试与读口一条不经撮合的入口）：生成某一格、某一商品的全部买卖订单，<b>不落账、不改任何副本</b>。
   *
   * <p>★ 它与 {@link #clearOncePerCycle} 用的是<b>同一条</b> {@code ordersFor} 实现 ⇒ 测到的订单与真正成交的订单不可能漂开。
   */
  static PlannedOrders planOrders(
      MarketRound round, HexCoord hex, MarketTable market, CommodityId commodity) {
    Objects.requireNonNull(round, "round");
    return planOrders(
        round, hex, market, commodity, EconomySettlement.rowsByHex(round.householdEconomies));
  }

  /**
   * ★ <b>纯订单生成的"已建索引"重载</b>（M2.7 读口用）：{@code rowsByHex} 由调用方一次建好 —— 逐区读数会对同一个 {@code rows}
   * 调它几十次，每次重扫全部行是纯浪费；两条重载走的是同一条 {@code ordersFor}。
   *
   * <p>★ <b>P-T5b：不注入币种面 ⇒ 订单币恒 = 本格计价币</b>（与改前逐值相同）。要按"该户最强持有币"下单的调用方走下面那条带 {@code payChoice}
   * 的重载（{@code clearOncePerCycle} 与读口都走它）。
   */
  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      Map<String, List<HouseholdId>> rowsByHex) {
    return planOrders(round, hex, market, commodity, rowsByHex, MarketPayChoice.none());
  }

  /**
   * ★★ <b>P-T5b：带"该户本轮支付币"的纯订单生成</b>（{@code payChoice} 的唯一消费点之一）。
   *
   * <p>★ <b>为什么读口必须走带 {@code payChoice} 的重载</b>：读口与结算用的是同一条 {@code ordersFor} —— 若读口按本格计价币生成订单、
   * 结算按"最强持有币"生成，多币世界里"看到的订单"与"会下的订单"就会在<b>买单数量</b>上漂开（{@code MarketReadout} 的供给/有效需求正是从这里来的）。
   */
  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      Map<String, List<HouseholdId>> rowsByHex,
      MarketPayChoice payChoice) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(commodity, "commodity");
    Objects.requireNonNull(rowsByHex, "rowsByHex");
    Objects.requireNonNull(payChoice, "payChoice");
    // ★★ 2026-10-09：有定价行（含明确 0 价）都进订单生成；"从未定价"才不交易。
    //   ★★ 2026-10-10 P-T1c：定价只看**逐格**价表（{@link MarketTable#hasPrice}）—— 区级参考价覆盖已删（设计书 §16）。
    if (!market.hasPrice(commodity)) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（同 MarketTable 的口径）
    }
    List<HouseholdId> keys =
        rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
    return ordersFor(round, planFor(round, hex, keys), hex, market, commodity, payChoice);
  }

  /**
   * ★★ <b>订单生成</b>：参考价/限价一律取自<b>本格价表</b>（{@link MarketTable#priceOf}/{@link
   * MarketTable#bidPriceOf}/{@link MarketTable#askPriceOf} 是唯一拼写点）—— 区级参考价/限价覆盖已于 P-T1c 删除（设计书
   * §16：定价只有逐格一层）。
   *
   * <p>成交仍按参考价（区内）/ 卖方格参考价（跨区）—— 逐 hex 物流成本另由 {@link HexTradeCost} 承担，单 hex 损耗<b>不</b>承担价格职能。
   */
  static PlannedOrders ordersFor(
      MarketRound round,
      HexPlan plan,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      MarketPayChoice payChoice) {
    // ★★ M2.6：参考价 = 本格价表里的固定报价；两个限价由 MarketTable 的两个**各自独立**的常量现算
    //   （bid = 卖方底价、ask = 买方限价），订单按它们过滤；成交仍按参考价（区内）/ 卖方格参考价（跨区）。
    // ★★ 2026-10-09：先区分"从未定价"（不交易）与"明确 0 价"（免费交易）。有定价行 ⇒ 可挂单；值为 0 ⇒ 货款腿 0，
    //    买方只承担运费（运费与价格解耦，见 freightUnitMilli）。
    if (!market.hasPrice(commodity)) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（不凭空造一行）
    }
    // ★★ A2（2026-10-10）：**运输服务不进订单簿**（本格成市时）—— 它的需求**不是独立需求**，而是由"其他商品的
    //   购买"派生出来的（用户 2026-10-10 原话「这个商品的需求需要额外通过其他已有商品的购买来计算」；设计书 §3.3）。
    //   ⇒ 供给也不该在订单簿里另挂一份：服务在**跨格成交那一刻**由运力池按"期限价升序 → 既有 canonical 序"选中的
    //   跑商家户现卖（{@code executeTrade} 的服务分支），并当场消耗（{@link HaulService#SERVICE_CONSUMED_ACCOUNT}）。
    //   ★ 若在这里给 haul 挂卖单，那批货会被 {@code commitFreezes} 冻结，而服务成交的实扣走同一条"可用量"判据
    //     ⇒ 卖家自己的卖单会把要卖的服务冻住（此刻的"卖不动"而不是"卖完了"）—— 这是结构性冲突，故服务不挂簿。
    //   ★ 缺省中性不受影响：本格没给 haul 定价时上面那条 hasPrice 守卫已经整行返回；本分支只在**成市**的格生效。
    if (HaulService.HAUL_COMMODITY.equals(commodity) && HaulService.pricedAt(market)) {
      return new PlannedOrders(List.of(), List.of());
    }
    long reference = market.priceOf(commodity);
    long bid = market.bidPriceOf(commodity);
    long ask = market.askPriceOf(commodity);
    List<BuyOrder> buys = new ArrayList<>();
    List<SellOrder> sells = new ArrayList<>();
    long deadline = round.day + MARKET_BUY_DEADLINE_DAYS;
    for (Participant participant : plan.participants) {
      // ★★★ R2（2026-10-11，控制方裁定）：本参与者的角色 —— **唯一判据**（{@link MarketRound#householdMarketRole}）。
      //   ★ 改前这里是 Z7b 的两层防线之一："单位户 ⇒ 不生成任何订单"；R2 把它收窄成"**只买不卖**"：
      //     军户/官吏户的买盘走下面**原样的**普通家户路径（同一个买盘目标 = 本期生产花费 + 口粮前瞻、同一份牌价、
      //     同一条现金腿/赊购腿），只有卖单不生成。国库户（hh-gov-*）两侧都不生成，走授权分支（改前口径一字不动）。
      HouseholdMarketRole role =
          participant.household == null
              ? HouseholdMarketRole.FULL
              : round.householdMarketRole(participant.household);
      // ── ★★ R1：政府国库户 = "只按授权下单"：**不生成任何自动订单**（需求/库存差异一概不产生买卖）──────
      //   它今天能出现在市场上的订单**全部**来自 EconomyData.govMarketMandates() 的明确授权
      //   （用户 2026-10-09 §1.3：政府经济行为 = 行政家户挂单，不加政策层）。
      //   ★ 为什么必须显式挡在这里、而不是"反正它没有需求"：国库户持有税收实收的粮/银，而下面的卖单判据是
      //     max(0, 持有 − 冻结 − 必要投入 − 生活保留 − 需求目标)，0 人口的国库户后四项全是 0 ⇒
      //     它会被当成卖家**清仓**（Z7b 的根因：run6 day50 中央粮 271,393 → 0）。
      if (role == HouseholdMarketRole.AUTHORIZATION_ONLY) {
        planGovMandateOrders(
            round,
            plan,
            participant,
            hex,
            market,
            commodity,
            reference,
            bid,
            ask,
            deadline,
            buys,
            sells,
            payChoice);
        continue;
      }
      // ★★ R2：单位户 = 卖侧维持 Z7b 原口径（下面 `sells.add` 那一处唯一消费本判据）。
      boolean buyOnly = role == HouseholdMarketRole.BUY_ONLY;
      long necessary =
          plan.necessaryInputs
              .getOrDefault(participant.actor, Map.of())
              .getOrDefault(commodity, 0L);
      // ★★ W2（S-10）：这里不再单独读 {@code plan.lifeReserves}（口粮）—— 它已并进
      //   {@link #householdProductionCostOf} 的"生产花费"里（下面 {@code productionCostHere}）。
      // ★★ R4-E2：GM 需求只归家户；经营者不因需求加目标（demandParts 恒空）。
      List<Long> demandParts =
          participant.household == null
              ? List.of()
              : plan.demandParts
                  .getOrDefault(participant.actor, Map.of())
                  .getOrDefault(commodity, List.of());
      long demandTarget = 0L;
      for (long part : demandParts) {
        demandTarget = Math.addExact(demandTarget, part);
      }
      long stock = stockOf(round, participant, commodity);
      long frozen = frozenGoodsOf(round, participant, commodity);
      long available = Math.max(0L, stock - frozen);
      // ★★ 3c：**本单的币**（唯一缺省拼写点 = orderCurrencyFor）—— 买方的支付币与卖方的收款币由订单携带；
      //   订单生成这一层把"缺省 = 本格计价币"填进去，槽位/预算/冻结/限价折算此后一律读订单。
      //   ⇒ 旧世界（谁也没指定币）逐值不变（I-C2）。
      //   ★★ P-T5b：选币策略**只改 orderCurrencyFor 一处**（它读 MarketPayChoice 的"该户最强持有币"）——
      //     预算/可负担量/冻结/运费折算都跟着这个币走，见下面各处的"按买方支付币折算"。
      CurrencyId orderCurrency = orderCurrencyFor(payChoice, participant, market);
      // ── 卖：可卖 = max(0, 持有 − 已冻结 − **生产花费（内含口粮）** − 有效需求目标) ─────────────
      //   ★★ 需求目标同时进"不卖"一侧：要买的粮/布（或任何新商品）不许在同一轮又被当余量卖掉。
      //   ★★ W2（S-10）：家户的"必要生产投入 + 生活保留"合并成**一个量**（生产花费，内含口粮）——
      //     {@link #householdProductionCostOf}。数值上与改动前的 {@code necessary + life + demandTarget}
      //     **逐值相同**（两个分量都在表里、只是口径合成一个；经营者那一支 {@code life} 恒 0）⇒ 卖侧零行为变化。
      Map<CommodityId, Long> productionCost = householdProductionCostOf(round, participant);
      long productionCostHere = productionCost.getOrDefault(commodity, 0L);
      long retention = Math.addExact(productionCostHere, demandTarget);
      long sellable = Math.max(0L, stock - frozen - retention);
      if (sellable > 0L && !buyOnly) {
        sells.add(
            new SellOrder(
                participant.actor, hex, commodity, sellable, bid, round.day, orderCurrency));
      } else if (sellable > 0L && MARKET.isTraceEnabled()) {
        // ★★ R2 具名日志（§一.9 TRACE = 逐笔明细）：**这条卖单被 R2 的卖出侧口径挡下**（改前它会被挂出去）。
        //   ★ 有它才回答得了"为什么这一户明明有粮却没有卖单" —— 也是 Z7b 根因守护仍然生效的证据。
        EventLog.channel(MARKET)
            .trace(
                LogEvent.of(
                    "MARKET_HOUSEHOLD_SELL_SUPPRESSED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "household",
                    participant.household.value(),
                    "commodity",
                    commodity.value(),
                    "hex",
                    hex.toString(),
                    "wouldBeSellMilli",
                    sellable,
                    "role",
                    role.name(),
                    "reason",
                    "r2-sell-side-keeps-z7b-scope-unit-household-holdings-are-not-cleared-as-surplus"));
      }
      // ── 买：家户补到**生产花费（内含口粮）** + 有效需求目标；经营者补到必要生产投入 ─────────────
      //   ★ M2.4：目标缺口 = target − 可用 − **该时限前确定到货**（在途批次里买方那一份；M2.1 的原文）。
      //   ★★ R4-E2 的预算优先级：先保生活保留基线，再按 demands 的 priority 升序（同 priority 按
      //     DemandId）逐个扣"按参考价折算的买得起量"；下层需求拿上一层剩下的额度。
      //   ★★ W2（S-10，用户第五批原话）：**家户只预估"生产花费"并据此下单** —— 改动前这里只拿 {@code life}
      //     （口粮前瞻），名下 unit 的必要投入不上买盘（只在卖侧保留）。现在两者合成一个目标量：
      //     "无业"家户的生产花费 = 基本生理需求（第一项为空），同一个算式、没有第二个分支。
      long baseTarget = participant.household != null ? productionCostHere : necessary;
      long incoming = confirmedIncoming(round, participant.actor, commodity, deadline);
      // ★★ 3c：预算按**买方支付币**读（不再把本格币的可花额当买方币）——缺省同币 ⇒ 逐值不变。
      long budget = spendableMoneyOf(round, participant, orderCurrency);
      // ★★ P-T5b：可负担量按**买方支付币**折算 —— 本格价表的限价/参考价是"毫本格计价币"，买方钱包是"毫支付币" ⇒
      //   经**同一份** CurrencyValuation 折一次（唯一拼写点 amountInPayCurrency；同币 ⇒ 原样，逐值不变）。
      //   ★ 说不出价（该币在本格既无报价、当地也不流通）⇒ **具名归因、本单不生成**：不静默按 1:1、也不静默退回本格计价币。
      long referenceInPay = amountInPayCurrency(payChoice, market, hex, orderCurrency, reference);
      if (referenceInPay < 0L) {
        logPayCurrencyUnvalued(
            round, participant, hex, market, commodity, orderCurrency, reference);
        continue; // 卖单不受影响（它只声明接受哪种币，不折算金额）—— 只有这张买单没有了
      }
      // ★★ 0 价免费交易：货款腿为 0 ⇒ 数量不受"货款买得起"约束（只受缺口约束）；运费仍由撮合阶段按
      //    route.freightPerUnit 逐笔复核（家户与经营者同口径）。未定价的商品已在方法开头整行返回。
      long cashAffordable =
          referenceInPay == 0L
              ? Long.MAX_VALUE
              : budget * EconomySettlement.MILLI_PER_GRAIN / referenceInPay;
      // ★★★ W2（2026-10-11）：**家户把"生产花费 + 需求缺口"整笔挂出来** —— 数量不按现金折回；撮合时先用手上的
      //   现金付货款、不足的部分**全额记为对该卖方的债务**（{@link #recordDeferredPaymentDebt}，用户 0–8 第 6 条）。
      //   ★ 前提是**本轮有债务工作副本**（{@code round.deferredPaymentsEnabled()}，与 {@code deferralPossible}
      // 同一条判据）——
      //     没有它就没有地方记那笔欠款，此时退回"按现金买得起量封顶"（旧口径，绝不凭空赊账）。
      //   ★ 经营者不参与债务式清算（{@code DebtContract} 的两端只能是家户）⇒ 仍按现金买得起量封顶。
      boolean deferredPaymentDemand =
          participant.household != null && round.deferredPaymentsEnabled();
      long quantity =
          deferredPaymentDemand
              ? desiredQuantity(baseTarget, demandParts, available, incoming)
              : allocateQuantity(baseTarget, demandParts, available, incoming, cashAffordable);
      // ── ★★ 2026-10-08（阶段 1，§4.3.1 第 ④ 步）：家户套利买盘 ────────────────────────────────
      //   ★ 它是"在既有买目标**之上**加量"（I15：生活保留/需求目标一份都不减），量已在排序阶段按
      //     "机会上限（30 天目标保有量的 25%）+ 可动现金的两成 + 价格冲击"封死（见 TradeArbitrageActivity）。
      //   ★★ **刻意不在订单层再按"计划量占用的现金"二次封顶**：credit 世界里既有买盘的最后一段本来就是信用
      //     （该段已随 W2-fix4 退役：缺口改由"未付价款记债"结），按 notional 现金再封一道会让套利在
      //     "现金相对货值极贫瘠"的真档世界里恒为 0
      //     （= 静默死分支，正是本仓最反对的形态）。"自有资源"约束因此落在**计划那一侧**（可动现金 ×
      //     MONEY_CAP_PER_MILLE‰），而不是订单侧；这条取舍与其数值后果已记进实现账本。
      if (participant.household != null) {
        MarketArbitragePlan.Instruction instruction =
            round.arbitrage().instructionFor(participant.household, commodity).orElse(null);
        if (instruction != null && instruction.direction() == HouseholdActivity.Direction.BUY) {
          long arbitrageQuantity = instruction.quantityMilli();
          // ★★ 2026-10-08 自配对（fix-ledger ①）：这一行**不**与上面的 `sellable` 对账 —— 卖单余量已经把
          //   生活保留(35 天)与需求目标扣掉，而套利买盘按 30 天目标的**劳动阶段**快照加量，两者在同一轮里
          //   可以同时为正（当日收获/产出落在两个阶段之间）⇒ 同一户在同一市场上既卖又买**同一商品**。
          //   ★ 本批不去改这一行的量（会改变未崩溃世界的数值），而是在撮合处加"同户配对跳过"守卫
          //     （{@code pairUp}，形制照 FxSettlement.matchBook）；根因与后果见账本。
          //   ★★ W2-fix4（2026-10-11）：当年与之配对的另一处守卫住在 {@code moneyCreditForBuy}（市场信用撮合）
          //     —— 那一段已整段退役 ⇒ 现在**只剩 pairUp 一处**需要这条守卫。
          quantity = Math.addExact(quantity, arbitrageQuantity);
          if (EconomyLog.market().isTraceEnabled()) {
            EventLog.channel(EconomyLog.market())
                .trace(
                    LogEvent.of(
                        "ARBITRAGE_BUY_ORDER",
                        EconomyLogSource.ECONOMY_ARBITRAGE,
                        "day",
                        round.day,
                        "household",
                        participant.household.value(),
                        "commodity",
                        commodity.value(),
                        "hex",
                        hex.toString(),
                        "orderQuantityMilli",
                        quantity,
                        "arbitrageQuantityMilli",
                        arbitrageQuantity,
                        // ★ 2026-10-08 诊断缺陷修复：字段名 = 真实量纲（微 numeraire / 商品单位；1 毫 = 1000 微）
                        "reservationMicro",
                        instruction.reservationMicro(),
                        "marketMicro",
                        instruction.marketMicro(),
                        "edgeMicro",
                        instruction.edgeMicro(),
                        "referenceMilli",
                        reference,
                        "cashAffordableMilli",
                        cashAffordable == Long.MAX_VALUE ? -1L : cashAffordable));
          }
        }
      }
      if (quantity <= 0L) {
        continue; // 没缺口 / 没钱的缺口不是有效需求（经营者仍按现金封顶；家户的缺口由信用补）
      }
      // ★ 买得起多少按**参考价**折算：区内成交价就是它；跨区若卖方到货价更高，{@link #matchRoute} 会按实际
      //   "单价 + 运费"复核预算并缩小成交。★ ask 只做**限价过滤**（买方最多愿付），不在这里折数量 —— 否则
      //   价格表很小时（真档粮价 = 1 毫）整数网格会把 +1 毫的价差放大成"买得起的量减半"。
      // ★ 限价 = **货款**上限；跨区运费另计（M2.5：买方付货款与运费）。到货时限 = 生活保留天数（见常量注释）。
      buys.add(
          new BuyOrder(
              participant.actor,
              hex,
              commodity,
              quantity,
              ask,
              deadline,
              new Budget(budget, orderCurrency),
              orderCurrency));
      if (buyOnly && MARKET.isDebugEnabled()) {
        // ★★ R2 具名日志（§一.9 DEBUG = 逐条池子/理由）：**单位户这一轮的买盘**（谁、买什么、多少、什么价、
        //   手里有多少钱、缺口会不会走赊购）—— R2 之后这些户的买入不再需要反读代码才能确认。
        //   ★ 逐笔成交的"实付/赊购"由既有的 {@code MARKET_DEFERRED_PAYMENT}（INFO）给出。
        EventLog.channel(MARKET)
            .debug(
                LogEvent.of(
                    "MARKET_HOUSEHOLD_BUY_ORDER",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "household",
                    participant.household.value(),
                    "commodity",
                    commodity.value(),
                    "hex",
                    hex.toString(),
                    "quantityMilli",
                    quantity,
                    "targetMilli",
                    baseTarget,
                    "availableMilli",
                    available,
                    "incomingMilli",
                    incoming,
                    "budgetMilli",
                    budget,
                    "currency",
                    orderCurrency.value(),
                    "referenceMilli",
                    reference,
                    "deferralEnabled",
                    deferredPaymentDemand,
                    "role",
                    role.name(),
                    "reason",
                    "r2-unit-household-buys-as-an-ordinary-household"));
      }
    }
    return new PlannedOrders(buys, sells);
  }

  /**
   * ★★ <b>订单币的唯一产生点（"用哪种钱付/收"只在这里决定一次）</b>。
   *
   * <p>★★ <b>P-T5b 口径（冻结）</b>：家户买单的支付币 = <b>该户当轮选定的"最强持有币"</b> （{@link MarketPayChoice}：F-1
   * 购买力强度序的首项，口径见 {@link HouseholdPurchasingPower}）。
   *
   * <pre>
   * 缺省（没有币种面）        ⇒ 本格计价币（I-C2 的缺省语义中性，不是兼容位）
   * 单一币 / 不可比 / 无需求  ⇒ 本格计价币（MarketPayChoice 里没有这一户 ⇒ getOrDefault 回落）
   * 选出来了                  ⇒ 那种币（家户即使在本格市场也用它付 —— 这正是 §20.4 要闭合的前置依赖）
   * </pre>
   *
   * <p>★ <b>买卖两侧共用它（"卖方侧不分道"）</b>：买方的 {@code payWith} 与卖方的 {@code receiveWith} 在缺省上是同一个口径。★
   * 卖方侧<b>不按自己的收款币过滤候选</b>（{@link #acceptsCurrency} 只看口岸规则，P-T1e），而结算的钱腿永远铸 <b>买方支付币</b>（{@code
   * executeTrade}）⇒ "收货币 = 买方的支付币" 这条现状一个字不改；{@link SellSlot#receiveCurrency} 只进日志/读数。
   *
   * <p>★ <b>不许在别处再拼一次"本格计价币"</b>：订单币只有这一个产生点，槽位/预算/冻结/结算此后一律读订单。
   */
  static CurrencyId orderCurrencyFor(
      MarketPayChoice payChoice, Participant participant, MarketTable market) {
    return payChoice.payCurrencyFor(participant.household, market);
  }

  /**
   * ★★ <b>P-T5b：本格计价币金额 → 买方支付币金额（唯一拼写点）</b>。
   *
   * <pre>
   * 金额(毫支付币) = ⌈金额(毫本格计价币) × 1000 ÷ 该币的价(微本格计价币/毫该币)⌉   // P-T5b
   * 同币 ⇒ 原样（不看任何表；1:1 是结构性事实，不是查到 1000 才恰好相等）
   * </pre>
   *
   * <p>★ <b>为什么向上取整</b>：与 {@code buyerUnitPriceFor}（结算侧"买方币单价"）逐字同源 —— 宁可多留一点预算， 不可少留（少留 =
   * 冻结不足，得靠余额兜底）。★ 同币时 {@code ⌈x×1000÷1000⌉ = x} 精确成立 ⇒ <b>旧世界逐值不变</b>。
   *
   * <p>★ <b>说不出价 ⇒ {@code -1}</b>（fail-closed 哨兵）：调用方必须<b>具名归因</b>，禁按 1:1 顶上。
   *
   * <p>★★ <b>折算锚是"买方自己的钱"</b>（本格价表的计价币 ↔ 买方支付币，区取该 hex 所属区）：订单侧问的是 "我这点钱买得起多少"，预算与限价都在这个尺度上。★
   * 结算侧（{@code settlementUnitPrice}）仍按<b>卖方</b>的尺度折算单价 —— 那是"这笔货按什么价成交"（3c 的口径，本批不改）。
   */
  static long amountInPayCurrency(
      MarketPayChoice payChoice,
      MarketTable market,
      HexCoord hex,
      CurrencyId payCurrency,
      long numeraireMilli) {
    if (numeraireMilli <= 0L) {
      return 0L; // 0 价（免费）与 0 金额：换算后仍是 0，不需要任何价
    }
    if (payCurrency.equals(market.numeraire())) {
      return numeraireMilli; // 同币：本币对自己 = 面值 1:1（结构性，逐值不变）
    }
    long valueMicro = payChoice.valueMicroOf(market, payCurrency, hex);
    if (valueMicro <= 0L) {
      return -1L; // 说不出这种钱的价 ⇒ 调用方具名拒（绝不静默 1:1）
    }
    return buyerUnitPriceFor(numeraireMilli, valueMicro);
  }

  /**
   * ★ <b>P-T5b：买方选定的支付币在本格市场说不出价 ⇒ 具名归因（DEBUG，不静默）</b>。
   *
   * <p>★ <b>为什么是 DEBUG 而不是 INFO</b>：它是"这一张订单为什么不生成"的判据（§一.9：理由归 DEBUG）， 与既有的 {@code
   * GOV_MARKET_MANDATE_ORDER_SKIPPED} 同一档；轮级的"这一轮多少人用外币付"由 {@code MarketPayChoice.of} 的 INFO 承担。
   */
  static void logPayCurrencyUnvalued(
      MarketRound round,
      Participant participant,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      CurrencyId payCurrency,
      long referenceMilli) {
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响订单生成
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_PAY_CURRENCY_UNVALUED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "household",
                participant.household == null ? "" : participant.household.value(),
                "hex",
                hex.toString(),
                "commodity",
                commodity.value(),
                "payCurrency",
                payCurrency.value(),
                "marketNumeraire",
                market.numeraire().value(),
                "referenceMilli",
                referenceMilli,
                "ordersPlanned",
                false,
                "why",
                "pay-currency-has-no-quotation-and-does-not-circulate-locally"));
  }

  /**
   * ★★ <b>D-031：借款人侧无额度时，一个家户在"目标缺口 + 需求缺口"上的全额挂单量</b>（毫商品）。
   *
   * <p>现金撮合仍只按真实预算付钱；这里挂出来的全部缺口由 {@code executeTrade} 的"未付价款 ⇒ 对该卖方的债务"补 （W2-fix4
   * 起，市场信用撮合已整段退役）。经营者不参与债务式清算（{@code DebtContract} 两端只能是家户） ⇒ 仍走 {@link #allocateQuantity}
   * 的现金封顶口径。
   */
  static long desiredQuantity(
      long baseTarget, List<Long> demandParts, long available, long incoming) {
    if (baseTarget < 0L) {
      throw new IllegalArgumentException("目标量不得为负: " + baseTarget);
    }
    long covered = Math.addExact(available, incoming);
    long baseGap = Math.max(0L, baseTarget - covered);
    long residualCover = Math.max(0L, covered - baseTarget);
    long quantity = baseGap;
    for (long part : demandParts) {
      if (part < 0L) {
        throw new IllegalArgumentException("需求分段不得为负: " + part);
      }
      long unmet = Math.max(0L, part - residualCover);
      residualCover = Math.max(0L, residualCover - part);
      if (unmet > 0L) {
        quantity = safeAdd(quantity, unmet);
      }
    }
    return quantity;
  }

  /** 防溢出的加法：真的越过 {@code long} ⇒ 饱和到 {@link Long#MAX_VALUE}（后续 min/配给会自然截回）。 */
  static long safeAdd(long left, long right) {
    if (left <= 0L || right <= 0L) {
      return Math.addExact(left, right);
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /**
   * ★★ R4-E2：把"生活保留基线 + 有序需求分段"的缺口切进有限的买得起量。
   *
   * <p>顺序固定：① 基线缺口先拿；② 每个需求分段按调用方给的有序 list 逐个拿（缺口按"库存 + 在途"先抵基线、 再抵高优先级需求）；③ 每层只拿剩余额度。{@code
   * demandParts} 为空时逐值等于旧式 {@code min(max(0, target - available - incoming), affordable)}。
   */
  static long allocateQuantity(
      long baseTarget, List<Long> demandParts, long available, long incoming, long affordable) {
    if (baseTarget < 0L) {
      throw new IllegalArgumentException("目标量不得为负: " + baseTarget);
    }
    long covered = Math.addExact(available, incoming);
    long baseGap = Math.max(0L, baseTarget - covered);
    long residualCover = Math.max(0L, covered - baseTarget);
    long quantity = Math.min(baseGap, affordable);
    long remaining = affordable - quantity;
    for (long part : demandParts) {
      long unmet = Math.max(0L, part - residualCover);
      residualCover = Math.max(0L, residualCover - part);
      if (unmet <= 0L || remaining <= 0L) {
        continue;
      }
      long take = Math.min(unmet, remaining);
      quantity = Math.addExact(quantity, take);
      remaining -= take;
    }
    return quantity;
  }

  /** 某买方在某商品上、在时限前**确定到货**的在途量（买方自己的票，按 allocation 逐票求和）。 */
  static long confirmedIncoming(
      MarketRound round, ActorRef buyer, CommodityId commodity, long deadline) {
    long sum = 0L;
    for (ShipmentBatch batch : round.shipments.values()) {
      if (!batch.commodity().equals(commodity) || batch.arrivalTick() > deadline) {
        continue;
      }
      for (ShipmentAllocation allocation : batch.allocations()) {
        if (allocation.buyer().equals(buyer)) {
          sum += allocation.quantity();
        }
      }
    }
    return sum;
  }

  // ── P2f：土地订单的最小入口（注入 + 校验/排序；**不伪造生产需求**）────────────────────────────────

  /**
   * ★★★ <b>P2f：向本轮订单簿注入一张土地订单</b>（唯一入口；{@link MarketRoundCore#addLandOrder} 委托到这里）。
   *
   * <p>★ <b>它不生成需求</b>：土地卖/买意向的规模与生成属 P3（生产侧两遍编排）；P2f 只提供"写进同一本订单簿"的入口与撮合。 校验/排序在 {@link
   * #planLandOrders}（撮合入口的第一段）完成 —— 因此本方法只做 null 守卫，不静默丢弃任何订单。
   */
  static void putLandOrder(MarketRoundCore round, LandOrder order) {
    Objects.requireNonNull(round, "putLandOrder 的 round 不得为 null");
    Objects.requireNonNull(order, "putLandOrder 的 order 不得为 null（没有订单就不要调用）");
    round.landOrders.add(order);
  }

  /**
   * ★★★ <b>P2f：把本轮土地订单簿校验成"可撮合的工作集"</b>（只读；不改任何状态）。
   *
   * <pre>
   * ① 份额必须存在、是 LAND、quantity > 0、订单数量 == 份额数量（P2f 只支持整块）
   * ② 订单位置必须 == 份额位置；该格必须落在有市场表的区，且该区 hasLandPrice()（empty 不生成、不当免费）
   * ③ SELL 的主体必须是当前 owner；BUY 的主体不得已是 owner
   * ④ 两端主体都必须能解析到已登记家户（现金腿的唯一写口只接受家户账户）
   * ⑤ 同一块地的多张卖单只保留稳定序第一张（其余具名 DUPLICATE_SELL）
   * ⑥ 稳定排序：区 id → shareId → side → 主体 canonical → 订单稳定键（内容的纯函数，禁哈希序）
   * </pre>
   *
   * <p>★ 每条被跳过的订单都发 {@code LAND_ORDER_REJECTED}（INFO，具名原因）；返回值按上述全序冻结。★ 不写账户、不改产权、 不生成任何订单。
   */
  static List<LandOrder> planLandOrders(MatchContext ctx, List<LandOrder> candidates) {
    Objects.requireNonNull(ctx, "planLandOrders 的 ctx 不得为 null");
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<LandOrder> accepted = new ArrayList<>();
    Set<String> sellSharesSeen = new LinkedHashSet<>();
    for (LandOrder order : candidates) {
      if (order == null) {
        continue; // null 无法具名（没有块/主体）；入口 putLandOrder 已拒绝 null
      }
      OwnershipStake share = ctx.round.assetShares.get(order.shareId());
      if (share == null) {
        MarketLandBook.logRejected(
            ctx, order, MarketLandBook.LandRejectReason.SHARE_MISSING, "share-not-in-asset-shares");
        continue;
      }
      if (!MarketLandBook.tradableLand(share)) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.SHARE_NOT_LAND,
            "asset=" + share.asset() + ";quantity=" + share.quantity());
        continue;
      }
      if (order.quantityMilliMu() != share.quantity()) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.SHARE_MISMATCH,
            "orderQuantityMilliMu="
                + order.quantityMilliMu()
                + ";shareQuantityMilliMu="
                + share.quantity()
                + ";whole-block-only");
        continue;
      }
      HexCoord shareHex = MarketLandBook.hexOf(share);
      if (!shareHex.equals(order.location())) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.SHARE_LOCATION_MISMATCH,
            "orderLocation="
                + order.location().q()
                + ","
                + order.location().r()
                + ";shareLocation="
                + shareHex.q()
                + ","
                + shareHex.r());
        continue;
      }
      MarketTable table = MarketLandBook.tableFor(ctx, shareHex);
      if (table == null) {
        MarketLandBook.logRejected(
            ctx, order, MarketLandBook.LandRejectReason.NO_MARKET, "location-has-no-market-table");
        continue;
      }
      if (!table.hasLandPrice()) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.LAND_PRICE_EMPTY,
            "market=" + MarketLandBook.zoneIdOf(ctx, shareHex).value() + ";landPrice=empty");
        continue;
      }
      if (order.side() == LandOrder.Side.SELL) {
        if (!share.owner().equals(order.actor())) {
          MarketLandBook.logRejected(
              ctx,
              order,
              MarketLandBook.LandRejectReason.SELLER_NOT_OWNER,
              "seller=" + MarketLandBook.actorKey(order.actor()) + ";owner=" + share.owner());
          continue;
        }
        if (!sellSharesSeen.add(order.shareId().value())) {
          MarketLandBook.logRejected(
              ctx,
              order,
              MarketLandBook.LandRejectReason.DUPLICATE_SELL,
              "share-already-has-a-sell-order-this-round");
          continue;
        }
      } else if (share.owner().equals(order.actor())) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.BUYER_ALREADY_OWNER,
            "buyer-is-the-current-owner");
        continue;
      }
      if (!ctx.round.householdOfActor.containsKey(order.actor())) {
        MarketLandBook.logRejected(
            ctx,
            order,
            MarketLandBook.LandRejectReason.ACTOR_NOT_HOUSEHOLD,
            "actor=" + MarketLandBook.actorKey(order.actor()));
        continue;
      }
      accepted.add(order);
    }
    accepted.sort(
        Comparator.comparing(
                (LandOrder order) -> {
                  MarketZoneId zone = MarketLandBook.zoneIdOf(ctx, order.location());
                  return zone == null ? "" : zone.value();
                })
            .thenComparing(order -> order.shareId().value())
            .thenComparing(order -> order.side().ordinal())
            .thenComparing(order -> MarketLandBook.actorKey(order.actor()))
            .thenComparing(LandOrder::stableKey));
    return List.copyOf(accepted);
  }
}
