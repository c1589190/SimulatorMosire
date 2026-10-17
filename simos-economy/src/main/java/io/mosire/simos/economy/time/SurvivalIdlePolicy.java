package io.mosire.simos.economy.time;

import io.mosire.simos.economy.model.EconomyPolicyKey;

/**
 * ★★★ <b>D-032 U23-b / D3 §4.4：生存不足时的 idle 选择策略</b>。
 *
 * <p>该策略只作用于 {@code idleEligible=true} 的家户；{@code idleEligible} 本身是资格读数，不等于已选择 idle。 值由 {@link
 * EconomyPolicyKey#SURVIVAL_IDLE_POLICY} 的工程默认 1 提供，可经既有政策面配置为 0。
 */
public enum SurvivalIdlePolicy {
  /** 值 1（默认）：资格成立即可自由分配剩余劳动直接选择 idle，正收益候选只进 {@code IDLE_FOREGONE_POSITIVE}。 */
  PREFER_IDLE_WHEN_SUBSISTENCE_UNMET(1L),
  /** 值 0：正收益工作先做；只有零/负收益且无可计价补偿的工作才可由 idle 击败。 */
  BEST_EFFORT_WORK(0L);

  private final long policyValue;

  SurvivalIdlePolicy(long policyValue) {
    this.policyValue = policyValue;
  }

  public long policyValue() {
    return policyValue;
  }

  /** 把 {@link EconomyPolicyKey#SURVIVAL_IDLE_POLICY} 的整数读数映射成闭枚举；越域 fail-closed。 */
  public static SurvivalIdlePolicy fromPolicyValue(long value) {
    for (SurvivalIdlePolicy policy : values()) {
      if (policy.policyValue == value) {
        return policy;
      }
    }
    throw new IllegalArgumentException(
        "survival-idle-policy-invalid: "
            + value
            + "（只接受 0=BEST_EFFORT_WORK / 1=PREFER_IDLE_WHEN_SUBSISTENCE_UNMET）");
  }
}
