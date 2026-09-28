package io.mosire.simos.economy.time;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★★ <b>日结算并行度配置</b>（R2 并行内核）：把"跑得多快"（worker/线程池大小）与"怎么分区"（结构分区数）<b>分开</b>， 后者是常量、前者才是可调项。
 *
 * <p>★★ <b>为什么分区数必须是结构常量</b>：{@link AccountPartitionKey#partitionIndexOf(String, int)} 把 分区数当哈希模数
 * —— 分区数一改，同一实体落的分区就变，{@link CommitOrder} 的 {@code (stage, partitionIndex, canonicalKey,
 * intraIndex)} 序也跟着变。若"4 线程用 4 个分区、8 线程用 8 个分区"， 1/4/8 三条路径的<b>提交序不同</b>，同一价位的分配、同一账户的先后都会被线程数改变 ——
 * 那正是 §4.5 禁止的。 ⇒ 本类固定 {@link #STRUCTURAL_PARTITIONS}，{@code workerCount} 只决定<b>谁先算完</b>，不参与任何判定。
 *
 * <p>★★ <b>1 线程不是另一套实现</b>：{@link #singleThreaded()} 仍然走同一份 {@link PartitionPlan}、同一套 {@link
 * AccountIntentBuffer}、同一个 {@link SettlementExecutor#commit}，只是 {@code pool == null} 时 {@link
 * SettlementExecutor#execute} 就地在调用线程按分区顺序逐个跑。
 *
 * <p>★ <b>线程池归属</b>：{@link #of(int)} 自建池并由本对象负责 {@link #close()}；{@link #of(int, ExecutorService)}
 * 用调用方注入的池（调用方负责关闭）。两者都不得跨日期复用同一批 worker 的可变状态 —— worker 只产出不可变意向， 缓冲是每个分区现建的（见 {@link
 * AccountIntentBuffer}）。
 */
public final class EconomyParallelism implements AutoCloseable {

  /**
   * ★★ <b>结构分区数（固定，不随线程数变）</b>：1/4/8 线程走同一份分区计划与同一个提交序。
   *
   * <p>★ 取 32 的理由：一次性结算的参与实体（hex / 账户 / 市场区）在真档是数千量级，32 个哈希桶足以把工作摊开； 桶多一个也不改变正确性（分区内仍串行、跨分区无共享账户）。★
   * 它<b>不是</b>性能旋钮，别为调优改它 —— 改它等于改提交序。
   */
  public static final int STRUCTURAL_PARTITIONS = 32;

  private final int workerCount;
  private final ExecutorService pool;
  private final boolean ownsPool;

  private EconomyParallelism(int workerCount, ExecutorService pool, boolean ownsPool) {
    if (workerCount < 1) {
      throw new IllegalArgumentException("workerCount 必须 ≥ 1: " + workerCount);
    }
    this.workerCount = workerCount;
    this.pool = pool;
    this.ownsPool = ownsPool;
  }

  /** ★ 单线程退化路径（默认；不建池、不启线程）。 */
  public static EconomyParallelism singleThreaded() {
    return new EconomyParallelism(1, null, false);
  }

  /**
   * ★ 自建 {@code workerCount} 个守护线程的固定池（{@link #close()} 负责关）。
   *
   * <p>★ {@code workerCount == 1} 时返回单线程退化路径（不建池）—— "1 线程"在任何调用点都不该表现为"额外的线程"。
   */
  public static EconomyParallelism of(int workerCount) {
    if (workerCount <= 1) {
      return singleThreaded();
    }
    AtomicInteger sequence = new AtomicInteger();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "simos-settlement-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return new EconomyParallelism(
        workerCount, Executors.newFixedThreadPool(workerCount, factory), true);
  }

  /**
   * ★ 用调用方注入的池（调用方负责生命周期）。{@code workerCount} 只作为配置记录，实际并发由池决定； {@code workerCount == 1}
   * 或不给池时仍走单线程退化路径。
   */
  public static EconomyParallelism of(int workerCount, ExecutorService injected) {
    if (workerCount <= 1 || injected == null) {
      return singleThreaded();
    }
    return new EconomyParallelism(workerCount, injected, false);
  }

  /** 配置的 worker 数（≥ 1；只服务日志与自检，不参与分区）。 */
  public int workerCount() {
    return workerCount;
  }

  /** ★★ 结构分区数（恒为 {@link #STRUCTURAL_PARTITIONS}；见类注）。 */
  public int partitionCount() {
    return STRUCTURAL_PARTITIONS;
  }

  /** 可交给 {@link SettlementExecutor#execute} 的池；单线程 ⇒ {@code null}（就地跑）。 */
  public ExecutorService poolOrNull() {
    return pool;
  }

  /** 这条配置会不会真的起多线程（{@code false} = 单线程退化路径）。 */
  public boolean parallel() {
    return workerCount > 1 && pool != null;
  }

  /** 只关自己建的池；注入的池由调用方关。 */
  @Override
  public void close() {
    if (ownsPool && pool != null) {
      pool.shutdown();
    }
  }

  @Override
  public String toString() {
    return "EconomyParallelism[workers="
        + workerCount
        + ", partitions="
        + STRUCTURAL_PARTITIONS
        + "]";
  }

  /** 供参数校验：配置不得为 null。 */
  public static EconomyParallelism requireNonNull(EconomyParallelism parallelism) {
    return Objects.requireNonNull(parallelism, "parallelism");
  }
}
