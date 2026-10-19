package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>U20 工资锚的只读读数</b>（D-032 P4 §6.4）：键 + 锚的全部持久字段。
 *
 * <p>P4b1 只创建读口类型，<b>不在 app 接线</b>（P4c 才接 M3/M6/M8 与视图）。0 工资是合法读数； 缺锚不构造本类型， 由 {@code
 * WageAnchorBook#anchorFor} 的 {@code Optional.empty()} 表达。
 *
 * @param key 锚键
 * @param currency 锚币种
 * @param wagePerLaborMilli 单位劳动工资（≥ 0）
 * @param shortageStreak 连续缺工 tick（≥ 0）
 * @param satisfiedStreak 连续满足 tick（≥ 0）
 * @param lastAdjustedTick 最近改价日（-1 = 从未）
 * @param lastObservedMatchedLaborMilli 上一观测匹配劳动（≥ 0）
 * @param lastObservedTargetLaborMilli 上一观测目标劳动（≥ 0）
 * @param lastAdjustmentDelta 最近改价差（可负）
 */
public record WageAnchorReadout(
    WageAnchorKey key,
    CurrencyId currency,
    long wagePerLaborMilli,
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedTick,
    long lastObservedMatchedLaborMilli,
    long lastObservedTargetLaborMilli,
    long lastAdjustmentDelta) {

  public WageAnchorReadout {
    Objects.requireNonNull(key, "WageAnchorReadout.key 不得为 null");
    Objects.requireNonNull(currency, "WageAnchorReadout.currency 不得为 null");
  }

  /** 从键 + 持久锚构造读数（唯一拼写点）。 */
  public static WageAnchorReadout of(WageAnchorKey key, ProductionWageAnchor anchor) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(anchor, "anchor");
    return new WageAnchorReadout(
        key,
        anchor.currency(),
        anchor.wagePerLaborMilli(),
        anchor.shortageStreak(),
        anchor.satisfiedStreak(),
        anchor.lastAdjustedTick(),
        anchor.lastObservedMatchedLaborMilli(),
        anchor.lastObservedTargetLaborMilli(),
        anchor.lastAdjustmentDelta());
  }
}
