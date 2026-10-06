package io.mosire.simos.social.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.provisioning.DemandPeriod;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * 可选字符串字段，但"给了就不许空白"：缺席/JSON {@code null} ⇒ {@code null}；出现且为空白字符串 ⇒ 抛。
   *
   * <p>用途是幂等键 / 批次 id / 来源字段这类"要么不给、给就必须有值"的字段——空白是静默无效值，比缺字段更坏。
   */
  static String optionalNonBlankText(JsonNode payload, String field) {
    String text = optionalText(payload, field);
    if (text != null && text.isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 若给必须非空白: " + payload);
    }
    return text;
  }

  /** 必填的 {@code {q,r}} 对象 ⇒ {@link HexCoord}。 */
  static HexCoord requireHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {q,r} 对象: " + payload);
    }
    return hexFrom(value, field);
  }

  /** 可选 {@code {q,r}} 对象：缺席或 JSON {@code null} ⇒ {@code null}（"不给"）；出现但形态不符 ⇒ 抛。 */
  static HexCoord optionalHex(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {q,r} 对象: " + payload);
    }
    return hexFrom(value, field);
  }

  /** 可选布尔：缺席或 JSON {@code null} ⇒ {@code defaultValue}；出现但非布尔 ⇒ 抛。 */
  static boolean optionalBoolean(JsonNode payload, String field, boolean defaultValue) {
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

  /** 必填家户 id 字段（S3a 的家户命令共用）：{@code HouseholdId.parse} 只校验非空白。 */
  static HouseholdId requireHouseholdId(JsonNode payload, String field) {
    return HouseholdId.parse(requireText(payload, field));
  }

  /**
   * 必填的 {@code {name,description?,metadata?}} 家户画像对象（{@code social.CreateHousehold} 与 {@code
   * social.SubmitHouseholdWorkOrder} 的 CREATE_HOUSEHOLD 共用一处解析，避免两份形状漂移）。
   *
   * <p>字段名 {@code profile} 的载体由 {@code field} 决定（工单操作里可以是 {@code profile}，将来也可换名）。 {@code name}
   * 非空白由 {@link HouseholdProfile} 构造期判。
   */
  static HouseholdProfile requireProfile(JsonNode payload, String field) {
    JsonNode profile = payload.get(field);
    if (profile == null || !profile.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {name,description?,metadata?} 对象: " + payload);
    }
    String name = requireText(profile, "name");
    String description = optionalText(profile, "description");
    Map<String, String> metadata = optionalStringMap(profile, "metadata");
    return new HouseholdProfile(name, description, metadata);
  }

  /** 必填性别字段（S3a 的家户命令共用）：只认词表里那两个名字（大小写一致，不做宽容匹配——见 {@link Sex} 的线格式约定）。 */
  static Sex requireSex(JsonNode element, String field) {
    String text = requireText(element, field);
    try {
      return Sex.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 " + Arrays.toString(Sex.values()) + ": " + text, e);
    }
  }

  /**
   * ★ <b>可选家户 id</b>（Batch 4 的 provisioning 命令共用）：缺席 / JSON {@code null} ⇒ {@code null}（= 全局默认）； 给了
   * ⇒ 必须非空白，再交给 {@link HouseholdId#parse}。
   */
  static HouseholdId optionalHouseholdId(JsonNode payload, String field) {
    String text = optionalNonBlankText(payload, field);
    return text == null ? null : HouseholdId.parse(text);
  }

  /**
   * ★ <b>必填年龄档</b>（Batch 4 的 provisioning 命令共用）：只认 {@link AgeBracket#key()} 的三个稳定拼写 {@code 0-14 |
   * 15-59 | 60+}（大小写一致，不做宽容匹配——key 就是读口拼写）。
   */
  static AgeBracket requireAgeBracket(JsonNode payload, String field) {
    String text = requireText(payload, field);
    for (AgeBracket bracket : AgeBracket.values()) {
      if (bracket.key().equals(text)) {
        return bracket;
      }
    }
    throw new IllegalArgumentException("字段 " + field + " 必须是 0-14|15-59|60+: " + text);
  }

  /** ★ <b>必填商品 id</b>（Batch 4）：非空白裸值交给 {@link CommodityId#parse}（商品词表由 GM/展开显式给行）。 */
  static CommodityId requireCommodity(JsonNode payload, String field) {
    return CommodityId.parse(requireText(payload, field));
  }

  /**
   * ★ <b>可选时间口径</b>（Batch 4 的 {@code social.SetDemandCoefficient.period}）：缺席 / JSON {@code null} ⇒
   * {@code null}（= 与 {@code cycleDays} 一并从全局默认口径推断）；给了只认 {@link DemandPeriod} 的两个稳定枚举名。
   */
  static DemandPeriod optionalDemandPeriod(JsonNode payload, String field) {
    String text = optionalNonBlankText(payload, field);
    if (text == null) {
      return null;
    }
    try {
      return DemandPeriod.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 PER_CYCLE_DAYS|PER_CALENDAR_YEAR: " + text, e);
    }
  }

  /**
   * 必填的 {@code location} 对象（S3a 的家户位置）：两档，形状与 {@link HouseholdLocation} 的线格式同源但更宽容 （命令载荷是 social
   * 模块私事，C26）：
   *
   * <pre>{@code
   * {"type":"HEX","hex":{"q":1,"r":0}}   // type 也接受 "@type"/"hex"（大小写不敏感）
   * {"type":"UNIT","unitId":"gov-central"}  // type 也接受 "@type"/"unit"
   * }</pre>
   *
   * ★ 缺 {@code type} 但给了 {@code hex} / {@code unitId} 也能判出档位（LLM 载荷常见形态）；两档都没有 / 都有 / 形状不符 ⇒ 具名拒。
   */
  static HouseholdLocation requireLocation(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须是 {type:HEX|UNIT, hex|unitId} 对象: " + payload);
    }
    String type = locationType(value, field);
    if (type.equalsIgnoreCase("hex")) {
      JsonNode hex = value.get("hex");
      if (hex == null || !hex.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的 HEX 位置必须有 {q,r} 对象 hex: " + value);
      }
      return new HouseholdLocation.Hex(hexFrom(hex, field));
    }
    if (type.equalsIgnoreCase("unit")) {
      JsonNode unitId = value.get("unitId");
      if (unitId == null || !unitId.isTextual() || unitId.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的 UNIT 位置必须有非空白 unitId: " + value);
      }
      return new HouseholdLocation.Unit(unitId.asText());
    }
    throw new IllegalArgumentException("字段 " + field + " 的 type 只认 HEX|UNIT（大小写不敏感）: " + value);
  }

  /** location 对象的档位文本：{@code type} / {@code @type}，缺省时按 {@code hex}/{@code unitId} 推断。 */
  private static String locationType(JsonNode value, String field) {
    JsonNode typeNode = value.get("type");
    if (typeNode == null || typeNode.isNull()) {
      typeNode = value.get("@type");
    }
    if (typeNode != null && !typeNode.isNull()) {
      if (!typeNode.isTextual() || typeNode.asText().isBlank()) {
        throw new IllegalArgumentException("字段 " + field + " 的 type 必须是非空白字符串: " + value);
      }
      return typeNode.asText();
    }
    boolean hasHex = value.hasNonNull("hex");
    boolean hasUnit = value.hasNonNull("unitId");
    if (hasHex && !hasUnit) {
      return "hex";
    }
    if (hasUnit && !hasHex) {
      return "unit";
    }
    throw new IllegalArgumentException(
        "字段 " + field + " 必须给 type（HEX|UNIT）或二选一的 hex/unitId: " + value);
  }

  /**
   * 可选的出生/死亡率数组（S3a 的 {@code social.SetHouseholdVitalRates.rates}）：缺失或 JSON {@code null} ⇒ 空表 （=
   * 清空率表，全部键回落全局默认）；给了 ⇒ 必须是 {@code
   * [{bracketId,sex,birthRatePerMillionPerTick?,deathRatePerMillionPerTick?}…]}，两个率缺省 0 （0 是"这一档确实按
   * 0 率结算"，不是"没有这一行"；要回落全局默认就别给这个键）。元素形状/数值非负由 {@link HouseholdVitalRate} 构造期判；(bracketId, sex)
   * 重复由率表构造期判。
   */
  static List<HouseholdVitalRate> requireVitalRates(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException(
          "字段 "
              + field
              + " 必须是 [{bracketId,sex,birthRatePerMillionPerTick?,deathRatePerMillionPerTick?}…] 数组: "
              + payload);
    }
    ArrayList<HouseholdVitalRate> rates = new ArrayList<>(value.size());
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("字段 " + field + " 的元素必须是率对象: " + element);
      }
      // ★★ 旧 perMille 口径字段已作废：出现即具名拒——静默当 0 会把"我设了率"变成一句假话。
      if (element.has("birthRatePerMillePerTick") || element.has("deathRatePerMillePerTick")) {
        throw new IllegalArgumentException(
            "字段 "
                + field
                + " 不再接受 perMille/tick 口径（旧档作废）：请改用 birthRatePerMillionPerTick / "
                + "deathRatePerMillionPerTick（ppm/tick）: "
                + element);
      }
      String bracketId = requireText(element, "bracketId");
      Sex sex = requireSex(element, "sex");
      Long birthValue = optionalLong(element, "birthRatePerMillionPerTick");
      Long deathValue = optionalLong(element, "deathRatePerMillionPerTick");
      rates.add(
          new HouseholdVitalRate(
              bracketId,
              sex,
              birthValue == null ? 0L : birthValue,
              deathValue == null ? 0L : deathValue));
    }
    return List.copyOf(rates);
  }

  /** 可选的字符串表（S3a 的 {@code profile.metadata}）：缺失或 JSON {@code null} ⇒ 空表；出现但非对象、或值非字符串 ⇒ 抛。 */
  static Map<String, String> optionalStringMap(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {键:字符串} 对象或 null: " + payload);
    }
    Map<String, String> map = new LinkedHashMap<>();
    value
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode item = entry.getValue();
              if (item == null || !item.isTextual()) {
                throw new IllegalArgumentException("字段 " + field + " 的值必须是字符串: " + entry.getKey());
              }
              map.put(entry.getKey(), item.asText());
            });
    return Collections.unmodifiableMap(map);
  }

  /**
   * 必填的 {@code [{id,q,r,sex,count,ageDays,anchorTick?,household?}…]} 数组 + 可选 {@code
   * households:[{id,q,r,name?,description?}…]} ⇒ **保序**的批次/位置/归户表（R1 的 {@code social.SeedGroups}，
   * S2 作家户归位）。
   *
   * <p>★★ <b>S2 的家户归位语义</b>：{@code PopulationGroup} 已无位置，位置只能来自家户 （{@code
   * SocialData.locationOfLot}）。本层把载荷翻译成三张表：
   *
   * <ul>
   *   <li>{@code groups}：批次本体（与旧载荷逐值一致）；
   *   <li>{@code locations}：逐批次的 {@code {q,r}}（供 handler 派生 hex 家户 id / 校验既有家户位置）；
   *   <li>{@code householdOfLot}：条目可选的 {@code household} 字段（指向 {@code households[]} 里的 id 或已存在家户）。
   * </ul>
   *
   * <p>★ <b>重复 id：后出现者覆盖先出现者，不报错</b>（先出现的那个位置保持）；空数组 ⇒ 抛。
   *
   * <p>★ **形状与类型在本层判**；{@code count}/{@code ageDays} 为负由 {@link PopulationGroup}
   * 构造期守卫拒；`households[]` 的 {@code q}/{@code r} 只表达 {@code HEX} 位置（unit 家户由命令/服务另建，不走本载荷）。
   *
   * <p>★★ <b>旧 {@code stress} 字段已退役</b>（用户 2026-10-09 裁定：生理压力与压力自动传导一起删，旧档作废）： 本层对出现的 {@code
   * stress} 字段具名拒，不静默忽略——静默会让"我设了压力"变成一句假话。
   *
   * @param defaultAnchorTick 载荷没给 {@code anchorTick} 时的缺省（= 世界当前世界日）
   */
  static GroupEntries requireGroupEntries(JsonNode payload, long defaultAnchorTick) {
    Map<HouseholdId, HouseholdDraft> households = new LinkedHashMap<>();
    JsonNode householdNodes = payload.get("households");
    if (householdNodes != null && !householdNodes.isNull()) {
      if (!householdNodes.isArray()) {
        throw new IllegalArgumentException("字段 households 必须是 [{id,q,r,…}…] 数组: " + payload);
      }
      for (JsonNode element : householdNodes) {
        if (!element.isObject()) {
          throw new IllegalArgumentException("字段 households 的元素必须是 {id,q,r,…} 对象: " + element);
        }
        HouseholdId householdId = HouseholdId.parse(requireText(element, "id"));
        HexCoord hex = hexFrom(element, "households");
        String name = optionalText(element, "name");
        String description = optionalText(element, "description");
        HouseholdProfile profile =
            new HouseholdProfile(name == null ? householdId.value() : name, description, Map.of());
        HouseholdDraft previous =
            households.put(householdId, new HouseholdDraft(householdId, hex, profile));
        if (previous != null) {
          throw new IllegalArgumentException("字段 households 的 id 重复: " + householdId);
        }
      }
    }
    JsonNode value = payload.get("entries");
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException(
          "字段 entries 必须是 [{id,q,r,sex,count,ageDays,anchorTick?,household?}…] 数组: " + payload);
    }
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    Map<PeopleLotId, HouseholdId> householdOfLot = new LinkedHashMap<>();
    for (JsonNode element : value) {
      if (!element.isObject()) {
        throw new IllegalArgumentException(
            "字段 entries 的元素必须是 {id,q,r,sex,count,ageDays,…} 对象: " + element);
      }
      PeopleLotId id = PeopleLotId.parse(requireText(element, "id"));
      HexCoord residence = hexFrom(element, "entries");
      Sex sex = sexFrom(element);
      long count = requireLong(element, "count");
      long ageDays = requireLong(element, "ageDays");
      Long anchorTick = optionalLong(element, "anchorTick");
      // ★★ 旧 stress 字段已退役：出现即具名拒（不静默忽略，见方法注）。
      JsonNode stressNode = element.get("stress");
      if (stressNode != null && !stressNode.isNull()) {
        throw new IllegalArgumentException(
            "字段 entries 不再接受 stress（用户 2026-10-09 裁定：生理压力与压力自动传导一起删，旧档作废）: " + element);
      }
      String householdText = optionalText(element, "household");
      // ★ 域不变量（count/ageDays/anchorTick 非负）由 PopulationGroup 的构造期守卫抛，本层不重复实现。
      groups.put(
          id,
          new PopulationGroup(
              id, sex, count, ageDays, anchorTick == null ? defaultAnchorTick : anchorTick));
      locations.put(id, residence);
      if (householdText != null) {
        HouseholdId householdId = HouseholdId.parse(householdText);
        householdOfLot.put(id, householdId);
        if (!households.containsKey(householdId)) {
          // 指向已存在家户也合法（handler 会校验存在性）；这里只保证"引用有名字的东西"。
          households.putIfAbsent(householdId, new HouseholdDraft(householdId, residence, null));
        } else {
          HouseholdDraft draft = households.get(householdId);
          if (draft.profile() == null && !draft.hex().equals(residence)) {
            throw new IllegalArgumentException(
                "批次 "
                    + id
                    + " 的落点 "
                    + residence
                    + " 与家户 "
                    + householdId
                    + " 的声明落点 "
                    + draft.hex()
                    + " 不符");
          }
        }
      }
    }
    if (groups.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    return new GroupEntries(groups, locations, householdOfLot, households);
  }

  /** {@code social.SeedGroups} 载荷解析结果：批次 + 落点 + 归户 + 声明家户（见 {@link #requireGroupEntries}）。 */
  record GroupEntries(
      Map<PeopleLotId, PopulationGroup> groups,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdOfLot,
      Map<HouseholdId, HouseholdDraft> households) {

    GroupEntries {
      groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
      locations = Collections.unmodifiableMap(new LinkedHashMap<>(locations));
      householdOfLot = Collections.unmodifiableMap(new LinkedHashMap<>(householdOfLot));
      households = Collections.unmodifiableMap(new LinkedHashMap<>(households));
    }
  }

  /** 载荷里声明/引用的家户：id + HEX 落点 + 可选画像（{@code null} = 已存在家户的引用，不覆盖画像）。 */
  record HouseholdDraft(HouseholdId id, HexCoord hex, HouseholdProfile profile) {}

  /**
   * ★★ **拒收已退役的 {@code population} 字段**（R1 / T5）：城市的城镇人口不再是 {@link SocialCity} 的字段、也不再由
   * 建城/改城命令写入（它是**派生量** = 该城名下各批次之和）。
   *
   * <p>★ 为什么**明令拒**而不是静默忽略：静默忽略会让"我改了人口"变成一句**看起来成功**的假话——本仓最忌的一族。 拒因里直接指路"人口的真值源是批次"。
   *
   * @param type 命令类型（进拒因，便于调用方定位）
   */
  static void rejectRetiredPopulation(JsonNode payload, String type) {
    JsonNode value = payload.get("population");
    if (value != null && !value.isNull()) {
      throw new IllegalArgumentException(
          type
              + " 不再接受 population 字段（R1：城的城镇人口是派生量 = 该城名下各批次之和；"
              + "改人口请改批次：social.SeedGroups 的 id = urban:<cityId>:<SEX>:<细分>）");
    }
  }

  /** 必填的 {@code sex} 字段：只认词表里那两个名字（大小写一致，不做宽容匹配——见 {@link Sex} 的线格式约定）。 */
  /** 保留旧私有名（SeedGroups 的调用点不动）：语义与 {@link #requireSex} 同。 */
  private static Sex sexFrom(JsonNode element) {
    return requireSex(element, "sex");
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

  /** 必填的 {@code reason} 字段：非空白（家户命令共用；事件/日志要能回答为什么）。 */
  static String requireReason(JsonNode payload) {
    String reason = requireText(payload, "reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("字段 reason 不得为空白（事件/日志要能回答为什么）");
    }
    return reason;
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
