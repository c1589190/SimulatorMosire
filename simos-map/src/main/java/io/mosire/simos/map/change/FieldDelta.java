package io.mosire.simos.map.change;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一个状态组件的差异。
 *
 * <p>★ {@code Unchanged} 与"变为空"是**两件事** —— GSimulator 的 {@code MapDiff.isEmpty()}
 * 混淆了这两者，导致"只改了一条边"产生空 diff、进而**根本不进 apply 流程**。
 *
 * <p>★ **三条变体各自的语义**（{@code MapChangeSet.apply} 逐条照此实现）：
 *
 * <ul>
 *   <li>{@link Unchanged} —— 该组件的内容**一字未动**。"内容变成空的"**不是**这一条，是 {@link Remove}。
 *   <li>{@link Upsert} —— 在 base 的该组件上**新增或覆盖**这些 key，其余 key 原样保留（**增量，不是全量替换**）。
 *   <li>{@link Remove} —— 从 base 的该组件里**删掉**这些 key，其余 key 原样保留。
 * </ul>
 *
 * <p>★ **一条组件只能有一种变体**：同一组件若**同时**"增"与"删"，这三条变体表达不了。遇到这种输入时 {@code MapChangeSet.between}
 * **当场抛**（{@link UnsupportedOperationException}）而**不静默丢弃任何一侧** —— 静默丢删除正是 GSimulator"只改了一条边产生空
 * diff"的病根。要同时表达，拆成两条变更集先后 apply。
 *
 * <p>★ **key 一律是 String**：{@code GameMap} 的 map key 由 {@code toString()} 变成地址串 （五个 key 类型各有"裸值
 * {@code toString()} + {@code static parse}"，见 R-48-f），值就是组件值本身， 判等**用 {@code equals}**（与 map
 * 迭代序无关：顺序变了而内容没变**不是**状态变更）。
 *
 * <p>★ 两个容器的**保序**与冻结是同一件事的两半：{@link LinkedHashMap}/{@link LinkedHashSet} 管"序 = 插入序"， {@code
 * unmodifiable*} 管"不可变"。**不得**改用 {@code Map.copyOf}/{@code Set.copyOf} —— 它们走 {@code
 * ImmutableCollections}，迭代序**不是内容的纯函数**（两键撞槽时相对次序随插入序），于是同一份数据会产出不同字节、 变更集跨运行漂移（Task 5 实测，见 {@code
 * GameMap} 的类注释）。
 *
 * <p>★ 两处构造是**逐字展开**的，没抽 helper：冻结那一步必须**写在赋值处**，否则 SpotBugs 报 {@code EI_EXPOSE_REP}（它只认自己**看得见**的
 * {@code Collections.unmodifiable*}）；而把这段挪进接口的 {@code private static} 会再报一条 {@code
 * UPM_UNCALLED_PRIVATE_METHOD} —— 跨类的私有接口方法调用它追不到。同 {@code GameMap} 的构造：展开换门禁干净，"拷一份 + 冻一层"也一眼可见。
 *
 * @param <T> 组件值的类型
 */
public sealed interface FieldDelta<T> {

  /** 未变。 */
  record Unchanged<T>() implements FieldDelta<T> {

    @Override
    public Optional<T> lookup(String key) {
      return Optional.empty();
    }
  }

  /** 新增或覆盖。key → 新值。 */
  record Upsert<T>(Map<String, T> entries) implements FieldDelta<T> {

    public Upsert {
      if (entries == null) {
        throw new IllegalArgumentException("Upsert.entries 不得为 null");
      }
      Map<String, T> copy = new LinkedHashMap<>();
      for (Map.Entry<String, T> entry : entries.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("Upsert.entries 的键与值都不得为 null: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      entries = Collections.unmodifiableMap(copy); // ★ 冻在赋值处，见类注释
      if (entries.isEmpty()) {
        throw new IllegalArgumentException("Upsert 不得为空");
      }
    }

    @Override
    public Optional<T> lookup(String key) {
      return Optional.ofNullable(entries.get(key));
    }
  }

  /** 删除。 */
  record Remove<T>(Set<String> keys) implements FieldDelta<T> {

    public Remove {
      if (keys == null) {
        throw new IllegalArgumentException("Remove.keys 不得为 null");
      }
      Set<String> copy = new LinkedHashSet<>();
      for (String key : keys) {
        if (key == null) {
          throw new IllegalArgumentException("Remove.keys 不得含 null");
        }
        copy.add(key);
      }
      keys = Collections.unmodifiableSet(copy); // ★ 冻在赋值处，见类注释
      if (keys.isEmpty()) {
        throw new IllegalArgumentException("Remove 不得为空");
      }
    }

    @Override
    public Optional<T> lookup(String key) {
      return Optional.empty();
    }
  }

  /** 本组件是否有变化。 */
  default boolean changed() {
    return !(this instanceof Unchanged<T>);
  }

  /** 取出本组件里的某 key 的新值；未变或不在 Upsert 里则返回缺席。 */
  Optional<T> lookup(String key);
}
