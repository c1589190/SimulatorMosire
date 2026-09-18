package io.mosire.simos.core.command;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * ——C17 的"两处检查"至此齐全。事件行与 correlationId 全链归 **Task 11**。
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
   * 信封支（spec §4.2 的第三支）：**唯一按 type 找 handler 的地方**。
   *
   * <p>次序是有意的：**先查 type 在不在册**（纯内存、不碰库），再查 head，再装配状态，最后才跑 handler。 handler 排最后是因为它最贵（C17 第 ②
   * 步"在锁外执行"的本意）。
   */
  private CommandResult dispatch(CommandEnvelope envelope) {
    Optional<CommandHandler> handler = registry.byType(envelope.type());
    if (handler.isEmpty()) {
      return new CommandResult.Rejected("未注册的命令类型: " + envelope.type());
    }

    // ① 入口乐观并发检查（C17）：head(branch) 必须等于 expectedRevision。③ 的锁内复查归 Task 10。
    Optional<RevisionId> head = timeline.head(envelope.branch());
    if (head.get().compareTo(envelope.expectedRevision()) != 0) {
      return head.<CommandResult>map(
              current -> new CommandResult.Conflict(new StateRef(envelope.branch(), current)))
          .orElseGet(() -> new CommandResult.Rejected("分支不存在: " + envelope.branch().value()));
    }

    // ② handler：装配状态 → 执行 → 折结局。★ payloadJson 逐字节转交（R11），此处不得 trim / re-serialize。
    // ★ ② 全程在锁外——见类注"handler 在锁外执行"。
    StateRef base = new StateRef(envelope.branch(), head.get());
    SimulationState state = stateLoader.load(base);
    return switch (handler.get().handle(state, envelope.payloadJson())) {
      case HandlerOutcome.Rejected rejected -> new CommandResult.Rejected(rejected.reason());
      case HandlerOutcome.Applied applied -> commitUnderLock(envelope, applied.changeSet());
    };
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
  private CommandResult commitUnderLock(CommandEnvelope envelope, ChangeSet changeSet) {
    synchronized (commitLock) {
      Optional<RevisionId> current = timeline.head(envelope.branch());
      if (current.isEmpty() || current.get().compareTo(envelope.expectedRevision()) != 0) {
        return current
            .<CommandResult>map(
                now -> new CommandResult.Conflict(new StateRef(envelope.branch(), now)))
            .orElseGet(() -> new CommandResult.Rejected("分支不存在: " + envelope.branch().value()));
      }
      return commit(envelope, new StateRef(envelope.branch(), current.get()), changeSet);
    }
  }

  /**
   * ④ 落一行 revision（**同一事务**；本任务只落这一行，事件行归 Task 11）。
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
  private CommandResult commit(CommandEnvelope envelope, StateRef base, ChangeSet changeSet) {
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
    timeline.appendRevision(row);
    return new CommandResult.Committed(new StateRef(row.branch(), row.revision()));
  }

  /** 裁定 37：{@code type} 形状已在 {@code CommandRegistry} 构造期校验过，这里直接切。 */
  private static String namespaceOf(String type) {
    return type.substring(0, type.indexOf('.'));
  }
}
