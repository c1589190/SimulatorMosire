package io.mosire.simos.economy.codec;

import static io.mosire.simos.economy.codec.EconomyCodecSupport.deltaEntries;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.iterableFields;
import static io.mosire.simos.economy.codec.EconomyCodecSupport.textOfId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** 旧债务节点/变更集的机械迁移 codec helper（P0.5-C2）。 */
final class EconomyDebtCodecs {

  static ObjectNode migrateLegacyDebtSnapshotComponent(ObjectNode node) {
    boolean hasOld = node.has("debts");
    boolean hasNew = node.has("debtContracts");
    if (hasOld && hasNew) {
      throw new IllegalStateException(
          "经济状态不得同时给 debts 与 debtContracts（E4a 起新键是 debtContracts，旧档键是 debts）: " + node);
    }
    if (!hasOld) {
      return node;
    }
    JsonNode legacy = node.remove("debts");
    ObjectNode newTable = JsonNodeFactory.instance.objectNode();
    node.set("debtContracts", newTable);
    if (legacy == null || legacy.isNull()) {
      return node;
    }
    if (!(legacy instanceof ObjectNode oldTable)) {
      throw new IllegalStateException("旧 debts 必须是 {旧债务id: Debt} 对象: " + legacy);
    }
    Map<String, DebtContract> merged = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(oldTable)) {
      DebtContract contract = legacyDebtContract(entry.getKey(), entry.getValue());
      merged.merge(contract.id().value(), contract, EconomyDebtCodecs::mergeDebtContracts);
    }
    for (Map.Entry<String, DebtContract> entry : merged.entrySet()) {
      newTable.set(entry.getKey(), EconomyCoreCodecs.valueToTree(entry.getValue()));
    }
    return node;
  }

  /**
   * ★★ E4a changeset 迁移：旧 {@code debts} 的 {@code FieldDelta} → 新 {@code debtContracts} 的 {@code
   * FieldDelta}。
   *
   * <p>四个变体逐档翻译：{@code unchanged} 原样换名；{@code upsert} 的每个旧值按 snapshot 同一套 {@link
   * #legacyDebtContract} 翻译并按新 id 合并（{@code principal} 用 {@code Math.addExact} 求和， 本金逐值守恒）；{@code
   * remove} 的旧键按旧格式解析出四元组后换新 id；{@code patch} 两侧分别翻译。
   *
   * <p>★★ <b>如实记的边界</b>：旧变更集里 {@code upsert.entries} 的值是<b>完整新值</b>（FieldDelta 语义）， 而迁移后同一新 id
   * 是跨旧周期合并的余额；因此“把旧 revision 链从新 checkpoint 继续重放”在
   * 同一四元组于旧基态已有多条周期合同、且中间变更集只更新其中一条时，无法只靠本方法恢复被合并条的明细 （那需要旧基态的逐条本金，旧线格式里没有）。本阶段按“变更集节点 principal
   * 守恒”实现，并把该重放边界 记入交付报告的已知缺口；最终回放必须用同一 revision 的完整 snapshot，或等 E4b 的显式债务 breakdown。
   *
   * <p>★ <b>幂等</b>：新形状再跑一遍时没有 {@code debts} 键 ⇒ 原样返回。
   */
  static ObjectNode migrateLegacyDebtChangeSetComponent(ObjectNode root) {
    JsonNode legacy = root.get("debts");
    boolean hasNew = root.has("debtContracts");
    if (legacy != null && hasNew) {
      throw new IllegalStateException(
          "经济变更集不得同时给 debts 与 debtContracts（E4a 起新键是 debtContracts，旧档键是 debts）: " + root);
    }
    if (legacy == null) {
      return root;
    }
    root.remove("debts");
    if (legacy.isNull()) {
      root.set("debtContracts", unchangedDeltaNode());
      return root;
    }
    if (!(legacy instanceof ObjectNode delta)) {
      throw new IllegalStateException("旧 debts 变更集必须是 FieldDelta 对象: " + legacy);
    }
    root.set("debtContracts", legacyDebtDelta(delta));
    return root;
  }

  /** 旧 debts 的一个 {@link FieldDelta} 变体 → 新 debtContracts 的同变体（键/值都换新）。 */
  static ObjectNode legacyDebtDelta(ObjectNode delta) {
    String kind = delta.path("@class").asText("");
    switch (kind) {
      case "", "unchanged":
        return unchangedDeltaNode();
      case "upsert":
        return legacyDebtUpsert(deltaEntries(delta));
      case "remove":
        return legacyDebtRemove(delta.get("keys"));
      case "patch":
        JsonNode upserts = delta.get("upserts");
        JsonNode removals = delta.get("removals");
        if (!(upserts instanceof ObjectNode upsertsObject)
            || !(removals instanceof ObjectNode removalsObject)) {
          throw new IllegalStateException("旧 debts patch 必须同时有 upserts 与 removals 对象: " + delta);
        }
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        patch.put("@class", "patch");
        patch.set("upserts", legacyDebtDelta(upsertsObject));
        patch.set("removals", legacyDebtDelta(removalsObject));
        return patch;
      default:
        throw new IllegalStateException("旧 debts 变更集的 FieldDelta 变体不认识: " + kind);
    }
  }

  /**
   * 旧 debts 的 {@code upsert.entries} → 新 debtContracts 的 {@code upsert.entries}（同新 id 合并
   * principal）。
   */
  static ObjectNode legacyDebtUpsert(ObjectNode entries) {
    if (entries == null) {
      throw new IllegalStateException("旧 debts upsert 缺 entries 对象");
    }
    Map<String, DebtContract> merged = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(entries)) {
      DebtContract contract = legacyDebtContract(entry.getKey(), entry.getValue());
      merged.merge(contract.id().value(), contract, EconomyDebtCodecs::mergeDebtContracts);
    }
    ObjectNode newEntries = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, DebtContract> entry : merged.entrySet()) {
      newEntries.set(entry.getKey(), EconomyCoreCodecs.valueToTree(entry.getValue()));
    }
    ObjectNode upsert = JsonNodeFactory.instance.objectNode();
    upsert.put("@class", "upsert");
    upsert.set("entries", newEntries);
    return upsert;
  }

  /** 旧 debts 的 {@code remove.keys} → 新 debtContracts 的 {@code remove.keys}（旧键解析四元组后换新 id）。 */
  static ObjectNode legacyDebtRemove(JsonNode keys) {
    if (keys == null || !keys.isArray()) {
      throw new IllegalStateException("旧 debts remove 缺 keys 数组: " + keys);
    }
    Set<String> newKeys = new LinkedHashSet<>();
    for (JsonNode key : keys) {
      String oldId = key.isTextual() ? key.asText() : textOfId(key);
      if (oldId == null) {
        throw new IllegalStateException("旧 debts remove 的 key 不是字符串/{\"value\":…}: " + key);
      }
      newKeys.add(legacyDebtContractIdFromOldKey(oldId).value());
    }
    var array = JsonNodeFactory.instance.arrayNode();
    for (String newKey : newKeys) {
      array.add(newKey);
    }
    ObjectNode remove = JsonNodeFactory.instance.objectNode();
    remove.put("@class", "remove");
    remove.set("keys", array);
    return remove;
  }

  /** 旧 {@code Debt} 节点 → 新 {@link DebtContract}（缺字段/坏形状 fail-closed，不猜）。 */
  static DebtContract legacyDebtContract(String oldId, JsonNode value) {
    if (!(value instanceof ObjectNode debt)) {
      throw new IllegalStateException("旧债务值必须是对象: " + value);
    }
    String declaredId = textOfId(debt.get("id"));
    if (declaredId != null && !declaredId.equals(oldId)) {
      throw new IllegalStateException("旧债务的键与值内 id 不一致：键=" + oldId + "，值内 id=" + declaredId);
    }
    String debtorText = textOfId(debt.get("debtor"));
    String creditorText = textOfId(debt.get("creditor"));
    if (debtorText == null || creditorText == null) {
      throw new IllegalStateException("旧债务必须给 debtor/creditor: " + debt);
    }
    JsonNode rateNode = debt.get("ratePerMillePerCycle");
    if (rateNode == null || !rateNode.isNumber()) {
      throw new IllegalStateException("旧债务必须给数字 ratePerMillePerCycle: " + debt);
    }
    int rate = rateNode.intValue();
    if (rate < 0) {
      throw new IllegalStateException("旧债务 ratePerMillePerCycle 不得为负: " + debt);
    }
    JsonNode principalNode = debt.get("principal");
    if (principalNode == null || !principalNode.isNumber()) {
      throw new IllegalStateException("旧债务必须给数字 principal: " + debt);
    }
    long principal = principalNode.longValue();
    if (principal < 0L) {
      throw new IllegalStateException("旧债务 principal 不得为负: " + debt);
    }
    OptionalLong dueCycle = OptionalLong.empty();
    JsonNode dueNode = debt.get("dueCycle");
    if (dueNode != null && dueNode.isNumber()) {
      long due = dueNode.longValue();
      if (due < 0L) {
        throw new IllegalStateException("旧债务 dueCycle 不得为负: " + debt);
      }
      dueCycle = OptionalLong.of(due);
    }
    boolean defaulted = debt.path("defaulted").asBoolean(false);
    DebtStatus status =
        defaulted
            ? DebtStatus.DEFAULTED
            : (principal == 0L ? DebtStatus.SETTLED : DebtStatus.NORMAL);
    HouseholdId debtor = HouseholdId.parse(debtorText);
    HouseholdId creditor = HouseholdId.parse(creditorText);
    DebtUnit.Money unit = legacyDebtUnit(debt.get("commodity"));
    DebtTerms terms = DebtTerms.legacyDefault(rate);
    return new DebtContract(
        DebtContractId.idOf(debtor, creditor, unit, terms),
        debtor,
        creditor,
        unit,
        terms,
        DebtSource.LEGACY_UNKNOWN,
        principal,
        0L,
        OptionalLong.empty(),
        dueCycle,
        status);
  }

  /**
   * 旧 {@code commodity} 空 ⇒ 银币货币债；有值 ⇒ <b>稳定拒绝</b>（P2c 起债务本金只允许货币，旧商品债按 Alpha 直接重建， 不写 1:1/0 折兼容位）。
   */
  static DebtUnit.Money legacyDebtUnit(JsonNode commodityNode) {
    if (commodityNode == null || commodityNode.isNull()) {
      return new DebtUnit.Money(MoneyVocabulary.SILVER_CURRENCY);
    }
    String commodity = textOfId(commodityNode);
    if (commodity == null || commodity.isBlank()) {
      throw new IllegalStateException("旧债务 commodity 形状不可识别: " + commodityNode);
    }
    throw new IllegalStateException(
        "legacy-commodity-debt-unsupported: P2c 起债务本金只允许货币，旧商品债请重建: commodity=" + commodity);
  }

  /** 同新 id 的旧条合并：principal 逐值相加、status/defaulted 优先、dueCycle 取最大、openedDay 取最早。 */
  static DebtContract mergeDebtContracts(DebtContract first, DebtContract second) {
    if (!first.id().equals(second.id())) {
      throw new IllegalStateException("合并旧债务时新合同 id 不一致: " + first.id() + " vs " + second.id());
    }
    if (first.source() != second.source()) {
      throw new IllegalStateException(
          "debt-source-mismatch: 合并同 id 旧债务时来源不一致: "
              + first.id()
              + " "
              + first.source()
              + " vs "
              + second.source());
    }
    long principal = Math.addExact(first.principal(), second.principal());
    DebtStatus status =
        (first.status() == DebtStatus.DEFAULTED || second.status() == DebtStatus.DEFAULTED)
            ? DebtStatus.DEFAULTED
            : (principal > 0L ? DebtStatus.NORMAL : DebtStatus.SETTLED);
    return new DebtContract(
        first.id(),
        first.debtor(),
        first.creditor(),
        first.unit(),
        first.terms(),
        first.source(),
        principal,
        Math.min(first.openedDay(), second.openedDay()),
        maxOptionalLong(first.lastInterestDay(), second.lastInterestDay()),
        maxOptionalLong(first.dueCycle(), second.dueCycle()),
        status);
  }

  static OptionalLong maxOptionalLong(OptionalLong first, OptionalLong second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    return OptionalLong.of(Math.max(first.getAsLong(), second.getAsLong()));
  }

  /**
   * 旧 {@code remove} 键解析：{@code debt-c<周期>-<债务人>><债权人>-<商品>} ⇒ 新合同 id。
   *
   * <p>★ <b>边界如实记</b>：旧格式没有转义，商品名/主体 id 含 {@code "-"}/{@code ">"} 时没有唯一解析； 本方法只覆盖既有生产格式（商品段取最后一个
   * {@code "-"}、主体按 {@code ">>"} 分）。旧生产路径从不删除 债务（还清只把本金写成 0），故该分支主要服务手工/外部变更集。
   */
  static DebtContractId legacyDebtContractIdFromOldKey(String oldId) {
    String prefix = "debt-c";
    if (!oldId.startsWith(prefix)) {
      throw new IllegalStateException("旧 debts remove 键不是已知格式: " + oldId);
    }
    int cycleEnd = oldId.indexOf('-', prefix.length());
    if (cycleEnd < 0) {
      throw new IllegalStateException("旧 debts remove 键缺周期段: " + oldId);
    }
    String cycleText = oldId.substring(prefix.length(), cycleEnd);
    if (cycleText.isEmpty()) {
      throw new IllegalStateException("旧 debts remove 键周期段为空: " + oldId);
    }
    try {
      Long.parseLong(cycleText);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("旧 debts remove 键周期段不是数字: " + oldId, e);
    }
    String rest = oldId.substring(cycleEnd + 1);
    int sides = rest.indexOf(">>");
    if (sides < 0) {
      throw new IllegalStateException("旧 debts remove 键缺债务人与债权人的 >> 分隔: " + oldId);
    }
    int commodityDash = rest.lastIndexOf('-');
    if (commodityDash <= sides) {
      throw new IllegalStateException("旧 debts remove 键缺商品段: " + oldId);
    }
    String debtorText = rest.substring(0, sides);
    String creditorText = rest.substring(sides + 2, commodityDash);
    String commodityText = rest.substring(commodityDash + 1);
    if (debtorText.isBlank() || creditorText.isBlank() || commodityText.isBlank()) {
      throw new IllegalStateException("旧 debts remove 键有空白段: " + oldId);
    }
    DebtUnit.Money unit;
    if ("money".equals(commodityText)) {
      unit = new DebtUnit.Money(MoneyVocabulary.SILVER_CURRENCY);
    } else {
      throw new IllegalStateException(
          "legacy-commodity-debt-unsupported: P2c 起债务本金只允许货币，旧 remove 键请重建: commodity="
              + commodityText);
    }
    return DebtContractId.idOf(
        HouseholdId.parse(debtorText),
        HouseholdId.parse(creditorText),
        unit,
        DebtTerms.legacyDefault());
  }

  static ObjectNode unchangedDeltaNode() {
    ObjectNode unchanged = JsonNodeFactory.instance.objectNode();
    unchanged.put("@class", "unchanged");
    return unchanged;
  }
}
