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
 * 自然死亡率探针：与饥饿死亡分开，按“每轮百万分率 + 余数累计”结算。
 *
 * <p>出厂量级：自然死亡 25‰/年、出生 30‰/年；1460 轮 = 4 年，所以每轮百万分率分别为
 * {@code 25000/365 ≈ 68}、{@code 30000/365 ≈ 82}。人口 1000、四年自然死亡约 100、出生约 122。
 */
class NaturalMortalityProbeTest {

  @Test
  void naturalMortalityAndBirthsRunForFourYears() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    initialPrice.put(Good.FIBER, 100L);
    Params params =
        new Params(
            200L, 100L, 100L, 10_000L, 0L, 0L, 700L, 1L, 0L, 0L, initialPrice, 68L, 82L);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);

    Household household = new Household("P", "H").cost(Good.GRAIN, 0L);
    household.population = 1_000L;
    household.need(Good.GRAIN, 1L);
    household.stock(Good.GRAIN, 1_000L);
    // 700 劳动 → 1400 粮，人口 1000 吃 1000，余粮可缓冲增长。
    household.recipe = new Recipe(Good.GRAIN, 2L, new EnumMap<>(Good.class), 1L, 700L);
    economy.addHousehold(household);

    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= 365 * 4; round++) {
      results.add(economy.runRound(round));
    }

    long deaths = results.stream().mapToLong(RoundResult::deathsThisRound).sum();
    long births = results.stream().mapToLong(RoundResult::birthsThisRound).sum();
    RoundResult last = results.get(results.size() - 1);
    System.out.println(
        "[NAT] pop="
            + last.totalPopulation()
            + " deaths="
            + deaths
            + " births="
            + births
            + " foodEaten="
            + household.lastFoodEaten
            + "/"
            + household.lastFoodNeed);

    assertThat(deaths).as("四年里自然死亡 > 0").isPositive();
    assertThat(births).as("四年里出生 > 0").isPositive();
    assertThat(last.totalPopulation()).as("出生略高于死亡 ⇒ 人口缓慢增长").isGreaterThan(1_000L);
    assertThat(household.lastFoodEaten)
        .as("自然死亡/出生不影响吃饱")
        .isEqualTo(household.lastFoodNeed);
  }
}
