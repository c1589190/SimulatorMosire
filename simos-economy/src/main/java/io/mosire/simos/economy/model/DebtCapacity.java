package io.mosire.simos.economy.model;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>E4b：一个家户的债务容量（F / headroom）—— 不可变、只读的结果类型</b>（理想架构 §5.2／§5.3 的 {@code
 * DebtCapacity}，2026-09-29 E4b）。
 *
 * <p>★★ <b>公式（唯一的拼写点在 {@link #F()}、{@link #pledgeableRealAssetValue()} 与 {@link #headroom()}）</b>：
 *
 * <pre>
 * F        = max(0, afterAllocationGrainIncome − basicRation − nextRoundNecessaryInput − taxPaid)
 * 可质押真实资产价值 = 可自用余粮（本 record 存成 pledgeableGrainSurplusValue）
 *                    + pledgeableAssetPolicyValue（显式钩子；E5 的 LiquidationPolicy／价格源未落地前恒 0）
 * headroom = max(0, ⌊κ × F ÷ 1000⌋ + 可质押真实资产价值 − existingDebt)     κ = {@link #CREDIT_F_MULTIPLE_PER_MILLE}
 * </pre>
 *
 * <p>★★ <b>每个输入的来源与统计窗口</b>（调用方 {@code DebtCapacityBook} 负责按这些口径取值，本类型只存结果）：
 *
 * <table border="1">
 *   <caption>输入来源</caption>
 *   <tr><th>字段</th><th>来源</th><th>窗口</th></tr>
 *   <tr><td>{@link #afterAllocationGrainIncome()}</td><td>{@code FlowRow.income} 的粮商品维</td>
 *       <td><b>本周期已实现</b>（逐日累加、关账日含那次收获；新周期第一天清零；没有 = 0）</td></tr>
 *   <tr><td>{@link #basicRation()}</td><td>{@code ClassRow.cycleNaturalNeedMilli}</td>
 *       <td><b>本周期累计</b>自然口粮需要（逐日按日初人口累加；新周期第一天重置为当天那一份）</td></tr>
 *   <tr><td>{@link #nextRoundNecessaryInput()}</td><td>该家户 operator 的 unit 的产业配方
 *       （{@code ⌊plannedCapacityScale × inputPerUnit[GRAIN]⌋}），或具名下标的代理口径
 *       {@link NextRoundNecessaryInputSource#NON_RATION_CONSUMED_PROXY}</td>
 *       <td><b>下一轮</b>（配方口径 = 读口时点的 unit／资产／状态；代理口径 = 本周期）</td></tr>
 *   <tr><td>{@link #taxPaid()}</td><td>{@code FlowRow.taxPaid}</td>
 *       <td><b>本周期已缴</b>（当前生产路径恒 0；照实读，不伪造）</td></tr>
 *   <tr><td>{@link #pledgeableGrainSurplusValue()}</td><td>调用方传入的粮库存
 *       − 本周期自需（与放贷方 {@code EconomySettlement.lendableOf} 同一算式、同一保留额）</td>
 *       <td><b>时点</b>（会话工作副本的调用时值）；库存读不到 ⇒ {@link OptionalLong#empty()}，<b>不是 0</b></td></tr>
 *   <tr><td>{@link #pledgeableAssetPolicyValue()}</td><td>显式钩子（E5 的 {@code LiquidationPolicy}／价格源）</td>
 *       <td><b>时点</b>；E4b 两侧调用点都传 {@link #PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED}（0），
 *       但在结果里单独列出，禁止静默省略</td></tr>
 *   <tr><td>{@link #existingDebt()}</td><td>该家户名下<b>同 unit（粮）</b>的 {@code DebtContract.principal} 合计</td>
 *       <td><b>时点</b>本金余额（E4b 只对粮 unit 算粮信用线）</td></tr>
 *   <tr><td>{@link #unpricedDebtAmount()}／{@link #unpricedDebtCount()}</td>
 *       <td>非粮 unit 且不能折算（{@code terms.monetaryConversion == NOT_ALLOWED}，或没有有效价格）的债务本金</td>
 *       <td><b>时点</b>；不硬折 ⇒ 明确排除在 {@link #headroom()} 之外、单独计数</td></tr>
 * </table>
 *
 * <p>★★ <b>下一轮必要投入的“代理”口径必须被叫成代理</b>：当该家户名下<b>没有任何可解析 operator unit</b> 时， {@code DebtCapacityBook}
 * 取 {@code max(0, 本周期 consumed[grain] − cycleNaturalNeedMilli)} 作为 <b>“本周期实际非口粮投入”的代理</b>（{@link
 * NextRoundNecessaryInputSource#NON_RATION_CONSUMED_PROXY}）， <b>不是</b>真实的下一轮投入；读口把它作为 {@code
 * nextRoundNecessaryInputSource} 原样发出。真正的配方口径见 {@link NextRoundNecessaryInputSource#RECIPE}。
 *
 * <p>★★ <b>单位</b>：见 {@link #UNIT_NOTE} —— 所有字段都是<b>毫粮</b>（{@code 1 粮 = 1000 毫粮}）， 除 {@link
 * #unpricedDebtAmount()} 是“各债各自计量单位的原始本金和”（跨 unit 混合，只作审计、<b>不参与</b> headroom；分单位明细见读口的债务明细）。
 *
 * <p>★★ <b>它不产生任何库存／货币／债务变更</b>：本类型是只读结果；构造期只做非负与派生校验，不写任何状态。
 *
 * @param afterAllocationGrainIncome 本周期已实现粮所得（毫粮）；不得为负
 * @param basicRation 本周期累计自然口粮需要（毫粮）；不得为负
 * @param nextRoundNecessaryInput 下一轮必要粮投入（毫粮）；不得为负
 * @param nextRoundNecessaryInputSource 下一轮必要投入的口径来源；不得为 null
 * @param taxPaid 本周期实缴税（毫粮）；不得为负
 * @param pledgeableGrainSurplusValue 可自用余粮（毫粮）；空 = 粮库存读不到（具名缺失），不是 0
 * @param pledgeableAssetPolicyValue 显式可质押资产政策价值钩子（毫粮）；E4b 恒 0；不得为负
 * @param existingDebt 同 unit（粮）既有本金合计（毫粮）；不得为负
 * @param unpricedDebtAmount 不能折算的非粮债务本金原始和（跨 unit，审计口径）；不得为负
 * @param unpricedDebtCount 不能折算的非粮债务条数；不得为负
 */
public record DebtCapacity(
    long afterAllocationGrainIncome,
    long basicRation,
    long nextRoundNecessaryInput,
    NextRoundNecessaryInputSource nextRoundNecessaryInputSource,
    long taxPaid,
    OptionalLong pledgeableGrainSurplusValue,
    long pledgeableAssetPolicyValue,
    long existingDebt,
    long unpricedDebtAmount,
    int unpricedDebtCount) {

  /**
   * ★★ <b>信用公式里 F 的乘数 κ（千分数）</b>：<b>1000</b>，复现旧信用线的乘数 （旧 {@code
   * EconomySettlement.LOAN_INCOME_MULTIPLE_PER_MILLE = 1000}）。<b>唯一拼写点</b>：旧的公开常量现在是
   * {@code @Deprecated} 别名，生产代码只读这里。
   *
   * <p>★ 它是制度层参数：{@code 0} = 五 F 即无 F 信用；{@code 2000} = 允许两倍 F（“宽信用”）。V7 参数目录落地后迁入参数表。
   */
  public static final int CREDIT_F_MULTIPLE_PER_MILLE = 1000;

  /**
   * ★★ <b>显式可质押资产政策价值的“未落地”默认值</b>：<b>0</b>。
   *
   * <p>E4b 不做 E5 的 {@code LiquidationPolicy}／价格源，故两侧调用点都传本值；但它在 {@link
   * #pledgeableAssetPolicyValue()} 里<b>单独列出</b>（不折进“可自用余粮”一起静默吞掉）—— 将来钩子接线时改的只是这一处输入。
   */
  public static final long PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED = 0L;

  /**
   * ★★ <b>单位口径说明</b>（读口原样发出）：全部数值为<b>毫粮</b>（毫最小计量单位；{@code 1 粮 = 1000 毫粮}）——
   * 粮库存、粮所得、口粮需要、下一轮投入、既有债务本金与 headroom 同单位；{@link #unpricedDebtAmount()} 例外（见类注）。
   */
  public static final String UNIT_NOTE =
      "毫粮（1 粮 = 1000 毫粮）：本周期粮所得/口粮需要/下一轮投入/实缴税/可自用余粮/政策钩子/既有粮债本金/headroom 同单位；"
          + "unpricedDebtAmount 是各非粮债各自计量单位的原始本金和（跨 unit、仅审计、不参与 headroom）";

  /** ★★ 下一轮必要投入的两种口径（读口按枚举名发出；代理口径不许被读成配方口径）。 */
  public enum NextRoundNecessaryInputSource {
    /** 配方口径：该家户 operator 的 unit × 产业 {@code inputPerUnit[GRAIN]}（尽力而为的真实下一轮投入）。 */
    RECIPE,
    /** 代理口径：{@code max(0, 本周期 consumed[grain] − cycleNaturalNeedMilli)}（当前实际非口粮投入，不是下一轮投入）。 */
    NON_RATION_CONSUMED_PROXY
  }

  public DebtCapacity {
    if (afterAllocationGrainIncome < 0L) {
      throw new IllegalArgumentException(
          "DebtCapacity.afterAllocationGrainIncome 不得为负: " + afterAllocationGrainIncome);
    }
    if (basicRation < 0L) {
      throw new IllegalArgumentException("DebtCapacity.basicRation 不得为负: " + basicRation);
    }
    if (nextRoundNecessaryInput < 0L) {
      throw new IllegalArgumentException(
          "DebtCapacity.nextRoundNecessaryInput 不得为负: " + nextRoundNecessaryInput);
    }
    Objects.requireNonNull(
        nextRoundNecessaryInputSource, "DebtCapacity.nextRoundNecessaryInputSource 不得为 null");
    if (taxPaid < 0L) {
      throw new IllegalArgumentException("DebtCapacity.taxPaid 不得为负: " + taxPaid);
    }
    if (pledgeableGrainSurplusValue == null) {
      pledgeableGrainSurplusValue = OptionalLong.empty(); // 旧档/手写 JSON 的缺键口径：空 = 读不到，不是 0
    }
    if (pledgeableGrainSurplusValue.isPresent() && pledgeableGrainSurplusValue.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "DebtCapacity.pledgeableGrainSurplusValue 不得为负: "
              + pledgeableGrainSurplusValue.getAsLong());
    }
    if (pledgeableAssetPolicyValue < 0L) {
      throw new IllegalArgumentException(
          "DebtCapacity.pledgeableAssetPolicyValue 不得为负: " + pledgeableAssetPolicyValue);
    }
    if (existingDebt < 0L) {
      throw new IllegalArgumentException("DebtCapacity.existingDebt 不得为负: " + existingDebt);
    }
    if (unpricedDebtAmount < 0L) {
      throw new IllegalArgumentException(
          "DebtCapacity.unpricedDebtAmount 不得为负: " + unpricedDebtAmount);
    }
    if (unpricedDebtCount < 0) {
      throw new IllegalArgumentException(
          "DebtCapacity.unpricedDebtCount 不得为负: " + unpricedDebtCount);
    }
  }

  /**
   * ★★ <b>可偿还流量 F</b>（毫粮）：
   *
   * <pre>
   * F = max(0, afterAllocationGrainIncome − basicRation − nextRoundNecessaryInput − taxPaid)
   * </pre>
   *
   * <p>★ 逐项相减都先把结果截到 0（避免三项同时巨大时的中间下溢）：{@code max(0, max(0, max(0, income − ration) − next) −
   * tax)}。 于是 F 恒 ≥ 0，且缺一项就只按缺的那一项收紧，不会把后项“欠”出负数。
   */
  public long F() {
    long f = Math.max(0L, afterAllocationGrainIncome - basicRation);
    f = Math.max(0L, f - nextRoundNecessaryInput);
    return Math.max(0L, f - taxPaid);
  }

  /** ★ 下一轮投入是否用的是代理口径（读口可读性；等价于 {@code source == NON_RATION_CONSUMED_PROXY}）。 */
  public boolean nextRoundNecessaryInputIsProxy() {
    return nextRoundNecessaryInputSource == NextRoundNecessaryInputSource.NON_RATION_CONSUMED_PROXY;
  }

  /**
   * ★★ <b>可质押真实资产价值</b>（毫粮）= {@link #pledgeableGrainSurplusValue()}（可自用余粮） + {@link
   * #pledgeableAssetPolicyValue()}（显式钩子）。
   *
   * <p>★ 可自用余粮读不到（粮库存缺失）⇒ 返回 {@link OptionalLong#empty()} —— “读不到”不是“0”， 读口据此标具名缺失。钩子本身是 0 不改变这条：未知
   * + 0 仍是未知。
   */
  public OptionalLong pledgeableRealAssetValue() {
    if (pledgeableGrainSurplusValue.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        Math.addExact(pledgeableGrainSurplusValue.getAsLong(), pledgeableAssetPolicyValue));
  }

  /**
   * ★★ <b>新信用额度 headroom</b>（毫粮）：
   *
   * <pre>
   * headroom = max(0, ⌊κ × F ÷ 1000⌋ + 可质押真实资产价值 − existingDebt)     κ = {@link #CREDIT_F_MULTIPLE_PER_MILLE}
   * </pre>
   *
   * <p>★ 可自用余粮读不到 ⇒ 返回 {@link OptionalLong#empty()}（<b>不是 0</b>：读口必须标具名缺失，
   * 不许把“无法计算”当成“没有信用”）。{@link #existingDebt()} 只含同 unit（粮）本金；非粮债不能折算的部分只在 {@link
   * #unpricedDebtAmount()}／{@link #unpricedDebtCount()} 里列出，<b>不</b>混进这个减法。
   *
   * <p>★ 本方法只做算术，不写任何状态；借入合法性仍由真实债权人库存转出与借粮路径守卫决定。
   */
  public OptionalLong headroom() {
    OptionalLong pledgeable = pledgeableRealAssetValue();
    if (pledgeable.isEmpty()) {
      return OptionalLong.empty();
    }
    long creditFromF = Math.multiplyExact(F(), (long) CREDIT_F_MULTIPLE_PER_MILLE) / 1000L;
    long room =
        Math.subtractExact(Math.addExact(creditFromF, pledgeable.getAsLong()), existingDebt);
    return OptionalLong.of(Math.max(0L, room));
  }

  /** ★ 单位口径说明（读口原样发出；与 {@link #UNIT_NOTE} 同文）。 */
  public String unitNote() {
    return UNIT_NOTE;
  }
}
