import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Method;

/** W1 收尾批：反射取 EconomyCodecTest.fullData()（private），逐条求值新增断言面。 */
public final class W1CodecFixtureProbe {
  private static final MarketZoneId MARKET = new MarketZoneId("codec-derived-market");
  private static final MarketZoneId ZONE = new MarketZoneId("codec-persistent-zone");

  public static void main(String[] args) throws Exception {
    Method m = Class.forName("io.mosire.simos.economy.codec.EconomyCodecTest").getDeclaredMethod("fullData");
    m.setAccessible(true);
    EconomyData data = (EconomyData) m.invoke(null); // 构造期守卫不过 ⇒ 这里就抛

    p("fullData() 构造不抛（跨表守卫全过）", true);
    p("marketTables hasSize 2", data.marketTables().size() == 2);
    p("MARKET 表 hexes 非空（派生形态）", !data.marketTables().get(MARKET).hexes().isEmpty());
    p("MARKET 表 hasPrice(tool)", data.marketTables().get(MARKET).hasPrice(new io.mosire.simos.economy.api.id.CommodityId("tool")));
    p("MARKET 表 isFree(tool)", data.marketTables().get(MARKET).isFree(new io.mosire.simos.economy.api.id.CommodityId("tool")));
    p("MARKET 表 prices 3", data.marketTables().get(MARKET).prices().size() == 3);
    p("ZONE 表 hexes 为空（持久形态）", data.marketTables().get(ZONE).hexes().isEmpty());
    p("ZONE 表 numeraire == 区法定币", data.marketTables().get(ZONE).numeraire().equals(data.marketZones().get(ZONE).legalTender()));
    p("marketRoundStats hasSize 2", data.marketRoundStats().size() == 2);
    boolean seg = true;
    for (var s : data.marketRoundStats().values()) {
      seg &= s.lastRound().size() == 2 && s.cycleToDate().size() == 2 && s.previousCycle().size() == 2;
    }
    p("每区三段窗口各两个商品", seg);

    EconomyCodec codec = new EconomyCodec();
    String snap =
        codec.encodeSnapshot(
            new EconomySnapshot(
                new StateRef(new BranchId("main"), new RevisionId(3)),
                SimosTimestamp.of(10, "弘光元年"),
                data));
    String snap2 = codec.encodeSnapshot(codec.decodeSnapshot(snap));
    p("快照字节级往返", snap2.equals(snap));
    EconomyChangeSet cs = EconomyChangeSet.between(EconomyData.empty(), data);
    String chg = codec.encodeChangeSet(cs);
    String chg2 = codec.encodeChangeSet(codec.decodeChangeSet(chg));
    p("变更集字节级往返", chg2.equals(chg));
    p("快照线格式含 marketTables/marketRoundStats", snap.contains("marketTables") && snap.contains("marketRoundStats"));
    p("变更集线格式含 marketTables/marketRoundStats", chg.contains("marketTables") && chg.contains("marketRoundStats"));
    p("快照含派生市场 id 字面量", snap.contains("codec-derived-market") && snap.contains("codec-persistent-zone"));
    p("变更集含派生市场 id 字面量", chg.contains("codec-derived-market") && chg.contains("codec-persistent-zone"));
    p("快照不含 \"empty\"", !snap.contains("\"empty\""));
    p("变更集不含 \"empty\"", !chg.contains("\"empty\""));
    System.out.println("=== 全部为 true ⇒ codec 测试新增断言面在原件上全绿 ===");
  }

  private static void p(String what, boolean ok) {
    System.out.println((ok ? "GREEN " : "**RED** ") + what);
  }
}
