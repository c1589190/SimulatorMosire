package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 周期流水（新经济设计 §3.3 逐字，表 3）：**本期发生额**——结算后清零，绝不回流成存量。
 *
 * <p>★★ **存量/流量分离**（§3.3 末条 + §6.5）：{@link HouseholdEconomy} 是存量；本类型只记本期发生额，**结算后清零**。 绝不用"生产成本"或"资产减少"
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
 * <p>★★ **"本期"的边界（v2 spec §八.5；V5 落地）**：{@code 旧结算引擎（R3a 已删除）} 在**新周期的第一天**（{@code progressDays ==
 * 0}，含创世）把该行**整行从 0 重记** —— 上周期末的读数在**关账那一支的 revision 里**读得到（归档），
 * 次日才归零（清零）。故关账日读到的是**一整个周期**的量（{@code income} 含那次收获的毛产分配），不是两个周期的累计。
 *
 * <p>★★ **未满足需求与饿死（2026-09-25 新增；V4 起默认不致命）**：{@code unmetNeed} = 本周期**需求 − 实得**的逐日累加（毫粮），
 * 是饿死判据的输入；{@code deaths} = 本周期因饿死而减少的人口（人）。两者都由 {@code 旧结算引擎（R3a 已删除）} 写入，
 * 且与其它字段**同口径**（本期量、新周期第一天归零）。
 *
 * <p>★★ **{@code deaths} 在默认路径上恒为 0**：致死率默认 {@code 旧结算引擎（R3a 已删除）.FAMINE_MORTALITY_PER_MILLE = 0‰}
 * （用户 2026-09-25：「可以先不做什么饿死人系统」）⇒ **缺口照记不误**（{@code unmetNeed} 非 0 是常态），但**不死人**。 字段**保留不删**（v1
 * spec §3.3 的形状，且致死判据落地时要用），旋钮是 {@code 旧结算引擎（R3a 已删除）} 的包内可见重载入参 （**不是** {@code static final} +
 * {@code if} 的死分支）。★ 读口读到的 {@code deaths == 0} 是**结论**，不是"没在记"。
 *
 * <p>★ **不变量（构造期判）**：{@code taxPaid}/{@code interestDue}/{@code newBorrowing}/{@code repaid}/{@code
 * unmetNeed}/{@code deaths} 均 {@code ≥ 0}；{@code income}/{@code consumed} 键值非空、逐值 {@code ≥
 * 0}。**{@code netSurplus} 允许为负** —— 它是"本期盈余/赤字" （§3.3 注释：income − 消费 − 税 − 利息），赤字是其正常取值，故**不设下界**。
 *
 * <p>★ 两张商品表都保序不可变（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**），冻结写在字段赋值处。
 *
 * @param id 家户稳定身份（S1；见 {@link HouseholdEconomy} 的类注）；在 {@code EconomyData.flows} 里必须与其 Map 键一致
 * @param income 本期所得（**逐商品**，按最小计量单位）；键值非空、逐值 ≥ 0
 * @param consumed ★★ <b>本期"从本行账上离开"的量</b>（**逐商品**；键值非空、逐值 ≥ 0）—— <b>只含两项</b>：
 *     <ol>
 *       <li>{@code 旧结算引擎（R3a 已删除）.consumeOwnStock} 的**日耗**（口粮/衣着，含当天借来就吃掉的那一笔）；
 *       <li>{@code drawCycleInputs} 的**现扣周期投入**（种子/原料；在"谁出料"的那一行的流水里）。
 *     </ol>
 *     ★★ <b>生产损耗不在里面</b>（饲料 + 折旧）：损耗不是"谁消费了"，是"蒸发了" ⇒ 只进 {@code
 *     ProductionLedger.losses()}，在守恒式里自成一项（见 {@code 旧结算引擎（R3a 已删除）} §6.1 的 H1 式）。 ★★
 *     <b>"同格取材转出"也不在里面</b>（H3 起那条通道整块删掉了）。⇒ 于是"真正被吃掉的"= {@code consumed} − 当天 ledger 的投入 —— 这正是守恒式里
 *     {@code ΣFinalConsumption} 的定义，**不要**把 {@code consumed} 直接读成"消费"。
 *     <p>★★ <b>历史留痕</b>（改前口径，已作废）：本字段曾写作"口粮 + 留种 + 生产损耗"。三者都不对：留种是**投入**不是消费、
 *     损耗不进这里、而"同格取材转出"曾一度混进来。实测有 3,931 行的 {@code consumed > 3 × 自然需求} —— 那就是这条旧口径的痕迹。
 * @param taxPaid 本期纳税；不得为负
 * @param interestDue 本期应付利息；不得为负
 * @param newBorrowing 本期新借入；不得为负
 * @param repaid 本期偿还**实际走粮腿**的毫粮（不得为负）—— ★ P11.1 / D-023 起它不再等于“粮债本金”：粮可以按价折付任何计价口径的债务，
 *     这里记真实出账的粮；货币腿见 {@link #repaidMoney()}；其它商品腿从对应合同的 {@code principal} 下降 + 转移凭据读出（本窗口不发按商品的偿还表，
 *     缺失是具名的）。窗口与 {@code income} 同：本周期累计、新周期第一天归零。
 * @param netSurplus 本期净盈余（**粮口径**：income[grain] − consumed[grain] − 税 − 利息；**可为负 = 赤字**）
 * @param unmetNeed 本期未满足的需求（**逐商品**：{@code 需求 − 实得} 的逐日累加，毫单位）；键值非空、逐值 ≥ 0；**新周期第一天归零**。 ★★ **R4
 *     起是逐商品的表**（原来是一个标量，口径只有粮）：spec §七 原文"粮食不足与衣物不足对死亡的时间尺度显然不能一样" ⇒
 *     两种缺口必须**各自读得出来**（合并成一个数就再也分不开）。形状与 {@code income}/{@code consumed} 对称。
 *     <p>★★ <b>统计窗口（M0.2 写明，报数前必读）</b>：口径 = <b>本周期累计</b>，<b>归档在关账那一支的 revision 里</b>、
 *     次日（新周期第一天）清零。⇒ ① <b>关账日</b>读到的是一整个周期缺的量（正确用法）；② <b>非关账日</b>读到的是"本周期到现在为止"
 *     的量（会逐日长大，**不可**当成整周期缺口）；③ 关账日的 {@code income}/{@code consumed} 同样是一整个周期的量。 三处读法混用是本仓踩过六次的口径坑。
 * @param deaths 本期死亡的人口（人）；不得为负。★★ **R4 起它有两条来源**：① {@code applyFamine}（直接按缺口处死， 默认致死率 0‰ ⇒
 *     默认路径不死人）；② **生理压力那条路**（{@code PopulationDynamics} 的月度结算，R4 的真正死亡来源） —— 两者都显式落在这里，故"人口守恒"逐值可核。
 *     <p>★ <b>它不是饿死数</b>：默认致死率 0‰ ⇒ 缺粮本身不产生 {@code deaths}（M0.2 的口径澄清）。报"饿死多少人"必须写清用的是哪条通道。
 * @param births 本期出生的人口（人）；不得为负；与 {@code deaths} **对称**（R4 起人口两头都会动，只记死亡会让 "年末人口 − 创世人口 == 出生 −
 *     死亡"写不出来）
 * @param repaidMoney ★★ <b>P11.1 / D-023：本期偿还**实际走货币腿**的逐币种毫钱</b>—— 键值非空、逐值 ≥ 0。 <b>不塞进 {@link
 *     #repaid()}</b>（那个标量是粮口径；把钱记成粮 = 篡改单位）。窗口与 {@code income}/消费同： 本周期累计、新周期第一天归零。★
 *     它不再等于“货币债本金”：钱可以按价折付任何计价口径的债务，这里记真实出账的钱；贷方收到的钱由 actor 账户与合同本金下降读，两处同值。
 * @param capitalizedArrears ★★ <b>E4c：本期资本化的欠租/欠薪（按 {@code DebtUnit.key()} 分组，例如 {@code
 *     "commodity:grain"} / {@code "money:silver"}）</b>—— 键为稳定 unit 串、值为本金增量（该 unit 的最小计量单位）。
 *     它<b>不是</b>库存/货币流动（资本化只记债权，不搬粮/钱），故<b>不</b>进 {@code newBorrowing}（借入才是那个字段）；
 *     它记的是"制度规定未付"转成合同债权的额度。窗口同上：本周期累计、新周期第一天归零； 读不到（旧档缺键）⇒ 空表 = 本周期没有资本化发生，而不是"没记账"。
 */
public record FlowRow(
    HouseholdId id,
    Map<CommodityId, Long> income,
    Map<CommodityId, Long> consumed,
    long taxPaid,
    long interestDue,
    long newBorrowing,
    long repaid,
    long netSurplus,
    Map<CommodityId, Long> unmetNeed,
    long deaths,
    long births,
    Map<CurrencyId, Long> repaidMoney,
    Map<String, Long> capitalizedArrears) {

  public FlowRow {
    if (id == null) {
      throw new IllegalArgumentException("FlowRow.id 不得为 null（家户稳定身份，S1 起与视图分离）");
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
    // ★ E4c：旧档/手写 JSON 缺键时 Jackson 会把追加的两个 Map 绑成 null ⇒ 统一收成空表（"这一期没有这笔发生额"）。
    if (repaidMoney == null) {
      repaidMoney = Map.of();
    }
    if (capitalizedArrears == null) {
      capitalizedArrears = Map.of();
    }
    Map<CurrencyId, Long> repaidMoneyCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : repaidMoney.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("FlowRow.repaidMoney 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "FlowRow.repaidMoney 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      repaidMoneyCopy.put(entry.getKey(), entry.getValue());
    }
    repaidMoney = Collections.unmodifiableMap(repaidMoneyCopy); // ★ 冻在赋值处
    Map<String, Long> capitalizedCopy = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : capitalizedArrears.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "FlowRow.capitalizedArrears 的键（DebtUnit.key()）与值都不得为 null/空白: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "FlowRow.capitalizedArrears 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      capitalizedCopy.put(entry.getKey(), entry.getValue());
    }
    capitalizedArrears = Collections.unmodifiableMap(capitalizedCopy); // ★ 冻在赋值处
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

  /**
   * ★★ <b>P2-D：辖区日税落进本行流水读数的唯一写法</b>。
   *
   * <p>语义：本期"粮口径"纳税累加 {@code delta}（毫粮），同时按本记录的既定恒等式 {@code netSurplus = income[grain] −
   * consumed[grain] − taxPaid − interestDue} 把 {@code netSurplus}
   * 同额减少——两处一起动，读口才不会出现"税涨了、净盈余没动"的自相矛盾。
   *
   * <p>★ <b>为什么只收粮口径</b>：{@code taxPaid} 的文档量纲是毫粮（{@link #netSurplus} 与 {@code DebtCapacityBook}
   * 都按粮读它）。货币税没有这个字段，只能在账户余额与日志里读 （P2-D 具名缺口：{@code FlowRow} 没有货币税位）。调用方对银税不要调本方法。
   *
   * <p>★ {@code delta == 0} ⇒ 返回 {@code this}（不是新实例）；{@code delta < 0} 当场抛（税不倒退）。
   */
  public FlowRow withAdditionalGrainTaxPaid(long delta) {
    if (delta < 0L) {
      throw new IllegalArgumentException("withAdditionalGrainTaxPaid 的 delta 不得为负: " + delta);
    }
    if (delta == 0L) {
      return this;
    }
    return new FlowRow(
        id,
        income,
        consumed,
        Math.addExact(taxPaid, delta),
        interestDue,
        newBorrowing,
        repaid,
        Math.subtractExact(netSurplus, delta),
        unmetNeed,
        deaths,
        births,
        repaidMoney,
        capitalizedArrears);
  }

  /**
   * ★ 兼容别名：{@code id}（家户稳定身份）。旧调用点的 {@code flow.key()} 逐字换成 {@code flow.id()} 即可； 本别名只是让"键 ==
   * 值内键"的守卫读起来与原口径同形。
   */
  public HouseholdId key() {
    return id;
  }
}
