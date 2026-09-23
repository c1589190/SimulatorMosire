package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **恢复提交**（{@link CommandBus#submitRestore}，2026-09-23）：撤销类操作的唯一落盘口。
 *
 * <p>它存在的理由与形状见 {@link CommandBus#RESTORE_COMMAND_TYPE}：时间线只追加、命令明文不留痕 ⇒ 撤销只能**追加一条逆变更
 * revision**。本用例钉住四件事：① 恰好落一条、行标签是自己那一个、变更集**逐值读得回**；② 坐标不新鲜 ⇒ {@code Conflict} 且**不落行**；③ 分支不存在 ⇒
 * {@code Rejected} 且不落行；④ 空变更集 ⇒ {@code Rejected}（一次什么都没改的 撤销是调用方的逻辑错，不该留一条空 revision 让审计去猜）。
 *
 * <p>★ 玩具 codec 与 {@code CommandBusBatchTest} 同制（test 侧不计 R1）：{@code apply} = 加后截断、{@code diff} =
 * target − base，于是"逆变更"就是同一个 codec 上一次负增量 —— 撤销的算术不需要真模块也能验。
 */
class CommandBusRestoreTest {

  /** 玩具变更集：一个增量。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具快照：{@code v} 是可观测的模块值。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int v)
      implements Snapshot {}

  /** 玩具 codec + differ。 */
  private static final class ToyCodec implements ModuleCodec, ModuleDiffer {

    private final String namespace;

    ToyCodec(String namespace) {
      this.namespace = namespace;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public ChangeSet decodeChangeSet(String json) {
      throw new UnsupportedOperationException("本用例只经 Timeline.readChangeSet 读回");
    }

    @Override
    public String encodeChangeSet(ChangeSet changeSet) {
      throw new UnsupportedOperationException("本用例只经 Timeline.changeSetJson 落盘");
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
          before.v() + ((ToyChangeSet) changeSet).v());
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
    store = SqliteStore.open(tempDir.resolve("restore.db"));
    timeline = new Timeline(store, 100L);
    append(ref("main", 1));
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  // ── ① 正常路径：一条 revision + 自己的行标签 + 变更集读得回 ──────────────────────────

  @Test
  void aRestoreAppendsExactlyOneRevisionWithItsOwnRowLabelAndTheGivenChangeSet() {
    CommandBus bus = bus();
    // 撤销：把 alpha 减 2（= 前一条提交 +2 的逆）。
    WorldChangeSet undo = new WorldChangeSet(Map.of("alpha", new ToyChangeSet(-2)));

    CommandResult result = bus.submitRestore(main(), new RevisionId(1), "gm:test", undo);

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref()).isEqualTo(ref("main", 2));
    assertThat(timeline.listRevisions(main())).as("恰好一条新 revision（两行 = 创世 + 这一条）").hasSize(2);

    RevisionRow row = timeline.row(ref("main", 2)).orElseThrow();
    assertThat(row.parent()).contains(ref("main", 1));
    assertThat(row.commandType())
        .as("★ 自己的行标签：审计要一眼看出『这一条没有命令来源，是一次撤销』")
        .isEqualTo(CommandBus.RESTORE_COMMAND_TYPE);
    assertThat(row.timestamp()).as("时刻继承 base").isEqualTo(SimosTimestamp.of(0L, "弘光元年"));
    assertThat(Timeline.readChangeSet(row.changesetJson()).modules())
        .as("变更集逐值读得回（逆变更就是这么落的）")
        .containsExactly(Map.entry("alpha", new ToyChangeSet(-2)));
  }

  // ── ② 坐标不新鲜 ⇒ Conflict 且不落行 ─────────────────────────────────────────────

  @Test
  void aStaleExpectedRevisionConflictsWithTheRealHeadAndLeavesNoRevision() {
    CommandBus bus = bus();

    CommandResult result =
        bus.submitRestore(
            main(),
            new RevisionId(99),
            "gm:test",
            new WorldChangeSet(Map.of("alpha", new ToyChangeSet(-2))));

    assertThat(result).isInstanceOf(CommandResult.Conflict.class);
    assertThat(((CommandResult.Conflict) result).current())
        .as("报**真实** head（与单条/批同口径）")
        .isEqualTo(ref("main", 1));
    assertThat(timeline.listRevisions(main())).as("冲突不落行").hasSize(1);
  }

  // ── ③ 分支不存在 ⇒ Rejected 且不落行 ────────────────────────────────────────────

  @Test
  void aMissingBranchIsRejectedAndLeavesNoRevision() {
    CommandBus bus = bus();

    CommandResult result =
        bus.submitRestore(
            new BranchId("nope"),
            new RevisionId(1),
            "gm:test",
            new WorldChangeSet(Map.of("alpha", new ToyChangeSet(-2))));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("分支不存在");
    assertThat(timeline.listRevisions(main())).hasSize(1);
  }

  // ── ④ 空变更集 ⇒ Rejected（不留空 revision 让审计去猜）────────────────────────────

  @Test
  void anEmptyChangeSetIsRejectedRatherThanAppendingAnEmptyRevision() {
    CommandBus bus = bus();

    CommandResult result =
        bus.submitRestore(main(), new RevisionId(1), "gm:test", WorldChangeSet.empty());

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("恢复变更集为空");
    assertThat(timeline.listRevisions(main())).as("不落一条什么都没改的 revision").hasSize(1);
  }

  // ── 助手 ───────────────────────────────────────────────────────────────────

  private CommandBus bus() {
    SimulationState state =
        new SimulationState(
            new StateMeta(ref("main", 1), SimosTimestamp.of(0L, "弘光元年")),
            Map.of(
                "alpha",
                new ToySnapshot(ref("main", 1), SimosTimestamp.of(0L, "弘光元年"), "alpha", 0)),
            InMemoryInfoSystem.empty());
    return new CommandBus(
        timeline,
        new CommandRegistry(List.<CommandHandler>of()),
        cmd -> new CommandResult.Rejected("该用例不走 AdvanceTime"),
        ref -> state,
        List.<MutationGuard>of(),
        List.<ModuleCodec>of(new ToyCodec("alpha")));
  }

  private void append(StateRef ref) {
    timeline.appendRevision(
        new RevisionRow(
            ref.branch(),
            ref.revision(),
            Optional.empty(),
            SimosTimestamp.of(0L, "弘光元年"),
            "cmd-genesis",
            "corr-genesis",
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
