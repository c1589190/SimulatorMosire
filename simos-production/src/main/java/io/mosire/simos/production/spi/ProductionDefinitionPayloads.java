package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.BarterDiscountPolicy;
import io.mosire.simos.production.contract.BarterReferencePrice;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.ExperienceGrowth;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.GrowthBasis;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionSkillKey;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>生产定义命令的载荷解析</b>（D-031 P1）：产业 / 生产方式节点 → production 契约定义类型。
 *
 * <p>★ 字段口径与原 {@code economy.Seed.industries[]} / {@code modes[]} / {@code economy.UpsertIndustry}
 * 逐字同形； 数值语义与冻结由契约类型的构造期强不变量判（一处真相，不在这里复写）。
 *
 * <p>★ <b>只吃本域固有约束</b>（D-031 spec §3.3）：不查 economy 实例、不查跨表引用 —— 那些在命令/执行边界经端口判。
 */
final class ProductionDefinitionPayloads {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final Set<String> ROLE_FIELDS =
      Set.of(
          "id",
          "name",
          "socialClass",
          "sharePerMille",
          "intraRoleSplit",
          "standardLaborMilliPerCycle",
          "experienceGrowth",
          "conditions",
          "payment");
  private static final Set<String> EXPERIENCE_GROWTH_FIELDS =
      Set.of("track", "basis", "coefficientPerMille", "maxPerTickValue", "countsManagement");
  private static final Set<String> PAYMENT_FIELDS = Set.of("kind", "currency", "barterDiscount");
  private static final Set<String> BARTER_DISCOUNT_FIELDS =
      Set.of("referencePrice", "discountPerMille");

  private ProductionDefinitionPayloads() {}

  /** 对象形状守卫（角色/支付等嵌套节点用；顶层仍沿用既有“多余键不读”的 Seed 习惯）。 */
  private static void requireObject(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
  }

