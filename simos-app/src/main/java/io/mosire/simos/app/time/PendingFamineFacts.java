package io.mosire.simos.app.time;

import io.mosire.simos.social.population.FamineMortalityInput;

/**
 * D-032 P1：上一 tick 经济缺口留给下一 tick Social 的单槽瞬态事实。
 *
 * <p>它不是 {@code SocialData}/ChangeSet/Codec 的组件；只活在 {@code PopulationEconomyTimeParticipant} 的
 * Shell 生命周期内。{@link #empty()} 表示"没有上一 tick 的缺口事实"（首 tick / 进程重启），Social 收到中性输入。
 *
 * @param sourceTick 产生本 facts 的经济 tick（没有事实时为 -1）
 * @param input 交给下一 tick Social 的饥荒死亡率输入
 */
record PendingFamineFacts(long sourceTick, FamineMortalityInput input) {

  PendingFamineFacts {
    if (input == null) {
      throw new IllegalArgumentException("PendingFamineFacts.input 不得为 null");
    }
  }

  /** 首 tick / 无事实的中性单槽。 */
  static PendingFamineFacts empty() {
    return new PendingFamineFacts(-1L, FamineMortalityInput.none());
  }
}
