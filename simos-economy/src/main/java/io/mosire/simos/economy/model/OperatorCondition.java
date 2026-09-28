package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Objects;

/**
 * ★★ <b>经营者状态</b>（S3.2 的最小字段；S1 一次性把组件 / 变更集 / 编解码补齐，避免以后再破 schema）。
 *
 * <p>★★ <b>为什么现在就要它</b>：计划 §S1.2 明说 {@code EconomyData} 的目标是 13 个组件，其中 {@code operatorConditions}
 * "若 S3 才启用可先空表，但组件和变更集一次性补齐" —— 否则 S3 落地时又要改一次 {@code EconomyData}/{@code EconomyChangeSet}/{@code
 * EconomyCodec}/往返反射断言（那正是"以后再破一次 schema"的债）。 本类型只承载 S3.2
 * 状态机需要的可观察量；<b>本阶段没有任何写入者</b>（空表缺省），阈值与状态转移在 S3 落地。
 *
 * <p>★★ <b>与 {@code Industry} 的分工</b>：{@code Industry} 是"技术配方 + 产能 + 进度"（生产的物理面），本类型是
 * "这个经营主体当前处于什么经营状态、连续多少个周期卖不动/缺投入、账上还有多少钱粮"（经营的行为面）。两者分开是因为 状态转移要读市场与债务，而 {@code Industry}
 * 的构造期守卫不该被行为读数污染。
 *
 * <p>★ <b>字段口径</b>：金额一律"毫"（毫货币 / 毫商品），计数一律"周期数"或"轮数"；{@code lastCycleNetMilli} 是净额（可负）；{@code
 * lastReason} 是最近一次状态转移的具名原因（可空串 = 尚未发生转移）。
 *
 * @param industry 本状态属于哪个产业（必须与所在表的键一致，由 {@code EconomyData} 的构造期守卫判）
 * @param status 当前经营状态（S3.2 的状态机词表）
 * @param consecutiveUnsoldCycles 连续"一个市场轮都没卖出去"的周期数
 * @param consecutiveInputShortfallCycles 连续"投入筹不齐"的周期数
 * @param cashReserveMilli 经营主体的现金储备（毫货币；S3 的状态机读它判偿付能力）
 * @param debtPrincipalMilli 经营主体的债务本金（毫商品折算口径；S3 接债务表后填真值）
 * @param debtServiceDueMilli 本周期应付本息（毫商品；S3 接债务表后填真值）
 * @param lastCycleRevenueMilli 上一周期收入（毫商品）
 * @param lastCycleCostMilli 上一周期成本（毫商品）
 * @param lastCycleNetMilli 上一周期净额（毫商品；可负）
 * @param unsoldStockMilli 滞销库存（毫商品）
 * @param selfUsableStockMilli 可自用库存（毫商品）
 * @param lastReason 最近一次状态转移的原因（具名文本；可空串）
 */
public record OperatorCondition(
    IndustryId industry,
    IndustryStatus status,
    long consecutiveUnsoldCycles,
    long consecutiveInputShortfallCycles,
    long cashReserveMilli,
    long debtPrincipalMilli,
    long debtServiceDueMilli,
    long lastCycleRevenueMilli,
    long lastCycleCostMilli,
    long lastCycleNetMilli,
    long unsoldStockMilli,
    long selfUsableStockMilli,
    long consecutiveDebtStressCycles,
    long consecutiveSuspendedCycles,
    long reopens,
    String lastReason) {

  /** S3.2 的经营者状态词表（状态转移规则在 S3 落地；本阶段只固定形状）。 */
  public enum IndustryStatus {
    /** 正常经营。 */
    ACTIVE,
    /** 试产（候选生产进入试产窗口）。 */
    TRIALING,
    /** ★ S3：积压（连续滞销且有 OUTCOMPETED 证据，尚未缩产）。 */
    OVERSUPPLIED,
    /** 缩产（卖不动但还没到破产）。 */
    CONTRACTING,
    /** ★ S3：债务压力（现金/库存缓冲不足以覆盖下一周期投入/本息，但还没停业）。 */
    INDEBTED,
    /** 停业（债务压力/再生产压力触发）。 */
    SUSPENDED,
    /** 退出中（处理库存/使用权/债务）。 */
    EXITING,
    /** 已退出（不再生产）。 */
    EXITED,
    /** 弃置（不再可能恢复）。 */
    ABANDONED
  }

  public OperatorCondition {
    Objects.requireNonNull(industry, "OperatorCondition.industry 不得为 null");
    Objects.requireNonNull(status, "OperatorCondition.status 不得为 null");
    if (consecutiveUnsoldCycles < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.consecutiveUnsoldCycles 不得为负: " + consecutiveUnsoldCycles);
    }
    if (consecutiveInputShortfallCycles < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.consecutiveInputShortfallCycles 不得为负: "
              + consecutiveInputShortfallCycles);
    }
    if (cashReserveMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.cashReserveMilli 不得为负: " + cashReserveMilli);
    }
    if (debtPrincipalMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.debtPrincipalMilli 不得为负: " + debtPrincipalMilli);
    }
    if (debtServiceDueMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.debtServiceDueMilli 不得为负: " + debtServiceDueMilli);
    }
    if (lastCycleRevenueMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.lastCycleRevenueMilli 不得为负: " + lastCycleRevenueMilli);
    }
    if (lastCycleCostMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.lastCycleCostMilli 不得为负: " + lastCycleCostMilli);
    }
    if (unsoldStockMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.unsoldStockMilli 不得为负: " + unsoldStockMilli);
    }
    if (selfUsableStockMilli < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.selfUsableStockMilli 不得为负: " + selfUsableStockMilli);
    }
    if (consecutiveDebtStressCycles < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.consecutiveDebtStressCycles 不得为负: " + consecutiveDebtStressCycles);
    }
    if (consecutiveSuspendedCycles < 0L) {
      throw new IllegalArgumentException(
          "OperatorCondition.consecutiveSuspendedCycles 不得为负: " + consecutiveSuspendedCycles);
    }
    if (reopens < 0L) {
      throw new IllegalArgumentException("OperatorCondition.reopens 不得为负: " + reopens);
    }
    if (lastReason == null) {
      throw new IllegalArgumentException("OperatorCondition.lastReason 不得为 null（没有就空串）");
    }
  }
}
