package io.mosire.simos.app.sd;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 跨模块效果的落点（spec §五.3，C5）：在**一次 {@code AdvanceTime} 提交成功之后**，读 sd 切片里 {@code FIRED} 且未 drain 的
 * {@code Action.EnqueueUnitCommand}，按 {@code effectId} 字典序经 {@link CoreSimos#submit} 提交。
 *
 * <p>★ **幂等**：每条待发指令的命令 id 取 {@code "drain:" + effectId}（R6 保证一个效果只 FIRED 一次 ⇒ effectId 是稳定键）；
 * {@link #drainedCommandIds} 从时间线**已提交的 revision 行**读回它 ⇒ 重复 drain / 重放**不重复提交**（进程重启后依然成立）。
 *
 * <p>★ **代价（如实记）**：drain 是"提交后再提交、跨多个 revision"；中途失败（提交被拒）会留下"sd 已记 effect、unit 未改"的中间态——
 * **不假装原子**，跨 revision 原子性列为挂起项（spec §十二）。被拒的指令**不写行** ⇒ 下次 drain 会重试（选择重试而非静默丢弃）。
 *
 * <p>★ **只经 {@code CoreSimos.submit}**：本类不得引用任何存储/时间线写面（{@code AppWritePathGuardTest} 的 R1）。
 */
public final class SdCommandDrain {

  private static final String INITIATOR = "system:sd-drain";

  private final CoreSimos core;

  public SdCommandDrain(CoreSimos core) {
    this.core = Objects.requireNonNull(core, "core");
  }

  public List<CommandResult> drainAfterAdvance(BranchId branch) {
    Objects.requireNonNull(branch, "branch");
    Optional<RevisionId> head = core.head(branch);
    if (head.isEmpty()) {
      return List.of();
    }
    SdState sd = sdOf(core.replay(new StateRef(branch, head.get())));
    Set<String> drained = drainedCommandIds(branch);
    List<Effect> pending = new ArrayList<>();
    for (Effect effect : sd.effects().values()) {
      if (effect.status() == EffectStatus.FIRED
          && effect.action() instanceof Action.EnqueueUnitCommand) {
        pending.add(effect);
      }
    }
    pending.sort((left, right) -> left.id().value().compareTo(right.id().value()));

    List<CommandResult> results = new ArrayList<>();
    long current = head.get().value();
    for (Effect effect : pending) {
      String commandId = drainCommandId(effect.id());
      if (drained.contains(commandId)) {
        continue;
      }
      Action.EnqueueUnitCommand enqueue = (Action.EnqueueUnitCommand) effect.action();
      CommandResult result =
          core.submit(
              new CommandEnvelope(
                  commandId,
                  commandId,
                  INITIATOR,
                  branch,
                  new RevisionId(current),
                  enqueue.type(),
                  enqueue.payloadJson()));
      results.add(result);
      if (result instanceof CommandResult.Committed committed) {
        current = committed.ref().revision().value();
        drained.add(commandId);
      }
    }
    return List.copyOf(results);
  }

  public static String drainCommandId(EffectId effectId) {
    return "drain:" + effectId.value();
  }

  private Set<String> drainedCommandIds(BranchId branch) {
    Set<String> ids = new LinkedHashSet<>();
    for (var row : core.revisions(branch)) {
      ids.add(row.commandId());
    }
    return ids;
  }

  private static SdState sdOf(SimulationState state) {
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("state 里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "state 的 sd 切片不是 SdSnapshot: " + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
