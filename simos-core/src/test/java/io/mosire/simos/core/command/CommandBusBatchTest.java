package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommandBus#submitBatch} 的**原子批量提交**护栏（"一批命令 = 一条 revision"）。
 *
 * <p>★ 用**玩具 codec/differ**（不依赖任何领域模块）把 Core 的机制单独钉住：恰好一条 revision、每 namespace 一个键、
 * 顺序可见性、整体拒绝不留行、冲突口径。**真模块**的端到端（map + unit，含"同域两条命令的派生变更集"与"后一条看见前一条"） 在 {@code
 * CoreSimosBatchEndToEndTest}。
 *
 * <p>★ 玩具 codec 的 {@code apply} 是 **加后截断**（{@code min(base + delta, cap)}），{@code diff} 是 {@code
 * target - base}。于是"两条 +7 命令、cap=10"能把**从状态派生**与**把两条 delta 相加**区分开：前者得 {@code 10}（候选态真值）、后者得
 * {@code 14}——这正是铁律 5 的判别力所在。
 */
class CommandBusBatchTest {

  /** 玩具变更集：一个增量（test 侧，R1 只数 main 源码）。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具快照：{@code v} 是可观测的模块值。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int v)
      implements Snapshot {}

  /** 玩具 codec + differ：{@code apply} = 加后截断、{@code diff} = target - base。 */
  private static final class ToyCodec implements ModuleCodec, ModuleDiffer {

    private final String namespace;
    private final int cap;

    ToyCodec(String namespace, int cap) {
      this.namespace = namespace;
      this.cap = cap;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public ChangeSet decodeChangeSet(String json) {
      throw new UnsupportedOperationException("本用例不落盘变更集正文");
    }

    @Override
    public String encodeChangeSet(ChangeSet changeSet) {
      throw new UnsupportedOperationException("本用例不落盘变更集正文");
    }

    @Override
    public Snapshot decodeSnapshot(String json) {
      throw new UnsupportedOperationException("本用例不做 checkpoint/replay");
    }

    @Override
    public String encodeSnapshot(Snapshot snapshot) {
      throw new UnsupportedOperationException("本用例不做 checkpoint/replay");
    }

    @Override
    public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
      ToySnapshot before = (ToySnapshot) base;
      return new ToySnapshot(
          newMeta.ref(),
          newMeta.timestamp(),
          namespace,
          Math.min(before.v() + ((ToyChangeSet) changeSet).v(), cap));
    }

    @Override
    public ChangeSet diff(Snapshot base, Snapshot target) {
      return new ToyChangeSet(((ToySnapshot) target).v() - ((ToySnapshot) base).v());
    }
  }

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;

