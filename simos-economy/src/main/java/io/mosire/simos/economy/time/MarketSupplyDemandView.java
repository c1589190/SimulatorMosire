package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>W1（2026-10-10）：每轮供需视图</b>（<b>现算派生、不落盘</b>）—— 按 <b>(市场, 商品)</b> 现算<b>本轮</b>的 <b>D /
 * S</b>，供读口/探针回答"眼下这一轮的供需长什么样"。
 *
 * <p>★★ <b>它与统计簿（{@code EconomyData.marketRoundStats}）是两件事</b>：
 *
 * <pre>
 * 统计簿    上一轮**已经发生**的读数（D/S 来自自适应定价的累加器 + 成交量/均价/未满足量来自成交流水）—— 持久、可回溯
 * 本视图    **当刻**按同一套订单生成现算的 D/S（"如果现在开一轮，供需是多少"）—— 不落盘、不进变更集、随读随算
 * </pre>
 *
 * <p>★★ <b>为什么口径不会漂</b>：本视图与结算走<b>同一条</b> {@code MarketSettlement.planOrders}（订单生成的唯一实现），
 * 不另写一套"需求估算"；逐区逐商品的 D = 买订单量之和、S = 卖订单 {@code sellable} 之和 —— 与 {@code MarketReadout} 的逐区读数同源。
 *
 * <p>★ <b>成本</b>：一次调用 = 逐市场逐商品的订单生成（与读口读一遍同量级）。它<b>不</b>在结算路径上，只被读口/探针调用。
 */
public final class MarketSupplyDemandView {

  private MarketSupplyDemandView() {}

  /**
   * ★★ <b>现算当刻供需</b>（不落盘）：市场 id →（商品 → {@link CommodityRound}，其中只填 D/S，其余三栏为 0）。
   *
   * @param round 当刻的市场轮视图（家户行/需求/库存…；与结算同一份只读输入）
   * @param view 当刻经济视图（价表/市场区）
   * @param topology 当刻区域拓扑（组合根装配）
   */
  public static Map<MarketZoneId, Map<CommodityId, CommodityRound>> compute(
      MarketSettlement.MarketRound round, EconomyDayView view, MarketTopology topology) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(topology, "topology");
    Map<String, List<io.mosire.simos.social.api.id.HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies());
    Map<MarketZoneId, Map<CommodityId, CommodityRound>> byMarket = new LinkedHashMap<>();
    for (MarketRegion region : topology.regions()) {
      MarketZoneId marketId = new MarketZoneId(region.node().nodeId());
      MarketTable table = view.marketTables().get(marketId);
      if (table == null) {
        continue;
      }
      Map<CommodityId, long[]> quantities = new LinkedHashMap<>();
      for (HexCoord hex : region.members()) {
        for (CommodityId commodity : table.prices().keySet()) {
          MarketSettlement.PlannedOrders orders =
              MarketSettlement.planOrders(round, hex, table, commodity, rowsByHex);
          long[] slot = quantities.computeIfAbsent(commodity, ignored -> new long[2]);
          for (var sell : orders.sells()) {
            slot[1] = Math.addExact(slot[1], sell.sellable());
          }
          for (var buy : orders.buys()) {
            slot[0] = Math.addExact(slot[0], buy.quantity());
          }
        }
      }
      Map<CommodityId, CommodityRound> perMarket = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, long[]> entry : quantities.entrySet()) {
        perMarket.put(
            entry.getKey(),
            new CommodityRound(entry.getValue()[0], entry.getValue()[1], 0L, 0L, 0L));
      }
      if (!perMarket.isEmpty()) {
        byMarket.put(marketId, Collections.unmodifiableMap(perMarket));
      }
    }
    return Collections.unmodifiableMap(byMarket);
  }

  /** ★ 逐市场一行摘要（日志/探针用）：市场 id + 逐商品的 D/S。 */
  public static List<String> describe(Map<MarketZoneId, Map<CommodityId, CommodityRound>> view) {
    Objects.requireNonNull(view, "view");
    List<String> lines = new java.util.ArrayList<>(view.size());
    for (MarketZoneId marketId : MarketTables.canonicalIdsOf(view.keySet())) {
      StringBuilder line = new StringBuilder(marketId.value()).append(' ');
      Map<CommodityId, CommodityRound> perMarket = view.get(marketId);
      Set<CommodityId> commodities = new LinkedHashSet<>(perMarket.keySet());
      List<CommodityId> ordered = new java.util.ArrayList<>(commodities);
      ordered.sort(java.util.Comparator.comparing(CommodityId::value));
      for (CommodityId commodity : ordered) {
        CommodityRound row = perMarket.get(commodity);
        line.append(commodity.value())
            .append(":D=")
            .append(row.demandMilli())
            .append("/S=")
            .append(row.supplyMilli())
            .append(' ');
      }
      lines.add(line.toString().trim());
    }
    return List.copyOf(lines);
  }
}
