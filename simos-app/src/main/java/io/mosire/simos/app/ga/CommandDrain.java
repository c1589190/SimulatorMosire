package io.mosire.simos.app.ga;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.effect.Action;
import io.mosire.simos.ga.effect.Effect;
import io.mosire.simos.ga.effect.EffectStatus;
import io.mosire.simos.ga.id.EffectId;
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
 * 跨模块效果的落点（spec §五.3，C5）：在**一次 {@code AdvanceTime} 提交成功之后**，读 {@code effects} 表里 {@code FIRED} 且未
 * drain 的 {@code Action.EnqueueUnitCommand}，按 {@code effectId} 字典序经 {@link CoreSimos#submit} 提交。
 *
 * <p>★ **幂等**：每条待发指令的命令 id 取 {@code "drain:" + effectId}（R6 保证一个效果只 FIRED 一次 ⇒ effectId 是稳定键）；
 * {@link #drainedCommandIds} 从时间线**已提交的 revision 行**读回它 ⇒ 重复 drain / 重放**不重复提交**（进程重启后依然成立）。
 *
 * <p>★ **代价（如实记）**：drain 是"提交后再提交、跨多个 revision"；中途失败（提交被拒）会留下"效果已 FIRED、unit 未改"的中间态—— **不假装原子**，跨
 * revision 原子性列为挂起项（spec §十二）。被拒的指令**不写行** ⇒ 下次 drain 会重试（选择重试而非静默丢弃）。
 *
 * <p>★ **只经 {@code CoreSimos.submit}**：本类不得引用任何存储/时间线写面（{@code AppWritePathGuardTest} 的 R1）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：取数切片 sd → ga（如实记）</b>：{@code effects} 表随整族值类型搬进 {@code
 * simos-ga}（{@code GaState} 第 7 组件）⇒ 本类改读 **ga 切片**；<b>drain 的语义、命令 id 形态（{@code "drain:" +
 * effectId}）、发起者串（{@code system:sd-drain}）、排序与重试口径一字未改</b>。★ 类名与包名保持 {@code
 * app.sd.CommandDrain}（APP 侧的类名不是命令类型、不进 catalog，改名要连带动装配面与用例 ⇒ 留待批次 3e 随 sd 空壳一起正名； 与 {@code
 * RegisterEffectTool} 同款处置）。
 */
public final class CommandDrain {

  private static final String INITIATOR = "system:sd-drain";

  private final CoreSimos core;

  public CommandDrain(CoreSimos core) {
    this.core = Objects.requireNonNull(core, "core");
  }

  public List<CommandResult> drainAfterAdvance(BranchId branch) {
    Objects.requireNonNull(branch, "branch");
    Optional<RevisionId> head = core.head(branch);
    if (head.isEmpty()) {
      return List.of();
    }
    GaState ga = gaOf(core.replay(new StateRef(branch, head.get())));
    Set<String> drained = drainedCommandIds(branch);
    List<Effect> pending = new ArrayList<>();
    for (Effect effect : ga.effects().values()) {
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

  /** ga 切片（效果表的家；本批起不再是 sd 切片）：缺席 / 类型不对 = 装配故障，当场炸。 */
  private static GaState gaOf(SimulationState state) {
    Snapshot snapshot =
        state.module("ga").orElseThrow(() -> new IllegalStateException("state 里没有 ga 切片（装配故障）"));
    if (!(snapshot instanceof GaSnapshot gaSnapshot)) {
      throw new IllegalStateException(
          "state 的 ga 切片不是 GaSnapshot: " + snapshot.getClass().getName());
    }
    return gaSnapshot.state();
  }
}
