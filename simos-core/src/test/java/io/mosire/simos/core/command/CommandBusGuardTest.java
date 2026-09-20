package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A6：写前守卫在 {@code handler.handle} **之前**被调用、拒绝不留 revision，且 Core 只按 type/载荷转发。
 *
 * <p>★ 形态 4（纯转发型 SPI）：{@link #guardReceivesCommandTypeAndPayloadUnchanged()} 钉住参数被**原样转交**。
 */
class CommandBusGuardTest {

  record ToyChangeSet(int v) implements ChangeSet {}

  private static final SimulationState STUB_STATE =
      new SimulationState(
          new StateMeta(
              new StateRef(new BranchId("stub"), new RevisionId(1)), SimosTimestamp.of(7L)),
          Map.of(),
          InMemoryInfoSystem.empty());

  /** 判别力来源：首尾空白 + 非字典序键；只测 {@code "{}"} 对 trim/re-serialize 零判别力。 */
  private static final String DISCRIMINATING_PAYLOAD = "  {\"z\":1, \"a\":\"  a\\\"b  \"}\n\t ";

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;

  @BeforeEach
  void open() {
    store = SqliteStore.open(tempDir.resolve("guard.db"));
    timeline = new Timeline(store, 4L);
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L));
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void rejectingGuardShortCircuitsBeforeTheHandler() {
    CommandBus bus =
        new CommandBus(
            timeline,
            registry(handler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到 route"),
            ref -> STUB_STATE,
            List.of(guard("g1", Optional.of("带国家 tag 的区域不可删"), new ArrayList<>())));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).isEqualTo("带国家 tag 的区域不可删");
    assertThat(timeline.head(main())).as("拒绝是原子的：head 不动").contains(new RevisionId(1));
  }

  @Test
  void rejectingGuardLeavesReceivedAndRejectedEvents() {
    CommandBus bus =
        new CommandBus(
            timeline,
            registry(handler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到 route"),
            ref -> STUB_STATE,
            List.of(guard("g1", Optional.of("拒绝理由"), new ArrayList<>())));

    bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    List<String> types =
        new EventStore(store).byCorrelation("corr-1").stream().map(EventRow::type).toList();
    assertThat(types).containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_REJECTED);
  }

  @Test
  void allowingGuardLetsTheHandlerRun() {
    CommandBus bus =
        new CommandBus(
            timeline,
            registry(applyingHandler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到 route"),
            ref -> STUB_STATE,
            List.of(guard("g1", Optional.empty(), new ArrayList<>())));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(timeline.head(main())).contains(new RevisionId(2));
  }

  @Test
  void guardReceivesCommandTypeAndPayloadUnchanged() {
    AtomicReference<String> seenType = new AtomicReference<>();
    AtomicReference<String> seenPayload = new AtomicReference<>();
    MutationGuard capturing =
        new MutationGuard() {
          @Override
          public String name() {
            return "capture";
          }

          @Override
          public Optional<String> rejection(
              SimulationState state, String commandType, String payloadJson) {
            seenType.set(commandType);
            seenPayload.set(payloadJson);
            return Optional.empty();
          }
        };
    CommandBus bus =
        new CommandBus(
            timeline,
            registry(applyingHandler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到 route"),
            ref -> STUB_STATE,
            List.of(capturing));

    bus.submit(envelope("main", 1, "unit.RenameUnit", DISCRIMINATING_PAYLOAD));

    assertThat(seenType.get()).isEqualTo("unit.RenameUnit");
    assertThat(seenPayload.get()).as("载荷逐字节转交（R11 对 guard 同样成立）").isEqualTo(DISCRIMINATING_PAYLOAD);
  }

  @Test
  void guardsRunInRegistrationOrderAndStopAtTheFirstRejection() {
    List<String> calls = new ArrayList<>();
    CommandBus bus =
        new CommandBus(
            timeline,
            registry(applyingHandler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到 route"),
            ref -> STUB_STATE,
            List.of(
                guard("g1", Optional.empty(), calls),
                guard("g2", Optional.of("g2 说不"), calls),
                guard("g3", Optional.empty(), calls)));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(calls).as("注册序 = 调用序，且首个拒绝后不再往下调").containsExactly("g1", "g2");
    assertThat(((CommandResult.Rejected) result).reason()).isEqualTo("g2 说不");
  }

  private CommandRegistry registry(CommandHandler handler) {
    return new CommandRegistry(List.of(handler));
  }

  private static MutationGuard guard(String name, Optional<String> rejection, List<String> calls) {
    return new MutationGuard() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Optional<String> rejection(
          SimulationState state, String commandType, String payloadJson) {
        calls.add(name);
        return rejection;
      }
    };
  }

  private static CommandHandler applyingHandler(String type) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Applied(new ToyChangeSet(1));
      }
    };
  }

  private static CommandHandler handler(String type) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        throw new IllegalStateException("守卫拒绝后不该跑到 handler: " + type);
      }
    };
  }

  private static CommandEnvelope envelope(
      String branch, long expected, String type, String payload) {
    return new CommandEnvelope(
        "cmd-1",
        "corr-1",
        "player:local",
        new BranchId(branch),
        new RevisionId(expected),
        type,
        payload);
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
            "core.AdvanceTime",
            Timeline.changeSetJson(WorldChangeSet.empty())));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
