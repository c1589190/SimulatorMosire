package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D-032 C7b2：{@code MarketRoundStats.lastTransportByHex} 非空往返与旧构造器空表语义。 */
class MarketRoundStatsTransportTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final MarketZoneId ZONE = new MarketZoneId("stats-zone");

  @Test
  void nonEmptyTransportObservationSurvivesRoundAndIsImmutable() {
    TransportHexObservation observation =
        new TransportHexObservation(ZONE, H, 5L, 100L, 80L, 60L, 40L, 20L, 0L);
    MarketRoundStats stats =
        MarketRoundStats.EMPTY.withRound(5L, Map.of(), 0L, 0L, false, Map.of(H, observation));

    assertThat(stats.lastTransportByHex()).containsExactly(Map.entry(H, observation));
    assertThat(stats.lastTransportByHex()).as("返回的是不可变冻结表").isUnmodifiable();
  }

  @Test
  void oldConstructorAndOldWithRoundKeepEmptyTransportObservation() {
    MarketRoundStats oldConstructor =
        new MarketRoundStats(5L, Map.of(), 0L, 0L, Map.of(), 0L, 0L, Map.of(), 0L, 0L);
    assertThat(oldConstructor.lastTransportByHex()).isEmpty();

    MarketRoundStats oldWithRound = MarketRoundStats.EMPTY.withRound(5L, Map.of(), 0L, 0L, false);
    assertThat(oldWithRound.lastTransportByHex()).isEmpty();
  }

  @Test
  void replacingObservationsDoesNotRewriteCommodityWindows() {
    MarketRoundStats base = MarketRoundStats.EMPTY.withRound(5L, Map.of(), 11L, 7L, false);
    MarketRoundStats replaced =
        base.withTransportObservations(
            Map.of(H, new TransportHexObservation(ZONE, H, 5L, 1L, 1L, 0L, 1L, 1L, 0L)));

    assertThat(replaced.lastRoundDay()).isEqualTo(5L);
    assertThat(replaced.lastRoundTurnoverMilli()).isEqualTo(11L);
    assertThat(replaced.lastRoundCashTurnoverMilli()).isEqualTo(7L);
    assertThat(replaced.lastTransportByHex()).hasSize(1);
  }
}
