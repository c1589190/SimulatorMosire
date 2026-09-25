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
 * <p>★ **量纲**（§7）：货币类字段按**最小币值**；{@code income}/{@code consumed} 按**最小计量单位**、**逐商品**。
 *
 * <p>★★ **R3 起 {@code income} 是逐商品的表**（原来是一个标量）：田里**同时**出粮与纤维、作坊同时出布与工具，一条标量表达不了 "所得是什么"。它与 {@code
 * consumed} 因此**对称**（同一个 {@code Map<CommodityId, Long>} 形状），守恒式也按商品逐条成立。
 *
 * <p>★★ **{@code netSurplus} 仍是标量，口径 = 粮**（**留白，不是遗漏**）：把两种商品折成一个数需要**价格**，而市场与价格明确属 R4 的
 * V8（本轮"不做城乡交换/市场/价格"）⇒ 硬折会编造一个本轮没有的换算率。故它 = {@code income[grain] − consumed[grain] − taxPaid −
 * interestDue}（利息也以粮计：实物债的本金口径就是粮）。其余商品的净额在 {@code income}/{@code consumed} 两张表里
 * **分别读得到**，只是不汇总成一个数。
 *
 * <p>★★ **"本期"的边界（v2 spec §八.5；V5 落地）**：{@code EconomySettlement} 在**新周期的第一天**（{@code progressDays
 * == 0}，含创世）把该行**整行从 0 重记** —— 上周期末的读数在**关账那一支的 revision 里**读得到（归档），
 * 次日才归零（清零）。故关账日读到的是**一整个周期**的量（{@code income} 含那次收获的毛产分配），不是两个周期的累计。
 *
 * <p>★★ **未满足需求与饿死（2026-09-25 新增；V4 起默认不致命）**：{@code unmetNeed} = 本周期**需求 − 实得**的逐日累加（毫粮），
 * 是饿死判据的输入；{@code deaths} = 本周期因饿死而减少的人口（人）。两者都由 {@code EconomySettlement} 写入，
 * 且与其它字段**同口径**（本期量、新周期第一天归零）。
 *
 * <p>★★ **{@code deaths} 在默认路径上恒为 0**：致死率默认 {@code EconomySettlement.FAMINE_MORTALITY_PER_MILLE =
 * 0‰} （用户 2026-09-25：「可以先不做什么饿死人系统」）⇒ **缺口照记不误**（{@code unmetNeed} 非 0 是常态），但**不死人**。 字段**保留不删**（v1
 * spec §3.3 的形状，且致死判据落地时要用），旋钮是 {@code EconomySettlement} 的包内可见重载入参 （**不是** {@code static final} +
 * {@code if} 的死分支）。★ 读口读到的 {@code deaths == 0} 是**结论**，不是"没在记"。
 *
 * <p>★ **不变量（构造期判）**：{@code taxPaid}/{@code interestDue}/{@code newBorrowing}/{@code repaid}/{@code
 * unmetNeed}/{@code deaths} 均 {@code ≥ 0}；{@code income}/{@code consumed} 键值非空、逐值 {@code ≥
 * 0}。**{@code netSurplus} 允许为负** —— 它是"本期盈余/赤字" （§3.3 注释：income − 消费 − 税 − 利息），赤字是其正常取值，故**不设下界**。
 *
 * <p>★ 两张商品表都保序不可变（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**），冻结写在字段赋值处。
 *
 * @param key 身份（产业 + 槽位）；在 {@code EconomyData.flows} 里必须与其 Map 键一致
 * @param income 本期所得（**逐商品**，按最小计量单位）；键值非空、逐值 ≥ 0
 * @param consumed 本期消费（**逐商品**：口粮 + 留种 + 生产损耗）；键值非空、逐值 ≥ 0
 * @param taxPaid 本期纳税；不得为负
 * @param interestDue 本期应付利息；不得为负
 * @param newBorrowing 本期新借入；不得为负
 * @param repaid 本期偿还；不得为负
 * @param netSurplus 本期净盈余（**粮口径**：income[grain] − consumed[grain] − 税 − 利息；**可为负 = 赤字**）
 * @param unmetNeed 本期未满足的需求（**逐商品**：{@code 需求 − 实得} 的逐日累加，毫单位）；键值非空、逐值 ≥ 0；**新周期第一天归零**。 ★★ **R4
 *     起是逐商品的表**（原来是一个标量，口径只有粮）：spec §七 原文"粮食不足与衣物不足对死亡的时间尺度显然不能一样" ⇒
 *     两种缺口必须**各自读得出来**（合并成一个数就再也分不开）。形状与 {@code income}/{@code consumed} 对称。
 * @param deaths 本期死亡的人口（人）；不得为负。★★ **R4 起它有两条来源**：① {@code applyFamine}（直接按缺口处死， 默认致死率 0‰ ⇒
 *     默认路径不死人）；② **生理压力那条路**（{@code PopulationDynamics} 的月度结算，R4 的真正死亡来源） —— 两者都显式落在这里，故"人口守恒"逐值可核。
 * @param births 本期出生的人口（人）；不得为负；与 {@code deaths} **对称**（R4 起人口两头都会动，只记死亡会让 "年末人口 − 创世人口 == 出生 −
 *     死亡"写不出来）
 */
public record FlowRow(
    ClassKey key,
    Map<CommodityId, Long> income,
    Map<CommodityId, Long> consumed,
    long taxPaid,
    long interestDue,
    long newBorrowing,
    long repaid,
    long netSurplus,
    Map<CommodityId, Long> unmetNeed,
    long deaths,
    long births) {

  public FlowRow {
    if (key == null) {
      throw new IllegalArgumentException("FlowRow.key 不得为 null");
    }
    if (income == null) {
      throw new IllegalArgumentException("FlowRow.income 不得为 null（无所得用空 map）");
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
    if (unmetNeed == null) {
      throw new IllegalArgumentException("FlowRow.unmetNeed 不得为 null（无缺口用空 map）");
    }
    if (deaths < 0) {
      throw new IllegalArgumentException("FlowRow.deaths 不得为负: " + deaths);
    }
    if (births < 0) {
      throw new IllegalArgumentException("FlowRow.births 不得为负: " + births);
    }
    Map<CommodityId, Long> unmetCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : unmetNeed.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("FlowRow.unmetNeed 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "FlowRow.unmetNeed 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      unmetCopy.put(entry.getKey(), entry.getValue());
    }
    unmetNeed = Collections.unmodifiableMap(unmetCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> incomeCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : income.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("FlowRow.income 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "FlowRow.income 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      incomeCopy.put(entry.getKey(), entry.getValue());
    }
    income = Collections.unmodifiableMap(incomeCopy); // ★ 冻在赋值处
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
