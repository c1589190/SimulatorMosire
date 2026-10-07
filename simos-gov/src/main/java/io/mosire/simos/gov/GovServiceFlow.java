package io.mosire.simos.gov;

import io.mosire.simos.unit.UnitId;
import java.util.Objects;

/**
 * ★★ <b>单个 GOV 本 tick 的行政服务流量（逐 tick 产生/消费/失效；设计书 §4.2 服务流量 / §8）</b>。
 *
 * <p>★★ <b>它不是商品、不进任何存量</b>：行政服务当 tick 产生、当 tick 被效率消费、未用完即失效——<b>不</b>进 {@code
 * HouseholdInventory}/库存/商品市场/ledger 存量，<b>不</b>落 {@code GovState}。进程内承载由 app 的 {@code
 * GovServiceFlowFeed}（同形先例：{@code MarketReportFeed}/MarketReadout 的 {@code unavailable} 具名）负责，
 * 重启/换进程后读不到（具名为"读不到"，绝不填 0）。
 *
 * <pre>
 * 承诺劳动_d（S_d）  = 该维岗位上 GOV_SERVICE 承诺劳动之和（毫小时/tick）
 * 有效劳动_d（E_d）  = 公式开方后的实际投入（供给 ≤ 需求 ⇒ S_d；超编 ⇒ 需求 + ⌊√(超额 ÷ 定额 × k)⌋ × 定额）
 * 服务产出_d        = E_d（V1 office 产业 outputPerUnit 为空 ⇒ 1:1；保留独立字段以免读口把两者混为一谈）
 * 被效率消费_d      = min(服务产出_d, 需求劳动_d)
 * 未用即失效_d      = 服务产出_d − 被效率消费_d
 * </pre>
 *
 * <p>★ <b>公式仍读有效劳动</b>（满足率 = E_d × 1000 ÷ 需求劳动_d，可 > 1000‰）：流量账只是把"产出/消费/失效"分开记， 不改变 {@link
 * GovEfficiency} 的冻结公式；{@code GovOfficeState} 只存当日读数，<b>不得</b>把昨日读数当次日输入。
 *
 * <p>★ <b>构造期守卫</b>：{@code gov} 非 null、{@code tick ≥ 0}、全部劳动量 {@code ≥ 0}；{@code 产出 == 有效劳动}、
 * {@code 消费 == min(产出, 需求)}、{@code 失效 == 产出 − 消费}（这是同一条流量账的三处读数，不许各自漂移）。新调用点用 {@link #of(UnitId,
 * long, long, long, GovEfficiency.Efficiency)} 从同一份效率结果派生。
 *
 * @param gov GOV 单位稳定身份（非 null）
 * @param tick 本流量所在的世界日（≥ 0）
 * @param securityCommittedLaborMilli 治安维承诺劳动（毫小时/tick；≥ 0）
 * @param paperworkCommittedLaborMilli 公文维承诺劳动（毫小时/tick；≥ 0）
 * @param securityEffectiveLaborMilli 治安维有效劳动（毫小时/tick；≥ 0）
 * @param paperworkEffectiveLaborMilli 公文维有效劳动（毫小时/tick；≥ 0）
 * @param securityDemandLaborMilli 治安维需求劳动（计划 × 静态/动态需求修正；≥ 0）
 * @param paperworkDemandLaborMilli 公文维需求劳动（同上；≥ 0）
 * @param securityServiceOutputMilli 治安维服务产出（V1 = 有效劳动；≥ 0）
 * @param paperworkServiceOutputMilli 公文维服务产出（V1 = 有效劳动；≥ 0）
 * @param securityConsumedByEfficiencyMilli 治安维被效率消费量（= min(产出, 需求)；≥ 0）
 * @param paperworkConsumedByEfficiencyMilli 公文维被效率消费量（同上；≥ 0）
 * @param securityExpiredUnusedMilli 治安维未用即失效量（= 产出 − 消费；≥ 0）
 * @param paperworkExpiredUnusedMilli 公文维未用即失效量（同上；≥ 0）
 */
