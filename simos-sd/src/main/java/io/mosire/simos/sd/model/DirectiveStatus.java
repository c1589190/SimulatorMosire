package io.mosire.simos.sd.model;

/**
 * 决策状态（spec §三.5 的 {@code Directive.status}）。
 *
 * <p>★ **spec 未定义取值**（§三.5 只把它列为字段类型）——实现期定为四档，见台账取代说明。
 */
public enum DirectiveStatus {
  /** 计划中（尚未生效）。 */
  PLANNED,
  /** 已发出（进入命令延迟通道）。 */
  ISSUED,
  /** 已执行。 */
  EXECUTED,
  /** 已取消。 */
  CANCELLED
}
