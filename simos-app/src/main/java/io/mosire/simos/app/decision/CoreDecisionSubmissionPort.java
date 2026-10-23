package io.mosire.simos.app.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPlan;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.DecisionSubmissionPort;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * AgentLib 五工具的宿主写口（设计书 §8.2）：所有表态/执行都折成 {@code ga.ApplyDecisionPackage}，从当前 head 读、以
 * expectedRevision 乐观提交。
 *
 * <p>状态机只调用 AgentLib {@link DecisionPackageTransitions}；本类不复制包状态、不吞具名拒。 {@code MERGED} 是 Simos
 * 领域投影：已有 {@code mergedPlanId} 的包由合并计划执行器唯一执行， {@link #commitPackage(String, String)} 具名拒 {@value
 * #PACKAGE_MERGED}。
 */
public final class CoreDecisionSubmissionPort implements DecisionSubmissionPort {

  /** Simos 领域具名拒：包已进入合并计划执行路径。 */
  public static final String PACKAGE_MERGED = "PACKAGE_MERGED";

  private static final String NOT_EXECUTED = "表态已记录，尚未执行；请显式调用 decision_package_commit";

  private final CoreSimos core;
  private final BranchId branch;
  private final String initiator;
  private final DecisionEffectExecutor executor;

  public CoreDecisionSubmissionPort(
      CoreSimos core, BranchId branch, String initiator, DecisionCallPreviewer previewer) {
    this.core = Objects.requireNonNull(core, "core");
    this.branch = Objects.requireNonNull(branch, "branch");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.executor =
        new DecisionEffectExecutor(this.core, Objects.requireNonNull(previewer, "previewer"));
  }

  @Override
  public DecisionSubmission submitDecision(String packageId, Decision decision) {
    Objects.requireNonNull(decision, "decision");
    Loaded loaded = load(packageId);
    DecisionPackageSnapshot next =
        DecisionPackageTransitions.decide(
            loaded.record().packageState(),
            loaded.record().packageState().contentVersion(),
            decision,
            now(loaded.state()));
    DecisionRecord nextRecord = withSnapshot(loaded, next);
    submit(nextRecord, loaded);
    return new DecisionSubmission(next.pkg(), null, NOT_EXECUTED);
  }

  @Override
  public DecisionSubmission itemDecided(
      String packageId, int index, boolean approve, String by, String note) {
    Loaded loaded = load(packageId);
    DecisionPackageSnapshot next =
        DecisionPackageTransitions.itemDecided(
            loaded.record().packageState(),
            loaded.record().packageState().contentVersion(),
            index,
            approve,
            by,
            note,
            now(loaded.state()));
    DecisionRecord nextRecord = withSnapshot(loaded, next);
    submit(nextRecord, loaded);
    String commitNote =
        next.pkg().state() == PackageState.DECIDING ? "条目表态已记录（包仍在 DECIDING，尚未执行）" : NOT_EXECUTED;
    return new DecisionSubmission(next.pkg(), null, commitNote);
  }

  @Override
  public DecisionSubmission commitPackage(String packageId, String by) {
    Loaded loaded = load(packageId);
    if (merged(loaded.record())) {
      throw new DecisionPackages.RejectionException(
          PACKAGE_MERGED, "决策包已进入合并计划执行路径，decision_package_commit 不再执行: " + packageId);
    }
    DecisionPackageSnapshot current = loaded.record().packageState();
    if (current.receipt() != null) {
      return new DecisionSubmission(current.pkg(), current.receipt(), "");
    }
    DecisionCommitPlan plan =
        DecisionPackageTransitions.prepareCommit(current, now(loaded.state()));
    DecisionEffectExecutor.DecisionCommitExecution execution =
        executor.executeDecisionPackage(loaded.state(), initiator, loaded.record(), plan);
    if (execution.committed()) {
      return new DecisionSubmission(
          execution.record().packageState().pkg(), execution.receipt(), "");
    }
    if (execution.execution().status() == DecisionEffectExecutor.Status.CONFLICT) {
      return settleConflict(packageId, current, plan, execution.execution());
    }
    String note =
        "提交未落定（零 revision，包保持 "
            + current.pkg().state()
            + "）："
            + Objects.toString(execution.execution().failureCode(), "COMMIT_FAILED")
            + " — "
            + Objects.toString(execution.execution().reason(), "无原因");
    return new DecisionSubmission(current.pkg(), null, note);
  }

  /**
   * effect 批冲突后的 package-only 落定（设计书 §6.3）：效果零落盘，但把**同一份未变**的 DECIDED 包推进到 CONFLICTED，并落一条只含 {@code
   * ga.ApplyDecisionPackage} 的 revision。若 head 上的包已被别人改动则具名拒， 绝不用旧 plan 覆盖新状态。
   */
  private DecisionSubmission settleConflict(
      String packageId,
      DecisionPackageSnapshot beforeConflict,
      DecisionCommitPlan plan,
      DecisionEffectExecutor.ExecutionResult execution) {
    Loaded latest = load(packageId);
    DecisionPackageSnapshot latestSnapshot = latest.record().packageState();
    if (latestSnapshot.pkg().state() != PackageState.DECIDED
        || latestSnapshot.contentVersion() != beforeConflict.contentVersion()) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_VERSION_STALE,
          "提交冲突后决策包已被并发改动：期望 contentVersion="
              + beforeConflict.contentVersion()
              + " state=DECIDED，实际 contentVersion="
              + latestSnapshot.contentVersion()
              + " state="
              + latestSnapshot.pkg().state()
              + "（拒绝用旧 plan 覆盖，包 id="
              + packageId
              + "）");
    }
    String reason = Objects.toString(execution.reason(), "提交冲突：效果批零落盘");
    CommitReceipt hostConflictReceipt =
        new CommitReceipt(CommitReceipt.Status.CONFLICTED, null, List.of(), reason);
    DecisionPackageSnapshot afterSnapshot;
    try {
      afterSnapshot = DecisionPackageTransitions.completeCommit(plan, hostConflictReceipt);
    } catch (DecisionPackages.RejectionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.COMMIT_FAILED,
          "冲突落定失败（未写入）：" + Objects.toString(e.getMessage(), e.getClass().getName()),
          e);
    }
    DecisionRecord latestRecord = latest.record();
    DecisionRecord afterRecord =
        new DecisionRecord(
            latestRecord.id(),
            latestRecord.branch(),
            latestRecord.tick(),
            latestRecord.proposerId(),
            latestRecord.intent(),
            latestRecord.createdAtRevision(),
            latestRecord.confirmedAtRevision(),
            latestRecord.reasonInfoId(),
            latestRecord.decidedAtRevision(),
            latestRecord.callMeta(),
            afterSnapshot);
    submit(afterRecord, latest);
    CommitReceipt conflictReceipt = afterSnapshot.receipt();
    if (conflictReceipt == null) {
      throw new IllegalStateException("CONFLICTED 快照缺少回执（不应发生）: " + packageId);
    }
    return new DecisionSubmission(afterSnapshot.pkg(), conflictReceipt, "");
  }

  private void submit(DecisionRecord next, Loaded loaded) {
    String batchId = UUID.randomUUID().toString();
    long expectedContentVersion = loaded.record().packageState().contentVersion();
    CommandEnvelope apply =
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            initiator,
            branch,
            loaded.expected(),
            ApplyDecisionPackageHandler.TYPE,
            GaCodec.encodeApplyDecisionPackagePayload(expectedContentVersion, next));
    BatchResult result = core.submitBatch(List.of(apply));
    if (result instanceof BatchResult.Committed committed) {
      long expectedRevision = loaded.expected().value() + 1L;
      if (committed.ref().revision().value() != expectedRevision) {
        throw new IllegalStateException(
            "决策包提交已落盘但 revision 对账失败：base="
                + loaded.expected().value()
                + " actual="
                + committed.ref().revision().value());
      }
      return;
    }
    if (result instanceof BatchResult.Conflict) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_VERSION_STALE,
          "决策包写入冲突：base=" + loaded.expected().value() + " 已被其他写入者推进");
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    String reason = rejectionReason(rejected);
    String code = leadingCode(reason);
    if (code == null) {
      code = DecisionPackages.COMMIT_FAILED;
    }
    throw new DecisionPackages.RejectionException(code, reason);
  }

  private DecisionRecord withSnapshot(Loaded loaded, DecisionPackageSnapshot next) {
    DecisionRecord record = loaded.record();
    OptionalLong decidedAtRevision = record.decidedAtRevision();
    if (next.pkg().decision() != null) {
      if (decidedAtRevision.isEmpty()) {
        decidedAtRevision = OptionalLong.of(loaded.expected().value() + 1L);
      }
    } else {
      decidedAtRevision = OptionalLong.empty();
    }
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        record.intent(),
        record.createdAtRevision(),
        record.confirmedAtRevision(),
        record.reasonInfoId(),
        decidedAtRevision,
        record.callMeta(),
        next);
  }

  private Loaded load(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    DecisionPacketId id;
    try {
      id = DecisionPacketId.parse(packageId);
    } catch (IllegalArgumentException e) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_NOT_FOUND, "未知的决策包 id: " + packageId);
    }
    RevisionId head =
        core.head(branch)
            .orElseThrow(
                () ->
                    new DecisionPackages.RejectionException(
                        DecisionPackages.PACKAGE_NOT_FOUND,
                        "分支 " + branch.value() + " 没有 head，决策包不存在: " + packageId));
    SimulationState state = core.replay(new StateRef(branch, head));
    GaState ga = ToolSupport.gaState(state);
    DecisionRecord record = ga.decisionPackets().get(id);
    if (record == null) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_NOT_FOUND, "未知的决策包 id: " + packageId);
    }
    return new Loaded(state, head, record);
  }

  private static boolean merged(DecisionRecord record) {
    for (DecisionCallMeta meta : record.callMeta().values()) {
      if (meta.mergedPlanId().isPresent()) {
        return true;
      }
    }
    return false;
  }

  private static long now(SimulationState state) {
    return Math.max(0L, state.meta().timestamp().tick());
  }

  /** 保留 core handler 的具名拒码（形如 {@code PACKAGE_VERSION_STALE: …}）；没有具名码才折成 COMMIT_FAILED。 */
  private static String leadingCode(String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    int end = 0;
    while (end < reason.length()) {
      char c = reason.charAt(end);
      if (c == ':' || c == '：' || Character.isWhitespace(c)) {
        break;
      }
      end++;
    }
    if (end == 0) {
      return null;
    }
    String candidate = reason.substring(0, end);
    for (int i = 0; i < candidate.length(); i++) {
      char c = candidate.charAt(i);
      boolean ok = Character.isUpperCase(c) || Character.isDigit(c) || c == '_';
      if (!ok) {
        return null;
      }
    }
    return candidate;
  }

  private static String rejectionReason(BatchResult.Rejected rejected) {
    for (CommandOutcome outcome : rejected.outcomes()) {
      CommandResult result = outcome.result();
      if (result instanceof CommandResult.Rejected refusal) {
        return refusal.reason();
      }
    }
    return "决策包提交被拒（无逐条拒因）";
  }

  private record Loaded(SimulationState state, RevisionId expected, DecisionRecord record) {}
}
