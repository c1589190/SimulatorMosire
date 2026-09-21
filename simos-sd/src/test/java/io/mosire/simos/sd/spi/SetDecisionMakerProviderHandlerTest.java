package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M11 判据 C7 的模块侧：绑定写进 DecisionMaker（可回放的数据）；坏载荷 / dm 不存在 ⇒ 拒绝。 */
class SetDecisionMakerProviderHandlerTest {

  private final SetDecisionMakerProviderHandler handler = new SetDecisionMakerProviderHandler();

  @Test
  void writesProviderBindingOntoTheDecisionMaker() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-a\"}");

    SdState after = applied(base, outcome);
    DecisionMaker maker = after.decisionMakers().get(SdFixtures.DM1);
    assertThat(maker.providerId()).contains("p-a");
    assertThat(maker.allowedTools())
        .as("绑定不动其他字段")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).allowedTools());
    assertThat(maker.viewScope())
        .as("绑定不动 viewScope")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).viewScope());
  }

  @Test
  void reBindingOverwritesTheProviderId() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState once =
        applied(base, handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-a\"}"));
    SdState twice =
        applied(once, handle(once, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-b\"}"));
    assertThat(twice.decisionMakers().get(SdFixtures.DM1).providerId()).contains("p-b");
  }

  @Test
  void rejectsUnknownDecisionMaker() {
    HandlerOutcome outcome =
        handle(SdState.empty(), "{\"decisionMakerId\":\"ghost\",\"providerId\":\"p\"}");
    assertThat(rejected(outcome)).contains("决策人不存在").contains("ghost");
  }

  @Test
  void rejectsMissingProviderId() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, "{\"decisionMakerId\":\"dm1\"}");
    assertThat(rejected(outcome)).contains("providerId");
  }

  @Test
  void rejectsBlankProviderId() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"   \"}");
    assertThat(rejected(outcome)).contains("providerId").contains("空白");
  }

  @Test
  void settingViewScopeKeepsTheProviderBinding() {
    // ★ 变异靶子 m4：SetViewScopeHandler 重建 DecisionMaker 时必须带回 providerId，否则配权静默丢绑定。
    SdState base = withDecisionMaker(SdState.empty());
    SdState bound =
        applied(base, handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-keep\"}"));
    HandlerOutcome scope =
        new SetViewScopeHandler()
            .handle(
                SdWorlds.world(bound),
                "{\"decisionMakerId\":\"dm1\",\"viewScope\":{\"visibleRegions\":[\"r1\"]}}");
    SdState after = applied(bound, scope);
    assertThat(after.decisionMakers().get(SdFixtures.DM1).providerId())
        .as("配权不得丢 providerId")
        .contains("p-keep");
    assertThat(after.decisionMakers().get(SdFixtures.DM1).viewScope().visibleRegions())
        .containsExactly(new io.mosire.simos.map.region.RegionId("r1"));
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }
}
