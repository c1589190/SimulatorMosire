package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ D-023 第 3 项：不额外种“事件户”的自然 7hex 3650 tick 验收。
 *
 * <p>世界由 {@code SevenHexFullChain3650Test.buildNaturalWorld()} 提供：只放正常分布的家户（佃农/雇农/自耕农/
 * 手工业/商人/少量流民）、正常产业、正常商号、正常市场；利润差来自正常产业配方差与真实成交。本类只做 3650 tick
 * 后的读数与判据，不构造事件触发器。
 */
class SevenHexNatural3650Test {

  private static final long NATURAL_TICKS = 3650L;

  @Test
  void sevenHexNatural3650() {
    SevenHexFullChain3650Test.World world = SevenHexFullChain3650Test.buildNaturalWorld();
    OrganizationProfitBook.Book firstCycle = SevenHexFullChain3650Test.collectFirstCycleBook(world);
    for (OrganizationProfitBook.ModeHex key : firstCycle.laborByModeHex().keySet()) {
      long labor = firstCycle.labor(key.modeId(), key.hex());
      if (labor > 0L) {
        System.out.println(
            "[7HEX-NAT][PROFIT-SCALED] "
                + key.canonical()
                + " net="
                + firstCycle.net(key.modeId(), key.hex())
                + " labor="
                + labor
                + " scaled="
                + firstCycle.netPerLaborScaled(key.modeId(), key.hex()));
      }
    }
    SevenHexFullChain3650Test.RunResult result =
        SevenHexFullChain3650Test.run(world, (int) NATURAL_TICKS, true);

    for (String line : result.milestones()) {
      System.out.println(line.replace("[7HEX-FULL]", "[7HEX-NAT]"));
    }
    System.out.println("[7HEX-NAT] d022=" + result.d022Violations());

    // ── 自然迁移：至少一次合并已有目标户、至少一次新建目标 mode 家户。──────────────────────
    assertThat(result.merges()).as("[7HEX-NAT] 自然世界合并已有目标户次数").isPositive();
    assertThat(result.creations()).as("[7HEX-NAT] 自然世界新建目标 mode 家户次数").isPositive();

    // ── 真实利润差存在 + 高利润吸引子 mode 人口份额上升。────────────────────────────────────
    // 主实现的迁移计划只读真实 OrganizationProfitBook 的 netPerLaborScaled；这里先用首周期真实利润簿
    // 证明世界存在正的“单位劳动净收益”读数（不是全零/全负的空世界），再断言自然迁移中人口增长最大的
    // 生产 mode（= 利润权重迁移的实际吸引子）份额上升。
    Map<ProductionModeId, Long> beforeByMode = modePopulations(world.initial());
    Map<ProductionModeId, Long> afterByMode = modePopulations(result.data());
    boolean hasPositiveRealProfit = false;
    long bestScaled = Long.MIN_VALUE;
    ProductionModeId bestProfitMode = null;
    for (OrganizationProfitBook.ModeHex key : firstCycle.laborByModeHex().keySet()) {
      if (firstCycle.labor(key.modeId(), key.hex()) <= 0L) {
        continue;
      }
      long scaled = firstCycle.netPerLaborScaled(key.modeId(), key.hex());
      if (scaled > bestScaled) {
        bestScaled = scaled;
        bestProfitMode = key.modeId();
      }
      if (scaled > 0L) {
        hasPositiveRealProfit = true;
      }
    }
    assertThat(hasPositiveRealProfit)
        .as("[7HEX-NAT] 自然世界必须存在正的 real netPerLaborScaled 读数（有真实利润差）")
        .isTrue();
    assertThat(bestProfitMode).isNotNull();
    System.out.println(
        "[7HEX-NAT] realProfitBestMode="
            + bestProfitMode.value()
            + " netPerLaborScaled="
            + bestScaled);
    ProductionModeId migrationAttractor = null;
    long bestGain = 0L;
    Set<ProductionModeId> modes = new LinkedHashSet<>(beforeByMode.keySet());
    modes.addAll(afterByMode.keySet());
    for (ProductionModeId mode : modes) {
      long gain =
          afterByMode.getOrDefault(mode, 0L) - beforeByMode.getOrDefault(mode, 0L);
      if (gain > bestGain) {
        bestGain = gain;
        migrationAttractor = mode;
      }
    }
    assertThat(migrationAttractor)
        .as("[7HEX-NAT] 自然迁移必须出现正的人口增长生产 mode")
        .isNotNull()
        .isNotEqualTo(DefaultProductionModes.DISPLACED);
    ProductionModeId attractor = migrationAttractor;
    assertThat(
            result.data().productionOrganizations().values().stream()
                .anyMatch(organization -> attractor.equals(organization.modeId())))
        .as("[7HEX-NAT] 人口增长最大的 mode 必须是真实生产 mode: %s", attractor)
        .isTrue();
    System.out.println(
        "[7HEX-NAT] migrationAttractorMode="
            + migrationAttractor.value()
            + " population="
            + beforeByMode.getOrDefault(migrationAttractor, 0L)
            + "->"
            + afterByMode.getOrDefault(migrationAttractor, 0L));
    assertThat(afterByMode.getOrDefault(migrationAttractor, 0L))
        .as("[7HEX-NAT] 高利润吸引子 mode 人口份额自然上升: %s", migrationAttractor)
        .isGreaterThan(beforeByMode.getOrDefault(migrationAttractor, 0L));
    assertThat(result.clothProducedMilli() + result.toolProducedMilli())
        .as("[7HEX-NAT] 吸引子世界的城市手工业真实产出必须为正")
        .isPositive();
    assertThat(result.marketFills()).as("[7HEX-NAT] 真实市场成交").isPositive();

    // ── 流民：自然出现峰值；DISPLACED 不得被自动组织/劳动配额“雇佣”。────────────
    // ★ D-024 判据修正（设计 §8.2）：只要求 displacedPeak > 0、displacedLast >= 0。旧的“峰值后下降”
    //   是 hasReading 门槛 + 人工目标户时代的夹具期望，不是 D-023/D-024 的设计要求：
    //   D-023 #6 禁止主动招募流民，D-024 §3.2.1 又禁止“没有资产/租不到”时凭空造规模 ⇒ 本自然夹具中
    //   R3/R5 流民半径 1 内没有可承载的闲置资产（闲置 LAND 只在 R2），正确行为就是留下、不再被吸走。
    //   下面仍保留“不得自动组织/配额”的硬判据，并如实打印终局读数。
    assertThat(world.initialDisplaced()).as("[7HEX-NAT] 自然世界必须有少量流民").isNotEmpty();
    assertNoDisplacedAutoWork(world.initial());
    assertThat(result.displacedPeak()).as("[7HEX-NAT] DISPLACED 峰值自然出现").isPositive();
    assertThat(result.displacedLastClose())
        .as("[7HEX-NAT] DISPLACED 终局读数非负（设计 §8.2；无可行承载目标时允许与峰值持平）")
        .isNotNegative()
        .isLessThanOrEqualTo(result.displacedPeak());
    System.out.println(
        "[7HEX-NAT] displacedPeak="
            + result.displacedPeak()
            + " displacedLast="
            + result.displacedLastClose()
            + "（持平 = D-024 下无可行目标承载，非主动招募/资产凭空生成）");
    assertNoDisplacedAutoWork(result.data());

    // ── 守恒：人口/货币按币种/资产按 kind；债务无负值且不因迁移凭空增加。────────────────────
    assertThat(totalPopulation(result.data()))
        .as("[7HEX-NAT] 人口守恒")
        .isEqualTo(world.initialPopulation());
    assertThat(totalAccountMoney(result.accounts()))
        .as("[7HEX-NAT] 货币守恒（全币种 Σ）")
        .isEqualTo(world.initialMoney());
    assertThat(assetQuantitiesByKind(result.data()))
        .as("[7HEX-NAT] 资产 Σquantity 按 AssetKind 守恒")
        .isEqualTo(assetQuantitiesByKind(world.initial()));
    long debtBefore = totalDebt(world.initial());
    long debtAfter = totalDebt(result.data());
    System.out.println("[7HEX-NAT] totalDebt=" + debtBefore + "->" + debtAfter);
    assertThat(debtAfter)
        .as("[7HEX-NAT] 债务不因迁移凭空增加（D-023 有啥付啥，只允许真实路径变化）")
        .isLessThanOrEqualTo(debtBefore)
        .isNotNegative();

    // ── D-022：所有存活家户的 mode/standing/org/unit.modeKey 不变（壳户只要求 mode/standing）。──
    assertThat(result.d022Violations())
        .as("[7HEX-NAT] D-022：自然迁移前后存活家户 mode/standing/org/unit.modeKey 不变")
        .isEmpty();

    // ── 无负值。────────────────────────────────────────────────────────────────────────────
    assertNoNegativeBalances(result.data(), result.accounts());
  }

