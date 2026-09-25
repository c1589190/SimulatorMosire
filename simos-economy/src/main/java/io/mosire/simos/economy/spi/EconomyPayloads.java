package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code economy.Seed} 命令的载荷解析助手（与 {@code SocialPayloads} / {@code MapPayloads} 同制）。
 *
 * <p>★ **载荷形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串。形态如下（**与 §3 的 record 字段一一对应**）：
 *
 * <pre>{@code
 * {"mapId":"Map1","rulesVersion":"aggregate-v1","entries":[
 *   {"q":0,"r":0,"industries":[
 *     {"id":"farm@0_0","name":"农业","regime":"feudal","cycleDays":120,"progressDays":0,
 *      "dailyInputPerUnit":{},"dailyLaborPerUnit":0,"outputPerUnit":{"grain":67},
 *      "cycleInputPerUnit":{"LAND":1200},"cycleSeedUsedMilli":0,
 *      "cycleLaborMilli":0,
 *      "allocation":{"@class":"split","meansWeightPerMille":700,"laborWeightPerMille":300},
 *      "slots":[{"id":"peasant","name":"贫农","laborParticipationPerMille":950}],
 *      "classes":[{"slot":"peasant","population":450,"laborMilli":261000,
 *                  "participationPerMille":950,"meansOfProduction":{"LAND":450000},
 *                  "goods":{"grain":2241000},"money":0,"debts":[],
 *                  "naturalNeeds":{"grain":37350},"effectiveDemand":{}}]}]}]}
 * }</pre>
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世**（带可读中文原因）：形状/类型不对在本层判，**数值语义**（人口/土地/劳动 ≥
 * 0、槽位必须在该产业的 {@code slots} 里、{@code progressDays ≤ cycleDays}）交给 §3 的领域类型与 {@link EconomyData}
 * 构造期守卫——**不重复实现**，一处真相。
 *
 * <p>★ **{@code debts} 本轮只接受空数组**（§十 明确"不做债务"）：给出非空债务 ⇒ 拒，免得落下一批指向空债务表的悬空引用。
 */
final class EconomyPayloads {

