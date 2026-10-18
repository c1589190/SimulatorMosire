package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.market.MarketZone;

/** 市场轮常量（P2a：5 天例行节奏；无额外安全库存/追加轮）。 */
final class MarketConstants {

  private MarketConstants() {}

  /**
   * ★★ <b>M2.0 的商品撮合间隔</b>（天）：每 5 天一轮。★ 它同时是"到下轮补货前"生活保留窗口的唯一来源 （见 {@link
   * #MARKET_LIFE_RESERVE_DAYS}）。
   */
  static final long MARKET_RESTOCK_INTERVAL_DAYS = 5L;

  /** ★ <b>生活保留窗口</b>（天）：= 到下一市场轮的基础消费 + 生产投入窗口。P2a 起没有额外安全库存；它是卖/买两侧生活保留的唯一拼写点。 */
  static final long MARKET_LIFE_RESERVE_DAYS = MARKET_RESTOCK_INTERVAL_DAYS;

  /** ★★ <b>买订单的到货时限</b>（天）：必须在下一市场轮前到货，超过它就救不了这次保留窗口。 */
  static final long MARKET_BUY_DEADLINE_DAYS = MARKET_RESTOCK_INTERVAL_DAYS;

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
   * ★★ <b>M2.6 自适应价格的开关（2026-10-07 用户裁定：打开）</b>：打开后每轮结算<b>结束</b>时按 {@code z = clamp((有预算且合限价的需求 −
   * 可出售供给) / max(需求 + 供给, ε), −1, 1)}、 {@code p_next = max(p_min, round(p × (1 + α·z)))}
   * 更新**各区集散节点价**，并把同一区成员格的同商品价一并改到该值 （"每区每商品一个报价"）。
   *
   * <p>★★ <b>唯一写回路径</b>：{@link #clearOncePerCycle} 返回新的市场表，{@code EconomySettlement} 把这份表放进它交出的
   * {@code EconomyData} —— 于是 {@code marketTables} 作为既有的 {@code FieldDelta} 组件进变更集。本类<b>不</b>直接改任何
   * {@code EconomyData}，也没有第二处改价。
   *
   * <p>★ 固定报价模式仍是可达的：把本常量改回 {@code false}（并提供旧初态/旧档）即可逐值回到 M2.6 之前。 读侧 {@link
   * MarketReport#priceMode()} / {@link MarketReadout#adaptivePricingEnabled()} 会如实标注本轮模式。
   */
  static final boolean MARKET_ADAPTIVE_PRICING_ENABLED = true;

  /**
   * ★★ <b>自适应的步长 α 的出厂缺省（千分比/轮）</b>：{@code 50‰ = 5%}。
   *
   * <p>★★ <b>W2（2026-10-11）：α 不再是这里的常量，而是逐区状态</b> —— 权威落点为 {@link
   * MarketZone#priceAlphaPerMille()}（{@code MarketZone} 的持久字段，GM 可经 {@code
   * economy.SetMarketZonePriceAlpha} 改，缺省 {@link MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}）。
   * 本常量保留为"出厂值与出厂缺省同值"的**单一事实**的别名，读点只有 {@link #priceAlphaPerMilleOf} 一处 ——
   * 结算路径<b>不</b>读它（它读区表；没有区表的世界读 {@code MarketZone} 的缺省常量）。
   */
  static final long MARKET_ADAPTIVE_ALPHA_PER_MILLE = MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE;

  /**
   * ★★ <b>D-030 §3.1：出借人的人均货币保留额</b>（毫计价货币 / 人）：默认 <b>12</b>。
   *
   * <p>★ 它是<b>GM 可调默认值</b>（不是物理常数）：与创世禀赋量级一致 —— 一个出借人必须先把"自己未覆盖的自然需求按市场价折算" 留出来，再额外留 {@code 12
   * 毫银/人} 的口粮/缓冲钱，剩下的才算可借货币。防的是"把出借人自己的口粮钱借空"，不是禁止 货币出借。参数目录落地后迁入 GM 参数表。
   */
  static final long LENDER_MONEY_BUFFER_PER_CAPITA_MILLI = 12L;

  /**
   * ★★ <b>运输损耗的记账账户</b>：{@code ProductionLedger.losses} 的键是 {@code IndustryId}，而运输不是产业 ——
   * 用一个**具名伪账户**把在途损耗与生产损耗分开（两者都进 ΣLoss，但读账分得清是谁的）。
   */
  static final IndustryId TRANSPORT_LOSS_ACCOUNT = new IndustryId("market-transport");

  /** 市场日志（market 分类）：开市/闭市/轮次。逐笔成交在 EconomySettlement 从报告产出（同一事实只拼一次）。 */
  static final org.slf4j.Logger MARKET = EconomyLog.market();
}
