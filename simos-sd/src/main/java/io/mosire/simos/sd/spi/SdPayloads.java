package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.CasualtySpec;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.LossClass;
import io.mosire.simos.sd.model.MergedEffect;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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

  static Address requireAddress(JsonNode payload, String field) {
    return Address.parse(requireText(payload, field));
  }

  static Optional<Address> optionalAddress(JsonNode payload, String field) {
    return optionalText(payload, field).map(Address::parse);
  }

  static Set<String> optionalTextSet(JsonNode payload, String field) {
    return optionalTextSetIfPresent(payload, field).orElse(Set.of());
  }

  /**
   * **"缺省"与"显式空"分得开**的文本集读取：键缺席（或 null）⇒ {@link Optional#empty()}；显式给（**含空数组**）⇒ 有值。
   *
   * <p>★ 与 {@link #optionalTextSet} 的差别就是这一格：那是"缺省即空集"，本方法是"缺省 = 没说"。{@code
   * sd.SetDecisionMakerAccess} 的载荷语义是"缺省 = 不改动、显式给 = 整份替换" ⇒ 用错那个会把"没写"读成"清空"， 于是**一条只改白名单的命令静默抹掉
   * GM 刚配的资源限制**（两个方向都不会报错）。
   */
  static Optional<Set<String>> optionalTextSetIfPresent(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      out.add(element.asText());
    }
    return Optional.of(out);
  }

  static List<DirectiveCommand> optionalDirectiveCommands(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是命令数组: " + payload);
    }
    List<DirectiveCommand> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是对象: " + element);
      }
      String type = requireText(element, "type");
      String payloadJson = optionalText(element, "payloadJson").orElse("{}");
      out.add(new DirectiveCommand(type, payloadJson));
    }
    return List.copyOf(out);
  }

  static Object requireValue(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("字段 " + field + " 不得缺失或为 null: " + payload);
    }
    return MAPPER.convertValue(value, Object.class);
  }

  static Affiliation requireAffiliation(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException(
          "字段 "
              + field
              + " 必须是 {\"kind\":\"nation\"|\"army\"|\"gov\",\"id\":\"…\"} 对象: "
              + payload);
    }
    return readAffiliation(value);
  }

  /**
   * **可选**的归属集合：键缺席/为 null ⇒ 空集（"不按归属发"）；给了 ⇒ 逐项按 {@link #readAffiliation} 解析（**含空数组**）。
   *
   * <p>★ 与 {@link #optionalTextSet} 同族（缺省即空集），因为 {@code affiliations} 的语义就是"这一份对谁可见"—— "什么都没说"
   * 与"说了空"在本语义下没有区别，都是**不按归属发**（fail-closed）。
   */
  static Set<Affiliation> optionalAffiliationSet(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Set.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException(
          "字段 "
              + field
              + " 必须是 [{\"kind\":\"nation\"|\"army\"|\"gov\",\"id\":\"…\"}…] 数组: "
              + payload);
    }
    Set<Affiliation> out = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的元素必须是 {\"kind\":…,\"id\":…} 对象: " + element);
      }
      out.add(readAffiliation(element));
    }
    return out;
  }

  /** 把一个 {@code {"kind","id"}} 对象读成 {@link Affiliation}（形状唯一拼写点）。 */
  private static Affiliation readAffiliation(JsonNode value) {
    String kind = requireText(value, "kind");
    String id = requireText(value, "id");
    return switch (kind) {
      case "nation" -> new Affiliation.Nation(NationId.parse(id));
      case "army" -> new Affiliation.Army(ArmyId.parse(id));
      case "gov" -> new Affiliation.Gov(UnitId.parse(id));
      default ->
          throw new IllegalArgumentException("affiliation.kind 必须是 nation|army|gov: " + kind);
    };
  }

  static HexCoord requireHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {\"q\":…,\"r\":…} 对象: " + payload);
    }
    int q = requireInt(value, "q");
    int r = requireInt(value, "r");
    return new HexCoord(q, r);
  }

  static Set<UnitId> optionalUnitIdSet(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Set.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    Set<UnitId> out = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      out.add(UnitId.parse(element.asText()));
    }
    return out;
  }

  static List<Trigger> optionalTriggers(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是条件数组: " + payload);
    }
    List<Trigger> out = new ArrayList<>();
    for (JsonNode element : value) {
      out.add(MAPPER.convertValue(element, Trigger.class));
    }
    return List.copyOf(out);
  }

  static CombatStage requireStage(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是阶段对象: " + payload);
    }
    CombatStageId id = CombatStageId.parse(requireText(value, "stageId"));
    String name = requireText(value, "name");
    Set<UnitId> participants = optionalUnitIdSet(value, "participants");
    List<Trigger> entry = optionalTriggers(value, "entry");
    List<Trigger> exit = optionalTriggers(value, "exit");
    long min = optionalLong(value, "minDurationTicks", 0L);
    long max = optionalLong(value, "maxDurationTicks", min);
    OutcomeTable outcomes = requireOutcomeTable(value, "outcomes");
    return new CombatStage(id, name, participants, entry, exit, min, max, outcomes);
  }

  static OutcomeTable requireOutcomeTable(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {\"options\":[…]}: " + payload);
    }
    JsonNode options = value.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      throw new IllegalArgumentException("outcomeTable.options 不得为空（N2）: " + value);
    }
    List<OutcomeOption> parsed = new ArrayList<>();
    for (JsonNode option : options) {
      CombatOutcomeId id = CombatOutcomeId.parse(requireText(option, "id"));
      String label = requireText(option, "label");
      int weight = requireInt(option, "weight");
      CasualtySpec casualties = optionalCasualtySpec(option, "casualties");
      parsed.add(new OutcomeOption(id, label, weight, casualties));
    }
    return new OutcomeTable(parsed);
  }

  private static CasualtySpec optionalCasualtySpec(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return new CasualtySpec(0, Map.of());
    }
    int personnel = optionalInt(value, "personnel", 0);
    Map<String, Integer> equipment = optionalIntMap(value, "equipment");
    return new CasualtySpec(personnel, equipment);
  }

  static List<CasualtyDelta> requireDeltas(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray() || value.isEmpty()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空数组: " + payload);
    }
    List<CasualtyDelta> out = new ArrayList<>();
    for (JsonNode delta : value) {
      UnitId unit = UnitId.parse(requireText(delta, "unit"));
      int personnel = optionalInt(delta, "personnel", 0);
      Map<String, Integer> equipment = optionalIntMap(delta, "equipment");
      LossClass lossClass = requireLossClass(delta, "lossClass");
      out.add(new CasualtyDelta(unit, personnel, equipment, lossClass));
    }
    return List.copyOf(out);
  }

  static LossClass requireLossClass(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return LossClass.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("lossClass 非法（PERMANENT|RECOVERABLE）: " + text);
    }
  }

  static EffectKind requireEffectKind(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return EffectKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("effect kind 非法: " + text);
    }
  }

  static Trigger requireTrigger(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是条件对象: " + payload);
    }
    return MAPPER.convertValue(value, Trigger.class);
  }

  static Action requireAction(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是动作对象: " + payload);
    }
    return MAPPER.convertValue(value, Action.class);
  }

  static long optionalLong(JsonNode payload, String field, long fallback) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
  }

  static int optionalInt(JsonNode payload, String field, int fallback) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asInt();
  }

  static Map<String, Integer> optionalIntMap(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {\"键\":整数} 对象: " + payload);
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    var fields = value.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      if (!entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToInt()) {
        throw new IllegalArgumentException("字段 " + field + " 的值必须是整数: " + entry.getKey());
      }
      out.put(entry.getKey(), entry.getValue().asInt());
    }
    return out;
  }

  static Optional<CombatStageId> optionalStageId(JsonNode payload, String field) {
    return optionalText(payload, field).map(CombatStageId::parse);
  }

  /**
   * 读 {@code sd.SetDecisionMakerAccess} 的 {@link AccessLimit}：**三个键各自"缺省 = 保持既有、显式给 = 整份替换"**。
   *
   * <pre>{@code
   * {"accessLimit":{"map":["Map1/region/701"]}, "redactedFields":["position"],
   *  "adjudicationDisclosure":"PERCEPTION_ONLY"}
   * }</pre>
   *
   * <p>★ **{@code accessLimit} 的键缺席 = 不动**（既有的前缀逐字保留）；给了就整份替换，**给空对象 = 清空**（= 无额外限制）。
   * 两个方向的差别只在"有没有这个键"，而它们**都不报错**——见 {@link #optionalTextSetIfPresent}。
   */
  static AccessLimit accessLimitOrKeep(AccessLimit existing, JsonNode payload) {
    Map<String, Set<String>> prefixes =
        optionalPrefixesByNamespace(payload).orElse(existing.prefixesByNamespace());
    Set<String> redactedFields =
        optionalTextSetIfPresent(payload, "redactedFields").orElse(existing.redactedFields());
    DisclosurePolicy disclosure =
        optionalDisclosureIfPresent(payload).orElse(existing.adjudicationDisclosure());
    return new AccessLimit(prefixes, redactedFields, disclosure);
  }

  /** 命名空间 → 前缀集；键缺席 ⇒ 空。值必须是 {@code [字符串…]}（**空数组是有效值** = 该命名空间"够不着"）。 */
  static Optional<Map<String, Set<String>>> optionalPrefixesByNamespace(JsonNode payload) {
    JsonNode value = payload.get("accessLimit");
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 accessLimit 必须是 {\"命名空间\":[前缀…]} 对象: " + payload);
    }
    Map<String, Set<String>> out = new LinkedHashMap<>();
    var fields = value.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      String namespace = entry.getKey();
      if (namespace.isBlank()) {
        throw new IllegalArgumentException("accessLimit 的命名空间不得为空白: " + value);
      }
      JsonNode prefixes = entry.getValue();
      if (!prefixes.isArray()) {
        throw new IllegalArgumentException(
            "accessLimit[" + namespace + "] 必须是 [前缀…] 数组: " + prefixes);
      }
      Set<String> items = new LinkedHashSet<>();
      for (JsonNode prefix : prefixes) {
        if (!prefix.isTextual() || prefix.asText().isBlank()) {
          throw new IllegalArgumentException(
              "accessLimit[" + namespace + "] 的元素必须是非空白字符串: " + prefix);
        }
        items.add(prefix.asText());
      }
      out.put(namespace, items);
    }
    return Optional.of(out);
  }

  /** 披露档；键缺席（或 null）⇒ 空（**不**回落任何缺省——调用方决定"保持既有"还是"取缺省"）。 */
  static Optional<DisclosurePolicy> optionalDisclosureIfPresent(JsonNode payload) {
    Optional<String> text = optionalText(payload, "adjudicationDisclosure");
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(DisclosurePolicy.valueOf(text.get()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "adjudicationDisclosure 非法（FULL|PERCEPTION_ONLY|WITHHELD）: " + text.get());
    }
  }

  // ── D2 决策包 / FormattedCall（扁平载荷，避免嵌套大对象解析）──────────────────────────

  /**
   * 读 {@code calls} 数组（一条扁平 {@code FormattedCall} 一个小对象）。
   *
   * <p>★ 键缺席/为 null ⇒ 空表（拟稿期"还没有 call"是合法形态）；给了就必须是对象数组。目标 {@code targets} 用 {@link CommandTarget}
   * 的 {@code {namespace,path}} 形状，{@code mergedPlanId} 缺省 = {@link Optional#empty()}； {@code
   * outcomeJson} 缺省 = {@link Optional#empty()}（D3 旧档兼容口径）。
   */
  static List<FormattedCall> optionalFormattedCalls(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是调用数组: " + payload);
    }
    List<FormattedCall> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是对象: " + element);
      }
      int callIndex = requireInt(element, "callIndex");
      String toolName = requireText(element, "toolName");
      String argsJson = optionalText(element, "argsJson").orElse("{}");
      List<CommandTarget> targets = requireCommandTargets(element, "targets");
      String previewJson = optionalText(element, "previewJson").orElse("{}");
      List<String> draftChecks = optionalTextList(element, "draftChecks");
      CallStatus status = parseCallStatus(requireText(element, "status"));
      Optional<String> mergedPlanId = optionalText(element, "mergedPlanId");
      Optional<String> outcomeJson = optionalText(element, "outcomeJson");
      out.add(
          new FormattedCall(
              callIndex,
              toolName,
              argsJson,
              targets,
              previewJson,
              draftChecks,
              status,
              mergedPlanId,
              outcomeJson));
    }
    return List.copyOf(out);
  }

  /** 读跨命名空间目标数组 {@code [{"namespace":"map","path":"Map1/hex/1_2"}…]}。 */
  static List<CommandTarget> requireCommandTargets(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 [{\"namespace\":…,\"path\":…}…] 数组: " + payload);
    }
    List<CommandTarget> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的元素必须是 {namespace,path} 对象: " + element);
      }
      out.add(new CommandTarget(requireText(element, "namespace"), requireText(element, "path")));
    }
    return List.copyOf(out);
  }

  /** 读包状态（只收枚举名，不收自由字符串）。 */
  static PacketStatus requirePacketStatus(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return PacketStatus.valueOf(text.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          field + " 非法（DRAFT|PENDING|APPROVED|REJECTED|MERGED|PARTIALLY_APPROVED）: " + text);
    }
  }

  /** 读 call 状态（只收枚举名）。 */
  static CallStatus parseCallStatus(String text) {
    try {
      return CallStatus.valueOf(text.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("status 非法（PENDING|APPROVED|REJECTED|MERGED）: " + text);
    }
  }

  /** 可空整数（键缺席/为 null ⇒ {@link OptionalLong#empty()}；给了必须是整数）。 */
  static OptionalLong optionalLongValue(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return OptionalLong.empty();
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数或 null: " + payload);
    }
    return OptionalLong.of(value.asLong());
  }

  /** 可选字符串数组（键缺席/为 null ⇒ 空表；元素必须是非空白文本）。 */
  static List<String> optionalTextList(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    List<String> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      out.add(element.asText());
    }
    return List.copyOf(out);
  }

  // ── D3 合并效果集（扁平载荷；整包 upsert）─────────────────────────────────────────────

  /**
   * 读 {@code sd.UpsertMergedEffectPlan} 的扁平载荷（整包）。
   *
   * <p>★ {@code participantIds}/{@code orderedEffects}/{@code sources} 缺省/为 null ⇒ 空表；{@code
   * outcome}/ {@code reasonInfoId} 缺省 ⇒ {@link Optional#empty()}（旧档兼容口径）。
   */
  static MergedEffectPlan requireMergedEffectPlan(JsonNode payload) {
    MergedEffectPlanId id = MergedEffectPlanId.parse(requireText(payload, "id"));
    long tick = requireLong(payload, "tick");
    List<DecisionMakerId> participantIds =
        optionalMergedPlanParticipants(payload, "participantIds");
    List<MergedEffect> orderedEffects = optionalMergedEffects(payload, "orderedEffects");
    List<String> sources = optionalTextList(payload, "sources");
    Optional<String> reasonInfoId = optionalText(payload, "reasonInfoId");
    Optional<String> outcome = optionalText(payload, "outcome");
    return new MergedEffectPlan(
        id, tick, participantIds, orderedEffects, sources, reasonInfoId, outcome);
  }

  /** 合并计划参与者：允许指向**已删除**的决策人（与 packet 同口径，不做存在性校验）。 */
  static List<DecisionMakerId> optionalMergedPlanParticipants(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
    List<DecisionMakerId> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      out.add(DecisionMakerId.parse(element.asText()));
    }
    return List.copyOf(out);
  }

  /** 有序效果数组 {@code [{toolName,argsJson,sourceCallRefs[]?}…]}；缺省/为 null ⇒ 空表。 */
  static List<MergedEffect> optionalMergedEffects(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是效果数组: " + payload);
    }
    List<MergedEffect> out = new ArrayList<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是对象: " + element);
      }
      String toolName = requireText(element, "toolName");
      String argsJson = optionalText(element, "argsJson").orElse("{}");
      List<String> sourceCallRefs = optionalTextList(element, "sourceCallRefs");
      out.add(new MergedEffect(toolName, argsJson, sourceCallRefs));
    }
    return List.copyOf(out);
  }
}
