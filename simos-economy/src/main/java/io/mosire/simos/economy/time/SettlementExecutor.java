package io.mosire.simos.economy.time;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * ★★ <b>并行结算的统一执行器</b>（R1 并行内核）：<b>分区计算</b>与<b>稳定提交</b>的唯一接口。
 *
 * <p>★★ <b>两件事，两个方法</b>：
 *
 * <ol>
 *   <li>{@link #execute} —— 按 {@link PartitionPlan} 把分区发给 worker。worker 只准读 {@link
 *       AccountSnapshot}、只准写自己的 {@link AccountIntentBuffer}（或返回纯计算结果）； <b>不得</b>触碰共享账户 Map。★
 *       单线程路径（{@code pool == null} 或只有一个分区）与多线程走 <b>同一个计划、同一个提交序</b>，只是执行器不同 —— 1 线程不是"另一套实现"；
 *   <li>{@link #commit} —— <b>单线程</b>按 {@code (stage, partitionIndex, canonicalKey, intraIndex)}
 *       字典序落账；转移一条不差地喂给 {@code EconomySettlement.applyTransfer}（唯一写口）。
 * </ol>
 *
 * <p>★★ <b>不允许的事</b>（硬边界，写在这里当静态审计的判据）：
 *
 * <ul>
 *   <li>不允许 {@code synchronized} 包住整段日结算冒充线程安全；
 *   <li>不允许 worker 直接调 {@link AccountSession} 的活视图 / {@link AccountIntentBuffer} 以外的写口；
 *   <li>不允许按 worker 完成顺序 / 线程 id / 随机 UUID 决定提交先后；
 *   <li>不允许跨日期并行：{@code execute} 一次只服务同一天的一个阶段。
 * </ul>
 *
 * <p>★ <b>异常语义</b>：任一 worker 抛出 ⇒ 整个阶段失败（异常原样向上抛，不吞）；提交期的校验失败同样整批拒绝。 本类不产生"半截 revision"——
 * 提交前的意向都是不可变的，提交循环中途抛错由调用方按既有 {@code TimeAdvance} 的 失败语义整体丢弃。
 */
public final class SettlementExecutor {

  /** 单线程提交口：三种意向各走自己的落账路径（转移必须走唯一写口）。 */
  public interface IntentSink {

    void applyTransfer(TransferIntent intent);

    void applyDelta(AccountDelta intent);

    void applyFreeze(FreezeIntent intent);
  }

  private SettlementExecutor() {}

  /**
   * ★★ <b>分区计算</b>：严格按 {@code plan.partitions()} 的顺序返回结果。
   *
   * <p>★ 单线程退化路径（{@code pool == null}）：就地在调用线程按同一分区顺序逐个跑 worker —— 与多线程路径 用同一份计划、同一套
   * worker，只是没有线程池。★ 多线程路径：按计划顺序提交、按计划顺序 {@code get()}， <b>结果顺序与线程调度无关</b>；任一 worker 抛错 ⇒ 立刻向上传播。
   *
   * @param worker 分区 → 纯计算结果（通常是 {@code AccountIntentBuffer} 或不可变记录）
   * @param pool 线程池；{@code null} = 单线程退化路径（R1 默认）。跨日期并行被禁止：池只服务本阶段
   */
  public static <T> List<T> execute(
      PartitionPlan plan, Function<PartitionPlan.Partition, T> worker, ExecutorService pool) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(worker, "worker");
    List<PartitionPlan.Partition> partitions = plan.partitions();
    List<T> results = new ArrayList<>(partitions.size());
    if (pool == null || partitions.size() <= 1) {
      for (PartitionPlan.Partition partition : partitions) {
        results.add(worker.apply(partition));
      }
      return Collections.unmodifiableList(results);
    }
    List<Future<T>> futures = new ArrayList<>(partitions.size());
    for (PartitionPlan.Partition partition : partitions) {
      futures.add(pool.submit(() -> worker.apply(partition)));
    }
    for (Future<T> future : futures) {
      try {
        results.add(future.get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("结算分区任务被中断（推进中断 = 失败，不产生半截 revision）", e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        if (cause instanceof Error error) {
          throw error;
        }
        throw new IllegalStateException("结算分区任务失败", cause);
      }
    }
    return Collections.unmodifiableList(results);
  }

  /**
   * ★★ <b>稳定提交</b>：把各分区产出的意向集合按 {@link CommitOrder} 字典序排序后逐条落账。
   *
   * <p>★ <b>并列键判死</b>：同一 {@code (stage, partitionIndex, canonicalKey, intraIndex)} 出现两次，说明分区或 序号分配有
   * bug（例如两个 worker 写了同一账户）⇒ 当场抛，<b>不靠</b> {@code List.sort} 的稳定性兜底。
   */
  public static void commit(Collection<? extends OrderedAccountIntent> intents, IntentSink sink) {
    Objects.requireNonNull(intents, "intents");
    Objects.requireNonNull(sink, "sink");
    List<OrderedAccountIntent> ordered = new ArrayList<>(intents);
    ordered.sort(null);
    for (int i = 1; i < ordered.size(); i++) {
      if (ordered.get(i - 1).order().equals(ordered.get(i).order())) {
        throw new IllegalStateException(
            "提交序出现并列键（分区或序号分配有 bug，拒绝按到达序兜底）: " + ordered.get(i).order());
      }
    }
    for (OrderedAccountIntent intent : ordered) {
      if (intent instanceof TransferIntent transferIntent) {
        sink.applyTransfer(transferIntent);
      } else if (intent instanceof AccountDelta accountDelta) {
        sink.applyDelta(accountDelta);
      } else if (intent instanceof FreezeIntent freezeIntent) {
        sink.applyFreeze(freezeIntent);
      } else {
        throw new IllegalStateException("未知的账户意向类型: " + intent.getClass().getName());
      }
    }
  }
}
