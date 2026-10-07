package io.mosire.simos.gov;

import io.mosire.simos.unit.UnitId;

/**
 * ★★ <b>程序内机制逐 tick 注入的行政效率动态修正参数</b>（Z3b，设计书 §3 / §5 工具表 / §10 C2；<b>GM 不可达</b>）。
 *
 * <p>它是 gov 模块的 typed 契约：外部项目内组件/组合根给出"哪个 GOV、这一 tick 供给两维与需求两维各打几千分修正、谁给的、为什么"， 由 app
 * 的推进参与者（{@code PopulationEconomyTimeParticipant#updateGovEfficiencyModifiers}）<b>替换</b>当日注入集，
 * 当日结算消费后清空。同一 GOV 重复注入 ⇒ 具名拒绝；未注入的 GOV = 四个修正 1000‰（中性）。
 *
 * <pre>
 * GovEfficiencyModifier(gov, securitySupplyPerMille, paperworkSupplyPerMille,
 *                       securityDemandPerMille, paperworkDemandPerMille, source, reason)
 * </pre>
 *
 * <p>★★ <b>与 {@code ProductionEfficiencyModifier} 同形</b>（经济侧先例）：typed record、GM 不可达、无命令/工具/白名单路径、 逐
 * tick 替换、消费后清空。差别只在维度：这里供给/需求各两维（治安/公文），共四个 ‰ 值。
 *
 * <p>★ <b>全不封顶</b>（用户 2026-10-23「都不封顶」）：四个修正只判 {@code ≥ 0}，<b>没有上界</b>；乘法溢出由 {@link GovEfficiency}
 * 折成具名契约 ERROR（不在这里截断）。
 *
 * <p>★ <b>构造期守卫</b>（坏数据 fail-closed）：{@code gov} 非 null；四个 ‰ 值 {@code ≥ 0}；{@code source} / {@code
 * reason} 非空白（审计用，建议稳定前缀，如 {@code weather-bridge}）。
 *
 * @param gov 目标 GOV 单位稳定身份（不得为 null）
 * @param securitySupplyPerMille 治安供给动态修正（‰；≥ 0，不封顶；缺省即 1000 = 中性）
 * @param paperworkSupplyPerMille 公文供给动态修正（‰；≥ 0，不封顶；缺省即 1000 = 中性）
 * @param securityDemandPerMille 治安需求动态修正（‰；≥ 0，不封顶；缺省即 1000 = 中性）
 * @param paperworkDemandPerMille 公文需求动态修正（‰；≥ 0，不封顶；缺省即 1000 = 中性）
 * @param source 修正来源（自由文本、稳定前缀；审计用；非空白）
 * @param reason 本次修正的原因（自由文本；审计用；非空白）
 */
public record GovEfficiencyModifier(
    UnitId gov,
    long securitySupplyPerMille,
    long paperworkSupplyPerMille,
    long securityDemandPerMille,
    long paperworkDemandPerMille,
    String source,
    String reason) {

  /** 中性修正（1.0；缺省值 = 未注入）。 */
  public static final long NEUTRAL_PER_MILLE = 1000L;

  public GovEfficiencyModifier {
    if (gov == null) {
      throw new IllegalArgumentException("GovEfficiencyModifier.gov 不得为 null");
    }
    requireNonNegative(securitySupplyPerMille, "securitySupplyPerMille");
    requireNonNegative(paperworkSupplyPerMille, "paperworkSupplyPerMille");
    requireNonNegative(securityDemandPerMille, "securityDemandPerMille");
    requireNonNegative(paperworkDemandPerMille, "paperworkDemandPerMille");
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("GovEfficiencyModifier.source 不得为空白");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("GovEfficiencyModifier.reason 不得为空白");
    }
  }

  /** 四个维度都取中性 1000‰ 的修正（只为调用方少写四个常量；{@code source}/{@code reason} 仍必填）。 */
  public static GovEfficiencyModifier neutral(UnitId gov, String source, String reason) {
    return new GovEfficiencyModifier(
        gov,
        NEUTRAL_PER_MILLE,
        NEUTRAL_PER_MILLE,
        NEUTRAL_PER_MILLE,
        NEUTRAL_PER_MILLE,
        source,
        reason);
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0（全不封顶）: " + value);
    }
  }
}
