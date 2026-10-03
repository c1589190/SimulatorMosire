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
 * 长程 + 人口死亡探针：回答“人开始死以后，信贷额度是否趋于稳定”。
 *
 * <p>场景刻意做成一个**极端但可判读**的断粮世界：地主固定产粮 100/轮，雇农 500 人、无生产、无收入，
 * 只能借粮活命。这里 {@code interestPerMille=0}，先隔离“死亡删债”本身的效果；正利息只会让存量债务
 * 增长更快，不改变本测试的结论。
 *
 * <p>探针结论（2026-10-04）：
 *
 * <ul>
 *   <li>死亡确实按人口比例删债；饥荒轮里总债务出现下降；
 *   <li>人口降到 29（地主 10 + 健存雇农 19）后因整数向下取整不再死亡；
 *   <li>新发行流量收敛为常数（每轮恰好 1,050,000），即“信贷发行流量稳定”；
 *   <li>但存量债务不收敛：雇农没有收入，信贷池除开局 100 货币外收不到还款，每轮新增发行全部沉淀为债务；
 *   <li>因此“死亡率”单独不足以让信贷稳定。要稳定存量债务，至少还需要收入闭环（M2 劳动/生产分配）、
 *       还债/税收/回笼，或信用额度上限。
 * </ul>
 */
class LongRunMortalityProbeTest {

