package io.mosire.simos.core.advance;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.StateLoader;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.Incident;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.StopReport;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>急停协议</b>（2026-10-13，约束设计书 §4.3 / §6.2 P1–P6 / 不变量 I4、I8）在 <b>core 侧</b>的判据。
 *
 * <p>★★ <b>本类的判据是"次序"，不是"有没有停"</b>（用户原话：「急停的时候是<b>先世界里做完</b>，然后再报给GM的」）：
 *
 * <pre>
 * ① 跑完当天**全部**参与者（不许短路）→ ② 落 revision（世界做完）→ ③ drain → ④ 聚合当天全部 Incident
 *   → ⑤ 非空 ⇒ 停并产出 StopReport
 * </pre>
 *
 * <p>关键在于 <b>②→③→⑤ 的次序只能从钩子内部观测</b>：{@link StopDrain} 拿到的 {@code worldRef} 必须<b>已经落盘</b> （{@code
 * timeline.row(worldRef)} 非空、且 tick == atTick）。这条判据写成"钩子里只**记录**、跑完再断言"—— ★ 因为 {@code TimeAdvance}
 * 对 drain 的异常只 WARN 不上抛（观察不许改被判事物的结局），在钩子里直接断言会被吞掉、变成假绿。
 *
 * <p>★ 参与者是玩具（{@code ToyParticipant}）：core 的推进管线不认识领域类型（ADR-1），而本类判的全是机械次序。 真判据（{@code
 * army.contact} 的逐日扫描）在 {@code simos-army} 侧的 {@code ArmyTimeParticipantStopTest}。
 */
class AdvanceStopProtocolTest {

