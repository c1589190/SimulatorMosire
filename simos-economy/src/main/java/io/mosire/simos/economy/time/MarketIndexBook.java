package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketMatcher.actorKeyOf;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketRosterBook.orderedCommodities;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketIndexBook {

  private MarketIndexBook() {}

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
  static final class MarketIndexes {

    final Map<String, Map<CommodityId, List<BuySlot>>> buysByRegionCommodity;
    final Map<String, Map<CommodityId, List<SellSlot>>> sellsByRegionCommodity;
    final Map<String, Map<CommodityId, List<BuySlot>>> buysByHexCommodity;
    final Map<String, Map<CommodityId, List<SellSlot>>> sellsByHexCommodity;
    final Map<CommodityId, List<HexCoord>> buyHexesByCommodity;
    final Map<CommodityId, List<HexCoord>> sellHexesByCommodity;
    final Map<String, Set<String>> adjacentRegionIds;
    final Map<String, List<Participant>> participantsByHex;
    final List<CommodityId> commodities;

    MarketIndexes(
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
        HexCoord hex = ctx.participantHex.get(participant.actor());
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
  static <K, V> void addIndexed(
      Map<String, Map<K, List<V>>> table, String outerKey, K innerKey, V value) {
    table
        .computeIfAbsent(outerKey, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(innerKey, ignored -> new ArrayList<>())
        .add(value);
  }

  // ── ★★ W4：结算序（S-4 / N-1；见 SettlementOrderBook 的口径）────────────────────────────────

  /**
   * ★★ <b>把买槽按结算序重排</b>（唯一拼写点）。排序键（全序，内容的纯函数）：
   *
   * <pre>
   * ① 区名次（S-4 的"区之间按上周期总 GDP"）升序 —— 未知名次排最后
   * ② 家户名次升序 —— ★ P3b：优先读本轮纯库存财富快照的 rank（钱 + 商品 + LAND，不扣债务）；
   *                 无快照时退回 W4 的净值 rank（资产 − 可定价债务；缺省中性）
   * ③ 格 (q, r) 升序 → ④ 买方 actor canonical 串 → ⑤ 商品 id → ⑥ 原下标
   * </pre>
   *
   * <p>★★ <b>为什么 ② 必须优先纯财富</b>（D-032 P3 §6.1/§10）：市场买方序只用"钱 + 货物 + 土地"，不扣债务、 不含工具/作坊/机器/船。{@link
   * SettlementOrderBook} 的净值/债务口径<b>保留给 ClassSlide</b>，不再决定买槽先后； 它只继续提供 ① 的区序和"没有纯财富快照"时的旧退路。
   *
   * <p>★ <b>为什么 ③~⑥ 必须有</b>：①②是"经济含义"的两级，但同一户可以在多格/多商品挂单 ⇒ 只按 ①② 排不是全序 （{@code List.sort}
   * 的稳定性会退化成"谁先被装配"= 写入史的函数）⇒ 用稳定 id 的 canonical 序兜底到全序。
   */
  static void sortBuySlotsByOrdering(MatchContext ctx) {
    List<BuySlot> buys = ctx.buys;
    if (buys.size() <= 1) {
      return;
    }
    MarketBuyerOrdering pureWealth = ctx.buyerOrdering;
    boolean pureWealthRanking = pureWealth != null && pureWealth.isActive();
    Map<BuySlot, Integer> originalIndex = new LinkedHashMap<>();
    for (int i = 0; i < buys.size(); i++) {
      originalIndex.put(buys.get(i), i);
    }
    Comparator<BuySlot> order =
        Comparator.comparingInt(
            (BuySlot buy) -> ctx.ordering.zoneRankOf(buy.region.node().nodeId()));
    order =
        pureWealthRanking
            ? order.thenComparingInt(buy -> pureWealth.rankOf(buy.buyer.household()))
            : order.thenComparingInt(buy -> ctx.ordering.householdRankOf(buy.buyer.household()));
    order =
        order
            .thenComparingInt(buy -> buy.hex.q())
            .thenComparingInt(buy -> buy.hex.r())
            .thenComparing(buy -> actorKeyOf(buy.buyer.actor()))
            .thenComparing(buy -> buy.order.commodity().value())
            .thenComparingInt(buy -> originalIndex.getOrDefault(buy, 0));
    buys.sort(order);
    if (MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_ORDER_APPLIED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "buySlots",
                  buys.size(),
                  "orderedHouseholds",
                  ctx.ordering.householdCount(),
                  "pureWealthHouseholds",
                  pureWealth == null ? 0 : pureWealth.rankedHouseholds(),
                  "householdRankSource",
                  pureWealthRanking ? "pure-inventory-wealth" : "net-worth-fallback",
                  "orderedZones",
                  ctx.ordering.zoneCount(),
                  "unpricedDebtContracts",
                  ctx.ordering.unpricedDebtContracts(),
                  "unpricedDebtAmountRawMilli",
                  ctx.ordering.unpricedDebtAmountRawMilli(),
                  "unpricedHouseholds",
                  ctx.ordering.unpricedHouseholds(),
                  "reason",
                  pureWealthRanking
                      ? "households-by-pure-inventory-wealth-desc+zones-by-previous-cycle-nominal-turnover-desc"
                      : "households-by-net-worth-desc-fallback-no-pure-snapshot+zones-by-previous-cycle-nominal-turnover-desc"));
    }
    if (MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_ORDER_WHY",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "zones",
                  ctx.ordering.describeZones(),
                  "householdRanking",
                  pureWealthRanking
                      ? "pure-inventory-wealth(money+goods-at-zone-price+land-at-zone-land-price;no-debt;no-tools)"
                      : "assets(money+inventory-at-zone-price)-minus-priced-debt-principal(fallback-no-pure-snapshot)",
                  "netWorthAudit",
                  "assets(money+inventory-at-zone-price)-minus-priced-debt-principal",
                  "zoneRanking",
                  "previous-cycle-nominal-turnover-then-cycle-to-date-then-zone-id",
                  "snapshot",
                  "pre-round(no-current-round-turnover)"));
    }
    if (MARKET.isTraceEnabled()) {
      EventLog.channel(MARKET)
          .trace(
              LogEvent.of(
                  "MARKET_ORDER_HOUSEHOLDS",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "pureWealthRanked",
                  pureWealthRanking ? pureWealth.describe() : List.of(),
                  "netWorthAudit",
                  ctx.ordering.describeHouseholds()));
    }
  }

  /** 每个商品一条按 {@code (q, r)} 升序的 distinct hex 表（与串行路径的 sort 口径逐字相同）。 */
  static Map<CommodityId, List<HexCoord>> sortedHexes(
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
  static String hexKeyOf(HexCoord hex) {
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
  static void applyProcurementPriority(MatchContext ctx, MarketIndexes indexes) {
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
                    slot -> slot.buyer.household(),
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
                    slot -> slot.seller.household(),
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
  static void logProcurementPriority(
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
}
