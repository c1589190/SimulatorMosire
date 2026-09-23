package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T11C 的 handler 级判据：{@code sd.RunDecision} 记"GM 让某决策人的 agent 跑一轮"这一**触发事实**、**不写 {@code
 * Directive}**（不占 R4 名额）、拒绝未知决策人。
 *
 * <p>★ **为什么"不写 Directive"仍是判据**（与 {@code sd.StartDecision} 同族）：R4 硬不变量是「同一 {@code
 * (decisionMakerId, tick)} 至多一条 {@code Directive}」；触发命令若写一条（哪怕 {@code PLANNED}），同一 tick 其后的 {@code
 * sd.IssueDirective}（决策人**自己**出令，正是本命令要促成的事）就会被 R4 拒——那就等于"触发一轮决策、然后把这一轮的产出堵死"。
 *
 * <p>★ **与 {@code StartDecisionHandler} 的形制一致性由用例钉住**（{@link
 * #sharesTheAddressAndValueFormatWithStartDecision}）：两个触发命令写在**同一个地址空间** {@code
 * sd:decision.<dmId>}、只以 key 区分（{@code start} / {@code run}）——这是两处各自独立实现的代码，靠用例防止静默漂移。
 */
class RunDecisionHandlerTest {

  private static final RunDecisionHandler HANDLER = new RunDecisionHandler();

  @Test
  void recordsTheRunTriggerInTheInfoLayerAndWritesNoDirective() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));

    assertThat(after.info().get("sd:decision.dm1"))
        .as("触发记录落进 sd:decision.<dmId> 的 INFO 覆盖层")
        .containsExactly(
            new SdInfoEntry(
                new SdInfoId("sd:decision.dm1#0"),
                0L,
                Set.of(SdFixtures.DM1),
                RunDecisionHandler.RUN_INFO_KEY,
                "0",
                Optional.empty(),
                new RevisionId(1),
                Optional.empty()));
    assertThat(after.directives()).as("★ 不写 Directive ⇒ R4 名额未被占（决策人自己照常出令）").isEmpty();
  }

  /** 每调一次记一条（append 语义）：同一决策人连跑两轮 = 两条事实。 */
  @Test
  void appendsOneEntryPerCall() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState first = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));
    SdState second = applied(first, handle(first, "{\"decisionMakerId\":\"dm1\"}"));
    assertThat(second.info().get("sd:decision.dm1")).hasSize(2);
  }

  @Test
  void rejectsUnknownDecisionMaker() {
    HandlerOutcome outcome =
        HANDLER.handle(SdWorlds.world(SdState.empty()), "{\"decisionMakerId\":\"dm-nope\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("dm-nope");
  }

  @Test
  void rejectsAMalformedPayload() {
    SdState base = withDecisionMaker(SdState.empty());
    assertThat(handle(base, "{}")).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(base, "not json")).isInstanceOf(HandlerOutcome.Rejected.class);
  }

  @Test
  void doesNotConsumeTheR4DirectiveSlot() {
    // 该 tick 已有一条 Directive（R4 的既有事实）——触发命令**照常成立**且**不改动 directives**。
    SdState base =
        withDecisionMaker(SdState.empty())
            .withDirectives(
                Map.of(SdFixtures.D1, SdFixtures.directive(SdFixtures.D1, SdFixtures.DM1, 0)));
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));

    assertThat(after.directives())
        .as("触发命令不碰 directives（R4 名额不由它占用）")
        .containsOnlyKeys(SdFixtures.D1);
    assertThat(after.info().get("sd:decision.dm1")).hasSize(1);
  }

  /** ★ 判据⑤（N5 的"两处都有 ⇒ 三条判据各自独立"的多条目那一半已在上面的 `hasSize(2)` 覆盖）。 */
  @Test
  void doesNotMutateTheDecisionMakersThemselves() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));
    assertThat(after.decisionMakers())
        .as("触发是**感知层事实**，不改决策人本身（改它是 sd.SetDecisionMakerAccess / SetDecisionMakerProvider 的事）")
        .isEqualTo(base.decisionMakers());
  }

  /**
   * ★★ **跨 handler 的形制一致性**（两个触发命令写同一个地址空间、只以 key 区分）：本用例**同时**跑两个 handler， 断言两条记录落在**同一地址**、**同一
   * {@code at}（**写入时所依据的基态** revision——写路径取当时 {@code state.meta().ref().revision()}，**不是**"该条目首次出现的 revision"）**、**同一 {@code value} 形制（本命令所在 tick 的字符串）**且 **key 逐字不同**。
   *
   * <p>★ **为什么需要它**：两处是**各自独立实现**的（没有共用助手：改 {@code StartDecisionHandler} 会让 T10 期的变异证据作废），
   * 故"形制一致"这件事**必须有判据**——否则某一处改成记别的值时，两份事实在 AAR 里会长得不一样，而**没有任何症状**。
   */
  @Test
  void sharesTheAddressAndValueFormatWithStartDecision() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));
    SdState afterStart =
        applied(
            base,
            new StartDecisionHandler()
                .handle(SdWorlds.world(base), "{\"decisionMakerId\":\"dm1\"}"));

    List<SdInfoEntry> runs = after.info().get("sd:decision.dm1");
    List<SdInfoEntry> starts = afterStart.info().get("sd:decision.dm1");
    assertThat(runs).hasSize(1);
    assertThat(starts).as("同一地址空间（sd:decision.<dmId>）").hasSize(1);
    assertThat(runs.get(0).key())
        .as("★ 两条记录的 key 必须**逐字不同**（run / start）——混同了 AAR 里就分不出「触发跑一轮」与「开始一次判决」")
        .isNotEqualTo(starts.get(0).key());
    assertThat(runs.get(0).key()).as("key 逐字：run / start（两个触发命令各记各的）").isEqualTo("run");
    assertThat(starts.get(0).key()).isEqualTo("start");
    assertThat(runs.get(0).value())
        .as("value 形制同源：本命令所在 tick 的**字符串**（数值跨 JSON 往返会 Long↔Integer 漂移，故取串）")
        .isEqualTo(starts.get(0).value())
        .isEqualTo("0");
    assertThat(runs.get(0).at()).isEqualTo(starts.get(0).at());
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private static HandlerOutcome handle(SdState base, String payloadJson) {
    return HANDLER.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }
}
