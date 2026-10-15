package io.mosire.simos.ga.tools;

import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;

/**
 * ★★ <b>决策人运行口</b>（2026-10-12 B3 第 7 域）：GM 侧的触发工具（{@code ga.RunDecision} / {@code
 * simos.ga.run-decision-makers} / 打回令时往会话里投递理由）与运行流之间的唯一接缝。
 *
 * <p>★ <b>为什么是端口而不是直接持 {@code DecisionAgentRunner}</b>：一轮的运行要 LLM 客户端、会话库、开场快照、权限链 —— 那些是 app
 * 的装配物（{@code DecisionAgentService} 按设计书 §4.5 判据留 app：它持有域工具实例与 LLM 装配）。本模块只保留**运行流的骨架** （{@link
 * DecisionAgentRunner}，B2a 已迁入）与"什么时候让谁跑一轮"。
 *
 * <p>★ <b>返回类型是 ga 自己的类型</b>（{@link DecisionAgentRunner.DecisionTurn} / {@code
 * TurnBudgetExceeded}）： 触发工具要对"跑完了 / 撞预算中止 / 失败"三档分别渲染视图，那三档的判据必须与运行流同源（本模块的类），不能靠字符串码。
 *
 * <p>★ <b>失败语义不许折平</b>：未绑定 provider（fail-closed）、决策人查无、路由坏掉一律**原样抛出**——触发工具按既有口径分别渲染 {@code
 * aborted} / {@code failed} 视图（I-G1 的"拒绝语义逐字不变"）。
 */
public interface GaDecisionHost {

  /**
   * 让某个决策人在给定的世界版本上跑一轮。
   *
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人（或 sd 切片缺失——装配故障）
   * @throws IllegalStateException 该决策人未绑定 LLM provider（fail-closed）
   * @throws DecisionAgentRunner.TurnBudgetExceeded 撞上回合预算
   */
  DecisionAgentRunner.DecisionTurn runRound(
      BranchId branch, RevisionId revision, DecisionMakerId decisionMakerId);

  /**
   * 同时决策的**冻结回合**：世界读钉在 {@code branch@revision} 快照上，世界写工具被移出工具面，只允许写各自的决策包； {@code packetWriteLock}
   * 被并发批次共享，保证 packet 写串行。
   */
  DecisionAgentRunner.DecisionTurn runRoundFrozen(
      BranchId branch,
      RevisionId revision,
      DecisionMakerId decisionMakerId,
      Object packetWriteLock);

  /**
   * 某个世界版本上，该决策人此刻该用的会话 id（按**世界事实**现算——会话世代会被 {@code ga.ResetDecisionMakerConversation} 改掉）。
   *
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人
   */
  String conversationIdOf(StateRef ref, DecisionMakerId decisionMakerId);

  /**
   * 把一句话**原样**投进该决策人的会话（打回令的理由走这条通道），并返回落进的会话 id。
   *
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人；或 {@code text} 为空白
   */
  String appendUserMessage(StateRef ref, DecisionMakerId decisionMakerId, String text);
}
