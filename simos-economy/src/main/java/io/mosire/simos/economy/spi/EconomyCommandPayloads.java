package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★ R4-E2：四条 GM 命令共用的载荷读取助手（只做形状/类型/范围的第一道判；跨表语义在各自的 handler 与 {@code EconomyData} 守卫里判）。
 *
 * <p>★ 为什么收在一处：四条命令都吃 {@code {kind,id}}、商品表、资产表、数组字段这几样；各写一份就是四份可能漂开的解析。 ★ 失败一律抛 {@link
 * IllegalArgumentException}（由各 handler 折成 {@code Rejected}）；**不默认、不归一**。
 */
final class EconomyCommandPayloads {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private EconomyCommandPayloads() {}

  /** 解析载荷 JSON 并要求是对象（不是对象/空白 ⇒ 抛具名）。 */
  static JsonNode parseObject(String command, String payloadJson) {
    try {
      JsonNode payload = MAPPER.readTree(payloadJson);
      if (payload == null || !payload.isObject()) {
        throw new IllegalArgumentException(command + " 载荷必须是 JSON 对象: " + payloadJson);
      }
      return payload;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(command + " 载荷不是合法 JSON: " + e.getOriginalMessage());
    }
  }

  /**
   * ★ <b>日志安全的拒绝理由</b>（照 L3 的 {@code logReason} 形态，plan §4.3）：命令/守卫消息为方便调用方排查会回显字段值或整段载荷，
   * 但日志纪律禁止载荷明文与 JSON 原文（plan §4.3）。这里只保留可读前缀 —— 截到第一个 JSON 起始符/换行；{@code payload …}
   * 这一类原始文本消息再截到冒号。截断只影响日志文本，不影响异常本身，也不改 {@code Rejected} 的理由。
   */
  static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    if (text.startsWith("payload ")) {
      int colon = text.indexOf(':');
      if (colon >= 0 && colon < cut) {
        cut = colon;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }

  /** 必填非空文本。 */
  static String requireText(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(command + " 缺少非空文本字段: " + field);
    }
    return node.asText();
  }

