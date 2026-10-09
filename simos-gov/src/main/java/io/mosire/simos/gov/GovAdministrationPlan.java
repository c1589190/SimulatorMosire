package io.mosire.simos.gov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个 GOV 的行政编制计划（Z2 新增源状态，设计书 §4.1；<b>R2 扩为三维</b>，2026-10-09 口岸设计书 §4.2 + 上位文档 §10-3）。
 * 这是用户可以估、可以改的<b>计划需求量</b>——{@link GovEfficiency} 的冻结公式 永远用这里的 {@code plannedLaborMilli}，<b>不用</b>
 * {@link GovDemand} 的建议值（建议值只在 Z3 用于默认/告警）。
 *
 * <p>★★ <b>字段（单位毫小时/tick）</b>：
 *
 * <ul>
 *   <li>{@link #securityPlannedLaborMilli()} / {@link #paperworkPlannedLaborMilli()} / {@link
 *       #portPlannedLaborMilli()}：治安、公文、<b>口岸</b>三维计划劳动量（毫小时/tick）；
 *   <li>{@link #postTiers()}：3 档岗位目录，每档<b>三维</b>权重的权重（‰）；默认目录见 {@link #DEFAULT_POST_TIERS()}；
 *   <li>{@link #securitySupplyStaticModifierPerMille} 等<b>六个</b>静态修正：供给/需求各三维（‰）；
 *   <li>{@link #supernumerarySqrtCoefficient()}：超编开方系数 {@code k}（默认 1；GM 可改）。
 * </ul>
 *
 * <p>★★ <b>中性默认</b>（{@link #neutral()}）：三维计划量 0、默认 3 档、六个修正 1000‰、{@code k=1}。旧档没有该 GOV 的源状态时由
 * {@link GovState#administrationPlanOrDefault(io.mosire.simos.unit.UnitId)} 返回它。
 *
 * <p>★★ <b>不封顶 + 保序不可变</b>：六个修正、权重、{@code k} 都只判 ≥ 0，<b>没有上界</b>（用户 2026-10-23「都不封顶」）； {@code
 * postTiers} 用 {@code ArrayList} 拷贝 + 赋值处 {@code Collections.unmodifiableList} 冻结，<b>不用 {@code
 * List.copyOf}</b>（与全仓保序纪律一致）。
 *
 * <p>★★ <b>R2 旧档/旧调用点兼容（口岸三字段）</b>：
 *
 * <ul>
 *   <li><b>Java 侧</b>：保留旧 8 参构造器，口岸三字段取 <b>计划 0 + 两个修正 1000‰</b>（= {@link #neutral()} 的口岸口径：
 *       "没设口岸编制，但修正面是中性的"）；
 *   <li><b>JSON 侧</b>：旧档缺这三个键 ⇒ Jackson 对原始 {@code long} 给 <b>0</b>。{@code portPlannedLaborMilli=0}
 *       正确；两个 修正 0 使口岸效率恒 0 ⇒ 实际管制力 0 ⇒ 一切照旧放行。<b>两条缺省路径的数值可能不同（0 vs 1000‰），但对世界的效果相同</b> （口岸维供给本就是
 *       0，见 {@link GovEfficiency} 的"任一维供给 = 0 ⇒ 该维效率 0"）——这是刻意的 fail-closed 方向， 探针里两种形态都逐值贴过（账本
 *       §探针）。
 * </ul>
 *
 * <p>★ <b>为什么没有 unitId</b>：身份是 {@link GovState#administrationPlans()} 的<b>键</b>（{@code
 * UnitId}），内容不内嵌 第二份身份；这样 {@link GovEfficiency} 的过渡桥也能直接用建议值构造计划（旧签名没有 UnitId）。键值一致性由 {@link
 * GovState} 的“键非空”守卫保证。
 *
 * @param securityPlannedLaborMilli 治安计划劳动量（毫小时/tick；≥ 0，不封顶）
 * @param paperworkPlannedLaborMilli 公文计划劳动量（毫小时/tick；≥ 0，不封顶）
 * @param portPlannedLaborMilli 口岸计划劳动量（毫小时/tick；≥ 0，不封顶；0 = 没设口岸编制）
 * @param postTiers 3 档岗位目录（恰 3 项、tierId 非空白且唯一、三维权重 ≥ 0；保序不可变）
 * @param securitySupplyStaticModifierPerMille 治安供给静态修正（‰；≥ 0，不封顶）
 * @param paperworkSupplyStaticModifierPerMille 公文供给静态修正（‰；≥ 0，不封顶）
 * @param portSupplyStaticModifierPerMille 口岸供给静态修正（‰；≥ 0，不封顶）
 * @param securityDemandStaticModifierPerMille 治安需求静态修正（‰；≥ 0，不封顶）
 * @param paperworkDemandStaticModifierPerMille 公文需求静态修正（‰；≥ 0，不封顶）
 * @param portDemandStaticModifierPerMille 口岸需求静态修正（‰；≥ 0，不封顶）
 * @param supernumerarySqrtCoefficient 超编开方系数 k（≥ 0，不封顶；默认 1）
 */
public record GovAdministrationPlan(
    long securityPlannedLaborMilli,
    long paperworkPlannedLaborMilli,
    long portPlannedLaborMilli,
    List<GovPostTier> postTiers,
    long securitySupplyStaticModifierPerMille,
    long paperworkSupplyStaticModifierPerMille,
    long portSupplyStaticModifierPerMille,
    long securityDemandStaticModifierPerMille,
    long paperworkDemandStaticModifierPerMille,
    long portDemandStaticModifierPerMille,
    long supernumerarySqrtCoefficient) {

  /** 中性静态修正：1000‰ = 1.0（旧档缺源状态时的默认）。 */
  public static final long NEUTRAL_MODIFIER_PER_MILLE = 1000L;

  /** 默认开方系数 k（用户 2026-10-23：默认 1，GM 可改）。 */
  public static final long DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT = 1L;

  /** 默认档 1：治安 1000‰、公文 0‰、口岸 0‰（设计书 §14.2；口岸维 R2 起缺省 0）。 */
  public static final GovPostTier DEFAULT_TIER_1 = new GovPostTier("tier-1", 1000L, 0L, 0L);

  /** 默认档 2：治安 0‰、公文 1000‰、口岸 0‰（设计书 §14.2）。 */
  public static final GovPostTier DEFAULT_TIER_2 = new GovPostTier("tier-2", 0L, 1000L, 0L);

  /** 默认档 3：治安 500‰、公文 500‰、口岸 0‰（设计书 §14.2 的控制方默认）。 */
  public static final GovPostTier DEFAULT_TIER_3 = new GovPostTier("tier-3", 500L, 500L, 0L);

  /** 默认 3 档岗位目录（用户 2026-10-23：先设 3 档普通基层；档名/角色映射归 Z4）。 */
  public static final List<GovPostTier> DEFAULT_POST_TIERS =
      List.of(DEFAULT_TIER_1, DEFAULT_TIER_2, DEFAULT_TIER_3);

  /** 目录必须恰 3 档（V1 冻结；要改档数须先改设计书，不在实现层静默放宽）。 */
  private static final int REQUIRED_TIER_COUNT = 3;

  public GovAdministrationPlan {
    requireNonNegative(securityPlannedLaborMilli, "securityPlannedLaborMilli");
    requireNonNegative(paperworkPlannedLaborMilli, "paperworkPlannedLaborMilli");
    requireNonNegative(portPlannedLaborMilli, "portPlannedLaborMilli");
    if (postTiers == null) {
      throw new IllegalArgumentException("postTiers 不得为 null（没有目录用 DEFAULT_POST_TIERS）");
    }
    if (postTiers.size() != REQUIRED_TIER_COUNT) {
      throw new IllegalArgumentException(
          "postTiers 必须恰 " + REQUIRED_TIER_COUNT + " 档（V1 冻结的 3 档普通基层）: " + postTiers.size());
    }
    List<GovPostTier> tiersCopy = new ArrayList<>(postTiers.size());
    Set<String> tierIds = new LinkedHashSet<>();
    for (GovPostTier tier : postTiers) {
      if (tier == null) {
        throw new IllegalArgumentException("postTiers 不得含 null");
      }
      if (!tierIds.add(tier.tierId())) {
        throw new IllegalArgumentException("postTiers.tierId 不得重复: " + tier.tierId());
      }
      tiersCopy.add(tier);
    }
    postTiers = Collections.unmodifiableList(tiersCopy); // ★ 冻在赋值处（保序）
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

  /**
   * ★ <b>旧 8 参构造器（R2 兼容）</b>：口岸三字段取 {@code 计划 0 / 供给静态 1000‰ / 需求静态 1000‰}（= 中性修正 + 不设口岸编制）。
   *
   * <p>旧调用点（bootstrap / 工具 / 旧测试夹具）不改一字即得"口岸维度缺席"的旧语义；要开口岸编制就走 canonical 构造器或 {@code
   * gov.SetAdministrationPlan} 的 {@code portPlannedLaborMilli}。
   */
  public GovAdministrationPlan(
      long securityPlannedLaborMilli,
      long paperworkPlannedLaborMilli,
      List<GovPostTier> postTiers,
      long securitySupplyStaticModifierPerMille,
      long paperworkSupplyStaticModifierPerMille,
      long securityDemandStaticModifierPerMille,
      long paperworkDemandStaticModifierPerMille,
      long supernumerarySqrtCoefficient) {
    this(
        securityPlannedLaborMilli,
        paperworkPlannedLaborMilli,
        0L,
        postTiers,
        securitySupplyStaticModifierPerMille,
        paperworkSupplyStaticModifierPerMille,
        NEUTRAL_MODIFIER_PER_MILLE,
        securityDemandStaticModifierPerMille,
        paperworkDemandStaticModifierPerMille,
        NEUTRAL_MODIFIER_PER_MILLE,
        supernumerarySqrtCoefficient);
  }

  /** 中性默认：三维计划量 0、默认 3 档、六个修正 1000‰、k=1（旧档缺源状态时使用）。 */
  public static GovAdministrationPlan neutral() {
    return new GovAdministrationPlan(
        0L,
        0L,
        0L,
        DEFAULT_POST_TIERS,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        NEUTRAL_MODIFIER_PER_MILLE,
        DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
