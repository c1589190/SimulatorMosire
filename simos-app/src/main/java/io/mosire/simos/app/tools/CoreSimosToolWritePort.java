package io.mosire.simos.app.tools;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ToolWritePort} 的宿主实现（组合根）：把端口调用还原成 core 的 {@code CommandEnvelope} 并转调 {@link CoreSimos}。
 *
 * <p>★★ <b>为什么适配器住在 app、不写进 ga</b>（设计书台账 §5 的裁定）：搬进域的工具**由 app 装配**——app 是唯一同时 认识 {@code core}
 * 与各域的模块；写进 {@code ga} 既无必要（它看不见域工具）又会把 {@code ga} 的依赖面撑大。
 *
 * <p>★ <b>信封字段的装配面口径</b>（逐条照 {@link ToolWritePort} 的适配规则）：{@code commandId} 由本类生成、 {@code
 * correlationId} 缺省 = {@code commandId}（C22 的单命令链）、{@code initiator} 由构造期注入（发起者属于装配面，
 * 不吃工具载荷）、{@code branch}/{@code expectedRevision} 由端口入参给、{@code payloadJson} **逐字节转交**。
 *
 * <p>★ <b>结局不许折平</b>：core 的三种结局原样映射到端口的三种结局（拒/冲突**不抛异常**），异常照旧逃出去。
 */
public final class CoreSimosToolWritePort implements ToolWritePort {

  private final CoreSimos core;
  private final String initiator;

  public CoreSimosToolWritePort(CoreSimos core, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public Receipt submitWithReceipt(
      BranchId branch, Command command, String type, String payloadJson) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payloadJson, "payloadJson");
    String commandId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId, commandId, initiator, branch, command.expectedRevision(), type, payloadJson);
    return new Receipt(commandId, adapt(core.submit(envelope)));
  }

  /**
   * 原子批提交：批内每条命令各生成一个 id（同一条链的 {@code correlationId} = 各自的 {@code commandId}），批级共享 {@code
   * branch}/{@code expectedRevision}；结局与入参**逐位对应**（端口契约的不变式）。
   */
  @Override
  public BatchOutcome submitBatch(
      BranchId branch, RevisionId expectedRevision, List<Request> batch) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(batch, "batch");
    List<CommandEnvelope> envelopes = new ArrayList<>(batch.size());
    for (Request request : batch) {
      Objects.requireNonNull(request, "request");
      String commandId = UUID.randomUUID().toString();
      envelopes.add(
          new CommandEnvelope(
              commandId,
              commandId,
              initiator,
              branch,
              expectedRevision,
              request.type(),
              request.payloadJson()));
    }
    return switch (core.submitBatch(envelopes)) {
      case BatchResult.Committed committed ->
          new BatchOutcome.Committed(committed.ref(), adaptAll(committed.outcomes()));
      case BatchResult.Rejected rejected ->
          new BatchOutcome.Rejected(adaptAll(rejected.outcomes()));
      case BatchResult.Conflict conflict ->
          new BatchOutcome.Conflict(conflict.current(), adaptAll(conflict.outcomes()));
    };
  }

  @Override
  public Optional<RevisionId> head(BranchId branch) {
    return core.head(Objects.requireNonNull(branch, "branch"));
  }

  @Override
  public SimulationState replay(StateRef ref) {
    return core.replay(Objects.requireNonNull(ref, "ref"));
  }

  private static List<Outcome> adaptAll(List<CommandOutcome> outcomes) {
    List<Outcome> adapted = new ArrayList<>(outcomes.size());
    for (CommandOutcome outcome : outcomes) {
      adapted.add(adapt(outcome.result()));
    }
    return List.copyOf(adapted);
  }

  /** core 结局 → 端口结局（逐字段同形；语义一字不改）。 */
  private static Outcome adapt(CommandResult result) {
    return switch (result) {
      case CommandResult.Committed committed -> new Outcome.Committed(committed.ref());
      case CommandResult.Rejected rejected -> new Outcome.Rejected(rejected.reason());
      case CommandResult.Conflict conflict -> new Outcome.Conflict(conflict.current());
    };
  }
}
