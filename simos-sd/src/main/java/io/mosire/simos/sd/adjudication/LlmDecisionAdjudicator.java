package io.mosire.simos.sd.adjudication;

import io.mosire.agentlib.llm.LlmException;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 基于 LLM 的裁决器实现（spec §八.5，N10/N13）：模块内**边界内聚**的那一半。
 *
 * <p>★ **N13 的判据来自 AgentLib 的异常分类**（B3）：不再 {@code catch (RuntimeException)} 一刀切。
 *
 * <ul>
 *   <li>{@link LlmException#degradable()} 为真（传输 / 超时 / 限流 / 5xx / 鉴权 / 被拒 / 协议违约 / 拿不到文本） ⇒ {@link
 *       Judgement.Failed}——**该断点本 tick 无判决，tick 继续**；
 *   <li>{@code degradable()} 为假（{@code CONFIG} / {@code CANCELLED} / {@code INTERNAL}）⇒ **原样重抛**：
 *       配置错不是供应商的问题，降级会把"配错了"变成"换个 provider 好像就行了"，把真正的故障藏起来（B3 原文）；
 *   <li>**非 {@link LlmException} 的运行时异常**（装配失败 / 密钥缺失 / 编程错误…）⇒ 同样**重抛**（该炸）—— 只有 AgentLib
 *       明确判过类的失败才允许降级。
 * </ul>
 *
 * <p>★ **schema 校验在模块内**（{@link AdjudicationSchemas}）：LLM 输出不合法是**可降级**的（模型没按契约答，换一次可能就好， 且本 tick
 * 无判决不致命）——故它是 {@link Judgement.Failed}，与"配置炸了"不同。
 *
 * <p>★ **输出契约（schema + 命令白名单）随提示词一起给模型**（取代说明，2026-09-22）：D 阶段只有假客户端， 提示词只发了简报、**没把 {@link
 * AdjudicationRequest#outputSchemaJson()} / {@link AdjudicationRequest#commandWhitelistJson()}
 * 交出去**——真模型无从知道要产出哪些字段，只能猜，于是"真 LLM 判决" 必然被 schema 判成非法。本类现在把四分量**全部**送进 user 提示词（两段都是 {@code
 * AdjudicationRequest} 本来就携带的、 给模型看的文本），不改任何判据；"字段是否齐"的最终裁决仍在 {@link
 * AdjudicationSchemas#validate}。★ **schema 缺失时 该请求不可裁决**（构造期已保证它非空），不在这里兜底编一个假 schema。
 *
 * <p>★ key / 重试 / 超时在 app 的 LLM 客户端实现里（AgentLib 侧）；本类只做"请求 → 判决"。
 */
public final class LlmDecisionAdjudicator implements DecisionAdjudicator {

  private static final Logger LOG = SdLog.decision();

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
    LOG.debug(
        "event=SD_ADJUDICATION_START adjudicator={} breakpoint={}", name(), breakpoint.value());
    String raw;
    try {
      raw =
          client.complete(
              new LlmRequest(breakpoint, systemPrompt(breakpoint), userPrompt(request)));
    } catch (LlmException e) {
      if (!e.degradable()) {
        LOG.warn(
            "event=SD_ADJUDICATION_ABORTED adjudicator={} breakpoint={} kind={}",
            name(),
            breakpoint.value(),
            e.kind());
        throw e; // CONFIG / CANCELLED / INTERNAL：该炸，降级会把真故障藏起来（B3）
      }
      LOG.info(
          "event=SD_ADJUDICATION_FINISHED adjudicator={} breakpoint={} result=Failed reason=llm",
          name(),
          breakpoint.value());
      return new Judgement.Failed("LLM 调用失败（降级 " + e.kind() + "）：" + e.getMessage());
    }
    try {
      if (AdjudicationSchemas.isAbstention(raw)) {
        LOG.info(
            "event=SD_ADJUDICATION_FINISHED adjudicator={} breakpoint={} result=Abstained",
            name(),
            breakpoint.value());
        return new Judgement.Abstained(AdjudicationSchemas.abstentionReason(raw));
      }
      AdjudicationSchemas.validate(breakpoint, raw);
      LOG.info(
          "event=SD_ADJUDICATION_FINISHED adjudicator={} breakpoint={} result=Accepted",
          name(),
          breakpoint.value());
      return new Judgement.Accepted(raw);
    } catch (IllegalArgumentException e) {
      LOG.info(
          "event=SD_ADJUDICATION_FINISHED adjudicator={} breakpoint={} result=Failed reason=schema",
          name(),
          breakpoint.value());
      return new Judgement.Failed("LLM 输出非法（降级）：" + e.getMessage());
    }
  }

  private static String systemPrompt(AdjudicationBreakpoint breakpoint) {
    return "你是 simos 的裁决器，断点 "
        + breakpoint.value()
        + "。只按给定 schema 在合法选项里选择并写理由，不发明新规则；只输出一个 JSON 对象，不要 markdown、不要多余文字。";
  }

  /**
   * 交给模型的 user 提示词：**已脱敏简报 + 输出 schema + 命令白名单 + 输出纪律**（四分量全给，见类注的取代说明）。
   *
   * <p>★ 若无法裁决，要求模型显式输出 {@code {"abstain":true,"reason":"…"}}——这是 {@link AdjudicationSchemas} 认的
   * 合法弃权，而不是让它编一个假判决。
   */
  private static String userPrompt(AdjudicationRequest request) {
    return "已脱敏简报："
        + request.redactedBriefJson()
        + "\n输出 schema（必须满足，JSON 对象）："
        + request.outputSchemaJson()
        + "\n允许使用的命令（白名单）："
        + request.commandWhitelistJson()
        + "\n只输出一个 JSON 对象；若无法裁决，输出 {\"abstain\":true,\"reason\":\"…\"}。";
  }
}
