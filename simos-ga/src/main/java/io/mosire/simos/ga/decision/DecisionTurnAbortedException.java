package io.mosire.simos.ga.decision;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import java.util.Objects;

/**
 * 决策回合的通用运行时异常载体（G1）：原异常作为 cause 保留，已登记 Ask 的队列随异常交回 app。
 *
 * <p>只在队列非空时由 {@link DecisionAgentRunner} 包装抛出；空队列不改变原异常类型。预算中止已有可判别类型， 不走本类（见 {@link
 * DecisionAgentRunner.TurnBudgetExceeded}）。
 */
public final class DecisionTurnAbortedException extends RuntimeException
    implements PendingDecisionCarrier {

  private static final long serialVersionUID = 1L;

  /** 待裁决队列（不参与 Java 序列化：AgentLib 队列不是 Serializable；本异常现实用途是同一进程内跨栈交回）。 */
  @SuppressFBWarnings(
      value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
      justification =
          "PendingDecisionQueue 是 AgentLib 的进程内运行态对象且不实现 Serializable；异常只用于同一进程内交回队列，不做落盘/反序列化")
  private final transient PendingDecisionQueue pendingDecisions;

  /** 原异常（构造期已收窄为 RuntimeException；避免从 Throwable getCause() 做未确认强转）。 */
  private final RuntimeException original;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "故意保存并交回原 RuntimeException：abort carrier 的语义就是把同一异常实例作为 cause/原始异常交给 app；异常本身可变是 Java 异常模型的既有形态")
  public DecisionTurnAbortedException(
      String message, PendingDecisionQueue pendingDecisions, RuntimeException cause) {
    super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    this.pendingDecisions = Objects.requireNonNull(pendingDecisions, "pendingDecisions");
    this.original = cause;
  }

  @Override
  public PendingDecisionQueue pendingDecisions() {
    return pendingDecisions;
  }

  /** 原始异常（构造期保存的 RuntimeException；本载体的 cause 与它同实例）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "按设计交回原异常实例，供 app 保留原类型/原实例语义；不复制异常（复制会改调用方判据）")
  public RuntimeException original() {
    return original;
  }
}
