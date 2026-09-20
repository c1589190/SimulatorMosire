package io.mosire.simos.sd.model;

/**
 * 效果状态机（spec §三.6，R6）。
 *
 * <p>★ R6 的落点：延期效果 = 在 {@code SdTimeParticipant.simulate} 里用 {@code range.to} 求值 {@code
 * Effect.trigger}， 达标才把状态推到 {@code FIRED}（**绝不**放 ③Resolve）。
 */
public enum EffectStatus {
  /** 计划中（条件未达成）。 */
  PLANNED,
  /** 已承诺（等待触发）。 */
  COMMITTED,
  /** 已触发（本 tick 发生；跨模块命令待 drain）。 */
  FIRED,
  /** 已取消。 */
  CANCELLED,
  /** 已过期（超时未触发）。 */
  EXPIRED
}
