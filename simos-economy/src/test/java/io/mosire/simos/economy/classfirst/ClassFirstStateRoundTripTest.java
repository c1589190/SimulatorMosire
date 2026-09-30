package io.mosire.simos.economy.classfirst;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ R1：非空 {@link ClassFirstState} 经 {@code EconomyData} → {@code EconomyChangeSet} → {@code
 * EconomyCodec} 往返逐值不变；旧档（快照/变更集/创世载荷）缺 {@code classFirst} 键读出空态。
 */
class ClassFirstStateRoundTripTest {

  private static final EconomyCodec CODEC = new EconomyCodec();

  @Test
  void nonEmptyClassFirstStateSurvivesEconomyDataChangeSetAndCodec() {
    ClassFirstState state = seededState();
    assertThat(state.isEmpty()).as("夹具必须是非空状态").isFalse();
    assertThat(state.classPools()).as("至少一个阶层池").isNotEmpty();
    assertThat(state.householdAccounts()).as("至少一个家户生产子账户").isNotEmpty();
    assertThat(ClassFirstState.empty().merge(state)).as("空态合并 = 另一侧").isEqualTo(state);
    assertThat(state.merge(ClassFirstState.empty())).as("合并空态 = 自己").isEqualTo(state);

    EconomyData target = EconomyData.empty().withClassFirst(state);
    EconomyData base = EconomyData.empty();

    EconomyChangeSet changeSet = EconomyChangeSet.between(base, target);
    assertThat(changeSet.isEmpty()).as("非空 classFirst 必须进变更集").isFalse();
    assertThat(changeSet.classFirst().changed()).as("classFirst 组件必须被报成非 Unchanged").isTrue();
    assertThat(EconomyChangeSet.apply(changeSet, base)).as("变更集往返逐值不变").isEqualTo(target);

    EconomyChangeSet decodedChangeSet =
        (EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(decodedChangeSet).isEqualTo(changeSet);
    assertThat(EconomyChangeSet.apply(decodedChangeSet, base))
        .as("变更集过线格式后重建逐值不变")
        .isEqualTo(target);

    // 反向：非空 → 空要走 Remove（"内容变成空"不是 Unchanged），过线格式后同样逐值回空。
    EconomyChangeSet removal = EconomyChangeSet.between(target, base);
    assertThat(removal.classFirst()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(EconomyChangeSet.apply(removal, target)).as("非空 → 空").isEqualTo(base);
    EconomyChangeSet decodedRemoval =
        (EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(removal));
    assertThat(EconomyChangeSet.apply(decodedRemoval, target))
        .as("非空 → 空过线格式后仍是空态")
        .isEqualTo(base);

    EconomySnapshot snapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(7)), SimosTimestamp.of(7), target);
    EconomySnapshot decoded =
        (EconomySnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(decoded.data()).as("快照过线格式后 classFirst 逐值不变").isEqualTo(target);
    assertThat(decoded.data().classFirst()).isEqualTo(state);
  }

  @Test
  void legacySnapshotWithoutClassFirstDecodesToEmptyState() {
    String legacy =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":0,\"calendarLabel\":null},"
            + "\"data\":{\"industries\":{}}}";

    EconomySnapshot back = (EconomySnapshot) CODEC.decodeSnapshot(legacy);

    assertThat(back.data().classFirst())
        .as("旧档缺键 ⇒ ClassFirstState.empty()")
        .isEqualTo(ClassFirstState.empty());
    assertThat(back.data().classFirst().isEmpty()).isTrue();
  }

  @Test
  void legacyChangeSetWithoutClassFirstDecodesToUnchangedAndAppliesAsEmptyState() {
    String legacy = "{\"industries\":{\"@class\":\"unchanged\"}}";

    EconomyChangeSet back = (EconomyChangeSet) CODEC.decodeChangeSet(legacy);

    assertThat(back.classFirst().changed()).as("旧变更集缺键 ⇒ Unchanged").isFalse();
    EconomyData applied = EconomyChangeSet.apply(back, EconomyData.empty());
    assertThat(applied.classFirst())
        .as("Unchanged 施加到空基态 ⇒ 仍是空态")
        .isEqualTo(ClassFirstState.empty());
  }

  /** 用真实引擎跑几个 tick，确保夹具覆盖账户/家户/流动事件等非空子表。 */
  private static ClassFirstState seededState() {
    PilotConfig config = config(MobilityPolicy.tenancyDefaults());
    ClassFirstPilotEngine engine = new ClassFirstPilotEngine(config, fixture());
    engine.runTicks(24);
    return engine.snapshot();
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
