package io.mosire.simos.sd.model;

/**
 * 决策状态（spec §三.5 的 {@code Directive.status}）。
 *
 * <p>★ **spec 未定义取值**（§三.5 只把它列为字段类型）——实现期定为四档 + 第五档 {@link #SUPERSEDED}，见台账取代说明。
 *
 * <p>★★ **{@link #SUPERSEDED} 是 2026-09-23 用户裁定的"令可重写"落地的那一档**：同一 ({@code decisionMakerId}, {@code
 * tick}) 允许**再出令**（重写），但**只有最新一条生效**——旧的那条由 {@code sd.IssueDirective} 在**出令那一刻**转成本档
 * （终态、不再参与裁决）。取值语义的分工：
 *
 * <ul>
 *   <li>{@link #PLANNED}/{@link #ISSUED} = **生效中**（"末位生效"只认这两档里的那一条）；
 *   <li>{@link #EXECUTED}/{@link #CANCELLED}/{@link #SUPERSEDED} = **终态**（不得再转移；{@code
 *       sd.SetDirectiveStatus} 的转移守卫对它们一律拒）。
 * </ul>
 *
 * <p>★ **本档不可由外部指定**：{@code sd.SetDirectiveStatus} 的合法目标只有 {@code EXECUTED}/{@code CANCELLED} 两个终态
 * ⇒ 它是 {@code sd.IssueDirective} 内部"顶掉旧令"的产物，不是一条调用方可以随便设的状态。
 */
public enum DirectiveStatus {
  /** 计划中（尚未生效）。 */
  PLANNED,
  /** 已发出（进入命令延迟通道）。 */
  ISSUED,
  /** 已执行。 */
  EXECUTED,
  /** 已取消。 */
  CANCELLED,
  /**
   * 被**同一 ({@code decisionMakerId}, {@code tick}) 的新版本令顶掉**（重写的产物，终态）。
   *
   * <p>★ 留痕而非删除：旧令的形状、执行原文（INFO）与命令清单**一条不动**，只是不再生效。
   */
  SUPERSEDED
}
