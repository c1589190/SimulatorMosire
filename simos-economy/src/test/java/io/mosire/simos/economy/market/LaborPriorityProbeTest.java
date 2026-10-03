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
 * 劳动优先级聚焦探针：多 recipe 家户按加入顺序分配有效劳动，且 Σ 实际劳动 ≤ 可用劳动。
 *
 * <p>顺序即优先级：主 recipe 在前，之后按 {@link Household#addRecipe(Recipe)} 的加入顺序。
 */
class LaborPriorityProbeTest {

  @Test
  void laborIsAllocatedInPriorityOrderAcrossRecipes() {
    ProbeEconomy economy = new ProbeEconomy(params(0L));
    economy.addHex("H", 0, 0);

    Household household = new Household("P", "H").cost(Good.GRAIN, 10L).cost(Good.CLOTH, 10L);
    household.population = 100L; // laborPerCapita=1000‰ ⇒ 可用劳动 100
    household.addRecipe(new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 1L, 60L));
    household.addRecipe(new Recipe(Good.CLOTH, 1L, new EnumMap<>(Good.class), 1L, 60L));
    economy.addHousehold(household);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("没有可售余粮/余布").isEmpty();
    assertThat(household.lastProducedByGood.get(Good.GRAIN)).as("高优先级先拿 60 劳动").isEqualTo(60L);
    assertThat(household.lastProducedByGood.get(Good.CLOTH)).as("剩余 40 劳动给低优先级").isEqualTo(40L);
    assertThat(household.lastProduced).as("总产出 60+40").isEqualTo(100L);
    assertThat(household.stockOf(Good.GRAIN)).as("粮库存=产出").isEqualTo(60L);
    assertThat(household.stockOf(Good.CLOTH)).as("布库存=产出").isEqualTo(40L);
    assertThat(60L + 40L).as("Σ 实际劳动 ≤ 可用劳动 100").isLessThanOrEqualTo(100L);
  }

  @Test
  void hungerShrinksLaborBudgetAndPriorityKeepsFirstRecipeAlive() {
    // 每轮每人口粮 5 ⇒ 100 人需要 500 粮；库存 0 ⇒ 吃饱度 0 ⇒ 效率保底 300‰。
    ProbeEconomy economy = new ProbeEconomy(params(5L));
    economy.addHex("H", 0, 0);

    Household household = new Household("P", "H").cost(Good.GRAIN, 10L).cost(Good.CLOTH, 10L);
    household.population = 100L;
    household.need(Good.GRAIN, 5L);
    household.addRecipe(new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 1L, 60L));
    household.addRecipe(new Recipe(Good.CLOTH, 1L, new EnumMap<>(Good.class), 1L, 60L));
    economy.addHousehold(household);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("断粮且没有可售余粮").isEmpty();
    assertThat(household.lastFoodEaten).isZero();
    assertThat(household.lastEfficiency).as("效率保底 300‰").isEqualTo(300L);
    assertThat(household.lastProducedByGood.get(Good.GRAIN))
        .as("有效劳动 30 全给高优先级 recipe")
        .isEqualTo(30L);
    assertThat(household.lastProducedByGood.getOrDefault(Good.CLOTH, 0L))
        .as("低优先级 recipe 分不到劳动")
        .isZero();
    assertThat(household.lastProduced).as("总产出 30").isEqualTo(30L);
  }

  @Test
  void inputLimitedRecipeReleasesLaborToNextRecipe() {
    ProbeEconomy economy = new ProbeEconomy(params(0L));
    economy.addHex("H", 0, 0);

    Household household = new Household("P", "H").cost(Good.CLOTH, 10L).cost(Good.GRAIN, 10L);
    household.population = 100L;
    household.stock(Good.GRAIN, 20L);
    EnumMap<Good, Long> clothInput = new EnumMap<>(Good.class);
    clothInput.put(Good.GRAIN, 1L);
    household.addRecipe(new Recipe(Good.CLOTH, 1L, clothInput, 1L, 50L));
    household.addRecipe(new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 1L, 50L));
    economy.addHousehold(household);

    RoundResult round = economy.runRound(1);

    assertThat(round.trades()).as("20 粮全部被 C1 留作投入，没有可售余粮").isEmpty();
    assertThat(household.lastProducedByGood.get(Good.CLOTH))
        .as("高优先级布产受 20 粮投入限制，只出 20")
        .isEqualTo(20L);
    assertThat(household.lastProducedByGood.get(Good.GRAIN))
        .as("释放的 80 劳动继续给低优先级粮产")
        .isEqualTo(50L);
    assertThat(household.lastProduced).as("总产出 20+50").isEqualTo(70L);
  }

  private static Params params(long foodPerCapita) {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    return new Params(
        200L, 100L, 100L, 10_000L, 0L, 0L, 1_000L, foodPerCapita, 0L, 0L, initialPrice);
  }
}
