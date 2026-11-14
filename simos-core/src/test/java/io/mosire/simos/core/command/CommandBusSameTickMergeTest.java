package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ModuleCodec;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D-036 同 tick 合并：写代次只能在候选态 checkpoint 落好之后推进。 */
class CommandBusSameTickMergeTest {

  private record ToyChangeSet(int delta) implements ChangeSet {}

  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int value)
      implements Snapshot {}

  private static final class ToyCodec implements ModuleCodec {
    @Override
    public String namespace() {
      return "toy";
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
          namespace(),
          before.value() + ((ToyChangeSet) changeSet).delta());
    }
  }

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;

  @BeforeEach
  void openTimeline() {
    store = SqliteStore.open(tempDir.resolve("same-tick-merge.db"));
    timeline = new Timeline(store, 100L);
    StateRef genesis = ref(1);
    timeline.appendRevision(
        new RevisionRow(
            genesis.branch(),
            genesis.revision(),
            Optional.empty(),
            SimosTimestamp.of(0L),
            "seed",
            "seed",
            "test",
            "test.Seed",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /**
   * 判别力：如果 {@link Timeline#updateRevision} 自己提前推进 writeEpoch，checkpointWriter 里读到的代次就已经是 before+1；
   * 读线程恰好在这个窗口查询时，会把旧 checkpoint 按新代次缓存，之后不再失效。修复后 checkpoint 写完前代次必须保持不变。
   */
  @Test
  void sameTickMergeAdvancesWriteEpochOnlyAfterCheckpointWriterReturns() {
    long before = timeline.writeEpoch();
    AtomicLong epochInsideWriter = new AtomicLong(-1L);
    CommandBus bus =
        new CommandBus(
            timeline,
            new CommandRegistry(List.of(appliedHandler())),
            cmd -> new CommandResult.Rejected("本用例不走 AdvanceTime"),
            ref -> state(ref(1)),
            List.of(),
            List.of(new ToyCodec()));
    bus.setSameTickMerge(true);
    bus.setCheckpointWriter(
        (ref, candidate) -> {
          assertThat(ref).isEqualTo(ref(1));
          epochInsideWriter.set(timeline.writeEpoch());
        });

    CommandResult result = bus.submit(envelope());

    assertThat(result).isEqualTo(new CommandResult.Committed(ref(1)));
    assertThat(epochInsideWriter.get())
        .as("checkpoint 写入期间必须仍是旧代次，读线程不会把旧 checkpoint 缓存成新代次")
        .isEqualTo(before);
    assertThat(timeline.writeEpoch()).as("checkpoint 写完后才推进代次").isEqualTo(before + 1);
  }

  private static CommandHandler appliedHandler() {
    return new CommandHandler() {
      @Override
      public String type() {
        return "toy.Write";
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Applied(new ToyChangeSet(1));
      }
    };
  }

  private static SimulationState state(StateRef ref) {
    SimosTimestamp timestamp = SimosTimestamp.of(0L);
    StateMeta meta = new StateMeta(ref, timestamp);
    return new SimulationState(
        meta, Map.of("toy", new ToySnapshot(ref, timestamp, "toy", 0)), InMemoryInfoSystem.empty());
  }

  private static CommandEnvelope envelope() {
    return new CommandEnvelope(
        "cmd-same-tick",
        "corr-same-tick",
        "test",
        new BranchId("main"),
        new RevisionId(1),
        "toy.Write",
        "{}");
  }

  private static StateRef ref(long revision) {
    return new StateRef(new BranchId("main"), new RevisionId(revision));
  }
}
