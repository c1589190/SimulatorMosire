package io.mosire.simos.economy.change;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import java.util.List;
import java.util.Map;

/** W1 收尾批：用**测试夹具本身**核对新增断言面（不跑 JUnit，只逐条求值）。 */
public final class W1FixtureProbe {
  private static final MarketZoneId MARKET = new MarketZoneId("market-round-trip");
  private static final MarketZoneId ZONE_EAST = new MarketZoneId("zone-east");
  private static final CommodityId TOOL = new CommodityId("tool");

  public static void main(String[] args) {
    MarketTable derived = EconomyRoundTripTest.market();
    MarketTable persistent = EconomyRoundTripTest.zoneMarket();
    Map<MarketZoneId, MarketTable> tables = Map.of(MARKET, derived, ZONE_EAST, persistent);
    Map<MarketZoneId, MarketRoundStats> stats = EconomyRoundTripTest.statsBook();
    var zoneEast = EconomyRoundTripTest.zoneEast();

    p("派生形态 hexes 非空", !tables.get(MARKET).hexes().isEmpty());
    p("派生形态含明确 0 价那一行 hasPrice(tool)", tables.get(MARKET).hasPrice(TOOL));
    p("派生形态 isFree(tool)", tables.get(MARKET).isFree(TOOL));
    p("派生形态 prices 三个商品", tables.get(MARKET).prices().size() == 3);
    p("持久形态 hexes 为空", tables.get(ZONE_EAST).hexes().isEmpty());
    p("持久形态 numeraire == 该区法定币", tables.get(ZONE_EAST).numeraire().equals(zoneEast.legalTender()));
    p("多区统计簿 size==2", stats.size() == 2);
    boolean seg = true;
    for (MarketRoundStats s : stats.values()) {
      seg &= s.lastRound().size() == 2 && s.cycleToDate().size() == 2 && s.previousCycle().size() == 2;
    }
    p("每区三段窗口各两个商品", seg);
    p("两区统计不是同一份值", !stats.get(MARKET).equals(stats.get(ZONE_EAST)));

    EconomyData base = EconomyData.empty();
    EconomyData target =
        base.withMarketZones(Map.of(ZONE_EAST, zoneEast))
            .withMarketTables(tables)
            .withMarketRoundStats(stats);
    EconomyChangeSet cs = EconomyChangeSet.between(base, target);
    p("cs.marketZones.changed", cs.marketZones().changed());
    p("cs.marketTables.changed", cs.marketTables().changed());
    p("cs.marketRoundStats.changed", cs.marketRoundStats().changed());
    EconomyData back = EconomyChangeSet.apply(cs, base);
    p("apply(cs,base).equals(target)", back.equals(target));
    p("back.marketZones onlyKeys ZONE_EAST", back.marketZones().keySet().equals(Map.of(ZONE_EAST, zoneEast).keySet()));
    p("back.marketTables onlyKeys MARKET,ZONE_EAST", back.marketTables().keySet().equals(tables.keySet()));
    p("back MARKET 表 == market()", back.marketTables().get(MARKET).equals(derived));
    p("back MARKET prices==3", back.marketTables().get(MARKET).prices().size() == 3);
    p("back MARKET hasPrice/isFree(tool)", back.marketTables().get(MARKET).hasPrice(TOOL) && back.marketTables().get(MARKET).isFree(TOOL));
    p("back ZONE_EAST 表 == zoneMarket()", back.marketTables().get(ZONE_EAST).equals(persistent));
    p("back 统计簿 == statsBook()", back.marketRoundStats().equals(stats));
    boolean segBack = true;
    for (MarketZoneId id : List.of(MARKET, ZONE_EAST)) {
      MarketRoundStats s = back.marketRoundStats().get(id);
      segBack &= s.lastRound().size() == 2 && s.cycleToDate().size() == 2 && s.previousCycle().size() == 2;
    }
    p("back 每区三段各两个商品", segBack);
    p("back 两区统计不同值", !back.marketRoundStats().get(MARKET).equals(back.marketRoundStats().get(ZONE_EAST)));

    // ── 逐组件用例的两条 mutate 分支（等价形态；mutate/withZoneAndItsTable 是 private，这里复刻同一份内容）──
    EconomyData mtTarget =
        EconomyData.empty()
            .withMarketZones(Map.of(ZONE_EAST, zoneEast))
            .withMarketTables(tables);
    EconomyChangeSet mtCs = EconomyChangeSet.between(EconomyData.empty(), mtTarget);
    p("[case marketTables] changed", mtCs.marketTables().changed());
    p("[case marketTables] 往返相等", EconomyChangeSet.apply(mtCs, EconomyData.empty()).equals(mtTarget));

    EconomyData rsTarget = EconomyData.empty().withMarketRoundStats(stats);
    EconomyChangeSet rsCs = EconomyChangeSet.between(EconomyData.empty(), rsTarget);
    p("[case marketRoundStats] changed", rsCs.marketRoundStats().changed());
    p("[case marketRoundStats] 往返相等", EconomyChangeSet.apply(rsCs, EconomyData.empty()).equals(rsTarget));

    // ── demands 分支：market() 改了（三个商品、含 0 价）之后登记格仍然自洽 ──
    EconomyData demandTarget =
        EconomyData.empty()
            .withMarketTables(Map.of(MARKET, derived))
            .withHouseholdDemands(Map.of(new DemandId("demand-1"), EconomyRoundTripTest.demand()));
    p("[case demands] 构造不抛 + 需求非空", demandTarget.demands().size() == 1);
    System.out.println("=== 全部为 true ⇒ 新增断言面在原件上全绿 ===");
  }

  private static void p(String what, boolean ok) {
    System.out.println((ok ? "GREEN " : "**RED** ") + what);
  }
}
