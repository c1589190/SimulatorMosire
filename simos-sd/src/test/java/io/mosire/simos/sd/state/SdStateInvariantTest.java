package io.mosire.simos.sd.state;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.testing.SdFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** spec §三.1 的五条构造期不变量：每条一个**故意违规**用例，构造必须抛。 */
class SdStateInvariantTest {

  @Test
  void r4DuplicateDirectiveIsRejected() {
    SdState base = SdFixtures.full();
    Map<DirectiveId, Directive> bad = new LinkedHashMap<>(base.directives());
    DirectiveId extra = new DirectiveId("d-dup");
    bad.put(extra, SdFixtures.directive(extra, SdFixtures.DM1, 0));
    assertThatThrownBy(() -> base.withDirectives(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("R4 违反：决策人 dm1 在 tick 0 已有 Directive");
  }

  @Test
  void danglingForeignKeyIsRejected() {
    SdState base = SdFixtures.full();
    Map<ArmyId, Army> bad = new LinkedHashMap<>(base.armies());
    ArmyId extra = new ArmyId("a-bad");
    bad.put(extra, new Army(extra, new NationId("ghost"), SdFixtures.U1, "a-bad"));
    assertThatThrownBy(() -> base.withArmies(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("引用完整性：Army a-bad 的 nationId ghost 不存在");
  }

  @Test
  void outcomeNotInAnyStageTableIsRejected() {
    SdState base = SdFixtures.full();
    Map<CombatStateId, CombatState> bad = new LinkedHashMap<>(base.combatStates());
    bad.put(
        SdFixtures.CS1,
        new CombatState(
            SdFixtures.CS1,
            SdFixtures.C1,
            SdFixtures.S1,
            new HexCoord(0, 0),
            Set.of(SdFixtures.U1),
            Optional.of(new CombatOutcomeId("ghost")),
            Set.of()));
    assertThatThrownBy(() -> base.withCombatStates(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("结局一致性")
        .hasMessageContaining("ghost");
  }

  @Test
  void brokenStageChainIsRejected() {
    SdState base = SdFixtures.full();
    CombatStage s1 =
        new CombatStage(
            SdFixtures.S1,
            "s1",
            Set.of(SdFixtures.U1),
            List.of(new Trigger.AtOrAfterTick(0)),
            List.of(new Trigger.AtOrAfterTick(7)),
            0,
            10,
            new OutcomeTable(List.of(SdFixtures.option(SdFixtures.O1))));
    CombatStage s2 =
        new CombatStage(
            SdFixtures.S2,
            "s2",
            Set.of(SdFixtures.U1),
            List.of(new Trigger.AtOrAfterTick(5)),
            List.of(new Trigger.AtOrAfterTick(9)),
            0,
            10,
            new OutcomeTable(List.of(SdFixtures.option(SdFixtures.O2))));
    Map<CombatId, Combat> bad = new LinkedHashMap<>(base.combats());
    bad.put(
        SdFixtures.C1,
        new Combat(
            SdFixtures.C1, "combat-c1", List.of(s1, s2), Set.of(SdFixtures.U1), Optional.empty()));
    assertThatThrownBy(() -> base.withCombats(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("阶段链断裂");
  }

  @Test
  void emptyDeltasLossRecordIsRejected() {
    SdState base = SdFixtures.full();
    Map<LossRecordId, LossRecord> bad = new LinkedHashMap<>(base.lossRecords());
    LossRecordId extra = new LossRecordId("l-empty");
    bad.put(extra, new LossRecord(extra, SdFixtures.C1, SdFixtures.S1, SdFixtures.REV1, List.of()));
    assertThatThrownBy(() -> base.withLossRecords(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("损失记录 l-empty 的 deltas 不得为空");
  }

  @Test
  void lossRecordFromAnotherCombatIsRejected() {
    SdState base = SdFixtures.full();
    Map<CombatStateId, CombatState> bad = new LinkedHashMap<>(base.combatStates());
    bad.put(
        SdFixtures.CS1,
        new CombatState(
            SdFixtures.CS1,
            SdFixtures.C2,
            SdFixtures.S1,
            new HexCoord(0, 0),
            Set.of(SdFixtures.U1),
            Optional.empty(),
            Set.of(SdFixtures.L1)));
    assertThatThrownBy(() -> base.withCombatStates(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("损失记录归属");
  }
}
