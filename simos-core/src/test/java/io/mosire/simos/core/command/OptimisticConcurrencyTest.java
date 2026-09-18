package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * **R7（判据三）**：乐观并发的两处检查（C17）在**真实并发**下确实是两处，而不是摆设。
 *
 * <p>★ **本用例验的是 ③「锁内复查」，不是 ①「入口检查」**——这两者的分工是 C17 的全部内容。 只有入口检查 ⇒ 两个线程都通过、都提交，
 * **最后写的赢**，乐观并发形同虚设；只在提交时检查 ⇒ 慢命令白跑一趟。
 *
 * <p>★★ **`CyclicBarrier` 是这条用例能成立的全部关键**（计划 Step 2 原话）：没有它， 线程 A 可能在 B 进入之前就提交完了，B
 * 在**入口检查**就被挡下——那样验的是 ①，不是 ③。 故屏障放在**替身 handler 里** （即 ① 与 ③ 之间）：两个线程**都越过入口检查之后**才被放行。
 *
 * <p>★★ **屏障必须带超时，否则本用例在 m3 下会挂死而不是判红**：m3 把 handler 挪进锁内 ⇒ A 持锁进 handler 等屏障，B 卡在锁上进不来。`await`
 * 无超时就是死锁；带超时则会走完并让 「同时进入数」那条自证**干净地判红**。这正是 m3 唯一能被抓到的地方。
 *
 * <p>★★ **「同时进入数」这条自证不能省**（计划 Step 2 原话："没有这条自证的 R7 就是装饰"）：
 * 本用例可能"通过"了却**从来没并发过**——那是**假绿**。故两次断言都要： ① 每一轮两个线程都真的到达过屏障；② 至少观察到 2 个线程同时在 handler 里。
 */
class OptimisticConcurrencyTest {

  /** 玩具变更集（test 侧，R1 不计）：只需能被 {@code Timeline.changeSetJson} 带上类型信息落盘。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  /** 计划 Step 2 定的轮数。 */
  private static final int K = 50;

  /** 屏障超时：正常路径下屏障即刻开闸（不花钱），只有被串行化时才等到超时。 */
  private static final long BARRIER_TIMEOUT_SECONDS = 2L;

  private static final SimulationState STUB_STATE =
      new SimulationState(
          new StateMeta(
              new StateRef(new BranchId("stub"), new RevisionId(1)), SimosTimestamp.of(7L)),
          Map.of(),
          InMemoryInfoSystem.empty());

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;

  // ── 形态 1 的自证夹具：这三个数是"这次真的并发过"的唯一凭据 ──

  /** 当前正在 handler 里的线程数。 */
  private final AtomicInteger insideHandler = new AtomicInteger();

  /** 观察到的**最大同时进入数**——必须 ≥ 2，否则整个用例是"从来没并发过"的假绿。 */
  private final AtomicInteger maxSimultaneous = new AtomicInteger();

  /** 成功越过屏障的**次数**——必须 == 2 × K（每轮两个线程各一次）。 */
  private final AtomicInteger barrierPasses = new AtomicInteger();

