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

/** M11 判据：绑定写进 {@link DecisionMaker}（可回放的数据，铁律 2）；坏载荷 / dm 不存在 ⇒ 拒绝；**sd 不校验 provider 是否存在**。 */
class SetDecisionMakerProviderHandlerTest {

  private final SetDecisionMakerProviderHandler handler = new SetDecisionMakerProviderHandler();

  @Test
  void writesProviderBindingOntoTheDecisionMakerAsANonEmptyChangeSet() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-a\"}");

    SdChangeSet changeSet = appliedChangeSet(outcome);
    assertThat(changeSet.isEmpty()).as("绑定必须真的进变更集（m1 变异靶子）").isFalse();

    SdState after = SdChangeSet.apply(changeSet, base);
    DecisionMaker maker = after.decisionMakers().get(SdFixtures.DM1);
    assertThat(maker.providerId()).contains("p-a");
    assertThat(maker.allowedTools())
        .as("绑定不动其他字段")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).allowedTools());
    assertThat(maker.accessLimit())
        .as("绑定不动 accessLimit")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).accessLimit());
    assertThat(maker.decisionCadenceTicks()).isEqualTo(1);
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

  /**
   * ★★ **回放/使用期语义的 sd 侧判据**：sd 不认识 provider 注册表（铁律 3）⇒ 绑一个**不存在**的 id 是**合法**的，必须被接受。
   *
   * <p>存在性由**使用时刻**的 app 层解析 fail-closed 强制。若有人"顺手"在 sd 里加了存在性校验，本用例会红。
   */
  @Test
  void acceptsAProviderIdThatDoesNotExistAnywhereBecauseExistenceIsNotSdsConcern() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome =
        handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-not-registered\"}");

    SdState after = applied(base, outcome);
    assertThat(after.decisionMakers().get(SdFixtures.DM1).providerId())
        .as("sd 只管世界事实，存在性归使用期解析")
        .contains("p-not-registered");
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
  void settingAccessAfterBindingKeepsTheProvider() {
    // ★ 变异靶子 m2：SetDecisionMakerAccessHandler 重建 DecisionMaker 时必须带回 providerId，否则配权静默丢绑定。
    SdState base = withDecisionMaker(SdState.empty());
    SdState bound =
        applied(base, handle(base, "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-keep\"}"));
    HandlerOutcome scope =
        new SetDecisionMakerAccessHandler()
            .handle(
                SdWorlds.world(bound),
                "{\"decisionMakerId\":\"dm1\",\"accessLimit\":{\"map\":[\"Map1/region/r1\"]}}");
    SdState after = applied(bound, scope);
    assertThat(after.decisionMakers().get(SdFixtures.DM1).providerId())
        .as("配权不得丢 providerId")
        .contains("p-keep");
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdChangeSet appliedChangeSet(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    return SdChangeSet.apply(appliedChangeSet(outcome), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }
}
