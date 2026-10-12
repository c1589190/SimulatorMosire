package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.log.LogOrigin;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * gov 命令 handler 共用的载荷解析助手（spec §四）。形制照 {@code UnitPayloads}/{@code MapPayloads}/ {@code
 * SdPayloads}。
 *
 * <p>★ <b>坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因</b>；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。★
 * 本类只管形状与类型；数值范围与域规则由 领域类型 / handler 判。
 *
 * <p>★★ <b>为什么 gov 有自己的一份（不是复制粘贴的懒）</b>：本批（3c）四条外交命令的 handler 自 {@code simos-sd} 迁入，而 {@code
 * simos-gov} 的 enforcer <b>禁 {@code simos-sd}</b>（gov/sd 互禁）⇒ 结构性拿不到那份 {@code SdPayloads}（它 729 行、且
 * 与 sd 的领域类型深度耦合，搬进 util 属另一件事）。本仓的既有形制本来就是"**每模块一份
 * XxxPayloads**"（map/unit/social/economy/actor/army 各一份，见各模块 {@code spi} 包）⇒
 * 这里照同形制落一份<b>最小子集</b>：只含迁入的四个 handler 真正用到的那几个方法，语义与拒绝文案 <b>逐字照抄</b>（不改调用方看到的理由）。★
 * 它<b>不是</b>线格式的第二个拼写点：这些命令的载荷形状只有这一份实现（旧那份随命令一起离开 sd）。
 */
public final class GovPayloads {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private GovPayloads() {}

  /** 解析载荷为 JSON 对象；非合法 JSON / 非对象 ⇒ 具名拒因。 */
  public static JsonNode parse(String payloadJson) {
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

  /** 必填非空白字符串（形状与类型；空白由领域 id 类型再把一层）。 */
  public static String requireText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串: " + payload);
    }
    return value.asText();
  }

  /** 必填整数（能转 {@code int}）。 */
  public static int requireInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asInt();
  }

  /** 可选字符串：缺键 / null ⇒ 空；给了但非文本 ⇒ 拒。 */
  public static Optional<String> optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串或 null: " + payload);
    }
    return Optional.of(value.asText());
  }

  /** 可选整数：缺键 / null ⇒ {@code fallback}；给了但非整数 ⇒ 拒。 */
  public static long optionalLong(JsonNode payload, String field, long fallback) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
  }

  /** 可选布尔：缺键 / null ⇒ {@code defaultValue}；给了但非布尔 ⇒ 拒。 */
  public static boolean optionalBoolean(JsonNode payload, String field, boolean defaultValue) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔: " + payload);
    }
    return value.asBoolean();
  }

  /**
   * ★ <b>日志安全的拒绝理由</b>：校验消息为方便调用方排查会回显字段值/整段载荷，而日志纪律禁止载荷明文与 JSON 原文（plan §4.3）。 这里只保留可读前缀——截到第一个
   * JSON 起始符/换行；{@code payload ...} 这一类原始文本消息再截到冒号。截断只影响日志文本，不改 {@code Rejected} 的理由。
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

  /**
   * ★ <b>具名拒绝的统一 INFO 发射</b>（2026-10-23 用户裁定：被拒绝一律 INFO，必须明显记录）：字段固定 {@code reason=} + 调用方给的关键 id（值
   * null/空白 ⇒ {@code -}）。理由先过 {@link #logReason}，载荷原文/JSON 原文不进日志。语义与 {@code
   * SdPayloads#logRejected} 逐字相同。
   */
  public static void logRejected(
      Logger logger, LogOrigin origin, String event, String reason, Object... idKeyValues) {
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(event, "event");
    if (idKeyValues.length % 2 != 0) {
      throw new IllegalArgumentException("logRejected 需要偶数个 id key/value: " + idKeyValues.length);
    }
    Object[] fields = new Object[idKeyValues.length + 2];
    fields[0] = "reason";
    fields[1] = logReason(reason);
    for (int index = 0; index < idKeyValues.length; index += 2) {
      Object value = idKeyValues[index + 1];
      fields[index + 2] = idKeyValues[index];
      fields[index + 3] = value == null || String.valueOf(value).isBlank() ? "-" : value;
    }
    EventLog.channel(logger).info(LogEvent.of(event, origin, fields));
  }
}
