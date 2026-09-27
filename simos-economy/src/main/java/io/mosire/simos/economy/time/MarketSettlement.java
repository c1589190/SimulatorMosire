package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.market.TradeRoute;
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
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>区域市场撮合与在途运输（M2.3 + M2.4 + M2.5）</b>：把 M2.1/M2.2 的"订单体系"从"每格一次"扩成 <b>每 5 天一轮、区内优先、邻区稀疏跨区、跨区走
 * {@link TradeRoute} 的 ETA/损耗/运力</b>。
 *
 * <p>★★ <b>调度（M2.0 #5）</b>：{@link #triggerFor} 只看<b>绝对世界日 + 当前状态</b> —— 每 {@link
 * #MARKET_RESTOCK_INTERVAL_DAYS} 天例行一轮；粮库存低于 {@link #MARKET_LOW_STOCK_TRIGGER_DAYS} 天时在 每个 5 天窗口的
 * {@link #MARKET_LOW_STOCK_EXTRA_PHASE_DAYS} 天**追加一轮**；产业关账日保底开市（兼容周期长度不是 5 的倍数的世界）。 ★
 * 没有任何"距上次开市几天"的计数器 ⇒ 一次 360 天与三次 120 天在同一世界日必然同轮（M0.1）。
 *
 * <p>★★ <b>区域（M2.3）</b>：{@link MarketTopology} 由组合根传入（城市节点 + tier 半径 + 地图格集 + 逐格地形代价），
 * 每个市场格恰属一个区。撮合顺序：
 *
 * <pre>
 * ① 区内优先：同一 (region, commodity) 内按参考价撮合（价格 = 集散节点格的市场价）
 * ② 跨区候选：只考"直接邻接供应区"（MarketTopology.adjacent）
 * ③ 判到货价/量/期限：卖方基准价 ≤ 买方限价；到货日 ≤ latestArrivalTick；限价为**货款**上限、运费另计
 * ④ 有限轮内分运力：每路线最多 MARKET_MAX_TRANSPORT_ROUNDS 个运力窗口
 * ⑤ 按原始限价与预算分配回主体：区内按剩余需求/供给比例配给，再逐笔落到原始订单
 * ⑥ 原子提交、建在途：货款/运费即时结清，货权转买方并进入 ShipmentBatch
 * </pre>
 *
 * <p>★★ <b>在途（M2.4）</b>：发运时卖方库存减、买方在途资产增；到货日（{@code EconomySettlement} 的日循环）在途减、
 * 目的地库存增；到货前目的地既不能消费、也不能再挂牌卖出（货不在任何 {@code GoodsAccount} 余额里）。损耗逐票由买方承担 （{@link
 * LossBearer#BUYER}），到货时从在途量里扣并记进 {@code ProductionLedger} 的损耗账户。
 *
 * <p>★★ <b>运费必须有收款方</b>：承运主体 = {@code ActorKind.ORGANIZATION} 且会话里有货币账。世界里没有承运 actor
 * 时<b>不收运费</b>（{@link MarketReport#freightUncollectedMilli()} 记下应收而未收的读数），禁钱凭空消失。
 *
 * <p>★★ <b>跨区结算暂设即时</b>：{@code MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE = true} —— 货款与运费在**发运日**
 * 结清，货却在 ETA 之后才到；这是设计允许的简化，L3 的读数契约必须原样标注（M2.0 #4）。
 *
 * <p>★★ <b>价格（M2.6）</b>：参考价仍是格价表里的固定报价（区内成交价 = 集散节点格价、跨区 = 卖方格价）；买卖两侧的 **限价**由 {@code Market}
 * 的两个具名常量现算（bid = 卖方底价、ask = 买方限价），订单按限价过滤 —— 价差没有中间人截留， 成交仍按参考价。可选自适应（{@link
 * #MARKET_ADAPTIVE_PRICING_ENABLED}，<b>默认关</b>）在每轮撮合后按供需 z 改下一轮的 **区价**（成员格同改），改价只经 {@link
 * #clearOncePerCycle} 返回的 {@code MarketOutcome} 交回 {@code EconomySettlement} ⇒ 走 {@code markets}
 * 这个既有 {@code FieldDelta} 组件的变更集，没有第二处改价。
 *
 * <p>★★ <b>本类仍不是第二个 applier</b>：一切库存/货币的换手都经 {@code EconomySettlement.applyTransfer}（唯一写口）； 冻结只动
 * {@code frozen*} 表（M1.2 的 {@code freeze*}/{@code release*} 语义），在发运/成交/轮末释放。★ 唯一的例外是
 * "装载在途"：发运时把刚记到买方名下的量从会话余额移进 {@link ShipmentBatch}（它不是换手，是**在途资产的落点**， 到货日再反向落回）。
 */
final class MarketSettlement {

  /**
   * ★★ <b>M2.0 的商品撮合间隔</b>（天）：每 5 天一轮。★ 它同时是"生活保留"里"到下轮补货前"那一项的来源 （见 {@link
   * #MARKET_LIFE_RESERVE_DAYS}）。
   */
  static final long MARKET_RESTOCK_INTERVAL_DAYS = 5L;

  /**
   * ★★ <b>安全库存默认值</b>（天）：用户 2026-09-27 裁定 —— 旧的 {@code MARKET_SELF_RESERVE_PER_MILLE = 1000‰}
   * <b>整周期自留退休</b>，改为"到下轮补货/成交前的预测消费 + 30 天安全库存"。★ 30 是<b>默认值、可调参数</b>，不是物理常数 （V7 参数目录落地后迁入、GM 可调）。
   */
  static final long MARKET_SAFETY_STOCK_DAYS = 30L;

  /** ★ <b>生活保留的总天数</b> = 撮合间隔 + 安全库存 = 35 天。它是"生活保留"这条算式的唯一拼写点。 */
  static final long MARKET_LIFE_RESERVE_DAYS =
      MARKET_RESTOCK_INTERVAL_DAYS + MARKET_SAFETY_STOCK_DAYS;

  /**
   * ★★ <b>买订单的到货时限</b>（天，= 生活保留 35 天）："在 35 天内到货的粮仍然顶得上这次保留" —— 超过它， 到货也救不了即将发生的断粮。★ L1
   * 把时限压在下单当天（区内即时），L2 起放开到这条算式（M2.1 的"该时限前确定到货"）。
   */
  static final long MARKET_BUY_DEADLINE_DAYS = MARKET_LIFE_RESERVE_DAYS;

  /**
   * ★★ <b>低库存追加轮的阈值</b>（天）：某市场格里家户的**可用粮覆盖天数**低于它 ⇒ 在例行轮之外追加一轮。
   *
   * <p>★ 10 天的依据：例行轮每 5 天一次、生活保留 35 天 —— 覆盖跌破 10 天意味着"再等一个例行窗口就要动用安全库存"， 这时追加一轮的成本（一次撮合）远小于断粮的代价。★
   * 它是 GM 可调默认值，不是物理常数。
   */
  static final long MARKET_LOW_STOCK_TRIGGER_DAYS = 10L;

  /**
   * ★ <b>追加轮落在 5 天窗口的哪一天</b>（第 3 天）：保证"追加一轮"在字面上成立 —— 每个 5 天窗口最多多开一次， 不会因为粮一直低就每天开市。★
   * 它是**绝对日相位**（{@code day % 5}），因此两条推进路径同相位（M0.1）。
   */
  static final long MARKET_LOW_STOCK_EXTRA_PHASE_DAYS = 3L;

  /**
   * ★ <b>一条路线最多排几个运力窗口</b>（M2.3 的"有限轮内分运力"）：每轮最多发一个 {@code capacityPerWindow}， 排不完就报物流瓶颈。★ 4 轮 = 4
   * 个窗口，给"一次性大缺口"留缓冲，又不会把一个周期的量全塞进一次推进。
   */
  static final long MARKET_MAX_TRANSPORT_ROUNDS = 4L;

  /**
   * ★ <b>每条路线的运力</b>（毫商品 / 窗口）：出厂值 100,000,000 毫 = 100,000 单位 ≈ 首都 3.3 天的口粮 ——
   * 既能让正常补给通过，也能在"一次性调全国余粮"时**真的卡住**（M2.4 的判据："有货、有路、运力不足 ⇒ 城市仍可能缺粮"）。 ★ GM
   * 可调默认值；标定见计划"本文件没有回答的（运费系数/运力来源）"。
   */
  static final long MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW = 100_000_000L;

  /** ★ 运力随距离衰减的参考距离（hex）：{@code capacity = 出厂值 × 参考 / max(1, distance)}（R8 满额、R16 半额）。 */
  static final long MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX = 8L;

  /**
   * ★ <b>运费的千分费率</b>：每 hex 收"货款价值的 10‰"（8 格 ≈ 货款 8%）。★ 它与"实际投入" （{@link TradeRoute#costPerUnit()} =
   * {@code 距离 × moveCost}）**是两个独立的数**（M2.4 不许一个系数兼三职）。 ★ GM 可调默认值。
   */
  static final long MARKET_FREIGHT_PER_MILLE_PER_HEX = 10L;

  /** ★ <b>在途损耗率出厂值</b>（千分数）：每程 5‰，逐票由买方承担（M2.5 基线合同）。★ GM 可调默认值。 */
  static final int MARKET_TRANSPORT_LOSS_PER_MILLE = 5;

  /**
   * ★ <b>预算取整的安全边距</b>（毫计价货币）：货款与运费各自 {@code ⌈…⌉}，两处向上取整相加最多比 {@code 数量 × 单位到货价 ÷ 1000} 多 2
   * 毫。买得起的量按它回退，避免"货腿已落、钱腿不足"的半笔。
   */
  static final long MARKET_MONEY_ROUNDING_MARGIN_MILLI = 2L;

  /**
   * ★★ <b>跨区结算暂设即时</b>（M2.0 #4 的具名标注）：货款与运费在发运日结清，货却在 ETA 之后到 —— 设计允许、 但读数契约必须写明。★
   * 本批不做到货付款（那要一条信用/挂账机制，属后续增量）。
   */
  static final boolean MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE = true;

  /**
   * ★★ <b>M2.6 可选自适应价格的开关（默认 {@code false} = 固定报价）</b>：打开后每轮结算<b>结束</b>时按 {@code z =
   * clamp((有预算且合限价的需求 − 可出售供给) / max(需求 + 供给, ε), −1, 1)}、 {@code p_next = max(p_min, round(p × (1
   * + α·z)))} 更新**各区集散节点价**，并把同一区成员格的同商品价一并改到该值 （"每区每商品一个报价"）。
   *
   * <p>★★ <b>唯一写回路径</b>：{@link #clearOncePerCycle} 返回新的市场表，{@code EconomySettlement} 把这份表放进它交出的
   * {@code EconomyData} —— 于是 {@code markets} 作为既有的 {@code FieldDelta} 组件进变更集。本类<b>不</b>直接改任何
   * {@code EconomyData}，也没有第二处改价。
   *
   * <p>★ 默认关闭 ⇒ 本常量取 {@code false} 时价格表逐值原样带过（数值行为与 M2.6 之前完全相同）。
   */
  static final boolean MARKET_ADAPTIVE_PRICING_ENABLED = false;

  /**
   * ★★ <b>自适应的步长 α（千分比/轮）</b>：{@code 50‰ = 5%} —— 计划要求的**起步上界 ≤5%/轮**。
   *
   * <p>★ 它是 GM 可调出厂值，与 {@link #MARKET_ADAPTIVE_PRICING_ENABLED} 分开：开关回答"调不调"，本值回答"每轮最多调多少"。
   */
  static final long MARKET_ADAPTIVE_ALPHA_PER_MILLE = 50L;

  /**
   * ★ <b>自适应价格的下限 p_min</b>（毫计价货币/商品单位）：{@code 1} —— 与 {@code Market} 的"价格必须 &gt; 0"同一条守卫。
   * 需求远小于供给时价格仍会停在 1 毫，不会出现 0 或负价。
   */
  static final long MARKET_PRICE_FLOOR_MILLI = 1L;

  /**
   * ★ <b>自适应公式里分母的 ε</b>（毫商品）：{@code 1} —— {@code max(需求 + 供给, ε)} 的分母保护，保证零供需时 {@code z = 0}
   * 而不是除零。
   */
  static final long MARKET_ADAPTIVE_Z_EPSILON_MILLI = 1L;

  /**
   * ★★ <b>运输损耗的记账账户</b>：{@code ProductionLedger.losses} 的键是 {@code IndustryId}，而运输不是产业 ——
   * 用一个**具名伪账户**把在途损耗与生产损耗分开（两者都进 ΣLoss，但读账分得清是谁的）。
   */
  static final IndustryId TRANSPORT_LOSS_ACCOUNT = new IndustryId("market-transport");

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
    private final Map<ShipmentId, ShipmentBatch> shipments;
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
        Map<ShipmentId, ShipmentBatch> shipments,
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
      this.shipments = Objects.requireNonNull(shipments, "shipments");
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

  /**
   * ★★ <b>一轮市场的完整交出物（M2.6/M2.7）</b>：{@code report} = 只读读数原料；{@code markets} = 本轮结束时的价格表
   * （固定模式下与入参逐值相同，自适应模式下是改价后的新表）。
   *
   * <p>★ 价格表<b>只经这里</b>离开本类 → {@code EconomySettlement} 把它放进交出的 {@code EconomyData} → {@code
   * markets} 作为既有 {@code FieldDelta} 组件进变更集。没有第二条改价路径。
   */
  record MarketOutcome(MarketReport report, Map<HexCoord, Market> markets) {
    MarketOutcome {
      Objects.requireNonNull(report, "report");
      Objects.requireNonNull(markets, "markets");
      // ★ 保序不可变（不用 Map.copyOf：迭代序不是内容的纯函数）；值是不可变 record。
      markets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(markets));
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
   * ★★ <b>今天的市场开不开、为什么开</b>（调度判据；见 {@link MarketTrigger} 与 {@link MarketSettlement} 类注）。
   *
   * <p>★ 只看绝对日与当前状态：例行轮 = {@code day % 5 == 0}；关账日保底；低库存追加轮 = 每个窗口的第 3 天且库存跌破阈值。 两条推进路径在同一天必然给同一答案。
   */
  static MarketTrigger triggerFor(
      long day, boolean anyCycleClosed, Map<HexCoord, Market> markets, MarketRound round) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(round, "round");
    if (markets.isEmpty()) {
      return MarketTrigger.NONE; // 世上没有市场 ⇒ 什么都不做
    }
    if (day % MARKET_RESTOCK_INTERVAL_DAYS == 0L) {
      return MarketTrigger.PERIODIC;
    }
    if (anyCycleClosed) {
      return MarketTrigger.CYCLE_CLOSE; // 兼容周期长度不是 5 的倍数的世界：旧时点照旧开市
    }
    if (day % MARKET_RESTOCK_INTERVAL_DAYS == MARKET_LOW_STOCK_EXTRA_PHASE_DAYS
        && lowGrainStock(round, markets)) {
      return MarketTrigger.LOW_GRAIN_STOCK;
    }
    return MarketTrigger.NONE;
  }

  /**
   * 有没有哪个**有需求的家户行**的"可用粮覆盖天数"低于 {@link #MARKET_LOW_STOCK_TRIGGER_DAYS}。
   *
   * <p>★ <b>逐行判、不是逐格求和</b>：同一格上"有粮的富裕户"若把缺口户平均掉，紧急轮就永远不开 —— 而本判据要救的恰恰是缺口户（M0.4
   * 的首都就是"整格都没有可卖余量"的形态）。★ 每个 5 天窗口最多追加一次 （绝对日相位），因此逐行判的代价有上界。
   */
  private static boolean lowGrainStock(MarketRound round, Map<HexCoord, Market> markets) {
    Map<String, List<CohortKey>> rowsByHex = EconomySettlement.rowsByHex(round.rows.keySet());
    for (HexCoord hex : markets.keySet()) {
      for (CohortKey key :
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of())) {
        ClassRow row = round.rows.get(key);
        if (row == null) {
          continue;
        }
        long need = row.naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
        if (need <= 0L) {
          continue;
        }
        long stock =
            round
                .householdGoods
                .getOrDefault(key, Map.of())
                .getOrDefault(EconomySettlement.GRAIN, 0L);
        long frozen =
            round
                .householdFrozenGoods
                .getOrDefault(key, Map.of())
                .getOrDefault(EconomySettlement.GRAIN, 0L);
        if (Math.max(0L, stock - frozen) < need * MARKET_LOW_STOCK_TRIGGER_DAYS) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * ★★ <b>纯订单生成</b>（包内可见；给测试与读口一条不经撮合的入口）：生成某一格、某一商品的全部买卖订单，<b>不落账、不改任何副本</b>。
   *
   * <p>★ 它与 {@link #clearOncePerCycle} 用的是<b>同一条</b> {@code ordersFor} 实现 ⇒ 测到的订单与真正成交的订单不可能漂开。
   */
  static PlannedOrders planOrders(
      MarketRound round, HexCoord hex, Market market, CommodityId commodity) {
    Objects.requireNonNull(round, "round");
    return planOrders(
        round, hex, market, commodity, EconomySettlement.rowsByHex(round.rows.keySet()));
  }

  /**
   * ★ <b>纯订单生成的"已建索引"重载</b>（M2.7 读口用）：{@code rowsByHex} 由调用方一次建好 —— 逐区读数会对同一个 {@code rows}
   * 调它几十次，每次重扫全部行是纯浪费；两条重载走的是同一条 {@code ordersFor}。
   */
  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      Map<String, List<CohortKey>> rowsByHex) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(commodity, "commodity");
    Objects.requireNonNull(rowsByHex, "rowsByHex");
    if (market.priceOf(commodity) <= 0L) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（同 Market 的口径）
    }
    List<CohortKey> keys =
        rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
    return ordersFor(round, planFor(round, hex, keys), hex, market, commodity);
  }

  /**
   * ★★ <b>开一次市</b>（由 {@code EconomySettlement.settleOneDay} 在触发日调用一次）。
   *
   * <p>★ 返回 {@link MarketOutcome}（= 报告 + 本轮结束时的价格表）是 L3 的读数原料与 M2.6 的**唯一改价出口**（见 {@link
   * #MARKET_ADAPTIVE_PRICING_ENABLED}）：本类保证"信息不聚合丢失"，不替 L3 落成读数组件。失败时抛异常 —— 一次推进整条回滚，不产生半轮市场。
   *
   * @param markets 价格表（键 = 格；缺格 = 该格没有市场）
   * @param round 本轮只读/可写会话视图（余额与冻结都在这里）
   * @param trigger 今天为什么开市（由 {@link #triggerFor} 判定；{@link MarketTrigger#NONE} 不该走到这里）
   * @param topology 区域拓扑（派生件；由组合根传入）
   */
  static MarketOutcome clearOncePerCycle(
      Map<HexCoord, Market> markets,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(topology, "topology");
    Optional<ActorRef> carrier = carrierOf(round);
    MatchContext ctx = new MatchContext(round, markets, topology, trigger, carrier);
    if (markets.isEmpty() || trigger == MarketTrigger.NONE) {
      return new MarketOutcome(
          MarketReport.empty(round.day, trigger, carrier.isPresent()), markets);
    }

    // ── 1. 逐格建计划与订单；参与表按 actor 去重（订单生成与撮合的唯一来源）──────────────────────
    Map<String, List<CohortKey>> rowsByHex = EconomySettlement.rowsByHex(round.rows.keySet());
    List<HexCoord> hexes = new ArrayList<>(markets.keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HexCoord hex : hexes) {
      Market market = markets.get(hex);
      List<CohortKey> keys =
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
      HexPlan plan = planFor(round, hex, keys);
      if (plan.participants.isEmpty()) {
        continue;
      }
      for (Participant participant : plan.participants) {
        ctx.participants.put(participant.actor, participant);
        ctx.participantHex.put(participant.actor, hex);
      }
      for (Map.Entry<CommodityId, Long> priced : market.prices().entrySet()) {
        CommodityId commodity = priced.getKey();
        if (market.priceOf(commodity) <= 0L) {
          continue; // Market 的构造期守卫已判死，这里只防御
        }
        PlannedOrders orders = ordersFor(round, plan, hex, market, commodity);
        MarketRegion region = topology.regionOf(hex);
        for (BuyOrder order : orders.buys()) {
          Participant buyer = ctx.participants.get(order.requester());
          if (buyer == null) {
            throw new IllegalStateException("买订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.requester());
          }
          ctx.buys.add(new BuySlot(order, buyer, hex, market, region, market.numeraire()));
        }
        for (SellOrder order : orders.sells()) {
          Participant seller = ctx.participants.get(order.supplier());
          if (seller == null) {
            throw new IllegalStateException("卖订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.supplier());
          }
          ctx.sells.add(new SellSlot(order, seller, hex, market, region));
        }
      }
    }

    // ── 2. 冻结（M1.2 的写者接上）：挂单即占用；成交/发运/轮末释放 ────────────────────────
    commitFreezes(ctx);
    try {
      // ── 3. 区内优先 ─────────────────────────────────────────────────────────────────
      matchWithinRegions(ctx);
      // ── 4. 跨区候选（第一版只考直接邻接供应区）──────────────────────────────────────────
      matchAcrossRegions(ctx);
    } finally {
      releaseAllFreezes(ctx);
    }

    // ── 5. 未成交原因（不聚合丢失；买卖两侧分开）────────────────────────────────────────
    collectUnfilled(ctx);

    List<MarketReport.RouteUsage> routeUsages = new ArrayList<>();
    for (RouteAccumulator acc : ctx.routes.values()) {
      routeUsages.add(
          new MarketReport.RouteUsage(
              acc.from,
              acc.to,
              acc.commodity,
              acc.capacityPerWindow,
              acc.costPerUnit,
              acc.used,
              acc.demandMilli,
              acc.supplyMilli,
              acc.bottleneck));
    }
    // ── 6. M2.6 可选自适应：只看**本轮计划订单**（有预算且限价内的需求 vs 可出售供给），
    //   在撮合之后改下一轮的参考价。默认关 ⇒ 价格表原样带过、更新表为空。
    AdaptivePrices adapted =
        MARKET_ADAPTIVE_PRICING_ENABLED
            ? adaptPrices(markets, ctx)
            : new AdaptivePrices(List.of(), markets);
    return new MarketOutcome(
        new MarketReport(
            round.day,
            trigger,
            ctx.carrier.isPresent(),
            ctx.fills,
            ctx.unfilled,
            routeUsages,
            ctx.freightPaidMilli,
            ctx.freightUncollectedMilli,
            ctx.scheduledLossMilli,
            ctx.immediateFills,
            ctx.crossRegionFills,
            priceMode(),
            adapted.updates()),
        adapted.markets());
  }

  /** 本进程当前的报价模式（M2.6 的开关只有一个：默认固定）。 */
  static PriceMode priceMode() {
    return MARKET_ADAPTIVE_PRICING_ENABLED ? PriceMode.ADAPTIVE : PriceMode.FIXED;
  }

  /**
   * ★★ <b>M2.6 自适应的一轮结果</b>：{@code markets} = 更新后的价格表（未更新时就是入参本身）；{@code updates} = 逐 (集散节点, 商品)
   * 的改价记录（读数组件原样发出）。
   */
  record AdaptivePrices(List<MarketReport.PriceUpdate> updates, Map<HexCoord, Market> markets) {
    AdaptivePrices {
      updates = List.copyOf(updates);
      Objects.requireNonNull(markets, "markets");
      // ★ 保序不可变（不用 Map.copyOf：迭代序不是内容的纯函数）；值是不可变 record。
      markets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(markets));
    }
  }

  /**
   * ★★ <b>按供需 z 逐区改价</b>（M2.6 可选；只在 {@link #MARKET_ADAPTIVE_PRICING_ENABLED} 为真时被调用）。
   *
   * <pre>
   * z      = clamp((demand − supply) / max(demand + supply, ε), −1, 1)
   * p_next = max(p_min, round(p × (1 + α·z)))       // 定点整数：BigInteger 中间量，避免 long 溢出与 double 不可复现
   * </pre>
   *
   * <p>★★ <b>需求口径</b>：{@code demand} = 本轮**已生成**的买订单数量（{@code ordersFor} 已在生成时施加"预算 &gt; 0 +
   * 买得起"两重过滤）之和；{@code supply} = 卖订单的 {@code sellable} 之和。★ 只按绝对供需，不看本轮成交结果 ——
   * 成交已受运力/运费/时限约束，那些属物流读数，不是价格信号。
   *
   * <p>★★ <b>区价 = 集散节点价，成员格同改</b>：新价对**该区所有"已经给这个商品定价"的成员格**生效（缺价的格不凭空造一行）——
   * 这就是"每区每商品一个报价"的落点，也避免成员价格各自漂开后"区价"这个词失去意义。
   */
  private static AdaptivePrices adaptPrices(Map<HexCoord, Market> markets, MatchContext ctx) {
    // 逐 (region, commodity) 汇总订单；region 用拓扑对象本身当键（它由 node+members 派生，等值即同区）。
    Map<MarketRegion, Map<CommodityId, long[]>> byRegion = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      byRegion.computeIfAbsent(buy.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(buy.order.commodity(), ignored -> new long[2])[0] +=
          buy.order.quantity();
    }
    for (SellSlot sell : ctx.sells) {
      byRegion.computeIfAbsent(sell.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(sell.order.commodity(), ignored -> new long[2])[1] +=
          sell.order.sellable();
    }
    LinkedHashMap<HexCoord, Market> updated = new LinkedHashMap<>(markets);
    List<MarketReport.PriceUpdate> updates = new ArrayList<>();
    for (MarketRegion region : ctx.topology.regions()) {
      Market anchorMarket = markets.get(region.anchor());
      if (anchorMarket == null) {
        continue;
      }
      Map<CommodityId, long[]> quantities = byRegion.getOrDefault(region, Map.of());
      for (CommodityId commodity : orderedCommodities(ctx)) {
        long reference = anchorMarket.priceOf(commodity);
        if (reference <= 0L) {
          continue;
        }
        long[] demandSupply = quantities.get(commodity);
        long demand = demandSupply == null ? 0L : demandSupply[0];
        long supply = demandSupply == null ? 0L : demandSupply[1];
        long next = adaptiveNextPrice(reference, demand, supply);
        if (next == reference) {
          continue;
        }
        for (HexCoord member : region.members()) {
          Market current = updated.get(member);
          if (current == null || current.priceOf(commodity) <= 0L) {
            continue;
          }
          if (current.priceOf(commodity) == next) {
            continue;
          }
          Map<CommodityId, Long> prices = new LinkedHashMap<>(current.prices());
          prices.put(commodity, next);
          updated.put(member, new Market(current.numeraire(), prices));
        }
        updates.add(new MarketReport.PriceUpdate(region.anchor(), commodity, reference, next));
      }
    }
    return new AdaptivePrices(updates, updated);
  }

  /**
   * ★★ <b>自适应公式的定点实现</b>（包内可见，直接可测）：{@code z} 用有理数表示（分子 = demand − supply、分母 = max(demand + supply,
   * ε)），再按 {@code p_next = max(p_min, round(p × (1 + α·z)))} 取整。
   *
   * <p>★ 为什么用 {@link BigInteger}：{@code price × α × 需求量} 在量级上可以越过 {@code long}；浮点又会破坏"可复现"。
   * BigInteger 是整数、无平台差异，且每轮每区每商品只调用一次（真档 &lt; 1000 次/轮），不是热路径。
   *
   * <p>★ 取整 = <b>四舍五入（远离零）</b>：余数 × 2 ≥ 分母就向远离零方向加一毫。z 为负（供 &gt; 求）时价格下调，公式对 {@code α·z ∈ (−5%, 0]}
   * 对称。
   */
  static long adaptiveNextPrice(long price, long demand, long supply) {
    if (price <= 0L) {
      throw new IllegalArgumentException("自适应价格的入参 price 必须 > 0: " + price);
    }
    BigInteger d = BigInteger.valueOf(Math.max(0L, demand));
    BigInteger s = BigInteger.valueOf(Math.max(0L, supply));
    BigInteger denominator = d.add(s);
    if (denominator.signum() == 0) {
      denominator = BigInteger.valueOf(MARKET_ADAPTIVE_Z_EPSILON_MILLI);
    }
    BigInteger numerator = d.subtract(s);
    // z ∈ [−1, 1]：分子 clamp 到 ±分母。
    if (numerator.compareTo(denominator) > 0) {
      numerator = denominator;
    } else if (numerator.compareTo(denominator.negate()) < 0) {
      numerator = denominator.negate();
    }
    BigInteger scaled =
        BigInteger.valueOf(price)
            .multiply(BigInteger.valueOf(MARKET_ADAPTIVE_ALPHA_PER_MILLE))
            .multiply(numerator);
    BigInteger divisor = BigInteger.valueOf(1000L).multiply(denominator);
    BigInteger[] quotientAndRemainder = scaled.divideAndRemainder(divisor);
    BigInteger delta = quotientAndRemainder[0];
    if (quotientAndRemainder[1].abs().shiftLeft(1).compareTo(divisor) >= 0) {
      delta = delta.add(scaled.signum() < 0 ? BigInteger.ONE.negate() : BigInteger.ONE);
    }
    long next;
    try {
      next = price + delta.longValueExact();
    } catch (ArithmeticException overflow) {
      next = delta.signum() < 0 ? MARKET_PRICE_FLOOR_MILLI : Long.MAX_VALUE;
    }
    return Math.max(MARKET_PRICE_FLOOR_MILLI, next);
  }

  // ── 订单生成 ───────────────────────────────────────────────────────────────────────

  /** 生成一个格 × 一个商品上的全部订单（主体各自生成；见类注的算式）。 */
  private static PlannedOrders ordersFor(
      MarketRound round, HexPlan plan, HexCoord hex, Market market, CommodityId commodity) {
    // ★★ M2.6：参考价 = 本格价表里的固定报价；两个限价由 Market 的两个**各自独立**的常量现算
    //   （bid = 卖方底价、ask = 买方限价），订单按它们过滤；成交仍按参考价（区内）/ 卖方格参考价（跨区）。
    long reference = market.priceOf(commodity);
    long bid = market.bidPriceOf(commodity);
    long ask = market.askPriceOf(commodity);
    if (reference <= 0L || bid <= 0L || ask <= 0L) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（与 Market.priceOf 同口径）
    }
    List<BuyOrder> buys = new ArrayList<>();
    List<SellOrder> sells = new ArrayList<>();
    long deadline = round.day + MARKET_BUY_DEADLINE_DAYS;
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
                participant.actor,
                hex,
                commodity,
                sellable,
                bid,
                round.day,
                SILVER_SPECIE)); // 本批单一工具（silver-specie）；多工具是后续增量
      }
      // ── 买：家户补到生活保留，经营者补到必要生产投入 ────────────────────────────────
      //   ★ M2.4：目标缺口 = target − 可用 − **该时限前确定到货**（在途批次里买方那一份；M2.1 的原文）。
      long target = participant.household != null ? life : necessary;
      long incoming = confirmedIncoming(round, participant.actor, commodity, deadline);
      long gap = Math.max(0L, target - available - incoming);
      if (gap <= 0L) {
        continue;
      }
      long budget = spendableMoneyOf(round, participant, market.numeraire());
      if (budget <= 0L) {
        continue; // 没钱的缺口不是有效需求（与旧口径同一条立场）
      }
      // ★ 买得起多少按**参考价**折算：区内成交价就是它；跨区若卖方到货价更高，{@link #matchRoute} 会按实际
      //   "单价 + 运费"复核预算并缩小成交。★ ask 只做**限价过滤**（买方最多愿付），不在这里折数量 —— 否则
      //   价格表很小时（真档粮价 = 1 毫）整数网格会把 +1 毫的价差放大成"买得起的量减半"。
      long affordable = budget * EconomySettlement.MILLI_PER_GRAIN / reference;
      long quantity = Math.min(gap, affordable);
      if (quantity <= 0L) {
        continue;
      }
      // ★ 限价 = **货款**上限；跨区运费另计（M2.5：买方付货款与运费）。到货时限 = 生活保留天数（见常量注释）。
      buys.add(
          new BuyOrder(
              participant.actor,
              hex,
              commodity,
              quantity,
              ask,
              deadline,
              new Budget(budget, SILVER_SPECIE),
              SILVER_SPECIE));
    }
    return new PlannedOrders(buys, sells);
  }

  /** 某买方在某商品上、在时限前**确定到货**的在途量（买方自己的票，按 allocation 逐票求和）。 */
  private static long confirmedIncoming(
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

  // ── 冻结生命周期（M1.2 的写者）────────────────────────────────────────────────────────

  private static void commitFreezes(MatchContext ctx) {
    // 卖：每条卖单全额冻结（绝对值 = 底值 + 承诺）。
    for (SellSlot sell : ctx.sells) {
      sell.baseFrozenGoods = frozenGoodsOf(ctx.round, sell.seller, sell.order.commodity());
      sell.frozenRemaining = sell.order.sellable();
    }
    for (SellSlot sell : ctx.sells) {
      refreshSellFrozen(ctx, sell);
    }
    // 买：逐主体把"最多要花多少钱"按可花余额封顶后冻结（预算独立算，但承诺不许超过钱包）。
    Map<String, List<BuySlot>> byOwner = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      String key = buy.buyer.actor.kind() + ":" + buy.buyer.actor.id() + ":" + buy.currency.value();
      byOwner.computeIfAbsent(key, ignored -> new ArrayList<>()).add(buy);
    }
    for (List<BuySlot> group : byOwner.values()) {
      BuySlot first = group.get(0);
      long requested = 0L;
      for (BuySlot buy : group) {
        buy.baseFrozenMoney = frozenMoneyOf(ctx.round, buy.buyer, buy.currency);
        buy.requestedMoney = requestedMoneyOf(buy);
        requested += buy.requestedMoney;
      }
      long spendable = spendableMoneyOf(ctx.round, first.buyer, first.currency);
      long total = Math.min(requested, spendable);
      if (total <= 0L) {
        for (BuySlot buy : group) {
          buy.frozenRemaining = 0L;
          buy.blocked = MarketUnfilledReason.NO_BUDGET;
        }
      } else if (total == requested) {
        for (BuySlot buy : group) {
          buy.frozenRemaining = buy.requestedMoney;
        }
      } else {
        long[] weights = new long[group.size()];
        for (int i = 0; i < group.size(); i++) {
          weights[i] = group.get(i).requestedMoney;
        }
        long[] parts = ProportionalSplit.byDenominator(total, weights, requested);
        for (int i = 0; i < group.size(); i++) {
          group.get(i).frozenRemaining = parts[i];
        }
      }
      for (BuySlot buy : group) {
        refreshBuyFrozen(ctx, buy);
      }
    }
  }

  /** 一条买单选多最多会花掉的钱：{@code min(预算, ⌈数量 × 限价 ÷ 1000⌉)}（限价 = 货款上限，运费另计）。 */
  private static long requestedMoneyOf(BuySlot buy) {
    long goods =
        ceilDiv(
            buy.order.quantity() * buy.order.maxLandedPrice(), EconomySettlement.MILLI_PER_GRAIN);
    return Math.min(buy.order.budget().amountMilli(), goods);
  }

  private static void releaseAllFreezes(MatchContext ctx) {
    for (BuySlot buy : ctx.buys) {
      buy.frozenRemaining = 0L;
      setFrozenMoney(ctx.round, buy.buyer, buy.currency, buy.baseFrozenMoney);
    }
    for (SellSlot sell : ctx.sells) {
      sell.frozenRemaining = 0L;
      setFrozenGoods(ctx.round, sell.seller, sell.order.commodity(), sell.baseFrozenGoods);
    }
  }

  private static void refreshSellFrozen(MatchContext ctx, SellSlot sell) {
    long sum = 0L;
    for (SellSlot other : ctx.sells) {
      if (other.seller.actor.equals(sell.seller.actor)
          && other.order.commodity().equals(sell.order.commodity())) {
        sum += other.frozenRemaining;
      }
    }
    setFrozenGoods(ctx.round, sell.seller, sell.order.commodity(), sell.baseFrozenGoods + sum);
  }

  private static void refreshBuyFrozen(MatchContext ctx, BuySlot buy) {
    long sum = 0L;
    for (BuySlot other : ctx.buys) {
      if (other.buyer.actor.equals(buy.buyer.actor) && other.currency.equals(buy.currency)) {
        sum += other.frozenRemaining;
      }
    }
    setFrozenMoney(ctx.round, buy.buyer, buy.currency, buy.baseFrozenMoney + sum);
  }

  // ── 区内撮合 ───────────────────────────────────────────────────────────────────────

  /**
   * 区内逐 (region, commodity) 撮合：参考价 = **集散节点格的市场价**（M2.6 的"区价"）；买方限价 = ask、卖方底价 = bid，
   * 两侧限价在这里按参考价过滤（正常时 ask ≥ 参考价 ≥ bid ⇒ 全部通过；成员格价表不同时由限价真的把单挡下）。
   */
  private static void matchWithinRegions(MatchContext ctx) {
    for (MarketRegion region : ctx.topology.regions()) {
      Market anchorMarket = ctx.markets.get(region.anchor());
      if (anchorMarket == null) {
        continue;
      }
      for (CommodityId commodity : orderedCommodities(ctx)) {
        long price = anchorMarket.priceOf(commodity);
        if (price <= 0L) {
          continue;
        }
        List<BuySlot> buys = new ArrayList<>();
        for (BuySlot buy : ctx.buys) {
          if (buy.remaining > 0
              && buy.region.equals(region)
              && buy.order.commodity().equals(commodity)
              && buy.order.maxLandedPrice() >= price
              && buy.order.latestArrivalTick() >= ctx.round.day) {
            buys.add(buy);
          }
        }
        List<SellSlot> sells = new ArrayList<>();
        for (SellSlot sell : ctx.sells) {
          if (sell.remaining > 0
              && sell.region.equals(region)
              && sell.order.commodity().equals(commodity)
              && sell.order.minPrice() <= price
              && sell.order.availableFromTick() <= ctx.round.day) {
            sells.add(sell);
          }
        }
        if (buys.isEmpty() || sells.isEmpty()) {
          continue;
        }
        matchGroup(ctx, buys, sells, price, null);
      }
    }
  }

  /** 一个区内分组（已是同一 region × commodity）：按预算/供给配给后逐笔即时成交。 */
  private static void matchGroup(
      MatchContext ctx, List<BuySlot> buys, List<SellSlot> sells, long price, RouteContext route) {
    long[] weights = new long[buys.size()];
    long demand = 0L;
    for (int i = 0; i < buys.size(); i++) {
      long affordable = affordableQuantity(ctx, buys.get(i), price, route);
      weights[i] = Math.min(buys.get(i).remaining, affordable);
      demand += weights[i];
    }
    if (demand <= 0L) {
      return;
    }
    long[] sellWeights = new long[sells.size()];
    long supply = 0L;
    for (int i = 0; i < sells.size(); i++) {
      sellWeights[i] = sells.get(i).remaining;
      supply += sellWeights[i];
    }
    long matched = Math.min(demand, supply);
    if (matched <= 0L) {
      return;
    }
    long[] buyParts = ProportionalSplit.byDenominator(matched, weights, demand);
    long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, supply);
    pairUp(ctx, buys, buyParts, sells, sellParts, price, route);
  }

  // ── 跨区撮合（邻接供应区）──────────────────────────────────────────────────────────

  private static void matchAcrossRegions(MatchContext ctx) {
    if (!ctx.topology.regional()) {
      return;
    }
    for (CommodityId commodity : orderedCommodities(ctx)) {
      List<HexCoord> buyerHexes = new ArrayList<>();
      for (BuySlot buy : ctx.buys) {
        if (buy.remaining > 0
            && buy.order.commodity().equals(commodity)
            && !buyerHexes.contains(buy.hex)) {
          buyerHexes.add(buy.hex);
        }
      }
      buyerHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      for (HexCoord buyerHex : buyerHexes) {
        MarketRegion buyerRegion = ctx.topology.regionOf(buyerHex);
        List<BuySlot> buys = new ArrayList<>();
        for (BuySlot buy : ctx.buys) {
          if (buy.remaining > 0
              && buy.order.commodity().equals(commodity)
              && buy.hex.equals(buyerHex)) {
            buys.add(buy);
          }
        }
        if (buys.isEmpty()) {
          continue;
        }
        List<HexCoord> sellerHexes = new ArrayList<>();
        for (SellSlot sell : ctx.sells) {
          if (sell.remaining > 0
              && sell.order.commodity().equals(commodity)
              && !sellerHexes.contains(sell.hex)) {
            sellerHexes.add(sell.hex);
          }
        }
        sellerHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
        for (HexCoord sellerHex : sellerHexes) {
          MarketRegion sellerRegion = ctx.topology.regionOf(sellerHex);
          if (sellerRegion.equals(buyerRegion)
              || !ctx.topology.adjacent(buyerRegion, sellerRegion)) {
            continue;
          }
          List<SellSlot> sells = new ArrayList<>();
          for (SellSlot sell : ctx.sells) {
            if (sell.remaining > 0
                && sell.order.commodity().equals(commodity)
                && sell.hex.equals(sellerHex)) {
              sells.add(sell);
            }
          }
          if (sells.isEmpty()) {
            continue;
          }
          matchRoute(ctx, buyerHex, sellerHex, commodity, buys, sells);
          // 这一对买卖里买家已经满足的不必再看别的卖方路线。
          buys.removeIf(buy -> buy.remaining <= 0);
          if (buys.isEmpty()) {
            break;
          }
        }
      }
    }
  }

  /** 一条具体的 sellerHex → buyerHex 路线：判价/时限/地形，再在有限轮内分运力。 */
  private static void matchRoute(
      MatchContext ctx,
      HexCoord buyerHex,
      HexCoord sellerHex,
      CommodityId commodity,
      List<BuySlot> rawBuys,
      List<SellSlot> rawSells) {
    long distance = ctx.topology.travelTicks(sellerHex, buyerHex);
    long moveCost = ctx.topology.moveCostAt(buyerHex);
    if (moveCost >= TerrainType.IMPASSABLE_MOVE_COST) {
      for (BuySlot buy : rawBuys) {
        buy.blocked = MarketUnfilledReason.NO_ROUTE;
      }
      return;
    }
    long capacityPerWindow =
        Math.max(
            1L,
            MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW
                * MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX
                / Math.max(1L, distance));
    long costPerUnit = distance * moveCost;
    long travelTicks = Math.max(1L, distance);
    String routeKey = sellerHex + "->" + buyerHex + "#" + commodity.value();
    RouteAccumulator acc =
        ctx.routes.computeIfAbsent(
            routeKey,
            ignored ->
                new RouteAccumulator(
                    sellerHex, buyerHex, commodity, capacityPerWindow, costPerUnit));

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
      long unitPrice = unitPriceOf(sells, commodity);
      long freightPerUnit = freightPerUnitOf(unitPrice, travelTicks);
      RouteContext route =
          new RouteContext(
              sellerHex,
              buyerHex,
              unitPrice,
              freightPerUnit,
              travelTicks,
              costPerUnit,
              capacityPerWindow,
              MARKET_TRANSPORT_LOSS_PER_MILLE,
              arrivalTick);
      long[] weights = new long[buys.size()];
      long demand = 0L;
      long supply = 0L;
      for (int i = 0; i < buys.size(); i++) {
        BuySlot buy = buys.get(i);
        if (buy.order.latestArrivalTick() < arrivalTick) {
          if (buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.LOGISTICS_TIME;
          }
          weights[i] = 0L;
          continue;
        }
        if (buy.order.maxLandedPrice() < unitPrice) {
          if (buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
          }
          weights[i] = 0L;
          continue;
        }
        long affordable = affordableQuantity(ctx, buy, unitPrice, route);
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
      long matched = Math.min(demand, Math.min(supply, capacityPerWindow));
      if (matched <= 0L) {
        break;
      }
      long[] buyParts = ProportionalSplit.byDenominator(matched, weights, demand);
      long[] sellWeights = new long[sells.size()];
      for (int i = 0; i < sells.size(); i++) {
        sellWeights[i] = sells.get(i).remaining;
      }
      long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, supply);
      acc.used += matched;
      pairUp(ctx, buys, buyParts, sells, sellParts, unitPrice, route);
      if (matched >= capacityPerWindow
          && (remainingOf(buys) > 0L || remainingOfSells(sells) > 0L)) {
        acc.bottleneck = true;
        for (BuySlot buy : buys) {
          if (buy.remaining > 0L && buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
          }
        }
      }
    }
  }

  /** 一批买卖按比例配对落账（区内即时或跨区在途）。 */
  private static void pairUp(
      MatchContext ctx,
      List<BuySlot> buyers,
      long[] buyParts,
      List<SellSlot> sellers,
      long[] sellParts,
      long price,
      RouteContext route) {
    int sellerIndex = 0;
    long sellLeft = sellers.isEmpty() ? 0L : sellParts[0];
    for (int i = 0; i < buyers.size(); i++) {
      long need = buyParts[i];
      while (need > 0L) {
        while (sellerIndex < sellers.size() && sellLeft <= 0L) {
          sellerIndex++;
          if (sellerIndex < sellers.size()) {
            sellLeft = sellParts[sellerIndex];
          }
        }
        if (sellerIndex >= sellers.size()) {
          return;
        }
        SellSlot sell = sellers.get(sellerIndex);
        BuySlot buy = buyers.get(i);
        long quantity = Math.min(need, sellLeft);
        executeTrade(ctx, buy, sell, quantity, price, route);
        buy.remaining -= quantity;
        sell.remaining -= quantity;
        need -= quantity;
        sellLeft -= quantity;
      }
    }
  }

  // ── 一笔成交（区内即时 / 跨区在途）────────────────────────────────────────────────

  private static void executeTrade(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      RouteContext route) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    HexCoord location = route == null ? sell.hex : route.from;
    long payment = ceilDiv(quantity * unitPrice, EconomySettlement.MILLI_PER_GRAIN);
    long nominalFreight =
        route == null ? 0L : freightOf(quantity, route.unitPrice, route.travelTicks);
    long freight = route != null && ctx.carrier.isPresent() ? nominalFreight : 0L;

    // ① 卖方把已冻结的那一份放出来，再走唯一 applier（货腿：卖方 → 买方）。
    long sellRelease = Math.min(quantity, sell.frozenRemaining);
    sell.frozenRemaining -= sellRelease;
    refreshSellFrozen(ctx, sell);
    Transfer goodsLeg =
        round.ledger.mint(
            sell.seller.actor,
            buy.buyer.actor,
            location,
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

    if (route != null) {
      // ★ 装载在途：货权已归买方（上面那条货腿），但货**不在目的地的余额里** —— 把它从买方的会话余额移进
      //   ShipmentBatch（到货日再反向落回）。这不是第二次换手，是在途资产的唯一落点。
      loadInTransit(ctx, buy, quantity);
    }

    // ② 买方把冻结的货款（+运费）放出来，再货款 → 卖方、运费 → 承运人。
    long total = payment + freight;
    long buyRelease = Math.min(total, buy.frozenRemaining);
    buy.frozenRemaining -= buyRelease;
    refreshBuyFrozen(ctx, buy);
    if (payment > 0L) {
      Transfer moneyLeg =
          round.ledger.mint(
              buy.buyer.actor,
              sell.seller.actor,
              location,
              Map.of(),
              Map.of(buy.currency, payment),
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
    }
    if (freight > 0L && ctx.carrier.isPresent()) {
      Transfer freightLeg =
          round.ledger.mint(
              buy.buyer.actor,
              ctx.carrier.get(),
              route.to,
              Map.of(),
              Map.of(buy.currency, freight),
              TransferReason.CARRIER_FEE);
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
          freightLeg);
      ctx.freightPaidMilli += freight;
    } else if (route != null) {
      ctx.freightUncollectedMilli += nominalFreight;
    }
    buy.spentMilli += total;

    if (route == null) {
      // 区内即时：买到的量冲减**当日**未满足需求（封顶 = 已记的缺口；只对家户，经营者没有那条读数）。
      if (buy.buyer.household != null) {
        reduceUnmet(round.unmetToday, buy.buyer.household, commodity, quantity);
      }
      ctx.immediateFills++;
      ctx.fills.add(
          new MarketReport.Fill(
              sell.hex,
              buy.hex,
              commodity,
              sell.seller.actor,
              buy.buyer.actor,
              quantity,
              unitPrice,
              0L,
              payment,
              0L,
              round.day,
              true,
              "",
              0L));
      return;
    }

    // ③ 跨区：合并到同 (from,to,commodity,arrival) 的在途批次，并保留逐票损耗归属。
    ShipmentKey key = new ShipmentKey(route.from, route.to, commodity, route.arrivalTick);
    ShipmentBuilder batch =
        ctx.shipments.computeIfAbsent(key, ignored -> new ShipmentBuilder(route));
    batch.quantity += quantity;
    batch.allocations.add(
        new ShipmentAllocation(
            sell.seller.actor, buy.buyer.actor, buy.order.deliverTo(), quantity, LossBearer.BUYER));
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
            route.arrivalTick,
            batch.quantity,
            batch.allocations));
    ctx.scheduledLossMilli += quantity * route.lossPerMille / 1000L;
    ctx.crossRegionFills++;
    // 读数里的单位运费按**本票实收**折算（不是 route 的指示费率）：L3 的到货价 = 单价 + 这一栏。
    long reportedFreightPerUnit =
        freight > 0L ? ceilDiv(freight * EconomySettlement.MILLI_PER_GRAIN, quantity) : 0L;
    ctx.fills.add(
        new MarketReport.Fill(
            route.from,
            route.to,
            commodity,
            sell.seller.actor,
            buy.buyer.actor,
            quantity,
            unitPrice,
            reportedFreightPerUnit,
            payment,
            freight,
            route.arrivalTick,
            false,
            shipmentId,
            // ★ M2.7：逐票预排损耗与到货日的扣减公式逐字同源（deliverShipments 也是 quantity × lossPerMille ÷ 1000）。
            quantity * route.lossPerMille / 1000L));
  }

  /** 把刚记到买方名下的量移出会话余额（在途资产的装载；到货日反向落回）。 */
  private static void loadInTransit(MatchContext ctx, BuySlot buy, long quantity) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    if (buy.buyer.household != null) {
      CohortKey key = buy.buyer.household;
      long stock = householdStockOf(round.householdGoods, key, commodity);
      if (stock < quantity) {
        throw new IllegalStateException(
            "装载在途时买方库存不足（货腿刚记上，账不应漂开）：家户=" + key + " 商品=" + commodity + " 余额=" + stock);
      }
      setHouseholdStock(round.householdGoods, key, commodity, stock - quantity);
      return;
    }
    if (!round.operatorGoods.containsKey(buy.buyer.actor)) {
      throw new IllegalStateException("装载在途时买方经营者账不在会话副本里（跨区买家必须在参与者里）：" + buy.buyer.actor);
    }
    long stock = operatorStockOf(round.operatorGoods, buy.buyer.actor, commodity);
    if (stock < quantity) {
      throw new IllegalStateException(
          "装载在途时买方经营者库存不足：经营者=" + buy.buyer.actor + " 商品=" + commodity + " 余额=" + stock);
    }
    setOperatorStock(round.operatorGoods, buy.buyer.actor, commodity, stock - quantity);
  }

  // ── 会话副本的最小读写（本类不引入第二个 applier；这里只是把"哪张表、哪个键"说清）──────────────

  private static long householdStockOf(
      Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key, CommodityId commodity) {
    return goods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  private static void setHouseholdStock(
      Map<CohortKey, Map<CommodityId, Long>> goods,
      CohortKey key,
      CommodityId commodity,
      long value) {
    Map<CommodityId, Long> inner = new LinkedHashMap<>(goods.getOrDefault(key, Map.of()));
    inner.put(commodity, Math.max(0L, value));
    goods.put(key, inner);
  }

  private static long operatorStockOf(
      Map<ActorRef, Map<CommodityId, Long>> goods, ActorRef actor, CommodityId commodity) {
    return goods.getOrDefault(actor, Map.of()).getOrDefault(commodity, 0L);
  }

  private static void setOperatorStock(
      Map<ActorRef, Map<CommodityId, Long>> goods,
      ActorRef actor,
      CommodityId commodity,
      long value) {
    Map<CommodityId, Long> inner = new LinkedHashMap<>(goods.getOrDefault(actor, Map.of()));
    inner.put(commodity, Math.max(0L, value));
    goods.put(actor, inner);
  }

  /**
   * 同一对 (sellerHex,buyerHex) 上的成交单价：取卖方**格价表里的参考价**的上界（M2.6：成交仍按参考价，买卖双方各自比自己的 bid/ask
   * 限价占优；多价表时宁高不低，不让卖家亏本）。★ 限价过滤在 {@link #matchRoute} 里逐买方判，不走这里。
   */
  private static long unitPriceOf(List<SellSlot> sells, CommodityId commodity) {
    long price = 0L;
    for (SellSlot sell : sells) {
      if (sell.order.commodity().equals(commodity)) {
        price = Math.max(price, sell.market.priceOf(commodity));
      }
    }
    return price;
  }

  private static long freightPerUnitOf(long unitPrice, long travelTicks) {
    return unitPrice * travelTicks * MARKET_FREIGHT_PER_MILLE_PER_HEX / 1000L;
  }

  /** 一条成交量的运费：{@code ⌈数量 × 单价 × 天数 × 10‰ ÷ 1000⌉}（毫计价货币）。 */
  private static long freightOf(long quantity, long unitPrice, long travelTicks) {
    return ceilDiv(
        quantity * unitPrice * travelTicks * MARKET_FREIGHT_PER_MILLE_PER_HEX, 1_000_000L);
  }

  /** 买方在这条路上买得起多少：可花余额 + 本单剩余冻结，按"单价 + 单位运费"折算；再受订单预算余额封顶。 */
  private static long affordableQuantity(
      MatchContext ctx, BuySlot buy, long unitPrice, RouteContext route) {
    long freightPerUnit = route == null ? 0L : route.freightPerUnit;
    long unitCost = unitPrice + freightPerUnit;
    long money = spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
    long budgetLeft = Math.max(0L, buy.order.budget().amountMilli() - buy.spentMilli);
    money = Math.min(money, budgetLeft + buy.frozenRemaining);
    if (money <= MARKET_MONEY_ROUNDING_MARGIN_MILLI) {
      return 0L;
    }
    money -= MARKET_MONEY_ROUNDING_MARGIN_MILLI;
    if (unitCost <= 0L) {
      return money * EconomySettlement.MILLI_PER_GRAIN;
    }
    return money * EconomySettlement.MILLI_PER_GRAIN / unitCost;
  }

  // ── 未成交原因 ─────────────────────────────────────────────────────────────────────

  private static void collectUnfilled(MatchContext ctx) {
    Map<CommodityId, Long> sellRemainingByCommodity = new LinkedHashMap<>();
    Map<CommodityId, Long> minSellerPriceByCommodity = new LinkedHashMap<>();
    for (SellSlot sell : ctx.sells) {
      CommodityId commodity = sell.order.commodity();
      sellRemainingByCommodity.merge(commodity, Math.max(0L, sell.remaining), Long::sum);
      minSellerPriceByCommodity.merge(commodity, sell.order.minPrice(), Math::min);
    }
    for (BuySlot buy : ctx.buys) {
      if (buy.remaining <= 0L) {
        continue;
      }
      long totalSellRemaining = sellRemainingByCommodity.getOrDefault(buy.order.commodity(), 0L);
      long minSellerPrice =
          minSellerPriceByCommodity.getOrDefault(buy.order.commodity(), Long.MAX_VALUE);
      MarketUnfilledReason reason = buy.blocked;
      if (reason == null) {
        if (buy.requestedMoney <= 0L
            || spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining <= 0L) {
          reason = MarketUnfilledReason.NO_BUDGET;
        } else if (totalSellRemaining <= 0L) {
          reason = classifyNoSupply(ctx, buy);
        } else if (!hasSupplyNear(ctx, buy)) {
          reason = MarketUnfilledReason.NO_ADJACENT_SUPPLY;
        } else if (minSellerPrice != Long.MAX_VALUE
            && buy.order.maxLandedPrice() < minSellerPrice) {
          reason = MarketUnfilledReason.PRICE_LIMIT;
        } else {
          reason = MarketUnfilledReason.ALGORITHM_UNCOVERED;
        }
      }
      ctx.unfilled.add(
          new MarketReport.Unfilled(
              buy.buyer.actor, true, buy.order.commodity(), buy.remaining, reason, buy.hex));
    }
    for (SellSlot sell : ctx.sells) {
      if (sell.remaining <= 0L) {
        continue;
      }
      boolean anyBuy = false;
      for (BuySlot buy : ctx.buys) {
        if (buy.remaining > 0L && buy.order.commodity().equals(sell.order.commodity())) {
          anyBuy = true;
          break;
        }
      }
      MarketUnfilledReason reason =
          anyBuy ? MarketUnfilledReason.NO_BUDGET : MarketUnfilledReason.NO_BUYER;
      ctx.unfilled.add(
          new MarketReport.Unfilled(
              sell.seller.actor, false, sell.order.commodity(), sell.remaining, reason, sell.hex));
    }
  }

  /** 买方的区里/直接邻接供应区里，还有同商品的剩余卖单吗（"看不见的供给"与"没有供给"分开）。 */
  private static boolean hasSupplyNear(MatchContext ctx, BuySlot buy) {
    for (SellSlot sell : ctx.sells) {
      if (sell.remaining <= 0L || !sell.order.commodity().equals(buy.order.commodity())) {
        continue;
      }
      if (sell.region.equals(buy.region) || ctx.topology.adjacent(buy.region, sell.region)) {
        return true;
      }
    }
    return false;
  }

  /** 没有可卖余量时的进一步归因：全部被保留 ⇒ {@code ALL_RESERVED}；根本没有邻区 ⇒ {@code NO_ADJACENT_SUPPLY}。 */
  private static MarketUnfilledReason classifyNoSupply(MatchContext ctx, BuySlot buy) {
    MarketRegion region = buy.region;
    for (Participant participant : ctx.participants.values()) {
      if (!ctx.participantHex.getOrDefault(participant.actor, buy.hex).equals(buy.hex)) {
        continue;
      }
      long held = stockOf(ctx.round, participant, buy.order.commodity());
      long frozen = frozenGoodsOf(ctx.round, participant, buy.order.commodity());
      if (held - frozen > 0L) {
        return MarketUnfilledReason.ALL_RESERVED;
      }
    }
    boolean anyAdjacent = false;
    for (MarketRegion other : ctx.topology.regions()) {
      if (!other.equals(region) && ctx.topology.adjacent(region, other)) {
        anyAdjacent = true;
        break;
      }
    }
    return anyAdjacent ? MarketUnfilledReason.NO_SELLER : MarketUnfilledReason.NO_ADJACENT_SUPPLY;
  }

  private static long remainingOf(List<BuySlot> buys) {
    long sum = 0L;
    for (BuySlot buy : buys) {
      sum += Math.max(0L, buy.remaining);
    }
    return sum;
  }

  private static long remainingOfSells(List<SellSlot> sells) {
    long sum = 0L;
    for (SellSlot sell : sells) {
      sum += Math.max(0L, sell.remaining);
    }
    return sum;
  }

  // ── 参与者 / 订单预计算（M2.1/M2.2 的既有算式，逐字保留）────────────────────────────

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

  private static boolean supplies(Participant participant, Recipient supplier) {
    return switch (supplier) {
      case Recipient.ToActor toActor -> participant.actor.equals(toActor.actor());
      case Recipient.ToCohort toCohort ->
          participant.household != null && participant.household.equals(toCohort.cohort());
    };
  }

  private static long selfNeedOf(long population, CommodityId commodity, long days) {
    if (commodity.equals(EconomySettlement.GRAIN)) {
      return EconomyVocabulary.cumulativeRationMilli(population, days);
    }
    if (commodity.equals(EconomySettlement.CLOTH)) {
      return EconomyVocabulary.cumulativeClothMilli(population, days);
    }
    return 0L;
  }

  // ── 账户读取 / 冻结写入（唯一拼写点）────────────────────────────────────────────────

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

  private static void setFrozenGoods(
      MarketRound round, Participant participant, CommodityId commodity, long amount) {
    long value = Math.max(0L, amount);
    if (participant.household != null) {
      Map<CommodityId, Long> inner =
          new LinkedHashMap<>(
              round.householdFrozenGoods.getOrDefault(participant.household, Map.of()));
      inner.put(commodity, value);
      round.householdFrozenGoods.put(participant.household, inner);
      return;
    }
    Map<CommodityId, Long> inner =
        new LinkedHashMap<>(round.operatorFrozenGoods.getOrDefault(participant.actor, Map.of()));
    inner.put(commodity, value);
    round.operatorFrozenGoods.put(participant.actor, inner);
  }

  private static long moneyOf(MarketRound round, Participant participant, CurrencyId currency) {
    if (participant.household != null) {
      return round
          .householdMoney
          .getOrDefault(participant.household, Map.of())
          .getOrDefault(currency, 0L);
    }
    return round.operatorMoney.getOrDefault(participant.actor, Map.of()).getOrDefault(currency, 0L);
  }

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

  private static void setFrozenMoney(
      MarketRound round, Participant participant, CurrencyId currency, long amount) {
    long value = Math.max(0L, amount);
    if (participant.household != null) {
      Map<CurrencyId, Long> inner =
          new LinkedHashMap<>(
              round.householdFrozenMoney.getOrDefault(participant.household, Map.of()));
      inner.put(currency, value);
      round.householdFrozenMoney.put(participant.household, inner);
      return;
    }
    Map<CurrencyId, Long> inner =
        new LinkedHashMap<>(round.operatorFrozenMoney.getOrDefault(participant.actor, Map.of()));
    inner.put(currency, value);
    round.operatorFrozenMoney.put(participant.actor, inner);
  }

  private static long spendableMoneyOf(
      MarketRound round, Participant participant, CurrencyId currency) {
    return Math.max(
        0L, moneyOf(round, participant, currency) - frozenMoneyOf(round, participant, currency));
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
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

  /**
   * 世界里唯一在用的承运主体：{@code ORGANIZATION} 且**会话里有货币账**；多个时按 id 字典序取第一个（可复现）。
   *
   * <p>★ <b>必须要求货币账</b>：只有商品账的组织收不了运费 —— 若把它当承运人，买方的运费腿会"记了但没人收" （钱凭空消失）。没有可收款的主体就**不收运费**（{@link
   * MarketReport#freightUncollectedMilli()} 如实记下）。
   */
  private static Optional<ActorRef> carrierOf(MarketRound round) {
    List<ActorRef> carriers = new ArrayList<>();
    for (ActorRef actor : round.operatorMoney.keySet()) {
      if (actor.kind() == ActorKind.ORGANIZATION) {
        carriers.add(actor);
      }
    }
    carriers.sort(Comparator.comparing(ActorRef::id));
    return carriers.isEmpty() ? Optional.empty() : Optional.of(carriers.get(0));
  }

  /** 本轮市场里出现过的全部商品（按 id 字典序；撮合顺序因此是内容的纯函数）。 */
  private static List<CommodityId> orderedCommodities(MatchContext ctx) {
    Set<CommodityId> all = new LinkedHashSet<>();
    for (Market market : ctx.markets.values()) {
      all.addAll(market.prices().keySet());
    }
    List<CommodityId> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing(CommodityId::value));
    return sorted;
  }

  // ── 内部数据结构 ───────────────────────────────────────────────────────────────────

  /** 一条买订单的撮合槽（冻结、剩余、限价阻塞原因都在这里）。 */
  private static final class BuySlot {
    final BuyOrder order;
    final Participant buyer;
    final HexCoord hex;
    final Market market;
    final MarketRegion region;
    final CurrencyId currency;
    long remaining;
    long frozenRemaining;
    long baseFrozenMoney;
    long requestedMoney;
    long spentMilli;
    MarketUnfilledReason blocked;

    BuySlot(
        BuyOrder order,
        Participant buyer,
        HexCoord hex,
        Market market,
        MarketRegion region,
        CurrencyId currency) {
      this.order = order;
      this.buyer = buyer;
      this.hex = hex;
      this.market = market;
      this.region = region;
      this.currency = currency;
      this.remaining = order.quantity();
    }
  }

  /** 一条卖订单的撮合槽。 */
  private static final class SellSlot {
    final SellOrder order;
    final Participant seller;
    final HexCoord hex;
    final Market market;
    final MarketRegion region;
    long remaining;
    long frozenRemaining;
    long baseFrozenGoods;

    SellSlot(
        SellOrder order, Participant seller, HexCoord hex, Market market, MarketRegion region) {
      this.order = order;
      this.seller = seller;
      this.hex = hex;
      this.market = market;
      this.region = region;
      this.remaining = order.sellable();
    }
  }

  /** 一条路线的运输参数（区内即时 {@code null}）。 */
  private record RouteContext(
      HexCoord from,
      HexCoord to,
      long unitPrice,
      long freightPerUnit,
      long travelTicks,
      long costPerUnit,
      long capacityPerWindow,
      int lossPerMille,
      long arrivalTick) {
    TradeRoute toRoute() {
      return new TradeRoute(from, to, capacityPerWindow, travelTicks, costPerUnit, lossPerMille);
    }
  }

  /** 在途批次的合并键（起终点 + 商品 + 到达日；同键的票合并进同一批）。 */
  private record ShipmentKey(HexCoord from, HexCoord to, CommodityId commodity, long arrivalTick) {}

  /** 在途批次的施工坞（不可变 record 的合并先在这里做）。 */
  private static final class ShipmentBuilder {
    final RouteContext route;
    long quantity;
    final List<ShipmentAllocation> allocations = new ArrayList<>();
    String shipmentId;

    ShipmentBuilder(RouteContext route) {
      this.route = route;
    }
  }

  /** 一条路线的运力用量累计。 */
  private static final class RouteAccumulator {
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
  private static final class MatchContext {
    final MarketRound round;
    final Map<HexCoord, Market> markets;
    final MarketTopology topology;
    final MarketTrigger trigger;
    final Optional<ActorRef> carrier;
    final List<BuySlot> buys = new ArrayList<>();
    final List<SellSlot> sells = new ArrayList<>();
    final Map<ActorRef, Participant> participants = new LinkedHashMap<>();
    final Map<ActorRef, HexCoord> participantHex = new LinkedHashMap<>();
    final List<MarketReport.Fill> fills = new ArrayList<>();
    final List<MarketReport.Unfilled> unfilled = new ArrayList<>();
    final Map<String, RouteAccumulator> routes = new LinkedHashMap<>();
    final Map<ShipmentKey, ShipmentBuilder> shipments = new LinkedHashMap<>();
    long freightPaidMilli;
    long freightUncollectedMilli;
    long scheduledLossMilli;
    long immediateFills;
    long crossRegionFills;
    long shipmentSequence;

    MatchContext(
        MarketRound round,
        Map<HexCoord, Market> markets,
        MarketTopology topology,
        MarketTrigger trigger,
        Optional<ActorRef> carrier) {
      this.round = round;
      this.markets = markets;
      this.topology = topology;
      this.trigger = trigger;
      this.carrier = carrier;
    }
  }
}
