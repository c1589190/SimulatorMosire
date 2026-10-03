package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import io.mosire.simos.economy.market.ProbeEconomy.TransportTeam;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** 运输队 T1：运费先付给承运队；运力不足的部分才落 escrow。 */
class TransportTeamProbeTest {

  @Test
  void transportFeeGoesToCarrierInsteadOfEscrow() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    initialPrice.put(Good.FIBER, 100L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 500L, 0L, 50L, 50L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H0-grain", 0, 0);
    economy.addHex("H2-buyer", 2, 0);

    Household grainSeller = new Household("G", "H0-grain").stock(Good.GRAIN, 100L);
    grainSeller.population = 0L;
    Household buyer = new Household("B", "H2-buyer").need(Good.GRAIN, 30L);
    buyer.population = 1L;
    Household carrier = new Household("T", "H2-buyer").need(Good.GRAIN, 0L);
    carrier.population = 0L;
    economy.addHousehold(grainSeller);
    economy.addHousehold(buyer);
    economy.addHousehold(carrier);

    TransportTeam team =
        economy.addTransportTeam(
            new TransportTeam("T1", carrier, "H0-grain", "H2-buyer", 100L));

    RoundResult round = economy.runRound(1);

    // H0→H2 距离 2：运费率 = 50‰ + 50‰×2 = 150‰；30 粮运费 = 30×15 = 450。
    assertThat(round.trades()).as("跨格成交").isNotEmpty();
    assertThat(team.lastUnitsMoved).as("承运 30 粮").isEqualTo(30L);
    assertThat(team.lastFeeEarned).as("运费 450 进运输队").isEqualTo(450L);
    assertThat(carrier.money).as("运输队家户收到 450").isEqualTo(450L);
    assertThat(round.transportEscrow()).as("有承运队 ⇒ 运费不落 escrow").isZero();
  }

  @Test
  void capacityShortageFallsBackToEscrowForTheRemainder() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    initialPrice.put(Good.FIBER, 100L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 500L, 0L, 50L, 50L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H0-grain", 0, 0);
    economy.addHex("H2-buyer", 2, 0);

    Household grainSeller = new Household("G", "H0-grain").stock(Good.GRAIN, 100L);
    grainSeller.population = 0L;
    Household buyer = new Household("B", "H2-buyer").need(Good.GRAIN, 40L);
    buyer.population = 1L;
    Household carrier = new Household("T", "H2-buyer").need(Good.GRAIN, 0L);
    carrier.population = 0L;
    economy.addHousehold(grainSeller);
    economy.addHousehold(buyer);
    economy.addHousehold(carrier);
    economy.addTransportTeam(
        new TransportTeam("T1", carrier, "H0-grain", "H2-buyer", 10L));

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("跨格成交").isNotEmpty();
    assertThat(carrier.money).as("只承运 10 粮：运费 150").isEqualTo(150L);
    assertThat(round.transportEscrow()).as("剩余 30 粮的运费 450 落 escrow").isEqualTo(450L);
  }
}
