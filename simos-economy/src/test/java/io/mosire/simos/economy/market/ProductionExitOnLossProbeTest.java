package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** ref < cost 时生产端停产；ref > cost 时恢复/继续生产（生产端转变的第一步）。 */
class ProductionExitOnLossProbeTest {

  @Test
  void refBelowCostStopsProductionAndRefAboveCostKeepsIt() {
    // 一、ref/初始价 100 < cost 150 ⇒ 停产。
    ProbeEconomy losing = new ProbeEconomy(paramsWithGrainPrice(100L));
    losing.addHex("H", 0, 0);
    Household loser = new Household("P", "H").cost(Good.GRAIN, 150L);
    loser.population = 10L;
    loser.recipe = grainRecipe();
    losing.addHousehold(loser);
    losing.runRound(1);
    assertThat(loser.productionStopped).as("ref<cost ⇒ 停产").isTrue();
    assertThat(loser.lastProduced).as("停产 ⇒ 零产出").isZero();

    // 二、ref/初始价 200 > cost 150 ⇒ 继续生产。
    ProbeEconomy profitable = new ProbeEconomy(paramsWithGrainPrice(200L));
    profitable.addHex("H", 0, 0);
    Household producer = new Household("P", "H").cost(Good.GRAIN, 150L);
    producer.population = 10L;
    producer.recipe = grainRecipe();
    profitable.addHousehold(producer);
    profitable.runRound(1);
    assertThat(producer.productionStopped).as("ref>cost ⇒ 不停产").isFalse();
    assertThat(producer.lastProduced).as("有产出").isPositive();
  }

  private static Params paramsWithGrainPrice(long grainPrice) {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, grainPrice);
    initialPrice.put(Good.CLOTH, 200L);
    return new Params(200L, 100L, 100L, 10_000L, 50L, 0L, 500L, 0L, 50L, 50L, initialPrice);
  }

  private static Recipe grainRecipe() {
    return new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 1L, 10L);
  }
}
