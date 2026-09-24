package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 单位状态（M3 spec §4.1/§4.2）：一张 {@code id → Unit} 的表 + **构造期的编制树不变量**。
 *
 * <p>★ **无环校验按"关键时点"逐点查**，**不得**把所有边合并成一张图查——`A→B`（t1）与 `B→A`（t2） 各自合法（改编是允许的），合并图会把它们误报成环。关键时点 =
 * 所有 {@code parent} 段 `from` 的集合， 父图只在段边界变化，故查遍关键时点即覆盖全时间轴（含 anchor 之前的恒定延拓）。
 *
 * <p>★ {@code units} 与 {@code commandChains} 都**保序不可变**（{@code LinkedHashMap} + {@code
 * unmodifiableMap}， **绝不用 {@code Map.copyOf}**）。
 *
 * <p>★ **T1 新增第二组件** {@code commandChains}（spec §一.2）：命令链引用完整性（commander/全部 members 必须存在于同快照的
 * {@code units}）在构造期强制；多属（同一 {@code UnitId} 出现在多条链）是**允许**的。
 */
public record UnitState(Map<UnitId, Unit> units, Map<CommandChainId, CommandChain> commandChains) {

  public UnitState {
    if (units == null) {
      throw new IllegalArgumentException("units 不得为 null");
    }
    Map<UnitId, Unit> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, Unit> entry : units.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("units 的键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    units = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    if (commandChains == null) {
      throw new IllegalArgumentException("commandChains 不得为 null（无链用 Map.of()）");
    }
    Map<CommandChainId, CommandChain> chainCopy = new LinkedHashMap<>();
    for (Map.Entry<CommandChainId, CommandChain> entry : commandChains.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("commandChains 的键与值都不得为 null");
      }
      chainCopy.put(entry.getKey(), entry.getValue());
    }
    commandChains =
        Collections.unmodifiableMap(chainCopy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    requireNoCycleAtKeyTimes(units);
    requireChainReferencesResolve(units, commandChains);
  }

  /** 兼容构造器（T1）：旧 1 参签名 ⇒ {@code commandChains} 为空表。 */
  public UnitState(Map<UnitId, Unit> units) {
    this(units, Map.of());
  }

