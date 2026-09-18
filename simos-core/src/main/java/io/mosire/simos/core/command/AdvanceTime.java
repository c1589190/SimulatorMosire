package io.mosire.simos.core.command;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.time.TimeRange;
import java.util.Objects;

/**
 * Core 自己的时间推进命令（spec §4.1，C16 的封闭集之一）。**不是**领域命令：它由 Core 的推进管线（Task 12）执行， 不过 {@code
 * CommandRegistry}。
 *
 * <p>★ **身份三件套在此**（裁定 36，取代 spec §4.1 的三组件形状）：{@code revisions} 的 {@code command_id} / {@code
 * correlation_id} / {@code initiator} **三列都是 NOT NULL**，而 spec §4.1 的草图里这三者一个都没有—— 数据必须有来源。与 {@link
 * CommandEnvelope} **同形同序**，没道理信封带身份而 Core 自己的命令不带。
 *
 * <p>★ {@code AdvanceTime} 这一支**不归本任务的 {@code CommandBus} 实现**（裁定 32）：它由构造期注入的 {@link AdvanceRoute}
 * 接手，所以本类只负责"把它原样递过去"。
 */
public record AdvanceTime(
    String commandId,
    String correlationId,
    String initiator,
    BranchId branch,
    RevisionId expectedRevision,
    TimeRange range)
    implements Command {

  public AdvanceTime {
    commandId = requireText(commandId, "commandId");
    correlationId = requireText(correlationId, "correlationId");
    initiator = requireText(initiator, "initiator");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(range, "range");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " 不得为空串");
    }
    return value;
  }
}
