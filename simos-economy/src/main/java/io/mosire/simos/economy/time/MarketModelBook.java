package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.PROCUREMENT_RANK_ABSENT;
import static io.mosire.simos.economy.time.MarketMatchSupport.FillIntent;
import static io.mosire.simos.economy.time.MarketMoneyBook.buyerUnitPriceFor;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketPricer.regionIdAt;
import static io.mosire.simos.economy.time.MarketRosterBook.costEstimateOf;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.TradeRoute;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketModelBook {

  private MarketModelBook() {}

  // ── 内部数据结构 ───────────────────────────────────────────────────────────────────

  /** 一条买订单的撮合槽（冻结、剩余、限价阻塞原因都在这里）。 */
  static final class BuySlot {
    final BuyOrder order;
    final Participant buyer;
    final HexCoord hex;
    final MarketRegion region;

    /**
     * ★★ <b>3c：本槽的支付币 = {@link BuyOrder#payWith()}（唯一真值在订单）</b> —— 构造期赋值一次、此后无写点，
     * 因此它只是同一事实的槽位视图，不是第二套币种真值。
     *
     * <p>★★ <b>改前它是"本格计价币"</b>（{@code market.numeraire()}，"付哪种钱由格决定"）；3c 起由订单决定， 缺省（订单不带币）仍是本格计价币 ⇒
     * 旧世界逐值不变（I-C2）。它驱动：预算/可花额（{@link #spendableMoneyOf}）、 冻结轴（{@code (buyer,
     * currency)}）、限价与可负担量的折算、以及结算的钱腿（{@code Map.of(buy.currency, payment)}）。 ★
     * 价格的<b>尺度</b>不在这里：单价仍由卖方格价表的计价币给出（计划 §2.1 冻结）。
     */
    final CurrencyId currency;

    /**
     * ★★ <b>P-T5b：本槽的"钱的价"—— 微本格计价币 / 毫支付币</b>（建槽位时冻结一次，此后只读；worker 副本逐值照抄）。
     *
     * <pre>
     * 支付币 == 本格价表的计价币 ⇒ 1000（面值 1:1，R3 的锚；结构性，不查任何表）
     * 否则                      ⇒ CurrencyValuation.valuationMicro(本格计价币, 支付币, 本格所在区)
     *                              （世界报价 / 当地实际流通 ⇒ 面值；两者都不是 ⇒ 0 = 说不出价）
     * </pre>
     *
     * <p>★★ <b>为什么冻结在槽位上而不是每次现算</b>：撮合是并行 worker 与协调器回放<b>跑同一套算式</b>的两条路径， 凡是判据里用到的价都必须是槽位的冻结事实（与
     * {@link SellSlot#reservationMicro} 同一条纪律）；且它一经确定就不该在 一轮撮合中途变化。★ {@code 0} = 说不出价 ⇒ {@link
     * #payAmountOf} 返回 {@code -1}，调用方 fail-closed（禁 1:1）。
     */
    final long payValueMicro;

    /**
     * ★★ <b>P-T5b：本槽限价的"买方支付币"口径</b>（毫支付币 / 商品单位）：{@code ⌈限价 × 1000 ÷ payValueMicro⌉}。
     *
     * <p>★ <b>为什么需要它</b>：{@link BuyOrder#maxLandedPrice()} 的量纲仍是<b>本格价表的计价币</b>（计划 §2.1 冻结：
     * 一格一张价表、一个尺度），而撮合里与它比较的 {@code buyerUnitPrice} 是<b>买方支付币</b>的单价（3c 的 {@code
     * settlementUnitPrice}）—— 异币时两者不能直接比大小。故建槽位时折一次、只存这一个口径。
     *
     * <p>★ 同币时它与 {@code maxLandedPrice} <b>逐值相同</b>（{@code ⌈x×1000÷1000⌉ = x}）⇒ 旧世界逐值不变。 ★ {@code
     * -1} = 说不出价（哨兵：任何 ≥ 0 的真价格都比它大 ⇒ 限价不过；正常路径上不可达，因为订单生成侧已先拒）。
     */
    final long limitInPayCurrency;

    /**
     * ★★ <b>D-027：本槽所属区的规范 id</b>（= {@code region.node().nodeId()}）—— 有效参考价/区级调控按它查 （单区里就是 {@code
     * "single-region"}）。★ 它只用于价格口径，不改槽位的其他语义。
     */
    final String regionId;

    /** 在 {@code ctx.buys} 里的全局下标（worker 的 FillIntent 用它定位回放目标）。 */
    int orderIndex;

    long remaining;
    long frozenRemaining;
    long baseFrozenMoney;
    long requestedMoney;
    long spentMilli;
    boolean noMoney;
    MarketUnfilledReason blocked;

    /**
     * ★★★ <b>W5.5（2026-10-11，S-12②）：本槽从<b>货币市场借到的钱</b>补进订单预算的那一份</b>（毫本槽支付币）。
     *
     * <pre>
     * 为什么必须有它：订单里的 {@code Budget} 是**下单当刻**的快照，而按用户裁定的次序（「在挂商品的时候最后挂钱，
     * 买东西的时候先借钱」）**借钱发生在下单之后** ⇒ 若不把借到的那一笔补进预算，{@code payableMoneyOf} 里的
     * "订单预算余额"那一项对现金为 0 的家户恒为 0 ⇒ **借到的钱在现金腿上不可用**，整条货币腿空转
     * （而这正是用户要的那件事：「先借钱再买货，然后后面不就有钱了？」）。
     * </pre>
     *
     * <p>★ 由货币腿交出物（{@link MoneyMarketSettlement.Round#borrowedByHousehold()}）经 {@link
     * #applyMoneyMarketBudgetTopUps} 写一次；此后只读。★ <b>它不是第二本账</b>：金额就是借方那一笔借款的
     * 本金（同一事实的槽位视图），借方的账户余额那一侧由唯一写口 {@code applyTransfer} 落账。
     */
    long borrowedBudgetTopUpMilli;

    /**
     * ★★ <b>F-2（2026-10-10）：本槽"卡在运力"的**具名归因档**</b>（{@link MerchantCapacityPool.LogisticsBlock}
     * 的具名字面量）—— 与 {@link #blocked}{@code == LOGISTICS_CAPACITY} **同一写点**落定（两者不可能漂开）。{@code null} =
     * 不适用。
     *
     * <p>★ 它只作读数（进 {@link MarketReport.Unfilled#logisticsDetail()} 与逐轮 INFO 分档），不改任何判据、不进账本、不落状态。
     */
    String logisticsDetail;

    /**
     * ★★ <b>M-A1（V-20）：本槽被运力截断的量</b>（毫商品）—— 与 {@link SellSlot#capacityTruncatedMilli} 对称：
     * 本轮因运力未获服务的量不进喂给自适应定价的 demand 统计（被截断的部分不提价；两侧都剔）。
     */
    long capacityTruncatedMilli;

    /**
     * ★★ <b>P-T1d：本槽在它那张挂单簿（市场区 × 商品 × 买侧）里的<b>撮合名次</b></b>（0 起，越小越先）。
     *
     * <p>★ 由撮合前的置顶 pass（{@code applyProcurementPriority}）一次写定：<b>没有</b>政府要求管控时它恒为 {@link
     * #PROCUREMENT_RANK_ABSENT}（不排序 ⇒ 逐值退回改前的列表序）；有管控时 = 该簿"置顶后"的位次（政府单被移到最前， 同政府多单保持 canonical
     * 序）。★ <b>全序</b>：同簿内两两不同（I7：排序键是内容的纯函数，不是迭代序）。
     */
    long procurementRank = PROCUREMENT_RANK_ABSENT;

    /**
     * ★★ <b>P-T1d：本槽这一轮是否<b>真的</b>被置顶过</b>（超越 ≥ 1 户 ⇒ {@code true}）。
     *
     * <p>★ 缺口径 = "剩余挂单按正常次序参加撮合"（设计书 §17.3-④）：行政力见底后没被推进的政府单保持 {@code false}，
     * 它既不带自己的档、也不该因"属于受管控政府"而白拿优先。★ 由置顶 pass 写、只读；撮合只用它切"档"（不改任何价格/量算式）。
     */
    boolean procurementOvertook;

    BuySlot(
        BuyOrder order,
        Participant buyer,
        HexCoord hex,
        MarketRegion region,
        MarketTable market,
        MarketPayChoice payChoice) {
      this.order = order;
      this.buyer = buyer;
      this.hex = hex;
      this.region = region;
      this.currency = order.payWith();
      this.regionId = region.node().nodeId();
      this.remaining = order.quantity();
      // ★★ P-T5b：本槽的两个折算冻结值（同一份 CurrencyValuation、各折**一次**；见字段注）。
      this.payValueMicro = payChoice.valueMicroOf(market, this.currency, hex);
      //   ★ 面值（同币 / 当地流通 ⇒ 1:1）⇒ 限价原样：不做乘除 ⇒ 旧世界逐值不变是"一条都不算"。
      this.limitInPayCurrency =
          this.payValueMicro == CurrencyValuation.faceValueMicro()
              ? order.maxLandedPrice()
              : (this.payValueMicro <= 0L
                  ? -1L
                  : buyerUnitPriceFor(order.maxLandedPrice(), this.payValueMicro));
    }

    /**
     * ★★ <b>P-T5b：毫本格计价币 → 毫本买方支付币</b>（{@link #payValueMicro} 的消费口；同币 ⇒ 原样）。
     *
     * <p>★ <b>"面值"直接原样返回（不做乘除）</b>：{@code payValueMicro == 1000} 时 {@code ⌈x×1000÷1000⌉ = x} 是恒等式，
     * 提前返回既省掉一次乘除、也让<b>旧世界逐值不变</b>成为"一条都不算"而不是"算出来恰好相等"（同币/当地流通 ⇒ 面值）。
     *
     * @return {@code >= 0} = 折算后的支付币金额；{@code -1} = 说不出这种钱的价（调用方 fail-closed，禁按 1:1 顶上）
     */
    long payAmountOf(long numeraireMilli) {
      if (numeraireMilli <= 0L) {
        return 0L;
      }
      if (payValueMicro == CurrencyValuation.faceValueMicro()) {
        return numeraireMilli; // 面值 1:1（同币 / 当地流通）⇒ 原样
      }
      if (payValueMicro <= 0L) {
        return -1L;
      }
      return buyerUnitPriceFor(numeraireMilli, payValueMicro);
    }

    /** ★ worker 的本区副本：状态照抄，后续只改副本（不触共享槽位）。 */
    BuySlot(BuySlot other) {
      this.order = other.order;
      this.buyer = other.buyer;
      this.hex = other.hex;
      this.region = other.region;
      this.currency = other.currency;
      this.regionId = other.regionId;
      // ★★ P-T5b：两个折算冻结值逐值照抄（worker 与协调器必须给出同一个价，否则回放对不上）。
      this.payValueMicro = other.payValueMicro;
      this.limitInPayCurrency = other.limitInPayCurrency;
      this.orderIndex = other.orderIndex;
      this.remaining = other.remaining;
      this.frozenRemaining = other.frozenRemaining;
      this.baseFrozenMoney = other.baseFrozenMoney;
      this.requestedMoney = other.requestedMoney;
      this.spentMilli = other.spentMilli;
      // ★★ W5.5：借款补进的预算逐值照抄（worker 与协调器必须给出同一个可付额，否则回放对不上）。
      this.borrowedBudgetTopUpMilli = other.borrowedBudgetTopUpMilli;
      this.noMoney = other.noMoney;
      this.blocked = other.blocked;
      this.capacityTruncatedMilli = other.capacityTruncatedMilli;
      // ★★ P-T1d：置顶名次与"是否真被推进"逐值照抄（worker 副本与协调器必须用同一套顺序，否则回放对不上）。
      this.procurementRank = other.procurementRank;
      this.procurementOvertook = other.procurementOvertook;
    }
  }

  /** 一条卖订单的撮合槽。 */
  static final class SellSlot {
    final SellOrder order;
    final Participant seller;
    final HexCoord hex;
    final MarketTable market;
    final MarketRegion region;

    /**
     * ★★ <b>3c：本槽<b>声明</b>"收哪种币" = {@link SellOrder#receiveWith()}（唯一真值在订单，构造期赋值一次）</b>。
     *
     * <p>★★ <b>语义（改前 → 改后）</b>：A2a 时代它是"卖方只收这一种钱"（不等 ⇒ 具名拒 {@link
     * MarketUnfilledReason#CURRENCY_MISMATCH}）；E 批之后它降为"本格默认收款币 = 本格价表的计价币"；<b>3c 起它由订单给出</b>；P-T5b
     * 起它带的是"卖方自己的最强持有币"。
     *
     * <p>★★ <b>P-T1e 裁定：它是<b>声明/读数</b>，不是过滤器</b>——"买方付的币能不能成交"由<b>口岸的币种挂单闸</b>回答（判定唯一拼写点 = {@link
     * #acceptsCurrency}，规则键 =（币种, 挂单类型, 方向））。把它当接受集会等于给<b>未设政策</b>的世界加新限制（违反 I-C2），
     * 且"卖家只收自己最强的那种钱"这条语义从未被裁定。★ 钱腿永远铸<b>买方支付币</b>（{@code executeTrade}）⇒ 本字段只进日志/读数。★
     * 卖方对钱"值多少"的判断锚在<b>本格计价币</b>（{@link #market}{@code .numeraire()} = 卖方自己的钱、 保留价 {@link
     * #reservationMicro} 的量纲、E 批 R1/R3 的"家户按自己的货币估值"）；价格尺度也不动 （计划 §2.1：一格一张价表、一个尺度）—— 本字段不参与估值折算。
     */
    final CurrencyId receiveCurrency;

    /**
     * ★★ <b>E：卖方对本次出售的那件商品的保留价</b>（微本格计价币 / 商品单位；与 {@link HouseholdValuationBook#MICRO_PER_MILLI}
     * 同微刻度）。
     *
     * <p>★ <b>为什么在<b>建槽位时</b>算一次并冻结</b>：成交判据（买方付出金额 × 我对该币的估值 ≥ 我的保留价 × 数量）必须在<b>worker
     * 的区内副本</b>与<b>协调器回放</b>上给出同一个答案（{@code replayRegionOutcome} 会复算 {@code
     * executeTrade}，槽位状态漂开就具名抛）——若在撮合中途读库存现算，两边的库存副本已经不同。 故它取自<b>本轮计划快照</b>（{@code ordersFor}
     * 用的同一份），随槽位（含副本）一起走。
     *
     * <p>★ {@code 0} = 没有保留价（经营者没有家户行 / 没有价表 / 未定价 / 明确 0 价）⇒ 判据不新增门槛。 ★
     * 算式与套利用的保留价<b>同一个拼写点</b>（{@link HouseholdValuationBook#reservationMicro}）。
     */
    final long reservationMicro;

    /**
     * ★★ <b>D-027：本槽所属区的规范 id</b>（= {@code region.node().nodeId()}）—— 有效参考价按它查区级调控 （单区里就是 {@code
     * "single-region"}）。★ 它只用于价格口径，不改槽位的其他语义。
     */
    final String regionId;

    /**
     * ★★ <b>S3：卖方单位成本估计</b>（{@link ProducerCostBook}；唯一拼写点）—— 同商品同区/邻区的撮合按 {@code unitCostEstimate
     * + freightPerUnit} 升序选卖方；同成本按 {@link #costTieBreak} 稳定升序。
     *
     * <p>★ 成本只在协调器建槽位时算一次（纯函数、只读），worker 副本逐值照抄 ⇒ 1/4/8 线程同序。
     */
    final ProducerCostBook.Estimate costEstimate;

    /** 同成本层的 canonical tie-break（{@code hex|actorKind|actorId}；内容的纯函数）。 */
    final String costTieBreak;

    /** 在 {@code ctx.sells} 里的全局下标（worker 的 FillIntent 用它定位回放目标）。 */
    int orderIndex;

    long remaining;
    long frozenRemaining;
    long baseFrozenGoods;

    /** ★★ 2026-10-09：本槽剩余是否卡在"承运运力不足"（路线窗口/逐 hex 运力池）。 */
    boolean capacityBlocked;

    /**
     * ★★ <b>F-2（2026-10-10）：本槽"卡在运力"的**具名归因档**</b>（{@link MerchantCapacityPool.LogisticsBlock}
     * 的具名字面量）—— 与 {@link #capacityBlocked} **同一写点**落定。{@code null} = 不适用。
     *
     * <p>★ 它只作读数（进 {@link MarketReport.Unfilled#logisticsDetail()} 与逐轮 INFO 分档），不改任何判据、不进账本、不落状态。
     */
    String logisticsDetail;

    /**
     * ★★ <b>M-A1（V-20）：本槽被运力截断的量</b>（毫商品）—— 它在**真正因运力拦下**的三处累加（发货格无运力 / 分配不足 / 路线窗口容量用尽），由 {@code
     * adaptPrices} 从喂给自适应定价的 demand/supply 里逐槽位扣除： <b>被截断的部分不提价、不压价</b>（两侧都剔，用户 2026-10-10 裁定 + 计划
     * V-20）。
     *
     * <p>★ 它<b>不是</b>状态：只在本轮内存里累加、只服务本轮定价统计，不落盘、不进报告金额。
     */
    long capacityTruncatedMilli;

    /**
     * ★★ <b>P-T1a：本槽剩余是否卡在"跨区口岸闸"</b>（两侧两道闸的可通过量用尽；见 {@link PortThrottle}）—— 与 {@link
     * #capacityBlocked} 对称的一档：有货、有路、有运力，但<b>法律不许过</b>（设计书 §4.5：口岸属法律规定层）。 归因口径见 {@link
     * #sellerReason}（{@link MarketUnfilledReason#PORT_THROTTLED}）。
     */
    boolean portBlocked;

    /**
     * ★★ <b>卖方槽位的具名拒因</b>—— 与 {@link BuySlot#blocked} 对称：优先级高于"被谁挤掉"这类泛化归因 （见 {@link
     * #sellerReason}）。{@code null} = 没有具名拒因。
     *
     * <p>★ <b>E（2026-10-09）起它装的是市场性理由</b>（{@link MarketUnfilledReason#PRICE_LIMIT}：说不出这个价 /
     * 折回本币不够保留价），不再装 A2a 的制度性 {@link MarketUnfilledReason#CURRENCY_MISMATCH} （该档已退役，不再由本结算产生）。
     */
    MarketUnfilledReason blocked;

    /**
     * ★★ <b>P-T1d：本槽在它那张挂单簿（市场区 × 商品 × 卖侧）里的<b>撮合名次</b></b>（0 起，越小越先）。
     *
     * <p>★ 由撮合前的置顶 pass 一次写定；<b>没有</b>政府要求管控时恒为 {@link #PROCUREMENT_RANK_ABSENT}（不排序 ⇒ 撮合仍走既有
     * {@code costOrder}）⇒ 逐值不变。有管控时 = "成本序 + 置顶推进"后的位次（政府单被推到最前、同政府多单保持 canonical 序）。
     *
     * <p>★ 为什么卖侧的名次能替代 {@code costOrder(route)}：{@link ProducerCostBook#landedCostMilli} 对单位成本是
     * <b>加常数（运费×1000）</b>⇒ 同一条路线上按到货成本排序 ≡ 按单位成本估计排序 ⇒ 成本序与车道无关， 名次可以在一张簿上算一次、所有车道/分层共用（详见 {@code
     * applyProcurementPriority} 的注）。
     */
    long procurementRank = PROCUREMENT_RANK_ABSENT;

    /**
     * ★★ <b>P-T1d：本槽这一轮是否<b>真的</b>被置顶过</b>（超越 ≥ 1 户 ⇒ {@code true}；口径见 {@link
     * BuySlot#procurementOvertook}）。
     */
    boolean procurementOvertook;

    SellSlot(
        SellOrder order,
        Participant seller,
        HexCoord hex,
        MarketTable market,
        MarketRegion region,
        MarketRound round) {
      this.order = order;
      this.seller = seller;
      this.hex = hex;
      this.market = market;
      this.region = region;
      this.regionId = region.node().nodeId();
      // ★★ 3c：收款币来自**订单**（SellOrder.receiveWith），不再硬绑本格计价币。
      this.receiveCurrency = order.receiveWith();
      this.reservationMicro = reservationMicroOf(round, seller, market, order.commodity());
      this.remaining = order.sellable();
      this.costEstimate = costEstimateOf(round, market, seller, order.commodity());
      this.costTieBreak = ProducerCostBook.canonicalKey(hex, seller.actor());
    }

    /** ★ worker 的本区副本：状态照抄，后续只改副本（不触共享槽位）。 */
    SellSlot(SellSlot other) {
      this.order = other.order;
      this.seller = other.seller;
      this.hex = other.hex;
      this.market = other.market;
      this.region = other.region;
      this.regionId = other.regionId;
      this.receiveCurrency = other.order.receiveWith(); // ★ 3c：与订单同源（订单是唯一真值）
      this.reservationMicro = other.reservationMicro; // ★ E：保留价是槽位的冻结事实（副本逐值照抄）
      this.blocked = other.blocked;
      this.costEstimate = other.costEstimate;
      this.costTieBreak = other.costTieBreak;
      this.orderIndex = other.orderIndex;
      this.remaining = other.remaining;
      this.frozenRemaining = other.frozenRemaining;
      this.baseFrozenGoods = other.baseFrozenGoods;
      // ★★ P-T1d：置顶名次与"是否真被推进"逐值照抄（worker 副本与协调器必须用同一套顺序，否则回放对不上）。
      this.procurementRank = other.procurementRank;
      this.procurementOvertook = other.procurementOvertook;
    }
  }

  /**
   * ★★ <b>E：卖方槽位的保留价（微本格计价币 / 商品单位）</b>——建槽位时从本轮计划快照算一次（{@link SellSlot#reservationMicro}
   * 的注写了为什么必须冻结）。
   *
   * <p>★ 可动库存 = 持有 − 已冻结（与 {@code ordersFor} 的可卖余量同源），<b>不是</b>卖单余量：判据问的是"这批货 对我值多少"，不是"我挂了多少"。★
   * 经营者（{@code household == null}）没有为它记价的账 ⇒ 返回 0（不新增门槛）。
   */
  static long reservationMicroOf(
      MarketRound round, Participant seller, MarketTable market, CommodityId commodity) {
    if (seller.household() == null) {
      return 0L;
    }
    long movable =
        Math.max(0L, stockOf(round, seller, commodity) - frozenGoodsOf(round, seller, commodity));
    return HouseholdValuationBook.reservationMicro(
        market,
        commodity,
        seller.household(),
        round.householdEconomies.get(seller.household()),
        movable,
        HouseholdValuationBook.NO_TRADE_HISTORY);
  }

  /**
   * 一条路线的运输参数（区内即时 {@code null}）；{@code freightRatePerMille} 是 {@link
   * MarketTopology#freightPerMilleBetween(HexCoord, HexCoord)} 给出的唯一费率来源。
   */
  record RouteContext(
      HexCoord from,
      HexCoord to,
      CommodityId commodity,
      long unitPrice,
      long freightRatePerMille,
      long freightPerUnit,
      long travelTicks,
      long costPerUnit,
      long capacityPerWindow,
      int lossPerMille,
      long arrivalTick,
      boolean immediate,
      boolean haulService,
      long servicePriceMilli) {

    TradeRoute toRoute() {
      return new TradeRoute(from, to, capacityPerWindow, travelTicks, costPerUnit, lossPerMille);
    }
  }

  /** 在途批次的合并键（起终点 + 商品 + 到达日；同键的票合并进同一批）。 */
  record ShipmentKey(HexCoord from, HexCoord to, CommodityId commodity, long arrivalTick) {}

  /** 在途批次的施工坞（不可变 record 的合并先在这里做）。 */
  static final class ShipmentBuilder {
    final RouteContext route;
    long quantity;
    final List<ShipmentAllocation> allocations = new ArrayList<>();
    String shipmentId;

    ShipmentBuilder(RouteContext route) {
      this.route = route;
    }
  }

  /** 一条路线的运力用量累计。 */
  static final class RouteAccumulator {
    final HexCoord from;
    final HexCoord to;
    final CommodityId commodity;
    final long capacityPerWindow;
    final long costPerUnit;
    long used;
    long demandMilli;
    long supplyMilli;
    boolean bottleneck;

    RouteAccumulator(
        HexCoord from,
        HexCoord to,
        CommodityId commodity,
        long capacityPerWindow,
        long costPerUnit) {
      this.from = from;
      this.to = to;
      this.commodity = commodity;
      this.capacityPerWindow = capacityPerWindow;
      this.costPerUnit = costPerUnit;
    }
  }

  /** 一轮撮合的施工坞（报告、冻结、在途、阻塞原因都收在这里）。 */
  static final class MatchContext {
    final MarketRound round;
    final Map<MarketZoneId, MarketTable> marketTables;

    /**
     * ★★ <b>W1：本区的价表</b> —— 区 id 与拓扑节点 id 是<b>同一个 id 空间</b>（持久形态 = {@code MarketZone.zoneId}； 派生形态
     * = 价表键，见 {@code MarketTopology.ofRegions} 的装配口径）。
     *
     * <p>★★ W1d：区没有价表条目 ⇒ {@code null}（缺表 = 该区不交易任何商品）。所有调用方都已按 {@code null} 跳过该区（{@code
     * matchWithinRegions} / {@code matchWithinRegionsSerial} / {@code matchWithinRegionsParallel}）。
     */
    MarketTable tableOf(MarketRegion region) {
      return marketTables.get(new MarketZoneId(region.node().nodeId()));
    }

    /**
     * ★★ <b>W1：本格的价表</b>（"这一格按哪张表报价"）—— 不在任何市场里（拓扑不覆盖）⇒ {@code null}。
     *
     * <p>★ 它逐值替掉改动前那一族 {@code marketTables.get(hex)}：新形状下"本格价表"就是"本格所属市场的
     * 那一张表"，而区内任何格共用一张（这正是"每区一张"的含义）。
     */
    MarketTable tableAt(HexCoord hex) {
      if (hex == null || topology == null || !topology.contains(hex)) {
        return null;
      }
      return tableOf(topology.regionOf(hex));
    }

    final MarketTopology topology;

    /** ★★ <b>W4：开盘前结算序快照</b>（家户名次 = 资产 − 负债净值；区名次 = 上周期名义成交额）。禁自指：只在盘前构造一次。 */
    final SettlementOrderBook ordering;

    /** ★★ <b>W4：本轮的区遍历序</b>（见 {@code orderRegions}；撮合与运力池消耗都按它走）。 */
    final List<MarketRegion> orderedRegions;

    /**
     * ★★ <b>本轮区内市场规则</b>（{@link MarketRound#regulation()} 的唯一读取点；逐轮瞬态、不落盘）。P-T1c 起它只剩"区内税"一类条目 ⇒ 只有
     * {@link MarketRegulation#anchor()} 所在的区受它约束；未锚定的区走默认路径（无税）。
     */
    final MarketRegulation regulation;

    /** ★★ D-027：单 hex 贸易成本的纯策略（只读拓扑现算；逐轮瞬态，不进状态）。 */
    final HexTradeCost hexTradeCost;

    /**
     * ★★ <b>D-027：逐票区级税费读数</b>（fill → 单位税费，毫计价货币/商品单位；只服务 {@link MarketReport#withRegulatedTariff}
     * 的只读聚合）。★ P-T1b 起它<b>同时</b>是"区内市场税"真收款的税率来源（{@link #tariffPerUnitOf}）；读数照旧累加
     * （读口一字不改），此外每一笔真收的钱另进 {@link #taxItems}。
     */
    final Map<MarketReport.Fill, Long> tariffByFill = new LinkedHashMap<>();

    /**
     * ★★ <b>P-T1b：本轮逐层税项（真收的账目）</b>—— 层 / 收款政府 / 币种 / 金额，逐笔按层序累加（保序）。
     *
     * <p>★★ <b>它与 {@link #tariffByFill} 的分工</b>：{@code tariffByFill} 是"费率 × 量"的<b>读数</b>（P-T4 口径，
     * 卖方计价币）；{@code taxItems} 是"<b>真的从买方账上搬进国库</b>"的逐项账目（买方支付币）。两者数额在"同币 + 单一政府"时一致，在多币/多政府下会不同 ——
     * 读账以本表为准（{@code MarketReport.taxByCurrency()}）。
     *
     * <p>★ 分区分并行的语义：worker 的本地 ctx 也会累加（它跑的是同一条 {@code executeTrade}），但**交回时丢弃** （与 {@code
     * fills}/{@code ledger} 同一条纪律）—— 协调器回放时在全局 ctx 上重铸，读数只可能来自协调器那一份。
     */
    final List<MarketReport.TaxItem> taxItems = new ArrayList<>();

    /** ★★ P-T1c：区 id → 该区适用的区内市场规则（每轮按区只读一次；未锚定/未定义 ⇒ 空表）。 */
    final Map<String, MarketRegulation> regulationByRegionId;

    /**
     * ★★ <b>E（2026-10-09）：本轮"钱的价格"</b>（世界行情 + 当地实际流通的币种）—— 成交判据（{@link
     * #settlementUnitPrice}）的唯一取价处。★ 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘（I17）。
     *
     * <p>★ <b>为什么由协调器统一装配并传给 worker 副本</b>：它含"哪些钱在当地流通"（依赖本轮<b>全部</b>家户的 货币账户），而 worker 的区副本只带走本区家户
     * ⇒ 各自现算会漂开，进而在"认不认得出这种钱"上给出不同答案 （回放对不上就具名抛）。故一份实例贯穿本轮。
     */
    final CurrencyValuation currencyValuation;

    /**
     * ★★ <b>M-A1：本轮逐 hex 运力池</b>（派生量，见 {@link MerchantCapacityPool}）—— 承运选择的唯一权威。 ★ 池空 =
     * 世界里没有"选了跑商的家户" ⇒ 跨格货走不动；同格成交不受影响。
     */
    final MerchantCapacityPool carrierPool;

    /**
     * ★★ <b>M-A2：本轮运力需求簿</b>（逐份需求 + 逐 hex / 逐区汇总 + 缺口；见 {@link CapacityDemandBook}）。
     *
     * <p>★ 只在协调器单线程路径上写（唯一写入点是 {@code executeTrade} 的跨格分支，而它只在"有运力池 ⇒ 协调器串行 撮合"时到达；worker 的 {@code
     * executeTrade} 恒传 {@code route == null}）⇒ 与 {@code taxItems}/{@code fills} 同一条纪律：worker
     * 本地副本上的累加在交回时丢弃，只从协调器那一份出日志/读数。
     */
    final CapacityDemandBook capacityDemands = new CapacityDemandBook();

    /**
     * ★★ <b>A2：本轮运输服务**交付量**的读数</b>（毫服务 = 毫商品·程；只作日志/读数，<b>不落状态</b>）。
     *
     * <p>★ 与 {@code taxItems}/{@code capacityDemands} 同一条纪律：只在**协调器**路径上写（worker 副本上的是本地累加， 交回时丢弃）⇒
     * 汇总只可能来自协调器那一份。★ 服务不成市的世界恒为 0 ⇒ 汇总行一条不打（缺省语义中性，I-H3）。
     *
     * <p>★★ A5：它**含被免运费 / 自承运的条目**（服务照跑、只是不收钱）⇒ 与 {@link #haulServiceTrades} 是两个量， 绝不能合成一个（A4 账本
     * §5-2 的 33.4% 漏报正是"只报 trades 一起的那份"造成的）。它随 {@link HaulServiceRound} 一起交出去。
     */
    long haulServiceSoldMilli;

    // ★★★ A5（2026-10-10）：这里原有 {@code final Map<CurrencyId, Long> haulServicePaidByCurrency} ——
    //   它是"本轮服务成交金额（逐币）"的第二份事实。A5 撤掉它：同一批金额已由 {@code carrierPool.recordFee}
    //   按**家户**逐笔记下（{@link MerchantCapacityPool#freightEarnedByHousehold()}），按币求和即得同一个逐币总额
    //   ⇒ 留着就是同一件事的第二处拼写（本仓最反对的"两个状态混成一个"的镜像：一份事实两个账）。

    /** ★★ A2：本轮服务成交的服务腿条数（= 铸出去的服务钱腿条数；免运费/自承运条目不计）。 */
    long haulServiceTrades;

    /**
     * ★★ A2：本轮因"服务货在交付点取不到"而放弃的成交笔数 —— <b>契约故障</b>（池只从当刻现货里分配，交付点却取不到货 ⇒ 跨切片一致性故障）。★ 它必须为 0；非 0
     * 时每条都发 ERROR（不降级）且本笔不成交（fail-closed，见 {@code deliverHaulService}）。
     */
    long haulServiceDeliveryFaults;

    // ★★★ A3（2026-10-10）：这里原有 {@code final MerchantProfitBook merchantProfits =
    //   new MerchantProfitBook();} —— **已整族退役**（T-H3）：跑商收益走标准企业利润
    //   {@code EnterpriseProfitBook}（服务成交的钱腿 reason = {@code MARKET_TRADE}，按卖方组织归集），
    //   不再有第二本"商号利润"账，也不再在这些逐笔落点记影子腿。

    /**
     * ★★ <b>M-C：本轮的纯商号家户集合</b>（H-2；免运费判据 H-A/H-G 的**范围**）。
     *
     * <p>★ 由 {@code EconomySettlement} 从 {@code classMemberships × classPositions} 现算（判据唯一拼写点 =
     * {@code MerchantIdentity}）；{@code Set.of()} = 没有纯商号 ⇒ 谁都不豁免 ⇒ 逐值退回 M-A2（缺省中性）。 ★ 与 {@code
     * recordFillIntents} 同款：构造后由入口赋值（worker 副本照抄），不改构造器签名。
     */
    Set<HouseholdId> pureMerchantHouseholds = Set.of();

    /**
     * ★★ <b>W2：本轮"未付价款记为债务"的轮级读数</b>（毫买方支付币 / 笔数）—— 只作日志与读数，不落状态 （债务本身落在 {@code debtContracts}）。
     */
    long deferredPaymentMilli;

    /** 见 {@link #deferredPaymentMilli}（本轮发生了赊购的成交笔数）。 */
    int deferredPaymentFills;

    /**
     * ★★ <b>W2：本轮的持久市场区表</b>（{@code MarketZoneId → MarketZone}）—— 只被自适应定价读一个字段： {@link
     * MarketZone#priceAlphaPerMille()}（逐区动态价步长 α，约束设计书 §2.1）。
     *
     * <p>★ 由 {@code clearOncePerCycle} 的入口参数赋值（照 {@link #pureMerchantHouseholds} 的先例：构造后赋值、 worker
     * 副本照抄、不改构造器签名）。{@code Map.of()} = 没有持久区（派生形态 / 夹具）⇒ α 一律取出厂缺省 {@link
     * MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}（缺省语义中性：没设过 α 的世界走 50‰）。
     */
    Map<MarketZoneId, MarketZone> marketZones = Map.of();

    final List<BuySlot> buys = new ArrayList<>();
    final List<SellSlot> sells = new ArrayList<>();
    final Map<ActorRef, Participant> participants = new LinkedHashMap<>();
    final Map<ActorRef, HexCoord> participantHex = new LinkedHashMap<>();
    final List<MarketReport.Fill> fills = new ArrayList<>();
    final List<MarketReport.Unfilled> unfilled = new ArrayList<>();
    // ★ S3：逐槽位只读结果（不落盘；供 MarketReadout / ApiViews 聚合）。
    final List<MarketReport.SellerOutcome> sellerOutcomes = new ArrayList<>();
    final List<MarketReport.BuyerOutcome> buyerOutcomes = new ArrayList<>();
    // ★★ W2：债务式清算的入参（来自 MarketRound；没有债务工作副本时为空 ⇒ 未付价款无处记 ⇒ 退回旧口径）。
    final MarketRound.DeferredPaymentConfig deferredPaymentConfig;
    final Map<DebtContractId, DebtContract> debts;

    final Map<String, RouteAccumulator> routes = new LinkedHashMap<>();

    /**
     * ★★ <b>D-1b：本轮"因运力未获服务的量"的净额记账簿</b>（{@link LaneUnservedBook}）。
     *
     * <p>三处运力截断落点（承运分配不足 / 整条拦下 / 路线窗口预算用尽）共享的同一份"已记"状态：同一份未服务量只记一次，
     * 免得截断读数超过该槽真正未服务的量、把<b>已服务</b>的那一份也剔出价格统计（V-20 幅度错）。
     *
     * <p>★ 逐轮瞬态（不进 {@code EconomyData} / 变更集 / 落盘），只由协调器单线程路径触碰。 ★ worker 副本各持一份新的空簿：它只走同格意向（{@code
     * route == null}）⇒ 到不了运力截断。
     */
    final LaneUnservedBook laneUnserved = new LaneUnservedBook();

    final Map<ShipmentKey, ShipmentBuilder> shipments = new LinkedHashMap<>();
    // ★ 地形代价的纯记忆化：组合根的 moveCostAt 会重建整张地形索引，同一 buyerHex 在逐卖方路线里只需算一次。
    final Map<HexCoord, Long> moveCostCache = new LinkedHashMap<>();
    // ★ R2：worker 本区副本上产生的区内成交意向（协调器回放；真实 ctx 的 recordFillIntents = false）。
    final List<FillIntent> fillIntents = new ArrayList<>();
    boolean recordFillIntents;
    // ★ P1.3：冻结轴累计值（key 见 sellFrozenAxis/buyFrozenAxis）；commitFreezes/executeTrade 增量维护。
    final Map<String, Long> sellFrozenSums = new LinkedHashMap<>();
    final Map<String, Long> buyFrozenSums = new LinkedHashMap<>();
    // ★★ P-T4：运费读数**按币分列**（键 = 铸这条腿用的钱 = 该笔买方的支付币）—— 禁跨币相加。
    final Map<CurrencyId, Long> freightPaidByCurrency = new LinkedHashMap<>();
    final Map<CurrencyId, Long> freightUncollectedByCurrency = new LinkedHashMap<>();
    long scheduledLossMilli;
    long immediateFills;
    long crossRegionFills;
    long shipmentSequence;

    /**
     * ★★ <b>P2f：本轮土地卖单的瞬态冻结集</b>（{@code AssetShareId}）—— 同一块地本轮最多卖一次；<b>不写</b> actor 的 {@code
     * frozenGoods}、不写第二本库存（土地不是商品，冻结只表达"这一块本轮已占"）。★ 逐轮瞬态：不落盘、不进变更集。
     */
    final Set<AssetShareId> landSellFrozen = new java.util.LinkedHashSet<>();

    /** ★★ P2f：本轮的土地成交读数（唯一写入点 = {@link MarketTradeExecutor#executeLandTrade}；只作日志/交账）。 */
    final List<MarketLandBook.LandFill> landFills = new ArrayList<>();

    /** ★★ P2f：本轮土地成交里记为普通货币债务的金额（毫买方支付币；只作日志/读数，债务本身落在 {@code debts}）。 */
    long landDeferredMilli;

    /** ★★ P2f：本轮发生赊购的土地成交笔数（见 {@link #landDeferredMilli}）。 */
    int landDeferredFills;

    /**
     * ★★ <b>P3b：商品买槽的纯库存财富序</b>（<b>盘前</b>由 M2 后快照构造一次，撮合期间只读）。
     *
     * <p>★ 与 {@link #landBuyerWealth} <b>同源</b>：两者都来自同一份 {@link PureInventoryWealthBook.Snapshot}
     * 实例，禁在商品成交/冻结之后另算第二份（否则商品买槽与土地买单会看到两份不同财富）。
     *
     * <p>★ {@code null}（旧夹具/读口）⇒ {@code MarketIndexBook.sortBuySlotsByOrdering} 退回 {@link
     * #ordering}{@code .householdRankOf(...)} 的旧净值序（缺省中性）。
     */
    MarketBuyerOrdering buyerOrdering;

    /**
     * ★★ <b>P2f/P3b：土地买方的纯库存财富快照</b>（<b>盘前</b>构造一次，撮合期间只读）—— 土地买槽的排序键。
     *
     * <p>★ <b>为什么必须在商品撮合之前取</b>：与 W4 的 {@code SettlementOrderBook} 同一条"禁自指"纪律 —— 若在商品成交之后现算，
     * 本轮商品成交/冻结就会改变"谁先买地"的顺序（同一个决定依赖它自己的结果）。{@code null} = 本轮没有土地订单（不白算）。
     *
     * <p>★★ <b>P3b 起与商品买槽共用同一实例</b>：{@code MarketRoundRunner} 只取一次快照，同时写入本字段与 {@link
     * #buyerOrdering}；土地 matcher 不得另外构造第二份。
     */
    PureInventoryWealthBook.Snapshot landBuyerWealth;

    /**
     * ★★ <b>P-T1a：本轮被口岸闸节流的（源区 → 目的区）对数与被拦下的量</b>（毫商品；只作日志/读数——被拦下的量 <b>不进候选集、不落状态、不进账本</b>，设计书
     * §11）。
     */
    int portGatedPairs;

    /** 见 {@link #portGatedPairs}（各<b>区对</b> {@code transit − 可通过量} 之和）。 */
    long portBlockedMilli;

    /**
     * ★★ <b>W2-fix：本轮被口岸闸拦下的<b>买家侧需求量</b>（逐 买方区 × 商品；毫商品）</b>—— 只作日志读数（ {@code
     * MARKET_DEMAND_COMPOSITION} 的 {@code blockedByPortMilli}），<b>不进任何算式、不落状态</b>。
     *
     * <p>★ <b>口径</b>（唯一写入点 = {@code matchRoute} 的"闸用尽且还有合格需求"分支）：记的是 {@code min(该 lane 本轮配给前仍愿买的合计,
     * 闸拦下的挂单余量)} —— 被拦下的量**本来就在 D 里** （D 取自买槽 {@code order.quantity()} 全额），这里只是把它**按区按商品读出来**，绝不回加进
     * D（回加就是重复计数）。
     *
     * <p>★ <b>它不重复计"运力截断"</b>：那条 lane 的运力截断记在 {@code capacityTruncatedMilli}（走 {@code capacityLeft
     * <= 0} 那一支），口岸闸走 {@code portLeft <= 0} 那一支，两支的判据不同 ⇒ 一份未服务量不会同时进两项。
     *
     * <p>★ 逐轮瞬态（不进 {@code EconomyData} / 变更集 / 落盘），且只在 {@code matchAcrossRegions}（协调器单线程）里写。
     */
    final Map<MarketZoneId, Map<CommodityId, Long>> portBlockedMilliByZoneCommodity =
        new LinkedHashMap<>();

    /**
     * ★★ <b>P-T1e：本轮被币种挂单闸挡下的候选笔数与被请求的货物量</b>（毫商品；只作日志/读数 —— 被挡下的候选<b>不成交、不落状态、不进账本</b>， 设计书
     * §14.3）。
     */
    int currencyGateBlocked;

    /** 见 {@link #currencyGateBlocked}（各笔"被挡下的请求量"之和）。 */
    long currencyGateBlockedMilli;

    /** ★★ <b>E：带"钱的价"的完整构造器</b>（协调器与区副本都用它 —— 副本传入<b>协调器那一份</b>实例， 保证"认不认得出某种钱"两边同一个答案）。 */
    MatchContext(
        MarketRound round,
        Map<MarketZoneId, MarketTable> marketTables,
        MarketTopology topology,
        MerchantCapacityPool carrierPool,
        MarketRegulation regulation,
        CurrencyValuation currencyValuation,
        SettlementOrderBook ordering) {
      this.round = round;
      this.marketTables = marketTables;
      this.topology = topology;
      this.regulation = regulation == null ? MarketRegulation.none() : regulation;
      this.deferredPaymentConfig = round.deferredPaymentConfig();
      this.debts = round.debts();
      this.hexTradeCost = new HexTradeCost(topology);
      this.currencyValuation =
          Objects.requireNonNull(currencyValuation, "MatchContext 的\"钱的价\"不得为 null（没有就给 none()）");
      this.carrierPool = carrierPool;
      this.ordering = ordering == null ? SettlementOrderBook.empty(round.day) : ordering;
      this.orderedRegions = orderRegions(this.topology, this.ordering);
      String anchorRegionId =
          this.regulation.defined() && topology.contains(this.regulation.anchor())
              ? regionIdAt(topology, this.regulation.anchor())
              : null;
      this.regulationByRegionId =
          anchorRegionId == null ? Map.of() : Map.of(anchorRegionId, this.regulation);
    }

    /**
     * ★★ <b>W4：本轮的区遍历序</b>（S-4：区之间按<b>上周期名义成交额</b>降序、同值按区 id 升序）。
     *
     * <p>★ 它决定"哪个区先被撮合、先消耗本轮共享的运力池"，因此与买槽序一样是结算序的一部分。★ 名次未知的区（快照里没有的） 一律排在已知区之后，并按区 id 升序 ——
     * 未知名次不许退化成"哈希序/写入史序"。
     */
    static List<MarketRegion> orderRegions(MarketTopology topology, SettlementOrderBook ordering) {
      List<MarketRegion> ordered = new ArrayList<>(topology.regions());
      ordered.sort(
          Comparator.comparingInt(
                  (MarketRegion region) -> ordering.zoneRankOf(region.node().nodeId()))
              .thenComparing(region -> region.node().nodeId()));
      return List.copyOf(ordered);
    }

    /** ★★ E：本轮的"钱的价"（只读）。 */
    CurrencyValuation currencyValuation() {
      return currencyValuation;
    }

    /**
     * ★★ <b>本入口有没有"未付价款 ⇒ 记债"的能力</b>（{@code deferredPaymentConfig} 与债务工作副本必须同时在场； 旧构造器 = 关闭 ⇒ 退回"货款
     * + 运费 + 税 ≤ 可付"的旧口径，绝不凭空赊账）。
     */
    boolean deferredPaymentsEnabled() {
      return deferredPaymentConfig != null && debts != null;
    }

    /** 本区适用区内市场规则（没有 ⇒ {@link MarketRegulation#none()}）。 */
    MarketRegulation regulationFor(String regionId) {
      MarketRegulation effective = regulationByRegionId.get(regionId);
      return effective == null ? MarketRegulation.none() : effective;
    }

    /** 本区该商品的单位税费（毫该区法定币 / 商品单位）；没有规则/缺项/0 ⇒ 0（P-T1b 起它是"区内市场税"真收款的税率来源）。 */
    long tariffPerUnitOf(String regionId, CommodityId commodity) {
      MarketRegulation effective = regulationFor(regionId);
      if (!effective.defined()) {
        return 0L;
      }
      return effective.tariffPerUnit().getOrDefault(commodity, 0L);
    }
  }
}
