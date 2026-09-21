package io.mosire.simos.sd.adjudication;

/**
 * 裁决请求（spec §八.5，N10）：四个字符串分量**全部非空**——断点、已脱敏简报、输出 schema、命令白名单。
 *
 * <p>★ **脱敏视图由模块侧构造**（N17）：请求里带的是 {@code redactedBriefJson}，渠道/裁决器**拿不到全量状态**。
 *
 * <p>★ 四个分量都是**不透明文本**：本模块不假设它们的内部结构，只保证非空、可携带。
 */
public record AdjudicationRequest(
    String breakpoint,
    String redactedBriefJson,
    String outputSchemaJson,
    String commandWhitelistJson) {

  public AdjudicationRequest {
    breakpoint = requireText(breakpoint, "breakpoint");
    redactedBriefJson = requireText(redactedBriefJson, "redactedBriefJson");
    outputSchemaJson = requireText(outputSchemaJson, "outputSchemaJson");
    commandWhitelistJson = requireText(commandWhitelistJson, "commandWhitelistJson");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
    return value;
  }
}
