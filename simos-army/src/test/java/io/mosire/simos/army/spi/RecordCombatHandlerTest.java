package io.mosire.simos.army.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import org.junit.jupiter.api.Test;

/**
 * {@code army.RecordCombat} 命令边界（T2a / D-009 补裁 + D-012）：tick 缺省=当前、未来拒、同 id 拒、initialStage 缺省合成
 * {@code start/初始阶段}、旧 {@code losses} 字段具名拒、hex 必填。
 *
 * <p>★ 夹具是**真 {@link io.mosire.simos.util.state.SimulationState} + 只有 army 切片**，不碰数据库。
 */
class RecordCombatHandlerTest {

  private static final RecordCombatHandler HANDLER = new RecordCombatHandler();

  @Test
  void typeIsArmyRecordCombatAndItIsGmOnly() {
    assertThat(HANDLER.type()).isEqualTo("army.RecordCombat");
    assertThat(HANDLER).as("★ 裁定战果是 GM 的活：标 GmOnly 后排除出决策人令白名单").isInstanceOf(GmOnlyCommand.class);
  }

  /** 缺省 tick ⇒ 世界当前 tick；initialStage 省略 ⇒ 合成 start/初始阶段（participants/text 取记录级）。 */
  @Test
  void defaultsTickToTheWorldTickAndSynthesizesTheInitialStage() {
    ArmyData next = applied(ArmyData.empty(), 7L, payload(""));

    CombatRecord record = next.combats().get(ArmyFixtures.C1);
    assertThat(record.tick()).as("缺省 tick = 世界当前 tick").isEqualTo(7L);
    assertThat(record.stages()).hasSize(1);
    CombatStage initial = record.stages().get(0);
    assertThat(initial.id().value()).isEqualTo("start");
    assertThat(initial.name()).isEqualTo("初始阶段");
    assertThat(initial.participants())
        .as("合成阶段的参与单位 = 记录级 participants（保序）")
        .containsExactly(ArmyFixtures.U1, ArmyFixtures.U2);
    assertThat(initial.text()).as("合成阶段的文本 = 记录级 text").isEqualTo("记录级过程");
    assertThat(initial.outcomes()).as("概率表随后追加 ⇒ 合成阶段是空表").isEmpty();
    assertThat(initial.selectedOutcomeId()).isEmpty();
    assertThat(initial.rollSeed()).isEmpty();
  }

