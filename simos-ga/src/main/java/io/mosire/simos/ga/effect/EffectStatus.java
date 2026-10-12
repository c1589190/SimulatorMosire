package io.mosire.simos.ga.effect;

/**
 * 效果状态机（spec §三.6，R6）。
 *
 * <p>★ R6 的落点：延期效果 = 在 {@link GaTimeParticipant} 里用 {@code range.to} 求值 {@code Effect.trigger}，
 * 达标才把状态推到 {@code FIRED}（**绝不**放 ③Resolve）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类型自 {@code io.mosire.simos.sd.model} 整族迁入本包</b>（用户裁定「sd 整个弃用！」）
 * ——<b>五档的枚举名与线格式取值一字未改</b>；推进者换家（{@code SdTimeParticipant} → {@link GaTimeParticipant}），
 * 状态机的转移规则逐字不变。
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
