package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxMode;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetCategory;
import io.mosire.simos.economy.govpolicy.GovBudgetLine;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicyEditMode;
import io.mosire.simos.economy.govpolicy.GovOfficialSalaryRule;
import io.mosire.simos.economy.govpolicy.GovPortPolicy;
import io.mosire.simos.economy.govpolicy.GovPostTier;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>{@code economy.SetGovernmentPolicy} 的载荷解析助手（追加裁定 12，2026-10-12 §21.9 D-1）</b>。
 *
 * <pre>{@code
 * { "governmentId": "gov-unit-gov-central",
 *   "policies": {
 *     "port":           { commodityRules?, currencyRules?, marketControl? },
 *     "budget":         { mode?, orderedCategories?, officialSalaryRule?, remittancePerMilleToSuperior? },
 *     "administration": { securityPlannedLaborMilli?, …, postTiers?, supernumerarySqrtCoefficient? } } }
 * }</pre>
 *
 * <p>★★ <b>本类是那三段载荷形状的唯一拼写点</b>（从 {@code GovPayloads} 整体搬来，逐字段语义一字不改）：命令 handler 与 app
 * 的预览投影<b>都调这里</b>，不存在"handler 一套解析、预览另一套"（那正是"预览说能写、提交却拒"的病根）。搬家的<b>唯一理由</b>是 模块边界：写口住 {@code
 * simos-economy} ⇒ 解析也必须住在写口这一侧（{@code simos-gov} 不能import economy，反之亦然）。
 *
 * <p>★ <b>坏载荷一律以 {@link IllegalArgumentException} 面世、带可读中文原因</b>；handler 在命令边界把它折成 {@code
 * HandlerOutcome.Rejected}（理由进 {@code simos.command.rejected} 事件，拒绝不留 revision）。数值范围（≥0、恰 3
 * 档、类别不重复、min ≤ cap）留给领域 record，两处不重复实现。
 *
 * <p>★ <b>子集语义</b>：三个子键<b>各自可缺席</b>（缺席 = 不改该类政策）；{@code policies} 本身缺失/{@code null} ⇒
 * <b>具名拒</b>（不做空写：一条"什么都没改"的命令不该落 revision）；{@code policies} 是空对象 ⇒ 同样具名拒。
 *
 * <p>★ <b>缺省口径</b>：编制计划除子集存在性外全部可缺省（计划量 0、默认 3 档、修正 1000‰、{@code k=1}）；预算政策分两模 （{@link
 * GovBudgetPolicyEditMode#PATCH}（缺省）的缺省字段 = <b>保留现值</b>，{@link GovBudgetPolicyEditMode#REPLACE}
 * 的缺省字段 = 空表 / 0/0 / 0）。<b>缺省值只在这里展开一次</b>，领域 record 不猜。
 */
public final class EconomyGovPolicyPayloads {

  private EconomyGovPolicyPayloads() {}

  /** 三个子集的键名（唯一拼写点）。 */
  public static final String PORT = "port";

  public static final String BUDGET = "budget";

  public static final String ADMINISTRATION = "administration";

  /**
   * 一次解析的产物：三类政策的<b>新值</b> + 哪些子集在载荷里出现过（缺席 = 不改）。
   *
   * @param port 口岸政策新值；{@code portSet} = false 时它是"现值原样"（调用方不必读）
   * @param budget 预算政策新值（PATCH 已按现值合并）；同上
   * @param administration 编制计划新值；同上
   */
  public record Parsed(
      boolean portSet,
      GovPortPolicy port,
      boolean budgetSet,
      GovBudgetPolicy budget,
      boolean administrationSet,
      GovAdministrationPlan administration) {

    /** 一个子集都没给 ⇒ 具名拒（不做空写）。 */
    public boolean isEmpty() {
      return !portSet && !budgetSet && !administrationSet;
    }
  }

  /**
   * 解析 {@code policies} 节点（{@code payload} 的 {@code "policies"} 字段）。
   *
   * @param payload 命令载荷根对象（已由 {@code EconomyCommandPayloads.parseObject} 判过是对象）
   * @param current 该政府的现值（PATCH 的合并基准；{@code null} = 无现值 ⇒ 等价中性默认）
   * @throws IllegalArgumentException {@code policies} 缺失/非对象/空对象，或任一子集的字段形状坏
   */
  public static Parsed parse(JsonNode payload, GovPolicyCurrent current) {
    JsonNode policies = payload.get("policies");
    if (policies == null || policies.isNull()) {
      throw new IllegalArgumentException(
          "字段 policies 必填（{port?,budget?,administration?} 至少给一个子集；一条什么都不改的命令不落 revision）");
    }
    if (!policies.isObject()) {
      throw new IllegalArgumentException(
          "字段 policies 必须是对象（{port?,budget?,administration?}）: " + policies.getNodeType());
    }
    boolean portSet = policies.hasNonNull(PORT);
    boolean budgetSet = policies.hasNonNull(BUDGET);
    boolean administrationSet = policies.hasNonNull(ADMINISTRATION);
    if (!portSet && !budgetSet && !administrationSet) {
      throw new IllegalArgumentException(
          "字段 policies 里一个子集都没给（可给 port / budget / administration 之一或任意组合；不认识的子键不生效）");
    }
    GovPortPolicy port = portSet ? portPolicy(child(policies, PORT)) : GovPortPolicy.empty();
    GovBudgetPolicy budget =
        budgetSet ? budgetPolicy(child(policies, BUDGET), current) : GovBudgetPolicy.neutral();
    GovAdministrationPlan administration =
        administrationSet
            ? administrationPlan(child(policies, ADMINISTRATION))
            : GovAdministrationPlan.neutral();
    return new Parsed(portSet, port, budgetSet, budget, administrationSet, administration);
  }

  /** 现值三元组（PATCH 的合并基准与"哪些子集真的变了"的判据）。 */
  public record GovPolicyCurrent(
      GovPortPolicy port, GovBudgetPolicy budget, GovAdministrationPlan administration) {

    public static GovPolicyCurrent neutral() {
      return new GovPolicyCurrent(
          GovPortPolicy.empty(), GovBudgetPolicy.neutral(), GovAdministrationPlan.neutral());
    }
  }

  /** 某个子集节点：存在但非对象 ⇒ 具名拒（不静默当"没给"）。 */
  private static JsonNode child(JsonNode policies, String field) {
    JsonNode node = policies.get(field);
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 policies." + field + " 必须是对象: " + node.getNodeType());
    }
    return node;
  }

  // ── 口岸（P-T1a/P-T1d/P-T1e；形状与 gov.SetPortPolicy 逐字相同）────────────────────────────────

  /**
   * ★★ <b>口岸管制政策（整表替换）</b>：{@code commodityRules} / {@code currencyRules} / {@code marketControl}
   * 三个字段<b>整体替换</b>该类政策的现值（与既有 {@code gov.SetPortPolicy} 的语义逐字相同：同类型重复设置 = 整表替换； 与现值逐值相同 ⇒ 空变更集）。
   *
   * <p>★ <b>每类四个数</b>：入口限制‰ / 出口限制‰ / 入口税 / 出口税 —— 四个字段各自可缺省（缺省 ⇒ 0 = 不限制 / 不收税 = {@link
   * PortRule#unrestricted()}，I-P1）；显式 0 与未设逐值同义。
   *
   * <p>★ <b>fail-closed 的拒因</b>：非对象 / 类不是对象 / 字段名不认识（拼错一个字段名 = 具名拒）/ 非整数 / 键空白 / 键词法非法 / 负限制 / 负税 /
   * {@code mode} 非法 / {@code none} 带非 0 数额 / <b>未登记的挂单类型</b> —— 全部在这里拒。
   */
  public static GovPortPolicy portPolicy(JsonNode node) {
    Map<CommodityId, PortRule> commodities = ruleTable(node, "commodityRules", CommodityId::parse);
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencies = currencyRuleTable(node);
    return new GovPortPolicy(commodities, currencies, marketControl(node));
  }

  /**
   * ★★ <b>词表守卫（N1 负向判据；2026-10-12 起在写口判）</b>：口岸规则里的商品键必须在世界商品词表里、币种键必须在 {@code
   * EconomyData.currencies()} 里 —— 否则<b>具名拒</b>（非法政策不许先落进状态）。
   *
   * <p>★ <b>为什么现在能在这里判</b>：写口 handler 住 economy ⇒ 编译期看得见经济词表（旧形状里 GOV 看不见，只能靠 app 的写前守卫 {@code
   * GovPortPolicyGuard}）。判据与那段守卫<b>逐字同源</b>：商品 = {@link EconomyVocabulary#allCommodityIds()}， 币种 =
   * {@code EconomyData.currencies()}（世界状态权威）。
   */
  public static void requireKnownPortClasses(GovPortPolicy policy, EconomyData economy) {
    Set<String> commodities = new LinkedHashSet<>(EconomyVocabulary.allCommodityIds());
    for (CommodityId commodity : policy.commodityRules().keySet()) {
      if (!commodities.contains(commodity.value())) {
        throw new IllegalArgumentException(
            "口岸政策非法：未知商品 " + commodity.value() + "（不在世界词表内；fail-closed 拒，不静默忽略）");
      }
    }
    Set<CurrencyId> currencies = economy.currencies().keySet();
    for (CurrencyId currency : policy.currencyRules().keySet()) {
      if (!currencies.contains(currency)) {
        throw new IllegalArgumentException(
            "口岸政策非法：未知币种 " + currency.value() + "（不在世界币种表内；fail-closed 拒，不静默忽略）");
      }
    }
  }

  /**
   * ★★ <b>P-T1d：{@code marketControl}（政府要求管控市场）的解析</b>：缺失/{@code null} ⇒ {@code false}（缺省 = 不要求管控 ⇒
   * 无置顶、无行政力消耗 ⇒ 逐值不变，I-C2）；必须是<b>JSON 布尔</b>（字符串 {@code "true"} / 数字 {@code 1} 一律具名拒 ——
   * 静默当布尔会让"到底开没开管控"变成猜）。
   */
  private static boolean marketControl(JsonNode node) {
    JsonNode value = node.get("marketControl");
    if (value == null || value.isNull()) {
      return false;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(
          "字段 marketControl 必须是布尔（true = 政府要求管控市场；不要求就省略或写 false）: " + value);
    }
    return value.booleanValue();
  }

  /**
   * 币种规则表（两层：币种 → 挂单类型 → 四元组）：缺失/{@code null} ⇒ 空；非对象 / 币种键空白 / 币种键词法非法 / 内层不是对象 / <b>未登记的挂单类型</b>
   * / 类型下的值不是对象 ⇒ 具名拒。内外两层都保序。
   */
  private static Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencyRuleTable(JsonNode node) {
    String field = "currencyRules";
    JsonNode table = node.get(field);
    if (table == null || table.isNull()) {
      return Map.of();
    }
    if (!table.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象（币种 → 挂单类型 → 规则对象）");
    }
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> byCurrency = new LinkedHashMap<>();
    table
        .fields()
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
              byCurrency.put(currency, byKind);
            });
    return byCurrency;
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
   * <p>★ {@code parse} 由调用方给（{@code CommodityId::parse}）：<b>词法</b>非法在这里拒（具名），<b>词表</b>里有没有这个类由
   * {@link #requireKnownPortClasses} 在命令边界判（具名拒，N1）。
   */
  private static <K> Map<K, PortRule> ruleTable(
      JsonNode node, String field, java.util.function.Function<String, K> parse) {
    JsonNode table = node.get(field);
    if (table == null || table.isNull()) {
      return Map.of();
    }
    if (!table.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象（id → 规则对象）");
    }
    Map<K, PortRule> rules = new LinkedHashMap<>();
    table
        .fields()
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
              rules.put(parsed, portRule(entry.getValue(), field + "[" + key + "]"));
            });
    return rules;
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
  private static final Set<String> PORT_RULE_FIELDS =
      Set.of("entryRestrictionPerMille", "exitRestrictionPerMille", "entryTax", "exitTax");

  /** 一条税规则里允许出现的字段名。 */
  private static final Set<String> PORT_TAX_FIELDS = Set.of("mode", "amount");

  /** 不认识的字段名 ⇒ 具名拒（不静默当缺省：那会让"税率拼错"变成"没设税"）。 */
  private static void requireOnlyFields(JsonNode node, String where, Set<String> known) {
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

  // ── 预算（Z7e-3 双模）──────────────────────────────────────────────────────────────────────

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
   * @param current 该政府当前的预算政策；{@code null} = 无现值（PATCH 无"现值"可保留 ⇒ 等价于中性默认）
   */
  public static GovBudgetPolicy budgetPolicy(JsonNode node, GovPolicyCurrent current) {
    GovBudgetPolicy present = current == null ? null : current.budget();
    GovBudgetPolicyEditMode mode = editMode(node);
    GovBudgetPolicy base =
        mode == GovBudgetPolicyEditMode.PATCH && present != null
            ? present
            : GovBudgetPolicy.neutral();
    JsonNode categories = node.get("orderedCategories");
    List<GovBudgetLine> orderedCategories =
        categories == null || categories.isNull()
            ? base.orderedCategories()
            : categoriesOf(categories);
    GovOfficialSalaryRule officialSalaryRule = salaryRule(node, base.officialSalaryRule());
    // ★ Z7c：范围 0..1000 由 GovBudgetPolicy 构造期判；REPLACE 的缺省 = 0（= 不上缴）。
    long remittancePerMilleToSuperior =
        optionalLong(node, "remittancePerMilleToSuperior", base.remittancePerMilleToSuperior());
    return new GovBudgetPolicy(orderedCategories, officialSalaryRule, remittancePerMilleToSuperior);
  }

  /**
   * 子集声明的编辑模式：{@code mode} 缺失/{@code null} ⇒ {@link GovBudgetPolicyEditMode#PATCH}（新缺省）； 非文本/空白/词表外
   * ⇒ 具名拒。
   */
  public static GovBudgetPolicyEditMode editMode(JsonNode node) {
    JsonNode value = node.get("mode");
    if (value == null || value.isNull()) {
      return GovBudgetPolicyEditMode.PATCH;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 mode 必须是 PATCH|REPLACE 文本");
    }
    return GovBudgetPolicyEditMode.parse(value.asText());
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

  /**
   * 工资规则：节点缺失/{@code null} ⇒ {@code fallback}（PATCH = 现值、REPLACE = 0/0）；给出 ⇒ 对象内缺省字段取 {@code
   * fallback} 的同项（PATCH 逐项保留、REPLACE = 0）。
   */
  private static GovOfficialSalaryRule salaryRule(JsonNode node, GovOfficialSalaryRule fallback) {
    JsonNode value = node.get("officialSalaryRule");
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 officialSalaryRule 必须是对象");
    }
    return new GovOfficialSalaryRule(
        optionalLong(value, "grainMilliPerCommittedHour", fallback.grainMilliPerCommittedHour()),
        optionalLong(value, "silverMilliPerCommittedHour", fallback.silverMilliPerCommittedHour()));
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

  // ── 编制（三维计划 + 3 档目录 + 六个静态修正 + k）────────────────────────────────────────────

  /** 三维编制计划载荷：全部字段可缺省（见类注）。 */
  public static GovAdministrationPlan administrationPlan(JsonNode node) {
    long securityPlannedLaborMilli = optionalLong(node, "securityPlannedLaborMilli", 0L);
    long paperworkPlannedLaborMilli = optionalLong(node, "paperworkPlannedLaborMilli", 0L);
    // ★ R2：口岸维计划量缺省 0（= 没设口岸编制；旧载荷逐字不动即得旧语义）。
    long portPlannedLaborMilli = optionalLong(node, "portPlannedLaborMilli", 0L);
    List<GovPostTier> postTiers =
        optionalPostTiers(node).orElse(GovAdministrationPlan.DEFAULT_POST_TIERS);
    long securitySupplyStatic =
        optionalLong(
            node,
            "securitySupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long paperworkSupplyStatic =
        optionalLong(
            node,
            "paperworkSupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long securityDemandStatic =
        optionalLong(
            node,
            "securityDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long paperworkDemandStatic =
        optionalLong(
            node,
            "paperworkDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    // ★ R2：口岸维两个静态修正的缺省 = 中性 1000‰（照另两维形制）。
    long portSupplyStatic =
        optionalLong(
            node,
            "portSupplyStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long portDemandStatic =
        optionalLong(
            node,
            "portDemandStaticModifierPerMille",
            GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    long supernumerarySqrtCoefficient =
        optionalLong(
            node,
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

  /** 可选 3 档目录：缺失/{@code null} ⇒ 空 Optional（调用方取默认目录）。 */
  private static Optional<List<GovPostTier>> optionalPostTiers(JsonNode node) {
    JsonNode value = node.get("postTiers");
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 postTiers 必须是数组");
    }
    List<GovPostTier> tiers = new ArrayList<>();
    for (JsonNode element : value) {
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
    return Optional.of(tiers);
  }

  // ── 共用小工具（形状与类型；数值范围留给领域 record）──────────────────────────────────────────

  /** 必填字符串字段（非空白）。 */
  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空白字符串");
    }
    return value.asText();
  }

  /** 可选整数字段：缺失/{@code null} ⇒ 缺省值；非整数或超出 long ⇒ 拒（不静默截断）。 */
  private static long optionalLong(JsonNode node, String field, long defaultValue) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是可表示 long 的整数");
    }
    return value.longValue();
  }
}
