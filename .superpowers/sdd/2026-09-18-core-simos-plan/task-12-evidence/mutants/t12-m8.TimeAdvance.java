package io.mosire.simos.core.advance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 两阶段时间推进（spec §5.1 的六步，C25）——{@link AdvanceRoute} 的**真实现**（Task 11 用例里用的是替身）。
 *
 * <p>★ **两阶段的全部意义在 ②**：{@code simulate} 是**纯函数**——拿同一份 base、吐提案，**不写状态**。
 * 故"没有模块能在别人提案之前就把自己的改动落下去"这句话有结构保证：本类在 ② 里除了调 {@code simulate} 什么也不做， 落盘只发生在 ⑤。
 *
 * <p>★★ **六步的边界（裁定 47 裁过 spec 的一处自相矛盾，见台账）**：
 *
 * <pre>
 * ① Prepare     查 head（乐观并发）→ 装配 base；参与者清单**构造期**已按 namespace 字典序定死（C25 / 裁定 44）
 * ② Propose     逐个 simulate → TimeProposal
 * ③ Resolve     {@link TimeProposalResolver}（写-写 ⇒ 拒；读-写 ⇒ 留痕放行）
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
 * 第二笔撞主键 ⇒ 回头读一次 head ⇒ **动了才折成 {@link CommandResult.Conflict}，没动就原样重抛** （后者是要害：无条件折成冲突会把真 bug
 * 伪装成"别人抢先了"，那是最难查的一类假象）。
 *
 * <p>★ **本类自己记结局日志**（§7.3）：{@code CommandBus} 的 {@code logOutcome} 只在**信封支**调（{@code dispatch}
 * 里），{@code submit} 的 {@code AdvanceTime} 那一支直接 {@code return advanceRoute.run(...)} ⇒
 * "命令接收/拒绝/冲突/提交"这四项**这一支一条都不会被记**。故三条结局各记一行 INFO，一条命令**恰好一行**。 日志纪律与 {@code CommandBus}
 * 同口径：不落载荷明文，只记结构信息。
 */
public final class TimeAdvance implements AdvanceRoute {

  private static final Logger LOG = LoggerFactory.getLogger(TimeAdvance.class);

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
    this.timeline = Objects.requireNonNull(timeline, "timeline");
    this.stateLoader = Objects.requireNonNull(stateLoader, "stateLoader");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    Objects.requireNonNull(codecs, "codecs");
    Objects.requireNonNull(participants, "participants");

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

