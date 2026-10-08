package io.mosire.simos.economy.api.fx;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>官方与实际汇率并存</b>（阶段 2-A2a；约束设计书 §3.3 的表 + §6.1 判据 F4）：一个币对、一个市场区的 <b>实际汇率读数</b>。
 *
 * <pre>
 * 有读数：ratePerMille = Σ(price_i × baseMilli_i) / Σ(baseMilli_i)   // 成交量加权均价（向下取整）
 * 无读数：hasReading() = false，ratePerMille() <b>当场抛</b>（不允许"没有读数"悄悄变成一个数）
 * </pre>
 *
 * <p>★★ <b>★ 本类型存在的唯一理由：不许把官方汇率冒充市场价（I18 / M4 / M8）</b>：
 *
 * <ul>
 *   <li>{@link #hasReading()} = false 时，{@link #ratePerMille()} <b>具名抛</b>（不是返回 0、不是返回官方价）——
 *       "无读数"必须显式处理，且不可能被顺手当成一个合法汇率用下去；
 *   <li>{@link #noReadingReason()} 给出具名原因（{@link FxRejectReason#NO_FX_FILLS}）。
 * </ul>
 *
 * <p>★ <b>"最近 N 笔"的边界（如实记）</b>：A 阶段实际汇率是<b>逐轮读数</b>（不落盘，I17）—— 生产侧的窗口是"最近一轮市场轮 的 FX 成交"（不超过 N
 * 笔）。跨轮的滚动窗口需要把成交史写进状态，那与 I17"汇率不进状态"冲突 ⇒ 留待 B（或用户裁定是否放宽 I17）。
 *
 * @param base 标的币
 * @param quote 计价币
 * @param hasReading 有没有读数（false = 无定义，不许回落官方价）
 * @param ratePerMille 成交量加权均价（per-mille；仅 {@code hasReading} 时有效）
 * @param sampleCount 参与计算的成交笔数（仅 {@code hasReading} 时 &gt; 0）
 * @param baseVolumeMilli 参与计算的 base 成交量合计（最小单位）
 * @param quoteVolumeMilli 参与计算的 quote 成交额合计（最小单位）
 * @param noReadingReason 无读数的具名原因（仅 {@code !hasReading} 时非 null）
 * @param regionId 读数所属市场区（A 阶段单区；空串 = 未按区过滤）
 */
public record FxMarketRate(
    CurrencyId base,
    CurrencyId quote,
    boolean hasReading,
    long ratePerMille,
    long sampleCount,
    long baseVolumeMilli,
    long quoteVolumeMilli,
    FxRejectReason noReadingReason,
    String regionId) {

  public FxMarketRate {
    Objects.requireNonNull(base, "FxMarketRate.base 不得为 null");
    Objects.requireNonNull(quote, "FxMarketRate.quote 不得为 null");
    regionId = regionId == null ? "" : regionId;
    if (hasReading) {
      if (ratePerMille <= 0L) {
        throw new IllegalArgumentException("有读数时 ratePerMille 必须 > 0: " + ratePerMille);
      }
      if (sampleCount <= 0L) {
        throw new IllegalArgumentException("有读数时 sampleCount 必须 > 0: " + sampleCount);
      }
      if (noReadingReason != null) {
        throw new IllegalArgumentException("有读数时不得同时带无读数原因: " + noReadingReason);
      }
    } else {
      if (ratePerMille != 0L || sampleCount != 0L) {
        throw new IllegalArgumentException(
            "无读数时 ratePerMille/sampleCount 必须为 0（不许留一个『看起来像读数』的值）: "
                + ratePerMille
                + "/"
                + sampleCount);
      }
      if (noReadingReason == null) {
        throw new IllegalArgumentException("无读数必须给具名原因（说不出为什么 = 不许静默）");
      }
    }
  }

  /**
   * ★★ <b>实际汇率（per-mille）</b>：无读数时<b>当场抛</b> —— 这是 I18"不得回落官方汇率冒充市场价"在类型上的落点 （调用方只能先问 {@link
   * #hasReading()}）。
   */
  @Override
  public long ratePerMille() {
    if (!hasReading) {
      throw new IllegalStateException(
          "该币对没有实际汇率读数（"
              + noReadingReason.wire()
              + "）；不得回落官方汇率冒充市场价（I18）: "
              + base.value()
              + "/"
              + quote.value());
    }
    return ratePerMille;
  }

  /** 无读数的读法（只给日志/读口；业务判断必须走 {@link #hasReading()}）。 */
  public FxRejectReason noReading() {
    return noReadingReason;
  }

  /** 一片"无读数"（{@link FxRejectReason#NO_FX_FILLS} 是唯一的生产原因）。 */
  public static FxMarketRate none(
      CurrencyId base, CurrencyId quote, String regionId, FxRejectReason reason) {
    return new FxMarketRate(base, quote, false, 0L, 0L, 0L, 0L, reason, regionId);
  }

  /** 成交量加权均价（{@code Σ(price×qty)/Σqty}，向下取整；零成交量 ⇒ 无读数，不猜）。 */
  public static FxMarketRate weightedAverage(
      CurrencyId base, CurrencyId quote, Iterable<FxFill> fills, String regionId) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    Objects.requireNonNull(fills, "fills");
    long weight = 0L;
    long weighted = 0L;
    long quoteVolume = 0L;
    long samples = 0L;
    for (FxFill fill : fills) {
      if (fill == null || !fill.base().equals(base) || !fill.quote().equals(quote)) {
        continue;
      }
      if (regionId != null && !regionId.isEmpty() && !regionId.equals(fill.regionId())) {
        continue;
      }
      weight = Math.addExact(weight, fill.baseMilli());
      weighted =
          Math.addExact(weighted, Math.multiplyExact(fill.pricePerMille(), fill.baseMilli()));
      quoteVolume = Math.addExact(quoteVolume, fill.quoteMilli());
      samples++;
    }
    if (weight <= 0L || samples <= 0L) {
      return none(base, quote, regionId, FxRejectReason.NO_FX_FILLS);
    }
    return new FxMarketRate(
        base,
        quote,
        true,
        weighted / weight,
        samples,
        weight,
        quoteVolume,
        null,
        regionId == null ? "" : regionId);
  }
}
