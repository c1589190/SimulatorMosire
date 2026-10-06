package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 闭环收入 + 还债 wave 探针：和断粮长程场景形成对照。
 *
 * <p>地主产粮 1000/轮、需要布 250/轮；织户 500 人，每人产 0.5 布、需要 2 粮/轮。 两边实物收支在初始价格（粮 100、布 400）刚好平衡，因此有收入、能还债。 运行
 * 3000 轮后，每轮发行 = 每轮还款 = 20 万，存量债务稳定在 20 万； 说明“人口死亡删债”之外，还需要劳动→收入→还债闭环，信贷才会真正稳定。
 */
class CircularFlowCreditProbeTest {

  @Test
  void circularIncomeLetsRepaymentStabilizeCredit() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 400L);
    Params params = new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 500L, 2L, 0L, 0L, initialPrice);

    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);

    Household landlord =
        new Household("L", "H")
            .stock(Good.GRAIN, 1_000L)
            .need(Good.GRAIN, 0L)
            .need(Good.CLOTH, 25L)
            .cost(Good.GRAIN, 10L);
    landlord.population = 10L;
    landlord.recipe = new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 0L, 1_000L);
    economy.addHousehold(landlord);

    Household weaver =
        new Household("W", "H").stock(Good.CLOTH, 250L).need(Good.GRAIN, 2L).cost(Good.CLOTH, 10L);
    weaver.population = 500L;
    weaver.recipe = new Recipe(Good.CLOTH, 1L, new EnumMap<>(Good.class), 1L, 250L);
    economy.addHousehold(weaver);

    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= 3_000; round++) {
      RoundResult row = economy.runRound(round);
      results.add(row);
      if (round <= 3 || round % 500 == 0) {
        System.out.println(
            "[CIRCLE] round="
                + round
                + " pop="
                + row.totalPopulation()
                + " newIssuance="
                + row.newIssuance()
                + " repayment="
                + row.newRepayment()
                + " debt="
                + row.totalDebt()
                + " money="
                + row.totalMoney()
                + " grainRef="
                + row.refs().get("H").get(Good.GRAIN)
                + " clothRef="
                + row.refs().get("H").get(Good.CLOTH));
      }
    }

    RoundResult last = results.get(results.size() - 1);

    // 两边都能活下去，人口不靠死亡删债下降。
    assertThat(last.totalPopulation()).as("510 人全部存活").isEqualTo(510L);
    assertThat(results.stream().mapToLong(RoundResult::deathsThisRound).sum())
        .as("闭环收入场景没有饥荒死亡")
        .isZero();

    // 每轮发行 = 每轮还款 = 20 万；存量债务/货币恒定。
    assertThat(last.newIssuance()).as("稳定期每轮发行 20 万").isEqualTo(200_000L);
    assertThat(last.newRepayment()).as("稳定期每轮还款 20 万").isEqualTo(200_000L);
    assertThat(last.totalDebt()).as("存量债务稳定在 20 万").isEqualTo(200_000L);
    assertThat(last.totalMoney()).as("家户货币稳定在 20 万").isEqualTo(200_000L);
    assertThat(last.transportEscrow()).as("同格、无运输费").isZero();

    // 至少后面 2000 轮不再变化。
    for (int index = results.size() - 2_000; index < results.size(); index++) {
      RoundResult row = results.get(index);
      assertThat(row.newIssuance()).as("轮 %s 发行稳定", row.round()).isEqualTo(200_000L);
      assertThat(row.newRepayment()).as("轮 %s 还款稳定", row.round()).isEqualTo(200_000L);
      assertThat(row.totalDebt()).as("轮 %s 存量债务稳定", row.round()).isEqualTo(200_000L);
    }

    // 货币账仍然守恒：累计发行 - 累计还款 = 家户货币 + 运输托管。
    assertThat(last.creditIssued() - last.creditRepaid())
        .as("累计发行-累计还款 = 现存量货币")
        .isEqualTo(last.totalMoney() + last.transportEscrow());
  }
}
