package io.mosire.simos.app.llm;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.LlmRouteAssembler;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.LlmDecisionAdjudicator;
import io.mosire.simos.sd.model.DecisionMaker;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用期解析（spec §三，判据 C8/C9）：把决策人绑定的 {@code providerId} 解析成 AgentLib 的 {@link LlmClient}， 并包成本模块 D 阶段的
 * {@link io.mosire.simos.sd.adjudication.LlmClient} SPI。
 *
 * <p>★ **不静默兜底**是核心不变量（这是本类唯一"自己的逻辑"，也是唯一的变异靶子）：
 *
 * <ul>
 *   <li>未绑定（{@code providerId} 空）⇒ 抛「未绑定」，**绝不**落到某条默认路由；
 *   <li>绑定的 id 在当前路由表里查无（如回放到一条绑了已删 provider 的 revision）⇒ 抛**点名该 id** 的「不存在」， **绝不**换一条能用的顶上；
 *   <li>路由在场但坏掉 / 装配失败 ⇒ AgentLib 的 {@link ConfigException} **原样冒泡**（不兜底、不换路由）。
 * </ul>
 *
 * <p>★ **降级 ≠ 换 provider**：调用方（{@link LlmDecisionAdjudicator}）可以把上述失败折成 {@code
 * Judgement.Failed}（N13：本 tick 无判决，tick 继续），但那是"这一次拿不到判决"，不是"换一个 provider 顶上"。 且按 {@link
 * LlmException.Kind} 的分类，**配置类错误该炸**（见 {@link LlmDecisionAdjudicator}）。
 *
 * <p>★ **本类不认识 HTTP / 密钥 / 超时**：那些全在 AgentLib 里（{@link LlmRouteAssembler} + {@code
 * ConfigApiKeySource} 或其 SPI 替身）。本类只做"名字 → 客户端"的映射与 fail-closed 判定。
 */
public final class LlmProviderResolver {

  private static final Logger LOG = LoggerFactory.getLogger(LlmProviderResolver.class);

  /** 未绑定 provider 时的失败信号（调用方据它决定降级还是炸）。 */
  public static final String E_UNBOUND = "E_LLM_PROVIDER_UNBOUND";

  /** 绑定的 provider 在当前路由表里查无（回放悬空 / 配置已被删）。 */
  public static final String E_NOT_FOUND = "E_LLM_PROVIDER_NOT_FOUND";

  /** 判决输出的 token 上界（需求 A2）：推理模型要留足思维链空间，隔壁实测"给足 ≥2048，建议 4096"。 */
  private static final int MAX_OUTPUT_TOKENS = 4096;

  private final AgentLibLlmConfig config;
  private final Path storeRoot;

  public LlmProviderResolver(AgentLibLlmConfig config, Path storeRoot) {
    this.config = Objects.requireNonNull(config, "config");
    this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
  }

  /**
   * {@code providerId} ⇒ AgentLib 的 {@link LlmClient}。
   *
   * @throws IllegalStateException providerId 空（未绑定）或查无（路由表里没有这个名字）
   * @throws ConfigException 路由在场但读不动（AgentLib 的错误码原样上报）
   */
  public LlmClient agentLibClientFor(String providerId) {
    String id = requireRouteId(providerId);
    requireKnownRoute(id);
    return LlmRouteAssembler.client(config.configStore(), id, AccessToken.SYSTEM);
  }

  /** 路由表里必须有这个名字；没有 ⇒ 抛**点名该 id** 的异常（绝不回退到别的 provider）。 */
  private void requireKnownRoute(String id) {
    if (!config.availableNames().contains(id)) {
      throw new IllegalStateException(
          E_NOT_FOUND
              + ": 绑定的 LLM provider 不存在: "
              + id
              + "（可用："
              + String.join(", ", config.availableNames())
              + "——本项绝不回退到别的 provider）");
    }
  }

  /** 同 {@link #agentLibClientFor}，但直接用 AgentLib 的取密钥 SPI（{@code ConfigApiKeySource}）。 */
  public LlmClient agentLibClientWithOfficialKeySource(String providerId) {
    ModelRoute route =
        io.mosire.agentlib.llm.LlmRouteLoader.load(
            config.configStore(), requireRouteId(providerId));
    return LlmRouteAssembler.client(
        route,
        new io.mosire.agentlib.llm.ConfigApiKeySource(
            config.configStore(), route.credentialsRef(), AccessToken.SYSTEM));
  }

