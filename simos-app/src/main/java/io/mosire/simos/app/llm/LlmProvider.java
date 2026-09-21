package io.mosire.simos.app.llm;

import java.time.Duration;

/**
 * 一条 LLM provider 配置（M11 spec §1.3）：OpenAI 兼容端点的显示名 / 协议 / baseUrl / model / 采样参数 / **密钥引用** / 超时。
 *
 * <p>★ 这是 **app 层基础设施**，不是世界事实（铁律 3）：它不进 sd、不落 revision；决策人只持有它的 {@code id}。 ★ {@code
 * apiKeyRef} 是 {@link SecretRef}（引用或字面值），**值不在此类型的字段语义里**（{@code LITERAL} 时 {@code
 * ref} 承载值，但视图/日志一律掩码）。
 *
 * <p>★ **默认值**（用户实测的坑）：{@code maxTokens} 缺省 {@value #DEFAULT_MAX_TOKENS}（推理模型给太小会只回
 * {@code reasoning_content}）、{@code timeout} 给足（≥ 2 分钟）；这些由**配置文件层**填默认，record 本身只做校验。
 */
public record LlmProvider(
    String id,
    String name,
    String protocol,
    String baseUrl,
    String model,
    double temperature,
    int maxTokens,
    SecretRef apiKeyRef,
    Duration timeout) {

  /** v1 只支持这一种协议（OpenAI 兼容 {@code POST /v1/chat/completions}）。 */
  public static final String PROTOCOL_OPENAI_COMPATIBLE = "openai-compatible";

  /** 单次调用超时上界（5 分钟，spec §1.3）：防止一个坏配置把裁决挂死。 */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  /** `maxTokens` 缺省（用户实测：推理模型 {@code max_tokens}=16 时 {@code content} 为空）。 */
  public static final int DEFAULT_MAX_TOKENS = 4096;

  /** `temperature` 缺省。 */
  public static final double DEFAULT_TEMPERATURE = 0.2;

  /** `timeoutMs` 缺省（30 秒；真实 provider 显式给 120000）。 */
  public static final long DEFAULT_TIMEOUT_MS = 30_000L;

  public LlmProvider {
    id = requireText(id, "id");
    if (name == null || name.isBlank()) {
      name = id;
    }
    protocol = requireText(protocol, "protocol");
    if (!PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
      throw new IllegalArgumentException(
          "不支持的 protocol（v1 只支持 " + PROTOCOL_OPENAI_COMPATIBLE + "）: " + protocol);
    }
    baseUrl = requireText(baseUrl, "baseUrl");
    model = requireText(model, "model");
    if (!(temperature >= 0.0 && temperature <= 2.0)) {
      throw new IllegalArgumentException("temperature 必须在 [0,2]: " + temperature);
    }
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens 必须 ≥ 1: " + maxTokens);
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

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
    return value;
  }
}
