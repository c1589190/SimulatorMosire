package io.mosire.simos.core.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.Digest;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令总线（spec §四，C16 的分派落点）。
 *
 * <p>★ **分派是 Core 对自己封闭命令集的 {@code instanceof}**（C16）：{@link AdvanceTime} → 注入的 {@link
 * AdvanceRoute}； {@link ForkBranch} → {@code Timeline.fork}；{@link CommandEnvelope} → {@code
 * CommandRegistry.byType}。 **最后一步是 Core 唯一按 type 找 handler 的地方。** Core 对领域载荷**一律不 switch、不
 * instanceof**——它手里只有 {@code type} 字符串与 {@code payloadJson} 文本。三条之外的类型一律抛：{@code Command} 是 util
 * 的开放接口， 穷尽性由这条兜底守住。
 *
 * <p>★ **Task 9 只做了**：分派 + ① 入口乐观并发检查 + ② handler + ④ 落一行 revision。**Task 10 补上了 ③ 的锁内复查与锁纪律**
 * ——C17 的"两处检查"至此齐全。**Task 11 补上了事件链**：信封支的 {@code received} 与结局事件（{@code rejected} / {@code
 * conflicted} / {@code committed}）**与 revision 行同一事务**落盘。
 *
 * <p>★ **事件链只有信封支归本类**（裁定 46）：{@link AdvanceTime} 在 {@link #submit} 里被**原样递给注入的 {@link
 * AdvanceRoute}**，它那一支的全部事件（{@code received} → {@code started} → N× {@code module.proposal} →
 * {@code finished} → {@code committed}）**由 route 自己写**。理由：只有 route 开得了那个事务，"全部事件行 + revision
 * 行同一事务"才成立；若本类替它写 {@code received}，那条事件必然落在 **另一个事务**里，原子性当场就破了。{@link ForkBranch} 支见 {@link
 * #fork} 的注（已知缺口）。
 *
 * <p>★ **为什么"received 也一起落"而不是一进门就写**：一条命令的全部事件要么都在、要么都不在。若 {@code received}
 * 先落、结局事件后落，进程死在中间就留下"收到了却不知结局"的半条链路，事后审计读不出它是被拒了还是崩了。 ⇒ 本类的四个结局方法（{@link #reject} / {@link
 * #conflict} / {@link #commit}）各自**一次性**落完整条事件链。
 *
 * <p>★ **为什么只锁 ③④ 就够**（Task 10 的实测结论）：{@code SqliteStore.inTransaction} 已经把**每个事务**串行化了（C23， 一把私有锁
 * + 一条共享连接）⇒ 竞态**不在事务内部**，而在 ① 那个事务与 ④ 那个事务**之间**。① 读到 head、handler 跑完、 ④
 * 才去写——这中间的窗口正是两个线程都能算出**同一个 revision 号**的原因（Task 10 开跑前实测：两线程撞 {@code SQLITE_CONSTRAINT_PRIMARYKEY
 * (UNIQUE constraint failed: revisions.branch, revisions.revision)}）。 锁住 ③④ 恰好封住这条缝，且 handler
 * 仍然**全程不占锁**。
 *
 * <p>★ **锁序**：{@code commitLock → store 的私有锁}（因为 ③ 要查 head）。反向次序（先持 store 锁再取 {@code commitLock}）
 * 在**本类中不存在**——{@code submit} 的 ① 与 {@code fork} 都只取 store 锁、不取 {@code commitLock} ⇒ 无环、无死锁。
 *
 * <p>★ **handler 在锁外执行**（C17 第 ② 步的形态）：它可能很慢，不能占着锁跑。**这条次序是本类最容易被改坏的地方**—— 把 handler
 * 挪进锁内**不影响正确性**（R7 仍会通过），却让"并发"名存实亡。护栏是 {@code OptimisticConcurrencyTest} 里那条**「同时进入数」自证断言**（变异 m3
 * 打的就是它）：没有那条断言，R7 是装饰。
 */
public final class CommandBus {

  private final Timeline timeline;
  private final CommandRegistry registry;
  private final AdvanceRoute advanceRoute;
  private final StateLoader stateLoader;

  /**
   * C17 的锁：**只罩住 ③ 锁内复查与 ④ 落盘**。
   *
   * <p>★ **不罩 ① 与 ②**，理由见类注。★ 用私有 {@code Object} 而不是 {@code this}：锁对象不外泄， 外部不可能误拿本实例当锁用。
   */
  private final Object commitLock = new Object();

  /**
   * 事件 {@code payload} 的 JSON 出口（Task 11）。
   *
   * <p>★ 用共享层那台而不是新搓一台：载荷全是 {@code Map<String, String>}，没有任何模块类型参与， 故不需要模块 mixin（与 `changeset_json`
   * 那台的分工不同，见裁定 39）。
   */
  private static final ObjectMapper EVENT_MAPPER = SimosObjectMapper.create();

  /**
   * 人类可读日志（spec §7.3："便于直接 debug，不需查库"）。
   *
   * <p>★ **载荷明文一律不进日志**（与 §8.1 的"不记明文"同一口径）：日志会被 grep、会被贴进 issue、会被采集走，
   * 若在这里落明文，事件表那边省下的明文等于从后门又漏了一遍。诊断需要的**结构信息**（类型 / 命令号 / 链路号 / 分支 / 期望与真实坐标 / 拒绝原因）足够定位，剩下的顺着
   * {@code correlationId} 一条 SQL 就能查到。
   */
  private static final Logger LOG = LoggerFactory.getLogger(CommandBus.class);

  /**
   * @param timeline 时间线（读写 revision 行、判 head）
   * @param registry type → handler 的不可变表
   * @param advanceRoute {@code AdvanceTime} 那一支的目标（裁定 32：由 Task 12 实现、Task 13 装配）
   * @param stateLoader "给定坐标给状态"（裁定 34：装配时传 {@code replay::replay}）
   */
  public CommandBus(
      Timeline timeline,
      CommandRegistry registry,
      AdvanceRoute advanceRoute,
      StateLoader stateLoader) {
    this.timeline = Objects.requireNonNull(timeline, "timeline");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.advanceRoute = Objects.requireNonNull(advanceRoute, "advanceRoute");
    this.stateLoader = Objects.requireNonNull(stateLoader, "stateLoader");
  }

  /**
   * 提交一条命令并**同步**返回结局。
   *
   * @throws IllegalArgumentException 命令不是 Core 封闭集里的三种之一
   */
  public CommandResult submit(Command command) {
    Objects.requireNonNull(command, "command");
    if (command instanceof AdvanceTime advanceTime) {
      return advanceRoute.run(advanceTime);
    }
    if (command instanceof ForkBranch forkBranch) {
      return fork(forkBranch);
    }
    if (command instanceof CommandEnvelope envelope) {
      return dispatch(envelope);
    }
    throw new IllegalArgumentException(
        "未知命令类型（Core 的封闭命令集只有 AdvanceTime / ForkBranch / CommandEnvelope，C16）: "
            + command.getClass().getName());
  }

  /**
   * {@code ForkBranch} 支（spec §3.4 冻结的语义全在 {@code Timeline.fork} 里，本方法只做结局翻译）。
   *
   * <p>★ **不用"先读 head 再 fork"两次查库**：直接 fork，失败时**才**去读 head——正常路径一次事务。 失败原因由 head 的存在性区分：**有 head ⇒
   * 冲突**（报真实 head），**无 head ⇒ 拒绝**（分支不存在， 此时没有 head 可报，编一个坐标出来才是错的）。
   *
   * <p>★★ **已知缺口（Task 11 的带裁定遗留条目）：本支不发事件。** 理由：分岔的 revision 行由 {@link Timeline#fork}
   * 在**它自己的事务里**写，而事件行要与它同事务 ⇒ 得给 {@code fork} 加一个收 {@code List<EventRow>} 的重载（第 7
   * 个参数）。**没有当场做**，因为：① 计划 Task 11 的判据二（R6） 只覆盖成功的时间推进，不覆盖分岔；② 让调用方预先构造 {@code committed}
   * 事件要**把"新分支的 revision 恰为 1" 这条 {@code Timeline} 的内部知识复制到本类**（spec §3.4 冻结了它，模板仍是耦合）。 ⇒ 如实记为缺口，交
   * Task 13 装配时或 Task 15 复核时裁。**不许把它读成"分岔已有事件链"。**
   */
  private CommandResult fork(ForkBranch command) {
    Optional<StateRef> forked =
        timeline.fork(
            command.source(),
            command.expectedRevision(),
            command.newBranch(),
            command.commandId(),
            command.correlationId(),
            command.initiator());
    if (forked.isPresent()) {
      return new CommandResult.Committed(forked.get());
    }
    return timeline
        .head(command.source())
        .<CommandResult>map(
            head -> new CommandResult.Conflict(new StateRef(command.source(), head)))
        .orElseGet(() -> new CommandResult.Rejected("分支不存在: " + command.source().value()));
  }

  /**
   * 信封支的**外壳**：记入口日志 → 路由 → 记结局日志（spec §7.3 的"命令接收 / 拒绝 / 冲突 / 提交"四项）。
   *
   * <p>★ **为什么拆成壳 + {@link #routeEnvelope} 两层，而不是在既有方法里就地插两行日志**：{@code commit} 是在 {@link
   * #commitLock} **里面**跑的，若把结局日志写在 {@code commit} 尾部，那次日志调用就**持着锁**；更糟的是 若 appender
   * 抛异常（配置错、磁盘满），异常会**在提交已经成功之后**穿出 {@code submit}——调用方以为命令没生效，
   * 而库里那一行**已经落了**。拆开之后，两处日志都在**锁外**：入口在进锁之前，结局在 {@link #routeEnvelope}
   * 返回（锁已释放）之后。日志是**观察**，绝不许改被判事物的结局。
   *
   * <p>★ 入口是 {@code DEBUG}、三项结局是 {@code INFO}：结局每条命令**恰好一次**（互斥的三种，见 {@code switch}）， 故 INFO
   * 下**一行命令一行日志**，不吵；入口那条会让行数翻倍却不带来新的终局信息，故退一档。
   */
  private CommandResult dispatch(CommandEnvelope envelope) {
    LOG.debug(
        "命令接收: type={} commandId={} correlationId={} branch={} expectedRevision={}",
        envelope.type(),
        envelope.commandId(),
        envelope.correlationId(),
        envelope.branch().value(),
        envelope.expectedRevision().value());
    CommandResult result = routeEnvelope(envelope);
    logOutcome(envelope, result);
    return result;
  }

  /**
   * 信封支的实际路由（spec §4.2 的第三支）：**唯一按 type 找 handler 的地方**。
   *
   * <p>次序是有意的：**先查 type 在不在册**（纯内存、不碰库），再查 head，再装配状态，最后才跑 handler。 handler 排最后是因为它最贵（C17 第 ②
   * 步"在锁外执行"的本意）。
   */
  private CommandResult routeEnvelope(CommandEnvelope envelope) {
    // ★ 事件链（Task 11）：`received` 在这里建，但**不在此时落盘**——见 commit/reject/conflict 的类注
    //   "全部事件与 revision 同一事务"。
    List<EventRow> trace = new ArrayList<>();
    trace.add(received(envelope));

    Optional<CommandHandler> handler = registry.byType(envelope.type());
    if (handler.isEmpty()) {
      return reject(envelope, trace, "未注册的命令类型: " + envelope.type());
    }

    // ① 入口乐观并发检查（C17）：head(branch) 必须等于 expectedRevision。③ 的锁内复查归 Task 10。
    Optional<RevisionId> head = timeline.head(envelope.branch());
    if (head.isEmpty() || head.get().compareTo(envelope.expectedRevision()) != 0) {
      return head.<CommandResult>map(
              current -> conflict(envelope, trace, new StateRef(envelope.branch(), current)))
          .orElseGet(() -> reject(envelope, trace, "分支不存在: " + envelope.branch().value()));
    }

    // ② handler：装配状态 → 执行 → 折结局。★ payloadJson 逐字节转交（R11），此处不得 trim / re-serialize。
    // ★ ② 全程在锁外——见类注"handler 在锁外执行"。
    StateRef base = new StateRef(envelope.branch(), head.get());
    SimulationState state = stateLoader.load(base);
    return switch (handler.get().handle(state, envelope.payloadJson())) {
      case HandlerOutcome.Rejected rejected -> reject(envelope, trace, rejected.reason());
      case HandlerOutcome.Applied applied -> commitUnderLock(envelope, trace, applied.changeSet());
    };
  }

  /** 被拒：落 {@code received + rejected} 两条事件（**不留 revision 行**），再折结局。 */
  private CommandResult reject(CommandEnvelope envelope, List<EventRow> trace, String reason) {
    trace.add(event(EventTypes.COMMAND_REJECTED, envelope, json(Map.of("reason", reason))));
    timeline.appendEvents(trace);
    return new CommandResult.Rejected(reason);
  }

  /** 冲突：落 {@code received + conflicted} 两条事件（**不留 revision 行**），再折结局。 */
  private CommandResult conflict(CommandEnvelope envelope, List<EventRow> trace, StateRef current) {
    trace.add(event(EventTypes.COMMAND_CONFLICTED, envelope, json(refJson(current))));
    timeline.appendEvents(trace);
    return new CommandResult.Conflict(current);
  }

  /**
   * 三项结局的人类可读日志（spec §7.3）。**只在锁外被调用**——见 {@link #dispatch} 的注。
   *
   * <p>★ {@code switch} **不写 {@code default}**：{@link CommandResult} 是封闭接口，穷尽性由编译器守；
   * 将来加第四种结局会**编译失败**，而不是静默地不记日志。
   *
   * <p>★ 三条消息各自带上"下一步该看什么"：拒绝给**原因**、冲突给**真实 head**（调用方拿它就能重试，日志里也一样）、 提交给**新坐标**。★ 都不带载荷明文（见
   * {@link #LOG}）。
   */
  private static void logOutcome(CommandEnvelope envelope, CommandResult result) {
    switch (result) {
      case CommandResult.Rejected rejected ->
          LOG.info(
              "命令被拒: type={} commandId={} correlationId={} 原因={}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              rejected.reason());
      case CommandResult.Conflict conflict ->
          LOG.info(
              "命令冲突: type={} commandId={} correlationId={} 真实head={}@{}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              conflict.current().branch().value(),
              conflict.current().revision().value());
      case CommandResult.Committed committed ->
          LOG.info(
              "命令提交: type={} commandId={} correlationId={} 新坐标={}@{} payload={}",
              envelope.type(),
              envelope.commandId(),
              envelope.correlationId(),
              committed.ref().branch().value(),
              committed.ref().revision().value(),
              envelope.payloadJson());
    }
  }

  /**
   * **③ 锁内复查 + ④ 落盘**（C17）：入口检查（①）与这里之间隔着 handler，它可能跑了很久——head 可能已经动了。
   *
   * <p>★ **两处检查缺一不可**：只有 ① ⇒ 两个线程都通过、都提交，"乐观并发形同虚设"（C17 原话，且 Task 10 开跑前已实测到 主键冲突）；只有 ③ ⇒
   * 慢命令白跑一趟才被拒。① 负责**快速失败**，③ 负责**最后把关**。
   *
   * <p>★ **③ 与 ④ 必须在同一把锁内**，中间不能再有缝：复查通过之后、写入落盘之前若松开锁，缝就又回来了。
   *
   * <p>★ **base 用 ③ 复查过的那个 head**，不用 ① 读到的那个：两者在成功路径上相等，但语义是"**现在**"——{@code commit} 据它算 {@code
   * revision + 1}，这正是 Task 9 报告 §5 记的那条硬接缝（"并发下两提交会算出同一个 revision 号"）的收口处。
   */
  private CommandResult commitUnderLock(
      CommandEnvelope envelope, List<EventRow> trace, ChangeSet changeSet) {
    synchronized (commitLock) {
      Optional<RevisionId> current = timeline.head(envelope.branch());
      if (current.isEmpty() || current.get().compareTo(envelope.expectedRevision()) != 0) {
        return current
            .<CommandResult>map(
                now -> conflict(envelope, trace, new StateRef(envelope.branch(), now)))
            .orElseGet(() -> reject(envelope, trace, "分支不存在: " + envelope.branch().value()));
      }
      return commit(envelope, trace, new StateRef(envelope.branch(), current.get()), changeSet);
    }
  }

  /**
   * ④ 落一行 revision **与它的全部事件行（同一事务**，Task 11 起）。
   *
   * <p>★ **只在 {@link #commitLock} 内被调用**（Task 10）：本方法**自己不复查** head，靠调用方先把 ③ 做完——把复查写在这里
   * 会让"锁罩住了什么"变得说不清。私有方法 + 唯一调用点，这条约定由结构本身守住。
   *
   * <p>★ **时刻继承父行**（裁定 35）：信封**不带任何时刻字段**（spec §4.1），而 {@code revisions.tick} 是 NOT
   * NULL——时刻只由时间推进改变（spec §五），一条 {@code unit.RenameUnit} 不该移动时钟。与 {@code Timeline.fork} 同一口径。
   *
   * <p>★ 变更集装成**单键** {@link WorldChangeSet}（裁定 37）：键 = {@code type} 的第一个 {@code '.'} 之前那段。 序列化必须走
   * {@code Timeline.changeSetJson}——它是**全仓唯一**的 changeset 落盘点，手写字面量等于"手工对着状态类型 维护的平行结构"（铁律 5
   * 的事故形态）。
   */
  private CommandResult commit(
      CommandEnvelope envelope, List<EventRow> trace, StateRef base, ChangeSet changeSet) {
    RevisionRow baseRow =
        timeline
            .row(base)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "base 行缺席（head 刚读到、行却不在库）: "
                            + base.branch().value()
                            + "@"
                            + base.revision().value()));
    SimosTimestamp timestamp = baseRow.timestamp();
    String changesetJson =
        Timeline.changeSetJson(new WorldChangeSet(Map.of(namespaceOf(envelope.type()), changeSet)));
    RevisionRow row =
        new RevisionRow(
            envelope.branch(),
            new RevisionId(base.revision().value() + 1),
            Optional.of(base),
            timestamp,
            envelope.commandId(),
            envelope.correlationId(),
            envelope.initiator(),
            envelope.type(),
            changesetJson);
    StateRef committed = new StateRef(row.branch(), row.revision());
    // ★ 全部事件与 revision 行**同一次 appendRevision ⇒ 同一个事务**（Task 11 / spec 〇.3 第 2 条）。
    //   `committed` 的载荷要带新坐标，故它只能在 row 建好之后拼——顺序是"先建行、再补事件、再一次落盘"。
    List<EventRow> events = new ArrayList<>(trace);
    events.add(event(EventTypes.COMMAND_COMMITTED, envelope, json(refJson(committed))));
    timeline.appendRevision(row, events);
    return new CommandResult.Committed(committed);
  }

  // ── Task 11：事件构造 ──────────────────────────────────────────────────────────────

  /**
   * 命令到达事件。
   *
   * <p>★ 载荷按**总纲 §8.1 的"每条命令固定记录"**取 Core **确实知道**的那几项。九项里 M4 记不全的四处 **如实留缺**——不编值出来：
   *
   * <ul>
   *   <li><b>目标地址</b>（Human 与 canonical）：Core 手里只有不透明 {@code payloadJson}（C26 / ADR-1），
   *       **看不见地址**。要记它必须有模块参与，而 M4 没有这个装配点。
   *   <li><b>耗时</b>：需要入口/出口两个时钟，M4 的 {@code CommandBus} 是同步单发、无计时器。
   *   <li><b>产出 ChangeSet 摘要</b>：{@code committed} 事件只带新坐标；变更集全文在 {@code revisions.changeset_json}
   *       里，**不复制一份**（复制就是"同一事实两个来源"）。
   *   <li><b>结果 / 拒绝原因</b>：已由各自的事件类型承载（{@code rejected} 带 reason、{@code conflicted} 带真实 head），不必再塞进
   *       {@code received} 的载荷。
   * </ul>
   */
  private static EventRow received(CommandEnvelope envelope) {
    return event(
        EventTypes.COMMAND_RECEIVED,
        envelope,
        json(
            Map.of(
                "commandId", envelope.commandId(),
                "type", envelope.type(),
                "branch", envelope.branch().value(),
                "expectedRevision", Long.toString(envelope.expectedRevision().value()),
                // ★ 总纲 §8.1「参数摘要 | 复用 AgentLibMosire 的 Digest（sha256 前 16 字节）——不记明文」
                //   ⇒ 领域载荷**只留摘要**，明文不进事件。Digest 是 agentlib 的既有类（裁定 45），不另造。
                "payloadDigest", Digest.sha256(envelope.payloadJson()))));
  }

  /**
   * 事件行的公共部分：{@code agent} 存发起者原文（C21），{@code correlationId} **逐字节取自信封**。
   *
   * <p>★★ 这一行是**判据二的全部**：{@code correlationId} 若在这里被换成 {@code commandId}（或任何别的东西），
   * "一条命令从入口追到落盘"当场断链——而**单命令链缺省 {@code correlationId == commandId}（C22）**， 所以**用例必须显式传一个不同的
   * correlationId**，否则那个变异恒绿（计划 Task 11 Step 5 明写的坑）。
   */
  private static EventRow event(String type, CommandEnvelope envelope, String payload) {
    return EventRow.of(type, envelope.initiator(), payload, envelope.correlationId());
  }

  private static Map<String, String> refJson(StateRef ref) {
    return Map.of(
        "branch", ref.branch().value(), "revision", Long.toString(ref.revision().value()));
  }

  private static String json(Map<String, String> fields) {
    try {
      return EVENT_MAPPER.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("事件载荷序列化失败（字段全是 String，不该发生）", e);
    }
  }

  /** 裁定 37：{@code type} 形状已在 {@code CommandRegistry} 构造期校验过，这里直接切。 */
  private static String namespaceOf(String type) {
    return type.substring(0, type.indexOf('.'));
  }
}
