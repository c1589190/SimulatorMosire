package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>同格市场清算（M2.1 / M2.2）</b>：<b>每周期一次</b>、<b>同一格内</b>把"订单"撮合到固定价上 —— 参与者 = 家户 + 经营者（庄园/作坊/产业型家户，
 * 以及不排斥的运输经营者）。
 *
 * <p>★★ <b>这一层没有订单簿状态</b>：每轮在同一个调用栈里<b>建单 → 过滤限价 → 按预算与供给配给 → 逐笔落账</b>，订单是瞬时的 —— 不给 {@code
 * EconomyData} 加组件、不进变更集/codec。L2 的在途批次（{@code ShipmentBatch}）才是跨轮存活的第一批状态。
 *
 * <p>★★ <b>订单从哪来（主体各自生成，不按格打包）</b>：
 *
 * <pre>
 * 家户（逐商品，商品 = 市场价表里的键）：
 *   生活保留 life = 到下轮补货/成交前的预测消费 + {@link #MARKET_SAFETY_STOCK_DAYS} 天安全库存
 *                = 该商品的人·天累计需求（本层取 {@link #MARKET_RESTOCK_INTERVAL_DAYS} + 安全天数）
 *   可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)
 *   要买 = max(0, 生活保留 − 可用)          // 可用 = max(0, 持有 − 已冻结)
 *   预算 = max(0, 该币种余额 − 已冻结货币)   // ★ 独立算：预算是钱，不是需求数量
 * 经营者（庄园 / 作坊 / 产业型家户）：
 *   必要生产投入 = 本产业配方 {@code inputPerUnit} × 本格产能折出的规模
 *                （配方里按生产资料种类归类的当期消耗 —— 例如作坊每座的 TOOL —— 就是"生产资料维护需求"；
 *                 本层不另造第二张维护表）
 *   可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)
 *   要买 = max(0, 必要生产投入 − 可用)       // 缺料则补料（工具不够时作坊自己也会是买方）
 *   生活保留 = {@link SubsistenceObligation#retentionOf}（M1.7 的唯一入口；无承诺 ⇒ 0）
 *   预算 = max(0, 该币种余额 − 已冻结货币)
 * </pre>
 *
 * <p>★★ <b>固定价与限价</b>：本层的成交价 = {@code Market} 里的挂牌价（价格是数据）。订单只做<b>限价过滤</b>（卖方底价 ≤ 挂牌价 ≤ 买方限价）与预算过滤
 * —— 自适应价格是 L3，本层不发明。区内即时 ⇒ 订单的 {@code latestArrivalTick}/{@code availableFromTick} 都取下单日。
 *
 * <p>★★ <b>已售出/已冻结不得再卖</b>：① 冻结额从可卖量里减掉（生成时），② 每个卖方在每个商品上只生成一条订单，成交后余额即时下降 ⇒ 同一批货在
 * 同一轮里不会被第二条订单再卖一次；③ 转移落账前还会过一次冻结校验（见 {@code EconomySettlement.applyTransfer} 的带冻结重载）。
 *
 * <p>★ <b>买卖双方都是 actor</b>（家户 actor 由 {@link HouseholdActors#of} 拼），落账仍只走唯一写口 {@code
 * EconomySettlement.applyTransfer} —— 本类绝不直接改任何副本。
 *
 * <p>★ <b>本层如实不做</b>：跨格/区域撮合、在途与运力、动态价格、市场读数组件（L2/L3）；每格仍按今天的"产业关账日开一次市"挂在 {@code
 * EconomySettlement.settleOneDay} 上。
 */
final class MarketSettlement {

  /**
   * ★★ <b>M2.0 的商品撮合间隔</b>（天）：设计定案是"每 5 天一轮"。本层（L1）**不**改调度频率（仍每周期开一次市），但这个数已经决定
   * "生活保留"里的"到下轮补货前"那一项 —— 到 L2/L3 真正按 5 天调度时，订单生成算式一字不改。
   */
  static final long MARKET_RESTOCK_INTERVAL_DAYS = 5L;

  /**
   * ★★ <b>安全库存默认值</b>（天）：用户 2026-09-27 裁定 —— 旧的 {@code MARKET_SELF_RESERVE_PER_MILLE = 1000‰}
   * <b>整周期自留退休</b>，改为"到下轮补货/成交前的预测消费 + 30 天安全库存"。★ 30 是<b>默认值、可调参数</b>，不是物理常数 （V7 参数目录落地后迁入、GM 可调）。
   */
  static final long MARKET_SAFETY_STOCK_DAYS = 30L;

  /** ★ <b>生活保留的总天数</b> = 撮合间隔 + 安全库存 = 35 天。它是"生活保留"这条算式的唯一拼写点：家户的买目标与卖扣减、经营者的给养保留 请求都读它。 */
  static final long MARKET_LIFE_RESERVE_DAYS =
      MARKET_RESTOCK_INTERVAL_DAYS + MARKET_SAFETY_STOCK_DAYS;

  /** 本层唯一的支付/收款工具（银币；生产侧不与 {@code MoneyVocabulary} 抢拼写）。 */
  private static final InstrumentId SILVER_SPECIE = MoneyVocabulary.SILVER_SPECIE.id();

  private MarketSettlement() {}

  /**
   * ★★ <b>一个市场轮次的只读会话视图</b>（包内可见的施工坞；类注见 {@link MarketSettlement}）。
   *
   * <p>★ <b>为什么不是 record</b>（形态由实现裁，理由记在这里）：它装的是**就地可变的会话副本**（{@code applyTransfer} 要写它们）—— 用
   * record 的访问器交出去会触发 {@code EI_EXPOSE_REP}，包成不可变视图又写不动。⇒ 按 {@code InputPlan} 的先例用 "final 字段 +
   * 包内可见构造器"的内部类；本类只在包内流转，不进任何持久状态。
   */
  static final class MarketRound {

    private final long day;
    private final Map<CohortKey, ClassRow> rows;
    private final Map<CohortKey, Map<CommodityId, Long>> householdGoods;
    private final Map<CohortKey, Map<CurrencyId, Long>> householdMoney;
    private final Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods;
    private final Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney;
    private final Map<ActorRef, Map<CommodityId, Long>> operatorGoods;
    private final Map<ActorRef, Map<CurrencyId, Long>> operatorMoney;
    private final Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods;
    private final Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney;
    private final Map<CohortKey, Map<CommodityId, Long>> unmetToday;
    private final Map<ActorRef, CohortKey> householdOfActor;
    private final Map<IndustryId, Industry> industries;
    private final Map<IndustryId, ProductionRelation> relations;
    private final Map<LaborAllocationId, LaborAllocation> allocations;
    private final ProductionLedger.Accumulator ledger;

    MarketRound(
        long day,
        Map<CohortKey, ClassRow> rows,
        Map<CohortKey, Map<CommodityId, Long>> householdGoods,
        Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
        Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods,
        Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
        Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
        Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
        Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
        Map<CohortKey, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, CohortKey> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<IndustryId, ProductionRelation> relations,
        Map<LaborAllocationId, LaborAllocation> allocations,
        ProductionLedger.Accumulator ledger) {
      this.day = day;
      this.rows = Objects.requireNonNull(rows, "rows");
      this.householdGoods = Objects.requireNonNull(householdGoods, "householdGoods");
      this.householdMoney = Objects.requireNonNull(householdMoney, "householdMoney");
      this.householdFrozenGoods =
          Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
      this.householdFrozenMoney =
          Objects.requireNonNull(householdFrozenMoney, "householdFrozenMoney");
      this.operatorGoods = Objects.requireNonNull(operatorGoods, "operatorGoods");
      this.operatorMoney = Objects.requireNonNull(operatorMoney, "operatorMoney");
      this.operatorFrozenGoods = Objects.requireNonNull(operatorFrozenGoods, "operatorFrozenGoods");
      this.operatorFrozenMoney = Objects.requireNonNull(operatorFrozenMoney, "operatorFrozenMoney");
      this.unmetToday = Objects.requireNonNull(unmetToday, "unmetToday");
      this.householdOfActor = Objects.requireNonNull(householdOfActor, "householdOfActor");
      this.industries = Objects.requireNonNull(industries, "industries");
      this.relations = Objects.requireNonNull(relations, "relations");
      this.allocations = Objects.requireNonNull(allocations, "allocations");
      this.ledger = Objects.requireNonNull(ledger, "ledger");
    }
  }

  /** 一个参与主体：家户（{@code household != null}）或经营者（{@code household == null}）。 */
  private record Participant(ActorRef actor, CohortKey household, List<IndustryId> industries) {
    Participant {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(industries, "industries");
      industries = List.copyOf(industries);
    }
  }

  /** 一个格上、某一商品的计划订单（**瞬时**；只在本轮内流转，不进任何状态）。 */
  record PlannedOrders(List<BuyOrder> buys, List<SellOrder> sells) {
    PlannedOrders {
      Objects.requireNonNull(buys, "buys");
      Objects.requireNonNull(sells, "sells");
      buys = List.copyOf(buys);
      sells = List.copyOf(sells);
    }
  }

  /** 一个格的预计算：参与者 + 逐主体的必要生产投入 / 生活保留（按商品）。 */
  private static final class HexPlan {
    final List<Participant> participants;
    final Map<ActorRef, Map<CommodityId, Long>> necessaryInputs;
    final Map<ActorRef, Map<CommodityId, Long>> lifeReserves;

    HexPlan(
        List<Participant> participants,
        Map<ActorRef, Map<CommodityId, Long>> necessaryInputs,
        Map<ActorRef, Map<CommodityId, Long>> lifeReserves) {
      this.participants = participants;
      this.necessaryInputs = necessaryInputs;
      this.lifeReserves = lifeReserves;
    }
  }

  /**
   * ★★ <b>开一次市</b>（每个周期一次；由 {@code EconomySettlement.settleOneDay} 在收获与分配之后调用）。
   *
   * @param markets 市场表（键 = 格；<b>缺格 = 该格没有市场</b>）；不得为 null
   * @param round 本轮只读视图（价格表在 {@code markets} 里；会话副本在 {@code round} 里）；不得为 null
   */
  static void clearOncePerCycle(Map<HexCoord, Market> markets, MarketRound round) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(round, "round");
    if (markets.isEmpty()) {
      return; // ★ 世上没有市场 ⇒ 什么都不做（本批之前的世界都是这一形态）
    }
    // ★ "这一格有哪些家户行"只有一处算法（EconomySettlement.rowsByHex）—— 建一次，逐格取用。
    Map<String, List<CohortKey>> rowsByHex = EconomySettlement.rowsByHex(round.rows.keySet());
    for (Map.Entry<HexCoord, Market> entry : markets.entrySet()) {
      HexCoord hex = entry.getKey();
      Market market = entry.getValue();
      List<CohortKey> keys =
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
      clearHex(round, hex, market, keys);
    }
  }

  /**
   * ★★ <b>纯订单生成</b>（包内可见；给测试与读口一条不经撮合的入口）：生成某一格、某一商品的全部买卖订单，<b>不落账、不改任何副本</b>。
   *
   * <p>★ 它与 {@link #clearOncePerCycle} 用的是<b>同一条</b> {@code ordersFor} 实现 ⇒ 测到的订单与真正成交的订单不可能漂开。
   */
  static PlannedOrders planOrders(
      MarketRound round, HexCoord hex, Market market, CommodityId commodity) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(commodity, "commodity");
    long price = market.priceOf(commodity);
    if (price <= 0L) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（同 Market 的口径）
    }
    List<CohortKey> keys =
        EconomySettlement.rowsByHex(round.rows.keySet())
            .getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
    return ordersFor(round, planFor(round, hex, keys), hex, market, commodity, price);
  }

  /** 一个格 × 一个已定价商品的撮合。 */
  private static void clearHex(
      MarketRound round, HexCoord hex, Market market, List<CohortKey> keys) {
    HexPlan plan = planFor(round, hex, keys);
    if (plan.participants.isEmpty()) {
      return; // 这一格还没有任何有账的主体
    }
    for (Map.Entry<CommodityId, Long> priced : market.prices().entrySet()) {
      long price = priced.getValue();
      if (price <= 0L) {
        continue; // Market 的构造期守卫已判死，这里只防御
      }
      CommodityId commodity = priced.getKey();
      PlannedOrders orders = ordersFor(round, plan, hex, market, commodity, price);
      match(round, plan, hex, market, commodity, price, orders);
    }
  }

  /** 生成一个格 × 一个商品上的全部订单（主体各自生成；见类注的算式）。 */
  private static PlannedOrders ordersFor(
      MarketRound round,
      HexPlan plan,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      long price) {
    List<BuyOrder> buys = new ArrayList<>();
    List<SellOrder> sells = new ArrayList<>();
    for (Participant participant : plan.participants) {
      long necessary =
          plan.necessaryInputs
              .getOrDefault(participant.actor, Map.of())
              .getOrDefault(commodity, 0L);
      long life =
          plan.lifeReserves.getOrDefault(participant.actor, Map.of()).getOrDefault(commodity, 0L);
      long stock = stockOf(round, participant, commodity);
      long frozen = frozenGoodsOf(round, participant, commodity);
      long available = Math.max(0L, stock - frozen);
      // ── 卖：可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留) ─────────────────────
      long sellable = Math.max(0L, stock - frozen - necessary - life);
      if (sellable > 0L) {
        sells.add(
            new SellOrder(
                participant.actor, hex, commodity, sellable, price, round.day, SILVER_SPECIE));
      }
      // ── 买：家户补到生活保留，经营者补到必要生产投入 ────────────────────────────────
      //   ★ "该时限前确定到货"本层恒为 0（L1 没有在途；ShipmentBatch 是 L2 的组件）⇒ 目标缺口 = target − available。
      long target = participant.household != null ? life : necessary;
      long gap = Math.max(0L, target - available);
      if (gap <= 0L) {
        continue;
      }
      long budget = spendableMoneyOf(round, participant, market.numeraire());
      if (budget <= 0L) {
        continue; // 没钱的缺口不是有效需求（与旧口径同一条立场）
      }
      long affordable = budget * EconomySettlement.MILLI_PER_GRAIN / price;
      long quantity = Math.min(gap, affordable);
      if (quantity <= 0L) {
        continue;
      }
      buys.add(
          new BuyOrder(
              participant.actor,
              hex,
              commodity,
              quantity,
              price,
              round.day,
              new Budget(budget, SILVER_SPECIE),
              SILVER_SPECIE));
    }
    return new PlannedOrders(buys, sells);
  }

  /**
   * ★★ <b>按订单撮合</b>：限价过滤 → 供 ≤ 求时按需求比例配给（最大余数法，Σ配给 == 供给）→ 逐笔经唯一 applier 落账。
   *
   * <p>★ 买方在成交那一刻还要再过一次<b>订单预算</b>与<b>当前可花余额</b>（预算独立算，但花不出去的钱不能透支）；卖方按订单序取货， 已成交部分从该订单的剩余里扣 ⇒
   * 同一批货不会卖第二次。
   */
  private static void match(
      MarketRound round,
      HexPlan plan,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      long price,
      PlannedOrders orders) {
    List<BuyOrder> buyers = new ArrayList<>();
    for (BuyOrder order : orders.buys()) {
      if (order.maxLandedPrice() >= price) {
        buyers.add(order);
      }
    }
    List<SellOrder> sellers = new ArrayList<>();
    for (SellOrder order : orders.sells()) {
      if (order.minPrice() <= price) {
        sellers.add(order);
      }
    }
    if (buyers.isEmpty() || sellers.isEmpty()) {
      return;
    }
    // 价格优先；同价保生成序（Java 的稳定排序）⇒ 可复现。
    buyers.sort(Comparator.comparingLong(BuyOrder::maxLandedPrice).reversed());
    sellers.sort(Comparator.comparingLong(SellOrder::minPrice));
    long supply = 0L;
    for (SellOrder order : sellers) {
      supply += order.sellable();
    }
    long demand = 0L;
    for (BuyOrder order : buyers) {
      demand += order.quantity();
    }
    long[] rationed;
    if (supply >= demand) {
      rationed = new long[buyers.size()];
      for (int i = 0; i < buyers.size(); i++) {
        rationed[i] = buyers.get(i).quantity();
      }
    } else {
      long[] weights = new long[buyers.size()];
      for (int i = 0; i < buyers.size(); i++) {
        weights[i] = buyers.get(i).quantity();
      }
      rationed = ProportionalSplit.byDenominator(supply, weights, demand);
    }
    Map<ActorRef, Participant> byActor = new LinkedHashMap<>();
    for (Participant participant : plan.participants) {
      byActor.put(participant.actor, participant);
    }
    long[] sellerLeft = new long[sellers.size()];
    for (int i = 0; i < sellers.size(); i++) {
      sellerLeft[i] = sellers.get(i).sellable();
    }
    int sellerIndex = 0;
    long leftWithSeller = sellerLeft.length == 0 ? 0L : sellerLeft[0];
    for (int i = 0; i < buyers.size(); i++) {
      BuyOrder buy = buyers.get(i);
      Participant buyer = byActor.get(buy.requester());
      if (buyer == null) {
        throw new IllegalStateException("买订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + buy.requester());
      }
      long need = rationed[i];
      // 预算（订单自己的上限）+ 当前可花余额，两者取小；★ 预算独立算，但撮合时不许透支。
      need = Math.min(need, buy.budget().amountMilli() * EconomySettlement.MILLI_PER_GRAIN / price);
      need =
          Math.min(
              need,
              spendableMoneyOf(round, buyer, market.numeraire())
                  * EconomySettlement.MILLI_PER_GRAIN
                  / price);
      while (need > 0L) {
        while (sellerIndex < sellers.size() && leftWithSeller <= 0L) {
          sellerIndex++;
          if (sellerIndex < sellers.size()) {
            leftWithSeller = sellerLeft[sellerIndex];
          }
        }
        if (sellerIndex >= sellers.size()) {
          break;
        }
        SellOrder sell = sellers.get(sellerIndex);
        Participant seller = byActor.get(sell.supplier());
        if (seller == null) {
          throw new IllegalStateException("卖订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + sell.supplier());
        }
        if (seller.actor.equals(buyer.actor)) {
          throw new IllegalStateException("同一主体在同一商品上同时买卖（订单生成的口径被破坏，自转移不是发生额）: " + seller.actor);
        }
        long quantity = Math.min(need, leftWithSeller);
        trade(round, hex, market, commodity, price, quantity, seller, buyer);
        need -= quantity;
        leftWithSeller -= quantity;
      }
    }
  }

  /**
   * ★★ <b>一笔成交</b>：铸<b>一对</b>同向转移（货一条、钱一条）并交给**唯一 applier** 落账；只把"买方是家户"的那一笔冲减当日缺口读数。
   *
   * <pre>
   * 货款 = ⌈数量 × 价格 ÷ 1000⌉      // 毫计价货币；向上取整（向下取整会让小额成交白送）
   * </pre>
   */
  private static void trade(
      MarketRound round,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      long price,
      long quantity,
      Participant seller,
      Participant buyer) {
    long payment =
        (quantity * price + EconomySettlement.MILLI_PER_GRAIN - 1L)
            / EconomySettlement.MILLI_PER_GRAIN;
    // ① 货：卖方 → 买方。
    Transfer goodsLeg =
        round.ledger.mint(
            seller.actor,
            buyer.actor,
            hex,
            Map.of(commodity, quantity),
            Map.of(),
            TransferReason.MARKET_TRADE);
    EconomySettlement.applyTransfer(
        round.householdGoods,
        round.householdMoney,
        round.operatorGoods,
        round.operatorMoney,
        round.householdFrozenGoods,
        round.householdFrozenMoney,
        round.operatorFrozenGoods,
        round.operatorFrozenMoney,
        round.householdOfActor,
        goodsLeg);
    // ② 钱：买方 → 卖方（同一套方向语义：这一张转移的 from 就是付钱的人）。
    Transfer moneyLeg =
        round.ledger.mint(
            buyer.actor,
            seller.actor,
            hex,
            Map.of(),
            Map.of(market.numeraire(), payment),
            TransferReason.MARKET_TRADE);
    EconomySettlement.applyTransfer(
        round.householdGoods,
        round.householdMoney,
        round.operatorGoods,
        round.operatorMoney,
        round.householdFrozenGoods,
        round.householdFrozenMoney,
        round.operatorFrozenGoods,
        round.operatorFrozenMoney,
        round.householdOfActor,
        moneyLeg);
    // ③ 缺口读数：买到的量冲减**当日**的未满足需求（封顶 = 已记的缺口；不改成负数、不凭空抵消历史缺口）。
    if (buyer.household != null) {
      reduceUnmet(round.unmetToday, buyer.household, commodity, quantity);
    }
  }

  /** 买到手的量冲减当日未满足需求（封顶 = 已记的缺口；**只对家户**，经营者没有那条读数）。 */
  private static void reduceUnmet(
      Map<CohortKey, Map<CommodityId, Long>> unmetToday,
      CohortKey buyer,
      CommodityId commodity,
      long quantity) {
    long recorded = unmetToday.getOrDefault(buyer, Map.of()).getOrDefault(commodity, 0L);
    long reduced = Math.min(recorded, quantity);
    if (reduced <= 0L) {
      return;
    }
    Map<CommodityId, Long> updated = new LinkedHashMap<>(unmetToday.getOrDefault(buyer, Map.of()));
    if (recorded - reduced <= 0L) {
      updated.remove(commodity);
    } else {
      updated.put(commodity, recorded - reduced);
    }
    unmetToday.put(buyer, updated);
  }

  /** 一个格的参与者：家户（按行）在前，经营者（该格产业的主体，逐 actor 去重）在后。 */
  private static List<Participant> participantsFor(
      MarketRound round, HexCoord hex, List<CohortKey> keys) {
    Map<ActorRef, List<IndustryId>> operatorIndustries = new LinkedHashMap<>();
    for (IndustryId id : IndustryHexKeys.at(round.industries, hex.q(), hex.r())) {
      Industry industry = round.industries.get(id);
      operatorIndustries.computeIfAbsent(industry.operator(), ignored -> new ArrayList<>()).add(id);
    }
    LinkedHashMap<ActorRef, Participant> byActor = new LinkedHashMap<>();
    for (CohortKey key : keys) {
      ActorRef actor = HouseholdActors.of(key);
      List<IndustryId> industries = operatorIndustries.remove(actor);
      byActor.put(actor, new Participant(actor, key, industries == null ? List.of() : industries));
    }
    for (Map.Entry<ActorRef, List<IndustryId>> entry : operatorIndustries.entrySet()) {
      ActorRef actor = entry.getKey();
      // ★ "账在会话副本里"才有可读库存/可花货币（缺席 = 看不见 = 可用 0，H4/H5 的既有口径）。
      if (!round.operatorGoods.containsKey(actor)
          && !round.operatorMoney.containsKey(actor)
          && !round.operatorFrozenGoods.containsKey(actor)
          && !round.operatorFrozenMoney.containsKey(actor)) {
        continue;
      }
      byActor.put(actor, new Participant(actor, null, entry.getValue()));
    }
    return List.copyOf(byActor.values());
  }

  /** 一个格的预计算（参与者 + 必要生产投入 + 生活保留）。 */
  private static HexPlan planFor(MarketRound round, HexCoord hex, List<CohortKey> keys) {
    List<Participant> participants = participantsFor(round, hex, keys);
    Map<ActorRef, Map<CommodityId, Long>> necessary = new LinkedHashMap<>();
    Map<ActorRef, Map<CommodityId, Long>> life = new LinkedHashMap<>();
    for (Participant participant : participants) {
      necessary.put(participant.actor, necessaryInputsOf(round, participant));
      if (participant.household != null) {
        ClassRow row = round.rows.get(participant.household);
        life.put(participant.actor, row == null ? Map.of() : householdLifeReserveOf(row));
      } else {
        life.put(participant.actor, operatorLifeRetentionOf(round, participant, hex));
      }
    }
    return new HexPlan(participants, necessary, life);
  }

  /**
   * ★★ <b>必要生产投入</b>（毫商品，逐商品）：该主体作为<b>投入提供方</b>的那些产业，配方 {@code inputPerUnit} × 产能折出的规模。
   *
   * <p>★ "谁是投入提供方"只读 {@code ProductionRelation.inputSupplier()}；缺 relation ⇒ 与 {@code
   * EconomySettlement.drawCycleInputs} 同一条缺省：经营者自己出。★ 配方里按生产资料种类归类的项（例如作坊的
   * TOOL/座·周期）就是<b>生产资料维护需求</b> —— 现有数据只有这一张表，本层不发明第二张。
   */
  private static Map<CommodityId, Long> necessaryInputsOf(
      MarketRound round, Participant participant) {
    Map<CommodityId, Long> necessary = new LinkedHashMap<>();
    for (IndustryId id : participant.industries) {
      Industry industry = round.industries.get(id);
      if (industry == null) {
        continue;
      }
      ProductionRelation relation = round.relations.get(id);
      Recipient supplier =
          relation == null ? new Recipient.ToActor(industry.operator()) : relation.inputSupplier();
      if (!supplies(participant, supplier)) {
        continue;
      }
      long scale = EconomySettlement.capacityScaleOf(industry);
      if (scale <= 0L) {
        continue; // 本格没有产能 ⇒ 不要料（同 drawCycleInputs 的口径）
      }
      for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        necessary.merge(entry.getKey(), entry.getValue() * scale, Long::sum);
      }
    }
    return necessary;
  }

  /** 家户的生活保留：粮/布按"人 × 天"的累计口径（{@link #MARKET_LIFE_RESERVE_DAYS} 天）。 */
  private static Map<CommodityId, Long> householdLifeReserveOf(ClassRow row) {
    Map<CommodityId, Long> life = new LinkedHashMap<>();
    long grain = selfNeedOf(row.population(), EconomySettlement.GRAIN, MARKET_LIFE_RESERVE_DAYS);
    if (grain > 0L) {
      life.put(EconomySettlement.GRAIN, grain);
    }
    long cloth = selfNeedOf(row.population(), EconomySettlement.CLOTH, MARKET_LIFE_RESERVE_DAYS);
    if (cloth > 0L) {
      life.put(EconomySettlement.CLOTH, cloth);
    }
    return life;
  }

  /**
   * ★★ <b>经营者的生活保留</b>：走 M1.7 的唯一入口 {@link SubsistenceObligation#retentionOf} —— 请求额 =
   * 关联家户（该产业劳动供给者）按 {@link #MARKET_LIFE_RESERVE_DAYS} 天的口粮/衣着需求，入口再把它**钉在经营者实际承诺的给养以内** （判据：Σ经营者保留
   * ≤ 它承诺的；且本方法不收人口参数，结构上不重复扣关联人口的整份口粮）。
   *
   * <p>★ 无 relation（没有规则要结算）⇒ 无承诺 ⇒ 空表。
   */
  private static Map<CommodityId, Long> operatorLifeRetentionOf(
      MarketRound round, Participant participant, HexCoord hex) {
    Map<CommodityId, Long> retained = new LinkedHashMap<>();
    for (IndustryId id : participant.industries) {
      Industry industry = round.industries.get(id);
      if (industry == null) {
        continue;
      }
      ProductionRelation relation = round.relations.get(id);
      if (relation == null) {
        continue;
      }
      if (!industry.operator().equals(participant.actor)
          && !relation.operator().equals(participant.actor)) {
        continue;
      }
      long population = 0L;
      for (CohortKey key : EconomySettlement.householdKeysOf(round.rows, id, round.allocations)) {
        ClassRow row = round.rows.get(key);
        if (row != null) {
          population += row.population();
        }
      }
      Map<CommodityId, Long> requested = new LinkedHashMap<>();
      long grain = selfNeedOf(population, EconomySettlement.GRAIN, MARKET_LIFE_RESERVE_DAYS);
      if (grain > 0L) {
        requested.put(EconomySettlement.GRAIN, grain);
      }
      long cloth = selfNeedOf(population, EconomySettlement.CLOTH, MARKET_LIFE_RESERVE_DAYS);
      if (cloth > 0L) {
        requested.put(EconomySettlement.CLOTH, cloth);
      }
      Map<CommodityId, Long> one =
          SubsistenceObligation.retentionOf(
              relation,
              EconomySettlement.laborOfCohort(round.rows, hex, industry.cycleDays()),
              requested);
      for (Map.Entry<CommodityId, Long> entry : one.entrySet()) {
        retained.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return retained;
  }

  /** 该主体是不是这条投入供给关系名下的受方（家户按 cohort、经营者按 actor；两者恰其一）。 */
  private static boolean supplies(Participant participant, Recipient supplier) {
    return switch (supplier) {
      case Recipient.ToActor toActor -> participant.actor.equals(toActor.actor());
      case Recipient.ToCohort toCohort ->
          participant.household != null && participant.household.equals(toCohort.cohort());
    };
  }

  /** 该商品"人 × {@code days} 天"的累计自然需求（毫单位）：粮走口粮口径、布走衣着口径、其余为 0。 */
  private static long selfNeedOf(long population, CommodityId commodity, long days) {
    if (commodity.equals(EconomySettlement.GRAIN)) {
      return EconomyVocabulary.cumulativeRationMilli(population, days);
    }
    if (commodity.equals(EconomySettlement.CLOTH)) {
      return EconomyVocabulary.cumulativeClothMilli(population, days);
    }
    return 0L;
  }

  /** 某主体在某商品上的持有量（没有这个键 ⇒ 0）。 */
  private static long stockOf(MarketRound round, Participant participant, CommodityId commodity) {
    if (participant.household != null) {
      return round
          .householdGoods
          .getOrDefault(participant.household, Map.of())
          .getOrDefault(commodity, 0L);
    }
    return round
        .operatorGoods
        .getOrDefault(participant.actor, Map.of())
        .getOrDefault(commodity, 0L);
  }

  /** 某主体在某商品上的**已冻结**量（没有这个键 ⇒ 0；本层没有冻结写者，结构上允许非零）。 */
  private static long frozenGoodsOf(
      MarketRound round, Participant participant, CommodityId commodity) {
    if (participant.household != null) {
      return round
          .householdFrozenGoods
          .getOrDefault(participant.household, Map.of())
          .getOrDefault(commodity, 0L);
    }
    return round
        .operatorFrozenGoods
        .getOrDefault(participant.actor, Map.of())
        .getOrDefault(commodity, 0L);
  }

  /** 某主体在某币种上的余额（没有这个键 ⇒ 0）。 */
  private static long moneyOf(MarketRound round, Participant participant, CurrencyId currency) {
    if (participant.household != null) {
      return round
          .householdMoney
          .getOrDefault(participant.household, Map.of())
          .getOrDefault(currency, 0L);
    }
    return round.operatorMoney.getOrDefault(participant.actor, Map.of()).getOrDefault(currency, 0L);
  }

  /** 某主体在某币种上的**已冻结**金额（没有这个键 ⇒ 0）。 */
  private static long frozenMoneyOf(
      MarketRound round, Participant participant, CurrencyId currency) {
    if (participant.household != null) {
      return round
          .householdFrozenMoney
          .getOrDefault(participant.household, Map.of())
          .getOrDefault(currency, 0L);
    }
    return round
        .operatorFrozenMoney
        .getOrDefault(participant.actor, Map.of())
        .getOrDefault(currency, 0L);
  }

  /** 可花的钱 = max(0, 余额 − 已冻结货币) —— 预算那一栏的唯一读法。 */
  private static long spendableMoneyOf(
      MarketRound round, Participant participant, CurrencyId currency) {
    return Math.max(
        0L, moneyOf(round, participant, currency) - frozenMoneyOf(round, participant, currency));
  }
}
