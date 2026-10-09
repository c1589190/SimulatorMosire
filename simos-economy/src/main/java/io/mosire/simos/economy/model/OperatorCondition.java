package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * @param lastCycleRevenueByCurrency 上一周期收入（★★ P-T4：<b>按币分列</b> —— 键 = 收到的那种钱，值 = 毫；空表 = 那周期没有收入）
 * @param lastCycleCostMilli 上一周期成本（毫本币：成本估计出自 {@code ProducerCostBook} 的单一计价口径）
 * @param lastCycleNetMilli 上一周期净额（毫本币；可负）—— ★★ P-T4：只对<b>本币</b>收入减成本（外币既不相加也不折算）
 * @param unsoldStockMilli 滞销库存（毫商品）
 * @param selfUsableStockMilli 可自用库存（毫商品）
 * @param lastReason 最近一次状态转移的原因（具名文本；可空串）
 * @param cycleOfferedQty ★ S3：本周期累计挂单量（毫商品）—— 每轮市场结束后累加、关账日状态机消费后清零
 * @param cycleFilledQty ★ S3：本周期累计成交量（毫商品）—— 同上
 * @param cycleUnfilledQty ★ S3：本周期累计未成交量（毫商品）—— 同上
 * @param cycleRevenueByCurrency ★ S3：本周期累计货款（★★ P-T4：<b>按币分列</b>，键 = 买方实付的那种钱）—— 同上
 * @param cycleOutcompetedActors ★ S3：本周期累计"更便宜且真的卖掉的卖方家数"证据（逐轮求和）—— 同上
 * @param cycleOutcompetedQty ★ S3：本周期累计被更便宜卖方挤掉的数量（毫商品）—— 同上
 * @param cycleMarketRounds ★ S3：本周期已经观察到的市场轮数（0 = 本周期没有市场证据；&gt;0 = 有）—— 同上
 * @param cycleInputShortfallCycles ★ S3：最近一个关账周期是否存在投入不足（0/1）。★ 市场证据在关账消费后清零；
 *     本字段保留最近一次关账读数到下一个关账日（历史连续计数在 {@link #consecutiveInputShortfallCycles()}）。
 */
public record OperatorCondition(
    IndustryId industry,
    IndustryStatus status,
    long consecutiveUnsoldCycles,
    long consecutiveInputShortfallCycles,
    long cashReserveMilli,
    long debtPrincipalMilli,
    long debtServiceDueMilli,
    Map<CurrencyId, Long> lastCycleRevenueByCurrency,
    long lastCycleCostMilli,
    long lastCycleNetMilli,
    long unsoldStockMilli,
    long selfUsableStockMilli,
    long consecutiveDebtStressCycles,
    long consecutiveSuspendedCycles,
    long reopens,
    String lastReason,
    long cycleOfferedQty,
    long cycleFilledQty,
    long cycleUnfilledQty,
    Map<CurrencyId, Long> cycleRevenueByCurrency,
    long cycleOutcompetedActors,
    long cycleOutcompetedQty,
    long cycleMarketRounds,
    long cycleInputShortfallCycles) {

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
    /** 退出中（处理库存/资产份额/债务）。 */
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
    if (lastCycleRevenueByCurrency == null || cycleRevenueByCurrency == null) {
      throw new IllegalArgumentException("OperatorCondition 的逐币收入表不得为 null（没有收入就给空表）");
    }
    // ★ 冻结写在**赋值处**（照 FieldDelta/MarketReadout 的成例：SpotBugs 只认它自己看得见的
    //   Collections.unmodifiable*），helper 只负责"拷一份 + 校验"。
    lastCycleRevenueByCurrency =
        Collections.unmodifiableMap(
            copyMoney(lastCycleRevenueByCurrency, "lastCycleRevenueByCurrency"));
    cycleRevenueByCurrency =
        Collections.unmodifiableMap(copyMoney(cycleRevenueByCurrency, "cycleRevenueByCurrency"));
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
    if (cycleOfferedQty < 0L
        || cycleFilledQty < 0L
        || cycleUnfilledQty < 0L
        || cycleOutcompetedActors < 0L
        || cycleOutcompetedQty < 0L
        || cycleMarketRounds < 0L
        || cycleInputShortfallCycles < 0L) {
      throw new IllegalArgumentException("OperatorCondition 的周期累计证据不得为负");
    }
  }

  /**
   * ★★ <b>P-T4：按币问本周期累计货款</b>（唯一算式；<b>不</b>跨币求和）。
   *
   * <p>★ 为什么签名里必须有 {@link CurrencyId}：世界没有汇率（I17），"这一种钱收了多少"与"全部钱 1:1 加总" 是两个问题。问不出币种（说不出本币的 unit）⇒
   * 0（fail-closed），<b>不</b>退回求和。
   */
  public long cycleRevenueMilliOf(CurrencyId currency) {
    if (currency == null) {
      return 0L;
    }
    return cycleRevenueByCurrency.getOrDefault(currency, 0L);
  }

  /** ★★ P-T4：按币问<b>上一周期</b>收入（与 {@link #cycleRevenueMilliOf} 同一条口径）。 */
  public long lastCycleRevenueMilliOf(CurrencyId currency) {
    if (currency == null) {
      return 0L;
    }
    return lastCycleRevenueByCurrency.getOrDefault(currency, 0L);
  }

  /**
   * ★★ P-T4：逐币收入表的**构造期防御性拷贝**（保序）。★ I7：用 {@code LinkedHashMap} 保序，<b>不用</b> {@code
   * Map.copyOf}（迭代序不是内容的纯函数）；不可变包装写在赋值处（见构造器）。
   */
  private static Map<CurrencyId, Long> copyMoney(Map<CurrencyId, Long> amounts, String what) {
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : amounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "OperatorCondition." + what + " 不得含 null 键/值或负额: " + entry);
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /**
   * ★★ <b>只换 {@code lastReason}</b>（E1 的退出处置摘要追加口）：其余字段逐字带过。
   *
   * <p>退出处置发生在状态机已把本 unit 置为 {@code EXITED} 之后（{@code 旧结算引擎（R3a 已删除）.settleOperatorExits} 里），
   * 那里只追加"资产退回 / 劳动释放 / 偿债违约 / 留存库存"的事实摘要，不改任何转移判据字段 —— 本方法就是那条"只改理由"的写口。
   */
  public OperatorCondition withLastReason(String nextReason) {
    if (nextReason == null) {
      throw new IllegalArgumentException("OperatorCondition.lastReason 不得为 null（没有就空串）");
    }
    return new OperatorCondition(
        industry,
        status,
        consecutiveUnsoldCycles,
        consecutiveInputShortfallCycles,
        cashReserveMilli,
        debtPrincipalMilli,
        debtServiceDueMilli,
        lastCycleRevenueByCurrency,
        lastCycleCostMilli,
        lastCycleNetMilli,
        unsoldStockMilli,
        selfUsableStockMilli,
        consecutiveDebtStressCycles,
        consecutiveSuspendedCycles,
        reopens,
        nextReason,
        cycleOfferedQty,
        cycleFilledQty,
        cycleUnfilledQty,
        cycleRevenueByCurrency,
        cycleOutcompetedActors,
        cycleOutcompetedQty,
        cycleMarketRounds,
        cycleInputShortfallCycles);
  }

  /**
   * ★★ <b>一轮市场结束后累加周期证据</b>（唯一写口）—— 只增量、不解释；关账日由 {@code OperatorSettlement.advance} 消费并清零。
   *
   * <p>★ <b>为什么用 {@code Math.addExact} 而不是裸加</b>：这些量会跨多个市场轮、跨多个 revision 累加；溢出时必须当场炸， 不能回绕成负数再被
   * {@code < 0} 守卫当成坏数据或悄悄改变状态机判据。
   *
   * <p>★★ <b>P-T4：货款按币累加</b>（{@code revenueByCurrency} 的键 = 买方实付的那种钱）—— 各种钱各自进自己的格子、
   * <b>永不</b>相加；零额腿不落键（"没有这种钱"与"这种钱收了 0"分得开，且与本批之前"0 增量不改状态"逐值相同）。
   */
  public OperatorCondition plusCycleEvidence(
      long offeredQty,
      long filledQty,
      long unfilledQty,
      Map<CurrencyId, Long> revenueByCurrency,
      long outcompetedActors,
      long outcompetedQty) {
    if (offeredQty < 0L
        || filledQty < 0L
        || unfilledQty < 0L
        || outcompetedActors < 0L
        || outcompetedQty < 0L) {
      throw new IllegalArgumentException("OperatorCondition.plusCycleEvidence 的增量不得为负");
    }
    if (revenueByCurrency == null) {
      throw new IllegalArgumentException(
          "OperatorCondition.plusCycleEvidence 的逐币货款不得为 null（没有收入就给空表）");
    }
    Map<CurrencyId, Long> mergedRevenue = new LinkedHashMap<>(cycleRevenueByCurrency);
    for (Map.Entry<CurrencyId, Long> leg : revenueByCurrency.entrySet()) {
      if (leg.getKey() == null || leg.getValue() == null || leg.getValue() < 0L) {
        throw new IllegalArgumentException(
            "OperatorCondition.plusCycleEvidence 的逐币货款不得含 null 键/值或负额: " + leg);
      }
      if (leg.getValue() == 0L) {
        continue; // 0 增量不落键（与本批之前逐值相同：它不改任何读数）
      }
      mergedRevenue.merge(leg.getKey(), leg.getValue(), Math::addExact);
    }
    return new OperatorCondition(
        industry,
        status,
        consecutiveUnsoldCycles,
        consecutiveInputShortfallCycles,
        cashReserveMilli,
        debtPrincipalMilli,
        debtServiceDueMilli,
        lastCycleRevenueByCurrency,
        lastCycleCostMilli,
        lastCycleNetMilli,
        unsoldStockMilli,
        selfUsableStockMilli,
        consecutiveDebtStressCycles,
        consecutiveSuspendedCycles,
        reopens,
        lastReason,
        Math.addExact(cycleOfferedQty, offeredQty),
        Math.addExact(cycleFilledQty, filledQty),
        Math.addExact(cycleUnfilledQty, unfilledQty),
        mergedRevenue,
        Math.addExact(cycleOutcompetedActors, outcompetedActors),
        Math.addExact(cycleOutcompetedQty, outcompetedQty),
        Math.addExact(cycleMarketRounds, 1L),
        cycleInputShortfallCycles);
  }
}
