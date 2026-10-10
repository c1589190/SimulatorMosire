package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.market.TradeRoute;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * 目的地库存增；到货前目的地既不能消费、也不能再挂牌卖出（货不在任何 {@code HouseholdInventory} 余额里）。损耗逐票由买方承担 （{@link
 * LossBearer#BUYER}），到货时从在途量里扣并记进 {@code ProductionLedger} 的损耗账户。
 *
 * <p>★★ <b>运费必须有收款方</b>：承运主体 = {@code ActorKind.ORGANIZATION} 且会话里有货币账。世界里没有承运 actor
 * 时<b>不收运费</b>（{@link MarketReport#freightUncollectedByCurrency()} 记下应收而未收的读数），禁钱凭空消失。
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

  /**
   * ★★ <b>P-T1d：槽位"没有置顶名次"的哨兵</b>（本轮没有政府要求管控市场 ⇒ 置顶 pass 根本没跑 ⇒ 撮合按既有顺序：卖侧成本序、买侧列表序）。
   *
   * <p>★ 为什么要一个哨兵而不是 {@code 0}：{@code 0} 是**合法名次**（簿首）。名次只在 {@code
   * ctx.round.procurementPriority().isActive()} 为真时才被读，两件事（"跑没跑"与"名次是几"）分开写、分开读。
   */
  static final long PROCUREMENT_RANK_ABSENT = -1L;

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
   * ★★ <b>M2.6 自适应价格的开关（2026-10-07 用户裁定：打开）</b>：打开后每轮结算<b>结束</b>时按 {@code z = clamp((有预算且合限价的需求 −
   * 可出售供给) / max(需求 + 供给, ε), −1, 1)}、 {@code p_next = max(p_min, round(p × (1 + α·z)))}
   * 更新**各区集散节点价**，并把同一区成员格的同商品价一并改到该值 （"每区每商品一个报价"）。
   *
   * <p>★★ <b>唯一写回路径</b>：{@link #clearOncePerCycle} 返回新的市场表，{@code EconomySettlement} 把这份表放进它交出的
   * {@code EconomyData} —— 于是 {@code markets} 作为既有的 {@code FieldDelta} 组件进变更集。本类<b>不</b>直接改任何
   * {@code EconomyData}，也没有第二处改价。
   *
   * <p>★ 固定报价模式仍是可达的：把本常量改回 {@code false}（并提供旧初态/旧档）即可逐值回到 M2.6 之前。 读侧 {@link
   * MarketReport#priceMode()} / {@link MarketReadout#adaptivePricingEnabled()} 会如实标注本轮模式。
   */
  static final boolean MARKET_ADAPTIVE_PRICING_ENABLED = true;

  /**
   * ★★ <b>自适应的步长 α（千分比/轮）</b>：{@code 50‰ = 5%} —— 计划要求的**起步上界 ≤5%/轮**。
   *
   * <p>★ 它是 GM 可调出厂值，与 {@link #MARKET_ADAPTIVE_PRICING_ENABLED} 分开：开关回答"调不调"，本值回答"每轮最多调多少"。
   */
  static final long MARKET_ADAPTIVE_ALPHA_PER_MILLE = 50L;

  /**
   * ★★ <b>自适应价格的下限 p_min</b>（毫计价货币/商品单位）：{@code 0}（2026-10-09 用户口径：取消 1 毫下限）。 价格可以一路降到 0 —— 0
   * 是<b>明确免费交易</b>（买方只出运费，货款腿为 0），不是"没有定价"；"从未定价"的商品根本不会进自适应 （{@link #hasEffectivePrice} 先判）。
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
   * <p>★ 它是<b>GM 可调默认值</b>（不是物理常数）：与创世禀赋量级一致 —— 一个出借人必须先把"自己未覆盖的自然需求按市场价折算" 留出来，再额外留 {@code 12
   * 毫银/人} 的口粮/缓冲钱，剩下的才算可借货币。防的是"把出借人自己的口粮钱借空"，不是禁止 货币出借。参数目录落地后迁入 GM 参数表。
   */
  static final long LENDER_MONEY_BUFFER_PER_CAPITA_MILLI = 12L;

  /**
   * ★★ <b>D-030：货币出借头寸每轮按余额自动派生（GM 默认开）</b>—— 本批没有显式挂单，出借人保留额之外的余额就是可借 货币；关掉它 ⇒
   * 本轮没有货币信用（现金与借实物仍可跑）。★ 它是 GM 默认开关，不是交易规则的一部分。
   */
  static final boolean MONEY_LENDING_AUTO_LIST = true;

  /**
   * ★★ <b>D-030：卖单剩余即可借（GM 默认开）</b>—— 现金成交后卖单剩余直接成为商品可借池，不另建仓库/不复制库存；关掉它 ⇒ 本轮没有实物信用（货币信用仍可跑）。★ 它是
   * GM 默认开关，与"卖单剩余"这一事实来源无关。
   */
  static final boolean MARKET_GOODS_LENDING_ENABLED = true;

  /**
   * ★★ <b>运输损耗的记账账户</b>：{@code ProductionLedger.losses} 的键是 {@code IndustryId}，而运输不是产业 ——
   * 用一个**具名伪账户**把在途损耗与生产损耗分开（两者都进 ΣLoss，但读账分得清是谁的）。
   */
  static final IndustryId TRANSPORT_LOSS_ACCOUNT = new IndustryId("market-transport");

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
    private final Map<HouseholdId, HouseholdEconomy> householdEconomies;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
    private final Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
    private final Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods;
    private final Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney;
    private final Map<HouseholdId, Map<CommodityId, Long>> unmetToday;
    private final Map<ActorRef, HouseholdId> householdOfActor;
    private final Map<IndustryId, Industry> industries;

    /** ★★ R3B.2：生产单元表（键 = unit id；参与者/必要投入/卖单归属都读它）。 */
    private final Map<ProductionUnitId, ProductionProcess> units;

    /** ★★ R3B.2：实物总账（只读；必要投入的规模从它纯派生 —— 它是唯一的 quantity 真相）。 */
    private final Map<AssetShareId, OwnershipStake> assetShares;

    private final Map<ProductionUnitId, ProductionRules> relations;
    private final Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments;
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
    private final Map<DemandId, HouseholdDemand> householdDemands;

    /**
     * ★★ <b>本轮区内市场规则</b>（逐轮瞬态，不落盘）—— P-T1c 起只剩"区内税"：{@link MarketRegulation#anchor()} 所在的区按它
     * 施加税费；其余区不受影响（单区世界里就是全区）。★ 不给 ⇒ 语义为 {@link MarketRegulation#none()}（无税费，逐值现状）。
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
     * ★★ <b>本轮的“退出商品市场”家户集合</b>（保序不可变；判定只用 {@code contains}）——R1 起**只含单位户**（由 {@code simos-app}
     * 组合根从 {@code Unit.households()} 算好传入，economy 看不见 unit 切片）。★ 政府国库户<b>不再</b>由 {@code
     * EconomySettlement} 并入：它回到市场，但只按授权下单（见 {@link #govMandates()}）。
     *
     * <p>★★ <b>口径（用户 2026-10-23 税制/财政闭环设计书 §3.1）</b>：这些家户<b>买卖都不生成</b> —— 0 人口国库户不得被当卖家 清仓（run6
     * day50 中央粮 271,393→0 的根因），官吏户/军户的实物供给也不得被市场当余量卖掉。★ 本集合只排除<b>市场订单/参与</b>：
     * 税、预算、转移照常动账，账户与冻结语义一个字不改。
     */
    private final Set<HouseholdId> marketExcludedHouseholds;

    /**
     * ★★ <b>2026-10-08（阶段 1）：本轮的套利决定</b>（§4.3.4/§4.3.5；由 {@code LaborQueueSettlement} 排序产出、 经
     * {@link #withArbitrage} 注入）。默认 {@link MarketArbitragePlan#empty()} ⇒ <b>逐值退回改前行为</b>
     * （旧构造器、只读计划轮与读口都不注入它）。
     *
     * <p>★ 它是<b>逐轮瞬态</b>：不进 {@code EconomyData}、不进变更集、不落盘；订单仍由 {@code ordersFor} 生成、 仍由 {@code
     * clearOncePerCycle} 撮合、仍由唯一写口落账（本字段只回答"这个家户这一轮想额外吃多少货"）。
     */
    private MarketArbitragePlan arbitrage = MarketArbitragePlan.empty();

    /**
     * ★★ <b>R1（2026-10-09）：本日的政府市场授权计划</b>（由 {@code EconomySettlement}/{@code MarketReadout} 从
     * {@code EconomyData.govMarketMandates()} + {@code governments()} 现算后经 {@link #withGovMandates}
     * 注入）。
     *
     * <p>★★ <b>它做了两件事</b>：① 点名单上的国库户（{@code hh-gov-*}）是"<b>只按授权下单</b>"—— 自动买卖单、市场信用放贷、
     * 家户外汇单<b>全部不生成</b>（国库户持有税收实收的粮/银且 0 人口 ⇒ 自动卖单会把它清仓，这正是 Z7b 的根因）； ②
     * 给出它们今天真正生效的挂单（谁/商品/方向/量/限价）。
     *
     * <p>★ 默认 {@link GovernmentMarketMandatePlan#empty()} ⇒ <b>逐值退回改前行为</b>（没有政府的世界、旧构造器与不走 {@code
     * withGovMandates} 的调用点都不受影响）。
     */
    private GovernmentMarketMandatePlan govMandates = GovernmentMarketMandatePlan.empty();

    /**
     * ★★ <b>R2（2026-10-09 口岸设计书 §4.4）：本轮逐区逐币种的实际管制力</b>（组合根折算后经 {@link #withPortEnforcement} 注入；由
     * {@code CurrencyValuation} 作为"家户对外币估值的减项"消费）。
     *
     * <p>★ 默认 {@link PortEnforcementInput#none()} ⇒ <b>逐值退回改前行为</b>（没有口岸政策的世界一个数都不动，I-P8）。 ★
     * 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code govMandates}
     * 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
     */
    private PortEnforcementInput portEnforcement = PortEnforcementInput.none();

    /**
     * ★★ <b>P-T1b（2026-10-10 口岸设计书 §13）：本轮逐区的<b>税率</b>与收税政府</b>（组合根折算后经 {@link #withPortTax} 注入）——
     * 出口税（源区）/ 进口税（目的区）/ 区内市场税（本区）三层真收款的<b>税率来源</b>。
     *
     * <p>★★ <b>与 {@link #portEnforcement} 分开的原因</b>：闸（E，能不能过）与税（收多少）是两个量，缺省也各管各的 ——
     * 只设税率而两侧全开的世界<b>能过但要多付钱</b>；只设限制而不设税的世界<b>过不去但不加价</b>。合成一个字段会让 "只设了税"（管制力全 0 ⇒
     * 那个字段看上去是缺省）静默丢掉税。
     *
     * <p>★ 默认 {@link PortTaxInput#none()} ⇒ <b>逐值退回改前行为</b>（未设税 ⇒ 一个数都不动，I-C2）。 ★ 逐轮瞬态：不进 {@code
     * EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code govMandates}/{@code
     * portEnforcement} 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
     */
    private PortTaxInput portTax = PortTaxInput.none();

    /**
     * ★★ <b>P-T1d（2026-10-10 口岸设计书 §16.3/§17）：本轮的政府采购优先级</b>（谁要求管控市场 + 各自的行政力池；组合根折算后经 {@link
     * #withProcurementPriority} 注入）。
     *
     * <p>★ 它只在<b>市场轮的撮合顺序</b>上兑现：要求管控市场的政府（表里有它的国库户 {@code hh-gov-<govUnitId>}），
     * 其挂单在行政力池余量内被移到挂单簿<b>最前</b>（最先卖 / 最先买），每超越一户扣一份、见底硬停；<b>只改顺序、不改价格与量规则</b> （唯一拼写点 = {@code
     * ProcurementPriorityOrder}）。
     *
     * <p>★ 默认 {@link ProcurementPriorityInput#none()} ⇒ <b>逐值退回改前行为</b>（没有政府要求管控 ⇒ 一个数都不动，I-C2）。 ★
     * 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code
     * govMandates}/{@code portEnforcement}/{@code portTax} 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
     */
    private ProcurementPriorityInput procurementPriority = ProcurementPriorityInput.none();

    /**
     * ★★ <b>§16.4 ①（2026-10-10 用户裁定 6）：本轮的<b>跑商家户</b>集合</b> —— 挂单保留"工具至少一趟"的<b>范围</b>。
     *
     * <p>★ <b>判据</b> = {@code MerchantIdentity.selectsMerchant}（<b>有效位置</b> = 主业 ∪ 副业里含 {@code
     * merchant.*} 的位置；唯一的 mode 比较点在那一个类里，本类不另判）；由 {@code EconomySettlement}/{@code MarketReadout}
     * 从同一份 {@code classMemberships × classPositions} 现算后经 {@link #withMerchantHouseholds} 注入。
     *
     * <p>★★ <b>为什么范围是"所有跑商家户"而不是运力池成员</b>：池成员多一道"运力 &gt; 0"的过滤；而 {@link #necessaryInputsOf}
     * 的这项保留必须与"有没有 {@code trade} unit / 有没有运力"无关（同格无 trade unit 的跑商家户 也要留住一趟的工具，否则它挂出的 tool 卖单被全额冻结
     * ⇒ 跑商当刻可用量 = 0）。
     *
     * <p>★ <b>缺省 {@code Set.of()} ⇒ 逐值退回改前</b>（缺省中性，I-C2）：夹具 / 旧构造器 / 不含 {@code merchant.*}
     * 位置的世界一个数都不动。★ 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘。
     */
    private Set<HouseholdId> merchantHouseholds = Set.of();

    /**
     * ★★ <b>2026-10-08（阶段 2-A2a）：本轮的外汇入参</b>（官方汇率 + 窗口储备上限；由 {@code EconomySettlement} 从 {@code
     * EconomyData.governments()} 装配后经 {@link #withFx} 注入；★ B4 起再加上 {@code
     * EconomyData.marketZones()} 的区级覆盖 —— 口径 = 区级优先、按币对回落该区发行 GOV 的 GOV 级报价）。 默认 {@link
     * FxRoundInput#none()} ⇒ <b>本轮没有政府窗口</b>（★ P-T5 起<b>不再</b>等于"没有外汇面"：家户的民间簿不依赖窗口， 逐格逐户按 F-1
     * 购买力自报价；见 {@code FxSettlement#planHouseholdOrders}）。★ 单币世界 / 无价可比 ⇒ 不挂单 ⇒ 逐值退回 A2a 之前（I-C2）。
     *
     * <p>★ 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘（I17：汇率不进状态）。
     */
    private FxRoundInput fx = FxRoundInput.none();

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
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands) {
      this(
          day,
          householdEconomies,
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
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          MarketRegulation.none(),
          null,
          null,
          Set.of());
    }

    /**
     * ★★ <b>带区内市场规则的完整构造器</b>（生产路径用；旧构造器委托 {@link MarketRegulation#none()}）。
     *
     * <p>★ 旧构造器<b>保留且行为不变</b>：{@code regulation = none()} ⇒ 无税费；{@code creditConfig}/{@code debts}
     * 为 {@code null} ⇒ 信用关闭、逐值退回现金市场。
     */
    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation) {
      this(
          day,
          householdEconomies,
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
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          regulation,
          null,
          null,
          Set.of());
    }

    /** ★★ <b>D-030：带市场信用的完整构造器（旧签名兼容）</b>：不含 Z7b 排除集 ⇒ 与改动前逐值同行为；生产、 只读计划轮与读口都走带排除集的下一支。 */
    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation,
        CreditConfig creditConfig,
        Map<DebtContractId, DebtContract> debts) {
      this(
          day,
          householdEconomies,
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
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          regulation,
          creditConfig,
          debts,
          Set.of());
    }

    /**
     * ★★ <b>Z7b：带市场排除集的完整构造器</b>（生产/只读计划轮/读口的唯一入口；旧构造器委托 {@code Set.of()}）。
     *
     * <p>★ {@code debts} 是<b>引用</b>：市场信用经 {@link DebtContractBook#upsert} 就地累加，不复制成第二份债务表。
     *
     * @param marketExcludedHouseholds 本轮不生成任何买单/卖单的家户（非 null、不得含 null）；由 {@code EconomySettlement}
     *     把 {@code governments} 国库户并入调用方传入的单位户集合
     */
    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, Industry> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation,
        CreditConfig creditConfig,
        Map<DebtContractId, DebtContract> debts,
        Set<HouseholdId> marketExcludedHouseholds) {
      this.day = day;
      this.householdEconomies = Objects.requireNonNull(householdEconomies, "rows");
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
      this.laborCommitments = Objects.requireNonNull(laborCommitments, "allocations");
      this.shipments = Objects.requireNonNull(shipments, "shipments");
      this.ledger = Objects.requireNonNull(ledger, "ledger");
      this.operatorConditions =
          operatorConditions == null ? Map.of() : Map.copyOf(operatorConditions);
      this.index = Objects.requireNonNull(index, "index");
      this.householdDemands = householdDemands == null ? Map.of() : householdDemands;
      this.regulation = regulation == null ? MarketRegulation.none() : regulation;
      this.creditConfig = creditConfig;
      this.debts = debts;
      this.marketExcludedHouseholds = freezeExcludedHouseholds(marketExcludedHouseholds);
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

    /** ★★ Z7b：本轮退出商品市场的家户集合（只读；只用于订单/参与生成处的 {@code contains}）。 */
    Set<HouseholdId> marketExcludedHouseholds() {
      return marketExcludedHouseholds;
    }

    /**
     * ★★ <b>P-T5：某户的持币表</b>（币种 → 余额，毫；只读）。
     *
     * <p>★ <b>为什么必须有这个读口</b>：民间簿要回答"这户手里有哪几种币"（F-1 的比较集），而"我有多钱"的唯一口径是 {@link
     * MarketSettlement#spendableMoneyOf}（余额 − 冻结）—— 持币表在 {@code MarketRound} 里是私有字段，FX 段（另一个类） 拿不到
     * ⇒ 给它一个<b>窄入口</b>，而不是在那边另拼一份"我有多钱"。缺行 ⇒ 空表（不是 null）。
     */
    Map<CurrencyId, Long> moneyOf(HouseholdId household) {
      return household == null ? Map.of() : householdMoney.getOrDefault(household, Map.of());
    }

    /**
     * ★★ <b>2026-10-08（阶段 1）：注入本轮的套利决定</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 沿用 {@code withCredit} 的形制：先用既有唯一完整构造器造一份新实例，再把套利计划挂上 ——
     * <b>不改任何构造器签名</b>（既有调用方/测试因此逐字不动，旧路径自然拿到 {@link
     * MarketArbitragePlan#empty()}）。挂载发生在对象发布之前（同一天结算内的协调器线程），因此不存在 数据竞争。
     */
    MarketRound withArbitrage(MarketArbitragePlan plan) {
      Objects.requireNonNull(plan, "withArbitrage 的套利计划不得为 null（没有就给 MarketArbitragePlan.empty()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = plan;
      next.fx = fx; // ★ A2a：同一处"替换式构造"必须逐字段带过（克隆丢字段是本类踩过的坑）
      next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段
      next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
      next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /**
     * ★★ <b>A2a：注入本轮的外汇入参</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withArbitrage} 逐字相同：不新增构造器签名，旧调用方自然拿到 {@link FxRoundInput#none()}（= 没有政府窗口；
     * ★ P-T5 起它<b>不</b>等于"没有外汇面"）。
     */
    MarketRound withFx(FxRoundInput input) {
      Objects.requireNonNull(input, "withFx 的外汇入参不得为 null（没有就给 FxRoundInput.none()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = input;
      next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段
      next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
      next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /** ★★ A2a：本轮的外汇入参（缺省 {@link FxRoundInput#none()} ⇒ 没有政府窗口；P-T5 起家户民间簿不依赖它）。 */
    FxRoundInput fx() {
      return fx;
    }

    /** ★★ 2026-10-08（阶段 1）：本轮的套利决定（缺省空 ⇒ 订单生成逐值退回改前口径）。 */
    MarketArbitragePlan arbitrage() {
      return arbitrage;
    }

    /** ★★ R1：本日的政府市场授权计划（缺省空 ⇒ 没有"只按授权下单"的家户，逐值退回改前行为）。 */
    GovernmentMarketMandatePlan govMandates() {
      return govMandates;
    }

    /** ★★ R2：本轮逐区逐币种/逐商品的实际管制力（缺省 {@link PortEnforcementInput#none()} ⇒ 没有口岸面）。 */
    PortEnforcementInput portEnforcement() {
      return portEnforcement;
    }

    /** ★★ P-T1b：本轮逐区的税率与收税政府（缺省 {@link PortTaxInput#none()} ⇒ 三层税都不收）。 */
    PortTaxInput portTax() {
      return portTax;
    }

    /**
     * ★★ <b>P-T1d：注入本轮的政府采购优先级（谁要求管控市场 + 各自的行政力池）</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withPortTax} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link
     * ProcurementPriorityInput#none()}（= 没有政府要求管控，逐值退回改前）。★ 它必须与其余五个 {@code withX} 互相带过（见各方法里的
     * {@code next.xxx = xxx} 几行）——丢字段是本类踩过三次的坑。
     */
    MarketRound withProcurementPriority(ProcurementPriorityInput input) {
      Objects.requireNonNull(
          input, "withProcurementPriority 的入参不得为 null（没有政府管控就给 ProcurementPriorityInput.none()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = govMandates;
      next.portEnforcement = portEnforcement;
      next.portTax = portTax;
      next.procurementPriority = input;
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /**
     * ★★ <b>P-T1d：本轮的政府采购优先级</b>（缺省 {@link ProcurementPriorityInput#none()} ⇒ 没有政府要求管控市场）。
     *
     * <p>★ 读者只有两处：撮合前的置顶排序（{@link #applyProcurementPriority}）与撮合里"按哪一套顺序"的判据 ——
     * 它<b>不</b>参与订单生成、不参与结算、不改任何价格/量算式。
     */
    ProcurementPriorityInput procurementPriority() {
      return procurementPriority;
    }

    /** ★★ §16.4 ①：本轮的跑商家户集合（缺省空集 ⇒ 没有任何"至少一趟工具"的追加保留，逐值退回改前）。 */
    Set<HouseholdId> merchantHouseholds() {
      return merchantHouseholds;
    }

    /**
     * ★★ <b>§16.4 ①（2026-10-10）：注入本轮的<b>跑商家户</b>集合</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withProcurementPriority} 逐字相同：<b>不新增构造器签名</b>（既有调用方/测试因此逐字不动， 旧路径自然拿到空集 ⇒
     * 缺省中性）。★ 它必须与其余六个 {@code withX} 互相带过（见各方法里的 {@code next.xxx = xxx} 几行 + {@link
     * #copyForWorker}）—— 丢字段是本类踩过三次的坑：丢了它 = "工具至少一趟"整段静默不生效。
     */
    MarketRound withMerchantHouseholds(Set<HouseholdId> households) {
      Objects.requireNonNull(households, "withMerchantHouseholds 的集合不得为 null（没有跑商家户就给 Set.of()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = govMandates;
      next.portEnforcement = portEnforcement;
      next.portTax = portTax;
      next.procurementPriority = procurementPriority; // ★ P-T1d：第六个字段（本方法逐字段带过）
      next.merchantHouseholds = freezeMerchantHouseholds(households);
      return next;
    }

    /**
     * ★ 跑商家户集合是**身份集合**：逐元素查 null、保序冻结（绝不用 {@code Set.copyOf} —— 它不承诺保序，I7）。
     *
     * <p>★ 与 {@code freezeExcludedHouseholds} 分开而不是共用一个helper：两者语义不同（一个"排除下单"、一个"追加保留"），
     * 合成一个会让将来只改一侧时静默改到另一侧。
     */
    private static Set<HouseholdId> freezeMerchantHouseholds(Set<HouseholdId> households) {
      LinkedHashSet<HouseholdId> copy = new LinkedHashSet<>();
      for (HouseholdId household : households) {
        if (household == null) {
          throw new IllegalArgumentException("merchantHouseholds 不得含 null");
        }
        copy.add(household);
      }
      return Collections.unmodifiableSet(copy);
    }

    /**
     * ★★ <b>P-T1b：注入本轮的三层税税率（区级 + 收税政府）</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withPortEnforcement} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link PortTaxInput#none()}（=
     * 不收税，逐值退回改前）。★ 它必须与其余四个 {@code withX} 互相带过（见各方法里的 {@code next.xxx = xxx} 几行）——丢字段是本类踩过三次的坑。
     */
    MarketRound withPortTax(PortTaxInput input) {
      Objects.requireNonNull(input, "withPortTax 的入参不得为 null（没有税就给 PortTaxInput.none()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = govMandates;
      next.portEnforcement = portEnforcement;
      next.portTax = input;
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /**
     * ★★ <b>R2：注入本轮的口岸实际管制力</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withFx} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link PortEnforcementInput#none()}。 ★
     * 它必须与其余三个 {@code withX} 互相带过（见各方法里的三行 `next.xxx = xxx`）。
     */
    MarketRound withPortEnforcement(PortEnforcementInput input) {
      Objects.requireNonNull(
          input, "withPortEnforcement 的入参不得为 null（没有口岸面就给 PortEnforcementInput.none()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = govMandates;
      next.portEnforcement = input;
      next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /**
     * ★★ <b>R1：注入本日的政府市场授权计划</b>（返回一个新的 {@link MarketRound}；原对象不动）。
     *
     * <p>★ 形制与 {@link #withArbitrage} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link
     * GovernmentMarketMandatePlan#empty()}。★ 它必须**最后**注入（或由各个 {@code withX} 逐字段带过），否则会被后续的 {@code
     * withCredit}/{@code withArbitrage}/{@code withFx} 静默丢掉 —— 那正是本类踩过的"克隆丢字段"坑。
     */
    MarketRound withGovMandates(GovernmentMarketMandatePlan plan) {
      Objects.requireNonNull(
          plan, "withGovMandates 的计划不得为 null（没有就给 GovernmentMarketMandatePlan.empty()）");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = plan;
      next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
      next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /**
     * ★★ <b>克隆的唯一拼写点</b>（2026-10-08 修复"克隆静默丢字段"这一类 bug）。
     *
     * <p>★★ <b>为什么必须有它</b>：并行 worker 用的两条克隆路径（{@code MarketSettlement.readOnlyPlanningRound} 与区副本的
     * local round）此前各自<b>手抄一遍 22 个构造参数</b>；{@code MarketRound} 一加字段 （本轮 = {@code
     * arbitrage}），克隆就会把它悄悄丢掉，而克隆出的那个轮<b>才是在下订单的那一份</b> ⇒ 整套套利一次都不生效、且没有任何报错（实测：360 tick 与基线逐值相同，计划
     * 83 户但 {@code ARBITRAGE_BUY_ORDER} = 0 条）。
     *
     * <p>★★ <b>语义</b>：<b>逐字段原样带过本轮的其余全部输入</b>（含 {@code regulation} / {@code creditConfig} / {@code
     * debts} / {@code marketExcludedHouseholds} / {@code index} / {@code arbitrage}），
     * 只替换调用方<b>显式点名</b>的六样：四张账户表、未满足表、账本累加器 —— 这六样正是两条克隆路径真正不同的部分。 因此"将来再加一个 {@code MarketRound}
     * 字段"不再需要改克隆点。
     *
     * <p>★ <b>与改前的逐值差异（已审计）</b>：旧克隆把 {@code regulation} 写死成 {@link MarketRegulation#none()}、 区副本还把
     * {@code creditConfig}/{@code debts}/{@code marketExcludedHouseholds} 留成缺省。三者在本轮**都没有
     * 读取点**：克隆轮只进 {@code planFor}/{@code ordersFor}（P-T1c 起订单生成只读逐格价表，不再读调控）与 {@code
     * matchGroup}（区副本，信用撮合 {@code creditRound}/{@code collectUnfilled}/{@code creditUnfilledReason}
     * 全部只在协调器的真实 {@code ctx} 上跑）⇒ 本方法把它们原样带过是**行为等价**的，只是不再有"字段悄悄变缺省"。
     */
    MarketRound copyForWorker(
        Map<HouseholdId, Map<CommodityId, Long>> householdGoodsCopy,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoneyCopy,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoodsCopy,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoneyCopy,
        Map<HouseholdId, Map<CommodityId, Long>> unmetTodayCopy,
        ProductionLedger.Accumulator workerLedger) {
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
              householdGoodsCopy,
              householdMoneyCopy,
              householdFrozenGoodsCopy,
              householdFrozenMoneyCopy,
              unmetTodayCopy,
              householdOfActor,
              industries,
              units,
              assetShares,
              relations,
              laborCommitments,
              shipments,
              workerLedger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              creditConfig,
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage; // ★ 新字段在这里被带过 —— 这正是"手抄 22 个参数"漏掉的那一行
      next.fx = fx; // ★ A2a：同一个坑的第二个字段（克隆轮丢 fx = 外汇面整段不生效且毫无报错）
      next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段（丢了它 = 国库户自动订单守卫整段失效）
      next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段（丢了它 = 口岸管制整段不生效）
      next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
      next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /** ★ 排除集是身份集合：逐元素查 null、保序冻结（绝不用 {@code Set.copyOf}——不承诺保序）。 */
    private static Set<HouseholdId> freezeExcludedHouseholds(Set<HouseholdId> excluded) {
      Objects.requireNonNull(excluded, "marketExcludedHouseholds 不得为 null（无排除给空集）");
      LinkedHashSet<HouseholdId> copy = new LinkedHashSet<>();
      for (HouseholdId household : excluded) {
        if (household == null) {
          throw new IllegalArgumentException("marketExcludedHouseholds 不得含 null");
        }
        copy.add(household);
      }
      return Collections.unmodifiableSet(copy);
    }

    /**
     * ★★ <b>D-030/D-031：给已构造的市场轮补上信用入参</b>（{@code EconomySettlement} 在判定今天真的开市之后才构造 {@link
     * CreditConfig}；本方法<b>不复制账户表</b>，只换信用字段）。借款人侧不再传入任何容量表。
     */
    MarketRound withCredit(long dueCycle, Map<DebtContractId, DebtContract> debts) {
      Objects.requireNonNull(debts, "debts");
      MarketRound next =
          new MarketRound(
              day,
              householdEconomies,
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
              laborCommitments,
              shipments,
              ledger,
              operatorConditions,
              index,
              householdDemands,
              regulation,
              new CreditConfig(dueCycle),
              debts,
              marketExcludedHouseholds);
      next.arbitrage = arbitrage;
      next.fx = fx;
      next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段（丢了它 = 授权整段静默失效）
      next.merchantHouseholds = merchantHouseholds; // ★ §16.4 ①：同一个坑的第七个字段（丢了它 = "工具至少一趟"静默不生效）
      return next;
    }

    /** ★ R4-E2：需求账本（只读；空表 = 没有 GM 需求，订单退回旧基线）。 */
    Map<DemandId, HouseholdDemand> householdDemands() {
      return householdDemands;
    }

    /** ★★ D-027：本轮区级市场总调控（逐轮瞬态；不落盘）。默认实例 = 逐值现状。 */
    MarketRegulation regulation() {
      return regulation;
    }

    /** 本轮世界日（与字段同源，不另设第二个日号）。 */
    long day() {
      return day;
    }

    /*
     * ★★ A2a：外汇面（{@code FxSettlement}）与本类同包但不同顶层类 ⇒ 它读不到 MarketRound 的私有字段。
     * 这里给出**只读访问器**（不复制表、不暴露 setter）：FX 撮合与商品撮合同住一轮，必须用同一份账户表与同一个
     * 账本累加器 —— 第二份副本就会变成"两本账"。
     */

    Map<HouseholdId, Map<CommodityId, Long>> householdGoods() {
      return householdGoods;
    }

    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney() {
      return householdMoney;
    }

    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods() {
      return householdFrozenGoods;
    }

    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney() {
      return householdFrozenMoney;
    }

    Map<ActorRef, HouseholdId> householdOfActor() {
      return householdOfActor;
    }

    Map<HouseholdId, HouseholdEconomy> householdEconomies() {
      return householdEconomies;
    }

    ProductionLedger.Accumulator ledger() {
      return ledger;
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
  record MarketOutcome(
      MarketReport report, Map<HexCoord, Market> markets, Map<MarketMandateId, Long> mandateFills) {

    MarketOutcome {
      Objects.requireNonNull(report, "report");
      Objects.requireNonNull(markets, "markets");
      Objects.requireNonNull(mandateFills, "mandateFills（没有授权成交给空表）");
      // ★ 保序不可变（不用 Map.copyOf：迭代序不是内容的纯函数）；值是不可变 record / Long。
      markets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(markets));
      mandateFills = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mandateFills));
    }

    /** ★ R1 的旧形状（没有政府授权成交）：{@code mandateFills} 取空表。 */
    MarketOutcome(MarketReport report, Map<HexCoord, Market> markets) {
      this(report, markets, Map.of());
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
    Map<String, List<HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies);
    for (HexCoord hex : markets.keySet()) {
      for (HouseholdId key :
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of())) {
        HouseholdEconomy householdEconomy = round.householdEconomies.get(key);
        if (householdEconomy == null) {
          continue;
        }
        long need = householdEconomy.naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
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
      Market market,
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
      Market market,
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
    //   ★★ 2026-10-10 P-T1c：定价只看**逐格**价表（{@link Market#hasPrice}）—— 区级参考价覆盖已删（设计书 §16）。
    if (!market.hasPrice(commodity)) {
      return new PlannedOrders(List.of(), List.of()); // 没定价的商品不交易（同 Market 的口径）
    }
    List<HouseholdId> keys =
        rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
    return ordersFor(round, planFor(round, hex, keys), hex, market, commodity, payChoice);
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
    // ★ M-A1：没有运力池的世界（没有"选了跑商的家户"）⇒ 跨格货走不动；同格成交逐值不变。
    // ★ M-C：没有纯商号集合（夹具 / 纯状态读者）⇒ 谁都不豁免运费 ⇒ 逐值退回 M-A2（缺省语义中性，I-C2）。
    return clearOncePerCycle(
        markets, round, trigger, topology, parallelism, MerchantCapacityPool.empty(), Set.of());
  }

  /**
   * ★★ <b>M-A1 承运入口</b>：每条跨格 lane 的运力由 {@link MerchantCapacityPool} 按<b>发货格</b>的逐 hex 运力池
   * 现算（提供方市场议价权序），买方 CARRIER_FEE 直接付给<b>提供运力的家户</b>。池空 ⇒ 该格没有"选了跑商的家户" ⇒ 跨格路线根本不建（具名 {@code
   * LOGISTICS_CAPACITY}）；<b>不再有</b>"没有承运人也照发货、运费记未收"的旧兜底。
   *
   * @param pureMerchantHouseholds ★★ <b>M-C：本轮的纯商号家户集合</b>（H-2；由 {@code EconomySettlement} 从 同一份
   *     {@code classMemberships × classPositions} 现算，判据的唯一拼写点是 {@code MerchantIdentity}）。
   *     <b>自运自货</b>（承运方与货主都是纯商号）⇒ 免运费（H-A/H-G）；空集 ⇒ 谁都不豁免 ⇒ 逐值退回 M-A2（缺省中性）。
   */
  static MarketOutcome clearOncePerCycle(
      Map<HexCoord, Market> markets,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(parallelism, "parallelism");
    Objects.requireNonNull(carrierPool, "carrierPool");
    if (MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_ROUND_START",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "trigger",
                  trigger,
                  "markets",
                  markets.size(),
                  "regions",
                  topology.regions().size(),
                  "capacityHouseholds",
                  carrierPool.householdCount(),
                  "capacityHexes",
                  carrierPool.hexCount(),
                  "rows",
                  round.householdEconomies.size(),
                  "creditEnabled",
                  round.creditEnabled()));
    }
    // ★★ E（2026-10-09）：本轮"钱的价"（世界行情 + 当地实际流通的币种）只装配一次 —— 它依赖本轮**全部**家户的
    //   货币账户，故必须由协调器算好、与各区 worker 副本共用同一份实例（各自现算会在"认不认得出某种钱"上漂开）。
    Map<String, List<HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies);
    CurrencyValuation currencyValuation =
        CurrencyValuation.of(
            round.fx(),
            CurrencyValuation.circulationByRegion(topology, rowsByHex, round.householdMoney()),
            // ★ R2：口岸实际管制力作为家户对外币估值的减项（缺省 none ⇒ 逐值退回改前行为）。
            round.portEnforcement());
    MatchContext ctx =
        new MatchContext(
            round, markets, topology, carrierPool, round.regulation(), currencyValuation);
    // ★★ M-C：本轮的纯商号集合（免运费判据的范围）—— 从调用方给的集合原样带入（装配点在 EconomySettlement）。
    ctx.pureMerchantHouseholds = pureMerchantHouseholds;
    if (MARKET.isDebugEnabled()) {
      // ★★ E（§一.9：DEBUG 写"为什么"）：本轮"钱的价"认得出哪些币 —— "某笔异币为什么没成交"的第一现场。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_CURRENCY_VALUATION",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day,
                  "quotedCurrencies",
                  currencyValuation.quotedCurrencies().size(),
                  "circulation",
                  currencyValuation.circulationSummary()));
    }
    if (markets.isEmpty() || trigger == MarketTrigger.NONE) {
      return new MarketOutcome(
          MarketReport.empty(round.day, trigger, carrierPresent(ctx)), markets);
    }
    // ★★ P-T1b：税表的区键口径核对（与口岸闸同一条 fail-closed 契约，见 {@link #requirePortZoneKeysAligned}）——
    //   税表非空却一个区键都命中不了本轮拓扑的区 id ⇒ 三层税会**静默一分不收**，那正是本仓最贵的一类故障。
    if (round.portTax().isActive()) {
      requirePortTaxZoneKeysAligned(ctx);
    }
    // ★★ P-T5b：逐户"本轮用哪种币付"（F-1 的"最强持有币"）—— **必须在并行下单段之前单线程算好**
    //   （worker 只读它；在并行段里现算 = 共享可变缓存 = 数据竞争），且必须用**上面那一份** currencyValuation
    //   （禁两套估值漂开：订单侧的折算与撮合的折算必须是同一个实例）。
    //   ★ 它放在"本轮不开市"的早退之后：不开市的日子不发"这一轮谁用外币付"的 INFO，也不白算一遍。
    MarketPayChoice payChoice =
        MarketPayChoice.of(round, markets, topology, rowsByHex, currencyValuation, true);

    // ── 1. 逐格建计划与订单；参与表按 actor 去重（订单生成与撮合的唯一来源）──────────────────────
    //   ★★ R2：按市场区并行构建，再按 hex (q,r) 序拼回 —— 与原串行序逐字相同（见方法注释）。
    //   ★★ C4：rowsByHex 只在这里建一次（旧 R2 让每个分区 worker 各自重建一次），只读传给 worker。
    //      ★ E 起它更靠前一步建（"钱的价"的当地流通集合要用它），本段只沿用同一个变量。
    //   ★★ 并行安全：worker 读的必须是**普通只读表**，不能是 AccountSession 的活视图（owner 守卫在 worker 线程
    //     第一次 get 就抛）⇒ 协调器先把八张账户表浅拷成 planningRound，worker 只读它。
    MarketRound planningRound = readOnlyPlanningRound(round);
    // ★★ 2026-10-08 防复发守卫：**克隆轮必须与母轮携带同一份套利决定**。
    //   下一段真正下单用的是 planningRound（不是 round）⇒ 克隆一旦丢字段，套利就会"计划满格、订单为零"且毫无报错
    //   （2026-10-08 实测踩到：360 tick 与基线逐值相同、ARBITRAGE_ROUND 83 户而 ARBITRAGE_BUY_ORDER = 0 条）。
    //   ⇒ 这是**契约/一致性故障**（AGENTS §一.9：不降级），具名 ERROR + fail-closed；正常路径上恒不触发
    //     （{@link MarketRound#copyForWorker} 是克隆的唯一拼写点，已逐字段带过）。
    if (!planningRound.arbitrage().instructions().equals(round.arbitrage().instructions())) {
      throw arbitragePlanLostByClone(round.arbitrage().size(), planningRound.arbitrage().size());
    }
    // ★★ R1 防复发守卫（与上面同一条坑）：**计划轮必须携带同一份政府市场授权计划**。
    //   它丢了 ⇒ 国库户在下单的那一份轮里不再是"只按授权下单"的家户 ⇒ 当场退回自动清仓（Z7b 的根因），
    //   而且没有任何报错。契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）。
    if (!planningRound.govMandates().equals(round.govMandates())) {
      EventLog.channel(MARKET)
          .error(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_CONTRACT",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "reason",
                  "authorization-plan-lost-by-planning-clone",
                  "expectedHouseholds",
                  round.govMandates().authorizationOnlyHouseholds(),
                  "actualHouseholds",
                  planningRound.govMandates().authorizationOnlyHouseholds()));
      throw new IllegalStateException("政府市场授权计划被计划轮克隆丢掉（国库户会退回自动下单，契约故障）: day=" + round.day);
    }
    // ★★ §16.4 ① 防复发守卫（与上面两条同一条坑）：**计划轮必须携带同一份跑商家户集合**。
    //   订单生成用的是 planningRound ⇒ 克隆丢了它 = "工具至少一趟"这项保留**整段静默不生效**，跑商家户照旧把工具
    //   全额挂出去（跑商当刻可用量恒 0）—— 与 2026-10-08 套利 / R1 授权计划被克隆丢掉是同一形态的无声故障
    //   （§16.4 ① 是用户裁定 6 的最小第一步，静默不生效等于没做）。契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）。
    if (!planningRound.merchantHouseholds().equals(round.merchantHouseholds())) {
      throw merchantHouseholdsLostByClone(
          round.merchantHouseholds().size(), planningRound.merchantHouseholds().size());
    }
    if (!round.arbitrage().isEmpty() && MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_ARBITRAGE_PLAN_ATTACHED",
                  EconomyLogSource.ECONOMY_ARBITRAGE,
                  "day",
                  round.day,
                  "households",
                  round.arbitrage().size(),
                  "planningRoundHouseholds",
                  planningRound.arbitrage().size()));
    }
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
                  logLifeReserves(planningRound, hex, hexPlan);
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
                    //    ★★ 2026-10-10 P-T1c：区级参考价覆盖已删 ⇒ 不再查"这一格属不属于调控锚区"。
                    PlannedOrders orders =
                        ordersFor(planningRound, hexPlan, hex, market, commodity, payChoice);
                    for (BuyOrder order : orders.buys()) {
                      Participant buyer = byActor.get(order.requester());
                      if (buyer == null) {
                        throw new IllegalStateException(
                            "买订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.requester());
                      }
                      // ★★ 3c：槽位不再从本格计价币取币 —— 支付币由**订单**给（BuySlot 构造期读 order.payWith()，
                      //   "缺省 = 本格计价币"落在订单生成那一侧的 orderCurrencyFor）。
                      //   ★★ P-T5b：槽位在这里把"本格计价币 ↔ 支付币"的价**冻结一次**（payValueMicro /
                      //     limitInPayCurrency）—— 限价口径比较、冻结额、运费腿此后都读这两个冻结值，
                      //     不在撮合中途反复折算（禁两次折算），worker 副本逐值照抄。
                      buys.add(new BuySlot(order, buyer, hex, region, market, payChoice));
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

    // ── 1c. ★★ P-T1d：政府采购优先级（§2.6 第 5 步：置顶在"商户决策步"之后、"撮合"之前）──────────
    //   ★ 只写槽位的两个顺序字段（名次 + 是否真被推进）；不碰订单、不碰价格、不碰任何量算式。
    //   ★ 缺省（没有政府要求管控市场）⇒ 本调用一行不跑 ⇒ 名次保持 PROCUREMENT_RANK_ABSENT ⇒ 逐值不变（I-C2）。
    //   ★ 位置必须在 MarketIndexes 之后（簿 = 索引的 (区 × 商品) 桶）且在撮合之前（顺序是撮合的输入）。
    applyProcurementPriority(ctx, indexes);

    // ── 2. 冻结（M1.2 的写者接上）：挂单即占用；成交/发运/轮末释放 ────────────────────────
    commitFreezes(ctx);
    try {
      // ── 3. 区内优先：按市场区并行计算 FillIntent，协调器按拓扑区序回放（唯一写口仍 applyTransfer）
      matchWithinRegions(ctx, parallelism, indexes);
      // ── 4. 跨区候选（第一版只考直接邻接供应区；P1.2 索引去掉全表扫描，协调器单线程）──────────────
      matchAcrossRegions(ctx, indexes);
      // ── 4a0. ★★ M-A1：逐格运力池与本轮分配汇总（INFO = 每格运力池与分配汇总；§一.9）────────────
      //   ★ 位置：区内 + 跨区撮合都做完之后（此时"谁用了多少运力"才是本轮的事实）。
      ctx.carrierPool.logRoundSummary(round.day);
      // ── 4a0b. ★★ M-A2：本轮运力需求与供需缺口汇总（K-A/K-B/K-5；INFO/DEBUG/TRACE，§一.9）──────
      //   ★ 需求簿只累加读数：不写状态、不铸转移、不改任何判据（守恒与铁律 2 不受影响）；"被运力截断的货物量"
      //     读买槽的既有 V-20 读数（那部分不成交、不成债、不计价 —— K-4/Q-27）。
      ctx.capacityDemands.logRoundSummary(round.day, ctx.carrierPool, goodsBlockedByCapacity(ctx));
      // ── 4a0c. ★★ M-C：本轮商号利润读数汇总（每轮算出来的读数、不落状态；§一.9 INFO = 门槛与利润汇总）──
      //   ★ 位置与上面两条并列：撮合已做完 ⇒ 差价/运费/税/损耗/劳动/工具都是本轮的事实。
      //   ★ 没有跑商家户 / 没有跨格运力 ⇒ 读数簿是空的 ⇒ 一行不打（缺省语义中性，I-C2）。
      ctx.merchantProfits.logRoundSummary(round.day, ctx.carrierPool.householdIds());
      // ── 4a0d. ★★ A2：本轮"运输服务成交"的汇总（§一.9 INFO = 这一轮发生了什么 + 具名计数）────────────
      //   ★ 位置：撮合（区内 + 跨区）之后 —— 此时"卖出去多少服务、收了多少钱"才是本轮的事实。
      //   ★ 一行不刷的条件：没有服务成交（服务不成市 / 没有跨格运力 / 服务货为 0）⇒ 缺省世界一行不打（I-H3）。
      if (ctx.haulServiceTrades > 0 && MARKET.isInfoEnabled()) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "HAUL_SERVICE_SETTLED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "trades",
                    ctx.haulServiceTrades,
                    "serviceMilli",
                    ctx.haulServiceSoldMilli,
                    "paidByCurrency",
                    ctx.haulServicePaidByCurrency,
                    "providers",
                    ctx.carrierPool.householdCount(),
                    "deliveryFaults",
                    ctx.haulServiceDeliveryFaults,
                    "reason",
                    "haul-service-sold-through-market-trade-legs"));
      }
      // ── 4a0e. ★★ A2（§一.9 级别规则）：交付点取不到服务货 = 跨切片一致性故障 ⇒ **ERROR 不降级**。──────
      //   ★ 它必须为 0（池的运力预算就是该户当刻的 haul 可用量，交付紧跟分配之后）；非 0 说明账被别处改了
      //     或池的预算不是从货来的 ⇒ 当场可见，绝不静默少扣（逐条 ERROR 在 deliverHaulService 里发）。
      if (ctx.haulServiceDeliveryFaults > 0) {
        EventLog.channel(MARKET)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERY_FAULTS",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "faults",
                    ctx.haulServiceDeliveryFaults,
                    "reason",
                    "contract-fault-service-goods-not-on-hand-at-delivery"));
      }
      // ── 4a. ★★ P-T1a：口岸节流的轮级汇总（INFO：发生了什么 + 具名计数；逐区对在 DEBUG/TRACE）──────
      //   ★ 只报"被拦下多少"这一件事（计数口径 = 源区→目的区 的<b>区对</b>）：被拦下的量不进候选集、不落状态、不进账本（§11），所以它是日志事实，不是账。
      if (ctx.portGatedPairs > 0 && MARKET.isInfoEnabled()) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "MARKET_PORT_THROTTLED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "gatedPairs",
                    ctx.portGatedPairs,
                    "blockedTransitMilli",
                    ctx.portBlockedMilli,
                    "reason",
                    "two-sided-port-gate-e-source-times-e-destination"));
      }
      // ── 4a1. ★★ P-T1e：币种挂单闸的轮级汇总（INFO：发生了什么 + 具名计数；逐笔在 DEBUG）────────────
      //   ★ 与上一条并列：只报"被挡下多少"这一件事。被挡下的候选不进候选集、不落状态、不进账本（§14.3），所以它是日志事实，不是账。
      if (ctx.currencyGateBlocked > 0 && MARKET.isInfoEnabled()) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "MARKET_CURRENCY_GATE_BLOCKED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "blockedAttempts",
                    ctx.currencyGateBlocked,
                    "blockedQuantityMilli",
                    ctx.currencyGateBlockedMilli,
                    "reason",
                    "currency-order-kind-blocked-by-two-sided-port-rules"));
      }
    } finally {
      releaseAllFreezes(ctx);
    }

    // ── 4b. ★★ D-030 市场信用：现金撮合（含区内/跨区）之后的借钱买货 → 借实物 ───────────────────
    //   ★ 位置：冻结已释放之后、未成交原因落档之前。冻结释放让"可花货币"回到市场轮的真实可用额（不被本轮临时挂单
    //     承诺占住）；信用成交消耗的是各主体的真实余额，转移仍全走唯一写口 applyTransfer。
    //   ★ 顺序：按"买方稳定序"逐户处理；每个买方先货币（钱优先），货币借不到/不够才用商品卖单剩余借实物。
    creditRound(ctx, indexes);

    // ── 4c. ★★ A2a + P-T5：外汇撮合（与商品撮合同一处落账口）──────────────────────────────
    //   ★ 位置：商品撮合 + 信用之后、未成交归因之前 —— 家户能花的钱是"商品买卖之后"的余额（= 用户"挂完生产需求后"的
    //     时点，F-5）；FX 的两条腿同样会改变余额，必须先落完再判商品的未成交档（否则那份归因读的是"还没花出去"的旧数）。
    //   ★★ P-T5 起本段<b>不再</b>要求"有官方汇率"：政府窗口照旧进簿，家户侧另有<b>民间簿</b>（自报价，缺省中性见
    //     {@code FxSettlement#match} 的注）。
    FxRoundResult fx = FxSettlement.match(round, markets, topology);

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
    // ★★ R1：本轮的政府授权成交（用于把 filledMilli 累加回授权行、并清除耗尽/到期行）。
    Map<MarketMandateId, Long> mandateFills = collectGovMandateFills(round, ctx);
    return new MarketOutcome(
        MarketReport.withRegulatedTariff(
            round.day,
            trigger,
            carrierPresent(ctx),
            ctx.fills,
            ctx.unfilled,
            routeUsages,
            ctx.freightPaidByCurrency,
            ctx.freightUncollectedByCurrency,
            ctx.scheduledLossMilli,
            ctx.immediateFills,
            ctx.crossRegionFills,
            priceMode(),
            adapted.updates(),
            ctx.sellerOutcomes,
            ctx.buyerOutcomes,
            ctx.creditFills,
            ctx.tariffByFill,
            fx,
            // ★★ P-T1b：三层税的逐项账目（层/金额/币种/收款政府）—— 读口 taxItems()/taxByCurrency() 的唯一来源。
            ctx.taxItems),
        adapted.markets(),
        mandateFills);
  }

  /**
   * ★★ <b>R1：本轮"政府授权挂单"的成交累计</b>（{@code mandateId → 毫商品}）。
   *
   * <p>★★ <b>归属为什么无歧义</b>：国库户是"只按授权下单"的家户 ⇒ 它在本轮的**全部**订单都来自授权表；
   * 而"同一政府同一商品同一方向至多一条生效授权"由命令边界（{@code economy.AuthorizeGovernmentMarketOrder}）与 {@link
   * GovernmentMarketMandatePlan#of} 的 fail-closed 守卫共同保证 ⇒ 一笔成交只可能属于那一条授权。★ 这里仍按 {@code (household,
   * commodity, side)} 解析而不是按槽位标记： 订单记录形状（{@code BuyOrder}/{@code SellOrder}）是本批**不动**的既有契约。
   */
  private static Map<MarketMandateId, Long> collectGovMandateFills(
      MarketRound round, MatchContext ctx) {
    Map<MarketMandateId, Long> fills = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      if (buy.buyer.household == null
          || !round.govMandates().isAuthorizationOnly(buy.buyer.household)) {
        continue;
      }
      long filled = Math.max(0L, buy.order.quantity() - buy.remaining);
      if (filled <= 0L) {
        continue;
      }
      accumulateMandateFill(
          round,
          fills,
          buy.buyer.household,
          buy.order.commodity(),
          GovernmentMarketMandate.Side.BUY,
          filled);
    }
    for (SellSlot sell : ctx.sells) {
      if (sell.seller.household == null
          || !round.govMandates().isAuthorizationOnly(sell.seller.household)) {
        continue;
      }
      long filled = Math.max(0L, sell.order.sellable() - sell.remaining);
      if (filled <= 0L) {
        continue;
      }
      accumulateMandateFill(
          round,
          fills,
          sell.seller.household,
          sell.order.commodity(),
          GovernmentMarketMandate.Side.SELL,
          filled);
    }
    return fills;
  }

  /** 把一笔成交记到那条唯一的生效授权上；一条以上 / 一条都没有 ⇒ 契约故障（fail-closed，绝不静默记到错的那条）。 */
  private static void accumulateMandateFill(
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
   * <p>★★ <b>M-A1（V-20）：被运力截断的部分不提价、不压价（两侧都剔）</b> —— 每个槽位先扣掉 {@code
   * capacityTruncatedMilli}（本轮真正因运力未获服务的量），再进上面的汇总。用户的裁定是"多出来的不计入当前 hex
   * 的家户商品价格表"：不可服务的跨格买卖不许把本格价格推上去（需求侧）或压下来（供给侧）。 ★ 扣除量按该槽本轮的统计量封顶（{@code min(统计量,
   * 截断量)}），跨车道累加也不会扣成负数。
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
        long reference = buyMarket.priceOf(commodity);
        if (reference > 0L) {
          long affordable =
              safeMulDiv(payableMoneyOf(ctx, buy), EconomySettlement.MILLI_PER_GRAIN, reference);
          quantity = Math.min(quantity, affordable);
        }
      }
      byRegion.computeIfAbsent(buy.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(commodity, ignored -> new long[2])[0] +=
          // ★★ M-A1（V-20）：先剔掉"因运力未获服务"的那一份（上限 = 上面的有效需求量）。
          quantity - Math.min(quantity, buy.capacityTruncatedMilli);
    }
    for (SellSlot sell : ctx.sells) {
      long sellable = sell.order.sellable();
      byRegion.computeIfAbsent(sell.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(sell.order.commodity(), ignored -> new long[2])[1] +=
          // ★★ M-A1（V-20）：供给侧同样剔除被运力截断的部分（上限 = 该槽可售量）。
          sellable - Math.min(sellable, sell.capacityTruncatedMilli);
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
   * ★★ <b>一个 hex 所属区的规范 id</b>（与 {@link MarketRegion#node()}{@code .nodeId()} 同源；不在任何区 ⇒ null，不抛）。★
   * P-T1c 起它只用于"区内税费归属哪个区"（{@link MatchContext#regulationByRegionId}）。
   */
  private static String regionIdAt(MarketTopology topology, HexCoord hex) {
    if (topology == null || hex == null || !topology.contains(hex)) {
      return null;
    }
    return topology.regionOf(hex).node().nodeId();
  }

  /**
   * ★★ <b>订单生成</b>：参考价/限价一律取自<b>本格价表</b>（{@link Market#priceOf}/{@link Market#bidPriceOf}/{@link
   * Market#askPriceOf} 是唯一拼写点）—— 区级参考价/限价覆盖已于 P-T1c 删除（设计书 §16：定价只有逐格一层）。
   *
   * <p>成交仍按参考价（区内）/ 卖方格参考价（跨区）—— 逐 hex 物流成本另由 {@link HexTradeCost} 承担，单 hex 损耗<b>不</b>承担价格职能。
   */
  private static PlannedOrders ordersFor(
      MarketRound round,
      HexPlan plan,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      MarketPayChoice payChoice) {
    // ★★ M2.6：参考价 = 本格价表里的固定报价；两个限价由 Market 的两个**各自独立**的常量现算
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
      // ★★ Z7b：单位户退出商品市场 —— participantsFor 已排除；这里再守一道，保证即使上游计划里混入
      //   排除户也绝不生成买单/卖单（两层防线都指向同一集合，判定无条件）。
      //   ★ R1（2026-10-09）：本集合**不再并入政府国库户**（那是本轮撤销的那一半）；国库户改走下面的
      //   "只按授权下单"分支。单位户那一半一个字不改。
      if (participant.household != null
          && round.marketExcludedHouseholds().contains(participant.household)) {
        continue;
      }
      // ── ★★ R1：政府国库户 = "只按授权下单"：**不生成任何自动订单**（需求/库存差异一概不产生买卖）──────
      //   它今天能出现在市场上的订单**全部**来自 EconomyData.govMarketMandates() 的明确授权
      //   （用户 2026-10-09 §1.3：政府经济行为 = 行政家户挂单，不加政策层）。
      //   ★ 为什么必须显式挡在这里、而不是"反正它没有需求"：国库户持有税收实收的粮/银，而下面的卖单判据是
      //     max(0, 持有 − 冻结 − 必要投入 − 生活保留 − 需求目标)，0 人口的国库户后四项全是 0 ⇒
      //     它会被当成卖家**清仓**（Z7b 的根因：run6 day50 中央粮 271,393 → 0）。
      if (participant.household != null
          && round.govMandates().isAuthorizationOnly(participant.household)) {
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
      // ★★ 3c：**本单的币**（唯一缺省拼写点 = orderCurrencyFor）—— 买方的支付币与卖方的收款币由订单携带；
      //   订单生成这一层把"缺省 = 本格计价币"填进去，槽位/预算/冻结/限价折算此后一律读订单。
      //   ⇒ 旧世界（谁也没指定币）逐值不变（I-C2）。
      //   ★★ P-T5b：选币策略**只改 orderCurrencyFor 一处**（它读 MarketPayChoice 的"该户最强持有币"）——
      //     预算/可负担量/冻结/运费折算都跟着这个币走，见下面各处的"按买方支付币折算"。
      CurrencyId orderCurrency = orderCurrencyFor(payChoice, participant, market);
      // ── 卖：可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留基线 − 有效需求目标) ─────
      //   ★★ 需求目标同时进"不卖"一侧：要买的粮/布（或任何新商品）不许在同一轮又被当余量卖掉。
      long retention = Math.addExact(life, demandTarget);
      long sellable = Math.max(0L, stock - frozen - necessary - retention);
      if (sellable > 0L) {
        sells.add(
            new SellOrder(
                participant.actor, hex, commodity, sellable, bid, round.day, orderCurrency));
      }
      // ── 买：家户补到"生活保留基线 + 有效需求目标"；经营者补到必要生产投入 ─────────────────
      //   ★ M2.4：目标缺口 = target − 可用 − **该时限前确定到货**（在途批次里买方那一份；M2.1 的原文）。
      //   ★★ R4-E2 的预算优先级：先保生活保留基线，再按 demands 的 priority 升序（同 priority 按
      //     DemandId）逐个扣"按参考价折算的买得起量"；下层需求拿上一层剩下的额度。
      long baseTarget = participant.household != null ? life : necessary;
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
      // ★★ D-031：借款人侧不再有额度上限。家户把"目标缺口 + 需求缺口"整笔挂出来（现金撮合仍只按真实预算付，
      //    剩余由信用撮合按放贷人实际可借头寸补）；经营者不参与信用 ⇒ 仍按现金买得起量封顶。
      boolean creditDemand = participant.household != null && round.creditEnabled();
      long quantity =
          creditDemand
              ? desiredQuantity(baseTarget, demandParts, available, incoming)
              : allocateQuantity(baseTarget, demandParts, available, incoming, cashAffordable);
      // ── ★★ 2026-10-08（阶段 1，§4.3.1 第 ④ 步）：家户套利买盘 ────────────────────────────────
      //   ★ 它是"在既有买目标**之上**加量"（I15：生活保留/需求目标一份都不减），量已在排序阶段按
      //     "机会上限（30 天目标保有量的 25%）+ 可动现金的两成 + 价格冲击"封死（见 TradeArbitrageActivity）。
      //   ★★ **刻意不在订单层再按"计划量占用的现金"二次封顶**：credit 世界里既有买盘的最后一段本来就是信用
      //     （`creditRound`）结的，按 notional 现金再封一道会让套利在"现金相对货值极贫瘠"的真档世界里恒为 0
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
          //   ★ 本批不去改这一行的量（会改变未崩溃世界的数值），而是在撮合与信用两处加"同户配对跳过"守卫
          //     （pairUp / moneyCreditForBuy，形制照 FxSettlement.matchBook）；根因与后果见账本。
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
  private static CurrencyId orderCurrencyFor(
      MarketPayChoice payChoice, Participant participant, Market market) {
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
  private static long amountInPayCurrency(
      MarketPayChoice payChoice,
      Market market,
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
  private static void logPayCurrencyUnvalued(
      MarketRound round,
      Participant participant,
      HexCoord hex,
      Market market,
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
   * ★★ <b>R1：政府国库户的"只按授权下单"分支</b>（约束设计书 §4.5 G8 / 不变量 I-P6）。
   *
   * <p>它<b>不</b>执行自动订单算式，只把 {@link GovernmentMarketMandatePlan} 里当天生效的授权折成订单：
   *
   * <ul>
   *   <li>{@code BUY}（收购/压价）：量 = {@code min(剩余授权量, 按参考价买得起的量)}，限价 = {@code min(授权限价, 市场买方限价)} ——
   *       授权只能比市场更严，<b>不能</b>突破市场自身的价格纪律；★ 买盘<b>不走信用</b>（授权 ≠ 加杠杆，见 {@code creditRound} 的 auth-only
   *       守卫）；
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
  private static void planGovMandateOrders(
      MarketRound round,
      HexPlan plan,
      Participant participant,
      HexCoord hex,
      Market market,
      CommodityId commodity,
      long reference,
      long bid,
      long ask,
      long deadline,
      List<BuyOrder> buys,
      List<SellOrder> sells,
      MarketPayChoice payChoice) {
    HouseholdId household = participant.household;
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
                participant.actor,
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
                participant.actor, hex, commodity, sellable, floor, round.day, orderCurrency));
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
      long necessary =
          plan.necessaryInputs
              .getOrDefault(participant.actor, Map.of())
              .getOrDefault(commodity, 0L);
      long life =
          plan.lifeReserves.getOrDefault(participant.actor, Map.of()).getOrDefault(commodity, 0L);
      long demandTarget = 0L;
      for (long part :
          plan.demandParts
              .getOrDefault(participant.actor, Map.of())
              .getOrDefault(commodity, List.of())) {
        demandTarget = Math.addExact(demandTarget, part);
      }
      long retention = Math.addExact(life, demandTarget);
      long wouldBeSell = Math.max(0L, stock - frozen - necessary - retention);
      long incoming = confirmedIncoming(round, participant.actor, commodity, deadline);
      long baseTarget = life;
      long wouldBeBuy =
          round.creditEnabled()
              ? desiredQuantity(
                  baseTarget,
                  plan.demandParts
                      .getOrDefault(participant.actor, Map.of())
                      .getOrDefault(commodity, List.of()),
                  available,
                  incoming)
              : allocateQuantity(
                  baseTarget,
                  plan.demandParts
                      .getOrDefault(participant.actor, Map.of())
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
   * ★★ <b>D-031：借款人侧无额度时，一个家户在"目标缺口 + 需求缺口"上的全额挂单量</b>（毫商品）。
   *
   * <p>现金撮合仍只按真实预算付钱；这里挂出来的全部缺口由后续信用撮合按放贷人实际可借头寸补。经营者不参与信用 ⇒ 仍走 {@link #allocateQuantity} 的现金封顶口径。
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

  /**
   * 一条买单选多最多会花掉的钱：{@code min(预算, ⌈数量 × 限价 ÷ 1000⌉)}（限价 = 货款上限，运费另计）。
   *
   * <p>★★ <b>P-T5b：整个算式在<b>买方支付币</b>上做</b> —— 冻结轴是 {@code (buyer, buy.currency)}，预算也是那个币的可花额 ⇒
   * 限价必须先折成同一个币（{@link BuySlot#limitInPayCurrency}，建槽位时已折一次）。★ 同币时它与 {@link
   * BuyOrder#maxLandedPrice()} 逐值相同 ⇒ 旧世界逐值不变（I-C2）。 ★ 限价折不出来（{@code -1}）⇒ 本单选不出可冻结额，按 0
   * 处理（订单生成侧本就不会产出这种单）。
   */
  private static long requestedMoneyOf(BuySlot buy) {
    if (buy.limitInPayCurrency < 0L) {
      return 0L;
    }
    long goods =
        ceilDiv(buy.order.quantity() * buy.limitInPayCurrency, EconomySettlement.MILLI_PER_GRAIN);
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
   * DebtContractBook#upsert}；市场只改槽位 remaining / 合同表 / 债务人行的派生引用。借货币买货对卖方仍是一笔现金销售 （连同 {@link
   * MarketReport.Fill} 与 MARKET_TRADE 两条腿），借实物只写货腿与 {@link MarketReport.CreditFill}。
   *
   * <p>★ <b>本批口径（与设计文档的差异均在此具名）</b>：① 单区（D-027）：只从买方所在区取货币/商品头寸；② 债权人/债务人 必须是家户（{@link
   * DebtContract} 的两端是 {@code HouseholdId}，经营者 actor 没有这一身份）⇒ 经营者只参与现金买卖， 不作为放贷人/借实物卖方/债务人；③
   * 不跨区、不承运、不聚集。
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
        long price = anchor.priceOf(commodity);
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
          // ★★ R1：**明确授权 ≠ 加杠杆** —— 国库户的授权买盘只花它自己的钱（不借货币、不借实物）。
          //   授权表只授权"买多少、什么价"，没有授权"借多少钱来买"；把它挂出来的量按现金封顶（见
          //   planGovMandateOrders），这里再守一道，保证它永远进不了信用撮合。
          if (round.govMandates().isAuthorizationOnly(buy.buyer.household)) {
            continue;
          }
          if (buy.order.latestArrivalTick() < round.day || buy.order.maxLandedPrice() < price) {
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
   * ★★ <b>D-030 ②a：借钱买货</b>（D-031 起借款人无额度上限）。逐出借人取 {@code amount = min(缺口货款, 出借人可借额)}； 由 amount
   * 反解能买的量（货款 ceil），货物必须同时在卖方剩余里 ⇒ 三腿原子落地：出借人→买方（LOAN_PRINCIPAL）、 买方→卖方（MARKET_TRADE
   * 货款）、卖方→买方（MARKET_TRADE 货）。
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
    // ★★ 2026-10-08 自配对守卫（与 pairUp 的同一条；形制照 goodsCreditForBuy 的 skippedForThisBuyer）：
    //   借款人本人名下的卖单不得成为"借来的钱要买的那批货"的卖方 —— 否则 executeMoneyCredit 的三条腿里有
    //   两条的两端相等（买方→卖方 的货款腿、卖方→买方 的货腿），`Transfer` 当场 fail-closed（整轮 400）。
    //   ★ 为什么这条腿以前没人堵：goodsCreditForBuy 一开始就按 household 排除了自己，货币腿这条路径没有。
    Set<SellSlot> skippedSelfSellers = new LinkedHashSet<>();
    while (buy.remaining > 0L) {
      SellSlot sell = pools.bestCashSeller(regionId, buy.order.commodity(), skippedSelfSellers);
      if (sell == null) {
        break; // 没有可买货物 ⇒ 不放贷（原子绑定）
      }
      if (sell.seller.actor.equals(buy.buyer.actor)) {
        // ★ 记录（§一.9）：业务拒绝 = INFO（具名），理由 = DEBUG；然后换下一个卖方，没有下一个就停在
        //   "没有可买货物"这一档（本买方这一轮不走货币信用，绝不铸自转移）。
        skippedSelfSellers.add(sell);
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "MARKET_CREDIT_SELF_MATCH_SKIPPED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.round.day,
                    "household",
                    buy.buyer.actor.id(),
                    "commodity",
                    buy.order.commodity().value(),
                    "hex",
                    sell.hex,
                    "skippedSellRemainingMilli",
                    sell.remaining,
                    "reason",
                    "borrower-is-also-the-seller-of-the-same-commodity"));
        if (MARKET.isDebugEnabled()) {
          EventLog.channel(MARKET)
              .debug(
                  LogEvent.of(
                      "MARKET_CREDIT_SELF_MATCH_SKIPPED_WHY",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.round.day,
                      "household",
                      buy.buyer.actor.id(),
                      "commodity",
                      buy.order.commodity().value(),
                      "buyRemainingMilli",
                      buy.remaining,
                      "reason",
                      "money-credit-legs-would-have-equal-ends-loan-principal-still-from-others"));
        }
        continue;
      }
      // ★★ E（§3.1）：信用腿同样走**唯一拼写点** —— 借来的钱按 buy.currency 付给卖方，故判据与现金腿逐字同一口径
      //   （估价不足/说不出价 ⇒ 不划算，市场性理由）。fail-closed：本买方这一轮不再走信用，绝不按 1:1 静默付出去。
      long buyerUnitPrice =
          settlementUnitPrice(ctx, buy, sell, buy.remaining, price, LEG_MONEY_CREDIT, true);
      if (buyerUnitPrice < 0L) {
        break;
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
      long gapPayment = paymentForQuantity(buy.remaining, buyerUnitPrice);
      long amount = Math.min(gapPayment, lender.remaining);
      if (amount <= 0L) {
        break;
      }
      long quantity =
          Math.min(
              buy.remaining, Math.min(sell.remaining, maxQuantityForMoney(amount, buyerUnitPrice)));
      if (quantity <= 0L) {
        if (amount == lender.remaining) {
          index++; // 这个出借人太小，买不起一个最小交易单位；看下一个
          continue;
        }
        break;
      }
      long payment = paymentForQuantity(quantity, buyerUnitPrice);
      if (payment <= 0L || payment > amount) {
        break; // 理论到不了；到得了就是算法漂开，停在本档不超借
      }
      executeMoneyCredit(ctx, buy, sell, lender, payment, quantity, price, buyerUnitPrice, pools);
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
   * ★★ <b>D-030/D-031 ②b：借实物</b>。从卖单剩余（卖家须是家户，才能成为 {@link DebtContract} 的债权人）按"可借数量 降序 → 商品 id 升序 →
   * 卖家 actor id 升序"取；量 = min(缺口, 卖单剩余, 配额剩余)，货腿 卖家 → 买方 （{@code LOAN_PRINCIPAL}），无货币腿、也不写普通 sale
   * fill。★ D-031：借款人侧不再设额度上限。
   *
   * <p>★★ <b>E（2026-10-09 裁定 R1/R2）：本腿不再看币种</b> —— 借实物不经货币（债务单位 = 商品），A2b 那条 "异币 ⇒
   * 具名拒"的判据随之退役；本腿仍然走过<b>同一个拼写点</b> {@link #settlementUnitPrice}（{@code leg =
   * goods-credit}），由它统一回答"这条腿要不要看币种"（答案：不看）。A2b 当年是在"三条腿只有它漏堵"的背景下加的门， 而 E
   * 批把三条腿的<b>判据</b>换成了"按钱的价判划算与否"——对不铸钱腿的这条腿，那个判据没有对象。
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
      SellSlot sell = pools.bestGoodsSeller(regionId, commodity, skippedForThisBuyer);
      if (sell == null) {
        break;
      }
      if (sell.seller.household == null || sell.seller.household.equals(borrower)) {
        skippedForThisBuyer.add(sell); // 不是可成立合同的两端（自借自买不是一笔信用）
        continue;
      }
      long quantity = Math.min(buy.remaining, sell.remaining);
      if (quantity <= 0L) {
        skippedForThisBuyer.add(sell);
        continue;
      }
      // ★★ E（2026-10-09 裁定 R1/R2）：借实物这条腿**不经货币**（债务单位 = 商品，见 {@code DebtUnit.Commodity}），
      //   故"币种不符"不再构成拒因 —— 但三条腿仍走**同一个拼写点** {@link #settlementUnitPrice}（{@code leg =
      //   goods-credit}）：它统一回答"这条腿要不要看币种"（答案：不看，币种维为空）。不许在这里另写第二套比较。
      //   ★ 本笔一个数都不动：不减剩余、不铸货腿、不建债务合同。
      settlementUnitPrice(ctx, buy, sell, quantity, 0L, LEG_GOODS_CREDIT, true);
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
      long sellerUnitPrice,
      long buyerUnitPrice,
      CreditPools pools) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
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
    // ★★ 2026-10-09 选项 A：这里改前会把新合同的派生引用补进债务人**行**（顺带 fail-closed 地断言该行在场）；
    //   拆表后引用表由 EconomyData 构造期的 DebtReferenceReconciler 按 ctx.debts 整表重建，故补引用这一步消失，
    //   但**那条具名断言逐字保留**（拆表不许放松守卫）：债务人的家户行必须在本轮工作副本里。
    requireHouseholdRowInRound(round, buy.buyer.household);
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
            // ★★ P-T4：单价币 = 卖方格计价币；实付币 = 买方支付币（钱腿就铸在它上面）。
            sell.market.numeraire(),
            sellerUnitPrice,
            buy.currency,
            payment,
            0L,
            0L,
            round.day,
            true,
            "",
            loss);
    ctx.fills.add(fill);
    if (!buy.currency.equals(sell.market.numeraire()) && payment > 0L) {
      // ★★ E（§3.1 日志）：信用腿的异币成交同样具名记录（哪两户、什么币、估值多少、成交比价）。
      logForeignCurrencyFill(
          ctx, buy, sell, commodity, quantity, sellerUnitPrice, buyerUnitPrice, payment);
    }
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
      MatchContext ctx, BuySlot buy, SellSlot sell, long quantity, CreditPools pools) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
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
    // ★★ 2026-10-09 选项 A：这里改前会把新合同的派生引用补进债务人**行**（顺带 fail-closed 地断言该行在场）；
    //   拆表后引用表由 EconomyData 构造期的 DebtReferenceReconciler 按 ctx.debts 整表重建，故补引用这一步消失，
    //   但**那条具名断言逐字保留**（拆表不许放松守卫）：债务人的家户行必须在本轮工作副本里。
    requireHouseholdRowInRound(round, buy.buyer.household);
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
        quantity, quantity * ctx.hexTradeCost.lossPerMilleBetween(sell.hex, buy.hex) / 1000L);
  }

  /** 与 {@code executeTrade} 的货款算式同源：{@code ⌈数量 × 单价 ÷ 1000⌉}（毫计价货币）。 */
  private static long paymentForQuantity(long quantity, long unitPrice) {
    if (quantity <= 0L || unitPrice <= 0L) {
      return 0L;
    }
    return ceilDivPositive(safeMulDiv(quantity, unitPrice, 1L), EconomySettlement.MILLI_PER_GRAIN);
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
   * <p>★ "未覆盖"= {@code max(0, 日自然需求 − (持有 − 冻结))}；缺该商品市场价 ⇒ 该商品需要不入保留额（不硬折、不假装 0 需要，见交付报告）。人口/行读不到
   * ⇒ {@link Long#MAX_VALUE}（fail-closed：不把"读不到"当"不用留"）。
   */
  private static long moneyReserveOf(MatchContext ctx, Participant participant, Market market) {
    // ★★ A2a：委托到家户版（同一份算式；两处各写一遍 = 下一个改口径的人只会改一处）。
    return moneyReserveOfHousehold(ctx.round, participant.household, market);
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

  /**
   * ★★ <b>2026-10-09 选项 A 后剩下的具名断言</b>（改前住在 {@code addDebtReference} 里）：信用成交的债务人行必须在本轮 市场工作副本里 ——
   * 行不在 = 状态漂开，fail-closed 具名抛（不静默当作"没有这一户"）。
   */
  private static void requireHouseholdRowInRound(MarketRound round, HouseholdId debtor) {
    if (!round.householdEconomies.containsKey(debtor)) {
      throw new IllegalStateException("信用成交的债务人行不在市场轮里（状态漂开）: " + debtor);
    }
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
    long cash = spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
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
        Participant lender,
        CurrencyId currency,
        long ratePerMille,
        HexCoord hex,
        long amountMilli) {
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
          // ★★ R1：国库户（"只按授权下单"）**不放贷** —— 自动放贷也是"没被授权的经济行为"。
          //   ★ 改前国库户不在参与者表里（Z7b 排除集）⇒ 它本来就不是放贷人；本守卫保住那一半语义，
          //     否则"回市场"会顺手把它变成市场信用的自动放贷人（普通家户的借贷结果随之改变）。
          if (ctx.round.govMandates().isAuthorizationOnly(participant.household)) {
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
          long price = anchor.priceOf(commodity);
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
     * ★ 一笔信用成交后的卖单池同步：{@code goods} 的排序键读 {@code remaining}，因此先摘旧值、扣减、再按新值放回； {@code cash}
     * 的排序键是静态成本，只在卖光时移除。
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

    SellSlot bestCashSeller(
        String regionId, CommodityId commodity, Set<SellSlot> skippedForThisBuyer) {
      TreeSet<SellSlot> pool = cashPool(regionId, commodity);
      if (pool == null) {
        return null;
      }
      while (!pool.isEmpty() && pool.first().remaining <= 0L) {
        pool.pollFirst();
      }
      for (SellSlot sell : pool) {
        if (sell.remaining > 0L && !skippedForThisBuyer.contains(sell)) {
          return sell;
        }
      }
      return null;
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

  // ── ★★ P-T1d：政府采购优先级（置顶 pass；**只改顺序、不改价格/量**）──────────────────────

  /**
   * ★★ <b>P-T1d：把"要求管控市场"的政府的挂单在行政力池余量内推到挂单簿最前</b>（设计书 §16.3/§17；用户原话
   * 「视为政府强制把自己的账户在市场交易里强制到最开始卖、最开始买，这时候前方要超越多少家户，政府就需要付多少额外行政劳动力」「如果行政力见底了那就不允许继续超越了」）。
   *
   * <pre>
   * 前置：本轮注入表里**有**该政府的国库户（hh-gov-&lt;govUnitId&gt;）= "政府要求管控市场"（缺省空表 ⇒ 本 pass 直接返回）
   * 簿   ：一张"挂单簿" = (市场区 × 商品 × 买/卖侧)；买侧的服务序 = 槽位列表序，卖侧 = 成本序
   *        （成本序与车道无关：{@link ProducerCostBook#landedCostMilli} 对单位成本只是"加运费常数"）
   * 推进 ：逐簿、逐政府（国库户 id 升序）、逐该政府的槽位（服务序）——
   *        从"本政府已放置块之后"起逐只**向前**越过，**每越过一户扣一份行政力**；
   *        付得起就继续、付不起（池 = 0）就地停住（fail-closed：**不许先超后欠**）
   * 产出 ：每只槽位一个**全序名次**（该簿置顶后的位次）+"是否真的越过 ≥ 1 户"；
   *        撮合按名次走（卖侧取代成本序、买侧取代列表序），价格与量算式一个字不改
   * </pre>
   *
   * <p>★★ <b>算法本体在 {@link ProcurementPriorityOrder#advance}</b>（买/卖两侧唯一的实现；本方法只负责"逐簿喂进去"）。
   *
   * <p>★★ <b>三条冻结口径（逐条对上派单 §4）</b>：
   *
   * <ol>
   *   <li><b>只改顺序</b>：本 pass 只写 {@code procurementRank}/{@code procurementOvertook} 两个槽位字段，
   *       <b>不动</b>订单、不动价格、不动限价、不动任何量算式；撮合侧只把"按成本序/列表序"换成"按名次"；
   *   <li><b>全序</b>：同一张簿里名次两两不同（= 置顶后的位次）；政府单在最先、<b>同政府多单保持 canonical 序</b>；
   *   <li><b>越一户一份 + 见底硬停</b>：行政力池单位 = 家户（§17.4 N-1）；同一张簿里同一户只付一次；池 = 0 时不再越过。
   * </ol>
   *
   * <p>★★ <b>簿的遍历序（确定性 I7）</b>：先买侧簿、后卖侧簿，各自按 {@code MarketIndexes} 的建表序（= 槽位插入序， 内容的纯函数）；簿内政府的处理序 =
   * 国库户 id 升序（见 {@link ProcurementPriorityOrder}）。<b>池子是跨簿共享的</b> （"每 tick
   * 一份编制劳动力"），所以"先买后卖"这条遍历序也是池子耗尽顺序的一部分 —— 它必须固定，故写在这里。
   *
   * <p>★★ <b>缺省语义中性（I-C2）</b>：{@code !input.isActive()} ⇒ <b>本方法一行不跑</b>（名次保持 {@link
   * #PROCUREMENT_RANK_ABSENT}）⇒ 撮合仍走既有顺序 ⇒ 没有任何政府要求管控的世界逐值不变。
   */
  private static void applyProcurementPriority(MatchContext ctx, MarketIndexes indexes) {
    ProcurementPriorityInput input = ctx.round.procurementPriority();
    if (!input.isActive()) {
      return; // ★ 缺省语义中性：一个字段都不写、一只槽位都不动（I-C2）
    }
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : input.overtakeUnitsByTreasury().entrySet()) {
      poolLeft.put(entry.getKey(), entry.getValue());
    }
    ProcurementPriorityOrder.BookResult tally = ProcurementPriorityOrder.BookResult.ZERO;
    // ① 买侧簿（区 × 商品）② 卖侧簿（区 × 商品）—— 顺序固定：先买后卖，各自按索引建表序。
    for (Map.Entry<String, Map<CommodityId, List<BuySlot>>> byRegion :
        indexes.buysByRegionCommodity.entrySet()) {
      for (Map.Entry<CommodityId, List<BuySlot>> book : byRegion.getValue().entrySet()) {
        tally =
            tally.plus(
                ProcurementPriorityOrder.advance(
                    book.getValue(),
                    slot -> slot.buyer.household,
                    (slot, rank) -> slot.procurementRank = rank,
                    (slot, overtook) -> slot.procurementOvertook = overtook,
                    input,
                    poolLeft,
                    ctx.round.day));
      }
    }
    for (Map.Entry<String, Map<CommodityId, List<SellSlot>>> byRegion :
        indexes.sellsByRegionCommodity.entrySet()) {
      for (Map.Entry<CommodityId, List<SellSlot>> book : byRegion.getValue().entrySet()) {
        tally =
            tally.plus(
                ProcurementPriorityOrder.advance(
                    book.getValue(),
                    slot -> slot.seller.household,
                    (slot, rank) -> slot.procurementRank = rank,
                    (slot, overtook) -> slot.procurementOvertook = overtook,
                    input,
                    poolLeft,
                    ctx.round.day));
      }
    }
    logProcurementPriority(ctx.round.day, input, poolLeft, tally);
  }

  /** ★ P-T1d 轮级 INFO：管控开启 / 本轮置顶数与消耗（§一.9：INFO = 这一轮发生了什么 + 具名计数）。 */
  private static void logProcurementPriority(
      long day,
      ProcurementPriorityInput input,
      Map<HouseholdId, Long> poolLeft,
      ProcurementPriorityOrder.BookResult tally) {
    if (!MARKET.isInfoEnabled()) {
      return;
    }
    long remaining = 0L;
    for (Long left : poolLeft.values()) {
      remaining = Math.addExact(remaining, left);
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_PROCUREMENT_PRIORITY_APPLIED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                day,
                "governments",
                input.governmentCount(),
                "poolUnits",
                input.totalUnits(),
                "overtakesConsumed",
                tally.consumed(),
                "poolUnitsLeft",
                remaining,
                "promotedSlots",
                tally.promoted(),
                "hardStops",
                tally.hardStops(),
                "reason",
                "market-control-demanded-orders-promoted-within-administrative-pool"));
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
    // ★★ M-A1：有运力池（= 有"选了跑商的家户"）时区内跨格也要由它们承运 —— 运力是**逐 hex 每轮一份**的硬约束，
    //   分散在并行 worker 里各自持副本会超发。⇒ 有运力池的世界改成协调器单线程按拓扑区序直接撮合
    //   （与 RegionClone.run 的区内序逐字同源），跨格/区内共用一个真实运力池。
    //   ★ 池空的世界继续走并行：那里**没有任何格有运力** ⇒ 跨格路线在候选生成处就不建（pairUp 的具名拦下），
    //     worker 与协调器看到的是同一个"全空"事实 ⇒ 1/4/8 线程逐值相同（I7/N7）。
    if (!ctx.carrierPool.isEmpty()) {
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
        // ★★ P-T1c：区级配额已删（设计书 §16）⇒ 不再有"worker 配额余量并回协调器"这一步；
        //   区内撮合按账号因果链回放，不需要额外的跨 worker 共享预算表。
      }
    }
  }

  /**
   * ★★ <b>有运力池时的区内串行撮合</b>（2026-10-09 立、2026-10-10 M-A1 改绑）：与 {@code RegionClone.run} 同一区内顺序 （拓扑区序
   * × 商品序 × 槽位插入序），但直接在协调器 {@code ctx} 上成交 —— 区内跨格运费因此与跨区运费共用同一个 {@link MerchantCapacityPool}，逐 hex
   * 运力不会被各 worker 副本重复发放。
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
        if (!anchorMarket.hasPrice(commodity)) {
          continue;
        }
        long price = anchorMarket.priceOf(commodity);
        List<BuySlot> buys = activeBuys(buysByCommodity.get(commodity), price, ctx.round.day);
        if (buys.isEmpty()) {
          continue;
        }
        List<SellSlot> sells = activeSells(sellsByCommodity.get(commodity), price, ctx.round.day);
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
      householdFrozenGoods.put(household, copyBalances(ctx.round.householdFrozenGoods, household));
      householdFrozenMoney.put(household, copyBalances(ctx.round.householdFrozenMoney, household));
      Map<CommodityId, Long> recorded = ctx.round.unmetToday.get(household);
      if (recorded != null) {
        unmetToday.put(household, new LinkedHashMap<>(recorded));
      }
    }
    // ★★ 2026-10-08：改走克隆的唯一拼写点（旧版在这里手抄 20 个构造参数 ⇒ 同样会静默丢掉后来新增的字段）。
    //   ★ 本地账本：worker 铸造的转移只服务于本地 applyTransfer，交回后丢弃；协调器回放时在全局累加器上重铸。
    MarketRound localRound =
        ctx.round.copyForWorker(
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            unmetToday,
            new ProductionLedger.Accumulator(ctx.round.day));
    MatchContext local =
        new MatchContext(
            localRound,
            ctx.markets,
            ctx.topology,
            // ★★ M-A1：worker 副本不持真实运力池（它是逐笔扣减的可变对象、只允许协调器单线程触碰）。
            //   ★ 为什么是安全的：只有池**空**的世界才走并行路径 ⇒ 协调器与 worker 看到的是同一个"全空"事实。
            MerchantCapacityPool.empty(),
            // ★★ D-027：worker 只读本区适用的调控（配额在本区副本上扣，回放后并回协调器）。
            ctx.regulationFor(regionId),
            // ★★ E：**协调器那一份**"钱的价"（含全部家户的流通币种）—— 副本自己现算会漂开，
            //   而"认不认得出某种钱"必须两边同一个答案（否则回放对不上，具名抛）。
            ctx.currencyValuation());
    // ★★ M-C：worker 副本照抄同一份纯商号集合（它只跑 route == null 的区内同格意向 ⇒ 判据不可达，但两份上下文保持一致）。
    local.pureMerchantHouseholds = ctx.pureMerchantHouseholds;
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
          // ★★ P-T1c：区内参考价 = 卖方格价表的固定报价（与 ordersFor 同一条口径）—— 区级覆盖已删。
          //    ★ 2026-10-09：有定价行（含明确 0 价免费）都进撮合；只有"从未定价"才跳过。
          if (!anchorMarket.hasPrice(commodity)) {
            continue;
          }
          long price = anchorMarket.priceOf(commodity);
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
                buy.blocked,
                buy.capacityTruncatedMilli));
      }
      List<SellSlotState> sellStates = new ArrayList<>(allSells.size());
      for (SellSlot sell : allSells) {
        sellStates.add(
            new SellSlotState(
                sell.orderIndex,
                sell.remaining,
                sell.frozenRemaining,
                sell.blocked,
                sell.capacityBlocked,
                sell.capacityTruncatedMilli));
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
      buy.capacityTruncatedMilli = state.capacityTruncatedMilli();
    }
    for (SellSlotState state : outcome.sellStates()) {
      SellSlot sell = ctx.sells.get(state.orderIndex());
      if (sell.remaining != state.remaining() || sell.frozenRemaining != state.frozenRemaining()) {
        throw new IllegalStateException(
            "区内市场回放与 worker 分区计算漂开（拒绝按到达序静默兜底）：卖槽=" + state.orderIndex());
      }
      // ★★ E：拒因（市场性理由）也是槽位状态的一部分 —— 不带回来，读数会把"卖方说不出这个价"误报成
      //   NO_BUYER/OUTCOMPETED（归因失真，N1 的判据就看不见了）。
      sell.blocked = state.blocked();
      // ★★ M-A1：被运力截断量同样带回（V-20 的并行路径落点）。
      sell.capacityBlocked = state.capacityBlocked();
      sell.capacityTruncatedMilli = state.capacityTruncatedMilli();
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
   * ★★ <b>2026-10-10 G3-fix-1：这个 unit 的经营者是不是"按构造不是家户行"的合成聚合经营者</b>。
   *
   * <p>★★ <b>为什么必须与"真坏数据"分开</b>：{@code participantsFor} 的第 ⑤ 分支（有正资产、却连劳动家户都解析不到）
   * 是**具名抛**的守卫，它防的是"经营者指着不存在的主体"这类坏数据（{@code
   * docs/superpowers/specs/2026-10-23-weave-not-a-market-subject.md} §3.4 明文要求保留）。但**聚合经营**（如家户纺织
   * {@code weave@<hex>}）的经营者**按构造就不是家户行**： {@code EconomySeeder} 不显式给 operator ⇒ 走 {@link
   * RegimeOperators#defaultOperator} 的合成 actor （{@code
   * HOUSEHOLD:weave@<hex>}），而它的账户主体是**名下劳动家户的集合**（{@link HouseholdRouting} ③）。
   * 成员户一旦全部消亡/被迁移删行（{@link ModeMigrationSettlement} 的 {@code retireSource} 会删人口归零的成员户行）， 这个 unit
   * 就落到第 ⑤ 分支 ⇒ 真实 world 复测里以"整条 AdvanceTime 不落 revision"的形式硬崩（day38）。
   *
   * <p>判据（两件同时成立，纯查表、无副作用）：
   *
   * <pre>
   * ① unit.operator **恰是**该 unit 产业的合成默认经营者
   *    {@link RegimeOperators#defaultOperator}(industry.regime(), industry.id())
   *    —— regime 未登记（该方法抛）⇒ 判据不成立（真坏数据仍走具名抛）
   * ② 该 operator **不是现存家户行**（{@link HouseholdRouting#householdOfActorOrNull} 为空）
   *    —— 显式换上组织者家户的 farm/craft/trade **不满足 ①**；换成真家户的 unit **不满足 ②**
   * </pre>
   *
   * <p>⇒ 只有"聚合 unit + 组织已无主"这一种合法终态被认出；绕过它就得伪造一个不存在的家户（铁律 1/3 禁止）。
   *
   * @param unitId 该 unit（必须在 {@code round.units} 里，调用方已保证）
   * @param operator 该 unit 的经营者 actor
   * @param round 本轮只读视图（取产业模板与现存家户行）
   */
  private static boolean isUnresolvedAggregateOperator(
      ProductionUnitId unitId, ActorRef operator, MarketRound round) {
    if (operator.kind() != ActorKind.HOUSEHOLD) {
      return false; // 合成默认经营者的制度（household / tenant）都是 HOUSEHOLD；其余一律不算
    }
    ProductionProcess unit = round.units.get(unitId);
    Industry industry = unit == null ? null : round.industries.get(unit.industry());
    if (unit == null || industry == null || industry.regime() == null) {
      return false;
    }
    ActorRef derived;
    try {
      derived = RegimeOperators.defaultOperator(industry.regime(), industry.id());
    } catch (RuntimeException unregisteredRegime) {
      // 制度未登记 ⇒ 推不出合成经营者 ⇒ 判据不成立（该 unit 仍走具名抛，fail-closed 不放宽）。
      return false;
    }
    if (!operator.equals(derived)) {
      return false; // 显式换上的组织者/经营者家户（或别的写法）⇒ 不属于本条
    }
    return HouseholdRouting.householdOfActorOrNull(operator, round.householdEconomies).isEmpty();
  }

  /**
   * ★★ <b>2026-10-09 D3：市场参与者解析失败（有正资产却既无关联家户、也无劳动家户）的具名诊断</b>。
   *
   * <p>先记 {@code MARKET_SUBJECT_UNRESOLVED_UNIT} ERROR（契约/跨切片一致性故障不降级），再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。旧实现只抛一句话（日志里只剩 {@code
   * error=IllegalStateException}），长跑现场完全看不出是哪个 unit / 哪个 operator / 它挂在哪一格 —— 本方法把"谁、在哪、为什么"
   * 一次性写进日志与异常正文。字段一律是身份与计数，不含载荷明文。
   */
  private static IllegalStateException unresolvedMarketSubject(
      MarketRound round, ProductionUnitId unitId, ActorRef operator, Map<AssetKind, Long> usable) {
    ProductionProcess unit = round.units.get(unitId);
    ProductionRules relation = round.relations.get(unitId);
    List<String> shareOwners = new ArrayList<>();
    List<String> shareOperators = new ArrayList<>();
    for (AssetShareId shareId : round.index.ownershipStakeIdsOfProcess(unitId)) {
      OwnershipStake share = round.assetShares.get(shareId);
      if (share == null) {
        continue;
      }
      if (shareOwners.size() < 8) {
        shareOwners.add(share.owner().toString());
      }
      if (shareOperators.size() < 8) {
        shareOperators.add(share.operator().toString());
      }
    }
    // operator 若本身就是家户 actor，则把它的家户身份与"经济行是否还在"一并记出来 —— 这两者一起才能回答
    // "是家户行被删了（坏数据）"还是"operator 从来就不是家户（聚合主体，需靠 relation/份额解析）"。
    HouseholdId operatorHousehold = null;
    boolean operatorIsHousehold = operator.kind() == ActorKind.HOUSEHOLD;
    if (operatorIsHousehold) {
      operatorHousehold = HouseholdActors.householdOf(operator);
    }
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_SUBJECT_UNRESOLVED_UNIT",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "unit",
                unitId.value(),
                "operator",
                operator,
                "operatorKind",
                operator.kind(),
                "operatorHousehold",
                operatorHousehold == null ? "none" : operatorHousehold.value(),
                "operatorHouseholdRow",
                operatorHousehold != null
                    && round.householdEconomies.containsKey(operatorHousehold),
                "hex",
                round.index.hexOf(unitId) == null ? "none" : round.index.hexOf(unitId),
                "industry",
                unit == null ? "none" : unit.industry(),
                "modeKey",
                unit == null ? "none" : unit.modeKey(),
                "usableAssets",
                usable,
                "shareOwners",
                shareOwners,
                "shareOperators",
                shareOperators,
                "relationResidualOwner",
                relation == null ? "none" : relation.residualOwner().toString(),
                "relationInputSupplier",
                relation == null ? "none" : relation.inputSupplier().toString(),
                "laborAllocations",
                round.index.allocationsOfUnit(unitId).size(),
                "reason",
                "positive-asset-but-no-economic-household-and-no-labor-household"));
    return new IllegalStateException(
        "市场参与者无法解析到任何家户（账户主体只有家户；聚合主体必须能解析到组织者/经营者家户）："
            + "day="
            + round.day
            + " unit="
            + unitId.value()
            + " operator="
            + operator
            + " operatorKind="
            + operator.kind()
            + " operatorHousehold="
            + (operatorHousehold == null ? "none" : operatorHousehold.value())
            + " operatorHouseholdRow="
            + (operatorHousehold != null && round.householdEconomies.containsKey(operatorHousehold))
            + " hex="
            + round.index.hexOf(unitId)
            + " industry="
            + (unit == null ? "none" : unit.industry())
            + " modeKey="
            + (unit == null ? "none" : unit.modeKey())
            + " usableAssets="
            + usable
            + " shareOwners="
            + shareOwners
            + " shareOperators="
            + shareOperators
            + " relationResidualOwner="
            + (relation == null ? "none" : relation.residualOwner())
            + " relationInputSupplier="
            + (relation == null ? "none" : relation.inputSupplier())
            + " laborAllocations="
            + round.index.allocationsOfUnit(unitId).size());
  }

  /**
   * ★★ <b>2026-10-08：克隆丢字段的具名契约故障</b>（防复发守卫的唯一发射点）。
   *
   * <p>先记 {@code MARKET_ARBITRAGE_PLAN_LOST} ERROR（契约/跨切片一致性故障不降级），再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。{@code reason} 只含数量，不含载荷明文。
   */
  private static IllegalStateException arbitragePlanLostByClone(
      int motherHouseholds, int cloneHouseholds) {
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_ARBITRAGE_PLAN_LOST",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "motherHouseholds",
                motherHouseholds,
                "cloneHouseholds",
                cloneHouseholds,
                "reason",
                "planning-round-clone-dropped-arbitrage-plan"));
    return new IllegalStateException(
        "市场轮的克隆丢了套利计划（母轮 "
            + motherHouseholds
            + " 户 / 克隆轮 "
            + cloneHouseholds
            + " 户）：订单生成用的是克隆轮，丢字段会让整个套利静默不生效。"
            + "克隆必须走 MarketRound.copyForWorker 这个唯一拼写点。");
  }

  /**
   * ★★ <b>§16.4 ①：计划轮克隆丢掉"跑商家户"集合时的契约故障</b>（{@link #clearOncePerCycle} 的防复发守卫调用）。
   *
   * <p>★ 与 {@link #arbitragePlanLostByClone} 同一条坑、同一个形制：订单生成用的是克隆轮，丢字段 = {@link #necessaryInputsOf}
   * 里"工具至少一趟"这一项整段静默不生效（跑商家户照旧把工具全额挂出去 ⇒ 跑商当刻可用量恒 0）。 契约/一致性故障 ⇒ ERROR 不降级 + fail-closed；正常路径上恒不触发。
   */
  private static IllegalStateException merchantHouseholdsLostByClone(
      int motherHouseholds, int cloneHouseholds) {
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MERCHANT_HOUSEHOLDS_LOST_BY_CLONE",
                EconomyLogSource.ECONOMY_MARKET,
                "motherHouseholds",
                motherHouseholds,
                "cloneHouseholds",
                cloneHouseholds,
                "reason",
                "planning-round-clone-dropped-merchant-households"));
    return new IllegalStateException(
        "市场轮的克隆丢了\"跑商家户\"集合（母轮 "
            + motherHouseholds
            + " 户 / 克隆轮 "
            + cloneHouseholds
            + " 户）：订单生成用的是克隆轮，丢字段会让\"工具至少一趟\"这项挂单保留静默不生效。"
            + "克隆必须走 MarketRound.copyForWorker 这个唯一拼写点（或任一 withX —— 它们逐字段带过）。");
  }

  /**
   * ★★ <b>给并行 worker 用的只读市场轮</b>：八张账户表浅拷成普通 {@code LinkedHashMap}（内层表只读共享）， 避开 {@link
   * AccountSession} 活视图的 owner 守卫；账本换成本地空累加器（订单生成不铸转移）。
   *
   * <p>★ 只允许在<b>协调器线程</b>调用（读活视图本身要过 owner 守卫），产物在并行阶段只读。
   *
   * <p>★★ <b>2026-10-08：改走克隆的唯一拼写点 {@link MarketRound#copyForWorker}</b>（旧版在这里手抄 22 个构造参数 ⇒ {@code
   * MarketRound} 新增的 {@code arbitrage} 字段被静默丢掉，而<b>这个克隆轮才是真正在下订单的那一份</b> ⇒
   * 套利一次都不生效且无任何报错）。语义与旧版唯一差异：{@code regulation} 由写死的 {@code none()} 改为<b>原样带过</b> ——
   * 逐调用点审计过，该值在克隆轮上没有读取点（P-T1c 起订单生成只读逐格价表，完全不读调控）。
   */
  private static MarketRound readOnlyPlanningRound(MarketRound round) {
    return round.copyForWorker(
        new LinkedHashMap<>(round.householdGoods),
        new LinkedHashMap<>(round.householdMoney),
        new LinkedHashMap<>(round.householdFrozenGoods),
        new LinkedHashMap<>(round.householdFrozenMoney),
        new LinkedHashMap<>(round.unmetToday),
        new ProductionLedger.Accumulator(round.day));
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
      MarketUnfilledReason blocked,
      // ★★ M-A1：被运力截断量必须随状态带回协调器（否则并行路径下 V-20 静默失效：协调器侧恒 0）。
      long capacityTruncatedMilli) {}

  /** worker 交回的卖槽终态。 */
  private record SellSlotState(
      int orderIndex,
      long remaining,
      long frozenRemaining,
      MarketUnfilledReason blocked,
      boolean capacityBlocked,
      long capacityTruncatedMilli) {}

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
   *
   * <p>★★ <b>P-T1d：顺序来自"服务序"（卖侧 = 成本序或置顶名次，买侧 = 列表序或置顶名次）</b>， 而"档"=
   * <b>相邻且同成本、同置顶状态</b>的连续段；买侧按"档"（相邻同置顶状态的连续段）逐段配给。 <b>没有</b>政府要求管控的世界：卖侧服务序 == 成本序、买侧只有一段 ==
   * 改前的唯一一组 ⇒ 逐值不变（I-C2）。
   */
  private static void matchGroup(
      MatchContext ctx, List<BuySlot> buys, List<SellSlot> sells, long price, RouteContext route) {
    if (buys.isEmpty() || sells.isEmpty()) {
      return;
    }
    List<BuySlot> orderedBuys = procurementOrderedBuys(ctx, buys);
    List<List<BuySlot>> buyRuns = procurementBuyRuns(orderedBuys);
    List<SellSlot> ordered = new ArrayList<>(sells);
    ordered.sort(servingOrder(ctx, route));
    int tierStart = 0;
    while (tierStart < ordered.size()) {
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
      // ★ 需求按**当前剩余**重算：上一层吃掉的量不再计入（"最低成本层优先"因此是逐层的，不是一次性预分配）。
      //   ★ P-T1d：逐"买档"再重算一次（缺省只有一段 ⇒ 与改前逐值相同）。
      for (List<BuySlot> run : buyRuns) {
        long[] weights = new long[run.size()];
        long demand = 0L;
        for (int i = 0; i < run.size(); i++) {
          // ★★ P-T5b：本档的价格 `price` 是**本格计价币**的参考价，而 affordableQuantity 收的是**买方支付币**单价
          //   （3c 起其余三处调用点传的都是折过的单价）⇒ 这里补上同一次折算（同币 ⇒ 原样，逐值不变）。
          //   ★ 说不出这种钱的价（-1）⇒ 本档买不起（不得让它落进"完全免费"那条分支）。
          long buyerPrice = run.get(i).payAmountOf(price);
          long affordable =
              buyerPrice < 0L ? 0L : affordableQuantity(ctx, run.get(i), buyerPrice, route);
          weights[i] = Math.min(run.get(i).remaining, affordable);
          demand += weights[i];
        }
        if (demand <= 0L) {
          continue; // 这一段没有可付需求 ⇒ 看下一段（缺省只有一段 ⇒ 与改前的"直接返回"逐值等价：后面的层也不再成交）
        }
        long supply = 0L;
        long[] sellWeights = new long[tier.size()];
        for (int i = 0; i < tier.size(); i++) {
          sellWeights[i] = tier.get(i).remaining;
          supply += sellWeights[i];
        }
        if (supply <= 0L) {
          break; // 这一档已被前面的买段吃光 ⇒ 后面的买段也卖不动
        }
        long matched = Math.min(demand, supply);
        if (matched > 0L) {
          long[] buyParts = ProportionalSplit.byDenominator(matched, weights, demand);
          long[] sellParts = ProportionalSplit.byDenominator(matched, sellWeights, supply);
          pairUp(ctx, run, buyParts, tier, sellParts, price, route);
        }
      }
      tierStart = tierEnd;
    }
  }

  /**
   * ★ P-T1d：<b>卖侧服务序</b>—— 没有政府要求管控市场时 = 既有 {@link #costOrder(RouteContext)}（逐值不变）； 有管控时 = 置顶 pass
   * 写定的全序名次（成本序 + 政府单的推进）。
   */
  private static Comparator<SellSlot> servingOrder(MatchContext ctx, RouteContext route) {
    if (!ctx.round.procurementPriority().isActive()) {
      return costOrder(route);
    }
    return Comparator.comparingLong(sell -> sell.procurementRank);
  }

  /**
   * ★ P-T1d：<b>买侧服务序</b>—— 没有政府要求管控市场时<b>原样返回同一张表</b>（不复制、不排序 ⇒ 逐值不变）； 有管控时按置顶名次稳定排序（名次是全序 ⇒ 结果唯一）。
   */
  private static List<BuySlot> procurementOrderedBuys(MatchContext ctx, List<BuySlot> buys) {
    if (!ctx.round.procurementPriority().isActive()) {
      return buys;
    }
    List<BuySlot> ordered = new ArrayList<>(buys);
    ordered.sort(Comparator.comparingLong(buy -> buy.procurementRank));
    return ordered;
  }

  /**
   * ★ P-T1d：<b>买侧"档"</b> = 相邻且 {@link BuySlot#procurementOvertook} 相同的连续段。
   *
   * <p>★ 为什么需要它：需求侧原本是**唯一一组**按权重比例分配（{@link ProportionalSplit}），"排在前面"只有在
   * <b>分组</b>上才看得见（同一组内人人按比例拿，与次序无关）⇒ 被置顶的政府单自成一段、先配给，剩下才是众家户那一组。 ★ 缺省（没有管控）⇒ 全部 {@code false} ⇒
   * 恰好一段 ⇒ 与改前**逐值相同**。
   */
  private static List<List<BuySlot>> procurementBuyRuns(List<BuySlot> orderedBuys) {
    List<List<BuySlot>> runs = new ArrayList<>();
    int start = 0;
    while (start < orderedBuys.size()) {
      boolean overtook = orderedBuys.get(start).procurementOvertook;
      int end = start + 1;
      while (end < orderedBuys.size() && orderedBuys.get(end).procurementOvertook == overtook) {
        end++;
      }
      runs.add(new ArrayList<>(orderedBuys.subList(start, end)));
      start = end;
    }
    return runs;
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

  /**
   * ★★ <b>跨区候选装配与撮合（P1.2 索引；协调器单线程）</b>；★ <b>P-T1a 起在这里做"两侧两道闸"的口岸节流</b>。
   *
   * <pre>
   * 逐（源区 A → 目的区 B × 商品 c）算一条<b>共享预算</b>（本轮一次性，见 {@link #buildPortBudgets}）：
   *   E_源 = A 的出口开放度（{@link PortDirection#EXIT}）；E_目的 = B 的入口开放度（{@link PortDirection#ENTRY}）
   *   可通过量 = ⌊ A 该商品想跨出的量 × E_源 × E_目的 ÷ 1,000,000 ⌋      （{@link PortThrottle} 是唯一拼写点）
   * 本轮的每一笔 (A,B,c) 跨区成交都从这条预算里扣，扣完即闸闭（该配对零候选）
   * </pre>
   *
   * <p>★★ <b>为什么是"逐区对共享预算"而不是"逐格对各自乘一遍"</b>：闸管的是<b>一条边界上的流量</b>（"一批货要过境， 两边都要过"，§12.2）。若按 (买方格,
   * 卖方格) 各自乘一遍，同一批货会被同一道闸按剩余量<b>重复打折</b> （A 有 100、B 两个买方格各要 100、两侧各 500‰ ⇒ 逐格 50+25=75 过闸，而正确是
   * 100×50%=50）—— 同一条形制是<b>区对级共享预算 + 逐笔按真正落账的量扣</b>（本轮的口岸闸预算表就是它的唯一实例）。
   *
   * <p>★★ <b>三条边界（设计书 §10/§11/§12，逐条）</b>：
   *
   * <ol>
   *   <li><b>只作用在跨区候选上</b>：本区自产的货没跨边界 ⇒ 区内撮合（{@code matchWithinRegions}）一个字不改
   *       ——"区内禁售"是市场管制、不是口岸（§10.3）；
   *   <li><b>不删卖单本身</b>：卖方槽位原样留着（本区买家还看得见它）；被拦下的量只是<b>进不了跨区候选</b> ——通过"预算上限"兑现，不删槽位（§10.3 的精度要求）；
   *   <li><b>不额外记录"走私"</b>：被拦下的量不记账、不落状态，只留具名归因（{@link MarketUnfilledReason#PORT_THROTTLED}）
   *       与日志（§11：没管住的那一份就是流入市场的正常供给，凭啥还要额外记录）。
   * </ol>
   *
   * <p>★★ <b>缺省语义中性（I-P8）</b>：{@code portEnforcement.isActive() == false}（未注入/空表）或该类两侧全开 ⇒
   * 预算表里<b>没有条目</b> ⇒ 上限取 {@link PortThrottle#NO_GATE_MILLI} ⇒ 撮合的 {@code Math.min} 逐值等于不加这一项 ⇒
   * 旧世界逐值不变。
   *
   * <p>★★ <b>区键口径（P-T1a 必核的接缝，fail-closed 不静默）</b>：注入表的区键 = 组合根 {@code PortExposureEdges} 的 {@code
   * MarketZone.zoneId().value()}；本处的区 id = {@code
   * topology.regionOf(hex).node().nodeId()}。两者<b>必须同一套键</b>： 有持久市场区时，组合根 {@code
   * MarketTopologyBook.byPersistentZones} 正是拿 {@code zone.zoneId().value()} 当 {@code
   * MarketNode.nodeId}（{@code MarketTopologyBook:236-241}）⇒ 逐值相同；<b>没有持久区 ⇒ 暴露边为空 ⇒ 注入表为空 ⇒
   * 根本走不到这里</b>。若将来有一处改了键，本方法会<b>具名 ERROR + fail-closed</b>（{@link
   * #requirePortZoneKeysAligned}），不让"政策设了却一点作用没有"静默发生。
   */
  private static void matchAcrossRegions(MatchContext ctx, MarketIndexes indexes) {
    if (!ctx.topology.regional()) {
      return;
    }
    PortEnforcementInput port = ctx.round.portEnforcement();
    if (port.isActive()) {
      requirePortZoneKeysAligned(ctx, port);
    }
    // ★★ P-T1a：逐（源区 → 目的区 × 商品）的共享闸预算（本轮一次性算好；无口岸面 ⇒ 空表 ⇒ 逐值不变）。
    Map<String, Long> portBudgetsLeft = buildPortBudgets(ctx, indexes, port);
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
          // ★★ M-A1：跨区车道的运力 = **发货格**（sellerHex）的逐 hex 运力池。该格没有"选了跑商的家户" ⇒ 这条
          //   车道根本不建（货走不动，具名 LOGISTICS_CAPACITY）；被截断量记进两个槽位（V-20）。
          if (!ctx.carrierPool.hasCapacityAt(sellerHex)) {
            blockLaneWithoutCapacity(ctx, buys, sells, sellerHex, buyerHex, commodity);
            continue;
          }
          String budgetKey =
              portLaneKey(sellerRegion.node().nodeId(), buyerRegion.node().nodeId(), commodity);
          long portTransitCapMilli =
              portBudgetsLeft.getOrDefault(budgetKey, PortThrottle.NO_GATE_MILLI);
          long portConsumedMilli =
              matchRoute(ctx, buyerHex, sellerHex, commodity, buys, sells, portTransitCapMilli);
          if (portTransitCapMilli != PortThrottle.NO_GATE_MILLI) {
            // ★ 共享预算按**真正落账**的量扣（与运力/配额同一口径）；扣到 0 ⇒ 这条区对的后续配对零候选。
            portBudgetsLeft.put(budgetKey, portTransitCapMilli - portConsumedMilli);
          }
          // 这一对买卖里买家已经满足 / 钱包已耗尽的都不必再看别的卖方路线。
          buys.removeIf(buy -> buy.remaining <= 0 || buy.noMoney);
          if (buys.isEmpty()) {
            break;
          }
        }
      }
    }
  }

  /** 一条区对闸预算的键（源区 → 目的区 × 商品；只在本方法族内用）。 */
  private static String portLaneKey(
      String sellerZoneId, String buyerZoneId, CommodityId commodity) {
    return sellerZoneId + "->" + buyerZoneId + "#" + commodity.value();
  }

  /**
   * ★★ <b>P-T1a：本轮逐（源区 → 目的区 × 商品）的口岸闸预算（毫商品）</b>——{@link PortThrottle} 的唯一调用点。
   *
   * <pre>
   * 想跨出的量 transit = 源区该商品的卖方剩余合计（区内撮合之后 ⇒ 剩下的才是"想跨出去"的）
   * E_源   = 源区该商品 EXIT 开放度（出口规则 × 该侧口岸效率）
   * E_目的 = 目的区该商品 ENTRY 开放度（入口规则 × 该侧口岸效率）
   * 预算   = ⌊transit × E_源 × E_目的 ÷ 1e6⌋
   * </pre>
   *
   * ★ <b>只在"有活的买方 + 有卖方"的区对上建条目</b>（可达集合与配对循环同源：买方的相邻区集合 × 有活跃买单的商品），
   * 于是日志里的"被闸拦下的车道数/量"不会把"没有买家的边界"也算进去。★ <b>两侧全开（各 1000‰）⇒ 不建条目</b> （=
   * 不设限的类/区不加闸：既保证逐值不变，也省掉逐车道乘法）。★ <b>任一侧全关（E = 0）⇒ 预算 0</b> ⇒ 该区对该商品本轮 <b>零候选</b>（判据
   * ②）；槽位仍在，本区买家与区内成交不受影响。
   *
   * <p>★ <b>确定性（I7）</b>：遍历序 = 拓扑区序 → 邻区 id 升序 → 商品序（{@code indexes.commodities}）， 与配对循环的先后无关 ⇒
   * 预算与遍历史无关（同一 revision 两跑逐值相同）。
   */
  private static Map<String, Long> buildPortBudgets(
      MatchContext ctx, MarketIndexes indexes, PortEnforcementInput port) {
    Map<String, Long> budgets = new LinkedHashMap<>();
    if (!port.isActive()) {
      return budgets;
    }
    for (MarketRegion buyerRegion : ctx.topology.regions()) {
      String buyerZoneId = buyerRegion.node().nodeId();
      Set<String> adjacent = indexes.adjacentRegionIds.getOrDefault(buyerZoneId, Set.of());
      if (adjacent.isEmpty()) {
        continue;
      }
      List<String> sellerZoneIds = new ArrayList<>(adjacent);
      sellerZoneIds.sort(Comparator.naturalOrder());
      for (String sellerZoneId : sellerZoneIds) {
        for (CommodityId commodity : indexes.commodities) {
          List<SellSlot> supply =
              indexes
                  .sellsByRegionCommodity
                  .getOrDefault(sellerZoneId, Map.of())
                  .getOrDefault(commodity, List.of());
          if (supply.isEmpty() || !hasActiveBuyInRegion(indexes, buyerZoneId, commodity)) {
            continue;
          }
          long transit = remainingOfSells(supply);
          if (transit <= 0L) {
            continue;
          }
          long eSource =
              port.commodityOpennessPerMille(sellerZoneId, commodity, PortDirection.EXIT);
          long eDestination =
              port.commodityOpennessPerMille(buyerZoneId, commodity, PortDirection.ENTRY);
          if (PortThrottle.bothFullyOpen(eSource, eDestination)) {
            // ★ 两侧都不设限（这一类在这个区对里没政策）⇒ 无闸：逐值等于改前行为（I-P8 的逐区对形态）。
            continue;
          }
          long allowed = PortThrottle.allowedTransitMilli(transit, eSource, eDestination);
          budgets.put(portLaneKey(sellerZoneId, buyerZoneId, commodity), allowed);
          if (allowed < transit) {
            ctx.portGatedPairs++;
            ctx.portBlockedMilli =
                Math.addExact(
                    ctx.portBlockedMilli, PortThrottle.blockedTransitMilli(transit, allowed));
            logPortLaneGated(
                ctx, commodity, sellerZoneId, buyerZoneId, transit, allowed, eSource, eDestination);
          } else if (MARKET.isTraceEnabled()) {
            logPortLaneEvaluated(
                ctx, commodity, sellerZoneId, buyerZoneId, transit, allowed, eSource, eDestination);
          }
        }
      }
    }
    return budgets;
  }

  /** 目的区该商品有没有"还活着"的买方（与 {@link #hasActiveBuyAtHex} 同一判据，粒度换成区）。 */
  private static boolean hasActiveBuyInRegion(
      MarketIndexes indexes, String zoneId, CommodityId commodity) {
    for (BuySlot buy :
        indexes
            .buysByRegionCommodity
            .getOrDefault(zoneId, Map.of())
            .getOrDefault(commodity, List.of())) {
      if (buy.remaining > 0 && !buy.noMoney) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ <b>P-T1a 区键口径核对（fail-closed）</b>：注入表的区键必须至少命中一个本轮拓扑的区 id。
   *
   * <p>★ <b>为什么"至少一个"就够</b>：注入表非空 ⇒ 至少有一个区的管制力 &gt; 0 ⇒ 那个区来自 {@code
   * EconomyData.marketZones}（{@code PortRegimeBridge} 的定义域）⇒ 它必然也被 {@code
   * MarketTopologyBook.byPersistentZones} 建成 {@code nodeId = zoneId} 的区。命中 0 个 ⇒ <b>两处用的不是同一套键</b>
   * （或注入的表来自另一个 revision）⇒ 若不判，政策会静默失效——这正是本仓最贵的一类故障。
   */
  private static void requirePortZoneKeysAligned(MatchContext ctx, PortEnforcementInput port) {
    Set<String> portZones = port.zoneIds();
    for (MarketRegion region : ctx.topology.regions()) {
      if (portZones.contains(region.node().nodeId())) {
        return;
      }
    }
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_PORT_ZONE_KEY_CONTRACT",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "reason",
                "port-enforcement-zone-keys-match-no-market-region",
                "portZones",
                portZones.size(),
                "firstPortZone",
                portZones.isEmpty() ? "-" : portZones.iterator().next(),
                "marketRegions",
                ctx.topology.regions().size(),
                "firstMarketRegion",
                ctx.topology.regions().get(0).node().nodeId()));
    throw new IllegalStateException(
        "口岸注入表的区键与本轮市场拓扑的区 id 不是同一套键（fail-closed：政策会静默失效）: portZones="
            + portZones.size()
            + " / marketRegions="
            + ctx.topology.regions().size());
  }

  /**
   * ★★ <b>P-T1b 税表区键口径核对（fail-closed）</b>：{@link #requirePortZoneKeysAligned} 的同一条契约 —— 税表非空
   * 却一个区键都命中不了本轮拓扑的区 id ⇒ 三层税<b>静默一分不收</b>（政策设了却毫无作用），必须当场炸。
   *
   * <p>★ 为什么"至少命中一个"就够：税表的区键来自组合根的 {@code MarketZoneBook.zones(economy)}，与本轮拓扑同源； 命中 0
   * 个只可能是两处键分叉（或注入值来自另一个 revision）。
   */
  private static void requirePortTaxZoneKeysAligned(MatchContext ctx) {
    Set<String> taxZones = ctx.round.portTax().zoneIds();
    for (MarketRegion region : ctx.topology.regions()) {
      if (taxZones.contains(region.node().nodeId())) {
        return;
      }
    }
    EventLog.channel(MARKET)
        .error(
            LogEvent.of(
                "MARKET_PORT_TAX_ZONE_KEY_CONTRACT",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "reason",
                "port-tax-zone-keys-match-no-market-region",
                "taxZones",
                taxZones.size(),
                "firstTaxZone",
                taxZones.isEmpty() ? "-" : taxZones.iterator().next(),
                "marketRegions",
                ctx.topology.regions().size(),
                "firstMarketRegion",
                ctx.topology.regions().get(0).node().nodeId()));
    throw new IllegalStateException(
        "三层税注入表的区键与本轮市场拓扑的区 id 不是同一套键（fail-closed：税会静默一分不收）: taxZones="
            + taxZones.size()
            + " / marketRegions="
            + ctx.topology.regions().size());
  }

  /** DEBUG 一条：这条车道被口岸闸节流了（判据 = 可通过量 < 想跨区的量；含两道闸的逐侧读数）。 */
  private static void logPortLaneGated(
      MatchContext ctx,
      CommodityId commodity,
      String sellerZoneId,
      String buyerZoneId,
      long transit,
      long allowed,
      long eSource,
      long eDestination) {
    if (!MARKET.isDebugEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_PORT_LANE_GATED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "sellerZone",
                sellerZoneId,
                "buyerZone",
                buyerZoneId,
                "transit",
                transit,
                "allowedTransit",
                allowed,
                "blockedTransit",
                transit - allowed,
                "sourceExitOpennessPerMille",
                eSource,
                "destinationEntryOpennessPerMille",
                eDestination,
                "reason",
                "two-sided-port-gate"));
  }

  /** TRACE 一条：这条车道过了闸（两侧读数照记；默认关，逐车道的"为什么没被拦"用它核）。 */
  private static void logPortLaneEvaluated(
      MatchContext ctx,
      CommodityId commodity,
      String sellerZoneId,
      String buyerZoneId,
      long transit,
      long allowed,
      long eSource,
      long eDestination) {
    EventLog.channel(MARKET)
        .trace(
            LogEvent.of(
                "MARKET_PORT_LANE_EVALUATED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "sellerZone",
                sellerZoneId,
                "buyerZone",
                buyerZoneId,
                "transit",
                transit,
                "allowedTransit",
                allowed,
                "sourceExitOpennessPerMille",
                eSource,
                "destinationEntryOpennessPerMille",
                eDestination));
  }

  /**
   * DEBUG 一条：这条车道的口岸闸<b>用尽</b>（还有合格需求/余货，但闸不让过了）——与预算/限价/时限/运力四类原因分开， 便于"为什么这批货没跨区"一眼看到是政策而不是别的。
   */
  private static void logPortRouteExhausted(
      MatchContext ctx,
      CommodityId commodity,
      HexCoord sellerHex,
      HexCoord buyerHex,
      int buys,
      int sells) {
    if (!MARKET.isDebugEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_PORT_ROUTE_EXHAUSTED",
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
                buys,
                "sells",
                sells,
                "reason",
                "port-transit-cap-exhausted"));
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
  private static long matchRoute(
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
        long affordable = affordableQuantity(ctx, buy, buyerUnitPrice, route);
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
            long tierBuyerPrice = worstBuyerUnitPrice(ctx, run.get(i), tier, unitPrice);
            long affordable =
                tierBuyerPrice < 0L
                    ? 0L
                    : affordableQuantity(ctx, run.get(i), tierBuyerPrice, route);
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
            long[] buyParts = ProportionalSplit.byDenominator(matched, tierBuyWeights, tierDemand);
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
  private static RouteContext intraRegionFreightRoute(
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
        if (sell.seller.actor.equals(buy.buyer.actor)) {
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
                      buy.buyer.actor.id(),
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
                        buy.buyer.actor.id(),
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
          continue;
        }
        // ★★ M-A1：跨格（同市场区、不同 hex）的成交必须由**发货格**的运力池承运 ⇒ 合成一条"即时但有运费"的路线；
        //   同 hex 仍走零运费即时成交（route == null，不受运力影响）。
        //   ★ 发货格**没有**运力（没有"选了跑商的家户"）⇒ 这条路线根本不建：本笔具名拦下（LOGISTICS_CAPACITY），
        //     货不走、"被截断量"记进两个槽位（V-20）。把判据放在候选生成处（而不是只靠 executeTrade）是**必需**的：
        //     并行回放路径不认 executed=0（见 RegionClone/prepareRegion 的注释）。
        RouteContext effectiveRoute = route;
        if (route == null && !buy.hex.equals(sell.hex)) {
          if (!ctx.carrierPool.hasCapacityAt(sell.hex)) {
            blockLaneWithoutCapacity(
                ctx, List.of(buy), List.of(sell), sell.hex, buy.hex, buy.order.commodity());
            break;
          }
          effectiveRoute = intraRegionFreightRoute(ctx, buy, sell, price);
        }
        boolean freeTicket =
            price <= 0L && (effectiveRoute == null || effectiveRoute.freightPerUnit <= 0L);
        // ★ 递归约束：上一笔实际付款（含各自 ceil 的货款与运费）已经写进 spentMilli 与余额/冻结表，
        //   这里按**当前**剩余可付重算上限，堵住 N 笔各 ceil 一毫的累计越界。先取原口径的保守配给量，
        //   再用逐笔实际算式精确封顶（跨区运费 floor + 两处 ceil 的累计误差都在这里削平）。
        long affordable = affordableQuantity(ctx, buy, buyerUnitPrice, effectiveRoute);
        if (quantity > affordable) {
          quantity = affordable;
        }
        long payable = payableMoneyOf(ctx, buy);
        quantity =
            exactAffordableUpTo(
                ctx,
                buy,
                sell,
                buy.order.commodity(),
                quantity,
                payable,
                buyerUnitPrice,
                effectiveRoute);
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
   * ★★ <b>E（2026-10-09 裁定 R1/R2/R3/R4/R6）：唯一拼写点 —— 本笔"收什么钱、按什么价收"的裁决</b>。
   *
   * <p>★★ <b>为什么必须共用一处</b>：商品面上"钱从买方到卖方"有<b>三条</b>腿 ——
   *
   * <pre>
   * ① 现金成交腿 executeTrade          ：payment 按 buy.currency 铸
   * ② 信用成交腿 moneyCreditForBuy     ：借来的钱同样按 buy.currency 付给卖方
   * ③ 借实物腿   goodsCreditForBuy     ：货腿不经货币（债务单位 = 商品）⇒ 币种维为空
   * </pre>
   *
   * <p>★★ <b>判据（逐字 = 文档 §3.1）</b>：
   *
   * <pre>
   * 买方付出金额 × 我对该币的估值 ≥ 我的保留价 × 数量   ⇒ 成交；否则"不划算"（不落任何账）
   * </pre>
   *
   * 三处的量纲与取法（全部同微刻度，见 {@link HouseholdValuationBook#MICRO_PER_MILLI}）：
   *
   * <ul>
   *   <li>"买方付出金额" = 本笔在<b>买方支付币</b>下的货款（毫），单价由"钱的价"换算： {@code 买方币单价 = ⌈卖方币单价 × 1000 ÷ 我对该币的估值⌉} ⇒
   *       估值越低、要的该币越多（E2）；
   *   <li>"我对该币的估值" = {@link CurrencyValuation#valuationMicro}（本币 1:1 / 世界行情 / 当地实际流通 ⇒ 面值 / 三者都不是
   *       ⇒ 说不出价）；
   *   <li>"我的保留价" = 卖方槽位建槽时冻结的 {@link SellSlot#reservationMicro}（与套利用的保留价同一个算式）。
   * </ul>
   *
   * <p>★★ <b>同币成交不做任何事</b>（{@code buy.currency == sell.receiveCurrency} ⇒ 直接返回原单价）：本币对自己 = 面值
   * 1:1（R3 的锚），判据恒成立 ⇒ 同币路径<b>逐值退回改前</b>（E3 / N2 是结构性保证，不是"实测碰巧一样"）。
   *
   * <p>★★ <b>删掉了什么</b>：A2a/A2b 的"币种不等 ⇒ 具名拒"（{@link MarketUnfilledReason#CURRENCY_MISMATCH}）整条语义。
   * 不划算一律走<b>既有市场性理由</b>（本批取 {@link MarketUnfilledReason#PRICE_LIMIT}：说不出这个价 / 折回本币不够
   * 保留价），<b>不新增"币种不符"语义</b>（N1/N3）。47.9 万条 {@code MARKET_CURRENCY_MISMATCH_REJECTED} 的噪声随之消失，
   * 取而代之的是逐笔 DEBUG 的"为什么"（{@code MARKET_CURRENCY_VALUE_REFUSED}）。
   *
   * <p>★★ <b>调用方的处置（停止 / 跳过这一家）不由本方法规定</b>：现金腿是<b>跳过这一家卖方、看下一家</b>；货币信用腿是 {@code
   * break}（那一轮为这个买方定下的唯一借款链就是它）；借实物腿币种维为空（不拒）。三条腿共享的是<b>判据与日志形态</b>。
   *
   * @param quantity 本次尝试的数量（毫商品；判据按它折算"付出金额"）
   * @param unitPrice 本笔的<b>卖方币</b>单价（毫卖方币 / 商品单位）
   * @param leg 哪条腿（{@code cash} / {@code money-credit} / {@code goods-credit}）
   * @param bookRefusal true = 真的落账（置买卖两侧的市场性理由 + DEBUG why）；false = 只做纸面裁决（配对前的
   *     可负担性/限价预判，不改任何状态、不写日志）
   * @return {@code >= 0} = 以买方支付币计价的单价（{@code 0} = 合法的 0 价/免费）；{@code -1} = 不划算 （调用方不得落账）
   */
  private static long settlementUnitPrice(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      String leg,
      boolean bookRefusal) {
    if (LEG_GOODS_CREDIT.equals(leg)) {
      // ★ 借实物腿不经货币（债务单位 = 商品）：币种维为空 ⇒ 判据落在既有的"可借余量"上，本方法不新增门槛。
      return unitPrice;
    }
    // ★★ 3c（V-1）+ P-T1e：候选成立条件的第一项 —— **买方支付币过得了"币种挂单闸"**（卖方接受集 ∩ 两侧口岸规则）。
    //   不给过 ⇒ 候选不成立：具名归因（CURRENCY_NOT_ACCEPTED）、DEBUG 一条"为什么"、返回"不划算"哨兵 ⇒ 三条腿都**不落任何账**
    //   （现金腿由调用方换下一家卖方、信用腿 break；见各自的处置注）。
    //   ★ 判定内容<b>只</b>在 acceptsCurrency / currencyGateBlock 一处拼写；归因、日志、不落账由这里自动生效。
    //   ★ 缺省中性（I-C2）：一条币种规则都没设 ⇒ 闸恒放行 ⇒ 逐值等于改前。
    if (!acceptsCurrency(ctx, buy, sell, buy.currency, orderKindOfLeg(leg))) {
      if (bookRefusal) {
        refuseUnacceptedCurrency(ctx, buy, sell, quantity, unitPrice, leg);
      }
      return UNPROFITABLE_CURRENCY;
    }
    if (buy.currency.equals(sell.market.numeraire())) {
      // ★ 同币：本币 1:1 ⇒ 判据恒成立 ⇒ 逐值退回改前（E3/N2 的结构性保证）。
      return unitPrice;
    }
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, unitPrice);
    if (terms == null) {
      if (bookRefusal) {
        refuseUnprofitableCurrency(
            ctx,
            buy,
            sell,
            quantity,
            unitPrice,
            0L,
            leg,
            "currency-has-no-quotation-and-does-not-circulate-locally",
            -1L);
      }
      return UNPROFITABLE_CURRENCY;
    }
    long payment = paymentForQuantity(quantity, terms.buyerUnitPrice());
    long offeredMicro = multiplyOrFail(payment, terms.valueMicro(), "买方付出金额 × 我对该币的估值");
    long requiredMicro =
        ceilDivPositive(
            multiplyOrFail(sell.reservationMicro, quantity, "我的保留价 × 数量"),
            HouseholdValuationBook.MICRO_PER_MILLI);
    if (offeredMicro < requiredMicro) {
      if (bookRefusal) {
        refuseUnprofitableCurrency(
            ctx,
            buy,
            sell,
            quantity,
            unitPrice,
            terms.valueMicro(),
            leg,
            "value-of-payment-below-my-reservation",
            requiredMicro);
      }
      return UNPROFITABLE_CURRENCY;
    }
    return terms.buyerUnitPrice();
  }

  /**
   * ★★ <b>E：不划算的哨兵</b>（{@code -1}）—— 与"合法的 0 价/免费"（{@code 0}）严格区分： 单价是毫整数、恒 {@code >= 0}，故 {@code
   * -1} 不可能是一个真价格。
   */
  private static final long UNPROFITABLE_CURRENCY = -1L;

  /** 三条腿的名字（唯一拼写点；进日志的 {@code leg} 段）。 */
  private static final String LEG_CASH = "cash";

  private static final String LEG_MONEY_CREDIT = "money-credit";

  private static final String LEG_GOODS_CREDIT = "goods-credit";

  /** ★★ E：本笔的收款裁决 —— 买方支付币单价 + 该币在卖方眼里的估值（微本币/毫买币）。 */
  private record CurrencySettlement(long buyerUnitPrice, long valueMicro, boolean sameCurrency) {}

  /**
   * ★★ <b>E：本笔的收款裁决</b>（纯函数）：同币 ⇒ 原价 + 面值；异币 ⇒ 查行情/当地流通的估值并折算买方币单价。
   *
   * @return {@code null} = 说不出这种钱的价（调用方按"不划算"落市场性理由，绝不静默按 1:1）
   */
  private static CurrencySettlement currencySettlement(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    if (buy.currency.equals(sell.market.numeraire())) {
      return new CurrencySettlement(unitPrice, CurrencyValuation.faceValueMicro(), true);
    }
    long valueMicro =
        ctx.currencyValuation()
            .valuationMicro(sell.market.numeraire(), buy.currency, sell.region.node().nodeId());
    if (valueMicro <= 0L) {
      return null;
    }
    return new CurrencySettlement(buyerUnitPriceFor(unitPrice, valueMicro), valueMicro, false);
  }

  /**
   * ★★ <b>E：以买方支付币计价的单价</b> {@code ⌈卖方币单价 × 1000 ÷ 我对该币的估值⌉}（毫买币 / 商品单位）： 估值越低 ⇒ 要的该币越多（E2）；估值 =
   * 1000（面值 1:1）⇒ 逐值等于原单价。
   */
  private static long buyerUnitPriceFor(long unitPrice, long valueMicro) {
    if (unitPrice <= 0L) {
      return 0L; // 0 价（免费）：换算后仍是 0，判据由调用方按"付出 0"判
    }
    if (valueMicro <= 0L) {
      throw new IllegalArgumentException("E 的买方币单价要求估值 > 0: " + valueMicro);
    }
    return ceilDivPositive(
        multiplyOrFail(unitPrice, HouseholdValuationBook.MICRO_PER_MILLI, "卖方币单价 × 1000"),
        valueMicro);
  }

  /**
   * ★★ <b>E：以买方支付币计价的单价（纯函数）</b>——限价过滤与可负担量预判用；不落账、不写日志。
   *
   * <p>★ {@code 0} = 说不出这种钱的价（调用方按"不划算"处理）；同币 ⇒ 原价原样返回。
   */
  private static long convertedBuyerUnitPrice(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, unitPrice);
    return terms == null ? UNPROFITABLE_CURRENCY : terms.buyerUnitPrice();
  }

  /**
   * ★★ <b>3c：一条（买方 × 一组卖方）的"买方支付币单价" —— "这个单价属于哪个币"的唯一读取口</b>。
   *
   * <p>★★ <b>为什么要有它</b>：改前 {@code matchRoute} 用一只代表槽位（{@code sells.get(0)}）承担整条路线的折算 ——
   * 那隐含"同一路线的卖方计价币一致"。订单可带币之后这条假设不再成立（计划 §2.1 明确要求放宽），
   * 于是折算改为<b>逐卖方槽位</b>做（每个槽位读它自己那张价表的计价币），本方法只负责把逐槽位结果合成一个数。
   *
   * <p>★★ <b>合成规则 = 取"对买方最不利"（折算后单价最高）的那一档</b>：
   *
   * <ul>
   *   <li>它是<b>预判</b>（限价过滤 + 可负担量的配给权重），不是落账判据：逐笔真判在 {@link #pairUp} / {@link #executeTrade}
   *       里按<b>当时那一个</b>卖方槽位重算（同一个拼写点 {@link #currencySettlement}）⇒ 预判绝不可能放行一笔付不起的成交；
   *   <li>取最不利 = 与既有 {@link #affordableQuantity} 的"保守少买"同一取向（宁可少配、不可多配）；
   *   <li><b>说不出价的槽位不参与</b>（{@code -1}）：那种币对这一买方根本不可成交，把它当"最高价"会把整条路线判死 —— 全组都说不出来时才返回 {@code -1}（=
   *       这条路线对这个买方不可成交）。
   * </ul>
   *
   * <p>★★ <b>缺省语义中性（I-C2）</b>：同一条路线上的卖方同格（{@code activeSellsAtHex} 只从<b>一个</b> hex 取槽位） ⇒ 同一张价表 ⇒
   * 逐槽位结果逐值相同 ⇒ 取最不利 = 取任一个 = 改前 {@code sells.get(0)} 的结果。<b>旧世界逐值不变</b>。
   *
   * <p>★ <b>已知取舍（如实记，留给 P-T1e/P-T5）</b>：真出现"同一路线混合币"的卖方组时，取最不利可能把某买方在这一轮 记为限价不过（具名 {@link
   * MarketUnfilledReason#PRICE_LIMIT}），而它其实能与其中更便宜的卖方成交。要"逐档配对" 就把本方法改成按卖方分组各算一次（调用点两处：首轮 weights
   * 与分档循环）——这是本批刻意不展开的口径。
   */
  private static long worstBuyerUnitPrice(
      MatchContext ctx, BuySlot buy, List<SellSlot> sellers, long unitPrice) {
    long worst = UNPROFITABLE_CURRENCY;
    for (SellSlot sell : sellers) {
      long converted = convertedBuyerUnitPrice(ctx, buy, sell, unitPrice);
      if (converted < 0L) {
        continue;
      }
      worst = Math.max(worst, converted);
    }
    return worst;
  }

  /**
   * ★★ <b>3c + P-T1e：币种挂单闸 —— "这条挂单能不能用这种钱成交"的唯一拼写点</b>（计划 §2.1 的候选成立条件 / §5.2 V-1；口岸设计书 §14.3）。
   *
   * <p>★★ <b>判定内容（P-T1e 冻结口径，用户 2026-10-10）</b>：规则可以按 <b>(币种, 挂单类型, 方向)</b>
   * 禁止某一类挂单进/出<b>市场区</b>；一票要过境必须<b>两侧都过</b>（§12 的两道闸对币种同样成立）：
   *
   * <pre>
   * 钱从买方区（源区）流向卖方区（目的区）——
   *   出口闸 = 源区（买方所在区）对 (币, 类型, EXIT) 的开放度
   *   入口闸 = 目的区（卖方所在区）对 (币, 类型, ENTRY) 的开放度
   * 任一侧开放度 = 0（该侧全禁）⇒ 不给过 ⇒ 本候选不成立（具名 CURRENCY_NOT_ACCEPTED）
   * </pre>
   *
   * <p>★★ <b>三条边界（刻意的，逐条有依据，别照"看起来更严"改）</b>：
   *
   * <ol>
   *   <li><b>只作用在跨区流动上</b>：源区 == 目的区（钱没跨边界）⇒ <b>恒放行</b>。依据：设计书 §10.3「口岸限制的是市场选择…
   *       过滤要作用在<b>跨区候选</b>上：本区自产的货没跨边界，不受口岸影响（否则等于"区内禁售"，那是市场管制、不是口岸）」， 以及 §15
   *       的两层分工「口岸层跨区、市场层区内」。⇒ 同区内的借贷/买卖<b>不受</b>任何口岸币种规则影响
   *       （这也正是"本国货币对本国居民来说只能借贷"能成立的前提：本币在本区内的借贷不会被自家的口岸规则掐死）。
   *   <li><b>两侧都要过，且只有"全禁"才算不给过</b>：开放度 = 1000 − 管制力（管制力 = 按暴露边加权平均的 {@code ⌊s×e÷1000⌋}） ⇒ {@code s
   *       = 1000} 且口岸效率 &gt; 0 才是"这一侧真的一分都不放"；部分强度是"抓不严"的比例量，不构成禁令 （挂单闸是禁入/禁出，不是节流；强度进读数与日志）。★
   *       与商品维一致：{@code s = 1000} 但 {@code e = 0}（没有口岸编制） ⇒ 管不住 ⇒ 不拦（P-T1a 已裁的同一条口径："没人管也管不住"）。
   *   <li><b>读的是"参与交易的市场区"的规则，不是"币种法定区"的规则</b>（设计书 §14.3-3 的红线）：一枚币在 两个都与它无关的区之间流动时，它的法定区规则管不着 ⇒
   *       <b>不是</b>"本币不得在境外使用／不得被外国人持有"。
   * </ol>
   *
   * <p>★ <b>挂单类型从"腿"来</b>（{@link #orderKindOfLeg}）：现金成交腿 = 货↔钱（{@link MarketOrderKind#COMMODITY}）、
   * 货币信用腿 = 借贷（{@link MarketOrderKind#LENDING}）；借实物腿不经货币（本方法根本不被它调用）。
   *
   * <p>★ <b>接受集是"规则允许的币"</b>，不是"卖方声明的收款币"：{@link SellSlot#receiveCurrency}（挂单声明的收款币）
   * 今天只进日志/读数（P-T5b 起它是"卖方自己的最强持有币"）——把它当过滤器等于给<b>未设政策</b>的世界加新限制（违反 I-C2），
   * 而且"卖家只收自己最强的那种钱"这条语义从未被裁定。⇒ 本批：<b>缺口 = 政策规则</b>，声明照旧只作读数（记在账本"关键判断"）。
   *
   * <p>★ <b>缺省语义中性（I-C2）</b>：{@code portEnforcement.currencyRegimeActive() == false}（一条币种规则都没设 /
   * 未注入） ⇒ 恒真 ⇒ 旧世界逐值不变；即使表非空，缺键也读作"管制力 0 ⇒ 开放度 1000 ⇒ 放行"。
   */
  private static boolean acceptsCurrency(
      MatchContext ctx, BuySlot buy, SellSlot sell, CurrencyId payment, MarketOrderKind orderKind) {
    return currencyGateBlock(ctx, buy, sell, payment, orderKind) == null;
  }

  /**
   * ★★ <b>P-T1e：币种挂单闸的判据本体</b>（{@link #acceptsCurrency} 与 {@link #refuseUnacceptedCurrency} 共用 ——
   * 判定<b>只在这里拼写一次</b>，日志那条不另算一遍）。
   *
   * @return {@code null} = 两侧都给过；非 null = 具名拒因（哪一侧、哪个方向、管制力多少）
   */
  private static CurrencyGateBlock currencyGateBlock(
      MatchContext ctx, BuySlot buy, SellSlot sell, CurrencyId payment, MarketOrderKind orderKind) {
    PortEnforcementInput port = ctx.round.portEnforcement();
    if (port == null || !port.currencyRegimeActive()) {
      return null; // 一条币种规则都没有 ⇒ 全币接受（缺省中性，I-C2）
    }
    String sourceZone = buy.regionId; // 钱的来源区 = 买方所在区
    String destinationZone = sell.regionId; // 钱的目的区 = 卖方所在区
    if (sourceZone.equals(destinationZone)) {
      return null; // 口岸是边界闸：同区流动不受口岸影响（§10.3/§15）
    }
    long exitOpenness =
        port.currencyOpennessPerMille(sourceZone, payment, orderKind, PortDirection.EXIT);
    if (exitOpenness <= 0L) {
      return new CurrencyGateBlock(
          "source-zone-exit-closed", PortDirection.EXIT, sourceZone, exitOpenness);
    }
    long entryOpenness =
        port.currencyOpennessPerMille(destinationZone, payment, orderKind, PortDirection.ENTRY);
    if (entryOpenness <= 0L) {
      return new CurrencyGateBlock(
          "destination-zone-entry-closed", PortDirection.ENTRY, destinationZone, entryOpenness);
    }
    return null;
  }

  /**
   * ★ <b>一次币种挂单闸拒因</b>（日志 payload 用；判据与 {@link #currencyGateBlock} 逐字同源）。
   *
   * @param why 规范拒因字面量（{@code source-zone-exit-closed} / {@code destination-zone-entry-closed}）
   * @param direction 被挡住的那一侧的方向
   * @param zoneId 被挡住的那一侧的市场区（注入表的区键）
   * @param opennessPerMille 该侧开放度（判据里必为 0；记下来是为了让"为什么"可核）
   */
  private record CurrencyGateBlock(
      String why, PortDirection direction, String zoneId, long opennessPerMille) {}

  /**
   * ★ <b>挂单类型（{@link MarketOrderKind}）与"腿"的唯一映射点</b>（{@link #settlementUnitPrice} 的三条腿）。
   *
   * <p>★ 未登记的腿 ⇒ <b>fail-closed 抛出</b>（不是静默当成某一种）：腿只有三条，多一条就说明调用点与判定点漂开了。
   */
  private static MarketOrderKind orderKindOfLeg(String leg) {
    if (LEG_CASH.equals(leg)) {
      return MarketOrderKind.COMMODITY; // 货 ↔ 钱
    }
    if (LEG_MONEY_CREDIT.equals(leg)) {
      return MarketOrderKind.LENDING; // 借来的钱买货（借贷也是市场挂单）
    }
    throw new IllegalStateException("未登记的成交腿（币种挂单闸无从判定挂单类型）: " + leg);
  }

  /**
   * ★★ <b>3c + P-T1e：挂单被币种闸挡下的具名落点</b>（与 {@link #refuseUnprofitableCurrency} 同形： 市场性归因 + DEBUG
   * "为什么" + 轮级 INFO 汇总，绝不静默丢）。
   *
   * <p>★ <b>两侧都记</b>：卖方是"我的挂单在哪个区、声明收什么钱"、买方是"我拿什么钱来买、从哪个区来"，缺一边就读不出是"目的区不让进" 还是"源区不让出"。
   *
   * <p>★ <b>判据不在这里重算</b>：{@link #currencyGateBlock} 是唯一拼写点，本方法只在 DEBUG 打开时问它一次"是哪一侧挡的"
   * （纯函数、逐值可复现）；日志关闭时连这一次询问都不做（热路径上只留两个计数）。
   *
   * <p>★ <b>为什么逐笔是 DEBUG 不是 INFO</b>（§一.9 的取舍）：跨区候选逐笔尝试，逐笔 INFO 就是上一批 47.9 万条噪声的翻版 ——
   * "这一轮被挡住了多少"由轮级 INFO {@code MARKET_CURRENCY_GATE_BLOCKED} 与成交侧 INFO 承担，"哪一笔被哪条规则挡住"归 DEBUG。
   */
  private static void refuseUnacceptedCurrency(
      MatchContext ctx, BuySlot buy, SellSlot sell, long quantity, long unitPrice, String leg) {
    MarketUnfilledReason reason = MarketUnfilledReason.CURRENCY_NOT_ACCEPTED;
    if (sell.blocked == null) {
      sell.blocked = reason;
    }
    if (buy.blocked == null) {
      buy.blocked = reason;
    }
    ctx.currencyGateBlocked++;
    ctx.currencyGateBlockedMilli = Math.addExact(ctx.currencyGateBlockedMilli, quantity);
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算，也不做无谓的字段拼装
    }
    MarketOrderKind orderKind = orderKindOfLeg(leg);
    CurrencyGateBlock block = currencyGateBlock(ctx, buy, sell, buy.currency, orderKind);
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_CURRENCY_NOT_ACCEPTED",
                EconomyLogSource.ECONOMY_FX,
                "day",
                ctx.round.day,
                "leg",
                leg,
                "orderKind",
                orderKind.value(),
                "reason",
                reason.value(),
                "commodity",
                buy.order.commodity().value(),
                "buyer",
                buy.buyer.actor,
                "seller",
                sell.seller.actor,
                "buyerPays",
                buy.currency.value(),
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "sourceZone",
                buy.regionId,
                "destinationZone",
                sell.regionId,
                "blockedSide",
                block == null ? "-" : block.direction().value(),
                "blockedZone",
                block == null ? "-" : block.zoneId(),
                "blockedZoneOpennessPerMille",
                block == null ? -1L : block.opennessPerMille(),
                "sellerUnitPriceMilli",
                unitPrice,
                "requestedQuantityMilli",
                quantity,
                "why",
                block == null ? "payment-currency-blocked-by-port-rule" : block.why()));
  }

  /**
   * ★★ <b>E：不划算的落点（三条腿共用）</b>——买卖两侧各留<b>市场性理由</b>（{@link MarketUnfilledReason#PRICE_LIMIT}）+ 一条
   * DEBUG "为什么"。
   *
   * <p>★ <b>为什么是 DEBUG 不是 INFO</b>（§一.9 的取舍，理由记在这里）：异币配对数远多于成交数，逐对 INFO 就是上一批 47.9
   * 万条噪声的翻版；"这一轮发生了什么"由成交侧的 INFO 与轮汇总承担，"为什么没成"按纪律归 DEBUG。
   *
   * @param valueMicro 该币在卖方眼里的估值（{@code 0} = 说不出价）；{@code requiredMicro} 只进日志（{@code -1} = 不适用）
   */
  private static void refuseUnprofitableCurrency(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      long valueMicro,
      String leg,
      String why,
      long requiredMicro) {
    if (sell.blocked == null) {
      sell.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算，也不做无谓的字段拼装
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_CURRENCY_VALUE_REFUSED",
                EconomyLogSource.ECONOMY_FX,
                "day",
                ctx.round.day,
                "leg",
                leg,
                "reason",
                MarketUnfilledReason.PRICE_LIMIT.value(),
                "commodity",
                buy.order.commodity().value(),
                "buyer",
                buy.buyer.actor,
                "seller",
                sell.seller.actor,
                "buyerPays",
                buy.currency.value(),
                // ★★ 3c：卖方**挂单声明的**收款币（接受集）与它的**估值锚**（本格计价币 = 它自己的钱）分开记
                //   —— 改前两者恒等，混记就读不出"挂单不收支币"与"支币折不回本币"这两种不同的拒因。
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "valueMicro",
                valueMicro,
                "sellerUnitPriceMilli",
                unitPrice,
                "requestedQuantityMilli",
                quantity,
                "reservationMicro",
                sell.reservationMicro,
                "requiredMicro",
                requiredMicro,
                "why",
                why));
  }

  /** 整数乘法：溢出 ⇒ 具名失败（与既有的"估价一律不静默回退到 double"同口径）。 */
  private static long multiplyOrFail(long left, long right, String what) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "E 的估值算式整数溢出（拒绝回退到 double）: " + what + " left=" + left + " right=" + right, overflow);
    }
  }

  /**
   * ★★ <b>E：（§一.9 + 文档 §3.1）一笔异币成交的 INFO —— 哪两户、什么币、估值多少、成交比价</b>。
   *
   * <pre>
   * impliedPerMille = 买方币单价 × 1000 ÷ 卖方币单价   // "1 毫卖方币要多少毫买方币"（‰）
   * </pre>
   *
   * <p>★ 只在<b>真的成交</b>且付款为正时调用（拒绝路径见 {@link #refuseUnprofitableCurrency} 的 DEBUG）；
   * 同币成交不进这里（那是改前的既有行为，没有新信息，也不该刷日志）。
   */
  private static void logForeignCurrencyFill(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long sellerUnitPrice,
      long buyerUnitPrice,
      long payment) {
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, sellerUnitPrice);
    long valueMicro = terms == null ? 0L : terms.valueMicro();
    long impliedPerMille =
        sellerUnitPrice > 0L
            ? multiplyOrFail(buyerUnitPrice, 1000L, "买方币单价 × 1000") / sellerUnitPrice
            : 0L;
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_FOREIGN_CURRENCY_FILL",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "buyer",
                buy.buyer.actor,
                "seller",
                sell.seller.actor,
                "buyerHex",
                buy.hex,
                "sellerHex",
                sell.hex,
                "buyerPays",
                buy.currency.value(),
                // ★★ 3c：卖方挂单声明的收款币（接受集）与估值锚（本格计价币）分开记。
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "valueMicroPerMilli",
                valueMicro,
                "sellerUnitPriceMilli",
                sellerUnitPrice,
                "buyerUnitPriceMilli",
                buyerUnitPrice,
                "impliedPerMille",
                impliedPerMille,
                "quantityMilli",
                quantity,
                "paymentMilli",
                payment));
  }

  // ── P-T1b：三层税（真收款）────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P-T1b：本笔成交的三层税（唯一计税点）</b>—— 出口税（源区）/ 进口税（目的区）/ 区内市场税（本区）； 每层<b>只算一次</b>（I-C3），金额毫、币种 =
   * 买方支付币。
   *
   * <pre>
   * 跨区在途（inTransit） ：出口税 = 源区该商品 EXIT 税率；进口税 = 目的区该商品 ENTRY 税率
   * 区内即时/区内跨格     ：区内市场税 = 卖方的区级 MarketRegulation.tariffPerUnit（**既有钩子**，P-T1b 起真收）
   * </pre>
   *
   * <p>★★ <b>三层各自的收款方</b>：出口税进源区管辖政府国库、进口税进目的区管辖政府国库、区内税进本区管辖政府国库 （多政府共管一个区时按暴露边权重分摊，见 {@link
   * MarketTaxBook}）。
   *
   * <p>★★ <b>缺省语义中性（I-C2）</b>：未注入税表 / 该层税率为 0 / 该区没有政府 ⇒ 本方法返回空表 ⇒ {@code total = payment +
   * freight} 逐值等于改前。
   *
   * <p>★ 调用点<b>只有两处</b>：{@link #executeTrade}（真收）与 {@link #totalCostAtMost}（可负担的精确封顶）——
   * 同一个方法保证"判得起"与"真的扣"不可能漂开（那正是负余额/静默少买这类故障的来源）。
   */
  private static List<MarketTaxBook.Charge> taxesFor(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long buyerUnitPrice,
      boolean inTransit,
      boolean logUnvalued) {
    PortTaxInput tax = ctx.round.portTax();
    if (!tax.isActive() || quantity <= 0L) {
      return List.of();
    }
    long payment = paymentForQuantity(quantity, buyerUnitPrice);
    List<MarketTaxBook.Charge> charges = new ArrayList<>(2);
    if (inTransit) {
      // ① 出口税：源区（卖方所在区）管辖政府收；② 进口税：目的区（买方所在区）管辖政府收。
      collectPortLayer(
          ctx,
          charges,
          buy,
          commodity,
          sell.regionId,
          PortDirection.EXIT,
          MarketTaxLayer.PORT_EXIT,
          quantity,
          payment,
          logUnvalued);
      collectPortLayer(
          ctx,
          charges,
          buy,
          commodity,
          buy.regionId,
          PortDirection.ENTRY,
          MarketTaxLayer.PORT_ENTRY,
          quantity,
          payment,
          logUnvalued);
      return charges;
    }
    // ③ 区内市场税：既有 MarketRegulation.tariffPerUnit（毫该区法定币 / 商品单位）—— 照旧只按卖方所在区取；
    //    收款方 = 该区的管辖政府（没有政府 ⇒ 收 0；与"无政府 ⇒ 该侧税 = 0"同源）。
    long inZonePerUnit = ctx.tariffPerUnitOf(sell.regionId, commodity);
    if (inZonePerUnit <= 0L) {
      return charges;
    }
    collectLayer(
        ctx,
        charges,
        buy,
        commodity,
        sell.regionId,
        MarketTaxLayer.IN_ZONE_MARKET,
        inZonePerUnit,
        0L,
        quantity,
        payment,
        logUnvalued);
    return charges;
  }

  /** 口岸某一侧的税（税率从注入表按 (区 × 商品 × 方向) 取）。 */
  private static void collectPortLayer(
      MatchContext ctx,
      List<MarketTaxBook.Charge> charges,
      BuySlot buy,
      CommodityId commodity,
      String zoneId,
      PortDirection direction,
      MarketTaxLayer layer,
      long quantity,
      long payment,
      boolean logUnvalued) {
    PortTaxInput tax = ctx.round.portTax();
    collectLayer(
        ctx,
        charges,
        buy,
        commodity,
        zoneId,
        layer,
        tax.perUnitMilli(zoneId, commodity, direction),
        tax.adValoremPerMille(zoneId, commodity, direction),
        quantity,
        payment,
        logUnvalued);
  }

  /** 计一层税并把结果并进 {@code charges}（说不出法定币的价 ⇒ 本层不收 + 具名记录，绝不静默按 1:1 猜）。 */
  private static void collectLayer(
      MatchContext ctx,
      List<MarketTaxBook.Charge> charges,
      BuySlot buy,
      CommodityId commodity,
      String zoneId,
      MarketTaxLayer layer,
      long perUnitMilli,
      long adValoremPerMille,
      long quantity,
      long payment,
      boolean logUnvalued) {
    if (perUnitMilli == 0L && adValoremPerMille == 0L) {
      return;
    }
    MarketTaxBook.Assessment assessment =
        MarketTaxBook.assess(
            layer,
            ctx.round.portTax().tableOf(zoneId),
            ctx.currencyValuation(),
            buy.currency,
            buy.regionId,
            buy.buyer.actor,
            quantity,
            payment,
            perUnitMilli,
            adValoremPerMille,
            zoneId);
    if (assessment.unvalued()) {
      // ★ 业务路径上的具名缺口（INFO，不降级为静默 0）：法定币在买方这一侧既无报价也不流通 ⇒ 说不出价。
      //   ★ `logUnvalued == false` = 可负担预判那条路（{@link #totalCostAtMost} 的二分里会被问很多次）⇒
      //     只算不记：日志只由**真的落账**那一处（{@link #executeTrade}）发一条，不刷屏、也不重复。
      if (!logUnvalued) {
        return;
      }
      //   ★ 为什么不是 fail-closed 拒绝成交：设计书 §13.2-4 明写"税不影响能不能过"（那是闸的事）。
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_TAX_UNVALUED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "layer",
                  layer.value(),
                  "zone",
                  zoneId,
                  "commodity",
                  commodity.value(),
                  "buyerPays",
                  buy.currency.value(),
                  "ratePerUnitMilli",
                  perUnitMilli,
                  "rateAdValoremPerMille",
                  adValoremPerMille,
                  "collected",
                  false,
                  "reason",
                  "legal-tender-has-no-quotation-and-does-not-circulate-locally"));
      return;
    }
    charges.addAll(assessment.charges());
  }

  /** 层 → 转移原因（唯一拼写点；三档各自具名，读账时分得出被抽的是哪一层）。 */
  private static TransferReason taxReasonOf(MarketTaxLayer layer) {
    return switch (layer) {
      case PORT_EXIT -> TransferReason.PORT_TAX_EXIT;
      case PORT_ENTRY -> TransferReason.PORT_TAX_ENTRY;
      case IN_ZONE_MARKET -> TransferReason.MARKET_TAX_IN_ZONE;
    };
  }

  /** TRACE 一条：逐笔逐层的税额与币种（§一.9 的逐笔档；默认关，排查"这笔怎么被抽了这么多"用它）。 */
  private static void logTaxCharged(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      MarketTaxBook.Charge charge,
      long quantity,
      long payment) {
    if (!MARKET.isTraceEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .trace(
            LogEvent.of(
                "MARKET_TAX_LAYER_CHARGED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "layer",
                charge.layer().value(),
                "zone",
                charge.zone(),
                "government",
                charge.governmentId(),
                "treasury",
                charge.treasury().id(),
                "currency",
                charge.currency().value(),
                "amountMilli",
                charge.amountMilli(),
                "commodity",
                commodity.value(),
                "quantityMilli",
                quantity,
                "paymentMilli",
                payment,
                "buyer",
                buy.buyer.actor.id(),
                "seller",
                sell.seller.actor.id()));
  }

  /**
   * ★ <b>P-T5b：买方支付币说不出价、连运费都算不出来 ⇒ 本笔不成交 + 具名归因（DEBUG）</b>。
   *
   * <p>★ 正常路径上不可达（{@code totalCostAtMost} 已先按同一个 {@link BuySlot#payAmountOf} 把这种路线判成"付不起"）；
   * 它在这里是<b>第二道 fail-closed</b>：万一预判被绕过，也绝不把本格计价币的运费当成支付币的运费去铸腿。
   */
  private static void logFreightCurrencyUnvalued(
      MatchContext ctx, BuySlot buy, SellSlot sell, RouteContext route) {
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_FREIGHT_CURRENCY_UNVALUED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "buyer",
                buy.buyer.actor,
                "seller",
                sell.seller.actor,
                "commodity",
                buy.order.commodity().value(),
                "buyerPays",
                buy.currency.value(),
                "lane",
                route.from + "->" + route.to,
                "freightPerUnitMilli",
                route.freightPerUnit,
                "reason",
                "pay-currency-has-no-quotation-and-does-not-circulate-locally"));
  }

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
  private static long executeTrade(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      RouteContext route) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
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
    boolean inTransit = route != null && !route.immediate;
    HexCoord location = inTransit ? route.from : sell.hex;

    // ── ① 承运选择（跨格才需要）：容量不足时把成交收缩到实际可承运量 ─────────────────────────
    long executed = quantity;
    List<FreightCharge> freightCharges = new ArrayList<>();
    long freight = 0L;
    long uncollectedFreight = 0L;
    // ★★ M-C：本笔的承运分配（route != null 才有）—— 工具消耗 / 劳动成本 / 免运费读数的唯一凭据。
    MerchantCapacityPool.CarrierAllocation allocation = null;
    // ★★ P-T5b：本笔的**单位运费（毫买方支付币 / 商品单位）**—— 全方法（以及 Fill 的读数）共用这一个折算值。
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit);
    if (route != null) {
      // ★★ P-T5b：单位运费从**本格计价币**折成**买方支付币**（运费腿铸在 buy.currency 上，见 P-T4 的
      //   Fill.freightCurrency ≡ paymentCurrency）—— 与 totalCostAtMost 走同一个 payAmountOf（唯一拼写点）
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
              commodityFreightBaseMilli(ctx.topology, route.commodity), route.freightRatePerMille);
      // ★★ M-A1/M-A2/M-C：跨格承运的唯一判据 —— 按**发货格**的逐 hex 运力池分配（报价口径 = 从最低限价起买；
      //   缺省口径 = 提供方市场议价权序）；分配多少才走多少，一点运力都没有 ⇒ 本笔不成交（绝不发"免费"的跨格货）。
      //   ★ M-C：**缺工具 ⇒ 该次跑商不成立**（H-5）也落在 select 里（具名 tool-short），因此"分不到"同样收缩成交。
      //   CARRIER_FEE 收款人 = 提供运力的**家户**（纯商号免运费，见 carrierChargeSplit）。
      // ★★ 2026-10-10（池侧读数去重）：把**请求身份**一并交给池 —— 它与 D-1b 槽侧认领（{@link LaneUnservedBook#claimPair}）
      //   **同键同拼写**（车道键 + 买槽序 > 卖槽序）。★ 它只被池侧 {@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的净额字段用：
      //   本笔的承运分配、成交量、价格、账目一字不动（池那边也只写它自己的观察簿，不碰任何判据）。
      String requestKey =
          LaneUnservedBook.pairKey(
              LaneUnservedBook.laneKey(route.from, route.to, route.commodity),
              buy.orderIndex,
              sell.orderIndex);
      allocation =
          ctx.carrierPool.select(route.from, route.to, quantity, workPerGoodPerMille, requestKey);
      long allocated = allocation.allocatedMilli();
      if (allocated <= 0L) {
        // ★★ M-A2：全被拦下的那部分同样是"这一份需求要运力但没买到" ⇒ 记进需求簿（供 K-4 的缺口归因）。
        ctx.capacityDemands.record(
            buy.buyer.household,
            buy.hex,
            buy.regionId,
            route.commodity,
            route.from,
            quantity,
            0L,
            workPerGoodPerMille);
        markCapacityBlocked(ctx, buy, sell, route.from, route.to, route.commodity, quantity);
        return 0L;
      }
      executed = Math.min(quantity, allocated);
      ctx.capacityDemands.record(
          buy.buyer.household,
          buy.hex,
          buy.regionId,
          route.commodity,
          route.from,
          quantity,
          executed,
          workPerGoodPerMille);
      if (executed < quantity) {
        markCapacityBlocked(
            ctx, buy, sell, route.from, route.to, route.commodity, quantity - executed);
        // 应收而未收的名义运费：被运力截断的那部分没有收款人，读数具名、不静默变 0。
        uncollectedFreight = freightOf(quantity - executed, unitFreight);
      }
      freightCharges =
          carrierChargeSplit(
              allocation,
              buy,
              route,
              commodityFreightBaseMilli(ctx.topology, route.commodity),
              // ★★ M-C：货主是不是纯商号（H-A/H-G 的另一半：自运自货）—— 判据的唯一拼写点在 MerchantIdentity。
              ctx.pureMerchantHouseholds.contains(buy.buyer.household));
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
    // ★★ M-C：利润读数的**差价收入**腿与**三层税**腿（只读；不改任何余额、不影响任何判据）——
    //   同一笔成交的两端各记一次（卖方 + 货款实收 / 买方 − 货款实付），币 = 买方支付币（钱腿就铸在它上面）。
    //   ★ 税按**层**分开记（I-C3/I-C10：层与币都不合并；读口逐层列）。
    ctx.merchantProfits.recordPurchase(buy.buyer.household, buy.currency, payment);
    ctx.merchantProfits.recordSale(sell.seller.household, buy.currency, payment);
    for (MarketTaxBook.Charge charge : taxCharges) {
      ctx.merchantProfits.recordTax(
          buy.buyer.household, charge.layer(), charge.currency(), charge.amountMilli());
    }
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
    // ★★ M-C：利润读数的**损耗**腿 = 本笔实际计量的实物损耗 × 该笔买方单价（毫买方支付币）。
    //   ★ 跨区在途的损耗不在这里（它在到货日由 deliverShipments 结算，市场轮只读本轮实际计量值）。
    if (lossMilli > 0L) {
      ctx.merchantProfits.recordLoss(
          buy.buyer.household,
          buy.currency,
          ceilDiv(
              Math.multiplyExact(lossMilli, buyerUnitPrice), EconomySettlement.MILLI_PER_GRAIN));
    }

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

    // ③ 买方把冻结的货款（+运费 +税）放出来，再货款 → 卖方、运费 → 承运人、三层税 → 各政府国库。
    //   ★★ P-T1b：`total` 含税 ⇒ 冻结的释放量与可负担判据（{@link #totalCostAtMost}）同口径；**卖方那一腿
    //     （payment）一个字不改** ⇒ "买方多付、卖方仍收原价" 是结构性的，不是两处对齐出来的。
    long total = Math.addExact(Math.addExact(payment, freight), taxTotal);
    // ★★ M-C：利润读数的**本钱占用**腿 = 本笔支出在**在途天数**上的机会成本（按既有市场利率折算；微毫）。
    //   ★ 即时成交（0 天）⇒ 0；世界利率 20‰/周期 ⇒ 本腿在毫级通常为 0（读数按微毫列出，不静默丢）。
    ctx.merchantProfits.recordCapitalOccupancy(
        buy.buyer.household, buy.currency, capitalOccupancyMicro(ctx, route, total, inTransit));
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
              buy.buyer.actor,
              charge.carrierActor(),
              route.to,
              Map.of(),
              Map.of(buy.currency, charge.amountMilli()),
              route.haulService() ? TransferReason.MARKET_TRADE : TransferReason.CARRIER_FEE);
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
      ctx.merchantProfits.recordFreightPaid(
          buy.buyer.household, buy.currency, charge.amountMilli());
      ctx.merchantProfits.recordFreightEarned(
          charge.household(), buy.currency, charge.amountMilli());
      if (route.haulService()) {
        // ★★ A2：服务成交的轮级读数（逐币；只作日志/读数 —— 与上面的运费读数**同源同额**，不另记一份事实）。
        //   服务量本身在 {@link #deliverHaulService} 里累加（它覆盖全部条目，含免运费条目）。
        ctx.haulServicePaidByCurrency.merge(buy.currency, charge.amountMilli(), Math::addExact);
        ctx.haulServiceTrades++;
      }
      // ★★ M-A1（§一.9：TRACE = 逐笔运费）：付款人 → 提供运力的家户、金额、币种、lane。
      //   ★★ A2：服务成市时事件名换成运输服务成交（同一条钱腿、同一个付款人/收款人；只是它现在表达的是"买了多少服务"）。
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    route.haulService() ? "HAUL_SERVICE_PAID" : "CARRIER_FEE_PAID",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "commodity",
                    commodity.value(),
                    "from",
                    buy.buyer.actor.id(),
                    "toHousehold",
                    charge.household().value(),
                    "carrierHex",
                    charge.hex(),
                    "fromHex",
                    route.from,
                    "toHex",
                    route.to,
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
          ctx, buy, route, allocation, ctx.pureMerchantHouseholds.contains(buy.buyer.household));
    }
    // ★★ P-T1b：三层税的**钱腿**（逐层逐收款政府一条；钱铸在买方支付币上）—— 唯一写口 {@code applyTransfer}。
    //   ★ 层的顺序 = 调用方给的规范序（出口 → 进口 / 区内一条）⇒ 逐值可复现（I7）；0 额条目根本不在表里。
    //   ★ 买方 = 国库本身时那一份已在 {@link MarketTaxBook#assess} 里剔除（自转移不是发生额）。
    for (MarketTaxBook.Charge charge : taxCharges) {
      Transfer taxLeg =
          round.ledger.mint(
              buy.buyer.actor,
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
              // ★★ P-T4：单价币 = 卖方格计价币；实付币 = 买方支付币（货款与运费两条钱腿都铸在它上面）。
              sell.market.numeraire(),
              unitPrice,
              buy.currency,
              payment,
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
            // ★★ P-T4：单价币 = 卖方格计价币；实付币 = 买方支付币（货款与运费两条钱腿都铸在它上面）。
            sell.market.numeraire(),
            unitPrice,
            buy.currency,
            payment,
            reportedFreightPerUnit,
            freight,
            route.arrivalTick,
            false,
            shipmentId,
            // ★ M2.7：逐票预排损耗与到货日的扣减公式逐字同源（deliverShipments 也是 quantity × lossPerMille ÷ 1000）。
            Math.multiplyExact(executed, route.lossPerMille) / 1000L));
    return executed;
  }

  /**
   * ★★ <b>承运运力不足的具名落点</b>：买方槽 blocked、卖方槽 capacityBlocked、路线 bottleneck（有这条车道时），三处都
   * 读得到；并把<b>被运力截断的量</b>累加到两个槽位（V-20：截断部分不许进自适应定价的 demand/supply 统计）。
   *
   * <p>★ M-A1 起参数改用 {@code (from, to, commodity)} 而不是 {@code RouteContext}：有一条"发货格没有运力"的拦下发生在
   * **路线根本不建**的那一刻（那时没有 RouteContext）。
   *
   * <p>★★ D-1b：记进两侧的是 {@link LaneUnservedBook#claimPair} / {@link LaneUnservedBook#claimLane}
   * 认领的**增量**。 同一条 lane 的同一份未服务量若已由本轮其它落点记过（候选生成处的整条拦下 / 路线窗口预算用尽），这里不再记第二遍。
   *
   * @param truncatedMilli 本笔因运力未获服务的量（毫商品；&gt; 0）
   */
  private static void markCapacityBlocked(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      HexCoord from,
      HexCoord to,
      CommodityId commodity,
      long truncatedMilli) {
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
    }
    sell.capacityBlocked = true;
    // ★★ D-1b（净额记账）：同一次配对的这份未服务量只记一次 —— 本笔"没分到的那份"在同一轮的多次试配 / 多个运力窗口上
    //   会被反复观察到（**同一份**量）；整条车道级的水位（整条拦下 / 路线窗口用尽记在所有还有剩余的槽位上）同样要认。
    String laneKey = LaneUnservedBook.laneKey(from, to, commodity);
    long recorded =
        ctx.laneUnserved.claimPair(laneKey, buy.orderIndex, sell.orderIndex, truncatedMilli);
    if (recorded > 0L) {
      buy.capacityTruncatedMilli = Math.addExact(buy.capacityTruncatedMilli, recorded);
      sell.capacityTruncatedMilli = Math.addExact(sell.capacityTruncatedMilli, recorded);
    }
    RouteAccumulator acc = ctx.routes.get(laneKey);
    if (acc != null) {
      acc.bottleneck = true;
    }
  }

  /**
   * ★★ <b>M-A1：发货格没有运力 ⇒ 这条车道整条拦下</b>（具名 {@code LOGISTICS_CAPACITY}）—— 买卖两侧的余量都记成
   * "被运力截断"（V-20：截断部分不进自适应定价的 demand/supply 统计），并各留一条 DEBUG 的"为什么"。
   *
   * <p>★ 两侧的截断量各自按对侧余量封顶（{@code min(本侧余量, 对侧余量)}）：一个买方要 100、卖方只剩 30 ⇒ 双方各记 30。 两侧记的是**同一条车道**的
   * {@code min(买方余量合计, 卖方余量合计)}（= 下面日志的 {@code truncatedMilli}），
   * 不是"对侧的整份余量"——后者会把本侧装不下的部分也记成截断，超过挂单量时连**已服务**的那份都被剔出统计（V-20）。
   *
   * <p>★★ <b>D-1b（净额记账）</b>：这里记进两侧的是 {@link LaneUnservedBook#claimLane} 认领的**增量**。 同一条 lane
   * 的同一份未服务量若已被本轮其它落点记过（承运分配不足 {@link #markCapacityBlocked} / 路线窗口预算用尽），
   * 本落点<b>不再记第二遍</b>（两次相加会把已服务的那一份也剔出统计 ⇒ demand 被剔光、价格信号被压低）。
   */
  private static void blockLaneWithoutCapacity(
      MatchContext ctx,
      List<BuySlot> buys,
      List<SellSlot> sells,
      HexCoord sellerHex,
      HexCoord buyerHex,
      CommodityId commodity) {
    long buyTotal = 0L;
    for (BuySlot buy : buys) {
      if (buy.remaining > 0L) {
        buyTotal = Math.addExact(buyTotal, buy.remaining);
      }
    }
    long sellTotal = 0L;
    for (SellSlot sell : sells) {
      if (sell.remaining > 0L) {
        sellTotal = Math.addExact(sellTotal, sell.remaining);
      }
    }
    long blocked = Math.min(buyTotal, sellTotal);
    // ★★ D-1b（净额记账）：整条车道的这份未服务量只记一次 —— 同一份量可能已由本轮的**别的落点**记过
    //   （承运分配不足 / 路线窗口预算用尽），那两次相加会把**已服务**的那一份也剔出价格统计（V-20 幅度错）。
    long recorded =
        ctx.laneUnserved.claimLane(
            LaneUnservedBook.laneKey(sellerHex, buyerHex, commodity), blocked);
    for (BuySlot buy : buys) {
      if (buy.remaining > 0L) {
        // ★★ D-1（2026-10-10 裁定）：按**本侧余量**封顶 —— 记的是这条车道真正装不下的量（min(买余, 卖余)），
        //   不是对侧的整份余量。记多了会把**挂单量以内、已被服务**的那一份也剔出价格统计（V-20）。
        if (recorded > 0L) {
          buy.capacityTruncatedMilli = Math.addExact(buy.capacityTruncatedMilli, recorded);
        }
        if (buy.blocked == null) {
          buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
        }
      }
    }
    for (SellSlot sell : sells) {
      if (sell.remaining > 0L) {
        sell.capacityBlocked = true;
        if (recorded > 0L) {
          sell.capacityTruncatedMilli = Math.addExact(sell.capacityTruncatedMilli, recorded);
        }
      }
    }
    if (MARKET.isDebugEnabled() && blocked > 0L) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MERCHANT_CAPACITY_LANE_BLOCKED",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  ctx.round.day,
                  "commodity",
                  commodity.value(),
                  "sellerHex",
                  sellerHex,
                  "buyerHex",
                  buyerHex,
                  "truncatedMilli",
                  blocked,
                  // ★★ D-1b：本落点**真正记进两个槽位**的量（0 = 这条 lane 的这份未服务量已由别的落点记过 ⇒ 未重复计入）。
                  "recordedMilli",
                  recorded,
                  "reason",
                  "shipping-hex-has-no-merchant-household"));
    }
  }

  /**
   * ★★ <b>M-A2：本轮因运力未获服务的货物量（毫商品）</b>—— 各买槽 {@code capacityTruncatedMilli}（M-A1 的 V-20 既有读数） 之和。
   *
   * <p>★ 口径：那部分货<b>不成交、不成债、不计价</b>（K-4 / Q-27）—— 它在撮合里被收缩掉，这里只是把同一个事实 读数化进需求簿的轮总（不重复记账、不写状态）。
   */
  private static long goodsBlockedByCapacity(MatchContext ctx) {
    long blocked = 0L;
    for (BuySlot buy : ctx.buys) {
      if (buy.capacityTruncatedMilli > 0L) {
        blocked = Math.addExact(blocked, buy.capacityTruncatedMilli);
      }
    }
    return blocked;
  }

  /**
   * ★★ <b>A2：服务交付 —— 把这条 lane 上被买走的运输服务从卖方（跑商家户）的货物账里消耗掉</b>（设计书 §3.3/§5 I-H2）。
   *
   * <pre>
   * 逐条承运条目（{@code allocation.choices()}，含被免运费/自承运的条目 —— 服务是**物理上真的发生了**）：
   *   服务量 = {@code CarrierChoice.consumedWorkMilli()}（= CapacityDemand.workConsumedBy(货量, 本 lane 耗用‰)，既有算式）
   *   落点   = {@link EconomySettlement#consumeForLoss}（**非换手损耗的唯一写口**：账户减 + 损耗账加同址 ⇒ Σ余额 + losses 守恒）
   *   账     = {@link HaulService#SERVICE_CONSUMED_ACCOUNT}（{@code market-haul-service}，与货损/工具磨损分开）
   * </pre>
   *
   * <p>★★ <b>为什么在"动任何账之前"就交付</b>：它必须早于货腿/钱腿 —— 取不到服务货时本笔成交当场放弃（{@code executeTrade} 直接 {@code
   * return 0}），库里一个字节都还没动 ⇒ "买不到 ⇒ 该笔不成交（具名归因，fail-closed）"是**完整**的， 不是"发了货再补一张欠条"。
   *
   * <p>★★ <b>取不到货 = 契约故障（ERROR 不降级）</b>：运力池的运力预算**就是**该户当刻的 {@code haul} 可用量 （{@link
   * MerchantCapacityPool} 的服务口径），而本次交付紧跟在该次分配之后、在同一张活表上 ⇒ 正常情况下恒能取到。 取不到只有两种可能：账被别处改了、或池的预算不是从货来的
   * —— 两者都必须当场可见（ERROR + 计数），绝不静默少扣。
   *
   * @return 实际消耗的服务总量（毫服务）；<b>-1 = 有一条被 fail-closed 挡下</b>（调用方必须放弃本笔成交）
   */
  private static long deliverHaulService(
      MatchContext ctx, RouteContext route, MerchantCapacityPool.CarrierAllocation allocation) {
    MarketRound round = ctx.round;
    long total = 0L;
    for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
      long service = choice.consumedWorkMilli();
      if (service <= 0L) {
        continue;
      }
      EconomySettlement.LossConsumption consumed =
          EconomySettlement.consumeForLoss(
              round.householdGoods,
              round.householdFrozenGoods,
              round.ledger,
              HaulService.SERVICE_CONSUMED_ACCOUNT,
              choice.household(),
              HaulService.HAUL_COMMODITY,
              service);
      if (consumed.consumedMilli() <= 0L) {
        EventLog.channel(MARKET)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERY_FAULT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "hex",
                    choice.hex(),
                    "fromHex",
                    route.from,
                    "toHex",
                    route.to,
                    "neededServiceMilli",
                    service,
                    "stockMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "availableMilli",
                    consumed.availableMilli(),
                    "reason",
                    "haul-service-goods-missing-at-delivery-no-trade"));
        return -1L;
      }
      total = Math.addExact(total, consumed.consumedMilli());
      // ★★ A2：轮级读数 = **物理上真的交付了多少服务**（含被免运费 / 自承运的条目 —— 服务照跑、只是不收钱）。
      ctx.haulServiceSoldMilli = Math.addExact(ctx.haulServiceSoldMilli, consumed.consumedMilli());
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "commodity",
                    route.commodity.value(),
                    "fromHex",
                    route.from,
                    "toHex",
                    route.to,
                    "goodsQuantityMilli",
                    choice.quantityMilli(),
                    "serviceMilli",
                    consumed.consumedMilli(),
                    "stockBeforeMilli",
                    consumed.stockMilli()));
      }
    }
    return total;
  }

  /**
   * ★★ <b>M-A1/M-A2/M-C：把一票跨格运费分摊成逐提供者（家户）的运费金额</b>。
   *
   * <pre>
   * ⓪ 【M-C】**自运自货**（承运方是纯商号 ∧ 货主是纯商号）⇒ **整条豁免**（H-A/H-G："商号自己买东西不计运费"、
   *    "只有纯商号能免运费拿货"），该条的运费既不进实收、也不进未收；它按"成本直接按劳动力计"落进利润读数的
   *    劳动力成本腿（见 {@link #settleHaulRuns}）。★ 免掉的金额本身作为**具名读数**记录，不铸任何钱腿；
   *    ★ 其余情形（含 H-F 的"顺便跑商"承担的运力）⇒ 运费**独立计算**，算式一字不改。
   * ① 自承运条目（carrier == 买方 actor）整条跳过：不铸自转移、不计实收、不记未收（P10.9 口径保留）；
   * ② 逐条按**该提供者的限价**算单位运费 = 商品基础费 × (1000 + 路线费率‰) × (1000 + 限价‰)（算式一字不改；
   *    缺省口径下限价 == 派生承运成本 ⇒ 与 M-A1 逐值相同）；
   * ③ 实际可收运费 = min(非自承运部分的名义运费上限, Σ 各条按自身限价的运费)；
   * ④ 【报价口径】名义上限没被顶到 ⇒ 逐条按自己的限价收（M-A2：这才是"按提供者的报价买运力"）；
   *    【缺省口径 / 上限被顶到】⇒ 按承运量占非自承运总量的比例 floor 分摊，顺序里**最后一条实际承运条目拿余数**
   *    ⇒ Σ各条金额 == 实际可收运费，且 ≤ 该票 nominal freight（M-A1 原样，逐值不变）；
   * ⑤ 金额为 0 的条目不产生转移（仍保持守恒）。
   * </pre>
   *
   * <p>★ 分摊顺序 = select 返回顺序（报价口径：限价升序 → 议价权降序 → 家户 id 升序；缺省口径：议价权降序 → 家户 id 升序）， 不读时钟/随机 ⇒
   * 同输入逐值确定（I7）。
   *
   * <p>★★ <b>P-T5b：全部分摊都在<b>买方支付币</b>上做</b>（运费腿铸在 {@code buy.currency} 上）—— 逐条的单位运费经 {@link
   * BuySlot#payAmountOf} 折一次（同币 ⇒ 原样），说不出价的条目不参与（整票退回空表，fail-closed）。 自承运判据因此从"买方 actor"改为直接读
   * {@code buy.buyer.actor}（同一事实，不再多传一个入参）。
   */
  private static List<FreightCharge> carrierChargeSplit(
      MerchantCapacityPool.CarrierAllocation allocation,
      BuySlot buy,
      RouteContext route,
      long commodityBaseMilli,
      boolean buyerIsPureMerchant) {
    List<MerchantCapacityPool.CarrierChoice> choices = allocation.choices();
    long chargeableQuantity = 0L;
    long effectiveFreightSum = 0L;
    int lastChargeable = -1;
    long[] ownFreight = new long[choices.size()];
    for (int i = 0; i < choices.size(); i++) {
      MerchantCapacityPool.CarrierChoice choice = choices.get(i);
      if (choice.carrier().equals(buy.buyer.actor)) {
        continue; // 自承运：该条的运费不进入实收，也不进入未收
      }
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        continue; // ★★ M-C（H-A/H-G）：**自运自货**（承运方与货主都是纯商号）⇒ 免运费；
        //   免掉的金额与劳动成本在 settleHaulRuns 里记读数（H-F：非纯商号承担的运力照收，一条不改）
      }
      lastChargeable = i;
      chargeableQuantity = Math.addExact(chargeableQuantity, choice.quantityMilli());
      // ★★ P-T5b：逐条的单位运费同样折成**买方支付币**（腿铸在 buy.currency 上）；说不出价 ⇒ 整票不收运费腿
      //   （fail-closed：与 totalCostAtMost / executeTrade 的判据同口径，绝不按 1:1 顶上）。
      //   ★★ M-A2：承运成本项改用**该提供者自己的限价**（`choice.askPerMille()`）——缺省口径下它就等于
      //   `carrierCostPerMille(tier)`（逐值不变），报价口径下它就是"从最低价起买"的成交价。
      long unitFreight =
          buy.payAmountOf(
              route.haulService()
                  // ★★ A2：服务成市 ⇒ 成交价 = **服务牌价**（本 lane 已按它折出 route.freightPerUnit，
                  //   预判与结算同源）；承运成本限价那一套不再进入**钱**的算式（只进"按最低价提供者买"的选择序）。
                  ? route.freightPerUnit
                  : freightUnitMilli(
                      commodityBaseMilli, route.freightRatePerMille, choice.askPerMille()));
      if (unitFreight < 0L) {
        return List.of();
      }
      ownFreight[i] = freightOf(choice.quantityMilli(), unitFreight);
      effectiveFreightSum = Math.addExact(effectiveFreightSum, ownFreight[i]);
    }
    if (chargeableQuantity <= 0L) {
      return List.of();
    }
    long nominalCap = freightOf(chargeableQuantity, buy.payAmountOf(route.freightPerUnit));
    long collectible = Math.min(nominalCap, effectiveFreightSum);
    List<FreightCharge> charges = new ArrayList<>();
    if (allocation.priced() && collectible == effectiveFreightSum) {
      // ★★ M-A2 报价口径：名义上限没被顶到 ⇒ **逐条按各自的限价收**（这才是"按提供者的报价买运力"；
      //   Σ == effectiveFreightSum == collectible ⇒ 守恒与 M-A1 同一式子）。任何一条说不出价都不会走到这里
      //   （上面已 fail-closed 退空表）。★ 缺省口径不进这一支：那样才能保证"无报价 ⇒ 逐值不变"是结构性成立，
      //   而不是"算出来恰好相等"。
      for (int i = 0; i < choices.size(); i++) {
        if (ownFreight[i] <= 0L) {
          continue;
        }
        MerchantCapacityPool.CarrierChoice choice = choices.get(i);
        charges.add(
            new FreightCharge(
                choice.carrier(),
                ownFreight[i],
                choice.consumedWorkMilli(),
                choice.household(),
                choice.hex(),
                choice.pureMerchant()));
      }
      return List.copyOf(charges);
    }
    long assigned = 0L;
    for (int i = 0; i < choices.size(); i++) {
      MerchantCapacityPool.CarrierChoice choice = choices.get(i);
      if (choice.carrier().equals(buy.buyer.actor)) {
        continue;
      }
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        continue; // ★★ M-C：免运费条目在**两条分摊分支**里都要跳过（与走哪一支无关）
      }
      long amount;
      if (i == lastChargeable) {
        amount = collectible - assigned; // 余数全部给顺序里最后一条实际承运条目
      } else {
        amount = Math.multiplyExact(collectible, choice.quantityMilli()) / chargeableQuantity;
        assigned = Math.addExact(assigned, amount);
      }
      if (amount > 0L) {
        charges.add(
            new FreightCharge(
                choice.carrier(),
                amount,
                choice.consumedWorkMilli(),
                choice.household(),
                choice.hex(),
                choice.pureMerchant()));
      }
    }
    return List.copyOf(charges);
  }

  /**
   * 一条实际要铸的运费腿（M-A1）：收款**家户** actor + 金额 + 本条的**服务量** + 归属（家户/发货格，日志用）。
   *
   * <p>★ <b>A2：{@code serviceMilli} = 这条承运消耗掉的运输服务（毫服务 = 毫商品·程）</b>—— 它就是 {@code
   * CapacityDemand.workConsumedBy(货量, 本 lane 耗用‰)} 的既有结果（{@code CarrierChoice.consumedWorkMilli}），
   * 服务成交时按它从卖方货物账扣（{@link HaulService#SERVICE_CONSUMED_ACCOUNT}）。★ 缺省（服务不成市）路径下它只作读数， 不进任何算式。
   */
  private record FreightCharge(
      ActorRef carrierActor,
      long amountMilli,
      long serviceMilli,
      HouseholdId household,
      HexCoord hex,
      boolean pureMerchant) {
    private FreightCharge {
      Objects.requireNonNull(carrierActor, "carrierActor");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("FreightCharge 金额必须为正: " + amountMilli);
      }
      if (serviceMilli < 0L) {
        throw new IllegalArgumentException("FreightCharge 的服务量不得为负: " + serviceMilli);
      }
    }
  }

  // ── ★★ M-C：跑商门槛（工具消耗）与利润读数的落点 ──────────────────────────────────────────

  /**
   * ★★ <b>M-C：把一笔成交里的每一条跑商落地</b>（H-1 的工具一次性消耗 + 利润读数的运行腿 + 免运费读数）。
   *
   * <pre>
   * ① 工具：从承运家户的 {@code tool} **商品账**扣 {@link MerchantHaul#TOOL_MILLI_PER_HAUL}，
   *    并记进 {@link MerchantHaul#TOOL_BURN_ACCOUNT} 损耗账 ⇒ 守恒式（Σ余额 + losses）不变；
   *    ★★ T-fix：实扣走 {@link EconomySettlement#consumeForLoss}（**非换手损耗的唯一写口**，账户减 + 损耗账加同址），
   *      判据 = **可用量** {@code max(0, stock − householdFrozenGoods)}：**被冻结的 tool 不许被跑商烧**
   *      （同轮该户 {@code tool} 卖单的承诺优先）；可用量 &lt; 一趟 ⇒ **该次跑商不成立** ——
   *      实扣恰为 {@code 0}（**绝不部分扣**），具名归因 {@code tool-frozen}（被冻结占住）/ {@code tool-short}（真缺货）；
   * ② 免运费读数（H-A/H-G）：**自运自货**（承运方 ∧ 货主都是纯商号）⇒ 该条运费不铸，改记"本应付多少"
   *    （同一张 {@link #freightUnitMilli} 算式 + 该户限价）⇒ 与利润读数的劳动力成本腿同一笔事实的两个面；
   * ③ 劳动成本腿：{@code 耗用运力 × 该户劳动 ÷ 该户运力}（{@code MerchantCapacityPool.laborHoursOf}）。
   * </pre>
   *
   * <p>★ 只在**协调器**路径被调用（{@code allocation != null} ⇒ {@code route != null} ⇒ 串行撮合）， 与 {@code
   * taxItems}/{@code merchantProfits} 同一条纪律：worker 副本上的累加在交回时丢弃。
   */
  private static void settleHaulRuns(
      MatchContext ctx,
      BuySlot buy,
      RouteContext route,
      MerchantCapacityPool.CarrierAllocation allocation,
      boolean buyerIsPureMerchant) {
    MarketRound round = ctx.round;
    long baseMilli = commodityFreightBaseMilli(ctx.topology, route.commodity);
    for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
      // ① 一次性消耗工具（H-1：计成本、不返还；V-22：商品账，不动 AssetKind.TOOL 产权份额）
      //   ★★ T-fix：实扣走唯一写口 {@link EconomySettlement#consumeForLoss}（账户减 + 损耗账加同址），
      //     判据 = **可用量** max(0, stock − frozen) ⇒ 被冻结的 tool（同轮该户的 tool 卖单承诺）不许被烧；
      //     可用量 < 一趟 ⇒ **该次跑商不成立**：一点也不烧（绝不部分扣），具名 tool-frozen / tool-short。
      long cost = choice.toolMilli();
      EconomySettlement.LossConsumption burn =
          EconomySettlement.consumeForLoss(
              round.householdGoods,
              round.householdFrozenGoods,
              round.ledger,
              MerchantHaul.TOOL_BURN_ACCOUNT,
              choice.household(),
              MerchantHaul.TOOL_COMMODITY,
              cost);
      long consumed = burn.consumedMilli();
      if (consumed > 0L) {
        // ★★ 「计成本」（H-D）：烧掉的工具按**该户所在格的牌价**折成钱，进利润读数的**损耗腿**
        //   —— 读数的损耗口径与账本一致（账上它就在损耗账里）。★ 该格没有该商品的价 ⇒ 只记实物量、
        //   金额记 0 并具名（绝不按 1:1 或别的格的价猜）。
        recordToolBurnValue(ctx, choice, consumed);
      } else if (burn.blocked() && MARKET.isDebugEnabled()) {
        // ★★ T-fix：具名归因 —— `tool-frozen`（余额够、被冻结占住）与 `tool-short`（真缺货）**分得开**
        //   （唯一拼写点在 {@link MerchantHaul}）。★ 记 DEBUG（与改前同级）：本行是**逐笔**归因。
        EventLog.channel(MARKET)
            .debug(
                LogEvent.of(
                    "MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "neededMilli",
                    cost,
                    "burnedMilli",
                    consumed,
                    "stockMilli",
                    burn.stockMilli(),
                    "frozenMilli",
                    burn.frozenMilli(),
                    "availableMilli",
                    burn.availableMilli(),
                    "reason",
                    MerchantHaul.blockedReason(burn.stockMilli(), cost)));
      }
      // ② 免运费读数（只对**自运自货**：承运方 ∧ 货主都是纯商号；"本应付多少"用同一个单位运费算式 + 该户限价）
      long waived = 0L;
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        long unitFreight =
            buy.payAmountOf(
                // ★★ A2：服务成市 ⇒ "本应付多少"同样按服务牌价口径（预判/实收/免运费三处同一个数）。
                route.haulService()
                    ? route.freightPerUnit()
                    : freightUnitMilli(baseMilli, route.freightRatePerMille, choice.askPerMille()));
        if (unitFreight > 0L) {
          waived = freightOf(choice.quantityMilli(), unitFreight);
        }
      }
      // ③ 运行腿（劳动小时 + 工具 + 被免运费）进利润读数
      long laborHoursMilli =
          ctx.carrierPool.laborHoursOf(choice.household(), choice.consumedWorkMilli());
      ctx.merchantProfits.recordRun(
          choice.household(),
          choice.pureMerchant(),
          buy.currency,
          laborHoursMilli,
          consumed,
          waived);
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "MERCHANT_HAUL_RUN",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "pureMerchant",
                    choice.pureMerchant(),
                    "commodity",
                    route.commodity.value(),
                    "from",
                    route.from,
                    "to",
                    route.to,
                    "quantityMilli",
                    choice.quantityMilli(),
                    "toolBurnedMilli",
                    consumed,
                    "laborHoursMilli",
                    laborHoursMilli,
                    "waivedFreightMilli",
                    waived,
                    "currency",
                    buy.currency.value()));
      }
    }
  }

  /**
   * ★ <b>M-C：把烧掉的工具按承运方所在格的牌价折成钱</b>（利润读数的**损耗腿**；§12 H-D「计成本」）。
   *
   * <p>★ 取值口径：{@code 该格市场的 tool 牌价}（毫计价货币 / 商品单位）× 实物量 ÷ 1000，币 = 该格计价币。 ★ 该格没有市场 / 该商品**从未定价** ⇒
   * 金额记 0 并具名（{@code MERCHANT_HAUL_TOOL_UNPRICED}）—— 实物量仍在读数里（{@code toolBurnMilli}），绝不按 1:1
   * 或别格的价猜。
   */
  private static void recordToolBurnValue(
      MatchContext ctx, MerchantCapacityPool.CarrierChoice choice, long consumed) {
    Market market = ctx.markets.get(choice.hex());
    Long price = market == null ? null : market.prices().get(MerchantHaul.TOOL_COMMODITY);
    if (price == null) {
      if (MARKET.isDebugEnabled()) {
        EventLog.channel(MARKET)
            .debug(
                LogEvent.of(
                    "MERCHANT_HAUL_TOOL_UNPRICED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.round.day,
                    "household",
                    choice.household().value(),
                    "hex",
                    choice.hex(),
                    "toolBurnMilli",
                    consumed,
                    "reason",
                    market == null ? "no-market-at-carrier-hex" : "tool-never-priced"));
      }
      return;
    }
    long valueMilli =
        ceilDiv(
            Math.multiplyExact(consumed, Math.max(0L, price)), EconomySettlement.MILLI_PER_GRAIN);
    if (valueMilli > 0L) {
      ctx.merchantProfits.recordLoss(choice.household(), market.numeraire(), valueMilli);
    }
  }

  /**
   * ★★ <b>M-C：本钱占用（微毫）</b>—— 本笔支出在**在途天数**上的机会成本 = {@code 支出 × 天数 × 既有市场利率 ÷ (1000 ×
   * 周期天数)}，按微毫表达（毫级以下的量级要看得见）。
   *
   * <p>★ 口径：只算**在途占款**（即时成交 0 天 ⇒ 0）；利率取既有的 {@link
   * DebtTerms#LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE}（单一拼写点）、周期天数取 {@link
   * ExpectedProfitBook#DEFAULT_MERCHANT_CYCLE_DAYS}。★ 跨轮持有的库存占用**不在**本读数里（那需要跨轮状态， Q-23 明写"不落状态"）——
   * 具名边界，见实现账本。
   */
  private static long capitalOccupancyMicro(
      MatchContext ctx, RouteContext route, long spentMilli, boolean inTransit) {
    if (!inTransit || route == null || spentMilli <= 0L) {
      return 0L;
    }
    long days = Math.max(0L, route.arrivalTick - ctx.round.day);
    if (days <= 0L) {
      return 0L;
    }
    long numerator =
        Math.multiplyExact(
            Math.multiplyExact(spentMilli, days),
            DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE);
    return Math.multiplyExact(numerator, MerchantProfitBook.MICRO_PER_MILLI)
        / (MarketTaxBook.PER_MILLE * ExpectedProfitBook.DEFAULT_MERCHANT_CYCLE_DAYS);
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
   * <p>货腿已由唯一 applier 按<b>毛量</b>记到买方名下；这里把损耗那一份从买方会话余额移走 —— 它<b>不换手给任何 人</b>，与 {@link
   * #loadInTransit} 把货物移进在途批次是同一类"货物离开账户但未换手"的落点（唯一区别：在途
   * 批次日后会反向落回，损耗不再回来）。扣减本身不产生转移、不铸币、不写第二本账；损耗的唯一凭据是紧随其后的 {@code
   * ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, …)}。
   *
   * <p>买方是家户 ⇒ 扣家户账；买方是经营者 ⇒ 扣经营者账。扣前校验买方余额 {@code >= lossMilli}（货腿刚按毛量 入账，正常必然够）；不足时抛具名 {@link
   * IllegalStateException}，不静默夹 0、不造负库存。
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

  /**
   * 同一对 (sellerHex,buyerHex) 上的成交单价：取卖方**格价表里的参考价**的上界（M2.6：成交仍按参考价，买卖双方各自比自己的 bid/ask
   * 限价占优；多价表时宁高不低，不让卖家亏本）。★ 限价过滤在 {@link #matchRoute} 里逐买方判，不走这里。
   *
   * <p>★★ P-T1c：参考价一律取卖方格价表（{@link Market#priceOf}）—— 区级参考价覆盖已删（设计书 §16）， 与 {@link #ordersFor}
   * 同一条口径，区内/跨区不漂开。
   */
  private static long unitPriceOf(MatchContext ctx, List<SellSlot> sells, CommodityId commodity) {
    long price = 0L;
    for (SellSlot sell : sells) {
      if (sell.order.commodity().equals(commodity)) {
        price = Math.max(price, sell.market.priceOf(commodity));
      }
    }
    return price;
  }

  /**
   * ★★ <b>商品基础运费（毫计价货币 / 商品单位 / 程）的唯一读取口</b> —— 读<b>当刻状态表</b>（{@link MarketTopology} 携带的 {@code
   * EconomyData.commodityFreightBaseMilli} 快照；GM 可改）。
   *
   * <p>★★ <b>它从硬编码 dispatch 改成读状态（2026-10-09 用户裁定「甲」后的纠正）</b>：改前粮 1 / 纤维 1 / 布 2 / 工具 3
   * 是写死在这里的常量（源自 {@code d7604ea5}），GM 改不了、且与 F 批后加的"费率乘数维"构成<b>两个商品维相乘</b>。
   * 现在这组分档降级为"表里没有该商品"时的<b>具名缺省</b>（{@link CommodityFreightBase#legacyMilli(CommodityId)}，
   * 全仓唯一拼写点）⇒ 空表（旧档 / 新世界 / 夹具）逐值等于改动前（不变量 I-F1）。
   */
  static long commodityFreightBaseMilli(MarketTopology topology, CommodityId commodity) {
    Objects.requireNonNull(topology, "topology");
    return topology.commodityFreightBaseMilliOf(commodity);
  }

  /**
   * ★★ <b>A2：某格运输服务的牌价（毫该格计价货币 / 商品单位服务）</b>；<b>该格没给 {@code haul} 定价 ⇒ 0</b>。
   *
   * <pre>
   * 0 ⇒ 该格**服务不成市** ⇒ lane 的单位运费走既有 {@code freightUnitMilli}（承运成本口径）、运费腿铸 CARRIER_FEE
   * &gt;0 ⇒ 该格服务成市   ⇒ 单位运费走 {@link HaulService#unitFreightMilli}（服务牌价口径）、运费只走服务商品成交
   * </pre>
   *
   * <p>★ 判据的唯一拼写点是 {@link HaulService#pricedAt}（本方法只负责"取哪一格的市场"）；没有市场的格 ⇒ 0（不成市）。
   */
  private static long servicePriceAt(MatchContext ctx, HexCoord hex) {
    Market market = ctx.markets.get(hex);
    if (!HaulService.pricedAt(market)) {
      return 0L;
    }
    return market.priceOf(HaulService.HAUL_COMMODITY);
  }

  /**
   * ★★ <b>A2：这条 lane 走不走"运输服务商品"口径</b> = 发货格有没有给 {@code haul} 定价（含**明确 0 价**）。
   *
   * <pre>
   * false（未定价 / 没有市场）⇒ **逐值退回改前**：单位运费由既有 freightUnitMilli（商品基础费 × 路线费率 × 承运成本）
   *                            给出、运费腿铸 {@code CARRIER_FEE}（I-H3 第一条腿）
   * true                       ⇒ 单位运费由服务牌价给出（{@link HaulService#unitFreightMilli}）、运费腿改走服务商品成交（I-H5）
   * </pre>
   *
   * <p>★★ <b>为什么开关是"有没有定价行"而不是"牌价 &gt; 0"</b>：本仓对"从未定价 ⇒ 不交易"与"明确 0 价 ⇒ 免费交易" 有明文区分（{@code
   * Market.hasPrice} / {@code isFree}）—— 用价格数值当开关会把"GM 明确设成免费服务"静默读成
   * "这一格没有服务市场"，那正是本仓最反对的"两个状态混成一个"。★ 0 牌价下服务照卖（{@link HaulService#unitFreightMilli} 的 {@code
   * max(1, …)} 沿用既有"0 基础费仍收 1 毫"口径）。
   */
  private static boolean haulServiceAt(MatchContext ctx, HexCoord hex) {
    return HaulService.pricedAt(ctx.markets.get(hex));
  }

  /**
   * ★★ <b>承运方运营成本加价（‰ / 每档 tier 城区当量）</b>：脚夫与商人"要吃饭"的粗估表示 —— 承运不是免费的 公共服务，运费里必须含这笔成本。PORTER 25‰ /
   * SELF_EMPLOYED 50‰ / BOSS 100‰（数值是粗估、可由 GM 改）。
   */
  static final long MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP = 25L;

  /** 没有运力池（没有"选了跑商的家户"）时的默认承运成本（‰）；也用于撮合前的可负担量预判。 */
  static final long MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE = 25L;

  /**
   * ★★ <b>某提供者的承运成本（‰）= 派生 tier 城区当量 × 每档步长</b>。
   *
   * <p>★ M-A1：tier 不再是持久状态（{@code MerchantFirm} 已退役）—— 它由运力规模<b>派生</b> （{@link
   * MerchantCapacity#tierOf(long)}），本条算式因此只剩"派生读数 → 成本"这一步，算式本身逐字保留。
   */
  static long carrierCostPerMille(MerchantPolicy.MerchantTier tier) {
    Objects.requireNonNull(tier, "tier");
    return Math.multiplyExact(
        (long) tier.districtUse(), MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>撮合前可负担性预判用的承运成本（‰）</b>：有运力池时取<b>上界</b>，保证 {@code pairUp} 按它规划的钱 ≤
   * 实际逐提供者收费；没有跑商家户（池空）沿用小默认值。
   *
   * <pre>
   * 池空（没有跑商家户）           ⇒ 小默认值 25‰（逐值不变）
   * 池非空 + 缺省口径（无报价）    ⇒ 最高档 tier 的成本上界（BOSS = 4 × 25 = 100‰；逐字等于 M-A1）
   * 池非空 + 报价口径（M-A2）      ⇒ 本轮所有提供者的**最高限价**（= max(自报价 + 上门附加费)）
   * </pre>
   *
   * <p>★ 报价口径为什么取"最高限价"而不是"最高档 tier 成本"：限价可能高于 BOSS 档成本（含上门附加费），也可能被逐户
   * 覆写拉高；取实际最高限价才是真正的上界。它同时是"买方名义运费上限"的口径来源（{@code carrierChargeSplit}），
   * 因此买方永远不会因报价而被收超过计划的钱（fail-closed，I-C1 守恒不变）。
   */
  static long plannedCarrierCostPerMille(MatchContext ctx) {
    if (ctx.carrierPool.isEmpty()) {
      return MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE;
    }
    if (ctx.carrierPool.isPriced()) {
      return ctx.carrierPool.maxAskPerMille();
    }
    return Math.multiplyExact(
        (long) MerchantPolicy.MerchantTier.BOSS.districtUse(),
        MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>单位运费（毫计价货币 / 商品单位）的唯一算式</b>（2026-10-09 与货款价格解耦；算式逐字保留）：
   *
   * <pre>
   * unit = max(1, ⌈ 商品基础费 × (1000 + 路线费率‰) × (1000 + 承运成本‰) ÷ 1,000,000 ⌉ )
   * </pre>
   *
   * <p>三项来源：① <b>商品基础费</b>（{@link #commodityFreightBaseMilli}：读当刻状态表 {@code
   * EconomyData.commodityFreightBaseMilli}，GM 可改；缺键 ⇒ 现行硬编码分档）；② 路线费率（{@link
   * MarketTopology#freightPerMilleBetween} 的里程/辐射/道路 —— <b>没有</b>商品维）；③ 承运成本（{@link
   * #carrierCostPerMille}，脚夫/商号要吃饭的粗估）。<b>不含</b> {@code unitPrice} —— 0 价免费商品仍产生正运费。
   *
   * <p>★★ <b>{@code 0} 基础费仍收 1 毫</b>：末尾的 {@code max(1, …)} 把"该商品免基础费"抬到 1 毫 —— 这是既有语义
   * （本批**不改**）：{@code baseMilli = 0} 是"免基础费"的明确表达，不是"免费运输"。
   */
  static long freightUnitMilli(
      long commodityBaseMilli, long ratePerMille, long carrierCostPerMille) {
    if (commodityBaseMilli < 0L) {
      throw new IllegalArgumentException(
          "commodityBaseMilli 不得为负（0 = 该商品免基础费）: " + commodityBaseMilli);
    }
    if (ratePerMille < 0L) {
      throw new IllegalArgumentException("ratePerMille 不得为负: " + ratePerMille);
    }
    if (carrierCostPerMille < 0L) {
      throw new IllegalArgumentException("carrierCostPerMille 不得为负: " + carrierCostPerMille);
    }
    long routeFactor = Math.addExact(1000L, ratePerMille);
    long carrierFactor = Math.addExact(1000L, carrierCostPerMille);
    long product =
        Math.multiplyExact(Math.multiplyExact(commodityBaseMilli, routeFactor), carrierFactor);
    return Math.max(1L, ceilDiv(product, 1_000_000L));
  }

  /**
   * 一条成交量的运费：{@code ⌈数量(毫商品) × 单位运费(毫钱/商品单位) ÷ 1000⌉}（毫计价货币）。 ★ 单位运费由 {@link #freightUnitMilli}
   * 给出；<b>不看货款单价</b>。
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
    // ★★ P-T5b：运费腿按**买方支付币**折算 —— unitPrice 已是支付币的单价（3c 的折算结果），而
    //   route.freightPerUnit 是**本格计价币**的单位运费 ⇒ 必须折成同一个币才能相加（禁把两种钱直接相加）。
    //   ★ 说不出这种钱的价 ⇒ 0（买不起）：与结算侧的具名拒（MARKET_CURRENCY_VALUE_REFUSED / 限价不过）同口径。
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit);
    if (unitFreight < 0L) {
      return 0L;
    }
    long unitCost = unitPrice + unitFreight;
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
   * 这一笔数量按**与 {@link #executeTrade} 同一算式**算出的总价（货款 + 运费 + <b>P-T1b 的三层税</b>）是否 ≤ {@code money}。
   * 运费与货款解耦（{@link #freightUnitMilli}），0 价商品仍计运费；税由 {@link #taxesFor} 逐层算（同一个方法 ⇒
   * "判得起"与"真的扣"不可能漂开）。全程 {@code long}；乘法真的会溢出 ⇒ 这个数量在 {@code executeTrade} 里同样不可付，按"付不起"处理。
   */
  private static boolean totalCostAtMost(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long money,
      long buyerUnitPrice,
      RouteContext route) {
    long payment;
    try {
      payment =
          ceilDivPositive(
              Math.multiplyExact(quantity, buyerUnitPrice), EconomySettlement.MILLI_PER_GRAIN);
    } catch (ArithmeticException overflow) {
      return false;
    }
    if (payment > money) {
      return false;
    }
    long outlay = payment;
    // ★★ P-T5b：运费腿按**买方支付币**折算（route.freightPerUnit 是本格计价币的单位运费，而 payment / money 都是
    //   支付币）—— 与 executeTrade 走**同一个** payAmountOf（唯一拼写点）⇒ "判得起"与"真的扣"不可能漂开。
    //   ★ 说不出这种钱的价 ⇒ 付不起（false），绝不按 1:1 顶上。
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit);
    if (unitFreight < 0L) {
      return false;
    }
    if (unitFreight > 0L) {
      long freight;
      try {
        freight = ceilDivPositive(Math.multiplyExact(quantity, unitFreight), 1000L);
      } catch (ArithmeticException overflow) {
        return false;
      }
      try {
        outlay = Math.addExact(outlay, freight);
      } catch (ArithmeticException overflow) {
        return false;
      }
    }
    // ★★ P-T1b：税是买方总支出的一部分（"买方多付"）⇒ 可负担的判据必须含它，否则会在成交那一步把
    //   家户账扣成负数（那是 fail-closed 的 400，不是"少买一点"）。
    try {
      for (MarketTaxBook.Charge charge :
          taxesFor(ctx, buy, sell, commodity, quantity, buyerUnitPrice, inTransit(route), false)) {
        outlay = Math.addExact(outlay, charge.amountMilli());
      }
    } catch (ArithmeticException overflow) {
      return false;
    }
    return outlay <= money;
  }

  /** 跨区在途判据（{@link #executeTrade} 与可负担判据的唯一拼写点）。 */
  private static boolean inTransit(RouteContext route) {
    return route != null && !route.immediate;
  }

  /**
   * 把预分配/计划量按**当前剩余可付**精确封顶：最大 {@code q ≤ upper} 使 {@code q} 这一笔的总价（货款 + 名义运费 + 三层税）≤ {@code
   * payable}。
   *
   * <p>★ 这是缺陷 A 的安全点：{@link #affordableQuantity} 的边距只负责"保守少买"，跨区运费、三层税与两处 ceil 造成 的累计越界在这里被逐笔按实际账削平
   * ⇒ 任何成交序列下付款 ≤ 可支配（冻结 + 可花）。★ 0 价 + 0 运费的完全免费交易 在 {@code payable == 0} 时也应放行，故这里不再用 {@code
   * payable <= 0} 提前判死（由 {@link #totalCostAtMost} 按真实总价回答）。
   *
   * <p>★ <b>单调性</b>（二分的前提）：货款、运费、从量税、从价税都是数量 q 的非降函数 ⇒ {@code totalCostAtMost} 关于 q 单调。
   */
  private static long exactAffordableUpTo(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long upper,
      long payable,
      long unitPrice,
      RouteContext route) {
    if (upper <= 0L) {
      return 0L;
    }
    if (totalCostAtMost(ctx, buy, sell, commodity, upper, payable, unitPrice, route)) {
      return upper;
    }
    long low = 0L;
    long high = upper;
    while (low < high) {
      long mid = low + (high - low + 1L) / 2L;
      if (totalCostAtMost(ctx, buy, sell, commodity, mid, payable, unitPrice, route)) {
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
        ProductionProcess unit = ctx.round.units.get(id);
        Industry industry = unit == null ? null : ctx.round.industries.get(unit.industry());
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
  private static boolean sellerSelfUsable(MatchContext ctx, SellSlot sell) {
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
  private static boolean sellerCannotReproduce(MatchContext ctx, SellSlot sell) {
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
      ProductionProcess unit = unitForSeller(ctx.round, sell.seller, commodity);
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

  /**
   * 本卖方的商品成交里最低的一笔到货价（单价 + 单位运费；无成交 ⇒ empty）。
   *
   * <p>★★ <b>P-T4：不把两种钱相加</b> —— 单价是<b>卖方币</b>、单位运费是<b>买方币</b>，异币成交时"单价 + 单位运费" 没有定义；{@link
   * MarketReport.Fill#landedUnitPriceMilli()} 返回 empty 的成交（单价币 ≠ 运费币）<b>具名排除</b>、 不参与最低价。单币世界两栏恒同币
   * ⇒ 逐值不变。
   */
  private static OptionalLong bestAcceptedLandedPrice(MatchContext ctx, SellSlot sell) {
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
        DemandTargets.totalsByHex(
            ctx.round.householdDemands(), ctx.round.householdEconomies, ctx.round.day);
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
                  budget <= 0L
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
   * payableMoneyOf}/{@code affordableQuantity}，都按 {@code buy.currency}）。
   *
   * <p>★ <b>缺省语义中性（I-C2）</b>：本批订单币恒等于本格计价币 ⇒ 与改前逐值相同。
   */
  private static long diagnosedBudgetOf(
      MatchContext ctx, Participant participant, Market market, List<BuySlot> slots) {
    long budget = 0L;
    for (BuySlot buy : slots) {
      budget = Math.max(budget, spendableMoneyOf(ctx.round, participant, buy.currency));
    }
    return slots.isEmpty() ? spendableMoneyOf(ctx.round, participant, market.numeraire()) : budget;
  }

  /** 家户在某商品上的生活保留（家户没有该商品的需要 ⇒ 0；不是"读不到"）。 */
  private static long lifeReserveOfParticipant(
      MatchContext ctx, Participant participant, CommodityId commodity) {
    HouseholdEconomy householdEconomy = ctx.round.householdEconomies.get(participant.household());
    return householdEconomy == null
        ? 0L
        : householdLifeReserveOf(householdEconomy).getOrDefault(commodity, 0L);
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
      ProductionProcess unit = round.units.get(id);
      if (unit == null) {
        continue; // 索引与活表同源；这里只防御手工状态在两者之间被改动
      }
      unitsByOperator.computeIfAbsent(unit.operator(), ignored -> new ArrayList<>()).add(id);
    }
    LinkedHashMap<ActorRef, Participant> byActor = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      // ★★ Z7b：国库/单位户不生成任何订单 ⇒ 直接从参与者集合里排除（不买、不卖、不挂信用）。
      if (round.marketExcludedHouseholds().contains(key)) {
        if (MARKET.isTraceEnabled()) {
          EventLog.channel(MARKET)
              .trace(
                  LogEvent.of(
                      "MARKET_HOUSEHOLD_EXCLUDED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      key.value(),
                      "hex",
                      hex,
                      "reason",
                      // ★ R1：本集合现在**只含单位户**（国库户回市场、只按授权下单）。
                      "unit-household"));
        }
        continue;
      }
      ActorRef actor = HouseholdActors.of(key);
      List<ProductionUnitId> units = unitsByOperator.remove(actor);
      if (units != null) {
        // ★ 同一 operator 多 unit 时按 unit id canonical 序累加（必要投入/自留/卖单的确定性口径）。
        units.sort(Comparator.comparing(ProductionUnitId::value));
      }
      byActor.put(
          actor,
          new Participant(
              actor,
              key,
              round.householdEconomies.get(key).view(),
              units == null ? List.of() : units));
    }
    for (List<ProductionUnitId> units : unitsByOperator.values()) {
      units.sort(Comparator.comparing(ProductionUnitId::value));
    }
    // ★★ P2-A §13.3：经营者角色（庄园/作坊/商号）不持账 —— 逐 unit 解析到组织者/经营者家户：
    //   ① 单一家户（operator/relation/份额可解析）⇒ 把 unit 挂到该家户的参与者上；
    //   ② 解析不到单一主体但有名下劳动家户（集体经营，如家户纺织主 unit）⇒ **不是市场主体**（2026-10-23
    //      裁定 B）：不把聚合 unit 当市场参与者，静默跳过（只留默认关闭的 TRACE）；其产出已按劳动分给
    //      各成员家户账，各家按自己的库存与预算买卖；
    //   ③ **没有任何正资产、也没有任何劳动配额**的 unit（合法空壳，B.3b）⇒ WARN `MARKET_SUBJECT_EMPTY_UNIT` + 跳过；
    //   ④ ★★ 2026-10-10 G3-fix-1：**合成聚合经营者 + 名下劳动家户已全部不在行表**（成员户消亡/被迁移删行）
    //      ⇒ 具名 WARN `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` + 跳过。它与 ③ 的区别是"这个生产组织还在、
    //      但已经没有任何家户能收它的账"——这是合法终态（{@code ModeMigrationSettlement.retireSource}
    //      会删掉人口归零的成员户行），不该以"整条 AdvanceTime 不落 revision"为代价；
    //      详见 {@link #isUnresolvedAggregateOperator}。
    //      ★ 区内路径与跨区路径**共用本方法**（跨区撮合只消费这里建好的槽位）⇒ 两条路径同口径。
    //   ⑤ 其余（经营者指着不存在/非家户的主体等真坏数据）⇒ 具名抛，不静默当成"零库存参与者"。
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
            Map<AssetKind, Long> usable = round.index.usableAssetsOf(unitId);
            boolean hasCapacity =
                usable.values().stream().anyMatch(quantity -> quantity != null && quantity > 0L);
            if (!hasCapacity) {
              EventLog.channel(MARKET)
                  .warn(
                      LogEvent.of(
                          "MARKET_SUBJECT_EMPTY_UNIT",
                          EconomyLogSource.ECONOMY_MARKET,
                          "day",
                          round.day,
                          "unit",
                          unitId.value(),
                          "operator",
                          entry.getKey(),
                          "reason",
                          "no-positive-asset-and-no-labor-allocation"));
              continue;
            }
            // ★★ 2026-10-10 G3-fix-1：**合成聚合经营者**（`HOUSEHOLD:weave@<hex>` 一族）且它不是现存家户行
            //   ⇒ 这个 unit 从来不是市场主体（裁定 B：聚合 unit 的产出/库存已按劳动落各成员家户账），
            //   只是"成员户全都没了"让 ② 的判据（`householdsOf` 非空）也失效了。
            //   ⇒ 具名 WARN 跳过，**不抛整轮**：AdvanceTime 必须能落 revision（真实 world 复测 day38 的崩点）。
            //   ★ 不放宽真坏数据：判据要求 operator **恰是**该产业的合成默认经营者（见方法注），
            //     显式换上的组织者家户（farm/craft/trade）走不到这里。
            if (isUnresolvedAggregateOperator(unitId, entry.getKey(), round)) {
              EventLog.channel(MARKET)
                  .warn(
                      LogEvent.of(
                          "MARKET_SUBJECT_COLLECTIVE_UNRESOLVED",
                          EconomyLogSource.ECONOMY_MARKET,
                          "day",
                          round.day,
                          "unit",
                          unitId.value(),
                          "operator",
                          entry.getKey(),
                          "hex",
                          round.index.hexOf(unitId) == null ? "none" : round.index.hexOf(unitId),
                          "industry",
                          round.units.get(unitId) == null
                              ? "none"
                              : round.units.get(unitId).industry(),
                          "usableAssets",
                          usable,
                          "laborAllocations",
                          round.index.allocationsOfUnit(unitId).size(),
                          "reason",
                          "aggregate-operator-without-any-household-row-is-not-a-market-subject"));
              continue;
            }
            throw unresolvedMarketSubject(round, unitId, entry.getKey(), usable);
          }
          // ② 集体经营（如家户纺织主 unit）：**by design 不是市场主体**（2026-10-23 裁定 B）—— 该聚合
          //    unit 的产出/库存已按劳动落各成员家户账，成员家户各自入市；这里静默跳过，只留一条默认
          //    关闭（TRACE）的审计事件，不再发 WARN。不变量：参与者列表与该 unit 仍是 WARN 跳过时逐值一致。
          if (MARKET.isTraceEnabled()) {
            EventLog.channel(MARKET)
                .trace(
                    LogEvent.of(
                        "MARKET_SUBJECT_COLLECTIVE_SKIPPED",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        round.day,
                        "unit",
                        unitId.value(),
                        "operator",
                        entry.getKey(),
                        "households",
                        collective,
                        "reason",
                        "by-design-not-a-market-subject"));
          }
          continue;
        }
        HouseholdId household = single.get();
        // ★★ Z7b：unit 的经营者解析到国库/单位户 ⇒ 这个 unit 也不入市（其库存随单位户一起退出）。
        if (round.marketExcludedHouseholds().contains(household)) {
          if (MARKET.isTraceEnabled()) {
            EventLog.channel(MARKET)
                .trace(
                    LogEvent.of(
                        "MARKET_HOUSEHOLD_EXCLUDED",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        round.day,
                        "household",
                        household.value(),
                        "unit",
                        unitId.value(),
                        "reason",
                        "operator-is-unit-household"));
          }
          continue;
        }
        HouseholdEconomy householdEconomy = round.householdEconomies.get(household);
        if (householdEconomy == null) {
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
                  HouseholdActors.of(household),
                  household,
                  householdEconomy.view(),
                  List.of(unitId)));
        } else {
          List<ProductionUnitId> merged = new ArrayList<>(existing.units());
          merged.add(unitId);
          merged.sort(Comparator.comparing(ProductionUnitId::value));
          byActor.put(
              existing.actor(),
              new Participant(existing.actor(), household, householdEconomy.view(), merged));
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
        DemandTargets.partsForHex(
            round.householdDemands(), round.householdEconomies, hex, keys, round.day);
    Map<ActorRef, Map<CommodityId, List<Long>>> demandPartsByActor = new LinkedHashMap<>();
    for (Participant participant : participants) {
      necessary.put(participant.actor, necessaryInputsOf(round, participant));
      if (participant.household != null) {
        HouseholdEconomy householdEconomy = round.householdEconomies.get(participant.household);
        life.put(
            participant.actor,
            householdEconomy == null ? Map.of() : householdLifeReserveOf(householdEconomy));
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
    ProductionProcess unit = unitForSeller(round, seller, commodity);
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
  private static ProductionProcess unitForSeller(
      MarketRound round, Participant seller, CommodityId commodity) {
    ProductionProcess match = null;
    for (ProductionUnitId id : seller.units) {
      ProductionProcess unit = round.units.get(id);
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
      ProductionProcess unit = round.units.get(id);
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
      ProductionProcess unit = round.units.get(id);
      Industry industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRules relation = round.relations.get(id);
      Payee supplier =
          relation == null ? new Payee.ToActor(unit.operator()) : relation.inputSupplier();
      if (!supplies(participant, supplier)) {
        continue;
      }
      long scale =
          ProductionProcessBook.plannedCapacityScaleOf(
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
    long haulToolReserve = merchantHaulToolReserveMilli(round, participant);
    if (haulToolReserve > 0L) {
      Long declared = necessary.get(MerchantHaul.TOOL_COMMODITY);
      // ★★ "至少一趟" = **下夹**（已有声明量更小才抬到一趟），不是"产业声明量 + 一趟"（sum）：
      //    ① 用户裁定 6 的措辞是"至少保留一趟跑商的量"；
      //    ② 求和会把 trade unit 那一户的保留从 10,000 抬到 11,000（凭空多冻 10%），而它本来就是同一件事的两套口径；
      //    ③ 下夹只增不减地保住"能跑一趟"，且**不按运力上界放大** ⇒ 不会把工具市场冻死。
      //    ★ put 覆盖已有键时**保序**（LinkedHashMap：键位置不变）⇒ I7 不被这一行扰动。
      if (declared == null || declared < haulToolReserve) {
        necessary.put(MerchantHaul.TOOL_COMMODITY, haulToolReserve);
      }
    }
    return necessary;
  }

  /**
   * ★★ <b>§16.4 ①（2026-10-10 用户裁定 6）：跑商家户在 {@code tool} 上的<b>保留下限 = 恰好一趟</b></b>（唯一拼写点）。
   *
   * <p>★★ <b>用户原话</b>：「跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？」
   *
   * <pre>
   * 范围 = "有效位置含 merchant.*"的家户（判据的唯一拼写点 = MerchantIdentity.selectsMerchant，本类不另判）
   *        ⇒ **与有没有 trade unit、有没有运力都无关**。这正是本项要修的那条缝：旧口径只看 participant.units，
   *          而同格里没有 trade unit 的跑商家户 necessary(tool) = 0 ⇒ 它挂出的 tool 卖单在 commitFreezes 里被
   *          **全额**冻结 ⇒ 跑商当刻可用量 = 0（3,589 趟被拦的机制面）。
   * 量   = MerchantHaul.TOOL_MILLI_PER_HAUL（1,000 毫工具/趟，**标定值一字不改**）。
   * </pre>
   *
   * <p>★★ <b>为什么是"一趟"而不是"运力 ÷ 门槛"（运力上界）</b>：一趟 = 跑商本体的**最小可成立单位** （{@code
   * MerchantHaul.blockedReason} 的判据就是"可用量 ≥ 1 趟"）—— 保留的意义只是"别把这一户的工具全额挂出去",
   * 保住"它想跑就有一趟"；按运力折算会随劳动/运力线性放大，把同一格的工具**长期冻在账上**（工具是存量、用完才补）， 那是把跑商的门槛变成对工具市场的抽干。★ 这一项因此是**每户恒
   * 1,000**，与运力、规模、趟数上限都无关。
   *
   * <p>★ <b>不走 {@code supplies} 那道守卫</b>（{@link #necessaryInputsOf} 的 unit 循环里那道）：那道判据问的是
   * "这份投入是不是我自己供的"（别人供的会由市场送到我手上，不必自留）；而跑商的工具消耗**无条件**从本户自己的 {@code tool} 商品账扣（{@code
   * MerchantCapacityPool} 读本户可用量、{@code consumeForLoss} 扣本户账）， 与谁供料无关 ⇒ 这里必须自留。
   *
   * <p>★ <b>只减"可卖量"，不加买单</b>：{@code ordersFor} 里家户的买目标 = 生活保留 + 需求目标，{@code necessary}
   * 只进卖单的减项（{@code sellable = max(0, 存量 − 冻结 − necessary − 保留)}）与"未卖余量自用"判据 （{@code
   * sellerSelfUsable}）⇒ 本项**不新增工具需求**、不改门槛、不改任何标定值（缺工具仍 fail-closed）。
   */
  private static long merchantHaulToolReserveMilli(MarketRound round, Participant participant) {
    return participant.household != null
            && round.merchantHouseholds().contains(participant.household)
        ? MerchantHaul.TOOL_MILLI_PER_HAUL
        : 0L;
  }

  private static Map<CommodityId, Long> householdLifeReserveOf(HouseholdEconomy householdEconomy) {
    Map<CommodityId, Long> life = new LinkedHashMap<>();
    // ★★ 2026-10-09 Batch 3：保留额 = 本户当前注入 naturalNeeds 在补货窗口上的前瞻（逐户读取），
    //    不再按 population × 人均定额现算。
    // ★★ D2（2026-10-09 口径修正）：键集 = **本户当前注入的 naturalNeeds 全部键**，不再由本方法写死粮/布两行。
    //    写死会让"新增的生活必需商品"永远进不了买目标：Social 侧把它算进了 naturalNeeds，而这里 life 恒空 ⇒
    //    baseTarget 恒 0（见 ordersFor 的 `baseTarget = life`）⇒ desiredQuantity 恒 0 ⇒ 零买单、永不产生有效需求。
    //    ★ 权威方向不变：需求仍是 Social 唯一权威，本方法只按**状态里已有的键**做多日前瞻（不自己造需求、不加默认表）。
    //    ★ 保序：naturalNeeds 本身是保序不可变 LinkedHashMap（键序 = app 注入序）⇒ 照它的键序展开；
    //      值为 0 的键当场跳过（expectedNeedMilli 对 0 份额返回 0，等价于"没有这一行需要"）。
    for (CommodityId commodity : householdEconomy.naturalNeeds().keySet()) {
      long need = householdEconomy.expectedNeedMilli(commodity, MARKET_LIFE_RESERVE_DAYS);
      if (need > 0L) {
        life.put(commodity, need);
      }
    }
    return life;
  }

  private static Map<CommodityId, Long> operatorLifeRetentionOf(
      MarketRound round, Participant participant, HexCoord hex) {
    Map<CommodityId, Long> retained = new LinkedHashMap<>();
    for (ProductionUnitId id : participant.units) {
      ProductionProcess unit = round.units.get(id);
      Industry industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRules relation = round.relations.get(id);
      if (relation == null) {
        continue;
      }
      if (!unit.operator().equals(participant.actor)
          && !relation.operator().equals(participant.actor)) {
        continue;
      }
      // ★ R4-B.3a-perf：家户行由入口索引给（旧实现每个 unit 现扫全量配额）。
      // ★★ 2026-10-09 Batch 3：逐户用 expectedNeedMilli 求补货窗口保留额再求和，
      //    不再先把人头相加、再乘全局人均定额。
      // ★★ D2（2026-10-09 口径修正）：逐户按**该户注入的 naturalNeeds 全部键**求和（与 householdLifeReserveOf 同一口径），
      //    不再写死粮/布两行。★ 现状如实记：参与者恒为家户（P2-A §13.3 —— participantsFor 的三处构造都带 household）
      //    ⇒ 本方法当前**不可达**；改它是为了"经营者角色重新入市"时这里不再留一处写死的商品表（同一族口径只留一份）。
      //    ★ retentionOf 只在"该关系承诺了给养"的商品上有键（承诺额由 relation.rules() 派生）⇒ 请求键集扩大
      //      不会凭空产生保留额。
      Map<CommodityId, Long> requested = new LinkedHashMap<>();
      for (HouseholdId key : round.index.householdsOf(id)) {
        HouseholdEconomy householdEconomy = round.householdEconomies.get(key);
        if (householdEconomy == null) {
          continue;
        }
        for (CommodityId commodity : householdEconomy.naturalNeeds().keySet()) {
          long need = householdEconomy.expectedNeedMilli(commodity, MARKET_LIFE_RESERVE_DAYS);
          if (need > 0L) {
            requested.merge(commodity, need, Math::addExact);
          }
        }
      }
      Map<CommodityId, Long> one =
          SubsistenceObligation.retentionOf(
              relation,
              EconomySettlement.laborOfCohort(round.householdEconomies, hex, industry.cycleDays()),
              requested);
      for (Map.Entry<CommodityId, Long> entry : one.entrySet()) {
        retained.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return retained;
  }

  /**
   * ★★ <b>§一.9（DEBUG）：本格"生活保留额现在含哪些商品"的读数</b>——生活保留口径由"硬编码粮/布两行"改成"遍历本户注入的 {@code naturalNeeds}
   * 全部键"之后，这一行是"某个商品为什么有/没有买目标"的第一现场（{@code life} 为空 ⇒ {@code baseTarget} 恒 0 ⇒ 该商品零买单、永不产生有效需求）。
   *
   * <p>★ <b>位置与粒度</b>：一条 / 格 / 轮（不是一条 / 户）——保留额逐户算，但按格汇总后既不随家户数放大，又能一眼看出商品集； 也刻意<b>不放进</b> {@link
   * #planFor}：读口 {@code planOrders} 会逐商品调 {@code planFor}，记在那里会按商品数放大日志。 家户与经营者两侧分列（经营者侧当前不可达，见
   * {@link #operatorLifeRetentionOf}）。
   */
  private static void logLifeReserves(MarketRound round, HexCoord hex, HexPlan plan) {
    if (!MARKET.isDebugEnabled() || plan.participants.isEmpty()) {
      return;
    }
    Map<CommodityId, Long> householdReserve =
        new TreeMap<>(Comparator.comparing(CommodityId::value));
    Map<CommodityId, Long> operatorReserve =
        new TreeMap<>(Comparator.comparing(CommodityId::value));
    int households = 0;
    int operators = 0;
    for (Participant participant : plan.participants) {
      boolean household = participant.household != null;
      Map<CommodityId, Long> totals = household ? householdReserve : operatorReserve;
      if (household) {
        households++;
      } else {
        operators++;
      }
      for (Map.Entry<CommodityId, Long> entry :
          plan.lifeReserves.getOrDefault(participant.actor, Map.of()).entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_LIFE_RESERVE",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "hex",
                hex.toString(),
                "households",
                households,
                "householdReserve",
                reserveSummary(householdReserve),
                "operators",
                operators,
                "operatorReserve",
                reserveSummary(operatorReserve)));
  }

  /** 逐商品保留额的规范串（商品 id 升序的 {@code [commodity=milli,...]}；空表 ⇒ {@code []}）。 */
  private static String reserveSummary(Map<CommodityId, Long> reserve) {
    if (reserve.isEmpty()) {
      return "[]";
    }
    StringBuilder text = new StringBuilder("[");
    for (Map.Entry<CommodityId, Long> entry : reserve.entrySet()) {
      if (text.length() > 1) {
        text.append(',');
      }
      text.append(entry.getKey().value()).append('=').append(entry.getValue());
    }
    return text.append(']').toString();
  }

  private static boolean supplies(Participant participant, Payee supplier) {
    return switch (supplier) {
      case Payee.ToActor toActor -> participant.actor.equals(toActor.actor());
      case Payee.ToHousehold toHousehold ->
          participant.household != null && participant.household.equals(toHousehold.household());
      // ★ 旧档变体：按**视图**比对（构造期归一化已把一对一转到 ToHousehold；这是兼容读的窄出口）。
      case Payee.ToCohort toCohort ->
          participant.view != null && participant.view.equals(toCohort.cohort());
    };
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
        new LinkedHashMap<>(
            round.householdFrozenGoods.getOrDefault(participant.household, Map.of()));
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
        new LinkedHashMap<>(
            round.householdFrozenMoney.getOrDefault(participant.household, Map.of()));
    inner.put(currency, value);
    round.householdFrozenMoney.put(participant.household, inner);
  }

  private static long spendableMoneyOf(
      MarketRound round, Participant participant, CurrencyId currency) {
    return spendableMoneyOf(round, participant.household, currency);
  }

  /**
   * ★★ <b>A2a：家户（而不是参与者）为键的可花额</b> —— 外汇面按"逐格逐户"扫描（政府国库户不在参与者表里，但它 的外汇窗口必须读得到自己的储备）。
   *
   * <p>★ 与上面那个 Participant 版<b>逐值同源</b>（后者直接委托本方法）：同一件事不许有两套算式。
   */
  static long spendableMoneyOf(MarketRound round, HouseholdId household, CurrencyId currency) {
    long balance =
        round.householdMoney.getOrDefault(household, Map.of()).getOrDefault(currency, 0L);
    long frozen =
        round.householdFrozenMoney.getOrDefault(household, Map.of()).getOrDefault(currency, 0L);
    return Math.max(0L, balance - frozen);
  }

  /** ★ A2a：家户为键的实物可花额（实物保留/信用口径读它；★ P-T5 起外汇面<b>不再</b>读它 —— F-3 是"挂单全部"，不扣任何储备）。 */
  static long spendableGoodsOf(MarketRound round, HouseholdId household, CommodityId commodity) {
    long balance =
        round.householdGoods.getOrDefault(household, Map.of()).getOrDefault(commodity, 0L);
    long frozen =
        round.householdFrozenGoods.getOrDefault(household, Map.of()).getOrDefault(commodity, 0L);
    return Math.max(0L, balance - frozen);
  }

  /**
   * ★★ <b>A2a：家户为键的货币保留额</b>（{@link #moneyReserveOf(MatchContext, Participant, Market)} 的家户版，
   * 逐值同源）：出借人可借头寸用它算"这笔钱是不是余钱"。
   *
   * <p>★★ <b>P-T5 起外汇面不再用它</b>：用户 2026-10-10 的 F-3 是"挂单全部（不扣生活/生产储备）"，家户 FX 单的量 = 该币
   * <b>全部可花额</b>（{@code FxSettlement#placeHouseholdOrder}）—— 旧口径"余钱才拿去换外币"已随旧规则退役。
   *
   * <p>★ 口径原样不动：未覆盖的日自然需求按本格市价折算 + 人均货币缓冲；缺价不入保留额、人口/行读不到 ⇒ {@code
   * Long.MAX_VALUE}（fail-closed：读不到不等于不用留）。
   */
  static long moneyReserveOfHousehold(MarketRound round, HouseholdId household, Market market) {
    HouseholdEconomy householdEconomy = round.householdEconomies.get(household);
    if (householdEconomy == null || market == null) {
      return Long.MAX_VALUE;
    }
    long reserve = 0L;
    for (Map.Entry<CommodityId, Long> need : householdEconomy.naturalNeeds().entrySet()) {
      if (need.getValue() <= 0L) {
        continue;
      }
      long available = spendableGoodsOf(round, household, need.getKey());
      long uncovered = Math.max(0L, need.getValue() - available);
      if (uncovered <= 0L) {
        continue;
      }
      long price = market.priceOf(need.getKey());
      if (price <= 0L) {
        continue;
      }
      reserve = safeAdd(reserve, safeMulDiv(uncovered, price, EconomySettlement.MILLI_PER_GRAIN));
    }
    reserve =
        safeAdd(
            reserve,
            safeMulDiv(householdEconomy.population(), LENDER_MONEY_BUFFER_PER_CAPITA_MILLI, 1L));
    return reserve;
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
   * ★★ <b>本世界有没有可收款承运人 = 有没有运力池</b>（M-A1：运力提供者 = 选了跑商的家户，CARRIER_FEE 收款人 = 它的家户账）。
   *
   * <p>★ 旧路径的"第一个有货币账的 ORGANIZATION"承运人**不复存在**（组织不持账）；没有运力池的世界 ⇒ 没有可收款承运人， 跨格路线在候选生成处就不建（具名 {@code
   * LOGISTICS_CAPACITY}），不再有"货照走、运费记未收"的兜底。
   */
  private static boolean carrierPresent(MatchContext ctx) {
    return !ctx.carrierPool.isEmpty();
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
        Market market,
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
      this.noMoney = other.noMoney;
      this.blocked = other.blocked;
      this.capacityTruncatedMilli = other.capacityTruncatedMilli;
      // ★★ P-T1d：置顶名次与"是否真被推进"逐值照抄（worker 副本与协调器必须用同一套顺序，否则回放对不上）。
      this.procurementRank = other.procurementRank;
      this.procurementOvertook = other.procurementOvertook;
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
        Market market,
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
  private static long reservationMicroOf(
      MarketRound round, Participant seller, Market market, CommodityId commodity) {
    if (seller.household == null) {
      return 0L;
    }
    long movable =
        Math.max(0L, stockOf(round, seller, commodity) - frozenGoodsOf(round, seller, commodity));
    return HouseholdValuationBook.reservationMicro(
        market,
        commodity,
        seller.household,
        round.householdEconomies.get(seller.household),
        movable,
        HouseholdValuationBook.NO_TRADE_HISTORY);
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
      boolean immediate,
      boolean haulService,
      long servicePriceMilli) {

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
     * ★★ <b>A2：本轮运输服务成交的读数</b>（毫服务 = 毫商品·程；只作日志/读数，<b>不落状态</b>）。
     *
     * <p>★ 与 {@code taxItems}/{@code capacityDemands} 同一条纪律：只在**协调器**路径上写（worker 副本上的是本地累加， 交回时丢弃）⇒
     * 汇总只可能来自协调器那一份。★ 服务不成市的世界恒为 0 ⇒ 汇总行一条不打（缺省语义中性，I-H3）。
     */
    long haulServiceSoldMilli;

    /** ★★ A2：本轮服务成交的金额（逐币分列；键 = 买方支付币；只作读数，不落状态）。 */
    final Map<CurrencyId, Long> haulServicePaidByCurrency = new LinkedHashMap<>();

    /** ★★ A2：本轮服务成交的服务腿条数（= 铸出去的服务钱腿条数）。 */
    long haulServiceTrades;

    /**
     * ★★ A2：本轮因"服务货在交付点取不到"而放弃的成交笔数 —— <b>契约故障</b>（池只从当刻现货里分配，交付点却取不到货 ⇒ 跨切片一致性故障）。★ 它必须为 0；非 0
     * 时每条都发 ERROR（不降级）且本笔不成交（fail-closed，见 {@code deliverHaulService}）。
     */
    long haulServiceDeliveryFaults;

    /**
     * ★★ <b>M-C：本轮商号利润读数</b>（逐户逐腿，**每轮算出来的读数、不落状态**；见 {@link MerchantProfitBook}）。
     *
     * <p>★ 与 {@code taxItems}/{@code capacityDemands} 同一条纪律：只在**协调器**路径上写（worker 本地副本上的 累加在交回时丢弃）⇒
     * 读数只可能来自协调器那一份。★ 它<b>不改任何余额、不铸转移、不影响任何判据</b>； 没有跑商家户 / 没有跨格运力 ⇒ 它是空的 ⇒ 一行日志都不打（缺省语义中性，I-C2）。
     */
    final MerchantProfitBook merchantProfits = new MerchantProfitBook();

    /**
     * ★★ <b>M-C：本轮的纯商号家户集合</b>（H-2；免运费判据 H-A/H-G 的**范围**）。
     *
     * <p>★ 由 {@code EconomySettlement} 从 {@code classMemberships × classPositions} 现算（判据唯一拼写点 =
     * {@code MerchantIdentity}）；{@code Set.of()} = 没有纯商号 ⇒ 谁都不豁免 ⇒ 逐值退回 M-A2（缺省中性）。 ★ 与 {@code
     * recordFillIntents} 同款：构造后由入口赋值（worker 副本照抄），不改构造器签名。
     */
    Set<HouseholdId> pureMerchantHouseholds = Set.of();

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
     * ★★ <b>P-T1a：本轮被口岸闸节流的（源区 → 目的区）对数与被拦下的量</b>（毫商品；只作日志/读数——被拦下的量 <b>不进候选集、不落状态、不进账本</b>，设计书
     * §11）。
     */
    int portGatedPairs;

    /** 见 {@link #portGatedPairs}（各<b>区对</b> {@code transit − 可通过量} 之和）。 */
    long portBlockedMilli;

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
        Map<HexCoord, Market> markets,
        MarketTopology topology,
        MerchantCapacityPool carrierPool,
        MarketRegulation regulation,
        CurrencyValuation currencyValuation) {
      this.round = round;
      this.markets = markets;
      this.topology = topology;
      this.regulation = regulation == null ? MarketRegulation.none() : regulation;
      this.creditConfig = round.creditConfig();
      this.debts = round.debts();
      this.hexTradeCost = new HexTradeCost(topology);
      this.currencyValuation =
          Objects.requireNonNull(currencyValuation, "MatchContext 的\"钱的价\"不得为 null（没有就给 none()）");
      this.carrierPool = carrierPool;
      String anchorRegionId =
          this.regulation.defined() && topology.contains(this.regulation.anchor())
              ? regionIdAt(topology, this.regulation.anchor())
              : null;
      this.regulationByRegionId =
          anchorRegionId == null ? Map.of() : Map.of(anchorRegionId, this.regulation);
    }

    /** ★★ E：本轮的"钱的价"（只读）。 */
    CurrencyValuation currencyValuation() {
      return currencyValuation;
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
