package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/**
 * 食物 / 劳动 / 生产闭环的聚焦探针。
 *
 * <p>只验证探针模型本身的三件事：
 *
 * <ol>
 *   <li>C1（方案甲）把预估口粮和生产投入都留出来，家户不会把活命粮卖掉；
 *   <li>断粮 → 吃饱度下降 → 有效劳动下降 → 劳动受限的产出下降；
 *   <li>吃饱 → 满效率 → 满劳动 → 达到目标规模。
 * </ol>
 */
class FoodLaborProductionProbeTest {

  @Test
  void c1ReservesFoodAndProductionInputSoTheyAreNotSold() {
    ProbeEconomy economy = new ProbeEconomy(params());
    economy.addHex("H", 0, 0);

    // 生产者：100 人 × 5 粮 = 500 口粮；织布 100 单位又需 100 粮投入 ⇒ C1=600。
    Household producer = new Household("P", "H").stock(Good.GRAIN, 600L).cost(Good.CLOTH, 50L);
    producer.population = 100L;
    producer.need(Good.GRAIN, 5L);
    EnumMap<Good, Long> clothInput = new EnumMap<>(Good.class);
    clothInput.put(Good.GRAIN, 1L);
    producer.recipe = new Recipe(Good.CLOTH, 1L, clothInput, 0L, 100L);
    economy.addHousehold(producer);

    // 买家：10 人 × 10 粮 = 100 粮需求。若 C1 漏掉生产投入，生产者会挂出 100 粮并被买走。
    Household buyer = new Household("B", "H");
    buyer.population = 10L;
    buyer.need(Good.GRAIN, 10L);
    economy.addHousehold(buyer);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("口粮+投入被 C1 全部留出，没有可售余粮").isEmpty();
    assertThat(buyer.debt).as("买家没有借到粮").isZero();
    assertThat(producer.lastFoodEaten).as("生产者吃满 500 口粮").isEqualTo(500L);
    assertThat(producer.lastProduced).as("留出的 100 粮投入转化为 100 布").isEqualTo(100L);
  }

  @Test
  void foodShortageLowersEfficiencyAndLaborLimitedOutput() {
    ProbeEconomy economy = new ProbeEconomy(params());
    economy.addHex("H", 0, 0);

    Household producer = grainProducer();
    producer.stock(Good.GRAIN, 0L);
    economy.addHousehold(producer);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("没有余粮可卖").isEmpty();
    assertThat(producer.lastFoodNeed).as("口粮需求 100×5").isEqualTo(500L);
    assertThat(producer.lastFoodEaten).as("断粮，吃不到").isZero();
    assertThat(producer.lastEfficiency).as("吃饱度 0 → 保底效率 300‰").isEqualTo(300L);
    assertThat(producer.lastProduced).as("可用劳动 100 × 300‰ / laborPerUnit 1 = 30 产出").isEqualTo(30L);
  }

  @Test
  void fullFoodAllowsFullLaborAndOutput() {
    ProbeEconomy economy = new ProbeEconomy(params());
    economy.addHex("H", 0, 0);

    Household producer = grainProducer();
    producer.stock(Good.GRAIN, 500L);
    economy.addHousehold(producer);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("口粮全部被 C1 留出，没有余粮可卖").isEmpty();
    assertThat(producer.lastFoodEaten).as("吃满 500 口粮").isEqualTo(500L);
    assertThat(producer.lastEfficiency).as("吃饱度 1000‰").isEqualTo(1_000L);
    assertThat(producer.lastProduced).as("可用劳动 100 → 目标规模 100 全部达成").isEqualTo(100L);
  }

  private static Params params() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    return new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 1_000L, 5L, 0L, 0L, initialPrice);
  }

  private static Household grainProducer() {
    Household producer = new Household("P", "H").cost(Good.GRAIN, 10L);
    producer.population = 100L;
    producer.need(Good.GRAIN, 5L);
    producer.recipe = new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 1L, 100L);
    return producer;
  }
}
