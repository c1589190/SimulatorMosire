package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.ProductionEfficiencyState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>Z2（2026-10-23-production-efficiency-framework §6）：单 tick 单产业生产效率公式与余数结转</b>。
 *
 * <p>本类是 §6 公式的<b>唯一拼写点</b>，全部为静态纯计算（无实例状态、禁用 {@code double}，只走 long 整数除法 / 取余）：
 *
 * <ul>
 *   <li>§6.1 {@link #accrueTick}：把当 tick 修正参数 {@code m_t}（缺省 1000‰，有效域 [0, 2000]）累进 {@code
 *       cycleModifierSumPerMille}；
 *   <li>§6.2 {@link #evaluateHarvest}：周期末 ① 平均修正 ② 劳动链（cycleDays 与 laborPerUnit 两层 + 余数）③ 满足率（显式算出）
 *       ④ {@code scaleBase = min(...)}（与旧 {@code scaleOf} 三路 min 同义）⑤ 修正乘算 + 余数 ⑦ 周期末清零 / 保留四余数。 （⑥
 *       产出数量的默认/覆盖在 {@code EconomySettlement.harvest}：覆盖表命中则覆盖、否则配方默认。）
 * </ul>
 *
 * <p>★★ <b>"缺行 = 中性"的物化规则</b>（§3.2）：{@code state == null} 表示缺行 —— 语义上等于 <b>四个余数全 0 + 本周期全
 * 1000‰</b>。为了不给 全部 unit 建零行：
 *
 * <ul>
 *   <li>某 tick 为中性且缺行 ⇒ {@link #accrueTick} 返回 {@code null}（保持缺行）；
 *   <li>某 tick 非中性且缺行 ⇒ 物化该行，并把本周期<b>此前已流逝的中性 tick</b> 一并补进累计（{@code elapsedCycleDays × 1000 +
 *       m_t}）， 否则周期平均修正会漏掉注入前的那些天；
 *   <li>周期末<b>保留四个余数</b>（跨周期结转；§1.2 的"长期不再系统性丢精度"）：缺行时若算出非零余数就把行物化留下； 只有
 *       <b>四余数全零且本轮无非中性注入证据</b>时才不建行 / 移除该行（缺行与全零行逐值等价，确定性）。
 * </ul>
 *
 * <p>★ <b>lpu == 0 退化</b>（§6.2 ②）：劳动链不适用 ⇒ {@code scaleBase = min(capPlanned, inputScale_j
 * …)}、修正乘算不生效（{@code scale = scaleBase}）、{@code laborScale} 仅用于日志；不抛。调用方另记具名 DEBUG（见 {@code
 * EconomySettlement}）。
 *
 * <p>★ <b>五余数有效域校验</b>：{@link #requireValidState} 在任何使用 / 写回前 fail-closed 校验 {@code
 * modifierRemainderMilli ∈ [0, cycleDays)}、{@code laborDayRemainderMilli ∈ [0, cycleDays)}、{@code
 * laborScaleRemainderMilli ∈ [0, laborPerUnit)}（{@code laborPerUnit == 0} ⇒ 必须为 0）、{@code
 * scaleRemainderMilli ∈ [0, 1000)}；越域 ⇒ {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR + {@link
 * IllegalStateException}。
 */
public final class ProductionEfficiencyBook {

  /** §6.1 缺省修正（中性）= 1000‰。 */
  public static final long NEUTRAL_MODIFIER_PER_MILLE = 1_000L;

  /** §5.1/§6.1 修正参数有效域上界 = 2000‰。 */
  public static final long MAX_MODIFIER_PER_MILLE = 2_000L;

  /** 修正乘算余数 {@code scaleRemainderMilli} 的有效域上界（不含）= 1000。 */
  public static final long SCALE_REMAINDER_MODULUS = 1_000L;

  private static final Logger LOG = EconomyLog.settlement();

  private ProductionEfficiencyBook() {}

  /**
   * ★★ <b>§6.1 逐 tick 累计</b>：{@code S.cycleModifierSumPerMille += m_t}（不取整、不丢精度）。
   *
   * @param state 上一 tick 后的效率状态；{@code null} = 缺行（全 0 余数 + 本周期全 1000‰ 中性）
   * @param modifierPerMille 本 tick 注入的修正参数 {@code m_t} ∈ [0, 2000]；未注入 = 1000
   * @param elapsedCycleDays 本 tick 之前本周期已流逝的天数（= {@code unit.progressDays()}；非负）
   * @return 下一 tick 的状态；{@code null} = 仍缺行（中性 + 缺行 ⇒ 不建零行）
   */
  public static ProductionEfficiencyState accrueTick(
      ProductionEfficiencyState state, long modifierPerMille, long elapsedCycleDays) {
    if (modifierPerMille < 0L || modifierPerMille > MAX_MODIFIER_PER_MILLE) {
      contractFault("accrueTick", "modifierPerMille 越域（不在 [0, 2000]）: " + modifierPerMille);
    }
    if (elapsedCycleDays < 0L) {
      contractFault("accrueTick", "elapsedCycleDays 不得为负: " + elapsedCycleDays);
    }
    if (state == null) {
      if (modifierPerMille == NEUTRAL_MODIFIER_PER_MILLE) {
        return null; // 缺行 + 中性 ⇒ 保持缺行（不给全部 unit 建零行）
      }
      // 非中性注入让缺行物化：补上本周期此前已流逝的中性 tick，周期平均修正才等于 Σm_t / cycleDays。
      return new ProductionEfficiencyState(
          elapsedCycleDays * NEUTRAL_MODIFIER_PER_MILLE + modifierPerMille, 0L, 0L, 0L, 0L);
    }
    return new ProductionEfficiencyState(
        state.cycleModifierSumPerMille() + modifierPerMille,
        state.modifierRemainderMilli(),
        state.laborDayRemainderMilli(),
        state.laborScaleRemainderMilli(),
        state.scaleRemainderMilli());
  }

  /**
   * ★★ <b>单个 unit 的周期末求值结果</b>（§6.2 ①~⑤+⑦）：调用方用 {@link #scale()} 去算毛产、用 {@link #nextState()}
   * 写回效率状态。
   *
   * @param nextState 写回的状态（{@code cycleModifierSumPerMille == 0}、四个余数跨周期保留；缺行但算出非零余数时也会物化）； {@code
   *     null} = 缺行（不建行 / 移除全零行）
   */
  public record HarvestEvaluation(
      long avgModifierPerMille,
      long laborScale,
      long satisfactionPerMille,
      long scaleBase,
      long scale,
      long modifierRemainderMilli,
      long laborDayRemainderMilli,
      long laborScaleRemainderMilli,
      long scaleRemainderMilli,
      boolean modifierEffective,
      boolean laborPerUnitZero,
      ProductionEfficiencyState nextState) {}

  /**
   * ★★ <b>§6.2 周期末求值</b>（harvest 的规模那一段）：① 平均修正 ② 劳动链 ③ 满足率 ④ 最紧规模 ⑤ 修正乘算 + 余数 ⑦ 清账。
   *
   * <p>★ ⑥（产品产出数量的默认 / GM 覆盖）不在这里：本方法只交规模，数量在 {@code EconomySettlement.harvest} 逐商品取 {@code
   * outputQuantityOverrides[industry][commodity]} 或配方默认（§6.2 ⑥）。
   *
   * <p>★ 旧口径等价性（§6.3.1）：{@code state == null}（余数全 0、修正全中性）时，{@code avgModifier == 1000}、{@code
   * laborScale} 与旧 {@code ⌊(cycledLabor / cycleDays) / laborPerUnit⌋} 逐值相同、{@code scaleBase} 与旧三路
   * min 逐值相同、{@code scale == scaleBase}。
   *
   * @param state 本周期逐 tick 累计后的状态；{@code null} = 缺行（全 0 余数 + 全 1000‰）
   * @param cycleDays industry.cycleDays()；必须 ≥ 1
   * @param cycleLaborMilli 本周期实际劳动合计 L（千分劳动·日）；非负
   * @param capacityScale 产能那一路的原始规模（{@code capacityScaleOf}，尚未乘 plannedPerMille）；非负
   * @param plannedPerMille 状态机计划规模系数（StressPolicy），∈ [0, 1000]
   * @param inputPerUnit 配方每单位投入（逐商品；每单位需求 ≤ 0 的路不施加约束）
   * @param cycleInputUsedMilli 本周期实际扣到的投入（逐商品；缺失键 = 0）
   * @param laborPerUnit 配方每单位规模劳动；0 = 劳动链不适用（旧口径，修正不生效）
   */
  public static HarvestEvaluation evaluateHarvest(
      ProductionEfficiencyState state,
      long cycleDays,
      long cycleLaborMilli,
      long capacityScale,
      long plannedPerMille,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> cycleInputUsedMilli,
      long laborPerUnit) {
    Objects.requireNonNull(inputPerUnit, "inputPerUnit");
    Objects.requireNonNull(cycleInputUsedMilli, "cycleInputUsedMilli");
    if (cycleDays < 1L) {
      contractFault("harvest", "cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (cycleLaborMilli < 0L) {
      contractFault("harvest", "cycleLaborMilli 不得为负: " + cycleLaborMilli);
    }
    if (capacityScale < 0L) {
      contractFault("harvest", "capacityScale 不得为负: " + capacityScale);
    }
    if (plannedPerMille < 0L || plannedPerMille > 1_000L) {
      contractFault("harvest", "plannedPerMille 越域（不在 [0, 1000]）: " + plannedPerMille);
    }
    requireValidState(state, cycleDays, laborPerUnit, "harvest-input");

    // ① 平均修正：n1 = Σm_t + modifierRemainder；avgModifier = n1 / D；carry = n1 % D。
    //    缺行 = 本周期全 1000‰ ⇒ Σm_t = D × 1000（余数 0）。
    long modifierSum =
        state == null ? cycleDays * NEUTRAL_MODIFIER_PER_MILLE : state.cycleModifierSumPerMille();
    long modifierCarry = state == null ? 0L : state.modifierRemainderMilli();
    long modifierNumerator = modifierSum + modifierCarry;
    long avgModifierPerMille = modifierNumerator / cycleDays;
    long nextModifierRemainder = modifierNumerator % cycleDays;

    // ② 劳动链（沿用旧除法链 + 余数结转）：n2 = L + laborDayRemainder；avgLabor = n2 / D。
    long laborDayCarry = state == null ? 0L : state.laborDayRemainderMilli();
    long laborNumerator = cycleLaborMilli + laborDayCarry;
    long avgLaborMilli = laborNumerator / cycleDays;
    long nextLaborDayRemainder = laborNumerator % cycleDays;

    boolean laborPerUnitZero = laborPerUnit <= 0L;
    long laborScale;
    long nextLaborScaleRemainder;
    if (laborPerUnitZero) {
      // §6.2 ②：lpu == 0 ⇒ 本公式不适用，走旧口径（无劳动那一路），修正不生效；laborScale 仅用于日志。
      laborScale = avgLaborMilli;
      nextLaborScaleRemainder = state == null ? 0L : state.laborScaleRemainderMilli();
    } else {
      long laborScaleCarry = state == null ? 0L : state.laborScaleRemainderMilli();
      long laborScaleNumerator = avgLaborMilli + laborScaleCarry;
      laborScale = laborScaleNumerator / laborPerUnit;
      nextLaborScaleRemainder = laborScaleNumerator % laborPerUnit;
    }

    long capPlanned = capacityScale * plannedPerMille / 1_000L;

    // ③ 满足率（0..1000，显式算出用于日志与不变量）；laborScale == 0 ⇒ 0。
    long satisfactionPerMille =
        satisfactionPerMille(laborScale, capPlanned, inputPerUnit, cycleInputUsedMilli);

    // ④ 最紧规模（与旧 scaleOf 的三路 min 同义；lpu == 0 ⇒ 无劳动那一路）。
    long scaleBase = laborPerUnitZero ? capPlanned : Math.min(laborScale, capPlanned);
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      Long perUnit = entry.getValue();
      if (perUnit == null || perUnit <= 0L) {
        continue; // 每单位需求 ≤ 0 ⇒ 这一路不构成约束（与旧代码 seedPerMu == 0 同款）
      }
      long drawn = cycleInputUsedMilli.getOrDefault(entry.getKey(), 0L);
      scaleBase = Math.min(scaleBase, drawn / perUnit);
    }

    // ⑤ 修正乘算 + 余数；lpu == 0 ⇒ 修正不生效（保持旧口径，scaleRemainder 原样结转）。
    boolean modifierEffective = !laborPerUnitZero;
    long scale;
    long nextScaleRemainder;
    if (laborPerUnitZero) {
      scale = scaleBase;
      nextScaleRemainder = state == null ? 0L : state.scaleRemainderMilli();
    } else {
      long scaleCarry = state == null ? 0L : state.scaleRemainderMilli();
      long scaleNumerator = scaleBase * avgModifierPerMille + scaleCarry;
      scale = scaleNumerator / SCALE_REMAINDER_MODULUS;
      nextScaleRemainder = scaleNumerator % SCALE_REMAINDER_MODULUS;
    }

    // ⑦ 周期末清账：清零 cycleModifierSumPerMille、保留四个余数（跨周期结转；这就是 §1.2 的"长期不再系统性丢精度"）。
    //   缺行语义 = 全 0 余数 + 全 1000‰：本轮若算出非零余数就把行物化，把余数留下来；
    //   四余数全零且本轮无非中性注入证据（缺行，或既有行累计 == D×1000）⇒ 不建行 / 移除该行（缺行与全零行逐值等价）。
    boolean allRemaindersZero =
        nextModifierRemainder == 0L
            && nextLaborDayRemainder == 0L
            && nextLaborScaleRemainder == 0L
            && nextScaleRemainder == 0L;
    boolean noNonNeutralInjectionEvidence =
        state == null || state.cycleModifierSumPerMille() == cycleDays * NEUTRAL_MODIFIER_PER_MILLE;
    ProductionEfficiencyState nextState;
    if (allRemaindersZero && noNonNeutralInjectionEvidence) {
      nextState = null;
    } else {
      nextState =
          new ProductionEfficiencyState(
              0L,
              nextModifierRemainder,
              nextLaborDayRemainder,
              nextLaborScaleRemainder,
              nextScaleRemainder);
      requireValidState(nextState, cycleDays, laborPerUnit, "harvest-next");
    }
    return new HarvestEvaluation(
        avgModifierPerMille,
        laborScale,
        satisfactionPerMille,
        scaleBase,
        scale,
        nextModifierRemainder,
        nextLaborDayRemainder,
        nextLaborScaleRemainder,
        nextScaleRemainder,
        modifierEffective,
        laborPerUnitZero,
        nextState);
  }

  /**
   * ★★ <b>§6.2 ③ 满足率</b>（0..1000）：{@code min(1000, capPlanned×1000/laborScale,
   * inputScale_j×1000/laborScale …)}； {@code laborScale == 0 ⇒ 0}。
   */
  private static long satisfactionPerMille(
      long laborScale,
      long capPlanned,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> cycleInputUsedMilli) {
    if (laborScale == 0L) {
      return 0L;
    }
    long satisfaction = Math.min(1_000L, capPlanned * 1_000L / laborScale);
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      Long perUnit = entry.getValue();
      if (perUnit == null || perUnit <= 0L) {
        continue;
      }
      long drawn = cycleInputUsedMilli.getOrDefault(entry.getKey(), 0L);
      long inputScale = drawn / perUnit;
      satisfaction = Math.min(satisfaction, inputScale * 1_000L / laborScale);
    }
    return satisfaction;
  }

  /**
   * ★★ <b>五个字段的有效域 fail-closed 校验</b>（§3.2）：周期和 ≥ 0；两个 {@code < cycleDays} 的余数；{@code laborPerUnit
   * > 0} 时 {@code < laborPerUnit}（{@code == 0} 时唯一合法值 = 0 —— 该域为空区间、该路不适用）；{@code scaleRemainder <
   * 1000}。
   *
   * <p>越域 ⇒ {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR + {@link
   * IllegalStateException}（契约故障，不降级、不放行）。
   */
  static void requireValidState(
      ProductionEfficiencyState state, long cycleDays, long laborPerUnit, String where) {
    if (state == null) {
      return; // 缺行 = 全 0 余数 + 全 1000‰（中性），天然在域内
    }
    if (state.cycleModifierSumPerMille() < 0L) {
      contractFault(where, "cycleModifierSumPerMille < 0: " + state);
    }
    if (state.modifierRemainderMilli() < 0L || state.modifierRemainderMilli() >= cycleDays) {
      contractFault(
          where,
          "modifierRemainderMilli 越域（应在 [0, "
              + cycleDays
              + ")）: "
              + state.modifierRemainderMilli());
    }
    if (state.laborDayRemainderMilli() < 0L || state.laborDayRemainderMilli() >= cycleDays) {
      contractFault(
          where,
          "laborDayRemainderMilli 越域（应在 [0, "
              + cycleDays
              + ")）: "
              + state.laborDayRemainderMilli());
    }
    if (laborPerUnit <= 0L) {
      if (state.laborScaleRemainderMilli() != 0L) {
        contractFault(
            where,
            "laborPerUnit == 0 时 laborScaleRemainderMilli 必须为 0: "
                + state.laborScaleRemainderMilli());
      }
    } else if (state.laborScaleRemainderMilli() < 0L
        || state.laborScaleRemainderMilli() >= laborPerUnit) {
      contractFault(
          where,
          "laborScaleRemainderMilli 越域（应在 [0, "
              + laborPerUnit
              + ")）: "
              + state.laborScaleRemainderMilli());
    }
    if (state.scaleRemainderMilli() < 0L
        || state.scaleRemainderMilli() >= SCALE_REMAINDER_MODULUS) {
      contractFault(where, "scaleRemainderMilli 越域（应在 [0, 1000)）: " + state.scaleRemainderMilli());
    }
  }

  /**
   * ★★ <b>契约故障的唯一发射点</b>：先记 {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR（§8：契约故障不降级）， 再抛 {@link
   * IllegalStateException} 供调用方 fail-closed。
   */
  private static void contractFault(String where, String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "PRODUCTION_EFFICIENCY_CONTRACT",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "where",
                where,
                "reason",
                reason));
    throw new IllegalStateException("生产效率契约违约：" + where + "：" + reason);
  }
}
