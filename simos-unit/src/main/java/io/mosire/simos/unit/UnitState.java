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
 * <p>★ {@code units} **保序不可变**（{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**）。
 */
public record UnitState(Map<UnitId, Unit> units) {

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
    requireNoCycleAtKeyTimes(units);
  }

  /** 往返用例的起点。 */
  public static UnitState empty() {
    return new UnitState(Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public UnitState withUnits(Map<UnitId, Unit> value) {
    return new UnitState(value);
  }

  /**
   * 有效位置（M3 spec §4.2）：自身有位置 ⇒ 它；否则向父取，递归；无父或查无此人 ⇒ 空。
   *
   * <p>★ 正常路径下构造期已拒环；这里撞环抛 {@link IllegalStateException} 是因为**手工拼出的状态** 仍可能绕过（它属数据故障，不是"没有候选"）。
   */
  public Optional<HexCoord> effectivePosition(UnitId id, SimosTimestamp at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    Unit unit = units.get(id);
    Set<UnitId> seen = new LinkedHashSet<>();
    while (unit != null) {
      if (!seen.add(unit.id())) {
        throw new IllegalStateException("编制链成环（UnitState 构造期本应拒绝）: " + unit.id());
      }
      Optional<HexCoord> here = unit.position().valueAt(at);
      if (here.isPresent()) {
        return here;
      }
      // 查无此父（手工拼的状态）⇒ 链到此为止
      unit = unit.parent().valueAt(at).map(units::get).orElse(null);
    }
    return Optional.empty();
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
