package io.mosire.simos.economy.classfirst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.spi.EconomyGmAdjustments;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 阶段 4 的接线 + 守恒测试：{@code economy.GmAdjust.levyStock} 从阶层池一次性抽粮/钱到外部 lender。
 *
 * <p>小世界（3 个格）：一个持粮/钱的雇农户 + 两个零粮空户；抽取只应改「源池」与「目标 lender」两处， 粮/钱总量（池 + lender）不变；grain 不得抽走口粮保护储备。
 */
class ClassFirstLevyWiringTest {

  private static final String LENDER_ID = "GM-LENDER-LEVY";
  private static final long LABORER_POPULATION = 1000L;

  @Test
  void levyMovesGrainAndMoneyToLenderRespectsReserveAndConservesTotals() throws Exception {
    PilotModel.Lender seedLender =
        new PilotModel.Lender(LENDER_ID, 100_000L, Map.of(), 20L, 60L, 1000L);
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            1_000_000_000L, // 关闭催收：本用例只看抽取本身
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotConfig config =
        PilotConfig.tenancyAgriculture(seedLender, policy, 90L, MobilityPolicy.tenancyDefaults());
    List<PilotModel.Household> households = new ArrayList<>();
    households.add(
        stockedHousehold(
            "hh-0_0-laborer", PilotModel.LABORER_ID, LABORER_POPULATION, 5_000_000L, 50_000L));
    households.add(grainlessHousehold("hh-1_0-middle", PilotModel.MIDDLE_PEASANT_ID, 100L));
    households.add(grainlessHousehold("hh-2_0-tenant", PilotModel.TENANT_ID, 100L));

    ClassFirstState state =
        new ClassFirstPilotEngine(config, households, List.of(seedLender)).snapshot();
    EconomyData base = EconomyData.empty().withClassFirst(state);

    ClassPool before = pool(state, PilotModel.LABORER_ID);
    long grainStockBefore = before.stock(AssetKind.GRAIN);
    long moneyStockBefore = before.stock(AssetKind.MONEY);
    long lenderGrainBefore = lender(state).goods().getOrDefault(PilotModel.GRAIN, 0L);
    long lenderMoneyBefore = lender(state).money();
    long totalGrainBefore = totalGrain(state);
    long totalMoneyBefore = totalMoney(state);

    // ── 抽粮 100,000：源池减、lender 加、总量不变 ────────────────────────────
    EconomyGmAdjustments.Projection grain = levy(base, "grain", 100_000L, "抽取军粮");
    assertThat(grain.changes()).as("恰两条变更（源池 + lender）").hasSize(2);
    ClassFirstState afterGrain = grain.projected().classFirst();
    assertThat(pool(afterGrain, PilotModel.LABORER_ID).stock(AssetKind.GRAIN))
        .isEqualTo(grainStockBefore - 100_000L);
    assertThat(lender(afterGrain).goods().getOrDefault(PilotModel.GRAIN, 0L))
        .isEqualTo(lenderGrainBefore + 100_000L);
    assertThat(totalGrain(afterGrain)).as("粮总量（池 + lender）守恒").isEqualTo(totalGrainBefore);
    assertThat(ClassFirstPilotEngine.restore(afterGrain)).as("抽取后的状态仍可 restore").isNotNull();

    // ── 抽钱 10,000：同样两处、总量不变 ─────────────────────────────────────
    EconomyGmAdjustments.Projection money = levy(base, "money", 10_000L, "抽取现金");
    ClassFirstState afterMoney = money.projected().classFirst();
    assertThat(pool(afterMoney, PilotModel.LABORER_ID).stock(AssetKind.MONEY))
        .isEqualTo(moneyStockBefore - 10_000L);
    assertThat(lender(afterMoney).money()).isEqualTo(lenderMoneyBefore + 10_000L);
    assertThat(totalMoney(afterMoney)).as("货币总量（池 + lender）守恒").isEqualTo(totalMoneyBefore);

    // ── 超上限：amount = 可用粮 + 1 ⇒ 具名拒绝并报 available（不截断） ────────
    long protectedReserve = config.protectedGrainReserve(before.population(), before.labor());
    long available = Math.max(0L, grainStockBefore - protectedReserve);
    assertThatThrownBy(() -> levy(base, "grain", available + 1L, "抽超上限"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("available");
  }

  private static EconomyGmAdjustments.Projection levy(
      EconomyData base, String unit, long amount, String reason) throws Exception {
    JsonNode parameters =
        new ObjectMapper()
            .readTree(
                "{\"fromClassPositionId\":\""
                    + PilotModel.LABORER_ID
                    + "\",\"lenderId\":\""
                    + LENDER_ID
                    + "\",\"unit\":\""
                    + unit
                    + "\",\"amount\":"
                    + amount
                    + "}");
    return EconomyGmAdjustments.project(base, "levyStock", parameters, reason, 0L);
  }

  private static ClassPool pool(ClassFirstState state, String classPositionId) {
    return state.classPools().values().stream()
        .filter(pool -> pool.classPositionId().equals(classPositionId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 classPositionId=" + classPositionId + " 的池"));
  }

  private static PilotModel.Lender lender(ClassFirstState state) {
    return state.lenders().get(ExternalLenderId.of(LENDER_ID));
  }

  private static long totalGrain(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.stock(AssetKind.GRAIN);
    }
    for (PilotModel.Lender lender : state.lenders().values()) {
      total += lender.goods().getOrDefault(PilotModel.GRAIN, 0L);
    }
    return total;
  }

  private static long totalMoney(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.stock(AssetKind.MONEY);
    }
    for (PilotModel.Lender lender : state.lenders().values()) {
      total += lender.money();
    }
    return total;
  }

  private static PilotModel.Household stockedHousehold(
      String id, String classPositionId, long population, long grain, long money) {
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>();
    goods.put(PilotModel.GRAIN, grain);
    return new PilotModel.Household(
        id, id, classPositionId, population, 500L, goods, money, 0L, 0L, 1000L);
  }

  private static PilotModel.Household grainlessHousehold(
      String id, String classPositionId, long population) {
    return new PilotModel.Household(
        id, id, classPositionId, population, 500L, Map.of(), 0L, 0L, 0L, 1000L);
  }
}
