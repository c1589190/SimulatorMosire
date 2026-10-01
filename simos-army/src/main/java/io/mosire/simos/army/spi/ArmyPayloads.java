package io.mosire.simos.army.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * army 各命令 handler 共用的载荷解析助手（阶段 D1 / D-012）：与 unit 的 {@code UnitPayloads}、sd 的 {@code SdPayloads}
 * 同制——<b>只管线格式这一层</b>（字段在不在、类型对不对）。
 *
 * <p>★ 坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因；handler 在命令边界折成 {@code
 * HandlerOutcome.Rejected}。数值范围与跨字段不变量（{@code tick ≥ 0}、{@code participants} 至少一个/不重复、损失量 ≥ 0）留给
 * {@link io.mosire.simos.army.CombatRecord} 构造期，两处不重复实现。
 *
 * <p>★ 载荷形态是 army 模块自己的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。
 */
final class ArmyPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private ArmyPayloads() {}

  /** 解析载荷文本：非 JSON、或不是 JSON 对象 ⇒ 抛。 */
  static JsonNode parse(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象: " + payloadJson);
    }
    return payload;
  }

  /** 必填字符串字段（只判"是不是字符串"；空白由领域类型拒）。 */
  static String requireText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + payload);
    }
    return value.asText();
  }

  /** 可选整数字段（long 量纲：{@code tick}）：缺失或 {@code null} ⇒ 空 Optional。 */
  static Optional<Long> optionalLong(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数或 null: " + payload);
    }
    return Optional.of(value.asLong());
  }

  /** 必填的 {@code {q,r}} 坐标对象。 */
  static HexCoord requireHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {\"q\":整数,\"r\":整数} 对象: " + payload);
    }
    JsonNode q = value.get("q");
    JsonNode r = value.get("r");
    if (q == null
        || !q.isIntegralNumber()
        || !q.canConvertToInt()
        || r == null
        || !r.isIntegralNumber()
        || !r.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须有整数 q 与 r: " + value);
    }
    return new HexCoord(q.asInt(), r.asInt());
  }

  /**
   * 必填的 {@code [unitId...]} 数组：每个元素必须是非空白字符串。★ 只做形状/身份解析，<b>不查 unit 切片里有没有这个单位</b>
   * （记录写的是历史，单位可能已被解散；存在性留给需要它的读侧/调用方）。
   */
  static List<UnitId> requireUnitIdList(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [unitId...] 数组: " + payload);
    }
    List<UnitId> participants = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白 unitId: " + element);
      }
      participants.add(UnitId.parse(element.asText()));
    }
    return participants;
  }

  /**
   * 可选的 {@code {自然语义键:损失量}} 对象：缺失或 {@code null} ⇒ 空表（D-012 的"可选损失"）。值只判整数形状， <b>非负范围由 {@link
   * io.mosire.simos.army.CombatRecord} 判</b>（两处不重复实现）。
   */
  static Map<String, Long> optionalLosses(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {自然语义键:非负整数} 对象或 null: " + payload);
    }
    Map<String, Long> losses = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode number = entry.getValue();
      if (!number.isIntegralNumber() || !number.canConvertToLong()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的值必须是整数: " + entry.getKey() + "=" + number);
      }
      losses.put(entry.getKey(), number.asLong());
    }
    return losses;
  }
}
