package io.mosire.simos.sd.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
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
  void changeSetHasExactlyTwelveComponentsMatchingTheState() {
    assertThat(SdChangeSet.class.getRecordComponents()).hasSize(12);
    assertThat(componentNames(SdChangeSet.class))
        .as("变更集的每个组件都必须在 SdState 里有同名的 record 组件")
        .isSubsetOf(componentNames(SdState.class));
    assertThat(componentNames(SdState.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(SdChangeSet.class));
  }

  /**
   * ★ D5 / R6 新增的两个组件**逐字段**往返：外交关系边（键的方向 + kind/text/updatedTick）与外交事件（id/tick/participants/text）。
   *
   * <p>★ 判别力来自目标态里**新加的条目本身**：反射枚举那条只判"整个组件进变更集 + 整态相等"，这里把新组件的每个字段单独钉住—— 任何"重建时丢一个字段、但整态 equals
   * 恰好被别的字段掩盖"都不会发生，因为目标态与重建态是逐字段对照。
   */
  @Test
  void theTwoNewDiplomaticComponentsSurviveTheRoundTripFieldByField() {
    SdState base = SdFixtures.full();
    SdState target =
        SdFixtures.mutated(SdFixtures.mutated(base, "diplomaticRelations"), "diplomaticEvents");
    SdChangeSet cs = SdChangeSet.between(base, target);

    assertThat(cs.diplomaticRelations().changed()).as("新关系边必须被 between 看见").isTrue();
    assertThat(cs.diplomaticEvents().changed()).as("新外交事件必须被 between 看见").isTrue();

    SdState rebuilt = SdChangeSet.apply(cs, base);
    assertThat(rebuilt).as("逐字段重建出 target").isEqualTo(target);

    DiplomaticRelationKey extraKey =
        new DiplomaticRelationKey(SdFixtures.N1, new NationId("n-extra"));
    DiplomaticRelation extraRelation = rebuilt.diplomaticRelations().get(extraKey);
    assertThat(extraRelation).as("新增关系边本身在重建态里").isNotNull();
    assertThat(extraRelation.kind()).as("关系边 kind 往返").contains("互市");
    assertThat(extraRelation.text()).as("关系边 text 往返").isEqualTo("新增外交边（夹具）");
    assertThat(extraRelation.updatedTick()).as("关系边 updatedTick 往返").isEqualTo(11L);

    DiplomaticRelation forward = rebuilt.diplomaticRelations().get(SdFixtures.DR12);
    assertThat(forward).as("既有 N1→N2 边原样保留").isNotNull();
    assertThat(forward.kind()).contains("称臣纳贡");
    assertThat(forward.text()).isEqualTo("N1 向 N2 称臣纳贡（夹具）");
    assertThat(forward.updatedTick()).isEqualTo(7L);
    assertThat(rebuilt.diplomaticRelations().get(SdFixtures.DR21).kind())
        .as("反向边 N2→N1 与 N1→N2 是两个不同的键（方向不丢）")
        .isEmpty();

    DiplomaticEventId extraEventId = new DiplomaticEventId("de-extra");
    DiplomaticEvent extraEvent = rebuilt.diplomaticEvents().get(extraEventId);
    assertThat(extraEvent).as("新增外交事件本身在重建态里").isNotNull();
    assertThat(extraEvent.id()).as("事件 id 往返").isEqualTo(extraEventId);
    assertThat(extraEvent.tick()).as("事件 tick 往返").isEqualTo(11L);
    assertThat(extraEvent.participants())
        .as("事件 participants 往返（保序）")
        .containsExactly(SdFixtures.N1, SdFixtures.N2);
    assertThat(extraEvent.text()).as("事件 text 往返").isEqualTo("追加外交事件（夹具）");
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

  /**
   * ★ 会话世代（{@code sd.ResetDecisionMakerConversation} 写的那个字段）逐字段往返（铁律 5）。
   *
   * <p>★ 判别力来自**目标态里那条目自己带非零世代**：缺省是 0，故"世代被打通"这件事在缺省夹具下看不见——用 {@code SdFixtures.decisionMaker(id)}
   * 造的 base/target 两边都是 0，重建丢字段也照样绿。
   */
  @Test
  void aNonZeroConversationGenerationSurvivesTheChangeSetRoundTrip() {
    SdState base = SdFixtures.full();
    SdState target =
        base.withDecisionMakers(
            Map.of(
                SdFixtures.DM1, SdFixtures.decisionMakerAtGeneration(SdFixtures.DM1, 3),
                SdFixtures.DM2, SdFixtures.decisionMaker(SdFixtures.DM2)));
    SdChangeSet cs = SdChangeSet.between(base, target);

    assertThat(cs.decisionMakers().changed()).as("世代变化必须被 between 看见").isTrue();
    SdState rebuilt = SdChangeSet.apply(cs, base);
    assertThat(rebuilt).as("逐字段重建出 target").isEqualTo(target);
    assertThat(rebuilt.decisionMakers().get(SdFixtures.DM1).conversationGeneration())
        .as("重建后世代一字不丢")
        .isEqualTo(3L);
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
      case "diplomaticRelations" -> cs.diplomaticRelations().changed();
      case "diplomaticEvents" -> cs.diplomaticEvents().changed();
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
