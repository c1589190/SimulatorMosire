package io.mosire.simos.economy.codec;

import static io.mosire.simos.economy.codec.EconomyCodecSupport.actorNode;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.actorOf;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.deltaEntries;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.ensureObjectField;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.idNode;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.iterableFields;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.longOf;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.objectField;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.textOf;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.textOfId;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.production.contract.ProductionLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 旧生产节点/变更集的机械迁移 codec helper（P0.5-C2）。 */
final class EconomyProductionCodecs {

  /** {@link ProductionUnitId#idOf} 生成的新档 unit id 前缀；只用于**旧档变更集整形**的幂等判别。 */
  static final String PRODUCTION_UNIT_ID_PREFIX = "unit-";

  // ── R3B.2：旧档 production 键/值 reshape（snapshot 与 changeset 两版）──────────────────────

  /**
   * ★★ <b>R3B.2 旧档 {@link EconomyData} 的生产部分整形</b>（在 {@link #migrateLegacyOwnershipStakeComponent}
   * 之后跑）：
   *
   * <ol>
   *   <li>旧 {@code industries.<id>} 节点带 {@code operator/capacity/progressDays/cycleLaborMilli/
   *       cycleInputUsedMilli} ⇒ 合成一条默认 {@code units.<unitId>}（{@code idOf(industry, operator)}、
   *       {@code modeKey = industryId}、进度/劳动/投入原样），并从 {@code capacity}（含 0 值）合成整额 OWNED {@code
   *       assetShares}；然后把这些旧键从 industry 节点摘掉（新 {@code IndustryDefinitionView} 模板没有它们，不摘会被严格读入拒）；
   *   <li>{@code relations} 的键与 {@code activity}（旧 = industry 串）按 {@code (industry, operator)} 对齐到
   *       unit； 解析不到 unit 的条目丢弃（没有 unit ⇒ 没有生产活动，等价于旧档产能 0）；
   *   <li>{@code operatorConditions} 的键按值内 {@code industry} 对齐到唯一 unit（0 条丢弃、多条 fail-closed）；
   *   <li>{@code allocations[].activity} 从旧活动标签/旧 industry 串改写为 unit id（解析不到 ⇒ 原样，自由家户劳动）。
   * </ol>
   *
   * <p>★ <b>幂等</b>：新形状（industry 模板 + units + unit 键关系）再跑一遍不改任何节点。
   */
  static ObjectNode migrateLegacyProductionComponents(ObjectNode node) {
    ObjectNode industries = objectField(node, "industries");
    if (industries == null) {
      return node;
    }
    ObjectNode assetShares = ensureObjectField(node, "assetShares");
    ObjectNode units = ensureObjectField(node, "units");
    Map<String, JsonNode> legacyOperators = new LinkedHashMap<>();
    List<String> industryKeys = new ArrayList<>();
    industries.fieldNames().forEachRemaining(industryKeys::add);
    for (String industryKey : industryKeys) {
      JsonNode value = industries.get(industryKey);
      if (!(value instanceof ObjectNode industry) || !isLegacyIndustry(industry)) {
        continue;
      }
      JsonNode operatorNode = industry.get("operator");
      if (operatorNode == null || !operatorNode.isObject()) {
        ActorRef fallback =
            RegimeOperators.defaultOperator(
                new RegimeId(textOfId(industry.get("regime"))), new IndustryId(industryKey));
        operatorNode = actorNode(fallback.kind().name(), fallback.id());
      }
      ActorRef operator = actorOf(operatorNode);
      ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(industryKey), operator);
      legacyOperators.put(industryKey, operatorNode.deepCopy());
      if (!hasShareForIndustry(assetShares, industryKey)) {
        synthesizeOwnedShares(assetShares, industryKey, operatorNode, industry.get("capacity"));
        if (!hasShareForIndustry(assetShares, industryKey)) {
          // capacity 与 capacityPerUnit 都空 ⇒ 没有任何实物可登记 ⇒ 不造 unit（旧档规模恒 0 的等价路径）
          industry.remove("operator");
          industry.remove("capacity");
          industry.remove("progressDays");
          industry.remove("cycleLaborMilli");
          industry.remove("cycleInputUsedMilli");
          continue;
        }
      }
      JsonNode inputUsed = industry.get("cycleInputUsedMilli");
      ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
      unitNode.put("id", unitId.value());
      unitNode.set("industry", idNode(industryKey));
      unitNode.set("operator", operatorNode.deepCopy());
      unitNode.put("modeKey", industryKey);
      unitNode.put("progressDays", longOf(industry.get("progressDays"), 0L));
      unitNode.put("cycleLaborMilli", longOf(industry.get("cycleLaborMilli"), 0L));
      unitNode.set(
          "cycleInputUsedMilli",
          inputUsed != null && inputUsed.isObject()
              ? inputUsed.deepCopy()
              : JsonNodeFactory.instance.objectNode());
      units.putIfAbsent(unitId.value(), unitNode);
      industry.remove("operator");
      industry.remove("capacity");
      industry.remove("progressDays");
      industry.remove("cycleLaborMilli");
      industry.remove("cycleInputUsedMilli");
    }
    // 把（既有 + 新合成的）unit 收集成 industry → 候选表，供关系/条件/配额对齐。
    Map<String, List<ProcessRef>> unitsByIndustry = unitsByIndustry(units);
    rewriteRelationKeys(unitsByIndustry, industries, legacyOperators, node);
    rewriteConditionKeys(unitsByIndustry, node);
    rewriteAllocationActivities(unitsByIndustry, node);
    return node;
  }

  /**
   * ★★ <b>R3B.2 旧档 {@link EconomyChangeSet} 的生产部分整形</b>：与 snapshot 同一套翻译，但组件是 {@code FieldDelta}
   * 节点（{@code @class} + {@code entries}/{@code upserts}/{@code keys}）。
   *
   * <p>★ <b>做</b>：旧 industry 增量里的 {@code operator/capacity/progress/cycleLabor/cycleInputUsed}
   * 摘掉，并合成 {@code units} 增量（进度/劳动/投入带过去）+ 按需合成 {@code assetShares} 增量（capacity 整额 OWNED）；关系增量 的键与
   * {@code activity} 按关系自己的 operator 重写（自足，不依赖 industries 组件在同一条变更集里）。
   *
   * <p>★ <b>不做（交给 {@code EconomyData} 构造期的迁移器）</b>：{@code operatorConditions} 与 {@code allocations}
   * 的键/activity 对齐 —— 它们需要 base 里的 units 才能判唯一，构造期已有那份状态。
   */
  static ObjectNode migrateLegacyProductionChangeSetComponents(ObjectNode root) {
    ObjectNode industries = deltaEntries(root.get("industries"));
    Map<String, ProcessRef> deltaUnits = new LinkedHashMap<>();
    Map<String, ObjectNode> deltaShareNodes = new LinkedHashMap<>();
    if (industries != null) {
      List<String> keys = new ArrayList<>();
      industries.fieldNames().forEachRemaining(keys::add);
      for (String industryKey : keys) {
        JsonNode value = industries.get(industryKey);
        if (!(value instanceof ObjectNode industry) || !isLegacyIndustry(industry)) {
          continue;
        }
        JsonNode operatorNode = industry.get("operator");
        JsonNode capacityNode = industry.get("capacity");
        ObjectNode sharesInDelta = deltaEntries(root.get("assetShares"));
        boolean hasCapacity = capacityNode instanceof ObjectNode cap && !cap.isEmpty();
        boolean hasSharesInDelta =
            sharesInDelta != null && hasShareForIndustry(sharesInDelta, industryKey);
        if (!hasCapacity && !hasSharesInDelta) {
          // capacity 与同批 assetShares 都没有实物 ⇒ 不造 unit（旧档规模恒 0 的等价路径）；
          // 旧字段仍要摘掉，否则新 IndustryDefinitionView 模板绑定会被未知属性拒。
          industry.remove("operator");
          industry.remove("capacity");
          industry.remove("progressDays");
          industry.remove("cycleLaborMilli");
          industry.remove("cycleInputUsedMilli");
          continue;
        }
        if (operatorNode == null || !operatorNode.isObject()) {
          ActorRef fallback =
              RegimeOperators.defaultOperator(
                  new RegimeId(textOfId(industry.get("regime"))), new IndustryId(industryKey));
          operatorNode = actorNode(fallback.kind().name(), fallback.id());
        }
        ActorRef operator = actorOf(operatorNode);
        ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(industryKey), operator);
        ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
        unitNode.put("id", unitId.value());
        unitNode.set("industry", idNode(industryKey));
        unitNode.set("operator", operatorNode.deepCopy());
        unitNode.put("modeKey", industryKey);
        unitNode.put("progressDays", longOf(industry.get("progressDays"), 0L));
        unitNode.put("cycleLaborMilli", longOf(industry.get("cycleLaborMilli"), 0L));
        JsonNode inputUsed = industry.get("cycleInputUsedMilli");
        unitNode.set(
            "cycleInputUsedMilli",
            inputUsed != null && inputUsed.isObject()
                ? inputUsed.deepCopy()
                : JsonNodeFactory.instance.objectNode());
        deltaUnits.put(
            unitId.value(),
            new ProcessRef(unitId.value(), operatorNode.deepCopy(), industryKey, unitNode));
        if (capacityNode instanceof ObjectNode capNode && !capNode.isEmpty()) {
          ObjectNode synthetic = JsonNodeFactory.instance.objectNode();
          synthesizeOwnedShares(synthetic, industryKey, operatorNode, capNode);
          for (Map.Entry<String, JsonNode> share : iterableFields(synthetic)) {
            if (share.getValue() instanceof ObjectNode shareNode) {
              deltaShareNodes.putIfAbsent(share.getKey(), shareNode);
            }
          }
        }
        industry.remove("operator");
        industry.remove("capacity");
        industry.remove("progressDays");
        industry.remove("cycleLaborMilli");
        industry.remove("cycleInputUsedMilli");
      }
    }
    if (!deltaUnits.isEmpty()) {
      upsertProcessesDelta(root, deltaUnits);
      // ★★ 旧档的**首播/按格追加**变更集把产能总量放在 IndustryDefinitionView.capacity 上（没有 assetShares 增量）⇒ 从这里
      //   合成整额 OWNED 份额 upsert，否则重放出"unit 没有份额"的非法中间态（单位守卫当场拒）。
      //   ★ 只在这一批确实是"播种批"时做：assetShares 组件没有增量（unchanged/缺席，即旧形状）**且** relations
      //     组件有增量（播种批一定写 relations；旧代码的日结算只改 industries/meta，relations 不动）。
      boolean sharesUntouched = deltaEntries(root.get("assetShares")) == null;
      boolean looksLikeSeedingBatch = deltaEntries(root.get("relations")) != null;
      if (sharesUntouched && looksLikeSeedingBatch && !deltaShareNodes.isEmpty()) {
        upsertOwnershipStakesDelta(root, deltaShareNodes);
      }
    }
    ObjectNode relations = deltaEntries(root.get("relations"));
    if (relations != null) {
      // ★★ 幂等判别（与 snapshot 路径的 {@code rewriteRelationKeys} 同义）：关系键若已经是 unit 身份，
      //   不得再按"旧 industry 串"包一层 —— 否则 `unit-farm-…` 会被二次拼成 `unit-unit-farm-…`。
      Set<String> knownUnitIds = new LinkedHashSet<>(deltaUnits.keySet());
      ObjectNode unitsInDelta = deltaEntries(root.get("units"));
      if (unitsInDelta != null) {
        unitsInDelta.fieldNames().forEachRemaining(knownUnitIds::add);
      }
      List<String> keys = new ArrayList<>();
      relations.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        JsonNode value = relations.get(key);
        if (!(value instanceof ObjectNode relation)) {
          continue;
        }
        JsonNode operatorNode = relation.get("operator");
        if (operatorNode == null || !operatorNode.isObject()) {
          continue; // 拿不到 operator：留给构造期迁移器按 actor/唯一 unit 对齐
        }
        // 键已在同批 units 增量里 ⇒ 已是 unit 口径；或键与 activity 逐字相等且形如 {@code unit-…}
        // （新档 unit id 的唯一拼写点见 {@link ProductionUnitId#idOf}）⇒ 已是 unit 口径。
        String activity = textOfId(relation.get("activity"));
        if (knownUnitIds.contains(key)
            || (key.equals(activity) && key.startsWith(PRODUCTION_UNIT_ID_PREFIX))) {
          continue;
        }
        ActorRef operator = actorOf(operatorNode);
        try {
          ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(key), operator);
          relations.remove(key);
          relation.put("activity", unitId.value());
          relation.set("operator", operatorNode.deepCopy());
          relations.set(unitId.value(), relation);
        } catch (IllegalArgumentException ignored) {
          // key 不是合法产业 id（混合态）：留给构造期守卫 fail-closed，不在 codec 里猜
        }
      }
    }
    return root;
  }

  /**
   * ★★ 旧 industry 节点判据（R3B.2b 起**按值**判，不再按"键是否出现"）：B.2b 给新模板的 canonical record 末尾加回了 5 个旧档 兼容位 ⇒
   * 新档序列化会写出中性值（{@code operator:null / progressDays:0 / capacity:{} / cycleLaborMilli:0 /
   * cycleInputUsedMilli:{}}）。若仍按"键出现"判，<b>新档会被误当成旧档</b>（凭空合成 unit/份额、破坏新形状往返）。
   * 判据就是下面这五条（本方法即唯一拼写点）：任一位非中性才算旧形状。
   */
  static boolean isLegacyIndustry(ObjectNode industry) {
    return industry.hasNonNull("operator")
        || longOf(industry.get("progressDays"), 0L) > 0L
        || longOf(industry.get("cycleLaborMilli"), 0L) > 0L
        || hasEntries(industry.get("capacity"))
        || hasEntries(industry.get("cycleInputUsedMilli"));
  }

  /** 非空对象（键值表）判据：缺席 / null / 空对象 / 非对象 ⇒ false。 */
  static boolean hasEntries(JsonNode node) {
    return node instanceof ObjectNode object && !object.isEmpty();
  }

  /** 一格的 capacity 表（旧形状 {@code {"LAND":3100000}}）⇒ 逐项整额 OWNED 份额；空表 ⇒ 不造。 */
  static void synthesizeOwnedShares(
      ObjectNode assetShares, String industryKey, JsonNode operatorNode, JsonNode capacity) {
    if (!(capacity instanceof ObjectNode capacityNode) || capacityNode.isEmpty()) {
      return;
    }
    List<String> assets = new ArrayList<>();
    capacityNode.fieldNames().forEachRemaining(assets::add);
    for (String assetName : assets) {
      JsonNode quantity = capacityNode.get(assetName);
      if (!quantity.isNumber()) {
        throw new IllegalStateException("旧档 capacity." + assetName + " 必须是整数: " + quantity);
      }
      addOwnedShare(assetShares, industryKey, operatorNode, assetName, quantity.longValue());
    }
  }

  /** 生成一条整额 OWNED 份额节点（id 走 {@link OwnershipStake#idOf}，sequence 恒 0：capacity 每项至多一条）。 */
  static void addOwnedShare(
      ObjectNode assetShares,
      String industryKey,
      JsonNode operatorNode,
      String assetName,
      long quantity) {
    AssetKind asset;
    try {
      asset = AssetKind.parse(assetName);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "旧档 capacity 的键不是生产资料种类: " + assetName + "；" + e.getMessage(), e);
    }
    ActorRef owner = actorOf(operatorNode);
    ProductionLocation location =
        ProductionLocation.ofPlan(new IndustryId(industryKey))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "旧档 capacity 的 industry 串不带格键，无法落定 OwnershipStake.location: "
                            + industryKey));
    AssetShareId shareId =
        OwnershipStake.idOf(
            new IndustryId(industryKey), asset, owner, owner, AssetRightKind.OWNED, location, 0L);
    ObjectNode share = JsonNodeFactory.instance.objectNode();
    share.put("id", shareId.value());
    share.set("industry", idNode(industryKey));
    share.put("asset", assetName);
    share.set("owner", operatorNode.deepCopy());
    share.set("operator", operatorNode.deepCopy());
    share.put("quantity", quantity);
    share.put("kind", AssetRightKind.OWNED.name());
    share.put("location", location.value());
    assetShares.putIfAbsent(shareId.value(), share);
  }

  /** 某个 industry 在 {@code assetShares} 节点里是否已有份额行（值内 industry 命中）。 */
  static boolean hasShareForIndustry(ObjectNode assetShares, String industryKey) {
    for (JsonNode value : assetShares) {
      if (value instanceof ObjectNode share
          && industryKey.equals(textOfId(share.get("industry")))) {
        return true;
      }
    }
    return false;
  }

  /** 把 {@code units} 节点收成 industry → 候选 unit 表（按 units 插入序）。 */
  static Map<String, List<ProcessRef>> unitsByIndustry(ObjectNode units) {
    Map<String, List<ProcessRef>> byIndustry = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(units)) {
      JsonNode value = entry.getValue();
      if (!(value instanceof ObjectNode unit)) {
        continue;
      }
      String industryKey = textOfId(unit.get("industry"));
      JsonNode operatorNode = unit.get("operator");
      if (industryKey == null || operatorNode == null || !operatorNode.isObject()) {
        continue;
      }
      byIndustry
          .computeIfAbsent(industryKey, ignored -> new ArrayList<>())
          .add(new ProcessRef(entry.getKey(), operatorNode, industryKey, value));
    }
    return byIndustry;
  }

  /** 关系键/activity 对齐：按 relation.operator + 旧 industry 串找唯一 unit；0 条丢弃、多条 fail-closed。 */
  static void rewriteRelationKeys(
      Map<String, List<ProcessRef>> unitsByIndustry,
      ObjectNode industries,
      Map<String, JsonNode> legacyOperators,
      ObjectNode root) {
    ObjectNode relations = objectField(root, "relations");
    if (relations == null) {
      return;
    }
    List<String> keys = new ArrayList<>();
    relations.fieldNames().forEachRemaining(keys::add);
    for (String key : keys) {
      JsonNode value = relations.get(key);
      if (!(value instanceof ObjectNode relation)) {
        continue;
      }
      JsonNode operatorNode = relation.get("operator");
      if (operatorNode == null || !operatorNode.isObject()) {
        operatorNode = legacyOperators.get(key);
      }
      ActorRef operator =
          operatorNode != null && operatorNode.isObject() ? actorOf(operatorNode) : null;
      // ★ 幂等：键已是现存 unit（新形状/多 unit）⇒ 只把值内 activity 对齐到键，不做 industry 解析（避免误判歧义）。
      ProcessRef byKey = findUnitById(unitsByIndustry, key);
      if (byKey != null) {
        continue; // 幂等：键已是现存 unit ⇒ 键与值内 activity 是否一致交给 EconomyData 守卫判（不在这里静默改写）
      }
      String industryKey = industryKeyOfRelation(relation, key);
      ProcessRef resolved = resolveUnit(unitsByIndustry, industryKey, operator);
      if (resolved == null) {
        relations.remove(key); // 没有 unit ⇒ 这条关系没有生产活动可结算（旧档产能 0 等价）
        continue;
      }
      if (resolved.id().equals(key) && resolved.id().equals(textOfId(relation.get("activity")))) {
        continue; // 已经是 unit 口径（幂等）
      }
      relations.remove(key);
      relation.put("activity", resolved.id());
      relations.set(resolved.id(), relation);
    }
  }

  /** 条件键对齐：值内只有 industry（没有 operator）⇒ 该产业唯一 unit；0 条丢弃、多条 fail-closed。 */
  static void rewriteConditionKeys(Map<String, List<ProcessRef>> unitsByIndustry, ObjectNode root) {
    ObjectNode conditions = objectField(root, "operatorConditions");
    if (conditions == null) {
      return;
    }
    List<String> keys = new ArrayList<>();
    conditions.fieldNames().forEachRemaining(keys::add);
    for (String key : keys) {
      JsonNode value = conditions.get(key);
      if (!(value instanceof ObjectNode condition)) {
        continue;
      }
      // ★ 幂等：键已是现存 unit 且 industry 匹配 ⇒ 原样（多 unit 产业的新形状不会被误判成歧义）。
      ProcessRef byKey = findUnitById(unitsByIndustry, key);
      if (byKey != null && byKey.industryKey().equals(textOfId(condition.get("industry")))) {
        continue;
      }
      List<ProcessRef> candidates =
          unitsByIndustry.getOrDefault(textOfId(condition.get("industry")), List.of());
      if (candidates.isEmpty()) {
        conditions.remove(key);
        continue;
      }
      if (candidates.size() > 1) {
        throw new IllegalStateException(
            "旧档 operatorConditions 的 "
                + key
                + " 指向产业 "
                + textOfId(condition.get("industry"))
                + "，但该产业有多条 unit ⇒ 说不清是哪一个（拒绝猜）: "
                + candidates);
      }
      String unitId = candidates.get(0).id();
      if (unitId.equals(key)) {
        continue;
      }
      conditions.remove(key);
      conditions.set(unitId, condition);
    }
  }

  /** 配额 activity 对齐：仍不是 unit id 的，按 actor.id() 当产业串找唯一 unit；解析不到 ⇒ 原样。 */
  static void rewriteAllocationActivities(
      Map<String, List<ProcessRef>> unitsByIndustry, ObjectNode root) {
    JsonNode allocations = root.get("allocations");
    if (allocations == null || !allocations.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : iterableFields((ObjectNode) allocations)) {
      JsonNode value = entry.getValue();
      if (!(value instanceof ObjectNode allocation)) {
        continue;
      }
      String activity = textOf(allocation.get("activity"));
      if (activity != null && unitIdExists(unitsByIndustry, activity)) {
        continue;
      }
      JsonNode actor = allocation.get("actor");
      if (actor == null || !actor.isObject()) {
        continue;
      }
      String actorId = textOf(actor.get("id"));
      if (actorId == null) {
        continue;
      }
      List<ProcessRef> candidates = unitsByIndustry.getOrDefault(actorId, List.of());
      if (candidates.size() == 1) {
        allocation.put("activity", candidates.get(0).id());
      }
    }
  }

  /**
   * ★★ <b>Z1b：旧档缺 {@code kind} 的读侧整形</b>（快照侧）—— {@code allocations} 表的每个值节点若不是记录对象就交给 绑定
   * fail-closed；是对象且缺 {@code kind} ⇒ 补 {@link LaborCommitmentKind#PRODUCTION}（Z1b 之前的档没有这一维）。
   *
   * <p>★ 新档 / 已有 {@code kind} 的档原样不动（幂等）；未知 {@code kind} 值不在这里兜底，由闭枚举绑定 fail-closed。
   */
  static void defaultLaborCommitmentKindsInSnapshot(JsonNode allocations) {
    if (!(allocations instanceof ObjectNode allocationsObject)) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : iterableFields(allocationsObject)) {
      if (entry.getValue() instanceof ObjectNode commitment) {
        putDefaultLaborCommitmentKind(commitment);
      }
    }
  }

  /**
   * ★★ <b>Z1b：旧档缺 {@code kind} 的读侧整形</b>（变更集侧）—— {@code allocations} 是 {@link
   * io.mosire.simos.util.state.FieldDelta} 的一个变体：{@code upsert.entries} 的值要补；{@code patch} 递归进
   * {@code upserts}/{@code removals}；{@code unchanged}/{@code remove} 无值可补。
   *
   * <p>★ 只加"缺省值"，不改 {@code ChangeSet}/{@code FieldDelta} 形状（变体、键、entries/keys 结构一字不动）。
   */
  static void defaultLaborCommitmentKindsInDelta(JsonNode delta) {
    if (!(delta instanceof ObjectNode deltaObject)) {
      return; // 坏形状交给后续绑定 fail-closed，不在 codec 里猜
    }
    switch (deltaObject.path("@class").asText("")) {
      case "upsert" -> defaultLaborCommitmentKindsInEntries(objectField(deltaObject, "entries"));
      case "patch" -> {
        defaultLaborCommitmentKindsInDelta(deltaObject.get("upserts"));
        defaultLaborCommitmentKindsInDelta(deltaObject.get("removals"));
      }
      default -> {
        // unchanged / remove / 未知变体：没有记录值要补；未知变体交给 FieldDelta 绑定 fail-closed。
      }
    }
  }

  /** {@code upsert.entries} 的每个记录值缺 {@code kind} ⇒ 补 PRODUCTION；非对象值原样交给绑定。 */
  static void defaultLaborCommitmentKindsInEntries(ObjectNode entries) {
    if (entries == null) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : iterableFields(entries)) {
      if (entry.getValue() instanceof ObjectNode commitment) {
        putDefaultLaborCommitmentKind(commitment);
      }
    }
  }

  /** 记录节点缺 {@code kind} ⇒ 写 {@code PRODUCTION}；已有键（含未知值）原样留给闭枚举绑定 fail-closed。 */
  static void putDefaultLaborCommitmentKind(ObjectNode commitment) {
    if (!commitment.hasNonNull("kind")) {
      commitment.put("kind", LaborCommitmentKind.PRODUCTION.name());
    }
  }

  /** root 的 {@code assetShares} 增量节点：把合成的整额份额 upsert 合并进去（变体落点同 {@link #upsertProcessesDelta}）。 */
  static void upsertOwnershipStakesDelta(ObjectNode root, Map<String, ObjectNode> shareNodes) {
    ObjectNode delta =
        root.has("assetShares") && root.get("assetShares").isObject()
            ? (ObjectNode) root.get("assetShares")
            : JsonNodeFactory.instance.objectNode();
    String kind = delta.path("@class").asText("");
    if ("remove".equals(kind)) {
      throw new IllegalStateException(
          "旧档变更集同时要删 assetShares 又要从旧 capacity 合成份额（同一件事两处拼写）：" + root.get("assetShares"));
    }
    ObjectNode entries;
    if (delta.isEmpty() || "unchanged".equals(kind)) {
      delta.removeAll();
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
      root.set("assetShares", delta);
    } else if ("upsert".equals(kind)) {
      entries = (ObjectNode) delta.get("entries");
    } else if ("patch".equals(kind)) {
      entries = (ObjectNode) delta.path("upserts").path("entries");
    } else {
      throw new IllegalStateException("无法识别的 assetShares 增量变体: " + delta);
    }
    for (Map.Entry<String, ObjectNode> share : shareNodes.entrySet()) {
      entries.putIfAbsent(share.getKey(), share.getValue());
    }
  }

  /** root 的 {@code units} 增量节点：把 unit upsert 合并进 units 组件增量（三种变体各自落点）。 */
  static void upsertProcessesDelta(ObjectNode root, Map<String, ProcessRef> unitRefs) {
    ObjectNode delta =
        root.has("units") && root.get("units").isObject()
            ? (ObjectNode) root.get("units")
            : JsonNodeFactory.instance.objectNode();
    String kind = delta.path("@class").asText("");
    if ("remove".equals(kind)) {
      throw new IllegalStateException(
          "旧档变更集同时要删 units 又要从旧 industry 合成 unit（同一件事两处拼写）：" + root.get("units"));
    }
    ObjectNode entries;
    if (delta.isEmpty()) {
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
      root.set("units", delta);
    } else if ("upsert".equals(kind)) {
      entries = (ObjectNode) delta.get("entries");
    } else if ("patch".equals(kind)) {
      entries = (ObjectNode) delta.path("upserts").path("entries");
    } else if ("unchanged".equals(kind)) {
      delta.removeAll();
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
    } else {
      throw new IllegalStateException("无法识别的 units 增量变体: " + delta);
    }
    for (ProcessRef ref : unitRefs.values()) {
      if (ref.unitNode() instanceof ObjectNode withState) {
        entries.putIfAbsent(ref.id(), withState.deepCopy());
        continue;
      }
      ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
      unitNode.put("id", ref.id());
      unitNode.set("industry", idNode(ref.industryKey()));
      unitNode.set("operator", ref.operatorNode().deepCopy());
      unitNode.put("modeKey", ref.industryKey());
      unitNode.put("progressDays", 0L);
      unitNode.put("cycleLaborMilli", 0L);
      unitNode.set("cycleInputUsedMilli", JsonNodeFactory.instance.objectNode());
      entries.putIfAbsent(ref.id(), unitNode);
    }
  }

  /** 关系值内 {@code activity} / 键 → 旧 industry 串（两种形态都可：{"value":…} 或裸串）。 */
  static String industryKeyOfRelation(ObjectNode relation, String fallbackKey) {
    String activity = textOfId(relation.get("activity"));
    return activity != null ? activity : fallbackKey;
  }

  /** 从 units 索引里解析唯一候选：优先 operator 相等；否则候选只有一个时用它；多个抛。 */
  static ProcessRef resolveUnit(
      Map<String, List<ProcessRef>> unitsByIndustry, String industryKey, ActorRef operator) {
    List<ProcessRef> candidates = unitsByIndustry.getOrDefault(industryKey, List.of());
    if (candidates.isEmpty()) {
      return null;
    }
    List<ProcessRef> matching = new ArrayList<>();
    for (ProcessRef candidate : candidates) {
      if (operator != null && actorOf(candidate.operatorNode()).equals(operator)) {
        matching.add(candidate);
      }
    }
    if (matching.size() == 1) {
      return matching.get(0);
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    throw new IllegalStateException(
        "旧档关系/条件指名的产业 "
            + industryKey
            + " 有多条 unit，operator="
            + operator
            + " ⇒ 无法确定是哪一条（拒绝猜）: "
            + candidates);
  }

  /** 按 id 找 unit（跨产业索引线性扫；reshape 期规模小，且只在读档一次）。 */
  static ProcessRef findUnitById(Map<String, List<ProcessRef>> unitsByIndustry, String unitId) {
    for (List<ProcessRef> refs : unitsByIndustry.values()) {
      for (ProcessRef ref : refs) {
        if (ref.id().equals(unitId)) {
          return ref;
        }
      }
    }
    return null;
  }

  static boolean unitIdExists(Map<String, List<ProcessRef>> unitsByIndustry, String unitId) {
    for (List<ProcessRef> refs : unitsByIndustry.values()) {
      for (ProcessRef ref : refs) {
        if (ref.id().equals(unitId)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 一个 unit 的 reshape 中间表示（id + operator 节点 + 产业串 + 完整 unit 节点，后者可空）。 */
  private record ProcessRef(
      String id, JsonNode operatorNode, String industryKey, JsonNode unitNode) {}

  /** {@link ProductionUnitId} 的值侧读入：裸字符串或 {@code {"value":…}}（与 AssetShareId 同款）。 */
  /** ★ D-032 C2：ProductionRoleId 的线格式 = 裸字符串（读侧也接受旧对象形 {@code {value:"…"}}）。 */
  static final class ProductionRoleIdDeserializer extends JsonDeserializer<ProductionRoleId> {

    @Override
    public ProductionRoleId deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (raw.isTextual()) {
        return ProductionRoleId.parse(raw.asText());
      }
      if (raw.isObject() && raw.hasNonNull("value") && raw.get("value").isTextual()) {
        return ProductionRoleId.parse(raw.get("value").asText());
      }
      throw new IllegalStateException("ProductionRoleId 必须是字符串或 {value:\"…\"}: " + raw);
    }
  }

  static final class ProductionRoleIdSerializer extends JsonSerializer<ProductionRoleId> {

    @Override
    public void serialize(
        ProductionRoleId value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeString(value.value());
    }
  }

  static final class ProductionUnitIdDeserializer extends JsonDeserializer<ProductionUnitId> {

    @Override
    public ProductionUnitId deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (raw.isTextual()) {
        return ProductionUnitId.parse(raw.asText());
      }
      if (raw.isObject() && raw.hasNonNull("value") && raw.get("value").isTextual()) {
        return ProductionUnitId.parse(raw.get("value").asText());
      }
      throw new IllegalStateException("ProductionUnitId 必须是字符串或 {value:\"…\"}: " + raw);
    }
  }
}
