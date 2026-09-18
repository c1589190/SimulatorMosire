package io.mosire.simos.core;

import io.mosire.simos.core.advance.TimeAdvance;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandBus;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandRegistry;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.Replay;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code CoreSimos} 的**装配门面**（spec §1.2；计划 Task 13）：把时间线、重放、checkpoint、推进管线与命令总线拼成一台可用的
 * 引擎，并对外只留四个动作——注册三类扩展、提交命令、重放坐标、关闭。
 *
 * <p>★ **装配的形状**（逐件对应已关账的任务，本类不重新实现任何一件）：
 *
 * <pre>
 * SqliteStore        ← Task 5（{@code <storeDir>/simos.db}，本类只选择文件名）
 * Timeline           ← Task 6（周期 N 取自 {@link CoreConfig#checkpointInterval()}）
 * CheckpointStore    ← Task 7（{@code <storeDir>/checkpoints/<branch>/<revision>.json}）
 * Replay             ← Task 8（收全部 codec）
 * CommandRegistry    ← Task 10（**构造期**收全量 handler，故本类延迟到封存才建）
 * TimeAdvance        ← Task 12（真 route；⑥ 自己写 checkpoint）
 * CommandBus         ← Task 9/10/11（真 route + 真 StateLoader）
 * </pre>
 *
 * <p>★ **封存（seal）规则**：{@code CommandRegistry} 是**构造期不可变**的（它没有可变的 {@code register()}，spec §4.3 要求同
 * type 注册两次**当场抛**）。因此本类先用可变表**收集**注册，第一次 {@link #submit} 或 {@link #replay}
 * 时把全部组件**一次性建成**并封存。**封存之后再注册一律抛 {@link IllegalStateException}**——静默忽略会让一次装配错误 表现为"这个模块的 handler
 * 永远不生效"，且不报错。
 *
 * <p>★ **Post-commit 只做 Core 自己的动作**（C24）：按 C19 写 checkpoint。**不引入模块钩子**——模块级派生索引是按需构建的，
 * 没有装配点与消费者（M2 {@code contourCacheMax} 的教训）。
 *
 * <p>★ **三种命令的 checkpoint 分工**（与 spec §3.4 ④ / C19 / 裁定 47 一致）：
 *
 * <ol>
 *   <li>{@link ForkBranch}：提交后按 C19 第②项，**源 head（= 分岔点）**必须有一份 checkpoint。
 *   <li>{@link CommandEnvelope}：提交后**仅当** {@code Timeline.hasCheckpoint(新坐标)} 为真（即 r % N == 0）才写。
 *   <li>{@link AdvanceTime}：**不重复**——真 {@link TimeAdvance} 的 ⑥ 已经写过（Task 12）。本类再写一次只会覆盖同一份文件，
 *       且让"checkpoint 的写出点唯一"这条变模糊。
 * </ol>
 *
 * ★ **绝不写比 C19 谓词更多的文件**：{@link #maybeWriteCheckpoint} 先问 {@code hasCheckpoint}。多写一份不会更正确，却会让
 * "判定与磁盘逐条一致"（R3）在**反方向**上破——磁盘有、判定说没有。
 *
 * <p>★ **失败只记 WARNING，绝不失败**（C18 / spec §3.4 ④）：checkpoint 是纯派生缓存，丢了只赔重放时间；而此刻命令**已经 提交**，异常若逃出
 * {@link #submit}，调用方会以为失败——**观察绝不许改被判事物的结局**。
 */
public final class CoreSimos implements AutoCloseable {

  /** 库文件名（相对 {@link CoreConfig#storeDir()}）。测试要种创世行就得种在**同一个**文件上。 */
  public static final String DB_FILE_NAME = "simos.db";

  private static final Logger LOG = LoggerFactory.getLogger(CoreSimos.class);

  private final SqliteStore store;
  private final Timeline timeline;
  private final CheckpointStore checkpoints;

  /** 封存前收集注册；封存后只读。**不加锁对外暴露**，并发注册不在本任务契约内（封存本身原子化，见 {@link #sealIfNeeded}）。 */
  private final List<ModuleCodec> codecs = new ArrayList<>();

  private final List<CommandHandler> handlers = new ArrayList<>();
  private final List<TimeParticipant> participants = new ArrayList<>();

  /** 封存标志：{@code volatile} 让 {@link #requireNotSealed()} 不必进锁读取。 */
  private volatile boolean sealed;

  /**
   * 封存后才有值。★ 两个字段都 **{@code volatile}**：写发生在 {@link #sealIfNeeded()} 的锁内，读发生在锁外（{@link #sealedBus}
   * / {@link #load} / {@link #maybeWriteCheckpoint}）——非 volatile 会被 SpotBugs 判 {@code
   * IS2_INCONSISTENT_SYNC}（同一字段一半加锁一半不加锁），而 volatile 本身就是"读侧不必加锁"的声明。
   */
  private volatile Replay replay;

  private volatile CommandBus bus;

  /**
   * 打开存储并建时间线。
   *
   * @param config 装配配置；{@code storeDir} 不存在会被建出来
   */
  public CoreSimos(CoreConfig config) {
    Objects.requireNonNull(config, "config");
    this.store = SqliteStore.open(config.storeDir().resolve(DB_FILE_NAME));
    this.timeline = new Timeline(store, config.checkpointInterval());
    this.checkpoints = new CheckpointStore(config.storeDir());
  }

  /**
   * 注册一个模块的状态 codec。**必须在第一次 {@link #submit}/{@link #replay} 之前**完成。
   *
   * @return {@code this}（便于链式装配）
   * @throws IllegalStateException 已封存（见类注"封存规则"）
   * @throws NullPointerException {@code codec} 为 null
   */
  public CoreSimos register(ModuleCodec codec) {
    requireNotSealed();
    codecs.add(Objects.requireNonNull(codec, "codec"));
    return this;
  }

  /**
   * 注册一个领域命令 handler（{@code type()} 形如 {@code <namespace>.<Command>}）。**必须在封存之前**。
   *
   * @return {@code this}
   * @throws IllegalStateException 已封存
   * @throws NullPointerException {@code handler} 为 null
   */
  public CoreSimos register(CommandHandler handler) {
    requireNotSealed();
    handlers.add(Objects.requireNonNull(handler, "handler"));
    return this;
  }

  /**
   * 注册一个时间参与者。**必须在封存之前**（参与者清单在 {@link TimeAdvance} 构造期按 namespace 字典序定死，C25）。
   *
   * @return {@code this}
   * @throws IllegalStateException 已封存
   * @throws NullPointerException {@code participant} 为 null
   */
  public CoreSimos register(TimeParticipant participant) {
    requireNotSealed();
    participants.add(Objects.requireNonNull(participant, "participant"));
    return this;
  }

  /**
   * 提交一条命令并同步返回结局。首次调用会**封存**装配。
   *
   * <p>若结局是 {@link CommandResult.Committed}，随后按类注的分工写 checkpoint（失败只 WARN，不改结局）。
   *
   * @throws IllegalArgumentException 命令不是 Core 封闭集里的三种之一（由 {@link CommandBus} 判定，C16）
   * @throws NullPointerException {@code command} 为 null
   */
  public CommandResult submit(Command command) {
    Objects.requireNonNull(command, "command");
    CommandResult result = sealedBus().submit(command);
    writePostCommitCheckpoint(command, result);
    return result;
  }

  /**
   * 重放任一坐标，返回该坐标的完整状态。首次调用会**封存**装配。
   *
   * @throws IllegalArgumentException 坐标不在时间线上、或信封/变更集读不出
   * @throws IllegalStateException 父链中断、或无任何可用 checkpoint（C18 的回退到头）
   * @throws NullPointerException {@code ref} 为 null
   */
  public SimulationState replay(StateRef ref) {
    Objects.requireNonNull(ref, "ref");
    return sealedReplay().replay(ref).state();
  }

  // ── 只读面（spec §S8）─────────────────────────────────────────────────────────────────

  /**
   * 分支清单（spec §S8 / Timeline §3.3）：直接委托 {@link Timeline#branches()}，按 branch 字典序。
   *
   * <p>★ **只读、零写面**：本方法不封存、不触发 checkpoint、不改任何行——它只把时间线的既成事实读出来。核心的**唯一写入口仍是 {@link #submit}**（铁律
   * 2）。
   *
   * <p>★ **未封存与已封存两态都可调用**：数据在 {@link SqliteStore} 那条连接上，与本类的 seal 状态无关；调用本方法**不会**触发 {@link
   * #sealIfNeeded()}（seal 仍只由 {@link #submit}/{@link #replay} 触发）。
   *
   * @return 全部出现过 revision 的分支名；库为空时是空集
   */
  public Set<BranchId> branches() {
    return timeline.branches();
  }

  /**
   * 分支的 head（spec §S8 / Timeline §3.3）：直接委托 {@link Timeline#head(BranchId)}。
   *
   * <p>★ **只读、零写面**：同 {@link #branches()}——不封存、不写盘，唯一写入口仍是 {@link #submit}。
   *
   * <p>★ 分支不存在 ⇒ {@link Optional#empty()}，**不是**抛异常：GUI/查询层问"这个分支现在到哪"时，"还没有这个分支"是正常答案。
   *
   * @param branch 分支名；null ⇒ {@link NullPointerException}
   * @return 该分支的最大 revision；分支不存在 ⇒ 空
   */
  public Optional<RevisionId> head(BranchId branch) {
    Objects.requireNonNull(branch, "branch");
    return timeline.head(branch);
  }

  /**
   * 关闭底层存储。
   *
   * <p>★ 其余组件（{@code Timeline} / {@code Replay} / {@code CommandBus} / {@code TimeAdvance} /
   * 两张文件与内存 结构）**不持有 IO 资源**：它们全部在 {@link SqliteStore} 的那一条连接之上工作，故关闭它一个就够。 ★ 幂等（ {@link
   * SqliteStore#close()} 自身幂等）。
   */
  @Override
  public void close() {
    store.close();
  }

  // ── 封存 ────────────────────────────────────────────────────────────────────────────

  private void requireNotSealed() {
    if (sealed) {
      throw new IllegalStateException(
          "装配已封存（seal）：register 必须在第一次 submit/replay 之前完成"
              + "——CommandRegistry 是构造期不可变的（spec §4.3），封存后再注册会静默不生效");
    }
  }

  /** 首次使用时一次性建成全部组件。{@code synchronized} 保证"建成"这件事只发生一次、且对调用者可见。 */
  private synchronized void sealIfNeeded() {
    if (sealed) {
      return;
    }
    // ★ 先赋 replay：下面的 this::load 与两条注入都要用它（运行时才被调用，但赋值顺序让字段非空可见）
    this.replay = new Replay(timeline, checkpoints, codecs);
    CommandRegistry registry = new CommandRegistry(handlers);
    TimeAdvance timeAdvance =
        new TimeAdvance(timeline, this::load, checkpoints, codecs, participants);
    this.bus = new CommandBus(timeline, registry, timeAdvance, this::load);
    this.sealed = true;
  }

  private CommandBus sealedBus() {
    sealIfNeeded();
    return bus;
  }

  private Replay sealedReplay() {
    sealIfNeeded();
    return replay;
  }

  /**
   * {@code StateLoader} 的实现（裁定 34）：{@code Replay.replay} 的返回值是 {@code ReplayResult}（状态 + 步数）， 而
   * {@code StateLoader} 只要状态——故这里是**一层取值适配**，不是"多包一层"。
   *
   * <p>★ **取代说明（计划草图的实测校正）**：计划与台账里写的装配草图为 {@code new TimeAdvance(timeline, replay::replay, …)} /
   * {@code new CommandBus(…, replay::replay)}，但 {@code Replay.replay(StateRef)} 返回 {@link
   * Replay.ReplayResult}，**不能**直接当作 {@code StateLoader}（{@code StateRef → SimulationState}）。本类以
   * {@code this::load} 落地同一意图；语义与"传 {@code replay::replay}"完全一致（replay 结果的 {@code state()} 分量）。
   */
  private SimulationState load(StateRef ref) {
    return replay.replay(ref).state();
  }

  // ── Post-commit：C24 的 checkpoint ───────────────────────────────────────────────────

  /**
   * 提交成功后按类注的分工补写 checkpoint。
   *
   * <p>★ 只看 {@link CommandResult.Committed}——被拒 / 冲突**没有产生坐标**，也就没有"新坐标的状态"可写（它们的事件由各自 命令支负责）。
   */
  private void writePostCommitCheckpoint(Command command, CommandResult result) {
    if (!(result instanceof CommandResult.Committed committed)) {
      return;
    }
    if (command instanceof ForkBranch fork) {
      // C19 第②项：分岔点必须有一份（写完才让"重放上界 ≤ N"跨分支成立，spec §3.4 ④）
      maybeWriteCheckpoint(new StateRef(fork.source(), fork.expectedRevision()));
      return;
    }
    if (command instanceof CommandEnvelope) {
      // C19 第①项：只有周期命中才写——绝不写比谓词更多的文件（否则 R3 的反方向破）
      maybeWriteCheckpoint(committed.ref());
      return;
    }
    // AdvanceTime：真 TimeAdvance 的 ⑥ 已经写过（Task 12），本类**不重复**
  }

  /**
   * C19 谓词命中才写；任何 {@code RuntimeException} ⇒ WARN，不上抛（C18 / spec §3.4 ④）。
   *
   * <p>★ **先判谓词再试写**：谓词是纯函数（只看 revisions 表），不命中就根本不该碰磁盘——这正是"不多写文件"那条。
   *
   * <p>★ **信封内容由 {@link CheckpointEncoder} 产出**（与解码侧逐条对称）：模块载荷交各 codec，{@code info} 段走 info
   * mapper。写失败（I/O、或装配缺 codec 的模块）都在这里被折成 WARNING——**此刻命令已经提交**，异常逃出去会让调用方 看到假失败。
   */
  private void maybeWriteCheckpoint(StateRef ref) {
    if (!timeline.hasCheckpoint(ref)) {
      return;
    }
    try {
      SimulationState state = replay.replay(ref).state();
      checkpoints.write(ref, CheckpointEncoder.encode(state, codecs));
    } catch (RuntimeException e) {
      LOG.warn(
          "checkpoint 写入失败（C18：纯优化，不回退命令；重放将回退到更早的 checkpoint）: {}@{}",
          ref.branch().value(),
          ref.revision().value(),
          e);
    }
  }
}
