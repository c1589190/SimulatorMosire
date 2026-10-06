package io.mosire.simos.app.access;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>审批通道的日志装饰器</b>（2026-10-23 L1）：把审批链的四个可观测点—— {@code pending / approved / denied /
 * timeout}——记到 {@link AppLog#approval()}，其余语义逐字转交给 {@link ApprovalChannel} 委托者。
 *
 * <p>★ <b>为什么在这里</b>：审批的阻塞与超时发生在 AgentLib 的 {@code ApprovalCoordinator} 内，app 层唯一能看到的边界就是 {@code
 * ApprovalChannel}（publish 进待批、await 出决议/超时）。不包装通道就只能看到"工具最终失败了"，看不到"卡在等审批"。
 *
 * <p>★ <b>密钥/载荷纪律</b>：只记 {@code approvalId/tool/caller/classKey/deadlineEpochMs} 这类元信息； 请求自报的
 * {@code summary}/{@code digest} 一概不进日志（summary 可能含参数与文本）。
 *
 * <p>★ 不改变判定语义：{@code available}/{@link #await} 的返回值逐字来自委托者；日志失败不得影响审批结果。
 */
public final class LoggingApprovalChannel implements ApprovalChannel {

  /** 审批事件的发射通道（分类 = {@link AppLog#approval()}，来源 = {@link AppLogSource#APPROVAL}）。 */
  private static final LogChannel APPROVAL = EventLog.channel(AppLog.approval());

  private final ApprovalChannel delegate;

  /** 包一个审批通道；不得为 null。 */
  public LoggingApprovalChannel(ApprovalChannel delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public boolean available() {
    return delegate.available();
  }

  @Override
  public void publish(ApprovalRequest request) {
    Objects.requireNonNull(request, "request");
    delegate.publish(request);
    APPROVAL.info(
        LogEvent.of(
            "APPROVAL_PENDING",
            AppLogSource.APPROVAL,
            "approvalId",
            request.id(),
            "tool",
            request.tool(),
            "caller",
            request.callerKey(),
            "classKey",
            request.classKey(),
            "deadlineEpochMs",
            request.deadlineEpochMs()));
  }

  @Override
  public Optional<ApprovalDecision> await(String id, Duration timeout) {
    Optional<ApprovalDecision> decision = delegate.await(id, timeout);
    if (decision.isEmpty()) {
      APPROVAL.info(
          LogEvent.of(
              "APPROVAL_TIMEOUT",
              AppLogSource.APPROVAL,
              "approvalId",
              id,
              "timeoutMs",
              timeout == null ? -1L : timeout.toMillis()));
    } else if (decision.get() == ApprovalDecision.DENY) {
      APPROVAL.info(
          LogEvent.of(
              "APPROVAL_DENIED", AppLogSource.APPROVAL, "approvalId", id, "decision", "DENY"));
    } else {
      APPROVAL.info(
          LogEvent.of(
              "APPROVAL_APPROVED",
              AppLogSource.APPROVAL,
              "approvalId",
              id,
              "decision",
              decision.get().name()));
    }
    return decision;
  }
}
