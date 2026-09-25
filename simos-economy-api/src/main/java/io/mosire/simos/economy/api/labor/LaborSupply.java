package io.mosire.simos.economy.api.labor;

import io.mosire.simos.economy.api.id.PeopleLotId;

/**
 * ★★ **一个批次的劳动供给**（第三阶段设计稿 §四）：这批人**有多少劳动可以支配** —— 本阶段最重要的那条第 1 项。
 *
 * <pre>
 * availableLabor(group) = Σ(count × 年龄×性别劳动系数) − 已服役 − 已承诺
 *                          └─────── {@link #grossLaborMilli} ──────┘  └ {@link #servedLaborMilli} ┘ └ {@link #committedLaborMilli} ┘
 * </pre>
 *
 * <p>★★ **为什么"毛额 − 两项扣除"要落成一个类型而不是一个数**：设计稿 §四 钉的是**三项相减**的口径，而"服役"（徭役/军役）与
 * "承诺"（已经许给别人、尚未开工的义务）**是两个不同来源**——压成一个数会让"这个批次到底被谁占住了"变成读不出来的差额。 本轮两项**恒为
 * 0**（没有徭役系统、没有第二种义务），但**字段与入口都在**：本仓禁的不是"值暂时为 0"，而是"**看起来在记、其实永远不被读**"—— 而它们由 {@link
 * #availableLabor()} 读、由 {@code EconomyData} 的构造期不变量读（{@code 已服役 + 已承诺 ≤ 毛额}）， 且各有一条用例把 0 与"非 0
 * 时逐值可算"两条路都走到。
 *
 * <p>★★ **毛额（{@code Σ(count × 系数)}）由谁算**：**调用方**（{@code EconomySeeder}，它以 R1 已落地的年龄×性别系数表 {@code
 * AGE_LABOR_COEF_BY_SEX} 折算，**不另写一套**）。理由不是偷懒：{@code count} 与年龄性别住在 {@code PopulationGroup}
 * （**social** 的类型），而本模块（api）**只依赖 util/map** ⇒ 它**编译期不认识** {@code PopulationGroup}。设计稿 §八.1 允许的方向是
 * {@code social → economy-api}，反向的认识会当场把三层拆分拆掉。
 *
 * <p>★ **{@code period} = 这一份供给属于哪个周期**（世界周期序号，从 1 起）：配额是**按周期发的**（设计稿 §四），故供给也按期记； 本轮创世只发第 1
 * 期、且常设不改。{@code EconomyData} 用它判"每条配额都有一份**同期**的供给"（否则那条配额的上限根本无从谈起）。
 *
 * <p>★ **不变量（构造期判）**：{@code group} 非 null；{@code period ≥ 0}；三项劳动量各自 ≥ 0，且 {@code served +
 * committed ≤ gross}（否则 {@link #availableLabor()} 为负 —— "可支配劳动是负数"不是一种状态，是坏数据）。
 *
 * @param group 这批人（**人口的真值源在 social**；本类型只持它的稳定身份）
 * @param period 本份供给所属的世界周期序号（从 1 起）；不得为负
 * @param grossLaborMilli 毛劳动 = Σ(人数 × 年龄×性别系数)（千分劳动）；不得为负
 * @param servedLaborMilli 已服役（千分劳动）：本阶段恒 0；不得为负
 * @param committedLaborMilli 已承诺（千分劳动）：本阶段恒 0；不得为负
 */
public record LaborSupply(
    PeopleLotId group,
    long period,
    long grossLaborMilli,
    long servedLaborMilli,
    long committedLaborMilli) {

  public LaborSupply {
    if (group == null) {
      throw new IllegalArgumentException("LaborSupply.group 不得为 null");
    }
    if (period < 0L) {
      throw new IllegalArgumentException("LaborSupply.period 不得为负: " + period);
    }
    if (grossLaborMilli < 0L) {
      throw new IllegalArgumentException("LaborSupply.grossLaborMilli 不得为负: " + grossLaborMilli);
    }
    if (servedLaborMilli < 0L) {
      throw new IllegalArgumentException("LaborSupply.servedLaborMilli 不得为负: " + servedLaborMilli);
    }
    if (committedLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "LaborSupply.committedLaborMilli 不得为负: " + committedLaborMilli);
    }
    if (servedLaborMilli + committedLaborMilli > grossLaborMilli) {
      throw new IllegalArgumentException(
          "LaborSupply 的已服役 + 已承诺不得超过毛劳动（可支配劳动不得为负）："
              + servedLaborMilli
              + " + "
              + committedLaborMilli
              + " > "
              + grossLaborMilli);
    }
  }

  /**
   * ★★ **可支配劳动**（设计稿 §四 那条公式的落点）：{@code 毛额 − 已服役 − 已承诺}。
   *
   * <p>它是 {@code Σ allocated ≤ available} 那条**本阶段最重要的不变量**的右端，也是读口"该格可用劳动 / 占用率"的分母。
   */
  public long availableLabor() {
    return grossLaborMilli - servedLaborMilli - committedLaborMilli;
  }
}
