package io.mosire.simos.production.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>外部属性事实</b>（D-032 约束设计书 §4.5）：由<b>组合根装配</b>、随本次调用传入的不可变事实表。
 *
 * <p>★★ <b>为什么是"事实表 + 作为入参"而不是一个查询接口</b>：
 *
 * <ol>
 *   <li>§4.2 硬约束：两个计算调用「<b>都不接受 economy 状态对象，只接受不可变输入值</b>」——一个能回读世界的 {@code Lookup}
 *       接口会把这条约束从类型面上抹掉；
 *   <li>试算必须<b>无副作用且可复现</b>（I5/V1/V2）：同一份事实 + 同一份状态必须给同一个答案；把属性做成
 *       入参，这一点是<b>结构上</b>成立的，而不是靠"实现者自觉不去读别的东西"；
 *   <li>§4.5「属性由所属领域提供、组合根装配为本次调用的不可变事实」——本类型就是那个事实。
 * </ol>
 *
 * <p>★★ <b>未知键的语义 = fail-closed</b>：{@link #valueOf} 对没有的键返回 {@code Optional.empty()}，
 * 由条件求值把它判成"不满足 + 具名理由"。★ 本类型<b>不</b>提供 {@code getOrDefault(key, 0)} 一类的口子—— 那正是"静默放行"的写法。
 *
 * <p>★ <b>保序不可变</b>：{@code values} 一律 {@code LinkedHashMap} 拷贝后冻结（迭代序是内容的纯函数）。
 *
 * @param values 属性键 → 值；不得为 null、键不得为空白、值不得为 null
 */
public record ExternalAttributeFacts(Map<String, Long> values) {

  /** ★ 空事实表（没有任何属性事实；所有外部属性条件一律不满足 = fail-closed）。 */
  public static final ExternalAttributeFacts EMPTY = new ExternalAttributeFacts(Map.of());

  public ExternalAttributeFacts {
    if (values == null) {
      throw new IllegalArgumentException("ExternalAttributeFacts.values 不得为 null（无属性用空表）");
    }
    Map<String, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("ExternalAttributeFacts.values 的键不得为空白");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ExternalAttributeFacts.values 的值不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    values = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 该键的事实值；<b>没有这个键 ⇒ empty</b>（调用方按 fail-closed 处理，不在这里兜底成 0）。 */
  public Optional<Long> valueOf(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(key));
  }

  /**
   * ★★ <b>条件求值</b>（唯一拼写点）：事实表与门槛 → 满足 / 不满足（不满足时带具名理由）。
   *
   * <p>★ 外部属性条件这里只判"事实存在且 ≥ 门槛"；⭐ {@link ProductionAvailabilityCondition.MinimumExperience}
   * 需要经验账，不在本类型的职责里，<b>本方法对它返回"不满足 + 理由"</b>而不是猜——猜就会静默放行。
   */
  public Verdict evaluate(ProductionAvailabilityCondition condition) {
    if (condition == null) {
      throw new IllegalArgumentException("ExternalAttributeFacts.evaluate 的 condition 不得为 null");
    }
    if (condition instanceof ProductionAvailabilityCondition.ExternalAttribute attribute) {
      Optional<Long> fact = valueOf(attribute.key());
      if (fact.isEmpty()) {
        // ★ fail-closed（§4.5）：缺事实 ⇒ 不满足，且理由是具名的"缺事实"而不是"值不够"。
        return new Verdict(false, "external-attribute-missing:" + attribute.key());
      }
      if (fact.get() < attribute.minimumValue()) {
        return new Verdict(false, condition.describe());
      }
      return new Verdict(true, null);
    }
    return new Verdict(
        false, "condition-not-evaluable-by-external-attributes:" + condition.describe());
  }

  /**
   * 条件求值结论。
   *
   * @param satisfied 是否满足
   * @param reason 不满足时的具名理由；满足时为 null
   */
  public record Verdict(boolean satisfied, String reason) {

    public Verdict {
      if (!satisfied && (reason == null || reason.isBlank())) {
        throw new IllegalArgumentException("ExternalAttributeFacts.Verdict 不满足时必须给具名理由");
      }
    }
  }
}
