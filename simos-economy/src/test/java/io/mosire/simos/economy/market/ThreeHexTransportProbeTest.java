package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** 3 格小市场：跨格交易 + 运输成本进入买家实付价。 */
class ThreeHexTransportProbeTest {

  @Test
  void threeHexesTradeAcrossDistanceWithPositiveTransportCost() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 50L, 0L, 500L, 0L, 50L, 50L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H0-grain", 0, 0);
    economy.addHex("H1-cloth", 2, 0);
    economy.addHex("H2-poor", 1, 0);

    Household grainSeller =
        new Household("G", "H0-grain").stock(Good.GRAIN, 100L).need(Good.CLOTH, 20L);
    grainSeller.population = 0L;
    Household clothSeller =
        new Household("C", "H1-cloth").stock(Good.CLOTH, 100L).need(Good.GRAIN, 20L);
    clothSeller.population = 0L;
    Household poor = new Household("P", "H2-poor").need(Good.GRAIN, 30L).need(Good.CLOTH, 10L);
    poor.population = 1L;
    poor.money = 100L;
    economy.addHousehold(grainSeller);
    economy.addHousehold(clothSeller);
    economy.addHousehold(poor);

    RoundResult first = economy.runRound(1);
    System.out.println("[3HEX] trades=" + first.trades());
    System.out.println("[3HEX] refs=" + first.refs());
    System.out.println("[3HEX] transportEscrow=" + first.transportEscrow());

    assertThat(first.trades()).as("跨格成交").isNotEmpty();
    assertThat(first.transportEscrow()).as("运输费 > 0").isPositive();
    assertThat(first.trades())
        .as("至少一笔成交的到货价高于卖方要价")
        .anyMatch(trade -> trade.landedPrice() > trade.basePrice());
    assertThat(first.trades())
        .as("H2 穷人从 H0 买粮、从 H1 买布")
        .anyMatch(trade -> trade.buyerHex().equals("H2-poor") && trade.good() == Good.GRAIN)
        .anyMatch(trade -> trade.buyerHex().equals("H2-poor") && trade.good() == Good.CLOTH);

    // 第二轮回看 ref/价格仍为正；成交继续。
    RoundResult second = economy.runRound(2);
    System.out.println("[3HEX] round2 trades=" + second.trades());
    System.out.println("[3HEX] round2 refs=" + second.refs());
    assertThat(second.trades()).as("第二轮仍有成交").isNotEmpty();
    assertThat(second.refs().values().stream().flatMap(map -> map.values().stream()))
        .allMatch(value -> value >= 0L);
  }
}
