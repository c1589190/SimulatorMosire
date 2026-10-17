package io.mosire.simos.economy.time;

import java.util.Objects;

/**
 * ★★★ <b>D-032 D3 §4.4：独立控制候选 IdleOption</b>。
 *
 * <p>它不是生产 offer，不经过 {@code queueable()/maxAbsorbableLaborMilli} 过滤，不占 unit cap，也不参与 maxAbsorbable
 * 汇总。{@code idleEligible} 只取 {@link SurvivalOutlook#idleEligible()}（资格读数）， 绝不在这里写 {@code
 * idleChosen} —— 选择结果由 {@link LaborQueueBook.Plan#idleChosen()} 表达。
 */
public record IdleOption(
    boolean idleEligible, SurvivalIdlePolicy policy, SurvivalOutlookReason reason) {

  public IdleOption {
    Objects.requireNonNull(policy, "IdleOption.policy 不得为 null");
    Objects.requireNonNull(reason, "IdleOption.reason 不得为 null");
  }

  /** 不具资格时的中性候选（不提供任何胜出路径）。 */
  public static IdleOption notEligible() {
    return new IdleOption(
        false,
        SurvivalIdlePolicy.PREFER_IDLE_WHEN_SUBSISTENCE_UNMET,
        SurvivalOutlookReason.SURVIVAL_REACHABLE);
  }

  /** 从纯只读 SurvivalOutlook 资格与当前政策构造；本方法不写选择结果。 */
  public static IdleOption of(SurvivalOutlook outlook, SurvivalIdlePolicy policy) {
    Objects.requireNonNull(outlook, "outlook");
    return new IdleOption(outlook.idleEligible(), policy, outlook.reason());
  }
}
