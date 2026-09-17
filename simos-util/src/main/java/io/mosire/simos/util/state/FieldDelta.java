package io.mosire.simos.util.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 一个状态组件的差异。
 *
 * <p>★ **本类型在 Util，不在 map**（C7）：它是通用机制件——三个变更集（map / social / unit）共用同一份 "差异 + 重建"语义，放在 map 里会让
 * social/unit 的变更集依赖 map 的变更机制（语义错位）。配套机制 {@link #diff} / {@link #rebuild} 一并在此，故任何模块都不需要第二份实现（R1
 * 扫描守卫把守"全仓恰一份"）。
 *
 * <p>★ {@code Unchanged} 与"变为空"是**两件事** —— GSimulator 的 {@code MapDiff.isEmpty()}
 * 混淆了这两者，导致"只改了一条边"产生空 diff、进而**根本不进 apply 流程**。
 *
 * <p>★ **四条变体各自的语义**（各变更集的 {@code apply} 逐条照此实现）：
 *
 * <ul>
 *   <li>{@link Unchanged} —— 该组件的内容**一字未动**。"内容变成空的"**不是**这一条，是 {@link Remove}。
 *   <li>{@link Upsert} —— 在 base 的该组件上**新增或覆盖**这些 key，其余 key 原样保留（**增量，不是全量替换**）。
 *   <li>{@link Remove} —— 从 base 的该组件里**删掉**这些 key，其余 key 原样保留。
 *   <li>{@link Patch} —— 同一组件**又增又删**，两侧各自是上面那两条。
 * </ul>
 *
 * <p>★ **key 一律是 String**：状态 map 的 key 由 {@code toString()} 变成规范串 （各 key 类型各有"裸值 {@code toString()}
 * + {@code static parse}"，见 R-48-f），值就是组件值本身， 判等**用 {@code equals}**（与 map
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
 * <p>★ **类型信息直接以注解钉在本接口上**（M4 / 台账裁定 4，2026-09-18）：本接口是 sealed 多态类型，裸往返**不可能**——探针实测 {@code
 * Unchanged}/{@code Upsert} 均"序列化出字节、反序列化必死"，且 {@code activateDefaultTyping(NON_FINAL)} 修不了它（record
 * 是 final，写出侧 不带 type id、读入侧的接口却非要一个）。两种落地都实测过：注解 vs mixin。**选注解**，理由：mixin 必须在**每一台** mapper 上补注册，
 * 忘了就是静默失效、恰好退化回今天的探针报错（{@code no Creators / abstract types}，实测复现）；注解跟着类型走，连裸 {@code new
 * ObjectMapper()} 都认得。"状态类型带 Jackson 注解"的代价在这里不成立——带注解的是 util 自己的机制件，而 util 的白名单本来就只有 Jackson；
 * 领域状态类型（{@code GameMap}/{@code Unit}/…）在两种方案下都保持零注解。type id 用 {@link JsonTypeInfo.Id#NAME} 而非
 * {@code Id.CLASS}（两种也都实测往返通过）：{@code Id.CLASS} 会把**全限定类名**写进存档（本例 {@code
 * …FieldDelta$Upsert}），持久化格式从此与 类名耦合——重命名/挪包即全部旧档不可读；且 {@code Id.NAME} + {@code @JsonSubTypes}
 * 是**封闭**子类集，读入侧不接受任意的 classpath 类名。属性名用 {@code "@class"}：{@code @} 前缀不可能与四个变体的真实属性（{@code
 * entries}/{@code keys}/{@code upserts}/{@code removals}）撞名。
 *
 * @param <T> 组件值的类型
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = FieldDelta.Unchanged.class, name = "unchanged"),
  @JsonSubTypes.Type(value = FieldDelta.Upsert.class, name = "upsert"),
  @JsonSubTypes.Type(value = FieldDelta.Remove.class, name = "remove"),
  @JsonSubTypes.Type(value = FieldDelta.Patch.class, name = "patch"),
})
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
   * <p>★ **语义：先删后增** —— 等于"先走 {@link Remove} 那一路、再走 {@link Upsert} 那一路"。若某个 key **两侧都在**（{@link
   * #diff} 不会产出这种重叠，只有手搓才可能），**增胜**。
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

  /**
   * 两份 map 的差异（{@link #rebuild} 的逆）。
   *
   * <p>★ **顺着 {@code target} 的迭代序读**，故 upsert 的键序 = target 的序（保序不可变是前提，见类注释）。
   *
   * <p>★ **同时有"增"与"删" ⇒ {@link Patch}**（两侧各自是 {@link Upsert} 与 {@link Remove}），**两侧都保留、 不丢任何一侧** ——
   * 丢删除正是 GSimulator"只改了一条边产生空 diff"的病根。
   */
  static <K, V> FieldDelta<V> diff(Map<K, V> base, Map<K, V> target) {
    Map<String, V> upserts = new LinkedHashMap<>();
    Set<String> removals = new LinkedHashSet<>();
    for (Map.Entry<K, V> entry : target.entrySet()) {
      if (!entry.getValue().equals(base.get(entry.getKey()))) {
        // 同 key 不同 value 与"新增的 key"走同一条：base.get 缺席即 null，equals 必为 false。
        upserts.put(entry.getKey().toString(), entry.getValue());
      }
    }
    for (K key : base.keySet()) {
      if (!target.containsKey(key)) {
        removals.add(key.toString());
      }
    }
    if (upserts.isEmpty() && removals.isEmpty()) {
      return new Unchanged<>();
    }
    if (upserts.isEmpty()) {
      return new Remove<>(removals);
    }
    if (removals.isEmpty()) {
      return new Upsert<>(upserts);
    }
    return new Patch<>(new Upsert<>(upserts), new Remove<>(removals));
  }

  /**
   * 从 base 与差异重建一份 map（{@link #diff} 的逆）。四条变体各一路，见 {@link Patch} 的语义。
   *
   * <p>★ **只有新出现的 key 需要 {@code parse}**：已在 base 里的 key 直接复用原对象（这正是各 key 类型 "裸值 {@code toString()}
   * + {@code static parse}" 三件套被用到的地方）。
   */
  static <K, V> Map<K, V> rebuild(Map<K, V> base, FieldDelta<V> delta, Function<String, K> parse) {
    if (!delta.changed()) {
      return base;
    }
    if (delta instanceof Remove<V> remove) {
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        if (!remove.keys().contains(entry.getKey().toString())) {
          out.put(entry.getKey(), entry.getValue());
        }
      }
      return out;
    }
    if (delta instanceof Upsert<V> upsert) {
      Map<String, V> entries = upsert.entries();
      Set<String> fromBase = new LinkedHashSet<>();
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        String key = entry.getKey().toString();
        fromBase.add(key);
        out.put(entry.getKey(), entries.containsKey(key) ? entries.get(key) : entry.getValue());
      }
      for (Map.Entry<String, V> entry : entries.entrySet()) {
        if (!fromBase.contains(entry.getKey())) {
          out.put(parse.apply(entry.getKey()), entry.getValue());
        }
      }
      return out;
    }
    if (delta instanceof Patch<V> patch) {
      // ★ **先删后增**（见 Patch 的语义），且**复用上面那两路**：Patch 的正确性恰恰**等于**
      //   "那两条纯情形的语义"，这里重新实现一遍就有了跟它们分叉的可能。递归调用即复用。
      return rebuild(rebuild(base, patch.removals(), parse), patch.upserts(), parse);
    }
    // 四条变体已穷尽；走到这里说明 FieldDelta 新增了变体而这里没跟上 —— 与铁律 5 同源的漂移，必须响。
    throw new IllegalStateException("未知的 FieldDelta 变体: " + delta.getClass());
  }

  /** 本组件是否有变化。 */
  default boolean changed() {
    return !(this instanceof Unchanged<T>);
  }

  /** 取出本组件里的某 key 的新值；未变或不在 Upsert 里则返回缺席。 */
  Optional<T> lookup(String key);
}
