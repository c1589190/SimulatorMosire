package io.mosire.simos.economy.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportHexObservation;
import io.mosire.simos.economy.model.TransportPriceAnchor;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-032 C6/C7：运输批次、锚价与 lastTransportByHex 的 Codec 往返。 */
class CarrierTransportCodecTest {

  private static final EconomyCodec CODEC = new EconomyCodec();
  private static final HexCoord H = new HexCoord(0, 0);
  private static final MarketZoneId ZONE = new MarketZoneId("codec-transport-zone");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-codec-carrier");
  private static final long ROUND = 5L;

  @Test
  void transportFieldsSurviveSnapshotCodecRoundTrip() {
    MarketZone zone = new MarketZone(ZONE, H, 0, Set.of(H), SILVER, Map.of(), 50L);
    MarketTable table = new MarketTable(SILVER, Set.of(), Map.of());
    TransportCapacityBatchKey batchKey = new TransportCapacityBatchKey(CARRIER, H, ROUND);
    TransportCapacityBatch batch = new TransportCapacityBatch(ZONE, 1_234L);
    TransportPriceAnchor anchor = new TransportPriceAnchor(ZONE, SILVER, 0L, 2L, 0L, ROUND);
    TransportHexObservation observation =
        new TransportHexObservation(ZONE, H, ROUND, 300L, 1_234L, 300L, 0L, 934L, 0L);
    MarketRoundStats stats =
        MarketRoundStats.EMPTY.withRound(ROUND, Map.of(), 0L, 0L, false, Map.of(H, observation));

    EconomyData data =
        EconomyData.empty()
            .withMarketZones(Map.of(ZONE, zone))
            .withMarketTables(Map.of(ZONE, table))
            .withTransportCapacityBatches(Map.of(batchKey, batch))
            .withTransportPriceAnchors(Map.of(ZONE, anchor))
            .withMarketRoundStats(Map.of(ZONE, stats));
    EconomySnapshot snapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(7)), SimosTimestamp.of(5L), data);

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back.data().transportCapacityBatches()).containsExactly(Map.entry(batchKey, batch));
    assertThat(back.data().transportPriceAnchors()).containsExactly(Map.entry(ZONE, anchor));
    assertThat(back.data().marketRoundStats().get(ZONE).lastTransportByHex())
        .containsExactly(Map.entry(H, observation));
    assertThat(back).isEqualTo(snapshot);
  }
}
