package io.mosire.simos.core.command;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import java.util.Objects;

/**
 * Core 自己的分岔命令（spec §4.1，C16 的封闭集之一）。语义在 {@code Timeline.fork}（spec §3.4 冻结）： {@code head(source)
 * == expectedRevision} 时写新行 {@code (newBranch, 1)}，parent 指向 {@code (source, expectedRevision)}，
 * 变更集为空（C13）。
 *
 * <p>★ **身份三件套在此**（裁定 36，取代 spec §4.1 的三组件形状）：理由同 {@link AdvanceTime}—— {@code revisions} 的三列 NOT
 * NULL，且与 {@link CommandEnvelope} 同形同序。
 *
 * <p>★ {@code commandType} **不在本 record 里**：分岔行的类型串由 {@code Timeline.FORK_COMMAND_TYPE} 钉住，
 * 调用方不必（也不该）重复写。
 */
public record ForkBranch(
    String commandId,
    String correlationId,
    String initiator,
    BranchId source,
    RevisionId expectedRevision,
    BranchId newBranch)
    implements Command {

  public ForkBranch {
    commandId = requireText(commandId, "commandId");
    correlationId = requireText(correlationId, "correlationId");
    initiator = requireText(initiator, "initiator");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(newBranch, "newBranch");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " 不得为空串");
    }
    return value;
  }
}
