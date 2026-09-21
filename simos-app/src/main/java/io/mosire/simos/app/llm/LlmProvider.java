package io.mosire.simos.app.llm;

import java.time.Duration;

/**
 * 一条 LLM provider 配置（M11 spec §1.3）：OpenAI 兼容端点的 baseUrl / model / **密钥引用** / 超时。
 *
 * <p>★ 这是 **app 层基础设施**，不是世界事实（铁律 3）：它不进 sd、不落 revision；决策人只持有它的 {@code id}。 ★ {@code apiKeyRef} 是
 * {@link SecretRef}（引用），**值不在此类型里**。
 */
public record LlmProvider(
    String id, String baseUrl, String model, SecretRef apiKeyRef, Duration timeout) {

  /** 单次调用超时上界（5 分钟，spec §1.3）：防止一个坏配置把裁决挂死。 */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  public LlmProvider {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id 不得为空白");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl 不得为空白");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model 不得为空白");
    }
    if (apiKeyRef == null) {
      throw new IllegalArgumentException("apiKeyRef 不得为 null");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout 必须为正: " + timeout);
    }
    if (timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException("timeout 不得超过 " + MAX_TIMEOUT + ": " + timeout);
    }
  }
}