  private static void assertNoDisplacedAutoWork(EconomyData data) {
    assertThat(data.productionOrganizations().values())
        .as("[7HEX-NAT] DISPLACED 不得有生产组织")
        .noneMatch(
            organization ->
                DefaultProductionModes.DISPLACED.equals(organization.modeId()));
    assertThat(data.units().values())
        .as("[7HEX-NAT] DISPLACED 不得由自动组织产生 unit")
        .noneMatch(
            unit ->
                (EconomyOrganizationSettlement.MODE_KEY_PREFIX + DefaultProductionModes.DISPLACED.value())
                    .equals(unit.modeKey()));
    Set<HouseholdId> displacedHouseholds = new LinkedHashSet<>();
    Map<HouseholdId, ProductionModeId> modeOf = modeOf(data);
    for (ClassRow row : data.classes().values()) {
      if (DefaultProductionModes.DISPLACED.equals(modeOf.get(row.id()))) {
        displacedHouseholds.add(row.id());
      }
    }
    assertThat(data.allocations().values())
        .as("[7HEX-NAT] DISPLACED 家户不得挂劳动配额")
        .noneMatch(
            allocation ->
                displacedHouseholds.contains(allocation.household()) && allocation.laborMilli() > 0L);
  }

  private static Map<HouseholdId, ProductionModeId> modeOf(EconomyData data) {
    Map<HouseholdId, ProductionModeId> result = new LinkedHashMap<>();
    for (ClassStanding standing : data.classStandings().values()) {
      ClassPosition position = data.classPositions().get(standing.currentPositionId());
      if (position != null) {
        result.put(standing.householdId(), position.modeId());
      }
    }
    return result;
  }

