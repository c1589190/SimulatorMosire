package io.mosire.simos.army.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import org.junit.jupiter.api.Test;

/**
 * {@code army.AppendCombatStage} 命令边界（T2a / D-009 补裁 + D-010）：阶段 id 重复拒、participants 缺省=记录级、载荷带
 * {@code selectedOutcomeId}/{@code rollSeed} 拒、记录不存在拒。
 */
class AppendCombatStageHandlerTest {

  private static final AppendCombatStageHandler HANDLER = new AppendCombatStageHandler();

  @Test
  void typeIsArmyAppendCombatStageAndItIsGmOnly() {
    assertThat(HANDLER.type()).isEqualTo("army.AppendCombatStage");
    assertThat(HANDLER).isInstanceOf(GmOnlyCommand.class);
  }

  @Test
  void appendsAStageAndDefaultsParticipantsToTheRecordLevel() {
    ArmyData base = baseData();
    ArmyData next =
        applied(
            base,
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"阶段二过程\","
                + "\"outcomes\":[{\"id\":\"o3\",\"label\":\"城破\",\"weight\":100}]}}");

    CombatRecord record = next.combats().get(ArmyFixtures.C1);
    assertThat(record.stages()).as("追加不是替换：旧阶段原样在前").hasSize(2);
    CombatStage appended = record.stages().get(1);
    assertThat(appended.id()).isEqualTo(ArmyFixtures.S2);
    assertThat(appended.participants())
        .as("阶段省略 participants ⇒ 沿用记录级（保序）")
        .containsExactly(ArmyFixtures.U1, ArmyFixtures.U2);
    assertThat(appended.outcomes()).hasSize(1);
    assertThat(appended.outcomes().get(0).id()).isEqualTo(ArmyFixtures.O3);
    assertThat(appended.selectedOutcomeId()).as("新阶段一律落未判定形态").isEmpty();
    assertThat(appended.rollSeed()).isEmpty();
    assertThat(base.combats().get(ArmyFixtures.C1).stages()).as("原切片不变").hasSize(1);
  }

  @Test
  void rejectsADuplicateStageIdByName() {
    ArmyData base = baseData();
    HandlerOutcome outcome =
        handle(
            base,
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s1\",\"name\":\"重复接触\",\"text\":\"过程\"}}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("★ 阶段按 id 不可变：拒因点名 s1")
        .contains("s1");
  }

  @Test
  void rejectsAStageCarryingADecision() {
    HandlerOutcome withOutcome =
        handle(
            baseData(),
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"过程\","
                + "\"selectedOutcomeId\":\"o1\"}}");
    HandlerOutcome withSeed =
        handle(
            baseData(),
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"过程\",\"rollSeed\":7}}");

    assertThat(withOutcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) withOutcome).reason()).contains("selectedOutcomeId");
    assertThat(withSeed).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) withSeed).reason()).contains("rollSeed");
  }

  @Test
  void rejectsAnUnknownRecord() {
    HandlerOutcome outcome =
        handle(
            ArmyData.empty(),
            "{\"combatId\":\"nope\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"过程\"}}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因点名不存在的记录 id")
        .contains("不存在")
        .contains("nope");
  }

  @Test
  void rejectsBadStageShapesAndDomainValues() {
    HandlerOutcome zeroWeight =
        handle(
            baseData(),
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"过程\","
                + "\"outcomes\":[{\"id\":\"o3\",\"label\":\"城破\",\"weight\":0}]}}");
    HandlerOutcome emptyParticipants =
        handle(
            baseData(),
            "{\"combatId\":\"c-1\",\"stage\":{\"id\":\"s2\",\"name\":\"续战\",\"text\":\"过程\","
                + "\"participants\":[]}}");
    HandlerOutcome missingStage = handle(baseData(), "{\"combatId\":\"c-1\"}");

    assertThat(((HandlerOutcome.Rejected) zeroWeight).reason()).contains("weight");
    assertThat(((HandlerOutcome.Rejected) emptyParticipants).reason()).contains("至少");
    assertThat(((HandlerOutcome.Rejected) missingStage).reason()).contains("stage");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static ArmyData baseData() {
    return ArmyFixtures.data(ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L));
  }

  private static HandlerOutcome handle(ArmyData base, String payload) {
    return HANDLER.handle(ArmyFixtures.world(base, 12L), payload);
  }

  private static ArmyData applied(ArmyData base, String payload) {
    HandlerOutcome outcome = handle(base, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    ArmyChangeSet changeSet = (ArmyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return ArmyChangeSet.apply(changeSet, base);
  }
}
