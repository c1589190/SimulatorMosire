package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.ApplyCasualtiesHandler;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T8 的**时间线恢复**判据（E4 / N3 / P14）：真 {@link CoreSimos}（真 {@code CommandBus} + 真 Sqlite store）上，
 * {@code unit.ApplyCasualties} 落成一条**真 revision**，且**回退到战损前那一 revision 读回的是战前值**。
 *
 * <p>★★ 这条判据的实质是"**revision 里落的是绝对值**"：命令边界给出的变更集是 {@code UnitChangeSet.between(base,
 * next)}（目标状态的新值），不是"−30 这个增量"。若哪一天有人把它改成增量语义（或让战损绕开 revision 直接覆写历史）， <b>回退就读不回战前值</b> ——
 * 本类就是那个咬点。
 *
 * <p>★ 与 {@code BranchingEndToEndTest} 的分工：那个类验分岔与分支隔离，本类只验**同一条分支上的历史**（R1 战前 / R2 战损后）
 * 与**事件载荷无明文**。夹具同法：先独立打开 store 种创世行 {@code (main,1)} + 写创世 checkpoint，关掉它，然后才构造 {@link CoreSimos}。
 *
 * <p>★ 三件事各有一条用例：① 战损落 revision + 回退取战前值 + 战损后 revision 上取战损值（m5 / m1 的端到端面）； ② 被拒的命令**不落
 * revision**（m2 的行数面；域层的上界本身在 {@code UnitOperationsTest} 判）； ③ {@code received} 事件**只带 digest、不带
 * delta 明文**（m6）。
 */
class UnitCasualtyRevisionTest {

  /** 创世模拟时刻；战损是命令，不移动时钟 ⇒ 全程 tick 恒为 T0。 */
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final UnitId U1 = new UnitId("u-1");

  /** 创世单位的位置（只是"另一个单位字段"，用来证明重放搬的是整棵树）。 */
  private static final HexCoord H11 = new HexCoord(1, 1);

  /** T8 冻结的命令类型（spec §五.2 / §四 表）。 */
  private static final String CASUALTY_TYPE = "unit.ApplyCasualties";

  /** ★ 战损载荷：人力 −30、装备只扣**提及**的 `步枪`（`炮` 必须留在原地）。 */
  private static final String CASUALTY_PAYLOAD =
      "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-30}],"
          + "\"equipment\":[{\"type\":\"步枪\",\"amount\":-10}]}";

  /** 与 {@code BranchingEndToEndTest} 同一口径：{@code CoreConfig.mapper} 无消费者，但也不许传 null。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  /**
   * ★★ 判据（**m5 靶子**）：战损后**回退前一 revision** ⇒ 战前值；且**战损那一 revision** 上取到的是战损值。
   *
   * <p>★ 两个方向都断言，是因为单一方向的断言可以被"历史被覆写"的实现糊弄过去（覆写之后"回退"读到的正是战损值，
   * 只判回退这条会红得含糊）；两条一起判，红点才落在"历史是不是两条各自独立的行"上。
   */
  @Test
  void casualtyLandsAsARealRevisionAndRollsBackToThePreBattleValue() {
    seedGenesis();

    CommandResult casualty;
    try (CoreSimos core = wiredCore()) {
      casualty = core.submit(casualty("cmd-cas", 1));

      assertThat(casualty)
          .as("① 战损落成一条真 revision（不是「没有落盘」就是「覆写历史」）")
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));

      // ── 战损后那一 revision：绝对值落盘 ⇒ 70 / 40（`炮` 未提及 ⇒ 4 留在原地）────────────
      UnitSnapshot afterBattle = unitOf(core.replay(ref("main", 2)));
      assertThat(afterBattle.state().units().get(U1).manpower())
          .as("R2：100 + (−30) = 70（有序条目表，人力条目留在原地）")
          .containsExactly(new CompositionEntry("步兵", 70));
      assertThat(afterBattle.state().units().get(U1).equipment())
          .as("R2：装备双轨——提及条目 40、未提及条目 4（保序）")
          .containsExactly(new CompositionEntry("步枪", 40), new CompositionEntry("炮", 4));
      assertThat(afterBattle.ref()).as("切片的 ref 是它自己那一 revision").isEqualTo(ref("main", 2));

