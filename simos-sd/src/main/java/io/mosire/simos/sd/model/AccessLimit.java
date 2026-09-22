package io.mosire.simos.sd.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * **GM 配的额外限制**（spec §4.2；用户 2026-09-22 裁定③）：取代旧的 {@code ViewScope}（那套是 GM **绝对指定**可见集合）。
 *
 * <p>★★ **这是换语义，不只是改名**：决策人"能看见什么"现在由 **app 层的范围函数现算**（`NationScope` = 本国 {@code nation:<id>} tag
 * 的区域、`ArmyScope` = 位置 + 视野圈；spec §3.1/§3.2）。GM 的配权在新模型里是**额外限制** ——与范围函数的结果做**交集**（AgentLib 的
 * {@code ResourceScopeMap#narrowTo}，语义 = "两边都要满足"）⇒ **GM 只能额外收紧、不能放大**。 旧的 {@code viewScope} 反过来：它是
 * GM **绝对指定**的可见集合，范围函数说什么都不算数。
 *
 * <p>★ **{@code prefixesByNamespace} 为空 = 本层不表态 = 不收紧**（不是 deny-all）：spec §5.2 第 3 条—— {@code
 * null}/空图是"本层不表态"（放行），要"够不着"必须**显式**给一个空前缀集 ⇒ 翻译成 {@code ResourceScope.none()}。
 * 两条方向相反，配错即**静默放宽**。
 *
 * <p>★ **sd 自己的描述类型**（spec §六.1）：AgentLib 的权限类型（{@code ResourceScope} 等）**不进 sd**—— sd 是另一个模块（铁律 3
 * 的结构化），且它们的 Jackson 往返未验。本类型只装字符串，**可往返、可回放**（铁律 2/5）， app 层在调用时刻把它翻译成 AgentLib 权限组。
 *
 * <p>★ **{@code redactedFields} 与 {@code adjudicationDisclosure} 一并收在这里**（取代说明，两处都不在 spec §4.2
 * 的载荷示例里，属实现期收口）：前者是**字段级**的只减不增（与资源级限制同向），后者是判决三档披露—— spec §4.2 明写"是 sd 域语义、保留，从 {@code ViewScope}
 * 迁到新字段"，本条即那个"新字段"。三者都是"GM 额外施加的可见性约束"， 合并成一个类型后**只有一个写点**，不会出现"改了资源限制、披露档留在旧类型里"的半迁移。
 *
 * <p>★ 集合一律**保序不可变**（{@code LinkedHashSet}/{@code TreeMap} + 冻在赋值处；**禁用** {@code
 * Set.copyOf}——迭代序不是内容的纯函数）。冻结写在赋值处是 SpotBugs 的硬要求（{@code EI_EXPOSE_REP} 只认构造器体内看得见的包装调用）。
 *
 * <p>★★ **故意没有 {@code isEmpty()}**：Jackson 会把 record 上那个派生判断内省成属性 {@code empty} 写进字节，而读侧严格 ⇒
 * **写出来的档自己读不回**（M4 裁定 39 的原形；共享层的摘除规则只覆盖 {@code ChangeSet} 实现，**不覆盖**本类型）。 要判"是否什么都没限制"就逐分量看
 * {@code prefixesByNamespace().isEmpty()}。
 */
public record AccessLimit(
    Map<String, Set<String>> prefixesByNamespace,
    Set<String> redactedFields,
    DisclosurePolicy adjudicationDisclosure) {

  public AccessLimit {
    if (prefixesByNamespace == null) {
      throw new IllegalArgumentException("prefixesByNamespace 不得为 null");
    }
    if (redactedFields == null) {
      throw new IllegalArgumentException("redactedFields 不得为 null");
    }
    if (adjudicationDisclosure == null) {
      throw new IllegalArgumentException("adjudicationDisclosure 不得为 null");
    }
    // ★ 命名空间用 TreeMap 冻住：键集进 JSON 时顺序稳定（"同状态两次编码逐字节相同"的前提）。
    Map<String, Set<String>> byNamespace = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : prefixesByNamespace.entrySet()) {
      String namespace = entry.getKey();
      if (namespace == null || namespace.isBlank()) {
        throw new IllegalArgumentException("prefixesByNamespace 的命名空间不得为空白");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("prefixesByNamespace[" + namespace + "] 不得为 null");
      }
      Set<String> prefixes = new TreeSet<>();
      for (String prefix : entry.getValue()) {
        if (prefix == null || prefix.isBlank()) {
          throw new IllegalArgumentException("prefixesByNamespace[" + namespace + "] 不得含空白前缀");
        }
        prefixes.add(prefix);
      }
      byNamespace.put(namespace, Collections.unmodifiableSet(prefixes)); // ★ 冻在赋值处
    }
    prefixesByNamespace = Collections.unmodifiableMap(byNamespace); // ★ 冻在赋值处

    Set<String> fields = new LinkedHashSet<>();
    for (String field : redactedFields) {
      if (field == null || field.isBlank()) {
        throw new IllegalArgumentException("redactedFields 不得含空白");
      }
      fields.add(field);
    }
    redactedFields = Collections.unmodifiableSet(fields); // ★ 冻在赋值处
  }

  /**
   * **无额外限制**（= 旧 {@code ViewScope.empty()} 的位置，但语义相反）。
   *
   * <p>旧空范围是 **deny-all**（什么都没有）；本条是 **不收紧**——范围函数说什么就是什么。缺省必须是它，否则 {@code sd.CreateDecisionMaker}
   * 建出来的决策人当场变瞎（与 spec §4.2「可为空 = 无额外限制」一致）。
   *
   * <p>{@code adjudicationDisclosure} 仍取 {@link DisclosurePolicy#WITHHELD}（fail-closed，与旧 {@code
   * optionalDisclosure} 的缺省同口径）：判决是另一维，"没配"不等于"全披露"。
   */
  public static AccessLimit empty() {
    return new AccessLimit(Map.of(), Set.of(), DisclosurePolicy.WITHHELD);
  }

  /** 只给前缀（另两维取缺省）——用例与"只配资源范围"的调用点的便利形态。 */
  public static AccessLimit ofPrefixes(Map<String, Set<String>> prefixesByNamespace) {
    return new AccessLimit(prefixesByNamespace, Set.of(), DisclosurePolicy.WITHHELD);
  }
}
