package io.mosire.simos.sd.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.util.address.Address;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** D2 判据：三态结构 / 四分量非空 / 断点合并 / schema 约束 / **N13 降级（不逃逸、不卡死）**。 */
class AdjudicationTest {

  private static final String VALID_D1 =
      "{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],"
          + "\"rationaleText\":\"强攻\"}";

  @Test
  void judgementHasThreeStatesAndRejectsBlankReasons() {
    assertThat(new Judgement.Accepted("{}").payloadJson()).isEqualTo("{}");
    assertThat(new Judgement.Abstained("无足够信息").reason()).isEqualTo("无足够信息");
    assertThat(new Judgement.Failed("超时").reason()).isEqualTo("超时");
    assertThatThrownBy(() -> new Judgement.Abstained(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Judgement.Failed("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void adjudicationRequestRejectsAnyBlankComponent() {
    assertThatThrownBy(() -> new AdjudicationRequest("D1", "{}", "{}", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandWhitelistJson");
    assertThatThrownBy(() -> new AdjudicationRequest("", "{}", "{}", "[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("breakpoint");
    AdjudicationRequest request = new AdjudicationRequest("D1", "{}", "{}", "[]");
    assertThat(request.breakpoint()).isEqualTo("D1");
  }

  @Test
  void d1AndD3ShareOneCallWhileOthersAreSeparate() {
    assertThat(Breakpoints.callCount()).as("D1+D3 合并 ⇒ 7 组").isEqualTo(7);
    assertThat(Breakpoints.callGroups()).hasSize(7);
    assertThat(Breakpoints.sharesCall(Breakpoints.D1, Breakpoints.D3)).isTrue();
    assertThat(Breakpoints.sharesCall(Breakpoints.D1, Breakpoints.D2)).isFalse();
  }

  @Test
  void verdictSurfaceIsCombatOnlyAndLimitedToD1D3D6() {
    Address combat = Address.parse("sd:combat.c1");
    Address decisionMaker = Address.parse("sd:decision-maker.dm1");
    Address map = Address.parse("map:Map1");

    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, combat)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D6, combat)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D2, combat))
        .as("D2 出令走 sd.IssueDirective，不走判决窄工具")
        .isFalse();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, decisionMaker)).isFalse();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, map)).isFalse();
  }

  @Test
  void schemaValidatesRequiredFieldsPerBreakpoint() {
    AdjudicationSchemas.validate(Breakpoints.D1, VALID_D1);

    assertThatThrownBy(() -> AdjudicationSchemas.validate(Breakpoints.D1, "{\"stageId\":\"s1\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rationaleText");
    assertThatThrownBy(
            () ->
                AdjudicationSchemas.validate(
                    Breakpoints.D4, "{\"action\":\"FLY\",\"unitIds\":[],\"rationaleText\":\"x\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    assertThatThrownBy(
            () ->
                AdjudicationSchemas.validate(
                    new AdjudicationBreakpoint("D9"), "{\"rationaleText\":\"x\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未登记的断点");
  }

  @Test
  void llmTimeoutDegradesToFailedWithoutEscaping() {
    LlmClient timeout =
        request -> {
          throw new IllegalStateException("timeout");
        };
    DecisionAdjudicator adjudicator = new LlmDecisionAdjudicator(timeout);

    Judgement judgement = adjudicator.adjudicate(requestFor("D1"));

    assertThat(judgement).isInstanceOf(Judgement.Failed.class);
    assertThat(((Judgement.Failed) judgement).reason()).contains("timeout");
  }

  @Test
  void illegalJsonDegradesToFailedWithoutEscaping() {
    DecisionAdjudicator adjudicator = new LlmDecisionAdjudicator(request -> "not-json");

    assertThat(adjudicator.adjudicate(requestFor("D1"))).isInstanceOf(Judgement.Failed.class);
  }

  @Test
  void schemaViolationDegradesToFailed() {
    DecisionAdjudicator adjudicator = new LlmDecisionAdjudicator(request -> "{\"stageId\":\"s1\"}");

    assertThat(adjudicator.adjudicate(requestFor("D1"))).isInstanceOf(Judgement.Failed.class);
  }

  @Test
  void explicitAbstentionIsAbstainedNotFailed() {
    DecisionAdjudicator adjudicator =
        new LlmDecisionAdjudicator(request -> "{\"abstain\":true,\"reason\":\"看不清\"}");

    Judgement judgement = adjudicator.adjudicate(requestFor("D1"));

    assertThat(judgement).isInstanceOf(Judgement.Abstained.class);
    assertThat(((Judgement.Abstained) judgement).reason()).isEqualTo("看不清");
  }

  @Test
  void validOutputIsAcceptedAndClientIsCalledExactlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    DecisionAdjudicator adjudicator =
        new LlmDecisionAdjudicator(
            request -> {
              calls.incrementAndGet();
              return VALID_D1;
            });

    Judgement judgement = adjudicator.adjudicate(requestFor("D1"));

    assertThat(judgement).isInstanceOf(Judgement.Accepted.class);
    assertThat(((Judgement.Accepted) judgement).payloadJson()).isEqualTo(VALID_D1);
    assertThat(calls.get()).isEqualTo(1);
  }

  private static AdjudicationRequest requestFor(String breakpoint) {
    return new AdjudicationRequest(
        breakpoint, "{\"brief\":\"已脱敏\"}", AdjudicationSchemas.schemaJson(Breakpoints.D1), "[]");
  }
}
