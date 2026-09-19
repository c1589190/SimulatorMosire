package io.mosire.simos.map.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * map 命令 handler 共用的载荷解析助手（M8 spec §二）。
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因**；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。域规则违反由 {@link
 * io.mosire.simos.map.ops.TerrainOperations} 抛出的同类异常沿用同一条路径——折算只发生在命令边界这一层。
 *
 * <p>★ **本类只管形状与类型**（字段在不在、类型对不对）；词表、边界等语义留给领域操作，两处不重复实现。
 *
 * <p>★ 载荷形态是本模块的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。坐标沿用仓内既有写法 {@code {q:整数,r:整数}}（与
 * {@code unit.PlaceAt}/{@code unit.PlanRoute} 同形）。
 */
final class MapPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private MapPayloads() {}

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

  /** 必填的 {@code [{q,r}…]} 数组 ⇒ 去重后的坐标集合（保序）。空数组**在这一层合法**（形状无错）， 由领域操作判定"至少要改一格"。 */
  static Set<HexCoord> requireHexes(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [{q,r}…] 数组: " + payload);
    }
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是 {q,r} 对象: " + element);
      }
      hexes.add(hexFrom(element, field));
    }
    return hexes;
  }

  /** 必填的 {@code regionId} 字符串 ⇒ {@link RegionId}（空白由 {@link RegionId#parse} 拒绝，消息是它自己的）。 */
  static RegionId requireRegionId(JsonNode payload, String field) {
    return RegionId.parse(requireText(payload, field));
  }

  /**
   * 可选的 {@code [{q,r}…]} 数组 ⇒ 去重后的坐标集合（保序）；**字段缺席或 JSON {@code null} ⇒ 返回 {@code null}**（"不给"），
   * 出现但形态不符 ⇒ 抛。用于 {@code map.UpdateRegion} 的"hexes 可选"语义——**缺席与空数组是两回事**：
   * 空数组在这一层合法，由领域操作判"至少要有一格"。
   */
  static Set<HexCoord> optionalHexes(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return requireHexes(payload, field);
  }

  /**
   * 可选的 {@code meta} 对象 ⇒ {@link RegionMeta}；**字段缺席或 JSON {@code null} ⇒ 返回 {@code null}**（"不给"）。
   * 出现但非对象、或四个子字段非字符串 ⇒ 抛。四个子字段各自可选（缺席 ⇒ 该项 {@code null}）。
   */
  static RegionMeta optionalMeta(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {color,tag,description,annexedBy} 对象: " + payload);
    }
    return new RegionMeta(
        optionalText(value, "color"),
        optionalText(value, "tag"),
        optionalText(value, "description"),
        optionalText(value, "annexedBy"));
  }

  /** 可选的字符串子字段：缺席或 JSON {@code null} ⇒ {@code null}；出现但非字符串 ⇒ 抛。 */
  private static String optionalText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("meta 字段 " + field + " 必须是字符串: " + object);
    }
    return value.asText();
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
