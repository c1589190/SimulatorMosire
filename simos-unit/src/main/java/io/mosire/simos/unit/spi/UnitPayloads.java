package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.MilitaryDutyKind;
import io.mosire.simos.unit.MilitaryDutyOfHousehold;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * unit 各命令 handler 共用的载荷解析助手（spec §四）。
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因**；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。域规则违反由 {@code
 * UnitOperations} / {@code Unit} 构造期抛出的同类异常沿用同一条路径——折算只发生在命令边界这一层。
 *
 * <p>★ **本类只管形状与类型**（字段在不在、类型对不对）；数值范围（`amount ≥ 0`、`speed ≥ 1` …）与编制树不变量留给领域类型， 两处不重复实现——领域异常同样被
 * handler 折成拒绝。载荷字段名与 spec §四的表一一对应。
 *
 * <p>★ 载荷形态是本模块的私事（C26）：Core 只转交 {@code payloadJson} 字节串，从不理解它的结构。
 */
final class UnitPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private UnitPayloads() {}

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

  /** 必填整数字段（long 量纲：辖区阶段 5 的税率以及后续抽取上限用；范围由领域层判）。 */
  static long requireLong(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + payload);
    }
    return value.asLong();
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

  /**
   * 可选整数字段（long 量纲：辖区阶段 5 的三个 {@code levy*CapPerCommand}）：缺失或 {@code null} ⇒ 空 Optional （**未给 ⇒
   * 保持原值**，不是清 0）；范围由 {@code Jurisdiction} 构造期判（≥ 0；语义 = 一条抽取命令的上限，0 = 该类无额度、拒）。
   */
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

  /**
   * 必填的政府编制层级（阶段 10a 的 {@code unit.SetGovFormation} 用）：未知串 ⇒ 抛（{@code GovernmentLevel.valueOf} 失败
   * 折成拒绝，理由点名词表）。
   */
  static GovernmentLevel requireGovernmentLevel(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return GovernmentLevel.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("字段 " + field + " 不是合法层级（CENTRAL|PROVINCE）: " + text, e);
    }
  }

  /**
   * 必填的行政角色（阶段 10b-i 的 {@code unit.RecruitStaff}/{@code unit.DismissStaff}）：未知串 ⇒ 抛 （{@code
   * StaffRole.valueOf} 失败折成拒绝，理由点名词表）。
   */
  static StaffRole requireStaffRole(JsonNode payload, String field) {
    String text = requireText(payload, field);
    try {
      return StaffRole.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("字段 " + field + " 不是合法角色（SCRIBE|YAMEN|POST）: " + text, e);
    }
  }

  /**
   * 可选的行政角色人数表（阶段 10a 的 {@code unit.SetGovFormation.staff}）：缺失或 {@code null} ⇒ 空 Optional； 给了 ⇒ 必须是
   * {@code {SCRIBE|YAMEN|POST:整数}} 对象。★ <b>角色词表在这里把关</b>（未知串具名拒，不静默丢条目）； <b>值域（≥0）不在这里判</b>——留给
   * {@link io.mosire.simos.unit.GovernmentFormation} 构造期，两处不重复实现。
   */
  static Optional<Map<StaffRole, Long>> optionalStaffMap(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {\"SCRIBE|YAMEN|POST\":整数} 对象或 null: " + payload);
    }
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      StaffRole role;
      try {
        role = StaffRole.valueOf(entry.getKey());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的角色未知（只认 SCRIBE / YAMEN / POST）: " + entry.getKey(), e);
      }
      JsonNode number = entry.getValue();
      if (!number.isIntegralNumber() || !number.canConvertToLong()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的值必须是整数: " + entry.getKey() + "=" + number);
      }
      staff.put(role, number.asLong());
    }
    return Optional.of(staff);
  }

  /**
   * 可选的「逐来源审计数组」形状校验（阶段 10b-i 的 {@code unit.RecruitStaff.sources}）：缺失或 {@code null} ⇒ 合法；给了 ⇒ 必须是
   * JSON 数组，且每个元素是 JSON 对象。
   *
   * <p>★ <b>刻意只校到这个深度</b>：来源元素的字段（来源类型、社会批次 id、人口单位 id、数量…）由 10b-ii 的配套工具批构造与消费；本条命令只把 roster
   * 入编一件事落盘，<b>不解析来源域对象、也不重复扣人</b> （扣人由同批 {@code social.SeedGroups} 负责）。若在这里顺手解析来源，就会长出第二份来源真相。
   */
  static void validateOptionalSourceArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return;
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 JSON 对象数组或 null: " + payload);
    }
    for (JsonNode source : value) {
      if (!source.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的每个来源必须是 JSON 对象: " + source);
      }
    }
  }

  /**
   * 可选的编制政策（阶段 10a 的 {@code unit.SetGovFormation.policy}）：缺失或 {@code null} ⇒ {@link
   * OfficePolicy#defaults()}；给了 ⇒ 只覆盖给出的字段，缺省字段取 defaults（**部分字段合法**）。
   *
   * <p>★ 五个字段都走同一台 mapper 的默认值：四个数值缺省取 {@code defaults().xxx()}，{@code staffCap} 缺省取空表
   * （不设上限）。越界（负值）由 {@link OfficePolicy} 构造期拒。
   */
  static OfficePolicy optionalPolicy(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return OfficePolicy.defaults();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是对象或 null（缺省 = OfficePolicy.defaults()）: " + payload);
    }
    OfficePolicy defaults = OfficePolicy.defaults();
    long grain =
        optionalLong(value, "grainPerStaffPerTick").orElse(defaults.grainPerStaffPerTick());
    long cloth =
        optionalLong(value, "clothPerStaffPerCycle").orElse(defaults.clothPerStaffPerCycle());
    long money =
        optionalLong(value, "moneyPerStaffPerTick").orElse(defaults.moneyPerStaffPerTick());
    long retirement =
        optionalLong(value, "retirementPerStaff").orElse(defaults.retirementPerStaff());
    Map<StaffRole, Long> staffCap = optionalStaffMap(value, "staffCap").orElse(defaults.staffCap());
    return new OfficePolicy(grain, cloth, money, retirement, staffCap);
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

  /**
   * ★★ <b>S3b（2026-10-09）：{@code Unit.manpower} 已退役</b>——人员人口唯一来源是 Social 家户（{@code
   * unit.households} + Social 成员批次现算），不再由 unit 侧的第二本 headcount 承载。
   *
   * <p>本方法给三条"旧载荷"命令的共同边界：{@code manpower} <b>缺席 / null / 空数组</b> ⇒ 合法（空表等价于"没有旧账"）； <b>非空数组</b> ⇒
   * 具名拒，消息指路 Social 家户命令。{@code equipment} 不走这条，照常在 {@code unit} 命令里 发放/调整/战损。
   */
  static void rejectRetiredManpower(JsonNode payload) {
    JsonNode value = payload.get("manpower");
    if (value == null || value.isNull()) {
      return;
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException(
          "字段 manpower 必须是数组或 null（Unit.manpower 已退役，人员由 Social 家户承载）: " + payload);
    }
    if (!value.isEmpty()) {
      throw new IllegalArgumentException(
          "字段 manpower 已退役（S3b / 2026-10-09）：Unit.manpower 不再是人口账，人员属于 Social 家户"
              + "（unit.households + 家户成员批次现算）；请用 social.* 家户命令移动人员，equipment 仍走本命令。"
              + " 收到 "
              + value.size()
              + " 条人力条目");
    }
  }

  /**
   * 必填的人力/装备**状态表**：JSON 数组 {@code [{type,amount}…]}（空数组合法）。每条：{@code type} 非空白、{@code amount}
   * 非负整数（long 量纲）；同表重复 type ⇒ 具名拒（一张表里同一 type 两条会让"加/减值"歧义）。
   *
   * <p>★ 本方法只管**形状与类型**；表级不变量（非 null、重复 type）由 {@link io.mosire.simos.unit.Unit} 构造期再判一遍—— handler
   * 边界要可读拒因，领域类型是最后一道，两处不重复实现数值规则（amount ≥ 0 由 {@link CompositionEntry} 判）。
   */
  static List<CompositionEntry> requireComposition(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [{type,amount}…] 数组: " + payload);
    }
    List<CompositionEntry> entries = new ArrayList<>(value.size());
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是 {type,amount} 对象: " + element);
      }
      String type = requireText(element, "type");
      long amount = requireLong(element, "amount");
      if (!seen.add(type)) {
        throw new IllegalArgumentException("字段 " + field + " 不得有重复 type: " + type);
      }
      entries.add(new CompositionEntry(type, amount));
    }
    return entries;
  }

  /**
   * 必填的人力/装备**有符号增量表**：JSON 数组 {@code [{type,amount}…]}（空数组合法）。每条：{@code type} 非空白、{@code amount}
   * 为可负整数（long 量纲）；同表重复 type ⇒ 具名拒。
   *
   * <p>★ 符号语义不在本方法：{@code ApplyCasualties} 的 ≤ 0、{@code AdjustComposition} 的有符号规则都由 {@code
   * UnitOperations} 按当前状态判（越界/存在性要看单位本体，载荷层看不到）。
   */
  static List<CompositionDelta> requireCompositionDelta(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [{type,amount}…] 数组: " + payload);
    }
    List<CompositionDelta> deltas = new ArrayList<>(value.size());
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是 {type,amount} 对象: " + element);
      }
      String type = requireText(element, "type");
      long amount = requireLong(element, "amount");
      if (!seen.add(type)) {
        throw new IllegalArgumentException("字段 " + field + " 不得有重复 type: " + type);
      }
      deltas.add(new CompositionDelta(type, amount));
    }
    return deltas;
  }

  /** 必填的非空字符串数组（T4：{@code SplitFormation} 的 {@code subUnitIds}）；空数组合法，由领域层判。 */
  static List<String> requireTextArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
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

  /**
   * 必填的家户 id 数组（S3a 的 {@code unit.SetUnitHouseholds.households}）：JSON 数组 {@code ["hh-1","hh-2"]}
   * （空数组合法）。元素必须是非空白字符串；同表重复 ⇒ 具名拒（{@link io.mosire.simos.unit.Unit} 构造期再判一遍，
   * 边界要可读拒因）。顺序是内容的一部分，原样保留。
   */
  static List<HouseholdId> requireHouseholdIds(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [家户 id…] 数组: " + payload);
    }
    List<HouseholdId> households = new ArrayList<>(value.size());
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是非空白字符串: " + element);
      }
      String id = element.asText();
      if (!seen.add(id)) {
        throw new IllegalArgumentException("字段 " + field + " 不得有重复: " + id);
      }
      households.add(HouseholdId.parse(id));
    }
    return households;
  }

  /**
   * 可选的家户 id 数组（{@code unit.CreateUnit.households}）：缺失或 {@code null} ⇒ 空 Optional；给了 ⇒ 形状与语义同
   * {@link #requireHouseholdIds}（空数组 = 显式空表）。★ 2026-10-09 起 {@code unit.SetGovFormation} 不再接收
   * {@code households} 键（唯一实质列表是 {@code Unit.households}；政府家户由域层立编制时同批编入）。
   */
  static Optional<List<HouseholdId>> optionalHouseholdIds(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    return Optional.of(requireHouseholdIds(payload, field));
  }

  /**
   * ★★ <b>S3b：可选的军官/军职家户配置数组</b>（{@code unit.SetArmyFormation.householdDuties}）：
   *
   * <pre>{@code
   * [{"household":"hh-1","kind":"OFFICER","appointment":"营官","commandOf":"u-2"} …]
   * }</pre>
   *
   * <p>★ 缺失或 {@code null} ⇒ 空 Optional（<b>未给 ⇒ 保持既有配置</b>，不是清空——与 {@code households} 的旧调用点兼容口径同款）；
   * 给了（含空数组）⇒ 整体替换。{@code commandOf} 可缺省。字段形状在这里把关；键 == 配置 id 等不变量由 {@link ArmyFormation} / {@link
   * UnitState} 判。
   */
  static Optional<Map<HouseholdId, MilitaryDutyOfHousehold>> optionalMilitaryDuties(
      JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是家户配置对象数组或 null: " + payload);
    }
    Map<HouseholdId, MilitaryDutyOfHousehold> duties = new LinkedHashMap<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是对象: " + element);
      }
      String householdText = requireText(element, "household");
      if (!seen.add(householdText)) {
        throw new IllegalArgumentException("字段 " + field + " 不得有重复 household: " + householdText);
      }
      HouseholdId household = HouseholdId.parse(householdText);
      String kindText = requireText(element, "kind");
      MilitaryDutyKind kind;
      try {
        kind = MilitaryDutyKind.valueOf(kindText);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的 kind 不是合法军职类别（SOLDIER|NCO|OFFICER|COMMANDER）: " + kindText, e);
      }
      String appointment = requireText(element, "appointment");
      Optional<UnitId> commandOf = optionalId(element, "commandOf");
      duties.put(household, new MilitaryDutyOfHousehold(household, kind, appointment, commandOf));
    }
    return Optional.of(duties);
  }

  /**
   * ★★ <b>P4b：军俸政策的逐家户金额表</b>（{@code grainPerHouseholdPerCycle} / {@code clothPerHouseholdPerCycle}
   * / {@code moneyPerHouseholdPerCycle}）：
   *
   * <pre>{@code {"hh-unit:army-1":300,"hh-unit:army-2":120}}</pre>
   *
   * <p>★ 缺失或 {@code null} ⇒ 空表（命令层语义：未给的表 = 空表）；给了 ⇒ 必须是 {@code {家户 id:整数}} 对象。本方法只管形状与类型：键非空白（走
   * {@link HouseholdId#parse}）、值是 long 量纲整数；<b>值域 &gt; 0 不在这里判</b>——留给 {@link MilitaryPayPolicy}
   * 构造期，两处不重复实现。
   */
  static Map<HouseholdId, Long> optionalHouseholdAmountMap(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {\"家户 id\":整数} 对象或 null: " + payload);
    }
    Map<HouseholdId, Long> amounts = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      HouseholdId household = HouseholdId.parse(entry.getKey());
      JsonNode number = entry.getValue();
      if (!number.isIntegralNumber() || !number.canConvertToLong()) {
        throw new IllegalArgumentException(
            "字段 " + field + " 的值必须是整数: " + entry.getKey() + "=" + number);
      }
      amounts.put(household, number.asLong());
    }
    return amounts;
  }

  /**
   * ★★ <b>P4b：从给定 JSON 对象解析一份军俸政策</b>（{@code unit.SetArmyPayPolicy} 的顶层载荷，或 {@code
   * unit.SetArmyFormation.militaryPayPolicy} 的内嵌对象）。
   *
   * <p>字段：{@code expiresOnDay} 缺失或 {@code null} = 永久；三张逐家户表缺失或 {@code null} = 空表；可选 {@code enabled}
   * 只作为“全空 = 停发”的显式边界：{@code enabled=true} + 三表全空 ⇒ 具名拒（声明要发却没有一条腿）， {@code enabled=false} +
   * 非空腿同样具名拒。
   *
   * <p>排期字段 {@code periodDays}/{@code phaseDay}/{@code startsOnDay}（可选带 {@code expiresOnDay}）：
   * <b>带非空腿时必须三个都给</b>（部分给 ⇒ 具名拒）；<b>三表全空且排期字段一个都没给</b> ⇒ 直接归一成 {@link
   * MilitaryPayPolicy#disabled()}（“停发”的最短载荷：{@code {"unitId":...}} 三张表全空）；三表全空但排期字段给了 ⇒ 仍按 {@link
   * MilitaryPayPolicy#requireValidSchedule} 验一遍再归一成 disabled（避免 {@code periodDays=0} 被“停发”静默放过）。
   *
   * <p>★ 本方法只管形状/词表归一；“值 &gt; 0、至少一腿非空、排期自洽、键 ⊆ Unit.households”由 {@link MilitaryPayPolicy} /
   * {@link UnitState} 构造期判。
   */
  static MilitaryPayPolicy requireMilitaryPayPolicy(JsonNode object) {
    Map<HouseholdId, Long> grain = optionalHouseholdAmountMap(object, "grainPerHouseholdPerCycle");
    Map<HouseholdId, Long> cloth = optionalHouseholdAmountMap(object, "clothPerHouseholdPerCycle");
    Map<HouseholdId, Long> money = optionalHouseholdAmountMap(object, "moneyPerHouseholdPerCycle");
    boolean allEmpty = grain.isEmpty() && cloth.isEmpty() && money.isEmpty();
    Optional<Boolean> enabled = optionalBoolean(object, "enabled");
    if (enabled.isPresent()) {
      if (enabled.get() && allEmpty) {
        throw new IllegalArgumentException(
            "军俸政策声明 enabled=true 但 grain/cloth/money 三表全空（要发就至少给一条腿；"
                + "停发请用三表全空且不带 enabled=true 的载荷）");
      }
      if (!enabled.get() && !allEmpty) {
        throw new IllegalArgumentException(
            "军俸政策声明 enabled=false 却带了非空腿（disabled 只能是三张空表）: payload=" + object);
      }
    }
    JsonNode periodNode = object.get("periodDays");
    JsonNode phaseNode = object.get("phaseDay");
    JsonNode startsNode = object.get("startsOnDay");
    boolean periodGiven = periodNode != null && !periodNode.isNull();
    boolean phaseGiven = phaseNode != null && !phaseNode.isNull();
    boolean startsGiven = startsNode != null && !startsNode.isNull();
    boolean expiresGiven =
        object.get("expiresOnDay") != null && !object.get("expiresOnDay").isNull();
    boolean anySchedule = periodGiven || phaseGiven || startsGiven || expiresGiven;
    boolean fullSchedule = periodGiven && phaseGiven && startsGiven;
    if (anySchedule && !fullSchedule) {
      throw new IllegalArgumentException(
          "军俸政策的排期字段必须同时给或同时省略（periodDays/phaseDay/startsOnDay；expiresOnDay 只在给排期时可带）: payload="
              + object);
    }
    if (!anySchedule) {
      if (!allEmpty) {
        throw new IllegalArgumentException(
            "军俸政策带非空腿时必须给 periodDays/phaseDay/startsOnDay: payload=" + object);
      }
      return MilitaryPayPolicy.disabled();
    }
    long periodDays = requireLong(object, "periodDays");
    long phaseDay = requireLong(object, "phaseDay");
    long startsOnDay = requireLong(object, "startsOnDay");
    OptionalLong expiresOnDay =
        optionalLong(object, "expiresOnDay").map(OptionalLong::of).orElseGet(OptionalLong::empty);
    if (allEmpty) {
      MilitaryPayPolicy.requireValidSchedule(periodDays, phaseDay, startsOnDay, expiresOnDay);
      return MilitaryPayPolicy.disabled();
    }
    return new MilitaryPayPolicy(
        periodDays, phaseDay, startsOnDay, expiresOnDay, grain, cloth, money);
  }

  /**
   * ★★ <b>P4b：可选的军俸政策</b>（{@code unit.SetArmyFormation.militaryPayPolicy}）：缺失或 {@code null} ⇒ 空
   * Optional（<b>未给 ⇒ 保持既有 policy</b>，与 {@code householdDuties} 的兼容口径同款）； 给了 ⇒ 必须是以 {@link
   * #requireMilitaryPayPolicy} 同口径解析的对象（整体替换）。
   */
  static Optional<MilitaryPayPolicy> optionalMilitaryPayPolicy(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象或 null: " + payload);
    }
    return Optional.of(requireMilitaryPayPolicy(value));
  }

  /**
   * ★★ <b>S3b：可选的领导层/官吏家户配置数组</b>（{@code unit.SetGovFormation.householdPosts}；Z4 加 {@code tierId}）：
   *
   * <pre>{@code
   * [{"household":"hh-1","role":"SCRIBE","level":"CENTRAL","head":true,"tierId":"tier-2"} …]
   * }</pre>
   *
   * <p>★ 缺失或 {@code null} ⇒ 空 Optional（<b>未给 ⇒ 保持既有配置</b>）；给了（含空数组）⇒ 整体替换。{@code head} 可缺省（缺省
   * false）；{@code tierId} 可缺省（缺省空串 = legacy/未指派档位，指向 {@code
   * GovAdministrationPlan.postTiers}）。字段形状在这里把关；键 == 配置 id、{@code tierId} 是否存在于计划目录等不变量由 {@link
   * GovernmentFormation} / app 组合根判。
   */
  static Optional<Map<HouseholdId, GovernmentPostOfHousehold>> optionalGovernmentPosts(
      JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是领导家户配置对象数组或 null: " + payload);
    }
    Map<HouseholdId, GovernmentPostOfHousehold> posts = new LinkedHashMap<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是对象: " + element);
      }
      String householdText = requireText(element, "household");
      if (!seen.add(householdText)) {
        throw new IllegalArgumentException("字段 " + field + " 不得有重复 household: " + householdText);
      }
      HouseholdId household = HouseholdId.parse(householdText);
      StaffRole role = requireStaffRole(element, "role");
      GovernmentLevel level = requireGovernmentLevel(element, "level");
      boolean head = optionalBoolean(element, "head").orElse(false);
      String tierId = optionalText(element, "tierId").orElse("");
      posts.put(household, new GovernmentPostOfHousehold(household, role, level, head, tierId));
    }
    return Optional.of(posts);
  }

  /** 可选布尔字段：缺失或 {@code null} ⇒ 空 Optional；给出但非布尔 ⇒ 抛。 */
  static Optional<Boolean> optionalBoolean(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔或 null: " + payload);
    }
    return Optional.of(value.asBoolean());
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
