package io.mosire.simos.core.advance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.CoreLog;
import io.mosire.simos.core.CoreLogSource;
import io.mosire.simos.core.command.AdvanceRoute;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.StateLoader;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.Incident;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.StopReport;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 两阶段时间推进（spec §5.1 的六步，C25）——{@link AdvanceRoute} 的**真实现**（Task 11 用例里用的是替身）。
 *
 * <p>★ **两阶段的全部意义在 ②**：{@code simulate} 是**纯函数**——拿同一份 base、吐提案，**不写状态**。
 * 故"没有模块能在别人提案之前就把自己的改动落下去"这句话有结构保证：本类在 ② 里除了调 {@code simulate} 什么也不做， 落盘只发生在 ⑤。
 *
 * <p>★★ **2026-10-13 急停协议：本类改成"按日编排、逐日落 revision"**（约束设计书 §4.3，口径 1 = (i)，用户已拍）。
 *
 * <p>**构造**：参与者清单**构造期**按 namespace 字典序定死（C25 / 裁定 44）⇒ 顺序不可能被注册序污染。
 *
 * <p>**每一天的次序（★★ 顺序铁律，用户原话「急停的时候是先世界里做完，然后再报给GM的」）**：
 *
 * <pre>
 * ① Propose   当天**全部**参与者 simulate（同一份当日 base、当日 range）——★ 不许短路、不许发现事件就中断当天
 * ② Resolve   {@link TimeProposalResolver}（写-写 ⇒ 冲突；读-写 ⇒ 留痕放行）
 * ③ Validate  五项机械校验（spec §5.4）
 * ④ Commit    1 行 revision（那天）+ 那天全部事件，一个事务 —— ★ 世界先做完
 * ⑤ Checkpoint 命中了才写（纯派生缓存，与既有口径一致）
 * ⑥ Drain     ★ 仅当当天收到 Incident：把跨模块效果落地（{@link StopDrain}，口径 3 = A）
 * ⑦ Stop      当天 Incident 非空 ⇒ 产出 {@link StopReport}（★ 聚合全部，不是取第一个）并返回 Stopped
 * ⑧ Next      否则把新状态当作下一天的 base，继续
 * </pre>
 *
 * ⇒ **不许**"先报再落"、**不许**"发现第一个事件就中断当天的剩余参与者"（两者都是本设计点名的假绿形态）。
 *
 * <p>★★ **为什么逐日**：口径 1 = (i)（用户：「1 肯定 i 啊」）——世界**真的停在 tick n**、可直接续推，而不是"推完了整段再假装停"。 代价如实记：一次 N
 * 天的推进落 **N 条 revision**（360 tick = 361 条；{@code checkpointInterval=1} 下旧实测 1.45 GB）， 且 N 天上界
 * {@link #MAX_ADVANCE_DAYS} 内的成本与 N 线性（每天一个事务）。
 *
 * <p>★★ **六步的边界（裁定 47 裁过 spec 的一处自相矛盾，见台账）**：
 *
 * <pre>
 * ① Prepare     查 head（乐观并发）→ 装配 base；参与者清单**构造期**已按 namespace 字典序定死（C25 / 裁定 44）
 * ② Propose     逐个 simulate → TimeProposal
 * ③ Resolve     {@link TimeProposalResolver}（写-写 ⇒ 拒；读-写 ⇒ 留痕放行；多切片提案按模块逐项查）
 * ④ Validate    五项机械校验（spec §5.4；第 0 项在 ① 之前——它不查库）
 * ⑤ Commit      1 行 revision + **全部**事件，一个事务
 * ⑥ Post-commit **只剩写 checkpoint** 一件事（失败不影响已落盘的事实）
 * </pre>
 *
 * ★ **`advance.finished` 在 ⑤ 里面，不在 ⑥**——spec 自己打了自己：§5.1 ⑤ 说"全部事件，一个事务"、§7.2 那张 `恰好` 表把 `finished`
 * 排在 `committed` **之前**（`seq` 是自增列，事务外写的行必然排在后面），而 §5.1 ⑥/§5.5 又说它在事务之外。 **2 对 1，按前两条**；且 §5.5
 * 那句的理由"失败不影响已经落盘的事实"**只对 checkpoint 成立**——事件行不是副作用， 它是记录本身。详见台账裁定 47。
 *
 * <p>★★ **不加锁（裁定 48）**：{@code AdvanceTime} **不过** {@code CommandBus} 的信封支（{@code submit} 直接把它
 * 递到本类），故 Task 10 那把 {@code commitLock} **罩不到这里**。本类靠 {@code (branch, revision)} 的**主键**挡并发——
 * 第二笔撞主键 ⇒ 回头读一次 head ⇒ **动了才折成冲突，没动就原样重抛** （后者是要害：无条件折成冲突会把真 bug 伪装成"别人抢先了"，那是最难查的一类假象）。★
 * 按日之后这条**逐日各查一次**：撞在第 k 天 ⇒ 前 k−1 天**已经落盘**（它们是真实推进 的一段），冲突报的是**真实 head**。
 *
 * <p>★ **本类自己记结局日志**（§7.3）：{@code CommandBus} 的 {@code logOutcome} 只在**信封支**调（{@code dispatch}
 * 里），{@code submit} 的 {@code AdvanceTime} 那一支直接 {@code return advanceRoute.run(...)} ⇒
 * "命令接收/拒绝/冲突/提交"这四项**这一支一条都不会被记**。故结局各记一行 INFO，一条命令**恰好一行** （{@code ADVANCE_END} / {@code
 * ADVANCE_REJECTED} / {@code ADVANCE_CONFLICTED} / {@code ADVANCE_STOPPED}）。 日志纪律与 {@code
 * CommandBus} 同口径：不落载荷明文，只记结构信息——★ {@link Incident#payload()} **绝不进文本日志**。
 *
 * <p>★ **一次 N 天、内部逐日**（2026-09-25 §十一 裁定，2026-10-13 由"内部逐日、只落一条"改为"**逐日落**"）：本类只执行**所有模块共用**
 * 的步长规则，两条：① 纯语法——{@code 1 ≤ N ≤ }{@link #MAX_ADVANCE_DAYS}（{@code N = to − from}；与"缺 to ⇒ 拒"同在 ④ 第
 * 0 项，不查库）；② 连续性——{@code range.from.tick == base 状态的时间戳 tick}（要 base 才知道，故排在装配状态之后、 simulate 之前）。
 *
 * <p>★★ **结算语义不跳日**：各模块的日结算仍由各参与者在自己内部逐日推进（经济：逐日消费/进度/周期末收获；SD：逐日评估触发与阶段；
 * unit：时间预算公式本身就是跨日累计）。判据是**等价性**：{@code advance(from, from+N)} 的**终态** == N 次单日 advance 的终态（**不变量
 * I2**）；★ 新判据必须**逐日自足**，不得引入跨日瞬态记忆。
 */
public final class TimeAdvance implements AdvanceRoute {

  /** 推进六阶段与结局的发射通道（来源 = {@link CoreLogSource#TICK_ADVANCE}，粗分类 = tick）。 */
  private static final LogChannel PHASE = EventLog.channel(CoreLog.advance());

  /** 逐提案明细的发射通道（来源同上；默认关）。 */
  private static final LogChannel TRACE = EventLog.channel(CoreLog.trace());

  /** 检查点事件的发射通道（来源 = {@link CoreLogSource#CHECKPOINT}）。 */
  private static final LogChannel STORE = EventLog.channel(CoreLog.store());

  /**
   * 单次推进的**理智上限**（天）：{@code 1 ≤ N ≤ 36500}（§十一：约 100 年的日步长）。
   *
   * <p>★ 它挡的是"把 100 万天塞进一条 revision"这类手滑/恶意输入——一次推进内部的逐日结算成本与 N 线性， 不设上界则一条命令就能把进程拖死。越过上界 ⇒ {@code
   * Rejected}，理由里点名这个字面量。★ 逐日落 revision 之后这条上界同时是"最多落多少条 revision"的上界。
   */
  public static final long MAX_ADVANCE_DAYS = 36500L;

  /**
   * 事件载荷的序列化器：**独立一台**、不带 {@code @JsonTypeInfo}——载荷全是 {@code String}，没有模块类型参与， 故不需要模块 mixin（与
   * {@code changeset_json} 那台的分工不同，见裁定 39）。与 {@code CommandBus} 同法。
   */
  private static final ObjectMapper EVENT_MAPPER = SimosObjectMapper.create();

  private final Timeline timeline;
  private final StateLoader stateLoader;
  private final CheckpointStore checkpoints;
  private final Map<String, ModuleCodec> codecs;

  /** ★ **已按 namespace 字典序排好**（C25）。构造期定死 ⇒ ① 的"参与者清单"不可能被调用方的入参顺序污染。 */
  private final List<TimeParticipant> participants;

  /** ★ 急停路径的 drain 钩子（口径 3 = A）。未装配 ⇒ {@link StopDrain#NONE}（急停仍停、仍报，只是不 drain）。 */
  private final StopDrain stopDrain;

  /**
   * @param timeline 时间线（查 head、落 revision 与事件、判 checkpoint「应当有」）
   * @param stateLoader "给定坐标给状态"（裁定 34；装配时传 {@code replay::replay}）
   * @param checkpoints checkpoint 的文件读写（C18：缺失 ⇒ 回退，不失败）
   * @param codecs 各领域模块的 codec；**必须覆盖 base 状态与全部提案的 namespace**，否则 ④ 判 {@code Rejected}、 ④ 通过后的 ⑥
   *     写不出档（后者只 WARN，见 {@link #writeCheckpointIfDue}）
   * @param participants 参与者清单，顺序**在此定死**（C25 / 裁定 44）
   * @throws IllegalArgumentException {@code codecs} 或 {@code participants} 里有重复的 namespace
   */
  public TimeAdvance(
      Timeline timeline,
      StateLoader stateLoader,
      CheckpointStore checkpoints,
      Collection<ModuleCodec> codecs,
      Collection<TimeParticipant> participants) {
    this(timeline, stateLoader, checkpoints, codecs, participants, StopDrain.NONE);
  }

  /**
   * ★ **带 drain 钩子的装配**（2026-10-13 急停协议）。{@code TimeAdvance} 保五参构造器 ⇒ 既有装配点（{@code CoreSimos} +
   * 测试夹具）**零改动**（与 I7 同一条"加字段/加参数配兼容入口"的纪律）。
   *
   * @param stopDrain 急停路径的 drain（口径 3 = A）；未装配传 {@link StopDrain#NONE}
   */
  public TimeAdvance(
      Timeline timeline,
      StateLoader stateLoader,
      CheckpointStore checkpoints,
      Collection<ModuleCodec> codecs,
      Collection<TimeParticipant> participants,
      StopDrain stopDrain) {
    this.timeline = Objects.requireNonNull(timeline, "timeline");
    this.stateLoader = Objects.requireNonNull(stateLoader, "stateLoader");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    Objects.requireNonNull(codecs, "codecs");
    Objects.requireNonNull(participants, "participants");
    this.stopDrain = Objects.requireNonNull(stopDrain, "stopDrain");

    Map<String, ModuleCodec> codecTable = new LinkedHashMap<>();
    for (ModuleCodec codec : codecs) {
      Objects.requireNonNull(codec, "codecs 的元素");
      ModuleCodec previous = codecTable.put(codec.namespace(), codec);
      if (previous != null) {
        // ★ 静默覆盖会让"路由到哪个 codec"取决于集合迭代序——同一个提案可能被两个 codec 解释出不同结果
        throw new IllegalArgumentException("codec 表里 namespace 重复: " + codec.namespace());
      }
    }
    this.codecs = Map.copyOf(codecTable);

    // ★ C25 / 裁定 44：SimulationState.modules() 是 Map.copyOf，**迭代序不是键集的纯函数**（M2 Task 5 实测
    //   30 次）。故参与者清单**显式排序**，绝不靠遍历 modules() 或注册表的插入序。
    TreeMap<String, TimeParticipant> sorted = new TreeMap<>();
    for (TimeParticipant participant : participants) {
      Objects.requireNonNull(participant, "participants 的元素");
      TimeParticipant previous = sorted.put(participant.namespace(), participant);
      if (previous != null) {
        throw new IllegalArgumentException("参与者 namespace 重复: " + participant.namespace());
      }
    }
    this.participants = List.copyOf(sorted.values());
  }

  /**
   * {@link AdvanceRoute} 的那条口：**折成铁律 2 的公共契约**（{@code CommandResult} 三种封闭）。
   *
   * <p>★ 它与 {@link #advance} 的关系：{@code run} = {@code advance} 的**降级视图**。急停时世界确实提交到 {@code atTick}
   * ⇒ 折成那一天的 {@link CommandResult.Committed}。★ **代价如实记**：这条折法**丢掉 {@link StopReport}**——
   * 要看急停原因，调用方必须走 {@link #advance}（{@code CoreSimos.submitAdvance}）。
   */
  @Override
  public CommandResult run(AdvanceTime cmd) {
    return advance(cmd).commandResult();
  }

  /**
   * ★ **按日编排的推进**（口径 1 = (i) + 顺序铁律）：逐日跑完全部参与者、逐日落 revision、逐日**先做完世界再收事件**。
   *
   * <p>返回值是四种结局的 {@link AdvanceOutcome}（{@code CommandResult} 三种封闭未动，不变量 I3）。
   *
   * @throws NullPointerException {@code cmd} 为 null
   */
  public AdvanceOutcome advance(AdvanceTime cmd) {
    Objects.requireNonNull(cmd, "cmd");

    // ④ 第 0 项：**先做**——它不查库、不碰状态，是纯语法判定。缺 to ⇒ 无上界的推进落不成一条 revision
    //   （revision 的 tick 是确定值）。
    List<EventRow> trace = new ArrayList<>();
    trace.add(received(cmd));
    if (cmd.range().to().isEmpty()) {
      return rejectedOutcome(
          cmd, trace, "推进必须有上界（range.to 缺失）：AdvanceTime 是写操作，语义上不允许开区间", startTick(cmd));
    }

    // ★ 第 0 项的第二条（同一层的纯语法判定）：**一次推进 N 天**（N ≥ 1 且 N ≤ MAX_ADVANCE_DAYS）。
    long fromTick = cmd.range().from().tick();
    long toTick = cmd.range().to().orElseThrow().tick();
    long spanDays = toTick - fromTick;
    if (spanDays < 1L || spanDays > MAX_ADVANCE_DAYS) {
      return rejectedOutcome(
          cmd,
          trace,
          "推进天数须满足 1 ≤ N ≤ "
              + MAX_ADVANCE_DAYS
              + "（N = to − from）: from="
              + fromTick
              + "，to="
              + toTick
              + "，N="
              + spanDays,
          fromTick);
    }

    // ① Prepare：入口乐观并发检查（C17 的同一条）。AdvanceTime 不过信封支 ⇒ 这里必须自己查一次，
    //   否则"期望坐标"这个参数对本命令形同虚设。
    Optional<RevisionId> head = timeline.head(cmd.branch());
    if (head.isEmpty()) {
      return rejectedOutcome(cmd, trace, "分支不存在: " + cmd.branch().value(), fromTick);
    }
    if (head.get().compareTo(cmd.expectedRevision()) != 0) {
      return conflictOutcome(
          cmd,
          trace,
          new StateRef(cmd.branch(), head.get()),
          fromTick,
          "期望坐标已过期（head 被别人推走）: 期望="
              + cmd.expectedRevision().value()
              + "，实得="
              + head.get().value());
    }

    StateRef base = new StateRef(cmd.branch(), head.get());
    SimulationState state = stateLoader.load(base);

    // ★ 连续性（一天一步的第二条）：range.from 必须等于 base 的时间戳。只比 tick，不比 calendarLabel
    //   ——label 是显示信息（如"正午"），客户端的 from 通常不带 label；把它纳入相等判据会让合法推进被拒。
    if (cmd.range().from().tick() != state.meta().timestamp().tick()) {
      return rejectedOutcome(
          cmd,
          trace,
          "推进必须从当前世界日出发（range.from = base 的时间戳）: from="
              + cmd.range().from().tick()
              + "，base="
              + state.meta().timestamp().tick(),
          fromTick);
    }

    PHASE.debug(
        LogEvent.of(
            "ADVANCE_START",
            CoreLogSource.TICK_ADVANCE,
            "day",
            toTick,
            "commandId",
            cmd.commandId(),
            "correlationId",
            cmd.correlationId(),
            "branch",
            cmd.branch().value(),
            "expectedRevision",
            cmd.expectedRevision().value(),
            "from",
            fromTick,
            "to",
            toTick,
            "spanDays",
            spanDays,
            "participants",
            participants.size(),
            "participantOrder",
            participants.stream().map(TimeParticipant::namespace).toList()));

    // ★ 逐日循环：`trace` 的第一份带着 `received`（它属于**这一条命令**，只落一次）；此后每天另起一份。
    long committedRevision = base.revision().value();
    StateRef lastCommitted = base;

    for (long day = fromTick + 1; day <= toTick; day++) {
      boolean firstDay = day == fromTick + 1;
      boolean lastDay = day == toTick;
      StateRef dayBase = new StateRef(cmd.branch(), new RevisionId(committedRevision));
      StateRef dayTarget = new StateRef(cmd.branch(), new RevisionId(committedRevision + 1));
      SimosTimestamp dayFrom = firstDay ? cmd.range().from() : SimosTimestamp.of(day - 1);
      SimosTimestamp dayTo = lastDay ? cmd.range().to().orElseThrow() : SimosTimestamp.of(day);
      StateMeta dayMeta = new StateMeta(dayTarget, dayTo);
      TimeRange dayRange = new TimeRange(dayFrom, Optional.of(dayTo));

      // ① Propose：**纯函数**，每个参与者拿到的都是同一份 base（C25：顺序不影响结果）。
      //   ★ 当天**全部**参与者都要跑完 —— 不许"发现第一个事件就中断当天剩余参与者"（顺序铁律）。
      //   ★ 只调 simulateWorld：单模块参与者的默认实现把 simulate 的结果包成单模块提案（既有模块零改动，
      //     且 `WorldTimeProposal.single` 会把 incidents 一并带过来）。
      List<WorldTimeProposal> proposals = new ArrayList<>();
      for (TimeParticipant participant : participants) {
        WorldTimeProposal proposal = participant.simulateWorld(state, dayRange);
        if (proposal == null) {
          return rejectedOutcome(
              cmd,
              trace,
              "参与者的提案为 null（装配错误）: " + participant.namespace() + "（第 " + day + " 天）",
              day);
        }
        proposals.add(proposal);
      }
      if (firstDay) {
        // ★ 与既有次序逐字一致：`started` 在 propose **之后**（"这一次推进有谁参与"要真的问过才知道）。
        trace.add(started(cmd, dayMeta));
      }

      // ④ 收齐当天**全部** Incident（聚合，不是取第一个）。★ core 只看 `Incident` 这个 util 类型，
      //   **不解释任何 kind**（不变量 I5：util/core 不得出现具体 kind 的字面量判断）。
      List<Incident> incidents = aggregateIncidents(proposals);
      // ★ 机械校验（与"提案为 null""未注册 namespace"同族）：模块报的 `tick` 必须就是**今天**。
      //   否则 StopReport 会带着"atTick=今天、事件日=别的天"这种自相矛盾的坐标 —— 宁可当场拒。
      for (Incident incident : incidents) {
        if (incident.tick() != day) {
          return rejectedOutcome(
              cmd,
              trace,
              "Incident 的 tick 与正在推进的当天不符（模块报错了日子）: kind="
                  + incident.kind()
                  + "，namespace="
                  + incident.namespace()
                  + "，incident.tick="
                  + incident.tick()
                  + "，day="
                  + day,
              day);
        }
      }
      // ★ 当天是否有事要停：**先知道、后落盘**——"停"这个决定不改变 ② 的次序（世界照样先做完）。
      boolean stopping = !incidents.isEmpty();

      PHASE.debug(
          LogEvent.of(
              "ADVANCE_PROPOSE",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "proposals",
              proposals.size(),
              "incidents",
              incidents.size()));
      if (TRACE.isTraceEnabled()) {
        for (WorldTimeProposal proposal : proposals) {
          TRACE.trace(
              LogEvent.of(
                  "ADVANCE_PROPOSAL",
                  CoreLogSource.TICK_ADVANCE,
                  "day",
                  day,
                  "participant",
                  proposal.participantId(),
                  "modules",
                  proposal.moduleChanges().size(),
                  "reads",
                  proposal.reads().size(),
                  "writes",
                  proposal.writes().size(),
                  "incidents",
                  proposal.incidents().size()));
        }
      }
      // ★★ 2026-09-30 用户裁定：**module.proposal 只留在内存里**，不再落事件表。
      //   `proposals` 列表继续供 ③ Resolve 判读写冲突；但它的全量地址清单不再逐条持久化——
      //   实测 360 tick 里它占 store 的 209/363MB（advance 次数 × namespace 数 × 万级地址串）。

      // ③ Resolve
      TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(proposals);
      if (outcome instanceof TimeProposalResolver.Outcome.Blocked blocked) {
        trace.add(conflictEvent(cmd, blocked.conflict()));
        String detail =
            String.join(" ∩ ", blocked.conflict().namespaces())
                + " 于 "
                + blocked.conflict().addresses();
        if (firstDay) {
          // ★ 世界**一天都没动** ⇒ 与既有的"拒绝整次推进"逐字同义（也保住了既有 R9 用例的判据）。
          return rejectedOutcome(cmd, trace, "写-写冲突，拒绝整次推进: " + detail, day);
        }
        // ★ 按日之后的 Q-B：**停在冲突前一天 + 报冲突**（不静默跳过）。前 k−1 天是真实推进的一段，留着；
        //   冲突那天的 revision **一行不留**（I4）。真实 head = 前一天。
        return conflictOutcome(
            cmd,
            trace,
            lastCommitted,
            day,
            "写-写冲突，停在第 " + (day - 1) + " 天（冲突发生在第 " + day + " 天，该日不落 revision）: " + detail);
      }
      TimeProposalResolver.Outcome.Resolved resolved =
          (TimeProposalResolver.Outcome.Resolved) outcome;
      for (AdvanceConflict warning : resolved.warnings()) {
        trace.add(conflictEvent(cmd, warning)); // 读-写：只留痕，不拒绝
      }
      PHASE.debug(
          LogEvent.of(
              "ADVANCE_RESOLVE",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "blocked",
              false,
              "readWriteWarnings",
              resolved.warnings().size()));

      // ④ Validate（第 1~4 项；第 0 项在上面）
      Validation validation = validate(state, proposals, dayMeta);
      if (validation.failed()) {
        return rejectedOutcome(cmd, trace, "第 " + day + " 天校验失败: " + validation.error(), day);
      }
      PHASE.debug(
          LogEvent.of(
              "ADVANCE_VALIDATE",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "modules",
              validation.applied().size(),
              "ok",
              true));

      // ⑤ Commit：1 行 revision + **全部**事件，一个事务（裁定 47：`finished` 也在里面）。
      //   ★ `finished` 落在**终结日**（整段走完的最后一天，或急停停下的那天——急停是一段真实推进的终点，I4）。
      boolean terminalDay = lastDay || stopping;
      if (terminalDay) {
        trace.add(finished(cmd, dayTarget));
      }
      trace.add(committed(cmd, dayTarget));
      try {
        timeline.appendRevision(
            revisionRow(cmd, dayBase, dayTarget, dayMeta, resolved.changeSet()), trace);
      } catch (IllegalStateException e) {
        // 裁定 48：**动了才折成冲突，没动就原样重抛**
        Optional<RevisionId> moved;
        try {
          moved = timeline.head(cmd.branch());
        } catch (RuntimeException reReadFailure) {
          // ★ 连"head 动了没有"都问不出来 ⇒ **不得声称冲突**——那会把一个未知故障伪装成"别人抢先了"，
          //   正是本裁定要防的那类假象。原样上报提交失败，把这次读失败挂在 suppressed 上（别丢掉它）。
          e.addSuppressed(reReadFailure);
          throw e;
        }
        if (moved.isPresent() && moved.get().compareTo(dayBase.revision()) != 0) {
          // ★ 事件链重新起算：这一条事务已整体回滚，它的事件一行都没落。第 1 天的事件连 `received` 一起重来
          //   （`received` 也在被回滚的那条事务里）；第 k>1 天**不补** `received`/`started`——前 k−1 天的事件
          //   **已经提交**，再补一次就是库里多一句假话。与信封支的冲突形态同形。
          List<EventRow> conflictTrace = new ArrayList<>();
          if (firstDay) {
            conflictTrace.add(received(cmd));
          }
          return conflictOutcome(
              cmd,
              conflictTrace,
              new StateRef(cmd.branch(), moved.get()),
              day,
              "提交撞上并发（期望 " + dayBase.revision().value() + "，实得 " + moved.get().value() + "）");
        }
        throw e;
      }
      PHASE.debug(
          LogEvent.of(
              "ADVANCE_COMMIT",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "branch",
              dayTarget.branch().value(),
              "revision",
              dayTarget.revision().value(),
              "events",
              trace.size()));

      // ⑥ Post-commit：**只剩 checkpoint**（C19 命中才写）。它失败不影响已落盘的事实。
      SimulationState dayState =
          new SimulationState(dayMeta, mergedModules(state, validation.applied()), state.info());
      writeCheckpointIfDue(dayTarget, dayState);

      committedRevision = dayTarget.revision().value();
      lastCommitted = dayTarget;

      // ⑦ 急停：当天 Incident 非空 ⇒ **先 drain（跨模块效果落地）再报**，然后停在**已经做完的那天**。
      if (stopping) {
        StateRef worldRef = drainAfterStop(dayTarget, day);
        StopReport report = StopReport.of(day, worldRef, incidents);
        PHASE.info(
            LogEvent.of(
                "ADVANCE_STOPPED",
                CoreLogSource.TICK_ADVANCE,
                "day",
                day,
                "commandId",
                cmd.commandId(),
                "correlationId",
                cmd.correlationId(),
                "branch",
                dayTarget.branch().value(),
                "revision",
                dayTarget.revision().value(),
                "from",
                fromTick,
                "to",
                toTick,
                "spanDays",
                spanDays,
                "advancedTo",
                day,
                "reasons",
                incidents.size(),
                "namespaces",
                incidents.stream().map(Incident::namespace).distinct().toList(),
                "worldRefRevision",
                worldRef.revision().value()));
        return new AdvanceOutcome.Stopped(report, new CommandResult.Committed(dayTarget));
      }

      // ⑧ 下一天的 base = 这一天做完的世界。
      state = dayState;
      trace = new ArrayList<>();
    }

    // ★ 2026-10-23：推进 END（START 见上面 ADVANCE_START）——一条完整的"这一轮从哪到哪、落了什么、参与者几条"。
    PHASE.info(
        LogEvent.of(
            "ADVANCE_END",
            CoreLogSource.TICK_ADVANCE,
            "day",
            toTick,
            "commandId",
            cmd.commandId(),
            "correlationId",
            cmd.correlationId(),
            "branch",
            lastCommitted.branch().value(),
            "revision",
            lastCommitted.revision().value(),
            "from",
            fromTick,
            "to",
            toTick,
            "spanDays",
            spanDays,
            "participants",
            participants.size(),
            "participantOrder",
            participants.stream().map(TimeParticipant::namespace).toList()));
    return new AdvanceOutcome.Committed(new CommandResult.Committed(lastCommitted));
  }

  // ── 当天的 Incident 聚合 ────────────────────────────────────────────────────────────

  /**
   * ★ **聚合当天全部参与者自报的 Incident**（顺序铁律的 ④；不是"取第一个"）。
   *
   * <p>次序 = 参与者字典序（构造期定死）× 各自上报次序 ⇒ **决定论**：同一个世界同一批提案，{@link StopReport#reasons()} 的字节序每次都一样。
   *
   * <p>★ 不去重、不截断：一个模块报三件事就三条原因——"每个模块各一条"在"每个模块各报一个"时自然成立，而**丢掉两件**才是真损失。
   */
  private static List<Incident> aggregateIncidents(List<WorldTimeProposal> proposals) {
    List<Incident> incidents = new ArrayList<>();
    for (WorldTimeProposal proposal : proposals) {
      incidents.addAll(proposal.incidents());
    }
    return List.copyOf(incidents);
  }

  // ── ④ Validate（spec §5.4；Core 不懂领域语义，故一条都不涉及"这个变更集对不对"）────────────────────

  /** 校验结局：{@code error == null} 表示通过；{@code applied} 是**已 apply 过的**快照（⑥ 直接复用，不重算）。 */
  private record Validation(Map<String, Snapshot> applied, String error) {

    static Validation ok(Map<String, Snapshot> applied) {
      return new Validation(Map.copyOf(applied), null);
    }

    static Validation fail(String error) {
      return new Validation(Map.of(), error);
    }

    boolean failed() {
      return error != null;
    }
  }

  /**
   * ★ **第 1~4 项**。任一项失败 ⇒ {@code Rejected}，**该日不产生 revision**（R9/R14 的"拒绝是原子的"）。
   *
   * <p>★ 按日之后如实记一处口径：**该日**不落 revision 是保证；但若前 k−1 天已经落过，它们**留着**——那是真实推进的一段，
   * 不在"被拒是原子的"的射程内（那条说的是"被拒的这一天不半途留痕"）。
   *
   * <p>★ 第 4 项**不是装饰**：{@link ModuleCodec#apply} 的第三参 {@code newMeta} 由本类**在 Commit 前算好**， codec
   * 照它产出新快照；随后本项核对 codec **真的用了它**。最常犯的错是"照抄 base 的坐标"——那会让新 revision 的快照带着**旧坐标**落进
   * checkpoint，重放时状态树与坐标对不上。这一项就是拦它的（C28 的兑现）。
   *
   * <p>★★ **与 spec 的一处有意偏离，如实记**：spec §5.4 第 2 项写的是"`ChangeSet`（**非空时**）能被该 codec `encodeChangeSet`
   * 成功"。那个"非空时"在 Core 里**不可实现**——{@code ChangeSet} 是**标记接口**（实测：接口体为空）， **没有
   * `isEmpty()`**；三个模块的变更集各自有 `public boolean isEmpty()`（裁定 39 记过它们被 Jackson 内省成属性 `empty`），但要调到它就得
   * {@code instanceof} 领域类型——**ADR-1 明令禁止**。 ⇒ 本类**无条件**校验编码，**严格更严**（空变更集若编不出来，那本身就该拒）。
   *
   * <p>★★ **逐模块校验（多切片提案，2026-09-24 日制裁定）**：一个参与者可以带多个模块的变更集，四项校验对 {@code moduleChanges}
   * 的**每一个键**各跑一遍——任一模块不过 ⇒ **整日提案被拒**，六切片都不落盘（原子性就在这里）。
   */
  private Validation validate(
      SimulationState state, List<WorldTimeProposal> proposals, StateMeta newMeta) {
    Map<String, Snapshot> applied = new LinkedHashMap<>();
    for (WorldTimeProposal proposal : proposals) {
      for (Map.Entry<String, ChangeSet> moduleChange : proposal.moduleChanges().entrySet()) {
        String namespace = moduleChange.getKey();
        ChangeSet changeSet = moduleChange.getValue();

        // 第 1 项：namespace 有已注册的 ModuleCodec
        ModuleCodec codec = codecs.get(namespace);
        if (codec == null) {
          return Validation.fail("未注册的 namespace（无 ModuleCodec）: " + namespace);
        }

        // 第 2 项：变更集编得出来
        try {
          codec.encodeChangeSet(changeSet);
        } catch (RuntimeException e) {
          return Validation.fail("变更集落不了盘（encodeChangeSet 抛）: namespace=" + namespace + " 原因=" + e);
        }

        // 第 3 项：codec.apply(cs, baseSnapshot) 不抛
        Optional<Snapshot> moduleBase = state.module(namespace);
        if (moduleBase.isEmpty()) {
          return Validation.fail("base 状态里没有该 namespace 的快照: " + namespace);
        }
        Snapshot appliedSnapshot;
        try {
          appliedSnapshot = codec.apply(changeSet, moduleBase.get(), newMeta);
        } catch (RuntimeException e) {
          return Validation.fail("codec.apply 抛异常: namespace=" + namespace + " 原因=" + e);
        }
        if (appliedSnapshot == null) {
          return Validation.fail("codec.apply 返回 null: " + namespace);
        }

        // 第 4 项：namespace() 与键一致，且 ref()/timestamp() == newMeta
        //   ★ 核对的是 Snapshot.ref() 与 Snapshot.timestamp()——StateMeta 只有这两件，**没有独立的 tick 字段**
        //     （台账"给 Task 12 的两条形状更正"实测）。
        if (!namespace.equals(appliedSnapshot.namespace())) {
          return Validation.fail(
              "apply 后的快照 namespace 与键不一致: 键=" + namespace + " 快照=" + appliedSnapshot.namespace());
        }
        if (!newMeta.ref().equals(appliedSnapshot.ref())
            || !newMeta.timestamp().equals(appliedSnapshot.timestamp())) {
          return Validation.fail(
              "apply 后的快照没被填入新的坐标（codec 照抄了 base 的）: namespace="
                  + namespace
                  + " 期望="
                  + newMeta.ref()
                  + "@"
                  + newMeta.timestamp()
                  + " 实得="
                  + appliedSnapshot.ref()
                  + "@"
                  + appliedSnapshot.timestamp());
        }
        applied.put(namespace, appliedSnapshot);
      }
    }
    return Validation.ok(applied);
  }

  /** 当天做完的世界：base 的模块表 + 本次 apply 过的那些（其余模块**保持原样、不推进坐标**，与 replay 同语义）。 */
  private static Map<String, Snapshot> mergedModules(
      SimulationState base, Map<String, Snapshot> applied) {
    Map<String, Snapshot> modules = new LinkedHashMap<>(base.modules());
    modules.putAll(applied); // 只覆盖本次推进动过的模块
    return modules;
  }

  // ── ⑥ checkpoint ────────────────────────────────────────────────────────────────────

  /**
   * C19 命中才写。{@code Timeline.hasCheckpoint} 是**纯函数判定**（C18 的关键：这里判"**应当**有"，读侧判"**实际**有"）。
   *
   * <p>★ 信封的 meta 用当天的 {@code dayState}（新坐标 + 当天的时刻）——**不是** {@code base.meta()}：一条 {@code (b, r)}
   * 的 checkpoint 描述的是"重放到 (b, r) 的结果"，坐标写错会让读侧拿去当别的坐标用。 而**模块快照各自保留自己的坐标**（只有本推进动过的那些才被覆盖成 当天的
   * meta）——这与 {@code Replay.applyWorld} 的语义一致：变更集里没提到的模块**保持原样、不推进坐标**。两者不同源会让 R4（从 checkpoint 重放
   * == 从创世重放）当场破。
   *
   * <p>★★ **失败只 WARN，不上抛**（C24 / §3.4 ④ 的明文要求）：checkpoint 是**纯派生缓存**（C18），丢了只赔重放时间—— {@code Replay}
   * 本来就会回退到更早的 checkpoint、最坏从创世重放。而此刻 **⑤ 已经把 revision 落盘了**，
   * 异常若逃出本方法，调用方会看到"提交失败"却库里明明有一行——**观察绝不许改被判事物的结局**（与 {@code CommandBus} 把日志挪到锁外是同一条纪律）。
   *
   * <p>★ 为什么这不是"静默吞掉"：① 这里记 WARNING（装配/I/O 故障档，带异常类名）；② 缺档在读侧另有 DEBUG（ {@code
   * CheckpointStore.read}）；③ 档的存在性有 R3 那条"判定与文件逐条一致"的护栏。三处都看得见。
   */
  private void writeCheckpointIfDue(StateRef target, SimulationState state) {
    if (!timeline.hasCheckpoint(target)) {
      return;
    }
    try {
      String envelopeJson = CheckpointEncoder.encode(state, codecs.values());
      checkpoints.write(target, envelopeJson);
      // ★ 2026-10-23：成功一行由 CheckpointStore.write 的 CHECKPOINT_WRITTEN 统一发射（本处原 LOG.debug 与它
      //   是同一事实，删去以免重复记）。
    } catch (RuntimeException e) {
      // I/O 失败（C24 预料之内）与装配缺口（信封里有本实例没装 codec 的模块）都走到这里。
      STORE.warn(
          LogEvent.of(
              "CHECKPOINT_WRITE_FAILED",
              CoreLogSource.CHECKPOINT,
              "branch",
              target.branch().value(),
              "revision",
              target.revision().value(),
              "error",
              e.getClass().getSimpleName()));
    }
  }

  // ── ⑦ 急停路径的 drain ──────────────────────────────────────────────────────────────

  /**
   * ★ **顺序铁律的 ③**：世界已落 revision、**尚未报给 GM** 时，把跨模块效果落地（口径 3 = A）。
   *
   * <p>★★ **失败不改已落盘的事实、也不吞掉急停**：此刻 revision 已经落盘（观察绝不许改被判事物的结局，与 checkpoint 同一条纪律），
   * 而"停下"这件事**必须**报出去（否则 GM 以为世界还在走）。故只记 WARN，然后照常产出 {@link StopReport}。★ 不静默：有人看得见。
   *
   * <p>★ **worldRef 取 drain 之后的真实 head**：drain 若又提交了新 revision（跨模块效果落地本身可能写世界），StopReport 的坐标
   * 必须指到"停下的那一刻"的真实世界（GM 据此 replay）。头读不到 ⇒ 退回本日目标（不编坐标、不上抛）。
   */
  private StateRef drainAfterStop(StateRef stopTarget, long day) {
    try {
      stopDrain.drainAfterStop(stopTarget, day);
    } catch (RuntimeException e) {
      PHASE.warn(
          LogEvent.of(
              "ADVANCE_STOP_DRAIN_FAILED",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "branch",
              stopTarget.branch().value(),
              "revision",
              stopTarget.revision().value(),
              "error",
              e.getClass().getSimpleName()));
    }
    try {
      return timeline
          .head(stopTarget.branch())
          .map(revision -> new StateRef(stopTarget.branch(), revision))
          .orElse(stopTarget);
    } catch (RuntimeException e) {
      PHASE.warn(
          LogEvent.of(
              "ADVANCE_STOP_WORLDREF_READ_FAILED",
              CoreLogSource.TICK_ADVANCE,
              "day",
              day,
              "branch",
              stopTarget.branch().value(),
              "revision",
              stopTarget.revision().value(),
              "error",
              e.getClass().getSimpleName()));
      return stopTarget;
    }
  }

  // ── 事件的构造（§7.1 冻结的八类里本任务用到的六类）──────────────────────────────────────

  /**
   * 命令到达。
   *
   * <p>★ 载荷只放 Core **确实知道**的项（与 {@code CommandBus.received} 同口径：目标地址/耗时/M4 记不全的项
   * **如实留缺，不编值**）。与信封支的差别是这里**有**参数可记——{@code range} 是 Core 自己的类型，不是不透明的领域载荷， 故落 {@code
   * from}/{@code to} 而不是一个摘要。
   *
   * <p>★ 用 {@link LinkedHashMap} 而非 {@code Map.of}：{@code Map.of} 的迭代序**不是键集的纯函数** （其实现的遍历序随 JVM
   * 启动时的 salt 变），落出来的载荷文本会逐次不同。同一纪律在 {@code CommandBus.received} 那边**尚未落实**，如实记为 Task 15 的复核项。
   */
  private static EventRow received(AdvanceTime cmd) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("commandId", cmd.commandId());
    payload.put("type", "core.AdvanceTime");
    payload.put("branch", cmd.branch().value());
    payload.put("expectedRevision", Long.toString(cmd.expectedRevision().value()));
    payload.put("from", cmd.range().from().toString());
    payload.put("to", cmd.range().to().map(Object::toString).orElse(""));
    return event(EventTypes.COMMAND_RECEIVED, cmd, json(payload));
  }

  /** 推进开始。★ 参与者清单落进载荷——事后看事件就知道"这一次有谁参与"。 */
  private EventRow started(AdvanceTime cmd, StateMeta newMeta) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("branch", cmd.branch().value());
    payload.put("expectedRevision", Long.toString(cmd.expectedRevision().value()));
    payload.put("to", newMeta.timestamp().toString());
    payload.put("participants", participants.stream().map(TimeParticipant::namespace).toList());
    return event(EventTypes.TIME_ADVANCE_STARTED, cmd, json(payload));
  }

  /**
   * 冲突留痕：{@code {kind, namespaces, addresses}}（spec §7.1 冻结的载荷形状，序已由 {@link AdvanceConflict} 定死）。
   */
  private static EventRow conflictEvent(AdvanceTime cmd, AdvanceConflict conflict) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", conflict.kind());
    payload.put("namespaces", conflict.namespaces());
    payload.put("addresses", conflict.addresses());
    return event(EventTypes.TIMELINE_CONFLICT, cmd, json(payload));
  }

  private static EventRow finished(AdvanceTime cmd, StateRef target) {
    return event(EventTypes.TIME_ADVANCE_FINISHED, cmd, json(refJson(target)));
  }

  private static EventRow committed(AdvanceTime cmd, StateRef target) {
    return event(EventTypes.COMMAND_COMMITTED, cmd, json(refJson(target)));
  }

  private static RevisionRow revisionRow(
      AdvanceTime cmd,
      StateRef base,
      StateRef target,
      StateMeta newMeta,
      WorldChangeSet changeSet) {
    return new RevisionRow(
        target.branch(),
        target.revision(),
        Optional.of(base),
        newMeta.timestamp(),
        cmd.commandId(),
        cmd.correlationId(),
        cmd.initiator(),
        "core.AdvanceTime",
        Timeline.changeSetJson(changeSet));
  }

  /** ★ {@code correlationId} **逐字节取自命令**——这一行是判据二的全部（与 Task 11 同一条纪律）。 */
  private static EventRow event(String type, AdvanceTime cmd, String payload) {
    return EventRow.of(type, cmd.initiator(), payload, cmd.correlationId());
  }

  private static Map<String, String> refJson(StateRef ref) {
    return Map.of(
        "branch", ref.branch().value(), "revision", Long.toString(ref.revision().value()));
  }

  private static String json(Map<String, ?> fields) {
    try {
      return EVENT_MAPPER.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("事件载荷序列化失败（字段全是 String 与 List<String>，不该发生）", e);
    }
  }

  // ── 两条"当天不留 revision 行"的结局 ───────────────────────────────────────────────

  /** 推进起点的世界日（日志字段用；语法拒绝时连 base 都没装）。 */
  private static long startTick(AdvanceTime cmd) {
    return cmd.range().from().tick();
  }

  /** 被拒：落 {@code trace + rejected}（**该日不留 revision 行**，R9/R14），再折结局。 */
  private AdvanceOutcome rejectedOutcome(
      AdvanceTime cmd, List<EventRow> trace, String reason, long day) {
    trace.add(event(EventTypes.COMMAND_REJECTED, cmd, json(Map.of("reason", reason))));
    timeline.appendEvents(trace);
    PHASE.info(
        LogEvent.of(
            "ADVANCE_REJECTED",
            CoreLogSource.TICK_ADVANCE,
            "day",
            day,
            "type",
            "core.AdvanceTime",
            "commandId",
            cmd.commandId(),
            "correlationId",
            cmd.correlationId(),
            "caller",
            cmd.initiator(),
            "reason",
            reason));
    return new AdvanceOutcome.Rejected(new CommandResult.Rejected(reason));
  }

  /**
   * 冲突：落 {@code trace + conflicted}（**这一天不留 revision**），再折结局。
   *
   * @param current **真实 head**（乐观并发 ⇒ 别人推到的坐标；单日写-写 ⇒ 冲突前一天，即"世界真的停在哪"）
   */
  private AdvanceOutcome conflictOutcome(
      AdvanceTime cmd, List<EventRow> trace, StateRef current, long day, String reason) {
    trace.add(event(EventTypes.COMMAND_CONFLICTED, cmd, json(refJson(current))));
    timeline.appendEvents(trace);
    PHASE.info(
        LogEvent.of(
            "ADVANCE_CONFLICTED",
            CoreLogSource.TICK_ADVANCE,
            "day",
            day,
            "type",
            "core.AdvanceTime",
            "commandId",
            cmd.commandId(),
            "correlationId",
            cmd.correlationId(),
            "caller",
            cmd.initiator(),
            "headBranch",
            current.branch().value(),
            "headRevision",
            current.revision().value(),
            "reason",
            reason));
    return new AdvanceOutcome.Conflict(new CommandResult.Conflict(current));
  }
}