  @BeforeEach
  void openTimeline() {
    store = SqliteStore.open(tempDir.resolve("batch.db"));
    timeline = new Timeline(store, 100L);
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L, "弘光元年"));
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  // ── 类别 1：跨命名空间一批 ⇒ 恰好一条 revision、每 namespace 一个键 ──────────────────────

  @Test
  void crossNamespaceBatchCommitsExactlyOneRevisionWithOneKeyPerNamespace() {
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000), new ToyCodec("beta", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2), add("beta.Add", 5)),
            state(Map.of("alpha", 0, "beta", 0)));

    BatchResult result =
        bus.submitBatch(List.of(envelope("alpha.Add", 1), envelope("beta.Add", 1)));

    assertThat(result).isInstanceOf(BatchResult.Committed.class);
    BatchResult.Committed committed = (BatchResult.Committed) result;
    assertThat(committed.ref()).as("新坐标 = base + 1").isEqualTo(ref("main", 2));

    // ★ 恰好一条新 revision（两行 = 创世 + 这一批）
    assertThat(timeline.listRevisions(main())).as("两命令仍然只落一条 revision").hasSize(2);
    assertThat(timeline.head(main())).contains(new RevisionId(2));

    RevisionRow row = timeline.row(ref("main", 2)).orElseThrow();
    assertThat(row.parent()).contains(ref("main", 1));
    assertThat(row.commandType()).as("批行的类型标签").isEqualTo(CommandBus.BATCH_COMMAND_TYPE);
    assertThat(row.timestamp()).as("时刻继承 base（裁定 35）").isEqualTo(SimosTimestamp.of(0L, "弘光元年"));

    WorldChangeSet changeset = Timeline.readChangeSet(row.changesetJson());
    // ★ 每个命名空间各一个键，且保序（首次出现序：alpha 先）
    assertThat(changeset.modules().keySet()).containsExactly("alpha", "beta");
    assertThat(changeset.modules().get("alpha")).isEqualTo(new ToyChangeSet(2));
    assertThat(changeset.modules().get("beta")).isEqualTo(new ToyChangeSet(5));

    // ★ 每条结局齐全，且都指同一个 ref
    assertThat(committed.outcomes()).hasSize(2);
    assertThat(committed.outcomes())
        .allSatisfy(
            outcome -> {
              assertThat(outcome.committed()).isTrue();
              assertThat(outcome.result()).isEqualTo(new CommandResult.Committed(ref("main", 2)));
            });
  }

  // ── 类别 2：同域两条 ⇒ 仍一条 revision，变更集 = 从 base 到 candidate 的派生 ─────────────

  @Test
  void sameNamespaceBatchDerivesOneChangeSetFromStateNotTheSumOfDeltas() {
    // cap = 10：两条 +7 的候选态是 10（截断），而"delta 相加"是 14 —— 二者可区分
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 10)),
            List.of(),
            List.of(add("alpha.Add", 7)),
            state(Map.of("alpha", 0)));

    BatchResult result =
        bus.submitBatch(List.of(envelope("alpha.Add", 1), envelope("alpha.Add", 1)));

    assertThat(result).isInstanceOf(BatchResult.Committed.class);
    assertThat(timeline.listRevisions(main())).as("同域两条也只落一条 revision").hasSize(2);

    WorldChangeSet changeset =
        Timeline.readChangeSet(timeline.row(ref("main", 2)).orElseThrow().changesetJson());
    assertThat(changeset.modules().keySet()).as("同域 ⇒ 只有一个键").containsExactly("alpha");
    assertThat(changeset.modules().get("alpha"))
        .as("★ 从 base(0) → candidate(10) 派生，而不是两条 delta 相加(14)")
        .isEqualTo(new ToyChangeSet(10))
        .isNotEqualTo(new ToyChangeSet(14));
  }

  // ── 类别 3：一条被拒 ⇒ 整体拒绝、不留行、每条结局齐全 ─────────────────────────────────

  @Test
  void oneRejectedCommandRejectsWholeBatchLeavesNoRevisionAndReportsEveryOutcome() {
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000), new ToyCodec("beta", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2), rejecting("alpha.Reject", "不许"), add("beta.Add", 5)),
            state(Map.of("alpha", 0, "beta", 0)));

    BatchResult result =
        bus.submitBatch(
            List.of(
                envelope("alpha.Add", 1), envelope("alpha.Reject", 1), envelope("beta.Add", 1)));

    assertThat(result).isInstanceOf(BatchResult.Rejected.class);
    assertThat(timeline.head(main())).as("整体拒绝 ⇒ 不新增 revision").contains(new RevisionId(1));
    assertThat(timeline.listRevisions(main())).as("只有创世那一行").hasSize(1);

    List<CommandOutcome> outcomes = result.outcomes();
    assertThat(outcomes).as("每条命令都拿得到结局（不是只返回第一条）").hasSize(3);

    // 首条：被接受但随整批复原
    assertThat(outcomes.get(0).committed()).isFalse();
    assertThat(outcomes.get(0).rejected()).isTrue();
    assertThat(((CommandResult.Rejected) outcomes.get(0).result()).reason()).contains("整批未提交");
    // 第二条：真被拒、带真拒因
    assertThat(outcomes.get(1).command().type()).isEqualTo("alpha.Reject");
    assertThat(outcomes.get(1).result()).isEqualTo(new CommandResult.Rejected("不许"));
    // 第三条：也被标为随整批复原
    assertThat(outcomes.get(2).rejected()).isTrue();
    assertThat(((CommandResult.Rejected) outcomes.get(2).result()).reason()).contains("整批未提交");
  }

  // ── 类别 4：顺序可见性（后一条看见前一条的效果）────────────────────────────────────────

  @Test
  void laterCommandSeesTheEffectOfTheEarlierOneInTheSameBatch() {
    // 玩具 handler：把"前一条有没有生效"编码成可见性——alpha.Sum 要求当前值 > 0，否则拒
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000)),
            List.of(),
            List.of(add("alpha.Add", 3), requirePositive("alpha.RequirePositive")),
            state(Map.of("alpha", 0)));

    BatchResult result =
        bus.submitBatch(List.of(envelope("alpha.Add", 1), envelope("alpha.RequirePositive", 1)));

    assertThat(result)
        .as("第二条在 base(alpha=0) 上会被拒；它通过了 ⇒ 它看见的是第一条之后的候选态(alpha=3)")
        .isInstanceOf(BatchResult.Committed.class);
    WorldChangeSet changeset =
        Timeline.readChangeSet(timeline.row(ref("main", 2)).orElseThrow().changesetJson());
    assertThat(changeset.modules().get("alpha")).isEqualTo(new ToyChangeSet(3));
  }

  // ── 冲突 / 分支不存在 / 空批 / 坐标不一致 / 守卫 ─────────────────────────────────────

  @Test
  void staleExpectedRevisionConflictsWholeBatchWithRealHead() {
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(5L));
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2)),
            state(Map.of("alpha", 0)));

    BatchResult result = bus.submitBatch(List.of(envelope("alpha.Add", 1)));

    assertThat(result).isInstanceOf(BatchResult.Conflict.class);
    BatchResult.Conflict conflict = (BatchResult.Conflict) result;
    assertThat(conflict.current()).as("报真实 head").isEqualTo(ref("main", 2));
    assertThat(conflict.outcomes()).hasSize(1);
    assertThat(conflict.outcomes().get(0).result())
        .isEqualTo(new CommandResult.Conflict(ref("main", 2)));
    assertThat(timeline.listRevisions(main())).as("冲突不落行").hasSize(2);
  }

  @Test
  void missingBranchRejectsWholeBatchWithPerCommandOutcomes() {
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2)),
            state(Map.of("alpha", 0)));

    BatchResult result =
        bus.submitBatch(
            List.of(
                new CommandEnvelope(
                    "cmd-1",
                    "corr-1",
                    "player:local",
                    new BranchId("ghost"),
                    new RevisionId(1),
                    "alpha.Add",
                    "{}")));

    assertThat(result).isInstanceOf(BatchResult.Rejected.class);
    assertThat(result.outcomes()).hasSize(1);
    assertThat(((CommandResult.Rejected) result.outcomes().get(0).result()).reason())
        .contains("分支不存在");
  }

  @Test
  void batchRejectedByGuardLeavesNoRevision() {
    MutationGuard guard =
        new MutationGuard() {
          @Override
          public String name() {
            return "no-alpha";
          }

          @Override
          public Optional<String> rejection(
              SimulationState state, String commandType, String payloadJson) {
            return commandType.equals("alpha.Add") ? Optional.of("守卫不让") : Optional.empty();
          }
        };
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000), new ToyCodec("beta", 1000)),
            List.of(guard),
            List.of(add("alpha.Add", 2), add("beta.Add", 5)),
            state(Map.of("alpha", 0, "beta", 0)));

    BatchResult result =
        bus.submitBatch(List.of(envelope("alpha.Add", 1), envelope("beta.Add", 1)));

    assertThat(result).isInstanceOf(BatchResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result.outcomes().get(0).result()).reason())
        .isEqualTo("守卫不让");
    assertThat(timeline.listRevisions(main())).as("守卫拒绝 ⇒ 无 revision").hasSize(1);
  }

  @Test
  void emptyBatchThrows() {
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2)),
            state(Map.of("alpha", 0)));

    assertThatThrownBy(() -> bus.submitBatch(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
  }

  @Test
  void batchWithMixedCoordinatesThrows() {
    CommandBus bus =
        bus(
            List.of(new ToyCodec("alpha", 1000)),
            List.of(),
            List.of(add("alpha.Add", 2)),
            state(Map.of("alpha", 0)));

    assertThatThrownBy(
            () -> bus.submitBatch(List.of(envelope("alpha.Add", 1), envelope("alpha.Add", 2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须共享 branch 与 expectedRevision");
  }

  @Test
  void missingModuleDifferFailsLoudly() {
    // 只有 ModuleCodec、没有 ModuleDiffer 的 codec：不得静默落空变更集，须响亮失败
    ModuleCodec codecOnly =
        new ModuleCodec() {
          @Override
          public String namespace() {
            return "alpha";
          }

          @Override
          public ChangeSet decodeChangeSet(String json) {
            throw new UnsupportedOperationException();
          }

          @Override
          public String encodeChangeSet(ChangeSet changeSet) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Snapshot decodeSnapshot(String json) {
            throw new UnsupportedOperationException();
          }

          @Override
          public String encodeSnapshot(Snapshot snapshot) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
            return base;
          }
        };
    CommandBus bus =
        bus(List.of(codecOnly), List.of(), List.of(add("alpha.Add", 2)), state(Map.of("alpha", 0)));

    assertThatThrownBy(() -> bus.submitBatch(List.of(envelope("alpha.Add", 1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ModuleDiffer");
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  private CommandBus bus(
      List<ModuleCodec> codecs,
      List<MutationGuard> guards,
      List<CommandHandler> handlers,
      SimulationState state) {
    return new CommandBus(
        timeline,
        new CommandRegistry(handlers),
        cmd -> new CommandResult.Rejected("该用例不走 AdvanceTime"),
        ref -> state,
        guards,
        codecs);
  }

  private static CommandHandler add(String type, int delta) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Applied(new ToyChangeSet(delta));
      }
    };
  }

  private static CommandHandler rejecting(String type, String reason) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Rejected(reason);
      }
    };
  }

  /** 只有当该 namespace 当前值 > 0 才接受——用于证明"后一条看见了前一条"。 */
  private static CommandHandler requirePositive(String type) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        int current = ((ToySnapshot) state.module("alpha").orElseThrow()).v();
        return current > 0
            ? new HandlerOutcome.Applied(new ToyChangeSet(0))
            : new HandlerOutcome.Rejected("前一条没生效：alpha = " + current);
      }
    };
  }

  private static SimulationState state(Map<String, Integer> values) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    values.forEach(
        (namespace, v) ->
            modules.put(namespace, new ToySnapshot(ref("main", 1), T0, namespace, v)));
    return new SimulationState(
        new StateMeta(ref("main", 1), T0), modules, InMemoryInfoSystem.empty());
  }

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  private static CommandEnvelope envelope(String type, long expected) {
    return new CommandEnvelope(
        "cmd-" + type,
        "corr-" + type,
        "player:local",
        main(),
        new RevisionId(expected),
        type,
        "{}");
  }

  private void append(StateRef target, Optional<StateRef> parent, SimosTimestamp timestamp) {
    timeline.appendRevision(
        new RevisionRow(
            target.branch(),
            target.revision(),
            parent,
            timestamp,
            "cmd-seed",
            "corr-seed",
            "player:local",
            "core.Bootstrap",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
