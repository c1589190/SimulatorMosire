package io.mosire.simos.economy.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import java.util.LinkedHashMap;
import java.util.Map;

/** 旧档节点整形的纯 JSON 形状助手（P0.5-C2；不读写状态）。 */
final class EconomyCodecSupport {

  /** 读顶层组件的对象字段；缺席/非对象 ⇒ null（调用方按"没有该组件"处理）。 */
  static ObjectNode objectField(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    return value instanceof ObjectNode object ? object : null;
  }

  /** 读顶层组件为对象；缺席 ⇒ 新建并挂上。 */
  static ObjectNode ensureObjectField(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value instanceof ObjectNode object) {
      return object;
    }
    ObjectNode created = JsonNodeFactory.instance.objectNode();
    node.set(field, created);
    return created;
  }

  /**
   * {@code FieldDelta} 节点的"增量值表"：{@code upsert.entries} 或 {@code patch.upserts.entries}；其余变体 ⇒
   * null。
   */
  static ObjectNode deltaEntries(JsonNode delta) {
    if (!(delta instanceof ObjectNode object)) {
      return null;
    }
    String kind = object.path("@class").asText("");
    if ("upsert".equals(kind)) {
      return objectField(object, "entries");
    }
    if ("patch".equals(kind)) {
      ObjectNode upserts = objectField(object, "upserts");
      return upserts == null ? null : objectField(upserts, "entries");
    }
    return null;
  }

  /** 保序遍历对象字段（Map.entry 形态；Jackson 的 fields() 也能用，这里只为读起来一致）。 */
  static Iterable<Map.Entry<String, JsonNode>> iterableFields(ObjectNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields.entrySet();
  }

  /** id 值的两种形态：{@code {"value":…}} 或裸字符串。 */
  static String textOfId(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isObject() && node.hasNonNull("value") && node.get("value").isTextual()) {
      return node.get("value").asText();
    }
    return null;
  }

  /** 必填文本（缺键/空白 ⇒ null；调用方决定 fail-closed 还是走缺省）。 */
  static String textOf(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }

  /** long 取值（非数字/缺席 ⇒ fallback）。 */
  static long longOf(JsonNode node, long fallback) {
    return node != null && node.isNumber() ? node.longValue() : fallback;
  }

  /** {@code {"value":<id>}} 形态的 id 节点。 */
  static ObjectNode idNode(String value) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("value", value);
    return node;
  }

  /** {@code {"kind":…,"id":…}} 形态的主体节点。 */
  static ObjectNode actorNode(String kind, String id) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", kind);
    node.put("id", id);
    return node;
  }

  /** 一个主体节点 → {@link ActorRef}（kind 走字段原文；缺失 ⇒ fail-closed）。 */
  static ActorRef actorOf(JsonNode node) {
    String kind = textOf(node.get("kind"));
    String id = textOf(node.get("id"));
    if (kind == null || id == null) {
      throw new IllegalStateException("主体节点必须给非空 kind/id: " + node);
    }
    return new ActorRef(io.mosire.simos.actor.api.actor.ActorKind.parse(kind), id);
  }
}
