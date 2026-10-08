package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.fx.FxFill;
import io.mosire.simos.economy.api.fx.FxMarketRate;
import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>一轮市场的外汇读数</b>（阶段 2-A2a；约束设计书 §3.3/§3.4、判据 F2/F3/F4、负向 M2/M3/M4）。
 *
 * <p>★★ <b>它不进状态（I17/I9）</b>：只挂在当轮 {@code MarketReport} 的只读派生位上（{@code MarketReport.fx()}），进程内、
 * 重启即失 —— 与既有的"区级税费读数"同一形制。
 *
 * <p>★ <b>三个部分</b>：
 *
 * <ul>
 *   <li>{@link #fills()}：本轮全部 FX 成交（每笔 = 两条腿，已在唯一写口落账）；
 *   <li>{@link #windows()}：逐窗口的报价/容量/实际成交/停做原因（储备触顶、储备见底、无对手方、相反报价、国库付不出）；
 *   <li>{@link #rejections()}：逐笔具名拒（异币、余额不足、超储备、无对手方、低于最小手……）。
 * </ul>
 *
 * <p>★ <b>实际汇率</b>由 {@link #marketRate(CurrencyId, CurrencyId, int, String)} 现算（成交量加权均价）；<b>没有成交 ⇒
 * 无读数</b>（{@link FxMarketRate#hasReading()} = false）—— <b>绝不</b>回落官方汇率（I18/M4）。
 *
 * @param fills 本轮 FX 成交（保序：撮合序）
 * @param windows 本轮窗口读数（保序：装配序）
 * @param rejections 本轮具名拒（保序：发生序）
 */
public record FxRoundResult(
    List<FxFill> fills, List<WindowOutcome> windows, List<NamedRejection> rejections) {

  public FxRoundResult {
    fills = fills == null ? List.of() : List.copyOf(fills);
    windows = windows == null ? List.of() : List.copyOf(windows);
    rejections = rejections == null ? List.of() : List.copyOf(rejections);
  }

  /** 本轮没有外汇面（没有窗口，或窗口整段跳过）—— 逐值退回 A2a 之前。 */
  public static FxRoundResult none() {
    return new FxRoundResult(List.of(), List.of(), List.of());
  }

  /** 本轮有没有发生过外汇事件（成交/拒/窗口读数都算）。 */
  public boolean isEmpty() {
    return fills.isEmpty() && windows.isEmpty() && rejections.isEmpty();
  }

  /**
   * ★★ <b>一个窗口的读数</b>：报价照旧给出，容量与成交分开（报价 ≠ 成交量，I18）。
   *
   * @param buyFilledBaseMilli 本轮窗口<b>买入</b>的 base 合计（= 家户卖出的）
   * @param sellFilledBaseMilli 本轮窗口<b>卖出</b>的 base 合计
   * @param buyQuotePaidMilli 窗口为买入付出的 quote 合计
   * @param sellQuoteReceivedMilli 窗口卖出收到的 quote 合计
   * @param unfilledBuyBaseMilli 政府想买但没买到的量（无对手方/容量不够）
   * @param unfilledSellBaseMilli 政府想卖但没卖出的量（容量不够）
   */
  public record WindowOutcome(
      GovernmentId governmentId,
      CurrencyId base,
      CurrencyId quote,
      long bidPerMille,
      long askPerMille,
      long reserveBaseMilli,
      long reserveCapBaseMilli,
      long buyCapacityBaseMilli,
      long sellCapacityBaseMilli,
      long buyFilledBaseMilli,
      long sellFilledBaseMilli,
      long buyQuotePaidMilli,
      long sellQuoteReceivedMilli,
      long unfilledBuyBaseMilli,
      long unfilledSellBaseMilli,
      FxRejectReason buyBlocked,
      FxRejectReason sellBlocked) {

    public WindowOutcome {
      Objects.requireNonNull(governmentId, "WindowOutcome.governmentId 不得为 null");
      Objects.requireNonNull(base, "WindowOutcome.base 不得为 null");
      Objects.requireNonNull(quote, "WindowOutcome.quote 不得为 null");
    }

    /** 窗口是否真的与家户成交过（F2 的"两方向都有真实成交"就核这个）。 */
    public boolean traded() {
      return buyFilledBaseMilli > 0L || sellFilledBaseMilli > 0L;
    }
  }

  /**
   * ★★ <b>一笔具名拒</b>：谁、哪个币对、请求多少、实际成交多少、为什么。
   *
   * @param actor 请求方（窗口侧的拒记为国库 actor）
   * @param requestedBaseMilli 请求量（拒时 &gt; 0）
   * @param filledBaseMilli 实际成交（拒时 = 0；部分成交不落"拒"，落"容量用尽"的窗口读数）
   * @param detail 一句话（面向人/模型；不参与判定）
   */
  public record NamedRejection(
      FxRejectReason reason,
      ActorRef actor,
      CurrencyId base,
      CurrencyId quote,
      long requestedBaseMilli,
      long filledBaseMilli,
      String detail) {

    public NamedRejection {
      Objects.requireNonNull(reason, "NamedRejection.reason 不得为 null（拒必须具名）");
      Objects.requireNonNull(actor, "NamedRejection.actor 不得为 null");
      Objects.requireNonNull(base, "NamedRejection.base 不得为 null");
      Objects.requireNonNull(quote, "NamedRejection.quote 不得为 null");
      detail = detail == null ? "" : detail;
    }
  }

  /** 按 (gov, base, quote) 找窗口读数。 */
  public java.util.Optional<WindowOutcome> window(
      GovernmentId governmentId, CurrencyId base, CurrencyId quote) {
    for (WindowOutcome outcome : windows) {
      if (outcome.governmentId().equals(governmentId)
          && outcome.base().equals(base)
          && outcome.quote().equals(quote)) {
        return java.util.Optional.of(outcome);
      }
    }
    return java.util.Optional.empty();
  }

  /** 某个具名拒因出现了几次（读口/日志聚合用）。 */
  public long rejectionCount(FxRejectReason reason) {
    long count = 0L;
    for (NamedRejection rejection : rejections) {
      if (rejection.reason() == reason) {
        count++;
      }
    }
    return count;
  }

  /**
   * ★★ <b>实际汇率读数</b>（= 最近 ≤ {@code windowN} 笔成交的成交量加权均价）：
   *
   * <ul>
   *   <li>一笔成交都没有 ⇒ <b>无读数</b>（{@link FxRejectReason#NO_FX_FILLS}）—— 这是硬判据 M4；
   *   <li>{@code regionId} 非空 ⇒ 只算该区的成交（"本区最近 N 笔"的口径）；
   *   <li>★ {@code windowN} 是采样窗口（设计书 §9-4 的建议：一个产业周期的成交数）；A 阶段逐轮瞬态 ⇒ 实际取 {@code min(windowN,
   *       本轮成交数)}。
   * </ul>
   */
  public FxMarketRate marketRate(CurrencyId base, CurrencyId quote, int windowN, String regionId) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    List<FxFill> sample = new ArrayList<>();
    for (FxFill fill : fills) {
      if (!fill.base().equals(base) || !fill.quote().equals(quote)) {
        continue;
      }
      if (regionId != null && !regionId.isEmpty() && !regionId.equals(fill.regionId())) {
        continue;
      }
      sample.add(fill);
    }
    if (windowN > 0 && sample.size() > windowN) {
      sample = sample.subList(sample.size() - windowN, sample.size());
    }
    return FxMarketRate.weightedAverage(base, quote, sample, regionId);
  }
}
