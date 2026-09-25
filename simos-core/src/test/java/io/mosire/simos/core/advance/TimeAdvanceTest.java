package io.mosire.simos.core.advance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.StateLoader;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.Envelope;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 两阶段推进六步的护栏（spec §5.1 / §5.3 / §5.4）——**R9 / R10 / R14**，外加 R6 的**真 route**端到端。
 *
 * <p>★★ **本用例补上 Task 11 欠的那一笔**：Task 11 的 {@code advanceSequenceShapeIsWhatR6Requires} 用的是**替身
 * route**，只证明了"落盘 + 排序 + 一条 SQL 追全链"这条通路，**不证明真的 {@code TimeAdvance} 会产出那个序列** （台账"给 Task 12 的三条"第
 * 3 条，且明写"在此之前不许引用它当证据"）。{@link #realRouteProducesTheFrozenR6Sequence} 跑的是**真 route**、**真
 * store**、**真事件表**。
 *
 * <p>★ 参与者与 codec 都是假的（{@link ToyParticipant} / {@link ToyCodec}）：Core 的推进管线**不认识领域类型**，
 * 用真模块只会让用例依赖三个模块的编译期（ADR-1 明令 core 的 main/test scope 都不许看见模块类型）。这不是偷懒——
 * 六步里**没有任何一步**与领域语义有关，全是机械的。
 */
class TimeAdvanceTest {

  /** 玩具变更集（test 侧，R1 的"恰 4 个实现者"只数 main 源码）。 */
  private record ToyChangeSet(int v) implements ChangeSet {}

  /** 玩具快照：把 {@code ref}/{@code timestamp} 真的带在身上，好让"坐标有没有被填对"可观测。 */
  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int v)
      implements Snapshot {}

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  /** 可配置的假 codec：三个失败开关各自对应 Validate 的一项。 */
  private static final class ToyCodec implements ModuleCodec {

    private final String namespace;
    private boolean throwOnEncodeChangeSet;
    private boolean throwOnApply;

    /** ★ **最常见的那个错**：{@code apply} 照抄 base 的坐标，不填 Core 给的新坐标（Validate 第 4 项拦它）。 */
    private boolean copyBaseMeta;

    ToyCodec(String namespace) {
      this.namespace = namespace;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public ChangeSet decodeChangeSet(String json) {
      return new ToyChangeSet(Integer.parseInt(json));
    }

    @Override
    public String encodeChangeSet(ChangeSet changeSet) {
      if (throwOnEncodeChangeSet) {
        throw new IllegalStateException("encodeChangeSet 故意失败");
      }
      return Integer.toString(((ToyChangeSet) changeSet).v());
    }

    @Override
    public Snapshot decodeSnapshot(String json) {
      String[] parts = json.split("\\|");
      return new ToySnapshot(
          new StateRef(new BranchId(parts[2]), new RevisionId(Long.parseLong(parts[3]))),
          SimosTimestamp.of(Long.parseLong(parts[4])),
          parts[0],
          Integer.parseInt(parts[1]));
    }

    @Override
    public String encodeSnapshot(Snapshot snapshot) {
      ToySnapshot toy = (ToySnapshot) snapshot;
      return String.join(
          "|",
          toy.namespace(),
          Integer.toString(toy.v()),
          toy.ref().branch().value(),
          Long.toString(toy.ref().revision().value()),
          Long.toString(toy.timestamp().tick()));
    }

    @Override
    public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
      if (throwOnApply) {
        throw new IllegalStateException("apply 故意失败");
      }
      ToySnapshot before = (ToySnapshot) base;
      if (copyBaseMeta) {
        return new ToySnapshot(before.ref(), before.timestamp(), namespace, before.v());
      }
      return new ToySnapshot(
          newMeta.ref(),
          newMeta.timestamp(),
          namespace,
          before.v() + ((ToyChangeSet) changeSet).v());
    }
  }

  private static final class ToyParticipant implements TimeParticipant {

    private final String namespace;
    private final Set<String> reads;
    private final Set<String> writes;
    private int simulateCalls;

    ToyParticipant(String namespace, Set<String> reads, Set<String> writes) {
      this.namespace = namespace;
      this.reads = reads;
      this.writes = writes;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public TimeProposal simulate(SimulationState state, TimeRange range) {
      simulateCalls++;
      return new TimeProposal(namespace, new ToyChangeSet(1), reads, writes);
    }

    @Override
    public String toString() {
      return "ToyParticipant[" + namespace + "]";
    }
  }

  private static ToyParticipant participant(String namespace) {
    return new ToyParticipant(namespace, Set.of(), Set.of());
  }

  /**
   * 多切片参与者（1b-1 的新契约）：一个提案带**多个模块**的变更集。
   *
   * <p>★ **有意不实现 `simulate`**：Core 若哪天改回按单切片入口调参与者，默认实现会抛 {@link UnsupportedOperationException} ⇒
   * 当场红，而不是悄悄只推一个模块。
   */
  private static final class ToyWorldParticipant implements TimeParticipant {

    private final String participantId;
    private final Map<String, ChangeSet> moduleChanges;
    private final Set<String> reads;
    private final Set<String> writes;

    ToyWorldParticipant(
        String participantId,
        Map<String, ChangeSet> moduleChanges,
        Set<String> reads,
        Set<String> writes) {
      this.participantId = participantId;
      this.moduleChanges = moduleChanges;
      this.reads = reads;
      this.writes = writes;
    }

    @Override
    public String namespace() {
      return participantId;
    }

    @Override
    public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
      return new WorldTimeProposal(participantId, moduleChanges, reads, writes);
    }
  }

  /** 保序的模块表（`Map.of` 的迭代序不是内容的纯函数——断言"先 alpha 后 beta"必须自己钉序）。 */
  private static Map<String, ChangeSet> modules(String... namespaces) {
    LinkedHashMap<String, ChangeSet> modules = new LinkedHashMap<>();
    for (String namespace : namespaces) {
      modules.put(namespace, new ToyChangeSet(1));
    }
    return modules;
  }

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private CheckpointStore checkpoints;
  private EventStore events;

  @BeforeEach
  void openStore() {
    store = SqliteStore.open(tempDir.resolve("advance.db"));
    events = new EventStore(store);
    checkpoints = new CheckpointStore(tempDir);
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  // ── R6：真 route 的端到端（Task 11 欠的那一笔）─────────────────────────────────────

  /**
   * ★★ **判据二的真 route 版**：一次成功的时间推进，{@code correlation_id = X} 的行**恰好**是 {@code received + started +
   * N×module.proposal + finished + committed}，且 {@code revisions} **恰 1 行**。
   *
   * <p>★ **参与者按 [beta, alpha] 注册**——事件里必须**先 alpha 后 beta**（C25 / 裁定 44）。这一条同时钉住了
   * "参与者清单不得靠注册表插入序"，而那正是 {@code SimulationState.modules()} 那类 {@code Map.copyOf} 的坑。
   */
  @Test
  void realRouteProducesTheFrozenR6Sequence() {
    seedMain(2L);
    String commandId = "cmd-adv";
    String correlationId = "corr-adv"; // ★ 故意 != commandId（否则"correlationId 写成 commandId"的变异恒绿）
    ToyParticipant beta = participant("beta");
    ToyParticipant alpha = participant("alpha");
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(beta, alpha), // ★ 注册序与字典序**相反**
            ref -> state(1L, 9L, "alpha", "beta"));

    CommandResult result = route.run(advanceCmd(commandId, correlationId, 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref()).isEqualTo(ref("main", 2));

    List<EventRow> chain = events.byCorrelation(correlationId);
    assertThat(chain.stream().map(EventRow::type).toList())
        .as("R6 的冻结序列（1×received + 1×started + N×proposal + 1×finished + 1×committed，N = 参与者数）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
    assertThat(chain)
        .as("★ correlationId 逐字节取自命令（判据二的全部）")
        .allSatisfy(row -> assertThat(row.correlationId()).isEqualTo(correlationId));
    assertThat(chain)
        .as("agent 存 initiator 原文（C21）")
        .allSatisfy(row -> assertThat(row.agent()).isEqualTo("player:local"));
    assertThat(
            chain.stream()
                .filter(row -> row.type().equals(EventTypes.MODULE_PROPOSAL))
                .map(EventRow::payload)
                .toList())
        .as("★ C25：按 namespace 字典序，与注册序 [beta, alpha] 相反")
        .allSatisfy(payload -> {})
        .hasSize(2)
        .satisfies(
            payloads -> {
              assertThat(payloads.get(0)).contains("\"namespace\":\"alpha\"");
              assertThat(payloads.get(1)).contains("\"namespace\":\"beta\"");
            });
    assertThat(timeline.byCorrelation(correlationId)).as("R6 的第二张表：revisions 恰 1 行").hasSize(1);
    assertThat(alpha.simulateCalls).as("两个参与者各被调一次").isEqualTo(1);
    assertThat(beta.simulateCalls).isEqualTo(1);
  }

  /** ★ 参与者清单在**构造期**定死：注册序不同 ⇒ 提案事件序相同（C25 的直接断言）。 */
  @Test
  void participantOrderIsFixedAtConstructionNotByRegistrationOrder() {
    seedMain(2L);
    TimeAdvance forward =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(participant("alpha"), participant("beta")),
            ref -> state(1L, 9L, "alpha", "beta"));
    TimeAdvance reversed =
        route(
            List.of(new ToyCodec("beta"), new ToyCodec("alpha")),
            List.of(participant("beta"), participant("alpha")),
            ref -> state(1L, 9L, "alpha", "beta"));

    forward.run(advanceCmd("cmd-f", "corr-f", 1L, 10L));
    // ★ 第二次推进的期望坐标必须是 **2**：第一次已经把 head 推到 2 了。写 1 会走 ① 的过期检查，
    //   结局是 Conflict **没有 proposal 事件** ⇒ 拿到空列表，与第一次的 [alpha,beta] 一比就红——
    //   而那个红是**夹具错**，不是产品错（本用例首轮实测正是如此，在此留痕以免后人重踩）。
    reversed.run(advanceCmd("cmd-r", "corr-r", 2L, 10L));

    assertThat(namespacesOfProposals("corr-r")).isEqualTo(namespacesOfProposals("corr-f"));
    assertThat(namespacesOfProposals("corr-f")).containsExactly("alpha", "beta");
  }

  // ── ③ Resolve 的结局（R9 / R10）────────────────────────────────────────────────────

  /**
   * **R9**：写-写冲突 ⇒ {@code Rejected}，**拒绝是原子的**——{@code revisions} 一行不留，head 不动， 但事件链完整（{@code
   * received + started + N×proposal + conflict + rejected}）。
   */
  @Test
  void writeWriteConflictIsRejectedAndLeavesNoRevision() {
    seedMain(2L);
    ToyParticipant alpha =
        new ToyParticipant("alpha", Set.of(), linkedSet("shared:x2", "shared:x1"));
    ToyParticipant beta = new ToyParticipant("beta", Set.of(), linkedSet("shared:x1", "shared:x3"));
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(alpha, beta),
            ref -> state(1L, 9L, "alpha", "beta"));

    CommandResult result = route.run(advanceCmd("cmd-ww", "corr-ww", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(timeline.row(ref("main", 2))).as("★ 拒绝 ⇒ 不留 revision 行").isEmpty();
    assertThat(timeline.head(main())).contains(new RevisionId(1L));
    assertThat(types("corr-ww"))
        .as("被拒也要留全链：冲突是**它唯一的痕迹**（C14）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIMELINE_CONFLICT,
            EventTypes.COMMAND_REJECTED);
    JsonNode payload = json(conflictPayload("corr-ww"));
    assertThat(payload.get("kind").asText()).isEqualTo(AdvanceConflict.WRITE_WRITE);
    assertThat(payload.get("namespaces").toString()).isEqualTo("[\"alpha\",\"beta\"]");
    assertThat(payload.get("addresses").toString()).as("相交地址按字典序").isEqualTo("[\"shared:x1\"]");
  }

  /**
   * **R10**：读-写相交 ⇒ **放行**，且事件里的地址列表**按字典序**。
   *
   * <p>★ 地址故意**乱序输入且 4 个键**：3 键的散列序有 7%~40% 恰好落回插入序（M2 Task 5 实测），键太少， "忘了排序"这个错可能碰巧不显形（变异 m4
   * 打的就是这里）。
   */
  @Test
  void readWriteOverlapIsCommittedAndItsEventAddressesAreSorted() {
    seedMain(2L);
    ToyParticipant reader =
        new ToyParticipant(
            "beta", linkedSet("shared:x3", "shared:x1", "shared:x4", "shared:x2"), Set.of());
    ToyParticipant writer =
        new ToyParticipant(
            "alpha", Set.of(), linkedSet("shared:x4", "shared:x2", "shared:x3", "shared:x1"));
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(reader, writer),
            ref -> state(1L, 9L, "alpha", "beta"));

    CommandResult result = route.run(advanceCmd("cmd-rw", "corr-rw", 1L, 10L));

    assertThat(result).as("读-写 ⇒ 放行，不拒绝").isInstanceOf(CommandResult.Committed.class);
    assertThat(timeline.byCorrelation("corr-rw")).hasSize(1);

    JsonNode payload = json(conflictPayload("corr-rw"));
    assertThat(payload.get("kind").asText()).isEqualTo(AdvanceConflict.READ_WRITE);
    assertThat(payload.get("addresses").toString())
        .as("★ 输入序 x3,x1,x4,x2 ⇒ 落事件必须是 x1,x2,x3,x4（差一位就红）")
        .isEqualTo("[\"shared:x1\",\"shared:x2\",\"shared:x3\",\"shared:x4\"]");
    assertThat(payload.get("namespaces").toString())
        .as("★ 有向对：**读方 beta 在前、写方 alpha 在后**（不是字典序——排序会把方向抹掉）")
        .isEqualTo("[\"beta\",\"alpha\"]");
    assertThat(types("corr-rw"))
        .as("冲突事件在 started/proposal 之后、finished 之前——它是**留痕**，不是结局")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIMELINE_CONFLICT,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
  }

  // ── 多切片提案（1b-1：WorldTimeProposal + 逐模块校验 + 原子性）────────────────────────

  /** ★ 多切片参与者：每模块一条提案事件，两份变更集都进同一条 revision 的 changeset_json。 */
  @Test
  void multiSliceParticipantCommitsEveryModuleAndEmitsOneProposalEventPerModule() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(
                new ToyWorldParticipant("economy", modules("alpha", "beta"), Set.of(), Set.of())),
            ref -> state(1L, 9L, "alpha", "beta"));

    CommandResult result = route.run(advanceCmd("cmd-multi", "corr-multi", 1L, 10L));

    assertThat(result).isEqualTo(new CommandResult.Committed(ref("main", 2)));
    assertThat(types("corr-multi"))
        .as("一个参与者两模块 ⇒ two module.proposal（每模块一条）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
    assertThat(namespacesOfProposals("corr-multi"))
        .as("事件按参与者给出的模块顺序（参与者自己负责确定性）")
        .containsExactly("alpha", "beta");
    assertThat(timeline.row(ref("main", 2)).orElseThrow().changesetJson())
        .as("★ 两份变更集进同一条 revision 的 changeset_json")
        .contains("\"alpha\"")
        .contains("\"beta\"");
  }

  /**
   * ★★ **两个参与者改同一模块 ⇒ 拒绝整次推进**（哪怕写地址不相交）：Core 手里的 {@code ChangeSet} 不透明， 没有能力合并两份；报告里用 {@code
   * module:<ns>} 合成地址标记。
   */
  @Test
  void twoParticipantsTouchingTheSameModuleAreRejectedAsAWhole() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(
                new ToyWorldParticipant("economy", modules("alpha"), Set.of(), Set.of()),
                new ToyWorldParticipant("rival", modules("alpha"), Set.of(), Set.of())),
            ref -> state(1L, 9L, "alpha"));

    CommandResult result = route.run(advanceCmd("cmd-clash", "corr-clash", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("写-写");
    assertThat(timeline.row(ref("main", 2))).as("拒绝是原子的").isEmpty();
    JsonNode payload = json(conflictPayload("corr-clash"));
    assertThat(payload.get("namespaces").toString())
        .as("参与者身份按构造期字典序：[economy, rival]")
        .isEqualTo("[\"economy\",\"rival\"]");
    assertThat(payload.get("addresses").toString())
        .as("模块 clash 的合成地址")
        .isEqualTo("[\"module:alpha\"]");
  }

  /** 多切片提案里**任一个**模块没有注册 codec ⇒ 整次拒绝（消息点名那个 namespace）。 */
  @Test
  void multiSliceProposalWithAnUnregisteredModuleIsRejected() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")), // 没有 ghost 的 codec
            List.of(
                new ToyWorldParticipant("economy", modules("alpha", "ghost"), Set.of(), Set.of())),
            ref -> state(1L, 9L, "alpha", "ghost"));

    CommandResult result = route.run(advanceCmd("cmd-mixghost", "corr-mixghost", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("ghost");
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /** ★★ **原子性**：多切片提案里一个模块的 {@code apply} 抛 ⇒ **整日提案被拒**，另一个已经算好的模块 也不许落盘（六切片一起提交的语义）。 */
  @Test
  void oneFailingModuleRejectsTheWholeDayProposalAtomically() {
    seedMain(2L);
    ToyCodec exploding = new ToyCodec("beta");
    exploding.throwOnApply = true;
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), exploding),
            List.of(
                new ToyWorldParticipant("economy", modules("alpha", "beta"), Set.of(), Set.of())),
            ref -> state(1L, 9L, "alpha", "beta"));

    CommandResult result = route.run(advanceCmd("cmd-atomic", "corr-atomic", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("beta");
    assertThat(timeline.row(ref("main", 2)))
        .as("★ alpha 那份也不落盘：revision 一行都没有 ⇒ 六切片一起保持基态")
        .isEmpty();
  }

  // ── ④ Validate 的五项（R14 + m3 + C28）─────────────────────────────────────────────

  /** **R14**：未注册 namespace 的 proposal ⇒ {@code Rejected} 且不留 revision。 */
  @Test
  void proposalWithoutARegisteredCodecIsRejected() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")), // ★ 没有 "ghost" 的 codec
            List.of(participant("ghost")),
            ref -> state(1L, 9L, "ghost"));

    CommandResult result = route.run(advanceCmd("cmd-ghost", "corr-ghost", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("ghost");
    assertThat(timeline.row(ref("main", 2))).isEmpty();
    assertThat(types("corr-ghost"))
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.COMMAND_REJECTED);
  }

  /**
   * ★ **第 0 项：缺 {@code range.to} ⇒ Rejected**（变异 m3 打的就是这里）。
   *
   * <p>★★ 断言里有两条**故意安排的自爆装置**：状态装配器与参与者**一被调到就抛 {@link AssertionError}**。 没有它们，本用例只证明"结局是
   * Rejected"，而"**在干什么之前**就拒了"完全没被钉住——一个把第 0 项挪到 ⑤ 之前的实现 照样绿，那时推进已经跑完了全部 simulate，只是最后没落盘而已。
   */
  @Test
  void missingRangeUpperBoundIsRejectedBeforeAnythingElseRuns() {
    seedMain(2L);
    StateLoader neverLoad =
        ref -> {
          throw new AssertionError("缺 to 的推进不该走到 ① 的装配状态");
        };
    TimeParticipant neverSimulate =
        new TimeParticipant() {
          @Override
          public String namespace() {
            return "alpha";
          }

          @Override
          public TimeProposal simulate(SimulationState state, TimeRange range) {
            throw new AssertionError("缺 to 的推进不该走到 ② 的 simulate");
          }
        };
    TimeAdvance route = route(List.of(new ToyCodec("alpha")), List.of(neverSimulate), neverLoad);

    CommandResult result =
        route.run(
            new AdvanceTime(
                "cmd-open",
                "corr-open",
                "player:local",
                main(),
                new RevisionId(1L),
                TimeRange.since(SimosTimestamp.of(5L)))); // ★ to 为空

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("上界");
    assertThat(types("corr-open"))
        .as("★ 只该有 received + rejected——第 0 项在 started/proposal 之前")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_REJECTED);
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /**
   * ★★ **第 0 项的第二条：跨多日 ⇒ 接受（一次推进 N 天，§十一 裁定）**。
   *
   * <p>★ 2026-09-24 的"每天恰好一天"已被 2026-09-25 §十一 取代：{@code
   * multiDayAdvanceIsRejectedBeforeAnythingElseRuns} 的场景（base 在日 9、命令 9 → 12，共 3 天）现在应当
   * **Committed** —— 同一场景下的同一条 {@code AdvanceTime} 落**一条** revision，时间戳 = {@code to}（12）。
   *
   * <p>★ 同时钉住"它真的跑了"：装配器与参与者的调用计数各为 1（不能是"没报错但什么都没干"）。
   */
  @Test
  void multiDayAdvanceIsAcceptedAndSettlesTheWholeSpan() {
    seedMain(2L);
    int[] loadCalls = {0};
    ToyParticipant alpha = participant("alpha");
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(alpha),
            ref -> {
              loadCalls[0]++;
              return state(1L, 9L, "alpha");
            });

    CommandResult result =
        route.run(
            new AdvanceTime(
                "cmd-3day",
                "corr-3day",
                "player:local",
                main(),
                new RevisionId(1L),
                new TimeRange(
                    SimosTimestamp.of(9L), Optional.of(SimosTimestamp.of(12L))))); // ★ 3 天

    assertThat(result)
        .as("§十一：一次推进 3 天 ⇒ Committed（不再是「恰好一天」的拒绝）")
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));
    assertThat(timeline.row(ref("main", 2)).orElseThrow().timestamp())
        .as("新 revision 的时间戳 = 推进终点 to（12），不是 from + 1")
        .isEqualTo(SimosTimestamp.of(12L));
    assertThat(types("corr-3day"))
        .as("一次 N 天 = 一条完整推进链（一条 revision）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.MODULE_PROPOSAL,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
    assertThat(loadCalls[0]).as("装配器被调一次（它真的跑了）").isEqualTo(1);
    assertThat(alpha.simulateCalls).as("参与者被调一次（内部逐日是它的责任）").isEqualTo(1);
  }

  /**
   * ★★ **理智上限**：{@code N > 36500} ⇒ {@code Rejected}，理由点名 {@code 36500} 这个字面量；边界 {@code N == 36500}
   * **恰好合法**。
   *
   * <p>与"跨多日"同一条纪律：上限是**纯语法**判定，排在查库之前——装配器与参与者一被调到就抛。 ★ 判别力：删掉 {@code N ≤ 36500} 上限 ⇒ 第一条
   * 断言（越界必拒）当场红。
   */
  @Test
  void absurdlyLongAdvanceIsRejectedWithTheCapInTheReason() {
    seedMain(2L);
    StateLoader neverLoad =
        ref -> {
          throw new AssertionError("越界的推进不该走到 ① 的装配状态");
        };
    TimeParticipant neverSimulate =
        new TimeParticipant() {
          @Override
          public String namespace() {
            return "alpha";
          }

          @Override
          public TimeProposal simulate(SimulationState state, TimeRange range) {
            throw new AssertionError("越界的推进不该走到 ② 的 simulate");
          }
        };
    TimeAdvance route = route(List.of(new ToyCodec("alpha")), List.of(neverSimulate), neverLoad);

    CommandResult tooLong =
        route.run(
            new AdvanceTime(
                "cmd-huge",
                "corr-huge",
                "player:local",
                main(),
                new RevisionId(1L),
                new TimeRange(
                    SimosTimestamp.of(9L),
                    Optional.of(SimosTimestamp.of(9L + TimeAdvance.MAX_ADVANCE_DAYS + 1L)))));

    assertThat(tooLong).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) tooLong).reason()).as("理由必须点名上限字面量").contains("36500");
    assertThat(types("corr-huge"))
        .as("★ 只该有 received + rejected——上限在 started/proposal 之前")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_REJECTED);
    assertThat(timeline.row(ref("main", 2))).isEmpty();

    // ★ 边界：N == 36500 恰好合法（上限含端点）。被拒的那次没动 head ⇒ 仍可从 revision 1 推进。
    TimeAdvance accepting =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(participant("alpha")),
            ref -> state(1L, 9L, "alpha"));
    CommandResult atCap =
        accepting.run(
            new AdvanceTime(
                "cmd-cap",
                "corr-cap",
                "player:local",
                main(),
                new RevisionId(1L),
                new TimeRange(
                    SimosTimestamp.of(9L),
                    Optional.of(SimosTimestamp.of(9L + TimeAdvance.MAX_ADVANCE_DAYS)))));
    assertThat(atCap)
        .as("N = 36500 是合法的（上限含端点）")
        .isEqualTo(new CommandResult.Committed(ref("main", 2)));
  }

  /**
   * ★★ **连续性：range.from 与 base 的时间戳不符 ⇒ Rejected**（同一条日制裁定）。
   *
   * <p>这一条要 base 才知道，故排在 ① 之后、② 之前；参与者上的自爆装置钉住"**在 simulate 之前**就拒了" ——否则一个把校验挪到 ④
   * 之后的实现照样绿，而那时本日全部经济步骤已经算过一遍。
   */
  @Test
  void advanceThatDoesNotStartFromTheCurrentWorldDayIsRejected() {
    seedMain(2L);
    TimeParticipant neverSimulate =
        new TimeParticipant() {
          @Override
          public String namespace() {
            return "alpha";
          }

          @Override
          public TimeProposal simulate(SimulationState state, TimeRange range) {
            throw new AssertionError("from 与 base 不符的推进不该走到 ② 的 simulate");
          }
        };
    // base 在日 9，命令却从日 5 起 ⇒ 中间 4 天没有被结算，必须拒
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")), List.of(neverSimulate), ref -> state(1L, 9L, "alpha"));

    CommandResult result =
        route.run(
            new AdvanceTime(
                "cmd-gap",
                "corr-gap",
                "player:local",
                main(),
                new RevisionId(1L),
                new TimeRange(SimosTimestamp.of(5L), Optional.of(SimosTimestamp.of(6L)))));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("当前世界日");
    assertThat(types("corr-gap"))
        .as("★ 只该有 received + rejected")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_REJECTED);
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /** ★ **Validate 第 4 项 / C28**：codec 照抄 base 的坐标 ⇒ 当场 {@code Rejected}（不产生 revision）。 */
  @Test
  void codecThatCopiesTheBaseCoordinatesIsRejected() {
    seedMain(2L);
    ToyCodec copyBaseMeta = new ToyCodec("alpha");
    copyBaseMeta.copyBaseMeta = true;
    TimeAdvance route =
        route(List.of(copyBaseMeta), List.of(participant("alpha")), ref -> state(1L, 9L, "alpha"));

    CommandResult result = route.run(advanceCmd("cmd-stale", "corr-stale", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason())
        .as("★ 这条校验的存在理由：不填新坐标的快照落进 checkpoint，重放时状态树与坐标对不上")
        .contains("坐标");
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /** Validate 第 3 项：{@code codec.apply} 抛 ⇒ {@code Rejected}。 */
  @Test
  void codecThatThrowsOnApplyIsRejected() {
    seedMain(2L);
    ToyCodec exploding = new ToyCodec("alpha");
    exploding.throwOnApply = true;
    TimeAdvance route =
        route(List.of(exploding), List.of(participant("alpha")), ref -> state(1L, 9L, "alpha"));

    CommandResult result = route.run(advanceCmd("cmd-apply", "corr-apply", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  /** Validate 第 2 项：变更集编不出来 ⇒ {@code Rejected}。 */
  @Test
  void codecThatCannotEncodeTheChangeSetIsRejected() {
    seedMain(2L);
    ToyCodec exploding = new ToyCodec("alpha");
    exploding.throwOnEncodeChangeSet = true;
    TimeAdvance route =
        route(List.of(exploding), List.of(participant("alpha")), ref -> state(1L, 9L, "alpha"));

    CommandResult result = route.run(advanceCmd("cmd-enc", "corr-enc", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(timeline.row(ref("main", 2))).isEmpty();
  }

  // ── ① 乐观并发与裁定 48 的两条路 ────────────────────────────────────────────────────

  /** 期望坐标过期 ⇒ {@code Conflict(真 head)}，事件链 {@code received + conflicted}。 */
  @Test
  void staleExpectedRevisionIsAConflictCarryingTheRealHead() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(participant("alpha")),
            ref -> state(1L, 9L, "alpha"));

    CommandResult result = route.run(advanceCmd("cmd-stale-head", "corr-sh", 1L + 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Conflict.class);
    assertThat(((CommandResult.Conflict) result).current()).isEqualTo(ref("main", 1));
    assertThat(types("corr-sh"))
        .as("入口就撞上了 ⇒ 只有 received + conflicted，没有 started/proposal")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_CONFLICTED);
  }

  /** 分支不存在 ⇒ {@code Rejected}（**没有 head 可报，就不许编一个坐标出来**）。 */
  @Test
  void missingBranchIsRejectedNotAConflict() {
    seedMain(2L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(participant("alpha")),
            ref -> state(1L, 9L, "alpha"));

    CommandResult result =
        route.run(
            new AdvanceTime(
                "cmd-nobranch",
                "corr-nb",
                "player:local",
                new BranchId("nowhere"),
                new RevisionId(1L),
                timeRange(10L)));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("分支不存在");
  }

  /**
   * ★★ **裁定 48 的第一条路：主键撞了、且 head 真的动了 ⇒ 折成 {@code Conflict(真 head)}**。
   *
   * <p>手法：把"别人抢先提交"插在 **① 的 head 检查与 ⑤ 的提交之间**——那个窗口正是 Task 10 的 {@code commitLock} 罩不到本类的地方（{@code
   * AdvanceTime} 不过信封支）。{@link StateLoader} 的 {@code load} 恰好落在这个窗口里，
   * 于是"并发"可以用**单线程、确定性**地演出来（不必真起线程）。
   *
   * <p>★ 断言里的 {@code types} 是**重新起算**的那条链（{@code received + conflicted}）：原来那批事件与 revision 行
   * 在同一个事务里被回滚了，一条都没落——**尤其是 {@code finished} 不许补发**（推进并没有完成）。
   */
  @Test
  void primaryKeyCollisionWithAMovedHeadIsFoldedIntoConflict() {
    seedMain(2L);
    StateLoader racer =
        ref -> {
          // ★ 抢先提交一笔同坐标的 (main, 2)：head 从此动到 2
          timeline.appendRevision(
              revisionRow(ref("main", 2), Optional.of(ref("main", 1)), "cmd-racer", "corr-racer"));
          return state(1L, 9L, "alpha");
        };
    TimeAdvance route = route(List.of(new ToyCodec("alpha")), List.of(participant("alpha")), racer);

    CommandResult result = route.run(advanceCmd("cmd-lost", "corr-lost", 1L, 10L));

    assertThat(result).isInstanceOf(CommandResult.Conflict.class);
    assertThat(((CommandResult.Conflict) result).current())
        .as("报的是**真实 head**，调用方拿到即可重试或分岔")
        .isEqualTo(ref("main", 2));
    assertThat(timeline.row(ref("main", 2)).orElseThrow().commandId())
        .as("库里那一行是抢先者的，不是我们的")
        .isEqualTo("cmd-racer");
    assertThat(timeline.byCorrelation("corr-lost")).as("我方不留 revision 行").isEmpty();
    assertThat(types("corr-lost"))
        .as("★ 事务回滚了 ⇒ 链从 received 重新起算；`finished` 不许补发（推进并没有完成）")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_CONFLICTED);
  }

  /**
   * ★★ **裁定 48 的第二条路：失败不是竞态 ⇒ 原样重抛，不许伪装成冲突**。
   *
   * <p>这是本裁定的**要害**：若无条件把提交失败折成 {@code Conflict}，任何真 bug（写不进去、库坏了、装配错了）都会
   * 变成"别人抢先了"——那是**最难查的一类假象**。
   *
   * <p>手法：让 {@link StateLoader} 在 ① 与 ⑤ 之间把 store 关掉 ⇒ 提交必失败、而 head **一动没动**。 ★ 这里同时覆盖了"**连 head
   * 都问不出来**"的第三态：关了库之后重读 head 也会失败，此时**更不许声称冲突**， 故原异常上抛、把重读失败挂在 {@code suppressed} 上（不丢掉）。
   */
  @Test
  void failureThatIsNotARaceIsRethrownInsteadOfDisguisedAsConflict() {
    seedMain(2L);
    StateLoader saboteur =
        ref -> {
          store.close(); // head 检查已完成；这一笔之后 head 一动不动
          return state(1L, 9L, "alpha");
        };
    TimeAdvance route =
        route(List.of(new ToyCodec("alpha")), List.of(participant("alpha")), saboteur);

    assertThatThrownBy(() -> route.run(advanceCmd("cmd-fail", "corr-fail", 1L, 10L)))
        .as("★ 不是竞态 ⇒ 抛，不是 Conflict")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已关闭")
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .as("重读 head 也失败了 ⇒ 挂在 suppressed 上，别静默丢掉")
                    .isNotEmpty());
  }

  // ── ⑥ checkpoint ───────────────────────────────────────────────────────────────────

  /**
   * ★ **⑥ 写 checkpoint**：命中 C19 才写；信封的 meta 是**新坐标**；而**没被本次推进动过的模块保留自己的坐标** （与 {@code
   * Replay.applyWorld} 同一条语义——变更集里没提到的模块不推进坐标）。
   *
   * <p>★ 后半条是 R4（从 checkpoint 重放 == 从创世重放）的**前提**：若这里图省事把**所有**模块都盖上 {@code newMeta}， 那么"从
   * checkpoint 起"与"从创世起"两条路会在**没被改动过的模块**上给出不同的坐标 ⇒ R4 当场破。
   */
  @Test
  void checkpointIsWrittenWhenDueAndKeepsUntouchedModulesAtTheirOwnCoordinates() {
    seedMain(2L); // 周期 2 ⇒ (main, 2) 命中 C19 第①项
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta")),
            List.of(participant("alpha")), // ★ 只推进 alpha；beta 是"没被提到"的那个
            ref -> state(1L, 9L, "alpha", "beta"));

    assertThat(route.run(advanceCmd("cmd-ckpt", "corr-ckpt", 1L, 10L)))
        .isInstanceOf(CommandResult.Committed.class);

    String envelopeJson =
        checkpoints
            .read(ref("main", 2))
            .orElseThrow(() -> new AssertionError("命中 C19 却没写 checkpoint"));
    Envelope.Decoded decoded = Envelope.decode(envelopeJson);

    assertThat(decoded.meta().ref()).as("信封的坐标是新 revision").isEqualTo(ref("main", 2));
    assertThat(decoded.meta().timestamp())
        .as("推进终点的时刻，不是 base 的")
        .isEqualTo(SimosTimestamp.of(10L));
    assertThat(decoded.modules().keySet()).containsExactlyInAnyOrder("alpha", "beta");

    ToyCodec alphaCodec = new ToyCodec("alpha");
    ToyCodec betaCodec = new ToyCodec("beta");
    ToySnapshot alpha = (ToySnapshot) alphaCodec.decodeSnapshot(decoded.modules().get("alpha"));
    ToySnapshot beta = (ToySnapshot) betaCodec.decodeSnapshot(decoded.modules().get("beta"));

    assertThat(alpha.ref()).as("被推进过的模块：新坐标").isEqualTo(ref("main", 2));
    assertThat(alpha.timestamp()).isEqualTo(SimosTimestamp.of(10L));
    assertThat(alpha.v()).as("变更集真的被 apply 了").isEqualTo(1);

    assertThat(beta.ref()).as("★ 没被提到的模块**保留自己的坐标**（不改它，正是 R4 成立的前提）").isEqualTo(ref("main", 1));
    assertThat(beta.timestamp()).isEqualTo(SimosTimestamp.of(9L));
    assertThat(beta.v()).isEqualTo(0);
  }

  /** C19 没命中 ⇒ **不写**（checkpoint 是纯优化，C18：多写一份不会更正确，只会让"应当有"的判定失去意义）。 */
  @Test
  void checkpointIsNotWrittenWhenTheIntervalDoesNotHit() {
    seedMain(1000L); // 周期 1000 ⇒ (main, 2) 不命中，也不是分岔点、不是创世
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(participant("alpha")),
            ref -> state(1L, 9L, "alpha"));

    assertThat(route.run(advanceCmd("cmd-nockpt", "corr-nc", 1L, 10L)))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(checkpoints.read(ref("main", 2))).isEmpty();
  }

  // ── 构造期护栏 ──────────────────────────────────────────────────────────────────────

  /** ★ 构造期就把重复的 namespace 挡掉：静默覆盖会让"哪个 codec 解释这个提案"取决于集合迭代序。 */
  @Test
  void duplicateNamespacesAreRejectedAtConstruction() {
    seedMain(2L);
    StateLoader loader = ref -> state(1L, 9L, "alpha");

    assertThatThrownBy(
            () ->
                new TimeAdvance(
                    timeline,
                    loader,
                    checkpoints,
                    List.of(new ToyCodec("alpha"), new ToyCodec("alpha")),
                    List.of(participant("alpha"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec 表里 namespace 重复");

    assertThatThrownBy(
            () ->
                new TimeAdvance(
                    timeline,
                    loader,
                    checkpoints,
                    List.of(new ToyCodec("alpha")),
                    List.of(participant("alpha"), participant("alpha"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("参与者 namespace 重复");
  }

  // ── 夹具方法 ────────────────────────────────────────────────────────────────────────

  private TimeAdvance route(
      List<ModuleCodec> codecs, List<TimeParticipant> participants, StateLoader loader) {
    return new TimeAdvance(timeline, loader, checkpoints, codecs, participants);
  }

  /** 建时间线并种一行 {@code (main, 1)}——head 存在是推进的前提。 */
  private void seedMain(long checkpointInterval) {
    timeline = new Timeline(store, checkpointInterval);
    timeline.appendRevision(
        revisionRow(ref("main", 1), Optional.empty(), "cmd-seed", "corr-seed"), List.of());
  }

  private static SimulationState state(long revision, long tick, String... namespaces) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (String namespace : namespaces) {
      modules.put(
          namespace, new ToySnapshot(ref("main", revision), SimosTimestamp.of(tick), namespace, 0));
    }
    return new SimulationState(
        new StateMeta(ref("main", revision), SimosTimestamp.of(tick)),
        modules,
        InMemoryInfoSystem.empty());
  }

  private static AdvanceTime advanceCmd(
      String commandId, String correlationId, long expectedRevision, long toTick) {
    return new AdvanceTime(
        commandId,
        correlationId,
        "player:local",
        main(),
        new RevisionId(expectedRevision),
        timeRange(toTick));
  }

  private static TimeRange timeRange(long toTick) {
    return new TimeRange(SimosTimestamp.of(toTick - 1), Optional.of(SimosTimestamp.of(toTick)));
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

  private List<String> types(String correlationId) {
    return events.byCorrelation(correlationId).stream().map(EventRow::type).toList();
  }

  private List<String> namespacesOfProposals(String correlationId) {
    return events.byCorrelation(correlationId).stream()
        .filter(row -> row.type().equals(EventTypes.MODULE_PROPOSAL))
        .map(row -> json(row.payload()).get("namespace").asText())
        .toList();
  }

  /** 取该链上**唯一**那条冲突事件的载荷。 */
  private String conflictPayload(String correlationId) {
    List<EventRow> conflicts =
        events.byCorrelation(correlationId).stream()
            .filter(row -> row.type().equals(EventTypes.TIMELINE_CONFLICT))
            .toList();
    assertThat(conflicts).as("该链上应恰有一条 timeline.conflict").hasSize(1);
    return conflicts.get(0).payload();
  }

  private static JsonNode json(String text) {
    try {
      return new ObjectMapper().readTree(text);
    } catch (Exception e) {
      throw new AssertionError("事件载荷不是合法 JSON: " + text, e);
    }
  }

  /** 保序的地址集——**故意让调用方写乱序**，好让"排序"这一步可观测。 */
  private static Set<String> linkedSet(String... values) {
    return new LinkedHashSet<>(new ArrayList<>(List.of(values)));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
