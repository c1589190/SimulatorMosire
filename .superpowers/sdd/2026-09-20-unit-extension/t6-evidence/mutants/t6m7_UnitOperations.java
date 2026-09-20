package io.mosire.simos.unit.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.PathFinder;
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
 * Unit 扩容 T3 的编制命令 A 三项**（attach 级联 / detach 只节点 / SetFormationOffset，spec §一.3）、**T4 的编制命令 B 三项**
 * （reparentSubtree 整树迁移 / splitFormation / mergeFormation），以及 **T5 的命令链两项**（{@code createChain} /
 * {@code updateChain}， spec §一.2 / §五.2 / P11）。
 *
 * <p>★ **每个操作都是纯函数**（产出的都是新 {@code UnitState}）。变更集**唯一**的生产路径是 {@code UnitChangeSet.between(base,
 * target)}——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ **改 `units` 一律走 {@code state.withUnits(next)}**（T5-U2）：{@code UnitState(Map<UnitId, Unit>)}
 * 那个 1 参兼容构造器 会把 {@code commandChains} **静默清空**，本类里凡是重建状态的写路径都不得再用它（链是无时刻的具名集合，任何编辑都不该碰它）。
 *
 * <p>★ 名单外的编辑（速度、机动性、装备之外的自定义字段）**不在操作面**：需要时走 `between`，即"改字段"永远是变更集的语义，不是操作面的语义（spec §4.6 第 7 条）。
 *
 * <p>★ **reparent 的成环不在这里重复实现**：{@code reparent} 只校验新父存在，环由 {@link UnitState} 构造期拒绝。 **唯一的例外是
 * {@link #attachSubtree}**：P3 要求 attach 成环时给可读理由，故它在 op 内**先显式拒**（不依赖构造期的兜底消息）；T4 的 {@link
 * #reparentSubtree} 同制。
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

  /**
   * 稀疏路线（T6 / spec §二.2 / P10）：`waypoints` **允许非相邻**，逐段用 A\* 展开成逐格 `path` 后复用 {@link
   * #planRoute}（起点校验、{@code Movement} 的装载与既有命令**同一条路**）。
   *
   * <p>★ **任一相邻段不可达 ⇒ 抛**（P12）：展开的失败由 {@link #expandSparsePath} 抛出，本方法不做"跳过该段"的处理。
   */
  public static UnitState planSparseRoute(
      UnitState state,
      UnitId id,
      GameMap map,
      List<HexCoord> waypoints,
      MovementCost cost,
      SimosTimestamp at) {
    Unit unit = require(state, id);
    Route route = new Route(waypoints, expandSparsePath(map, unit, waypoints, cost));
    return planRoute(state, id, route, at);
  }

  /**
   * 稀疏路线的**纯展开**（T6）：把 `waypoints` 的每一相邻对交给 {@link PathFinder#findPath} 求段，段首尾相接成逐格
   * `path`（第二段起去掉与上一段重复的连接点）。
   *
   * <p>★ **段不可达 ⇒ 抛**（P12）：{@link PathFinder#findPath} 的空值在这里折成 {@link IllegalArgumentException}
   * （调用方 {@code PlanSparseRouteHandler} 再折成命令拒绝），**绝不**静默截断或跳段。
   *
   * <p>★ **跨段重复格不由本方法兜底**：A\* 单段产物是简单路径，但两段拼接后可能出现重复格，此时 {@link Route} 的构造期不变量会抛（裁定 R4），消息里带"重复"。
   */
  public static List<HexCoord> expandSparsePath(
      GameMap map, Unit unit, List<HexCoord> waypoints, MovementCost cost) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(waypoints, "waypoints");
    Objects.requireNonNull(cost, "cost");
    List<HexCoord> path = new ArrayList<>();
    for (int i = 0; i + 1 < waypoints.size(); i++) {
      HexCoord from = waypoints.get(i);
      HexCoord to = waypoints.get(i + 1);
      List<HexCoord> segment =
          PathFinder.findPath(map, from, to, unit, cost)
              .orElseThrow(() -> new IllegalArgumentException("稀疏路线的段不可达: " + from + " → " + to));
      path.addAll(i == 0 ? segment : segment.subList(1, segment.size()));
    }
    return List.copyOf(path);
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

  /**
   * 解散：**在 `at` 时刻有下属 ⇒ 抛**（判据 = 遍历所有单位在该时刻的 `parent` 值是否指向它）；**还在任何命令链里 ⇒ 抛**（T5， spec §一.2 不变量
   * 2「链引用完整性」）。
   *
   * <p>★ 链的**两个方向都拒**（commander 与 member）：链引用的是 id，被解散的 id 若还挂在链上，状态立刻违反"链的 commander/members 都在
   * units 里"。这里**先显式拒**（而不是让 {@link UnitState} 构造期丢一句"链 X 的成员不在 units"）—— 命令边界要的是可读的原因 + 可执行的下一步，与
   * {@link #attachSubtree} 的成环显式拒同一口径。
   */
  public static UnitState disband(UnitState state, UnitId id, SimosTimestamp at) {
    require(state, id); // 存在性校验（查无此人 ⇒ 抛）；unit 本体在解散时无需再取
    requireNotInAnyChain(state, id);
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
    return state.withUnits(next);
  }

  /** 链引用的可读理由（T5 / spec §一.2 不变量 2）：`id` 是任何一条链的 commander 或 member ⇒ 拒。 */
  private static void requireNotInAnyChain(UnitState state, UnitId id) {
    for (CommandChain chain : state.commandChains().values()) {
      if (chain.commander().equals(id)) {
        throw new IllegalArgumentException(
            "单位 " + id + " 仍是链 " + chain.id() + " 的 commander：先改链、再解散");
      }
      if (chain.members().contains(id)) {
        throw new IllegalArgumentException("单位 " + id + " 仍是链 " + chain.id() + " 的成员：先改链、再解散");
      }
    }
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
    for (UnitId member : subtree) {
      Unit current = state.units().get(member);
      // 只有根换父：后代的 parent 原样带过
      SegmentedSeries<Optional<UnitId>> parents =
          member.equals(id) ? append(current.parent(), at, Optional.of(parent)) : current.parent();
      next.put(
          member,
          copyFormation(current, parents, append(current.attached(), at, true), current.offset()));
    }
    return state.withUnits(next);
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

  // ── 编制命令 B（T4 / spec §一.3 / §一.4 / P4 / P9） ──────────────

  /**
   * 子树**整体**迁移（T4 / spec §一.3 / §一.5 表 / P4）：`rootId` 连**同其全部后代**在同一刻重新挂载——`rootId` 换到 `newParent`
   * 下，每个后代都在同一 `at` 追加一段，值仍是它**本来的父**。
   *
   * <p>★ **为什么后代也要追加段**（T4 就地裁定，判据与取代说明见 {@code t4-evidence/t4-report.md}）：spec §一.3 的机制列写的是"给
   * `rootId` **及其全部后代**在同 `at` 追加 `parent` 段"，§八 #6 与 §一.5 表两处判据都写"迁移后**每个后代**的父都更新 | 只改 root ⇒
   * 后代父不变 ⇒ 红"。⇒ 整棵树在同一刻被重新挂载，后代的值不变但**段必须落**。计划 §三 T4 第 1 步括注的"（后代父不变——只有 root
   * 换父）"因此读作**值层面**的反扁平化提示（没有后代改挂到 `newParent`），与本节实现一致；把"后代不动"读成实现语义会让该计划自己在同一节列的 m1
   * 变异体变成**零差异**（变异体将等于参照实现，"只改 root 会红"这条判据同时失去意义）。
   *
   * <p>★ `newParent` 缺省/`null` ⇒ **提升为根**（P4：孤儿的处置是提升为根，绝不留下悬空的 `parentId`）。这是本操作面**唯一**
   * 的"降为根"路径（{@link #attachSubtree} 的 `parent` 必填）。
   *
   * <p>★ `attached` **一律不动**：本操作换的是父，不是归属开关（attach/detach 才是）。⇒ `rootId` 若本就 `attached=false`，
   * 迁移后它仍不跟随新父，这是调用方要给的另一条命令，不在这里替他决定。
   *
   * <p>★ 成环在 op 内**先显式拒**（判据 = `newParent` 落在 `rootId` 的子树内，含自身），理由与 {@link #attachSubtree} 同源：
   * {@code UnitState} 构造期也会拒成环，但那里的消息不含"子树"，命令边界要的是**可读的原因**。
   */
  public static UnitState reparentSubtree(
      UnitState state, UnitId rootId, Optional<UnitId> newParent, SimosTimestamp at) {
    Objects.requireNonNull(newParent, "newParent");
    require(state, rootId); // 存在性校验
    newParent.ifPresent(parent -> requireExists(state, parent));
    List<UnitId> subtree = subtreeOf(state, rootId, at);
    if (newParent.isPresent() && subtree.contains(newParent.get())) {
      throw new IllegalArgumentException(
          "新父 " + newParent.get() + " 落在 " + rootId + " 的子树内（含自身）：会成环");
    }
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    for (UnitId member : subtree) {
      Unit current = state.units().get(member);
      // 只有 root 换父；后代在同一刻重新挂载，值仍是它本来的父（整树一起落到 at）
      Optional<UnitId> parent =
          member.equals(rootId) ? newParent : requiredParentAt(state, member, at);
      next.put(
          member,
          copyFormation(
              current, append(current.parent(), at, parent), current.attached(), current.offset()));
    }
    return state.withUnits(next);
  }

  /**
   * 拆分（T4 / spec §一.3 / §一.5 表）：`subUnitIds` 里的每个单位都必须在 `rootId` 在 `at` 的**子树内**，然后逐个 {@link
   * #detachUnit}——**只节点**（P3 的不对称：拆下来的节点**自己的后代不动**，与 detach 同一口径）。
   *
   * <p>★ 每个目标两查（spec §一.5 表把"不存在"与"不在 root 子树"列为两条独立的拒绝理由）：不存在 ⇒ `单位不存在`；存在但不在子树内 ⇒
   * `不在…子树内`。`subUnitIds` 为空 ⇒ 拒——指不到任何目标的拆分是坏命令。
   *
   * <p>★ 重复项按**首现序去重**：同一目标在同一 `at` 只 detach 一次（第二次追加会撞 {@code SegmentedSeries}
   * 的严格升序校验，理由会变得莫名，而调用方想说的其实是"拆这一个"）。
   *
   * <p>★ `rootId` 自身出现在 `subUnitIds` 里**合法**（它在自己的子树内）：能不能拆看它在 `at` 有没有父——由 {@link #detachUnit}
   * 的既有语义接管（已是根 ⇒ 拒）。这里**不新增**守卫。
   */
  public static UnitState splitFormation(
      UnitState state, UnitId rootId, List<UnitId> subUnitIds, SimosTimestamp at) {
    Objects.requireNonNull(subUnitIds, "subUnitIds");
    require(state, rootId); // 存在性校验
    if (subUnitIds.isEmpty()) {
      throw new IllegalArgumentException("subUnitIds 不得为空：拆分命令至少要指名一个目标");
    }
    List<UnitId> subtree = subtreeOf(state, rootId, at);
    Set<UnitId> targets = new LinkedHashSet<>();
    for (UnitId id : subUnitIds) {
      require(state, id); // 存在性校验（缺 id 时先报"不存在"，而不是"不在子树内"）
      if (!subtree.contains(id)) {
        throw new IllegalArgumentException(
            "单位 " + id + " 不在 " + rootId + " 在 " + at + " 的子树内：不能拆分");
      }
      targets.add(id);
    }
    UnitState next = state;
    for (UnitId id : targets) {
      next = detachUnit(next, id, at);
    }
    return next;
  }

  /**
   * 合体（T4 / spec §一.3 / §一.4 / P9）：`childId` 在 `at` 与 `parentId` **同格**、且 `childId` **正在移动**
   * 时，才把它重新 attach 回去。
   *
   * <p>★ **两个前置条件各自独立**（spec §一.4："与「同格」是**两个独立的拒绝条件**"）：同格但不在 MOVING ⇒ 拒；在 MOVING 但不同格 ⇒
   * 拒。同格判据的两半是"两侧都能定出位置"与"位置相等"——**任一不可确定同样拒**（spec §一.4 的措辞是"不同格**或任一不可确定**"）， 两条理由都带"同格"字样。
   *
   * <p>★ 通过后**复用** {@link #attachSubtree}（spec §一.5 表把本命令的操作记作 **attach**；P3 的 attach **级联**； P9"合体
   * = 重新 attach（不销毁节点）"）：`childId` 换父 + 它**全部后代**级联 `attached=true`。级联是刻意的——合体带回来的是一支编队， 不是一个光杆节点。
   *
   * <p>★ **不判"已是父"**（与 {@link #attachSubtree} 的同一条裁定，spec §一.5 表的 T3 回填注）：{@link #detachUnit} 只翻
   * `attached`、**不碰 `parent`**，故拆→合的往返里 `childId` 的父本来就是 `parentId`；判"已是父"会把 spec §八 E2
   * 的核心场景整条堵死。本操作面**不判"无变化的命令"**，这一条也不在操作面里开例外。
   */
  public static UnitState mergeFormation(
      UnitState state, UnitId childId, UnitId parentId, SimosTimestamp at) {
    Objects.requireNonNull(parentId, "parentId");
    Unit child = require(state, childId); // 存在性校验
    requireExists(state, parentId);
    Optional<HexCoord> childHex = state.effectivePosition(childId, at);
    Optional<HexCoord> parentHex = state.effectivePosition(parentId, at);
    if (childHex.isEmpty() || parentHex.isEmpty()) {
      throw new IllegalArgumentException(
          "单位 " + childId + " 或 " + parentId + " 在 " + at + " 没有可确定的位置：只有同格才能合体");
    }
    if (!childHex.get().equals(parentHex.get())) {
      throw new IllegalArgumentException(
          "单位 "
              + childId
              + " 在 "
              + at
              + " 位于 "
              + childHex.get()
              + "，与 "
              + parentId
              + " 的 "
              + parentHex.get()
              + " 不同格：只有同格才能合体");
    }
    if (child.status() != UnitStatus.MOVING) {
      throw new IllegalArgumentException(
          "单位 " + childId + " 的状态是 " + child.status() + " 而不是 MOVING：只有移动中的单位才能合体");
    }
    return attachSubtree(state, childId, parentId, at);
  }

  // ── 命令链（T5 / spec §一.2 / §五.2 / §五.3 / P11） ──────────────

  /**
   * 建链（T5 / spec §五.2 的 `unit.CreateCommandChain`）：链 id 已在 ⇒ 拒；commander 与全部 members 必须在同快照的
   * `units` 里（不存在的 id 指的是**没有这个人**，链引用完整性当场就破）。
   *
   * <p>★ **多属一律放行**（spec §一.2 / P11）：同一个单位同时出现在两条链里是**正常态**（一条链里当然只有一个位置，靠 {@code LinkedHashSet}
   * 去重），本操作面**不判**"这单位已经在别的链里"。
   *
   * <p>★ 本操作**无 `at`**：链没有时刻分量（它不落任何 {@code SegmentedSeries}），与 {@link #rename}/{@link #setStatus}
   * 一族 同形；带 `at` 的那批都是"往某个段序列追加一段"的操作。计划 §三 T5 第 1 步写的 `createChain(state, chain, at)` 因此**就地
   * 校正**为两参（执行期取代说明，同族前例见 T3/T4 台账）。
   *
   * <p>★ **`commander ∈ members` 不在本操作面重复实现**：{@code CommandChain} 是 record、构造期已强制它（T1），本操作收到的
   * **只能**是合法值对象 ⇒ 这条检查在 op 层**结构性不可达**。权威检查点是 {@code CommandChain} 构造器，命令边界的可读理由由 它给出（`commander
   * 必须是 members 之一: u-x`），payload 层的拒绝用例钉在 {@code UnitCommandHandlersTest}。写一条不可达
   * 的守卫等于装饰（没有任何变异体能被它杀掉）。
   */
  public static UnitState createChain(UnitState state, CommandChain chain) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(chain, "chain");
    if (state.commandChains().containsKey(chain.id())) {
      throw new IllegalArgumentException("链 id 已存在: " + chain.id());
    }
    requireChainMembersResolve(state, chain.id(), chain.commander(), chain.members());
    Map<CommandChainId, CommandChain> next = new LinkedHashMap<>(state.commandChains());
    next.put(chain.id(), chain);
    return state.withCommandChains(next);
  }

  /**
   * 改链（T5 / spec §五.2 的 `unit.UpdateCommandChain`）：三个 Optional **各自独立**，未给的字段**原样不动**（不是清空）。
   *
   * <p>★ 三条判据（spec §五.3 第 2/3 条 + 本操作面的既有口径）：
   *
   * <ul>
   *   <li>链不存在 ⇒ 拒（改一条不存在的链是坏命令，不是"顺手建一条"）
   *   <li>**只给 `commander`** ⇒ 它必须落在**既有** `members` 里；**给了 `members`** ⇒ 生效的 commander（给没给都由它定）
   *       必须落在**新** `members` 里。两条都由同一句"commander 必须在生效 members 里"落地
   *   <li>**三个都缺 ⇒ 允许**（本操作面不判"无变化的命令"——T3 在 {@link #attachSubtree} 立的裁定，这里不开例外）
   * </ul>
   *
   * <p>★ `members` 给成空表时由"commander 必须在 members 里"拒（消息自带下一步：把它加进 members）；<b>不</b>另设一条
   * "不得为空"的守卫——{@code CommandChain} 构造期那条已被这一句**先**拦住，重复的守卫只会多一处无人能杀的装饰。
   */
  public static UnitState updateChain(
      UnitState state,
      CommandChainId id,
      Optional<String> name,
      Optional<UnitId> commander,
      Optional<List<UnitId>> members) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(commander, "commander");
    Objects.requireNonNull(members, "members");
    CommandChain existing = state.commandChains().get(id);
    if (existing == null) {
      throw new IllegalArgumentException("链不存在: " + id);
    }
    Set<UnitId> nextMembers = new LinkedHashSet<>();
    if (members.isPresent()) {
      for (UnitId member : members.get()) {
        if (member == null) {
          throw new IllegalArgumentException("链 " + id + " 的 members 不得含 null");
        }
        nextMembers.add(member);
      }
    } else {
      nextMembers.addAll(existing.members()); // 未给 ⇒ 不动（不是清空）
    }
    UnitId nextCommander = commander.orElse(existing.commander());
    requireChainMembersResolve(state, id, nextCommander, nextMembers);
    if (!nextMembers.contains(nextCommander)) {
      throw new IllegalArgumentException(
          "链 " + id + " 的 commander " + nextCommander + " 不在 members 内：先把它加进 members 再改链");
    }
    CommandChain updated =
        new CommandChain(id, name.orElse(existing.name()), nextCommander, nextMembers);
    Map<CommandChainId, CommandChain> next = new LinkedHashMap<>(state.commandChains());
    next.put(id, updated); // 覆盖时保持原键位（LinkedHashMap 的既有键不改变位置）
    return state.withCommandChains(next);
  }

  /**
   * 链引用的**命令边界**可读理由（spec §一.2 不变量 2）：commander 与全部 members 必须在同快照的 `units` 里。
   *
   * <p>★ {@link UnitState} 构造期也拒这条（`链 X 的 commander/members 不在 units`），但那里是**数据故障**口径；这里先显式拒，
   * 理由是给命令边界读的（`…不存在: u-ghost`），也是 T5 的判据之一。两条消息措辞**刻意不同**——若相同，把 op 层这条删掉后
   * 构造期兜底会说同样的话，"是哪一层拒的"就判不出来（T3/T4 的 m9 正是栽在这上面）。
   */
  private static void requireChainMembersResolve(
      UnitState state, CommandChainId chainId, UnitId commander, Iterable<UnitId> members) {
    if (!state.units().containsKey(commander)) {
      throw new IllegalArgumentException("链 " + chainId + " 的 commander 不存在: " + commander);
    }
    for (UnitId member : members) {
      if (!state.units().containsKey(member)) {
        throw new IllegalArgumentException("链 " + chainId + " 的成员不存在: " + member);
      }
    }
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

  /**
   * 改单个单位的**唯一**入口（面最宽）：`rename` / `setStrength` / `placeAt` / `planRoute` / `setStatus` /
   * `cancelRoute` / `setOffset` / `create` / `reparent` / `detachUnit` 十个操作都经这里落盘。
   *
   * <p>★ **T5-U2**：`commandChains` 用 {@link UnitState#withUnits(Map)} 带过。此前这里用 1 参兼容构造器 （`new
   * UnitState(next)`）⇒ **十个操作的任何一次调用都会把全部链静默清空**；链是**无时刻的具名集合**，改某个单位的字段
   * 与它无关。既有用例之所以全绿，是因为"`Map.of()` 换 `Map.of()`"是恒等——没有一条用例在带链的状态上跑过本助手。
   */
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
   * 子树成员的父**必须有**（除根之外，子树成员按定义都是某个成员的子）：取不到就是状态被拼坏了 ⇒ 抛 {@link
   * IllegalStateException}（这是"不可能发生"的编程错误，不是可拒绝的坏命令，故**不**折成 `Rejected`）。
   */
  private static Optional<UnitId> requiredParentAt(UnitState state, UnitId id, SimosTimestamp at) {
    return Optional.of(
        parentAt(state, id, at)
            .orElseThrow(() -> new IllegalStateException("子树成员 " + id + " 在 " + at + " 没有父")));
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
