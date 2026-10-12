package io.mosire.simos.army.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 铁律 5 在 army 切片上的机械化落地（T2a / D-012；<b>2026-10-12 批次 3d-1 扩到两个组件</b>）：反射枚举 {@link ArmyData} 的
 * record 组件，逐组件造差异，三条断言 ——新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★★ <b>本批为什么必须当场改（§12.3 的"护栏例外"）</b>：{@code armies}（军队身份）自 {@code SdState} 搬进 {@link ArmyData}
 * ⇒ 组件数 1 → 2。本类是**新架构的护栏**（防静默漂移），不是"钉旧机制的旧测试"⇒ 按 §12.3 当场修，不攒台账。
 *
 * <p>★ 本类也是"不丢失"变异自证的落点：把 {@link ArmyChangeSet#between} 的 {@code armies} 改成恒 {@code Unchanged}（或
 * 干脆不给 {@link ArmyData} 的新组件接上变更集）， {@link #everyArmyDataComponentParticipatesInTheChangeSet()}
 * 当场红。
 *
 * <p>★ <b>夹具为什么在本类里就地拼（不改 {@code ArmyFixtures}）</b>：本批的写权只覆盖"生产代码 + 本文件"，而 {@code ArmyFixtures}
 * 是别的测试类共用的夹具 ⇒ 就地拼一份非平凡 {@code armies}（两条、插入序刻意非 id 升序）， 不让第二个测试文件进本批的改动面。
 */
class ArmyRoundTripTest {

  /** 本切片没有"不进变更集"的组件 ⇒ 豁免集合必须是空集，且单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── 本类自用的 armies 夹具（见类注：不动共享夹具）────────────────────────────────────

  private static final ArmyId A1 = new ArmyId("a-1");
  private static final ArmyId A2 = new ArmyId("a-2");

  /** 第三条 id：只用来造"同 id 换值"的 Patch 变体（不动 {@code a-1}/{@code a-2} 的插入序）。 */
  private static final ArmyId A3 = new ArmyId("a-3");

  /** 认领了一个 GOV 主子的军队（{@code masterGovUnitId} 非空 ⇒ Optional 那一维也被覆盖）。 */
  private static Army armyWithMaster(ArmyId id, String name) {
    return new Army(
        id, Optional.of(new UnitId("g-" + id.value())), new UnitId("u-" + id.value()), name);
  }

  /** 未认领主子的军队（{@code Optional.empty()} 那一档）。 */
  private static Army armyWithoutMaster(ArmyId id, String name) {
    return new Army(id, Optional.empty(), new UnitId("u-" + id.value()), name);
  }

  /** 插入序刻意是 {@code a-2 → a-1}（不是 id 升序）：保序断言的判别力据此成立。 */
  private static Map<ArmyId, Army> sampleArmies() {
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(A2, armyWithoutMaster(A2, "第二军"));
    armies.put(A1, armyWithMaster(A1, "第一军"));
    return armies;
  }

  /** 一份两个组件都非空、且各自非平凡的 army 切片。 */
  private static ArmyData sampleData() {
    return ArmyFixtures.sampleData().withArmies(sampleArmies());
  }

  @Test
  void everyArmyDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : ArmyData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      ArmyData base = ArmyData.empty();
      ArmyData target =
          switch (name) {
            case "armies" -> ArmyData.empty().withArmies(sampleArmies());
            case "combats" -> ArmyFixtures.sampleData();
            default -> base;
          };
      ArmyChangeSet changeSet = ArmyChangeSet.between(base, target);

      assertThat(changeSet.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changedComponent(changeSet, name))
          .as("组件 %s 必须被 between 报成非 Unchanged", name)
          .isTrue();
      assertThat(ArmyChangeSet.apply(changeSet, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /** 按组件名取那条 {@link FieldDelta}（新组件忘了接变更集时，这里当场红而不是编译不过）。 */
  private static boolean changedComponent(ArmyChangeSet changeSet, String component) {
    return switch (component) {
      case "armies" -> changeSet.armies().changed();
      case "combats" -> changeSet.combats().changed();
      default -> throw new AssertionError("ArmyData 有未接进变更集的组件: " + component);
    };
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("ArmyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyTwoComponentsMatchingTheState() {
    assertThat(ArmyChangeSet.class.getRecordComponents()).hasSize(2);
    assertThat(componentNames(ArmyChangeSet.class))
        .as("变更集的每个组件都必须在 ArmyData 里有同名的 record 组件")
        .isEqualTo(componentNames(ArmyData.class));
  }

  @Test
  void allEqualYieldsUnchangedNotAnEmptyObject() {
    ArmyData full = sampleData();
    ArmyChangeSet changeSet = ArmyChangeSet.between(full, full);

    assertThat(changeSet.armies()).as("全相等 ⇒ Unchanged").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(changeSet.combats()).as("全相等 ⇒ Unchanged").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(changeSet.isEmpty()).isTrue();
    assertThat(ArmyChangeSet.apply(changeSet, full)).as("Unchanged 施加回 base 原样").isEqualTo(full);
  }

  @Test
  void aLegacyChangeSetWithoutEitherKeyReadsAsUnchanged() {
    ArmyData full = sampleData();
    ArmyChangeSet legacy = new ArmyChangeSet(null, null);

    assertThat(legacy.armies())
        .as("★ 旧档缺 armies 键 ⇒ Unchanged（fail-closed：旧档没提该组件 = 没动它）")
        .isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(legacy.combats())
        .as("★ 旧档缺 combats 键 ⇒ Unchanged")
        .isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(legacy.isEmpty()).isTrue();
    assertThat(ArmyChangeSet.apply(legacy, full)).as("ΔUnchanged 不得清空已有军队身份/交战表").isEqualTo(full);
  }

  /** 逐字段重建非平凡目标：整体 equals 只是最后一条，前面每条指出"哪个字段丢了"。 */
  @Test
  void roundTripRebuildsEveryArmyField() {
    ArmyData base = ArmyData.empty();
    ArmyData target = sampleData();

    ArmyData rebuilt = ArmyChangeSet.apply(ArmyChangeSet.between(base, target), base);

    assertThat(rebuilt).as("整态往返").isEqualTo(target);
    assertThat(rebuilt.armies().keySet()).as("★ 军队表保序（插入序 a-2 → a-1）").containsExactly(A2, A1);

    Army a1 = rebuilt.armies().get(A1);
    assertThat(a1.id()).isEqualTo(A1);
    assertThat(a1.name()).isEqualTo("第一军");
    assertThat(a1.rootUnit()).isEqualTo(new UnitId("u-a-1"));
    assertThat(a1.masterGovUnitId())
        .as("认领的 GOV 主子（Optional 的 present 侧）")
        .contains(new UnitId("g-a-1"));

    Army a2 = rebuilt.armies().get(A2);
    assertThat(a2.name()).isEqualTo("第二军");
    assertThat(a2.masterGovUnitId()).as("未认领主子（Optional 的 empty 侧）").isEmpty();

    assertThat(rebuilt.combats().keySet())
        .as("★ 交战记录保序（插入序 c-2 → c-1）")
        .containsExactly(ArmyFixtures.C2, ArmyFixtures.C1);
  }

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
    ArmyData full = sampleData();

    ArmyChangeSet remove = ArmyChangeSet.between(full, ArmyData.empty());
    assertThat(remove.armies()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(remove.combats()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(ArmyChangeSet.apply(remove, full)).isEqualTo(ArmyData.empty());

    // ① armies 纯增（a-3）⇒ Upsert；combats 又删 c-2 又增 c-3 ⇒ Patch。两侧一起重建到 target。
    ArmyData added =
        ArmyFixtures.data(
                ArmyFixtures.duelRecord(ArmyFixtures.C3, 12L), full.combats().get(ArmyFixtures.C1))
            .withArmies(full.armies())
            .withArmy(armyWithMaster(A3, "新加入的第三军"));
    ArmyChangeSet add = ArmyChangeSet.between(full, added);
    assertThat(add.armies())
        .as("只增不删 ⇒ Upsert（不是 Unchanged）")
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(add.combats())
        .as("删 c-2 + 增 c-3 ⇒ Patch，两侧都不丢")
        .isInstanceOf(FieldDelta.Patch.class);
    ArmyData rebuiltAdd = ArmyChangeSet.apply(add, full);
    assertThat(rebuiltAdd).as("Upsert + Patch 一起重建到 target").isEqualTo(added);
    assertThat(rebuiltAdd.armies().keySet())
        .as("★ Upsert 不丢已有军队（a-2 / a-1 原样 + 新增 a-3）")
        .containsExactly(A2, A1, A3);
    assertThat(rebuiltAdd.combats().keySet())
        .as("★ 同一批里 combats 也两侧都不丢")
        .containsExactly(ArmyFixtures.C1, ArmyFixtures.C3);

    // ② armies 又删又增（去掉 a-2 + 增 a-3）⇒ Patch（**先删后增**，见 FieldDelta.Patch 的语义）。
    Map<ArmyId, Army> swapped = new LinkedHashMap<>();
    swapped.put(A1, full.armies().get(A1));
    swapped.put(A3, armyWithMaster(A3, "换防的第三军"));
    ArmyData replaced = full.withArmies(swapped);
    ArmyChangeSet patch = ArmyChangeSet.between(full, replaced);
    assertThat(patch.armies()).as("同组件又删又增 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(ArmyChangeSet.apply(patch, full))
        .as("Patch：删 a-2 + 增 a-3，a-1 原样保留")
        .isEqualTo(replaced);
    assertThat(ArmyChangeSet.apply(patch, full).armies().keySet())
        .as("★ Patch 先删后增：留下的正是 {a-1, a-3}")
        .containsExactly(A1, A3);
  }

  @Test
  void applyToTakesTheNewRefAndTimestampFromTheMeta() {
    ArmyData full = sampleData();
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
    assertThatThrownBy(() -> ArmyChangeSet.apply(new ArmyChangeSet(null, null), null))
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
