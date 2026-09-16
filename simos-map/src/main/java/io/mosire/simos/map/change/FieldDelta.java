package io.mosire.simos.map.change;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一个状态组件的差异。
 *
 * <p>★ {@code Unchanged} 与"变为空"是**两件事** —— GSimulator 的 {@code MapDiff.isEmpty()}
 * 混淆了这两者，导致"只改了一条边"产生空 diff、进而**根本不进 apply 流程**。
 *
 * <p>★ **四条变体各自的语义**（{@code MapChangeSet.apply} 逐条照此实现）：
 *
 * <ul>
 *   <li>{@link Unchanged} —— 该组件的内容**一字未动**。"内容变成空的"**不是**这一条，是 {@link Remove}。
 *   <li>{@link Upsert} —— 在 base 的该组件上**新增或覆盖**这些 key，其余 key 原样保留（**增量，不是全量替换**）。
 *   <li>{@link Remove} —— 从 base 的该组件里**删掉**这些 key，其余 key 原样保留。
 *   <li>{@link Patch} —— 同一组件**又增又删**，两侧各自是上面那两条。
 * </ul>
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

  /**
   * 同一组件**又增又删**。两侧各自沿用 {@link Upsert}/{@link Remove}，故非空、保序、冻结、null 校验全部**继承**，本类型不新增一行校验。
   *
   * <p>★ **语义：先删后增** —— 等于"先走 {@link Remove} 那一路、再走 {@link Upsert} 那一路"。若某个 key **两侧都在**（{@code
   * MapChangeSet.between} 不会产出这种重叠，只有手搓才可能），**增胜**。
   *
   * <p>★ **不为重叠写守卫**：{@code between} 是唯一生产者、不可能产出重叠，为不存在的输入写守卫正是 R-48-e 反对的"为不存在的世界写代码"。
   */
  record Patch<T>(Upsert<T> upserts, Remove<T> removals) implements FieldDelta<T> {

    public Patch {
      Objects.requireNonNull(upserts, "upserts");
      Objects.requireNonNull(removals, "removals");
    }

    @Override
    public Optional<T> lookup(String key) {
      return upserts.lookup(key); // 删除那一侧没有"新值"可给，故只看 upserts
    }
  }

  /** 本组件是否有变化。 */
  default boolean changed() {
    return !(this instanceof Unchanged<T>);
  }

  /** 取出本组件里的某 key 的新值；未变或不在 Upsert 里则返回缺席。 */
  Optional<T> lookup(String key);
}
