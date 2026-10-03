package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** 还债 wave 聚焦探针：有货币的欠债家户先还钱，信贷池回收货币。 */
class RepaymentWaveProbeTest {

  @Test
  void householdWithMoneyRepaysDebtAtStartOfRound() {
    ProbeEconomy economy = new ProbeEconomy(params());
    economy.addHex("H", 0, 0);

    Household partial = new Household("PARTIAL", "H");
    partial.population = 1L;
    partial.money = 30L;
    partial.debt = 100L;
    economy.addHousehold(partial);

    Household full = new Household("FULL", "H");
    full.population = 1L;
    full.money = 80L;
    full.debt = 50L;
    economy.addHousehold(full);

    RoundResult round = economy.runRound(1);

    assertThat(partial.money).as("部分还款：货币全部交出").isZero();
    assertThat(partial.debt).as("部分还款：债务减少 30").isEqualTo(70L);
    assertThat(full.money).as("足额还款：还清后余 30").isEqualTo(30L);
    assertThat(full.debt).as("足额还款：债务归零").isZero();
    assertThat(round.newRepayment()).as("本轮回收 30+50").isEqualTo(80L);
    assertThat(round.creditRepaid()).as("累计回收").isEqualTo(80L);
    assertThat(round.totalMoney()).as("还款后家户货币 0+30").isEqualTo(30L);
    assertThat(round.totalDebt()).as("剩余债务 70+0").isEqualTo(70L);
  }

  private static Params params() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    return new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 500L, 0L, 50L, 50L, initialPrice);
  }
}
