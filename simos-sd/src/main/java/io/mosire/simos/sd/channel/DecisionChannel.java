package io.mosire.simos.sd.channel;

import java.util.Set;

/**
 * 决策提交渠道（spec §十三.1，照 AgentLib {@code ApprovalChannel} 形制）：**落点适配器，不是新语义**。
 *
 * <p>★ **无论从哪条渠道进来，最终都写同一落点**（{@code sd.IssueDirective} / {@code sd.SubmitVerdict}），走同一条 {@code
 * Command → ChangeSet → Revision} 路径（R9 / 铁律 2）。
 *
 * <p>★ **模块侧强制四条**（spec §十三.2，渠道不得代劳）：actor ∈ {@link #representableActors()}（{@link
 * ChannelAdmission#requireRepresentable}）；视图按 actor 的 {@code accessLimit} 由 sd 构造（N17）；1 令/tick 与发
 * revision 同事务 （落点命令自带 R4）；留痕（渠道 id + actor 进 revision 行 {@code initiator}，N18）。
 *
 * <p>★ {@link #available()} 是**可选**语义（spec §十三.1 明说不强制，计划 G4）：缺省可用，装配层在端口真的监听后再打开。
 */
public interface DecisionChannel {

  /** 渠道标识（留痕用，N18）。 */
  String channelId();

  /** 本渠道声明可代表的 actor 集合（**声明 ≠ 授权**，N2 校验见 {@link ChannelAdmission}）。 */
  Set<ActorId> representableActors();

  /** 把输入交进 sd 的落点；被拒 / 冲突 ⇒ 抛（调用方据此知道决策未落地）。 */
  void submit(ActorId actor, DecisionRequest request);

  /** 渠道是否可用（缺省可用）。 */
  default boolean available() {
    return true;
  }
}