  /** 本类唯一的一台 mapper（共享基座出厂配置；{@code AllocationRule} 的多态注解跟着类型走，不依赖 mixin）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private EconomyPayloads() {}

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

  /**
   * 载荷里逐格的可寻址路径（{@code <q>_<r>}，{@link io.mosire.simos.util.spi.ResourcePaths#economy(int, int)}
   * 的形态） ——{@code CommandTargets} 用它，**不建完整记录**（目标声明只关心"动谁"）。
   */
  static List<String> entryHexKeys(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<String> keys = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      keys.add(requireInt(entry, "q") + "_" + requireInt(entry, "r"));
    }
    return List.copyOf(keys);
  }

  /**
   * 把载荷整份 materialize 成 {@link EconomyData}（含激活元信息）。
   *
   * @param at 世界当前时刻（{@code activatedDay} 取它的 {@link SimosTimestamp#tick()}）
   */
  static EconomyData toData(JsonNode payload, SimosTimestamp at) {
    Objects.requireNonNull(at, "at");
    String mapId = requireText(payload, "mapId");
    String rulesVersion = requireText(payload, "rulesVersion");
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      requireInt(entry, "q");
      requireInt(entry, "r");
      for (JsonNode node : requireArray(entry, "industries")) {
        Industry industry = industry(node);
        IndustryId id = industry.id();
        if (industries.putIfAbsent(id, industry) != null) {
          throw new IllegalArgumentException("同一份载荷里产业 id 重复: " + id);
        }
        for (JsonNode row : optionalArray(node, "classes")) {
          ClassRow classRow = classRow(id, row);
          if (classes.putIfAbsent(classRow.key(), classRow) != null) {
            throw new IllegalArgumentException("同一份载荷里阶层行重复: " + classRow.key());
          }
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(mapId, at.tick(), OptionalLong.empty(), rulesVersion, Optional.empty());
    return new EconomyData(Optional.of(meta), industries, classes, Map.of(), Map.of());
  }

  // ── 产业 / 阶层行 ────────────────────────────────────────────────────────────────────

  private static Industry industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    long cycleDays = requireLong(node, "cycleDays");
    long progressDays = optionalLong(node, "progressDays", 0L);
    Map<AssetKind, Long> dailyInput =
        assetMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    // ★ v2 spec §3.3：每单位生产资料**每周期一次性**投入（v1 只有 LAND = 每亩需种，单位毫粮/亩）。
    //   缺键 ⇒ 空 map（旧载荷兼容；六种 AssetKind 都收，v1 只读 LAND）。
    Map<AssetKind, Long> cycleInput =
        assetMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    // ★ 本周期实际扣到的种子（毫粮）累加器；缺键 ⇒ 0（旧载荷兼容）。负值由 Industry 的构造期守卫拒。
    long cycleSeedUsed = optionalLong(node, "cycleSeedUsedMilli", 0L);
    // ★ R3a：周期累计实际劳动（缺键 ⇒ 0，旧载荷兼容：生成器不写它时按"新周期、尚未投入"）。
    long cycleLabor = optionalLong(node, "cycleLaborMilli", 0L);
    List<ClassSlot> slots = new ArrayList<>();
    for (JsonNode slot : requireArray(node, "slots")) {
      slots.add(
          new ClassSlot(
              ClassSlotId.parse(requireText(slot, "id")),
              requireText(slot, "name"),
              requireInt(slot, "laborParticipationPerMille")));
    }
    JsonNode allocation = node.get("allocation");
    if (allocation == null || !allocation.isObject()) {
      throw new IllegalArgumentException("字段 allocation 必须是对象: " + node);
    }
    AllocationRule rule;
    try {
      rule = MAPPER.convertValue(allocation, AllocationRule.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("allocation 形状不对（见 AllocationRule）: " + allocation, e);
    }
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（小农/封建租佃/手工业）。
    //   WageFirst（资本主义工业）是后续增量 —— 必须在**播种期**拒，而不是等某个收获日
    //   在 EconomySettlement.harvest 里抛 UnsupportedOperationException：那个异常会穿出
    //   EconomyTimeParticipant.simulateWorld，让整条 AdvanceTime revision 失败。
    //   ★ 为什么拒在这里而不是 Industry 构造期：构造期拒会让 WageFirst 这个**状态形状**
    //     （spec §五 的第四种制度）变得不可表达，连带 EconomyCodecTest 的 wage_first 多态
    //     往返夹具无法构造 ⇒ 丢一条 JSON 分支的覆盖。播种期拒已堵住命令路径，且不砍覆盖。
    if (!(rule instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException("v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + rule);
    }
    return new Industry(
        id,
        name,
        regime,
        cycleDays,
        progressDays,
        dailyInput,
        dailyLabor,
        output,
        cycleInput,
        slots,
        rule,
        cycleLabor,
        cycleSeedUsed);
  }

  private static ClassRow classRow(IndustryId industry, JsonNode node) {
    ClassSlotId slot = ClassSlotId.parse(requireText(node, "slot"));
    long population = requireLong(node, "population");
    long laborMilli = requireLong(node, "laborMilli");
    int participation = requireInt(node, "participationPerMille");
    Map<AssetKind, Long> means =
        assetMap(optionalObject(node, "meansOfProduction"), "meansOfProduction");
    Map<CommodityId, Long> goods = commodityMap(optionalObject(node, "goods"), "goods");
    long money = optionalLong(node, "money", 0L);
    List<DebtId> debts = new ArrayList<>();
    for (JsonNode debt : optionalArray(node, "debts")) {
      // ★ §十：本轮"不做债务"⇒ 只接受空数组（拒绝非空，免得落下一批指向空债务表的悬空引用）。
      throw new IllegalArgumentException("本轮不支持债务（debts 只接受空数组）: " + debt);
    }
    Map<CommodityId, Long> needs =
        commodityMap(optionalObject(node, "naturalNeeds"), "naturalNeeds");
    Map<CommodityId, Long> demand =
        commodityMap(optionalObject(node, "effectiveDemand"), "effectiveDemand");
    return new ClassRow(
        new ClassKey(industry, slot),
        population,
        laborMilli,
        participation,
        means,
        goods,
        money,
        debts,
        needs,
        demand);
  }

  /** 键是 {@link AssetKind} 名的定点整数表（值非整/键不认识 ⇒ 抛）。 */
  private static Map<AssetKind, Long> assetMap(JsonNode object, String field) {
    Map<AssetKind, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              AssetKind kind;
              try {
                kind = AssetKind.valueOf(entry.getKey());
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "字段 " + field + " 的键不是生产资料种类: " + entry.getKey());
              }
              out.put(kind, requireIntegral(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

  /** 键是商品 id 的定点整数表（值非整 ⇒ 抛）。 */
  private static Map<CommodityId, Long> commodityMap(JsonNode object, String field) {
    Map<CommodityId, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry ->
                out.put(
                    CommodityId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), field + "." + entry.getKey())));
    return out;
  }

  // ── 形状助手 ─────────────────────────────────────────────────────────────────────────

  private static void requireEntryObject(JsonNode entry) {
    if (entry == null || !entry.isObject()) {
      throw new IllegalArgumentException("字段 entries 的元素必须是 {q,r,industries} 对象: " + entry);
    }
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return value.asText();
  }

  private static long requireLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return requireIntegral(value, field);
  }

  private static int requireInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  private static long optionalLong(JsonNode node, String field, long fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return requireIntegral(value, field);
  }

  private static long requireIntegral(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数");
    }
    return value.asLong();
  }

  private static JsonNode requireArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  private static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  private static JsonNode optionalObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
    return value;
  }
}
