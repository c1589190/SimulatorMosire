package io.mosire.simos.social.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * social 命令 handler 共用的载荷解析助手（与 {@code MapPayloads} / {@code UnitPayloads} 同制）。
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因**；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。域规则违反由 {@code
 * SocialCity} 的构造期守卫抛出的同类异常沿用同一条路径——折算只发生在命令边界这一层。
 *
 * <p>★ **本类只管形状与类型**（字段在不在、类型对不对）；人口非负、城市 id 重复等语义留给 handler / 领域类型，两处不重复实现。
 *
 * <p>★ 载荷形态是本模块的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。坐标沿用仓内既有写法 {@code {q:整数,r:整数}}。
 */
final class SocialPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private SocialPayloads() {}

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

  /** 必填字符串字段。 */
  static String requireText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + payload);
    }
    return value.asText();
  }

  /** 必填的整数字段（非整数/超 long ⇒ 抛）。 */
  static long requireLong(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
  }

  /** 可选整数字段：缺席或 JSON {@code null} ⇒ {@code null}（"不给"）；出现但非整数 ⇒ 抛。 */
  static Long optionalLong(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
  }

  /** 可选字符串字段：缺席或 JSON {@code null} ⇒ {@code null}（"不给"）；出现但非字符串 ⇒ 抛。 */
  static String optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + payload);
    }
    return value.asText();
  }

  /** 必填的 {@code {q,r}} 对象 ⇒ {@link HexCoord}。 */
  static HexCoord requireHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {q,r} 对象: " + payload);
    }
    return hexFrom(value, field);
  }

  /**
   * 必填的 {@code [{q,r,population}…]} 数组 ⇒ **保序**的坐标 → 人口表。**重复坐标：后出现者覆盖先出现者，不报错**（先出现的那个位置保持）； 空数组 ⇒
   * 抛。population 为负 ⇒ 抛（这条语义在本层判，不留给构造器——{@code PopulationSeries} 不校验人口非负）。
   */
  static Map<HexCoord, Long> requireEntries(JsonNode payload) {
    JsonNode value = payload.get("entries");
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 entries 必须是 [{q,r,population}…] 数组: " + payload);
    }
    Map<HexCoord, Long> entries = new LinkedHashMap<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 entries 的元素必须是 {q,r,population} 对象: " + element);
      }
      HexCoord at = hexFrom(element, "entries");
      long population = requireLong(element, "population");
      if (population < 0) {
        throw new IllegalArgumentException("population 必须 ≥ 0: " + population);
      }
      entries.put(at, population); // ★ 后出现者覆盖先出现者（位置保持首次出现处）
    }
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    return entries;
  }

  /**
   * 可选的 {@code props} 对象：缺席或 JSON {@code null} ⇒ {@code null}（"不给"）；出现但非对象、或某个值为 JSON {@code null}
   * ⇒ 抛。 值为任意 JSON（字符串/数/布尔/数组/对象），按 Jackson 的默认绑定成 {@code String}/{@code Integer}/{@code
   * Long}/{@code Double}/ {@code Boolean}/{@code List}/{@code Map}。
   */
  static Map<String, Object> optionalProps(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {键:值} 对象: " + payload);
    }
    Map<String, Object> props = new LinkedHashMap<>();
    value
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode item = entry.getValue();
              if (item == null || item.isNull()) {
                throw new IllegalArgumentException(
                    "字段 " + field + " 的值不得为 null: " + entry.getKey());
              }
              props.put(entry.getKey(), MAPPER.convertValue(item, Object.class));
            });
    return props;
  }

  private static HexCoord hexFrom(JsonNode object, String field) {
    JsonNode q = object.get("q");
    JsonNode r = object.get("r");
    if (q == null
        || !q.isIntegralNumber()
        || !q.canConvertToInt()
        || r == null
        || !r.isIntegralNumber()
        || !r.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须有整数 q 与 r: " + object);
    }
    return new HexCoord(q.asInt(), r.asInt());
  }
}