  @Test
  void acceptsAnExplicitPastTickAndRejectsTheFuture() {
    ArmyData past = applied(ArmyData.empty(), 7L, payload(",\"tick\":3"));
    assertThat(past.combats().get(ArmyFixtures.C1).tick()).as("补记过去合法").isEqualTo(3L);

    HandlerOutcome future = handle(ArmyData.empty(), 7L, payload(",\"tick\":8"));
    assertThat(future).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) future).reason())
        .as("拒因点名未来与世界 tick")
        .contains("未来")
        .contains("8")
        .contains("7");
  }

  @Test
  void rejectsADuplicateRecordIdByName() {
    ArmyData first = applied(ArmyData.empty(), 7L, payload(""));
    HandlerOutcome second = handle(first, 7L, payload(""));

    assertThat(second).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) second).reason())
        .as("记录 id 是一次性身份：拒因点名 c-1")
        .contains("已存在")
        .contains("c-1");
  }

  @Test
  void rejectsTheLegacyRecordLevelLossesField() {
    HandlerOutcome outcome = handle(ArmyData.empty(), 7L, payload(",\"losses\":{\"士兵\":-1}"));

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("D-011/R4：旧字段显式拒，不静默丢、不留双轨")
        .contains("losses")
        .contains("D-011");
  }

  @Test
  void requiresHex() {
    HandlerOutcome outcome =
        handle(
            ArmyData.empty(),
            7L,
            "{\"id\":\"c-1\",\"kind\":\"野战\",\"participants\":[\"u-1\"],\"text\":\"过程\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("hex");
  }

  /** 给了 initialStage ⇒ 用它；阶段 participants 省略 ⇒ 沿用记录级 participants（D4 的缺省口径）。 */
  @Test
  void anExplicitInitialStageMayOmitParticipantsAndDefaultsToTheRecordLevel() {
    ArmyData next =
        applied(
            ArmyData.empty(),
            7L,
            payload(
                ",\"initialStage\":{\"id\":\"s1\",\"name\":\"接触\",\"text\":\"阶段过程\","
                    + "\"outcomes\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":60}]}"));

    CombatRecord record = next.combats().get(ArmyFixtures.C1);
    assertThat(record.stages()).hasSize(1);
    CombatStage stage = record.stages().get(0);
    assertThat(stage.id()).isEqualTo(ArmyFixtures.S1);
    assertThat(stage.participants()).containsExactly(ArmyFixtures.U1, ArmyFixtures.U2);
    assertThat(stage.outcomes()).hasSize(1);
    assertThat(stage.outcomes().get(0).id()).isEqualTo(ArmyFixtures.O1);
    assertThat(stage.outcomes().get(0).weight()).isEqualTo(60L);
  }

  @Test
  void rejectsAnInitialStageCarryingADecision() {
    HandlerOutcome withOutcome =
        handle(
            ArmyData.empty(),
            7L,
            payload(
                ",\"initialStage\":{\"id\":\"s1\",\"name\":\"接触\",\"text\":\"过程\","
                    + "\"selectedOutcomeId\":\"o1\"}"));
    HandlerOutcome withSeed =
        handle(
            ArmyData.empty(),
            7L,
            payload(
                ",\"initialStage\":{\"id\":\"s1\",\"name\":\"接触\",\"text\":\"过程\",\"rollSeed\":7}"));

    assertThat(withOutcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) withOutcome).reason()).contains("selectedOutcomeId");
    assertThat(withSeed).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) withSeed).reason()).contains("rollSeed");
  }

  @Test
  void rejectsBlankKindTextAndOutcomeWeight() {
    HandlerOutcome blankKind =
        handle(ArmyData.empty(), 7L, payload("").replace("\"野战\"", "\"  \""));
    HandlerOutcome blankText = handle(ArmyData.empty(), 7L, payload("").replace("记录级过程", "  "));
    HandlerOutcome zeroWeight =
        handle(
            ArmyData.empty(),
            7L,
            payload(
                ",\"initialStage\":{\"id\":\"s1\",\"name\":\"接触\",\"text\":\"过程\","
                    + "\"outcomes\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":0}]}"));

    assertThat(((HandlerOutcome.Rejected) blankKind).reason()).contains("kind");
    assertThat(((HandlerOutcome.Rejected) blankText).reason()).contains("text");
    assertThat(((HandlerOutcome.Rejected) zeroWeight).reason()).contains("weight");
  }

  /**
   * ★ 参与者**不查存在性**（handler 类注的明示口径）：记录写的是历史，单位可能已被解散/改 id。
   *
   * <p>这条用例守的是"未来有人好心加存在性校验、结果让旧记录再也读不出来/写不进去"的回归。
   */
  @Test
  void participantsAreHistoricalAndAreNotCheckedAgainstTheUnitSlice() {
    ArmyData next =
        applied(
            ArmyData.empty(),
            7L,
            "{\"id\":\"c-hist\",\"kind\":\"野战\",\"hex\":{\"q\":0,\"r\":0},"
                + "\"participants\":[\"u-已解散\"],\"text\":\"历史记录\"}");

    assertThat(next.combats().get(new CombatRecordId("c-hist")).participants()).hasSize(1);
  }

  @Test
  void malformedPayloadsAreRejectedInsteadOfThrown() {
    assertThat(handle(ArmyData.empty(), 7L, "not json"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(ArmyData.empty(), 7L, "[1,2]")).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(ArmyData.empty(), 7L, "{\"kind\":\"野战\"}"))
        .as("缺 id ⇒ 拒绝（不抛到命令边界之外）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static String payload(String extraFields) {
    return "{\"id\":\"c-1\",\"kind\":\"野战\",\"hex\":{\"q\":3,\"r\":4},"
        + "\"participants\":[\"u-1\",\"u-2\"],\"text\":\"记录级过程\""
        + extraFields
        + "}";
  }

  private static HandlerOutcome handle(ArmyData base, long worldTick, String payload) {
    return HANDLER.handle(ArmyFixtures.world(base, worldTick), payload);
  }

  private static ArmyData applied(ArmyData base, long worldTick, String payload) {
    HandlerOutcome outcome = handle(base, worldTick, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    ArmyChangeSet changeSet = (ArmyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return ArmyChangeSet.apply(changeSet, base);
  }
}
