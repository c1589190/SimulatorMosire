package io.mosire.simos.ga.adjudication;

import io.mosire.simos.ga.llm.SystemPromptPolicy;
import io.mosire.simos.ga.model.AdjudicationBreakpoint;

/**
 * 交给 LLM 客户端的一次请求（spec §八.5）：断点 + 提示词 + 已脱敏简报 + 期望输出 schema。
 *
 * <p>★ **本 record 不含 key / 超时 / 重试语气**——那些是 app 侧 {@link LlmClient} 实现的事（N10 的"执行上浮"）。
 *
 * <p>★★ **2026-10-17 单空格 system 硬约束**（用户原话见 {@link SystemPromptPolicy}）：{@code systemPrompt}
 * 的**唯一合法值**是 {@link SystemPromptPolicy#SINGLE_SPACE}；本 record 是裁决请求的**边界**，任何非单空格（null / 空串 / 多空格
 * / 换行 / 长文本）都在这里 **响亮失败**，拒绝消息不回显内容。判决指令本身走 {@code userPrompt}（见 {@code LlmDecisionAdjudicator}）。
 */
public record LlmRequest(
    AdjudicationBreakpoint breakpoint, String systemPrompt, String userPrompt) {

  public LlmRequest {
    if (breakpoint == null) {
      throw new IllegalArgumentException("breakpoint 不得为 null");
    }
    systemPrompt = SystemPromptPolicy.requireSingleSpace(systemPrompt, "裁决请求");
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt 不得为空白");
    }
  }
}
