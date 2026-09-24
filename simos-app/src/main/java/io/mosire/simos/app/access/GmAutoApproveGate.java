package io.mosire.simos.app.access;

import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalGate;
import io.mosire.agentlib.approval.ApprovalRequest;
import java.util.Optional;

/**
 * ★★ **GM 面的写：无脑过**（2026-09-24 用户裁定，原话：「为啥这种 GM 级命令要额外审批？改成 MCP/GM Agent 无脑过」）。
 *
 * <p>它只挂在**一条**审批链上——{@code AgentToMcpServer}（MCP 口 = GM 组）用的那条 {@code ApprovalCoordinator}。 理由：MCP
 * 口持有者本来就能 {@code simos.command.submit} 任意命令（用户 2026-09-22 裁定 D2：GM 组保留通用写是
 * 有意取舍），再要求逐条点批准只是摩擦、不构成边界。
 *
 * <p>★ **决策人那条链一个字不改**：出令（{@code sd.IssueDirective} 等）仍走 {@code AutoApproveGate → ConfirmGate}，GM
 * 照旧在「决策 → 审批」里点头。证据在别处：{@code DecisionMakerScopeEndToEndTest} / {@code RunDecisionEndToEndTest} /
 * {@code DecisionAgentRunnerTest} 都在决策人一轮里读 {@code pendingApprovals().pending()}（那正是"决策人仍要审批"的判据）。
 *
 * <p>★ **为什么按"用哪条链"分，而不是按请求字段分**：两张面的 caller 桶都是 {@code DEFAULT}（会话级放行对 {@code DEFAULT} 桶还会被
 * AgentLib 的 {@code effectiveDecision} 收窄成一次，实测见 {@code ShellApprovalTest}）， 从 {@link
 * ApprovalRequest} 的 {@code callerKey}/{@code requesterId} 上**分不开** GM 与决策人——分得开的只有 "这次调用用的是哪条
 * authorizer"。
 *
 * <p>★ 它**不**放松任何别的东西：权限（{@code ToolSpec.level} 与 {@code AgentPermissionSet}）、资源 （{@code
 * ResourceManifest}）、{@code ToolGate.Block} 仍在 {@code ToolCallAuthorizer} 的同一条流水线上照旧判定——
 * 本类只替"要不要问人"这一个问题回答"不用问"。
 */
public final class GmAutoApproveGate implements ApprovalGate {

  /** 链上的名字（出现在事件/日志里，便于一眼看出是这条链放的行）。 */
  public static final String NAME = "simos-gm-auto-approve";

  @Override
  public String name() {
    return NAME;
  }

  /**
   * 一律批准（{@link ApprovalDecision#APPROVE_ONCE}）：每次调用各批一次，不留会话级授权 ——"无脑过"是这条链的口径，不是给某个 caller 发的通行证。
   */
  @Override
  public Optional<ApprovalDecision> decide(ApprovalRequest request) {
    return Optional.of(ApprovalDecision.APPROVE_ONCE);
  }
}
