package io.mosire.simos.sd.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.LlmException;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.util.address.Address;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  void verdictSurfaceHasACombatFaceForD1D3D6AndADecisionMakerFaceForD2() {
    Address combat = Address.parse("sd:combat.c1");
    Address decisionMaker = Address.parse("sd:decision-maker.dm1");
    Address map = Address.parse("map:Map1");

    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, combat)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D6, combat)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D2, combat))
        .as("D2 不裁决战斗——战斗判决归 D1/D3/D6")
        .isFalse();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, decisionMaker)).isFalse();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D1, map)).isFalse();
    // ★ 2026-09-22 用户裁定「战斗是状态、不是决策前提」：D2 对**决策人本人**裁决（决策记录）。
    assertThat(Breakpoints.producesVerdict(Breakpoints.D2)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D2, decisionMaker)).isTrue();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D2, map)).isFalse();
    assertThat(Breakpoints.producesVerdict(Breakpoints.D4)).isFalse();
    assertThat(Breakpoints.acceptsVerdictSubject(Breakpoints.D4, decisionMaker)).isFalse();
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
  void degradableLlmFailuresFoldToFailedWithoutEscaping() {
    // ★ B3：degradable() 为真的失败（TRANSPORT/TIMEOUT/RATE_LIMIT/…）⇒ 本 tick 无判决，tick 继续。
    for (LlmException.Kind kind :
        List.of(
            LlmException.Kind.TRANSPORT,
            LlmException.Kind.TIMEOUT,
            LlmException.Kind.RATE_LIMIT,
            LlmException.Kind.PROVIDER_ERROR,
            LlmException.Kind.AUTH,
            LlmException.Kind.REQUEST_REJECTED,
            LlmException.Kind.PROTOCOL,
            LlmException.Kind.NO_TEXT)) {
      assertThat(kind.degradable()).as("前提：" + kind + " 应是可降级类").isTrue();
      DecisionAdjudicator adjudicator =
          new LlmDecisionAdjudicator(
              request -> {
                throw new LlmException("upstream failed", kind);
              });
      Judgement judgement = adjudicator.adjudicate(requestFor("D1"));
      assertThat(judgement).isInstanceOf(Judgement.Failed.class);
      assertThat(((Judgement.Failed) judgement).reason()).contains(kind.name());
    }
  }

  @Test
  void nonDegradableLlmFailuresAreRethrownNotSwallowed() {
    // ★ B3（m4 的杀点）：CONFIG / CANCELLED / INTERNAL **该炸**——配置错降级 = 把故障藏起来。
    for (LlmException.Kind kind :
        List.of(
            LlmException.Kind.CONFIG, LlmException.Kind.CANCELLED, LlmException.Kind.INTERNAL)) {
      assertThat(kind.degradable()).as("前提：" + kind + " 应是不可降级类").isFalse();
      DecisionAdjudicator adjudicator =
          new LlmDecisionAdjudicator(
              request -> {
                throw new LlmException("config broken", kind);
              });
      assertThatThrownBy(() -> adjudicator.adjudicate(requestFor("D1")))
          .isInstanceOf(LlmException.class)
          .hasMessageContaining("config broken");
    }
  }

  @Test
  void nonLlmRuntimeExceptionsAreRethrown() {
    // ★ 非 AgentLib 判过类的失败（装配/编程错误）保守判"该炸"——不许悄悄降级。
    DecisionAdjudicator adjudicator =
        new LlmDecisionAdjudicator(
            request -> {
              throw new IllegalStateException("装配失败");
            });
    assertThatThrownBy(() -> adjudicator.adjudicate(requestFor("D1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配失败");
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

  @Test
  void promptCarriesTheOutputSchemaAndCommandWhitelistToTheModel() {
    AtomicReference<LlmRequest> seen = new AtomicReference<>();
    DecisionAdjudicator adjudicator =
        new LlmDecisionAdjudicator(
            request -> {
              seen.set(request);
              return VALID_D1;
            });
    AdjudicationRequest request =
        new AdjudicationRequest(
            "D1",
            "{\"brief\":\"已脱敏\"}",
            AdjudicationSchemas.schemaJson(Breakpoints.D1),
            "[\"sd.SubmitVerdict\"]");

    Judgement judgement = adjudicator.adjudicate(request);

    assertThat(judgement).isInstanceOf(Judgement.Accepted.class);
    assertThat(seen.get().userPrompt())
        .as("输出 schema 必须随提示词交给模型（否则真模型无从知道字段）")
        .contains(AdjudicationSchemas.schemaJson(Breakpoints.D1))
        .contains("sd.SubmitVerdict")
        .contains("已脱敏");
  }

  private static AdjudicationRequest requestFor(String breakpoint) {
    return new AdjudicationRequest(
        breakpoint, "{\"brief\":\"已脱敏\"}", AdjudicationSchemas.schemaJson(Breakpoints.D1), "[]");
  }
}
