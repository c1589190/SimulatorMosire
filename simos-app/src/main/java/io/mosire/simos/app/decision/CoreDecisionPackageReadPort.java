package io.mosire.simos.app.decision;

import io.mosire.agentlib.decision.DecisionItemVerdict;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackageReadPort;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AgentLib 决策包五工具的宿主读口（设计书 §5.5 / §8.3）：只读当前 head 的 {@link GaState#decisionPackets()}，不持有可变缓存、不依赖
 * {@code DecisionPackages} 内存表。
 *
 * <p>{@link #find(String)} 是历史入口；{@link #pending()} / {@link #all()} 是当前 tick 的工具列表投影， 并排除已有 {@code
 * mergedPlanId} 的包，避免五工具把 MERGED 包当成可 commit。
 */
public final class CoreDecisionPackageReadPort implements DecisionPackageReadPort {

  private final CoreSimos core;
  private final BranchId branch;

  public CoreDecisionPackageReadPort(CoreSimos core, BranchId branch) {
    this.core = Objects.requireNonNull(core, "core");
    this.branch = Objects.requireNonNull(branch, "branch");
  }

  @Override
  public Optional<DecisionPackage> find(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    DecisionPacketId id;
    try {
      id = DecisionPacketId.parse(packageId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    DecisionRecord record = load().decisionPackets().get(id);
    return record == null ? Optional.empty() : Optional.of(record.packageState().pkg());
  }

  @Override
  public List<DecisionPackage> pending() {
    Snapshot snapshot = loadWithTick();
    List<DecisionPackage> out = new ArrayList<>();
    for (DecisionRecord record : snapshot.ga().decisionPackets().values()) {
      if (record.tick() != snapshot.tick() || merged(record)) {
        continue;
      }
      PackageState state = record.packageState().pkg().state();
      if (state == PackageState.PROPOSED || state == PackageState.DECIDING) {
        out.add(record.packageState().pkg());
      }
    }
    return List.copyOf(out);
  }

  @Override
  public List<DecisionPackage> all() {
    Snapshot snapshot = loadWithTick();
    List<DecisionPackage> out = new ArrayList<>();
    for (DecisionRecord record : snapshot.ga().decisionPackets().values()) {
      if (record.tick() == snapshot.tick() && !merged(record)) {
        out.add(record.packageState().pkg());
      }
    }
    return List.copyOf(out);
  }

  @Override
  public List<DecisionItemVerdict> verdicts(String packageId) {
    DecisionRecord record = record(packageId);
    return record == null ? List.of() : record.packageState().verdicts();
  }

  @Override
  public Optional<String> expiryReason(String packageId) {
    DecisionRecord record = record(packageId);
    return record == null
        ? Optional.empty()
        : Optional.ofNullable(record.packageState().expiryReason());
  }

  private DecisionRecord record(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    DecisionPacketId id;
    try {
      id = DecisionPacketId.parse(packageId);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return load().decisionPackets().get(id);
  }

  private GaState load() {
    RevisionId head =
        core.head(branch)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "决策包读口 fail-closed：分支 " + branch.value() + " 没有 head"));
    SimulationState state = core.replay(new StateRef(branch, head));
    return ToolSupport.gaState(state);
  }

  private Snapshot loadWithTick() {
    RevisionId head =
        core.head(branch)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "决策包读口 fail-closed：分支 " + branch.value() + " 没有 head"));
    SimulationState state = core.replay(new StateRef(branch, head));
    return new Snapshot(ToolSupport.gaState(state), state.meta().timestamp().tick());
  }

  static boolean merged(DecisionRecord record) {
    for (DecisionCallMeta meta : record.callMeta().values()) {
      if (meta.mergedPlanId().isPresent()) {
        return true;
      }
    }
    return false;
  }

  private record Snapshot(GaState ga, long tick) {}
}
