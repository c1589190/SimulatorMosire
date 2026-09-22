package io.mosire.simos.sd.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.unit.UnitId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * spec §三 数据模型的构造期不变量：{@code OutcomeOption.weight > 0}、{@code OutcomeTable.options} 非空、 {@code
 * CasualtyDelta} 双轨符号、集合不可变、{@code VerdictMeta} 三字段非空、{@code Trigger} 变体边界。
 */
class SdModelTest {

  private static CasualtySpec spec() {
    return new CasualtySpec(0, Map.of());
  }

  private static OutcomeOption option(String id, int weight) {
    return new OutcomeOption(new CombatOutcomeId(id), "label-" + id, weight, spec());
  }

  @Test
  void outcomeOptionRejectsNonPositiveWeight() {
    assertThatThrownBy(() -> option("o1", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("weight 必须 > 0: 0");
    assertThatThrownBy(() -> option("o1", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("weight 必须 > 0: -1");
  }

  @Test
  void outcomeTableRejectsEmptyOptions() {
    assertThatThrownBy(() -> new OutcomeTable(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("OutcomeTable.options 不得为空（N2）");
    assertThatThrownBy(() -> new OutcomeTable(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("options 不得为 null");
  }

  @Test
  void outcomeTableCopiesAndFreezesOptions() {
    List<OutcomeOption> mutable = new ArrayList<>();
    mutable.add(option("o1", 1));
    OutcomeTable table = new OutcomeTable(mutable);
    mutable.add(option("o2", 1));
    assertThat(table.options()).hasSize(1);
    assertThatThrownBy(() -> table.options().add(option("o3", 1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void casualtyDeltaRejectsPositivePersonnel() {
    assertThatThrownBy(() -> new CasualtyDelta(new UnitId("u1"), 1, Map.of(), LossClass.PERMANENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("personnel 必须 ≤ 0: 1");
  }

  @Test
  void casualtyDeltaRejectsNonNegativeEquipment() {
    assertThatThrownBy(
            () -> new CasualtyDelta(new UnitId("u1"), -1, Map.of("tank", 1), LossClass.PERMANENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("equipment 的值必须为负: tank=1");
    assertThatThrownBy(
            () -> new CasualtyDelta(new UnitId("u1"), -1, Map.of("tank", 0), LossClass.RECOVERABLE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("equipment 的值必须为负: tank=0");
  }

  @Test
  void casualtyDeltaCopiesAndFreezesEquipment() {
    Map<String, Integer> mutable = new LinkedHashMap<>();
    mutable.put("tank", -2);
    CasualtyDelta delta = new CasualtyDelta(new UnitId("u1"), -1, mutable, LossClass.RECOVERABLE);
    mutable.put("tank", -99);
    mutable.put("artillery", -5);
    assertThat(delta.equipment()).containsExactly(Map.entry("tank", -2));
    assertThatThrownBy(() -> delta.equipment().put("x", -1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void collectionComponentsAreCopiedAndFrozen() {
    UnitId u1 = new UnitId("u1");
    UnitId u2 = new UnitId("u2");

    Set<UnitId> participants = new LinkedHashSet<>();
    participants.add(u1);
    Combat combat =
        new Combat(new CombatId("c1"), "combat", List.of(), participants, Optional.empty());
    participants.add(u2);
    assertThat(combat.participants()).containsExactly(u1);
    assertThatThrownBy(() -> combat.participants().add(u2))
        .isInstanceOf(UnsupportedOperationException.class);

    Set<String> tools = new LinkedHashSet<>();
    tools.add("sd.SubmitVerdict");
    DecisionMaker dm =
        new DecisionMaker(
            new DecisionMakerId("dm1"),
            new Affiliation.Nation(new NationId("n1")),
            tools,
            AccessLimit.empty(),
            1);
    tools.add("simos.command.submit");
    assertThat(dm.allowedTools()).containsExactly("sd.SubmitVerdict");
    assertThatThrownBy(() -> dm.allowedTools().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);

    Set<String> prefixes = new LinkedHashSet<>();
    prefixes.add("Map1/region/r1");
    Map<String, Set<String>> byNamespace = new LinkedHashMap<>();
    byNamespace.put("map", prefixes);
    AccessLimit limit =
        new AccessLimit(byNamespace, Set.of("casualties"), DisclosurePolicy.PERCEPTION_ONLY);
    prefixes.add("Map1/region/r2");
    byNamespace.put("unit", Set.of("u-9"));
    assertThat(limit.prefixesByNamespace().get("map")).containsExactly("Map1/region/r1");
    assertThat(limit.prefixesByNamespace()).as("命名空间图冻在赋值处（外部 map 改了不算）").containsOnlyKeys("map");
    assertThatThrownBy(() -> limit.prefixesByNamespace().get("map").add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> limit.prefixesByNamespace().put("x", Set.of("y")))
        .isInstanceOf(UnsupportedOperationException.class);

    Set<EffectId> effects = new LinkedHashSet<>();
    effects.add(new EffectId("e1"));
    Directive directive =
        new Directive(
            new DirectiveId("d1"),
            new DecisionMakerId("dm1"),
            0,
            Optional.empty(),
            "intent-key",
            List.of(),
            effects,
            Optional.empty(),
            DirectiveStatus.PLANNED);
    effects.add(new EffectId("e2"));
    assertThat(directive.effects()).containsExactly(new EffectId("e1"));
    assertThatThrownBy(() -> directive.effects().add(new EffectId("e3")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void verdictMetaRejectsBlankFields() {
    assertThatThrownBy(() -> new VerdictMeta("", "p1", "d1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("model 不得为空白");
    assertThatThrownBy(() -> new VerdictMeta("m1", "  ", "d1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("promptVersion 不得为空白");
    assertThatThrownBy(() -> new VerdictMeta("m1", "p1", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("inputBriefDigest 不得为空白");
  }

  @Test
  void triggerVariantsEnforceTheirBounds() {
    assertThatThrownBy(() -> new Trigger.And(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("And 不得为空（空 And 恒真，属拼写错误）");
    assertThatThrownBy(() -> new Trigger.Or(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Or 不得为空（空 Or 恒假，属拼写错误）");
    assertThatThrownBy(() -> new Trigger.ThresholdKills(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("kills 必须 ≥ 1: 0");
    assertThatThrownBy(() -> new Trigger.AfterTicks(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ticks 必须 ≥ 1: 0");
    assertThat(new Trigger.AtOrAfterTick(0)).isEqualTo(new Trigger.AtOrAfterTick(0));
  }

  @Test
  void nationArmyAndCombatStageRejectInvalidShapes() {
    assertThatThrownBy(() -> new Nation(new NationId("n1"), "  ", new RegionId("r1"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("name 不得为空白");
    assertThatThrownBy(() -> new Nation(new NationId("n1"), "n", new RegionId("r1"), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("adminBudgetPerTick 必须 ≥ 0: -1");
    assertThatThrownBy(() -> new Army(new ArmyId("a1"), null, new UnitId("u1"), "a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nationId 不得为 null");

    OutcomeTable table = new OutcomeTable(List.of(option("o1", 1)));
    assertThatThrownBy(
            () ->
                new CombatStage(
                    new CombatStageId("s1"), "stage", Set.of(), List.of(), List.of(), 5, 2, table))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxDurationTicks 必须 ≥ minDurationTicks: 2 < 5");

    CombatState state =
        new CombatState(
            new CombatStateId("cs1"),
            new CombatId("c1"),
            new CombatStageId("s1"),
            new HexCoord(0, 0),
            Set.of(),
            Optional.empty(),
            Set.of());
    assertThat(state.combatId()).isEqualTo(new CombatId("c1"));
  }

  @Test
  void combatStateFreezesParticipantsAndLosses() {
    UnitId u1 = new UnitId("u1");
    UnitId u2 = new UnitId("u2");
    Set<UnitId> participants = new LinkedHashSet<>();
    participants.add(u1);
    Set<LossRecordId> losses = new LinkedHashSet<>();
    losses.add(new LossRecordId("l1"));
    CombatState state =
        new CombatState(
            new CombatStateId("cs1"),
            new CombatId("c1"),
            new CombatStageId("s1"),
            new HexCoord(0, 0),
            participants,
            Optional.empty(),
            losses);
    participants.add(u2);
    losses.add(new LossRecordId("l2"));
    assertThat(state.participants()).containsExactly(u1);
    assertThat(state.losses()).containsExactly(new LossRecordId("l1"));
    assertThatThrownBy(() -> state.participants().add(u2))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> state.losses().add(new LossRecordId("l3")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
