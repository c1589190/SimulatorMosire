package io.mosire.simos.app.llm;

import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.LlmClient;
import io.mosire.simos.sd.adjudication.LlmDecisionAdjudicator;
import io.mosire.simos.sd.model.DecisionMaker;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * 使用期解析（M11 spec §三，判据 C8/C9）：把决策人绑定的 providerId 解析成一个真 {@link LlmClient}。
 *
 * <p>★ **不静默兜底**是核心不变量：
 *
 * <ul>
 *   <li>注册表为空（两处配置都没配 / 都空）⇒ 抛「未配置」，**绝不**编造一个默认 provider；
 *   <li>未绑定（providerId 空）⇒ 抛「未绑定」，**绝不**落到某个默认 provider；
 *   <li>绑定的 id 在当前注册表里查无（如回放到一条绑了已删 provider 的 revision）⇒ 抛**点名该 id** 的「不存在」， **绝不**换一个能用的顶上。
 * </ul>
 *
 * <p>★ 调用方（如 {@code LlmDecisionAdjudicator}）可以把它折成 {@code Judgement.Failed} 降级（N13），但那是"本 tick
 * 无判决"， 不是"换 provider"。
 */
public final class LlmProviderResolver {

  private final LlmProviderRegistry registry;
  private final HttpClient http;

  public LlmProviderResolver(LlmProviderRegistry registry) {
    this(registry, HttpClient.newHttpClient());
  }

  public LlmProviderResolver(LlmProviderRegistry registry, HttpClient http) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.http = Objects.requireNonNull(http, "http");
  }

  /**
   * providerId ⇒ {@link LlmClient}。
   *
   * @throws IllegalStateException providerId 空（未绑定）、注册表空（未配置）或查无（回放悬空）
   */
  public LlmClient llmClientFor(String providerId) {
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalStateException("决策人未绑定 LLM provider（providerId 为空）");
    }
    if (registry.list().isEmpty()) {
      throw new IllegalStateException(
          "LLM provider 未配置（仓库默认配置 "
              + registry.defaultsFile()
              + " 与 store 覆盖 "
              + registry.file()
              + " 都不存在或为空）");
    }
    LlmProvider provider =
        registry
            .find(providerId)
            .orElseThrow(() -> new IllegalStateException("绑定的 LLM provider 不存在: " + providerId));
    return new HttpLlmClient(
        provider.baseUrl(),
        provider.model(),
        provider.temperature(),
        provider.maxTokens(),
        () ->
            registry
                .resolveSecret(provider.apiKeyRef())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "LLM provider 的密钥引用不可解析: " + provider.apiKeyRef().kind() + ":" + provider.apiKeyRef().ref())),
        provider.timeout(),
        http);
  }

  /** 按决策人**当前**绑定的 provider 造裁决器（未绑定 / 悬空 ⇒ 抛，见 {@link #llmClientFor}）。 */
  public DecisionAdjudicator adjudicatorFor(DecisionMaker maker) {
    Objects.requireNonNull(maker, "maker");
    return new LlmDecisionAdjudicator(llmClientFor(maker.providerId().orElse(null)));
  }
}
