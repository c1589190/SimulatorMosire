package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 会话世代命令（{@code sd.ResetDecisionMakerConversation}）：GM 把某个决策人的 LLM 会话**重置为新会话**。
 *
 * <p>★ **现实动因是现场实测**：改版前落盘的老会话在回放时会 400（历史里没有 {@code reasoning_content}，补不上）⇒ 处置只能是"换个新会话重开"。
 * 本命令把那件事**工具化**（铁律 2：它仍是一条真 revision，可回放、可回退分岔）。
 *
 * <p>★★ **它加的是"世代"而不是"会话 id"**：命令只能把计数器 +1，**说不出一个 id 来**。若载荷收一个 id，工具就能把某个决策人指向
 * **别人的**会话（冒名/串话，且没有任何东西挡得住）——计数器天然没有这个面。会话 id 仍由**世界事实纯函数派生**（见 {@code
 * DecisionAgentRunner.conversationIdOf}）。
 *
 * <p>★★ **重建必须逐字段带过来**（本任务最容易写错的一处，与 M11 那条同形）：{@code allowedTools} / {@code accessLimit} / {@code
 * decisionCadenceTicks} / {@code providerId} 一个都不能丢——只写"世代 +1"的实现在这里红。
 */
class ResetDecisionMakerConversationHandlerTest {

  private static final DecisionMakerId DM = SdFixtures.DM1;
  private static final String PROVIDER_ID = "p-rt";

  private final ResetDecisionMakerConversationHandler handler =
      new ResetDecisionMakerConversationHandler();

  @Test
  void resetsTheGenerationFromZeroToOne() {
    SdState base = stateWith(ripeMaker(0));

    DecisionMaker after = appliedState(base, handle(base, payload("dm1"))).decisionMakers().get(DM);

    assertThat(after.conversationGeneration()).as("世界事实：世代 0 → 1").isEqualTo(1L);
  }

  /** ★ **是"每重置一次 +1"，不是"置 1"**：置 1 的实现在第二次重置后**原地不动** ⇒ 决策人下一轮又接回第一段（已经作废的）会话，且没有症状。 */
  @Test
  void eachResetAddsOneRatherThanSettingOne() {
    SdState base1 = stateWith(ripeMaker(0));
    SdState afterFirst = appliedState(base1, handle(base1, payload("dm1")));

    SdState base2 = stateWith(ripeMaker(1));
    SdState afterSecond = appliedState(base2, handle(base2, payload("dm1")));

    assertThat(afterFirst.decisionMakers().get(DM).conversationGeneration()).isEqualTo(1L);
    assertThat(afterSecond.decisionMakers().get(DM).conversationGeneration())
        .as("世代 1 → 2（两个不同的会话，不是同一个）")
        .isEqualTo(2L);
  }

  /**
   * ★★ **只动世代，别的一个字段都不许丢**（本任务的主护栏之一）。
   *
   * <p>判别力：重建时漏带任意一个字段（最常见的写法是"只 new 出 id + 世代"）⇒ 本用例逐条红。它与 M11 那条 {@code
   * SetDecisionMakerAccessHandler} 丢 {@code providerId} 是**同一族**，只是换了个命令。
   */
  @Test
  void keepsEveryOtherFieldByteIdentical() {
    DecisionMaker before = ripeMaker(0);
    SdState base = stateWith(before);

    DecisionMaker after = appliedState(base, handle(base, payload("dm1"))).decisionMakers().get(DM);

    assertThat(after.id()).as("id").isEqualTo(before.id());
    assertThat(after.affiliation()).as("归属").isEqualTo(before.affiliation());
    assertThat(after.allowedTools()).as("窄工具白名单").isEqualTo(before.allowedTools());
    assertThat(after.accessLimit()).as("accessLimit（GM 配的那一层）").isEqualTo(before.accessLimit());
    assertThat(after.decisionCadenceTicks()).as("决策周期").isEqualTo(before.decisionCadenceTicks());
    assertThat(after.providerId())
        .as("★ LLM provider 绑定（漏了它 = 重置会话顺手把 provider 解绑，与 M11 变异靶子 m2 同形）")
        .contains(PROVIDER_ID);
  }