  /** 白名单拒未知键：新配置节点不静默吞字段（D1 §2.8）。 */
  private static void requireKnownFields(JsonNode node, Set<String> allowed, String field) {
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      String key = fields.next().getKey();
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException("字段 " + field + " 含未知键: " + key);
      }
    }
  }

  /** 产业节点：完整 {@link IndustryDefinitionView}（技能可选；缺省 = 未绑技能键）。 */
  static IndustryDefinitionView industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    Optional<ProductionSkillKey> skill = optionalText(node, "skill").map(ProductionSkillKey::parse);
    long cycleDays = requireLong(node, "cycleDays");
    // ★★ B2c（裁定八）：候选的 buildDays 搬进方案定义；缺键 ⇒ 0（逐值等于旧候选的缺省）。
    long buildDays = optionalLong(node, "buildDays", 0L);
    if (buildDays < 0L) {
      throw new IllegalArgumentException("字段 buildDays 不得为负: " + buildDays);
    }
    // ★★ B2c（裁定八）：候选的 acceptedRightKinds 搬进方案定义；缺键 ⇒ 空集（逐值等于旧候选的缺省，
    //   采用算法据此具名拒绝 NO_RIGHT_KIND —— 要能被采用的方案必须显式声明）。
    Set<AssetRightKind> acceptedRightKinds = rightKinds(node.get("acceptedRightKinds"));
    Map<AssetKind, Long> capacityPerUnit =
        assetMap(optionalObject(node, "capacityPerUnit"), "capacityPerUnit");
    long laborPerUnit = optionalLong(node, "laborPerUnit", 0L);
    Map<AssetKind, Map<CommodityId, Long>> dailyInput =
        assetCommodityMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    Map<CurrencyId, Long> moneyOutput =
        currencyMap(optionalObject(node, "moneyOutputPerUnit"), "moneyOutputPerUnit");
    Map<AssetKind, Map<CommodityId, Long>> cycleInput =
        assetCommodityMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
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
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（WageFirst 在播种期拒）。
    if (!(rule instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException("v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + rule);
    }
    // ★★ D-032：方案引用的经验条/公式/解锁门槛 与 可用性条件（两段都缺省 ⇒ 不挂经验面 / 无条件）。
    ProductionExperienceUse experience = experienceUse(node.get("experience"));
    List<ProductionAvailabilityCondition> availability =
        availabilityConditions(node.get("availability"));
    List<ProductionRoleDefinition> roles = roles(node.get("roles"));
    RoleShareRemainder roleShareRemainderRule =
        roleShareRemainder(node.get("roleShareRemainderRule"));
    List<IndustryId> upgradesTo = upgradesTo(node.get("upgradesTo"));
    Optional<ProductionModeId> mode = optionalText(node, "mode").map(ProductionModeId::parse);
    return new IndustryDefinitionView(
        id,
        name,
        regime,
        skill,
        cycleDays,
        buildDays,
        capacityPerUnit,
        dailyInput,
        dailyLabor,
        laborPerUnit,
        output,
        cycleInput,
        slots,
        rule,
        new MoneyOutput(moneyOutput),
        experience,
        availability,
        acceptedRightKinds,
        roles,
        roleShareRemainderRule,
        upgradesTo,
        mode);
  }

  /**
   * ★★ <b>D1：{@code roles} 数组 → 角色定义表</b>。
   *
   * <p>缺键 / JSON null ⇒ 单角色 {@code operator}（D1 §2.8 的同版本缺省形态）；显式空数组 ⇒ 拒绝
   * （方案必须至少有一个角色）。角色节点按白名单拒未知键，id 重复与份额合计由 {@link IndustryDefinitionView} 构造期再守一次。
   */
  private static List<ProductionRoleDefinition> roles(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of(ProductionRoleDefinition.defaultOperator());
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("字段 roles 必须是数组: " + node);
    }
    List<ProductionRoleDefinition> roles = new ArrayList<>();
    for (JsonNode role : node) {
      roles.add(role(role));
    }
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("字段 roles 不得为空数组（至少给一个经营者角色）");
    }
    return roles;
  }

  private static ProductionRoleDefinition role(JsonNode node) {
    requireObject(node, "roles[]");
    requireKnownFields(node, ROLE_FIELDS, "roles[]");
    ProductionRoleId id = ProductionRoleId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    Optional<SocialClassId> socialClass =
        optionalText(node, "socialClass").map(SocialClassId::parse);
    long sharePerMille = requireLong(node, "sharePerMille");
    IntraRoleSplitRule intraRoleSplit = intraRoleSplit(requireText(node, "intraRoleSplit"));
    long standardLaborMilliPerCycle = requireLong(node, "standardLaborMilliPerCycle");
    Optional<ExperienceGrowth> experienceGrowth = experienceGrowth(node.get("experienceGrowth"));
    List<ProductionAvailabilityCondition> conditions =
        availabilityConditions(node.get("conditions"));
    RolePayment payment = payment(node.get("payment"));
    return new ProductionRoleDefinition(
        id,
        name,
        socialClass,
        sharePerMille,
        intraRoleSplit,
        standardLaborMilliPerCycle,
        experienceGrowth,
        conditions,
        payment);
  }

  private static IntraRoleSplitRule intraRoleSplit(String text) {
    IntraRoleSplitRule rule = IntraRoleSplitRule.fromKey(text);
    if (rule == null) {
      throw new IllegalArgumentException(
          "字段 intraRoleSplit 不是已知规则（合法值：by_recognized_labor / by_rights / equal / configured_weights）: "
              + text);
    }
    return rule;
  }

  private static Optional<ExperienceGrowth> experienceGrowth(JsonNode node) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    requireObject(node, "experienceGrowth");
    requireKnownFields(node, EXPERIENCE_GROWTH_FIELDS, "experienceGrowth");
    ExperienceTrackId track = ExperienceTrackId.parse(requireText(node, "track"));
    GrowthBasis basis = growthBasis(requireText(node, "basis"));
    long coefficientPerMille = requireLong(node, "coefficientPerMille");
    long maxPerTickValue = optionalLong(node, "maxPerTickValue", 0L);
    boolean countsManagement = optionalBoolean(node, "countsManagement", false);
    return Optional.of(
        new ExperienceGrowth(track, basis, coefficientPerMille, maxPerTickValue, countsManagement));
  }

  private static GrowthBasis growthBasis(String text) {
    GrowthBasis basis = GrowthBasis.fromKey(text);
    if (basis == null) {
      throw new IllegalArgumentException(
          "字段 experienceGrowth.basis 不是已知基准（合法值：actual_labor / normalized_participation）: " + text);
    }
    return basis;
  }

  private static RolePayment payment(JsonNode node) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException("字段 payment 不得缺省（product_share 或 money_wage）");
    }
    if (node.isTextual()) {
      String text = node.asText();
      if ("product_share".equals(text) || "PRODUCT_SHARE".equals(text)) {
        return new RolePayment.ProductShare();
      }
      throw new IllegalArgumentException("字段 payment 的文本形式只允许 product_share: " + text);
    }
    requireObject(node, "payment");
    requireKnownFields(node, PAYMENT_FIELDS, "payment");
    String kind = requireText(node, "kind");
    if ("product_share".equals(kind) || "PRODUCT_SHARE".equals(kind)) {
      return new RolePayment.ProductShare();
    }
    if (!"money_wage".equals(kind) && !"MONEY_WAGE".equals(kind)) {
      throw new IllegalArgumentException("字段 payment.kind 不是已知支付形态: " + kind);
    }
    JsonNode currency = node.get("currency");
    if (currency != null && !currency.isNull()) {
      if (!currency.isTextual()
          || !("LOCAL_MARKET_AREA".equals(currency.asText())
              || "local_market_area".equals(currency.asText()))) {
        throw new IllegalArgumentException(
            "字段 payment.currency 只允许 LOCAL_MARKET_AREA（工资结算在本地市场区）: " + currency);
      }
    }
    return new RolePayment.MoneyWage(barterDiscount(node.get("barterDiscount")));
  }

  private static BarterDiscountPolicy barterDiscount(JsonNode node) {
    if (node == null || node.isNull()) {
      return BarterDiscountPolicy.defaultPolicy();
    }
    requireObject(node, "payment.barterDiscount");
    requireKnownFields(node, BARTER_DISCOUNT_FIELDS, "payment.barterDiscount");
    BarterReferencePrice referencePrice =
        barterReferencePrice(optionalText(node, "referencePrice").orElse("local_bid"));
    long discountPerMille = optionalLong(node, "discountPerMille", 1000L);
    return new BarterDiscountPolicy(referencePrice, discountPerMille);
  }

  private static BarterReferencePrice barterReferencePrice(String text) {
    BarterReferencePrice price = BarterReferencePrice.fromKey(text);
    if (price == null) {
      throw new IllegalArgumentException(
          "字段 payment.barterDiscount.referencePrice 只允许 local_bid / local_ask: " + text);
    }
    return price;
  }

  private static RoleShareRemainder roleShareRemainder(JsonNode node) {
    if (node == null || node.isNull()) {
      return RoleShareRemainder.UNALLOCATED;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 roleShareRemainderRule 必须是非空文本: " + node);
    }
    RoleShareRemainder rule = RoleShareRemainder.fromKey(node.asText());
    if (rule == null) {
      throw new IllegalArgumentException(
          "字段 roleShareRemainderRule 不是已知规则（合法值：unallocated / to_last_role）: " + node.asText());
    }
    return rule;
  }

  private static List<IndustryId> upgradesTo(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("字段 upgradesTo 必须是数组: " + node);
    }
    List<IndustryId> upgrades = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw new IllegalArgumentException("字段 upgradesTo 的元素必须是非空文本: " + node);
      }
      upgrades.add(IndustryId.parse(item.asText()));
    }
    return upgrades;
  }

  /**
   * ★★ <b>B2c：{@code acceptedRightKinds} 节点 → {@link AssetRightKind} 集合</b>（裁定八）。
   *
   * <p>★ 缺节点 / JSON null ⇒ <b>空集</b>（= 旧候选 {@code acceptedRightKinds} 的缺省语义：不接受任何权利； 采用算法据此具名拒绝
   * {@code NO_RIGHT_KIND}）；给数组则逐项按词表解析，未知值即抛（不默认、不归一）。 ★ 保序不可变：用 {@code LinkedHashSet}
   * 收，去重后交给契约类型再冻一次。
   */
  private static Set<AssetRightKind> rightKinds(JsonNode node) {
    Set<AssetRightKind> out = new LinkedHashSet<>();
    if (node == null || node.isNull()) {
      return out;
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("字段 acceptedRightKinds 必须是数组: " + node);
    }
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException("字段 acceptedRightKinds 的元素必须是文本: " + element);
      }
      out.add(AssetRightKind.parse(element.asText()));
    }
    return out;
  }

  /**
   * ★★ <b>D-032：{@code experience} 节点 → {@link ProductionExperienceUse}</b>。
   *
   * <p>缺节点/缺键一律取<b>显式中性</b>（{@link ProductionExperienceUse#NONE}、效率公式 {@link
   * ExpressionDefinition#NEUTRAL_PER_MILLE}）——"没配经验面"是合法世界形态（纯劳动方案）， ★ 与"公式求值失败"完全不同：后者抛 {@link
   * io.mosire.simos.production.contract.ProductionContractFault}。
   */
  private static ProductionExperienceUse experienceUse(JsonNode node) {
    if (node == null || node.isNull()) {
      return ProductionExperienceUse.NONE;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("字段 experience 必须是对象: " + node);
    }
    Optional<ExperienceTrackId> primary =
        optionalText(node, "primaryTrack").map(ExperienceTrackId::parse);
    Optional<ExperienceTrackId> shared =
        optionalText(node, "sharedTrack").map(ExperienceTrackId::parse);
    ExpressionDefinition efficiencyScale =
        expression(node.get("efficiencyScale"), ExpressionDefinition.NEUTRAL_PER_MILLE);
    long unlockAt = optionalLong(node, "unlockAtPrimaryExperience", 0L);
    // ★★ A 批：加成的"作用通道"必须能被明确配置（复核裁决 §3）；缺省 = 总生产效率倍率（用户例式语义）。
    ProductionEffectScope effectScope = effectScope(node.get("effectScope"));
    return new ProductionExperienceUse(primary, shared, effectScope, efficiencyScale, unlockAt);
  }

  /**
   * ★★ <b>A 批：{@code effectScope} 键 → 加成作用通道</b>（复核裁决 §3「方案若<b>明确配置</b>某个效果 作用于劳动效率，可按其声明计算」）。
   *
   * <p>★ 缺键/空串 ⇒ {@link ProductionEffectScope#TOTAL}（<b>默认 = 总生产效率倍率</b>，用户 U4 例式语义： 三通道都不乘 E
   * 求基准规模，再整体乘一次）。★ 显式配 {@code labor} 才是"只作用于劳动效率"的局部模型 —— ★★ <b>后果：瓶颈不在劳动（资产/原料更紧）时加成可能整段为
   * 0</b>（详见 {@link ProductionEffectScope} 类注的 `LABOR` 一节与 §13.8 裁定一末段）。 ★ 给了未知键 ⇒
   * 具名拒绝（<b>不</b>静默取默认：那会让配置错误变成"悄悄按另一种语义算"）。
   */
  private static ProductionEffectScope effectScope(JsonNode node) {
    if (node == null || node.isNull()) {
      return ProductionEffectScope.TOTAL;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("字段 effectScope 必须是字符串: " + node);
    }
    String key = node.asText();
    if (key.isBlank()) {
      return ProductionEffectScope.TOTAL;
    }
    ProductionEffectScope scope = ProductionEffectScope.fromKey(key);
    if (scope == null) {
      throw new IllegalArgumentException("字段 effectScope 不是已知通道（合法值：total / labor）: " + key);
    }
    return scope;
  }

  /**
   * ★★ <b>D-032：{@code availability} 数组 → 可用性条件表</b>（每条按 {@code @class} 判型， 同 {@code
   * AllocationRule} 的多态口径）。缺节点 ⇒ 空表（无条件）。
   */
  private static List<ProductionAvailabilityCondition> availabilityConditions(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("字段 availability 必须是数组: " + node);
    }
    List<ProductionAvailabilityCondition> conditions = new ArrayList<>();
    for (JsonNode item : node) {
      try {
        conditions.add(MAPPER.convertValue(item, ProductionAvailabilityCondition.class));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "availability 形状不对（见 ProductionAvailabilityCondition）: " + item, e);
      }
    }
    return conditions;
  }

  /**
   * ★★ <b>D-032：受限表达式节点 → {@link ExpressionDefinition}</b>（缺省取 {@code fallback}）。
   *
   * <p>★ 形状：{@code {"version":1,"inputs":["x","primary_mastery"],"root":{...}}}；{@code root} 按
   * {@code @class} 判型（见 {@link RestrictedExpression}）。缺 {@code version} ⇒ 现行版本。
   */
  private static ExpressionDefinition expression(JsonNode node, ExpressionDefinition fallback) {
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("公式节点必须是对象: " + node);
    }
    int version =
        node.hasNonNull("version")
            ? requireInt(node, "version")
            : ExpressionDefinition.CURRENT_VERSION;
    List<String> inputs = new ArrayList<>();
    JsonNode inputsNode = node.get("inputs");
    if (inputsNode != null && !inputsNode.isNull()) {
      if (!inputsNode.isArray()) {
        throw new IllegalArgumentException("公式的 inputs 必须是数组: " + node);
      }
      for (JsonNode input : inputsNode) {
        if (!input.isTextual() || input.asText().isBlank()) {
          throw new IllegalArgumentException("公式的 inputs 必须是非空字符串数组: " + node);
        }
        inputs.add(input.asText());
      }
    }
    JsonNode root = node.get("root");
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("公式必须给 root 对象: " + node);
    }
    RestrictedExpression tree;
    try {
      tree = MAPPER.convertValue(root, RestrictedExpression.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("公式 root 形状不对（见 RestrictedExpression）: " + root, e);
    }
    return new ExpressionDefinition(version, inputs, tree);
  }

  /** 生产方式节点：{@code {id,name,version?,classStructureId}}；version 缺省 = {@code null}（由命令层按旧语义补）。 */
  static ModePayload mode(JsonNode node) {
    ProductionModeId id = ProductionModeId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    Integer version = node.hasNonNull("version") ? optionalInt(node, "version") : null;
    ClassStructureId classStructureId =
        ClassStructureId.parse(requireText(node, "classStructureId"));
    return new ModePayload(id, name, version, classStructureId);
  }

  /** 生产方式载荷：version 可为空（命令层按"新 id 缺省 1 / 既有 id 缺省沿用"补）。 */
  record ModePayload(
      ProductionModeId id, String name, Integer version, ClassStructureId classStructureId) {}

  // ── 形状助手（与 EconomyPayloads 同口径）──────────────────────────────────────────────

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return value.asText();
  }

  private static long requireLong(JsonNode node, String field) {
    return requireIntegral(node.get(field), field);
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

  private static int optionalInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔值: " + node);
    }
    return value.asBoolean();
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return Optional.of(value.asText());
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

  /** {@code AssetKind} 词表解析的唯一入口（旧 {@code TOOL} ⇒ {@code asset-kind-retired:TOOL}）。 */
  private static AssetKind assetKind(String text, String field) {
    try {
      return AssetKind.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "字段 " + field + " 的键不是生产资料种类: " + text + "；" + e.getMessage(), e);
    }
  }

  private static Map<AssetKind, Long> assetMap(JsonNode object, String field) {
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
              AssetKind kind = assetKind(entry.getKey(), field);
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "." + entry.getKey() + " 必须是商品表（对象）: " + entry.getValue());
              }
              out.put(kind, commodityMap(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

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

  private static Map<CurrencyId, Long> currencyMap(JsonNode object, String field) {
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
}
