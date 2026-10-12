package io.mosire.simos.army.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.log.LogOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * army 各命令 handler 共用的载荷解析助手（阶段 D1 落地、阶段 D4 扩展 / D-009 补裁 + D-010）：与 unit 的 {@code UnitPayloads}、sd
 * 的 {@code SdPayloads} 同制——<b>只管线格式这一层</b>（字段在不在、类型对不对）。
 *
 * <p>★ 坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因；handler 在命令边界折成 {@code
 * HandlerOutcome.Rejected}。数值范围与跨字段不变量（{@code tick ≥ 0}、{@code participants} 至少一个/不重复、{@code weight
 * > 0}、 {@code selectedOutcomeId} ∈ outcomes、同表 type 不重复）留给领域 record 的构造期，两处不重复实现。
 *
 * <p>★ 载荷形态是 army 模块自己的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。
 *
 * <p>★ <b>阶段解析的缺省口径</b>：{@code participants} 缺省 = 本条记录的 {@code participants}（调用方把缺省列表传进来）；{@code
 * outcomes} / {@code losses} 缺省 = 空数组。★ 阶段载荷里**不允许**出现 {@code selectedOutcomeId}/{@code
 * rollSeed}：判定只能走 {@code army.ResolveCombatStage}，见 {@link #requireStage}。
 */
final class ArmyPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private ArmyPayloads() {}

  /**
   * ★ <b>日志安全的拒绝理由</b>：本类的校验消息为方便调用方排查会回显字段值/整段载荷，但日志纪律禁止载荷明文与 JSON 原文（plan §4.3）。 这里只保留可读前缀——截到第一个
   * JSON 起始符/换行；{@code payload ...} 这一类原始文本消息再截到冒号，避免把调用方原文带进日志。 截断只影响日志文本，不影响异常本身，也不改 {@code
   * Rejected} 的理由。
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
   * SdPayloads#logRejected} / {@code GovPayloads#logRejected} 逐字相同（2026-10-12 批次 3d-1 随军队身份两条命令
   * 迁入时补：它们原先借的是 sd 的那一份）。
   *
   * <p>每个具名拒绝路径（显式 {@code Rejected} 与 catch）各调用一次；{@code idKeyValues} 是偶数个 {@code key,value}。
   */
  static void logRejected(
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

  /** 可选字符串字段：缺失或 {@code null} ⇒ 空 Optional；给出但非文本/空白 ⇒ 抛。 */
  static Optional<String> optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 若给出必须是非空白字符串: " + payload);
    }
    return Optional.of(value.asText());
  }

  /**
   * ★ <b>「空串也是空」的可选字符串字段</b>（2026-10-12 批次 3d-1 新增，<b>逐字照抄 sd 侧那份的语义</b>）：缺失或 {@code null} ⇒ 空
   * Optional；给出但<b>非文本</b> ⇒ 抛；给出且是文本（<b>含空串/空白串</b>）⇒ 原样返回。
   *
   * <p>★★ <b>为什么必须与 {@link #optionalText} 并存（不是重复）</b>：军队身份的两条命令自 {@code sd} 迁入时，两条的
   * "可选串"口径<b>本来就不同</b>，且都已有调用方依赖：
   *
   * <ul>
   *   <li>{@code army.CreateArmy} 的 {@code masterGovUnitId} 走 {@code
   *       SdPayloads.optionalText}（<b>不</b>拒空白）⇒ <b>空串是一份形状合法的"未认主子"载荷</b>，随后由 {@code UnitId.parse}
   *       具名拒（"UnitId 不得为空白"）；迁入后必须保持这条 拒绝链（工具描述里明写"缺省 = 未认主子"，而 {@code army.SetArmyMasterGov} 的
   *       docs 明写"空串 = 解除认领"）；
   *   <li>{@code army.SetArmyMasterGov} 的 {@code masterGovUnitId} <b>先过这一条、再由调用方 filter 掉空白</b>（"缺席
   *       / {@code null} / 空串（含空白串）= 解除认领"，见该 handler 的类注）。
   * </ul>
   *
   * ⇒ 两条命令各用各的、语义各自冻结。<b>不把 {@link #optionalText} 改成宽容</b>：那会静默改掉 {@code army.RecordCombat}
   * 一族已经生效的严格口径（形状坏的载荷不该被放过）。非文本的拒因文案与 sd 侧那份逐字相同。
   */
  static Optional<String> optionalTextAllowingBlank(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是字符串或 null: " + payload);
    }
    return Optional.of(value.asText());
  }

  /** 可选整数字段（long 量纲：{@code tick}/{@code seed}）：缺失或 {@code null} ⇒ 空 Optional；非整数 ⇒ 抛。 */
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

  /** 必填整数字段（long 量纲；允许负数，如损失增量与 seed）。 */
  static long requireLong(JsonNode payload, String field) {
    Optional<Long> value = optionalLong(payload, field);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("字段 " + field + " 必填且为整数: " + payload);
    }
    return value.get();
  }

  /** 必填的 JSON 对象字段。 */
  static JsonNode requireObject(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 JSON 对象: " + payload);
    }
    return value;
  }

  /** 可选的 JSON 对象字段：缺失或 {@code null} ⇒ null；其它类型 ⇒ 抛。 */
  static JsonNode optionalObject(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 JSON 对象或 null: " + payload);
    }
    return value;
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
    List<UnitId> units = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白 unitId: " + element);
      }
      units.add(UnitId.parse(element.asText()));
    }
    return units;
  }

  /** 可选 {@code [unitId...]} 数组：缺失或 {@code null} ⇒ {@code fallback}；给出则按必填口径解析。 */
  static List<UnitId> optionalUnitIdList(JsonNode payload, String field, List<UnitId> fallback) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return requireUnitIdList(payload, field);
  }

  /**
   * 可选阶段对象：缺失或 {@code null} ⇒ null；给出 ⇒ {@link #stageOf}。
   *
   * @param defaultParticipants 阶段载荷未给 {@code participants} 时沿用的参与单位（通常是记录级 participants）
   */
  static CombatStage optionalStage(
      JsonNode payload, String field, List<UnitId> defaultParticipants) {
    JsonNode stage = optionalObject(payload, field);
    if (stage == null) {
      return null;
    }
    return stageOf(stage, field, defaultParticipants);
  }

  /** 必填阶段对象（{@code army.AppendCombatStage} 的 {@code stage}）：按 {@link #stageOf} 解析。 */
  static CombatStage requireStage(
      JsonNode payload, String field, List<UnitId> defaultParticipants) {
    JsonNode stage = requireObject(payload, field);
    return stageOf(stage, field, defaultParticipants);
  }

  /** 阶段对象解析（形状层；表内不变量留给 {@link CombatStage} 构造期）。 */
  private static CombatStage stageOf(
      JsonNode stage, String where, List<UnitId> defaultParticipants) {
    if (stage.has("selectedOutcomeId") || stage.has("rollSeed")) {
      throw new IllegalArgumentException(
          "字段 "
              + where
              + " 不得携带 selectedOutcomeId/rollSeed：阶段创建只落「未判定」形态，判定走 army.ResolveCombatStage");
    }
    CombatStageId id = CombatStageId.parse(requireText(stage, "id"));
    String name = requireText(stage, "name");
    List<UnitId> participants = optionalUnitIdList(stage, "participants", defaultParticipants);
    String text = requireText(stage, "text");
    List<CombatOutcome> outcomes = optionalOutcomes(stage);
    return new CombatStage(
        id, name, participants, text, outcomes, Optional.empty(), Optional.empty());
  }

  /** 可选 {@code outcomes} 数组：缺失或 {@code null} ⇒ 空表；给出 ⇒ 逐项按 {@link #outcomeOf} 解析。 */
  private static List<CombatOutcome> optionalOutcomes(JsonNode stage) {
    JsonNode value = stage.get("outcomes");
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 outcomes 必须是 [结局...] 数组或 null: " + stage);
    }
    List<CombatOutcome> outcomes = new ArrayList<>();
    for (JsonNode node : value) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(
            "outcomes 的元素必须是 {\"id\",\"label\",\"weight\",\"losses?\"} 对象: " + node);
      }
      outcomes.add(outcomeOf(node));
    }
    return outcomes;
  }

  /** 单个结局：{@code id}/{@code label}/{@code weight}/{@code losses?}。 */
  private static CombatOutcome outcomeOf(JsonNode node) {
    CombatOutcomeId id = CombatOutcomeId.parse(requireText(node, "id"));
    String label = requireText(node, "label");
    long weight = requireLong(node, "weight");
    List<CombatUnitLoss> losses = optionalUnitLosses(node);
    return new CombatOutcome(id, label, weight, losses);
  }

  /** 可选 {@code losses} 数组：缺失或 {@code null} ⇒ 空表；给出 ⇒ 逐项按 {@link #lossOf} 解析。 */
  private static List<CombatUnitLoss> optionalUnitLosses(JsonNode outcome) {
    JsonNode value = outcome.get("losses");
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 losses 必须是 [单位损失...] 数组或 null: " + outcome);
    }
    List<CombatUnitLoss> losses = new ArrayList<>();
    for (JsonNode node : value) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(
            "losses 的元素必须是 {\"unit\",\"manpower?\",\"equipment?\"} 对象: " + node);
      }
      losses.add(lossOf(node));
    }
    return losses;
  }

  /** 单个单位的损失：{@code unit}/{@code manpower?}/{@code equipment?}（两条增量表缺省为空）。 */
  private static CombatUnitLoss lossOf(JsonNode node) {
    UnitId unit = UnitId.parse(requireText(node, "unit"));
    List<CompositionDelta> manpower = optionalCompositionDeltas(node, "manpower");
    List<CompositionDelta> equipment = optionalCompositionDeltas(node, "equipment");
    return new CombatUnitLoss(unit, manpower, equipment);
  }

  /** 可选 {@code [{type,amount}]} 增量表：缺失或 {@code null} ⇒ 空表；给出 ⇒ 逐项解析（符号不限）。 */
  static List<CompositionDelta> optionalCompositionDeltas(JsonNode container, String field) {
    JsonNode value = container.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 [{\"type\",\"amount\"}...] 数组或 null: " + container);
    }
    List<CompositionDelta> deltas = new ArrayList<>();
    for (JsonNode node : value) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的元素必须是 {\"type\",\"amount\"} 对象: " + node);
      }
      deltas.add(new CompositionDelta(requireText(node, "type"), requireLong(node, "amount")));
    }
    return deltas;
  }
}