  @BeforeEach
  void openBus() {
    store = SqliteStore.open(tempDir.resolve("optimistic.db"));
    timeline = new Timeline(store, 4L);
    append(ref("main", 1), Optional.empty());
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /**
   * **R7 本体**：K 轮，每轮两个线程拿**同一个** `expectedRevision` 提交，且都被屏障放到 ① 与 ③ 之间。
   *
   * <p>每轮必须**恰一个 `Committed`、恰一个 `Conflict`**，且败者看到的 `current` **就是**胜者的新 ref。
   */
  @Test
  void twoThreadsWithTheSameExpectedRevisionProduceExactlyOneCommitAndOneConflict()
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < K; round++) {
        long expected = timeline.head(main()).orElseThrow().value();
        // ★ 屏障**每轮新建**：某一轮被超时打破后不会污染下一轮（CyclicBarrier 一旦 broken 会让同代的其他方立刻抛）
        CyclicBarrier barrier = new CyclicBarrier(2);
        CommandBus bus =
            new CommandBus(
                timeline,
                registry(racingHandler(barrier)),
                cmd -> {
                  throw new IllegalStateException("本用例不该走到推进支");
                },
                ref -> STUB_STATE);

        CommandEnvelope first = envelope("cmd-a", "corr-a", expected);
        CommandEnvelope second = envelope("cmd-b", "corr-b", expected);

        Future<CommandResult> one = pool.submit(() -> bus.submit(first));
        Future<CommandResult> two = pool.submit(() -> bus.submit(second));
        CommandResult resultOne = one.get(30, TimeUnit.SECONDS);
        CommandResult resultTwo = two.get(30, TimeUnit.SECONDS);

        List<CommandResult> results = new ArrayList<>(List.of(resultOne, resultTwo));
        List<CommandResult> committed =
            results.stream().filter(r -> r instanceof CommandResult.Committed).toList();
        List<CommandResult> conflicted =
            results.stream().filter(r -> r instanceof CommandResult.Conflict).toList();

        String prefix = "第 " + round + " 轮（expectedRevision = " + expected + "）：";
        assertThat(committed)
            .as(prefix + "必须**恰一个** Committed（两个都提交 = ③ 锁内复查不存在，" + "乐观并发形同虚设，C17）；实得 %s", results)
            .hasSize(1);
        assertThat(conflicted)
            .as(prefix + "必须**恰一个** Conflict（C17 的锁内复查要把后到者折成冲突，而不是让他也写进去）；实得 %s", results)
            .hasSize(1);

        StateRef winner = ((CommandResult.Committed) committed.get(0)).ref();
        StateRef loserSawCurrent = ((CommandResult.Conflict) conflicted.get(0)).current();
        assertThat(winner.revision())
            .as(
                prefix
                    + "胜者的新 ref 必须**恰是** expected + 1——这条算式（Task 9 报告的硬接缝："
                    + "并发下两提交会算出同一个 revision 号）由 ③ 复查过的 head 提供 base，故一轮只走一步")
            .isEqualTo(new RevisionId(expected + 1));
        assertThat(loserSawCurrent)
            .as(prefix + "败者看到的 current 必须**就是**胜者的新 ref（而不是它自己以为的那个）")
            .isEqualTo(winner);
        assertThat(timeline.head(main()))
            .as(prefix + "库里的 head 必须落在胜者的 ref 上，且只前进一步")
            .contains(winner.revision());

        // 下一轮的 expectedRevision：就是本轮胜者写下的那个，下一轮再来一次
      }
    } finally {
      pool.shutdownNow();
    }

    // ── 形态 1 的自证：没有这两条，上面那 50 轮可能是"从来没并发过"的假绿 ──
    assertThat(barrierPasses.get())
        .as(
            "自证①：每一轮两个线程都必须**真的到达过屏障**（%d 轮 × 2 = %d 次）。"
                + "实得 %d 次 ⇒ 有轮次被串行化了（handler 若被锁罩住，A 等屏障、B 进不来）",
            K, 2 * K, barrierPasses.get())
        .isEqualTo(2 * K);
    assertThat(maxSimultaneous.get())
        .as("自证②：必须观察到**至少 2 个线程同时在 handler 里**，否则 R7 验的不是并发")
        .isGreaterThanOrEqualTo(2);
  }

  /**
   * 替身 handler：进 handler 计数 → 在屏障上对齐 → 吐一个非空变更集。
   *
   * <p>★ **屏障超时不许把异常往外抛**：超时正是"被串行化"的**信号**，要让它走到自证断言上去判红， 而不是变成一条看不出所以然的
   * `TimeoutException`。故此处吞掉它，只让计数器说话。
   */
  private CommandHandler racingHandler(CyclicBarrier barrier) {
    return new CommandHandler() {
      @Override
      public String type() {
        return "unit.RenameUnit";
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        int now = insideHandler.incrementAndGet();
        maxSimultaneous.accumulateAndGet(now, Math::max);
        try {
          barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          barrierPasses.incrementAndGet();
        } catch (TimeoutException | BrokenBarrierException e) {
          // 刻意吞掉：这是"被串行化"的信号，交给自己证断言去判红（见方法注）
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          insideHandler.decrementAndGet();
        }
        return new HandlerOutcome.Applied(new ToyChangeSet(now));
      }
    };
  }

  private CommandRegistry registry(CommandHandler handler) {
    return new CommandRegistry(List.of(handler));
  }

  private static CommandEnvelope envelope(String commandId, String correlationId, long expected) {
    return new CommandEnvelope(
        commandId,
        correlationId,
        "player:local",
        new BranchId("main"),
        new RevisionId(expected),
        "unit.RenameUnit",
        "{}");
  }

  private void append(StateRef target, Optional<StateRef> parent) {
    timeline.appendRevision(
        new RevisionRow(
            target.branch(),
            target.revision(),
            parent,
            SimosTimestamp.of(target.revision().value()),
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
