package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxMode;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovBudgetPolicyEditMode;
import io.mosire.simos.gov.GovOfficialSalaryRule;
import io.mosire.simos.gov.GovPortPolicy;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * gov 各命令 handler 共用的载荷解析助手（Z2，spec §5 / 设计书 §4.1）。
 *
 * <p>★ <b>坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因</b>；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。域规则违反由 {@link
 * GovAdministrationPlan} / {@link GovBudgetPolicy} 构造期抛出的同类异常沿用同一条路径——折算只发生在命令边界这一层。
 *
 * <p>★ <b>本类只管形状与类型</b>（字段在不在、类型对不对、缺省值）；数值范围（≥0、恰 3 档、类别不重复、min ≤ cap）留给领域 record， 两处不重复实现。
 *
 * <p>★ <b>缺省口径</b>：编制计划除 {@code unitId} 外全部可缺省（计划量 0、默认 3 档、修正 1000‰、{@code k=1}）； 预算政策分两模（★
 * Z7e-3）：{@link GovBudgetPolicyEditMode#PATCH}（缺省）的缺省字段 = <b>保留现值</b>（键不存在时 = 空表 / 0/0 / 0），{@link
 * GovBudgetPolicyEditMode#REPLACE} 的缺省字段 = 空表 / 0/0 / 0（旧整表替换）。<b>缺省值只在这里展开一次</b>，领域 record 不猜。
 *
 * <p>★ <b>{@code unitId} 是“要写给哪个 GOV”</b>：它是 {@code GovState} 两条源状态表的键，不属于计划/政策 record 的内容（设计书 §4.1
 * 的键）。
 */
final class GovPayloads {

  /** 本类唯一的一台 mapper：共享基座出厂配置，不认识任何领域类型（载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private GovPayloads() {}

  /**
   * ★ <b>日志安全的拒绝理由</b>：校验消息为方便调用方排查会回显字段值/整段载荷，但日志纪律禁止载荷明文与 JSON 原文。这里只保留可读前缀 ——截到第一个 JSON
   * 起始符/换行；{@code payload ...} 这一类原始文本消息再截到冒号。截断只影响日志文本，不影响异常本身，也不改 {@code Rejected} 的理由。
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
      throw new IllegalArgumentException("payload 必须是 JSON 对象");
    }
    return payload;
  }

  /** 三维编制计划载荷：{@code unitId} 单独由 handler 取；其余字段可缺省（见类注）。★ R2 起多口岸一维。 */
  static GovAdministrationPlan administrationPlan(JsonNode payload) {
    long securityPlannedLaborMilli = optionalLong(payload, "securityPlannedLaborMilli", 0L);
    long paperworkPlannedLaborMilli = optionalLong(payload, "paperworkPlannedLaborMilli", 0L);
    // ★ R2：口岸维计划量缺省 0（= 没设口岸编制；旧载荷逐字不动即得旧语义）。
    long portPlannedLaborMilli = optionalLong(payload, "portPlannedLaborMilli", 0L);
    List<GovPostTier> postTiers =
        optionalPostTiers(payload).orElse(GovAdministrationPlan.DEFAULT_POST_TIERS);
    long securitySupplyStatic =
        optionalLong(
            payload,
            "securitySupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long paperworkSupplyStatic =
        optionalLong(
            payload,
            "paperworkSupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long securityDemandStatic =
        optionalLong(
            payload,
            "securityDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long paperworkDemandStatic =
        optionalLong(
            payload,
            "paperworkDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    // ★ R2：口岸维两个静态修正的缺省 = 中性 1000‰（照另两维形制）。
    long portSupplyStatic =
        optionalLong(
            payload,
            "portSupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long portDemandStatic =
        optionalLong(
            payload,
            "portDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long supernumerarySqrtCoefficient =
        optionalLong(
            payload,
            "supernumerarySqrtCoefficient",
            GovAdministrationPlan.DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
    return new GovAdministrationPlan(
        securityPlannedLaborMilli,
        paperworkPlannedLaborMilli,
        portPlannedLaborMilli,
        postTiers,
        securitySupplyStatic,
        paperworkSupplyStatic,
        portSupplyStatic,
        securityDemandStatic,
        paperworkDemandStatic,
        portDemandStatic,
        supernumerarySqrtCoefficient);
  }

  /**
   * ★★ <b>P-T1a：口岸管制政策载荷</b>（{@code gov.SetPortPolicy}）——<b>整表替换</b>（照 {@code
   * SetAdministrationPlan} 的形制：同类型重复设置 = 整体替换；与既有政策逐值相同 ⇒ 空变更集）。
   *
   * <pre>{@code
   * {"unitId":"gov-1",
   *  "commodityRules":{
   *    "grain":{"entryRestrictionPerMille":1000,"exitRestrictionPerMille":250,
   *             "entryTax":{"mode":"per_unit_milli","amount":5},
   *             "exitTax":{"mode":"ad_valorem_per_mille","amount":100}},
   *    "cloth":{"exitRestrictionPerMille":0}},
   *  "currencyRules":{
   *    "silver":{"lending":{"entryRestrictionPerMille":1000},
   *              "commodity":{"exitRestrictionPerMille":250,
   *                           "exitTax":{"mode":"ad_valorem_per_mille","amount":50}}}}}
   * }</pre>
   *
   * <p>★★ <b>币种表多一层"挂单类型"键</b>（P-T1e；设计书 §14.3-4）：{@code currencyRules} 的值是 {@code {挂单类型字面量 →
   * 规则对象}}（词表 = {@code exchange|commodity|lending}，见 {@link
   * io.mosire.simos.economy.api.market.MarketOrderKind}）—— 只有一个"币种 → 规则"的键<b>表达不出</b>用户给的规则
   * "禁止本市场区货币被外国借贷"（借贷走的也是市场挂单）。<b>类型键不认识/值不是对象 ⇒ 具名拒</b>（不静默当"该类没规则"）。
   *
   * <p>★ <b>每类四个数</b>（2026-10-10 冻结口径 T-5）：入口限制‰ / 出口限制‰ / 入口税 / 出口税 —— 四个字段<b>各自可缺省</b> （缺省 ⇒ 0 =
   * 不限制 / 不收税 = {@link PortRule#unrestricted()}，I-P1）；显式 0 与未设逐值同义。
   *
   * <p>★ <b>税从量从价都行</b>（用户「规则可以灵活，从量从价都行」）：{@code entryTax}/{@code exitTax} 是 {@code
   * {"mode":"none|per_unit_milli|ad_valorem_per_mille","amount":N}}；{@code amount} 的量纲由 {@code
   * mode} 决定（毫/单位 或 货值‰），<b>缺省 {@code {"mode":"none"}}</b> = 不收税。
   *
   * <p>★ <b>fail-closed 的拒因</b>：非对象 / 类不是对象 / 字段名不认识（<b>拼错一个字段名 = 具名拒</b>，不静默当 0）/ 非整数 / 键空白 /
   * 键词法非法 / 负限制 / 负税 / {@code mode} 非法 / {@code none} 带非 0 数额 / <b>未登记的挂单类型</b> —— 全部在这里拒（N1 负向判据：
   * <b>非法政策 fail-closed 具名拒，不静默忽略</b>）。"这个商品/币种在世界里存在吗"由组合根判（gov 看不见经济词表）； 挂单类型是 gov 编译期看得见的受控词表 ⇒
   * 在这里判。
   *
   * <p>★ <b>未知的顶层字段不在这里拒</b>（与 {@link #administrationPlan} 等载荷同一条既有口径：额外字段留给将来的扩展）；
   * 但<b>规则对象内部</b>的字段名必须逐个认识（那是"一条规则的完整拼法"，少一个字母就是另一条规则）。
   */
  static GovPortPolicy portPolicy(JsonNode payload) {
    Map<CommodityId, PortRule> commodities =
        ruleTable(payload, "commodityRules", CommodityId::parse);
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencies = currencyRuleTable(payload);
    return new GovPortPolicy(commodities, currencies);
  }

  /**
   * ★★ <b>币种规则表（两层：币种 → 挂单类型 → 四元组）</b>：缺失/{@code null} ⇒ 空；非对象 / 币种键空白 / 币种键词法非法 / 内层不是对象 /
   * <b>未登记的挂单类型</b> / 类型下的值不是对象 ⇒ 具名拒。内外两层都保序。
   *
   * <p>★ 内层<b>空对象</b>（{@code {"silver":{}}}）= 这种钱一个类型都没设规则 = 与不写这条同义（不拒：它只是"没规则"的另一种拼法）。
   */
  private static Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencyRuleTable(
      JsonNode payload) {
    String field = "currencyRules";
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象（币种 → 挂单类型 → 规则对象）");
    }
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> table = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("字段 " + field + " 的键不得为空白");
              }
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "[" + key + "] 必须是对象（" + legalOrderKinds() + " → 规则对象）");
              }
              CurrencyId currency;
              try {
                currency = CurrencyId.parse(key);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("字段 " + field + " 的键不是合法稳定 id: " + key, e);
              }
              Map<MarketOrderKind, PortRule> byKind = new LinkedHashMap<>();
              entry
                  .getValue()
                  .fields()
                  .forEachRemaining(
                      kindEntry -> {
                        String kindKey = kindEntry.getKey();
                        MarketOrderKind orderKind;
                        try {
                          orderKind = MarketOrderKind.parse(kindKey);
                        } catch (IllegalArgumentException e) {
                          throw new IllegalArgumentException(
                              "字段 "
                                  + field
                                  + "["
                                  + key
                                  + "] 的挂单类型非法: "
                                  + kindKey
                                  + "（合法值: "
                                  + legalOrderKinds()
                                  + "）",
                              e);
                        }
                        if (!kindEntry.getValue().isObject()) {
                          throw new IllegalArgumentException(
                              "字段 "
                                  + field
                                  + "["
                                  + key
                                  + "]["
                                  + kindKey
                                  + "] 必须是对象（入口/出口限制 + 入口/出口税）");
                        }
                        byKind.put(
                            orderKind,
                            portRule(
                                kindEntry.getValue(), field + "[" + key + "][" + kindKey + "]"));
                      });
              table.put(currency, byKind);
            });
    return table;
  }

  /** 合法的挂单类型字面量（拒绝消息里列出全部，便于一次改对）。 */
  private static String legalOrderKinds() {
    List<String> kinds = new ArrayList<>();
    for (MarketOrderKind kind : MarketOrderKind.all()) {
      kinds.add(kind.value());
    }
    return String.join("|", kinds);
  }

  /**
   * 一张 {@code 稳定 id → 四元组规则} 表：缺失/{@code null} ⇒ 空；非对象/项非对象/键空白/键词法非法 ⇒ 具名拒。保序。
   *
   * <p>★ {@code parse} 由调用方给（{@code CommodityId::parse} / {@code
   * CurrencyId::parse}）：<b>词法</b>非法在这里拒（具名）， <b>词表</b>里有没有这个类不在这里判（gov 看不见经济词表；由组合根 fail-closed
   * 具名拒，N1）。
   */
  private static <K> Map<K, PortRule> ruleTable(
      JsonNode payload, String field, java.util.function.Function<String, K> parse) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象（id → 规则对象）");
    }
    Map<K, PortRule> table = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("字段 " + field + " 的键不得为空白");
              }
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "[" + key + "] 必须是对象（入口/出口限制 + 入口/出口税）");
              }
              K parsed;
              try {
                parsed = parse.apply(key);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("字段 " + field + " 的键不是合法稳定 id: " + key, e);
              }
              table.put(parsed, portRule(entry.getValue(), field + "[" + key + "]"));
            });
    return table;
  }

  /** 一条四元组规则：四个字段各自可缺省（缺省 = 0 / 不收税）；<b>不认识的字段名 ⇒ 具名拒</b>。 */
  private static PortRule portRule(JsonNode node, String where) {
    requireOnlyFields(node, where, PORT_RULE_FIELDS);
    return new PortRule(
        optionalLong(node, "entryRestrictionPerMille", PortRule.RESTRICTION_NONE_PER_MILLE),
        optionalLong(node, "exitRestrictionPerMille", PortRule.RESTRICTION_NONE_PER_MILLE),
        portTax(node.get("entryTax"), where + ".entryTax"),
        portTax(node.get("exitTax"), where + ".exitTax"));
  }

  /**
   * 一条税规则：缺失/{@code null} ⇒ {@link PortTaxRule#none()}（不收税）；{@code mode} 必填且必须在词表里， {@code amount}
   * 缺省 0；<b>不认识的字段名 ⇒ 具名拒</b>。
   */
  private static PortTaxRule portTax(JsonNode node, String where) {
    if (node == null || node.isNull()) {
      return PortTaxRule.none();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(where + " 必须是对象（{mode, amount}）或省略（= 不收税）");
    }
    requireOnlyFields(node, where, PORT_TAX_FIELDS);
    JsonNode modeNode = node.get("mode");
    if (modeNode == null || modeNode.isNull()) {
      throw new IllegalArgumentException(
          where + ".mode 必填（" + legalModes() + "）；不收税就省略整个 " + where);
    }
    if (!modeNode.isTextual()) {
      throw new IllegalArgumentException(where + ".mode 必须是字符串（" + legalModes() + "）");
    }
    PortTaxMode mode;
    try {
      mode = PortTaxMode.parse(modeNode.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(where + " 的税计量方式非法: " + modeNode.asText(), e);
    }
    return new PortTaxRule(mode, optionalLong(node, "amount", 0L));
  }

  /** 一条规则里允许出现的字段名（多一个/少一个都拒：拼错一个字母就是另一条规则）。 */
  private static final java.util.Set<String> PORT_RULE_FIELDS =
      java.util.Set.of(
          "entryRestrictionPerMille", "exitRestrictionPerMille", "entryTax", "exitTax");

  /** 一条税规则里允许出现的字段名。 */
  private static final java.util.Set<String> PORT_TAX_FIELDS = java.util.Set.of("mode", "amount");

  /** 不认识的字段名 ⇒ 具名拒（不静默当缺省：那会让"税率拼错"变成"没设税"）。 */
  private static void requireOnlyFields(JsonNode node, String where, java.util.Set<String> known) {
    var fields = node.fieldNames();
    while (fields.hasNext()) {
      String name = fields.next();
      if (!known.contains(name)) {
        throw new IllegalArgumentException(
            "字段 " + where + " 里不认识的键: " + name + "（合法键: " + new java.util.TreeSet<>(known) + "）");
      }
    }
  }

  /** 合法的计量方式字面量（拒绝消息里列出全部，便于一次改对）。 */
  private static String legalModes() {
    List<String> modes = new ArrayList<>();
    for (PortTaxMode mode : PortTaxMode.all()) {
      modes.add(mode.value());
    }
    return String.join("|", modes);
  }

  /**
   * 预算政策载荷（★ Z7e-3 双模，控制方 2026-10-23 裁定 A+B："AB同时应用吧"）：
   *
   * <ul>
   *   <li>{@link GovBudgetPolicyEditMode#PATCH}（{@code mode} 缺省）：<b>缺省字段保留现值</b>——{@code
   *       orderedCategories} 缺失/{@code null} ⇒ 保留 {@code current} 的表（{@code current=null} ⇒
   *       空表）；显式给出（含 {@code []} = 清空）⇒ 整表替换；{@code officialSalaryRule} 给出 ⇒ 对象内缺省字段逐项保留现值；{@code
   *       remittancePerMilleToSuperior} 给出才覆盖。
   *   <li>{@link GovBudgetPolicyEditMode#REPLACE}：旧整表替换语义——类别表缺省 = 空（不自动付）、工资规则缺省 = 0/0、 上缴比例缺省 =
   *       0。
   * </ul>
   *
   * <p>★ {@code mode} 只影响本次解析、不落状态；数值范围（min ≤ cap、类别不重复、比例 0..1000‰）仍由 {@link GovBudgetPolicy}
   * 构造期一条口径判。
   *
   * @param current 该 GOV 的现值政策；{@code null} = 键不存在（PATCH 无"现值"可保留 ⇒ 等价于中性默认）
   */
  static GovBudgetPolicy budgetPolicy(JsonNode payload, GovBudgetPolicy current) {
    GovBudgetPolicyEditMode mode = editMode(payload);
    GovBudgetPolicy base =
        mode == GovBudgetPolicyEditMode.PATCH && current != null
            ? current
            : GovBudgetPolicy.neutral();
    JsonNode categories = payload.get("orderedCategories");
    List<GovBudgetLine> orderedCategories =
        categories == null || categories.isNull()
            ? base.orderedCategories()
            : categoriesOf(categories);
    GovOfficialSalaryRule officialSalaryRule = salaryRule(payload, base.officialSalaryRule());
    // ★ Z7c：范围 0..1000 由 GovBudgetPolicy 构造期判；REPLACE 的缺省 = 0（= 不上缴）。
    long remittancePerMilleToSuperior =
        optionalLong(payload, "remittancePerMilleToSuperior", base.remittancePerMilleToSuperior());
    return new GovBudgetPolicy(orderedCategories, officialSalaryRule, remittancePerMilleToSuperior);
  }

  /**
   * 载荷声明的编辑模式：{@code mode} 缺失/{@code null} ⇒ {@link GovBudgetPolicyEditMode#PATCH}（新缺省）； 非文本/空白/词表外
   * ⇒ 具名拒。
   */
  static GovBudgetPolicyEditMode editMode(JsonNode payload) {
    JsonNode node = payload.get("mode");
    if (node == null || node.isNull()) {
      return GovBudgetPolicyEditMode.PATCH;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 mode 必须是 PATCH|REPLACE 文本");
    }
    return GovBudgetPolicyEditMode.parse(node.asText());
  }

  /** 类别表整表解析（保序；min/cap 缺省展开一次）。 */
  private static List<GovBudgetLine> categoriesOf(JsonNode categories) {
    if (!categories.isArray()) {
      throw new IllegalArgumentException("字段 orderedCategories 必须是数组");
    }
    List<GovBudgetLine> orderedCategories = new ArrayList<>();
    for (JsonNode element : categories) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("orderedCategories 的每一项必须是对象");
      }
      GovBudgetCategory category = requireCategory(requireText(element, "category"));
      long minPerCycle = optionalLong(element, "minPerCycle", 0L);
      long capPerCycle = optionalLong(element, "capPerCycle", Long.MAX_VALUE);
      orderedCategories.add(new GovBudgetLine(category, minPerCycle, capPerCycle));
    }
    return orderedCategories;
  }

  /** 必填字符串字段（非空白）。 */
  static String requireText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空白字符串");
    }
    return value.asText();
  }

  /** {@code unitId} 字段（要写给哪个 GOV）；非空白、{@link UnitId#parse} 能解。 */
  static UnitId requireUnitId(JsonNode payload) {
    return UnitId.parse(requireText(payload, "unitId"));
  }

  /** 可选整数字段：缺失/{@code null} ⇒ 缺省值；非整数或超出 long ⇒ 拒（不静默截断）。 */
  static long optionalLong(JsonNode payload, String field, long defaultValue) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是可表示 long 的整数");
    }
    return value.longValue();
  }

  /** 可选 3 档目录：缺失/{@code null} ⇒ 空 Optional（调用方取默认目录）。 */
  private static java.util.Optional<List<GovPostTier>> optionalPostTiers(JsonNode payload) {
    JsonNode node = payload.get("postTiers");
    if (node == null || node.isNull()) {
      return java.util.Optional.empty();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("字段 postTiers 必须是数组");
    }
    List<GovPostTier> tiers = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("postTiers 的每一项必须是对象");
      }
      tiers.add(
          new GovPostTier(
              requireText(element, "tierId"),
              optionalLong(element, "securityWeightPerMille", 0L),
              optionalLong(element, "paperworkWeightPerMille", 0L),
              // ★ R2：口岸维权重缺省 0（= 该档位不产出口岸编制；旧载荷逐字不动）。
              optionalLong(element, "portWeightPerMille", 0L)));
    }
    return java.util.Optional.of(tiers);
  }

  /**
   * 工资规则：节点缺失/{@code null} ⇒ {@code fallback}（PATCH = 现值、REPLACE = 0/0）；给出 ⇒ 对象内缺省字段取 {@code
   * fallback} 的同项（PATCH 逐项保留、REPLACE = 0）。
   */
  private static GovOfficialSalaryRule salaryRule(
      JsonNode payload, GovOfficialSalaryRule fallback) {
    JsonNode node = payload.get("officialSalaryRule");
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 officialSalaryRule 必须是对象");
    }
    return new GovOfficialSalaryRule(
        optionalLong(node, "grainMilliPerCommittedHour", fallback.grainMilliPerCommittedHour()),
        optionalLong(node, "silverMilliPerCommittedHour", fallback.silverMilliPerCommittedHour()));
  }

  /** 类别词表严格解析（常量名；未知文本具名拒，不做模糊匹配）。 */
  private static GovBudgetCategory requireCategory(String text) {
    try {
      return GovBudgetCategory.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知预算类别: "
              + text
              + "（词表: ADMIN_STIPEND/MILITARY_STIPEND/ADMIN_SALARY/DEBT_SERVICE/OTHER）",
          e);
    }
  }
}
