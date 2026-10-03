package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import io.mosire.simos.economy.market.ProbeEconomy.WageFarm;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** 手工业 H1：纤维户 + 作坊（纤维 + 劳动 → 布），作坊主雇工并发钱工资。 */
class HandicraftProbeTest {

  @Test
  void workshopTurnsFiberIntoClothAndPaysCashWage() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.FIBER, 100L);
    initialPrice.put(Good.CLOTH, 300L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 0L, 700L, 1L, 0L, 0L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);

    // 粮食卖家：让作坊工人/纤维户有钱买粮（H1 聚焦测试里不接农业）。
    Household grainSeller = new Household("S", "H").stock(Good.GRAIN, 6_000L).cost(Good.GRAIN, 10L);
    grainSeller.population = 0L;
    economy.addHousehold(grainSeller);

    // 纤维户：100 人，产 400 纤维，吃 100 粮。
    Household fiberHousehold =
        new Household("F", "H").stock(Good.FIBER, 400L).cost(Good.FIBER, 10L);
    fiberHousehold.population = 100L;
    fiberHousehold.need(Good.GRAIN, 1L);
    fiberHousehold.recipe =
        new Recipe(Good.FIBER, 10L, new EnumMap<>(Good.class), 1L, 40L);
    economy.addHousehold(fiberHousehold);

    // 作坊主：出场地/原料/工资，产出归自己；不吃粮（聚焦手工业）。
    Household owner = new Household("O", "H").cost(Good.CLOTH, 10L).cost(Good.FIBER, 10L);
    owner.population = 10L;
    owner.need(Good.GRAIN, 0L);
    owner.money = 60_000L;
    economy.addHousehold(owner);

    // 雇工：600 人，吃 600 粮；拿 150 钱/劳动的工资。
    Household artisan = new Household("A", "H").cost(Good.GRAIN, 10L);
    artisan.population = 600L;
    artisan.need(Good.GRAIN, 1L);
    economy.addHousehold(artisan);

    // 布买家：每轮买 400 布。
    Household clothBuyer = new Household("C", "H").need(Good.CLOTH, 400L);
    clothBuyer.population = 1L;
    clothBuyer.need(Good.GRAIN, 0L);
    economy.addHousehold(clothBuyer);

    EnumMap<Good, Long> clothInput = new EnumMap<>(Good.class);
    clothInput.put(Good.FIBER, 1L);
    WageFarm workshop =
        economy.addWageFarm(
            new WageFarm("workshop", owner, new Recipe(Good.CLOTH, 1L, clothInput, 1L, 400L), 400L));
    workshop.hire(artisan, 150L, 0L);

    RoundResult first = economy.runRound(1);
    // 第一轮：作坊买纤维、生产 400 布、发 60,000 钱工资；布还没到买家手里。
    assertThat(first.trades()).as("纤维/粮食成交").isNotEmpty();
    assertThat(workshop.lastOutput).as("作坊产出 400 布").isEqualTo(400L);
    assertThat(workshop.lastLaborUsed).as("用工 400").isEqualTo(400L);
    assertThat(workshop.lastCashPaid).as("钱工资 60,000").isEqualTo(60_000L);
    assertThat(workshop.lastGrainPaid).as("不发粮工资").isZero();
    assertThat(owner.stockOf(Good.CLOTH)).as("作坊主期末持有 400 布").isEqualTo(400L);
    assertThat(owner.stockOf(Good.FIBER)).as("纤维已投入").isZero();
    assertThat(fiberHousehold.stockOf(Good.FIBER)).as("纤维户期末 400 纤维（本轮产出）").isEqualTo(400L);
    assertThat(artisan.lastFoodEaten).as("雇工吃到 600 粮").isEqualTo(600L);
    assertThat(artisan.money).as("雇工拿到 60,000 钱工资").isEqualTo(60_000L);

    RoundResult second = economy.runRound(2);
    // 第二轮：买家买走 400 布，作坊主收到钱，继续下一轮。
    assertThat(owner.stockOf(Good.CLOTH)).as("第二轮又产出 400 布").isEqualTo(400L);
    assertThat(workshop.lastOutput).as("连续生产").isEqualTo(400L);
    assertThat(workshop.lastCashPaid).as("连续发工资").isEqualTo(60_000L);
    assertThat(artisan.lastFoodEaten).as("雇工连续吃满").isEqualTo(600L);
  }
}