  private record ToyChangeSet(int v) implements ChangeSet {}

  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace)
      implements Snapshot {}

  /** 最小 codec：照 Core 给的新坐标落盘（不搞 Validate 那几项失败的开关——本类不测它们）。 */
  private static final class ToyCodec implements ModuleCodec {

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
      return new ToyChangeSet(Integer.parseInt(json));
    }

    @Override
    public String encodeChangeSet(ChangeSet changeSet) {
      return Integer.toString(((ToyChangeSet) changeSet).v());
    }

    @Override
    public Snapshot decodeSnapshot(String json) {
      String[] parts = json.split("\\|");
      return new ToySnapshot(
          new StateRef(new BranchId(parts[1]), new RevisionId(Long.parseLong(parts[2]))),
          SimosTimestamp.of(Long.parseLong(parts[3])),
          parts[0]);
    }

    @Override
    public String encodeSnapshot(Snapshot snapshot) {
      ToySnapshot toy = (ToySnapshot) snapshot;
      return String.join(
          "|",
          toy.namespace(),
          toy.ref().branch().value(),
          Long.toString(toy.ref().revision().value()),
          Long.toString(toy.timestamp().tick()));
    }

    @Override
    public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
      return new ToySnapshot(newMeta.ref(), newMeta.timestamp(), namespace);
    }
  }

  /**
   * 会在指定那一天报一条突发事件（{@code kind = "<namespace>.bump"}）的玩具参与者。
   *
   * <p>★ {@code reportDay} = -1 ⇒ 永不报（P1）；{@code tickOffset} ≠ 0 ⇒ 故意报错日子（机械校验的负向）。
   */
  private static final class ReportingParticipant implements TimeParticipant {

    private final String namespace;
    private final long reportDay;
    private final long tickOffset;
    private final Map<String, Object> payload;
    private int simulateCalls;
    private final List<Long> simulatedDays = new ArrayList<>();

    ReportingParticipant(String namespace, long reportDay) {
      this(namespace, reportDay, 0L, Map.of("hex", "5_0"));
    }

    ReportingParticipant(
        String namespace, long reportDay, long tickOffset, Map<String, Object> payload) {
      this.namespace = namespace;
      this.reportDay = reportDay;
      this.tickOffset = tickOffset;
      this.payload = payload;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public TimeProposal simulate(SimulationState state, TimeRange range) {
      simulateCalls++;
      long day = range.to().orElseThrow().tick();
      simulatedDays.add(day);
      if (day != reportDay) {
        return new TimeProposal(namespace, new ToyChangeSet(1), Set.of(), Set.of());
      }
      Incident incident =
          new Incident(
              namespace + ".bump",
              day + tickOffset,
              namespace,
              payload,
              List.of("ANCHOR " + namespace + " day=" + day));
      return new TimeProposal(
          namespace, new ToyChangeSet(1), Set.of(), Set.of(), List.of(incident));
    }
  }

  /** 记录态 drain 替身：★ 钩子里**只记录、不断言**（异常会被 TimeAdvance 吞成 WARN ⇒ 断言会变假绿）。 */
  private static final class RecordingDrain implements StopDrain {

    private final List<StateRef> refs = new ArrayList<>();
    private final List<Long> ticks = new ArrayList<>();
    private final List<Optional<RevisionRow>> rowsSeen = new ArrayList<>();
    private final List<Long> headsSeen = new ArrayList<>();
    private final Timeline timeline;
    private Runnable afterRecord = () -> {};

    RecordingDrain(Timeline timeline) {
      this.timeline = timeline;
    }

    @Override
    public void drainAfterStop(StateRef worldRef, long atTick) {
      refs.add(worldRef);
      ticks.add(atTick);
      rowsSeen.add(timeline.row(worldRef));
      headsSeen.add(timeline.head(worldRef.branch()).orElseThrow().value());
      afterRecord.run();
    }
  }

  @TempDir Path tempDir;

  private SqliteStore store;
  private Timeline timeline;
  private CheckpointStore checkpoints;
  private EventStore events;

  @BeforeEach
  void openStore() {
    store = SqliteStore.open(tempDir.resolve("stop.db"));
    events = new EventStore(store);
    checkpoints = new CheckpointStore(tempDir);
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  // ── P1：没有突发事件 ⇒ 普通提交、不 drain、落 N 条 revision ────────────────────────────

  /**
   * ★ <b>P1：单日/多日推进里没有任何模块报突发事件 ⇒ {@code Committed}、不停、不报、<b>不 drain</b></b>。
   *
   * <p>★ 「不 drain」是本条的一半判别力：drain 是**急停路径专属**的（口径 3=A），平稳推进不该被它碰到。
   */
  @Test
  void noIncidentCommitsTheWholeSpanWithoutDraining() {
    seedMain(2L);
    ReportingParticipant alpha = new ReportingParticipant("alpha", -1L);
    RecordingDrain drain = new RecordingDrain(timeline);
    TimeAdvance route =
        route(List.of(new ToyCodec("alpha")), List.of(alpha), stateLoader(4L, "alpha"), drain);

    AdvanceOutcome outcome = route.advance(advanceCmd("cmd-plain", "corr-plain", 1L, 4L, 7L));

    assertThat(outcome).isInstanceOf(AdvanceOutcome.Committed.class);
    assertThat(outcome.stopped()).as("★ P1：没人报 ⇒ 不停").isFalse();
    assertThat(outcome.stopReport()).as("★ P1：不停 ⇒ 没有原因系统").isEmpty();
    assertThat(outcome.commandResult())
        .as("§十一：整段走完 ⇒ 终点在第 3 天那条 revision（main:4）")
        .isEqualTo(new CommandResult.Committed(ref("main", 4)));
    assertThat(alpha.simulateCalls).as("逐日 ⇒ 3 天 3 次").isEqualTo(3);
    assertThat(alpha.simulatedDays).as("逐日进出：第 5、6、7 天").containsExactly(5L, 6L, 7L);
    assertThat(drain.refs).as("★ 平稳推进不 drain（drain 是急停路径专属）").isEmpty();
    assertThat(timeline.row(ref("main", 2)).orElseThrow().timestamp())
        .as("逐日化：main:2 == 第 5 天")
        .isEqualTo(SimosTimestamp.of(5L));
    assertThat(timeline.row(ref("main", 3)).orElseThrow().timestamp())
        .isEqualTo(SimosTimestamp.of(6L));
    assertThat(timeline.row(ref("main", 4)).orElseThrow().timestamp())
        .isEqualTo(SimosTimestamp.of(7L));
  }

  // ── P2：命中那天停下 ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P2：第 2 天（tick 6）命中 ⇒ 停在第 6 天</b>，且：{@code worldRef} = 第 6 天那条 revision； 世界**没有**继续走到第 7
   * 天（{@code main:4} 不存在）。
   */
  @Test
  void stopsOnTheVeryDayTheIncidentHappens() {
    seedMain(2L);
    ReportingParticipant alpha = new ReportingParticipant("alpha", 6L);
    RecordingDrain drain = new RecordingDrain(timeline);
    TimeAdvance route =
        route(List.of(new ToyCodec("alpha")), List.of(alpha), stateLoader(4L, "alpha"), drain);

    AdvanceOutcome outcome = route.advance(advanceCmd("cmd-stop", "corr-stop", 1L, 4L, 10L));

    assertThat(outcome).isInstanceOf(AdvanceOutcome.Stopped.class);
    AdvanceOutcome.Stopped stopped = (AdvanceOutcome.Stopped) outcome;
    StopReport report = stopped.report();
    assertThat(report.atTick()).as("★ 报的是**命中的那一天**").isEqualTo(6L);
    assertThat(report.worldRef())
        .as("★ worldRef = 第 6 天落下的坐标（drain 后重读 head）")
        .isEqualTo(ref("main", 3));
    assertThat(stopped.committed())
        .as("I4：急停是一段真实推进的终点 ⇒ 折成那一天的 Committed")
        .isEqualTo(new CommandResult.Committed(ref("main", 3)));
    assertThat(outcome.commandResult()).isEqualTo(new CommandResult.Committed(ref("main", 3)));
    assertThat(outcome.stopped()).isTrue();

    assertThat(timeline.row(ref("main", 2)).orElseThrow().timestamp())
        .isEqualTo(SimosTimestamp.of(5L));
    assertThat(timeline.row(ref("main", 3)).orElseThrow().timestamp())
        .as("★ 第 6 天的 revision 真的落了（世界先做完）")
        .isEqualTo(SimosTimestamp.of(6L));
    assertThat(timeline.row(ref("main", 4))).as("★ 世界真的停在 6：第 7 天**没有** revision").isEmpty();
    assertThat(alpha.simulatedDays).as("第 7 天根本没被跑").containsExactly(5L, 6L);
    assertThat(types("corr-stop"))
        .as("committed 每天一条；finished 只落在**终结日**（= 停下那天）")
        .containsExactly(
            EventTypes.COMMAND_RECEIVED,
            EventTypes.TIME_ADVANCE_STARTED,
            EventTypes.COMMAND_COMMITTED,
            EventTypes.TIME_ADVANCE_FINISHED,
            EventTypes.COMMAND_COMMITTED);
  }

  // ── P3：停后世界真在那天、且可续推 ───────────────────────────────────────────────────

  /**
   * ★★ <b>P3：停在世界真的在 tick 6 —— 从它继续推进能正常走完</b>。
   *
   * <p>这是口径 1=(i)「Core 逐日落 revision」的全部意义：停下的那一刻就是一个**可续推的合法世界坐标**。
   */
  @Test
  void stoppedWorldIsReallyAtThatTickAndCanBeAdvancedFurther() {
    seedMain(2L);
    RecordingDrain drain = new RecordingDrain(timeline);
    TimeAdvance first =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(new ReportingParticipant("alpha", 6L)),
            stateLoader(4L, "alpha"),
            drain);
    first.advance(advanceCmd("cmd-stop", "corr-stop", 1L, 4L, 10L));

    // 续推：从停下的坐标（main:3，世界日 6）出发走到第 10 天。
    TimeAdvance second =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(new ReportingParticipant("alpha", -1L)),
            ref -> state(3L, 6L, "alpha"),
            new RecordingDrain(timeline));
    AdvanceOutcome resumed = second.advance(advanceCmd("cmd-resume", "corr-resume", 3L, 6L, 10L));

    assertThat(resumed).isInstanceOf(AdvanceOutcome.Committed.class);
    assertThat(resumed.commandResult())
        .as("★ 从 tick 6 续推 4 天 ⇒ 再落 4 条 revision（main:4…main:7）")
        .isEqualTo(new CommandResult.Committed(ref("main", 7)));
    assertThat(timeline.row(ref("main", 7)).orElseThrow().timestamp())
        .as("续推终点 = 第 10 天")
        .isEqualTo(SimosTimestamp.of(10L));
  }

  // ── P5 / P5b：原因系统（聚合而非短路）────────────────────────────────────────────────

  /** ★ <b>P5：原因系统的每个字段都可读</b>（GM 用 MCP 工具读回的就是这份）。 */
  @Test
  void reasonSystemCarriesTickCoordinatesNamespaceKindPayloadAndLogRefs() {
    seedMain(2L);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("armies", List.of("a-army", "b-army"));
    payload.put("hex", "5_0");
    ReportingParticipant alpha = new ReportingParticipant("alpha", 6L, 0L, payload);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(alpha),
            stateLoader(4L, "alpha"),
            new RecordingDrain(timeline));

    StopReport report =
        route.advance(advanceCmd("cmd-p5", "corr-p5", 1L, 4L, 10L)).stopReport().orElseThrow();

    assertThat(report.atTick()).isEqualTo(6L);
    assertThat(report.worldRef()).isEqualTo(ref("main", 3));
    assertThat(report.reasons()).hasSize(1);
    StopReport.Reason reason = report.reasons().get(0);
    assertThat(reason.namespace()).as("哪个模块要求停").isEqualTo("alpha");
    assertThat(reason.kind()).as("它自报的种类").isEqualTo("alpha.bump");
    assertThat(reason.payload()).as("结构化证据逐键相同").containsExactlyEntriesOf(payload);
    assertThat(reason.logRefs())
        .as("★ 日志锚由模块自报，util/core 不解释")
        .containsExactly("ANCHOR alpha day=6");
  }

  /**
   * ★★ <b>P5b：聚合而非短路 —— 同一天两个模块各报一条 ⇒ {@code reasons()} 里两条都在</b>。
   *
   * <p>判别力有两层：① 只报第一条的实现 ⇒ {@code reasons} 少一条 ⇒ 红；② 「发现第一个事件就中断当天的剩余参与者」的实现 ⇒ 字典序在后的那个参与者的 {@code
   * simulateCalls} 少一次 ⇒ 红。
   */
  @Test
  void bothModulesReportingOnTheSameDayAreBothInTheReportAndNeitherIsShortCircuited() {
    seedMain(2L);
    ReportingParticipant alpha = new ReportingParticipant("alpha", 6L);
    ReportingParticipant beta = new ReportingParticipant("beta", 6L);
    ReportingParticipant gamma = new ReportingParticipant("gamma", -1L);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha"), new ToyCodec("beta"), new ToyCodec("gamma")),
            // ★ 注册序故意与字典序相反：报告次序必须是字典序（内容的纯函数）。
            List.of(gamma, beta, alpha),
            stateLoader(4L, "alpha", "beta", "gamma"),
            new RecordingDrain(timeline));

    AdvanceOutcome outcome = route.advance(advanceCmd("cmd-p5b", "corr-p5b", 1L, 4L, 10L));
    assertThat(outcome).as("前提：两个模块同日各报一条 ⇒ 必须停下").isInstanceOf(AdvanceOutcome.Stopped.class);
    StopReport report = outcome.stopReport().orElseThrow();

    assertThat(report.reasons()).as("★★ P5b：两个模块同一天各报一条 ⇒ **两条都在**（只报第一条 ⇒ 这里红）").hasSize(2);
    assertThat(report.reasons().stream().map(StopReport.Reason::namespace).toList())
        .as("★ 次序 = 参与者字典序（决定论）")
        .containsExactly("alpha", "beta");
    assertThat(report.reasons().stream().map(StopReport.Reason::kind).toList())
        .containsExactly("alpha.bump", "beta.bump");
    assertThat(alpha.simulateCalls).as("第 1、2 天各跑一次（命中日也把当天**全部**参与者跑完）").isEqualTo(2);
    assertThat(beta.simulateCalls).as("★ 不短路：beta 在命中日必须也被调到").isEqualTo(2);
    assertThat(gamma.simulateCalls).as("★ 不短路：字典序最后的参与者同样被调到").isEqualTo(2);
  }

  // ── P5c / P6：drain（②→③ 的次序）────────────────────────────────────────────────────

  /**
   * ★★ <b>P5c：先做完世界再报</b> —— drain 钩子被调到时，那一天的 revision <b>已经落盘</b>（{@code timeline.row(worldRef)}
   * 非空）且 tick == atTick。
   *
   * <p>★ 钩子里只记录、跑完再断言：{@code TimeAdvance} 对 drain 的异常只 WARN（观察不许改被判事物的结局）， 在钩子里直接 {@code
   * assertThat} 会被吞掉 ⇒ 假绿。
   */
  @Test
  void worldIsAlreadyCommittedWhenTheDrainHookRuns() {
    seedMain(2L);
    RecordingDrain drain = new RecordingDrain(timeline);
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(new ReportingParticipant("alpha", 6L)),
            stateLoader(4L, "alpha"),
            drain);

    route.advance(advanceCmd("cmd-p5c", "corr-p5c", 1L, 4L, 10L));

    assertThat(drain.refs).as("急停 ⇒ drain 恰一次").hasSize(1);
    assertThat(drain.refs.get(0)).isEqualTo(ref("main", 3));
    assertThat(drain.ticks.get(0)).as("atTick = 停下的那一天").isEqualTo(6L);
    assertThat(drain.rowsSeen.get(0))
        .as("★★ P5c：③ 被调到时 ② **已经落盘** —— 钩子内读到的 revision 行非空")
        .isPresent();
    assertThat(drain.rowsSeen.get(0).orElseThrow().timestamp())
        .as("★★ 而且那一行的世界时间戳就是 atTick（世界真的做到了那一天，不是「报了但没做」）")
        .isEqualTo(SimosTimestamp.of(6L));
    assertThat(drain.headsSeen.get(0)).as("钩子内 head 已指向该天").isEqualTo(3L);
  }

  /** ★ <b>P6：drain 生效且只在急停那天调一次</b>；drain 之后若又落了 revision，{@code worldRef} 取**真实 head**。 */
  @Test
  void drainRunsOnceAndWorldRefFollowsTheHeadAfterDraining() {
    seedMain(2L);
    RecordingDrain drain = new RecordingDrain(timeline);
    // ★ 模拟"跨模块效果落地又写了一次世界"：drain 里再落一条 revision（main:4）。
    drain.afterRecord =
        () ->
            timeline.appendRevision(
                revisionRow(ref("main", 4), Optional.of(ref("main", 3)), "cmd-drain", "corr-drain"),
                List.of());
    TimeAdvance route =
        route(
            List.of(new ToyCodec("alpha")),
            List.of(new ReportingParticipant("alpha", 6L)),
            stateLoader(4L, "alpha"),
            drain);

    StopReport report =
        route.advance(advanceCmd("cmd-p6", "corr-p6", 1L, 4L, 10L)).stopReport().orElseThrow();

    assertThat(drain.refs).as("★ P6：急停路径 drain 恰一次（口径 3=A：只接进急停路径）").hasSize(1);
    assertThat(drain.ticks).containsExactly(6L);
    assertThat(report.atTick()).as("drain 不改「停在哪一天」").isEqualTo(6L);
    assertThat(report.worldRef())
        .as("★ worldRef 取 drain **之后**的真实 head（GM 据此 replay 到停下的那一刻）")
        .isEqualTo(ref("main", 4));
  }

  // ── 机械校验：模块报错日子 ⇒ 当场拒 ──────────────────────────────────────────────────

  /**
   * ★ 设计书 §4.3 的机械校验：{@code Incident.tick} 必须 == 当天推进日，否则 {@code Rejected}（**该日不留 revision**）。
   *
   * <p>★ 这条拦的是"atTick 是今天、事件日却是别的天"这种自相矛盾的坐标。判别力：删掉校验 ⇒ 本用例绿。
   */
  @Test
  void incidentReportingTheWrongDayIsRejectedAndLeavesNoRevisionForThatDay() {
    seedMain(2L);
    // 第 6 天报一条 tick=5 的事件（早了一天）。
    ReportingParticipant wrongDay =
        new ReportingParticipant("alpha", 6L, -1L, Map.of("hex", "5_0"));
    RecordingDrain drain = new RecordingDrain(timeline);
    TimeAdvance route =
        route(List.of(new ToyCodec("alpha")), List.of(wrongDay), stateLoader(4L, "alpha"), drain);

    AdvanceOutcome outcome = route.advance(advanceCmd("cmd-badtick", "corr-badtick", 1L, 4L, 10L));

    assertThat(outcome).isInstanceOf(AdvanceOutcome.Rejected.class);
    assertThat(((AdvanceOutcome.Rejected) outcome).rejected().reason())
        .as("理由必须点名这次机械校验")
        .contains("tick");
    assertThat(timeline.row(ref("main", 2)).orElseThrow().timestamp())
        .as("★ 第 5 天（无事发生）已经落了 revision —— 逐日化后前几天的推进是真的")
        .isEqualTo(SimosTimestamp.of(5L));
    assertThat(timeline.row(ref("main", 3))).as("★ I4：报错日子那一天**不留 revision**").isEmpty();
    assertThat(drain.refs).as("被拒 ⇒ 不 drain").isEmpty();
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  private TimeAdvance route(
      List<ModuleCodec> codecs,
      List<TimeParticipant> participants,
      StateLoader loader,
      StopDrain drain) {
    return new TimeAdvance(timeline, loader, checkpoints, codecs, participants, drain);
  }

  private StateLoader stateLoader(long tick, String... namespaces) {
    return ref -> state(1L, tick, namespaces);
  }

  private void seedMain(long checkpointInterval) {
    timeline = new Timeline(store, checkpointInterval);
    timeline.appendRevision(
        revisionRow(ref("main", 1), Optional.empty(), "cmd-seed", "corr-seed"), List.of());
  }

  private static SimulationState state(long revision, long tick, String... namespaces) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (String namespace : namespaces) {
      modules.put(
          namespace, new ToySnapshot(ref("main", revision), SimosTimestamp.of(tick), namespace));
    }
    return new SimulationState(
        new StateMeta(ref("main", revision), SimosTimestamp.of(tick)),
        modules,
        InMemoryInfoSystem.empty());
  }

  /** 一次推进 {@code [4, to)} 的命令（{@code fromTick} 与 base 的世界日一致）。 */
  private static AdvanceTime advanceCmd(
      String commandId, String correlationId, long expectedRevision, long fromTick, long toTick) {
    return new AdvanceTime(
        commandId,
        correlationId,
        "player:local",
        main(),
        new RevisionId(expectedRevision),
        new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick))));
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

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
