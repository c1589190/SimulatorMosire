package io.mosire.simos.economy.time;

import java.util.Objects;

/**
 * ★★ <b>稳定提交序</b>（R1 并行内核）：{@code (stage, partitionIndex, canonicalKey, intraIndex)} 的四元字典序。
 *
 * <p>★★ <b>唯一提交序</b>：单线程提交器只按本类型排序，<b>不按 worker 完成顺序</b>、不按线程 id、不按随机数。 分区函数只依赖 canonical 串（见
 * {@link AccountPartitionKey#partitionIndexOf(String, int)}）⇒ 1/4/8 线程
 * 产出的意向集合在排序后<b>逐条同序</b>；{@code intraIndex} 是 worker 在<b>自己分区内</b>按下标递增的序号， 分区内实体按 canonical 升序遍历 ⇒
 * 同一分区的意向生成序是内容的纯函数。
 *
 * @param stage 阶段（ordinal 即日序）
 * @param partitionIndex 分区号（∈ [0, partitionCount)）
 * @param canonicalKey 分区实体的 canonical 串（账户 = {@link AccountPartitionKey#canonical()}；hex/市场区 = 其
 *     canonical 串）
 * @param intraIndex 分区内序号（≥ 0；同一 worker 的意向计数器）
 */
public record CommitOrder(
    SettlementStage stage, int partitionIndex, String canonicalKey, int intraIndex)
    implements Comparable<CommitOrder> {

  public CommitOrder {
    Objects.requireNonNull(stage, "CommitOrder.stage 不得为 null");
    if (partitionIndex < 0) {
      throw new IllegalArgumentException("CommitOrder.partitionIndex 不得为负: " + partitionIndex);
    }
    if (canonicalKey == null || canonicalKey.isBlank()) {
      throw new IllegalArgumentException("CommitOrder.canonicalKey 不得为空白");
    }
    if (intraIndex < 0) {
      throw new IllegalArgumentException("CommitOrder.intraIndex 不得为负: " + intraIndex);
    }
  }

  @Override
  public int compareTo(CommitOrder other) {
    Objects.requireNonNull(other, "other");
    int byStage = Integer.compare(stage.ordinal(), other.stage.ordinal());
    if (byStage != 0) {
      return byStage;
    }
    int byPartition = Integer.compare(partitionIndex, other.partitionIndex);
    if (byPartition != 0) {
      return byPartition;
    }
    int byKey = canonicalKey.compareTo(other.canonicalKey);
    if (byKey != 0) {
      return byKey;
    }
    return Integer.compare(intraIndex, other.intraIndex);
  }
}
