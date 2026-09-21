package io.mosire.simos.sd.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.verify.RoundTripAssertions;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 铁律 5 的机械化落地：反射枚举 {@link SdState} 的 record 组件，逐组件造差异，三条断言。 */
class SdRoundTripTest {

  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyStateComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : SdState.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      SdState base = SdFixtures.full();
      SdState target = SdFixtures.mutated(base, name);
      SdChangeSet cs = SdChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(SdChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("SdState 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyTenComponentsMatchingTheState() {
    assertThat(SdChangeSet.class.getRecordComponents()).hasSize(10);
    assertThat(componentNames(SdChangeSet.class))
        .as("变更集的每个组件都必须在 SdState 里有同名的 record 组件")
        .isSubsetOf(componentNames(SdState.class));
    assertThat(componentNames(SdState.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(SdChangeSet.class));
  }

  @Test
  void fullFixtureRoundTripsThroughTheChangeSet() {
    RoundTripAssertions.assertRoundTrip(
        SdFixtures.empty(), SdFixtures.full(), SdChangeSet::between, SdChangeSet::apply);
  }

  /**
   * ★ M11 判据：绑定 provider 的决策人逐字段往返（铁律 5）。变异靶子 m2 = 重建路径丢 {@code providerId}。
   *
   * <p>★ 判别力来自**目标态里那个被改动的条目本身带 providerId**：只测"未绑定"的往返看不见新字段。
   */
  @Test
  void boundProviderSurvivesTheChangeSetRoundTrip() {
    SdState base = SdFixtures.full();
    SdState target =
        base.withDecisionMakers(
            Map.of(
                SdFixtures.DM1, SdFixtures.boundDecisionMaker(SdFixtures.DM1, "p-rt"),
                SdFixtures.DM2, SdFixtures.decisionMaker(SdFixtures.DM2)));
    SdChangeSet cs = SdChangeSet.between(base, target);

    assertThat(cs.decisionMakers().changed()).as("绑定必须被 between 看见").isTrue();
    SdState rebuilt = SdChangeSet.apply(cs, base);
    assertThat(rebuilt).as("逐字段重建出 target").isEqualTo(target);
    assertThat(rebuilt.decisionMakers().get(SdFixtures.DM1).providerId())
        .as("重建后 providerId 一字不丢")
        .contains("p-rt");
  }

  @Test
  void snapshotNamespaceIsSd() {
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            SdFixtures.empty());
    assertThat(snapshot.namespace()).isEqualTo("sd");
  }

  private static boolean changedOf(SdChangeSet cs, String name) {
    return switch (name) {
      case "nations" -> cs.nations().changed();
      case "armies" -> cs.armies().changed();
      case "combats" -> cs.combats().changed();
      case "combatStates" -> cs.combatStates().changed();
      case "decisionMakers" -> cs.decisionMakers().changed();
      case "directives" -> cs.directives().changed();
      case "effects" -> cs.effects().changed();
      case "verdicts" -> cs.verdicts().changed();
      case "lossRecords" -> cs.lossRecords().changed();
      case "info" -> cs.info().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }
}
