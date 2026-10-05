package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
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
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.time.YearFraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <p>★★ <b>价格（M2.6）</b>：参考价是格价表里的报价（区内成交价 = 集散节点格价、跨区 = 卖方格价）；买卖两侧的 **限价**由 {@code Market}
 * 的两个具名常量现算（bid = 卖方底价、ask = 买方限价），订单按限价过滤 —— 价差没有中间人截留， 成交仍按参考价。自适应（{@link
 * #MARKET_ADAPTIVE_PRICING_ENABLED}，2026-10-07 用户裁定打开）在每轮撮合后按供需 z 改下一轮的 **区价**（成员格同改），改价只经 {@link
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

  /*
   * ★★ P4/P6：运费的千分费率不再出自本类的"每 hex 10‰"常量，而是唯一来自
   * {@link MarketTopology#freightPerMilleBetween(HexCoord, HexCoord)}（基础距离费 + 离城辐射 − 道路瓶颈折扣 −
   * 城市折扣 + 农村惩罚；P6 的两个调整量默认 0，由组合根注入的只读函数给出）。它仍与"实际投入"（{@link
   * TradeRoute#costPerUnit()} = 距离 × moveCost）**是两个独立的数**
   * （M2.4 不许一个系数兼三职）。
   */

  /** ★ <b>在途损耗率出厂值</b>（千分数）：每程 5‰，逐票由买方承担（M2.5 基线合同）。★ GM 可调默认值。 */
  static final int MARKET_TRANSPORT_LOSS_PER_MILLE = 5;

  /**
   * ★ <b>买方可付额的回退边距</b>（毫计价货币）：货款与运费各自 {@code ⌈…⌉}，按这个边距先少买一点。
   *
   * <p>★★ <b>安全性不再靠它</b>（缺陷 A 的收口）：任何成交序列的累计付款 ≤ 买方剩余可付，由 {@link #pairUp} 每笔成交前 按当前实际账重新复核 + {@link
   * #totalCostAtMost} 的精确上限保证。本边距只保留原口径的"保守少买"行为，不让取整余数咬进预算。
   */
  static final long MARKET_MONEY_ROUNDING_MARGIN_MILLI = 2L;

  /**
   * ★★ <b>跨区结算暂设即时</b>（M2.0 #4 的具名标注）：货款与运费在发运日结清，货却在 ETA 之后到 —— 设计允许、 但读数契约必须写明。★
   * 本批不做到货付款（那要一条信用/挂账机制，属后续增量）。
   */
  static final boolean MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE = true;

  /**
   * ★★ <b>M2.6 自适应价格的开关（2026-10-07 用户裁定：打开）</b>：打开后每轮结算<b>结束</b>时按 {@code z =
   * clamp((有预算且合限价的需求 − 可出售供给) / max(需求 + 供给, ε), −1, 1)}、 {@code p_next = max(p_min, round(p × (1
   * + α·z)))} 更新**各区集散节点价**，并把同一区成员格的同商品价一并改到该值 （"每区每商品一个报价"）。
   *
   * <p>★★ <b>唯一写回路径</b>：{@link #clearOncePerCycle} 返回新的市场表，{@code EconomySettlement} 把这份表放进它交出的
   * {@code EconomyData} —— 于是 {@code markets} 作为既有的 {@code FieldDelta} 组件进变更集。本类<b>不</b>直接改任何
   * {@code EconomyData}，也没有第二处改价。
   *
   * <p>★ 固定报价模式仍是可达的：把本常量改回 {@code false}（并提供旧初态/旧档）即可逐值回到 M2.6 之前。
   * 读侧 {@link MarketReport#priceMode()} / {@link MarketReadout#adaptivePricingEnabled()} 会如实标注本轮模式。
   */
  static final boolean MARKET_ADAPTIVE_PRICING_ENABLED = true;

  /**
   * ★★ <b>自适应的步长 α（千分比/轮）</b>：{@code 50‰ = 5%} —— 计划要求的**起步上界 ≤5%/轮**。
   *
   * <p>★ 它是 GM 可调出厂值，与 {@link #MARKET_ADAPTIVE_PRICING_ENABLED} 分开：开关回答"调不调"，本值回答"每轮最多调多少"。
   */
  static final long MARKET_ADAPTIVE_ALPHA_PER_MILLE = 50L;

  /**
   * ★★ <b>自适应价格的下限 p_min</b>（毫计价货币/商品单位）：{@code 0}（2026-10-09 用户口径：取消 1 毫下限）。 价格可以一路降到
   * 0 —— 0 是<b>明确免费交易</b>（买方只出运费，货款腿为 0），不是"没有定价"；"从未定价"的商品根本不会进自适应
   * （{@link #hasEffectivePrice} 先判）。
   */
  static final long MARKET_PRICE_FLOOR_MILLI = 0L;

  /**
   * ★ <b>自适应公式里分母的 ε</b>（毫商品）：{@code 1} —— {@code max(需求 + 供给, ε)} 的分母保护，保证零供需时 {@code z = 0}
   * 而不是除零。
   */
  static final long MARKET_ADAPTIVE_Z_EPSILON_MILLI = 1L;

  /**
   * ★★ <b>D-030 §3.1：出借人的人均货币保留额</b>（毫计价货币 / 人）：默认 <b>12</b>。
   *
   * <p>★ 它是<b>GM 可调默认值</b>（不是物理常数）：与创世禀赋量级一致 —— 一个出借人必须先把"自己未覆盖的自然需求按市场价折算"
   * 留出来，再额外留 {@code 12 毫银/人} 的口粮/缓冲钱，剩下的才算可借货币。防的是"把出借人自己的口粮钱借空"，不是禁止
   * 货币出借。参数目录落地后迁入 GM 参数表。
   */
  static final long LENDER_MONEY_BUFFER_PER_CAPITA_MILLI = 12L;

  /**
   * ★★ <b>D-030：货币出借头寸每轮按余额自动派生（GM 默认开）</b>—— 本批没有显式挂单，出借人保留额之外的余额就是可借
   * 货币；关掉它 ⇒ 本轮没有货币信用（现金与借实物仍可跑）。★ 它是 GM 默认开关，不是交易规则的一部分。
   */
  static final boolean MONEY_LENDING_AUTO_LIST = true;

  /**
   * ★★ <b>D-030：卖单剩余即可借（GM 默认开）</b>—— 现金成交后卖单剩余直接成为商品可借池，不另建仓库/不复制库存；关掉它
   * ⇒ 本轮没有实物信用（货币信用仍可跑）。★ 它是 GM 默认开关，与"卖单剩余"这一事实来源无关。
   */
  static final boolean MARKET_GOODS_LENDING_ENABLED = true;

  /**
   * ★★ <b>运输损耗的记账账户</b>：{@code ProductionLedger.losses} 的键是 {@code IndustryId}，而运输不是产业 ——
   * 用一个**具名伪账户**把在途损耗与生产损耗分开（两者都进 ΣLoss，但读账分得清是谁的）。
   */
  static final IndustryId TRANSPORT_LOSS_ACCOUNT = new IndustryId("market-transport");

  private static final InstrumentId SILVER_SPECIE = MoneyVocabulary.SILVER_SPECIE.id();

  /** 市场日志（market 分类）：开市/闭市/轮次。逐笔成交在 EconomySettlement 从报告产出（同一事实只拼一次）。 */
  private static final org.slf4j.Logger MARKET = EconomyLog.market();

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
    private final Map<HouseholdId, ClassRow> rows;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
    private final Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods;
    private final Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney;
    private final Map<HouseholdId, Map<CommodityId, Long>> unmetToday;
    private final Map<ActorRef, HouseholdId> householdOfActor;
    private final Map<IndustryId, Industry> industries;

    /** ★★ R3B.2：生产单元表（键 = unit id；参与者/必要投入/卖单归属都读它）。 */
    private final Map<ProductionUnitId, ProductionUnit> units;

    /** ★★ R3B.2：实物总账（只读；必要投入的规模从它纯派生 —— 它是唯一的 quantity 真相）。 */
    private final Map<AssetShareId, AssetShare> assetShares;

    private final Map<ProductionUnitId, ProductionRelation> relations;
    private final Map<LaborAllocationId, LaborAllocation> allocations;
    private final Map<ShipmentId, ShipmentBatch> shipments;
    private final ProductionLedger.Accumulator ledger;

    /** ★ S3：经营者状态（只读；市场的原因归因与"真无法再生产"判据读它，不写它；R3B.2 起键 = unit id）。 */
    private final Map<ProductionUnitId, OperatorCondition> operatorConditions;

    /** ★★ R4-B.3a-perf：本轮参与者/必要投入/自留/家户归属共用的只读派生索引（入口构建一次）。 */
    private final SettlementIndex index;

    /**
     * ★★ R4-E2：需求账本（只读；由 {@code EconomySettlement}/{@code MarketReadout} 从 {@code
     * EconomyData.demands()} 传入）。订单路径用它把"生活保留基线 + 有效需求目标"合成买/不卖目标。
     */
    private final Map<DemandId, DemandEntry> demands;

    /**
     * ★★ <b>D-027：本轮区级市场总调控</b>（逐轮瞬态，不落盘）—— {@link MarketRegulation#anchor()} 所在的区按它施加
     * 参考价/限价/配额/开闭市/税费；其余区不受影响（单区世界里就是全区）。★ 旧调用点不给 ⇒ 语义为
     * {@link MarketRegulation#none()}（逐值现状）。
     */
    private final MarketRegulation regulation;

    /**
     * ★★ <b>D-030/D-031：本轮市场信用的入参</b>（{@code null} = 本入口没有信用能力，逐值退回现金市场）；只带
     * 到期周期，借款人侧不再有额度上限，唯一上限是放贷人实际可借头寸。
     */
    private final CreditConfig creditConfig;

    /**
     * ★★ <b>D-030：债务工作表</b>（与 {@code EconomySettlement} 的当日工作副本同一个 map；信用合同经 {@link
     * DebtContractBook#upsert} 写入，市场不另造债务表）。{@code null} = 信用关闭。
     */
    private final Map<DebtContractId, DebtContract> debts;

    /**
     * ★★ <b>D-031：市场信用的一轮入参</b>（借款人侧不再有额度；唯一上限 = 放贷人实际可借头寸）。
     *
     * @param dueCycle 新合同的到期周期（本批 = 当前周期 + 1；与 {@code lendDeficitsInHex} 同源）
     */
    record CreditConfig(long dueCycle) {
      CreditConfig {
        if (dueCycle < 0L) {
          throw new IllegalArgumentException("CreditConfig.dueCycle 不得为负: " + dueCycle);
        }
      }
    }

    MarketRound(
        long day,
        Map<HouseholdId, ClassRow> rows,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionUnit> units,
        Map<AssetShareId, AssetShare> assetShares,
        Map<ProductionUnitId, ProductionRelation> relations,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, DemandEntry> demands) {
      this(
          day,
          rows,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          allocations,
          shipments,
          ledger,
          operatorConditions,
          index,
          demands,
          MarketRegulation.none(),
          null,
          null);
    }

    /**
     * ★★ <b>D-027：带区级调控的完整构造器</b>（生产路径用；旧构造器委托 {@link MarketRegulation#none()}）。
     *
     * <p>★ 旧构造器<b>保留且行为不变</b>：{@code regulation = none()} ⇒ 参考价/限价/配额/税费退回原常量与各 hex
     * {@code Market.prices}；{@code creditConfig}/{@code debts} 为 {@code null} ⇒ 信用关闭、逐值退回现金市场。
     */
    MarketRound(
        long day,
        Map<HouseholdId, ClassRow> rows,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionUnit> units,
        Map<AssetShareId, AssetShare> assetShares,
        Map<ProductionUnitId, ProductionRelation> relations,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, DemandEntry> demands,
        MarketRegulation regulation) {
      this(day, rows, householdGoods, householdMoney, householdFrozenGoods, householdFrozenMoney,
          unmetToday,
          householdOfActor, industries, units, assetShares, relations, allocations, shipments,
          ledger, operatorConditions, index, demands, regulation, null, null);
    }

    /**
     * ★★ <b>D-030：带市场信用的完整构造器</b>（{@code MarketRound.withCredit} 的唯一出口）。
     *
     * <p>★ {@code debts} 是<b>引用</b>：市场信用经 {@link DebtContractBook#upsert} 就地累加，不复制成第二份债务表。
     */
    MarketRound(
        long day,
        Map<HouseholdId, ClassRow> rows,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionUnit> units,
        Map<AssetShareId, AssetShare> assetShares,
        Map<ProductionUnitId, ProductionRelation> relations,
        Map<LaborAllocationId, LaborAllocation> allocations,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, DemandEntry> demands,
        MarketRegulation regulation,
        CreditConfig creditConfig,
        Map<DebtContractId, DebtContract> debts) {
      this.day = day;
      this.rows = Objects.requireNonNull(rows, "rows");
      this.householdGoods = Objects.requireNonNull(householdGoods, "householdGoods");
      this.householdMoney = Objects.requireNonNull(householdMoney, "householdMoney");
      this.householdFrozenGoods =
          Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
      this.householdFrozenMoney =
          Objects.requireNonNull(householdFrozenMoney, "householdFrozenMoney");
      this.unmetToday = Objects.requireNonNull(unmetToday, "unmetToday");
      this.householdOfActor = Objects.requireNonNull(householdOfActor, "householdOfActor");
      this.industries = Objects.requireNonNull(industries, "industries");
      this.units = Objects.requireNonNull(units, "units");
      this.assetShares = Objects.requireNonNull(assetShares, "assetShares");
      this.relations = Objects.requireNonNull(relations, "relations");
      this.allocations = Objects.requireNonNull(allocations, "allocations");
      this.shipments = Objects.requireNonNull(shipments, "shipments");
      this.ledger = Objects.requireNonNull(ledger, "ledger");
      this.operatorConditions =
          operatorConditions == null ? Map.of() : Map.copyOf(operatorConditions);
      this.index = Objects.requireNonNull(index, "index");
      this.demands = demands == null ? Map.of() : demands;
      this.regulation = regulation == null ? MarketRegulation.none() : regulation;
      this.creditConfig = creditConfig;
      this.debts = debts;
    }

    /** ★★ D-030：本入口有没有市场信用能力（缺一即关闭；旧构造器因此逐值退回现金市场）。 */
    boolean creditEnabled() {
      return creditConfig != null && debts != null;
    }

    /** ★ D-030：信用入参（{@code null} = 信用关闭；订单生成与信用撮合都只读它）。 */
    CreditConfig creditConfig() {
      return creditConfig;
    }

    /** ★ D-030：债务工作表引用（信用关闭时为 {@code null}；市场不复制、不另建第二份）。 */
    Map<DebtContractId, DebtContract> debts() {
      return debts;
    }

    /**
     * ★★ <b>D-030/D-031：给已构造的市场轮补上信用入参</b>（{@code EconomySettlement} 在判定今天真的开市之后才构造
     * {@link CreditConfig}；本方法<b>不复制账户表</b>，只换信用字段）。借款人侧不再传入任何容量表。
     */
    MarketRound withCredit(long dueCycle, Map<DebtContractId, DebtContract> debts) {
      Objects.requireNonNull(debts, "debts");
      return new MarketRound(
          day,
          rows,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          allocations,
          shipments,
          ledger,
          operatorConditions,
          index,
          demands,
          regulation,
          new CreditConfig(dueCycle),
          debts);
    }

    /** ★ R4-E2：需求账本（只读；空表 = 没有 GM 需求，订单退回旧基线）。 */
    Map<DemandId, DemandEntry> demands() {
      return demands;
    }

    /** ★★ D-027：本轮区级市场总调控（逐轮瞬态；不落盘）。默认实例 = 逐值现状。 */
    MarketRegulation regulation() {
      return regulation;
    }

    /** 本轮世界日（与字段同源，不另设第二个日号）。 */
    long day() {
      return day;
    }
  }

  /** 一个参与主体：家户（{@code household != null}）或经营者（{@code household == null}）。 */
  private record Participant(
      ActorRef actor, HouseholdId household, CohortKey view, List<ProductionUnitId> units) {
    Participant {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(units, "units");
      units = List.copyOf(units);
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
   * ★★ <b>R2：一个 hex 的并行订单构建产物</b>（参与者 + 买卖槽，均按原串行遍历序）。
   *
   * <p>worker 在分区内构建、协调器按 {@code (q,r)} 升序拼回 ⇒ 与串行版本的插入序逐字相同（见 {@code clearOncePerCycle}
   * 的并行入口注释）。字段都只在本次调用内流转，不进状态、不共享。
   */
  private record HexOrderPlan(
      HexCoord hex, List<Participant> participants, List<BuySlot> buys, List<SellSlot> sells) {}

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
    Map<String, List<HouseholdId>> rowsByHex = EconomySettlement.rowsByHex(round.rows);
    for (HexCoord hex : markets.keySet()) {
      for (HouseholdId key :
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
    return planOrders(round, hex, market, commodity, EconomySettlement.rowsByHex(round.rows));
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
      Map<String, List<HouseholdId>> rowsByHex) {
    Objects.requireNonNull(round, "round");
    MarketRegulation regulation = round.regulation();
    return planOrders(
        round, hex, market, commodity, rowsByHex, regulation.anchor().toString(), regulation);
  }

  /**
   * ★★ <b>D-027：带区级调控的纯订单生成</b>（读口与结算共用同一条路径）：参考价/限价按 {@code regulation} 的
   * {@link MarketRegulation#anchor()} 所在区覆盖；旧重载委托 {@code round.regulation()} ⇒ 默认实例逐值现状。
   */
  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      Map<String, List<HouseholdId>> rowsByHex,
      String regionId,
      MarketRegulation regulation) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(commodity, "commodity");
    Objects.requireNonNull(rowsByHex, "rowsByHex");
    Objects.requireNonNull(regionId, "regionId");
    Objects.requireNonNull(regulation, "regulation");
    boolean regulated =
        regulation.defined() && regulation.anchor().toString().equals(regionId);
    long reference =
        regulatedReference(market, commodity, regulated ? regulation : MarketRegulation.none());
    // ★★ 2026-10-09：有定价行（含明确 0 价）都进订单生成；"从未定价"才不交易。
    if (!(regulated ? regulation : MarketRegulation.none()).hasPrice(market, commodity)) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（同 Market 的口径）
    }
    List<HouseholdId> keys =
        rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
    return ordersFor(round, planFor(round, hex, keys), hex, market, commodity, regulation, regulated);
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
    return clearOncePerCycle(
        markets, round, trigger, topology, EconomyParallelism.singleThreaded());
  }

  /**
   * ★★ <b>R2 并行入口</b>：① 按<b>市场区</b>并行构建参与者/买卖订单（P1.2 扫描热点）；② 按<b>市场区</b>并行执行区内撮合 —— worker
   * 只在<b>本区账户副本</b>上算出 {@code FillIntent}（不可变意向），协调器再按<b>拓扑区序</b>把每个区的意向经唯一写口 {@code
   * EconomySettlement.applyTransfer} 回放到真实会话（冻结写只发生在协调器阶段，M8）。
   *
   * <p>★★ <b>为什么 worker 要拿账户副本</b>：同一区域的多个主体共用账户表，撮合是"先扣后加、再判断"的可变计算；直接让多个 worker 写 {@code
   * MarketRound} 的八张共享表是数据竞争。⇒ 每个区在 worker 内建一份<b>本区参与者的账户副本</b>（外层表复制、内层表只读换新）， 用与串行路径
   * <b>逐字同一条</b> {@code matchGroup/pairUp/executeTrade} 算完本区的成交序列；协调器按区序回放这些成交（同一套算式、同一套写口），
   * 因此最终账户/冻结/读数与串行路径逐值相同，且线程数不改变任何判定。
   *
   * <p>★★ <b>撮合顺序</b>：回放序 = {@code topology.regions()} 的拓扑区序 × 区内 {@code orderedCommodities} 商品序 ×
   * worker 的 canonical 槽位序（{@code ctx.buys}/{@code ctx.sells} 的全局插入序）。三者都是内容的纯函数，与 worker 完成顺序、线程
   * id 无关；因此 1/4/8 线程产出的成交序列、账户终态与报告逐值相同。★ 跨区撮合仍是协调器单线程（跨区一笔同时触碰两个区的路线/在途/运费， 属 R3 的 P3
   * 跨区协调）；本轮只把区内并行做实，并用 P1.2 索引去掉它的全表扫描。
   */
  static MarketOutcome clearOncePerCycle(
      Map<HexCoord, Market> markets,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism) {
    // ★ P10.2 兼容入口：没有 merchantFirms 的世界走旧承运路径（逐值不变）。
    return clearOncePerCycle(
        markets, round, trigger, topology, parallelism, Map.of(), MerchantSettlement.CarrierPool.empty());
  }

  /**
   * ★★ <b>P10.2 承运商入口</b>：{@code merchantFirms} 非空时每条跨区 lane 由 {@link MerchantSettlement.CarrierPool}
   * 现选商号（服务半径/剩余运力/到货费率序），买方 CARRIER_FEE 直接付给 principal 家户；为空时退回旧 {@link #carrierOf}。
   */
  static MarketOutcome clearOncePerCycle(
      Map<HexCoord, Market> markets,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      Map<ProductionOrganizationId, MerchantFirm> merchantFirms,
      MerchantSettlement.CarrierPool carrierPool) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(parallelism, "parallelism");
    Objects.requireNonNull(merchantFirms, "merchantFirms");
    Objects.requireNonNull(carrierPool, "carrierPool");
    if (MARKET.isDebugEnabled()) {
      MARKET.debug(
          "event=MARKET_ROUND_START day={} trigger={} markets={} regions={} merchantFirms={} rows={} creditEnabled={}",
          round.day,
          trigger,
          markets.size(),
          topology.regions().size(),
          merchantFirms.size(),
          round.rows.size(),
          round.creditEnabled());
    }
    boolean merchantWorld = !merchantFirms.isEmpty();
    Optional<ActorRef> carrier = merchantWorld ? Optional.empty() : carrierOf(round);
    MatchContext ctx =
        new MatchContext(round, markets, topology, carrier, merchantFirms, carrierPool);
    if (markets.isEmpty() || trigger == MarketTrigger.NONE) {
      return new MarketOutcome(
          MarketReport.empty(round.day, trigger, carrierPresent(ctx)), markets);
    }
    // ★★ D-027：闭市 = 该区本轮不撮合（返回空报告；trigger 不变、不抛）。排在冻结之前 ⇒ 不产生任何冻结/成交。
    if (!ctx.regulation.open()) {
      return new MarketOutcome(
          MarketReport.empty(round.day, trigger, carrierPresent(ctx)), markets);
    }

    // ── 1. 逐格建计划与订单；参与表按 actor 去重（订单生成与撮合的唯一来源）──────────────────────
    //   ★★ R2：按市场区并行构建，再按 hex (q,r) 序拼回 —— 与原串行序逐字相同（见方法注释）。
    //   ★★ C4：rowsByHex 只在这里建一次（旧 R2 让每个分区 worker 各自重建一次），只读传给 worker。
    //   ★★ 并行安全：worker 读的必须是**普通只读表**，不能是 AccountSession 的活视图（owner 守卫在 worker 线程
    //     第一次 get 就抛）⇒ 协调器先把八张账户表浅拷成 planningRound，worker 只读它。
    Map<String, List<HouseholdId>> rowsByHex = EconomySettlement.rowsByHex(round.rows);
    MarketRound planningRound = readOnlyPlanningRound(round);
    TreeMap<String, List<HexCoord>> hexesByRegion = new TreeMap<>();
    for (HexCoord hex : markets.keySet()) {
      hexesByRegion
          .computeIfAbsent(topology.regionOf(hex).node().nodeId(), ignored -> new ArrayList<>())
          .add(hex);
    }
    for (List<HexCoord> regionHexes : hexesByRegion.values()) {
      regionHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.LOCAL_MARKET, hexesByRegion.keySet(), parallelism.partitionCount());
    List<List<HexOrderPlan>> plansByPartition =
        SettlementExecutor.execute(
            plan,
            partition -> {
              List<HexOrderPlan> planned = new ArrayList<>();
              for (String regionId : partition.canonicalKeys()) {
                for (HexCoord hex : hexesByRegion.get(regionId)) {
                  Market market = markets.get(hex);
                  List<HouseholdId> keys =
                      rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
                  HexPlan hexPlan = planFor(planningRound, hex, keys);
                  if (hexPlan.participants.isEmpty()) {
                    continue;
                  }
                  Map<ActorRef, Participant> byActor = new LinkedHashMap<>();
                  for (Participant participant : hexPlan.participants) {
                    byActor.put(participant.actor, participant);
                  }
                  List<BuySlot> buys = new ArrayList<>();
                  List<SellSlot> sells = new ArrayList<>();
                  MarketRegion region = topology.regionOf(hex);
                  for (Map.Entry<CommodityId, Long> priced : market.prices().entrySet()) {
                    CommodityId commodity = priced.getKey();
                    // ★★ 2026-10-09：有这一行就是"已定价"—— 值为 0 = 明确免费交易，不能再按 <=0 当缺价跳过。
                    //    "从未定价"的商品根本不在 market.prices() 里，这个循环天然不会碰它。
                    MarketRegulation regionRegulation =
                        ctx.regulationFor(region.node().nodeId());
                    PlannedOrders orders =
                        ordersFor(
                            planningRound,
                            hexPlan,
                            hex,
                            market,
                            commodity,
                            regionRegulation,
                            regionRegulation.defined());
                    for (BuyOrder order : orders.buys()) {
                      Participant buyer = byActor.get(order.requester());
                      if (buyer == null) {
                        throw new IllegalStateException(
                            "买订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.requester());
                      }
                      buys.add(new BuySlot(order, buyer, hex, region, market.numeraire()));
                    }
                    for (SellOrder order : orders.sells()) {
                      Participant seller = byActor.get(order.supplier());
                      if (seller == null) {
                        throw new IllegalStateException(
                            "卖订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.supplier());
                      }
                      sells.add(new SellSlot(order, seller, hex, market, region, planningRound));
                    }
                  }
                  planned.add(
                      new HexOrderPlan(hex, List.copyOf(hexPlan.participants), buys, sells));
                }
              }
              return planned;
            },
            parallelism.poolOrNull());
    List<HexOrderPlan> orderedPlans = new ArrayList<>();
    for (List<HexOrderPlan> partitionPlans : plansByPartition) {
      orderedPlans.addAll(partitionPlans);
    }
    // ★ 拼回序 = 原先的 hex (q,r) 升序（分区只影响谁先算完，不影响拼回后的顺序）。
    orderedPlans.sort(
        Comparator.comparingInt((HexOrderPlan entry) -> entry.hex().q())
            .thenComparingInt(entry -> entry.hex().r()));
    for (HexOrderPlan ordered : orderedPlans) {
      for (Participant participant : ordered.participants()) {
        ctx.participants.put(participant.actor, participant);
        ctx.participantHex.put(participant.actor, ordered.hex());
      }
      ctx.buys.addAll(ordered.buys());
      ctx.sells.addAll(ordered.sells());
    }
    // ★ 槽位的全局下标 = 它们在 ctx.buys / ctx.sells 里的位置；worker 产出的 FillIntent 用它定位回放目标。
    for (int i = 0; i < ctx.buys.size(); i++) {
      ctx.buys.get(i).orderIndex = i;
    }
    for (int i = 0; i < ctx.sells.size(); i++) {
      ctx.sells.get(i).orderIndex = i;
    }

    // ── 1b. P1.2：每轮只建一次的只读索引（区内/跨区/邻接/商品序）—— worker 只读，不持有可写状态 ─────
    MarketIndexes indexes = MarketIndexes.build(ctx);

    // ── 2. 冻结（M1.2 的写者接上）：挂单即占用；成交/发运/轮末释放 ────────────────────────
    commitFreezes(ctx);
    try {
      // ── 3. 区内优先：按市场区并行计算 FillIntent，协调器按拓扑区序回放（唯一写口仍 applyTransfer）
      matchWithinRegions(ctx, parallelism, indexes);
      // ── 4. 跨区候选（第一版只考直接邻接供应区；P1.2 索引去掉全表扫描，协调器单线程）──────────────
      matchAcrossRegions(ctx, indexes);
    } finally {
      releaseAllFreezes(ctx);
    }

    // ── 4b. ★★ D-030 市场信用：现金撮合（含区内/跨区）之后的借钱买货 → 借实物 ───────────────────
    //   ★ 位置：冻结已释放之后、未成交原因落档之前。冻结释放让"可花货币"回到市场轮的真实可用额（不被本轮临时挂单
    //     承诺占住）；信用成交消耗的是各主体的真实余额，转移仍全走唯一写口 applyTransfer。
    //   ★ 顺序：按"买方稳定序"逐户处理；每个买方先货币（钱优先），货币借不到/不够才用商品卖单剩余借实物。
    creditRound(ctx, indexes);

    // ── 5. 未成交原因（不聚合丢失；买卖两侧分开）────────────────────────────────────────
    collectUnfilled(ctx, indexes);

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
    //   在撮合之后改下一轮的参考价。★ 开关判据收在 adaptPrices 内部、这里**无条件调用** ——
    //   ★ 开关判据收在 adaptPrices 内部、这里**无条件调用**；即使将来把常量改回 false，也能保证方法在字节码里存在。
    AdaptivePrices adapted = adaptPrices(markets, ctx);
    return new MarketOutcome(
        MarketReport.withRegulatedTariff(
            round.day,
            trigger,
            carrierPresent(ctx),
            ctx.fills,
            ctx.unfilled,
            routeUsages,
            ctx.freightPaidMilli,
            ctx.freightUncollectedMilli,
            ctx.scheduledLossMilli,
            ctx.immediateFills,
            ctx.crossRegionFills,
            priceMode(),
            adapted.updates(),
            ctx.sellerOutcomes,
            ctx.buyerOutcomes,
            ctx.creditFills,
            ctx.tariffByFill),
        adapted.markets());
  }

  /** 本进程当前的报价模式（M2.6 的开关只有一个：2026-10-07 起默认自适应）。 */
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
   * ★★ <b>按供需 z 逐区改价</b>（M2.6 自适应；开关判据在本方法内，调用点无条件）—— 开关关时立即原样交回入参价格表。
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
    if (!MARKET_ADAPTIVE_PRICING_ENABLED) {
      // ★ 固定报价回退：开关关时价格表逐值原样带过、更新表为空。判断放在这里而不是调用点，保证方法在字节码里存在。
      return new AdaptivePrices(List.of(), markets);
    }
    // 逐 (region, commodity) 汇总订单；region 用拓扑对象本身当键（它由 node+members 派生，等值即同区）。
    Map<MarketRegion, Map<CommodityId, long[]>> byRegion = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      // ★★ 2026-10-09 红字修复：信用世界里的挂单量 = 整笔缺口（不按现金封顶），直接喂给自适应公式会让
      //   需求对价格**完全不敏感** ⇒ 价格每轮 multiplicative 上涨（实测 cloth 涨到 1.2e10 毫/单位，
      //   跨区布匹贸易因此停滞、商号运费实收归 0）。这里把需求折回"按当前参考价真正付得起的量"：
      //   价格越涨、有效需求越小，公式才能在供需处收敛。信用能补的那部分不在价格信号里重复放大。
      CommodityId commodity = buy.order.commodity();
      long quantity = buy.order.quantity();
      Market buyMarket = ctx.markets.get(buy.hex);
      if (buyMarket == null) {
        buyMarket = ctx.markets.get(buy.region.anchor());
      }
      if (buyMarket != null) {
        long reference = ctx.referencePriceOf(buy.regionId, buyMarket, commodity);
        if (reference > 0L) {
          long affordable =
              safeMulDiv(
                  payableMoneyOf(ctx, buy), EconomySettlement.MILLI_PER_GRAIN, reference);
          quantity = Math.min(quantity, affordable);
        }
      }
      byRegion
          .computeIfAbsent(buy.region, ignored -> new LinkedHashMap<>())
          .computeIfAbsent(commodity, ignored -> new long[2])[0] += quantity;
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
        // ★★ 2026-10-09：有定价行（含明确 0 价）才自适应；"从未定价"的商品跳过（不凭空造一行）。
        if (!anchorMarket.hasPrice(commodity)) {
          continue;
        }
        long reference = anchorMarket.priceOf(commodity);
        long[] demandSupply = quantities.get(commodity);
        long demand = demandSupply == null ? 0L : demandSupply[0];
        long supply = demandSupply == null ? 0L : demandSupply[1];
        long next = adaptiveNextPrice(reference, demand, supply);
        if (next == reference) {
          continue;
        }
        for (HexCoord member : region.members()) {
          Market current = updated.get(member);
          if (current == null || !current.hasPrice(commodity)) {
            continue; // 成员格没给该商品定价（含"明确 0 价"仍可被区价覆盖）
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
    if (price < 0L) {
      throw new IllegalArgumentException("自适应价格的入参 price 不得为负: " + price);
    }
    // ★★ 0 价态（明确免费交易）：乘法公式在 0 上恒为 0，故显式给一条具名的"退出免费态"规则 ——
    //   需求 > 供给 ⇒ 下一轮从 1 毫重新起步；供给 ≥ 需求 ⇒ 维持 0（免费）。这是粗口径，不是第二套定价公式。
    if (price == 0L) {
      return demand > supply ? 1L : 0L;
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

  /**
   * ★★ <b>D-027：一个 hex 所属区的规范 id</b>（与 {@link MarketRegion#node()}{@code .nodeId()} 同源；不在任何区 ⇒
   * null，不抛）。调控的区级归属判定 = {@code regionIdAt(hex).equals(regulation.anchor().toString())}。
   */
  private static String regionIdAt(MarketTopology topology, HexCoord hex) {
    if (topology == null || hex == null || !topology.contains(hex)) {
      return null;
    }
    return topology.regionOf(hex).node().nodeId();
  }

  /** 有效参考价：调控覆盖优先，未覆盖 ⇒ {@link Market#priceOf}（唯一拼写点）。 */
  private static long regulatedReference(
      Market market, CommodityId commodity, MarketRegulation regulation) {
    return regulation.referencePriceOf(market, commodity);
  }

  /** 有效卖方底价：调控覆盖优先，未覆盖 ⇒ {@link Market#bidPriceOf}（唯一拼写点）。 */
  private static long regulatedBid(
      Market market, CommodityId commodity, MarketRegulation regulation) {
    return regulation.defined()
        ? regulation.bidPriceOf(market, commodity)
        : market.bidPriceOf(commodity);
  }

  /** 有效买方限价：调控覆盖优先，未覆盖 ⇒ {@link Market#askPriceOf}（唯一拼写点）。 */
  private static long regulatedAsk(
      Market market, CommodityId commodity, MarketRegulation regulation) {
    return regulation.defined()
        ? regulation.askPriceOf(market, commodity)
        : market.askPriceOf(commodity);
  }

  /** 生成一个格 × 一个商品上的全部订单（主体各自生成；见类注的算式）。默认 regulation ⇒ 逐值现状。 */
  private static PlannedOrders ordersFor(
      MarketRound round, HexPlan plan, HexCoord hex, Market market, CommodityId commodity) {
    return ordersFor(round, plan, hex, market, commodity, MarketRegulation.none(), false);
  }

  /**
   * ★★ <b>D-027：带区级调控的订单生成</b>：{@code regulated == true} 时参考价/限价按 {@code regulation} 覆盖
   * （{@link MarketRegulation#referencePriceOf}/{@link MarketRegulation#bidPriceOf}/{@link
   * MarketRegulation#askPriceOf} 是唯一拼写点）；成交仍按参考价（区内）/ 卖方格参考价（跨区）—— 调控<b>不</b>改
   * 逐 hex 物流成本，单 hex 损耗也<b>不</b>承担价格职能。
   */
  private static PlannedOrders ordersFor(
      MarketRound round,
      HexPlan plan,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      MarketRegulation regulation,
      boolean regulated) {
    // ★★ M2.6：参考价 = 本格价表里的固定报价；两个限价由 Market 的两个**各自独立**的常量现算
    //   （bid = 卖方底价、ask = 买方限价），订单按它们过滤；成交仍按参考价（区内）/ 卖方格参考价（跨区）。
    // ★★ D-027：调控覆盖只对"这一格所属区的锚格 == regulation.anchor()"生效；未覆盖时逐值退回上面的口径。
    MarketRegulation effective = regulated ? regulation : MarketRegulation.none();
    // ★★ 2026-10-09：先区分"从未定价"（不交易）与"明确 0 价"（免费交易）。有定价行 ⇒ 可挂单；值为 0 ⇒ 货款腿 0，
    //    买方只承担运费（运费与价格解耦，见 freightUnitMilli）。
    if (!effective.hasPrice(market, commodity)) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（不凭空造一行）
    }
    long reference = regulatedReference(market, commodity, effective);
    long bid = regulatedBid(market, commodity, effective);
    long ask = regulatedAsk(market, commodity, effective);
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
      // ── 卖：可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留基线 − 有效需求目标) ─────
      //   ★★ 需求目标同时进"不卖"一侧：要买的粮/布（或任何新商品）不许在同一轮又被当余量卖掉。
      long retention = Math.addExact(life, demandTarget);
      long sellable = Math.max(0L, stock - frozen - necessary - retention);
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
      // ── 买：家户补到"生活保留基线 + 有效需求目标"；经营者补到必要生产投入 ─────────────────
      //   ★ M2.4：目标缺口 = target − 可用 − **该时限前确定到货**（在途批次里买方那一份；M2.1 的原文）。
      //   ★★ R4-E2 的预算优先级：先保生活保留基线，再按 demands 的 priority 升序（同 priority 按
      //     DemandId）逐个扣"按参考价折算的买得起量"；下层需求拿上一层剩下的额度。
      long baseTarget = participant.household != null ? life : necessary;
      long incoming = confirmedIncoming(round, participant.actor, commodity, deadline);
      long budget = spendableMoneyOf(round, participant, market.numeraire());
      // ★★ 0 价免费交易：货款腿为 0 ⇒ 数量不受"货款买得起"约束（只受缺口约束）；运费仍由撮合阶段按
      //    route.freightPerUnit 逐笔复核（家户与经营者同口径）。未定价的商品已在方法开头整行返回。
      long cashAffordable =
          reference == 0L
              ? Long.MAX_VALUE
              : budget * EconomySettlement.MILLI_PER_GRAIN / reference;
      // ★★ D-031：借款人侧不再有额度上限。家户把"目标缺口 + 需求缺口"整笔挂出来（现金撮合仍只按真实预算付，
      //    剩余由信用撮合按放贷人实际可借头寸补）；经营者不参与信用 ⇒ 仍按现金买得起量封顶。
      boolean creditDemand = participant.household != null && round.creditEnabled();
      long quantity =
          creditDemand
              ? desiredQuantity(baseTarget, demandParts, available, incoming)
              : allocateQuantity(baseTarget, demandParts, available, incoming, cashAffordable);
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
              new Budget(budget, SILVER_SPECIE),
              SILVER_SPECIE));
    }
    return new PlannedOrders(buys, sells);
  }

  /**
   * ★★ <b>D-031：借款人侧无额度时，一个家户在"目标缺口 + 需求缺口"上的全额挂单量</b>（毫商品）。
   *
   * <p>现金撮合仍只按真实预算付钱；这里挂出来的全部缺口由后续信用撮合按放贷人实际可借头寸补。经营者不参与信用 ⇒
   * 仍走 {@link #allocateQuantity} 的现金封顶口径。
   */
  private static long desiredQuantity(
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
  private static long safeAdd(long left, long right) {
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
  private static long allocateQuantity(
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
    // 卖：每条卖单全额冻结（绝对值 = 底值 + 承诺）。★ P1.3：按 (seller, commodity) 轴一次求和，不再逐条扫全表。
    ctx.sellFrozenSums.clear();
    for (SellSlot sell : ctx.sells) {
      sell.baseFrozenGoods = frozenGoodsOf(ctx.round, sell.seller, sell.order.commodity());
      sell.frozenRemaining = sell.order.sellable();
      ctx.sellFrozenSums.merge(sellFrozenAxis(sell), sell.frozenRemaining, Long::sum);
    }
    for (SellSlot sell : ctx.sells) {
      refreshSellFrozen(ctx, sell); // O(1)：读轴累计值（见 MatchContext.sellFrozenSums）
    }
    // 买：逐主体把"最多要花多少钱"按可花余额封顶后冻结（预算独立算，但承诺不许超过钱包）。
    //   ★ P1.3：先把全部分组的 frozenRemaining 定完，再按 (buyer, currency) 轴一次写冻结表。
    ctx.buyFrozenSums.clear();
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
    }
    for (BuySlot buy : ctx.buys) {
      ctx.buyFrozenSums.merge(buyFrozenAxis(buy), buy.frozenRemaining, Long::sum);
    }
    for (BuySlot buy : ctx.buys) {
      refreshBuyFrozen(ctx, buy); // O(1)：读轴累计值
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
    ctx.buyFrozenSums.clear();
    ctx.sellFrozenSums.clear();
  }

  /**
   * ★ P1.3：卖单冻结的轴键 = {@code (seller actor, commodity)}。一个主体的全部卖单必在同一市场区（账户在唯一格），因此 worker
   * 的本区副本上的轴累计值与全局轴累计值一致。
   */
  private static String sellFrozenAxis(SellSlot sell) {
    return sell.seller.actor.kind()
        + ":"
        + sell.seller.actor.id()
        + ":"
        + sell.order.commodity().value();
  }

  /** ★ P1.3：买单冻结的轴键 = {@code (buyer actor, currency)}（与 commitFreezes 的分组键逐字同源）。 */
  private static String buyFrozenAxis(BuySlot buy) {
    return buy.buyer.actor.kind() + ":" + buy.buyer.actor.id() + ":" + buy.currency.value();
  }

  /** 刷新卖方商品冻结为绝对值：{@code base + 该轴剩余承诺}`（轴累计值由 commitFreezes/executeTrade 增量维护）。 */
  private static void refreshSellFrozen(MatchContext ctx, SellSlot sell) {
    long sum = ctx.sellFrozenSums.getOrDefault(sellFrozenAxis(sell), 0L);
    setFrozenGoods(ctx.round, sell.seller, sell.order.commodity(), sell.baseFrozenGoods + sum);
  }

  /** 刷新买方货币冻结为绝对值：{@code base + 该轴剩余承诺}`（轴累计值由 commitFreezes/executeTrade 增量维护）。 */
  private static void refreshBuyFrozen(MatchContext ctx, BuySlot buy) {
    long sum = ctx.buyFrozenSums.getOrDefault(buyFrozenAxis(buy), 0L);
    setFrozenMoney(ctx.round, buy.buyer, buy.currency, buy.baseFrozenMoney + sum);
  }

  /** 从卖冻结轴累计值里扣掉已释放量（成交释放冻结时调用；负值 = 轴账与槽位漂开 ⇒ fail-closed）。 */
  private static void releaseSellFrozenSum(MatchContext ctx, SellSlot sell, long released) {
    if (released == 0L) {
      return;
    }
    String axis = sellFrozenAxis(sell);
    long sum = ctx.sellFrozenSums.getOrDefault(axis, 0L) - released;
    if (sum < 0L) {
      throw new IllegalStateException("卖冻结轴累计值被扣成负数（槽位与轴账漂开）：轴=" + axis + " 释放=" + released);
    }
    ctx.sellFrozenSums.put(axis, sum);
  }

  /** 从买冻结轴累计值里扣掉已释放量（成交释放冻结时调用；负值 = 轴账与槽位漂开 ⇒ fail-closed）。 */
  private static void releaseBuyFrozenSum(MatchContext ctx, BuySlot buy, long released) {
    if (released == 0L) {
      return;
    }
    String axis = buyFrozenAxis(buy);
    long sum = ctx.buyFrozenSums.getOrDefault(axis, 0L) - released;
    if (sum < 0L) {
      throw new IllegalStateException("买冻结轴累计值被扣成负数（槽位与轴账漂开）：轴=" + axis + " 释放=" + released);
    }
    ctx.buyFrozenSums.put(axis, sum);
  }

  // ── D-030 市场信用：现金撮合之后的"借钱买货 → 借实物" ─────────────────────────────────────

  /** ★ D-030 的市场信用条款（与 {@code lendDeficitsInHex} 同一利率常量、同一 legacy 维；身份 ⇒ 同债权人的借入累加）。 */
  private static final DebtTerms MARKET_CREDIT_TERMS =
      DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE);

  /**
   * ★★ <b>D-030 市场信用撮合</b>（在现金买卖之后、未成交归因之前跑一次）：
   *
   * <pre>
   * 每个仍有缺口的家户买方，按现金撮合的稳定序（拓扑区序 × 商品 id 序 × 全局买槽插入序）：
   *   ① 自己的现金已在现金撮合里花完；
   *   ② 先借货币：出借人可借额 = max(0, 可花货币 − reserve)，按费率升序 → 可借额降序 →
   *      出借人 actor id 升序取；借入额 = min(缺口货款, 出借人可借额)；货物必须同时存在
   *      （原子绑定：没有可买货物就不放贷）；
   *   ③ 货币借不到/不够时借实物：从现金成交后的卖单剩余（且卖家是家户、能成为债权人）
   *      按"可借数量降序 → 商品 id 升序 → 卖家 actor id 升序"取；货腿 卖家 → 买方（LOAN_PRINCIPAL）。
   * ★★ D-031：借款人侧没有额度上限；每笔成交只受"放贷人剩余可借头寸"限制，借空即止。
   * </pre>
   *
   * <p>★★ <b>唯一写口不变</b>：货币/商品换手全部经 {@code EconomySettlement.applyTransfer}；债务经 {@link
   * DebtContractBook#upsert}；市场只改槽位 remaining / 合同表 / 债务人行的派生引用。借货币买货对卖方仍是一笔现金销售
   * （连同 {@link MarketReport.Fill} 与 MARKET_TRADE 两条腿），借实物只写货腿与 {@link MarketReport.CreditFill}。
   *
   * <p>★ <b>本批口径（与设计文档的差异均在此具名）</b>：① 单区（D-027）：只从买方所在区取货币/商品头寸；② 债权人/债务人
   * 必须是家户（{@link DebtContract} 的两端是 {@code HouseholdId}，经营者 actor 没有这一身份）⇒ 经营者只参与现金买卖，
   * 不作为放贷人/借实物卖方/债务人；③ 不跨区、不承运、不聚集。
   */
  private static void creditRound(MatchContext ctx, MarketIndexes indexes) {
    if (!ctx.creditEnabled()) {
      return;
    }
    if (!MONEY_LENDING_AUTO_LIST && !MARKET_GOODS_LENDING_ENABLED) {
      return;
    }
    MarketRound round = ctx.round;
    // ★★ D-031：借款人侧不再有额度/头寸上限 —— 信用撮合直接以放贷人可借池为唯一上限。
    CreditPools pools = CreditPools.build(ctx, indexes);
    // ② 稳定买方序：与 matchWithinRegions 的 (区序 × 商品序 × 买槽插入序) 逐字同源。
    for (MarketRegion region : ctx.topology.regions()) {
      Market anchor = ctx.markets.get(region.anchor());
      if (anchor == null) {
        continue;
      }
      String regionId = region.node().nodeId();
      Map<CommodityId, List<BuySlot>> buysByCommodity =
          indexes.buysByRegionCommodity.getOrDefault(regionId, Map.of());
      for (CommodityId commodity : indexes.commodities) {
        List<BuySlot> slots = buysByCommodity.getOrDefault(commodity, List.of());
        if (slots.isEmpty()) {
          continue;
        }
        long price = ctx.referencePriceOf(regionId, anchor, commodity);
        if (!anchor.hasPrice(commodity)) {
          continue; // 从未定价 ⇒ 不交易，也谈不上信用
        }
        if (price == 0L) {
          continue; // 明确 0 价 ⇒ 货款腿为 0，不需要货款信用（运费由现金腿承担）
        }
        for (BuySlot buy : slots) {
          if (buy.remaining <= 0L || buy.buyer.household == null) {
            continue;
          }
          if (buy.order.latestArrivalTick() < round.day
              || buy.order.maxLandedPrice() < price) {
            continue; // 与现金撮合的 activeBuys 同一组门槛（信用不改变到货时限/买方限价）
          }
          creditForBuy(ctx, buy, price, pools);
        }
      }
    }
    pools.recordRemaining(ctx);
  }

  /** 一个买方的信用顺序：钱优先；钱不够（或没有）才借实物。 */
  private static void creditForBuy(MatchContext ctx, BuySlot buy, long price, CreditPools pools) {
    if (MONEY_LENDING_AUTO_LIST) {
      moneyCreditForBuy(ctx, buy, price, pools);
    }
    if (MARKET_GOODS_LENDING_ENABLED && buy.remaining > 0L) {
      goodsCreditForBuy(ctx, buy, price, pools);
    }
  }

  /**
   * ★★ <b>D-030 ②a：借钱买货</b>（D-031 起借款人无额度上限）。逐出借人取 {@code amount = min(缺口货款, 出借人可借额)}；
   * 由 amount 反解能买的量（货款 ceil），货物必须同时在卖方剩余里 ⇒ 三腿原子落地：出借人→买方（LOAN_PRINCIPAL）、
   * 买方→卖方（MARKET_TRADE 货款）、卖方→买方（MARKET_TRADE 货）。
   */
  private static void moneyCreditForBuy(
      MatchContext ctx, BuySlot buy, long price, CreditPools pools) {
    HouseholdId borrower = buy.buyer.household;
    if (borrower == null) {
      return;
    }
    String regionId = buy.regionId;
    List<MoneyLendOrder> lenders = pools.moneyLenders(regionId);
    int cursor = pools.moneyCursor(regionId);
    int index = cursor;
    while (buy.remaining > 0L) {
      SellSlot sell = pools.bestCashSeller(regionId, buy.order.commodity());
      if (sell == null) {
        break; // 没有可买货物 ⇒ 不放贷（原子绑定）
      }
      while (index < lenders.size()) {
        MoneyLendOrder candidate = lenders.get(index);
        if (candidate.remaining <= 0L
            || !candidate.currency.equals(buy.currency)
            || candidate.lender.household.equals(borrower)) {
          index++;
          continue;
        }
        break;
      }
      if (index >= lenders.size()) {
        break;
      }
      MoneyLendOrder lender = lenders.get(index);
      long gapPayment = paymentForQuantity(buy.remaining, price);
      long amount = Math.min(gapPayment, lender.remaining);
      if (amount <= 0L) {
        break;
      }
      long quantity =
          Math.min(
              buy.remaining, Math.min(sell.remaining, maxQuantityForMoney(amount, price)));
      if (quantity <= 0L) {
        if (amount == lender.remaining) {
          index++; // 这个出借人太小，买不起一个最小交易单位；看下一个
          continue;
        }
        break;
      }
      long quotaLeft = ctx.quotaRemaining(sell.region, buy.order.commodity());
      if (quotaLeft <= 0L) {
        ctx.markQuotaExhausted(sell.region, buy.order.commodity());
        break;
      }
      if (quantity > quotaLeft) {
        quantity = quotaLeft;
        if (quantity <= 0L) {
          ctx.markQuotaExhausted(sell.region, buy.order.commodity());
          break;
        }
      }
      long payment = paymentForQuantity(quantity, price);
      if (payment <= 0L || payment > amount) {
        break; // 理论到不了；到得了就是算法漂开，停在本档不超借
      }
      executeMoneyCredit(ctx, buy, sell, lender, payment, quantity, price, pools);
      while (cursor < lenders.size() && lenders.get(cursor).remaining <= 0L) {
        cursor++;
      }
      if (index < cursor) {
        index = cursor;
      }
    }
    pools.putMoneyCursor(regionId, cursor);
  }

  /**
   * ★★ <b>D-030/D-031 ②b：借实物</b>。从卖单剩余（卖家须是家户，才能成为 {@link DebtContract} 的债权人）按"可借数量
   * 降序 → 商品 id 升序 → 卖家 actor id 升序"取；量 = min(缺口, 卖单剩余, 配额剩余)，货腿 卖家 → 买方
   * （{@code LOAN_PRINCIPAL}），无货币腿、也不写普通 sale fill。★ D-031：借款人侧不再设额度上限。
   */
  private static void goodsCreditForBuy(
      MatchContext ctx, BuySlot buy, long price, CreditPools pools) {
    HouseholdId borrower = buy.buyer.household;
    if (borrower == null) {
      return;
    }
    String regionId = buy.regionId;
    CommodityId commodity = buy.order.commodity();
    Set<SellSlot> skippedForThisBuyer = new LinkedHashSet<>();
    while (buy.remaining > 0L) {
      SellSlot sell =
          pools.bestGoodsSeller(regionId, commodity, skippedForThisBuyer);
      if (sell == null) {
        break;
      }
      if (sell.seller.household == null || sell.seller.household.equals(borrower)) {
        skippedForThisBuyer.add(sell); // 不是可成立合同的两端（自借自买不是一笔信用）
        continue;
      }
      long quantity = Math.min(buy.remaining, sell.remaining);
      long quotaLeft = ctx.quotaRemaining(sell.region, commodity);
      if (quotaLeft <= 0L) {
        ctx.markQuotaExhausted(sell.region, commodity);
        break;
      }
      quantity = Math.min(quantity, quotaLeft);
      if (quantity <= 0L) {
        skippedForThisBuyer.add(sell); // 配额已空；换个卖家也没用，但保持保守
        continue;
      }
      executeGoodsCredit(ctx, buy, sell, quantity, pools);
    }
  }

  /** 借货币买货的三腿 + 债务合同 + 读数（唯一 applier / 唯一债务写口）。 */
  private static void executeMoneyCredit(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      MoneyLendOrder lender,
      long payment,
      long quantity,
      long price,
      CreditPools pools) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    long quota = ctx.quotaRemaining(sell.region, commodity);
    if (quantity > quota) {
      throw new IllegalStateException("信用成交越过区级配额（调用方应先封顶）：" + quantity + " > " + quota);
    }
    ctx.consumeQuota(sell.region, commodity, quantity);
    // ① 出借人 → 买方：本金腿（LOAN_PRINCIPAL）。
    applyMarketLeg(
        ctx,
        round.ledger.mint(
            lender.lender.actor,
            buy.buyer.actor,
            lender.hex,
            Map.of(),
            Map.of(buy.currency, payment),
            TransferReason.LOAN_PRINCIPAL));
    // ② 卖方 → 买方：货腿（MARKET_TRADE，毛量）。
    applyMarketLeg(
        ctx,
        round.ledger.mint(
            sell.seller.actor,
            buy.buyer.actor,
            sell.hex,
            Map.of(commodity, quantity),
            Map.of(),
            TransferReason.MARKET_TRADE));
    long loss = creditLossMilli(ctx, sell, buy, quantity);
    if (loss > 0L) {
      deductBuyerLossNoTransfer(ctx, buy, loss);
      round.ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, commodity, loss);
    }
    // ③ 买方 → 卖方：货款腿（MARKET_TRADE）—— 对卖方就是一笔现金销售。
    applyMarketLeg(
        ctx,
        round.ledger.mint(
            buy.buyer.actor,
            sell.seller.actor,
            sell.hex,
            Map.of(),
            Map.of(buy.currency, payment),
            TransferReason.MARKET_TRADE));
    // 槽位/头寸/合同。债务合同在转移之后 upsert：生成债务不碰余额，失败时整轮回滚不产生半笔。
    pools.sellChanged(sell, quantity);
    buy.remaining -= quantity;
    lender.remaining -= payment;
    DebtUnit unit = new DebtUnit.Money(buy.currency);
    DebtContract contract =
        DebtContractBook.upsert(
            ctx.debts,
            buy.buyer.household,
            lender.lender.household,
            unit,
            MARKET_CREDIT_TERMS,
            payment,
            round.day,
            OptionalLong.of(ctx.creditConfig.dueCycle()));
    addDebtReference(round, buy.buyer.household, contract.id());
    ctx.creditFills.add(
        new MarketReport.CreditFill(
            sell.hex,
            commodity,
            buy.buyer.actor,
            lender.lender.actor,
            payment,
            unit,
            contract.id(),
            EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE,
            ctx.creditConfig.dueCycle()));
    MarketReport.Fill fill =
        new MarketReport.Fill(
            sell.hex,
            buy.hex,
            commodity,
            sell.seller.actor,
            buy.buyer.actor,
            quantity,
            price,
            0L,
            payment,
            0L,
            round.day,
            true,
            "",
            loss);
    ctx.fills.add(fill);
    long tariffPerUnit = ctx.tariffPerUnitOf(sell.regionId, commodity);
    if (tariffPerUnit > 0L) {
      ctx.tariffByFill.put(fill, tariffPerUnit);
    }
    ctx.immediateFills++;
    if (buy.buyer.household != null) {
      reduceUnmet(round.unmetToday, buy.buyer.household, commodity, quantity - loss);
    }
  }

  /** 借实物：货腿 卖家 → 买方（LOAN_PRINCIPAL）+ 债务合同 + CreditFill；没有货币腿、不写普通 sale fill。 */
  private static void executeGoodsCredit(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      CreditPools pools) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    long quota = ctx.quotaRemaining(sell.region, commodity);
    if (quantity > quota) {
      throw new IllegalStateException("借实物越过区级配额（调用方应先封顶）：" + quantity + " > " + quota);
    }
    ctx.consumeQuota(sell.region, commodity, quantity);
    applyMarketLeg(
        ctx,
        round.ledger.mint(
            sell.seller.actor,
            buy.buyer.actor,
            sell.hex,
            Map.of(commodity, quantity),
            Map.of(),
            TransferReason.LOAN_PRINCIPAL));
    long loss = creditLossMilli(ctx, sell, buy, quantity);
    if (loss > 0L) {
      deductBuyerLossNoTransfer(ctx, buy, loss);
      round.ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, commodity, loss);
    }
    pools.sellChanged(sell, quantity);
    buy.remaining -= quantity;
    DebtUnit unit = new DebtUnit.Commodity(commodity);
    DebtContract contract =
        DebtContractBook.upsert(
            ctx.debts,
            buy.buyer.household,
            sell.seller.household,
            unit,
            MARKET_CREDIT_TERMS,
            quantity,
            round.day,
            OptionalLong.of(ctx.creditConfig.dueCycle()));
    addDebtReference(round, buy.buyer.household, contract.id());
    ctx.creditFills.add(
        new MarketReport.CreditFill(
            sell.hex,
            commodity,
            buy.buyer.actor,
            sell.seller.actor,
            quantity,
            unit,
            contract.id(),
            EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE,
            ctx.creditConfig.dueCycle()));
    if (buy.buyer.household != null) {
      reduceUnmet(round.unmetToday, buy.buyer.household, commodity, quantity - loss);
    }
  }

  /** 信用的单 hex 即时损耗（与现金成交逐字同源；损耗不产生货币运费）。 */
  private static long creditLossMilli(MatchContext ctx, SellSlot sell, BuySlot buy, long quantity) {
    if (sell.hex.equals(buy.hex)) {
      return 0L;
    }
    return Math.min(
        quantity,
        quantity * ctx.hexTradeCost.lossPerMilleBetween(sell.hex, buy.hex) / 1000L);
  }

  /** 与 {@code executeTrade} 的货款算式同源：{@code ⌈数量 × 单价 ÷ 1000⌉}（毫计价货币）。 */
  private static long paymentForQuantity(long quantity, long unitPrice) {
    if (quantity <= 0L || unitPrice <= 0L) {
      return 0L;
    }
    return ceilDivPositive(
        safeMulDiv(quantity, unitPrice, 1L), EconomySettlement.MILLI_PER_GRAIN);
  }

  /** {@code amount} 毫计价货币最多能买多少毫商品（保证 ceil(量 × 单价 ÷ 1000) ≤ amount）。 */
  private static long maxQuantityForMoney(long amountMilli, long unitPrice) {
    if (amountMilli <= 0L || unitPrice <= 0L) {
      return 0L;
    }
    return safeMulDiv(amountMilli, EconomySettlement.MILLI_PER_GRAIN, unitPrice);
  }

  /**
   * ★★ <b>D-030/D-031 §3.1：一个家户出借人的货币保留额</b>（毫计价货币）：
   *
   * <pre>
   * reserve = value(自身未覆盖自然需求，按市场价) + LENDER_MONEY_BUFFER_PER_CAPITA_MILLI × 人口
   * </pre>
   *
   * <p>★ "未覆盖"= {@code max(0, 日自然需求 − (持有 − 冻结))}；缺该商品市场价 ⇒ 该商品需要不入保留额（不硬折、不假装 0
   * 需要，见交付报告）。人口/行读不到 ⇒ {@link Long#MAX_VALUE}（fail-closed：不把"读不到"当"不用留"）。
   */
  private static long moneyReserveOf(MatchContext ctx, Participant participant, Market market) {
    ClassRow row = ctx.round.rows.get(participant.household);
    if (row == null || market == null) {
      return Long.MAX_VALUE;
    }
    long reserve = 0L;
    for (Map.Entry<CommodityId, Long> need : row.naturalNeeds().entrySet()) {
      if (need.getValue() <= 0L) {
        continue;
      }
      long available =
          Math.max(
              0L,
              stockOf(ctx.round, participant, need.getKey())
                  - frozenGoodsOf(ctx.round, participant, need.getKey()));
      long uncovered = Math.max(0L, need.getValue() - available);
      if (uncovered <= 0L) {
        continue;
      }
      long price = market.priceOf(need.getKey());
      if (price <= 0L) {
        continue; // 缺价不硬折：该商品需要不能折成保留额（交付报告具名）
      }
      reserve = safeAdd(reserve, safeMulDiv(uncovered, price, EconomySettlement.MILLI_PER_GRAIN));
    }
    reserve =
        safeAdd(
            reserve,
            safeMulDiv(
                row.population(), LENDER_MONEY_BUFFER_PER_CAPITA_MILLI, 1L));
    return reserve;
  }

  /** 买方所有格市场（缺则回落该买方所在区的锚格价目表；两者都没有 ⇒ null）。 */
  private static Market marketForBuy(MatchContext ctx, BuySlot buy) {
    Market direct = ctx.markets.get(buy.hex);
    return direct != null ? direct : ctx.markets.get(buy.region.anchor());
  }

  /** 参与者所在格市场（缺则回落所在区锚格；参与者必在某个市场格里）。 */
  private static Market marketForParticipant(MatchContext ctx, Participant participant) {
    HexCoord hex = ctx.participantHex.get(participant.actor);
    if (hex == null) {
      return null;
    }
    Market direct = ctx.markets.get(hex);
    if (direct != null) {
      return direct;
    }
    return ctx.topology.contains(hex) ? ctx.markets.get(ctx.topology.regionOf(hex).anchor()) : null;
  }

  /** 市场信用的转移腿：唯一 applier = {@code EconomySettlement.applyTransfer}（带冻结表的 10 参入口）。 */
  private static void applyMarketLeg(MatchContext ctx, Transfer transfer) {
    MarketRound round = ctx.round;
    EconomySettlement.applyTransfer(
        round.householdGoods,
        round.householdMoney,
        round.householdFrozenGoods,
        round.householdFrozenMoney,
        round.householdOfActor,
        transfer);
  }

  /** 把新合同的派生引用补进债务人行（幂等；权威重建仍在 {@code DebtReferenceReconciler}）。 */
  private static void addDebtReference(MarketRound round, HouseholdId debtor, DebtContractId id) {
    ClassRow row = round.rows.get(debtor);
    if (row == null) {
      throw new IllegalStateException("信用成交的债务人行不在市场轮里（状态漂开）: " + debtor);
    }
    round.rows.put(debtor, DebtContractBook.withDebtReference(row, id));
  }

  /**
   * ★★ <b>D-030/D-031：信用撮合结束后、买方剩余的信用归因</b>。
   *
   * <pre>
   * 两个可借池都空且没钱        ⇒ NO_BUDGET
   * 两个可借池都空但买方还有钱  ⇒ NO_CREDIT_LIMIT（语义已更正：不再表示"借款人额度为 0"）
   * 无商品可借                  ⇒ NO_LENDABLE_GOODS（原子绑定：没有可成立的商品头寸）
   * 无货币可借                  ⇒ NO_LENDABLE_MONEY
   * 两池都还有剩余却凑不成正交易 ⇒ NO_CREDIT_LIMIT
   * </pre>
   *
   * <p>只对家户买方生效；经营者（{@code household == null}）没有合同主体身份 ⇒ 返回 null、走旧分档。
   */
  private static MarketUnfilledReason creditUnfilledReason(MatchContext ctx, BuySlot buy) {
    if (!ctx.creditEnabled() || buy.buyer.household == null) {
      return null;
    }
    if (!MONEY_LENDING_AUTO_LIST && !MARKET_GOODS_LENDING_ENABLED) {
      return null; // 信用由 GM 开关整体关闭 ⇒ 逐值退回旧分档
    }
    long cash =
        spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
    boolean goodsLeft =
        MARKET_GOODS_LENDING_ENABLED
            && ctx.creditGoodsRemaining(buy.regionId, buy.order.commodity()) > 0L;
    boolean moneyLeft =
        MONEY_LENDING_AUTO_LIST
            && ctx.creditMoneyRemainingByRegion.getOrDefault(buy.regionId, 0L) > 0L;
    if (!goodsLeft && !moneyLeft) {
      // 两个可借池都空了：没钱可借时退回 NO_BUDGET；还有自己的现金但买不成 ⇒ NO_CREDIT_LIMIT（语义已更正）。
      return cash <= 0L ? MarketUnfilledReason.NO_BUDGET : MarketUnfilledReason.NO_CREDIT_LIMIT;
    }
    if (moneyLeft && !goodsLeft) {
      return MarketUnfilledReason.NO_LENDABLE_GOODS;
    }
    if (!moneyLeft && goodsLeft) {
      return MarketUnfilledReason.NO_LENDABLE_MONEY;
    }
    // 两个池都还有剩余，但剩余头寸凑不成一笔正交易。★ D-031：不再表示“借款人额度为 0”。
    return MarketUnfilledReason.NO_CREDIT_LIMIT;
  }

  /** 一条瞬态货币出借头寸（每轮从余额派生；不落盘；{@code remaining} 在撮合里递减）。 */
  private static final class MoneyLendOrder {
    final Participant lender;
    final CurrencyId currency;
    final long ratePerMille;
    final HexCoord hex;
    long remaining;

    MoneyLendOrder(
        Participant lender, CurrencyId currency, long ratePerMille, HexCoord hex, long amountMilli) {
      this.lender = lender;
      this.currency = currency;
      this.ratePerMille = ratePerMille;
      this.hex = hex;
      this.remaining = amountMilli;
    }
  }

  /**
   * ★★ <b>一轮信用撮合的瞬态池</b>：货币出借单按区（费率升序 → 可借额降序 → actor id 升序）+ 两类卖单池：
   *
   * <ul>
   *   <li>{@code cashSellers}：任意卖家（借钱买货对卖方是现金销售），按现金撮合的 {@code costOrder}；
   *   <li>{@code goodsLenders}：家户卖家（能成为债权人），按"可借数量降序 → 商品 id 升序 → 卖家 actor id 升序"。
   * </ul>
   *
   * <p>★ 都是本轮瞬态；不新增持久状态、不复制库存。
   */
  private static final class CreditPools {
    private final Map<String, List<MoneyLendOrder>> moneyByRegion = new LinkedHashMap<>();
    private final Map<String, Integer> moneyCursorByRegion = new LinkedHashMap<>();
    private final Map<String, Map<CommodityId, TreeSet<SellSlot>>> cashSellers =
        new LinkedHashMap<>();
    private final Map<String, Map<CommodityId, TreeSet<SellSlot>>> goodsLenders =
        new LinkedHashMap<>();

    private static final Comparator<SellSlot> CASH_SELLER_ORDER = costOrder(null);
    private static final Comparator<MoneyLendOrder> MONEY_ORDER =
        Comparator.comparingLong((MoneyLendOrder order) -> order.ratePerMille)
            .thenComparing(
                Comparator.comparingLong((MoneyLendOrder order) -> order.remaining).reversed())
            .thenComparing(order -> order.lender.actor.id());
    private static final Comparator<SellSlot> GOODS_ORDER =
        Comparator.comparingLong((SellSlot sell) -> sell.remaining)
            .reversed()
            .thenComparing(sell -> sell.order.commodity().value())
            .thenComparing(sell -> sell.seller.actor.id());

    private CreditPools() {}

    static CreditPools build(MatchContext ctx, MarketIndexes indexes) {
      CreditPools pools = new CreditPools();
      if (MONEY_LENDING_AUTO_LIST) {
        for (Participant participant : ctx.participants.values()) {
          if (participant.household == null) {
            continue;
          }
          Market market = marketForParticipant(ctx, participant);
          HexCoord hex = ctx.participantHex.get(participant.actor);
          if (market == null || hex == null || !ctx.topology.contains(hex)) {
            continue;
          }
          CurrencyId currency = market.numeraire();
          long spendable =
              Math.max(
                  0L,
                  moneyOf(ctx.round, participant, currency)
                      - frozenMoneyOf(ctx.round, participant, currency));
          long reserve = moneyReserveOf(ctx, participant, market);
          long lendable = Math.max(0L, spendable - reserve);
          if (lendable <= 0L) {
            continue;
          }
          String regionId = ctx.topology.regionOf(hex).node().nodeId();
          pools
              .moneyByRegion
              .computeIfAbsent(regionId, ignored -> new ArrayList<>())
              .add(
                  new MoneyLendOrder(
                      participant,
                      currency,
                      EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE,
                      hex,
                      lendable));
        }
        for (List<MoneyLendOrder> orders : pools.moneyByRegion.values()) {
          orders.sort(MONEY_ORDER);
        }
      }
      for (MarketRegion region : ctx.topology.regions()) {
        Market anchor = ctx.markets.get(region.anchor());
        if (anchor == null) {
          continue;
        }
        String regionId = region.node().nodeId();
        Map<CommodityId, List<SellSlot>> byCommodity =
            indexes.sellsByRegionCommodity.getOrDefault(regionId, Map.of());
        for (CommodityId commodity : indexes.commodities) {
          long price = ctx.referencePriceOf(regionId, anchor, commodity);
          if (!anchor.hasPrice(commodity)) {
            continue; // 从未定价 ⇒ 不交易
          }
          if (price == 0L) {
            continue; // 明确 0 价 ⇒ 无货可借（货款腿 0，不存在要借的货款）
          }
          for (SellSlot sell : byCommodity.getOrDefault(commodity, List.of())) {
            if (sell.remaining <= 0L
                || sell.order.minPrice() > price
                || sell.order.availableFromTick() > ctx.round.day) {
              continue;
            }
            pools.addSeller(regionId, commodity, sell);
          }
        }
      }
      return pools;
    }

    private void addSeller(String regionId, CommodityId commodity, SellSlot sell) {
      cashSellers
          .computeIfAbsent(regionId, ignored -> new LinkedHashMap<>())
          .computeIfAbsent(commodity, ignored -> new TreeSet<>(CASH_SELLER_ORDER))
          .add(sell);
      if (sell.seller.household != null) {
        goodsLenders
            .computeIfAbsent(regionId, ignored -> new LinkedHashMap<>())
            .computeIfAbsent(commodity, ignored -> new TreeSet<>(GOODS_ORDER))
            .add(sell);
      }
    }

    /**
     * ★ 一笔信用成交后的卖单池同步：{@code goods} 的排序键读 {@code remaining}，因此先摘旧值、扣减、再按新值放回；
     * {@code cash} 的排序键是静态成本，只在卖光时移除。
     */
    void sellChanged(SellSlot sell, long delta) {
      if (delta <= 0L) {
        return;
      }
      TreeSet<SellSlot> goods = goodsPool(sell.regionId, sell.order.commodity());
      TreeSet<SellSlot> cash = cashPool(sell.regionId, sell.order.commodity());
      if (goods != null) {
        goods.remove(sell);
      }
      sell.remaining -= delta;
      if (goods != null && sell.remaining > 0L) {
        goods.add(sell);
      }
      if (cash != null && sell.remaining <= 0L) {
        cash.remove(sell);
      }
    }

    List<MoneyLendOrder> moneyLenders(String regionId) {
      return moneyByRegion.getOrDefault(regionId, List.of());
    }

    int moneyCursor(String regionId) {
      return moneyCursorByRegion.getOrDefault(regionId, 0);
    }

    void putMoneyCursor(String regionId, int cursor) {
      moneyCursorByRegion.put(regionId, cursor);
    }

    SellSlot bestCashSeller(String regionId, CommodityId commodity) {
      TreeSet<SellSlot> pool = cashPool(regionId, commodity);
      if (pool == null) {
        return null;
      }
      while (!pool.isEmpty() && pool.first().remaining <= 0L) {
        pool.pollFirst();
      }
      return pool.isEmpty() ? null : pool.first();
    }

    SellSlot bestGoodsSeller(
        String regionId, CommodityId commodity, Set<SellSlot> skippedForThisBuyer) {
      TreeSet<SellSlot> pool = goodsPool(regionId, commodity);
      if (pool == null) {
        return null;
      }
      for (SellSlot sell : pool) {
        if (sell.remaining > 0L && !skippedForThisBuyer.contains(sell)) {
          return sell;
        }
      }
      return null;
    }

    void recordRemaining(MatchContext ctx) {
      for (Map.Entry<String, List<MoneyLendOrder>> entry : moneyByRegion.entrySet()) {
        long sum = 0L;
        for (MoneyLendOrder order : entry.getValue()) {
          sum += Math.max(0L, order.remaining);
        }
        ctx.creditMoneyRemainingByRegion.put(entry.getKey(), sum);
      }
      for (Map.Entry<String, Map<CommodityId, TreeSet<SellSlot>>> byRegion :
          goodsLenders.entrySet()) {
        Map<CommodityId, Long> byCommodity = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, TreeSet<SellSlot>> entry : byRegion.getValue().entrySet()) {
          long sum = 0L;
          for (SellSlot sell : entry.getValue()) {
            if (sell.remaining > 0L) {
              sum += sell.remaining;
            }
          }
          byCommodity.put(entry.getKey(), sum);
        }
        ctx.creditGoodsRemainingByRegionCommodity.put(byRegion.getKey(), byCommodity);
      }
    }

    private TreeSet<SellSlot> cashPool(String regionId, CommodityId commodity) {
      Map<CommodityId, TreeSet<SellSlot>> byCommodity = cashSellers.get(regionId);
      return byCommodity == null ? null : byCommodity.get(commodity);
    }

    private TreeSet<SellSlot> goodsPool(String regionId, CommodityId commodity) {
      Map<CommodityId, TreeSet<SellSlot>> byCommodity = goodsLenders.get(regionId);
      return byCommodity == null ? null : byCommodity.get(commodity);
    }
  }

  // ── P1.2：每轮一次的只读索引（worker 只读；构建序全部来自 canonical 内容）──────────────────────

  /**
   * ★★ <b>市场轮索引</b>（P1.2）：把区内/跨区撮合里的 O(买卖单 × 商品 × 格) 全表扫描换成一次建表、按 (区/格, 商品) 直取。
   *
   * <p>★ <b>为什么索引里的槽位列表必须是全局插入序</b>：区内 {@code matchGroup} 与跨区 {@code matchRoute} 都按 {@code
   * ctx.buys}/{@code ctx.sells} 的插入序决定 {@code ProportionalSplit} 的下标序（tie-break = 下标升序）⇒
   * 索引只做<b>分桶</b>，桶内保持原相对序；动态条件（{@code remaining}/{@code noMoney}/限价/时限）在取用时过滤，不在建表时淘汰。
   *
   * <p>★ <b>邻接表</b>：按 {@code topology.regions()} 的声明序建 {@code regionId → 邻接 regionId 集}，只消费 {@code
   * topology.adjacent}（唯一判定处）；跨区循环只查表，不再对每个买卖单对调一次几何判断。
   */
  private static final class MarketIndexes {

    final Map<String, Map<CommodityId, List<BuySlot>>> buysByRegionCommodity;
    final Map<String, Map<CommodityId, List<SellSlot>>> sellsByRegionCommodity;
    final Map<String, Map<CommodityId, List<BuySlot>>> buysByHexCommodity;
    final Map<String, Map<CommodityId, List<SellSlot>>> sellsByHexCommodity;
    final Map<CommodityId, List<HexCoord>> buyHexesByCommodity;
    final Map<CommodityId, List<HexCoord>> sellHexesByCommodity;
    final Map<String, Set<String>> adjacentRegionIds;
    final Map<String, List<Participant>> participantsByHex;
    final List<CommodityId> commodities;

    private MarketIndexes(
        Map<String, Map<CommodityId, List<BuySlot>>> buysByRegionCommodity,
        Map<String, Map<CommodityId, List<SellSlot>>> sellsByRegionCommodity,
        Map<String, Map<CommodityId, List<BuySlot>>> buysByHexCommodity,
        Map<String, Map<CommodityId, List<SellSlot>>> sellsByHexCommodity,
        Map<CommodityId, List<HexCoord>> buyHexesByCommodity,
        Map<CommodityId, List<HexCoord>> sellHexesByCommodity,
        Map<String, Set<String>> adjacentRegionIds,
        Map<String, List<Participant>> participantsByHex,
        List<CommodityId> commodities) {
      this.buysByRegionCommodity = buysByRegionCommodity;
      this.sellsByRegionCommodity = sellsByRegionCommodity;
      this.buysByHexCommodity = buysByHexCommodity;
      this.sellsByHexCommodity = sellsByHexCommodity;
      this.buyHexesByCommodity = buyHexesByCommodity;
      this.sellHexesByCommodity = sellHexesByCommodity;
      this.adjacentRegionIds = adjacentRegionIds;
      this.participantsByHex = participantsByHex;
      this.commodities = commodities;
    }

    static MarketIndexes build(MatchContext ctx) {
      Map<String, Map<CommodityId, List<BuySlot>>> buysByRegionCommodity = new LinkedHashMap<>();
      Map<String, Map<CommodityId, List<BuySlot>>> buysByHexCommodity = new LinkedHashMap<>();
      Map<CommodityId, LinkedHashSet<HexCoord>> buyHexes = new LinkedHashMap<>();
      for (BuySlot buy : ctx.buys) {
        CommodityId commodity = buy.order.commodity();
        addIndexed(buysByRegionCommodity, buy.region.node().nodeId(), commodity, buy);
        addIndexed(buysByHexCommodity, hexKeyOf(buy.hex), commodity, buy);
        buyHexes.computeIfAbsent(commodity, ignored -> new LinkedHashSet<>()).add(buy.hex);
      }
      Map<String, Map<CommodityId, List<SellSlot>>> sellsByRegionCommodity = new LinkedHashMap<>();
      Map<String, Map<CommodityId, List<SellSlot>>> sellsByHexCommodity = new LinkedHashMap<>();
      Map<CommodityId, LinkedHashSet<HexCoord>> sellHexes = new LinkedHashMap<>();
      for (SellSlot sell : ctx.sells) {
        CommodityId commodity = sell.order.commodity();
        addIndexed(sellsByRegionCommodity, sell.region.node().nodeId(), commodity, sell);
        addIndexed(sellsByHexCommodity, hexKeyOf(sell.hex), commodity, sell);
        sellHexes.computeIfAbsent(commodity, ignored -> new LinkedHashSet<>()).add(sell.hex);
      }
      Map<String, Set<String>> adjacentRegionIds = new LinkedHashMap<>();
      for (MarketRegion region : ctx.topology.regions()) {
        LinkedHashSet<String> adjacent = new LinkedHashSet<>();
        for (MarketRegion other : ctx.topology.regions()) {
          if (!other.equals(region) && ctx.topology.adjacent(region, other)) {
            adjacent.add(other.node().nodeId());
          }
        }
        adjacentRegionIds.put(region.node().nodeId(), Collections.unmodifiableSet(adjacent));
      }
      Map<String, List<Participant>> participantsByHex = new LinkedHashMap<>();
      for (Participant participant : ctx.participants.values()) {
        HexCoord hex = ctx.participantHex.get(participant.actor);
        if (hex == null) {
          continue; // 与 ctx.participantHex 同源；正常路径每个参与者都有落点格
        }
        participantsByHex
            .computeIfAbsent(hexKeyOf(hex), ignored -> new ArrayList<>())
            .add(participant);
      }
      return new MarketIndexes(
          buysByRegionCommodity,
          sellsByRegionCommodity,
          buysByHexCommodity,
          sellsByHexCommodity,
          sortedHexes(buyHexes),
          sortedHexes(sellHexes),
          adjacentRegionIds,
          participantsByHex,
          orderedCommodities(ctx));
    }
  }

  /** 往 {@code (outerKey, innerKey)} 桶里按遍历序追加一条（桶内相对序 = 全局插入序，见 MarketIndexes 类注）。 */
  private static <K, V> void addIndexed(
      Map<String, Map<K, List<V>>> table, String outerKey, K innerKey, V value) {
    table
        .computeIfAbsent(outerKey, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(innerKey, ignored -> new ArrayList<>())
        .add(value);
  }

  /** 每个商品一条按 {@code (q, r)} 升序的 distinct hex 表（与串行路径的 sort 口径逐字相同）。 */
  private static Map<CommodityId, List<HexCoord>> sortedHexes(
      Map<CommodityId, LinkedHashSet<HexCoord>> raw) {
    Map<CommodityId, List<HexCoord>> sorted = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, LinkedHashSet<HexCoord>> entry : raw.entrySet()) {
      List<HexCoord> hexes = new ArrayList<>(entry.getValue());
      hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      sorted.put(entry.getKey(), List.copyOf(hexes));
    }
    return sorted;
  }

  /** 行键与 {@code EconomySettlement.rowsByHex} 同源：一格一个 canonical 串（唯一拼写点）。 */
  private static String hexKeyOf(HexCoord hex) {
    return IndustryHexKeys.hexKey(hex.q(), hex.r());
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
  private static void matchWithinRegions(
      MatchContext ctx, EconomyParallelism parallelism, MarketIndexes indexes) {
    List<MarketRegion> regions = new ArrayList<>();
    for (MarketRegion region : ctx.topology.regions()) {
      if (ctx.markets.get(region.anchor()) == null) {
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
    // ★★ 2026-10-09：有商号（merchantFirms 非空）时区内跨格也要由商号承运 —— 承运容量是**全商号每周期一份**
    //   的全局硬约束，分散在并行 worker 里各自持副本会超发。⇒ 商号世界改成协调器单线程按拓扑区序直接撮合
    //   （与 RegionClone.run 的区内序逐字同源），跨区/区内共用一个真实 CarrierPool。
    if (!ctx.merchantFirms.isEmpty()) {
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
        // ★★ D-027：把 worker 本区副本的配额余量与"用尽"标记并回协调器（跨区撮合继续用同一张表；
        //   同区只被一个 worker 触碰 ⇒ 合并无竞争、逐值确定）。
        RegionClone clone = clonesById.get(outcome.regionId());
        if (clone != null) {
          ctx.absorbQuotas(clone.local);
          ctx.regulationQuotaExhausted.addAll(clone.local.regulationQuotaExhausted);
        }
      }
    }
  }

  /**
   * ★★ <b>商号世界的区内串行撮合</b>（2026-10-09）：与 {@code RegionClone.run} 同一区内顺序
   * （拓扑区序 × 商品序 × 槽位插入序），但直接在协调器 {@code ctx} 上成交 —— 区内跨格运费因此与跨区运费共用同一个
   * {@link MerchantSettlement.CarrierPool}，商号每周期运力不会被各 worker 副本重复发放。
   */
  private static void matchWithinRegionsSerial(
      MatchContext ctx, List<MarketRegion> regions, MarketIndexes indexes) {
    for (MarketRegion region : regions) {
      String regionId = region.node().nodeId();
      Market anchorMarket = ctx.markets.get(region.anchor());
      if (anchorMarket == null) {
        continue;
      }
      Map<CommodityId, List<BuySlot>> buysByCommodity =
          indexes.buysByRegionCommodity.getOrDefault(regionId, Map.of());
      Map<CommodityId, List<SellSlot>> sellsByCommodity =
          indexes.sellsByRegionCommodity.getOrDefault(regionId, Map.of());
      for (CommodityId commodity : indexes.commodities) {
        if (!ctx.hasPrice(regionId, anchorMarket, commodity)) {
          continue;
        }
        long price = ctx.referencePriceOf(regionId, anchorMarket, commodity);
        List<BuySlot> buys =
            activeBuys(buysByCommodity.get(commodity), price, ctx.round.day);
        if (buys.isEmpty()) {
          continue;
        }
        List<SellSlot> sells =
            activeSells(sellsByCommodity.get(commodity), price, ctx.round.day);
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
  private static RegionClone prepareRegion(
      MarketRegion region, MatchContext ctx, MarketIndexes indexes) {
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
      regionParticipants.putIfAbsent(buy.buyer.actor, buy.buyer);
    }
    for (SellSlot sell : localSells) {
      regionParticipants.putIfAbsent(sell.seller.actor, sell.seller);
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
      HouseholdId household = participant.household;
      householdGoods.put(household, copyBalances(ctx.round.householdGoods, household));
      householdMoney.put(household, copyBalances(ctx.round.householdMoney, household));
      householdFrozenGoods.put(
          household, copyBalances(ctx.round.householdFrozenGoods, household));
      householdFrozenMoney.put(
          household, copyBalances(ctx.round.householdFrozenMoney, household));
      Map<CommodityId, Long> recorded = ctx.round.unmetToday.get(household);
      if (recorded != null) {
        unmetToday.put(household, new LinkedHashMap<>(recorded));
      }
    }
    MarketRound localRound =
        new MarketRound(
            ctx.round.day,
            ctx.round.rows,
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            unmetToday,
            ctx.round.householdOfActor,
            ctx.round.industries,
            ctx.round.units,
            ctx.round.assetShares,
            ctx.round.relations,
            ctx.round.allocations,
            ctx.round.shipments,
            // ★ 本地账本：worker 铸造的转移只服务于本地 applyTransfer，交回后丢弃；协调器回放时在全局累加器上重铸。
            new ProductionLedger.Accumulator(ctx.round.day),
            ctx.round.operatorConditions,
            ctx.round.index,
            ctx.round.demands);
    MatchContext local =
        new MatchContext(
            localRound,
            ctx.markets,
            ctx.topology,
            ctx.carrier,
            ctx.merchantFirms,
            MerchantSettlement.CarrierPool.empty(),
            // ★★ D-027：worker 只读本区适用的调控（配额在本区副本上扣，回放后并回协调器）。
            ctx.regulationFor(regionId));
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
        ctx.markets.get(region.anchor()),
        ctx.round.day);
  }

  /**
   * 一个区的 worker 私有工作集：协调器建好副本后交给 worker，worker 只在本对象内跑同一条 {@code matchGroup}，交回不可变结果。
   *
   * <p>★ 本类<b>不</b>持有 {@link AccountSession}、{@link MarketRound} 的活账户视图或任何共享可写表 ——
   * 线程安全靠"每区一个实例、实例只被一个 worker 触碰"的结构保证。
   */
  private static final class RegionClone {

    final String regionId;
    final Map<CommodityId, List<BuySlot>> buysByCommodity;
    final Map<CommodityId, List<SellSlot>> sellsByCommodity;
    final List<BuySlot> allBuys;
    final List<SellSlot> allSells;
    final MatchContext local;
    final Market anchorMarket;
    final long day;

    RegionClone(
        String regionId,
        Map<CommodityId, List<BuySlot>> buysByCommodity,
        Map<CommodityId, List<SellSlot>> sellsByCommodity,
        List<BuySlot> allBuys,
        List<SellSlot> allSells,
        MatchContext local,
        Market anchorMarket,
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
          // ★★ D-027：区内参考价 = 卖方格价 + 该区区级调控覆盖（与 ordersFor 同一条口径）。
          //    ★ 2026-10-09：有定价行（含明确 0 价免费）都进撮合；只有"从未定价"才跳过。
          if (!local.hasPrice(regionId, anchorMarket, commodity)) {
            continue;
          }
          long price = local.referencePriceOf(regionId, anchorMarket, commodity);
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
                buy.blocked));
      }
      List<SellSlotState> sellStates = new ArrayList<>(allSells.size());
      for (SellSlot sell : allSells) {
        sellStates.add(new SellSlotState(sell.orderIndex, sell.remaining, sell.frozenRemaining));
      }
      return new RegionOutcome(
          regionId, List.copyOf(local.fillIntents), buyStates, sellStates, local.round.unmetToday);
    }
  }

  /** 买方槽位的动态过滤（与串行扫描里的条件逐字相同；桶内相对序不变 ⇒ ProportionalSplit 下标序不变）。 */
  private static List<BuySlot> activeBuys(List<BuySlot> source, long price, long day) {
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
  private static List<SellSlot> activeSells(List<SellSlot> source, long price, long day) {
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
  private static void replayRegionOutcome(MatchContext ctx, RegionOutcome outcome) {
    for (FillIntent fill : outcome.fills()) {
      BuySlot buy = ctx.buys.get(fill.buyIndex());
      SellSlot sell = ctx.sells.get(fill.sellIndex());
      // ★ canonical 键自检：worker 的意向必须落在它声明的 (区, 商品, 卖方, 买方) 槽位上；回放序仍是
      //   topology 区序 × worker 成交序（内容的纯函数），不按 (seller,buyer) 重排 —— 重排会改变同一区内
      //   已由串行基准确定的逐笔顺序与冻结释放中间态。
      if (!fill.regionId().equals(sell.region.node().nodeId())
          || !fill.commodity().equals(sell.order.commodity())
          || !fill.sellerKey().equals(actorKeyOf(sell.seller.actor))
          || !fill.buyerKey().equals(actorKeyOf(buy.buyer.actor))) {
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
    }
    for (SellSlotState state : outcome.sellStates()) {
      SellSlot sell = ctx.sells.get(state.orderIndex());
      if (sell.remaining != state.remaining() || sell.frozenRemaining != state.frozenRemaining()) {
        throw new IllegalStateException(
            "区内市场回放与 worker 分区计算漂开（拒绝按到达序静默兜底）：卖槽=" + state.orderIndex());
      }
    }
    ctx.round.unmetToday.putAll(copyUnmet(outcome.unmetToday()));
  }

  /** 深拷贝 {@code unmetToday} 的一层两层表（worker 交出的读取物与共享表脱钩）。 */
  private static Map<HouseholdId, Map<CommodityId, Long>> copyUnmet(
      Map<HouseholdId, Map<CommodityId, Long>> source) {
    Map<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : source.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  /** ★ 审计/稳定键用的主体 canonical 串（只进意向记录，不参与任何判定）。 */
  private static String actorKeyOf(ActorRef actor) {
    return actor.kind() + ":" + actor.id();
  }

  /** 复制一张账户表里某一主体的内层余额表（内层表只读；改值一律由 applyTransfer 换新表）。 */
  private static <K, I> Map<I, Long> copyBalances(Map<K, Map<I, Long>> table, K key) {
    return new LinkedHashMap<>(table.getOrDefault(key, Map.of()));
  }

  /**
   * ★★ <b>给并行 worker 用的只读市场轮</b>：八张账户表浅拷成普通 {@code LinkedHashMap}（内层表只读共享）， 避开 {@link
   * AccountSession} 活视图的 owner 守卫；账本换成本地空累加器（订单生成不铸转移）。
   *
   * <p>★ 只允许在<b>协调器线程</b>调用（读活视图本身要过 owner 守卫），产物在并行阶段只读。
   */
  private static MarketRound readOnlyPlanningRound(MarketRound round) {
    return new MarketRound(
        round.day,
        round.rows,
        new LinkedHashMap<>(round.householdGoods),
        new LinkedHashMap<>(round.householdMoney),
        new LinkedHashMap<>(round.householdFrozenGoods),
        new LinkedHashMap<>(round.householdFrozenMoney),
        new LinkedHashMap<>(round.unmetToday),
        round.householdOfActor,
        round.industries,
        round.units,
        round.assetShares,
        round.relations,
        round.allocations,
        round.shipments,
        new ProductionLedger.Accumulator(round.day),
        round.operatorConditions,
        round.index,
        round.demands,
        // ★ 与旧短构造器逐值同源：worker 的订单生成由调用方显式传 regionRegulation，不读这里的默认值。
        MarketRegulation.none(),
        round.creditConfig,
        round.debts);
  }

  /** ★ 区内一笔成交的不可变意向：worker 产出，协调器按区序/成交序回放（索引 = ctx.buys/ctx.sells 的全局下标）。 */
  private record FillIntent(
      int buyIndex,
      int sellIndex,
      long quantity,
      long unitPrice,
      String regionId,
      CommodityId commodity,
      String sellerKey,
      String buyerKey) {}

  /** worker 交回的买槽终态（noMoney/blocked 只可能由本区撮合改变；remaining/frozen/spent 会在回放后校验）。 */
  private record BuySlotState(
      int orderIndex,
      long remaining,
      long frozenRemaining,
      long spentMilli,
      boolean noMoney,
      MarketUnfilledReason blocked) {}

  /** worker 交回的卖槽终态。 */
  private record SellSlotState(int orderIndex, long remaining, long frozenRemaining) {}

  /** 一个区的 worker 产物（回放序 = 协调器遍历 topology.regions() 的区序；区与区之间的账户不重叠）。 */
  private record RegionOutcome(
      String regionId,
      List<FillIntent> fills,
      List<BuySlotState> buyStates,
      List<SellSlotState> sellStates,
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday) {

    RegionOutcome {
      Objects.requireNonNull(regionId, "regionId");
      fills = List.copyOf(fills);
      buyStates = List.copyOf(buyStates);
      sellStates = List.copyOf(sellStates);
      unmetToday = copyUnmet(unmetToday);
    }
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
   */
  private static void matchGroup(
      MatchContext ctx, List<BuySlot> buys, List<SellSlot> sells, long price, RouteContext route) {
    if (buys.isEmpty() || sells.isEmpty()) {
      return;
    }
    List<SellSlot> ordered = new ArrayList<>(sells);
    ordered.sort(costOrder(route));
    int tierStart = 0;
    while (tierStart < ordered.size()) {
      long tierCost = landedCostOf(ordered.get(tierStart), route);
      int tierEnd = tierStart + 1;
      while (tierEnd < ordered.size() && landedCostOf(ordered.get(tierEnd), route) == tierCost) {
        tierEnd++;
      }
      List<SellSlot> tier = new ArrayList<>(tierEnd - tierStart);
      long supply = 0L;
      for (int i = tierStart; i < tierEnd; i++) {
        SellSlot sell = ordered.get(i);
        if (sell.remaining > 0L) {
          tier.add(sell);
          supply += sell.remaining;
        }
      }
      // ★ 需求按**当前剩余**重算：上一层吃掉的量不再计入（"最低成本层优先"因此是逐层的，不是一次性预分配）。
      long[] weights = new long[buys.size()];
      long demand = 0L;
      for (int i = 0; i < buys.size(); i++) {
        long affordable = affordableQuantity(ctx, buys.get(i), price, route);
        weights[i] = Math.min(buys.get(i).remaining, affordable);
        demand += weights[i];
      }
      if (demand <= 0L) {
        return; // 没有可付需求 ⇒ 后面的层也卖不动（成本排序不改变这一事实）
      }
      // ★★ D-027：区级配额 = 本轮该（区, 商品）卖方成交量的上限（毫商品）。它只压"能成交多少"，
      //   不改参考价/成本排序/限价；超出部分在 collectUnfilled 里落成 REGULATION_QUOTA。
      CommodityId commodity = commodityOf(buys, sells);
      MarketRegion region = sells.get(0).region;
      long quotaLeft = ctx.quotaRemaining(region, commodity);
      long matched = Math.min(demand, Math.min(supply, quotaLeft));
      if (quotaLeft <= 0L && demand > 0L) {
        ctx.markQuotaExhausted(region, commodity);
      }
      if (matched > 0L) {
        long[] sellWeights = new long[tier.size()];
        for (int i = 0; i < tier.size(); i++) {
          sellWeights[i] = tier.get(i).remaining;
        }
        long[] buyParts = ProportionalSplit.byDenominator(matched, weights, demand);
        long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, supply);
        // ★ 配额按**真正落账**的量逐笔扣（唯一扣减点 = executeTrade），这里不再预扣：pairUp 会按预算
        //   缩量，预扣会高估用量。matched 已按剩余配额封顶 ⇒ 逐笔累计不会越过配额。
        pairUp(ctx, buys, buyParts, tier, sellParts, price, route);
      }
      tierStart = tierEnd;
    }
  }

  /** 一组买卖单的商品（同一分组内必相同；给配额键用；空组 ⇒ null）。 */
  private static CommodityId commodityOf(List<BuySlot> buys, List<SellSlot> sells) {
    if (!buys.isEmpty()) {
      return buys.get(0).order.commodity();
    }
    return sells.isEmpty() ? null : sells.get(0).order.commodity();
  }

  /** 卖方成本排序（{@code unitCostEstimate + freightPerUnit} 升序；同成本按 canonical key 升序）。 */
  private static Comparator<SellSlot> costOrder(RouteContext route) {
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
  private static long landedCostOf(SellSlot sell, RouteContext route) {
    if (!sell.costEstimate.costKnown()) {
      return Long.MAX_VALUE / 2L;
    }
    long freight = route == null ? 0L : route.freightPerUnit;
    return ProducerCostBook.landedCostMilli(sell.costEstimate.unitCostEstimateMilli(), freight);
  }

  // ── 跨区撮合（邻接供应区；协调器单线程 + P1.2 索引）────────────────────────────────────

  private static void matchAcrossRegions(MatchContext ctx, MarketIndexes indexes) {
    if (!ctx.topology.regional()) {
      return;
    }
    for (CommodityId commodity : indexes.commodities) {
      List<HexCoord> buyerHexes = new ArrayList<>();
      for (HexCoord hex : indexes.buyHexesByCommodity.getOrDefault(commodity, List.of())) {
        if (hasActiveBuyAtHex(indexes, hex, commodity)) {
          buyerHexes.add(hex);
        }
      }
      for (HexCoord buyerHex : buyerHexes) {
        MarketRegion buyerRegion = ctx.topology.regionOf(buyerHex);
        List<BuySlot> buys = activeBuysAtHex(indexes, buyerHex, commodity);
        if (buys.isEmpty()) {
          continue;
        }
        List<HexCoord> sellerHexes = new ArrayList<>();
        for (HexCoord hex : indexes.sellHexesByCommodity.getOrDefault(commodity, List.of())) {
          if (hasActiveSellAtHex(indexes, hex, commodity)) {
            sellerHexes.add(hex);
          }
        }
        for (HexCoord sellerHex : sellerHexes) {
          MarketRegion sellerRegion = ctx.topology.regionOf(sellerHex);
          if (sellerRegion.equals(buyerRegion)
              || !indexes
                  .adjacentRegionIds
                  .getOrDefault(buyerRegion.node().nodeId(), Set.of())
                  .contains(sellerRegion.node().nodeId())) {
            continue;
          }
          List<SellSlot> sells = activeSellsAtHex(indexes, sellerHex, commodity);
          if (sells.isEmpty()) {
            continue;
          }
          matchRoute(ctx, buyerHex, sellerHex, commodity, buys, sells);
          // 这一对买卖里买家已经满足 / 钱包已耗尽的都不必再看别的卖方路线。
          buys.removeIf(buy -> buy.remaining <= 0 || buy.noMoney);
          if (buys.isEmpty()) {
            break;
          }
        }
      }
    }
  }

  private static boolean hasActiveBuyAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    for (BuySlot buy : indexedBuysAtHex(indexes, hex, commodity)) {
      if (buy.remaining > 0 && !buy.noMoney) {
        return true;
      }
    }
    return false;
  }

  private static List<BuySlot> activeBuysAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    List<BuySlot> result = new ArrayList<>();
    for (BuySlot buy : indexedBuysAtHex(indexes, hex, commodity)) {
      if (buy.remaining > 0 && !buy.noMoney) {
        result.add(buy);
      }
    }
    return result;
  }

  private static List<BuySlot> indexedBuysAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    return indexes
        .buysByHexCommodity
        .getOrDefault(hexKeyOf(hex), Map.of())
        .getOrDefault(commodity, List.of());
  }

  private static boolean hasActiveSellAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    for (SellSlot sell : indexedSellsAtHex(indexes, hex, commodity)) {
      if (sell.remaining > 0) {
        return true;
      }
    }
    return false;
  }

  private static List<SellSlot> activeSellsAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    List<SellSlot> result = new ArrayList<>();
    for (SellSlot sell : indexedSellsAtHex(indexes, hex, commodity)) {
      if (sell.remaining > 0) {
        result.add(sell);
      }
    }
    return result;
  }

  private static List<SellSlot> indexedSellsAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    return indexes
        .sellsByHexCommodity
        .getOrDefault(hexKeyOf(hex), Map.of())
        .getOrDefault(commodity, List.of());
  }

  private static long moveCostOf(MatchContext ctx, HexCoord hex) {
    return ctx.moveCostCache.computeIfAbsent(hex, key -> (long) ctx.topology.moveCostAt(key));
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
    long moveCost = moveCostOf(ctx, buyerHex);
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
    // ★★ P4/P6：费率唯一来源 = 拓扑的 road/radial/基础费公式；P6 的两个商人调整量先从拓扑的只读函数取出
    //   （默认入口恒 0，旧行为逐值不变），再显式传给同一条 TransportTariff.perMille 算式。P9/P7 在组合根按
    //   MerchantPolicy.cityDiscountForLane/ruralPenaltyForLane 汇总后注入这两个函数。
    //   注意费率与上面的 costPerUnit（距离 × moveCost）是两个独立的数。
    long cityDiscountPerMille = ctx.topology.cityDiscountPerMilleBetween(sellerHex, buyerHex);
    long ruralPenaltyPerMille = ctx.topology.ruralPenaltyPerMilleBetween(sellerHex, buyerHex);
    long freightRatePerMille =
        ctx.topology.freightPerMilleBetween(
            sellerHex, buyerHex, cityDiscountPerMille, ruralPenaltyPerMille);
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
      long unitPrice = unitPriceOf(ctx, sells, commodity);
      // ★★ 2026-10-09：单位运费与货款价格解耦（商品种类 × 路线费率 × 默认承运成本）；撮合前的可负担量按它预判，
      //    真正的逐商号承运成本差异在 executeTrade/carrierChargeSplit 里按选中商号现算。
      long freightPerUnit =
          freightUnitMilli(
              commodity, freightRatePerMille, plannedCarrierCostPerMille(ctx));
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
              false);
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
      // ★★ S3：本路线按 unitCostEstimate + freightPerUnit 升序分档；每档在剩余运力内按同一条 ProportionalSplit 配给。
      List<SellSlot> ordered = new ArrayList<>(sells);
      ordered.sort(costOrder(route));
      long capacityLeft = capacityPerWindow;
      int tierStart = 0;
      while (tierStart < ordered.size() && capacityLeft > 0L) {
        long tierCost = landedCostOf(ordered.get(tierStart), route);
        int tierEnd = tierStart + 1;
        while (tierEnd < ordered.size() && landedCostOf(ordered.get(tierEnd), route) == tierCost) {
          tierEnd++;
        }
        List<SellSlot> tier = new ArrayList<>(tierEnd - tierStart);
        long tierSupply = 0L;
        for (int i = tierStart; i < tierEnd; i++) {
          SellSlot sell = ordered.get(i);
          if (sell.remaining > 0L) {
            tier.add(sell);
            tierSupply += sell.remaining;
          }
        }
        // 需求按**当前剩余**重算（前一层已成交的不再计入；与 matchGroup 的逐层语义同源）。
        long[] tierBuyWeights = new long[buys.size()];
        long tierDemand = 0L;
        for (int i = 0; i < buys.size(); i++) {
          long affordable = affordableQuantity(ctx, buys.get(i), unitPrice, route);
          tierBuyWeights[i] = Math.min(buys.get(i).remaining, affordable);
          tierDemand += tierBuyWeights[i];
        }
        if (tierDemand <= 0L) {
          break;
        }
        // ★★ D-027：跨区（多区世界）同样受卖方区的区级配额约束；单区世界走不到这里（adjacent 恒 false）。
        //   区级调控只压"能成交多少"，不改运力/费率/损耗（两层不互相顶替）。
        MarketRegion sellerRegion = sells.get(0).region;
        long quotaLeft = ctx.quotaRemaining(sellerRegion, commodity);
        long matched =
            Math.min(tierDemand, Math.min(tierSupply, Math.min(capacityLeft, quotaLeft)));
        if (quotaLeft <= 0L && tierDemand > 0L) {
          ctx.markQuotaExhausted(sellerRegion, commodity);
        }
        if (matched > 0L) {
          long[] sellWeights = new long[tier.size()];
          for (int i = 0; i < tier.size(); i++) {
            sellWeights[i] = tier.get(i).remaining;
          }
          long[] buyParts = ProportionalSplit.byDenominator(matched, tierBuyWeights, tierDemand);
          long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, tierSupply);
          // ★★ 2026-10-09：路线窗口容量按**真正落账**的量扣 —— 承运商每周期运力不足时 pairUp 只会
          //   成交可承运的部分，若这里仍按 matched 扣，窗口容量会被高估、后续买方被误判"没运力"。
          //   ★ 配额同样按真正落账的量逐笔扣（唯一扣减点 = executeTrade）。
          long executed = pairUp(ctx, buys, buyParts, tier, sellParts, unitPrice, route);
          acc.used = Math.addExact(acc.used, executed);
          capacityLeft -= executed;
        }
        tierStart = tierEnd;
      }
      if (capacityLeft <= 0L && (remainingOf(buys) > 0L || remainingOfSells(sells) > 0L)) {
        acc.bottleneck = true;
        for (BuySlot buy : buys) {
          if (buy.remaining > 0L && buy.blocked == null) {
            buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
          }
        }
        // ★★ 2026-10-09：卖方剩余同样具名（路线每窗口运力用尽）—— 不让它落进 OUTCOMPETED 的误档。
        for (SellSlot sell : sells) {
          if (sell.remaining > 0L) {
            sell.capacityBlocked = true;
          }
        }
      }
    }
  }

  /**
   * ★★ <b>2026-10-09：区内跨格（同市场区、不同 hex）也走商人的运输职能</b> —— 构造一条"即时结算、但要付运费"的
   * 合成路线：{@code immediate = true}（不走 ShipmentBatch/在途），货款与运费仍在成交日结清。
   *
   * <p>★ 保留旧世界行为：只有 {@code merchantFirms} 非空（有商号）才启用；没有商号的旧档/旧测试仍不产生区内货币运费。
   * 费率与跨区同源（{@link MarketTopology#freightPerMilleBetween} + {@link #freightUnitMilli}），路线窗口容量取出厂值
   * （真正的硬约束是商号的每周期运力，由 {@link MerchantSettlement.CarrierPool} 扣）。
   */
  private static RouteContext intraRegionFreightRoute(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    CommodityId commodity = buy.order.commodity();
    long distance = Math.max(1L, ctx.topology.travelTicks(sell.hex, buy.hex));
    long moveCost = Math.max(1L, moveCostOf(ctx, buy.hex));
    long rate = ctx.topology.freightPerMilleBetween(sell.hex, buy.hex);
    long freightPerUnit =
        freightUnitMilli(commodity, rate, plannedCarrierCostPerMille(ctx));
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
        true);
  }

  /**
   * 一批买卖按比例配对落账（区内即时或跨区在途）。
   *
   * <p>★★ <b>逐笔复核买方的剩余可付</b>（缺陷 A 的修法）：预分配的 {@code buyParts} 只是**上限**；每一小笔真正落账前， 都按当前账（{@code
   * spendable + 本单剩余冻结}、订单预算余额）重新算一次"这一笔最多买多少"，并取小。
   * 货款/运费每个小笔各自向上取整，因此整单按总价反解出的数量不保证逐笔加起来付得起；只有把每笔的实际付款累进 {@link BuySlot#spentMilli}（{@code
   * executeTrade} 写）再递推复核，任何成交序列下累计付款才不会越过后端的冻结/余额守卫。
   */
  private static long pairUp(
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
          return executedTotal;
        }
        SellSlot sell = sellers.get(sellerIndex);
        long quantity = Math.min(need, sellLeft);
        // ★★ 2026-10-09：同市场区、不同 hex 的成交也由商号承运（有 merchantFirms 时）⇒ 合成一条"即时但有运费"的
        //   路线；同 hex 仍走零运费即时成交（route == null）。
        RouteContext effectiveRoute = route;
        if (route == null
            && !ctx.merchantFirms.isEmpty()
            && !buy.hex.equals(sell.hex)) {
          effectiveRoute = intraRegionFreightRoute(ctx, buy, sell, price);
        }
        boolean freeTicket =
            price <= 0L && (effectiveRoute == null || effectiveRoute.freightPerUnit <= 0L);
        // ★ 递归约束：上一笔实际付款（含各自 ceil 的货款与运费）已经写进 spentMilli 与余额/冻结表，
        //   这里按**当前**剩余可付重算上限，堵住 N 笔各 ceil 一毫的累计越界。先取原口径的保守配给量，
        //   再用逐笔实际算式精确封顶（跨区运费 floor + 两处 ceil 的累计误差都在这里削平）。
        long affordable = affordableQuantity(ctx, buy, price, effectiveRoute);
        if (quantity > affordable) {
          quantity = affordable;
        }
        long payable = payableMoneyOf(ctx, buy);
        quantity = exactAffordableUpTo(quantity, payable, price, effectiveRoute);
        if (quantity <= 0L) {
          // 钱包/预算在账面上已经归零（不是"这个价买不起"）⇒ 这个买方在**任何**正价格上都再无成交可能：
          // 置 noMoney 让后续跨区路线直接跳过它（否则它会以 remaining>0 的身份把每条路线都试一遍）。
          // ★ 完全免费（0 价 + 0 运费）不是"没钱"：不得置 noMoney，否则区内免费拿货会被自己关死。
          if (payable <= 0L && !freeTicket) {
            buy.noMoney = true;
            if (buy.blocked == null) {
              buy.blocked = MarketUnfilledReason.NO_BUDGET;
            }
          }
          break;
        }
        long executed = executeTrade(ctx, buy, sell, quantity, price, effectiveRoute);
        if (executed <= 0L) {
          // ★★ 承运运力不足 ⇒ 本笔不成交（executeTrade 已把买卖槽位与路线标成 LOGISTICS_CAPACITY）。
          //   不置 noMoney（钱不是瓶颈），也不扣槽位剩余 —— 剩余留给下一轮/下一窗口。
          break;
        }
        executedTotal = Math.addExact(executedTotal, executed);
        buy.remaining -= executed;
        sell.remaining -= executed;
        need -= executed;
        sellLeft -= executed;
      }
    }
    return executedTotal;
  }

  // ── 一笔成交（区内即时 / 跨区在途）────────────────────────────────────────────────

  /**
   * ★★ <b>落一笔成交</b>（区内即时 / 跨区在途）。
   *
   * <p>★★ <b>2026-10-09 承运硬约束</b>：跨区成交<b>先选承运、再落账</b>；商号/路线可承运量不足 ⇒ 成交数量收缩到实际可承运量
   * （{@code executed}），未承运部分不发货、不免费成交，并以 {@link MarketUnfilledReason#LOGISTICS_CAPACITY} 具名留在
   * 买卖槽位的剩余里。完全没有可承运量 ⇒ 本笔成交量为 0（不改任何余额、不铸货腿/钱腿）。
   *
   * <p>★★ <b>运费与货款解耦</b>：货款腿只由 {@code unitPrice} 决定（0 价 ⇒ 0），运费腿由
   * {@code route.freightPerUnit}（商品种类 × 路线费率 × 承运成本）决定 —— 0 价免费交易仍要付运费。
   *
   * @return 实际成交量（毫商品）；0 = 本笔没有成交（调用方不得再减槽位剩余）
   */
  private static long executeTrade(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      RouteContext route) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    // ★ 2026-10-09：route 非 null 且 immediate = 区内跨格（有商号承运，货款/运费当日结清、没有 ShipmentBatch）；
    //   route 非 null 且 !immediate = 跨区在途；route == null = 同 hex 即时（零运费）。
    boolean inTransit = route != null && !route.immediate;
    HexCoord location = inTransit ? route.from : sell.hex;

    // ── ① 承运选择（跨格才需要）：容量不足时把成交收缩到实际可承运量 ─────────────────────────
    long executed = quantity;
    List<FreightCharge> freightCharges = new ArrayList<>();
    long freight = 0L;
    long uncollectedFreight = 0L;
    if (route != null) {
      long nominalFreight = freightOf(quantity, route.freightPerUnit);
      if (!ctx.merchantFirms.isEmpty()) {
        MerchantSettlement.CarrierAllocation allocation =
            ctx.carrierPool.select(route.from, route.to, quantity, route.freightRatePerMille);
        long allocated = allocation.allocatedMilli();
        if (allocated <= 0L) {
          markCapacityBlocked(ctx, buy, sell, route);
          return 0L; // 一点承运运力都没有 ⇒ 不成交（绝不发"免费"的跨区货）
        }
        executed = Math.min(quantity, allocated);
        if (executed < quantity) {
          markCapacityBlocked(ctx, buy, sell, route);
          // 应收而未收的名义运费：承运池算不出这部分的收款人，读数具名、不静默变 0。
          uncollectedFreight = freightOf(quantity - executed, route.freightPerUnit);
        }
        freightCharges = carrierChargeSplit(allocation, buy.buyer.actor, route);
        for (FreightCharge charge : freightCharges) {
          freight = Math.addExact(freight, charge.amountMilli());
        }
      } else if (ctx.carrier.isPresent()) {
        // 旧路径（merchantFirms 为空）：第一个有货币账的 ORGANIZATION 承运整票；自承运同样不收运费。
        // 名义费率为 0 时不出零额腿，也不记未收（旧口径 freight > 0 才铸）。
        ActorRef legacyCarrier = ctx.carrier.get();
        if (!legacyCarrier.equals(buy.buyer.actor) && nominalFreight > 0L) {
          freightCharges = List.of(new FreightCharge(legacyCarrier, nominalFreight));
          freight = nominalFreight;
        }
      } else {
        // 没有商号服务 / 没有可用承运人：整票名义运费记未收（旧行为），钱不凭空消失。
        uncollectedFreight = nominalFreight;
      }
    }

    long payment =
        ceilDiv(
            Math.multiplyExact(executed, unitPrice), EconomySettlement.MILLI_PER_GRAIN);
    // ★★ D-027：区级配额按**真正落账**的毛量逐笔扣（唯一扣减点；worker 扣本区副本、协调器回放时扣共享表
    //   ⇒ 跨区撮合看到的是剩余额度）。配额只压成交上限，不改价、不承担物流成本。
    ctx.consumeQuota(sell.region, commodity, executed);
    // ★★ D-027：单 hex 贸易成本只在**同一市场区**的区内即时成交上逐笔计量（跨区在途走 route.lossPerMille，
    //   口径不变）。第一版只表达为实物损耗：同格 = 0、跨格 = HexTradeCost 的具名公式并夹在 quantity 内。
    long lossMilli =
        !inTransit && !sell.hex.equals(buy.hex)
            ? Math.min(
                executed,
                Math.multiplyExact(executed, ctx.hexTradeCost.lossPerMilleBetween(sell.hex, buy.hex))
                    / 1000L)
            : 0L;

    // ② 卖方把已冻结的那一份放出来，再走唯一 applier（货腿：卖方 → 买方）。
    long sellRelease = Math.min(executed, sell.frozenRemaining);
    sell.frozenRemaining -= sellRelease;
    releaseSellFrozenSum(ctx, sell, sellRelease);
    refreshSellFrozen(ctx, sell);
    Transfer goodsLeg =
        round.ledger.mint(
            sell.seller.actor,
            buy.buyer.actor,
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

    // ③ 买方把冻结的货款（+运费）放出来，再货款 → 卖方、运费 → 承运人。
    long total = payment + freight;
    long buyRelease = Math.min(total, buy.frozenRemaining);
    buy.frozenRemaining -= buyRelease;
    releaseBuyFrozenSum(ctx, buy, buyRelease);
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
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          moneyLeg);
    }
    // ★ P11.3：逐条实际承运条目分别铸 CARRIER_FEE；freightPaidMilli 只累加真实铸出的金额（Σ = 实际可收运费，
    //   自承运条目已在 carrierChargeSplit 里剔除，因此不会出现"买方 → 买方"的自转移）。
    for (FreightCharge charge : freightCharges) {
      Transfer freightLeg =
          round.ledger.mint(
              buy.buyer.actor,
              charge.carrierActor(),
              route.to,
              Map.of(),
              Map.of(buy.currency, charge.amountMilli()),
              TransferReason.CARRIER_FEE);
      EconomySettlement.applyTransfer(
          round.householdGoods,
          round.householdMoney,
          round.householdFrozenGoods,
          round.householdFrozenMoney,
          round.householdOfActor,
          freightLeg);
      ctx.freightPaidMilli += charge.amountMilli();
    }
    ctx.freightUncollectedMilli += uncollectedFreight;
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
              actorKeyOf(sell.seller.actor),
              actorKeyOf(buy.buyer.actor)));
    }

    if (!inTransit) {
      // 区内即时（同 hex 零运费 / 同区跨格有商号运费）：买到的**净量**冲减当日未满足需求（封顶 = 已记的缺口；只对家户）。
      // ★ D-027：买方按毛量付款、收到毛量 − 损耗 ⇒ 冲减的也是净量（否则缺口会被高估成"买到没损耗"）。
      if (buy.buyer.household != null) {
        reduceUnmet(round.unmetToday, buy.buyer.household, commodity, executed - lossMilli);
      }
      ctx.immediateFills++;
      long tariffPerUnit = ctx.tariffPerUnitOf(sell.regionId, commodity);
      MarketReport.Fill fill =
          new MarketReport.Fill(
              sell.hex,
              buy.hex,
              commodity,
              sell.seller.actor,
              buy.buyer.actor,
              executed,
              unitPrice,
              route == null ? 0L : route.freightPerUnit,
              payment,
              freight,
              round.day,
              true,
              "",
              lossMilli);
      ctx.fills.add(fill);
      if (tariffPerUnit > 0L) {
        // ★★ D-027：区级税费**只累计读数**（毫计价货币；本批不搬钱、不铸币、不落债务），收款方后续批次再定。
        ctx.tariffByFill.put(fill, tariffPerUnit);
      }
      return executed;
    }

    // ④ 跨区：合并到同 (from,to,commodity,arrival) 的在途批次，并保留逐票损耗归属。
    ShipmentKey key = new ShipmentKey(route.from, route.to, commodity, route.arrivalTick);
    ShipmentBuilder batch =
        ctx.shipments.computeIfAbsent(key, ignored -> new ShipmentBuilder(route));
    batch.quantity += executed;
    batch.allocations.add(
        new ShipmentAllocation(
            sell.seller.actor, buy.buyer.actor, buy.order.deliverTo(), executed, LossBearer.BUYER));
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
    ctx.scheduledLossMilli += Math.multiplyExact(executed, route.lossPerMille) / 1000L;
    ctx.crossRegionFills++;
    // 读数里的单位运费按**本票实收**折算（不是 route 的指示费率）：L3 的到货价 = 单价 + 这一栏。
    long reportedFreightPerUnit =
        freight > 0L
            ? ceilDiv(Math.multiplyExact(freight, EconomySettlement.MILLI_PER_GRAIN), executed)
            : 0L;
    ctx.fills.add(
        new MarketReport.Fill(
            route.from,
            route.to,
            commodity,
            sell.seller.actor,
            buy.buyer.actor,
            executed,
            unitPrice,
            reportedFreightPerUnit,
            payment,
            freight,
            route.arrivalTick,
            false,
            shipmentId,
            // ★ M2.7：逐票预排损耗与到货日的扣减公式逐字同源（deliverShipments 也是 quantity × lossPerMille ÷ 1000）。
            Math.multiplyExact(executed, route.lossPerMille) / 1000L));
    return executed;
  }

  /** ★★ 承运容量不足的具名落点：买方槽 blocked、卖方槽 capacityBlocked、路线 bottleneck，三处都读得到。 */
  private static void markCapacityBlocked(
      MatchContext ctx, BuySlot buy, SellSlot sell, RouteContext route) {
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
    }
    sell.capacityBlocked = true;
    RouteAccumulator acc =
        ctx.routes.get(route.from + "->" + route.to + "#" + route.commodity.value());
    if (acc != null) {
      acc.bottleneck = true;
    }
  }

  /**
   * ★★ <b>P11.3：把一票跨区运费分摊成逐商号的 CARRIER_FEE 金额</b>。
   *
   * <pre>
   * ① 自承运条目（principalActor == 买方 actor）整条跳过：不铸自转移、不计实收、不记未收（P10.9 口径）；
   * ② 实际可收运费 = min(非自承运部分的名义运费上限, Σ 各条按自身有效到货费率的运费)；
   * ③ 其余条目按承运量占非自承运总量的比例 floor 分摊，顺序里**最后一条实际承运条目拿余数**
   *    ⇒ Σ各条金额 == 实际可收运费，且 ≤ 该票 nominal freight；
   * ④ 金额为 0 的条目不产生转移（仍保持守恒）。
   * </pre>
   *
   * <p>★ 分摊顺序 = select 返回顺序（有效费率升序 → organizationId 升序），不读时钟/随机 ⇒ 同输入逐值确定。
   */
  private static List<FreightCharge> carrierChargeSplit(
      MerchantSettlement.CarrierAllocation allocation, ActorRef buyerActor, RouteContext route) {
    List<MerchantSettlement.CarrierChoice> choices = allocation.choices();
    long chargeableQuantity = 0L;
    long effectiveFreightSum = 0L;
    int lastChargeable = -1;
    for (int i = 0; i < choices.size(); i++) {
      MerchantSettlement.CarrierChoice choice = choices.get(i);
      if (choice.principalActor().equals(buyerActor)) {
        continue; // 自承运：该条的运费不进入实收，也不进入未收
      }
      lastChargeable = i;
      chargeableQuantity = Math.addExact(chargeableQuantity, choice.quantityMilli());
      effectiveFreightSum =
          Math.addExact(
              effectiveFreightSum,
              freightOf(
                  choice.quantityMilli(),
                  freightUnitMilli(
                      route.commodity,
                      choice.effectiveRatePerMille(route.freightRatePerMille),
                      carrierCostPerMille(choice.firm()))));
    }
    if (chargeableQuantity <= 0L) {
      return List.of();
    }
    long nominalCap = freightOf(chargeableQuantity, route.freightPerUnit);
    long collectible = Math.min(nominalCap, effectiveFreightSum);
    List<FreightCharge> charges = new ArrayList<>();
    long assigned = 0L;
    for (int i = 0; i < choices.size(); i++) {
      MerchantSettlement.CarrierChoice choice = choices.get(i);
      if (choice.principalActor().equals(buyerActor)) {
        continue;
      }
      long amount;
      if (i == lastChargeable) {
        amount = collectible - assigned; // 余数全部给顺序里最后一条实际承运条目
      } else {
        amount = Math.multiplyExact(collectible, choice.quantityMilli()) / chargeableQuantity;
        assigned = Math.addExact(assigned, amount);
      }
      if (amount > 0L) {
        charges.add(new FreightCharge(choice.principalActor(), amount));
      }
    }
    return List.copyOf(charges);
  }

  /** 一条实际要铸的 CARRIER_FEE 腿（P11.3）：收款 principal + 金额（毫计价货币）。 */
  private record FreightCharge(ActorRef carrierActor, long amountMilli) {
    private FreightCharge {
      Objects.requireNonNull(carrierActor, "carrierActor");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("FreightCharge 金额必须为正: " + amountMilli);
      }
    }
  }

  /** 把刚记到买方名下的量移出会话余额（在途资产的装载；到货日反向落回）。 */
  private static void loadInTransit(MatchContext ctx, BuySlot buy, long quantity) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    HouseholdId key = buy.buyer.household;
    long stock = householdStockOf(round.householdGoods, key, commodity);
    if (stock < quantity) {
      throw new IllegalStateException(
          "装载在途时买方库存不足（货腿刚记上，账不应漂开）：家户=" + key + " 商品=" + commodity + " 余额=" + stock);
    }
    setHouseholdStock(round.householdGoods, key, commodity, stock - quantity);
  }

  /**
   * ★★ <b>D-027：单 hex 即时贸易损耗的「非换手扣减」落点</b>（spec §3.2 的守恒实现口径）。
   *
   * <p>货腿已由唯一 applier 按<b>毛量</b>记到买方名下；这里把损耗那一份从买方会话余额移走 —— 它<b>不换手给任何
   * 人</b>，与 {@link #loadInTransit} 把货物移进在途批次是同一类"货物离开账户但未换手"的落点（唯一区别：在途
   * 批次日后会反向落回，损耗不再回来）。扣减本身不产生转移、不铸币、不写第二本账；损耗的唯一凭据是紧随其后的
   * {@code ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, …)}。
   *
   * <p>买方是家户 ⇒ 扣家户账；买方是经营者 ⇒ 扣经营者账。扣前校验买方余额 {@code >= lossMilli}（货腿刚按毛量
   * 入账，正常必然够）；不足时抛具名 {@link IllegalStateException}，不静默夹 0、不造负库存。
   */
  private static void deductBuyerLossNoTransfer(MatchContext ctx, BuySlot buy, long lossMilli) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    HouseholdId key = buy.buyer.household;
    long stock = householdStockOf(round.householdGoods, key, commodity);
    if (stock < lossMilli) {
      throw new IllegalStateException(
          "扣减单 hex 贸易损耗时买方家户库存不足（货腿刚按毛量记上，账不应漂开）：家户="
              + key
              + " 商品="
              + commodity
              + " 余额="
              + stock
              + " 损耗="
              + lossMilli);
    }
    setHouseholdStock(round.householdGoods, key, commodity, stock - lossMilli);
  }

  // ── 会话副本的最小读写（本类不引入第二个 applier；这里只是把"哪张表、哪个键"说清）──────────────

  private static long householdStockOf(
      Map<HouseholdId, Map<CommodityId, Long>> goods, HouseholdId key, CommodityId commodity) {
    return goods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  private static void setHouseholdStock(
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      HouseholdId key,
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
   *
   * <p>★★ D-027：参考价走 {@link MatchContext#referencePriceOf}（卖方格所属区的调控覆盖优先）—— 与
   * {@link #ordersFor} 同一条口径，区内/跨区不漂开。
   */
  private static long unitPriceOf(MatchContext ctx, List<SellSlot> sells, CommodityId commodity) {
    long price = 0L;
    for (SellSlot sell : sells) {
      if (sell.order.commodity().equals(commodity)) {
        price = Math.max(price, ctx.referencePriceOf(sell.regionId, sell.market, commodity));
      }
    }
    return price;
  }

  /**
   * ★★ <b>商品种类的基础运费（毫计价货币 / 商品单位 / 程）</b>：只由商品种类决定，<b>与商品价格无关</b>
   * （2026-10-09 用户口径："运费只和商品种类有关"）。粮/纤维轻而贱、布/工具更重更占运力，故基础费分档；
   * 未登记的商品取 {@link #MARKET_FREIGHT_BASE_PER_UNIT_DEFAULT_MILLI}（粗估，不静默给 0）。
   */
  static final long MARKET_FREIGHT_BASE_PER_UNIT_GRAIN_MILLI = 1L;

  static final long MARKET_FREIGHT_BASE_PER_UNIT_FIBER_MILLI = 1L;
  static final long MARKET_FREIGHT_BASE_PER_UNIT_CLOTH_MILLI = 2L;
  static final long MARKET_FREIGHT_BASE_PER_UNIT_TOOL_MILLI = 3L;
  static final long MARKET_FREIGHT_BASE_PER_UNIT_DEFAULT_MILLI = 1L;

  /**
   * ★★ <b>承运方运营成本加价（‰ / 每档 tier 城区当量）</b>：脚夫与商人"要吃饭"的粗估表示 —— 承运不是免费的
   * 公共服务，运费里必须含这笔成本。PORTER 25‰ / SELF_EMPLOYED 50‰ / BOSS 100‰（数值是粗估、可由 GM 改）。
   */
  static final long MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP = 25L;

  /** 没有商号（旧 {@code carrierOf} 路径）时的默认承运成本（‰）；也用于撮合前的可负担量预判。 */
  static final long MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE = 25L;

  /** 商品种类基础运费（按 {@code EconomyVocabulary} 的稳定 id 分档；只此一处拼写）。 */
  static long commodityFreightBaseMilli(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    String value = commodity.value();
    if (EconomyVocabulary.GRAIN_COMMODITY_ID.equals(value)) {
      return MARKET_FREIGHT_BASE_PER_UNIT_GRAIN_MILLI;
    }
    if (EconomyVocabulary.FIBER_COMMODITY_ID.equals(value)) {
      return MARKET_FREIGHT_BASE_PER_UNIT_FIBER_MILLI;
    }
    if (EconomyVocabulary.CLOTH_COMMODITY_ID.equals(value)) {
      return MARKET_FREIGHT_BASE_PER_UNIT_CLOTH_MILLI;
    }
    if (EconomyVocabulary.TOOL_COMMODITY_ID.equals(value)) {
      return MARKET_FREIGHT_BASE_PER_UNIT_TOOL_MILLI;
    }
    return MARKET_FREIGHT_BASE_PER_UNIT_DEFAULT_MILLI;
  }

  /** 某商号的承运成本（‰）：tier 城区当量 × {@link #MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP}。 */
  static long carrierCostPerMille(MerchantFirm firm) {
    Objects.requireNonNull(firm, "firm");
    return Math.multiplyExact(
        (long) firm.tier().districtUse(), MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>撮合前可负担性预判用的承运成本（‰）</b>：有商号时取最高档 tier 的成本上界（BOSS=4×25=100‰），
   * 保证 {@code pairUp} 按它规划的钱 ≤ 实际逐商号收费；没有商号（旧路径）沿用小默认值。
   */
  static long plannedCarrierCostPerMille(MatchContext ctx) {
    if (ctx.merchantFirms.isEmpty()) {
      return MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE;
    }
    return Math.multiplyExact(
        (long) MerchantPolicy.MerchantTier.BOSS.districtUse(),
        MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>单位运费（毫计价货币 / 商品单位）的唯一算式</b>（2026-10-09 与货款价格解耦）：
   *
   * <pre>
   * unit = max(1, ⌈ 商品种类基础费 × (1000 + 路线费率‰) × (1000 + 承运成本‰) ÷ 1,000,000 ⌉ )
   * </pre>
   *
   * <p>三项来源：① 商品种类（{@link #commodityFreightBaseMilli}）；② 路线费率（{@link
   * MarketTopology#freightPerMilleBetween} 的里程/辐射/道路）；③ 承运成本（{@link #carrierCostPerMille}，
   * 脚夫/商号要吃饭的粗估）。<b>不含</b> {@code unitPrice} —— 0 价免费商品仍产生正运费。
   */
  static long freightUnitMilli(
      CommodityId commodity, long ratePerMille, long carrierCostPerMille) {
    if (ratePerMille < 0L) {
      throw new IllegalArgumentException("ratePerMille 不得为负: " + ratePerMille);
    }
    if (carrierCostPerMille < 0L) {
      throw new IllegalArgumentException("carrierCostPerMille 不得为负: " + carrierCostPerMille);
    }
    long routeFactor = Math.addExact(1000L, ratePerMille);
    long carrierFactor = Math.addExact(1000L, carrierCostPerMille);
    long product =
        Math.multiplyExact(
            Math.multiplyExact(commodityFreightBaseMilli(commodity), routeFactor), carrierFactor);
    return Math.max(1L, ceilDiv(product, 1_000_000L));
  }

  /**
   * 一条成交量的运费：{@code ⌈数量(毫商品) × 单位运费(毫钱/商品单位) ÷ 1000⌉}（毫计价货币）。 ★ 单位运费由
   * {@link #freightUnitMilli} 给出；<b>不看货款单价</b>。
   */
  private static long freightOf(long quantityMilli, long unitFreightMilli) {
    if (quantityMilli <= 0L || unitFreightMilli <= 0L) {
      return 0L;
    }
    return ceilDiv(Math.multiplyExact(quantityMilli, unitFreightMilli), 1000L);
  }

  /**
   * 买方在这条路上**计划**买得起多少：可花余额 + 本单剩余冻结，按"单价 + 单位运费"折算，再受订单预算余额封顶， 并保留 {@link
   * #MARKET_MONEY_ROUNDING_MARGIN_MILLI} 的保守回退。
   *
   * <p>★★ <b>它不是安全边界的守卫</b>：货款/运费各自向上取整，整单反解出的量逐笔成交时可能多付。累计付款 ≤ 买方 可支配的保证在 {@link #pairUp} ——
   * 每笔成交前用 {@link #totalCostAtMost} 按当前剩余可付精确复核并封顶（见 {@link
   * #exactAffordableUpTo}）。这样保留原口径的配给数量，不再依赖边距兜住"N 笔各 ceil 一毫"。
   */
  private static long affordableQuantity(
      MatchContext ctx, BuySlot buy, long unitPrice, RouteContext route) {
    long unitCost = unitPrice + (route == null ? 0L : route.freightPerUnit);
    if (unitCost <= 0L) {
      // ★★ 完全免费（0 价 + 0 运费，典型 = 区内即时免费拿）：钱不是约束，数量由需求/供给决定。
      //    ★ 跨区 0 价仍要付运费（route.freightPerUnit > 0）⇒ 走下面的按钱折算分支。
      return Long.MAX_VALUE;
    }
    long money = payableMoneyOf(ctx, buy);
    if (money <= MARKET_MONEY_ROUNDING_MARGIN_MILLI) {
      return 0L;
    }
    money -= MARKET_MONEY_ROUNDING_MARGIN_MILLI;
    return safeMulDiv(money, EconomySettlement.MILLI_PER_GRAIN, unitCost);
  }

  /**
   * 这一张买单当前还能动用的钱：{@code min(可花 + 本单剩余冻结, 订单预算余额 + 本单剩余冻结)}。
   *
   * <p>★ 它不是"还买得起多少"（那还要按价与逐笔 ceil 反解），只是"钱包/预算层面是否已经耗尽" —— {@link #pairUp} 用它给 {@link
   * BuySlot#noMoney} 置位，避免余额已经归零的买方在每条跨区路线上反复重试。
   */
  private static long payableMoneyOf(MatchContext ctx, BuySlot buy) {
    long money = spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
    long budgetLeft = Math.max(0L, buy.order.budget().amountMilli() - buy.spentMilli);
    return Math.min(money, budgetLeft + buy.frozenRemaining);
  }

  /**
   * 这一笔数量按**与 {@link #executeTrade} 同一算式**算出的总价（货款 + 运费）是否 ≤ {@code money}。运费与货款解耦
   * （{@link #freightUnitMilli}），0 价商品仍计运费。全程 {@code long}；乘法真的会溢出 ⇒ 这个数量在 {@code
   * executeTrade} 里同样不可付，按"付不起"处理。
   */
  private static boolean totalCostAtMost(
      long quantity, long money, long unitPrice, RouteContext route) {
    long payment;
    try {
      payment =
          ceilDivPositive(
              Math.multiplyExact(quantity, unitPrice), EconomySettlement.MILLI_PER_GRAIN);
    } catch (ArithmeticException overflow) {
      return false;
    }
    if (payment > money) {
      return false;
    }
    if (route == null || route.freightPerUnit <= 0L) {
      return true;
    }
    long freight;
    try {
      freight = ceilDivPositive(Math.multiplyExact(quantity, route.freightPerUnit), 1000L);
    } catch (ArithmeticException overflow) {
      return false;
    }
    return freight <= money - payment;
  }

  /**
   * 把预分配/计划量按**当前剩余可付**精确封顶：最大 {@code q ≤ upper} 使 {@code q} 这一笔的总价（货款 + 名义运费）≤ {@code payable}。
   *
   * <p>★ 这是缺陷 A 的安全点：{@link #affordableQuantity} 的边距只负责"保守少买"，跨区运费与两处 ceil 造成
   * 的累计越界在这里被逐笔按实际账削平 ⇒ 任何成交序列下付款 ≤ 可支配（冻结 + 可花）。★ 0 价 + 0 运费的完全免费交易
   * 在 {@code payable == 0} 时也应放行，故这里不再用 {@code payable <= 0} 提前判死（由 {@link
   * #totalCostAtMost} 按真实总价回答）。
   */
  private static long exactAffordableUpTo(
      long upper, long payable, long unitPrice, RouteContext route) {
    if (upper <= 0L) {
      return 0L;
    }
    if (totalCostAtMost(upper, payable, unitPrice, route)) {
      return upper;
    }
    long low = 0L;
    long high = upper;
    while (low < high) {
      long mid = low + (high - low + 1L) / 2L;
      if (totalCostAtMost(mid, payable, unitPrice, route)) {
        low = mid;
      } else {
        high = mid - 1L;
      }
    }
    return low;
  }

  /** 向下取整的 {@code value × multiplier ÷ divisor}；乘法溢出时保守回退成 `Long.MAX_VALUE`。 */
  private static long safeMulDiv(long value, long multiplier, long divisor) {
    if (divisor <= 0L) {
      return Long.MAX_VALUE;
    }
    try {
      return Math.multiplyExact(value, multiplier) / divisor;
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** 正整数的向上取整除法（不靠 {@code numerator + divisor - 1}，避免那一处溢出）。 */
  private static long ceilDivPositive(long numerator, long divisor) {
    long quotient = numerator / divisor;
    return numerator % divisor == 0L ? quotient : quotient + 1L;
  }

  // ── 未成交原因 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>S3：逐买方 / 逐卖方原因 + 逐槽位只读结果</b>（计划 §S3.1）。
   *
   * <p>★★ <b>卖方原因不再由全局 {@code anyBuy} 决定</b>：逐条卖方剩余按"同商品、同/邻区是否存在合格买方 （预算、限价、到货时限、路线可达）"分档；买方的
   * {@code NO_BUDGET} 只挂在该买方自己的槽位上（见下）。
   */
  private static void collectUnfilled(MatchContext ctx, MarketIndexes indexes) {
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
      // ★★ D-030：信用可能覆盖"没钱"这一档 ⇒ NO_BUDGET 先让位给信用归因，最后仍没信用再落回 NO_BUDGET。
      if (reason == MarketUnfilledReason.NO_BUDGET) {
        reason = null;
      }
      if (reason == null && ctx.quotaExhausted(buy.region, buy.order.commodity())) {
        // ★★ D-027：区级配额已经用尽 ⇒ 买方剩余是制度原因（不是没钱/没货/路不通），具名 REGULATION_QUOTA。
        reason = MarketUnfilledReason.REGULATION_QUOTA;
      }
      if (reason == null) {
        MarketUnfilledReason creditReason = creditUnfilledReason(ctx, buy);
        long cashSpendable =
            spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
        boolean cashBlocked = buy.requestedMoney <= 0L || cashSpendable <= 0L;
        if (cashBlocked
            && (creditReason == null || creditReason == MarketUnfilledReason.NO_BUDGET)) {
          reason = MarketUnfilledReason.NO_BUDGET;
        } else if (totalSellRemaining <= 0L) {
          reason =
              creditReason == MarketUnfilledReason.NO_LENDABLE_GOODS
                  ? creditReason
                  : inputShortfallNear(ctx, buy, indexes)
                      ? MarketUnfilledReason.INPUT_SHORTFALL
                      : classifyNoSupply(ctx, buy, indexes);
        } else if (!hasSupplyNear(buy, indexes)) {
          reason = MarketUnfilledReason.NO_ADJACENT_SUPPLY;
        } else if (minSellerPrice != Long.MAX_VALUE
            && buy.order.maxLandedPrice() < minSellerPrice) {
          reason = MarketUnfilledReason.PRICE_LIMIT;
        } else if (creditReason != null) {
          reason = creditReason;
        } else if (cashBlocked) {
          reason = MarketUnfilledReason.NO_BUDGET;
        } else {
          reason = MarketUnfilledReason.ALGORITHM_UNCOVERED;
        }
      }
      ctx.unfilled.add(
          new MarketReport.Unfilled(
              buy.buyer.actor, true, buy.order.commodity(), buy.remaining, reason, buy.hex));
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
              sell.seller.actor, false, sell.order.commodity(), sell.remaining, reason, sell.hex));
    }
    collectSellerOutcomes(ctx, indexes, sellerReasons);
    collectBuyerOutcomes(ctx, indexes);
  }

  /** 一个卖方槽位的合格买方扫描结果（逐条判定；{@code anyBuy} 只作证据，不直接决定档位）。 */
  private record BuyEligibility(
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
  private static MarketUnfilledReason sellerReason(
      MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
    // ★★ 2026-10-09：承运容量（商号每周期运力 / 路线每窗口容量）是本槽剩余的直接原因时，优先具名物流瓶颈，
    //    不让它掉进 OUTCOMPETED/ALGORITHM_UNCOVERED 掩盖过去。
    if (sell.capacityBlocked) {
      return MarketUnfilledReason.LOGISTICS_CAPACITY;
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
  private static BuyEligibility scanEligibleBuyers(
      MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
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
        long spendable = spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
        if (buy.noMoney || spendable <= 0L) {
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
        if (buy.order.maxLandedPrice() < sell.order.minPrice()) {
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
  private static Outcompeted outcompetedBy(MatchContext ctx, SellSlot sell, MarketIndexes indexes) {
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
          cheaperActors.add(other.seller.actor.kind() + ":" + other.seller.actor.id());
          cheaperFilled += filled;
        }
      }
    }
    return new Outcompeted(cheaperActors.size(), cheaperFilled);
  }

  private record Outcompeted(long actors, long qty) {}

  /** 买方"没有供给"时是否其实是生产侧投入不足：本格有产该商品、有产能、但本周期投入没凑齐的 unit。 ★ 只查买方所在格（第一版口径；邻接格留待跨区协调那一轮，不为假想需要造扫描）。 */
  private static boolean inputShortfallNear(MatchContext ctx, BuySlot buy, MarketIndexes indexes) {
    String hexKey = hexKeyOf(buy.hex);
    // ★ R4-B.3a-perf：参与者按 hex 的索引已在 MarketIndexes 里建好（旧实现每次现扫全部参与者）。
    for (Participant participant : indexes.participantsByHex.getOrDefault(hexKey, List.of())) {
      for (ProductionUnitId id : participant.units) {
        ProductionUnit unit = ctx.round.units.get(id);
        Industry industry = unit == null ? null : ctx.round.industries.get(unit.industry());
        if (unit == null
            || industry == null
            || !industry.outputPerUnit().containsKey(buy.order.commodity())) {
          continue;
        }
        long scale =
            ProductionUnitBook.plannedCapacityScaleOf(
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
  private static boolean sellerSelfUsable(MatchContext ctx, SellSlot sell) {
    CommodityId commodity = sell.order.commodity();
    if (necessaryInputsOf(ctx.round, sell.seller).getOrDefault(commodity, 0L) > 0L) {
      return true;
    }
    if (sell.seller.household() != null) {
      ClassRow row = ctx.round.rows.get(sell.seller.household());
      return row != null && householdLifeReserveOf(row).getOrDefault(commodity, 0L) > 0L;
    }
    return false;
  }

  /** 经营者状态机判定"真无法再生产"：{@code INDEBTED} 及以后（ACTIVE/TRIALING/CONTRACTING 不在此列）。 */
  private static boolean sellerCannotReproduce(MatchContext ctx, SellSlot sell) {
    ProductionUnit unit = unitForSeller(ctx.round, sell.seller, sell.order.commodity());
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
  private static void collectSellerOutcomes(
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
      ProductionUnit unit = unitForSeller(ctx.round, sell.seller, commodity);
      ctx.sellerOutcomes.add(
          new MarketReport.SellerOutcome(
              ctx.round.day,
              sell.seller.actor,
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
  private static Map<SellSlot, Integer> sellerCostRanks(MatchContext ctx) {
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

  /** 本卖方的商品成交里最低的一笔到货价（单价 + 单位运费；无成交 ⇒ empty）。 */
  private static OptionalLong bestAcceptedLandedPrice(MatchContext ctx, SellSlot sell) {
    long best = Long.MAX_VALUE;
    for (MarketReport.Fill fill : ctx.fills) {
      if (!fill.commodity().equals(sell.order.commodity())
          || !fill.seller().equals(sell.seller.actor())) {
        continue;
      }
      long landed = fill.unitPriceMilli() + fill.freightPerUnitMilli();
      if (landed < best) {
        best = landed;
      }
    }
    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }

  /** 逐买方槽位的只读结果（库存/覆盖/缺口/预算/下单/成交/原因；含"库存已足"的 {@code STOCK_SUFFICIENT}）。 */
  private static void collectBuyerOutcomes(MatchContext ctx, MarketIndexes indexes) {
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
              actorKeyOf(buy.buyer.actor) + "#" + commodity.value(), ignored -> new ArrayList<>())
          .add(buy);
    }
    // ★★ R4-E2：读口 desired 必须与订单生成同源 —— 有效需求目标也进"目标量"（否则报告会说"缺口 0"
    //   而买单非 0）。量与 planFor 走同一段 DemandTargets 逻辑，不另算一份。
    Map<String, Map<HouseholdId, Map<CommodityId, Long>>> demandTargetsByHex =
        DemandTargets.totalsByHex(ctx.round.demands(), ctx.round.rows, ctx.round.day);
    for (Participant participant : ctx.participants.values()) {
      HexCoord hex = ctx.participantHex.get(participant.actor);
      if (hex == null) {
        continue;
      }
      Market market = ctx.markets.get(hex);
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
          desired =
              Math.addExact(lifeReserveOfParticipant(ctx, participant, commodity), demandTarget);
        }
        List<BuySlot> slots =
            buysByActorCommodity.getOrDefault(
                actorKeyOf(participant.actor) + "#" + commodity.value(), List.of());
        if (desired <= 0L && slots.isEmpty()) {
          continue;
        }
        long stock = stockOf(ctx.round, participant, commodity);
        long frozen = frozenGoodsOf(ctx.round, participant, commodity);
        long onHand = Math.max(0L, stock - frozen);
        long incoming =
            incomingByActorCommodity.getOrDefault(
                actorKeyOf(participant.actor) + "#" + commodity.value(), 0L);
        long gap = Math.max(0L, desired - onHand - incoming);
        long budget = spendableMoneyOf(ctx.round, participant, market.numeraire());
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
          ClassRow row = ctx.round.rows.get(participant.household());
          if (row != null) {
            long daily = row.naturalNeeds().getOrDefault(commodity, 0L);
            if (daily > 0L) {
              coverDays = OptionalLong.of(onHand / daily);
            }
          }
        }
        Optional<MarketUnfilledReason> reason;
        if (gap <= 0L) {
          reason = Optional.of(MarketUnfilledReason.STOCK_SUFFICIENT);
        } else if (ordered <= 0L) {
          reason =
              Optional.of(
                  budget <= 0L || affordable <= 0L
                      ? MarketUnfilledReason.NO_BUDGET
                      : MarketUnfilledReason.ALGORITHM_UNCOVERED);
        } else if (filled >= ordered) {
          reason = Optional.empty();
        } else if (slotReason != null) {
          reason = Optional.of(slotReason);
        } else {
          reason =
              Optional.of(
                  spendableMoneyOf(ctx.round, participant, market.numeraire()) <= 0L
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

  /** 家户在某商品上的生活保留（家户没有该商品的需要 ⇒ 0；不是"读不到"）。 */
  private static long lifeReserveOfParticipant(
      MatchContext ctx, Participant participant, CommodityId commodity) {
    ClassRow row = ctx.round.rows.get(participant.household());
    return row == null ? 0L : householdLifeReserveOf(row).getOrDefault(commodity, 0L);
  }

  /**
   * 买方的区里/直接邻接供应区里，还有同商品的剩余卖单吗（"看不见的供给"与"没有供给"分开）。
   *
   * <p>★ P1.2：用 {@code sellsByRegionCommodity} + {@code adjacentRegionIds} 直取，不再扫全部卖单、也不再逐对调几何判断。
   */
  private static boolean hasSupplyNear(BuySlot buy, MarketIndexes indexes) {
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
  private static boolean hasActiveSellInRegion(
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
  private static MarketUnfilledReason classifyNoSupply(
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

  /** 一个格的参与者：家户（按行）在前，经营者（该格 unit 的 operator，逐 actor 去重）在后。 */
  private static List<Participant> participantsFor(
      MarketRound round, HexCoord hex, List<HouseholdId> keys) {
    String hexKey = hexKeyOf(hex);
    Map<ActorRef, List<ProductionUnitId>> unitsByOperator = new LinkedHashMap<>();
    // ★ R4-B.3a-perf：本格 unit 由入口索引一次给出（序 = unit 表序，旧实现的全表过滤同此序）。
    for (ProductionUnitId id : round.index.unitsInHex(hexKey)) {
      ProductionUnit unit = round.units.get(id);
      if (unit == null) {
        continue; // 索引与活表同源；这里只防御手工状态在两者之间被改动
      }
      unitsByOperator.computeIfAbsent(unit.operator(), ignored -> new ArrayList<>()).add(id);
    }
    LinkedHashMap<ActorRef, Participant> byActor = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      ActorRef actor = HouseholdActors.of(key);
      List<ProductionUnitId> units = unitsByOperator.remove(actor);
      if (units != null) {
        // ★ 同一 operator 多 unit 时按 unit id canonical 序累加（必要投入/自留/卖单的确定性口径）。
        units.sort(Comparator.comparing(ProductionUnitId::value));
      }
      byActor.put(
          actor,
          new Participant(
              actor, key, round.rows.get(key).view(), units == null ? List.of() : units));
    }
    for (List<ProductionUnitId> units : unitsByOperator.values()) {
      units.sort(Comparator.comparing(ProductionUnitId::value));
    }
    // ★★ P2-A §13.3：经营者角色（庄园/作坊/商号）不持账 —— 逐 unit 解析到组织者/经营者家户：
    //   ① 单一家户（operator/relation/份额可解析）⇒ 把 unit 挂到该家户的参与者上；
    //   ② 解析不到单一主体但有名下劳动家户（集体经营，如家户纺织主 unit）⇒ 本批**具名缺口**：
    //      不把聚合 unit 当市场参与者（其产出已按劳动分给各家家户账，各家按自己的库存买卖）；
    //   ③ 连劳动家户都没有 ⇒ 具名抛（坏数据，不静默当成"零库存参与者"）。
    for (Map.Entry<ActorRef, List<ProductionUnitId>> entry : unitsByOperator.entrySet()) {
      for (ProductionUnitId unitId : entry.getValue()) {
        Optional<HouseholdId> single = round.index.economicHouseholdOf(unitId);
        if (single.isEmpty()) {
          List<HouseholdId> collective = round.index.householdsOf(unitId);
          if (collective.isEmpty()) {
            // ★★ P2-E：**没有任何正产能、也没有任何劳动配额**的 unit 是合法空壳（B.3b：
            //   "有经营者、无资产、不生产"是合法状态，资产可以被全部转走；0 产能的份额也走这里）。
            //   它没有库存可卖、没有产能可买投入，因此不生成任何订单 —— 具名跳过，不静默当成
            //   "零库存参与者"，也不把它当坏数据。有正资产或有劳动却解析不到家户 ⇒ 仍走下面的具名抛。
            boolean hasCapacity =
                round.index.usableAssetsOf(unitId).values().stream()
                    .anyMatch(quantity -> quantity != null && quantity > 0L);
            if (!hasCapacity) {
              MARKET.warn(
                  "event=MARKET_SUBJECT_EMPTY_UNIT unit={} operator={} reason=no-positive-asset-and-no-labor-allocation",
                  unitId.value(),
                  entry.getKey());
              continue;
            }
            throw new IllegalStateException(
                "市场参与者无法解析到任何家户（账户主体只有家户；聚合主体必须能解析到组织者/经营者家户）："
                    + "unit="
                    + unitId.value()
                    + " operator="
                    + entry.getKey());
          }
          // ② 集体经营（如家户纺织主 unit）：具名缺口 —— 不进市场参与者（产出已按劳动分给各家家户账，
          //    各家按自己的库存与预算参与市场）。
          MARKET.warn(
              "event=MARKET_SUBJECT_COLLECTIVE unit={} operator={} households={} reason=aggregate-unit-not-a-single-household",
              unitId.value(),
              entry.getKey(),
              collective);
          continue;
        }
        HouseholdId household = single.get();
        ClassRow row = round.rows.get(household);
        if (row == null) {
          throw new IllegalStateException(
              "市场参与者的组织者家户没有 ClassRow（解析结果必须是现存家户）：household="
                  + household
                  + " unit="
                  + unitId.value());
        }
        Participant existing = byActor.get(HouseholdActors.of(household));
        if (existing == null) {
          byActor.put(
              HouseholdActors.of(household),
              new Participant(
                  HouseholdActors.of(household), household, row.view(), List.of(unitId)));
        } else {
          List<ProductionUnitId> merged = new ArrayList<>(existing.units());
          merged.add(unitId);
          merged.sort(Comparator.comparing(ProductionUnitId::value));
          byActor.put(
              existing.actor(),
              new Participant(existing.actor(), household, row.view(), merged));
        }
      }
    }
    return List.copyOf(byActor.values());
  }

  /** 一个格的预计算（参与者 + 必要生产投入 + 生活保留 + 有效需求分段）。 */
  private static HexPlan planFor(MarketRound round, HexCoord hex, List<HouseholdId> keys) {
    List<Participant> participants = participantsFor(round, hex, keys);
    Map<ActorRef, Map<CommodityId, Long>> necessary = new LinkedHashMap<>();
    Map<ActorRef, Map<CommodityId, Long>> life = new LinkedHashMap<>();
    // ★★ R4-E2：需求账本 → 本格逐户目标量（每 hex 扫一次 demand 表；demands 空时是空表、零行为差异）。
    Map<HouseholdId, Map<CommodityId, List<Long>>> demandParts =
        DemandTargets.partsForHex(round.demands(), round.rows, hex, keys, round.day);
    Map<ActorRef, Map<CommodityId, List<Long>>> demandPartsByActor = new LinkedHashMap<>();
    for (Participant participant : participants) {
      necessary.put(participant.actor, necessaryInputsOf(round, participant));
      if (participant.household != null) {
        ClassRow row = round.rows.get(participant.household);
        life.put(participant.actor, row == null ? Map.of() : householdLifeReserveOf(row));
        demandPartsByActor.put(
            participant.actor, demandParts.getOrDefault(participant.household, Map.of()));
      } else {
        life.put(participant.actor, operatorLifeRetentionOf(round, participant, hex));
        demandPartsByActor.put(participant.actor, Map.of()); // 需求只归家户；经营者不因 GM 需求加目标
      }
    }
    return new HexPlan(participants, necessary, life, demandPartsByActor);
  }

  /**
   * ★★ <b>S3：一个卖方的单位成本估计</b>（{@link ProducerCostBook} 是唯一拼写点）—— 先在本格 unit 里认出"经营这个卖方的 unit"（优先：同一
   * actor 既是 unit.operator 又产出该商品；其次：本格任一出产该商品的 unit，按 id canonical 取小）， 再按该 unit 的产业模板 + 本格参考价算成本。
   *
   * <p>★★ <b>认不出 unit ⇒ {@link ProducerCostBook.Estimate#unknown()}</b>（排序排在已知成本之后）：不拿 0
   * 冒充"成本很低"（那是本仓禁的"假装便宜"）。
   */
  private static ProducerCostBook.Estimate costEstimateOf(
      MarketRound round, Market market, Participant seller, CommodityId commodity) {
    ProductionUnit unit = unitForSeller(round, seller, commodity);
    if (unit == null) {
      return ProducerCostBook.Estimate.unknown();
    }
    Industry industry = round.industries.get(unit.industry());
    if (industry == null) {
      return ProducerCostBook.Estimate.unknown();
    }
    return ProducerCostBook.estimate(
        unit, industry, round.index, market, round.relations.get(unit.id()));
  }

  /**
   * ★★ <b>"经营这个卖方的 unit"的唯一判定</b>：优先本格 unit 里 {@code unit.operator == seller} 且其模板出产该商品者 （按 unit id
   * canonical 取小），否则本格任一出产该商品者（同序取小）；认不出 ⇒ null（成本未知）。
   */
  private static ProductionUnit unitForSeller(
      MarketRound round, Participant seller, CommodityId commodity) {
    ProductionUnit match = null;
    for (ProductionUnitId id : seller.units) {
      ProductionUnit unit = round.units.get(id);
      Industry candidate = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null
          || candidate == null
          || !candidate.outputPerUnit().containsKey(commodity)
          || !seller.actor.equals(unit.operator())) {
        continue;
      }
      if (match == null || id.value().compareTo(match.id().value()) < 0) {
        match = unit;
      }
    }
    if (match != null) {
      return match;
    }
    for (ProductionUnitId id : seller.units) {
      ProductionUnit unit = round.units.get(id);
      Industry candidate = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || candidate == null || !candidate.outputPerUnit().containsKey(commodity)) {
        continue;
      }
      if (match == null || id.value().compareTo(match.id().value()) < 0) {
        match = unit;
      }
    }
    return match;
  }

  private static Map<CommodityId, Long> necessaryInputsOf(
      MarketRound round, Participant participant) {
    Map<CommodityId, Long> necessary = new LinkedHashMap<>();
    for (ProductionUnitId id : participant.units) {
      ProductionUnit unit = round.units.get(id);
      Industry industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRelation relation = round.relations.get(id);
      Recipient supplier =
          relation == null ? new Recipient.ToActor(unit.operator()) : relation.inputSupplier();
      if (!supplies(participant, supplier)) {
        continue;
      }
      long scale =
          ProductionUnitBook.plannedCapacityScaleOf(
              unit, industry, round.index, round.operatorConditions.get(id));
      if (scale <= 0L) {
        continue; // 本格没有产能 / 已缩到 0 ⇒ 不要料（同 drawCycleInputs 的口径）
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
    for (ProductionUnitId id : participant.units) {
      ProductionUnit unit = round.units.get(id);
      Industry industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRelation relation = round.relations.get(id);
      if (relation == null) {
        continue;
      }
      if (!unit.operator().equals(participant.actor)
          && !relation.operator().equals(participant.actor)) {
        continue;
      }
      long population = 0L;
      // ★ R4-B.3a-perf：家户行由入口索引给（旧实现每个 unit 现扫全量配额）。
      for (HouseholdId key : round.index.householdsOf(id)) {
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
      case Recipient.ToHousehold toHousehold ->
          participant.household != null && participant.household.equals(toHousehold.household());
      // ★ 旧档变体：按**视图**比对（构造期归一化已把一对一转到 ToHousehold；这是兼容读的窄出口）。
      case Recipient.ToCohort toCohort ->
          participant.view != null && participant.view.equals(toCohort.cohort());
    };
  }

  private static long selfNeedOf(long population, CommodityId commodity, long days) {
    if (commodity.equals(EconomySettlement.GRAIN)) {
      return EconomyVocabulary.cumulativeRationMilli(population, days);
    }
    if (commodity.equals(EconomySettlement.CLOTH)) {
      return EconomyVocabulary.cumulativeClothMilli(population, new YearFraction(days, 365L));
    }
    return 0L;
  }

  // ── 账户读取 / 冻结写入（唯一拼写点）────────────────────────────────────────────────

  private static long stockOf(MarketRound round, Participant participant, CommodityId commodity) {
    // ★★ P2-A §13.3：参与者恒为家户（账户主体只有家户）。
    return round
        .householdGoods
        .getOrDefault(participant.household, Map.of())
        .getOrDefault(commodity, 0L);
  }

  private static long frozenGoodsOf(
      MarketRound round, Participant participant, CommodityId commodity) {
    return round
        .householdFrozenGoods
        .getOrDefault(participant.household, Map.of())
        .getOrDefault(commodity, 0L);
  }

  private static void setFrozenGoods(
      MarketRound round, Participant participant, CommodityId commodity, long amount) {
    long value = Math.max(0L, amount);
    Map<CommodityId, Long> inner =
        new LinkedHashMap<>(round.householdFrozenGoods.getOrDefault(participant.household, Map.of()));
    inner.put(commodity, value);
    round.householdFrozenGoods.put(participant.household, inner);
  }

  private static long moneyOf(MarketRound round, Participant participant, CurrencyId currency) {
    return round
        .householdMoney
        .getOrDefault(participant.household, Map.of())
        .getOrDefault(currency, 0L);
  }

  private static long frozenMoneyOf(
      MarketRound round, Participant participant, CurrencyId currency) {
    return round
        .householdFrozenMoney
        .getOrDefault(participant.household, Map.of())
        .getOrDefault(currency, 0L);
  }

  private static void setFrozenMoney(
      MarketRound round, Participant participant, CurrencyId currency, long amount) {
    long value = Math.max(0L, amount);
    Map<CurrencyId, Long> inner =
        new LinkedHashMap<>(round.householdFrozenMoney.getOrDefault(participant.household, Map.of()));
    inner.put(currency, value);
    round.householdFrozenMoney.put(participant.household, inner);
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
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
      HouseholdId buyer,
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
  private static boolean carrierPresent(MatchContext ctx) {
    return ctx.carrier.isPresent() || !ctx.merchantFirms.isEmpty();
  }

  /**
   * ★★ P2-A §13.3：旧路径的"第一个有货币账的 ORGANIZATION"承运人不复存在（组织不持账）。
   * 没有商号（{@code merchantFirms} 为空）的世界因此没有可收款承运人 —— 名义运费如实记进
   * {@code MarketReport.freightUncollectedMilli()}（具名缺口，不把钱凭空塞给某个家户）。
   */
  private static Optional<ActorRef> carrierOf(MarketRound round) {
    return Optional.empty();
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
    final MarketRegion region;
    final CurrencyId currency;

    /**
     * ★★ <b>D-027：本槽所属区的规范 id</b>（= {@code region.node().nodeId()}）—— 有效参考价/区级调控按它查
     * （单区里就是 {@code "single-region"}）。★ 它只用于价格口径，不改槽位的其他语义。
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

    BuySlot(
        BuyOrder order, Participant buyer, HexCoord hex, MarketRegion region, CurrencyId currency) {
      this.order = order;
      this.buyer = buyer;
      this.hex = hex;
      this.region = region;
      this.currency = currency;
      this.regionId = region.node().nodeId();
      this.remaining = order.quantity();
    }

    /** ★ worker 的本区副本：状态照抄，后续只改副本（不触共享槽位）。 */
    BuySlot(BuySlot other) {
      this.order = other.order;
      this.buyer = other.buyer;
      this.hex = other.hex;
      this.region = other.region;
      this.currency = other.currency;
      this.regionId = other.regionId;
      this.orderIndex = other.orderIndex;
      this.remaining = other.remaining;
      this.frozenRemaining = other.frozenRemaining;
      this.baseFrozenMoney = other.baseFrozenMoney;
      this.requestedMoney = other.requestedMoney;
      this.spentMilli = other.spentMilli;
      this.noMoney = other.noMoney;
      this.blocked = other.blocked;
    }
  }

  /** 一条卖订单的撮合槽。 */
  private static final class SellSlot {
    final SellOrder order;
    final Participant seller;
    final HexCoord hex;
    final Market market;
    final MarketRegion region;

    /**
     * ★★ <b>D-027：本槽所属区的规范 id</b>（= {@code region.node().nodeId()}）—— 有效参考价按它查区级调控
     * （单区里就是 {@code "single-region"}）。★ 它只用于价格口径，不改槽位的其他语义。
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

    /** ★★ 2026-10-09：本槽剩余是否卡在"承运运力不足"（路线窗口/商号每周期运力）。 */
    boolean capacityBlocked;

    SellSlot(
        SellOrder order,
        Participant seller,
        HexCoord hex,
        Market market,
        MarketRegion region,
        MarketRound round) {
      this.order = order;
      this.seller = seller;
      this.hex = hex;
      this.market = market;
      this.region = region;
      this.regionId = region.node().nodeId();
      this.remaining = order.sellable();
      this.costEstimate = costEstimateOf(round, market, seller, order.commodity());
      this.costTieBreak = ProducerCostBook.canonicalKey(hex, seller.actor);
    }

    /** ★ worker 的本区副本：状态照抄，后续只改副本（不触共享槽位）。 */
    SellSlot(SellSlot other) {
      this.order = other.order;
      this.seller = other.seller;
      this.hex = other.hex;
      this.market = other.market;
      this.region = other.region;
      this.regionId = other.regionId;
      this.costEstimate = other.costEstimate;
      this.costTieBreak = other.costTieBreak;
      this.orderIndex = other.orderIndex;
      this.remaining = other.remaining;
      this.frozenRemaining = other.frozenRemaining;
      this.baseFrozenGoods = other.baseFrozenGoods;
    }
  }

  /**
   * 一条路线的运输参数（区内即时 {@code null}）；{@code freightRatePerMille} 是 {@link
   * MarketTopology#freightPerMilleBetween(HexCoord, HexCoord)} 给出的唯一费率来源。
   */
  private record RouteContext(
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
      boolean immediate) {
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

    /**
     * ★★ <b>D-027：本轮区级市场总调控</b>（{@link MarketRound#regulation()} 的唯一读取点；逐轮瞬态、不落盘）。
     * 只有 {@link MarketRegulation#anchor()} 所在的区受它约束；未锚定的区走默认路径。
     */
    final MarketRegulation regulation;

    /** ★★ D-027：单 hex 贸易成本的纯策略（只读拓扑现算；逐轮瞬态，不进状态）。 */
    final HexTradeCost hexTradeCost;

    /**
     * ★★ <b>D-027：逐票区级税费读数</b>（fill → 单位税费，毫计价货币/商品单位；只服务
     * {@link MarketReport#withRegulatedTariff} 的只读聚合）。★ 它不参与任何账务：本批税费<b>只记读数、不搬钱</b>。
     */
    final Map<MarketReport.Fill, Long> tariffByFill = new LinkedHashMap<>();

    /**
     * ★★ <b>D-027：本轮各（商品 × 区）剩余配额</b>（毫商品；{@code null}/{@code Long.MAX_VALUE} = 无配额）。
     * 区内撮合在 worker 的本区副本上扣它，协调器回放后把本区已用量并回；跨区撮合（协调器单线程）继续用同一张表。
     * 它是<b>逐轮瞬态</b>：每轮从 {@link MarketRegulation#quotaPerWindow()} 重建，不落盘。
     */
    final Map<CommodityId, Map<String, Long>> quotas = new LinkedHashMap<>();

    /**
     * ★★ <b>D-027：本轮"配额真的用尽"的（区, 商品）键集</b>（见 {@link #markQuotaExhausted}）——
     * {@link #collectUnfilled} 用它把买方剩余落成 {@link MarketUnfilledReason#REGULATION_QUOTA}。
     * 只记本轮的制度事实，不落盘。
     */
    final Set<String> regulationQuotaExhausted = new LinkedHashSet<>();

    /** ★★ D-027：区 id → 该区适用的调控（每轮按区只读一次；未锚定/未定义 ⇒ 空表）。 */
    final Map<String, MarketRegulation> regulationByRegionId;

    final Optional<ActorRef> carrier;
    final Map<ProductionOrganizationId, MerchantFirm> merchantFirms;
    final MerchantSettlement.CarrierPool carrierPool;
    final List<BuySlot> buys = new ArrayList<>();
    final List<SellSlot> sells = new ArrayList<>();
    final Map<ActorRef, Participant> participants = new LinkedHashMap<>();
    final Map<ActorRef, HexCoord> participantHex = new LinkedHashMap<>();
    final List<MarketReport.Fill> fills = new ArrayList<>();
    final List<MarketReport.Unfilled> unfilled = new ArrayList<>();
    // ★ S3：逐槽位只读结果（不落盘；供 MarketReadout / ApiViews 聚合）。
    final List<MarketReport.SellerOutcome> sellerOutcomes = new ArrayList<>();
    final List<MarketReport.BuyerOutcome> buyerOutcomes = new ArrayList<>();
    // ★★ D-030 市场信用：入参来自 MarketRound（关闭时为 null）；本表只记录本轮的债务来源与剩额头寸。
    final MarketRound.CreditConfig creditConfig;
    final Map<DebtContractId, DebtContract> debts;
    final List<MarketReport.CreditFill> creditFills = new ArrayList<>();
    /** 区 id → 本轮结束时货币可借池剩余（毫计价货币；collectUnfilled 的 NO_LENDABLE_MONEY 判据）。 */
    final Map<String, Long> creditMoneyRemainingByRegion = new LinkedHashMap<>();
    /** 区 id × 商品 → 本轮结束时商品可借池剩余（毫商品；NO_LENDABLE_GOODS 判据）。 */
    final Map<String, Map<CommodityId, Long>> creditGoodsRemainingByRegionCommodity =
        new LinkedHashMap<>();
    final Map<String, RouteAccumulator> routes = new LinkedHashMap<>();
    final Map<ShipmentKey, ShipmentBuilder> shipments = new LinkedHashMap<>();
    // ★ 地形代价的纯记忆化：组合根的 moveCostAt 会重建整张地形索引，同一 buyerHex 在逐卖方路线里只需算一次。
    final Map<HexCoord, Long> moveCostCache = new LinkedHashMap<>();
    // ★ R2：worker 本区副本上产生的区内成交意向（协调器回放；真实 ctx 的 recordFillIntents = false）。
    final List<FillIntent> fillIntents = new ArrayList<>();
    boolean recordFillIntents;
    // ★ P1.3：冻结轴累计值（key 见 sellFrozenAxis/buyFrozenAxis）；commitFreezes/executeTrade 增量维护。
    final Map<String, Long> sellFrozenSums = new LinkedHashMap<>();
    final Map<String, Long> buyFrozenSums = new LinkedHashMap<>();
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
        Optional<ActorRef> carrier,
        Map<ProductionOrganizationId, MerchantFirm> merchantFirms,
        MerchantSettlement.CarrierPool carrierPool) {
      this(round, markets, topology, carrier, merchantFirms, carrierPool, round.regulation());
    }

    MatchContext(
        MarketRound round,
        Map<HexCoord, Market> markets,
        MarketTopology topology,
        Optional<ActorRef> carrier,
        Map<ProductionOrganizationId, MerchantFirm> merchantFirms,
        MerchantSettlement.CarrierPool carrierPool,
        MarketRegulation regulation) {
      this.round = round;
      this.markets = markets;
      this.topology = topology;
      this.regulation = regulation == null ? MarketRegulation.none() : regulation;
      this.creditConfig = round.creditConfig();
      this.debts = round.debts();
      this.hexTradeCost = new HexTradeCost(topology);
      this.carrier = carrier;
      this.merchantFirms = merchantFirms;
      this.carrierPool = carrierPool;
      String anchorRegionId =
          this.regulation.defined() && topology.contains(this.regulation.anchor())
              ? regionIdAt(topology, this.regulation.anchor())
              : null;
      this.regulationByRegionId =
          anchorRegionId == null ? Map.of() : Map.of(anchorRegionId, this.regulation);
      configureQuotas();
    }

    /** 逐轮从调控重建配额表（空表 ⇒ 无配额；{@code Long.MAX_VALUE} = 无上限）。 */
    private void configureQuotas() {
      if (regulation.quotaPerWindow().isEmpty() || regulationByRegionId.isEmpty()) {
        return;
      }
      String anchorId = regulationByRegionId.keySet().iterator().next();
      for (Map.Entry<CommodityId, Long> entry : regulation.quotaPerWindow().entrySet()) {
        Map<String, Long> byRegion = new LinkedHashMap<>();
        byRegion.put(anchorId, Math.max(0L, entry.getValue()));
        quotas.put(entry.getKey(), byRegion);
      }
    }

    /** ★★ D-030：本入口有没有市场信用能力（{@code creditConfig} 与债务表必须同时在场；旧构造器 = 关闭）。 */
    boolean creditEnabled() {
      return creditConfig != null && debts != null;
    }

    /** ★★ D-030：某区本轮结束时的商品可借池剩余（毫商品；缺键 ⇒ 0）。 */
    long creditGoodsRemaining(String regionId, CommodityId commodity) {
      return creditGoodsRemainingByRegionCommodity
          .getOrDefault(regionId, Map.of())
          .getOrDefault(commodity, 0L);
    }

    /**
     * ★★ <b>区 id → 该区适用的调控</b>（{@link MarketRegulation#anchor()} 所在区；不在任何区 ⇒ 空表）。
     * 它是"每轮按区只读一次调控"的落点：区内撮合/跨区撮合/回放都查这一张表，不各自重算归属。
     */
    Map<String, MarketRegulation> regulationByRegionId() {
      return regulationByRegionId;
    }

    /** 本区适用调控（没有 ⇒ {@link MarketRegulation#none()}）。 */
    MarketRegulation regulationFor(String regionId) {
      MarketRegulation effective = regulationByRegionId.get(regionId);
      return effective == null ? MarketRegulation.none() : effective;
    }

    /** 本区有效参考价：有调控 ⇒ 覆盖价；没有 ⇒ {@link Market#priceOf}（唯一拼写点）。 */
    long referencePriceOf(String regionId, Market market, CommodityId commodity) {
      MarketRegulation effective = regulationFor(regionId);
      return effective.defined()
          ? effective.referencePriceOf(market, commodity)
          : market.priceOf(commodity);
    }

    /**
     * ★★ 本区该商品有没有有效定价（区级覆盖优先；<b>值为 0 也算定价</b> = 明确免费交易）。
     * 它是"未定价 ⇒ 不交易"与"0 价 ⇒ 免费交易"的唯一分辨点。
     */
    boolean hasPrice(String regionId, Market market, CommodityId commodity) {
      return regulationFor(regionId).hasPrice(market, commodity);
    }

    /** 本区该商品的单位税费（毫计价货币/商品单位）；没有调控/缺项/0 ⇒ 0（只记读数，不搬钱）。 */
    long tariffPerUnitOf(String regionId, CommodityId commodity) {
      MarketRegulation effective = regulationFor(regionId);
      if (!effective.defined()) {
        return 0L;
      }
      return effective.tariffPerUnit().getOrDefault(commodity, 0L);
    }

    /** 本（区, 商品）剩余配额（毫商品）；{@code Long.MAX_VALUE} = 无配额。 */
    long quotaRemaining(MarketRegion region, CommodityId commodity) {
      return quotaRemaining(region.node().nodeId(), commodity);
    }

    /** 本（区, 商品）剩余配额（毫商品）；{@code Long.MAX_VALUE} = 无配额。 */
    long quotaRemaining(String regionId, CommodityId commodity) {
      Map<String, Long> byRegion = quotas.get(commodity);
      if (byRegion == null) {
        return Long.MAX_VALUE;
      }
      Long remaining = byRegion.get(regionId);
      return remaining == null ? Long.MAX_VALUE : remaining;
    }

    /** 从剩余配额里扣掉本笔已用量（毫商品；无配额 ⇒ 不记）。 */
    void consumeQuota(MarketRegion region, CommodityId commodity, long quantity) {
      if (quantity <= 0L) {
        return;
      }
      Map<String, Long> byRegion = quotas.get(commodity);
      if (byRegion == null) {
        return;
      }
      String regionId = region.node().nodeId();
      Long remaining = byRegion.get(regionId);
      if (remaining == null) {
        return;
      }
      byRegion.put(regionId, Math.max(0L, remaining - quantity));
    }

    /**
     * ★★ <b>D-027：标记本（区, 商品）配额已用尽</b>——{@link #collectUnfilled} 读它把"因配额没成交"的买方剩余落成
     * {@link MarketUnfilledReason#REGULATION_QUOTA}。★ 只记"真的用尽"（配额表里有这一项且剩余 ≤ 0），
     * 没有配额的区/商品不受影响。
     */
    void markQuotaExhausted(MarketRegion region, CommodityId commodity) {
      if (!quotaConfigured(region, commodity)) {
        return;
      }
      regulationQuotaExhausted.add(quotaKey(region, commodity));
    }

    /** 本（区, 商品）有没有配置配额（与 {@link #markQuotaExhausted} 同源，只查表不扣减）。 */
    boolean quotaConfigured(MarketRegion region, CommodityId commodity) {
      Map<String, Long> byRegion = quotas.get(commodity);
      return byRegion != null && byRegion.containsKey(region.node().nodeId());
    }

    /** 本（区, 商品）是否已配额用尽（collectUnfilled 的只读判据）。 */
    boolean quotaExhausted(MarketRegion region, CommodityId commodity) {
      return regulationQuotaExhausted.contains(quotaKey(region, commodity));
    }

    private static String quotaKey(MarketRegion region, CommodityId commodity) {
      return region.node().nodeId() + "#" + commodity.value();
    }

    /** 把 worker 本区副本的配额用量并回本 ctx（同区只被一个 worker 触碰；无配额 ⇒ 空表）。 */
    void absorbQuotas(MatchContext worker) {
      for (Map.Entry<CommodityId, Map<String, Long>> entry : worker.quotas.entrySet()) {
        Map<String, Long> target =
            quotas.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
        for (Map.Entry<String, Long> usage : entry.getValue().entrySet()) {
          target.put(usage.getKey(), usage.getValue());
        }
      }
    }
  }
}
