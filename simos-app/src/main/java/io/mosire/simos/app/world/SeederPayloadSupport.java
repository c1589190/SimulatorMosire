package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 创世载荷的共享小节点构造（actor / unit / relation / rule / 保序两键表）。 */
final class SeederPayloadSupport {
  private SeederPayloadSupport() {}

  /** 一个主体引用的载荷节点（{@code {kind,id}}）——与 {@code EconomyPayloads.actorRef} 同一形状。 */
  static Map<String, Object> actorNode(ActorRef actor) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("kind", actor.kind().name());
    node.put("id", actor.id());
    return node;
  }

  /**
   * ★★ <b>保序的两键表</b>（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**不用 {@code
   * Map.of}**）。
   *
   * <p>★★ <b>为什么这条不是洁癖</b>（2026-10-10 K 批实测，装置与全部 digest 见 {@code
   * .superpowers/sdd/2026-10-10-key-order-determinism/fix-ledger.md}）：{@code Map.of} 的 <b>≥2
   * 键</b>形态是 {@code ImmutableCollections.MapN}，槽位 = {@code floorMod(键.hashCode() ^ SALT, 表长)}，而
   * {@code SALT} 取自 <b>JVM 启动时的 nanoTime</b> ⇒ <b>同一份内容在不同 JVM 上迭代序可以不同</b>（1 键形态是 {@code
   * Map1}，恒稳定，故 上面那些单键 {@code Map.of} 无此问题）。这些表经 {@code economy.Seed} 载荷进 {@code
   * IndustryDefinitionView}，而 {@code IndustryDefinitionView} 的冻结口径是"<b>按入参迭代序</b>拷进 {@code
   * LinkedHashMap}" ⇒ 迭代序原样进状态树 ⇒ 快照字节不是内容的纯函数 （实测：同一棵树 6 个 JVM 得 <b>4 个不同
   * digest</b>，而键集与值逐项相同、顺序无关 digest 唯一）。
   *
   * <p>★ 与 {@link #factoryPrices()} 是<b>同一条纪律</b>（那里早已写着"为什么不直接 {@code Map.of}"），本方法是它在<b>配方表</b>上的
   * 拼写点。★ 只吃两个键：本目录里三个真的会抖的表恰好都是两键；多于两键的表请照 {@link #factoryPrices()} 逐条 {@code put}。
   */
  static Map<String, Object> orderedTable(
      String firstKey, Object firstValue, String secondKey, Object secondValue) {
    Map<String, Object> table = new LinkedHashMap<>();
    table.put(firstKey, firstValue);
    table.put(secondKey, secondValue);
    return Collections.unmodifiableMap(table);
  }

  /**
   * ★★ <b>R3B.2：一个产业 plan → 一条默认 unit 载荷</b>（一产业一 unit；多 unit 拆分留给 B.3）。
   *
   * <pre>
   * id            = ProductionUnitId.idOf(industry, operator)
   * modeKey       = industry.id().value()（旧档口径；候选预设留给 E2）
   * progressDays/cycleLaborMilli/cycleInputUsedMilli = 0/0/{}（周期刚起）
   * </pre>
   */
  static Map<String, Object> unitOf(IndustryPlan plan) {
    return unitOf(plan, plan.operator(), null);
  }

  /**
   * ★★ <b>R4-B.3a：一个家户副 unit 的载荷</b>：与主 unit 逐字段同形（{@code id/industry/operator/modeKey/
   * progressDays/cycleLaborMilli/cycleInputUsedMilli}），额外显式发 {@code relation}（副 unit 的规则不靠制度默认）。
   */
  static Map<String, Object> unitOf(IndustryPlan plan, HouseholdUnit secondary) {
    return unitOf(plan, secondary.operator(), secondary.relation());
  }

  /**
   * 一条 unit 载荷的共同构造（主 unit 与副 unit 的唯一拼写点）：{@code id} 由契约层工厂按 {@code (产业, 经营者)} 算； {@code modeKey =
   * 产业 id}（旧档口径；候选预设留给 E2）；周期状态全部从 0 起（创世）。
   *
   * @param relation 只在副 unit 上显式发出（主 unit 仍走制度默认关系）；主 unit 传 {@code null}
   */
  static Map<String, Object> unitOf(
      IndustryPlan plan, ActorRef operator, ProductionRules relation) {
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("id", ProductionUnitId.idOf(new IndustryId(plan.id()), operator).value());
    unit.put("industry", plan.id());
    unit.put("operator", actorNode(operator));
    unit.put("modeKey", plan.id());
    unit.put("progressDays", 0);
    unit.put("cycleLaborMilli", 0);
    unit.put("cycleInputUsedMilli", Map.of());
    if (relation != null) {
      unit.put("relation", relationNode(relation));
    }
    return unit;
  }

  /**
   * ★★ <b>W5：某一格 entry 的 {@code allocations} 列表</b>（铸币要往里追加一条劳动配额）。
   *
   * <p>★ 找不到 entry ⇒ 抛（播种器的内部不一致不许静默吞掉：那时铸币会拿到一份假的空配额表）。
   */
  static List<Map<String, Object>> mintAllocationsOf(
      HexCoord hex, List<Map<String, Object>> entries) {
    for (Map<String, Object> entry : entries) {
      int q = ((Number) entry.get("q")).intValue();
      int r = ((Number) entry.get("r")).intValue();
      if (q != hex.q() || r != hex.r()) {
        continue;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> allocations = (List<Map<String, Object>>) entry.get("allocations");
      return allocations;
    }
    throw new IllegalStateException(
        "铸币配额找不到落点 entry（seed 内部不一致）：hex=" + hex + " entries=" + entries.size());
  }

  /** 一个受方的载荷节点（{@code actor|household|cohort} 恰其一）——与 {@code EconomyPayloads.recipient} 同一形状。 */
  static Map<String, Object> payeeNode(Payee recipient) {
    Map<String, Object> node = new LinkedHashMap<>();
    if (recipient instanceof Payee.ToActor toActor) {
      node.put("actor", actorNode(toActor.actor()));
    } else if (recipient instanceof Payee.ToHousehold toHousehold) {
      node.put("household", toHousehold.household().value());
    } else if (recipient instanceof Payee.ToCohort toCohort) {
      node.put("cohort", toCohort.cohort().toString());
    } else {
      throw new IllegalStateException("未知 Payee 变体：" + recipient);
    }
    return node;
  }

  /** 一条显式生产关系 → 载荷节点（{@code operator/inputSupplier/residualOwner/rules/laborSource}）。 */
  static Map<String, Object> relationNode(ProductionRules relation) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("operator", actorNode(relation.operator()));
    node.put("inputSupplier", payeeNode(relation.inputSupplier()));
    node.put("residualOwner", actorNode(relation.residualOwner()));
    List<Map<String, Object>> rules = new ArrayList<>(relation.rules().size());
    for (CompensationRule rule : relation.rules()) {
      rules.add(ruleNode(rule));
    }
    node.put("rules", rules);
    node.put("laborSource", relation.laborSource().name());
    return node;
  }

  /** 一条补偿规则 → 载荷节点（{@code EconomyPayloads.compensationRule} 的逆形状）。 */
  static Map<String, Object> ruleNode(CompensationRule rule) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", rule.type().name());
    node.put("recipient", payeeNode(rule.recipient()));
    node.put("pool", rule.pool().name());
    node.put("weight", rule.weight().name());
    node.put("ratePerMille", rule.ratePerMille());
    node.put("fixedAmount", rule.fixedAmount());
    rule.commodity().ifPresent(commodity -> node.put("commodity", commodity.value()));
    rule.currency().ifPresent(currency -> node.put("currency", currency.value()));
    node.put("priority", rule.priority());
    return node;
  }
}
