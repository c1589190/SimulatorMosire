package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
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
 *      "capacityPerUnit":{"LAND":1000},"laborPerUnit":143,
 *      "dailyInputPerUnit":{},"dailyLaborPerUnit":0,"outputPerUnit":{"grain":67,"fiber":12},
 *      "cycleInputPerUnit":{"LAND":{"grain":8000}},"cycleInputUsedMilli":{},
 *      "cycleLaborMilli":0,
 *      "allocation":{"@class":"split","meansWeightPerMille":700,"laborWeightPerMille":300},
 *      "slots":[{"id":"poor_peasant","name":"贫农","laborParticipationPerMille":950}],
 *      "classes":[{"slot":"poor_peasant","population":450,"laborMilli":261000,
 *                  "participationPerMille":950,"meansOfProduction":{"LAND":450000},
 *                  "goods":{"grain":2241000},"money":0,"debts":[],
 *                  "naturalNeeds":{"grain":37350},"effectiveDemand":{}}]}],
 *    "laborSupply":[{"group":"rural:0_0:MALE:1","period":1,"grossLaborMilli":261000,
 *                    "servedLaborMilli":0,"committedLaborMilli":0}],
 *    "allocations":[{"id":"alloc-0-farm@0_0","group":"rural:0_0:MALE:1",
 *                    "actor":{"kind":"ESTATE","id":"farm@0_0"},"activity":"farm",
 *                    "laborMilli":261000,"period":1}]}]}
 * }</pre>
 *
 * <p>★★ **R3（V7）的两处形状变化**（spec §五）：
 *
 * <ul>
 *   <li>新增 {@code capacityPerUnit}（每 1 单位规模需要多少生产资料）与 {@code laborPerUnit}（每 1 单位规模需要多少劳动） —— 它们与
 *       {@code inputPerUnit}（由 {@code cycleInputPerUnit} 合计而来，**不进载荷**）和 {@code outputPerUnit} 一起构成
 *       {@code Industry.recipe()} 的四个分量；
 *   <li>两个投入表的**值侧带上商品维度**：{@code "cycleInputPerUnit":{"LAND":8000}} ⇒ {@code
 *       "cycleInputPerUnit":{"LAND":{"grain":8000}}}（"消耗 IRON"这种话原来表达不了）； {@code
 *       "cycleSeedUsedMilli":0} ⇒ {@code "cycleInputUsedMilli":{}}（按商品的累加器）。
 * </ul>
 *
 * <p>★ **旧档兼容不在本轮范围**（spec §十.4 的裁定："旧档：重建也没关系"）：随包的 {@code worlds/v17levant.json} **不含 economy
 * 切片**（只有 map/social/unit），故这两处形状变化不影响它能否打开。
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
    // ★ R2 的两张新表：**逐格**声明（格是命令目标与权限的粒度：一条命令动的是这些格）。
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
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
      // ★ R2：该格各批次的劳动供给（可支配劳动的上限）—— 缺省 ⇒ 空表（与 classes 同款）。
      //   ★ 空表**不是静默兜底**：没有供给记录的批次就不可能有配额（EconomyData 的构造期守卫按月判），
      //     而没有配额的产业当日劳动为 0 —— 那是新口径的直接后果（劳动是**分配**来的），不是"忘了算"。
      for (JsonNode node : optionalArray(entry, "laborSupply")) {
        LaborSupply supply = laborSupply(node);
        if (laborSupply.putIfAbsent(supply.group(), supply) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动供给重复: " + supply.group());
        }
      }
      // ★ R2：该格各批次的劳动配额（谁把多少劳动给了谁）。
      for (JsonNode node : optionalArray(entry, "allocations")) {
        LaborAllocation allocation = allocation(node);
        if (allocations.putIfAbsent(allocation.id(), allocation) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动分配重复: " + allocation.id());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(mapId, at.tick(), OptionalLong.empty(), rulesVersion, Optional.empty());
    return new EconomyData(
        Optional.of(meta), industries, classes, Map.of(), Map.of(), laborSupply, allocations);
  }

  // ── 劳动供给 / 劳动分配（R2，设计稿 §四）──────────────────────────────────────────────

  /**
   * 一份劳动供给：{@code {group, period, grossLaborMilli, servedLaborMilli?, committedLaborMilli?}}。
   *
   * <p>★ **毛额由载荷给**（不由本层从人口算）：人口与年龄性别住在 social 的 {@code PopulationGroup}，economy 编译期不认识它 （设计稿
   * §二/§八.1）—— 算出毛额的是 {@code EconomySeeder}（它以 R1 已落地的年龄×性别系数表折算）。
   */
  private static LaborSupply laborSupply(JsonNode node) {
    return new LaborSupply(
        PeopleLotId.parse(requireText(node, "group")),
        requireLong(node, "period"),
        requireLong(node, "grossLaborMilli"),
        // ★ 两项扣除缺省 0（本轮的形态）：它们**不是可有可无**的字段，只是值为 0 ——
        //   LaborSupply 照减（见其类注），故载荷给非 0 时逐值生效。
        optionalLong(node, "servedLaborMilli", 0L),
        optionalLong(node, "committedLaborMilli", 0L));
  }

  /**
   * 一条劳动配额：{@code {id, group, actor:{kind,id}, activity, laborMilli, period}}。
   *
   * <p>★ {@code actor.kind} 走 {@link ActorKind#parse} 的**词表**（词表外的种类即抛并列出合法值）； {@code actor.id}
   * 与产业的对应关系由 {@code EconomyData} 的构造期守卫判死（不在这里重复实现）。
   */
  private static LaborAllocation allocation(JsonNode node) {
    JsonNode actor = optionalObject(node, "actor");
    if (actor == null) {
      throw new IllegalArgumentException("劳动分配的字段 actor 必须是对象: " + node);
    }
    return new LaborAllocation(
        LaborAllocationId.parse(requireText(node, "id")),
        PeopleLotId.parse(requireText(node, "group")),
        new ActorRef(ActorKind.parse(requireText(actor, "kind")), requireText(actor, "id")),
        requireText(node, "activity"),
        requireLong(node, "laborMilli"),
        requireLong(node, "period"));
  }

  // ── 产业 / 阶层行 ────────────────────────────────────────────────────────────────────

  private static Industry industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    long cycleDays = requireLong(node, "cycleDays");
    long progressDays = optionalLong(node, "progressDays", 0L);
    // ★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    //   ★ capacityPerUnit **必填**（它是"单位规模"的锚，没有它规模无上界）；缺键 ⇒ 空表 ⇒ 由 Industry 的构造期守卫拒。
    Map<AssetKind, Long> capacity =
        assetMap(optionalObject(node, "capacityPerUnit"), "capacityPerUnit");
    long laborPerUnit = optionalLong(node, "laborPerUnit", 0L);
    Map<AssetKind, Map<CommodityId, Long>> dailyInput =
        assetCommodityMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    // ★ v2 spec §3.3：每单位生产资料**每周期一次性**投入（农业 = 每亩需种，单位毫粮/亩）。
    //   ★ R3 换型：值侧带上商品维度（{"LAND":{"grain":8000}}）⇒ 表达得了"消耗 IRON"。
    Map<AssetKind, Map<CommodityId, Long>> cycleInput =
        assetCommodityMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    // ★ 本周期实际扣到的投入（**按商品**的累加器）；缺键 ⇒ 空表。负值由 Industry 的构造期守卫拒。
    Map<CommodityId, Long> cycleInputUsed =
        commodityMap(optionalObject(node, "cycleInputUsedMilli"), "cycleInputUsedMilli");
    // ★ R3a：周期累计实际劳动（缺键 ⇒ 0，旧载荷兼容：生成器不写它时按"新周期、尚未投入"）。
    long cycleLabor = optionalLong(node, "cycleLaborMilli", 0L);
    List<ClassSlot> slots = new ArrayList<>();
    for (JsonNode slot : requireArray(node, "slots")) {
      slots.add(
          new ClassSlot(
              SocialClassId.parse(requireText(slot, "id")),
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
        capacity,
        dailyInput,
        dailyLabor,
        laborPerUnit,
        output,
        cycleInput,
        slots,
        rule,
        cycleLabor,
        cycleInputUsed);
  }

  private static ClassRow classRow(IndustryId industry, JsonNode node) {
    SocialClassId slot = SocialClassId.parse(requireText(node, "slot"));
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

  /**
   * 键是 {@link AssetKind} 名、**值本身又是商品表的表**（R3 换型的那两张投入表）：{@code {"LAND":{"grain":8000}}}。
   *
   * <p>★ 三条拒因各自给出可读的中文原因：键不认识生产资料种类、值不是对象、内层值不是整数 —— 都由本层判， 数值语义（≥ 0）交给 {@code Industry}
   * 的构造期守卫（一处真相）。
   */
  private static Map<AssetKind, Map<CommodityId, Long>> assetCommodityMap(
      JsonNode object, String field) {
    Map<AssetKind, Map<CommodityId, Long>> out = new LinkedHashMap<>();
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
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "." + entry.getKey() + " 必须是商品表（对象）: " + entry.getValue());
              }
              out.put(kind, commodityMap(entry.getValue(), field + "." + entry.getKey()));
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
