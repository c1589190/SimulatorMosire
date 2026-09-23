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
 * T10 的 handler 级判据：{@code sd.StartDecision} 记"发起"事实、**不写 {@code Directive}**（不占 R4 名额）、拒绝未知决策人。
 *
 * <p>★ **为什么"不写 Directive"是判据而不是实现细节**：R4 硬不变量是「同一 {@code (decisionMakerId, tick)} 至多一条 {@code
 * Directive}」；本命令若写一条（哪怕 {@code PLANNED}），同一 tick 其后的 {@code sd.IssueDirective}（决策人出令）就会被 R4 拒。
 * {@link #doesNotConsumeTheR4DirectiveSlot} 把这条钉死。
 */
class StartDecisionHandlerTest {

  private static final StartDecisionHandler HANDLER = new StartDecisionHandler();

  @Test
  void recordsStartInTheInfoLayerAndWritesNoDirective() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));

    assertThat(after.info().get("sd:decision.dm1"))
        .as("发起记录落进 sd:decision.<dmId> 的 INFO 覆盖层")
        .containsExactly(
            new SdInfoEntry(
                new SdInfoId("sd:decision.dm1#0"),
                0L,
                Set.of(SdFixtures.DM1),
                Set.of(),
                "start",
                "0",
                Optional.empty(),
                new RevisionId(1),
                Optional.empty(),
                Optional.empty()));
    assertThat(after.directives()).as("★ 不写 Directive ⇒ R4 名额未被占").isEmpty();
  }

  /** ★ 决策结果三件套（第 3 波第 1 步）：id 按 (canonical 地址, 序号) 合成、tick = 世界 tick、tags 挂发起人。 */
  @Test
  void writesTheDecisionResultTriple() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));

    SdInfoEntry entry = after.info().get("sd:decision.dm1").get(0);
    assertThat(entry.id())
        .as("id = canonical 地址 + 追加序号（格式由这里钉住）")
        .isEqualTo(new SdInfoId("sd:decision.dm1#0"));
    assertThat(entry.tick()).isZero();
    assertThat(entry.tags())
        .as("标签挂**发起人**（DecisionMakerId，不是自由字符串）")
        .containsExactly(SdFixtures.DM1);
  }

  @Test
  void recordsTheOptionalNote() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\",\"note\":\"用户发起\"}"));
    List<SdInfoEntry> entries = after.info().get("sd:decision.dm1");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).value()).isEqualTo("0");
    assertThat(entries.get(0).note()).contains("用户发起");
  }

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
  void doesNotConsumeTheR4DirectiveSlot() {
    // 该 tick 已有一条 Directive（R4 的既有事实）——StartDecision **照常成立**且**不改动 directives**。
    SdState base =
        withDecisionMaker(SdState.empty())
            .withDirectives(
                Map.of(SdFixtures.D1, SdFixtures.directive(SdFixtures.D1, SdFixtures.DM1, 0)));
    SdState after = applied(base, handle(base, "{\"decisionMakerId\":\"dm1\"}"));

    assertThat(after.directives())
        .as("StartDecision 不碰 directives（R4 名额不由它占用）")
        .containsOnlyKeys(SdFixtures.D1);
    assertThat(after.info().get("sd:decision.dm1")).hasSize(1);
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
