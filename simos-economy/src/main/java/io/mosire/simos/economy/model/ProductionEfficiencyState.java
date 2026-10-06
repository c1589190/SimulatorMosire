package io.mosire.simos.economy.model;

/**
 * ★★ <b>单个生产单元的生产效率累计与余数状态</b>（约束设计书 2026-10-23-production-efficiency-framework §3.2；与 {@link
 * OperatorCondition} 同目录、同"每 unit 一条状态"形制）。
 *
 * <p>它是 {@code EconomyData.productionEfficiency}（键 = {@code ProductionUnitId}）的值，五个 long 字段冻结如下：
 *
 * <ul>
 *   <li>{@code cycleModifierSumPerMille}：本周期逐 tick 修正参数之和（缺省 tick = 1000）；≥ 0；<b>周期末消费后清零</b>；
 *   <li>{@code modifierRemainderMilli}：Σ修正 ÷ cycleDays 的余数；有效域 [0, cycleDays)；<b>跨周期保留</b>；
 *   <li>{@code laborDayRemainderMilli}：周期劳动 ÷ cycleDays 的余数；有效域 [0, cycleDays)；<b>跨周期保留</b>；
 *   <li>{@code laborScaleRemainderMilli}：日均劳动 ÷ laborPerUnit 的余数；有效域 [0,
 *       laborPerUnit)；<b>跨周期保留</b>；
 *   <li>{@code scaleRemainderMilli}：最紧规模 × 平均修正 ÷ 1000 的余数；有效域 [0, 1000)；<b>跨周期保留</b>。
 * </ul>
 *
 * <p>★★ <b>构造期守卫只判"五个字段都 ≥ 0"</b>：其余有效域（{@code < cycleDays} / {@code < laborPerUnit} / {@code <
 * 1000}）的分母是 per-industry 值，不能写进 record 构造期（§3.2 明文）；由公式代码在使用时 fail-closed 校验。
 *
 * <p>★ <b>旧档缺行</b> = 全 0 余数、当周期全 1000‰ ⇒ 中性；{@code ClearRegion} 按 unit 所在格键删除（与 {@code units}
 * 同生共死），{@code Seed} 保持空表。
 */
public record ProductionEfficiencyState(
    long cycleModifierSumPerMille,
    long modifierRemainderMilli,
    long laborDayRemainderMilli,
    long laborScaleRemainderMilli,
    long scaleRemainderMilli) {

  public ProductionEfficiencyState {
    if (cycleModifierSumPerMille < 0L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.cycleModifierSumPerMille 不得为负: " + cycleModifierSumPerMille);
    }
    if (modifierRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.modifierRemainderMilli 不得为负: " + modifierRemainderMilli);
    }
    if (laborDayRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.laborDayRemainderMilli 不得为负: " + laborDayRemainderMilli);
    }
    if (laborScaleRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.laborScaleRemainderMilli 不得为负: " + laborScaleRemainderMilli);
    }
    if (scaleRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.scaleRemainderMilli 不得为负: " + scaleRemainderMilli);
    }
  }
}
