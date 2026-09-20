package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * sd 命令 handler 共用的载荷解析助手（spec §四）。形制照 {@code UnitPayloads}/{@code MapPayloads}。
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因**；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。
 *
 * <p>★ **本类只管形状与类型**；数值范围与域规则由领域类型 / handler 判。
 */
final class SdPayloads {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private SdPayloads() {}

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

  static String requireText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + payload);
    }
    return value.asText();
  }

  static Optional<String> optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串或 null: " + payload);
    }
    return Optional.of(value.asText());
  }

  static int requireInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asInt();
  }

  static long requireLong(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
  }

  /** 必填的 {@code [字符串…]} ⇒ 去重保序集合（空白元素 ⇒ 抛）。 */
  static Set<String> requireTextSet(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      out.add(element.asText());
    }
    return out;
  }

  /** 必填的地址文本 ⇒ {@link Address}（非法地址由 {@link Address#parse} 自己抛）。 */
  static Address requireAddress(JsonNode payload, String field) {
    return Address.parse(requireText(payload, field));
  }

  /** 必填的任意 JSON 值 ⇒ {@code Object}（标量：String / Integer / Double / Boolean）。 */
  static Object requireValue(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("字段 " + field + " 不得缺失或为 null: " + payload);
    }
    return MAPPER.convertValue(value, Object.class);
  }

  /** 必填的 {@code {"kind":"nation"|"army","id":"…"}} ⇒ {@link Affiliation}。 */
  static Affiliation requireAffiliation(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {\"kind\":\"nation\"|\"army\",\"id\":\"…\"} 对象: " + payload);
    }
    String kind = requireText(value, "kind");
    String id = requireText(value, "id");
    return switch (kind) {
      case "nation" -> new Affiliation.Nation(NationId.parse(id));
      case "army" -> new Affiliation.Army(ArmyId.parse(id));
      default -> throw new IllegalArgumentException("affiliation.kind 必须是 nation|army: " + kind);
    };
  }
}
