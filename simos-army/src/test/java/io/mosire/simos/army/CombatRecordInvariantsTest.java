package io.mosire.simos.army;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 交战记录四件套的构造期不变量（T2a / D-009 补裁 + D-010）：阶段 id 不重复、outcomes 权重与 id、判定引用完整性、 participants 非空不重复、同表
 * type 不重复。
 *
 * <p>★ 判据来源是各类型类注的"本类自身的不变量"清单，**逐条一条断言**；每条都点名消息里的字段，删掉某条守卫时能读出是哪一条失守。
 */
class CombatRecordInvariantsTest {

  // ── CombatRecord ─────────────────────────────────────────────────────────────────────

  @Test
  void rejectsBlankKindAndText() {
    CombatStage stage =
        ArmyFixtures.stage(ArmyFixtures.S1, "接触", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "  ",
                    1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1),
                    "文本",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind");
    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1),
                    " ",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text");
  }

  @Test
  void rejectsNegativeTickAndNullHex() {
    CombatStage stage =
        ArmyFixtures.stage(ArmyFixtures.S1, "接触", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    -1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1),
                    "文本",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick");
    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    null,
                    List.of(ArmyFixtures.U1),
                    "文本",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hex");
  }

  @Test
  void requiresAtLeastOneDistinctParticipant() {
    CombatStage stage =
        ArmyFixtures.stage(ArmyFixtures.S1, "接触", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1, "野战", 1L, ArmyFixtures.HEX, List.of(), "文本", List.of(stage)))
        .as("D-009：单方入场可判，但空表不是一种记录")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少");
    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1, ArmyFixtures.U1),
                    "文本",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得重复");
    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    ArmyFixtures.HEX,
                    Arrays.asList(ArmyFixtures.U1, null),
                    "文本",
                    List.of(stage)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void requiresAtLeastOneStageAndUniqueStageIds() {
    CombatStage s1 =
        ArmyFixtures.stage(ArmyFixtures.S1, "接触", List.of(ArmyFixtures.U1), "过程", List.of());
    CombatStage s2 =
        ArmyFixtures.stage(ArmyFixtures.S2, "续战", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1),
                    "文本",
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少");
    assertThatThrownBy(
            () ->
                new CombatRecord(
                    ArmyFixtures.C1,
                    "野战",
                    1L,
                    ArmyFixtures.HEX,
                    List.of(ArmyFixtures.U1),
                    "文本",
                    List.of(s1, s2, s1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得重复阶段 id");
  }

  @Test
  void appendingAStageRejectsADuplicateIdAndKeepsTheOrder() {
    CombatRecord record = ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L);
    CombatStage duplicate =
        ArmyFixtures.stage(ArmyFixtures.S1, "另一个接触", List.of(ArmyFixtures.U1), "过程", List.of());
    CombatStage appended =
        ArmyFixtures.stage(ArmyFixtures.S2, "续战", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(() -> record.withAppendedStage(duplicate))
        .as("阶段按 id 不可变，不静默覆盖历史阶段")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("s1");

    CombatRecord next = record.withAppendedStage(appended);
    assertThat(next.stages())
        .as("追加保序：原阶段在前、新阶段在后")
        .containsExactly(record.stages().get(0), appended);
    assertThat(record.stages()).as("原记录不变").hasSize(1);
  }

  @Test
  void replacingAStageRequiresItToExistAndPreservesPosition() {
    CombatRecord record = ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L);
    CombatStage missing =
        ArmyFixtures.stage(ArmyFixtures.S2, "续战", List.of(ArmyFixtures.U1), "过程", List.of());

    assertThatThrownBy(() -> record.withReplacedStage(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("阶段不存在");

    CombatStage resolved = record.stages().get(0).resolvedAs(ArmyFixtures.O2, Optional.of(7L));
    CombatRecord next = record.withReplacedStage(resolved);

    assertThat(next.stages()).containsExactly(resolved);
    assertThat(next.allStagesResolved()).as("唯一阶段已判定").isTrue();
    assertThat(record.allStagesResolved()).as("原记录仍未被判定").isFalse();
  }

  // ── CombatStage ──────────────────────────────────────────────────────────────────────

  @Test
  void stageParticipantsMustBeNonEmptyAndDistinct() {
    assertThatThrownBy(() -> ArmyFixtures.stage(ArmyFixtures.S1, "接触", List.of(), "过程", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少");
    assertThatThrownBy(
            () ->
                ArmyFixtures.stage(
                    ArmyFixtures.S1,
                    "接触",
                    List.of(ArmyFixtures.U1, ArmyFixtures.U1),
                    "过程",
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得重复");
  }

  @Test
  void stageRejectsDuplicateOutcomeIds() {
    CombatOutcome first = ArmyFixtures.outcome(ArmyFixtures.O1, "胜", 1L);
    CombatOutcome sameId = ArmyFixtures.outcome(ArmyFixtures.O1, "再胜", 2L);

    assertThatThrownBy(
            () ->
                ArmyFixtures.stage(
                    ArmyFixtures.S1, "接触", List.of(ArmyFixtures.U1), "过程", List.of(first, sameId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得重复 id");
  }

  @Test
  void stageRejectsADecisionThatReferencesAMissingOutcome() {
    CombatStage stage =
        ArmyFixtures.stage(
            ArmyFixtures.S1,
            "接触",
            List.of(ArmyFixtures.U1),
            "过程",
            List.of(ArmyFixtures.outcome(ArmyFixtures.O1, "胜", 1L)));

    assertThatThrownBy(
            () ->
                new CombatStage(
                    ArmyFixtures.S1,
                    "接触",
                    List.of(ArmyFixtures.U1),
                    "过程",
                    stage.outcomes(),
                    Optional.of(ArmyFixtures.O2),
                    Optional.empty()))
        .as("selectedOutcomeId 必须 ∈ 本阶段 outcomes")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在本阶段");
  }

  @Test
  void stageRejectsARollSeedWithoutASelectedOutcome() {
    assertThatThrownBy(
            () ->
                new CombatStage(
                    ArmyFixtures.S1,
                    "接触",
                    List.of(ArmyFixtures.U1),
                    "过程",
                    List.of(ArmyFixtures.outcome(ArmyFixtures.O1, "胜", 1L)),
                    Optional.empty(),
                    Optional.of(5L)))
        .as("投了骰却没有结局不是合法状态")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("投了骰却没有结局");
  }

  @Test
  void stageRejectsNullOptionalsInsteadOfSilentlyTreatingThemAsAbsent() {
    assertThatThrownBy(
            () ->
                new CombatStage(
                    ArmyFixtures.S1,
                    "接触",
                    List.of(ArmyFixtures.U1),
                    "过程",
                    List.of(),
                    null,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selectedOutcomeId");
    assertThatThrownBy(
            () ->
                new CombatStage(
                    ArmyFixtures.S1,
                    "接触",
                    List.of(ArmyFixtures.U1),
                    "过程",
                    List.of(),
                    Optional.empty(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rollSeed");
  }

  @Test
  void resolvedAsIsOneShotAndRequiresAKnownOutcome() {
    CombatStage stage =
        ArmyFixtures.stage(
            ArmyFixtures.S1,
            "接触",
            List.of(ArmyFixtures.U1),
            "过程",
            List.of(
                ArmyFixtures.outcome(ArmyFixtures.O1, "胜", 1L),
                ArmyFixtures.outcome(ArmyFixtures.O2, "负", 1L)));

    CombatStage resolved = stage.resolvedAs(ArmyFixtures.O1, Optional.of(9L));
    assertThat(resolved.resolved()).isTrue();
    assertThat(resolved.selectedOutcome()).contains(ArmyFixtures.outcome(ArmyFixtures.O1, "胜", 1L));
    assertThat(resolved.rollSeed()).contains(9L);

    assertThatThrownBy(() -> resolved.resolvedAs(ArmyFixtures.O2, Optional.of(9L)))
        .as("★ 同一阶段判定只做一次")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已判定");
    assertThatThrownBy(() -> stage.resolvedAs(ArmyFixtures.O3, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在该阶段");
  }

  // ── CombatOutcome / CombatUnitLoss ───────────────────────────────────────────────────

  @Test
  void outcomeWeightMustBePositive() {
    assertThatThrownBy(() -> new CombatOutcome(ArmyFixtures.O1, "胜", 0L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("weight");
    assertThatThrownBy(() -> new CombatOutcome(ArmyFixtures.O1, "胜", -3L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("weight");
    assertThatThrownBy(() -> new CombatOutcome(ArmyFixtures.O1, "  ", 1L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
  }

  @Test
  void anOutcomeRejectsTheSameUnitTwice() {
    CombatUnitLoss loss = ArmyFixtures.loss(ArmyFixtures.U1, "士兵", -1L, "步枪", -1L);

    assertThatThrownBy(() -> new CombatOutcome(ArmyFixtures.O1, "胜", 1L, List.of(loss, loss)))
        .as("同一 outcome 里重复 unit 会让结算产生两条同单位命令（没有合并裁决）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得重复 unit");
  }

  @Test
  void aUnitLossRejectsDuplicateTypesWithinOneTableOnly() {
    assertThatThrownBy(
            () ->
                new CombatUnitLoss(
                    ArmyFixtures.U1,
                    List.of(new CompositionDelta("士兵", -1L), new CompositionDelta("士兵", -2L)),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manpower")
        .hasMessageContaining("不得有重复 type");
    assertThatThrownBy(
            () ->
                new CombatUnitLoss(
                    ArmyFixtures.U1,
                    List.of(),
                    List.of(new CompositionDelta("步枪", -1L), new CompositionDelta("步枪", -2L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("equipment")
        .hasMessageContaining("不得有重复 type");

    CombatUnitLoss crossTable =
        new CombatUnitLoss(
            ArmyFixtures.U1,
            List.of(new CompositionDelta("同名", -1L)),
            List.of(new CompositionDelta("同名", -1L)));
    assertThat(crossTable.empty()).as("两条表各自的 type 判重；同名跨表是合法的").isFalse();
  }

  @Test
  void anEmptyUnitLossIsEmptyAndRecordsOrder() {
    UnitId other = new UnitId("u-9");
    CombatUnitLoss loss =
        new CombatUnitLoss(
            ArmyFixtures.U1,
            List.of(new CompositionDelta("士兵", -30L), new CompositionDelta("民夫", -2L)),
            List.of());

    assertThat(loss.manpower())
        .as("增量表保序")
        .containsExactly(new CompositionDelta("士兵", -30L), new CompositionDelta("民夫", -2L));
    assertThat(loss.empty()).isFalse();
    assertThat(new CombatUnitLoss(other, List.of(), List.of()).empty())
        .as("两条表都空 ⇒ 不产生单位命令")
        .isTrue();
  }

  @Test
  void sampleRecordKeepsHexAndListOrder() {
    CombatRecord record = ArmyFixtures.sampleData().combats().get(ArmyFixtures.C1);

    assertThat(record.hex()).isEqualTo(new HexCoord(3, 4));
    assertThat(record.participants())
        .as("participants 保序")
        .containsExactly(ArmyFixtures.U2, ArmyFixtures.U1);
    assertThat(record.stages())
        .as("stages 保序（s1 → s2）")
        .extracting(CombatStage::id)
        .containsExactly(ArmyFixtures.S1, ArmyFixtures.S2);
  }
}
