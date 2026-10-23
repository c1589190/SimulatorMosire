package io.mosire.simos.ga.decision;

import io.mosire.agentlib.decision.PendingDecisionQueue;

/**
 * 异常载体：本回合已登记、但尚未落包的 {@link PendingDecisionQueue}。
 *
 * <p>正常路径由 {@link DecisionAgentRunner.DecisionTurn#pendingDecisions()} 交回；异常/预算中止路径由 {@link
 * DecisionAgentRunner.TurnBudgetExceeded} 或 {@link DecisionTurnAbortedException} 实现本接口， 供 app 的
 * abort 结算取出队列并恰好消费一次。
 */
public interface PendingDecisionCarrier {

  /** 本回合的待裁决队列（非 null；异常载体绝不复制队列）。 */
  PendingDecisionQueue pendingDecisions();
}
