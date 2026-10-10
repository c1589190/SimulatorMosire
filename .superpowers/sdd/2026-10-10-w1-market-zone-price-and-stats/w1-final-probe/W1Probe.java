import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** W1 收尾批的判别力探针：直接走被测代码路径，不经过 JUnit / Maven test 生命周期。 */
public final class W1Probe {
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CommodityId TOOL = new CommodityId("tool");
  private static final MarketZoneId MARKET = new MarketZoneId("probe-derived-market");
  private static final HexCoord MARKET_HEX = new HexCoord(0, 0);
  private static final MarketZoneId ZONE = new MarketZoneId("probe-zone");
  private static final HexCoord ZONE_HEX = new HexCoord(2, 0);

  public static void main(String[] args) {
    String mode = args.length > 0 ? args[0] : "all";
    if (mode.equals("codec") || mode.equals("all")) {
      codec("A 无市场载荷（旧夹具形态）", EconomyData.empty());
      codec("B 有市场载荷（新夹具形态）", withMarkets());
    }
    if (mode.equals("changeset") || mode.equals("all")) {
      changeSet();
    }
  }

  private static void codec(String label, EconomyData data) {
    EconomyCodec codec = new EconomyCodec();
    String json;
    try {
      json =
          codec.encodeSnapshot(
              new EconomySnapshot(
                  new StateRef(new BranchId("main"), new RevisionId(1)),
                  SimosTimestamp.of(10),
                  data));
    } catch (RuntimeException e) {
      System.out.println("[codec] " + label + " | encode THREW " + e.getClass().getSimpleName());
      return;
    }
    boolean leaked = json.contains("\"empty\"");
    String decode;
    try {
      codec.decodeSnapshot(json);
      decode = "ok";
    } catch (RuntimeException e) {
      String msg = String.valueOf(e.getMessage());
      int nl = msg.indexOf('\n');
      decode = e.getClass().getSimpleName() + ": " + (nl < 0 ? msg : msg.substring(0, nl));
    }
    System.out.println(
        "[codec] "
            + label
            + " | snapshot keys marketTables="
            + json.contains("marketTables")
            + " marketRoundStats="
            + json.contains("marketRoundStats")
            + " | contains \"empty\"="
            + leaked
            + " | decode="
            + decode);
  }

  private static void changeSet() {
    EconomyData base = EconomyData.empty();
    EconomyData target = withMarkets();
    EconomyChangeSet cs = EconomyChangeSet.between(base, target);
    System.out.println(
        "[changeset] changed: marketZones="
            + cs.marketZones().changed()
            + " marketTables="
            + cs.marketTables().changed()
            + " marketRoundStats="
            + cs.marketRoundStats().changed()
            + " | cs.isEmpty="
            + cs.isEmpty());
    EconomyCodec codec = new EconomyCodec();
    String changeJson = codec.encodeChangeSet(cs);
    System.out.println(
        "[changeset] changeSet 线格式 keys marketTables="
            + changeJson.contains("marketTables")
            + " marketRoundStats="
            + changeJson.contains("marketRoundStats")
            + " contains \"empty\"=false-check="
            + changeJson.contains("\"empty\""));
    EconomyData back = EconomyChangeSet.apply(cs, base);
    System.out.println("[changeset] apply(cs, base).equals(target)=" + back.equals(target));
    System.out.println(
        "[changeset] 逐字段: tables.keys="
            + back.marketTables().keySet()
            + " stats.keys="
            + back.marketRoundStats().keySet()
            + " derived.prices="
            + back.marketTables().get(MARKET).prices().size()
            + " derived.isFree(tool)="
            + back.marketTables().get(MARKET).isFree(TOOL)
            + " persistent.hexes="
            + back.marketTables().get(ZONE).hexes().size()
            + " stats.last/cycle/prev="
            + back.marketRoundStats().get(MARKET).lastRound().size()
            + "/"
            + back.marketRoundStats().get(MARKET).cycleToDate().size()
            + "/"
            + back.marketRoundStats().get(MARKET).previousCycle().size());
  }

  private static EconomyData withMarkets() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 12L);
    prices.put(CLOTH, 5L);
    prices.put(TOOL, 0L);
    MarketTable derived = new MarketTable(SILVER, Set.of(MARKET_HEX), prices);
    MarketTable persistent = new MarketTable(SILVER, Set.of(), Map.of(GRAIN, 12L, CLOTH, 5L));
    MarketZone zone = new MarketZone(ZONE, ZONE_HEX, 0, Set.of(ZONE_HEX), SILVER, Map.of());
    return EconomyData.empty()
        .withMarketZones(Map.of(ZONE, zone))
        .withMarketTables(Map.of(MARKET, derived, ZONE, persistent))
        .withMarketRoundStats(
            Map.of(MARKET, stats(9L, 3_200L, 6_400L, 5_601L), ZONE, stats(11L, 900L, 1_800L, 1_200L)));
  }

  private static MarketRoundStats stats(
      long day, long lastRoundTurnover, long cycleTurnover, long previousTurnover) {
    return new MarketRoundStats(
        day,
        Map.of(GRAIN, round(1_000L, 900L, 800L, 12L, 100L), CLOTH, round(500L, 480L, 470L, 5L, 30L)),
        lastRoundTurnover,
        Map.of(
            GRAIN, round(2_000L, 1_800L, 1_600L, 12L, 200L), CLOTH, round(900L, 880L, 860L, 5L, 40L)),
        cycleTurnover,
        Map.of(
            GRAIN, round(3_000L, 2_800L, 2_700L, 12L, 150L), CLOTH, round(700L, 690L, 680L, 5L, 20L)),
        previousTurnover);
  }

  private static CommodityRound round(
      long demand, long supply, long traded, long averagePrice, long unmet) {
    return new CommodityRound(demand, supply, traded, averagePrice, unmet);
  }
}
