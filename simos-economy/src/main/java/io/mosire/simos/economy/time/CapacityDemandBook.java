package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>M-A2：一轮的运力需求簿（逐轮瞬态；每需求一份 → 逐 hex / 逐区汇总）</b>。
 *
 * <p>★★ <b>逐条冻结口径的落点</b>：
 *
 * <pre>
 * K-A  单户<b>每需求一个商品</b>产生<b>一份</b>运力需求 ⇒ {@link #record} 一次 = 一份；"份数"按
 *      (家户, 商品) <b>去重计数</b>（同一户同一商品在同轮多条 lane 上反复试着买，仍然只是一份需求）
 * K-B  算完汇总总运力需求（该 hex / 该区）⇒ 逐<b>需求格</b>、逐<b>发货格</b>、逐<b>区</b>三张汇总 + 轮总
 * K-5  量纲 = 数量 × 距离，沿用既有运费口径 ⇒ {@link CapacityDemand#workPerGoodPerMille}（不新造量纲）
 * ★    缺口 = 该发货格的运力需求 − 该格的运力预算（{@link MerchantCapacityPool#totalCapacityAt}）——
 *      运力不够的部分<b>不成交、不成债、不计价</b>（K-4/Q-27 由 {@code executeTrade} 的收缩与
 *      {@code LOGISTICS_CAPACITY} 具名拦下落实；本簿只把它读数化 + 具名化）
 * </pre>
 *
 * <p>★★ <b>它不改任何账</b>：本簿只累加读数与日志，不写状态、不铸转移、不参与任何判据（守恒与铁律 2 不受影响）。 运力"不可储存、不可转卖"（H-E）⇒ 买卖只能逐 lane
 * 现买现用，本簿的"总需求"因此是<b>供需读数/缺口归因</b>， 不是"先囤总量再分发"的配额表。
 *
 * <p>★ <b>只在协调器单线程路径上写</b>：{@link #record} 的唯一调用点是 {@code MarketSettlement.executeTrade} 的
 * 跨格分支（{@code route != null}）—— 那条路径只在"有运力池 ⇒ 协调器串行撮合"时到达（worker 的 {@code executeTrade} 恒传 {@code
 * route == null}）。与 {@code MatchContext} 里其他只读汇总同一条纪律：worker 本地副本上的累加在交回时丢弃。
 *
 * <p>★ <b>候选生成处"格无运力"的拦下不记在这里</b>：那个量是撮合前买卖余量之和（{@code blockLaneWithoutCapacity}），
 * 记了会与逐笔请求重复计数；它的货物侧由 {@code MarketSettlement.goodsBlockedByCapacity}（V-20 的既有读数）覆盖。
 *
 * <p>★ 日志（§一.9）：INFO = 本轮供需与成交汇总（逐发货格 / 逐需求格 / 逐区 / 轮总）；DEBUG = 判据（缺口为什么存在）； TRACE = 逐份需求。全部带
 * {@code day}（tick 算法）。
 *
 * <p>★★ <b>A3（2026-10-10）分类：<b>降级为只读</b>（保持）</b>—— 设计书 §3.4 列它为"为私有门槛服务的读数"；
 * <b>代码事实</b>（§四）是：它<b>本来就只是读数</b>，A3 之后<b>仍然只是读数</b>：
 *
 * <pre>
 * 判据面  它**不参与任何判据**：唯一调用点是 {@code executeTrade} 里的 {@code ctx.capacityDemands.record(...)}
 *         （受理量/获承运量/work 系数三个读数），返回值无人使用；不写状态、不铸转移、不影响守恒（本类 :31-32 原文）。
 * 读口    {@code logRoundSummary} 的 INFO/DEBUG/TRACE 三档（§一.9）。
 * ⇒ 删它只会损失"运力需求与缺口"的读数，不会改变一个数 ⇒ **保留为只读**，并把"只是读数"写在类注里（本节）。
 * ★ 唯一需要留意的词：类名里的 "capacity" 现在指**服务量**（毫服务 = 毫商品·程），不是"运力池配额"——
 *   A2 起运力的权威是"手上的服务货"（{@code MerchantCapacityPool} 的服务口径 / I-H2）。
 * </pre>
 */
final class CapacityDemandBook {

  /** 需求簿日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /** 一份需求的身份：(家户, 商品)。同一户同一商品的多条 lane 尝试 = 同一份需求。 */
  private record DemandKey(HouseholdId household, CommodityId commodity) {
    private DemandKey {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(commodity, "commodity");
    }
  }

  /** 一个格/一个区的汇总（可变，只在本轮内累加）。 */
  private static final class Bucket {
    private final Set<DemandKey> instances = new LinkedHashSet<>();
    private long requestedQuantityMilli;
    private long servedQuantityMilli;
    private long demandWorkMilli;
    private long servedWorkMilli;

    private void add(DemandKey key, long requested, long served, long demandWork, long servedWork) {
      instances.add(key);
      requestedQuantityMilli = Math.addExact(requestedQuantityMilli, requested);
      servedQuantityMilli = Math.addExact(servedQuantityMilli, served);
      demandWorkMilli = Math.addExact(demandWorkMilli, demandWork);
      servedWorkMilli = Math.addExact(servedWorkMilli, servedWork);
    }
  }

  // 键序 = 首次出现的规范序（保序；禁 Map.copyOf/Set.copyOf —— I7）。
  private final Map<HexCoord, Bucket> byBuyerHex = new LinkedHashMap<>();
  private final Map<HexCoord, Bucket> byShippingHex = new LinkedHashMap<>();
  private final Map<String, Bucket> byRegionId = new LinkedHashMap<>();
  private final Bucket total = new Bucket();

  /**
   * ★★ <b>记一份运力需求</b>（K-A：单个家户每需求一个商品一份）。
   *
   * @param household 需求方家户
   * @param buyerHex 需求格（家户所在格）
   * @param regionId 需求方所在市场区（规范 id）
   * @param commodity 被需求的商品
   * @param shippingHex 发货格（= 运力池所在格；本条 lane 的承运方出自这里）
   * @param requestedQuantityMilli 本条 lane 上请求承运的量（毫商品；含最终被运力截断的部分）
   * @param servedQuantityMilli 实际获承运的量（毫商品；运力不足 ⇒ &lt; 请求量）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand#workPerGoodPerMille}）
   */
  void record(
      HouseholdId household,
      HexCoord buyerHex,
      String regionId,
      CommodityId commodity,
      HexCoord shippingHex,
      long requestedQuantityMilli,
      long servedQuantityMilli,
      long workPerGoodPerMille) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(buyerHex, "buyerHex");
    Objects.requireNonNull(regionId, "regionId");
    Objects.requireNonNull(commodity, "commodity");
    Objects.requireNonNull(shippingHex, "shippingHex");
    long requested = Math.max(0L, requestedQuantityMilli);
    long served = Math.min(requested, Math.max(0L, servedQuantityMilli));
    long demandWork = CapacityDemand.workMilliOf(requested, workPerGoodPerMille);
    long servedWork = CapacityDemand.workMilliOf(served, workPerGoodPerMille);
    DemandKey key = new DemandKey(household, commodity);
    bucket(byBuyerHex, buyerHex).add(key, requested, served, demandWork, servedWork);
    bucket(byShippingHex, shippingHex).add(key, requested, served, demandWork, servedWork);
    bucket(byRegionId, regionId).add(key, requested, served, demandWork, servedWork);
    total.add(key, requested, served, demandWork, servedWork);
    if (EconomyLog.trace().isTraceEnabled()) {
      EventLog.channel(EconomyLog.trace())
          .trace(
              LogEvent.of(
                  "CAPACITY_DEMAND_INSTANCE",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "household",
                  household.value(),
                  "commodity",
                  commodity.value(),
                  "buyerHex",
                  buyerHex,
                  "region",
                  regionId,
                  "shippingHex",
                  shippingHex,
                  "requestedQuantityMilli",
                  requested,
                  "servedQuantityMilli",
                  served,
                  "workPerGoodPerMille",
                  workPerGoodPerMille,
                  "demandWorkMilli",
                  demandWork,
                  "servedWorkMilli",
                  servedWork));
    }
  }

  /** 本轮的运力需求份数（(家户, 商品) 去重）。 */
  long demandInstances() {
    return total.instances.size();
  }

  /** 本轮运力需求总量（毫商品·程）。 */
  long demandWorkMilli() {
    return total.demandWorkMilli;
  }

  /** 本轮获服务的运力量（毫商品·程）。 */
  long servedWorkMilli() {
    return total.servedWorkMilli;
  }

  /**
   * ★★ <b>本轮运力供需与成交汇总</b>（INFO = 发生了什么 + 具名计数；DEBUG = 缺口为什么存在）。
   *
   * @param day 当前日（tick 事件必带）
   * @param pool 本轮逐 hex 运力池（取"该发货格的运力预算"与"有没有提供者"）
   * @param goodsBlockedByCapacityMilli 本轮因运力未获服务的货物量（毫商品；= 各买槽 {@code capacityTruncatedMilli}
   *     之和，V-20 的既有读数 —— 那部分不成交、不成债、不计价）
   */
  void logRoundSummary(long day, MerchantCapacityPool pool, long goodsBlockedByCapacityMilli) {
    Objects.requireNonNull(pool, "pool");
    if (byBuyerHex.isEmpty() && pool.isEmpty()) {
      return; // 既没有运力也没有运力需求 ⇒ 本轮无话可说（不刷空行）
    }
    if (LOG.isInfoEnabled()) {
      for (Map.Entry<HexCoord, Bucket> entry : byShippingHex.entrySet()) {
        HexCoord hex = entry.getKey();
        Bucket bucket = entry.getValue();
        long budget = pool.totalCapacityAt(hex);
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "CAPACITY_DEMAND_SUPPLY_HEX",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "shippingHex",
                    hex,
                    "demandInstances",
                    bucket.instances.size(),
                    "demandWorkMilli",
                    bucket.demandWorkMilli,
                    "servedWorkMilli",
                    bucket.servedWorkMilli,
                    "unservedWorkMilli",
                    bucket.demandWorkMilli - bucket.servedWorkMilli,
                    "capacityBudgetMilli",
                    budget,
                    "gapMilli",
                    Math.max(0L, bucket.demandWorkMilli - budget),
                    "providerHouseholds",
                    pool.householdCountAt(hex),
                    "priced",
                    pool.isPriced()));
      }
      for (Map.Entry<HexCoord, Bucket> entry : byBuyerHex.entrySet()) {
        Bucket bucket = entry.getValue();
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "CAPACITY_DEMAND_BUYER_HEX",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "buyerHex",
                    entry.getKey(),
                    "demandInstances",
                    bucket.instances.size(),
                    "demandQuantityMilli",
                    bucket.requestedQuantityMilli,
                    "servedQuantityMilli",
                    bucket.servedQuantityMilli,
                    "demandWorkMilli",
                    bucket.demandWorkMilli,
                    "servedWorkMilli",
                    bucket.servedWorkMilli));
      }
      for (Map.Entry<String, Bucket> entry : byRegionId.entrySet()) {
        Bucket bucket = entry.getValue();
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "CAPACITY_DEMAND_REGION",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "region",
                    entry.getKey(),
                    "demandInstances",
                    bucket.instances.size(),
                    "demandQuantityMilli",
                    bucket.requestedQuantityMilli,
                    "servedQuantityMilli",
                    bucket.servedQuantityMilli,
                    "demandWorkMilli",
                    bucket.demandWorkMilli,
                    "servedWorkMilli",
                    bucket.servedWorkMilli,
                    "unservedWorkMilli",
                    bucket.demandWorkMilli - bucket.servedWorkMilli));
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "CAPACITY_DEMAND_TOTAL",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "demandInstances",
                  demandInstances(),
                  "demandQuantityMilli",
                  total.requestedQuantityMilli,
                  "servedQuantityMilli",
                  total.servedQuantityMilli,
                  "demandWorkMilli",
                  demandWorkMilli(),
                  "servedWorkMilli",
                  servedWorkMilli(),
                  "unservedWorkMilli",
                  demandWorkMilli() - servedWorkMilli(),
                  "goodsBlockedByCapacityMilli",
                  goodsBlockedByCapacityMilli,
                  "priced",
                  pool.isPriced(),
                  "maxAskPerMille",
                  pool.maxAskPerMille(),
                  "supplyWorkBudgetMilli",
                  pool.totalCapacityMilli()));
    }
    if (LOG.isDebugEnabled()) {
      for (Map.Entry<HexCoord, Bucket> entry : byShippingHex.entrySet()) {
        Bucket bucket = entry.getValue();
        long unserved = bucket.demandWorkMilli - bucket.servedWorkMilli;
        if (unserved <= 0L) {
          continue;
        }
        long budget = pool.totalCapacityAt(entry.getKey());
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "CAPACITY_DEMAND_GAP_WHY",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "shippingHex",
                    entry.getKey(),
                    "unservedWorkMilli",
                    unserved,
                    "capacityBudgetMilli",
                    budget,
                    "remainingBudgetMilli",
                    pool.remainingCapacityAt(entry.getKey()),
                    "providerHouseholds",
                    pool.householdCountAt(entry.getKey()),
                    "reason",
                    pool.householdCountAt(entry.getKey()) <= 0L
                        ? "shipping-hex-has-no-merchant-household"
                        : "capacity-budget-below-demand-or-out-of-derived-radius"));
      }
    }
  }

  /** 取（必要时建）一份汇总桶：键序 = 首次出现序（保序，I7）。 */
  private static <K> Bucket bucket(Map<K, Bucket> table, K key) {
    return table.computeIfAbsent(key, ignored -> new Bucket());
  }
}
