package io.mosire.simos.gov;

import java.util.Objects;

/**
 * ★★ <b>行政编制系数（纯值入参）</b>——{@link GovEfficiency} 算三维效率所需的<b>全部</b>编制侧数值，一格不多、一格不少。
 *
 * <p>★★★ <b>它为什么存在（2026-10-12 追加裁定 12 §21.9 末段，选 (a)）</b>：编制计划类型（原 {@code
 * io.mosire.simos.gov.GovAdministrationPlan}）已随三类政府政策搬进 {@code simos-economy}（{@code
 * economy.govpolicy}），而 {@link GovEfficiency} 是 <b>gov 模块</b>的公式。若它继续把那个类型当入参，{@code simos-gov} 就必须
 * {@code import simos-economy} —— 那是<b>反向依赖</b>（I-B5 明令禁止）。 ⇒ 本模块只吃<b>纯值</b>：组合根（app）从 {@code
 * EconomyData.governments} 取出 {@code Government.administrationPlan()}，经 {@link #of} 折成这九个 {@code
 * long} 后传入。
 *
 * <ul>
 *   <li>公式<b>一个数都不改</b>（I-B7）：本类型只做"换个形状把同样的数端过来"，不参与任何算术；
 *   <li>gov 从此<b>不认识 economy</b>（I-B5）——它只认识 {@code GovernmentFormation}（自己的切片）+ 本类型（自己的切片）；
 *   <li>★ 与 {@code GovEfficiencyModifier}（动态修正的纯值载体）同制的先例：跨模块的数值面走"值对象"而不是"别人的状态类型"。
 * </ul>
 *
 * @param securityPlannedLaborMilli 治安维计划劳动量（毫小时/tick；≥ 0，不封顶）
 * @param paperworkPlannedLaborMilli 公文维计划劳动量（毫小时/tick；≥ 0，不封顶）
 * @param portPlannedLaborMilli 口岸维计划劳动量（毫小时/tick；≥ 0，不封顶；0 = 没设口岸编制）
 * @param securitySupplyStaticModifierPerMille 治安供给静态修正（‰；≥ 0，不封顶）
 * @param paperworkSupplyStaticModifierPerMille 公文供给静态修正（‰；≥ 0，不封顶）
 * @param portSupplyStaticModifierPerMille 口岸供给静态修正（‰；≥ 0，不封顶）
 * @param securityDemandStaticModifierPerMille 治安需求静态修正（‰；≥ 0，不封顶）
 * @param paperworkDemandStaticModifierPerMille 公文需求静态修正（‰；≥ 0，不封顶）
 * @param portDemandStaticModifierPerMille 口岸需求静态修正（‰；≥ 0，不封顶）
 * @param supernumerarySqrtCoefficient 超编开方系数 k（≥ 0，不封顶；中性默认 1）
 */
public record AdministrationCoefficients(
    long securityPlannedLaborMilli,
    long paperworkPlannedLaborMilli,
    long portPlannedLaborMilli,
    long securitySupplyStaticModifierPerMille,
    long paperworkSupplyStaticModifierPerMille,
    long portSupplyStaticModifierPerMille,
    long securityDemandStaticModifierPerMille,
    long paperworkDemandStaticModifierPerMille,
    long portDemandStaticModifierPerMille,
    long supernumerarySqrtCoefficient) {

  /** 中性静态修正：1000‰ = 1.0（= 编制计划的中性缺省；唯一拼写点在本模块，见 {@link #neutral()}）。 */
  public static final long NEUTRAL_MODIFIER_PER_MILLE = 1000L;

  /** 默认开方系数 k（用户 2026-10-23：默认 1，GM 可改）。 */
  public static final long DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT = 1L;

  public AdministrationCoefficients {
    requireNonNegative(securityPlannedLaborMilli, "securityPlannedLaborMilli");
    requireNonNegative(paperworkPlannedLaborMilli, "paperworkPlannedLaborMilli");
    requireNonNegative(portPlannedLaborMilli, "portPlannedLaborMilli");
    requireNonNegative(
        securitySupplyStaticModifierPerMille, "securitySupplyStaticModifierPerMille");
    requireNonNegative(
        paperworkSupplyStaticModifierPerMille, "paperworkSupplyStaticModifierPerMille");
    requireNonNegative(portSupplyStaticModifierPerMille, "portSupplyStaticModifierPerMille");
    requireNonNegative(
        securityDemandStaticModifierPerMille, "securityDemandStaticModifierPerMille");
    requireNonNegative(
        paperworkDemandStaticModifierPerMille, "paperworkDemandStaticModifierPerMille");
    requireNonNegative(portDemandStaticModifierPerMille, "portDemandStaticModifierPerMille");
    requireNonNegative(supernumerarySqrtCoefficient, "supernumerarySqrtCoefficient");
  }

  /** ★ <b>中性缺省</b>（= 编制计划的中性值）：三维计划量 0、六个修正 1000‰、{@code k=1} ⇒ 三维效率恒 0 ⇒ 一切照旧放行 （I-B1 的编制面）。 */
  public static AdministrationCoefficients neutral() {
    return new AdministrationCoefficients(
        0L,
        0L,
        0L,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
  }

  /**
   * ★★ <b>十个数的规范构造（与 canonical 构造器同签名的显式入口）</b>：组合根从 {@code EconomyData.governments} 取出 {@code
   * Government.administrationPlan()} 后，按<b>本记录组件的声明顺序</b>逐字段端过来。
   *
   * <p>★ <b>为什么本模块的签名里不出现那个计划类型</b>：{@code simos-gov} <b>不许</b> import {@code simos-economy}（I-B5）
   * ⇒ 只能收纯值。参数顺序与本记录组件顺序<b>逐字一致</b>；十个 {@code long} 在编译期分不出谁是谁，故调用点必须逐字段具名 （见 {@code
   * PopulationEconomyTimeParticipant} 的调用点注释）——这是本方案唯一的人工映射点，也是它的代价。
   */
  public static AdministrationCoefficients of(
      long securityPlannedLaborMilli,
      long paperworkPlannedLaborMilli,
      long portPlannedLaborMilli,
      long securitySupplyStaticModifierPerMille,
      long paperworkSupplyStaticModifierPerMille,
      long portSupplyStaticModifierPerMille,
      long securityDemandStaticModifierPerMille,
      long paperworkDemandStaticModifierPerMille,
      long portDemandStaticModifierPerMille,
      long supernumerarySqrtCoefficient) {
    return new AdministrationCoefficients(
        securityPlannedLaborMilli,
        paperworkPlannedLaborMilli,
        portPlannedLaborMilli,
        securitySupplyStaticModifierPerMille,
        paperworkSupplyStaticModifierPerMille,
        portSupplyStaticModifierPerMille,
        securityDemandStaticModifierPerMille,
        paperworkDemandStaticModifierPerMille,
        portDemandStaticModifierPerMille,
        supernumerarySqrtCoefficient);
  }

  private static void requireNonNegative(long value, String field) {
    Objects.requireNonNull(field, "field");
    if (value < 0L) {
      throw new IllegalArgumentException(
          "AdministrationCoefficients." + field + " 必须 ≥ 0: " + value);
    }
  }
}
