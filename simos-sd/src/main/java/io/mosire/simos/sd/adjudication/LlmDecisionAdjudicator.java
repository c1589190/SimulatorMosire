package io.mosire.simos.sd.adjudication;

import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import java.util.Objects;

/**
 * 基于 LLM 的裁决器实现（spec §八.5，N10/N13）：模块内**边界内聚**的那一半。
 *
 * <p>★ 它把 {@link LlmClient} 的任意异常与非法输出**折成** {@link Judgement.Failed}/{@link
 * Judgement.Abstained}，绝不让异常逃逸（N13：该断点本 tick 无判决，但 tick 继续）。
 *
 * <p>★ schema 校验在**模块内**（{@link AdjudicationSchemas}）；key / 重试 / 超时在 app 的 `LlmClient` 实现里。
 */
public final class LlmDecisionAdjudicator implements DecisionAdjudicator {

  private final LlmClient client;

  public LlmDecisionAdjudicator(LlmClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public String name() {
    return "llm";
  }

  @Override
  public Judgement adjudicate(AdjudicationRequest request) {
    Objects.requireNonNull(request, "request");
    AdjudicationBreakpoint breakpoint = AdjudicationBreakpoint.parse(request.breakpoint());
    String raw;
    try {
      raw =
          client.complete(
              new LlmRequest(breakpoint, systemPrompt(breakpoint), request.redactedBriefJson()));
    } catch (RuntimeException e) {
      return new Judgement.Failed(
          "LLM 调用失败（降级）：" + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    try {
      if (AdjudicationSchemas.isAbstention(raw)) {
        return new Judgement.Abstained(AdjudicationSchemas.abstentionReason(raw));
      }
      AdjudicationSchemas.validate(breakpoint, raw);
      return new Judgement.Accepted(raw);
    } catch (IllegalArgumentException e) {
      return new Judgement.Failed("LLM 输出非法（降级）：" + e.getMessage());
    }
  }

  private static String systemPrompt(AdjudicationBreakpoint breakpoint) {
    return "你是 simos 的裁决器，断点 " + breakpoint.value() + "。只按给定 schema 在合法选项里选择并写理由，不发明新规则。";
  }
}
