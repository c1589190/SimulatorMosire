package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.market.ProbeEconomy.Good;
import io.mosire.simos.economy.market.ProbeEconomy.Household;
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
 * D1：农村-城镇-集市的多元 365×4 长程探针。
 *
 * <pre>
 * H0 农村：地主 L + 佃农 P（粮）+ 纤维户 F（纤维）
 * H1 城镇：作坊主 O（纤维+劳动→布，雇 A，发钱工资）+ 运输队 T
 * 运输：H0→H1 运粮/纤维，H1→H0 运布；运费进 T 家户，不再沉没
 * </pre>
 *
 * 价格（出厂）：粮 100、纤维 25、布 205；运输费率 100‰（距离 1）。
 * 目标是把农业、手工业、运输三个部门都跑起来，不是为了精确稳态。
 */
class DiversifiedEconomyProbeTest {

  @Test
  void diversifiedThreeSectorEconomyRunsFourYears() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.FIBER, 25L);
    initialPrice.put(Good.CLOTH, 176L);
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 100L, 700L, 1L, 5L, 5L, initialPrice, 68L, 82L);
    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H0-rural", 0, 0);
    economy.addHex("H1-town", 1, 0);

    // H0：地主 + 佃农 + 纤维户
    Household landlord = new Household("L", "H0-rural").cost(Good.GRAIN, 10L).cost(Good.CLOTH, 10L);
    landlord.population = 10L;
    landlord.need(Good.GRAIN, 1L).need(Good.CLOTH, 10L);
    landlord.stock(Good.GRAIN, 410L);
    economy.addHousehold(landlord);

    Household farmer = new Household("P", "H0-rural").cost(Good.GRAIN, 0L);
    farmer.population = 300L;
    farmer.need(Good.GRAIN, 1L).need(Good.CLOTH, 1L);
    farmer.stock(Good.GRAIN, 610L);
    economy.addHousehold(farmer);

    Household fiberHousehold = new Household("F", "H0-rural").cost(Good.FIBER, 0L);
    fiberHousehold.population = 100L;
    fiberHousehold.need(Good.GRAIN, 1L);
    fiberHousehold.stock(Good.FIBER, 400L);
    fiberHousehold.recipe =
        new Recipe(Good.FIBER, 10L, new EnumMap<>(Good.class), 1L, 40L);
    economy.addHousehold(fiberHousehold);

    // H1：作坊主 + 雇工 + 运输队
    Household owner = new Household("O", "H1-town").cost(Good.CLOTH, 0L).cost(Good.FIBER, 0L);
    owner.population = 10L;
    owner.need(Good.GRAIN, 0L);
    owner.money = 66_000L;
    economy.addHousehold(owner);

    Household artisan = new Household("A", "H1-town").cost(Good.GRAIN, 0L);
    artisan.population = 600L;
    artisan.need(Good.GRAIN, 1L);
    economy.addHousehold(artisan);

    Household carrier = new Household("T", "H1-town");
    carrier.population = 10L;
    carrier.need(Good.GRAIN, 1L);
    economy.addHousehold(carrier);

    // 佃农制：121 劳动 → 1210 粮；固定实物租 410。
    economy.addTenancy(
        new Tenancy(
            "tenancy-rural",
            landlord,
            farmer,
            new Recipe(Good.GRAIN, 10L, new EnumMap<>(Good.class), 1L, 102L),
            102L,
            RentForm.FIXED_KIND,
            410L,
            0L,
            0L));

    // 手工业：纤维 + 劳动 → 布；雇工拿 165 钱/劳动工资。
    EnumMap<Good, Long> clothInput = new EnumMap<>(Good.class);
    clothInput.put(Good.FIBER, 1L);
    WageFarm workshop =
        economy.addWageFarm(
            new WageFarm("workshop", owner, new Recipe(Good.CLOTH, 1L, clothInput, 1L, 400L), 400L));
    workshop.hire(artisan, 150L, 0L);

    // 运输队：两条 lane，每轮重置运力。
    economy.addTransportTeam(
        new TransportTeam("T-rural-to-town", carrier, "H0-rural", "H1-town", 3_000L));
    economy.addTransportTeam(
        new TransportTeam("T-town-to-rural", carrier, "H1-town", "H0-rural", 1_000L));

    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= 365 * 4; round++) {
      RoundResult row = economy.runRound(round);
      results.add(row);
    }

    RoundResult last = results.get(results.size() - 1);
    TransportTeam ruralToTown = economy.transportTeams.get(0);
    TransportTeam townToRural = economy.transportTeams.get(1);
    System.out.println(
        "[D1] pop="
            + last.totalPopulation()
            + " issued="
            + last.creditIssued()
            + " repaid="
            + last.creditRepaid()
            + " debtDeleted="
            + last.debtDeleted()
            + " deaths="
            + results.stream().mapToLong(RoundResult::deathsThisRound).sum()
            + " debt="
            + last.totalDebt()
            + " money="
            + last.totalMoney()
            + " escrow="
            + last.transportEscrow()
            + " refs="
            + last.refs());
    System.out.println(
        "[D1] workshopOutput="
            + workshop.lastOutput
            + " clothWage="
            + workshop.lastCashPaid
            + " arrears="
            + workshop.lastCashArrears
            + " owner{cloth="
            + owner.stockOf(Good.CLOTH)
            + ",fiber="
            + owner.stockOf(Good.FIBER)
            + ",money="
            + owner.money
            + "} artisan{grain="
            + artisan.stockOf(Good.GRAIN)
            + ",eat="
            + artisan.lastFoodEaten
            + "/"
            + artisan.lastFoodNeed
            + ",money="
            + artisan.money
            + "}");
    System.out.println(
        "[D1] farmOutput="
            + economy.tenancies.get(0).lastOutput
            + " rent="
            + economy.tenancies.get(0).lastRentPaid
            + " farmer{grain="
            + farmer.stockOf(Good.GRAIN)
            + ",money="
            + farmer.money
            + ",eat="
            + farmer.lastFoodEaten
            + "/"
            + farmer.lastFoodNeed
            + "} fiber{stock="
            + fiberHousehold.stockOf(Good.FIBER)
            + ",money="
            + fiberHousehold.money
            + "}");
    System.out.println(
        "[D1] transport R->T moved="
            + ruralToTown.lastUnitsMoved
            + " fee="
            + ruralToTown.lastFeeEarned
            + " T->R moved="
            + townToRural.lastUnitsMoved
            + " fee="
            + townToRural.lastFeeEarned
            + " carrierMoney="
            + carrier.money);

    // 基本判据：三部门都活着，运输费归承运队，不靠 escrow；自然死亡/出生同时发生。
    long totalDeaths = results.stream().mapToLong(RoundResult::deathsThisRound).sum();
    long totalBirths = results.stream().mapToLong(RoundResult::birthsThisRound).sum();
    assertThat(totalDeaths).as("四年自然死亡 > 0").isPositive();
    assertThat(totalBirths).as("四年出生 > 0").isPositive();
    assertThat(last.totalPopulation()).as("出生略高于死亡 ⇒ 人口缓慢增长").isGreaterThan(1_030L);
    assertThat(workshop.lastOutput).as("作坊稳定产 400 布").isEqualTo(400L);
    assertThat(economy.tenancies.get(0).lastOutput).as("农场稳定产 1020 粮").isEqualTo(1_020L);
    assertThat(ruralToTown.lastFeeEarned + townToRural.lastFeeEarned)
        .as("运输队有运费收入")
        .isPositive();
    assertThat(last.transportEscrow()).as("运力足够时运费不落 escrow").isZero();
    assertThat(fiberHousehold.stockOf(Good.FIBER)).as("纤维仍在生产").isPositive();
    assertThat(last.refs().get("H0-rural").get(Good.GRAIN)).as("粮价仍为正").isPositive();
    assertThat(last.refs().get("H0-rural").get(Good.FIBER)).as("纤维价仍为正").isPositive();
    assertThat(last.refs().get("H1-town").get(Good.CLOTH)).as("布价仍为正").isPositive();

    // 货币/债务守恒：货币 = 初始 66,000 + 累计发行 − 累计还款（escrow 计入货币侧）；
    // 债务 = 累计发行 − 累计还款 − 死亡删债（本例利息为 0）。
    assertThat(last.totalMoney() + last.transportEscrow())
        .as("货币守恒")
        .isEqualTo(66_000L + last.creditIssued() - last.creditRepaid());
    assertThat(last.totalDebt())
        .as("债务守恒（利息 0）")
        .isEqualTo(last.creditIssued() - last.creditRepaid() - last.debtDeleted());
  }
}