  /**
   * 按决策人**当前**绑定的 provider 造裁决器（未绑定 / 悬空 ⇒ 抛，见 {@link #llmClientFor}）。
   *
   * <p>★ 这是 app 与 sd 的**唯一接缝**：sd 侧只认它自己的 {@code LlmClient} SPI（{@code String
   * complete(LlmRequest)}）， 由 {@link AdapterLlmClient} 把 AgentLib 的 {@code LlmResponse} 折成文本。
   */
  public DecisionAdjudicator adjudicatorFor(DecisionMaker maker) {
    Objects.requireNonNull(maker, "maker");
    return new LlmDecisionAdjudicator(llmClientFor(maker.providerId().orElse(null)));
  }

  /**
   * 本模块（sd）的 {@link io.mosire.simos.sd.adjudication.LlmClient} SPI：AgentLib 客户端的薄适配。
   *
   * <p>★ **走 {@link LlmClient#text} 而不是 {@code chat}**：判决场景要的就是"一段文本"（JSON 草案）， 而 AgentLib 的 {@code
   * text()} 已经把 A1 的思维链折叠（`reasoning_content` 非空且正文为空时折进正文）与 "拿不到就抛 {@code NO_TEXT}"两条语义处理掉了 ——
   * 本类不重复实现，也不静默返回空串。
   *
   * <p>★ **不走工具**：判决不需要工具目录（给了工具反而会得到"只有工具调用、没有文本"，见 {@code text()} 的 {@code NO_TEXT} 说明）。故请求永远只有
   * system + user 两条消息。
   *
   * <p>★ **采样**：{@code temperature=0}（判决要可复现，需求 A2）+ {@code maxTokens=4096}（推理模型给足思维链空间）。
   */
  public io.mosire.simos.sd.adjudication.LlmClient llmClientFor(String providerId) {
    String id = requireRouteId(providerId);
    // ★ fail-closed 的第二道（第一道是 requireRouteId）：路由表里没有这个名字 ⇒ **点名该 id** 抛出，
    //   绝不落到别的 provider（这正是回放悬空 / 配置被删时的形态）。
    requireKnownRoute(id);
    // ★ 密钥源：先 keys.*（AgentLib 配置），再 ENV，再 FILE（SimosApiKeySource）——ENV/FILE 是用户裁定给的逃生口。
    ModelRoute route = io.mosire.agentlib.llm.LlmRouteLoader.load(config.configStore(), id);
    LlmClient agentLib =
        LlmRouteAssembler.client(
            route, new SimosApiKeySource(config, storeRoot, route.credentialsRef()));
    LlmClient observing = observeResponses(agentLib);
    return request ->
        observing.text(
            new LlmRequest(
                    List.of(
                        LlmMessage.system(request.systemPrompt()),
                        LlmMessage.user(request.userPrompt())))
                .withTemperature(0)
                .withMaxTokens(MAX_OUTPUT_TOKENS));
  }

  /**
   * 观测装饰器：在 {@code chat()} 上记一行**模型自报的用量**（model / 输入输出 token / 思维链处置），供"开始决策" 之后核对真 provider
   * 确实被调用过。
   *
   * <p>★ 只覆盖 {@code chat()}（不重写 {@code text()}）——{@link LlmClient#text} 是 AgentLib 的 default
   * 方法，它会调回本 装饰器的 {@code chat()}，于是 A1 的思维链折叠与 {@code NO_TEXT} 语义**逐字仍是 AgentLib 的**，本类不重复实现。
   */
  private static LlmClient observeResponses(LlmClient delegate) {
    return new ObservingLlmClient(delegate);
  }

  private static final class ObservingLlmClient implements LlmClient {

    private final LlmClient delegate;

    private ObservingLlmClient(LlmClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws LlmException {
      LlmResponse response = delegate.chat(request);
      LOG.info(
          "LLM 判决响应 model={} inputTokens={} outputTokens={} reasoning={}",
          response.model(),
          response.inputTokens(),
          response.outputTokens(),
          response.reasoningDisposition());
      return response;
    }

    @Override
    public String model() {
      return delegate.model();
    }
  }

  /** providerId 形态检查（未绑定 = 空白；**这是 fail-closed 的第一道**，在碰配置之前）。 */
  private static String requireRouteId(String providerId) {
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalStateException(
          E_UNBOUND + ": 决策人未绑定 LLM provider（providerId 为空）——本项绝不落到默认 provider");
    }
    return providerId.strip();
  }

  /** 模型名（观测用；路由读不动时给 {@code unknown}，不谎报）。 */
  public String modelNameOf(String providerId) {
    try {
      return io.mosire.agentlib.llm.LlmRouteLoader.load(config.configStore(), providerId).model();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }
}
