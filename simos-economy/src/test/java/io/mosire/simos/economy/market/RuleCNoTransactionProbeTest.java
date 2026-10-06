package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** 规则 C：无成交 → ref=0；下一次由产出者（或 GM 初始价）重新定价。 */
class RuleCNoTransactionProbeTest {

  @Test
  void noTradeResetsRefToZeroThenFirstSellerPrices() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 50L, 0L, 500L, 0L, 50L, 50L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);

    Household seller = new Household("S", "H").stock(Good.GRAIN, 50L);
    seller.population = 0L; // 无消费、无死亡
    economy.addHousehold(seller);

    RoundResult first = economy.runRound(1);
    assertThat(first.trades()).as("第一轮没有买家 ⇒ 无成交").isEmpty();
    assertThat(first.refs().get("H").get(Good.GRAIN)).as("规则 C：无成交 → ref=0").isZero();

    Household buyer = new Household("B", "H").need(Good.GRAIN, 10L);
    buyer.population = 1L;
    buyer.money = 1_000L;
    economy.addHousehold(buyer);

    RoundResult second = economy.runRound(2);
    assertThat(second.trades()).as("有人买 ⇒ 成交").isNotEmpty();
    assertThat(second.trades().get(0).basePrice()).as("ref=0 时按 GM 初始价 100 成交").isEqualTo(100L);
    assertThat(second.refs().get("H").get(Good.GRAIN)).as("成交后 ref 出现").isPositive();
  }
}
