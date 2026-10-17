package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>本周期内"某个家户对某个实例的实际生产贡献"的累计事实</b>——D-032 A 批「周期贡献」修正的落形 （复核裁决 §5；设计书 §13.4 A 批第 3 条；★ A′ 批按
 * §13.8 裁定一把折算改成<b>不截断的精确累计</b>）。
 *
 * <p>★★ <b>它替换掉的旧行为</b>（复核裁决 §5 原文）：旧引擎「非关账日提前返回；经验在关账分支才增长； 实例只留周期劳动总数」⇒
 * 整周期逐家户贡献与"当时生效效率"无法重建；收获计算只拿最后一天劳动除以 整个周期天数、只让最后一天出工者获得整周期影响。
 *
 * <p>★★ <b>修正后的四条口径（逐条对应裁决 §5 的 1–4）</b>：
 *
 * <ol>
 *   <li><b>逐 tick 累计</b>：每一个生产日（一次 {@code execute}）就把该家户的<b>实际贡献</b>累进来， <b>不</b>等关账日；
 *   <li><b>一次实际劳动只计一次</b>：本记录是<b>唯一</b>的周期贡献台账；关账日只做"读累计 → 算规模 → 清零"，<b>不</b>把此前 tick
 *       的劳动再发一遍（关账日的经验增长只针对<b>本次</b>贡献）；
 *   <li><b>整周期可重建</b>：{@link #efficiencyWeightedLaborMilli()} 是"按当时生效效率加权过的劳动"的 累计，收获与 E_cycle
 *       用的是它（<b>不</b>是"最后一天劳动 ÷ 周期天数"）；中途换出工户后双方贡献 各自保留；关账日无人出工时，此前累计仍完整有效；
 *   <li><b>按当时生效值累计</b>：熟练度随生产变化时，本记录累的是各 tick <b>当刻</b>的加权劳动， 不拿关账日的熟练度反推整周期。
 * </ol>
 *
 * <p>★★ <b>A′ 批：加权量<b>不除 1000</b>（单位 = 千分劳动·日 × 千分倍率）</b>——这是"足够精度累计" 的最强形态：
 *
 * <pre>
 * efficiencyWeightedLaborMilli = Σ_ticks ( 实际劳动(千分劳动·日) × 该 tick 生效效率倍率(千分制) )
 * E_cycle                      = ⌊ Σ_h 加权_h ÷ Σ_h 劳动_h ⌋          // 按实际劳动贡献加权
 * </pre>
 *
 * <p>★ 旧形态 {@code effectiveLaborMilli = Σ ⌊(劳动 × E + 余数) ÷ 1000⌋} 需要一条<b>按实例</b>存放的
 * 折算余数；同一实例多户出工时它们共享那个累加器（口径偏松，§13.8 末段）。改成本记录直接留未截断的 分子后：<b>没有可丢的零头</b>，也没有跨家户共享的累加器，逐家户归因与遍历顺序无关。
 * ★ 折算只在关账时做一次（{@code ProductionEfficiency}），试算与实际读<b>同一个</b>入口。
 *
 * <p>★★ <b>新增状态仍由 production 唯一维护</b>（裁决 §5 第 4 条末句）：本类型是 {@code
 * ProductionProcess.cycleContributions} 的值类型，住在 production 契约包，economy 只读。
 *
 * <p>★ <b>两个量纲</b>：{@link #laborMilli()} = 该家户本周期实际投入的劳动（千分劳动·日）； {@link
 * #efficiencyWeightedLaborMilli()} = 这些劳动按<b>各 tick 当刻</b>效率倍率加权后的精确累计 （千分劳动·日 × 千分倍率，未除
 * 1000）。二者不同：后者才是"当时生效效率"的载体。
 *
 * <p>★ <b>代表效率倍率</b>（{@link #efficiencyPerMille()}）：本记录<b>派生</b>给出 {@code ⌊加权 ÷ 劳动⌋}（劳动为 0 ⇒ 1000
 * 中性），供读数/回执核对；不是独立状态。
 *
 * @param household 家户稳定身份；不得为 null
 * @param role 本项贡献的生产角色；不得为 null。同一户不同角色必须分行，不得合并
 * @param laborMilli 本周期该家户在该角色上实际投入的劳动（毫小时/日）；不得为负
 * @param efficiencyWeightedLaborMilli 本周期该家户在该角色上按各 tick 当刻效率倍率加权后的劳动累计 （毫小时/日 × 千分倍率；<b>不</b>除
 *     1000、<b>不</b>截断）；不得为负
 */
public record CycleContribution(
    HouseholdId household,
    ProductionRoleId role,
    long laborMilli,
    long efficiencyWeightedLaborMilli) {

  public CycleContribution {
    Objects.requireNonNull(household, "CycleContribution.household 不得为 null");
    Objects.requireNonNull(role, "CycleContribution.role 不得为 null");
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("CycleContribution.laborMilli 不得为负: " + laborMilli);
    }
    if (efficiencyWeightedLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "CycleContribution.efficiencyWeightedLaborMilli 不得为负: " + efficiencyWeightedLaborMilli);
    }
  }

  /** 本贡献的复合键（家户 × 角色）。 */
  public ProductionParticipantKey participantKey() {
    return new ProductionParticipantKey(household, role);
  }

  /** ★ 本周期该家户在该角色上的代表效率倍率（千分制；劳动为 0 ⇒ 1000 中性）——派生值，不参与状态。 */
  public long efficiencyPerMille() {
    if (laborMilli <= 0L) {
      return 1_000L;
    }
    return Math.floorDiv(efficiencyWeightedLaborMilli, laborMilli);
  }

  /**
   * ★ <b>加一笔本次贡献</b>（同一家户在同一实例上有多次出工 ⇒ 累计，不覆盖）。
   *
   * <p>★ 两个量各自相加：{@code labor += Δlabor}、{@code 加权 += Δ加权}； 调用方（引擎）保证 Δ加权 是"本次劳动 ×
   * 当刻效率"的<b>精确乘积</b>（不除 1000）。
   */
  public CycleContribution plus(long deltaLaborMilli, long deltaEfficiencyWeightedLaborMilli) {
    if (deltaLaborMilli < 0L || deltaEfficiencyWeightedLaborMilli < 0L) {
      throw new IllegalArgumentException("CycleContribution.plus 的增量不得为负");
    }
    return new CycleContribution(
        household,
        role,
        Math.addExact(laborMilli, deltaLaborMilli),
        Math.addExact(efficiencyWeightedLaborMilli, deltaEfficiencyWeightedLaborMilli));
  }
}
