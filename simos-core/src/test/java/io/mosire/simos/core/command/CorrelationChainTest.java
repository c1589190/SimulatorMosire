package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * **R6（判据二）**：一次成功的推进，{@code correlation_id = X} 的事件行**恰好**是 {@code received + started +
 * N×module.proposal + finished + committed}，且 {@code revisions} **恰 1 行**。
 *
 * <p>★★ **本用例把"真代码"与"机制"分得很清楚，因为混起来会变成同义反复**：序列的**真正生产者是 Task 12 的 {@code TimeAdvance}`**，Task 11
 * 手里没有它（当前 {@link AdvanceRoute} 是注入的替身）。若拿替身 route 写一遍序列再读回来， 验的只是"我写进去的能读出来"——**那不是判据二**。故：
 *
 * <ul>
 *   <li>{@link #successfulCommandLeavesReceivedAndCommittedUnderItsCorrelationId}：**真代码路径**——
 *       {@link CommandBus} 的信封支。{@code received} 与 {@code committed} 由**本任务写的**代码产出。
 *   <li>{@link #failedRevisionInsertLeavesNoEventRowsBehind}：**真的原子性守卫**——见其方法注。
 *   <li>{@link #advanceSequenceShapeIsWhatR6Requires}：**机制级**，替身 route 产出序列，钉的是"落盘 + 排序 + 一条 SQL
 *       追全链"这条**通路**，以及把 R6 的**冻结形状**写成一个 Task 12 必须满足的目标。 ★ **它不证明真 route 会产出该序列**——那条在 Task
 *       12/15。
 * </ul>
 *
 * <p>★★ **两个 id 必须不同**：C22 说单命令链**缺省** {@code correlationId == commandId}。若用例图省事让它们相等， "把事件的
 * correlationId 写成 commandId"那个变异（m1）就**恒绿**——因为它俩本来就一样。这是计划 Step 5 明写的坑。
 */
class CorrelationChainTest {

  /** 玩具变更集（test 侧，R1 不计）。 */
  record ToyChangeSet(int v) implements ChangeSet {}

  private static final SimulationState STUB_STATE =
      new SimulationState(
          new StateMeta(
              new StateRef(new BranchId("stub"), new RevisionId(1)), SimosTimestamp.of(7L)),
          Map.of(),
          InMemoryInfoSystem.empty());

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private EventStore events;

  @BeforeEach
  void openStore() {
    store = SqliteStore.open(tempDir.resolve("chain.db"));
    timeline = new Timeline(store, 4L);
    events = new EventStore(store);
    appendSeed(ref("main", 1), Optional.empty(), "corr-seed");
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /**
   * **真代码路径**：一条走 {@link CommandBus} 的成功命令 ⇒ 该 correlationId 下**恰好** {@code received + committed}
   * 两条事件、{@code revisions} **恰 1 行**，且两条事件的 correlationId **与信封逐字节相同**。
   */
  @Test
  void successfulCommandLeavesReceivedAndCommittedUnderItsCorrelationId() {
    String commandId = "cmd-1";
    String correlationId = "corr-1"; // ★ 故意 != commandId：否则 m1 恒绿（见类注）
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));

    CommandResult result = bus.submit(envelope(commandId, correlationId, 1L));

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    StateRef committed = ((CommandResult.Committed) result).ref();

    List<EventRow> chain = events.byCorrelation(correlationId);
    assertThat(chain)
        .as("该 correlationId 下必须**恰** received + committed 两条，顺序也是它俩；实得 %s", chain)
        .hasSize(2);
    assertThat(chain.stream().map(EventRow::type).toList())
        .as("类型序列必须逐条（不是数个数——数个数对顺序错与张冠李戴零判别力）")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_COMMITTED);
    assertThat(chain)
        .as("★ 两条事件的 correlationId 必须**逐字节**是信封上那个，不是 commandId（判据二的全部）")
        .allSatisfy(row -> assertThat(row.correlationId()).isEqualTo(correlationId));
    assertThat(chain)
        .as("agent 存 initiator 原文（C21）")
        .allSatisfy(row -> assertThat(row.agent()).isEqualTo("player:local"));
    assertThat(chain.get(1).payload())
        .as("committed 的载荷要带新坐标，从事件就能直接跳到 revision")
        .contains(committed.branch().value())
        .contains(Long.toString(committed.revision().value()));

    assertThat(timeline.byCorrelation(correlationId))
        .as("revisions 表在该 correlationId 下**恰 1 行**（R6 的第二张表）")
        .hasSize(1);
  }

  /**
   * ★★ **原子性守卫**（spec §〇.3 第 2 条：revision 行与**全部**事件行必须同一个事务）。
   *
   * <p>手法：**让事务在写完事件之后、提交之前失败**——用一个主键冲突的 revision 行（同一个 {@code (branch, revision)} 写第二次）触发 {@code
   * SQLITE_CONSTRAINT_PRIMARYKEY}。事务回滚后，**那批事件行必须一行不剩**。
   *
   * <p>★ 这条守卫的判别力在于**它只对"同事务"成立**：若谁把事件写入挪到事务外（计划 Step 5 的 m2 就是打这个）， 回滚只回掉 revision
   * 行，事件行**会留下来**，本断言当场红。**没有它，"同一事务"这句话是装饰。**
   */
  @Test
  void failedRevisionInsertLeavesNoEventRowsBehind() {
    long before = events.count();
    // 种子已占掉 (main, 1)：再写一次同坐标必撞 PRIMARY KEY (branch, revision)
    RevisionRow duplicate = revisionRow(ref("main", 1), Optional.empty(), "cmd-dup", "corr-dup");
    List<EventRow> doomed =
        List.of(EventRow.of(EventTypes.COMMAND_RECEIVED, "player:local", "{}", "corr-dup"));

    assertThatThrownBy(() -> timeline.appendRevision(duplicate, doomed))
        .as("同坐标重复写入必须被库拒绝（R2/R13 的形态）")
        .isInstanceOf(IllegalStateException.class);

    assertThat(events.count())
        .as("★ 事务回滚后**事件行一行不剩**。若留下了一条，说明事件写入不在 revision 的那个事务里")
        .isEqualTo(before);
    assertThat(events.byCorrelation("corr-dup")).as("被回滚的链路不该留下任何痕迹").isEmpty();
  }

  /**
   * ★★ **原子性守卫的另一半**：让**事件**写失败 ⇒ **revision 行也不留**。
   *
   * <p>★★ **为什么必须有这一半**（想清楚才发现上面那条单打独斗是**不完整的**）：上面那条的失败点在 **revision 写**。若谁把 {@link
   * Timeline#appendRevision(RevisionRow, List)} 拆成两个事务、且**先写 revision 后写事件**， 上面那条**照样绿**——revision
   * 先炸，事件压根没轮到写，"事件一行不剩"当然成立。**那是假绿。**
   *
   * <p>⇒ **两条合起来才闭合**：本条的失败点在**事件写**，上一条的失败点在 **revision 写**。 任何"拆成两个事务"的实现，无论哪个先写，**必落在其中一条上变红**：
   *
   * <ul>
   *   <li>拆成"先 revision 后事件" ⇒ 本条红（revision 已提交，事件才炸，残行留下了）。
   *   <li>拆成"先事件后 revision" ⇒ 上一条红（事件已提交，revision 才炸）。
   * </ul>
   *
   * <p>手法：事件清单里塞一个 {@code null}（{@code Arrays.asList} 才收 null，{@code List.of} 不收），
   * 且**放在第二位**——好让第一位那条**已经写进库**，从而真正考验"同一事务内的前一条写入会不会跟着回滚"。 抛的是 {@code NullPointerException}，被
   * {@code SqliteStore.inTransaction} 的 {@code catch (Exception)} 收口（源码实测：非 SQL 的运行时异常一样触发回滚）。
   */
  @Test
  void failedEventInsertLeavesNoRevisionRowBehind() {
    StateRef target = ref("main", 2);
    List<EventRow> poisoned =
        Arrays.asList(
            EventRow.of(EventTypes.COMMAND_RECEIVED, "player:local", "{}", "corr-poisoned"), null);

    assertThatThrownBy(
            () ->
                timeline.appendRevision(
                    revisionRow(
                        target, Optional.of(ref("main", 1)), "cmd-poisoned", "corr-poisoned"),
                    poisoned))
        .as("事件写里的异常必须触发整事务回滚")
        .isInstanceOf(IllegalStateException.class);

    assertThat(timeline.row(target))
        .as("★ 事件写失败 ⇒ revision 行**也不留**。若留下了，说明两处写入不在同一个事务里")
        .isEmpty();
    assertThat(events.byCorrelation("corr-poisoned")).as("先写进去的那条事件也必须被回滚掉").isEmpty();
  }

  /** 不同 correlationId 的链路**不互相污染**：判据二那条 SQL 的 `WHERE` 必须真的在筛。 */
  @Test
  void eventsUnderDifferentCorrelationIdsDoNotMix() {
    CommandBus bus = busReturning(new HandlerOutcome.Applied(new ToyChangeSet(1)));
    bus.submit(envelope("cmd-a", "corr-a", 1L));
    bus.submit(envelope("cmd-b", "corr-b", 2L));

    assertThat(
            events.byCorrelation("corr-a").stream()
                .map(EventRow::correlationId)
                .distinct()
                .toList())
        .containsExactly("corr-a");
    assertThat(
            events.byCorrelation("corr-b").stream()
                .map(EventRow::correlationId)
                .distinct()
                .toList())
        .containsExactly("corr-b");
    assertThat(events.count()).as("两条命令各 2 条事件").isEqualTo(4L);
  }

  /** 被拒的命令也留链：{@code received + rejected}，且**不留 revision 行**（拒绝必须是原子的，R9 的形态）。 */
  @Test
  void rejectedCommandStillLeavesItsChainAndNoRevision() {
    CommandBus bus = busReturning(new HandlerOutcome.Rejected("领域侧拒绝"));
    long revisionsBefore = timeline.byCorrelation("corr-rej").size();

    assertThat(bus.submit(envelope("cmd-rej", "corr-rej", 1L)))
        .isInstanceOf(CommandResult.Rejected.class);

    assertThat(events.byCorrelation("corr-rej").stream().map(EventRow::type).toList())
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_REJECTED);
    assertThat(timeline.byCorrelation("corr-rej"))
        .as("被拒 ⇒ 不留 revision 行")
        .hasSize((int) revisionsBefore);
  }

  /**
   * **机制级，不是端到端**（见类注）：替身 route 产出 R6 的冻结序列 ⇒ 全部事件行与 revision 行**落盘后可被 一条 SQL 完整追回**，且**按 {@code
   * seq} 升序**。
   *
   * <p>★ 它把 R6 的形状写成一个 Task 12 必须满足的目标，并钉住"落盘 + 排序 + 追全链"这条通路； **它不证明真 {@code TimeAdvance}
   * 会产出这个序列**。N = 2（两个替身参与者）。
   */
  @Test
  void advanceSequenceShapeIsWhatR6Requires() {
    String commandId = "cmd-adv";
    String correlationId = "corr-adv";
    List<String> namespaces = List.of("map", "unit");

    AdvanceRoute standIn =
        cmd -> {
          List<EventRow> chain = new ArrayList<>();
          chain.add(
              EventRow.of(EventTypes.COMMAND_RECEIVED, cmd.initiator(), "{}", cmd.correlationId()));
          chain.add(
              EventRow.of(
                  EventTypes.TIME_ADVANCE_STARTED, cmd.initiator(), "{}", cmd.correlationId()));
          for (String namespace : namespaces) {
            chain.add(
                EventRow.of(
                    EventTypes.MODULE_PROPOSAL,
                    cmd.initiator(),
                    "{\"namespace\":\"" + namespace + "\"}",
                    cmd.correlationId()));
          }
          chain.add(
              EventRow.of(
                  EventTypes.TIME_ADVANCE_FINISHED, cmd.initiator(), "{}", cmd.correlationId()));
          StateRef target = ref(cmd.branch().value(), cmd.expectedRevision().value() + 1);
          chain.add(
              EventRow.of(
                  EventTypes.COMMAND_COMMITTED,
                  cmd.initiator(),
                  "{\"revision\":\"" + target.revision().value() + "\"}",
                  cmd.correlationId()));
          RevisionRow row =
              revisionRow(
                  target,
                  Optional.of(new StateRef(cmd.branch(), cmd.expectedRevision())),
                  cmd.commandId(),
                  cmd.correlationId());
          timeline.appendRevision(row, chain);
          return new CommandResult.Committed(target);
        };
    CommandBus bus =
        new CommandBus(
            timeline,
            new CommandRegistry(List.of(passthroughHandler())),
            standIn,
            ref -> STUB_STATE);

    CommandResult result =
        bus.submit(
            new AdvanceTime(
                commandId,
                correlationId,
                "player:local",
                main(),
                new RevisionId(1L),
                new TimeRange(SimosTimestamp.of(1L), Optional.of(SimosTimestamp.of(2L)))));

    assertThat(result).isInstanceOf(CommandResult.Committed.class);

    List<String> types = events.byCorrelation(correlationId).stream().map(EventRow::type).toList();
    assertThat(types)
        .as("R6 的冻结序列：1×received + 1×started + N×proposal + 1×finished + 1×committed（N = 参与者数）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
    assertThat(timeline.byCorrelation(correlationId)).as("R6 的第二张表：revisions 恰 1 行").hasSize(1);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  private CommandBus busReturning(HandlerOutcome outcome) {
    CommandHandler handler =
        new CommandHandler() {
          @Override
          public String type() {
            return "unit.RenameUnit";
          }

          @Override
          public HandlerOutcome handle(SimulationState state, String payloadJson) {
            return outcome;
          }
        };
    return new CommandBus(
        timeline,
        new CommandRegistry(List.of(handler)),
        cmd -> {
          throw new IllegalStateException("本用例不该走到推进支");
        },
        ref -> STUB_STATE);
  }

  private static CommandHandler passthroughHandler() {
    return new CommandHandler() {
      @Override
      public String type() {
        return "unit.RenameUnit";
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Rejected("本用例不走 handler");
      }
    };
  }

  private static CommandEnvelope envelope(String commandId, String correlationId, long expected) {
    return new CommandEnvelope(
        commandId,
        correlationId,
        "player:local",
        main(),
        new RevisionId(expected),
        "unit.RenameUnit",
        "{}");
  }

  private void appendSeed(StateRef target, Optional<StateRef> parent, String correlationId) {
    timeline.appendRevision(revisionRow(target, parent, "cmd-seed", correlationId));
  }

  private static RevisionRow revisionRow(
      StateRef target, Optional<StateRef> parent, String commandId, String correlationId) {
    return new RevisionRow(
        target.branch(),
        target.revision(),
        parent,
        SimosTimestamp.of(target.revision().value()),
        commandId,
        correlationId,
        "player:local",
        "core.AdvanceTime",
        Timeline.changeSetJson(WorldChangeSet.empty()));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