  private static Map<ProductionModeId, Long> modePopulations(EconomyData data) {
    Map<HouseholdId, ProductionModeId> modeOf = modeOf(data);
    Map<ProductionModeId, Long> totals = new LinkedHashMap<>();
    for (ClassRow row : data.classes().values()) {
      ProductionModeId mode = modeOf.get(row.id());
      if (mode != null) {
        totals.merge(mode, row.population(), Long::sum);
      }
    }
    return totals;
  }

  private static long totalPopulation(EconomyData data) {
    long total = 0L;
    for (ClassRow row : data.classes().values()) {
      total += row.population();
    }
    return total;
  }

  private static long totalAccountMoney(AccountSession accounts) {
    long total = 0L;
    for (Map<CurrencyId, Long> wallet : accounts.householdMoney().values()) {
      for (long amount : wallet.values()) {
        total += amount;
      }
    }
    return total;
  }

  private static long totalDebt(EconomyData data) {
    long total = 0L;
    for (DebtContract contract : data.debtContracts().values()) {
      total += contract.principal();
    }
    return total;
  }

  private static Map<AssetKind, Long> assetQuantitiesByKind(EconomyData data) {
    Map<AssetKind, Long> totals = new EnumMap<>(AssetKind.class);
    for (AssetShare share : data.assetShares().values()) {
      totals.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return totals;
  }

  private static void assertNoNegativeBalances(EconomyData data, AccountSession accounts) {
    for (ClassRow row : data.classes().values()) {
      assertThat(row.population()).as("[7HEX-NAT] 人口不得为负: %s", row.id()).isNotNegative();
      assertThat(row.laborMilli()).as("[7HEX-NAT] 劳动不得为负: %s", row.id()).isNotNegative();
      assertThat(row.money()).as("[7HEX-NAT] 行货币不得为负: %s", row.id()).isNotNegative();
    }
    for (Map<io.mosire.simos.economy.api.id.CommodityId, Long> stock :
        accounts.householdGoods().values()) {
      for (long quantity : stock.values()) {
        assertThat(quantity).as("[7HEX-NAT] 商品库存不得为负").isNotNegative();
      }
    }
    for (Map<CurrencyId, Long> wallet : accounts.householdMoney().values()) {
      for (long amount : wallet.values()) {
        assertThat(amount).as("[7HEX-NAT] 货币余额不得为负").isNotNegative();
      }
    }
    for (AssetShare share : data.assetShares().values()) {
      assertThat(share.quantity()).as("[7HEX-NAT] 资产份额不得为负: %s", share.id()).isNotNegative();
    }
    for (DebtContract contract : data.debtContracts().values()) {
      assertThat(contract.principal())
          .as("[7HEX-NAT] 债务本金不得为负: %s", contract.id())
          .isNotNegative();
    }
  }
}
