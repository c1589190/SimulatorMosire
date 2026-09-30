package io.mosire.simos.economy.classfirst;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.ClassPoolId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>R2c：世界级合并判据</b>：三国的 seed 逐国到达时，同 {@code (mode, 阶层位置)} 键的池必须做<b>加法</b>
 * （人口/劳动/库存/债务/租约/借贷），家户账户按 id append，同 id 放贷账户资金/商品求和，累计/初始读数求和 —— 而不是后播覆盖。
 */
class ClassFirstStateMergeTest {

  @Test
  void mergeSumsPoolsHouseholdsAndLendersWorldWide() {
    ClassFirstState left =
        engineWith(config("PILOT-LENDER", 1_000_000L), fixture("left", 6L, 10L, 40L, 70L))
            .snapshot();
    ClassFirstState right =
        engineWith(config("PILOT-LENDER", 2_500_000L), fixture("right", 4L, 20L, 30L, 90L))
            .snapshot();

    ClassFirstState merged = left.merge(right);

    assertThat(merged.classPools()).as("同键池只留 4 个").hasSize(4);
    for (Map.Entry<ClassPoolId, ClassPool> entry : merged.classPools().entrySet()) {
      ClassPool before = left.classPools().get(entry.getKey());
      ClassPool added = right.classPools().get(entry.getKey());
      ClassPool pool = entry.getValue();
      assertThat(before).as("两侧都有同键池：%s", entry.getKey()).isNotNull();
      assertThat(added).as("两侧都有同键池：%s", entry.getKey()).isNotNull();
      assertThat(pool.population())
          .as("人口 = 两侧之和: %s", entry.getKey())
          .isEqualTo(before.population() + added.population());
      assertThat(pool.labor())
          .as("劳动 = 两侧之和: %s", entry.getKey())
          .isEqualTo(before.labor() + added.labor());
      for (AssetKind kind : AssetKind.ordered()) {
        if (kind == AssetKind.DEBT) {
          assertThat(pool.debtGrainMilli())
              .as("债务 = 两侧之和: %s", entry.getKey())
              .isEqualTo(before.debtGrainMilli() + added.debtGrainMilli());
        } else if (kind == AssetKind.LEASE_SECURITY) {
          assertThat(pool.leaseHolding())
              .as("租约 = 两侧之和: %s", entry.getKey())
              .isEqualTo(before.leaseHolding() + added.leaseHolding());
        } else {
          assertThat(pool.stock(kind))
              .as("%s = 两侧之和: %s", kind, entry.getKey())
              .isEqualTo(before.stock(kind) + added.stock(kind));
        }
      }
      assertThat(pool.population()).as("合并后不得为负").isNotNegative();
    }

    assertThat(merged.householdAccounts()).as("家户账户按 id append（两侧 4 + 4）").hasSize(8);
    assertThat(merged.householdAccounts().keySet())
        .containsExactlyInAnyOrderElementsOf(
            concat(left.householdAccounts().keySet(), right.householdAccounts().keySet()));

    PilotModel.Lender lender = merged.lenders().values().iterator().next();
    assertThat(lender.money()).as("同 id 放贷主体资金求和").isEqualTo(3_500_000L);
    assertThat(merged.meta().config().lender().money())
        .as("meta.config.lender 与 state.lenders 同一份（不两处拼写）")
        .isEqualTo(lender.money());
    assertThat(merged.meta().initial().populationTotal())
        .as("初始人口基数求和")
        .isEqualTo(
            left.meta().initial().populationTotal() + right.meta().initial().populationTotal());
    assertThat(merged.meta().initial().householdMoneyTotal())
        .as("初始家户货币基数求和")
        .isEqualTo(
            left.meta().initial().householdMoneyTotal()
                + right.meta().initial().householdMoneyTotal());
    assertThat(merged.meta().tick()).as("两侧 tick 都是 0 ⇒ 合并后仍为 0").isZero();

    assertThat(ClassFirstState.empty().merge(merged)).as("空态合并 = 另一侧").isEqualTo(merged);
    assertThat(merged.merge(ClassFirstState.empty())).as("合并空态 = 自己").isEqualTo(merged);
  }

  private static <T> List<T> concat(Iterable<T> a, Iterable<T> b) {
    List<T> all = new ArrayList<>();
    a.forEach(all::add);
    b.forEach(all::add);
    return all;
  }

  private static ClassFirstPilotEngine engineWith(
      PilotConfig config, List<PilotModel.Household> households) {
    return new ClassFirstPilotEngine(config, households);
  }

  private static PilotConfig config(String lenderId, long lenderMoney) {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            900L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender lender =
        new PilotModel.Lender(lenderId, lenderMoney, Map.of(), 20L, 60L, 1000L);
    return PilotConfig.tenancyAgriculture(lender, policy);
  }

  /** 每侧都覆盖四池（空池由引擎补 0 人口占位账户）⇒ 合并输出恰 4 个家户账户。 */
  private static List<PilotModel.Household> fixture(
      String prefix, long landlord, long middle, long tenant, long laborer) {
    Map<String, Long> grain = new LinkedHashMap<>();
    grain.put(PilotModel.GRAIN, 1000L);
    Map<String, Long> cloth = new LinkedHashMap<>();
    cloth.put(PilotModel.CLOTH, 100L);
    return List.of(
        household(prefix + "-L", PilotModel.LANDLORD_ID, landlord, 0L, grain, cloth, 200L, 0L),
        household(
            prefix + "-M", PilotModel.MIDDLE_PEASANT_ID, middle, 500L, grain, cloth, 100L, 50L),
        household(prefix + "-T", PilotModel.TENANT_ID, tenant, 500L, grain, cloth, 0L, 20L),
        household(prefix + "-W", PilotModel.LABORER_ID, laborer, 500L, grain, cloth, 0L, 0L));
  }

  private static PilotModel.Household household(
      String id,
      String position,
      long population,
      long laborPerCapita,
      Map<String, Long> grain,
      Map<String, Long> cloth,
      long land,
      long tools) {
    Map<String, Long> goods = new LinkedHashMap<>(grain);
    goods.putAll(cloth);
    return new PilotModel.Household(
        id, id, position, population, laborPerCapita, goods, 100L, land, tools, 1000L);
  }
}
