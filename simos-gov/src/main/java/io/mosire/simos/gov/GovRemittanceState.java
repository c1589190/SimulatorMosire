package io.mosire.simos.gov;

/**
 * ★★ <b>单个 GOV 的 remittance 周期账 + 最近一次关账执行读数</b>（Z7c 新增源状态，设计书 §4）。
 *
 * <p>它把两件同属"上缴"的事实放在一条 record 里（身份 = {@link GovState#remittanceStates()} 的键，<b>不内嵌 unitId</b>；与
 * {@link GovAdministrationPlan} / {@link GovBudgetPolicy} 同制）：
 *
 * <ul>
 *   <li><b>本周期累计</b>：{@link #cycleGrainCollectedMilli()} / {@link #cycleSilverCollectedMilli()} ——
 *       由 app 组合根在每个结算日把 {@code JurisdictionDailyTax} 的<b>逐 unit
 *       实收</b>加进来；只在周期末（关账日）结算应缴，<b>结算后清零</b>（不跨周期重复计）；
 *   <li><b>最近一次关账读数</b>：{@link #lastDueGrainMilli()} / {@link #lastPaidGrainMilli()} / {@link
 *       #lastShortfallGrainMilli()}（银同三件）与 {@link #lastCycleCloseDay()} —— 只落"事实"（应缴/实缴/缺口），
 *       <b>不</b>在 gov 侧重算任何比例或余额：比例在 {@link GovBudgetPolicy#remittancePerMilleToSuperior()}，
 *       可用额在账户会话里。
 * </ul>
 *
 * <p>★★ <b>不变量（构造期一律当场抛，fail-closed）</b>：所有量 ≥ 0；每维 {@code paid ≤ due} 且 {@code shortfall == due −
 * paid}（这是账，不是三笔可以各自漂移的读数）；{@code lastCycleCloseDay ≥ 0}（0 = 还没有关账过）。
 *
 * <p>★ <b>缺键 = 空</b>：旧档/旧世界没有本组件时，{@link
 * GovState#remittanceStateOrDefault(io.mosire.simos.unit.UnitId)} 返回 {@link #empty()}（全 0）。
 *
 * @param cycleGrainCollectedMilli 本周期实收粮（毫；≥ 0；remit 后清零）
 * @param cycleSilverCollectedMilli 本周期实收银（毫；≥ 0；remit 后清零）
 * @param lastDueGrainMilli 最近一次关账应缴粮（毫；≥ 0）
 * @param lastPaidGrainMilli 最近一次关账实缴粮（毫；≥ 0；≤ due）
 * @param lastShortfallGrainMilli 最近一次关账粮缺口（= due − paid；≥ 0）
 * @param lastDueSilverMilli 最近一次关账应缴银（毫；≥ 0）
 * @param lastPaidSilverMilli 最近一次关账实缴银（毫；≥ 0；≤ due）
 * @param lastShortfallSilverMilli 最近一次关账银缺口（= due − paid；≥ 0）
 * @param lastCycleCloseDay 最近一次关账的世界日（≥ 0；0 = 尚未关账）
 */
