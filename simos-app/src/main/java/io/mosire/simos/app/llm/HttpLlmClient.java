package io.mosire.simos.app.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.sd.adjudication.LlmClient;
import io.mosire.simos.sd.adjudication.LlmRequest;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 真 LLM 客户端（M11 spec §四，判据 C5/C6）：OpenAI 兼容 {@code POST /v1/chat/completions}。
 *
 * <p>★ **全可注入**：{@code HttpClient} 与密钥供应器（{@link Supplier}）都由构造器传入 ⇒ 测试打本地 stub、用替身供应器；
 * 「执行上浮」（N10）在此兑现——key / 网络 / 超时都在这里，{@code LlmClient} 接口只有一次同步调用。
 *
 * <p>★ **密钥纪律**：key 由供应器**调用时**取、**不存字段**、**绝不打印**；{@code Authorization} 头不进日志；异常消息只含 状态码或原因类型，**不含
 * key、不含响应体**。
 *
 * <p>★ 抛出的任何 {@link RuntimeException} 会被 {@code LlmDecisionAdjudicator} 折成 {@code
 * Judgement.Failed}（N13）。
 */
public final class HttpLlmClient implements LlmClient {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final String chatCompletionsUrl;
  private final String model;
  private final Supplier<String> apiKey;
  private final Duration timeout;
  private final HttpClient http;

  /**
   * @param baseUrl OpenAI 兼容根（如 {@code https://api.example.com}，可带/不带末尾斜杠）
   * @param model 模型名（进请求体）
   * @param apiKey 密钥供应器（调用时取，返回值不进任何字段/日志）
   * @param timeout 单次调用超时（正、有上界，见 {@link LlmProvider#MAX_TIMEOUT}）
   * @param http HTTP 客户端（生产传 {@code HttpClient.newHttpClient()}，测试传替身/真客户端打本地 stub）
   */
  public HttpLlmClient(
      String baseUrl, String model, Supplier<String> apiKey, Duration timeout, HttpClient http) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(apiKey, "apiKey");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(http, "http");
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.chatCompletionsUrl = trimmed + "/v1/chat/completions";
    this.model = model;
    this.apiKey = apiKey;
    this.timeout = timeout;
    this.http = http;
  }

  @Override
  public String complete(LlmRequest request) {
    Objects.requireNonNull(request, "request");
    String key = apiKey.get();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("LLM 密钥不可解析（引用未配置或值为空）");
    }
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(chatCompletionsUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(request), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response;
    try {
      response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("LLM 调用被中断");
    } catch (IOException e) {
      throw new IllegalStateException("LLM 调用失败: " + e.getClass().getSimpleName());
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("LLM 返回非 2xx 状态码: " + response.statusCode());
    }
    return extractContent(response.body());
  }

  private String buildBody(LlmRequest request) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", model);
    root.put("temperature", 0);
    ArrayNode messages = root.putArray("messages");
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", request.systemPrompt());
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", request.userPrompt());
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("LLM 请求体序列化失败");
    }
  }

  /** 取 {@code choices[0].message.content}；形状不符 ⇒ 抛（不含响应体，避免回显敏感内容）。 */
  private static String extractContent(String responseBody) {
    JsonNode root;
    try {
      root = MAPPER.readTree(responseBody);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("LLM 响应不是合法 JSON");
    }
    JsonNode content = root.path("choices").path(0).path("message").path("content");
    if (!content.isTextual()) {
      throw new IllegalStateException("LLM 响应缺 choices[0].message.content");
    }
    return content.asText();
  }
}