public record GovServiceFlow(
    UnitId gov,
    long tick,
    long securityCommittedLaborMilli,
    long paperworkCommittedLaborMilli,
    long securityEffectiveLaborMilli,
    long paperworkEffectiveLaborMilli,
    long securityDemandLaborMilli,
    long paperworkDemandLaborMilli,
    long securityServiceOutputMilli,
    long paperworkServiceOutputMilli,
    long securityConsumedByEfficiencyMilli,
    long paperworkConsumedByEfficiencyMilli,
    long securityExpiredUnusedMilli,
    long paperworkExpiredUnusedMilli) {

  public GovServiceFlow {
    Objects.requireNonNull(gov, "gov");
    if (tick < 0L) {
      throw new IllegalArgumentException("GovServiceFlow.tick 必须 ≥ 0: " + tick);
    }
    requireNonNegative(securityCommittedLaborMilli, "securityCommittedLaborMilli");
    requireNonNegative(paperworkCommittedLaborMilli, "paperworkCommittedLaborMilli");
    requireNonNegative(securityEffectiveLaborMilli, "securityEffectiveLaborMilli");
    requireNonNegative(paperworkEffectiveLaborMilli, "paperworkEffectiveLaborMilli");
    requireNonNegative(securityDemandLaborMilli, "securityDemandLaborMilli");
    requireNonNegative(paperworkDemandLaborMilli, "paperworkDemandLaborMilli");
    requireNonNegative(securityServiceOutputMilli, "securityServiceOutputMilli");
    requireNonNegative(paperworkServiceOutputMilli, "paperworkServiceOutputMilli");
    requireNonNegative(securityConsumedByEfficiencyMilli, "securityConsumedByEfficiencyMilli");
    requireNonNegative(paperworkConsumedByEfficiencyMilli, "paperworkConsumedByEfficiencyMilli");
    requireNonNegative(securityExpiredUnusedMilli, "securityExpiredUnusedMilli");
    requireNonNegative(paperworkExpiredUnusedMilli, "paperworkExpiredUnusedMilli");
    requireEquals(
        securityServiceOutputMilli, securityEffectiveLaborMilli, "securityServiceOutputMilli");
    requireEquals(
        paperworkServiceOutputMilli, paperworkEffectiveLaborMilli, "paperworkServiceOutputMilli");
    requireEquals(
        securityConsumedByEfficiencyMilli,
        Math.min(securityServiceOutputMilli, securityDemandLaborMilli),
        "securityConsumedByEfficiencyMilli");
    requireEquals(
        paperworkConsumedByEfficiencyMilli,
        Math.min(paperworkServiceOutputMilli, paperworkDemandLaborMilli),
        "paperworkConsumedByEfficiencyMilli");
    requireEquals(
        securityExpiredUnusedMilli,
        Math.subtractExact(securityServiceOutputMilli, securityConsumedByEfficiencyMilli),
        "securityExpiredUnusedMilli");
    requireEquals(
        paperworkExpiredUnusedMilli,
        Math.subtractExact(paperworkServiceOutputMilli, paperworkConsumedByEfficiencyMilli),
        "paperworkExpiredUnusedMilli");
  }

  /**
   * ★★ <b>从唯一一份效率结果派生流量</b>（Z3b 唯一调用点）：两维承诺劳动由调用方给出（它来自承诺→两维供给桥），有效劳动/需求劳动取 {@link
   * GovEfficiency.Efficiency} 的同一份计算结果——<b>不在第二处重算</b>。
   */
  public static GovServiceFlow of(
      UnitId gov,
      long tick,
      long securityCommittedLaborMilli,
      long paperworkCommittedLaborMilli,
      GovEfficiency.Efficiency efficiency) {
    Objects.requireNonNull(efficiency, "efficiency");
    long securityOutput = efficiency.securityEffectiveLaborMilli();
    long paperworkOutput = efficiency.paperworkEffectiveLaborMilli();
    long securityConsumed = Math.min(securityOutput, efficiency.securityDemandLaborMilli());
    long paperworkConsumed = Math.min(paperworkOutput, efficiency.paperworkDemandLaborMilli());
    return new GovServiceFlow(
        gov,
        tick,
        securityCommittedLaborMilli,
        paperworkCommittedLaborMilli,
        efficiency.securityEffectiveLaborMilli(),
        efficiency.paperworkEffectiveLaborMilli(),
        efficiency.securityDemandLaborMilli(),
        efficiency.paperworkDemandLaborMilli(),
        securityOutput,
        paperworkOutput,
        securityConsumed,
        paperworkConsumed,
        securityOutput - securityConsumed,
        paperworkOutput - paperworkConsumed);
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }

  private static void requireEquals(long actual, long expected, String field) {
    if (actual != expected) {
      throw new IllegalArgumentException(
          field + " 与流量账不符: actual=" + actual + " expected=" + expected);
    }
  }
}
