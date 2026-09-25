package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 周期流水（新经济设计 §3.3 逐字，表 3）：**本期发生额**——结算后清零，绝不回流成存量。
 *
 * <p>★★ **存量/流量分离**（§3.3 末条 + §6.5）：{@link ClassRow} 是存量；本类型只记本期发生额，**结算后清零**。 绝不用"生产成本"或"资产减少"
 * 冒充负债——{@code newBorrowing} 才是借入，{@code repaid} 才是偿债。
 *
 * <p>★ **量纲**（§7）：货币类字段按**最小币值**；{@code consumed} 按**最小计量单位**。{@code income} 的实物部分按当周期
 * **"粮值"折算**（口径入 {@code rulesVersion}，§7），故它只是一个数、不落成第二份真相。
 *
 * <p>★ **不变量（构造期判）**：{@code income}/{@code taxPaid}/{@code interestDue}/{@code newBorrowing}/{@code
 * repaid} 均 {@code ≥ 0}；{@code consumed} 键值非空、逐值 {@code ≥ 0}。**{@code netSurplus} 允许为负** ——
 * 它是"本期盈余/赤字" （§3.3 注释：income − 消费 − 税 − 利息），赤字是其正常取值，故**不设下界**。
 *
 * <p>★ {@code consumed} 保序不可变（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用
 * {@code Map.copyOf}**），冻结写在字段赋值处。
 *
 * @param key 身份（产业 + 槽位）；在 {@code EconomyData.flows} 里必须与其 Map 键一致
 * @param income 本期所得（实物按当周期"粮值"折算，§7）；不得为负
 * @param consumed 本期消费（按商品）；键值非空、逐值 ≥ 0
 * @param taxPaid 本期纳税；不得为负
 * @param interestDue 本期应付利息；不得为负
 * @param newBorrowing 本期新借入；不得为负
 * @param repaid 本期偿还；不得为负
 * @param netSurplus 本期净盈余（income − 消费 − 税 − 利息；**可为负 = 赤字**）
 */
public record FlowRow(
    ClassKey key,
    long income,
    Map<CommodityId, Long> consumed,
    long taxPaid,
    long interestDue,
    long newBorrowing,
    long repaid,
    long netSurplus) {

  public FlowRow {
    if (key == null) {
      throw new IllegalArgumentException("FlowRow.key 不得为 null");
    }
    if (income < 0) {
      throw new IllegalArgumentException("FlowRow.income 不得为负: " + income);
    }
    if (consumed == null) {
      throw new IllegalArgumentException("FlowRow.consumed 不得为 null（无消费用空 map）");
    }
    if (taxPaid < 0) {
      throw new IllegalArgumentException("FlowRow.taxPaid 不得为负: " + taxPaid);
    }
    if (interestDue < 0) {
      throw new IllegalArgumentException("FlowRow.interestDue 不得为负: " + interestDue);
    }
    if (newBorrowing < 0) {
      throw new IllegalArgumentException("FlowRow.newBorrowing 不得为负: " + newBorrowing);
    }
    if (repaid < 0) {
      throw new IllegalArgumentException("FlowRow.repaid 不得为负: " + repaid);
    }
    Map<CommodityId, Long> consumedCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : consumed.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("FlowRow.consumed 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "FlowRow.consumed 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      consumedCopy.put(entry.getKey(), entry.getValue());
    }
    consumed = Collections.unmodifiableMap(consumedCopy); // ★ 冻在赋值处
  }
}
