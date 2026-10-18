package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyPayloadSupport.actorRef;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.assetKind;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.enumValue;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalText;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireBoolean;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireIntegral;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 阶层/关系/政策/政府与货币发行的机械解析 helper（P0.5-C2）。 */
final class EconomyPolicyPayloads {

  // ── P1：E1/E2 完整经济地基的可选载荷（缺键 ⇒ 空表；词表/范围守卫复用领域构造期）────────────

  // ★★★ B5（§13.15.2 裁定十八）：原 `parseModes(JsonNode)`（顶层可选 `modes` 数组 → economy 的
  //   生产方式定义副本）**整方法删除** —— 定义权威唯一在 production（`production.Seed` 的 `modes[]`
  //   经 `ProductionSeedHandler` 落 `ProductionData.modes`），economy 侧不再解析它。

  /**
   * ★★ <b>顶层可选 {@code classStructures} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"id","modeId","positions":[...],"defaultSharesPerMille":{...}}}； {@code positions}
   * 非空/重复键由构造期守卫与解析层判死，{@code defaultSharesPerMille} 可缺省 ⇒ 空表。
   */
  static Map<ClassStructureId, ClassStructure> parseClassStructures(JsonNode payload) {
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classStructures")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classStructures 的每项必须是对象: " + node);
      }
      ClassStructureId id = ClassStructureId.parse(requireText(node, "id"));
      ProductionModeId modeId = ProductionModeId.parse(requireText(node, "modeId"));
      Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>();
      for (JsonNode positionNode : requireArray(node, "positions")) {
        ProductionRole position = productionRole(positionNode);
        if (positions.putIfAbsent(position.id(), position) != null) {
          throw new IllegalArgumentException(
              "同一份 classStructure 里 position id 重复: " + position.id());
        }
      }
      Map<ClassPositionId, Long> shares =
          productionRoleShareMap(
              optionalObject(node, "defaultSharesPerMille"),
              "classStructures[].defaultSharesPerMille");
      ClassStructure structure = new ClassStructure(id, modeId, positions, shares);
      if (structures.putIfAbsent(id, structure) != null) {
        throw new IllegalArgumentException("同一份载荷里 classStructure id 重复: " + id);
      }
    }
    return structures;
  }

  /**
   * ★★ <b>顶层可选 {@code classPositions} 数组</b>（缺键 ⇒ 空表）。形状与 classStructures 内嵌的位置逐字相同；
   * 两条路径都构造同值对象，随后由 {@link EconomyData} 判"结构内位置 == 全局位置表"。
   */
  static Map<ClassPositionId, ProductionRole> parseProductionRoles(JsonNode payload) {
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classPositions")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classPositions 的每项必须是对象: " + node);
      }
      ProductionRole position = productionRole(node);
      if (positions.putIfAbsent(position.id(), position) != null) {
        throw new IllegalArgumentException("同一份载荷里 classPosition id 重复: " + position.id());
      }
    }
    return positions;
  }

  /** 一个阶层位置节点：id/modeId/name + 三个结构维词表 + 可选 ruleExtensions（缺键 ⇒ 空表）。 */
  static ProductionRole productionRole(JsonNode node) {
    return productionRole(node, null);
  }

  /**
   * 一个阶层位置节点（同 {@link #productionRole(JsonNode)}），{@code modeId} 可缺省为 {@code defaultModeId}。
   *
   * <p>★ P7 起 {@code economy.GmAdjust} 的两个 class 结构编辑 kind 复用本解析：{@code upsertClassStructure}
   * 的每个位置在 {@code classStructure.modeId} 缺省时取结构自身 modeId（给了则逐值参与后续一致性判据）；播种路径仍以 {@code null} 调本方法 ⇒
   * {@code modeId} 必填的旧行为逐字不变。
   */
  static ProductionRole productionRole(JsonNode node, String defaultModeId) {
    ClassPositionId id = ClassPositionId.parse(requireText(node, "id"));
    ProductionModeId modeId =
        node.hasNonNull("modeId")
            ? ProductionModeId.parse(requireText(node, "modeId"))
            : ProductionModeId.parse(
                defaultModeId == null ? requireText(node, "modeId") : defaultModeId);
    ProductionRole.RelationToMeans relationToMeans =
        enumValue(
            ProductionRole.RelationToMeans.class,
            requireText(node, "relationToMeans"),
            "classPositions[].relationToMeans");
    ProductionRole.LaborRole laborRole =
        enumValue(
            ProductionRole.LaborRole.class,
            requireText(node, "laborRole"),
            "classPositions[].laborRole");
    ProductionRole.SurplusRole surplusRole =
        enumValue(
            ProductionRole.SurplusRole.class,
            requireText(node, "surplusRole"),
            "classPositions[].surplusRole");
    Map<String, String> extensions =
        stringMap(optionalObject(node, "ruleExtensions"), "classPositions[].ruleExtensions");
    return new ProductionRole(
        id, modeId, requireText(node, "name"), relationToMeans, laborRole, surplusRole, extensions);
  }

  /**
   * ★★ <b>顶层可选 {@code classStandings} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"householdId","originalPositionId","currentPositionId","participatingPositionIds"?,"retainedShares"?,
   * "consecutiveDebtStressCycles"?,"lastTransitionDay"?,"reason"?}}；数值可缺省，reason 缺省空串。 ★
   * P2-B：{@code participatingPositionIds} 是当前位置之外**追加**参与的生产位置（缺键/空数组 = 只参与当前位置）。 引用完整性（家户存在、位置存在）由
   * {@link EconomyData} 构造期守卫判。
   */
  static Map<HouseholdId, HouseholdClassMembership> parseClassMemberships(JsonNode payload) {
    Map<HouseholdId, HouseholdClassMembership> classMemberships = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classStandings")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classStandings 的每项必须是对象: " + node);
      }
      HouseholdId householdId = HouseholdId.parse(requireText(node, "householdId"));
      ClassPositionId original = ClassPositionId.parse(requireText(node, "originalPositionId"));
      ClassPositionId current = ClassPositionId.parse(requireText(node, "currentPositionId"));
      Set<ClassPositionId> participating =
          optionalProductionRoleIds(node, "participatingPositionIds");
      Map<ClassPositionId, Long> retainedShares =
          productionRoleShareMap(
              optionalObject(node, "retainedShares"), "classStandings[].retainedShares");
      long consecutiveDebtStressCycles = optionalLong(node, "consecutiveDebtStressCycles", 0L);
      long lastTransitionDay = optionalLong(node, "lastTransitionDay", 0L);
      String reason = optionalText(node, "reason").orElse("");
      HouseholdClassMembership classMembership =
          new HouseholdClassMembership(
              householdId,
              original,
              current,
              participating,
              retainedShares,
              consecutiveDebtStressCycles,
              lastTransitionDay,
              reason);
      if (classMemberships.putIfAbsent(householdId, classMembership) != null) {
        throw new IllegalArgumentException("同一份载荷里 classStanding 家户重复: " + householdId);
      }
    }
    return classMemberships;
  }

  /** 可选的位置 id 数组（缺键 / null ⇒ 空集；元素必须是非空白文本，走 {@link ClassPositionId#parse}）。 */
  static Set<ClassPositionId> optionalProductionRoleIds(JsonNode node, String field) {
    JsonNode values = node.get(field);
    if (values == null || values.isNull()) {
      return Set.of();
    }
    if (!values.isArray()) {
      throw new IllegalArgumentException("classStandings[]." + field + " 必须是数组: " + values);
    }
    Set<ClassPositionId> positions = new LinkedHashSet<>();
    for (JsonNode element : values) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(
            "classStandings[]." + field + " 的元素必须是非空白文本: " + element);
      }
      positions.add(ClassPositionId.parse(element.asText()));
    }
    return positions;
  }

  /**
   * ★★ <b>顶层可选 {@code assetRules} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"modeId","assetKind","isCoreMeans","rentRule"?,"transferRule"}}；id 由 {@code
   * AssetRuleId.idOf(modeId, assetKind)} 派生，若显式给了 {@code id} 必须与派生值一致。
   */
  static Map<AssetRuleId, AssetRule> parseAssetRules(JsonNode payload) {
    Map<AssetRuleId, AssetRule> rules = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "assetRules")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("assetRules 的每项必须是对象: " + node);
      }
      ProductionModeId modeId = ProductionModeId.parse(requireText(node, "modeId"));
      AssetKind assetKind = assetKind(requireText(node, "assetKind"), "assetRules[].assetKind");
      boolean isCoreMeans = requireBoolean(node, "isCoreMeans");
      Optional<RentRule> rentRule =
          node.hasNonNull("rentRule")
              ? Optional.of(rentRule(requireObject(node, "rentRule")))
              : Optional.empty();
      TransferRule transferRule = transferRule(requireObject(node, "transferRule"));
      AssetRuleId derived = AssetRuleId.idOf(modeId, assetKind);
      if (node.hasNonNull("id")) {
        AssetRuleId declared = AssetRuleId.parse(requireText(node, "id"));
        if (!declared.equals(derived)) {
          throw new IllegalArgumentException(
              "assetRules[].id 必须与 (modeId, assetKind) 派生值一致（不许手写第二份身份）：声明="
                  + declared
                  + " 派生="
                  + derived);
        }
      }
      AssetRule rule =
          new AssetRule(derived, modeId, assetKind, isCoreMeans, rentRule, transferRule);
      if (rules.putIfAbsent(derived, rule) != null) {
        throw new IllegalArgumentException("同一份载荷里 assetRule id 重复: " + derived);
      }
    }
    return rules;
  }

  /** 一条租金模板节点：type/priority/legs；leg 的 kind/rate/fixed 与商品/币种二选一由构造期守卫判。 */
  static RentRule rentRule(JsonNode node) {
    RentRule.RentType type = RentRule.RentType.parse(requireText(node, "type"));
    int priority = requireInt(node, "priority");
    List<RentRule.RentLeg> legs = new ArrayList<>();
    for (JsonNode legNode : requireArray(node, "legs")) {
      if (!legNode.isObject()) {
        throw new IllegalArgumentException("assetRules[].rentRule.legs 的每项必须是对象: " + legNode);
      }
      RentRule.RentType kind = RentRule.RentType.parse(requireText(legNode, "kind"));
      int ratePerMille = optionalInt(legNode, "ratePerMille", 0);
      long fixedAmount = optionalLong(legNode, "fixedAmount", 0L);
      Optional<CommodityId> commodity = optionalText(legNode, "commodity").map(CommodityId::parse);
      Optional<CurrencyId> currency = optionalText(legNode, "currency").map(CurrencyId::parse);
      legs.add(new RentRule.RentLeg(kind, ratePerMille, fixedAmount, commodity, currency));
    }
    return new RentRule(type, priority, legs);
  }

  /** 一条转移规则节点（三个布尔都必填；构造期无额外不变量，形状即语义）。 */
  static TransferRule transferRule(JsonNode node) {
    return new TransferRule(
        requireBoolean(node, "transferable"),
        requireBoolean(node, "requiresOwnerConsent"),
        requireBoolean(node, "allowSublease"));
  }

  /** 位置 id → 千分比/数量表（用于 classStructures.defaultSharesPerMille 与 classStandings.retainedShares）。 */
  static Map<ClassPositionId, Long> productionRoleShareMap(JsonNode object, String field) {
    Map<ClassPositionId, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry ->
                out.put(
                    ClassPositionId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), field + "." + entry.getKey())));
    return out;
  }

  /** 字符串表（用于 {@code ProductionRole.ruleExtensions}）；值非文本 ⇒ 抛。 */
  static Map<String, String> stringMap(JsonNode object, String field) {
    Map<String, String> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(field + " 的键不得为空白: " + entry.getKey());
              }
              JsonNode value = entry.getValue();
              if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException(
                    field + "." + entry.getKey() + " 必须是字符串: " + value);
              }
              out.put(entry.getKey(), value.asText());
            });
    return out;
  }

  // ── 生产关系（T2；计划 R3/R8）────────────────────────────────────────────────────────

  /**
   * ★★ <b>一个 unit 的 {@code relation}</b>（可选键）：缺 ⇒ 按 {@code regime} 推导；给了 ⇒ 逐值采纳 + {@code operator}
   * 与 unit.operator 的一致性判死（R3B.2 起关系挂 unit）。
   *
   * <p>★★ <b>H0：推导还要一个"这批家户住哪种居住类型"</b>（cohort 键的居住维）—— 它的**唯一来源是同一条 entry 的配额表** （{@link
   * ResidenceKind#ofLot}），由调用方算好传进来（本方法看不见整条 entry）。★ <b>显式给了 {@code relation} 时它不参与</b>：
   * 那时受方是载荷逐字写出的 cohort 串（自带居住段），不推导、也不校验（"载荷说什么就是什么"）。
   *
   * @param activity 关系挂的那个 unit（键 = 它，铁律 1）
   * @param operator 该 unit 的经营者（显式 relation 里的 operator 必须与它逐值相等）
   */
  static ProductionRules relation(
      JsonNode relationNode,
      IndustryDefinitionView industry,
      ProductionUnitId activity,
      ActorRef operator,
      Set<ResidenceKind> residences) {
    if (relationNode == null || relationNode.isNull()) {
      return RegimeRelations.defaultRelation(
          industry.regime(), activity, industry.id(), operator, residences);
    }
    JsonNode operatorNode = optionalObject(relationNode, "operator");
    ActorRef relationOperator = operatorNode == null ? operator : actorRef(operatorNode);
    // ★★ 载荷边缘的一致性判据（R3 的第 2 条；状态层还有同一条守卫 —— 两层都在是**有意**的）。
    if (!relationOperator.equals(operator)) {
      throw new IllegalArgumentException(
          "relation.operator 必须与 unit.operator 一致（同一件事不许两处拼写）：关系="
              + relationOperator
              + "，unit="
              + operator
              + "（unit "
              + activity
              + "，产业 "
              + industry.id()
              + "）");
    }
    JsonNode residualNode = optionalObject(relationNode, "residualOwner");
    ActorRef residualOwner = residualNode == null ? operator : actorRef(residualNode);
    // ★★ **H3（裁定 C3）：投入由谁出** —— 可选键，形状与补偿规则的 recipient 逐字同款（`{actor:{kind,id}}` 或
    //   `{cohort:"0_0|rural|landlord"}`，恰给其一）。★ **缺键 ⇒ 取 operator**（= 四档默认，见 RegimeRelations）：
    //   这是旧档兼容的那一处边缘 —— H3 之前的 relation JSON 没有这个键，而"旧档读不回来"不是兼容，是事故。
    //   ★ 缺省**不在这里另写一遍值**：交给 ProductionRules 的构造期缺省（null ⇒ ToActor(operator)），
    //     一处拼写点（本层只解析"给了什么"，不发明"没给时是什么"）。
    JsonNode inputSupplierNode = optionalObject(relationNode, "inputSupplier");
    Payee inputSupplier =
        inputSupplierNode == null ? null : recipient(inputSupplierNode, "inputSupplier");
    List<CompensationRule> rules = new ArrayList<>();
    for (JsonNode rule : optionalArray(relationNode, "rules")) {
      rules.add(compensationRule(rule));
    }
    // ★ S1：劳动来源（可选键；缺省留给 ProductionRules 的构造期兜底 SELF，与旧档口径一致）。
    LaborSource laborSource =
        relationNode.hasNonNull("laborSource")
            ? LaborSource.parse(requireText(relationNode, "laborSource"))
            : null;
    return new ProductionRules(
        activity, operator, inputSupplier, rules, residualOwner, laborSource);
  }

  /**
   * ★★ <b>一个受方</b>（{@code {actor:{kind,id}}} 恰给其一，或 {@code {cohort:"<CohortKey 规范串>"}}）—— 补偿规则的
   * {@code recipient} 与关系的 {@code inputSupplier}（H3）<b>共用本方法</b>。
   *
   * <p>★ <b>抽出来的是形状与拒因，不是文案</b>：两个调用点的消息里都带字段名（{@code what}），于是"哪个键写歪了"一眼可见；
   * 少了这一步，同一套"恰其一"的规则就会在第二处再写一遍（本仓明令禁止的第二拼写点）。
   *
   * @param node 受方节点（非 null；调用方已确认它是对象）
   * @param what 字段名（进错误消息；如 {@code "recipient"} / {@code "inputSupplier"}）
   */
  static Payee recipient(JsonNode node, String what) {
    JsonNode actorNode = optionalObject(node, "actor");
    boolean hasCohort = node.hasNonNull("cohort");
    boolean hasHousehold = node.hasNonNull("household");
    int given = (actorNode != null ? 1 : 0) + (hasCohort ? 1 : 0) + (hasHousehold ? 1 : 0);
    if (given != 1) {
      throw new IllegalArgumentException(
          what + " 必须恰给 actor / household / cohort 之一（给 " + given + " 个）: " + node);
    }
    if (actorNode != null) {
      return new Payee.ToActor(actorRef(actorNode));
    }
    if (hasHousehold) {
      return new Payee.ToHousehold(HouseholdId.parse(requireText(node, "household")));
    }
    return new Payee.ToCohort(CohortKey.parse(requireText(node, "cohort")));
  }

  /**
   * 一条补偿规则：{@code {type, recipient:{actor|cohort}, pool, weight?, basis?, ratePerMille,
   * fixedAmount, commodity?, currency?, priority}}。
   *
   * <p>★ <b>受方"恰其一"</b>：两个变体都没给 / 都给了 ⇒ 抛（契约里它是**类型事实**，载荷这一层负责把它喂对）。 ★ <b>{@code commodity} 缺键 =
   * 货币档</b>（空 {@code Optional}）—— 与 {@link CompensationRule} 的二选一守卫
   * 同源，本层不重复判它（违反了那条守卫会由契约自己抛，消息更准）。
   *
   * <p>★★ <b>H2：{@code pool} × {@code weight} 与旧档的 {@code basis} 都收</b>（详见类注）—— 这是"旧档不许当场抛"
   * 那条纪律的落点：真档的关系载荷全是 {@code basis}，少了这条翻译，整个真档播不出来。
   */
  static CompensationRule compensationRule(JsonNode node) {
    JsonNode payeeNode = optionalObject(node, "recipient");
    if (payeeNode == null) {
      throw new IllegalArgumentException("补偿规则的字段 recipient 必须是对象: " + node);
    }
    // ★ H3 起受方的解析与关系的 inputSupplier **共用同一处**（见 recipient）：同一套"恰其一"的规则只有一个拼写点。
    Payee recipient = recipient(payeeNode, "recipient");
    RuleType type = RuleType.parse(requireText(node, "type"));
    return new CompensationRule(
        type,
        recipient,
        poolOf(node),
        weightOf(node),
        requireInt(node, "ratePerMille"),
        requireLong(node, "fixedAmount"),
        optionalText(node, "commodity").map(CommodityId::parse),
        currencyOf(node, type),
        requireInt(node, "priority"));
  }

  /**
   * ★★ <b>池：新档 {@code pool}、旧档 {@code basis}（H2 之前的关系载荷全是这一形状）</b>—— 旧字面量经 {@link Basis#pool()}
   * 翻译（映射表在 {@code Basis} 的类注里）。
   *
   * <p>★ <b>两个键都没给 ⇒ 抛</b>（缺一个必填字段是坏载荷，不是缺省）；★ <b>两个都给了 ⇒ 也抛</b>（不猜：同一件事的两处拼写不一致时， 没有哪一处能判谁对 —— 同
   * {@code operator} 那条一致性强判的口径）。
   */
  static Pool poolOf(JsonNode node) {
    Optional<String> pool = optionalText(node, "pool");
    Optional<String> basis = optionalText(node, "basis");
    if (pool.isPresent() && basis.isPresent()) {
      throw new IllegalArgumentException(
          "关系规则不得同时给 pool 与 basis（H2 起 pool+weight 是新档、basis 是旧档，两者只能给一个）: " + node);
    }
    if (pool.isPresent()) {
      return Pool.parse(pool.get());
    }
    if (basis.isPresent()) {
      return Basis.parse(basis.get()).pool();
    }
    throw new IllegalArgumentException("关系规则缺 pool（H2 起的必填键；旧档写 basis）: " + node);
  }

  /** ★★ <b>权重：新档 {@code weight}（缺省 {@code NONE}）、旧档 {@code basis}</b>—— 同 {@link #poolOf} 的口径。 */
  static Weight weightOf(JsonNode node) {
    Optional<String> weight = optionalText(node, "weight");
    Optional<String> basis = optionalText(node, "basis");
    if (weight.isPresent() && basis.isPresent()) {
      throw new IllegalArgumentException(
          "关系规则不得同时给 weight 与 basis（H2 起 pool+weight 是新档、basis 是旧档，两者只能给一个）: " + node);
    }
    if (weight.isPresent()) {
      return Weight.parse(weight.get());
    }
    if (basis.isPresent()) {
      return Basis.parse(basis.get()).weight();
    }
    // ★ 给了 pool 而没给 weight ⇒ NONE（"不分"是绝大多数规则的那一档；旧档走不到这里，它在上面就返回了）。
    return Weight.NONE;
  }

  /**
   * ★★ <b>币种（H2 的币种位）</b>：实物档不得给、货币档必须有 —— 货币档缺键时取<b>出厂货币</b> （{@link
   * RegimeRelations#DEFAULT_CURRENCY} 是唯一拼写点）。
   *
   * <p>★ <b>为什么缺键是"取出厂值"而不是"抛"</b>：旧档的货币规则<b>没有这个键</b>（币种位是 H2 才有的）， 而"1000 毫钱"在旧档里本来就没有说是哪种钱 ⇒
   * 翻译成出厂货币是**旧档兼容**，不是猜（真值随 S2 的货币口径定）。 ★ 实物档给了币种 ⇒ 抛（那是坏数据：实物不是钱）。
   */
  static Optional<CurrencyId> currencyOf(JsonNode node, RuleType type) {
    Optional<CurrencyId> currency = optionalText(node, "currency").map(CurrencyId::parse);
    if (!type.money()) {
      if (currency.isPresent()) {
        throw new IllegalArgumentException(
            "实物规则不得带 currency（"
                + type
                + "）：currency="
                + currency.get()
                + " —— 实物档恒空，币种只对货币档合法: "
                + node);
      }
      return Optional.empty();
    }
    return currency.isPresent() ? currency : Optional.of(RegimeRelations.DEFAULT_CURRENCY);
  }

  // ── 劳动供给 / 劳动分配（R2，设计稿 §四）──────────────────────────────────────────────

  /**
   * 一份劳动供给：{@code {group, period, grossLaborMilli, servedLaborMilli?, committedLaborMilli?}}。
   *
   * <p>★ **毛额由载荷给**（不由本层从人口算）：人口与年龄性别住在 social 的 {@code PopulationGroup}，economy 编译期不认识它 （设计稿
   * §二/§八.1）—— 算出毛额的是 {@code EconomySeeder}（它以 R1 已落地的年龄×性别系数表折算）。
   */

  /**
   * ★★ <b>R3B.2：配额 activity → unit</b>。新载荷必须直接给 unit id；旧载荷给活动标签（{@code farm}）或旧产业串 ⇒ 按 {@code
   * actor.id()} 当产业串找**唯一** unit 改写；多个候选抛（新载荷必须写 unit id，不猜）；解析不到 ⇒ 原样 （自由家户劳动，不喂任何生产，旧档同义）。
   */
  /**
   * ★★ E6a：顶层可选 {@code modeTransitions} 数组（缺键 ⇒ 空表；旧载荷逐值不变）。每项：
   *
   * <pre>{@code
   * {"organizationId":"org-…","fromModeId":"…","toModeId":"…","retainOriginalPerMille":400,
   *  "requestedDay":120,"effectiveDay":121,"status":"PENDING","reason":"…","id":"mt-…"?}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link ModeTransitionId#idOf(ProductionOrganizationId,
   * ProductionModeId, long)} 一致（不一致 ⇒ 抛）；不给就派生。★ 同一数组里派生 id 重复 ⇒ 抛（同一请求不得写两条）。 组织/mode 引用与"同一组织至多一条
   * PENDING"由 {@code EconomyData} 构造期守卫判。
   */
  static Map<ModeTransitionId, ModeTransition> modeTransitions(JsonNode payload) {
    Map<ModeTransitionId, ModeTransition> transitions = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "modeTransitions")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("modeTransitions 的每项必须是对象: " + node);
      }
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.parse(requireText(node, "organizationId"));
      ProductionModeId fromModeId = ProductionModeId.parse(requireText(node, "fromModeId"));
      ProductionModeId toModeId = ProductionModeId.parse(requireText(node, "toModeId"));
      int retainOriginalPerMille = requireInt(node, "retainOriginalPerMille");
      long requestedDay = requireLong(node, "requestedDay");
      long effectiveDay = requireLong(node, "effectiveDay");
      ModeTransition.Status status =
          enumValue(
              ModeTransition.Status.class, requireText(node, "status"), "modeTransitions[].status");
      String reason = optionalText(node, "reason").orElse("");
      ModeTransitionId derived = ModeTransitionId.idOf(organizationId, toModeId, effectiveDay);
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(ModeTransitionId.parse(id))) {
                  throw new IllegalArgumentException(
                      "modeTransitions[].id 必须与 (organizationId, toModeId, effectiveDay) 派生值一致: id="
                          + id
                          + "，派生="
                          + derived);
                }
              });
      ModeTransition transition =
          new ModeTransition(
              derived,
              organizationId,
              fromModeId,
              toModeId,
              retainOriginalPerMille,
              requestedDay,
              effectiveDay,
              status,
              reason);
      if (transitions.putIfAbsent(derived, transition) != null) {
        throw new IllegalArgumentException("同一份载荷里模式变迁 id 重复: " + derived);
      }
    }
    return transitions;
  }

  /**
   * ★★ E6a：顶层可选 {@code classShares} 数组（缺键 ⇒ 空表）。每项：
   *
   * <pre>{@code
   * {"transitionId":"mt-…","householdId":"hh-…","classPositionId":"…","sharePerMille":400,"id":"cs-…"?}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link ClassShareId#idOf(ModeTransitionId, HouseholdId,
   * ClassPositionId)} 一致（不一致 ⇒ 抛）；不给就派生。同一 {@code (transitionId, householdId)} 的 Σ =
   * 1000‰、变迁/家户/位置引用完整性由 {@code EconomyData} 构造期守卫判。
   */
  static Map<ClassShareId, ClassShare> classShares(JsonNode payload) {
    Map<ClassShareId, ClassShare> shares = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classShares")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classShares 的每项必须是对象: " + node);
      }
      ModeTransitionId transitionId = ModeTransitionId.parse(requireText(node, "transitionId"));
      HouseholdId householdId = HouseholdId.parse(requireText(node, "householdId"));
      ClassPositionId classPositionId = ClassPositionId.parse(requireText(node, "classPositionId"));
      long sharePerMille = requireLong(node, "sharePerMille");
      ClassShareId derived = ClassShareId.idOf(transitionId, householdId, classPositionId);
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(ClassShareId.parse(id))) {
                  throw new IllegalArgumentException(
                      "classShares[].id 必须与 (transitionId, householdId, classPositionId) 派生值一致: id="
                          + id
                          + "，派生="
                          + derived);
                }
              });
      ClassShare share =
          new ClassShare(derived, transitionId, householdId, classPositionId, sharePerMille);
      if (shares.putIfAbsent(derived, share) != null) {
        throw new IllegalArgumentException("同一份载荷里阶层保留份额 id 重复: " + derived);
      }
    }
    return shares;
  }

  /**
   * 顶层可选 {@code governments}：{@code
   * [{id,nationRef,treasury:{kind,id},issuable:[币种…],mintScalePerCycle?}]}。
   *
   * <p>缺键 ⇒ 空表（旧载荷没有政府 ⇒ 零登记，旧 fail-closed 行为逐字不变）；一个币种只能有一个发行主体由 {@code EconomyData} 的构造期守卫判死。
   */
  static Map<GovernmentId, Government> governments(JsonNode payload) {
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "governments")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("governments 的每项必须是对象: " + node);
      }
      GovernmentId id = GovernmentId.parse(requireText(node, "id"));
      String nationRef = requireText(node, "nationRef");
      JsonNode treasuryNode = requireObject(node, "treasury");
      ActorRef treasury = actorRef(treasuryNode);
      Set<CurrencyId> issuable = new LinkedHashSet<>();
      for (JsonNode currencyNode : optionalArray(node, "issuable")) {
        if (!currencyNode.isTextual() || currencyNode.asText().isBlank()) {
          throw new IllegalArgumentException("governments[].issuable 的每项必须是非空币种字符串: " + node);
        }
        issuable.add(CurrencyId.parse(currencyNode.asText()));
      }
      // ★★★ W5（2026-10-11）：双轨合并 —— 铸币政策旋钮只剩 `mintScalePerCycle` 一个字段；
      //   `seignioragePerCycle` / `debtIssuePerCycle` 两个旧键**直接删**（§一.11：旧设计不留兼容位）。
      //   ★ "上一轮印量"不是载荷字段：它是结算写的事实（MintSettlement 每轮关账覆盖写）⇒ 走 Government 的
      //     缺省空表，播种出来的政府还没有过任何铸币轮。
      long mintScalePerCycle = optionalLong(node, "mintScalePerCycle", 0L);
      if (mintScalePerCycle < 0L) {
        throw new IllegalArgumentException(
            "governments[].mintScalePerCycle 不得为负（0 = 政策不许铸币）: " + mintScalePerCycle);
      }
      Government government = new Government(id, nationRef, treasury, issuable, mintScalePerCycle);
      if (governments.putIfAbsent(id, government) != null) {
        throw new IllegalArgumentException("同一份载荷里政府 id 重复: " + id);
      }
    }
    return governments;
  }

  /**
   * 顶层可选 {@code moneyIssuances}：{@code
   * [{id,governmentId,day?,period?,currency,amount,kind,reason}]}。
   *
   * <p>缺 {@code day} ⇒ 取命令锚点 {@code at.tick()}；缺 {@code period} ⇒ 1（创世周期，与 {@code
   * EconomySeeder.FIRST_PERIOD} 同值，但载荷边缘不复用 app 常量）；缺键 ⇒ 空表。
   */
  static Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances(
      JsonNode payload, SimosTimestamp at, Map<GovernmentId, Government> governments) {
    Map<MoneyIssuanceId, MoneyIssuanceRecord> records = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "moneyIssuances")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("moneyIssuances 的每项必须是对象: " + node);
      }
      MoneyIssuanceId id = MoneyIssuanceId.parse(requireText(node, "id"));
      GovernmentId governmentId = GovernmentId.parse(requireText(node, "governmentId"));
      if (!governments.containsKey(governmentId)) {
        throw new IllegalArgumentException(
            "发行记录指名的政府不存在（同一份载荷内）：记录=" + id + "，governmentId=" + governmentId);
      }
      long day = optionalLong(node, "day", at.tick());
      long period = optionalLong(node, "period", 1L);
      CurrencyId currency = CurrencyId.parse(requireText(node, "currency"));
      long amount = requireLong(node, "amount");
      MoneyIssuanceKind kind = issuanceKind(requireText(node, "kind"));
      String reason = requireText(node, "reason");
      MoneyIssuanceRecord record =
          new MoneyIssuanceRecord(id, governmentId, day, period, currency, amount, kind, reason);
      if (records.putIfAbsent(id, record) != null) {
        throw new IllegalArgumentException("同一份载荷里发行记录 id 重复: " + id);
      }
    }
    return records;
  }

  static MoneyIssuanceKind issuanceKind(String text) {
    try {
      return MoneyIssuanceKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知的 MoneyIssuanceKind: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(MoneyIssuanceKind.values()),
          e);
    }
  }
}
