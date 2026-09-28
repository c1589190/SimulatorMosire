package io.mosire.simos.economy.time;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★ <b>确定性分区计划</b>（R1 并行内核）：把一个阶段的实体按 canonical 键切成 {@code partitionCount} 个分区， 并且<b>1/4/8
 * 线程走同一份计划</b>。
 *
 * <p>★★ <b>三条硬口径</b>：
 *
 * <ol>
 *   <li>分区函数只依赖 canonical 串（{@link AccountPartitionKey#partitionIndexOf(String, int)}）⇒ 同一实体在
 *       任何线程数下落同一分区；
 *   <li>分区内实体按 canonical 升序排列 ⇒ worker 的遍历序是内容的纯函数（意向生成序因此确定）；
 *   <li>分区本身按 {@code partitionIndex} 升序排列 ⇒ 单线程退化路径"按同一分区顺序循环"与多线程的结果合并顺序一致。
 * </ol>
 *
 * <p>★ 分区粒度由调用方给：账户分区用 {@link AccountPartitionKey#canonical()}；消费/投入/借粮用 hex 的 canonical 串；
 * 区内市场用市场区的 canonical 字符串。★ 本类<b>不</b>决定冲突表（谁与谁必须同区）—— 那是调用方按计划 §4.3 的账户冲突表 保证的：凡可能写同一 {@code
 * (actor, location)} 的两个任务，必须给同一个 canonical 键。
 */
public final class PartitionPlan {

  /** 一个分区：分区号 + 该分区内按 canonical 升序的实体键（只读）。 */
  public record Partition(int partitionIndex, List<String> canonicalKeys) {

    public Partition {
      if (partitionIndex < 0) {
        throw new IllegalArgumentException("Partition.partitionIndex 不得为负: " + partitionIndex);
      }
      canonicalKeys =
          Collections.unmodifiableList(
              new ArrayList<>(Objects.requireNonNull(canonicalKeys, "canonicalKeys")));
    }

    public int size() {
      return canonicalKeys.size();
    }
  }

  private final SettlementStage stage;
  private final int partitionCount;
  private final List<Partition> partitions;

  private PartitionPlan(SettlementStage stage, int partitionCount, List<Partition> partitions) {
    this.stage = Objects.requireNonNull(stage, "stage");
    this.partitionCount = partitionCount;
    this.partitions = Collections.unmodifiableList(new ArrayList<>(partitions));
  }

  /**
   * 由实体 canonical 键集合构造计划（去重 + canonical 升序）。
   *
   * @param canonicalKeys 参与本阶段的实体 canonical 串（账户 / hex / 市场区…）；只依赖串内容
   * @param partitionCount 分区数（≥ 1；= 并行 worker 数）
   */
  public static PartitionPlan of(
      SettlementStage stage, Collection<String> canonicalKeys, int partitionCount) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(canonicalKeys, "canonicalKeys");
    if (partitionCount < 1) {
      throw new IllegalArgumentException("分区数必须 ≥ 1: " + partitionCount);
    }
    // TreeSet：去重 + canonical 升序（绝不用 HashMap/HashSet 的裸迭代进入分区/算术）。
    TreeSet<String> sorted = new TreeSet<>();
    for (String canonical : canonicalKeys) {
      if (canonical == null || canonical.isBlank()) {
        throw new IllegalArgumentException("分区实体键不得为空白: " + canonical);
      }
      sorted.add(canonical);
    }
    TreeMap<Integer, List<String>> byIndex = new TreeMap<>();
    for (String canonical : sorted) {
      int index = AccountPartitionKey.partitionIndexOf(canonical, partitionCount);
      byIndex.computeIfAbsent(index, ignored -> new ArrayList<>()).add(canonical);
    }
    List<Partition> partitions = new ArrayList<>(byIndex.size());
    for (Map.Entry<Integer, List<String>> entry : byIndex.entrySet()) {
      partitions.add(new Partition(entry.getKey(), entry.getValue()));
    }
    return new PartitionPlan(stage, partitionCount, partitions);
  }

  /** 空计划（该阶段没有分区实体 ⇒ worker 数量为 0，直接交回空结果）。 */
  public static PartitionPlan empty(SettlementStage stage, int partitionCount) {
    return of(stage, List.of(), partitionCount);
  }

  public SettlementStage stage() {
    return stage;
  }

  public int partitionCount() {
    return partitionCount;
  }

  /** 分区列表（按 partitionIndex 升序；只读）。 */
  public List<Partition> partitions() {
    return partitions;
  }

  /** 某个 canonical 键在计划里的分区号（与构造时同一函数，供调用方自检/对账）。 */
  public int partitionIndexOf(String canonical) {
    return AccountPartitionKey.partitionIndexOf(canonical, partitionCount);
  }
}
