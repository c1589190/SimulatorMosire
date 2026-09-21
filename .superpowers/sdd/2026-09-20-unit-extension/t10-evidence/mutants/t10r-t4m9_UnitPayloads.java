package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * unit 二十个命令 handler 共用的载荷解析助手（spec §四）。
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因**；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。域规则违反由 {@code
 * UnitOperations} / {@code Unit} 构造期抛出的同类异常沿用同一条路径——折算只发生在命令边界这一层。
 *
 * <p>★ **本类只管形状与类型**（字段在不在、类型对不对）；数值范围（`member ≥ 0`、`speed ≥ 1` …）与编制树不变量留给领域类型， 两处不重复实现——领域异常同样被
 * handler 折成拒绝。载荷字段名与 spec §四的表一一对应。
 *
 * <p>★ 载荷形态是本模块的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。
 */
final class UnitPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private UnitPayloads() {}

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

  /** 可选字符串字段：缺失或 {@code null} ⇒ 空 Optional。 */
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

  /** 可选 ID 字段（如 {@code parent}）：缺失或 {@code null} ⇒ 空 Optional（清根语义）。 */
  static Optional<UnitId> optionalId(JsonNode payload, String field) {
    Optional<String> text = optionalText(payload, field);
    if (text.isPresent() && text.get().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 不得为空白");
    }
    return text.map(UnitId::parse);
  }

  /** 必填整数字段。 */
  static int requireInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asInt();
  }

  /**
   * 可选整数字段（T3：{@code SetFormationOffset} 的 {@code dq}/{@code dr}）：缺失或 {@code null} ⇒ 空 Optional。
   */
  static Optional<Integer> optionalInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数或 null: " + payload);
    }
    return Optional.of(value.asInt());
  }

  /** 必填的三态状态（T2）：未知串 ⇒ 抛（`UnitStatus.valueOf` 失败折成拒绝）。 */
  static UnitStatus requireStatus(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return UnitStatus.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("字段 " + field + " 不是合法状态: " + text, e);
    }
  }

  /** 可选的三态状态（T2）：缺失或 {@code null} ⇒ 空 Optional（`CreateUnit` 缺省 MOVING）。 */
  static Optional<UnitStatus> optionalStatus(JsonNode payload, String field) {
    Optional<String> text = optionalText(payload, field);
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UnitStatus.valueOf(text.get()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("字段 " + field + " 不是合法状态: " + text.get(), e);
    }
  }

  /** 必填的 {@code {q,r}} 坐标对象。 */
  static HexCoord requireHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {\"q\":整数,\"r\":整数} 对象: " + payload);
    }
    return hexFrom(value, field);
  }

  /** 可选坐标：缺失或 {@code null} ⇒ 空 Optional（撤销位置语义）。 */
  static Optional<HexCoord> optionalHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {\"q\":整数,\"r\":整数} 对象或 null: " + payload);
    }
    return Optional.of(hexFrom(value, field));
  }

  /** 必填的 {@code {字符串:整数}} 装备对象（空对象合法，范围由 {@code Unit} 判）。 */
  static Map<String, Integer> requireEquipment(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {字符串:整数} 对象: " + payload);
    }
    Map<String, Integer> equipment = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode number = entry.getValue();
      if (!number.isIntegralNumber() || !number.canConvertToInt()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的值必须是整数: " + entry.getKey() + "=" + number);
      }
      equipment.put(entry.getKey(), number.asInt());
    }
    return equipment;
  }

  /** 必填的非空字符串数组（T4：{@code SplitFormation} 的 {@code subUnitIds}）；空数组合法，由领域层判。 */
  static List<String> requireTextArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    List<String> items = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空字符串: " + element);
      }
      items.add(element.asText());
    }
    return items;
  }

  /**
   * 可选的非空字符串数组（T5：{@code UpdateCommandChain} 的 {@code members}）：缺失或 {@code null} ⇒ 空 Optional （**未给
   * ⇒ 不动**，不是清空）；形状与消息口径照 {@link #requireTextArray}，空数组合法、由领域层判。
   */
  static Optional<List<String>> optionalTextArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组或 null: " + payload);
    }
    List<String> items = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空字符串: " + element);
      }
      items.add(element.asText());
    }
    return Optional.of(items);
  }

  /** 必填的 {@code [{q,r}…]} 序列（个数与相邻性由 {@code Route} 构造期判）。 */
  static List<HexCoord> requireWaypoints(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [{q,r}…] 数组: " + payload);
    }
    List<HexCoord> waypoints = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是 {q,r} 对象: " + element);
      }
      waypoints.add(hexFrom(element, field));
    }
    return waypoints;
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
