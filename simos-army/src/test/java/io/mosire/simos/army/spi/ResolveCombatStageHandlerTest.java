package io.mosire.simos.army.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatResolution;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code army.ResolveCombatStage} 命令边界（T2a / D-009 补裁 + D-010）：同阶段不可重复判定；显式 outcomeId 不投骰；只给 seed
 * 投骰落 seed；都不给 ⇒ 派生 seed 投骰；两者都给 ⇒ 按 seed 复核一致接受 / 不一致拒。
 */
class ResolveCombatStageHandlerTest {

  private static final ResolveCombatStageHandler HANDLER = new ResolveCombatStageHandler();

  private static final long RECORD_TICK = 12L;

  @Test
  void typeIsArmyResolveCombatStageAndItIsGmOnly() {
    assertThat(HANDLER.type()).isEqualTo("army.ResolveCombatStage");
    assertThat(HANDLER).isInstanceOf(GmOnlyCommand.class);
  }

  @Test
  void anExplicitOutcomeIsAcceptedWithoutRolling() {
    CombatStage resolved =
        resolvedStage(baseData(), "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\"o1\"}");

    assertThat(resolved.selectedOutcomeId()).as("显式结局直接写入").contains(ArmyFixtures.O1);
    assertThat(resolved.rollSeed()).as("没投骰 ⇒ rollSeed 空").isEmpty();
  }

  @Test
  void anExplicitSeedRollsAndIsRecorded() {
    ArmyData base = baseData();
    CombatStage first =
        resolvedStage(base, "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"seed\":42}");
    CombatStage second =
        resolvedStage(base, "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"seed\":42}");

    assertThat(first.rollSeed()).as("投骰路径必须把 seed 落进记录").contains(42L);
    assertThat(first.selectedOutcomeId()).isPresent();
    assertThat(second).as("同 base 同 seed ⇒ 逐字段同结果（无墙钟/系统随机）").isEqualTo(first);
  }

  @Test
  void whenNeitherIsGivenASeedIsDerivedFromTheRecordStageTickAndTable() {
    ArmyData base = baseData();
    List<CombatOutcome> outcomes = base.combats().get(ArmyFixtures.C1).stages().get(0).outcomes();
    long derived =
        CombatResolution.deriveSeed(ArmyFixtures.C1, ArmyFixtures.S1, RECORD_TICK, outcomes);

    CombatStage resolved = resolvedStage(base, "{\"combatId\":\"c-1\",\"stageId\":\"s1\"}");

    assertThat(resolved.rollSeed()).as("派生 seed 在场").contains(derived);
    assertThat(resolved.selectedOutcomeId())
        .as("命中项 = 按该派生 seed 投骰的结果")
        .contains(CombatResolution.roll(derived, outcomes).id());
    assertThat(resolvedStage(base, "{\"combatId\":\"c-1\",\"stageId\":\"s1\"}"))
        .as("同基态重放 ⇒ 同结果（纯函数）")
        .isEqualTo(resolved);
  }

  @Test
  void anExplicitOutcomeAndSeedAreCrossChecked() {
    ArmyData base = baseData();
    List<CombatOutcome> outcomes = base.combats().get(ArmyFixtures.C1).stages().get(0).outcomes();
    long seed = 7L;
    CombatOutcome rolled = CombatResolution.roll(seed, outcomes);
    CombatOutcomeId other = rolled.id().equals(ArmyFixtures.O1) ? ArmyFixtures.O2 : ArmyFixtures.O1;

    CombatStage accepted =
        resolvedStage(
            base,
            "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\""
                + rolled.id().value()
                + "\",\"seed\":"
                + seed
                + "}");
    assertThat(accepted.selectedOutcomeId()).contains(rolled.id());
    assertThat(accepted.rollSeed()).contains(seed);

    HandlerOutcome rejected =
        handle(
            base,
            "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\""
                + other.value()
                + "\",\"seed\":"
                + seed
                + "}");
    assertThat(rejected).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) rejected).reason())
        .as("★ 记了一个与该 seed 不一致的结局 ⇒ 具名拒")
        .contains("不一致")
        .contains(rolled.id().value())
        .contains(other.value());
  }

  @Test
  void rejectsResolvingTheSameStageTwice() {
    ArmyData first =
        applied(baseData(), "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\"o1\"}");
    HandlerOutcome second = handle(first, "{\"combatId\":\"c-1\",\"stageId\":\"s1\"}");

    assertThat(second).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) second).reason())
        .as("同一阶段判定只做一次")
        .contains("已判定")
        .contains("s1");
  }

  @Test
  void rejectsUnknownRecordsStagesAndOutcomes() {
    HandlerOutcome unknownRecord =
        handle(ArmyData.empty(), "{\"combatId\":\"nope\",\"stageId\":\"s1\"}");
    HandlerOutcome unknownStage = handle(baseData(), "{\"combatId\":\"c-1\",\"stageId\":\"nope\"}");
    HandlerOutcome unknownOutcome =
        handle(baseData(), "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\"nope\"}");

    assertThat(((HandlerOutcome.Rejected) unknownRecord).reason()).contains("交战记录不存在");
    assertThat(((HandlerOutcome.Rejected) unknownStage).reason()).contains("阶段不存在");
    assertThat(((HandlerOutcome.Rejected) unknownOutcome).reason()).contains("不在该阶段的概率表");
  }

  @Test
  void rejectsRollingWithoutAnOutcomeTable() {
    ArmyData noOutcomes =
        ArmyFixtures.data(
            new CombatRecord(
                ArmyFixtures.C1,
                "野战",
                RECORD_TICK,
                ArmyFixtures.HEX,
                List.of(ArmyFixtures.U1, ArmyFixtures.U2),
                "记录级过程",
                List.of(
                    ArmyFixtures.stage(
                        ArmyFixtures.S1,
                        "接触",
                        List.of(ArmyFixtures.U1, ArmyFixtures.U2),
                        "阶段过程",
                        List.of()))));

    HandlerOutcome rolled = handle(noOutcomes, "{\"combatId\":\"c-1\",\"stageId\":\"s1\"}");
    HandlerOutcome explicit =
        handle(noOutcomes, "{\"combatId\":\"c-1\",\"stageId\":\"s1\",\"outcomeId\":\"o1\"}");

    assertThat(rolled).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) rolled).reason()).contains("无法投骰");
    assertThat(explicit).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) explicit).reason()).contains("不在该阶段的概率表");
  }

  @Test
  void malformedPayloadsAreRejectedInsteadOfThrown() {
    assertThat(handle(baseData(), "not json")).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(baseData(), "{\"stageId\":\"s1\"}"))
        .as("缺 combatId ⇒ 拒绝")
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static ArmyData baseData() {
    return ArmyFixtures.data(ArmyFixtures.duelRecord(ArmyFixtures.C1, RECORD_TICK));
  }

  private static HandlerOutcome handle(ArmyData base, String payload) {
    return HANDLER.handle(ArmyFixtures.world(base, RECORD_TICK), payload);
  }

  private static ArmyData applied(ArmyData base, String payload) {
    HandlerOutcome outcome = handle(base, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    ArmyChangeSet changeSet = (ArmyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return ArmyChangeSet.apply(changeSet, base);
  }

  /** 施加命令后取回唯一阶段（本类所有载荷都打在 {@code s1} 上）。 */
  private static CombatStage resolvedStage(ArmyData base, String payload) {
    return applied(base, payload).combats().get(ArmyFixtures.C1).stages().get(0);
  }
}