public record GovRemittanceState(
    long cycleGrainCollectedMilli,
    long cycleSilverCollectedMilli,
    long lastDueGrainMilli,
    long lastPaidGrainMilli,
    long lastShortfallGrainMilli,
    long lastDueSilverMilli,
    long lastPaidSilverMilli,
    long lastShortfallSilverMilli,
    long lastCycleCloseDay) {

  public GovRemittanceState {
    requireNonNegative(cycleGrainCollectedMilli, "cycleGrainCollectedMilli");
    requireNonNegative(cycleSilverCollectedMilli, "cycleSilverCollectedMilli");
    requireLedger("Grain", lastDueGrainMilli, lastPaidGrainMilli, lastShortfallGrainMilli);
    requireLedger("Silver", lastDueSilverMilli, lastPaidSilverMilli, lastShortfallSilverMilli);
    requireNonNegative(lastCycleCloseDay, "lastCycleCloseDay");
  }

  /** 旧档/未配置 GOV 的缺省读数：全 0（0 = "还没有关账过"，不是"关账了但全是 0"）。 */
  public static GovRemittanceState empty() {
    return new GovRemittanceState(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
  }

  /**
   * 把一个结算日的逐 unit 实收（粮/银，毫）加进本周期累计；溢出饱和到 {@link Long#MAX_VALUE}（与税侧 {@code scalePerMille}
   * 的饱和方向一致，绝不回绕）。
   */
  public GovRemittanceState accumulate(long grainCollectedMilli, long silverCollectedMilli) {
    requireNonNegative(grainCollectedMilli, "grainCollectedMilli");
    requireNonNegative(silverCollectedMilli, "silverCollectedMilli");
    if (grainCollectedMilli == 0L && silverCollectedMilli == 0L) {
      return this;
    }
    return new GovRemittanceState(
        saturatedAdd(cycleGrainCollectedMilli, grainCollectedMilli),
        saturatedAdd(cycleSilverCollectedMilli, silverCollectedMilli),
        lastDueGrainMilli,
        lastPaidGrainMilli,
        lastShortfallGrainMilli,
        lastDueSilverMilli,
        lastPaidSilverMilli,
        lastShortfallSilverMilli,
        lastCycleCloseDay);
  }

  /**
   * 关账日结算：<b>清零本周期累计</b>，写入本次应缴/实缴/缺口（缺口由 due − paid 派生）与关账日。即使 rate=0 / 无上级 /
   * 自己上级（no-op）也必须清零——否则同一批实收会在下个周期被重复计。
   */
  public GovRemittanceState afterCycleClose(
      long day,
      long dueGrainMilli,
      long paidGrainMilli,
      long dueSilverMilli,
      long paidSilverMilli) {
    requireNonNegative(day, "day");
    requireLedger("Grain", dueGrainMilli, paidGrainMilli, dueGrainMilli - paidGrainMilli);
    requireLedger("Silver", dueSilverMilli, paidSilverMilli, dueSilverMilli - paidSilverMilli);
    return new GovRemittanceState(
        0L,
        0L,
        dueGrainMilli,
        paidGrainMilli,
        dueGrainMilli - paidGrainMilli,
        dueSilverMilli,
        paidSilverMilli,
        dueSilverMilli - paidSilverMilli,
        day);
  }

  /** 本周期是否已有实收（读口用来区分"周期还没开始"与"累计为 0"不作伪判，不参与任何执行判定）。 */
  public boolean cycleReceiptsPresent() {
    return cycleGrainCollectedMilli > 0L || cycleSilverCollectedMilli > 0L;
  }

  /**
   * ★ <b>唯一的 {@code floor(value × perMille / 1000)} 饱和算式</b>（remittance 应缴与读口投影共用同一份；与 {@code
   * JurisdictionDailyTax.scalePerMille} 同语义、同溢出方向——那时另一个类里的私有副本先于本类存在）。{@code value ≥ 0}、{@code
   * perMille ≥ 0}；结果超过 {@link Long#MAX_VALUE} 时饱和，绝不回绕。
   */
  public static long applyPerMille(long value, long perMille) {
    requireNonNegative(value, "value");
    requireNonNegative(perMille, "perMille");
    long whole = value / 1000L;
    long remainder = value % 1000L;
    long wholePart = saturatedMultiply(whole, perMille);
    long remainderPart = saturatedMultiply(remainder, perMille) / 1000L;
    return saturatedAdd(wholePart, remainderPart);
  }

  private static void requireLedger(String dimension, long due, long paid, long shortfall) {
    requireNonNegative(due, "lastDue" + dimension + "Milli");
    requireNonNegative(paid, "lastPaid" + dimension + "Milli");
    requireNonNegative(shortfall, "lastShortfall" + dimension + "Milli");
    if (paid > due) {
      throw new IllegalArgumentException(
          "GovRemittanceState." + dimension + " paid 不得超过 due: paid=" + paid + " due=" + due);
    }
    if (shortfall != due - paid) {
      throw new IllegalArgumentException(
          "GovRemittanceState."
              + dimension
              + " shortfall 必须等于 due − paid: shortfall="
              + shortfall
              + " due="
              + due
              + " paid="
              + paid);
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException("GovRemittanceState." + field + " 必须 ≥ 0: " + value);
    }
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long saturatedMultiply(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }
}
