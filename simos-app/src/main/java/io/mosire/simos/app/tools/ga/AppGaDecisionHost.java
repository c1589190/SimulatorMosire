package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.ga.tools.GaDecisionHost;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.util.Objects;

/**
 * ★★ <b>{@link GaDecisionHost} 的 app 实现</b>（2026-10-12 B3 第七域）：**逐条转调** {@link
 * DecisionAgentService} ——它的运行装配（LLM 客户端、会话库、开场快照、权限链）是 app 的装配物 ⇒ 按设计书 §4.5 判据留 app。
 *
 * <p>★ <b>失败语义逐字保留</b>：未绑定 provider（fail-closed）、决策人查无、路由坏掉一律**原样上抛**（调用方按既有口径分别渲染 {@code aborted}
 * / {@code failed} 视图）——本类不 catch、不折叠、不换异常类型。
 */
public final class AppGaDecisionHost implements GaDecisionHost {

  private final DecisionAgentService service;

  public AppGaDecisionHost(DecisionAgentService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public DecisionAgentRunner.DecisionTurn runRound(
      BranchId branch, RevisionId revision, DecisionMakerId decisionMakerId) {
    return service.runRound(branch, revision, decisionMakerId);
  }

  @Override
  public DecisionAgentRunner.DecisionTurn runRoundFrozen(
      BranchId branch,
      RevisionId revision,
      DecisionMakerId decisionMakerId,
      Object packetWriteLock) {
    return service.runRoundFrozen(branch, revision, decisionMakerId, packetWriteLock);
  }

  @Override
  public String conversationIdOf(StateRef ref, DecisionMakerId decisionMakerId) {
    return service.conversationIdOf(ref, decisionMakerId);
  }

  @Override
  public String appendUserMessage(StateRef ref, DecisionMakerId decisionMakerId, String text) {
    return service.appendUserMessage(ref, decisionMakerId, text);
  }
}
