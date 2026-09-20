package io.mosire.simos.unit.ops;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 编制树操作面（M3 spec §4.6，用户裁定 U5 的**全套 8 项**）：创建 / 改编 / 改名 / 人数·装备变更 / 位置设置 / 下达路线 / 取消路线 / 解散； **加上
 * Unit 扩容 T3 的编制命令 A 三项**（attach 级联 / detach 只节点 / SetFormationOffset，spec §一.3）。
 *
 * <p>★ **每个操作都是纯函数**（产出的都是新 {@code UnitState}）。变更集**唯一**的生产路径是 {@code UnitChangeSet.between(base,
 * target)}——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ 名单外的编辑（速度、机动性、装备之外的自定义字段）**不在操作面**：需要时走 `between`，即"改字段"永远是变更集的语义，不是操作面的语义（spec §4.6 第 7 条）。
 *
 * <p>★ **reparent 的成环不在这里重复实现**：{@code reparent} 只校验新父存在，环由 {@link UnitState} 构造期拒绝。 **唯一的例外是
 * {@link #attachSubtree}**：P3 要求 attach 成环时给可读理由，故它在 op 内**先显式拒**（不依赖构造期的兜底消息）。
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
            Optional.of(new Movement(route, at, unit.effectiveSpeed(), unit.mobilityPerMille()))));
  }

  /** 改三态（T2 / spec §三.2）：status 是普通字段，只改它；历史由 revision 承载。 */
  public static UnitState setStatus(UnitState state, UnitId id, UnitStatus status) {
    Objects.requireNonNull(status, "status");
    Unit unit = require(state, id);
    return withUnit(state, withStatus(unit, status));
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

  // ── 编制命令 A（T3 / spec §一.3 / P2 / P3） ──────────────────────

  /**
   * attach（P3：**级联**）：把 `id` 挂到 `parent` 下，并把 `id` **及其全部后代**的 `attached` 追加 `true` 段； 只有 `id`
   * 换父，后代的 `parent` 不动（子树整体迁移是另一条命令）。
   *
   * <p>★ **成环在 op 内先显式拒**（判据 = `parent` 落在 `id` 的子树内，含 `id` 自身）：{@link UnitState}
   * 构造期也会拒，但那里的理由是"编制树…成环"；命令边界要给出**可读的原因**（plan §三 T3 第 1 步、spec §一.5 表）。
   *
   * <p>★ 两处**有意不拒**（裁定见 T3 台账）：`parent` 已是 `id` 当前的父不拒（重挂同一父正是 P9 的"合体 = 重新 attach"，
   * 且级联对子树仍有效）；`attached` 已是 `true` 的节点也不拒（本操作面不判"无变化命令"）。
   */
  public static UnitState attachSubtree(
      UnitState state, UnitId id, UnitId parent, SimosTimestamp at) {
    Objects.requireNonNull(parent, "parent");
    require(state, id); // 存在性校验
    requireExists(state, parent);
    List<UnitId> subtree = subtreeOf(state, id, at);
    if (subtree.contains(parent)) {
      throw new IllegalArgumentException("父单位 " + parent + " 落在 " + id + " 的子树内（含自身）：会成环");
    }
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    for (UnitId member : List.of(id)) {
      Unit current = state.units().get(member);
      // 只有根换父：后代的 parent 原样带过
      SegmentedSeries<Optional<UnitId>> parents =
          member.equals(id) ? append(current.parent(), at, Optional.of(parent)) : current.parent();
      next.put(
          member,
          copyFormation(current, parents, append(current.attached(), at, true), current.offset()));
    }
    return new UnitState(next);
  }

  /**
   * detach（P3：**只节点**）：**只**给 `id` 追加 `attached=false` 段——子节点**不动**（与 attach 刻意不对称； 子树整体的分离是
   * SplitFormation，T4）。`id` 在 `at` 已是根 ⇒ 拒：detached 的语义是"不再跟随这个父"， 没有父就没有可脱离的编队，那是坏命令（spec §一.5 表）。
   */
  public static UnitState detachUnit(UnitState state, UnitId id, SimosTimestamp at) {
    Unit unit = require(state, id);
    if (unit.parent().valueAt(at).isEmpty()) {
      throw new IllegalArgumentException("单位 " + id + " 在 " + at + " 已是根单位：没有可脱离的父");
    }
    return withUnit(
        state,
        copyFormation(unit, unit.parent(), append(unit.attached(), at, false), unit.offset()));
  }

  /**
   * 相对偏移（spec §一.3 / P2）：追加一条 `offset` 段（`Optional.empty()` = 清除偏移）。
   *
   * <p>★ **不强制落在地图内**（P2）：它是"相对父的站位"，父位在图界、子偏移越界是合法组合；形状合法性由 {@link RelativeOffset} 与 payload
   * 层保证，本操作**不看地图**（`simos-unit` 的地图只经 `effectivePosition` 的语义参与）。
   */
  public static UnitState setOffset(
      UnitState state, UnitId id, Optional<RelativeOffset> offset, SimosTimestamp at) {
    Objects.requireNonNull(offset, "offset");
    Unit unit = require(state, id);
    return withUnit(
        state,
        copyFormation(unit, unit.parent(), unit.attached(), append(unit.offset(), at, offset)));
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
   * `id` 及**其全部后代**（键序确定：先 `id`，再按 `units` 的键序遍历）。父图取 `at` 时刻的值，与 {@code
   * UnitState.requireNoCycleAtKeyTimes} 同一口径（父图只在段边界变化，故"在 `at` 处查一遍"即覆盖全部段）。
   */
  private static List<UnitId> subtreeOf(UnitState state, UnitId root, SimosTimestamp at) {
    Set<UnitId> subtree = new LinkedHashSet<>();
    subtree.add(root);
    for (Unit unit : state.units().values()) {
      UnitId cursor = unit.id();
      Set<UnitId> walked = new LinkedHashSet<>();
      while (walked.add(cursor)) {
        if (subtree.contains(cursor)) {
          subtree.add(unit.id());
          break;
        }
        Optional<UnitId> parent = parentAt(state, cursor, at);
        if (parent.isEmpty()) {
          break;
        }
        cursor = parent.get();
      }
    }
    return new ArrayList<>(subtree);
  }

  /** `at` 时刻的父（查无此单位或父链指向不存在的 id ⇒ 空：手工拼装的状态可以两者都绕过构造期校验）。 */
  private static Optional<UnitId> parentAt(UnitState state, UnitId id, SimosTimestamp at) {
    Unit unit = state.units().get(id);
    return unit == null ? Optional.empty() : unit.parent().valueAt(at);
  }

  /**
   * ★ **canonical 拷贝点**：9 个可变字段由调用方给，T1 的四个新字段（{@code status}/{@code attached}/{@code
   * offset}/{@code rejoinTarget}）一律**原样带过**——不用兼容构造器（那会把新字段重置成默认值，正是 R1 的残留风险）。 只动编队三件套（{@code
   * parent}/{@code attached}/{@code offset}）的操作用同族的 {@link #copyFormation}。
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

  /**
   * ★ **T3 的 canonical 拷贝点**：在 9 参 {@link #copy} 之上**显式**给 `attached`/`offset` 两个分量（`status`/
   * `rejoinTarget` 仍原样带过）。三个形参类型两两不同 ⇒ 传错顺序是**编译错误**，不是静默错位；T3 的三个操作只动
   * `parent`/`attached`/`offset`，故不走全 13 参。
   */
  private static Unit copyFormation(
      Unit unit,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset) {
    return new Unit(
        unit.id(),
        unit.name(),
        parent,
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        attached,
        offset,
        unit.rejoinTarget());
  }

  /** 只换 status、其余 12 个组件（含另外三个新字段）原样带过。 */
  private static Unit withStatus(Unit unit, UnitStatus status) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        status,
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget());
  }
}