  /** 往返用例的起点。 */
  public static UnitState empty() {
    return new UnitState(Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）——**保留另一组件**。 */
  public UnitState withUnits(Map<UnitId, Unit> value) {
    return new UnitState(value, commandChains);
  }

  /** 命令链组件的 with（保留 {@code units}）。 */
  public UnitState withCommandChains(Map<CommandChainId, CommandChain> value) {
    return new UnitState(units, value);
  }

  /**
   * 有效位置（**编制 v2**，2026-09-24 取代 Unit 扩容 spec §一.4 五行情形的"继承支"）：**就是该单位自己的位置**； 自己没有位置 = 不知在哪（空）。
   *
   * <p>★★ **这里是"取消跟随"的落点**（用户 2026-09-24 原话：「把跟随功能取消掉吧，如果要合并到同一一同移动的编制， 有且只有单位在同一格子时生效」）：旧语义下"无自身位置
   * + attached=true ⇒ 向父取（offset 叠加）"那一支**已作废**， 因为"跟随"整条机制被取消，改为**由顶层带动整支一起搬**（见 {@link
   * #formationMembers} 与 {@code UnitTimeParticipant}）。
   *
   * <p>★ **名字保留**（不改调用点）：它现在的"有效"只指"按时刻解析自身位置"，不再含任何继承。★ 行为差异只落在 **"无自身位置的单位"**上（旧：返回父位 /
   * 新：空）——本局世界里每个单位都有自身位置，故无观测差异。
   *
   * <p>★ 查无此人（或未给位置）⇒ 空：定位问的是"现在在哪"，没有答案就是没有。
   */
  public Optional<HexCoord> effectivePosition(UnitId id, SimosTimestamp at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    Unit unit = units.get(id);
    return unit == null ? Optional.empty() : unit.position().valueAt(at);
  }

  /**
   * **编制顶层（带动者）**：沿 {@code parent} 上溯，遇到 {@code attached=false} 的节点就停在它；**无父者一律算顶层** （不管 {@code
   * attached} 位——worldgen 造出的军根正是"无父 + attached=true"，读侧必须容错）。
   *
   * <p>★ 语义（编制 v2）：{@code parent} 是**编制归属**（谁向谁报告），{@code attached} 才是**是否与父一起走**。
   * 于是"有归属但独立行动"是一种正常状态（顶层 ID 就是那个独立者自己）；这正是用户那句「即使这个单位有归属，也可以自动移动」。
   *
   * <p>★ 查无此人 ⇒ 空。★ 撞环抛（正常路径下构造期已拒环；手工拼出的状态仍可能绕过 ⇒ 属数据故障，不静默）。
   */
  public Optional<UnitId> formationRoot(UnitId id, SimosTimestamp at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    Unit current = units.get(id);
    if (current == null) {
      return Optional.empty();
    }
    Set<UnitId> seen = new LinkedHashSet<>();
    while (true) {
      if (!seen.add(current.id())) {
        throw new IllegalStateException("编制链成环（UnitState 构造期本应拒绝）: " + current.id());
      }
      Optional<UnitId> parentId = current.parent().valueAt(at);
      if (parentId.isEmpty()) {
        return Optional.of(current.id()); // 无父 ⇒ 顶层
      }
      if (!current.attached().valueAt(at)) {
        return Optional.of(current.id()); // 有归属但独立 ⇒ 顶层就是它自己
      }
      Unit parent = units.get(parentId.get());
      if (parent == null) {
        return Optional.of(current.id()); // 父查无（手工拼出的状态）⇒ 到此为顶
      }
      current = parent;
    }
  }

  /**
   * **该顶层带着一起走的整支**：它自己 + 全部"经 {@code attached=true} 链可达"的后代。
   *
   * <p>★★ **这是"整支移动"与"整支速度"的同一个取数点**（{@code planRoute} 装载速度、{@code UnitTimeParticipant}
   * 搬移都走它）：两处各写一遍遍历，迟早会给出**不一样的集合**（比如一处把 detached 后代也算进来），而那种偏差只会在 某些编制形状下显形。★ {@code
   * attached=false} 的后代**不在其中**（它们是各自独立的顶层，不跟着本支走）。
   *
   * <p>★ 入参给的不是顶层也没关系（结果按"沿 attached 链能到谁"算），但调用方应先用 {@link #formationRoot} 求顶层。
   */
  public Set<UnitId> formationMembers(UnitId rootId, SimosTimestamp at) {
    Objects.requireNonNull(rootId, "rootId");
    Objects.requireNonNull(at, "at");
    if (!units.containsKey(rootId)) {
      return Set.of();
    }
    Map<UnitId, List<UnitId>> children = childrenAt(at);
    Set<UnitId> members = new LinkedHashSet<>();
    Deque<UnitId> queue = new ArrayDeque<>();
    queue.add(rootId);
    while (!queue.isEmpty()) {
      UnitId id = queue.poll();
      if (!members.add(id)) {
        continue;
      }
      for (UnitId child : children.getOrDefault(id, List.of())) {
        Unit childUnit = units.get(child);
        // ★ 只有"与父一起走"的后代才在本支里；detached 的另起一支（它自己带自己的下挂）。
        if (childUnit != null && childUnit.attached().valueAt(at)) {
          queue.add(child);
        }
      }
    }
    return Collections.unmodifiableSet(members);
  }

  /**
   * **整支速度**：{@link #formationMembers} 里所有单位 {@link Unit#effectiveSpeed()} 的**最小值**。
   *
   * <p>★ 口径 = 用户 2026-09-24 的裁定「这个单位有下挂单位总速度为下挂单位中最慢者速度」，且明确选**含状态折算** （{@code effectiveSpeed} 已经把
   * RESTING 半速 / ENGAGED ¼ 折进去 ⇒ 一个休整中的慢下属会真的拖住整支）。
   *
   * <p>★ 顶层查无 ⇒ 0（调用方在装载前必已 `require` 过存在性，故这条只是防御）。
   */
  public int formationSpeed(UnitId rootId, SimosTimestamp at) {
    Set<UnitId> members = formationMembers(rootId, at);
    int slowest = Integer.MAX_VALUE;
    for (UnitId member : members) {
      slowest = Math.min(slowest, units.get(member).effectiveSpeed());
    }
    return slowest == Integer.MAX_VALUE ? 0 : slowest;
  }

  /**
   * 按时刻物化"谁是谁的子"（{@code parent} 是单值序列 ⇒ 每个时刻一张森林）。
   *
   * <p>★ 做一次索引而不是每次遍历全表：{@link #formationMembers} 会被"每个在途单位"调到，朴素写法是 O(单位数²)。
   */
  private Map<UnitId, List<UnitId>> childrenAt(SimosTimestamp at) {
    Map<UnitId, List<UnitId>> children = new LinkedHashMap<>();
    for (Unit unit : units.values()) {
      Optional<UnitId> parentId = unit.parent().valueAt(at);
      if (parentId.isPresent()) {
        children.computeIfAbsent(parentId.get(), key -> new ArrayList<>()).add(unit.id());
      }
    }
    return children;
  }

  private static void requireChainReferencesResolve(
      Map<UnitId, Unit> units, Map<CommandChainId, CommandChain> commandChains) {
    for (CommandChain chain : commandChains.values()) {
      if (!units.containsKey(chain.commander())) {
        throw new IllegalArgumentException(
            "链 " + chain.id() + " 的 commander 不在 units: " + chain.commander());
      }
      for (UnitId member : chain.members()) {
        if (!units.containsKey(member)) {
          throw new IllegalArgumentException("链 " + chain.id() + " 的成员不在 units: " + member);
        }
      }
    }
  }

  private static void requireNoCycleAtKeyTimes(Map<UnitId, Unit> units) {
    Set<SimosTimestamp> keyTimes = new LinkedHashSet<>();
    for (Unit unit : units.values()) {
      for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
        keyTimes.add(segment.from());
      }
    }
    for (SimosTimestamp at : keyTimes) {
      Map<UnitId, UnitId> parentOf = new LinkedHashMap<>();
      for (Unit unit : units.values()) {
        unit.parent().valueAt(at).ifPresent(parent -> parentOf.put(unit.id(), parent));
      }
      for (UnitId start : parentOf.keySet()) {
        Set<UnitId> seen = new LinkedHashSet<>();
        UnitId current = start;
        while (current != null && parentOf.containsKey(current)) {
          if (!seen.add(current)) {
            throw new IllegalArgumentException("编制树在 " + at + " 成环，环上含 " + current);
          }
          current = parentOf.get(current);
        }
      }
    }
  }
}
