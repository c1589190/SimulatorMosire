package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** 载荷形状/词表/商品表的纯解析助手（P0.5-C2；不读写状态）。 */
final class EconomyPayloadSupport {

  /** 本包唯一的载荷 mapper（共享基座出厂配置；{@code AllocationRule} 的多态注解跟着类型走，不依赖 mixin）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  static ObjectMapper mapper() {
    return MAPPER;
  }

  static Map<AssetKind, Long> assetMap(JsonNode object, String field) {
    Map<AssetKind, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              AssetKind kind = assetKind(entry.getKey(), field);
              out.put(kind, requireIntegral(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

  /**
   * 键是 {@link AssetKind} 名、**值本身又是商品表的表**（R3 换型的那两张投入表）：{@code {"LAND":{"grain":8000}}}。
   *
   * <p>★ 三条拒因各自给出可读的中文原因：键不认识生产资料种类、值不是对象、内层值不是整数 —— 都由本层判， 数值语义（≥ 0）交给 {@code
   * IndustryDefinitionView} 的构造期守卫（一处真相）。
   */
  static Map<AssetKind, Map<CommodityId, Long>> assetCommodityMap(JsonNode object, String field) {
    Map<AssetKind, Map<CommodityId, Long>> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              AssetKind kind = assetKind(entry.getKey(), field);
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "." + entry.getKey() + " 必须是商品表（对象）: " + entry.getValue());
              }
              out.put(kind, commodityMap(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

  /** 键是商品 id 的定点整数表（值非整 ⇒ 抛）。 */
  static Map<CommodityId, Long> commodityMap(JsonNode object, String field) {
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

  /**
   * ★★ <b>W5：铸币产出率表</b>（{@code {币种: 毫/规模·周期}}）—— 与 {@link #commodityMap} <b>同形但键类型不同</b>。
   *
   * <p>★ 形状守卫只判"能不能解析成 {@code CurrencyId} + 值是不是整数"；<b>逐值必须 &gt; 0</b> 与冻结由 {@link
   * io.mosire.simos.economy.model.MoneyOutput} 的构造期守卫判（同一处口径，不在这里写第二遍）。
   */
  static Map<CurrencyId, Long> currencyMap(JsonNode object, String field) {
    Map<CurrencyId, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry ->
                out.put(
                    CurrencyId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), field + "." + entry.getKey())));
    return out;
  }

  // ── E3：政府与货币发行审计的载荷解析 ─────────────────────────────────────────────────

  // ── E4c：债务合同的载荷解析 ───────────────────────────────────────────────────

  /**
   * ★★ <b>E4c：顶层可选 {@code debtContracts} 数组（放开非空）</b>。
   *
   * <pre>
   * {"debtContracts":[
   *   {"debtor":"h-0_0-rural-poor_peasant","creditor":"h-0_0-rural-landlord",
   *    "unit":{"kind":"money","currency":"silver"},
   *    "terms":{"interestRatePerMillePerCycle":20,"interestTiming":"AFTER_REPAYMENT_ON_CLOSE",
   *             "repaymentRule":"AVAILABLE_SURPLUS_SHARE","monetaryConversion":"NOT_ALLOWED",
   *             "defaultRemedy":"MARK_DEFAULTED"},
   *    "source":"ORDINARY_LOAN",
   *    "principal":1000,"openedDay":0,"lastInterestDay":null,"dueCycle":1,"status":"NORMAL"}]}
   * </pre>
   *
   * <ul>
   *   <li>{@code id} 可省：由 {@code (debtor, creditor, unit, terms)} 经 {@link DebtContractId#idOf} 派生；
   *       给了就必须与派生值逐字相同（否则构造期守卫拒绝，不许手写第二份身份）；
   *   <li>★★ {@code terms} <b>必填</b>（不是"省略 ⇒ legacyDefault"）：种子必须把 {@code
   *       interestRatePerMillePerCycle / interestTiming / repaymentRule / monetaryConversion /
   *       defaultRemedy} 五个键给全 —— 缺键 fail-closed，不把漏写静默补成默认档（资本化路径的 `legacyDefault` 只在结算层显式使用，并由
   *       {@code termsSource} 标注）；
   *   <li>★★ {@code source} <b>必填</b>：普通贷款 seed 显式写 {@code ORDINARY_LOAN}；缺键 ⇒ {@code
   *       debt-source-missing}，不得静默给 {@code LEGACY_UNKNOWN} 或工资债；
   *   <li>{@code openedDay} 缺省 = 0；{@code lastInterestDay}/{@code dueCycle} 缺省 = 空；{@code status}
   *       缺省 = NORMAL；
   *   <li>单位：P2c 起只接受 {@code {"kind":"money","currency":"silver"}}；{@code kind=commodity}
   *       稳定拒绝（商品欠款须先按活动市场区价格折货币）。
   * </ul>
   *
   * <p>★★ <b>结构/引用/条款校验不在这里重复实现</b>：{@link DebtContract} 与 {@code EconomyData} 的构造期守卫会判 id
   * 派生、debtor/creditor 行存在、{@code HouseholdEconomy.debts} 引用存在。
   *
   * <p>★★ <b>初始债务的配套责任（必须写清）</b>：本方法只把债权记进 economy 状态；它<b>不搬任何 actor 库存/货币/权利</b>。 如果 seed 声明"H1 欠
   * H2 1000 粮"，app 侧的 actor.Seed 协调器必须已经在 H1/H2 的账户里备好对应的真实粮/钱 （否则这条债没有对价，是凭空造出的债权名册）。economy 不自动搬
   * actor 库存，也不为演示凭空补配套。 /** ★ E5a：{@code crisisSignals[].evidence} —— 可选对象；键非空白、值为 long（可为负）。缺键 ⇒
   * 空表。
   */
  static Map<String, Long> evidence(JsonNode node) {
    JsonNode evidence = optionalObject(node, "evidence");
    Map<String, Long> values = new LinkedHashMap<>();
    if (evidence == null) {
      return values;
    }
    evidence
        .fields()
        .forEachRemaining(
            field -> {
              if (field.getKey() == null || field.getKey().isBlank()) {
                throw new IllegalArgumentException("crisisSignals[].evidence 的键不得空白: " + evidence);
              }
              values.put(
                  field.getKey(), requireIntegral(field.getValue(), "evidence." + field.getKey()));
            });
    return values;
  }

  /** ★ E4c：可空整数键（缺席/null ⇒ 空；给了非整数 ⇒ 具名抛）。 */
  static OptionalLong optionalOptionalLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return OptionalLong.empty();
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数或 null: " + value);
    }
    return OptionalLong.of(value.asLong());
  }

  /** {@code AssetKind} 词表解析的唯一入口（旧 {@code TOOL} ⇒ {@code asset-kind-retired:TOOL}）。 */
  static AssetKind assetKind(String text, String field) {
    try {
      return AssetKind.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "字段 " + field + " 不是生产资料种类: " + text + "；" + e.getMessage(), e);
    }
  }

  /** ★ E4c：枚举词表解析（未知值 ⇒ 列出合法值，不 silent fallback）。 */
  static <E extends Enum<E>> E enumValue(Class<E> type, String text, String field) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知的 "
              + field
              + ": "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(type.getEnumConstants()),
          e);
    }
  }

  static JsonNode requireObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
    return value;
  }

  // ── 形状助手 ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code {"kind","id"}}：一个主体引用（{@code allocations[].actor} 与 {@code industries[].operator}
   * 共用**同一个**形状与同一套解析）。
   *
   * <p>★ {@code kind} 走 {@link ActorKind#parse} 的**词表**（词表外的种类即抛并列出合法值）、{@code id} 不得为空白 ——
   * 两句拒因的文案一字不改（消息是契约），故抽的是**解析**、不是文案。
   */
  static ActorRef actorRef(JsonNode node) {
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }

  static void requireEntryObject(JsonNode entry) {
    if (entry == null || !entry.isObject()) {
      throw new IllegalArgumentException("字段 entries 的元素必须是 {q,r,industries} 对象: " + entry);
    }
  }

  static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return value.asText();
  }

  static long requireLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return requireIntegral(value, field);
  }

  static int requireInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  /** 布尔键（三个字段必填）：缺键 / null / 非布尔 ⇒ 抛（不把漏写静默补成 false）。 */
  static boolean requireBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔值: " + node);
    }
    return value.asBoolean();
  }

  static long optionalLong(JsonNode node, String field, long fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return requireIntegral(value, field);
  }

  static int optionalInt(JsonNode node, String field, int fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  /**
   * 可选的文本键：缺键 / JSON {@code null} ⇒ {@link Optional#empty()}（"空"的唯二形态）。
   *
   * <p>★ <b>给了但形状不对（非文本 / 空白）⇒ 抛</b>：与 {@link #optionalObject} 同款 —— "可选"说的是**可以不给**，
   * 不是"给了什么都收"。静默把它当"没给"会让一个写错的键看起来像合法的货币规则。
   */
  static Optional<String> optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return Optional.of(value.asText());
  }

  static long requireIntegral(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数");
    }
    return value.asLong();
  }

  static JsonNode requireArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  static JsonNode optionalObject(JsonNode node, String field) {
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
