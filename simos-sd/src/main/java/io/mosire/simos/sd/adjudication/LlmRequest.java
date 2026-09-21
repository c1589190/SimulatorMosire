package io.mosire.simos.sd.adjudication;

import io.mosire.simos.sd.model.AdjudicationBreakpoint;

/**
 * 交给 LLM 客户端的一次请求（spec §八.5）：断点 + 提示词 + 已脱敏简报 + 期望输出 schema。
 *
 * <p>★ **本 record 不含 key / 超时 / 重试语气**——那些是 app 侧 {@link LlmClient} 实现的事（N10 的"执行上浮"）。
 */
public record LlmRequest(
    AdjudicationBreakpoint breakpoint, String systemPrompt, String userPrompt) {

  public LlmRequest {
    if (breakpoint == null) {
      throw new IllegalArgumentException("breakpoint 不得为 null");
    }
    if (systemPrompt == null) {
      throw new IllegalArgumentException("systemPrompt 不得为 null");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt 不得为空白");
    }
  }
}
