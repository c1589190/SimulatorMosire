package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
   * 有效位置（M3 spec §4.2 + Unit 扩容 spec §一.4 的五行情形的取代/共存）：自身有位置 ⇒ 它；否则 {@code attached} 为 true
   * 时向父取（{@code offset} 非空则叠加偏移），为 false 时**空（不回退父）**。
   *
   * <p>★ 正常路径下构造期已拒环；这里撞环抛 {@link IllegalStateException} 是因为**手工拼出的状态** 仍可能绕过（它属数据故障，不是"没有候选"）。
   */
  public Optional<HexCoord> effectivePosition(UnitId id, SimosTimestamp at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    return effectivePositionFrom(units.get(id), at, new LinkedHashSet<>());
  }

  private Optional<HexCoord> effectivePositionFrom(Unit unit, SimosTimestamp at, Set<UnitId> seen) {
    Unit current = unit;
    while (current != null) {
      if (!seen.add(current.id())) {
        throw new IllegalStateException("编制链成环（UnitState 构造期本应拒绝）: " + current.id());
      }
      Optional<HexCoord> here = current.position().valueAt(at);
      if (here.isPresent()) {
        return here; // 自身有位置 ⇒ 它（attached/detached 都适用）
      }
      if (!current.attached().valueAt(at)) {
        return Optional.empty(); // detached 且无自身位置 ⇒ 空（不回退父，spec §一.4）
      }
      Optional<UnitId> parentId = current.parent().valueAt(at);
      if (parentId.isEmpty()) {
        return Optional.empty();
      }
      Unit parent = units.get(parentId.get());
      if (parent == null) {
        return Optional.empty(); // 查无此父（手工拼的状态）⇒ 链到此为止
      }
      Optional<RelativeOffset> offset = current.offset().valueAt(at);
      if (offset.isEmpty()) {
        current = parent;
        continue;
      }
      return effectivePositionFrom(parent, at, seen);
    }
    return Optional.empty();
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