  @Override
  public CommandResult run(AdvanceTime cmd) {
    Objects.requireNonNull(cmd, "cmd");

    List<EventRow> trace = new ArrayList<>();
    trace.add(received(cmd));

    // ④ 第 0 项：**先做**——它不查库、不碰状态，是纯语法判定。缺 to ⇒ 无上界的推进落不成一条 revision
    //   （revision 的 tick 是确定值）。
    if (cmd.range().to().isEmpty()) {
      return rejected(cmd, trace, "推进必须有上界（range.to 缺失）：AdvanceTime 是写操作，语义上不允许开区间");
    }

    // ① Prepare：入口乐观并发检查（C17 的同一条）。AdvanceTime 不过信封支 ⇒ 这里必须自己查一次，
    //   否则"期望坐标"这个参数对本命令形同虚设。
    Optional<RevisionId> head = timeline.head(cmd.branch());
    if (head.isEmpty() || head.get().compareTo(cmd.expectedRevision()) != 0) {
      return head.<CommandResult>map(
              current -> conflict(cmd, trace, new StateRef(cmd.branch(), current)))
          .orElseGet(() -> rejected(cmd, trace, "分支不存在: " + cmd.branch().value()));
    }

    StateRef base = new StateRef(cmd.branch(), head.get());
    StateRef target = new StateRef(cmd.branch(), new RevisionId(base.revision().value() + 1));
    StateMeta newMeta = new StateMeta(target, cmd.range().to().orElseThrow());

    LOG.debug(
        "推进开始: commandId={} correlationId={} branch={} expectedRevision={} to={} 参与者={}",
        cmd.commandId(),
        cmd.correlationId(),
        cmd.branch().value(),
        cmd.expectedRevision().value(),
        newMeta.timestamp(),
        participants.size());

    SimulationState state = stateLoader.load(base);

    // ② Propose：**纯函数**，每个参与者拿到的都是同一份 base（C25：顺序不影响结果）
    List<TimeProposal> proposals = new ArrayList<>();
    for (TimeParticipant participant : participants) {
      proposals.add(participant.simulate(state, cmd.range()));
    }
    trace.add(started(cmd, newMeta));
    for (TimeProposal proposal : proposals) {
      trace.add(proposalEvent(cmd, proposal));
    }

    // ③ Resolve
    TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(proposals);
    if (outcome instanceof TimeProposalResolver.Outcome.Blocked blocked) {
      trace.add(conflictEvent(cmd, blocked.conflict()));
      return rejected(
          cmd,
          trace,
          "写-写冲突，拒绝整次推进: "
              + String.join(" ∩ ", blocked.conflict().namespaces())
              + " 于 "
              + blocked.conflict().addresses());
    }
    TimeProposalResolver.Outcome.Resolved resolved =
        (TimeProposalResolver.Outcome.Resolved) outcome;
    for (AdvanceConflict warning : resolved.warnings()) {
      trace.add(conflictEvent(cmd, warning)); // 读-写：只留痕，不拒绝
    }

    // ④ Validate（第 1~4 项；第 0 项在上面）
    Validation validation = validate(state, proposals, newMeta);
    if (validation.failed()) {
      return rejected(cmd, trace, validation.error());
    }

    // ⑤ Commit：1 行 revision + **全部**事件，一个事务（裁定 47：`finished` 也在里面）
    trace.add(finished(cmd, target));
    trace.add(committed(cmd, target));
    try {
      timeline.appendRevision(revisionRow(cmd, base, target, newMeta, resolved.changeSet()), trace);
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
      if (moved.isPresent() && moved.get().compareTo(cmd.expectedRevision()) != 0) {
        // ★ 事件链重新从 `received` 起算：上一条事务已整体回滚，它的 `started`/`proposal`/`finished` 一条都没落。
        //   此处**不补发**它们——`finished` 尤其不能补（推进并没有完成），补发等于在库里留一句假话。
        //   与信封支的冲突形态（`received + conflicted`）同形。
        List<EventRow> conflictTrace = new ArrayList<>();
        conflictTrace.add(received(cmd));
        return conflict(cmd, conflictTrace, new StateRef(cmd.branch(), moved.get()));
      }
      throw e;
    }

    // ⑥ Post-commit：**只剩 checkpoint**（C19 命中才写）。它失败不影响已落盘的事实。
    writeCheckpointIfDue(target, newMeta, state, validation.applied());

    LOG.info(
        "命令提交: type=core.AdvanceTime commandId={} correlationId={} 新坐标={}@{}",
        cmd.commandId(),
        cmd.correlationId(),
        target.branch().value(),
        target.revision().value());
    return new CommandResult.Committed(target);
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
   * ★ **第 1~4 项**。任一项失败 ⇒ {@code Rejected}，**不产生 revision**（R9/R14 的"拒绝是原子的"）。
   *
   * <p>★ 第 4 项**不是装饰**：{@link ModuleCodec#apply} 的第三参 {@code newMeta} 由本类**在 Commit 前算好**， codec
   * 照它产出新快照；随后本项核对 codec **真的用了它**。最常犯的错是"照抄 base 的坐标"——那会让新 revision 的快照带着**旧坐标**落进
   * checkpoint，重放时状态树与坐标对不上。这一项就是拦它的（C28 的兑现）。
   *
   * <p>★★ **与 spec 的一处有意偏离，如实记**：spec §5.4 第 2 项写的是"`ChangeSet`（**非空时**）能被该 codec `encodeChangeSet`
   * 成功"。那个"非空时"在 Core 里**不可实现**——{@code ChangeSet} 是**标记接口**（实测：接口体为空）， **没有
   * `isEmpty()`**；三个模块的变更集各自有 `public boolean isEmpty()`（裁定 39 记过它们被 Jackson 内省成属性 `empty`），但要调到它就得
   * {@code instanceof} 领域类型——**ADR-1 明令禁止**。 ⇒ 本类**无条件**校验编码，**严格更严**（空变更集若编不出来，那本身就该拒）。
   */
  private Validation validate(
      SimulationState state, List<TimeProposal> proposals, StateMeta newMeta) {
    Map<String, Snapshot> applied = new LinkedHashMap<>();
    for (TimeProposal proposal : proposals) {
      String namespace = proposal.namespace();

      // 第 1 项：namespace 有已注册的 ModuleCodec
      ModuleCodec codec = codecs.get(namespace);
      if (codec == null) {
        return Validation.fail("未注册的 namespace（无 ModuleCodec）: " + namespace);
      }

      // 第 2 项：变更集编得出来
      try {
        codec.encodeChangeSet(proposal.changeSet());
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
        appliedSnapshot = codec.apply(proposal.changeSet(), moduleBase.get(), newMeta);
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
    return Validation.ok(applied);
  }

  // ── ⑥ checkpoint ────────────────────────────────────────────────────────────────────

  /**
   * C19 命中才写。{@code Timeline.hasCheckpoint} 是**纯函数判定**（C18 的关键：这里判"**应当**有"，读侧判"**实际**有"）。
   *
   * <p>★ 信封的 meta 用 {@code newMeta}（新坐标 + 推进终点的时刻）——**不是** {@code base.meta()}：一条 {@code (b, r)} 的
   * checkpoint 描述的是"重放到 (b, r) 的结果"，坐标写错会让读侧拿去当别的坐标用。 而**模块快照各自保留自己的坐标**（只有本推进动过的那些才被覆盖成 {@code
   * newMeta}）——这与 {@code Replay.applyWorld} 的语义一致：变更集里没提到的模块**保持原样、不推进坐标**。两者不同源会让 R4（从 checkpoint
   * 重放 == 从创世重放）当场破。
   *
   * <p>★★ **失败只 WARN，不上抛**（C24 / §3.4 ④ 的明文要求）：checkpoint 是**纯派生缓存**（C18），丢了只赔重放时间—— {@code Replay}
   * 本来就会回退到更早的 checkpoint、最坏从创世重放。而此刻 **⑤ 已经把 revision 落盘了**，
   * 异常若逃出本方法，调用方会看到"提交失败"却库里明明有一行——**观察绝不许改被判事物的结局**（与 {@code CommandBus} 把日志挪到锁外是同一条纪律）。
   *
   * <p>★ 为什么这不是"静默吞掉"：① 这里记 WARNING **带异常堆栈**；② 缺档在读侧另有 WARNING（ {@code CheckpointStore.read}）；③
   * 档的存在性有 R3 那条"判定与文件逐条一致"的护栏。三处都看得见。
   */
  private void writeCheckpointIfDue(
      StateRef target, StateMeta newMeta, SimulationState base, Map<String, Snapshot> applied) {
    if (!timeline.hasCheckpoint(target)) {
      return;
    }
    Map<String, Snapshot> modules =
        new LinkedHashMap<>(applied); // 变异 m8：只写动过的模块，未触碰的丢掉
    try {
      String envelopeJson =
          CheckpointEncoder.encode(
              new SimulationState(newMeta, modules, base.info()), codecs.values());
      checkpoints.write(target, envelopeJson);
      LOG.debug("checkpoint 写入: {}@{}", target.branch().value(), target.revision().value());
    } catch (RuntimeException e) {
      // I/O 失败（C24 预料之内）与装配缺口（信封里有本实例没装 codec 的模块）都走到这里。
      LOG.warn(
          "checkpoint 写入失败（C18：纯优化，不回退提交；重放将回退到更早的 checkpoint）: {}@{}",
          target.branch().value(),
          target.revision().value(),
          e);
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
   * 一条模块提案。
   *
   * <p>★ {@code reads}/{@code writes} **按字典序**（C15 / 裁定 44 的第二个落点）——它们来自 {@code TimeProposal} 的
   * {@code Set}，而 {@code Set} 的迭代序不是键集的纯函数。故在此**显式排序**。
   *
   * <p>★★ **载荷里没有变更集摘要，这是有意的，别来"补"**：§7.1 那句"参数摘要复用 {@code Digest}——不记明文"针对的是
   * **会泄露领域载荷明文的**载荷（如信封支的 {@code payloadDigest}）。本载荷的字段全是 canonical 地址串与 namespace，
   * **没有明文可藏**；而要为它算出摘要就得先 {@code encodeChangeSet}，那道编码在 ④ 才做、判定归属也在那里。
   * 硬塞一个"摘要"出来只会得到一个**名字叫摘要、内容却不是摘要**的假字段——那正是本项目最贵的那类事故形态。
   */
  private static EventRow proposalEvent(AdvanceTime cmd, TimeProposal proposal) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("namespace", proposal.namespace());
    payload.put("reads", sorted(proposal.reads()));
    payload.put("writes", sorted(proposal.writes()));
    return event(EventTypes.MODULE_PROPOSAL, cmd, json(payload));
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

  /** 字典序去重（C15）。{@code TreeSet} 同时给了去重，虽然集合运算本就不产生重复。 */
  private static List<String> sorted(Set<String> values) {
    return List.copyOf(new TreeSet<>(values));
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

  // ── 两条"不留 revision 行"的结局 ─────────────────────────────────────────────────────

  /** 被拒：落 {@code trace + rejected}（**不留 revision 行**，R9/R14），再折结局。 */
  private CommandResult rejected(AdvanceTime cmd, List<EventRow> trace, String reason) {
    trace.add(event(EventTypes.COMMAND_REJECTED, cmd, json(Map.of("reason", reason))));
    timeline.appendEvents(trace);
    LOG.info(
        "命令被拒: type=core.AdvanceTime commandId={} correlationId={} 原因={}",
        cmd.commandId(),
        cmd.correlationId(),
        reason);
    return new CommandResult.Rejected(reason);
  }

  /** 冲突：落 {@code trace + conflicted}（**不留 revision 行**），再折结局。 */
  private CommandResult conflict(AdvanceTime cmd, List<EventRow> trace, StateRef current) {
    trace.add(event(EventTypes.COMMAND_CONFLICTED, cmd, json(refJson(current))));
    timeline.appendEvents(trace);
    LOG.info(
        "命令冲突: type=core.AdvanceTime commandId={} correlationId={} 真实head={}@{}",
        cmd.commandId(),
        cmd.correlationId(),
        current.branch().value(),
        current.revision().value());
    return new CommandResult.Conflict(current);
  }
}
