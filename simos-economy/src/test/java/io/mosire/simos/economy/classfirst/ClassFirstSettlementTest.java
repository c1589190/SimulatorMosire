package io.mosire.simos.economy.classfirst;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.MobilityPolicyId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ R1：正式单日入口 {@link ClassFirstSettlement#settleOneDay} 的链式调用必须与"一次性跑 N tick 的引擎"逐值同轨迹； 输出的 actor
 * 账户增量/社会人口增量必须能与状态差分对账。
 */
class ClassFirstSettlementTest {

  private static final long TICKS = 24L;

  @Test
  void dailySettlementChainMatchesSingleEngineRun() {
    PilotConfig config = config(MobilityPolicy.tenancyDefaults());
    List<PilotModel.Household> households = fixture();

    ClassFirstPilotEngine direct = new ClassFirstPilotEngine(config, households);
    direct.runTicks((int) TICKS);

    ClassFirstState state = ClassFirstState.empty();
    for (long day = 1L; day <= TICKS; day++) {
      ClassFirstSettlement.Result result =
          ClassFirstSettlement.settleOneDay(
              state, ClassFirstSettlement.Inputs.of(day, config, households));
      assertThat(result.audit().tick()).as("日审计 tick == 输入 day").isEqualTo(day);
      long populationDelta =
          result.socialPopulationDeltas().stream()
              .mapToLong(ClassFirstSettlement.PopulationDelta::populationDelta)
              .sum();
      assertThat(populationDelta).as("社会人口增量合计守恒（迁移只换池，不消失）").isZero();
      state = result.state();
    }

    assertThat(state)
        .as("逐日 settleOneDay 链与一次性 %s tick 的引擎快照逐值相等", TICKS)
        .isEqualTo(direct.snapshot());
  }

  @Test
  void settlementResultCanBePersistedIntoEconomyDataAndDiffed() {
    PilotConfig config = config(MobilityPolicy.tenancyDefaults());
    List<PilotModel.Household> households = fixture();

    ClassFirstSettlement.Result result =
        ClassFirstSettlement.settleOneDay(
            ClassFirstState.empty(), ClassFirstSettlement.Inputs.of(1L, config, households));

    EconomyData data = EconomyData.empty().withClassFirst(result.state());
    EconomyChangeSet changeSet = EconomyChangeSet.between(EconomyData.empty(), data);
    assertThat(changeSet.isEmpty()).isFalse();
    assertThat(changeSet.classFirst().changed()).isTrue();
    assertThat(EconomyChangeSet.apply(changeSet, EconomyData.empty())).isEqualTo(data);
  }

  @Test
  void restoreHonoursMobilityPolicyStoredInState() {
    PilotConfig config = config(MobilityPolicy.tenancyDefaults());
    ClassFirstState state =
        ClassFirstSettlement.settleOneDay(
                ClassFirstState.empty(), ClassFirstSettlement.Inputs.of(1L, config, fixture()))
            .state();
    String modeId = config.mode().id();
    MobilityPolicy tuned = config.mobilityPolicy().withUpCapPerMillePerTick(10L);
    assertThat(tuned).as("夹具必须是显式调过的政策").isNotEqualTo(config.mobilityPolicy());

    ClassFirstState tunedState = withMobilityPolicy(state, modeId, tuned);
    ClassFirstPilotEngine restored = ClassFirstPilotEngine.restore(tunedState);

    assertThat(restored.mobilityPolicy())
        .as("restore 以 state.mobilityPolicies 为权威")
        .isEqualTo(tuned);
    assertThat(restored.config().mobilityPolicy()).as("config 与 state 政策保持同一处拼写").isEqualTo(tuned);
    assertThat(restored.snapshot().mobilityPolicies().get(MobilityPolicyId.of(modeId)))
        .as("下一次快照仍带调后政策")
        .isEqualTo(tuned);
  }

  private static ClassFirstState withMobilityPolicy(
      ClassFirstState state, String modeId, MobilityPolicy policy) {
    LinkedHashMap<MobilityPolicyId, MobilityPolicy> policies =
        new LinkedHashMap<>(state.mobilityPolicies());
    policies.put(MobilityPolicyId.of(modeId), policy);
    return new ClassFirstState(
        state.modeParticipations(),
        state.classPools(),
        state.householdAccounts(),
        state.assetStateSchemas(),
        state.classBounds(),
        policies,
        state.classFlowEvents(),
        state.accounts(),
        state.lenders(),
        state.meta());
  }

  private static PilotConfig config(MobilityPolicy mobilityPolicy) {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            900L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender lender =
        new PilotModel.Lender("PILOT-LENDER", 1_000_000L, Map.of(), 20L, 60L, 1000L);
    return PilotConfig.tenancyAgriculture(lender, policy, 90L, mobilityPolicy);
  }

  private static List<PilotModel.Household> fixture() {
    List<PilotModel.Household> initial = new ArrayList<>();
    initial.add(
        new PilotModel.Household(
            "H1",
            "地主",
            PilotModel.LANDLORD_ID,
            5L,
            0L,
            goods(500L, 100L),
            1000L,
            2000L,
            0L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H2",
            "中农",
            PilotModel.MIDDLE_PEASANT_ID,
            40L,
            500L,
            goods(460L, 200L),
            140L,
            175L,
            34L,
            1000L));
    initial.add(
        new PilotModel.Household(
            "H3", "佃农", PilotModel.TENANT_ID, 60L, 500L, goods(300L, 150L), 150L, 0L, 50L, 1000L));
    initial.add(
        new PilotModel.Household(
            "H4", "雇农", PilotModel.LABORER_ID, 100L, 500L, goods(600L, 80L), 150L, 0L, 40L, 1000L));
    return List.copyOf(initial);
  }

  private static LinkedHashMap<String, Long> goods(long grain, long cloth) {
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>();
    goods.put(PilotModel.GRAIN, grain);
    goods.put(PilotModel.CLOTH, cloth);
    return goods;
  }
}
