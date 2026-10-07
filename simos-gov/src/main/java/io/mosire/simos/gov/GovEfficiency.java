package io.mosire.simos.gov;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * 行政效率（阶段 11a；Z2 换冻结两维公式，设计书 §3）：由编制计划与两维承诺劳动算出<b>两维满足率 + 两维最终效率 + 总效率</b>。
 *
 * <p>★★ <b>Z2 冻结公式（逐项）</b>：每维 {@code d ∈ {治安, 公文}}，{@code P_d} = 编制计划需求量（毫小时/tick）、{@code S_d} =
 * 实际承诺劳动（毫小时/tick）、{@code 定额} = {@link
 * io.mosire.simos.social.provisioning.SocialProvisioning#standardLaborMilliHoursPerTick()}（C8
 * 唯一权威）、 {@code k} = {@link GovAdministrationPlan#supernumerarySqrtCoefficient()}：
 *
 * <pre>{@code
 * 需求劳动_d = P_d × 需求静态_d‰ / 1000 × 需求动态_d‰ / 1000
 * 超额_d     = max(0, S_d − 需求劳动_d)
 * 有效劳动_d = S_d                                                        , S_d ≤ 需求劳动_d
 *            = 需求劳动_d + ⌊√(超额_d ÷ 定额 × k)⌋ × 定额                , S_d > 需求劳动_d
 * 满足率_d   = 有效劳动_d × 1000 ÷ 需求劳动_d      （需求劳动_d = 0 ⇒ 1000‰）
 * 效率_d‰    = 满足率_d × 供给静态_d‰ / 1000 × 供给动态_d‰ / 1000
 * 总效率‰    = 效率_治安‰ × 效率_公文‰ ÷ 1000
 * }</pre>
 *
 * <p>★★ <b>三条硬口径</b>：
 *
 * <ul>
 *   <li><b>全不封顶</b>（用户 2026-10-23「都不封顶」）：所有乘法用 {@link Math#multiplyExact}、有效劳动相加用 {@link
 *       Math#addExact}；溢出 ⇒ 具名 {@code GOV_EFFICIENCY_CONTRACT_VIOLATION} ERROR + {@link
 *       IllegalStateException}，<b>绝不静默截断</b>；
 *   <li><b>任一维供给 = 0 ⇒ 该维最终效率 0，总效率 0</b>（即便该维需求为 0，也不能白拿“无需求 = 全额”）；调用方用 {@link
 *       #anySupplyZero(long, long)} 判据记具名 INFO（本类不自己发 INFO，保持纯函数）；
 *   <li><b>需求 = 0 维记 1000‰</b>（无需求 = 全额），但供给 = 0 时按上一条压回 0。
 * </ul>
 *
 * <p>★★ <b>新签名（Z3 消费者用）</b>：{@link #of(GovernmentFormation, Map, GovAdministrationPlan, long, long,
 * long, long, long, long, long)}。入参 = 编制（身份/兼容用，公式不读 {@code staff}）、逐格需求（<b>供建议值/日志</b>，
 * 公式按冻结口径只用计划 {@code P_d}）、编制计划、两维供给承诺劳动、两维动态修正（供给/需求各二，共 4 个）、标准劳动系数**值**（毫小时/tick；Z3 消费者从当前世界
 * {@code SocialData.provisioning()} 的 C8 唯一权威读出后传入——纯函数只拿值，不持有 provisioning 表）。
 *
 * <p>★★ <b>Z3b 起没有旧 2 参桥</b>：旧 {@code of(formation, demand)} 过渡桥已删除；唯一供给权威是 app 的承诺→两维供给桥 （{@code
 * GovServiceFlow}/效率表），{@link GovDaily} 只消费 app 算好的 {@link Efficiency} 结果、不再自己重算。
 *
 * <p>★ <b>返回值里的两维有效劳动/两维需求劳动</b>（Z3b 追加）：{@link Efficiency#securityEffectiveLaborMilli()} 等四个字段让
 * {@link GovServiceFlow} 与 {@link GovDaily} 的信号 evidence 都从<b>同一份</b>计算结果读数，不在第二处重算。
 *
 * <p>★ <b>手算例（新公式，定额 16,000 毫小时/tick、k=1、四个修正 1000‰）</b>：计划 治安 200 人当量 = 3,200,000、公文 100 人当量 =
 * 1,600,000；供给 治安 220 人当量 = 3,520,000、公文 110 人当量 = 1,760,000。治安：超额 320,000 ÷ 16,000 = 20 ⇒ ⌊√20⌋ =
 * 4 ⇒ 有效 = 3,200,000 + 4×16,000 = 3,264,000 ⇒ 满足率 1,020‰；公文：超额 160,000 ÷ 16,000 = 10 ⇒ ⌊√10⌋ = 3 ⇒
 * 有效 = 1,648,000 ⇒ 满足率 1,030‰；总效率 = 1,020 × 1,030 ÷ 1000 = 1,050（向下取整）。
 *
 * <p>★ <b>纯函数</b>：不写状态、不调命令；同一输入逐字段相同（唯一副作用是契约故障时的 ERROR 日志）。
 */
public final class GovEfficiency {

  private static final Logger LOG = GovLog.efficiency();

  private GovEfficiency() {}

  /**
   * ★★ <b>Z2 冻结的两维效率公式（纯函数；Z3b 起返回值多带四个计算量）</b>。
   *
   * @param governmentFormation 编制（<b>身份/兼容用</b>：新公式不读 {@code staff}，它是 Z3 对齐调用方与旧口径的载体）；不得为 null
   * @param suggestedDemand 逐格需求（{@link GovDemand#of} 的输出；<b>供建议值/DEBUG 日志</b>，公式按冻结口径只用计划 {@code
   *     P_d}）；不得为 null、键值不得为 null
   * @param plan 行政编制计划（{@code P_d}/档位/四个静态修正/{@code k}）；不得为 null
   * @param securitySupplyLaborMilli 治安维实际承诺劳动（毫小时/tick；≥ 0）
   * @param paperworkSupplyLaborMilli 公文维实际承诺劳动（毫小时/tick；≥ 0）
   * @param securitySupplyDynamicModifierPerMille 治安供给动态修正（‰；≥ 0）
   * @param paperworkSupplyDynamicModifierPerMille 公文供给动态修正（‰；≥ 0）
   * @param securityDemandDynamicModifierPerMille 治安需求动态修正（‰；≥ 0）
   * @param paperworkDemandDynamicModifierPerMille 公文需求动态修正（‰；≥ 0）
   * @param standardLaborMilliHoursPerTick 标准劳动系数（岗位定额；毫小时/tick）。**值**必须来自当前世界 C8 唯一权威 {@link
   *     io.mosire.simos.social.provisioning.SocialProvisioning#standardLaborMilliHoursPerTick()}（Z3
   *     消费者从 {@code SocialData.provisioning()} 读出后传入；本函数不持有 provisioning 表，控制方 2026-10-23 裁定）
   * @return 两维满足率 + 两维最终效率 + 总效率 + 两维有效劳动 + 两维需求劳动（全部 ≥ 0、不封顶）
   * @throws IllegalArgumentException 入参为 null、逐格需求表形状坏（编程错误）
   * @throws IllegalStateException 供给/动态修正为负、标准系数非正、算术溢出等<b>契约故障</b>（已发具名 ERROR，fail-closed）
   */
  public static Efficiency of(
      GovernmentFormation governmentFormation,
      Map<HexCoord, GovDemand.HexDemand> suggestedDemand,
      GovAdministrationPlan plan,
      long securitySupplyLaborMilli,
      long paperworkSupplyLaborMilli,
      long securitySupplyDynamicModifierPerMille,
      long paperworkSupplyDynamicModifierPerMille,
      long securityDemandDynamicModifierPerMille,
      long paperworkDemandDynamicModifierPerMille,
      long standardLaborMilliHoursPerTick) {
    requireGovernmentFormation(governmentFormation);
    requireDemand(suggestedDemand);
    Objects.requireNonNull(plan, "plan");
    requireContractNonNegative(securitySupplyLaborMilli, "securitySupplyLaborMilli");
    requireContractNonNegative(paperworkSupplyLaborMilli, "paperworkSupplyLaborMilli");
    requireContractNonNegative(
        securitySupplyDynamicModifierPerMille, "securitySupplyDynamicModifierPerMille");
    requireContractNonNegative(
        paperworkSupplyDynamicModifierPerMille, "paperworkSupplyDynamicModifierPerMille");
    requireContractNonNegative(
        securityDemandDynamicModifierPerMille, "securityDemandDynamicModifierPerMille");
    requireContractNonNegative(
        paperworkDemandDynamicModifierPerMille, "paperworkDemandDynamicModifierPerMille");
    if (standardLaborMilliHoursPerTick <= 0L) {
      throw contractFailure("standard-labor-coefficient-non-positive", null);
    }

    Efficiency efficiency;
    try {
      efficiency =
          compute(
              plan,
              securitySupplyLaborMilli,
              paperworkSupplyLaborMilli,
              securitySupplyDynamicModifierPerMille,
              paperworkSupplyDynamicModifierPerMille,
              securityDemandDynamicModifierPerMille,
              paperworkDemandDynamicModifierPerMille,
              standardLaborMilliHoursPerTick);
    } catch (ArithmeticException e) {
      throw contractFailure("arithmetic-overflow", e);
    }
    logComputed(suggestedDemand, plan, efficiency);
    return efficiency;
  }

  /** 逐项计算（可能抛 {@link ArithmeticException}，由公开入口统一折成具名契约 ERROR）。 */
  private static Efficiency compute(
      GovAdministrationPlan plan,
      long securitySupplyLaborMilli,
      long paperworkSupplyLaborMilli,
      long securitySupplyDynamicModifierPerMille,
      long paperworkSupplyDynamicModifierPerMille,
      long securityDemandDynamicModifierPerMille,
      long paperworkDemandDynamicModifierPerMille,
      long standardLaborMilliHoursPerTick) {
    long securityDemandLaborMilli =
        demandLabor(
            plan.securityPlannedLaborMilli(),
            plan.securityDemandStaticModifierPerMille(),
            securityDemandDynamicModifierPerMille);
    long paperworkDemandLaborMilli =
        demandLabor(
            plan.paperworkPlannedLaborMilli(),
            plan.paperworkDemandStaticModifierPerMille(),
            paperworkDemandDynamicModifierPerMille);

    long securityEffectiveLaborMilli =
        effectiveLabor(
            securitySupplyLaborMilli,
            securityDemandLaborMilli,
            plan.supernumerarySqrtCoefficient(),
            standardLaborMilliHoursPerTick);
    long paperworkEffectiveLaborMilli =
        effectiveLabor(
            paperworkSupplyLaborMilli,
            paperworkDemandLaborMilli,
            plan.supernumerarySqrtCoefficient(),
            standardLaborMilliHoursPerTick);

    long securitySatisfactionPerMille =
        satisfaction(securityEffectiveLaborMilli, securityDemandLaborMilli);
    long paperworkSatisfactionPerMille =
        satisfaction(paperworkEffectiveLaborMilli, paperworkDemandLaborMilli);

    // ★ 任一维供给 = 0 ⇒ 该维最终效率 0（需求为 0 的“1000‰ 全额”也不能绕过这条）。
    long securityEfficiencyPerMille =
        securitySupplyLaborMilli == 0L
            ? 0L
            : applyModifiers(
                securitySatisfactionPerMille,
                plan.securitySupplyStaticModifierPerMille(),
                securitySupplyDynamicModifierPerMille);
    long paperworkEfficiencyPerMille =
        paperworkSupplyLaborMilli == 0L
            ? 0L
            : applyModifiers(
                paperworkSatisfactionPerMille,
                plan.paperworkSupplyStaticModifierPerMille(),
                paperworkSupplyDynamicModifierPerMille);

    long efficiencyPerMille =
        Math.floorDiv(
            Math.multiplyExact(securityEfficiencyPerMille, paperworkEfficiencyPerMille),
            GovRules.PER_MILLE);
    return new Efficiency(
        securitySatisfactionPerMille,
        paperworkSatisfactionPerMille,
        0L, // legacy：Z2 新状态恒 0
        efficiencyPerMille,
        securityEfficiencyPerMille,
        paperworkEfficiencyPerMille,
        securityEffectiveLaborMilli,
        paperworkEffectiveLaborMilli,
        securityDemandLaborMilli,
        paperworkDemandLaborMilli);
  }

  /** 需求劳动 = {@code P × 需求静态‰/1000 × 需求动态‰/1000}（两次整数向下取整，照冻结公式的书写序）。 */
  private static long demandLabor(
      long plannedLaborMilli, long staticModifier, long dynamicModifier) {
    return perMille(perMille(plannedLaborMilli, staticModifier), dynamicModifier);
  }

  /** {@code value × factor‰ / 1000}（乘法 exact、除法向下取整）。 */
  private static long perMille(long value, long factorPerMille) {
    return Math.floorDiv(Math.multiplyExact(value, factorPerMille), GovRules.PER_MILLE);
  }

  /**
   * 有效劳动：供给 ≤ 需求 ⇒ 供给；否则 {@code 需求 + ⌊√(超额 ÷ 定额 × k)⌋ × 定额}（开方在“岗位当量”上做）。
   *
   * <p>★ 整数口径：{@code ⌊√(超额×k÷定额)⌋} 先算 {@code ⌊超额×k ÷ 定额⌋}，再取整数平方根——对非负整数 {@code n}， {@code n ≤
   * √(实数)} 等价于 {@code n² ≤ ⌊实数⌋}，故两步 floor 与“先实数除再开方”逐值相同。
   */
  private static long effectiveLabor(
      long supplyLaborMilli, long demandLaborMilli, long coefficient, long quotaMilli) {
    if (supplyLaborMilli <= demandLaborMilli) {
      return supplyLaborMilli;
    }
    long excessLaborMilli = Math.subtractExact(supplyLaborMilli, demandLaborMilli);
    long postEquivalents =
        Math.floorDiv(Math.multiplyExact(excessLaborMilli, coefficient), quotaMilli);
    long wholeExtraPosts = integerSqrt(postEquivalents);
    return Math.addExact(demandLaborMilli, Math.multiplyExact(wholeExtraPosts, quotaMilli));
  }

  /** 满足率：需求 = 0 ⇒ 1000‰；否则 {@code 有效 × 1000 ÷ 需求}（向下取整，不封顶）。 */
  private static long satisfaction(long effectiveLaborMilli, long demandLaborMilli) {
    if (demandLaborMilli == 0L) {
      return GovRules.PER_MILLE;
    }
    return Math.floorDiv(
        Math.multiplyExact(effectiveLaborMilli, GovRules.PER_MILLE), demandLaborMilli);
  }

  /** 供给修正：{@code 满足率 × 供给静态‰/1000 × 供给动态‰/1000}（两次整数向下取整，照冻结公式的书写序）。 */
  private static long applyModifiers(
      long satisfactionPerMille, long staticModifier, long dynamicModifier) {
    return perMille(perMille(satisfactionPerMille, staticModifier), dynamicModifier);
  }

  /**
   * 整数平方根 {@code ⌊√value⌋}（{@code value ≥ 0}）。
   *
   * <p>★ 先用 {@link Math#sqrt(double)} 取近似根，再用<b>除法比较</b>（<b>不</b>用 {@code root*root}，避免根接近 {@code
   * Long.MAX_VALUE} 时乘法溢出）校正到精确值。
   */
  static long integerSqrt(long value) {
    if (value < 0L) {
      throw new IllegalArgumentException("integerSqrt 的入参必须 ≥ 0: " + value);
    }
    if (value < 2L) {
      return value;
    }
    long root = (long) Math.sqrt((double) value);
    while (root > value / root) {
      root--;
    }
    while (root + 1L <= value / (root + 1L)) {
      root++;
    }
    return root;
  }

  /**
   * ★ <b>零供给判据</b>（本区提供，调用方负责记具名 INFO）：任一维实际承诺劳动 = 0 ⇒ 该维最终效率被压成 0、总效率为 0。
   *
   * <p>为什么不让本函数自己发 INFO：它是纯函数，日志只是契约故障时的例外副作用；常态判据交给持有 {@code day}/{@code unit} 上下文的 调用方（Z3 app）。
   */
  public static boolean anySupplyZero(
      long securitySupplyLaborMilli, long paperworkSupplyLaborMilli) {
    return securitySupplyLaborMilli == 0L || paperworkSupplyLaborMilli == 0L;
  }

  /** DEBUG 汇总（默认关；不改变任何输出）。 */
  private static void logComputed(
      Map<HexCoord, GovDemand.HexDemand> suggestedDemand,
      GovAdministrationPlan plan,
      Efficiency efficiency) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "GOV_EFFICIENCY_COMPUTED",
                GovLogSource.GOV_EFFICIENCY,
                "suggestedHexes",
                suggestedDemand.size(),
                "securityPlannedLaborMilli",
                plan.securityPlannedLaborMilli(),
                "paperworkPlannedLaborMilli",
                plan.paperworkPlannedLaborMilli(),
                "securitySatisfactionPerMille",
                efficiency.securityCoveragePerMille(),
                "paperworkSatisfactionPerMille",
                efficiency.paperworkCoveragePerMille(),
                "securityEfficiencyPerMille",
                efficiency.securityEfficiencyPerMille(),
                "paperworkEfficiencyPerMille",
                efficiency.paperworkEfficiencyPerMille(),
                "efficiencyPerMille",
                efficiency.efficiencyPerMille()));
  }

  /** 契约故障：具名 ERROR（不降级）+ {@link IllegalStateException}（fail-closed）。 */
  private static IllegalStateException contractFailure(String reason, Throwable cause) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_EFFICIENCY_CONTRACT_VIOLATION",
                GovLogSource.GOV_EFFICIENCY,
                "reason",
                reason,
                "failure",
                cause == null ? "-" : cause.getClass().getSimpleName()));
    return new IllegalStateException("gov 行政效率公式契约故障: " + reason, cause);
  }

  private static void requireContractNonNegative(long value, String field) {
    if (value < 0L) {
      throw contractFailure("negative-" + field, null);
    }
  }

  /** 治安供给：{@code YAMEN} 在编人数（缺角色 = 0）。package-private：旧桥与每日结算的信号 evidence 共用同一份求和。 */
  static long securitySupply(GovernmentFormation governmentFormation) {
    requireGovernmentFormation(governmentFormation);
    return governmentFormation.staff().getOrDefault(StaffRole.YAMEN, 0L);
  }

  /** 文书供给：{@code SCRIBE + POST} 在编人数（缺角色 = 0）。★ 两个角色同口径，但驿传不另算第三种需求。 */
  static long paperworkSupply(GovernmentFormation governmentFormation) {
    requireGovernmentFormation(governmentFormation);
    return governmentFormation.staff().getOrDefault(StaffRole.SCRIBE, 0L)
        + governmentFormation.staff().getOrDefault(StaffRole.POST, 0L);
  }

  /** 治安需求汇总（逐格 {@code security} 求和）。旧桥与信号 evidence 共用。 */
  static long securityDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    requireDemand(demand);
    long total = 0L;
    for (GovDemand.HexDemand hexDemand : demand.values()) {
      total += hexDemand.security();
    }
    return total;
  }

  /** 文书需求汇总（逐格 {@code paperwork} 求和）。旧桥与信号 evidence 共用。 */
  static long paperworkDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    requireDemand(demand);
    long total = 0L;
    for (GovDemand.HexDemand hexDemand : demand.values()) {
      total += hexDemand.paperwork();
    }
    return total;
  }

  private static void requireGovernmentFormation(GovernmentFormation governmentFormation) {
    if (governmentFormation == null) {
      throw new IllegalArgumentException("governmentFormation 不得为 null");
    }
  }

  private static void requireDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    if (demand == null) {
      throw new IllegalArgumentException("demand 不得为 null（无需求用 Map.of()）");
    }
    for (Map.Entry<HexCoord, GovDemand.HexDemand> entry : demand.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("demand 的键与值都不得为 null");
      }
    }
  }

  /**
   * 行政效率读数（Z2 六读数 + Z3b 四计算量）：<b>两维满足率 + 两维最终效率 + 总效率</b>，外加<b>两维有效劳动 + 两维需求劳动</b>； 全部 ≥ 0、
   * <b>全部不封顶</b>（C3）。
   *
   * <p>★ <b>字段语义</b>：{@link #securityCoveragePerMille()} / {@link #paperworkCoveragePerMille()} =
   * 两维满足率（沿用旧 {@code coverage} 字段名）；{@link #securityEfficiencyPerMille()} / {@link
   * #paperworkEfficiencyPerMille()} = 两维最终效率； {@link #efficiencyPerMille()} = 总效率 = 两维最终效率相乘 ÷
   * 1000；{@link #bonusPerMille()} = <b>legacy</b>（Z2 新公式恒 0，仅为旧档/旧构造点保留）。
   *
   * <p>★★ <b>Z3b 追加的四个字段</b>：{@link #securityEffectiveLaborMilli()} / {@link
   * #paperworkEffectiveLaborMilli()} = 两维<b>有效劳动</b>（含超编开方；{@link GovServiceFlow} 的服务产出取它）； {@link
   * #securityDemandLaborMilli()} / {@link #paperworkDemandLaborMilli()} = 两维<b>需求劳动</b>（计划 {@code
   * P_d} × 需求静态/动态修正）。{@link GovServiceFlow} 与 {@link GovDaily} 的缺口信号 evidence 都从这同一份结果读数， 不在第二处重算。
   *
   * <p>★ <b>构造期校验</b>：十个字段都只要求 ≥ 0（Z2 拆掉 1000/1100/100 上界）；不静默钳制。旧 6 参/4 参构造器保留（Z3b 追加的四个字段取
   * 0），免得既有编译点全改；新调用点应使用 10 参 canonical 构造器。
   *
   * @param securityCoveragePerMille 治安满足率（‰；≥ 0，不封顶）
   * @param paperworkCoveragePerMille 公文满足率（‰；≥ 0，不封顶）
   * @param bonusPerMille 旧 11a 超编加成（legacy；Z2 新公式恒 0）
   * @param efficiencyPerMille 总行政效率（‰；≥ 0，不封顶）
   * @param securityEfficiencyPerMille 治安最终效率（‰；≥ 0，不封顶）
   * @param paperworkEfficiencyPerMille 公文最终效率（‰；≥ 0，不封顶）
   * @param securityEffectiveLaborMilli 治安有效劳动（毫小时/tick；≥ 0）
   * @param paperworkEffectiveLaborMilli 公文有效劳动（毫小时/tick；≥ 0）
   * @param securityDemandLaborMilli 治安需求劳动（毫小时/tick；≥ 0）
   * @param paperworkDemandLaborMilli 公文需求劳动（毫小时/tick；≥ 0）
   */
  public record Efficiency(
      long securityCoveragePerMille,
      long paperworkCoveragePerMille,
      long bonusPerMille,
      long efficiencyPerMille,
      long securityEfficiencyPerMille,
      long paperworkEfficiencyPerMille,
      long securityEffectiveLaborMilli,
      long paperworkEffectiveLaborMilli,
      long securityDemandLaborMilli,
      long paperworkDemandLaborMilli) {

    public Efficiency {
      requireNonNegative(securityCoveragePerMille, "securityCoveragePerMille");
      requireNonNegative(paperworkCoveragePerMille, "paperworkCoveragePerMille");
      requireNonNegative(bonusPerMille, "bonusPerMille");
      requireNonNegative(efficiencyPerMille, "efficiencyPerMille");
      requireNonNegative(securityEfficiencyPerMille, "securityEfficiencyPerMille");
      requireNonNegative(paperworkEfficiencyPerMille, "paperworkEfficiencyPerMille");
      requireNonNegative(securityEffectiveLaborMilli, "securityEffectiveLaborMilli");
      requireNonNegative(paperworkEffectiveLaborMilli, "paperworkEffectiveLaborMilli");
      requireNonNegative(securityDemandLaborMilli, "securityDemandLaborMilli");
      requireNonNegative(paperworkDemandLaborMilli, "paperworkDemandLaborMilli");
    }

    /** 旧 6 参构造器（Z2 兼容）：Z3b 追加的有效/需求劳动四项取 0（旧口径没有这四个读数）。 */
    public Efficiency(
        long securityCoveragePerMille,
        long paperworkCoveragePerMille,
        long bonusPerMille,
        long efficiencyPerMille,
        long securityEfficiencyPerMille,
        long paperworkEfficiencyPerMille) {
      this(
          securityCoveragePerMille,
          paperworkCoveragePerMille,
          bonusPerMille,
          efficiencyPerMille,
          securityEfficiencyPerMille,
          paperworkEfficiencyPerMille,
          0L,
          0L,
          0L,
          0L);
    }

    /** 旧 4 参构造器（Z2 兼容）：两个新维效率与 Z3b 的四个计算量都取 0（旧口径没有这些读数）。 */
    public Efficiency(
        long securityCoveragePerMille,
        long paperworkCoveragePerMille,
        long bonusPerMille,
        long efficiencyPerMille) {
      this(
          securityCoveragePerMille,
          paperworkCoveragePerMille,
          bonusPerMille,
          efficiencyPerMille,
          0L,
          0L);
    }

    private static void requireNonNegative(long value, String field) {
      if (value < 0L) {
        throw new IllegalArgumentException(field + " 必须 ≥ 0（Z2 起不封顶）: " + value);
      }
    }
  }
}
