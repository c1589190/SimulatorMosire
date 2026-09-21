package io.mosire.simos.sd.adjudication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各断点的输出 schema 与**代码侧校验器**（spec §八.2 / §八.4，N10/N14）。
 *
 * <p>★ **模型的输出必须过这里**：校验失败 ⇒ {@link LlmDecisionAdjudicator} 折成 {@link Judgement.Failed}
 * （不逃逸）。校验的是**形状与枚举**（哪个字段在、动作是否在闭集里），不是"选得好不好"——N14 明确判据不断言 LLM 的具体选择。
 *
 * <p>★ **不是恒真**：D1/D2/D4/D5/D6/D7/D8 各有自己的必填字段与枚举约束，{@link #validate} 对未登记断点也当场拒。
 */
public final class AdjudicationSchemas {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private AdjudicationSchemas() {}

  /** 该断点的输出 schema（给模型看的 JSON 文本）。 */
  public static String schemaJson(AdjudicationBreakpoint breakpoint) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("breakpoint", breakpoint.value());
    schema.put("type", "object");
    schema.put("required", requiredFields(breakpoint));
    return toJson(schema);
  }

  /** 校验模型输出：必须是 JSON 对象、含共同的理由字段、且满足该断点的必填与枚举约束。 */
  public static void validate(AdjudicationBreakpoint breakpoint, String payloadJson) {
    JsonNode node = parseObject(payloadJson);
    requireText(node, "rationaleText");
    switch (breakpoint.value()) {
      case "D1", "D3" -> {
        requireText(node, "stageId");
        requireText(node, "selectedOutcomeId");
        requireArray(node, "casualtyDeltas");
      }
      case "D2" -> {
        requireText(node, "directiveId");
        requireText(node, "intentText");
        requireArray(node, "commands");
      }
      case "D4" -> {
        requireEnum(node, "action", List.of("ENGAGE", "DISENGAGE", "WITHDRAW"));
        requireArray(node, "unitIds");
      }
      case "D5" -> {
        requireEnum(node, "op", List.of("SPLIT", "MERGE"));
        requireText(node, "rootUnitId");
      }
      case "D6" -> requireText(node, "disposition");
      case "D7", "D8" -> requireText(node, "action");
      default -> throw new IllegalArgumentException("未登记的断点: " + breakpoint.value());
    }
  }

  /** 模型是否显式弃权（合法输出，非失败）。 */
  public static boolean isAbstention(String payloadJson) {
    JsonNode node = parseObject(payloadJson);
    JsonNode abstain = node.get("abstain");
    return abstain != null && abstain.isBoolean() && abstain.asBoolean();
  }

  /** 弃权理由（缺省给稳定文案，仍是非空白）。 */
  public static String abstentionReason(String payloadJson) {
    JsonNode node = parseObject(payloadJson);
    JsonNode reason = node.get("reason");
    return reason != null && reason.isTextual() && !reason.asText().isBlank()
        ? reason.asText()
        : "模型弃权（无理由）";
  }

  static JsonNode parseObject(String payloadJson) {
    if (payloadJson == null) {
      throw new IllegalArgumentException("payload 不得为 null");
    }
    JsonNode node;
    try {
      node = MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象: " + payloadJson);
    }
    return node;
  }

  private static List<String> requiredFields(AdjudicationBreakpoint breakpoint) {
    return switch (breakpoint.value()) {
      case "D1", "D3" -> List.of("stageId", "selectedOutcomeId", "casualtyDeltas", "rationaleText");
      case "D2" -> List.of("directiveId", "intentText", "commands", "rationaleText");
      case "D4" -> List.of("action", "unitIds", "rationaleText");
      case "D5" -> List.of("op", "rootUnitId", "rationaleText");
      case "D6" -> List.of("disposition", "rationaleText");
      case "D7", "D8" -> List.of("action", "rationaleText");
      default -> throw new IllegalArgumentException("未登记的断点: " + breakpoint.value());
    };
  }

  private static void requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空白字符串: " + node);
    }
  }

  private static void requireArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
  }

  private static void requireEnum(JsonNode node, String field, List<String> allowed) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || !allowed.contains(value.asText())) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 " + allowed + " 之一: " + node);
    }
  }

  private static String toJson(Object view) {
    try {
      return MAPPER.writeValueAsString(view);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("schema 序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
