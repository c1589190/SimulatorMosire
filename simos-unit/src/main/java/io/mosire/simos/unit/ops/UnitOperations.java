package io.mosire.simos.unit.ops;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 编制树操作面（M3 spec §4.6，用户裁定 U5 的**全套 8 项**）：创建 / 改编 / 改名 / 人数·装备变更 / 位置设置 / 下达路线 / 取消路线 / 解散。
 *
 * <p>★ **每个操作都是纯函数**（产出的都是新 {@code UnitState}）。变更集**唯一**的生产路径是 {@code UnitChangeSet.between(base,
 * target)}——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ 名单外的编辑（速度、机动性、装备之外的自定义字段）**不在操作面**：需要时走 `between`，即"改字段"永远是变更集的语义，不是操作面的语义（spec §4.6 第 7 条）。
 *
 * <p>★ **成环不在这里重复实现**：{@code reparent} 只校验新父存在，环由 {@link UnitState} 构造期拒绝。
 */
public final class UnitOperations {

  private UnitOperations() {}

  /** 创建：同 id 已在 ⇒ 抛；`parent` 值（若 present）必须在 `units` 里。 */
  public static UnitState create(UnitState state, Unit unit) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(unit, "unit");
    if (state.units().containsKey(unit.id())) {
      throw new IllegalArgumentException("单位 id 已存在: " + unit.id());
    }
    for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
      segment.value().ifPresent(parent -> requireExists(state, parent));
    }
    return withUnit(state, unit);
  }

  /** 改编：追加一条 `parent` 段（`from = at`）。同刻已有段 ⇒ 由严格升序校验抛。 */
  public static UnitState reparent(
      UnitState state, UnitId id, Optional<UnitId> newParent, SimosTimestamp at) {
    Objects.requireNonNull(newParent, "newParent");
    Unit unit = require(state, id);
    newParent.ifPresent(parent -> requireExists(state, parent));
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            append(unit.parent(), at, newParent),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  public static UnitState rename(UnitState state, UnitId id, String name) {
    Unit unit = require(state, id);
    return withUnit(
        state,
        copy(
            unit,
            name,
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  public static UnitState setStrength(
      UnitState state, UnitId id, int member, Map<String, Integer> equipment) {
    Unit unit = require(state, id);
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            member,
            equipment,
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  /** 位置设置：追加一条 `position` 段；**顺带清空在途路线**（改了位置，旧路线不再有意义）。 */
  public static UnitState placeAt(
      UnitState state, UnitId id, Optional<HexCoord> hex, SimosTimestamp at) {
    Objects.requireNonNull(hex, "hex");
    Unit unit = require(state, id);
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            append(unit.position(), at, hex),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.empty()));
  }

  /** 下达路线：路线起点必须等于该单位在 `at` 的 {@code effectivePosition}（无位置 ⇒ 抛）。 */
  public static UnitState planRoute(UnitState state, UnitId id, Route route, SimosTimestamp at) {
    Objects.requireNonNull(route, "route");
    Unit unit = require(state, id);
    HexCoord start =
        state
            .effectivePosition(id, at)
            .orElseThrow(
                () -> new IllegalArgumentException("单位 " + id + " 在 " + at + " 没有可确定的位置，无法下达路线"));
    HexCoord routeStart = route.waypoints().get(0);
    if (!start.equals(routeStart)) {
      throw new IllegalArgumentException("路线起点 " + routeStart + " 不是单位在 " + at + " 的位置 " + start);
    }
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.of(new Movement(route, at, unit.speed(), unit.mobilityPerMille()))));
  }

  public static UnitState cancelRoute(UnitState state, UnitId id) {
    Unit unit = require(state, id);
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.empty()));
  }

  /** 解散：**在 `at` 时刻有下属 ⇒ 抛**（判据 = 遍历所有单位在该时刻的 `parent` 值是否指向它）。 */
  public static UnitState disband(UnitState state, UnitId id, SimosTimestamp at) {
    require(state, id); // 存在性校验（查无此人 ⇒ 抛）；unit 本体在解散时无需再取
    for (Unit other : state.units().values()) {
      if (other.id().equals(id)) {
        continue;
      }
      if (other.parent().valueAt(at).filter(id::equals).isPresent()) {
        throw new IllegalArgumentException(
            "单位 " + id + " 在 " + at + " 仍有下属 " + other.id() + "：先改编、再解散");
      }
    }
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    return new UnitState(next);
  }

  // ── 私有助手 ────────────────────────────────────────────────────

  private static Unit require(UnitState state, UnitId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    Unit unit = state.units().get(id);
    if (unit == null) {
      throw new IllegalArgumentException("单位不存在: " + id);
    }
    return unit;
  }

  private static void requireExists(UnitState state, UnitId id) {
    if (!state.units().containsKey(id)) {
      throw new IllegalArgumentException("父单位不存在: " + id);
    }
  }

  private static UnitState withUnit(UnitState state, Unit unit) {
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）
    return new UnitState(next);
  }

  private static <T> SegmentedSeries<T> append(
      SegmentedSeries<T> series, SimosTimestamp at, T value) {
    List<Segment<T>> segments = new ArrayList<>(series.segments());
    segments.add(new Segment<>(at, value));
    return new SegmentedSeries<>(segments, series.events(), series.addition());
  }

  /**
   * ★ **canonical 拷贝点**：9 个可变字段由调用方给，T1 的四个新字段（{@code status}/{@code attached}/{@code
   * offset}/{@code rejoinTarget}）一律**原样带过**——不用兼容构造器（那会把新字段重置成默认值，正是 R1 的残留风险）。
   */
  private static Unit copy(
      Unit unit,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    return new Unit(
        unit.id(),
        name,
        parent,
        position,
        member,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget());
  }
}
