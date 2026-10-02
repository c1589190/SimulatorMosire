package io.mosire.simos.army.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 铁律 5 在 army 切片上的机械化落地（T2a / D-012）：反射枚举 {@link ArmyData} 的 record 组件，逐组件造差异，三条断言
 * ——新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ 本类也是"不丢失"变异自证的落点：把 {@link ArmyChangeSet#between} 的 {@code combats} 改成恒 {@code Unchanged}，
 * {@link #everyArmyDataComponentParticipatesInTheChangeSet()} 当场红。
 */
class ArmyRoundTripTest {

  /** 本切片没有"不进变更集"的组件 ⇒ 豁免集合必须是空集，且单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyArmyDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : ArmyData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      ArmyData base = ArmyData.empty();
      ArmyData target = name.equals("combats") ? ArmyFixtures.sampleData() : base;
      ArmyChangeSet changeSet = ArmyChangeSet.between(base, target);

      assertThat(changeSet.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changeSet.combats().changed())
          .as("组件 %s 必须被 between 报成非 Unchanged", name)
          .isTrue();
      assertThat(ArmyChangeSet.apply(changeSet, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("ArmyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyOneComponentMatchingTheState() {
    assertThat(ArmyChangeSet.class.getRecordComponents()).hasSize(1);
    assertThat(componentNames(ArmyChangeSet.class))
        .as("变更集的每个组件都必须在 ArmyData 里有同名的 record 组件")
        .isEqualTo(componentNames(ArmyData.class));
  }

  @Test
  void allEqualYieldsUnchangedNotAnEmptyObject() {
    ArmyData full = ArmyFixtures.sampleData();
    ArmyChangeSet changeSet = ArmyChangeSet.between(full, full);

    assertThat(changeSet.combats()).as("全相等 ⇒ Unchanged").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(changeSet.isEmpty()).isTrue();
    assertThat(ArmyChangeSet.apply(changeSet, full)).as("Unchanged 施加回 base 原样").isEqualTo(full);
  }

  @Test
  void aLegacyChangeSetWithoutTheKeyReadsAsUnchanged() {
    ArmyData full = ArmyFixtures.sampleData();
    ArmyChangeSet legacy = new ArmyChangeSet(null);

    assertThat(legacy.combats())
        .as("★ 旧档缺 combats 键 ⇒ Unchanged（fail-closed：旧档没提该组件 = 没动它）")
        .isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(legacy.isEmpty()).isTrue();
    assertThat(ArmyChangeSet.apply(legacy, full)).as("ΔUnchanged 不得清空已有交战表").isEqualTo(full);
  }

  /** 逐字段重建非平凡目标：整体 equals 只是最后一条，前面每条指出"哪个字段丢了"。 */
  @Test
  void roundTripRebuildsEveryRecordStageOutcomeAndLossField() {
    ArmyData base = ArmyData.empty();
    ArmyData target = ArmyFixtures.sampleData();

    ArmyData rebuilt = ArmyChangeSet.apply(ArmyChangeSet.between(base, target), base);

    assertThat(rebuilt).as("整态往返").isEqualTo(target);
    assertThat(rebuilt.combats().keySet())
        .as("★ 记录保序（插入序 c-2 → c-1）")
        .containsExactly(ArmyFixtures.C2, ArmyFixtures.C1);

    CombatRecord c1 = rebuilt.combats().get(ArmyFixtures.C1);
    assertThat(c1.kind()).isEqualTo("野战");
    assertThat(c1.tick()).isEqualTo(12L);
    assertThat(c1.hex()).isEqualTo(ArmyFixtures.HEX);
    assertThat(c1.participants())
        .as("participants 保序")
        .containsExactly(ArmyFixtures.U2, ArmyFixtures.U1);
    assertThat(c1.text()).isEqualTo("记录级过程：接触后转入续战");
    assertThat(c1.stages())
        .as("stages 保序")
        .extracting(CombatStage::id)
        .containsExactly(ArmyFixtures.S1, ArmyFixtures.S2);

    CombatStage s1 = c1.stages().get(0);
    assertThat(s1.name()).isEqualTo("接触");
    assertThat(s1.text()).isEqualTo("阶段一：前锋接敌");
    assertThat(s1.participants()).containsExactly(ArmyFixtures.U2, ArmyFixtures.U1);
    assertThat(s1.selectedOutcomeId()).as("未判定").isEmpty();
    assertThat(s1.rollSeed()).as("未投骰").isEmpty();
    assertThat(s1.outcomes())
        .as("outcomes 保序")
        .extracting(CombatOutcome::id)
        .containsExactly(ArmyFixtures.O1, ArmyFixtures.O2);

    CombatOutcome o1 = s1.outcomes().get(0);
    assertThat(o1.label()).isEqualTo("胜");
    assertThat(o1.weight()).isEqualTo(60L);
    assertThat(o1.losses()).hasSize(1);
    CombatUnitLoss loss = o1.losses().get(0);
    assertThat(loss.unit()).isEqualTo(ArmyFixtures.U1);
    assertThat(loss.manpower()).containsExactly(new CompositionDelta("士兵", -30L));
    assertThat(loss.equipment()).containsExactly(new CompositionDelta("步枪", -5L));

    CombatStage s2 = c1.stages().get(1);
    assertThat(s2.outcomes().get(0).id()).isEqualTo(ArmyFixtures.O3);
    assertThat(s2.outcomes().get(0).weight()).isEqualTo(100L);
    assertThat(s2.outcomes().get(0).losses().get(0).equipment())
        .containsExactly(new CompositionDelta("火炮", -1L));

    CombatRecord c2 = rebuilt.combats().get(ArmyFixtures.C2);
    assertThat(c2.kind()).as("kind 是自由文本（轰城与野战同一条命令）").isEqualTo("轰城");
    assertThat(c2.hex()).isEqualTo(ArmyFixtures.OTHER_HEX);
    assertThat(c2.participants()).containsExactly(ArmyFixtures.U1);
    assertThat(c2.stages().get(0).outcomes().get(0).id()).isEqualTo(ArmyFixtures.O1);
  }

  @Test
  void removeAndPatchVariantsRebuildTheTarget() {
    ArmyData full = ArmyFixtures.sampleData();

    ArmyChangeSet remove = ArmyChangeSet.between(full, ArmyData.empty());
    assertThat(remove.combats()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(ArmyChangeSet.apply(remove, full)).isEqualTo(ArmyData.empty());

    ArmyData patched =
        ArmyFixtures.data(
            ArmyFixtures.duelRecord(ArmyFixtures.C3, 12L), full.combats().get(ArmyFixtures.C1));
    ArmyChangeSet patch = ArmyChangeSet.between(full, patched);
    assertThat(patch.combats()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(ArmyChangeSet.apply(patch, full)).as("Patch：删 c-2 + 增 c-3，两侧都不丢").isEqualTo(patched);
  }

  @Test
  void applyToTakesTheNewRefAndTimestampFromTheMeta() {
    ArmyData full = ArmyFixtures.sampleData();
    ArmySnapshot base = new ArmySnapshot(ArmyFixtures.REF, ArmyFixtures.T0, ArmyData.empty());
    ArmyChangeSet changeSet = ArmyChangeSet.between(ArmyData.empty(), full);
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    ArmySnapshot next = changeSet.applyTo(base, newMeta);

    assertThat(next.ref()).as("C28：ref 取 newMeta，不照抄 base 的陈旧坐标").isEqualTo(newMeta.ref());
    assertThat(next.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(next.data()).isEqualTo(full);
  }

  @Test
  void applyAndBetweenRejectNullArguments() {
    assertThatThrownBy(() -> ArmyChangeSet.between(null, ArmyData.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ArmyChangeSet.between(ArmyData.empty(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ArmyChangeSet.apply(null, ArmyData.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ArmyChangeSet.apply(new ArmyChangeSet(null), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void snapshotNamespaceIsArmy() {
    ArmySnapshot snapshot = new ArmySnapshot(ArmyFixtures.REF, ArmyFixtures.T7, ArmyData.empty());
    assertThat(snapshot.namespace())
        .as("SimulationState 构造期校验 modules 的键 == snapshot.namespace() ⇒ 这个字面量是装配契约")
        .isEqualTo("army");
  }

  @Test
  void snapshotRejectsNullComponents() {
    assertThatThrownBy(() -> new ArmySnapshot(null, ArmyFixtures.T7, ArmyData.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ref");
    assertThatThrownBy(() -> new ArmySnapshot(ArmyFixtures.REF, null, ArmyData.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
    assertThatThrownBy(() -> new ArmySnapshot(ArmyFixtures.REF, ArmyFixtures.T7, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("data");
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }
}
