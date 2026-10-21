package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.TransportPriceAnchor;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D-032 C7a：市场区运输锚价——0 价合法、D>S 涨、D<=S 延迟降、floor 0、缺键只读 0。 */
class CarrierC7TransportPriceAnchorTest {

  private static final HexCoord H = new HexCoord(0, 0);
  private static final MarketZoneId ZONE = new MarketZoneId("c7a-zone");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  @Test
  void missingAnchorReadsZeroWithoutWritingState() {
    World world = world();
    assertThat(TransportPriceAnchorBook.pricePerWorkMilli(world.view, ZONE)).isZero();
    assertThat(world.sheet.transportPriceAnchors()).as("缺键读 0 是只读默认，不物化状态").isEmpty();
  }

  @Test
  void zeroPriceIsLegalAndDEqualsSKeepsItZero() {
    World world = world();
    TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, 5L, 0L, 0L);

    TransportPriceAnchor anchor = world.sheet.transportPriceAnchors().get(ZONE);
    assertThat(anchor).isNotNull();
    assertThat(anchor.pricePerWorkMilli()).as("0 是合法免费价，不得被 max(1,...) 改成 1").isZero();
    assertThat(anchor.satisfiedStreak()).isEqualTo(1L);
    assertThat(anchor.lastAdjustedMarketRound()).isEqualTo(5L);
  }

  @Test
  void shortageRaisesPriceAndSatisfiedRoundsDecreaseOnlyAfterDelayThenFloorAtZero() {
    World world = world();
    TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, 5L, 100L, 10L);
    long raised = world.sheet.transportPriceAnchors().get(ZONE).pricePerWorkMilli();
    assertThat(raised).as("D>S ⇒ 0 价退出免费态").isEqualTo(1L);

    TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, 10L, 1L, 100L);
    TransportPriceAnchor firstSatisfied = world.sheet.transportPriceAnchors().get(ZONE);
    assertThat(firstSatisfied.pricePerWorkMilli())
        .as("D<=S 首轮只累计 satisfiedStreak，不立即降价")
        .isEqualTo(raised);
    assertThat(firstSatisfied.satisfiedStreak()).isEqualTo(1L);

    TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, 15L, 1L, 100L);
    TransportPriceAnchor secondSatisfied = world.sheet.transportPriceAnchors().get(ZONE);
    assertThat(secondSatisfied.pricePerWorkMilli()).as("连续两轮满足后开始降价").isLessThan(raised);

    for (long round = 20L; round <= 100L; round += 5L) {
      TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, round, 1L, 100L);
    }
    assertThat(world.sheet.transportPriceAnchors().get(ZONE).pricePerWorkMilli())
        .as("降价地板是 0，跌破 0 即契约故障")
        .isZero();
  }

  @Test
  void currencyDriftAndRoundRollbackFailClosedWithoutHalfWrite() {
    World world = world();
    TransportPriceAnchorBook.recordMarketRound(world.sheet, world.view, ZONE, 10L, 10L, 1L);
    TransportPriceAnchor before = world.sheet.transportPriceAnchors().get(ZONE);

    assertThatThrownBy(
            () ->
                TransportPriceAnchorBook.recordMarketRound(
                    world.sheet, world.view, ZONE, 5L, 10L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("round");
    assertThat(world.sheet.transportPriceAnchors().get(ZONE)).as("失败不得半写").isEqualTo(before);
  }

  private static World world() {
    MarketZone zone = new MarketZone(ZONE, H, 0, Set.of(H), SILVER, Map.of(), 50L);
    MarketTable table = new MarketTable(SILVER, Set.of(), Map.of());
    EconomyData base =
        EconomyData.empty()
            .withMarketZones(Map.of(ZONE, zone))
            .withMarketTables(Map.of(ZONE, table));
    EconomySession session =
        new EconomySession(
            base, ProductionData.empty(), new ProductionRuntimeEngine(ProductionData.empty()));
    EconomyStateBuilder sheet = session.sheet();
    return new World(sheet, new WorkingDayView(sheet));
  }

  private record World(EconomyStateBuilder sheet, WorkingDayView view) {}
}
