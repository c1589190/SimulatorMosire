package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code sd.RecordDiplomaticEvent} 命令边界（T2a / D-005 + R6）：participants ≥2、不重复、空串拒；显式 id 撞车拒；id 合成
 * 恰好 {@code diplomatic-event:<tick>#<序数>} 且同 tick 递增；未来 tick 拒。
 */
class RecordDiplomaticEventHandlerTest {

  private static final RecordDiplomaticEventHandler HANDLER = new RecordDiplomaticEventHandler();

  @Test
  void typeIsSdRecordDiplomaticEventAndItIsNotGmOnly() {
    assertThat(HANDLER.type()).isEqualTo("sd.RecordDiplomaticEvent");
    assertThat(HANDLER)
        .as("身份约束在决策人窄工具层（signature），命令本身非 GmOnly")
        .isNotInstanceOf(GmOnlyCommand.class);
  }

  @Test
  void synthesizesTheIdFromTickAndOrdinalAndKeepsParticipantOrder() {
    SdState next =
        applied(SdState.empty(), 7L, "{\"participants\":[\"n2\",\"n1\"],\"text\":\"多国谈判开始\"}");

    DiplomaticEvent event = onlyEvent(next);
    assertThat(event.id())
        .as("合成 id 的拼写点：diplomatic-event:<tick>#<该 tick 已有事件数>")
        .isEqualTo(new DiplomaticEventId("diplomatic-event:7#0"));
    assertThat(event.tick()).as("tick 缺省 = 世界当前 tick").isEqualTo(7L);
    assertThat(event.participants())
        .as("参与国保序（不是 Set 去重后的任意序）")
        .containsExactly(SdFixtures.N2, SdFixtures.N1);
    assertThat(event.text()).isEqualTo("多国谈判开始");
  }

  @Test
  void multipleEventsAtTheSameTickIncrementTheOrdinal() {
    SdState first =
        applied(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"第一轮\"}");
    SdState second = applied(first, 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"第二轮\"}");
    SdState third = applied(second, 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"第三轮\"}");

    assertThat(third.diplomaticEvents().keySet())
        .as("同 tick 逐条 append，序号 0/1/2")
        .containsExactly(
            new DiplomaticEventId("diplomatic-event:7#0"),
            new DiplomaticEventId("diplomatic-event:7#1"),
            new DiplomaticEventId("diplomatic-event:7#2"));
    assertThat(third.diplomaticEvents()).hasSize(3);
  }

  @Test
  void anExplicitIdIsUsedAndACollisionIsRejected() {
    SdState first =
        applied(
            SdState.empty(),
            7L,
            "{\"eventId\":\"e-1\",\"participants\":[\"n1\",\"n2\"],\"text\":\"显式 id\"}");
    assertThat(onlyEvent(first).id()).isEqualTo(new DiplomaticEventId("e-1"));

    HandlerOutcome collision =
        handle(first, 7L, "{\"eventId\":\"e-1\",\"participants\":[\"n1\",\"n2\"],\"text\":\"撞车\"}");
    assertThat(collision).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) collision).reason()).contains("已存在").contains("e-1");

    SdState synthesized =
        applied(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"合成 id\"}");
    assertThat(onlyEvent(synthesized).id())
        .isEqualTo(new DiplomaticEventId("diplomatic-event:7#0"));

    HandlerOutcome synthesizedCollision =
        handle(
            synthesized,
            7L,
            "{\"eventId\":\"diplomatic-event:7#0\",\"participants\":[\"n1\",\"n2\"],\"text\":\"恰好长成合成串\"}");
    assertThat(synthesizedCollision).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) synthesizedCollision).reason())
        .as("显式 id 恰好等于合成串 ⇒ 响亮拒（不静默换 id）")
        .contains("已存在");
  }

  @Test
  void rejectsParticipantsBelowTwoDuplicatesOrBlank() {
    HandlerOutcome single =
        handle(SdState.empty(), 7L, "{\"participants\":[\"n1\"],\"text\":\"单国\"}");
    HandlerOutcome duplicated =
        handle(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n1\"],\"text\":\"重复\"}");
    HandlerOutcome blank =
        handle(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"  \"],\"text\":\"空白元素\"}");
    HandlerOutcome notArray =
        handle(SdState.empty(), 7L, "{\"participants\":\"n1\",\"text\":\"形状错\"}");
    HandlerOutcome missing = handle(SdState.empty(), 7L, "{\"text\":\"缺 participants\"}");

    assertThat(((HandlerOutcome.Rejected) single).reason()).contains("至少 2 个");
    assertThat(((HandlerOutcome.Rejected) duplicated).reason()).contains("不得重复").contains("n1");
    assertThat(((HandlerOutcome.Rejected) blank).reason()).contains("非空白");
    assertThat(((HandlerOutcome.Rejected) notArray).reason()).contains("participants");
    assertThat(((HandlerOutcome.Rejected) missing).reason()).contains("participants");
  }

  @Test
  void tickDefaultsToTheWorldTickAndAcceptsPastButRejectsFuture() {
    SdState defaultTick =
        applied(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"缺省\"}");
    assertThat(onlyEvent(defaultTick).tick()).isEqualTo(7L);

    SdState past =
        applied(
            SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"补记\",\"tick\":6}");
    DiplomaticEvent event = onlyEvent(past);
    assertThat(event.tick()).as("过去合法（补记）").isEqualTo(6L);
    assertThat(event.id())
        .as("合成 id 用载荷 tick，不是世界 tick")
        .isEqualTo(new DiplomaticEventId("diplomatic-event:6#0"));

    HandlerOutcome future =
        handle(
            SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"未来\",\"tick\":8}");
    assertThat(future).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) future).reason())
        .contains("未来")
        .contains("8")
        .contains("7");
  }

  @Test
  void rejectsBlankText() {
    HandlerOutcome blank =
        handle(SdState.empty(), 7L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"   \"}");

    assertThat(blank).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) blank).reason()).contains("text");
  }

  /**
   * ★ 参与国**不查存在性**（handler 类注的明示口径）：事件是"当时谁参与了"的记录，D-005 只要求参与国与内容。
   *
   * <p>这条用例守的是"未来有人好心加存在性校验、结果让历史谈判记录再也写不进去"的回归。
   */
  @Test
  void participantsAreHistoricalAndAreNotCheckedAgainstNations() {
    SdState next =
        applied(SdState.empty(), 7L, "{\"participants\":[\"已灭亡国\",\"n2\"],\"text\":\"历史谈判\"}");

    assertThat(onlyEvent(next).participants()).hasSize(2);
  }

  @Test
  void changeSetTouchesOnlyDiplomaticEvents() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        handle(base, 10L, "{\"participants\":[\"n1\",\"n2\"],\"text\":\"只动事件表\"}");

    SdChangeSet changeSet = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.diplomaticEvents().changed()).as("目标组件必须进变更集").isTrue();
    assertThat(
            List.of(
                changeSet.nations().changed(),
                changeSet.armies().changed(),
                changeSet.combats().changed(),
                changeSet.combatStates().changed(),
                changeSet.decisionMakers().changed(),
                changeSet.directives().changed(),
                changeSet.effects().changed(),
                changeSet.verdicts().changed(),
                changeSet.lossRecords().changed(),
                changeSet.info().changed(),
                changeSet.diplomaticRelations().changed()))
        .as("其余 11 个组件一个都不许动")
        .containsOnly(false);
    assertThat(SdChangeSet.apply(changeSet, base).diplomaticRelations())
        .as("外交关系边逐字不变")
        .isEqualTo(base.diplomaticRelations());
  }

  @Test
  void malformedPayloadsAreRejectedInsteadOfThrown() {
    assertThat(handle(SdState.empty(), 7L, "not json")).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(SdState.empty(), 7L, "[]")).isInstanceOf(HandlerOutcome.Rejected.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static DiplomaticEvent onlyEvent(SdState state) {
    assertThat(state.diplomaticEvents()).hasSize(1);
    return state.diplomaticEvents().values().iterator().next();
  }

  private static HandlerOutcome handle(SdState base, long worldTick, String payload) {
    return HANDLER.handle(SdWorlds.world(base, worldTick), payload);
  }

  private static SdState applied(SdState base, long worldTick, String payload) {
    HandlerOutcome outcome = handle(base, worldTick, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }
}