  /**
   * ★★ **重置只改 {@code decisionMakers} 一个组件**：变更集的其余九个组件必须逐条 {@code Unchanged}。
   *
   * <p>判别力：把状态"重建"成 {@code SdState.empty().withDecisionMakers(...)}
   * 之类，本用例红——那种写法**不会报错**，只会静默清空别的组件 （本仓「重建状态的代码一律用 {@code withX}」那条通则的由来）。
   */
  @Test
  void theChangeSetTouchesOnlyTheDecisionMakerComponent() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), payload("dm1"));

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(cs.decisionMakers().changed()).as("被重置的那个人进了变更集").isTrue();
    assertThat(cs.nations().changed()).as("nations 必须 Unchanged").isFalse();
    assertThat(cs.armies().changed()).as("armies 必须 Unchanged").isFalse();
    assertThat(cs.combats().changed()).as("combats 必须 Unchanged").isFalse();
    assertThat(cs.combatStates().changed()).as("combatStates 必须 Unchanged").isFalse();
    assertThat(cs.directives().changed()).as("directives 必须 Unchanged").isFalse();
    assertThat(cs.effects().changed()).as("effects 必须 Unchanged").isFalse();
    assertThat(cs.verdicts().changed()).as("verdicts 必须 Unchanged").isFalse();
    assertThat(cs.lossRecords().changed()).as("lossRecords 必须 Unchanged").isFalse();
    assertThat(cs.info().changed()).as("info 必须 Unchanged").isFalse();
  }

  /** 决策人不存在 ⇒ 拒（不许"重置一个不存在的会话"静默成功）。 */
  @Test
  void rejectsAnUnknownDecisionMaker() {
    SdState base = stateWith(ripeMaker(0));

    assertThat(rejected(handle(base, payload("dm-nope")))).contains("决策人不存在").contains("dm-nope");
  }

  /** 载荷缺 {@code decisionMakerId} ⇒ 拒（不许默认成"某个决策人"）。 */
  @Test
  void rejectsAMissingOrBlankDecisionMakerId() {
    SdState base = stateWith(ripeMaker(0));

    assertThat(rejected(handle(base, "{}")))
        .as("缺字段 ⇒ **载荷层**点名 decisionMakerId")
        .contains("decisionMakerId");
    assertThat(rejected(handle(base, "{\"decisionMakerId\":\"  \"}")))
        .as("空白 ⇒ **域层**的 DecisionMakerId 不变式拒（消息点名它自己；与既有各 handler 同一形态，不在此改口径）")
        .contains("不得为空白");
    assertThat(rejected(handle(base, "not-json"))).as("非 JSON 载荷也要**拒**（不是抛出去）").isNotEmpty();
  }

  /**
   * 命令名**逐字**钉死：它同时出现在 app 侧的窄工具 {@code NAME} 与 catalog 提示里，且必须是**字面量**——app 侧两条派生式同源判据按源码字面量扫描。
   */
  @Test
  void commandTypeIsTheSpecifiedLiteral() {
    assertThat(handler.type()).isEqualTo("sd.ResetDecisionMakerConversation");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 一个**四个可选字段全非空**的决策人：只有这样"重建丢字段"才看得见（全缺省的夹具对丢字段没有判别力）。 */
  private static DecisionMaker ripeMaker(long generation) {
    return new DecisionMaker(
        DM,
        new Affiliation.Nation(SdFixtures.N1),
        Set.of("sd.IssueDirective", "sd.SubmitVerdict"),
        AccessLimit.empty(),
        3,
        Optional.of(PROVIDER_ID),
        generation);
  }

  private static SdState stateWith(DecisionMaker maker) {
    return SdFixtures.empty().withDecisionMakers(Map.of(DM, maker));
  }

  private static String payload(String dm) {
    return "{\"decisionMakerId\":\"" + dm + "\"}";
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdState appliedState(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }
}
