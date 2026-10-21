package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketIndexBook.MarketIndexes;
import static io.mosire.simos.economy.time.MarketIndexBook.hexKeyOf;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketReadoutBook.remainingOfSells;
import static io.mosire.simos.economy.time.MarketRouteMatcher.matchRoute;
import static io.mosire.simos.economy.time.MarketShipmentBook.blockLaneWithoutCapacity;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketPortBook {

  private MarketPortBook() {}

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
  static void matchAcrossRegions(MatchContext ctx, MarketIndexes indexes) {
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
      // ★★ W4：跨区的买方格序 = 结算序（买方所在区的名次 → (q, r)）—— 与区内/区序同一口径，
      //   否则"区之间按总 GDP 排序"只作用于区内路径、跨区路径仍按 (q, r)（同一件事两处拼写必然漂开）。
      buyerHexes.sort(
          Comparator.comparingInt(
                  (HexCoord hex) ->
                      ctx.ordering.zoneRankOf(ctx.topology.regionOf(hex).node().nodeId()))
              .thenComparingInt(HexCoord::q)
              .thenComparingInt(HexCoord::r));
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
          //   ★★★ W7（2026-10-11）：判据加上"**全池**也没有可交付服务"这一半（用户裁定：运力提供全市场区共享）——
          //   发货格没有承运方但别格有货时，这条车道必须**建起来**（需求才有被接单的机会）。
          boolean noLocalTransportCapacity =
              ctx.transportRoundPlan != null
                  ? !ctx.transportPlanHasLocalCapacity(sellerHex)
                  : (!ctx.carrierPool.hasCapacityAt(sellerHex)
                      && ctx.carrierPool.totalCapacityMilli() <= 0L);
          if (noLocalTransportCapacity) {
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
  static String portLaneKey(String sellerZoneId, String buyerZoneId, CommodityId commodity) {
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
  static Map<String, Long> buildPortBudgets(
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
  static boolean hasActiveBuyInRegion(MarketIndexes indexes, String zoneId, CommodityId commodity) {
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
  static void requirePortZoneKeysAligned(MatchContext ctx, PortEnforcementInput port) {
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
  static void requirePortTaxZoneKeysAligned(MatchContext ctx) {
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
  static void logPortLaneGated(
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
  static void logPortLaneEvaluated(
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
  static void logPortRouteExhausted(
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

  /**
   * ★★ <b>W2-fix：把"被口岸闸拦下的买家侧需求量"按 买方区 × 商品 累加</b>（唯一写入点 = {@code matchRoute} 的 "闸用尽且还有合格需求"那一支；见
   * {@link MatchContext#portBlockedMilliByZoneCommodity}）。
   *
   * <p>★ 这是**读数**，不是算式：量本身**本来就在 D 里**（D 取自买槽 {@code order.quantity()} 全额，口岸闸只写 {@code
   * remaining}/{@code blocked}）⇒ 回加进 D 就是重复计数，v3.5 §2.1 明令不新增口岸项。它只让 {@code
   * MARKET_DEMAND_COMPOSITION} 那一行的 {@code blockedByPortMilli} 有值可读。
   *
   * <p>★ 入参 {@code blockedMilli} 必须是"闸拦下的"那一份（调用方已按合格需求与未满足量封顶）；{@code <= 0} ⇒ 一个键都不建（缺省世界无口岸面 ⇒
   * 这张表恒空 ⇒ 逐值不变、日志恒 0）。
   */
  static void recordPortBlockedDemand(
      MatchContext ctx, HexCoord buyerHex, CommodityId commodity, long blockedMilli) {
    if (blockedMilli <= 0L || buyerHex == null || ctx.topology == null) {
      return;
    }
    // ★ 键 = **买方所在区**（与 adaptPrices 的 D 累加器同一条归属口径：D 也按买方所在区汇总）。
    MarketZoneId zoneId = new MarketZoneId(ctx.topology.regionOf(buyerHex).node().nodeId());
    ctx.portBlockedMilliByZoneCommodity
        .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
        .merge(commodity, blockedMilli, Math::addExact);
  }

  static boolean hasActiveBuyAtHex(MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    for (BuySlot buy : indexedBuysAtHex(indexes, hex, commodity)) {
      if (buy.remaining > 0 && !buy.noMoney) {
        return true;
      }
    }
    return false;
  }

  static List<BuySlot> activeBuysAtHex(MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    List<BuySlot> result = new ArrayList<>();
    for (BuySlot buy : indexedBuysAtHex(indexes, hex, commodity)) {
      if (buy.remaining > 0 && !buy.noMoney) {
        result.add(buy);
      }
    }
    return result;
  }

  static List<BuySlot> indexedBuysAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    return indexes
        .buysByHexCommodity
        .getOrDefault(hexKeyOf(hex), Map.of())
        .getOrDefault(commodity, List.of());
  }

  static boolean hasActiveSellAtHex(MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    for (SellSlot sell : indexedSellsAtHex(indexes, hex, commodity)) {
      if (sell.remaining > 0) {
        return true;
      }
    }
    return false;
  }

  static List<SellSlot> activeSellsAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    List<SellSlot> result = new ArrayList<>();
    for (SellSlot sell : indexedSellsAtHex(indexes, hex, commodity)) {
      if (sell.remaining > 0) {
        result.add(sell);
      }
    }
    return result;
  }

  static List<SellSlot> indexedSellsAtHex(
      MarketIndexes indexes, HexCoord hex, CommodityId commodity) {
    return indexes
        .sellsByHexCommodity
        .getOrDefault(hexKeyOf(hex), Map.of())
        .getOrDefault(commodity, List.of());
  }

  static long moveCostOf(MatchContext ctx, HexCoord hex) {
    return ctx.moveCostCache.computeIfAbsent(hex, key -> (long) ctx.topology.moveCostAt(key));
  }
}
