package io.mosire.simos.economy.api.production;

import io.mosire.simos.economy.api.id.ProductionUnitId;

/**
 * ★★ <b>程序内机制逐 tick 注入的生产效率修正参数</b>（约束设计书 2026-10-23-production-efficiency-framework §5.1；<b>GM
 * 不可达</b>）。
 *
 * <p>它是 economy-api 的 typed 契约：外部模块/组合根给出"哪个生产单元、这一 tick 打几千分修正、谁给的、为什么"， 由 {@code simos-economy} 的
 * {@code EconomyDayStepper.updateProductionModifiers} <b>替换</b>当日注入集， 并在周期末按天平均消费（§6.2）。同一 unit
 * 重复注入 ⇒ 具名拒绝；未注入的 unit = 1000‰（中性）。
 *
 * <pre>
 * ProductionEfficiencyModifier(unit, modifierPerMille, source, reason)
 * </pre>
 *
 * <p>★ <b>构造期守卫</b>（坏数据 fail-closed）：{@code unit} 非 null；{@code modifierPerMille ∈ [0, 2000]}；
 * {@code source} / {@code reason} 非空白（审计用，建议稳定前缀，如 {@code test-bridge}）。
 *
 * <p>★ {@code source} 用<b>自由文本</b>（本批不建封闭枚举；将来若文化/天气等机制稳定再收紧，是兼容的收紧方向）。
 *
 * @param unit 生产单元稳定身份
 * @param modifierPerMille 修正参数（千分；1000 = 中性，有效域 0..2000）
 * @param source 修正来源（自由文本、稳定前缀；审计用）
 * @param reason 本次修正的原因（自由文本；审计用）
 */
public record ProductionEfficiencyModifier(
    ProductionUnitId unit, long modifierPerMille, String source, String reason) {

  public ProductionEfficiencyModifier {
    if (unit == null) {
      throw new IllegalArgumentException("ProductionEfficiencyModifier.unit 不得为 null");
    }
    if (modifierPerMille < 0L || modifierPerMille > 2_000L) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyModifier.modifierPerMille 必须 ∈ [0, 2000]: " + modifierPerMille);
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("ProductionEfficiencyModifier.source 不得为空白");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("ProductionEfficiencyModifier.reason 不得为空白");
    }
  }
}