  /** 可选非空文本；缺键 / JSON null ⇒ fallback；给了但空白/类型不对 ⇒ 抛。 */
  static String optionalText(String command, JsonNode payload, String field, String fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是非空文本: " + node);
    }
    return node.asText();
  }

  /** 必填整数（JSON 整型；超 long / 小数量词 ⇒ 抛）。 */
  static long requireLong(String command, JsonNode payload, String field) {
    return requireLongNode(command, field, payload.get(field));
  }

  /** 可选整数；缺键 / JSON null ⇒ fallback。 */
  static long optionalLong(String command, JsonNode payload, String field, long fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    return requireLongNode(command, field, node);
  }

  /** 必填 int（JSON 整型；超 int 范围 ⇒ 抛）。 */
  static int requireInt(String command, JsonNode payload, String field) {
    long value = requireLong(command, payload, field);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 超出 int 范围: " + value);
    }
    return (int) value;
  }

  /** 可选 int；缺键 / JSON null ⇒ fallback；超出 int 范围 ⇒ 抛。 */
  static int optionalInt(String command, JsonNode payload, String field, int fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    long value = requireLongNode(command, field, node);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 超出 int 范围: " + value);
    }
    return (int) value;
  }

  private static long requireLongNode(String command, String field, JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是整数: " + node);
    }
    return node.asLong();
  }

  /** 必填额坐标：接受 {@code {q,r}} 对象或 {@code "q_r"} 文本。 */
  static HexCoord requireHex(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(command + " 缺少格字段: " + field);
    }
    return decodeHex(command, field, node);
  }

  /** 可选额坐标；缺键 / JSON null ⇒ empty。 */
  static Optional<HexCoord> optionalHex(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    return Optional.of(decodeHex(command, field, node));
  }

  private static HexCoord decodeHex(String command, String field, JsonNode node) {
    if (node.isTextual() && !node.asText().isBlank()) {
      return HexCoord.parse(node.asText());
    }
    if (node.isObject() && node.hasNonNull("q") && node.hasNonNull("r")) {
      return new HexCoord(
          requireIntNode(command, field + ".q", node.get("q")),
          requireIntNode(command, field + ".r", node.get("r")));
    }
    throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是 {q,r} 或 \"q_r\": " + node);
  }

  private static int requireIntNode(String command, String field, JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是 int: " + node);
    }
    return node.asInt();
  }

  /** 必填主体 {@code {kind,id}}。 */
  static ActorRef requireActor(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(command + " 缺少主体字段: " + field);
    }
    return decodeActor(command, field, node);
  }

  /** 可选主体；缺键 / JSON null ⇒ empty。 */
  static Optional<ActorRef> optionalActor(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    return Optional.of(decodeActor(command, field, node));
  }

  private static ActorRef decodeActor(String command, String field, JsonNode node) {
    if (!node.isObject()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是 {kind,id} 对象: " + node);
    }
    return new ActorRef(
        ActorKind.parse(requireText(command, node, "kind")), requireText(command, node, "id"));
  }

  /**
   * 商品 → 非负整数表（缺键 / JSON null ⇒ 空表）。
   *
   * @param positive true ⇒ 逐值必须 &gt; 0（产出表）；false ⇒ 逐值 ≥ 0（投入表）
   */
  static Map<CommodityId, Long> optionalCommodityMap(
      String command, JsonNode payload, String field, boolean positive) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是对象: " + node);
    }
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String commodity = entry.getKey();
              long value = requireLongNode(command, field + "." + commodity, entry.getValue());
              if (positive ? value <= 0L : value < 0L) {
                throw new IllegalArgumentException(
                    command
                        + " 的字段 "
                        + field
                        + "."
                        + commodity
                        + (positive ? " 必须 > 0: " : " 不得为负: ")
                        + value);
              }
              values.put(CommodityId.parse(commodity), value);
            });
    return values;
  }

  /**
   * 币种 → 整数表（缺键 / JSON null ⇒ 空表）。
   *
   * @param positive true ⇒ 逐值必须 &gt; 0（P4a 规则请求量）；false ⇒ 逐值 ≥ 0
   */
  static Map<CurrencyId, Long> optionalCurrencyMap(
      String command, JsonNode payload, String field, boolean positive) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是对象: " + node);
    }
    Map<CurrencyId, Long> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String currency = entry.getKey();
              long value = requireLongNode(command, field + "." + currency, entry.getValue());
              if (positive ? value <= 0L : value < 0L) {
                throw new IllegalArgumentException(
                    command
                        + " 的字段 "
                        + field
                        + "."
                        + currency
                        + (positive ? " 必须 > 0: " : " 不得为负: ")
                        + value);
              }
              values.put(CurrencyId.parse(currency), value);
            });
    return values;
  }

  /** 资产种类 → 非负整数表（缺键 / JSON null ⇒ 空表）；键走 {@link AssetKind#valueOf}。 */
  static Map<AssetKind, Long> optionalAssetMap(String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是对象: " + node);
    }
    Map<AssetKind, Long> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              AssetKind asset = parseAssetKind(command, entry.getKey());
              long value = requireLongNode(command, field + "." + entry.getKey(), entry.getValue());
              if (value < 0L) {
                throw new IllegalArgumentException(
                    command + " 的字段 " + field + "." + entry.getKey() + " 不得为负: " + value);
              }
              values.put(asset, value);
            });
    return values;
  }

  /** 权利性质集合（缺键 / JSON null ⇒ 空集；数组元素走 {@link OwnershipStake.RightKind#valueOf}）。 */
  static Set<OwnershipStake.RightKind> optionalRightKinds(
      String command, JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Set.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是数组: " + node);
    }
    Set<OwnershipStake.RightKind> values = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(command + " 的字段 " + field + " 的元素必须是文本: " + element);
      }
      values.add(parseRightKind(command, field, element.asText()));
    }
    return values;
  }

  /** 可选 {@link LaborSource}；缺键 / JSON null ⇒ fallback。 */
  static LaborSource optionalLaborSource(
      String command, JsonNode payload, String field, LaborSource fallback) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(command + " 的字段 " + field + " 必须是文本: " + node);
    }
    try {
      return LaborSource.parse(node.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(command + " 的字段 " + field + ": " + e.getMessage());
    }
  }

  private static AssetKind parseAssetKind(String command, String text) {
    try {
      return AssetKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          command + " 的资产种类未知: " + text + "；合法值: " + java.util.Arrays.toString(AssetKind.values()));
    }
  }

  private static OwnershipStake.RightKind parseRightKind(
      String command, String field, String text) {
    try {
      return OwnershipStake.RightKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          command
              + " 的字段 "
              + field
              + " 含未知权利性质: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(OwnershipStake.RightKind.values()));
    }
  }
}
