package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import io.mosire.simos.economy.market.ProbeEconomy.RentForm;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import io.mosire.simos.economy.market.ProbeEconomy.Tenancy;
import io.mosire.simos.economy.market.ProbeEconomy.WageFarm;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 佃农制 / 雇农制经济循环探针。
 *
 * <p>同一个三人世界（地主 L、农家 P、织户 W）分别跑：
 *
 * <ul>
 *   <li>定额实物租佃农：P 经营、产 1910 粮、交固定 410 粮；
 *   <li>分成租佃农：P 经营、地租 215‰（同样 410 粮）；
 *   <li>货币租佃农：P 经营、卖粮后交 41,000 钱；
 *   <li>雇农制：L 经营、雇 P 做工，发 191 粮 + 131,026 钱工资。
 * </ul>
 *
 * <p>市场价：粮 100、布 400；无运输、零利息。这个价格/数量组合使四条循环都严格闭合， 便于对比“租/工资”对收入、库存、债务和风险的影响。
 */
class ProductionModeEconomyProbeTest {

  private static final long GRAIN_PRICE = 100L;
  private static final long CLOTH_PRICE = 400L;

  @Test
  void fixedKindTenancyRunsClosedCycle() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 410L);
    Household farmer = addFarmer(economy, 1_500L);
    Household weaver = addWeaver(economy);

    Tenancy tenancy =
        economy.addTenancy(
            new Tenancy(
                "tenancy-fixed",
                landlord,
                farmer,
                farmRecipe(),
                191L,
                RentForm.FIXED_KIND,
                410L,
                0L,
                0L));

    List<RoundResult> results = run(economy, 1_460);
    print("fixed-kind", results, tenancy, null, landlord, farmer, weaver);

    assertThat(results).allMatch(row -> row.deathsThisRound() == 0L, "基准循环无死亡");
    assertThat(tenancy.lastOutput).as("产出 1910").isEqualTo(1_910L);
    assertThat(tenancy.lastRentPaid).as("固定租 410").isEqualTo(410L);
    assertThat(tenancy.lastRentArrears).isZero();
    assertThat(landlord.stockOf(Good.GRAIN)).as("地主期末粮 = 410").isEqualTo(410L);
    assertThat(farmer.stockOf(Good.GRAIN)).as("佃农期末粮 = 1500").isEqualTo(1_500L);
    assertThat(weaver.stockOf(Good.CLOTH)).as("织户期末布 = 400").isEqualTo(400L);
    assertThat(farmer.money).as("佃农期末货币").isEqualTo(120_000L);
    assertThat(landlord.money).as("地主期末货币").isEqualTo(40_000L);
  }

  @Test
  void shareTenancyRunsClosedCycle() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 410L);
    Household farmer = addFarmer(economy, 1_500L);
    Household weaver = addWeaver(economy);

    Tenancy tenancy =
        economy.addTenancy(
            new Tenancy(
                "tenancy-share",
                landlord,
                farmer,
                farmRecipe(),
                191L,
                RentForm.SHARE,
                0L,
                215L,
                0L));

    List<RoundResult> results = run(economy, 1_460);
    print("share", results, tenancy, null, landlord, farmer, weaver);

    assertThat(results).allMatch(row -> row.deathsThisRound() == 0L, "基准循环无死亡");
    assertThat(tenancy.lastOutput).as("产出 1910").isEqualTo(1_910L);
    assertThat(tenancy.lastRentPaid).as("分成 215‰ → 410 粮").isEqualTo(410L);
    assertThat(tenancy.lastRentArrears).isZero();
    assertThat(landlord.stockOf(Good.GRAIN)).as("地主期末粮 = 410").isEqualTo(410L);
    assertThat(farmer.stockOf(Good.GRAIN)).as("佃农期末粮 = 1500").isEqualTo(1_500L);
  }

  @Test
  void cashTenancyRunsClosedCycle() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 0L);
    Household farmer = addFarmer(economy, 1_910L);
    Household weaver = addWeaver(economy);

    Tenancy tenancy =
        economy.addTenancy(
            new Tenancy(
                "tenancy-cash",
                landlord,
                farmer,
                farmRecipe(),
                191L,
                RentForm.FIXED_CASH,
                0L,
                0L,
                41_000L));

    List<RoundResult> results = run(economy, 1_460);
    print("cash", results, tenancy, null, landlord, farmer, weaver);

    assertThat(results).allMatch(row -> row.deathsThisRound() == 0L, "基准循环无死亡");
    assertThat(tenancy.lastOutput).as("产出 1910").isEqualTo(1_910L);
    assertThat(tenancy.lastRentPaid).as("货币租 41,000").isEqualTo(41_000L);
    assertThat(tenancy.lastRentArrears).isZero();
    assertThat(farmer.money).as("佃农期末货币").isEqualTo(120_000L);
    assertThat(landlord.money).as("地主期末货币").isEqualTo(41_000L);
    assertThat(weaver.money).as("织户期末货币").isEqualTo(160_000L);
  }

  @Test
  void wageFarmRunsClosedCycle() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 1_528L);
    Household laborer = addFarmer(economy, 382L);
    Household weaver = addWeaver(economy);

    WageFarm farm = economy.addWageFarm(new WageFarm("wage-farm", landlord, farmRecipe(), 191L));
    farm.hire(laborer, 585L, 2L).fixedCash(65L);

    List<RoundResult> results = run(economy, 1_460);
    print("wage", results, null, farm, landlord, laborer, weaver);

    assertThat(results).allMatch(row -> row.deathsThisRound() == 0L, "基准循环无死亡");
    assertThat(farm.lastOutput).as("经营者产出 1910").isEqualTo(1_910L);
    assertThat(farm.lastGrainPaid).as("粮工资 382").isEqualTo(382L);
    assertThat(farm.lastCashPaid).as("钱工资 111,800").isEqualTo(111_800L);
    assertThat(farm.lastGrainArrears).isZero();
    assertThat(farm.lastCashArrears).isZero();
    assertThat(laborer.stockOf(Good.GRAIN)).as("雇农期末粮 = 382").isEqualTo(382L);
    assertThat(laborer.money).as("雇农期末货币 = 120,000").isEqualTo(120_000L);
    assertThat(landlord.stockOf(Good.GRAIN)).as("经营者期末粮 = 1528").isEqualTo(1_528L);
    assertThat(landlord.money).as("经营者期末货币 = 40,000").isEqualTo(40_000L);
  }

  @Test
  void fixedKindTenancyBearsHarvestRiskButKeepsPayingFixedRent() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 410L);
    Household farmer = addFarmer(economy, 1_500L);
    Household weaver = addWeaver(economy);
    Tenancy tenancy =
        economy.addTenancy(
            new Tenancy(
                "tenancy-fixed-shock",
                landlord,
                farmer,
                farmRecipe(),
                191L,
                RentForm.FIXED_KIND,
                410L,
                0L,
                0L));

    List<RoundResult> results = new ArrayList<>();
    long deathsDuringShock = 0L;
    long rentAtShock = 0L;
    long tenantFoodAtShock = 0L;
    long tenantNeedAtShock = 0L;
    for (int round = 1; round <= 1_460; round++) {
      applyHarvest(tenancy, null, isHarvestShock(round) ? 400L : 1_000L);
      RoundResult row = economy.runRound(round);
      results.add(row);
      if (isHarvestShock(round)) {
        deathsDuringShock += row.deathsThisRound();
      }
      if (round == 90) {
        rentAtShock = tenancy.lastRentPaid;
        tenantFoodAtShock = farmer.lastFoodEaten;
        tenantNeedAtShock = farmer.lastFoodNeed;
      }
    }
    printShock("fixed-shock", results, tenancy, null, landlord, farmer, weaver);
    System.out.println(
        "[SHOCK-fixed-shock] rentAtShock="
            + rentAtShock
            + " tenantFoodAtShock="
            + tenantFoodAtShock
            + "/"
            + tenantNeedAtShock
            + " deathsDuringShock="
            + deathsDuringShock);

    assertThat(deathsDuringShock).as("歉收期发生饥饿死亡").isPositive();
    assertThat(rentAtShock).as("定额租在歉收期仍收满 410").isEqualTo(410L);
    assertThat(tenantFoodAtShock).as("佃农自己仍吃满").isEqualTo(tenantNeedAtShock);
    assertThat(results.get(results.size() - 1).totalPopulation()).isLessThan(1_110L);
  }

  @Test
  void shareTenancySplitsHarvestRiskWithLandlord() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 410L);
    Household farmer = addFarmer(economy, 1_500L);
    Household weaver = addWeaver(economy);
    Tenancy tenancy =
        economy.addTenancy(
            new Tenancy(
                "tenancy-share-shock",
                landlord,
                farmer,
                farmRecipe(),
                191L,
                RentForm.SHARE,
                0L,
                215L,
                0L));

    List<RoundResult> results = new ArrayList<>();
    long deathsDuringShock = 0L;
    long rentAtShock = 0L;
    for (int round = 1; round <= 1_460; round++) {
      applyHarvest(tenancy, null, isHarvestShock(round) ? 400L : 1_000L);
      RoundResult row = economy.runRound(round);
      results.add(row);
      if (isHarvestShock(round)) {
        deathsDuringShock += row.deathsThisRound();
      }
      if (round == 90) {
        rentAtShock = tenancy.lastRentPaid;
      }
    }
    printShock("share-shock", results, tenancy, null, landlord, farmer, weaver);
    System.out.println(
        "[SHOCK-share-shock] rentAtShock="
            + rentAtShock
            + " deathsDuringShock="
            + deathsDuringShock);

    assertThat(deathsDuringShock).as("歉收期发生饥饿死亡").isPositive();
    assertThat(rentAtShock).as("分成租随歉收降到 164").isEqualTo(164L);
    assertThat(rentAtShock).isLessThan(410L);
  }

  @Test
  void wageFarmKeepsPayingTheLongTermWorkerWhileOperatorBearsLoss() {
    ProbeEconomy economy = world();
    Household landlord = addLandlord(economy, 1_528L);
    Household laborer = addFarmer(economy, 382L);
    Household weaver = addWeaver(economy);
    WageFarm farm =
        economy.addWageFarm(new WageFarm("wage-farm-shock", landlord, farmRecipe(), 191L));
    farm.hire(laborer, 585L, 2L).fixedCash(65L);

    List<RoundResult> results = new ArrayList<>();
    long deathsDuringShock = 0L;
    long grainWageAtShock = 0L;
    long workerFoodAtShock = 0L;
    long workerNeedAtShock = 0L;
    long operatorGrainAtShock = 0L;
    long maxCashArrearsDuringShock = 0L;
    for (int round = 1; round <= 1_460; round++) {
      applyHarvest(null, farm, isHarvestShock(round) ? 400L : 1_000L);
      RoundResult row = economy.runRound(round);
      results.add(row);
      if (isHarvestShock(round)) {
        deathsDuringShock += row.deathsThisRound();
        maxCashArrearsDuringShock = Math.max(maxCashArrearsDuringShock, farm.lastCashArrears);
      }
      if (round == 90) {
        grainWageAtShock = farm.lastGrainPaid;
        workerFoodAtShock = laborer.lastFoodEaten;
        workerNeedAtShock = laborer.lastFoodNeed;
        operatorGrainAtShock = landlord.stockOf(Good.GRAIN);
      }
    }
    printShock("wage-shock", results, null, farm, landlord, laborer, weaver);
    System.out.println(
        "[SHOCK-wage-shock] grainWageAtShock="
            + grainWageAtShock
            + " workerFoodAtShock="
            + workerFoodAtShock
            + "/"
            + workerNeedAtShock
            + " operatorGrainAtShock="
            + operatorGrainAtShock
            + " maxCashArrearsDuringShock="
            + maxCashArrearsDuringShock
            + " deathsDuringShock="
            + deathsDuringShock);

    assertThat(deathsDuringShock).as("歉收期发生饥饿死亡").isPositive();
    assertThat(grainWageAtShock).as("长工实物工资仍按 382 发").isEqualTo(382L);
    assertThat(workerFoodAtShock).as("雇农自己仍吃满").isEqualTo(workerNeedAtShock);
    assertThat(operatorGrainAtShock).as("经营者库存承担歉收损失").isLessThan(1_528L);
    assertThat(maxCashArrearsDuringShock).as("经营者现金不足时出现欠薪").isPositive();
  }

  private static void applyHarvest(Tenancy tenancy, WageFarm farm, long perMille) {
    if (tenancy != null) {
      tenancy.harvestPerMille = perMille;
    }
    if (farm != null) {
      farm.harvestPerMille = perMille;
    }
  }

  /** 365×4 测试：每年第 51–90 天为歉收（收成 400‰），其余日子正常。 */
  private static boolean isHarvestShock(int round) {
    int dayInYear = (round - 1) % 365 + 1;
    return dayInYear >= 51 && dayInYear <= 90;
  }

  private static void printShock(
      String label,
      List<RoundResult> results,
      Tenancy tenancy,
      WageFarm farm,
      Household landlord,
      Household farmer,
      Household weaver) {
    System.out.println(
        "[SHOCK-"
            + label
            + "] finalPop="
            + results.get(results.size() - 1).totalPopulation()
            + " totalDeaths="
            + results.stream().mapToLong(RoundResult::deathsThisRound).sum()
            + " finalDebt="
            + results.get(results.size() - 1).totalDebt()
            + " finalMoney="
            + results.get(results.size() - 1).totalMoney()
            + " L{grain="
            + landlord.stockOf(Good.GRAIN)
            + ",money="
            + landlord.money
            + "} P{grain="
            + farmer.stockOf(Good.GRAIN)
            + ",money="
            + farmer.money
            + ",eat="
            + farmer.lastFoodEaten
            + "/"
            + farmer.lastFoodNeed
            + "} W{pop="
            + weaver.population
            + ",eat="
            + weaver.lastFoodEaten
            + "/"
            + weaver.lastFoodNeed
            + "}");
    if (tenancy != null) {
      System.out.println(
          "[SHOCK-"
              + label
              + "] output="
              + tenancy.lastOutput
              + " rentPaid="
              + tenancy.lastRentPaid
              + " rentArrears="
              + tenancy.lastRentArrears);
    }
    if (farm != null) {
      System.out.println(
          "[SHOCK-"
              + label
              + "] output="
              + farm.lastOutput
              + " grainWage="
              + farm.lastGrainPaid
              + " cashWage="
              + farm.lastCashPaid
              + " arrears="
              + farm.lastGrainArrears
              + "/"
              + farm.lastCashArrears);
    }
  }

  // ── 世界装配 ─────────────────────────────────────────────────────────────

  private static ProbeEconomy world() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, GRAIN_PRICE);
    initialPrice.put(Good.CLOTH, CLOTH_PRICE);
    // 零利息、零运输，先把租/工资的结构跑清楚。
    Params params = new Params(200L, 100L, 100L, 10_000L, 0L, 100L, 700L, 1L, 0L, 0L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);
    return economy;
  }

  private static Household addLandlord(ProbeEconomy economy, long grain) {
    Household landlord = new Household("L", "H").cost(Good.GRAIN, 10L).cost(Good.CLOTH, 10L);
    landlord.population = 10L;
    landlord.need(Good.GRAIN, 1L).need(Good.CLOTH, 10L);
    landlord.stock(Good.GRAIN, grain);
    economy.addHousehold(landlord);
    return landlord;
  }

  /** 农家/雇农：300 人，劳动系数 700‰ ⇒ 210 有效劳动；每人每轮 1 粮 + 1 布。 */
  private static Household addFarmer(ProbeEconomy economy, long grain) {
    Household farmer = new Household("P", "H").cost(Good.GRAIN, 10L);
    farmer.population = 300L;
    farmer.need(Good.GRAIN, 1L).need(Good.CLOTH, 1L);
    farmer.stock(Good.GRAIN, grain);
    economy.addHousehold(farmer);
    return farmer;
  }

  /** 织户：800 人，每人每轮 2 粮；产 400 布。 */
  private static Household addWeaver(ProbeEconomy economy) {
    Household weaver = new Household("W", "H").cost(Good.CLOTH, 10L);
    weaver.population = 800L;
    weaver.need(Good.GRAIN, 2L);
    weaver.stock(Good.CLOTH, 400L);
    weaver.recipe = new Recipe(Good.CLOTH, 1L, new EnumMap<>(Good.class), 1L, 400L);
    economy.addHousehold(weaver);
    return weaver;
  }

  /** 农场配方：191 劳动 → 1910 粮（outputPerUnit=10，desiredScale=191）。 */
  private static Recipe farmRecipe() {
    return new Recipe(Good.GRAIN, 10L, new EnumMap<>(Good.class), 1L, 191L);
  }

  private static List<RoundResult> run(ProbeEconomy economy, int rounds) {
    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= rounds; round++) {
      results.add(economy.runRound(round));
    }
    return results;
  }

  private static void print(
      String label,
      List<RoundResult> results,
      Tenancy tenancy,
      WageFarm farm,
      Household landlord,
      Household farmer,
      Household weaver) {
    RoundResult last = results.get(results.size() - 1);
    System.out.println(
        "[MODE-"
            + label
            + "] pop="
            + last.totalPopulation()
            + " debt="
            + last.totalDebt()
            + " money="
            + last.totalMoney()
            + " L{grain="
            + landlord.stockOf(Good.GRAIN)
            + ",money="
            + landlord.money
            + "} P{grain="
            + farmer.stockOf(Good.GRAIN)
            + ",money="
            + farmer.money
            + ",eat="
            + farmer.lastFoodEaten
            + "/"
            + farmer.lastFoodNeed
            + "} W{grain="
            + weaver.stockOf(Good.GRAIN)
            + ",cloth="
            + weaver.stockOf(Good.CLOTH)
            + ",eat="
            + weaver.lastFoodEaten
            + "/"
            + weaver.lastFoodNeed
            + "}");
    if (tenancy != null) {
      System.out.println(
          "[MODE-"
              + label
              + "] output="
              + tenancy.lastOutput
              + " rentPaid="
              + tenancy.lastRentPaid
              + " rentArrears="
              + tenancy.lastRentArrears
              + " labor="
              + tenancy.lastLaborUsed);
    }
    if (farm != null) {
      System.out.println(
          "[MODE-"
              + label
              + "] output="
              + farm.lastOutput
              + " grainWage="
              + farm.lastGrainPaid
              + " cashWage="
              + farm.lastCashPaid
              + " arrears="
              + farm.lastGrainArrears
              + "/"
              + farm.lastCashArrears
              + " labor="
              + farm.lastLaborUsed);
    }
  }
}
