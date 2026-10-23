package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DM 决策包写工具的共用小件：包 id、当前 tick 守卫、内容冻结守卫、领域字段重打包与 {@code ga.ApplyDecisionPackage} 提交折叠。
 *
 * <p>它<b>不执行任何状态转移</b>：AgentLib 的状态机调用仍逐个发生在工具里 （{@code
 * DecisionPackageTransitions.replaceItem/removeItem/...}）。本类只把“读当前记录 → 提交一条内部命令”
 * 这段宿主胶水收成一处，避免每个工具各拼一份载荷与结局折叠。
 */
final class DecisionPackageWriteSupport {

  private DecisionPackageWriteSupport() {}

  static DecisionPacketId packetId(io.mosire.simos.util.spi.DecisionMakerId proposerId, long tick) {
    return DecisionPacketId.parse("pkt-" + proposerId.value() + "-" + tick);
  }

  static DecisionRecord find(
      GaState ga, io.mosire.simos.util.spi.DecisionMakerId proposerId, long tick) {
    return ga.decisionPackets().get(packetId(proposerId, tick));
  }

  static long currentTick(ToolReadPort reads, BranchId branch) {
    return reads.stateAt(branch, null).meta().timestamp().tick();
  }

  static Optional<ToolResult> rejectIfNotCurrentTick(
      DecisionPacketId packetId, long requestedTick, long currentTick) {
    if (requestedTick == currentTick) {
      return Optional.empty();
    }
    return Optional.of(
        ToolResult.error(
            "BAD_REQUEST",
            "只许动当前 tick 的决策包：请求 tick="
                + requestedTick
                + " 而当前世界 tick="
                + currentTick
                + "，历史 tick 的包只读（"
                + packetId.value()
                + "）"));
  }

  /** 设计书 §9 I6：第一条逐条裁决后内容冻结；PROPOSED 且无 verdict 是唯一可编辑窗口。 */
  static boolean contentEditable(DecisionRecord record) {
    return record != null
        && record.packageState().pkg().state() == PackageState.PROPOSED
        && record.packageState().verdicts().isEmpty();
  }

  static ToolResult contentFrozen(DecisionRecord record) {
    return ToolResult.error(
        "PACKAGE_CONTENT_FROZEN",
        "决策包内容已冻结（state="
            + record.packageState().pkg().state()
            + " verdicts="
            + record.packageState().verdicts().size()
            + "）：append/replace/remove/intent 一律拒绝，包 id="
            + record.id().value());
  }

  /** 换 AgentLib 快照，其余领域字段原样保留（AgentLib 是唯一状态机）。 */
  static DecisionRecord withPackageState(
      DecisionRecord record, DecisionPackageSnapshot packageState) {
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(packageState, "packageState");
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        record.intent(),
        record.createdAtRevision(),
        record.confirmedAtRevision(),
        record.reasonInfoId(),
        record.decidedAtRevision(),
        record.callMeta(),
        packageState);
  }

  /** 只改领域字段（intent/confirm 等），AgentLib 快照一字不动。 */
  static DecisionRecord withDomainFields(
      DecisionRecord record,
      String intent,
      java.util.OptionalLong confirmedAtRevision,
      java.util.Optional<String> reasonInfoId) {
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        intent,
        record.createdAtRevision(),
        confirmedAtRevision,
        reasonInfoId,
        record.decidedAtRevision(),
        record.callMeta(),
        record.packageState());
  }

  /** 提交一条 {@code ga.ApplyDecisionPackage}，并把三结局折成工具结果。 */
  static ToolResult submit(
      ToolWritePort writes,
      BranchId branch,
      SimulationState state,
      DecisionRecord expected,
      DecisionRecord next,
      Map<String, Object> view) {
    Objects.requireNonNull(writes, "writes");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(view, "view");
    long expectedContentVersion = expected.packageState().contentVersion();
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(
            branch,
            () -> new RevisionId(state.meta().ref().revision().value()),
            ApplyDecisionPackageHandler.TYPE,
            GaCodec.encodeApplyDecisionPackagePayload(expectedContentVersion, next));
    return fold(receipt.outcome(), view, receipt.commandId());
  }

  private static ToolResult fold(
      ToolWritePort.Outcome outcome, Map<String, Object> view, String commandId) {
    return switch (outcome) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  /** 写资源断言：只写自己的决策包前缀（身份派生，不采信载荷）。 */
  static void requireSelfWrite(
      ToolContext context,
      GaToolIdentity identity,
      io.mosire.simos.util.spi.DecisionMakerId proposerId) {
    ToolResources.requireAll(
        context,
        io.mosire.agentlib.permission.Operation.WRITE,
        java.util.List.of(
            ToolResources.resourceGa(identity.decisionPacketKind(), proposerId.value())));
  }
}
