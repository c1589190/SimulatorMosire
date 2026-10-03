package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.CityState;
import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
import io.mosire.simos.economy.market.ProbeEconomy.MerchantTier;
import io.mosire.simos.economy.market.ProbeEconomy.Params;
import io.mosire.simos.economy.market.ProbeEconomy.Recipe;
import io.mosire.simos.economy.market.ProbeEconomy.RentForm;
import io.mosire.simos.economy.market.ProbeEconomy.RoundResult;
import io.mosire.simos.economy.market.ProbeEconomy.Tenancy;
import io.mosire.simos.economy.market.ProbeEconomy.TransportTeam;
import io.mosire.simos.economy.market.ProbeEconomy.WageFarm;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 19 格中心城探针：中心 C 城市，内环 R0–R5，外环 O0–O11。
 *
 * <p>验证：
 *
 * <ol>
 *   <li>城市商人运力可扩散到服务半径内的所有 lane（一条总池覆盖周边，不再一 lane 一队）；
 *   <li>周边小农参与纤维→布循环：R0 纤维 400 + 周边 5×50 = 650，正好养城市作坊；
 *   <li>城区占地扣减可耕地：城市扩建后 R0 可用耕地下降；
 *   <li>农村商人成本累积、城市承载/慢速扩建继续生效。
 * </ol>
 */
class SevenHexCityMerchantProbeTest {

