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
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommandBus} 的分派与四步（Task 9 的边界，裁定 33）：分派（C16 三支）+ ① 入口检查 + ② handler + ④ 落一行 revision。**③
 * 的锁内复查与锁纪律不在这里**——归 Task 10，本类的 {@code submit} 现在**故意**不是线程安全的。
 *
 * <p>★ 本文件里两条**形态 4（纯转发型 SPI）**的用例是重点，它们各钉一个注入点： {@link
 * #advanceTimeIsRoutedToInjectedRouteWithRequestUnchanged()}（裁定 32）与 {@link
 * #stateLoaderIsAskedForTheCurrentTipAndItsStateReachesTheHandler()}（裁定 34）。
 * 没有这两条，那两个注入点**本身没有判别力**——测试只会证明"调用了某个东西"。
 */
class CommandBusDispatchTest {

  /** 玩具变更集（test 侧，R1 不计）：只需能被 {@code Timeline.changeSetJson} 带上类型信息落盘。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具状态：`modules` 空表合法（`SimulationState` 只校验"键 = 快照 namespace"，空表无键可违）。 */
  private static final SimulationState STUB_STATE =
      new SimulationState(
          new StateMeta(
              new StateRef(new BranchId("stub"), new RevisionId(1)), SimosTimestamp.of(7L)),
          Map.of(),
          InMemoryInfoSystem.empty());

  /** R11 的判别力来源：首尾空白 + **非字典序键** + 转义引号。只测 `"{}"` 的用例对 trim/re-serialize 零判别力。 */
  private static final String DISCRIMINATING_PAYLOAD = "  {\"z\":1, \"a\":\"  a\\\"b  \"}\n\t ";

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private CommandBus bus;

  /** 上一轮 handler 收到的载荷（R11 用；每次 submit 前由用例重置）。 */
  private final AtomicReference<String> seenPayload = new AtomicReference<>();

  /** 上一轮 handler 收到的状态（裁定 34 用）。 */
  private final AtomicReference<SimulationState> seenState = new AtomicReference<>();

  @BeforeEach
  void openBus() {
    store = SqliteStore.open(tempDir.resolve("bus.db"));
    timeline = new Timeline(store, 4L);
    // 创世行：让 (main, 1) 有 head，命令才有 base 可落
    append(ref("main", 1), Optional.empty(), SimosTimestamp.of(0L, "弘光元年"));
    bus = null; // 由各用例按需装配（替身不同）
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  // ── 形态 4：两个注入点各自的判别力 ──

  /**
   * 裁定 32：{@code AdvanceTime} 必须被路由到**注入的那个实现**，且**请求原样转交**（一个字段都不许改）。
   *
   * <p>★ 判别力所在：替身**逐字段比对**收到的命令与发出去的那条**是同一个对象**（`isSameAs`）—— 若 `CommandBus` 在转发路径上重建了一个
   * `AdvanceTime`（哪怕字段相同），这条立刻红。
   */
  @Test
  void advanceTimeIsRoutedToInjectedRouteWithRequestUnchanged() {
    AtomicReference<AdvanceTime> received = new AtomicReference<>();
    AdvanceTime command = advance("main", 1, 0L, 10L);
    bus =
        new CommandBus(
            timeline,
            registry(handler("unit.RenameUnit")),
            cmd -> {
              received.set(cmd);
              return new CommandResult.Committed(new StateRef(cmd.branch(), new RevisionId(99)));
            },
            ref -> STUB_STATE);

    CommandResult result = bus.submit(command);

    assertThat(received.get()).isSameAs(command);
    assertThat(result)
        .isEqualTo(new CommandResult.Committed(new StateRef(main(), new RevisionId(99))));
    // ★ 推进支**不落任何行**：本任务里它的目标在替身手里，CommandBus 不该自作主张写库
    assertThat(timeline.head(main())).contains(new RevisionId(1));
  }

  /**
   * 裁定 34：{@code StateLoader} 要被**以确切的坐标**问过，且它返回的状态**就是**交给 handler 的那个。
   *
   * <p>★ 坐标钉的是 {@code (branch, head)}——**当前 tip**，不是信封上的 {@code expectedRevision}。 本用例故意让两者**都等于
   * 1**（无法区分）**再补一条让它们分叉的场景**是不行的：入口检查要求二者相等。 ⇒ 改用**另一条更硬的断言**——loader 拿到的坐标必须是 head 的**真实值**（用例把
   * head 推到 2，信封写 2， 若实现传的是常量或信封原值，`isSameAs` 那侧仍会过，但**下一次** `ref` 比对会因 branch 不符而红）。
   */
  @Test
  void stateLoaderIsAskedForTheCurrentTipAndItsStateReachesTheHandler() {
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(5L));
    AtomicReference<StateRef> asked = new AtomicReference<>();
    bus =
        new CommandBus(
            timeline,
            registry(capturingHandler("unit.RenameUnit", new HandlerOutcome.Rejected("到此为止"))),
            cmd -> new CommandResult.Rejected("不该走到这里"),
            ref -> {
              asked.set(ref);
              return STUB_STATE;
            });

    bus.submit(envelope("main", 2, "unit.RenameUnit", "{}"));

    assertThat(asked.get()).isEqualTo(ref("main", 2));
    assertThat(asked.get().branch()).isEqualTo(main());
    assertThat(seenState.get()).isSameAs(STUB_STATE);
  }

  // ── R11：载荷逐字节转交 ──

  /**
   * R11：handler 收到的 {@code payloadJson} 与信封里的**逐字节相同**。
   *
   * <p>★ 夹具的先决条件当场自证：载荷**必须**与它的 {@code trim()} 不同，否则 m1（在分派路径上 trim）不会红， 这条用例就是装饰。同理键序是 {@code z,
   * a}（非字典序），re-serialize 会打乱。
   */
  @Test
  void payloadJsonIsForwardedByteForByte() {
    assertThat(DISCRIMINATING_PAYLOAD.trim())
        .as("夹具判别力：载荷必须带首尾空白，否则 trim 变异不会红")
        .isNotEqualTo(DISCRIMINATING_PAYLOAD);
    assertThat(DISCRIMINATING_PAYLOAD)
        .as("夹具判别力：键序必须非字典序，否则 re-serialize 变异不会红")
        .startsWith("  {\"z\":1");

    bus =
        new CommandBus(
            timeline,
            registry(capturingHandler("unit.RenameUnit", new HandlerOutcome.Rejected("到此为止"))),
            cmd -> new CommandResult.Rejected("不该走到这里"),
            ref -> STUB_STATE);

    bus.submit(envelope("main", 1, "unit.RenameUnit", DISCRIMINATING_PAYLOAD));

    assertThat(seenPayload.get()).isEqualTo(DISCRIMINATING_PAYLOAD);
  }

  // ── ① 入口检查 ──

  /**
   * ① C17 第一处：head 不等于 {@code expectedRevision} ⇒ {@link CommandResult.Conflict}，且报的是**真实 head**。
   */
  @Test
  void staleExpectedRevisionConflictsWithRealHead() {
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(5L));
    bus = busWith(handler("unit.RenameUnit"));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isEqualTo(new CommandResult.Conflict(ref("main", 2)));
  }

  /** 分支不存在 ⇒ **拒绝**而不是冲突：没有 head 可报，编一个坐标出来才是错的。 */
  @Test
  void missingBranchIsRejectedNotConflicted() {
    bus = busWith(handler("unit.RenameUnit"));

    CommandResult result = bus.submit(envelope("ghost", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("分支不存在");
  }

  /** 未注册的 type ⇒ 拒绝，且**不碰库、不调 loader**（先查内存再查库的次序是有意的）。 */
  @Test
  void unregisteredTypeIsRejectedWithoutTouchingStoreOrLoader() {
    AtomicReference<StateRef> asked = new AtomicReference<>();
    bus =
        new CommandBus(
            timeline,
            registry(handler("unit.RenameUnit")),
            cmd -> new CommandResult.Rejected("不该走到这里"),
            ref -> {
              asked.set(ref);
              return STUB_STATE;
            });

    CommandResult result = bus.submit(envelope("main", 1, "social.Populate", "{}"));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("未注册的命令类型");
    assertThat(asked.get()).isNull();
  }

  // ── ② + ④：Applied 落行 ──

  /** handler 拒绝 ⇒ {@link CommandResult.Rejected} 带原理由，且**不落任何行**（revision 必须还是 1）。 */
  @Test
  void rejectedHandlerWritesNoRow() {
    bus = busWith(capturingHandler("unit.RenameUnit", new HandlerOutcome.Rejected("名字空白")));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isEqualTo(new CommandResult.Rejected("名字空白"));
    assertThat(timeline.head(main())).contains(new RevisionId(1));
  }

  /**
   * ④：{@code Applied} 落成**一行** revision，四件事同时钉住——坐标 = head+1、parent = base、 身份三件套原样落地、变更集按 type 的
   * namespace 装成**单键** {@link WorldChangeSet}（裁定 37）。
   */
  @Test
  void appliedChangeSetBecomesOneRevisionRowUnderTheTypeNamespace() {
    bus =
        busWith(
            capturingHandler("unit.RenameUnit", new HandlerOutcome.Applied(new ToyChangeSet(42))));

    CommandResult result = bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    assertThat(result).isEqualTo(new CommandResult.Committed(ref("main", 2)));
    RevisionRow row = timeline.row(ref("main", 2)).orElseThrow();
    assertThat(row.parent()).contains(ref("main", 1));
    assertThat(row.commandId()).isEqualTo("cmd-1");
    assertThat(row.correlationId()).isEqualTo("corr-1");
    assertThat(row.initiator()).isEqualTo("player:local");
    assertThat(row.commandType()).isEqualTo("unit.RenameUnit");

    WorldChangeSet changeset = Timeline.readChangeSet(row.changesetJson());
    assertThat(changeset.modules()).containsOnlyKeys("unit");
    assertThat(changeset.modules().get("unit")).isEqualTo(new ToyChangeSet(42));
  }

  /** 裁定 35：领域命令落的行**继承父行的时刻**（信封不带时刻字段；只有时间推进改时钟）。 */
  @Test
  void committedRevisionInheritsParentTimestamp() {
    bus =
        busWith(
            capturingHandler("unit.RenameUnit", new HandlerOutcome.Applied(new ToyChangeSet(1))));

    bus.submit(envelope("main", 1, "unit.RenameUnit", "{}"));

    RevisionRow genesis = timeline.row(ref("main", 1)).orElseThrow();
    RevisionRow committed = timeline.row(ref("main", 2)).orElseThrow();
    assertThat(committed.timestamp()).isEqualTo(genesis.timestamp());
    assertThat(committed.timestamp().calendarLabel()).contains("弘光元年");
  }

  // ── ForkBranch 支 ──

  /** 分岔成功 ⇒ 新坐标 `(newBranch, 1)`；分岔行的变更集是空集（C13），父指向源分支的 expected。 */
  @Test
  void forkBranchCommitsNewBranchAtRevisionOne() {
    bus = busWith(handler("unit.RenameUnit"));

    CommandResult result =
        bus.submit(
            new ForkBranch(
                "cmd-f",
                "corr-f",
                "player:local",
                main(),
                new RevisionId(1),
                new BranchId("side")));

    assertThat(result).isEqualTo(new CommandResult.Committed(ref("side", 1)));
    RevisionRow forkRow = timeline.row(ref("side", 1)).orElseThrow();
    assertThat(forkRow.parent()).contains(ref("main", 1));
    assertThat(Timeline.readChangeSet(forkRow.changesetJson()).modules()).isEmpty();
  }

  /** 分岔的期望值过期 ⇒ 冲突并报源分支的**真实 head**。 */
  @Test
  void forkBranchWithStaleExpectedConflictsWithSourceHead() {
    append(ref("main", 2), Optional.of(ref("main", 1)), SimosTimestamp.of(5L));
    bus = busWith(handler("unit.RenameUnit"));

    CommandResult result =
        bus.submit(
            new ForkBranch(
                "cmd-f",
                "corr-f",
                "player:local",
                main(),
                new RevisionId(1),
                new BranchId("side")));

    assertThat(result).isEqualTo(new CommandResult.Conflict(ref("main", 2)));
    assertThat(timeline.head(new BranchId("side"))).isEmpty();
  }

  /** 源分支不存在 ⇒ 拒绝（没有 head 可报）。 */
  @Test
  void forkBranchFromMissingSourceIsRejected() {
    bus = busWith(handler("unit.RenameUnit"));

    CommandResult result =
        bus.submit(
            new ForkBranch(
                "cmd-f",
                "corr-f",
                "player:local",
                new BranchId("ghost"),
                new RevisionId(1),
                new BranchId("side")));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("分支不存在");
  }

  // ── 封闭集兜底 ──

  /** {@code Command} 是 util 的**开放**接口 ⇒ 三条之外必须当场炸，不能静默吞掉（C16 的封闭性由这条守住）。 */
  @Test
  void commandOutsideTheClosedSetThrows() {
    bus = busWith(handler("unit.RenameUnit"));
    Command alien =
        new Command() {
          @Override
          public RevisionId expectedRevision() {
            return new RevisionId(1);
          }
        };

    assertThatThrownBy(() -> bus.submit(alien))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知命令类型");
  }

  // ── 夹具 ──

  private CommandBus busWith(CommandHandler handler) {
    return new CommandBus(
        timeline,
        registry(handler),
        cmd -> new CommandResult.Rejected("不该走到这里"),
        ref -> STUB_STATE);
  }

  private CommandRegistry registry(CommandHandler handler) {
    return new CommandRegistry(List.of(handler));
  }

  /** 记录载荷与状态的替身 handler：**只**记录，结局由调用方给。 */
  private CommandHandler capturingHandler(String type, HandlerOutcome outcome) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        seenPayload.set(payloadJson);
        seenState.set(state);
        return outcome;
      }
    };
  }

  /** 不记录的替身 handler。 */
  private static CommandHandler handler(String type) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        throw new IllegalStateException("本用例不该跑到 handler: " + type);
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

  private static AdvanceTime advance(String branch, long expected, long from, long to) {
    return new AdvanceTime(
        "cmd-a",
        "corr-a",
        "player:local",
        new BranchId(branch),
        new RevisionId(expected),
        new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to))));
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