  @Test
  void mortalityDeletesDebtButStockNeedsIncomeOrCreditLimit() {
    EnumMap<Good, Long> initialPrice = new EnumMap<>(Good.class);
    initialPrice.put(Good.GRAIN, 100L);
    initialPrice.put(Good.CLOTH, 200L);
    // 利息设为 0：避免 3000 轮正利息把 long 撑爆，同时隔离“死亡删债”这一单一机制。
    Params params =
        new Params(200L, 100L, 100L, 10_000L, 0L, 100L, 500L, 10L, 50L, 50L, initialPrice);

    ProbeEconomy economy = new ProbeEconomy(params);
    economy.addHex("H", 0, 0);

    // 地主：不吃饭，只产粮；每轮 100 粮。
    Household landlord = new Household("L", "H").cost(Good.GRAIN, 10L).stock(Good.GRAIN, 10_000L);
    landlord.population = 10L;
    landlord.need(Good.GRAIN, 0L);
    landlord.recipe = new Recipe(Good.GRAIN, 1L, new EnumMap<>(Good.class), 0L, 100L);
    economy.addHousehold(landlord);

    // 工人：500 人，每人每轮要 10 粮；没有生产，只能借粮。
    Household worker = new Household("W", "H");
    worker.population = 500L;
    worker.need(Good.GRAIN, 10L);
    worker.stock(Good.GRAIN, 100L);
    worker.money = 100L;
    economy.addHousehold(worker);
    long initialMoney = landlord.money + worker.money;

    List<RoundResult> results = new ArrayList<>();
    for (int round = 1; round <= 3_000; round++) {
      RoundResult row = economy.runRound(round);
      results.add(row);
      if (round <= 10 || round % 500 == 0) {
        System.out.println(
            "[LONG] round="
                + round
                + " pop="
                + row.totalPopulation()
                + " deaths="
                + row.deathsThisRound()
                + " newIssuance="
                + row.newIssuance()
                + " repaid="
                + row.newRepayment()
                + " debtDeleted="
                + row.debtDeletedThisRound()
                + " debt="
                + row.totalDebt()
                + " W.stock="
                + worker.stockOf(Good.GRAIN)
                + " W.eat="
                + worker.lastFoodEaten
                + "/"
                + worker.lastFoodNeed
                + " ref="
                + row.refs().get("H").get(Good.GRAIN));
      }
    }

    RoundResult last = results.get(results.size() - 1);
    long totalDeaths = results.stream().mapToLong(RoundResult::deathsThisRound).sum();
    long totalDebtDeleted = results.stream().mapToLong(RoundResult::debtDeletedThisRound).sum();

    // 1) 确实发生了饥荒死亡，并且死亡按人口比例删了债。
    assertThat(totalDeaths).as("发生了饥饿死亡").isPositive();
    assertThat(totalDebtDeleted).as("死亡按人口比例删除债务").isPositive();
    boolean debtDroppedOnDeathRound =
        java.util.stream.IntStream.range(1, results.size())
            .anyMatch(
                index ->
                    results.get(index).deathsThisRound() > 0L
                        && results.get(index).totalDebt() < results.get(index - 1).totalDebt());
    assertThat(debtDroppedOnDeathRound).as("饥荒轮总债务出现过下降").isTrue();

    // 2) 人口下降到当前自给能力附近的低水平并停住（整数取整导致的探针现象）。
    assertThat(last.totalPopulation()).as("人口下降到承载量附近").isBetween(1L, 50L);
    assertThat(results.subList(results.size() - 100, results.size()))
        .as("人口稳定后不再死亡")
        .allMatch(row -> row.deathsThisRound() == 0L);

    // 3) 新发行“流量”收敛为常数：这是“信贷额度趋于稳定”里成立的那一半。
    long lateMin = Long.MAX_VALUE;
    long lateMax = Long.MIN_VALUE;
    for (int index = results.size() - 200; index < results.size(); index++) {
      long issuance = results.get(index).newIssuance();
      lateMin = Math.min(lateMin, issuance);
      lateMax = Math.max(lateMax, issuance);
    }
    assertThat(lateMin).as("稳定期每轮仍有信贷发行").isPositive();
    assertThat(lateMax - lateMin).as("稳定期新发行流量为常数（波动为零）").isZero();

    long earlyIssuance = results.get(199).creditIssued() - results.get(0).creditIssued();
    long lateIssuance =
        results.get(results.size() - 1).creditIssued()
            - results.get(results.size() - 201).creditIssued();
    System.out.println("[LONG] earlyIssuance(200轮)=" + earlyIssuance);
    System.out.println("[LONG] lateIssuance(200轮)=" + lateIssuance);
    System.out.println(
        "[LONG] lateIssuance/round=" + lateIssuance / 200 + " lateDebt=" + last.totalDebt());
    System.out.println(
        "[LONG] totalDeaths="
            + totalDeaths
            + " totalDebtDeleted="
            + totalDebtDeleted
            + " totalRepaid="
            + last.creditRepaid()
            + " finalPopulation="
            + last.totalPopulation());

    // 4) 还债 wave 在这个场景里基本无法启动：借款人开局只有 100 货币，此后没有收入，
    //    信贷池只能回收这 100；之后每轮新发行全部沉淀为债务。
    assertThat(last.creditRepaid())
        .as("借款人无收入，累计只回收了开局 100 货币")
        .isEqualTo(100L);

    // 5) 存量债务不收敛：每轮新增发行全部沉淀，债务随轮次线性增长。
    assertThat(results.get(999).totalDebt())
        .as("500 轮后债务仍在增长")
        .isGreaterThan(results.get(499).totalDebt());
    assertThat(results.get(1999).totalDebt())
        .as("1500 轮后债务仍在增长")
        .isGreaterThan(results.get(999).totalDebt());
    assertThat(last.totalDebt())
        .as("3000 轮后债务仍在增长；死亡率单独不足以稳定存量信贷")
        .isGreaterThan(results.get(1999).totalDebt());

    // 6) 货币/债务账仍可对账：家户货币 + 运输托管 = 初始货币 + 累计发行 - 累计还款。
    for (RoundResult row : results) {
      assertThat(row.totalMoney() + row.transportEscrow())
          .as("轮 %s 的货币账守恒", row.round())
          .isEqualTo(initialMoney + row.creditIssued() - row.creditRepaid());
    }
  }
}