      // ── ★★ 回退到战损前那一 revision：战前值（m5 的咬点）─────────────────────────────
      UnitSnapshot beforeBattle = unitOf(core.replay(ref("main", 1)));
      assertThat(beforeBattle.state().units().get(U1).manpower())
          .as("★ 回退 R1 ⇒ 战前值 100（历史若被覆写，这里读到的是 70）")
          .containsExactly(new CompositionEntry("步兵", 100));
      assertThat(beforeBattle.state().units().get(U1).equipment())
          .as("★ 回退 R1 ⇒ 战前装备（步枪 50 / 炮 4，保序）")
          .containsExactly(new CompositionEntry("步枪", 50), new CompositionEntry("炮", 4));

      // ── 再回一次、再取一次：重放是纯函数，历史行不被读操作改写 ────────────────────────
      assertThat(unitOf(core.replay(ref("main", 1))).state().units().get(U1).manpower())
          .as("重复回退仍是战前值")
          .containsExactly(new CompositionEntry("步兵", 100));
      assertThat(unitOf(core.replay(ref("main", 2))).state().units().get(U1).manpower())
          .as("重复取战损后仍是战损值")
          .containsExactly(new CompositionEntry("步兵", 70));

      assertThat(core.revisions(main()))
          .as("两条各自独立的行（创世 + 战损）")
          .hasSize(2)
          .extracting(RevisionRow::revision)
          .containsExactly(new RevisionId(1), new RevisionId(2));
    }
    assertThat(casualty).isEqualTo(new CommandResult.Committed(ref("main", 2)));

    // ── 行级（独立 store）：R2 的父链指 R1、命令类型是战损 ──────────────────────────────
    RevisionRow casualtyRow = row(ref("main", 2));
    assertThat(casualtyRow.parent()).as("R2 的 parent 是 R1（同一条历史链）").contains(ref("main", 1));
    assertThat(casualtyRow.commandType()).isEqualTo(CASUALTY_TYPE);
    assertThat(casualtyRow.timestamp()).isEqualTo(T0);
    assertThat(casualtyRow.changesetJson())
        .as("★ 落盘的是变更集（绝对值），不是命令载荷的 delta 明文")
        .contains("\"amount\":70")
        .contains("\"amount\":40")
        .doesNotContain("\"amount\":-30")
        .doesNotContain("\"amount\":-10");
  }

  /**
   * ★★ 判据（**m2 的行数面**）：被拒的战损命令 —— 越界 / 正 Δ / 未知装备类型 —— **一条 revision 都不落**，
   * 且世界逐值不动（三条拒绝的理由各不相同，说明它们真被域层各自判过，不是"一律拒"）。
   *
   * <p>★ 域层的上界本身（`100 + (−101)` ⇒ 抛）在 {@code UnitOperationsTest} 判；这里判的是**命令边界之后**的落盘面。
   */
  @Test
  void rejectedCasualtyLeavesTheRevisionTableUntouched() {
    seedGenesis();

    try (CoreSimos core = wiredCore()) {
      assertThat(core.revisions(main())).as("起点：只有创世一行").hasSize(1);

      CommandResult outOfRange =
          core.submit(
              casualtyPayload(
                  "cmd-over",
                  1,
                  "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-101}],"
                      + "\"equipment\":[]}"));
      CommandResult positive =
          core.submit(
              casualtyPayload(
                  "cmd-plus",
                  1,
                  "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":5}],"
                      + "\"equipment\":[]}"));
      CommandResult unknownKey =
          core.submit(
              casualtyPayload(
                  "cmd-unknown",
                  1,
                  "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-1}],"
                      + "\"equipment\":[{\"type\":\"坦克\",\"amount\":-1}]}"));

      assertThat(outOfRange).as("★ 上界：100 + (−101) ⇒ 拒").isInstanceOf(CommandResult.Rejected.class);
      assertThat(((CommandResult.Rejected) outOfRange).reason()).contains("人员战损超出当前值");
      assertThat(((CommandResult.Rejected) positive).reason()).contains("人员增量必须 ≤ 0");
      assertThat(((CommandResult.Rejected) unknownKey).reason())
          .as("★ P14：未知类型 ⇒ 拒（不视作 0）")
          .contains("未知装备类型");

      assertThat(core.revisions(main())).as("★ 三条拒绝之后，revisions 行数仍是一（拒绝不落 revision）").hasSize(1);
      UnitSnapshot still = unitOf(core.replay(ref("main", 1)));
      assertThat(still.state().units().get(U1).manpower())
          .as("世界逐值不动")
          .containsExactly(new CompositionEntry("步兵", 100));
      assertThat(still.state().units().get(U1).equipment())
          .containsExactly(new CompositionEntry("步枪", 50), new CompositionEntry("炮", 4));
    }
  }

  /**
   * ★★ 判据（**m6 靶子**）：{@code received} 事件载荷**只含 digest**（`payloadDigest` = 64 位十六进制）， **不含 delta
   * 明文**（字段名与数值都不出现）。
   *
   * <p>★ 靶子是把领域载荷塞进事件（"顺手记一下参数明文"）的实现——那样 delta 明文就进了旁路日志，本用例红在 "不该出现的字符串"那几条上。★ 判据不写成"payload
   * 等于某个定值"：摘要的算法是 agentlib 的事（裁定 45）， 这里只判**形状**与**不含明文**，避免把摘要实现钉进用例。
   */
  @Test
  void receivedEventCarriesOnlyTheDigestNotTheDeltaPlaintext() {
    seedGenesis();

    try (CoreSimos core = wiredCore()) {
      assertThat(core.submit(casualty("cmd-cas", 1)))
          .isEqualTo(new CommandResult.Committed(ref("main", 2)));
    }

    // 关库后用一棵独立 store 读事件（与门面持有的连接各走各的 WAL 读）。
    List<EventRow> trace;
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      trace = new EventStore(store).byCorrelation(CORR);
    }

    List<EventRow> received =
        trace.stream().filter(r -> EventTypes.COMMAND_RECEIVED.equals(r.type())).toList();
    assertThat(received).as("恰一条 received").hasSize(1);
    assertThat(trace.stream().map(EventRow::type).toList())
        .as("命令链完整（received + committed）")
        .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_COMMITTED);

    String payload = received.getFirst().payload();
    assertThat(payload).as("命令类型是记的（它不是明文领域参数）").contains(CASUALTY_TYPE);
    assertThat(payload)
        .as("★ 载荷里只有 payloadDigest，且形状是 `sha256:` + 32 位十六进制（sha256 前 16 字节，裁定 45）")
        .containsPattern("\"payloadDigest\":\"sha256:[0-9a-f]{32}\"");
    assertThat(payload)
        .as("★ m6：delta 的字段名不进事件载荷")
        .doesNotContain("manpower")
        .doesNotContain("equipment");
    assertThat(payload)
        .as("★ m6：delta 的数值不进事件载荷（负数一定带 `-`，十六进制里不可能出现）")
        .doesNotContain("-30")
        .doesNotContain("-10")
        .doesNotContain("步枪");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 真 {@code unit.ApplyCasualties} 信封（correlationId 显式给，便于按它读事件链）。 */
  private static final String CORR = "corr-cas";

  private static CommandEnvelope casualty(String commandId, long expectedRevision) {
    return casualtyPayload(commandId, expectedRevision, CASUALTY_PAYLOAD);
  }

  private static CommandEnvelope casualtyPayload(
      String commandId, long expectedRevision, String payloadJson) {
    return new CommandEnvelope(
        commandId,
        CORR,
        "player:local",
        main(),
        new RevisionId(expectedRevision),
        CASUALTY_TYPE,
        payloadJson);
  }

  /** 装配真的 codec + 真的 T8 handler（无替身）。 */
  private CoreSimos wiredCore() {
    CoreSimos core = new CoreSimos(new CoreConfig(tempDir, 4, MAPPER));
    core.register(new UnitCodec());
    core.register(new ApplyCasualtiesHandler());
    return core;
  }

  /**
   * 种创世：独立打开 store 落一行 {@code (main,1)}（parent 空、变更集空），写创世 checkpoint 文件，再关掉它。
   *
   * <p>★ 世界只含 {@code unit} 一个切片——T8 的两个文件都不需要别的模块，故不引入无关模块。
   */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, 4L)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T0,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(Map.of(U1, unit()));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T0),
            Map.of("unit", new UnitSnapshot(ref("main", 1), T0, units)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(ref("main", 1), CheckpointEncoder.encode(genesis, List.of(new UnitCodec())));
  }

  /** 创世单位：100 人、**两件装备**（`炮` 是"未被提及的条目必须留在原地"那半条的载体）。 */
  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50), new CompositionEntry("炮", 4)),
        2,
        500,
        Optional.empty());
  }

  /** 读一行的机制面：用一棵独立 store 读（与门面持有的连接各走各的 WAL 读）。 */
  private RevisionRow row(StateRef at) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return new Timeline(store, 4L)
          .row(at)
          .orElseThrow(() -> new AssertionError("期望存在的 revision 行: " + at));
    }
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static UnitSnapshot unitOf(SimulationState state) {
    return (UnitSnapshot)
        state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