  @Test
  void sevenHexCityMerchantsAndSlowExpansionRunTenYears() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.FIBER, 25L);
    initialPrice.put(Good.CLOTH, 185L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 100L, 700L, 1L, 5L, 5L, initialPrice);
    ProbeEconomy economy = new ProbeEconomy(params);

    economy.addHex("C", 0, 0);
    economy.addHex("R0", 1, 0);
    economy.addHex("R1", 0, 1);
    economy.addHex("R2", -1, 1);
    economy.addHex("R3", -1, 0);
    economy.addHex("R4", 0, -1);
    economy.addHex("R5", 1, -1);
    String[][] outerHexes = {
      {"O0", "2", "0"}, {"O1", "1", "1"}, {"O2", "0", "2"}, {"O3", "-1", "2"},
      {"O4", "-2", "2"}, {"O5", "-2", "1"}, {"O6", "-2", "0"}, {"O7", "-1", "-1"},
      {"O8", "0", "-2"}, {"O9", "1", "-2"}, {"O10", "2", "-2"}, {"O11", "2", "-1"}
    };
    for (String[] hex : outerHexes) {
      economy.addHex(hex[0], Integer.parseInt(hex[1]), Integer.parseInt(hex[2]));
    }
    CityState city = economy.addCity(new CityState("C-city", "C", 10L, 4L));

    // ── R0 主农场：地主 + 佃农 + 纤维户 ─────────────────────────────────
    Household landlord = new Household("L", "R0").cost(Good.GRAIN, 0L).cost(Good.CLOTH, 0L);
    landlord.population = 10L;
    landlord.need(Good.GRAIN, 1L).need(Good.CLOTH, 10L);
    landlord.stock(Good.GRAIN, 410L);
    economy.addHousehold(landlord);

    Household farmer = new Household("P", "R0").cost(Good.GRAIN, 0L);
    farmer.population = 300L;
    farmer.need(Good.GRAIN, 1L).need(Good.CLOTH, 1L);
    farmer.stock(Good.GRAIN, 610L);
    economy.addHousehold(farmer);

    Household fiberHousehold = new Household("F", "R0").cost(Good.FIBER, 0L);
    fiberHousehold.population = 100L;
    fiberHousehold.need(Good.GRAIN, 1L);
    fiberHousehold.stock(Good.FIBER, 400L);
    fiberHousehold.recipe = new Recipe(Good.FIBER, 10L, new EnumMap<>(Good.class), 1L, 40L);
    economy.addHousehold(fiberHousehold);

    // ── C 城市：作坊主 + 匠户 + 城市商人 ─────────────────────────────────
    Household owner = new Household("O", "C").cost(Good.CLOTH, 0L).cost(Good.FIBER, 0L);
    owner.population = 10L;
    owner.need(Good.GRAIN, 0L);
    owner.money = 66_000L;
    economy.addHousehold(owner);

    Household artisan = new Household("A", "C").cost(Good.GRAIN, 0L);
    artisan.population = 755L;
    artisan.need(Good.GRAIN, 1L);
    economy.addHousehold(artisan);

    Household cityMerchant = new Household("M-city", "C");
    cityMerchant.population = 10L;
    cityMerchant.need(Good.GRAIN, 1L);
    economy.addHousehold(cityMerchant);

    Household cityLaborer = new Household("W-city", "C");
    cityLaborer.population = 0L;
    cityLaborer.need(Good.GRAIN, 1L);
    economy.addHousehold(cityLaborer);

    // ── R1–R5 周边小农：自给粮 + 卖纤维 + 买布 ──────────────────────────
    List<Household> peripheral = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      Household smallholder =
          new Household("H-r" + i, "R" + i).cost(Good.GRAIN, 0L).cost(Good.FIBER, 0L);
      smallholder.population = 50L;
      smallholder.need(Good.GRAIN, 1L).need(Good.CLOTH, 1L);
      smallholder.stock(Good.GRAIN, 500L);
      smallholder.recipe = new Recipe(Good.GRAIN, 10L, new EnumMap<>(Good.class), 1L, 5L);
      smallholder.addRecipe(new Recipe(Good.FIBER, 10L, new EnumMap<>(Good.class), 1L, 5L));
      economy.addHousehold(smallholder);
      peripheral.add(smallholder);
    }

    // 外环贸易站：O0 卖粮、O1 买粮，用来验证商路辐射到第二圈。
    Household outerSeller = new Household("S-outer", "O0").cost(Good.GRAIN, 0L);
    outerSeller.population = 10L;
    outerSeller.need(Good.GRAIN, 0L);
    outerSeller.stock(Good.GRAIN, 100_000L);
    outerSeller.recipe = new Recipe(Good.GRAIN, 10L, new EnumMap<>(Good.class), 1L, 1L);
    economy.addHousehold(outerSeller);

    Household outerBuyer = new Household("B-outer", "O1").need(Good.GRAIN, 10L);
    outerBuyer.population = 10L;
    economy.addHousehold(outerBuyer);

    // 从城市延伸出去的一条道路：C→R0→O0→O1→O2。
    economy.setRoad("C", "R0", 2L);
    economy.setRoad("R0", "O0", 2L);
    economy.setRoad("O0", "O1", 1L);
    economy.setRoad("O1", "O2", 1L);

    // 农村脚夫（家在 R1，实际承运 R0→C）。
    Household ruralMerchant = new Household("M-rural", "R1");
    ruralMerchant.population = 0L;
    economy.addHousehold(ruralMerchant);
    economy.addTransportTeam(
        new TransportTeam(
            "rural-porter", ruralMerchant, "R0", "C", 300L, MerchantTier.PORTER, false));

    // 城市商人总池：家在 C、服务半径 2，覆盖 C 与周边所有 lane。
    economy.addTransportTeam(
        new TransportTeam(
            "city-pool", cityMerchant, "*", "*", 20_000L, MerchantTier.BOSS, true, "C", 2L));

    // ── 生产组织 ─────────────────────────────────────────────────────────
    economy.addTenancy(
        new Tenancy(
            "tenancy-r0",
            landlord,
            farmer,
            new Recipe(Good.GRAIN, 10L, new EnumMap<>(Good.class), 1L, 142L),
            142L,
            RentForm.FIXED_KIND,
            410L,
            0L,
            0L)
            .landPerUnit(22L));

    EnumMap<Good, Long> clothInput = new EnumMap<>(Good.class);
    clothInput.put(Good.FIBER, 1L);
    WageFarm workshop =
        economy.addWageFarm(
            new WageFarm(
                "workshop-city", owner, new Recipe(Good.CLOTH, 1L, clothInput, 1L, 650L), 650L));
    workshop.hire(artisan, 160L, 0L).hire(cityLaborer, 160L, 0L);

    long initialRuralLaneCost = economy.transportPerMille("R0", "C");
    long initialPeripheralLaneCost = economy.transportPerMille("R1", "C");

    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= 3_650; round++) {
      results.add(economy.runRound(round));
      // 城市作坊劳动不足 ⇒ 周边小农迁入；每户至少留 15 人维持口粮+纤维。
      if (round >= 5) {
        long currentLabor = economy.effectiveLabor(artisan) + economy.effectiveLabor(cityLaborer);
        long neededLabor = workshop.desiredScale;
        if (workshop.lastScale < neededLabor && currentLabor < neededLabor) {
          long remainingLabor = neededLabor - currentLabor;
          long needPeople = (remainingLabor * 10L + 6L) / 7L; // ceil(劳动 / 0.7)
          for (Household smallholder : peripheral) {
            if (needPeople <= 0L) {
              break;
            }
            long movable = Math.max(0L, smallholder.population - 15L);
            long move = Math.min(5L, Math.min(movable, needPeople));
            if (move > 0L) {
              economy.migratePopulation(smallholder, cityLaborer, move);
              needPeople -= move;
            }
          }
        }
      }
    }

    RoundResult last = results.get(results.size() - 1);
    TransportTeam cityPool =
        economy.transportTeams.stream()
            .filter(team -> team.id.equals("city-pool"))
            .findFirst()
            .orElseThrow();
    TransportTeam ruralPorter =
        economy.transportTeams.stream()
            .filter(team -> team.id.equals("rural-porter"))
            .findFirst()
            .orElseThrow();
    long finalRuralLaneCost = economy.transportPerMille("R0", "C");
    long finalPeripheralLaneCost = economy.transportPerMille("R1", "C");
    long availableArableC = economy.availableArableMu("C");
    long availableArableR0 = economy.availableArableMu("R0");

    System.out.println(
        "[7HEX2] pop="
            + last.totalPopulation()
            + " deaths="
            + results.stream().mapToLong(RoundResult::deathsThisRound).sum()
            + " R0->C="
            + initialRuralLaneCost
            + "->"
            + finalRuralLaneCost
            + " (ruralPenalty="
            + ruralPorter.ruralTradeCostPenaltyPerMille
            + ") R1->C="
            + initialPeripheralLaneCost
            + "->"
            + finalPeripheralLaneCost
            + " cityPoolMoved="
            + cityPool.lastUnitsMoved
            + " cityPoolProfit="
            + cityPool.lastTradeProfit
            + " cityPoolCapacity="
            + cityPool.capacityPerRound);
    System.out.println(
        "[7HEX2] city capacity="
            + city.capacity
            + " used="
            + city.usedCapacity
            + " expansions="
            + city.expansionCount
            + " builtAreaPerMille="
            + city.builtAreaPerMille
            + " arableC="
            + availableArableC
            + " arableR0="
            + availableArableR0
            + " cumulativeTrade="
            + city.cumulativeTradeVolume);
    long peripheralToCityCost = economy.transportPerMille("R1", "C");
    long fartherRuralCost = economy.transportPerMille("R1", "R2");
    long outerRoadLaneCost = economy.transportPerMille("O0", "O1");
    long outerOffRoadLaneCost = economy.transportPerMille("O2", "O11");
    long cityToOuterRoadCost = economy.transportPerMille("C", "O2");
    long cityToOuterOffRoadCost = economy.transportPerMille("C", "O11");
    long outerBuyerDebt = outerBuyer.debt;
    // 之后临时给 R1→R2 修一条路，测“修路立刻降价”的接口。
    economy.setRoad("R1", "R2", 1L);
    long afterRoadCost = economy.transportPerMille("R1", "R2");
    long peripheralPopulation = 0L;
    for (Household smallholder : peripheral) {
      peripheralPopulation += smallholder.population;
    }
    long migratedPopulation = 250L - peripheralPopulation;
    System.out.println(
        "[7HEX2] radial: R1->C="
            + peripheralToCityCost
            + " R1->R2="
            + fartherRuralCost
            + " afterRoad(level1)="
            + afterRoadCost
            + " outerRoad="
            + outerRoadLaneCost
            + " outerOffRoad="
            + outerOffRoadLaneCost
            + " C->O2(road)="
            + cityToOuterRoadCost
            + " C->O11(offroad)="
            + cityToOuterOffRoadCost
            + " outerBuyerDebt="
            + outerBuyerDebt
            + " migratedToCity="
            + migratedPopulation
            + " cityLaborer="
            + cityLaborer.population);
    System.out.println(
        "[7HEX2] workshopOutput="
            + workshop.lastOutput
            + " farmOutput="
            + economy.tenancies.get(0).lastOutput
            + " rent="
            + economy.tenancies.get(0).lastRentPaid
            + " fiberHousehold="
            + fiberHousehold.stockOf(Good.FIBER)
            + " peripheralFiberStock="
            + peripheral.stream().mapToLong(h -> h.stockOf(Good.FIBER)).sum()
            + " refs="
            + last.refs());

    // 城市商人扩散：R1→C 没有专属城市商队，但城市总池半径覆盖它。
    assertThat(initialPeripheralLaneCost)
        .as("R1→C 也被城市折扣覆盖（小于基础 10‰）")
        .isLessThan(10L);
    assertThat(finalPeripheralLaneCost)
        .as("城市商人运力扩散到周边 lane")
        .isLessThanOrEqualTo(initialPeripheralLaneCost);
    assertThat(finalRuralLaneCost).as("农村商人让 R0→C 变贵").isGreaterThan(initialRuralLaneCost);
    assertThat(cityPool.lastUnitsMoved).as("城市商人总池实际承运过").isPositive();
    assertThat(cityPool.capacityPerRound).as("商人总池总体扩编过运力").isGreaterThan(20_000L);
    assertThat(city.capacity).as("城市自动扩建").isGreaterThan(10L);
    assertThat(city.expansionCount).as("扩建次数 > 0").isPositive();
    assertThat(city.builtAreaPerMille).as("城区比例上升").isPositive();
    assertThat(availableArableC).as("城区占地扣减了城市本格可耕地").isLessThan(3_100L);
    assertThat(availableArableR0).as("城区辐射也占用周边可耕地").isLessThan(3_100L);
    assertThat(workshop.lastOutput).as("作坊扩容后仍在生产").isPositive();
    assertThat(economy.tenancies.get(0).lastOutput).as("农业仍在生产").isPositive();
    assertThat(last.totalPopulation()).as("人口未灭绝").isPositive();
    assertThat(fartherRuralCost).as("离城越远越贵：R1→R2 > R1→C").isGreaterThan(peripheralToCityCost);
    assertThat(outerRoadLaneCost).as("沿路的外环 lane 比无路外环 lane 便宜").isLessThan(outerOffRoadLaneCost);
    assertThat(cityToOuterRoadCost).as("沿路到 C→O2 比无路 C→O11 便宜").isLessThan(cityToOuterOffRoadCost);
    assertThat(outerBuyerDebt).as("外环贸易站真的成交（买家欠款 > 0）").isPositive();
    assertThat(afterRoadCost).as("道路降低运输费").isLessThan(fartherRuralCost);
    assertThat(migratedPopulation).as("周边小农迁入城市").isPositive();
    assertThat(cityLaborer.population).as("城市工坊新增劳动力").isPositive();
    assertThat(workshop.lastScale)
        .as("迁入后作坊达到目标规模（650）")
        .isEqualTo(workshop.desiredScale);
    assertThat(economy.tenancies.get(0).lastOutput)
        .as("城区辐射占用可耕地后，农业规模被 landPerUnit 压住")
        .isLessThan(1_420L);
  }
}
