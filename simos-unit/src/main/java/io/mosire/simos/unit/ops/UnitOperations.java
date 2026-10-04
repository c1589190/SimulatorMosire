package io.mosire.simos.unit.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.MilitaryHouseholdDuty;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
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
 * {@code updateChain}， spec §一.2 / §五.2 / P11）、**T7 的回归路径两项**（{@code setRejoinTarget} / {@code
 * rejoinRoute}， spec §二.2 / P8）。
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
 *
 * <p>★ **GOV 编制编辑四件（阶段 10b-i，2026-10-01）**：{@link #setGovPolicy}/{@link #setGovSuperior}/{@link
 * #recruitStaff}/{@link #dismissStaff} 都只认 {@link GovFormation}，结果统一走 {@link #withModule} canonical
 * 拷贝（18 个组件一个不丢）。
 */
public final class UnitOperations {

  /** GOV 上级链环检测的最大层数（阶段 10b-i，2026-10-01）：超过即具名拒，防止深链/环拖爆。 */
  private static final int MAX_GOV_SUPERIOR_CHAIN = 64;

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

  /** 改编：落一条 `parent` 段（`from = at`）。**同刻已有段 ⇒ 覆盖**（后写者胜，见 {@link #setOrAppend}）。 */
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
            setOrAppend(unit.parent(), at, newParent),
            unit.position(),
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
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  /**
   * ★ <b>整表复写装备</b>（{@code unit.SetComposition} 的领域实现；原 {@code setStrength} 的 rename，D3a；S3b 起
   * manpower 退役）： 载荷给出的装备表**整体取代**旧表，不是增量合并。★ <b>未知 type 不是错误</b>——整表语义就是"给什么就是什么"；数值范围 （{@code
   * amount ≥ 0}、同表 type 不重复）由 {@link Unit} 构造期判，本方法不重复实现。
   *
   * <p>★ 纯函数：结果单位走 canonical 17 参拷贝，其余 16 个组件一个不丢。
   */
  public static UnitState setComposition(
      UnitState state, UnitId id, List<CompositionEntry> equipment) {
    Unit unit = require(state, id);
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            equipment,
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  /**
   * ★★ <b>整体替换一个 unit 容纳的家户列表</b>（{@code unit.SetUnitHouseholds} 的领域实现，S3a / 2026-10-09）：目标 unit
   * 必须存在；列表整体取代旧列表（保序）。
   *
   * <p>★ <b>拒因</b>：unit 不存在；元素为 null；同一 unit 内 household id 重复（{@link Unit} 构造期也会判）。跨单位不变量
   * （同一家户不得同时属于两个 unit、Unit id 不得与 household id 撞名）由 {@link UnitState} 构造期判——{@link #withUnit}
   * 重建状态时自然强制执行，本方法不另写一份。
   *
   * <p>★ 纯函数；结果走 {@link #withHouseholds} 的 canonical 17 参拷贝（其余组件一个不丢）。
   */
  public static UnitState setUnitHouseholds(
      UnitState state, UnitId id, List<HouseholdId> households) {
    Objects.requireNonNull(households, "households");
    Unit unit = require(state, id);
    List<HouseholdId> copy = new ArrayList<>(households.size());
    Set<HouseholdId> seen = new LinkedHashSet<>();
    for (HouseholdId household : households) {
      if (household == null) {
        throw new IllegalArgumentException("households 的元素不得为 null");
      }
      if (!seen.add(household)) {
        throw new IllegalArgumentException("households 不得有重复: " + household);
      }
      copy.add(household);
    }
    return withUnit(state, withHouseholds(unit, copy));
  }

  /**
   * ★ <b>有符号直改装备</b>（{@code unit.AdjustComposition} 的领域实现，D-009 补裁的 GM 调试原语；S3b 起 manpower 退役）：
   * 增量表**有序**，一条命令原子地把它们落在当前表上。
   *
   * <p>★ <b>符号语义</b>：
   *
   * <ul>
   *   <li><b>正增量</b>：type 已存在 ⇒ 加上去（long 溢出 ⇒ 具名拒）；不存在 ⇒ <b>新建</b>一条并追加在表尾；
   *   <li><b>负增量</b>：type 必须已存在（不存在 ⇒ 具名拒，不视作 0）且 {@code |Δ| ≤ 当前值}（越界 ⇒ 具名拒）；减到 0 的条目**保留** （值
   *       0，顺序不变）；
   *   <li><b>零增量</b>：合法 no-op（本操作面不判"无变化命令"，与既有 attach/updateChain 同口径）。
   * </ul>
   *
   * <p>★ <b>同表重复 type 一律拒</b>：一条 type 两条增量会让"先加后减"与"先减后加"产生不同结果，超出"一张表"的语义。
   *
   * <p>★ 纯函数；结果走 canonical 17 参拷贝（其余组件一个不丢）。变更集仍由 {@code UnitChangeSet.between} 派生，不做第二条增量通道。
   */
  public static UnitState adjustComposition(
      UnitState state, UnitId id, List<CompositionDelta> equipmentDeltas) {
    Objects.requireNonNull(equipmentDeltas, "equipmentDeltas");
    Unit unit = require(state, id);
    requireNoDuplicateDeltaTypes(equipmentDeltas, "equipment");
    List<CompositionEntry> equipment = applyAdjustDeltas(unit.equipment(), equipmentDeltas, "装备");
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            equipment,
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  /**
   * ★ **装备战损增量**（T8 / spec §四 表 / E4 / N3 / P14；D3a 双轨条目；S3b 起 manpower 退役）：{@code equipmentDeltas}
   * 是**≤ 0 的增量**，逐项**落在当前值上**——与 {@link #setComposition} 的"整表复写"是两种语义，绝不混用。
   *
   * <p>三条判据（都抛 {@link IllegalArgumentException}，由 handler 在命令边界折成拒绝）：
   *
   * <ol>
   *   <li><b>增量必须 ≤ 0</b>：正数 ⇒ 抛（战损只减员；"把 −30 当绝对值"的旧靶子，本操作不接这种解释）。
   *   <li><b>逐项上界 {@code |Δ| ≤ 当前值}</b>：人力与**每一件**装备各自独立判（P14 的"逐项"面）；越界 ⇒ 抛， 消息里带当前值与本条增量。判据写成
   *       {@code delta < -current}（**不取负号**：`-Long.MIN_VALUE` 会溢出成自身、把越界悄悄放过去）。
   *   <li><b>未提及的 type 不变；提及一个不存在的 type ⇒ 抛</b>（P14）：绝不"视作 0 忽略"（那是 m4 的靶子）。判定在增量符号之前 ⇒ 未知
   *       type**无论**带什么值都拒。
   * </ol>
   *
   * <p>★ 结果的不变量（每条 {@code amount ≥ 0}）由上面的逐项上界**先行保证**，{@code Unit} 构造期继续把守同一件事（两道
   * 不重复实现：上界判据给出可读的领域理由，构造期是最后一道）。
   *
   * <p>★ **绝对值落 revision**：本操作只产新 {@code UnitState}，命令层用 {@code UnitChangeSet.between(base, next)}
   * 取差分 ⇒ 落库的是**新绝对值**（不是增量），故回退到战损前那一 revision 读回的就是战前值（m5 的靶子）。
   *
   * <p>★ **T5-L4 通则**：重建状态一律走 {@link #withUnit}（内部 {@code state.withUnits(...)}）——`commandChains`
   * 必须原样带过，绝不写 `new UnitState(units)`。
   */
  public static UnitState applyCasualties(
      UnitState state, UnitId id, List<CompositionDelta> equipmentDeltas) {
    Objects.requireNonNull(equipmentDeltas, "equipmentDeltas");
    Unit unit = require(state, id);
    requireNoDuplicateDeltaTypes(equipmentDeltas, "equipment");
    List<CompositionEntry> equipment = applyCasualtyDeltas(unit.equipment(), equipmentDeltas, "装备");
    return withUnit(
        state,
        copy(
            unit,
            unit.name(),
            unit.parent(),
            unit.position(),
            equipment,
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement()));
  }

  /** 直改增量的逐项落表：正增量可新建 type（追加表尾），负增量要求存在且不越界；零增量 no-op。 */
  private static List<CompositionEntry> applyAdjustDeltas(
      List<CompositionEntry> current, List<CompositionDelta> deltas, String field) {
    List<CompositionEntry> next = new ArrayList<>(current);
    Map<String, Integer> indexByType = indexByType(next);
    for (CompositionDelta delta : deltas) {
      Integer at = indexByType.get(delta.type());
      if (delta.amount() > 0L) {
        if (at == null) {
          next.add(new CompositionEntry(delta.type(), delta.amount()));
          indexByType.put(delta.type(), next.size() - 1);
          continue;
        }
        long amount = next.get(at).amount();
        if (amount > Long.MAX_VALUE - delta.amount()) {
          throw new IllegalArgumentException(
              field + "增量溢出 long: " + delta.type() + "=" + amount + " + " + delta.amount());
        }
        next.set(at, new CompositionEntry(delta.type(), amount + delta.amount()));
      } else if (delta.amount() < 0L) {
        if (at == null) {
          // ★ 负增量指向一个不存在的 type 是错误（不视作 0 新建——那会让"损失"变成"负资产"，无意义）。
          throw new IllegalArgumentException("未知" + field + "类型: " + delta.type());
        }
        long amount = next.get(at).amount();
        if (delta.amount() < -amount) {
          throw new IllegalArgumentException(
              field + "减少超出当前值: " + delta.type() + "=" + amount + " + (" + delta.amount() + ")");
        }
        next.set(at, new CompositionEntry(delta.type(), amount + delta.amount()));
      }
      // amount == 0：合法 no-op（type 不存在也不新建——"无变化"不该凭空造条目）。
    }
    return next;
  }

  /** 战损增量的逐项落表：≤ 0、未知 type 拒、|Δ| ≤ 当前值；未提及的条目原样保留（含顺序）。 */
  private static List<CompositionEntry> applyCasualtyDeltas(
      List<CompositionEntry> current, List<CompositionDelta> deltas, String field) {
    List<CompositionEntry> next = new ArrayList<>(current);
    Map<String, Integer> indexByType = indexByType(next);
    for (CompositionDelta delta : deltas) {
      Integer at = indexByType.get(delta.type());
      if (at == null) {
        // ★ P14：未知 type**拒绝**，不视作 0（"没有这个 type"不是"这个 type 是 0"）；
        //   判定在增量符号之前 ⇒ 未知 type **无论**带什么值都拒。
        throw new IllegalArgumentException("未知" + field + "类型: " + delta.type());
      }
      if (delta.amount() > 0L) {
        throw new IllegalArgumentException(
            field + "增量必须 ≤ 0（战损只减员）: " + delta.type() + "=" + delta.amount());
      }
      long amount = next.get(at).amount();
      if (delta.amount() < -amount) {
        throw new IllegalArgumentException(
            field + "战损超出当前值: " + delta.type() + "=" + amount + " + (" + delta.amount() + ")");
      }
      next.set(at, new CompositionEntry(delta.type(), amount + delta.amount()));
    }
    return next;
  }

  /** 表的 type → 下标索引（{@code LinkedHashMap}，顺序只为可读；值域判据只看存在性）。 */
  private static Map<String, Integer> indexByType(List<CompositionEntry> entries) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < entries.size(); i++) {
      index.put(entries.get(i).type(), i);
    }
    return index;
  }

  /** 两张增量表各自不得有重复 type（先判完再动手，避免"先加后减"的中间态被当成规则）。 */
  private static void requireNoDuplicateDeltaTypes(List<CompositionDelta> deltas, String field) {
    Set<String> seen = new LinkedHashSet<>();
    for (CompositionDelta delta : deltas) {
      if (!seen.add(delta.type())) {
        throw new IllegalArgumentException(field + " 增量不得有重复 type: " + delta.type());
      }
    }
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
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.empty()));
  }

  /**
   * 下达路线：**只有编制顶层能下**（编制 v2），路线起点必须等于该单位在 `at` 的 {@code effectivePosition}（无位置 ⇒ 抛）。
   *
   * <p>★★ **v2 的两条改动**（2026-09-24）：
   *
   * <ol>
   *   <li><b>只许顶层</b>（{@link #requireTopOfFormation}）：与别人一同移动的编制成员**不能**自己走——它一动就会与整支脱节。
   *       用户原话：「如果选中一个有依附/一同移动的单位，且不是最父单位，那么提示应当控制顶层进行移动或拆分」。拒绝理由**点名顶层 id**
   *       并给出两条出路，模型据此才知道下一步该发什么命令。
   *   <li><b>速度取整支最慢</b>：装载的 {@code speedAtDeparture} = {@code state.formationSpeed(...)}（整支里
   *       {@code effectiveSpeed} 的最小值，含状态折算）——用户原话「这个单位有下挂单位总速度为下挂单位中最慢者速度」。 以前这里是 {@code
   *       unit.effectiveSpeed()}（只看自己）⇒ 一支里塞个慢兵种也不影响行程，那与"一同移动"的直觉相反。
   * </ol>
   *
   * <p>★ 起点校验保留：路线必须从**它现在所在的格**开始（这条不因 v2 而松）。
   */
  public static UnitState planRoute(UnitState state, UnitId id, Route route, SimosTimestamp at) {
    Objects.requireNonNull(route, "route");
    Unit unit = require(state, id);
    UnitId root = requireTopOfFormation(state, id, at);
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
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.of(
                new Movement(route, at, state.formationSpeed(root, at), unit.mobilityPerMille()))));
  }

  /**
   * **只许顶层移动**的闸门（编制 v2）：返回该单位的顶层 id；它若不是顶层 ⇒ 抛，理由**点名顶层**并给出两条出路。
   *
   * <p>★ 顶层判定见 {@code UnitState.formationRoot}：沿 {@code parent} 上溯到 `attached=false` 或**无父**为止。 ⇒
   * "有归属但独立"的单位（有 parent、`attached=false`）**是顶层**，它可以自己走（用户那句「即使这个单位有归属，也可以自动移动」）。
   */
  private static UnitId requireTopOfFormation(UnitState state, UnitId id, SimosTimestamp at) {
    UnitId root =
        state.formationRoot(id, at).orElseThrow(() -> new IllegalArgumentException("单位不存在: " + id));
    if (!root.equals(id)) {
      throw new IllegalArgumentException(
          "单位 "
              + id
              + " 是与其它单位一同移动的编制成员（这一支的顶层是 "
              + root
              + "）：只有顶层能下路线——要么对 "
              + root
              + " 下令（整支一起走），要么先用 unit.SplitFormation 或 unit.DetachUnit 把 "
              + id
              + " 拆成独立单位再下路线");
    }
    return root;
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
   * <p>★★ **跨段重复格是正当的**（2026-09-24 新裁定：「巡逻环线肯定是要支持的」）：两段拼接后回到已走过的格 （`H11→H13→H11`）会展开成"去程 + 回程"的
   * `path`，{@link Route} **接受**它（旧口径 R4 在此拒，已作废）。
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

  // ── 状态描述地址（阶段 D1，2026-10-02 / D-012） ──────────────

  /**
   * ★ <b>当前回合状态 ↔ 状态描述地址的 upsert / 删除</b>（{@code unit.SetStateDescription} 的领域实现，D-012）。
   *
   * <p>语义三条：
   *
   * <ol>
   *   <li><b>upsert</b>：{@code address} present 且非空白 ⇒ 写入/覆盖 {@code stateKey} 的链接（覆盖合法——"这个状态现在链到哪个
   *       地址"是存量语义）；
   *   <li><b>删除</b>：{@code address} 为空（缺省 / null / 空串）⇒ 删掉 {@code stateKey} 的链接；**本来就没有这条链接 ⇒ 具名拒**
   *       （不是静默 no-op：一条"清除"命令什么都没清，调用方应当知道）；
   *   <li>{@code stateKey} 空白 ⇒ 具名拒。地址文本的 canonical 校验在 {@link Unit} 构造期（本方法只把候选表交给它）——域模型是
   *       唯一真相，不在操作层再抄一遍语法。
   * </ol>
   *
   * <p>★ <b>只动这一条链接</b>：其余 16 个组件（含整张 {@code stateDescriptions} 的其它键、编制/管辖）经 {@link
   * #withStateDescriptions} 原样带过。
   *
   * <p>★ <b>不含时间语义</b>：谁在什么 tick 清链接、链接是否跨 tick 延续，本批不做（D-012 只要求"当前回合状态"的链接表）。
   */
  public static UnitState setStateDescription(
      UnitState state, UnitId id, String stateKey, Optional<String> address) {
    Objects.requireNonNull(address, "address");
    if (stateKey == null || stateKey.isBlank()) {
      throw new IllegalArgumentException("state 不得为空白");
    }
    Unit unit = require(state, id);
    Map<String, String> next = new LinkedHashMap<>(unit.stateDescriptions());
    if (address.isEmpty() || address.get().isBlank()) {
      if (!next.containsKey(stateKey)) {
        throw new IllegalArgumentException("状态 " + stateKey + " 本来就没有描述地址，无可清除");
      }
      next.remove(stateKey);
    } else {
      next.put(stateKey, address.get()); // 覆盖时既有键保持原位（LinkedHashMap 的既有键不改变位置）
    }
    return withUnit(state, withStateDescriptions(unit, next));
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
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.empty()));
  }

  // ── 辖区（阶段 5，2026-09-30） ──────────────────────────────

  /**
   * ★ **整体替换管辖区域集合 + upsert 政策字段**（{@code unit.SetJurisdiction} 的领域实现，计划 §2.2）。
   *
   * <p>语义四条：
   *
   * <ol>
   *   <li>{@code regions} 的每个 id 必须在当前 {@code map.regions()} 里存在——不存在 ⇒ 具名拒（**不静默丢**，坏的 regionId
   *       不会变成"少管一个区域"）。
   *   <li>区域集合**整体替换**：不在 {@code regions} 里的旧区域连同其税率一并移除；保留区域的旧税率**upsert 保留**； 新区域的税率从 0 起。
   *   <li>四个可选政策字段（三个 {@code levy*CapPerCommand} 与 {@code administrationPerMille}）**未给 ⇒ 保持原值**；
   *       单位原本没有 {@code jurisdiction} ⇒ 保持 0。
   *   <li>{@code regions} 为空数组合法 = **撤销全部管辖**：结果是一个 {@code jurisdiction} present、税率表为空的单位
   *       （政策字段照常按上面的规则更新）——"空 map = 无管辖"是 {@link Jurisdiction} 的既定表示，不把整个 Optional 丢掉。
   * </ol>
   *
   * <p>★★ <b>三个 {@code levy*CapPerCommand} 的语义（计划 §6.0-1）</b>：上限 = **一条**抽取命令的上限（0 =
   * 该类无额度、拒）；本方法只把这三个数字 upsert 进 {@link Jurisdiction}，<b>不建周期累计账本</b>（具名：本批没有"每周期已抽多少"
   * 的状态，逐周期累计留待测试阶段后按需再裁）。负值由 {@link Jurisdiction} 构造期拒。
   *
   * <p>★ **纯函数**：产新 {@code UnitState}，变更集仍由 {@code UnitChangeSet.between} 派生。
   */
  public static UnitState setJurisdiction(
      UnitState state,
      UnitId id,
      GameMap map,
      List<RegionId> regions,
      Optional<Long> levyGrainCapPerCommand,
      Optional<Long> levyMoneyCapPerCommand,
      Optional<Long> levyManpowerCapPerCommand,
      Optional<Integer> administrationPerMille) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(regions, "regions");
    Objects.requireNonNull(levyGrainCapPerCommand, "levyGrainCapPerCommand");
    Objects.requireNonNull(levyMoneyCapPerCommand, "levyMoneyCapPerCommand");
    Objects.requireNonNull(levyManpowerCapPerCommand, "levyManpowerCapPerCommand");
    Objects.requireNonNull(administrationPerMille, "administrationPerMille");
    Unit unit = require(state, id);
    Optional<Jurisdiction> current = unit.jurisdiction();
    Map<RegionId, Long> oldRates =
        current.map(Jurisdiction::taxRatePerMilleByRegion).orElseGet(Map::of);
    // ★ 先逐条验区域存在（具名拒），再动手——不静默丢任何 regionId。
    for (RegionId region : regions) {
      if (region == null) {
        throw new IllegalArgumentException("regions 的元素不得为 null");
      }
      if (!map.regions().containsKey(region)) {
        throw new IllegalArgumentException("区域不存在: " + region + "（当前地图 regions() 里没有它，无法纳入管辖）");
      }
    }
    Map<RegionId, Long> nextRates = new LinkedHashMap<>();
    for (RegionId region : regions) {
      nextRates.put(region, oldRates.getOrDefault(region, 0L));
    }
    long grainCap = current.map(Jurisdiction::levyGrainCapPerCommand).orElse(0L);
    long moneyCap = current.map(Jurisdiction::levyMoneyCapPerCommand).orElse(0L);
    long manpowerCap = current.map(Jurisdiction::levyManpowerCapPerCommand).orElse(0L);
    long adminPerMille = current.map(Jurisdiction::administrationPerMille).orElse(0L);
    Jurisdiction next =
        new Jurisdiction(
            nextRates,
            levyGrainCapPerCommand.orElse(grainCap),
            levyMoneyCapPerCommand.orElse(moneyCap),
            levyManpowerCapPerCommand.orElse(manpowerCap),
            administrationPerMille.map(Integer::longValue).orElse(adminPerMille));
    return withUnit(state, withJurisdiction(unit, Optional.of(next)));
  }

  /**
   * ★ **upsert 某管辖区域的长期税率**（{@code unit.SetTaxRate} 的领域实现，计划 §2.2）：只改 {@code
   * taxRatePerMilleByRegion} 里一个键的值，其余字段与 map 顺序原样带过。
   *
   * <p>两条具名拒（都指向纠正动作，不是模糊的"坏参数"）：
   *
   * <ul>
   *   <li>单位没有 {@code jurisdiction} ⇒ 拒，指路 {@code unit.SetJurisdiction}；
   *   <li>{@code regionId} 不在该单位的管辖 key 集里 ⇒ 拒，指路先 {@code unit.SetJurisdiction} 把它纳入管辖。
   * </ul>
   *
   * <p>★ 税率范围 {@code [0,1000]}‰ 在本方法显式判（{@link Jurisdiction} 构造期同判），超界 ⇒ 具名拒、不钳制。
   */
  public static UnitState setTaxRate(
      UnitState state, UnitId id, RegionId regionId, long ratePerMille) {
    Objects.requireNonNull(regionId, "regionId");
    Unit unit = require(state, id);
    Jurisdiction current =
        unit.jurisdiction()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "单位 " + id + " 没有 jurisdiction：先用 unit.SetJurisdiction 设定管辖区域"));
    if (!current.taxRatePerMilleByRegion().containsKey(regionId)) {
      throw new IllegalArgumentException(
          "区域 " + regionId + " 不在单位 " + id + " 的管辖里：先用 unit.SetJurisdiction 把它纳入管辖");
    }
    if (ratePerMille < 0 || ratePerMille > 1000) {
      throw new IllegalArgumentException("ratePerMille 必须 ∈ [0,1000]: " + ratePerMille);
    }
    Map<RegionId, Long> nextRates = new LinkedHashMap<>(current.taxRatePerMilleByRegion());
    nextRates.put(regionId, ratePerMille); // 既有键保持原位（LinkedHashMap 对已有键只换值）
    Jurisdiction next =
        new Jurisdiction(
            nextRates,
            current.levyGrainCapPerCommand(),
            current.levyMoneyCapPerCommand(),
            current.levyManpowerCapPerCommand(),
            current.administrationPerMille());
    return withUnit(state, withJurisdiction(unit, Optional.of(next)));
  }

  // ── 编制标签（阶段 10a，控制方修订：编制在 unit 片，故这两条是 unit.* 域命令） ─────────────

  /**
   * ★ <b>立 GOV 编制</b>（{@code unit.SetGovFormation} 的领域实现，阶段 10a）：把给定 {@link GovFormation} 挂到单位上。
   *
   * <p>★ <b>语义与拒因</b>：
   *
   * <ol>
   *   <li>单位必须存在（{@link #require}）；
   *   <li><b>一单位至多一个编制标签</b>：既有 {@link ArmyFormation} ⇒ 具名拒，<b>不做静默替换</b>；
   *   <li>{@code superiorGov} 非空 ⇒ 必须存在、必须是带 {@link GovFormation} 的单位、且不得指向自身；
   *   <li><b>同类型重复设置 = 整体替换</b>：已有 {@code GovFormation} 时不做字段级合并，直接换成传入的整份（命令层缺省 = 空 staff + {@code
   *       OfficePolicy.defaults()}）。这条是文档化的：要改一部分就先把完整目标编制造出来。
   * </ol>
   *
   * <p>★ 纯函数：产新 {@code UnitState}；变更集仍由 {@code UnitChangeSet.between} 派生（不做第二条拼增量路径）。 结果单位走 {@link
   * #withModule} 的 canonical 拷贝，18 个组件一个不丢。
   */
  public static UnitState setGovFormation(UnitState state, UnitId id, GovFormation formation) {
    Objects.requireNonNull(formation, "formation");
    Unit unit = require(state, id);
    if (unit.module().orElse(null) instanceof ArmyFormation) {
      throw new IllegalArgumentException(
          "单位 " + id + " 已带 ArmyFormation（一单位至多一个编制标签）：不能改挂 GovFormation；本命令不做静默替换");
    }
    formation
        .superiorGov()
        .ifPresent(
            superior -> {
              if (superior.equals(id)) {
                throw new IllegalArgumentException("上级 GOV 不得指向自身: " + id);
              }
              requireGovUnit(state, superior, "superiorGov");
            });
    return withUnit(state, withModule(unit, Optional.of(formation)));
  }

  /**
   * ★ <b>立 Army 编制</b>（{@code unit.SetArmyFormation} 的领域实现，阶段 10a）：把给定 {@link ArmyFormation} 挂到单位上。
   *
   * <p>★ <b>语义与拒因</b>：
   *
   * <ol>
   *   <li>单位必须存在（{@link #require}）；
   *   <li><b>一单位至多一个编制标签</b>：既有 {@link GovFormation} ⇒ 具名拒，<b>不做静默替换</b>；
   *   <li>{@code masterGov} 非空 ⇒ 必须存在、且必须是带 {@link GovFormation} 的单位（认主子只认 GOV）；
   *   <li><b>同类型重复设置 = 整体替换</b>（{@code role}/{@code masterGov} 一起换成传入的整份）。
   * </ol>
   *
   * <p>★ 纯函数；结果单位走 {@link #withModule}，18 个组件一个不丢。
   */
  public static UnitState setArmyFormation(UnitState state, UnitId id, ArmyFormation formation) {
    Objects.requireNonNull(formation, "formation");
    Unit unit = require(state, id);
    if (unit.module().orElse(null) instanceof GovFormation) {
      throw new IllegalArgumentException(
          "单位 " + id + " 已带 GovFormation（一单位至多一个编制标签）：不能改挂 ArmyFormation；本命令不做静默替换");
    }
    formation.masterGov().ifPresent(master -> requireGovUnit(state, master, "masterGov"));
    // ★ S3b：军官配置里的 commandOf 必须指向现存单位（悬空指挥引用不得进状态）。
    for (MilitaryHouseholdDuty duty : formation.householdDuties().values()) {
      duty.commandOf()
          .ifPresent(
              commandOf -> {
                if (!state.units().containsKey(commandOf)) {
                  throw new IllegalArgumentException(
                      "军官家户配置 " + duty.householdId() + " 的 commandOf 指向不存在的单位: " + commandOf);
                }
              });
    }
    return withUnit(state, withModule(unit, Optional.of(formation)));
  }

  // ── GOV 编制编辑四件（阶段 10b-i，2026-10-01） ──────────────────

  /**
   * ★ <b>改 GOV 政策</b>（{@code unit.SetGovPolicy} 的领域实现，阶段 10b-i）：四个数值与 {@code staffCap}
   * 都是**可选覆盖**——未给（{@link Optional#empty()}）保持原值；{@code staffCap} 给空表 = 清空上限。
   *
   * <p>★ <b>拒因</b>：
   *
   * <ol>
   *   <li>单位必须存在、且带 {@link GovFormation}（Army 或无编制 ⇒ 具名拒）；
   *   <li>四个数值 ≥ 0 与 {@code staffCap} 各值 ≥ 0 由 {@link OfficePolicy} 构造期拒（本方法不重复实现）。
   * </ol>
   *
   * <p>★ 保序：{@code staffCap} 经 {@link OfficePolicy} 的 LinkedHashMap 拷贝，已有键保持原位、新键追加在末尾。 ★ 纯函数：结果单位走
   * {@link #withModule} canonical 拷贝，18 个组件一个不丢。
   */
  public static UnitState setGovPolicy(
      UnitState state,
      UnitId id,
      Optional<Long> grainPerStaffPerTick,
      Optional<Long> clothPerStaffPerCycle,
      Optional<Long> moneyPerStaffPerTick,
      Optional<Long> retirementPerStaff,
      Optional<Map<StaffRole, Long>> staffCap) {
    Objects.requireNonNull(grainPerStaffPerTick, "grainPerStaffPerTick");
    Objects.requireNonNull(clothPerStaffPerCycle, "clothPerStaffPerCycle");
    Objects.requireNonNull(moneyPerStaffPerTick, "moneyPerStaffPerTick");
    Objects.requireNonNull(retirementPerStaff, "retirementPerStaff");
    Objects.requireNonNull(staffCap, "staffCap");
    Unit unit = require(state, id);
    GovFormation gov = requireGovFormation(unit, id);
    OfficePolicy current = gov.policy();
    OfficePolicy next =
        new OfficePolicy(
            grainPerStaffPerTick.orElse(current.grainPerStaffPerTick()),
            clothPerStaffPerCycle.orElse(current.clothPerStaffPerCycle()),
            moneyPerStaffPerTick.orElse(current.moneyPerStaffPerTick()),
            retirementPerStaff.orElse(current.retirementPerStaff()),
            staffCap.orElse(current.staffCap()));
    return withUnit(state, withModule(unit, Optional.of(withGovPolicy(gov, next))));
  }

  /**
   * ★ <b>改 GOV 上级</b>（{@code unit.SetGovSuperior} 的领域实现，阶段 10b-i）：{@code superiorGov} 空 = 中央（无上级）。
   *
   * <p>★ <b>拒因</b>：
   *
   * <ol>
   *   <li>单位必须存在、且带 {@link GovFormation}（Army 或无编制 ⇒ 具名拒）；
   *   <li>非空上级必须存在、带 {@link GovFormation}、不得指向自身；
   *   <li><b>不得成环</b>：从新上级沿 {@code superiorGov} 向上走，命中自己即拒；同时用 seen 兜住已损坏链的重复， 并限制最多 {@value
   *       #MAX_GOV_SUPERIOR_CHAIN} 层。
   * </ol>
   *
   * <p>★ 环检测对"查无此人 / 链上单位不是 GOV"的祖先视为链路终点（本命令只负责不引入环；悬空链的修复不在本命令面）。 ★ 纯函数；结果单位走 {@link
   * #withModule}，18 个组件一个不丢。
   */
  public static UnitState setGovSuperior(UnitState state, UnitId id, Optional<UnitId> superiorGov) {
    Objects.requireNonNull(superiorGov, "superiorGov");
    Unit unit = require(state, id);
    GovFormation gov = requireGovFormation(unit, id);
    superiorGov.ifPresent(
        superior -> {
          if (superior.equals(id)) {
            throw new IllegalArgumentException("上级 GOV 不得指向自身: " + id);
          }
          requireGovUnit(state, superior, "superiorGov");
          requireNoSuperiorCycle(state, id, superior);
        });
    return withUnit(state, withModule(unit, Optional.of(withGovSuperior(gov, superiorGov))));
  }

  /**
   * ★ <b>招募入编</b>（{@code unit.RecruitStaff} 的领域实现，阶段 10b-i）：只对 GOV 单位的 roster 做 {@code role +=
   * count}，<b>不扣任何人员来源</b>——来源扣减由同批 {@code social.SeedGroups} / 人口单位命令负责。
   *
   * <p>★ <b>staffCap 语义</b>：{@code policy.staffCap} **含该角色**且 {@code 现有 + count > cap} ⇒ 具名拒（消息带现有
   * / 上限 / 请求三个数字），<b>不截断</b>；不含该角色 = 不设上限。
   *
   * <p>★ 保序：{@code staff} 经 LinkedHashMap 拷贝，已有角色保持原位、新角色追加在末尾。★ 纯函数；结果走 {@link #withModule}。
   */
  public static UnitState recruitStaff(UnitState state, UnitId id, StaffRole role, long count) {
    Objects.requireNonNull(role, "role");
    if (count < 1L) {
      throw new IllegalArgumentException("招募人数 count 必须 ≥ 1: " + count);
    }
    Unit unit = require(state, id);
    GovFormation gov = requireGovFormation(unit, id);
    requireStaffNotProjected(gov, id, "招募");
    long current = gov.staff().getOrDefault(role, 0L);
    Long cap = gov.policy().staffCap().get(role);
    if (cap != null && current > cap - count) {
      throw new IllegalArgumentException(
          "招募 "
              + role
              + " "
              + count
              + " 人会超编制上限: 现有 "
              + current
              + " + 请求 "
              + count
              + " > staffCap "
              + cap
              + "（不截断；先 unit.SetGovPolicy 提上限或减少 count）");
    }
    if (current > Long.MAX_VALUE - count) {
      throw new IllegalArgumentException(
          "招募后 " + role + " 在编人数溢出 long: 现有 " + current + " + 请求 " + count);
    }
    Map<StaffRole, Long> staff = new LinkedHashMap<>(gov.staff());
    staff.put(role, current + count);
    return withUnit(state, withModule(unit, Optional.of(withGovStaff(gov, staff))));
  }

  /**
   * ★ <b>离编</b>（{@code unit.DismissStaff} 的领域实现，阶段 10b-i）：只对 GOV 单位的 roster 做 {@code role -=
   * count}，<b>不支付退休待遇、不把人员回写社会</b>——支付/回写由同批 actor / social 命令或 10b-ii 的组合工具批承担。
   *
   * <p>★ <b>拒因</b>：单位必须存在且带 {@link GovFormation}；{@code count ≥ 1}；{@code 现有 < count} ⇒
   * 具名拒（带现有与请求数字）。★ 结果 0 <b>保留键不删</b>（保序 + 保留"该角色编制存在"的事实）。★ 纯函数；结果走 {@link #withModule}。
   */
  public static UnitState dismissStaff(UnitState state, UnitId id, StaffRole role, long count) {
    Objects.requireNonNull(role, "role");
    if (count < 1L) {
      throw new IllegalArgumentException("离编人数 count 必须 ≥ 1: " + count);
    }
    Unit unit = require(state, id);
    GovFormation gov = requireGovFormation(unit, id);
    requireStaffNotProjected(gov, id, "离编");
    long current = gov.staff().getOrDefault(role, 0L);
    if (current < count) {
      throw new IllegalArgumentException(
          "离编 " + role + " " + count + " 人超过现有在编: 现有 " + current + " < 请求 " + count);
    }
    Map<StaffRole, Long> staff = new LinkedHashMap<>(gov.staff());
    staff.put(role, current - count); // ★ 0 保留键，不删（保序 + 保留角色编制事实）。
    return withUnit(state, withModule(unit, Optional.of(withGovStaff(gov, staff))));
  }

  /**
   * 阶段 10b-i 的四条 GOV 编辑命令共用守卫：单位存在且 {@code module} 必须是 {@link GovFormation}。
   *
   * <p>★ 与 {@link #requireGovUnit} 的区别：那个是"认主子/上级"的引用校验（消息带字段名），本方法是"被编辑对象必须是 GOV"（消息给出下一步：先 {@code
   * unit.SetGovFormation}）。
   */
  private static GovFormation requireGovFormation(Unit unit, UnitId id) {
    UnitModule module = unit.module().orElse(null);
    if (module instanceof GovFormation gov) {
      return gov;
    }
    if (module instanceof ArmyFormation) {
      throw new IllegalArgumentException(
          "单位 " + id + " 带的是 ArmyFormation 而不是 GovFormation：本命令只改 GOV 编制");
    }
    throw new IllegalArgumentException(
        "单位 " + id + " 没有 GovFormation：先用 unit.SetGovFormation 立 GOV 编制");
  }

  /**
   * 环检测（{@link #setGovSuperior}）：从新上级沿 {@code superiorGov} 向上走，命中 {@code id} ⇒ 具名拒； seen 重复 ⇒
   * 现有链已坏，具名拒；最多走 {@value #MAX_GOV_SUPERIOR_CHAIN} 层，超过 ⇒ 具名拒。
   *
   * <p>★ 查无此人或链上单位不是 {@link GovFormation} ⇒ 视为链路终点（本命令只负责不引入环）。
   */
  private static void requireNoSuperiorCycle(UnitState state, UnitId id, UnitId newSuperior) {
    Set<UnitId> seen = new LinkedHashSet<>();
    UnitId cursor = newSuperior;
    int depth = 0;
    while (cursor != null) {
      if (cursor.equals(id)) {
        throw new IllegalArgumentException(
            "不能把 " + id + " 的上级设为 " + newSuperior + "：沿 superiorGov 上溯会回到自己，会成环");
      }
      if (!seen.add(cursor)) {
        throw new IllegalArgumentException(
            "现有上级 GOV 链在 " + cursor + " 处重复/成环，无法安全设置 " + id + " 的上级: " + newSuperior);
      }
      if (depth >= MAX_GOV_SUPERIOR_CHAIN) {
        throw new IllegalArgumentException(
            "上级 GOV 链超过 " + MAX_GOV_SUPERIOR_CHAIN + " 层，拒绝设置 " + id + " 的上级: " + newSuperior);
      }
      Unit unit = state.units().get(cursor);
      if (unit == null) {
        return;
      }
      if (!(unit.module().orElse(null) instanceof GovFormation gov)) {
        return;
      }
      cursor = gov.superiorGov().orElse(null);
      depth++;
    }
  }

  /**
   * ★★ <b>S3b（2026-10-09）：{@code householdPosts} 非空 ⇒ staff 已是家户投影，禁止再直改 staff</b>。
   *
   * <p>{@code staff} 是兼容字段；一旦 GOV 用 {@link GovernmentHouseholdPost} 把领导层家户配置起来，编制人数只能由家户人口现算 （app
   * 组合根按 {@code PopulationLookup} 投影）。直接加减 staff 会制造第二本权威 ⇒ 具名拒，指路 Social 家户命令。
   */
  private static void requireStaffNotProjected(GovFormation gov, UnitId id, String action) {
    if (!gov.householdPosts().isEmpty()) {
      throw new IllegalArgumentException(
          "单位 "
              + id
              + " 已用 householdPosts 配置领导层家户，staff 只是家户人口投影：不能直接"
              + action
              + " staff（会制造第二本权威）。请改 Social 家户人口（social.* 家户成员命令），或先清空 householdPosts");
    }
  }

  /** 只换 {@link GovFormation#policy()}，其余三个组件原样带过（阶段 10b-i）。 */
  private static GovFormation withGovPolicy(GovFormation gov, OfficePolicy policy) {
    return new GovFormation(
        gov.staff(),
        gov.households(),
        gov.householdPosts(),
        policy,
        gov.superiorGov(),
        gov.level());
  }

  /** 只换 {@link GovFormation#superiorGov()}，其余三个组件原样带过（阶段 10b-i）。 */
  private static GovFormation withGovSuperior(GovFormation gov, Optional<UnitId> superiorGov) {
    return new GovFormation(
        gov.staff(),
        gov.households(),
        gov.householdPosts(),
        gov.policy(),
        superiorGov,
        gov.level());
  }

  /** 只换 {@link GovFormation#staff()}，其余三个组件原样带过（阶段 10b-i）。 */
  private static GovFormation withGovStaff(GovFormation gov, Map<StaffRole, Long> staff) {
    return new GovFormation(
        staff,
        gov.households(),
        gov.householdPosts(),
        gov.policy(),
        gov.superiorGov(),
        gov.level());
  }

  /**
   * 认主子/上级的共用守卫：目标必须存在、且必须带 {@link GovFormation}（GOV 只能认 GOV）。
   *
   * <p>★ 两条消息都点名 {@code field} 与坏 id：载荷写歪时模型要能知道是 {@code superiorGov} 还是 {@code masterGov}
   * 写错了，而不是笼统一句"参数不合法"。自身指涉的上游检查不在这里（{@link #setGovFormation} 显式判， 因为只有上级 GOV 有这一条）。
   */
  private static void requireGovUnit(UnitState state, UnitId govUnitId, String field) {
    Unit gov = state.units().get(govUnitId);
    if (gov == null) {
      throw new IllegalArgumentException(field + " 指定的 GOV 单位不存在: " + govUnitId);
    }
    if (!(gov.module().orElse(null) instanceof GovFormation)) {
      throw new IllegalArgumentException(
          field + " 指定的单位 " + govUnitId + " 没有 GovFormation：不能作为 GOV");
    }
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
   * 把 `id` 及其**全部后代**编入 `parent` 那一支「一同移动」的编制（**编制 v2**，2026-09-24 取代"偏移式加入 + 进入跟随"）。
   *
   * <p>★★ **v2 的两条硬改动**（用户 2026-09-24 原话：「把跟随功能取消掉吧，如果要合并到同一一同移动的编制， **有且只有单位在同一格子时生效**」）：
   *
   * <ol>
   *   <li><b>必须同格</b>：`id` 的整棵子树里**每一个**即将 `attached=true` 的节点，都必须与 `parent` 同格（任一不可确定
   *       也拒）。旧写法允许"不同格也能加入"（`8a005b7`）——那是为"跟随"服务的，**已作废**。
   *   <li><b>不再清位、不再反算 offset</b>：跟随取消了 ⇒ 位置各归各的（每个单位的位置永远是自己的，见 {@code
   *       UnitState.effectivePosition}），`offset` 不再参与任何计算（字段保留只为不破老档往返）。
   * </ol>
   *
   * <p>★ 仍然级联：编入的是**一支编队**，不是一个光杆节点（每个后代都落一条 `attached=true` 段）。
   *
   * <p>★ 两处**有意不拒**（沿用 T3 台账）：`parent` 已是 `id` 当前的父不拒（重挂同一父是"合体 = 重新 attach"的形态）； `attached` 已是
   * `true` 的节点不拒（本操作面不判"无变化命令"）。
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
    Optional<HexCoord> parentHex = state.effectivePosition(parent, at);
    if (parentHex.isEmpty()) {
      throw new IllegalArgumentException("单位 " + parent + " 在 " + at + " 没有可确定的位置：编制必须同格才能编入");
    }
    // ★ v2：整棵子树逐一校验同格（不是只查根）——编入之后它们都"与父一起走"，而"一起走"的前提就是此刻同格。
    for (UnitId member : subtree) {
      Optional<HexCoord> memberHex = state.effectivePosition(member, at);
      if (memberHex.isEmpty() || !memberHex.get().equals(parentHex.get())) {
        throw new IllegalArgumentException(
            "单位 "
                + member
                + " 在 "
                + at
                + " 位于 "
                + memberHex.map(HexCoord::toString).orElse("(不可确定)")
                + "，与 "
                + parent
                + " 的 "
                + parentHex.get()
                + " 不同格：只有同格的单位才能编入同一支编制");
      }
    }
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    for (UnitId member : subtree) {
      Unit current = state.units().get(member);
      // 只有根换父：后代的 parent 原样带过
      SegmentedSeries<Optional<UnitId>> parents =
          member.equals(id)
              ? setOrAppend(current.parent(), at, Optional.of(parent))
              : current.parent();
      // ★ v2：位置、offset 都**原样带过**（跟随已取消 ⇒ 没有"进入跟随"要清位/反算的东西）。
      next.put(
          member,
          copyFormation(
              current,
              parents,
              current.position(),
              setOrAppend(current.attached(), at, true),
              current.offset()));
    }
    return state.withUnits(next);
  }

  /**
   * detach（P3：**只节点**）：**只**给 `id` 落一条 `attached=false` 段（同刻已有段 ⇒ 覆盖，见 {@link
   * #setOrAppend}）——子节点**不动**（与 attach 刻意不对称； 子树整体的分离是 SplitFormation，T4）：脱离之后 `id`
   * 就是**它自己那一支的顶层**（它带着自己的下挂走）。
   *
   * <p>★★ **v2 起不再"物化位置"**（2026-09-24）：旧写法要在翻 `attached` 之前把有效位置写进自身 `position`，理由是 "detached
   * 且无自身位置 ⇒ 有效位置为空 ⇒ 单位从图上消失"。**跟随取消之后这条前提没了**：每个单位的位置永远是自己的，
   * 脱离只是换一个编制身份，位置一个字都不用动（故也没有"先算后改"的顺序陷阱了）。
   *
   * <p>★ `id` 在 `at` 没有父 ⇒ 拒：它本来就是顶层（没有"从谁的编制里出来"这件事），这是坏命令。
   */
  public static UnitState detachUnit(UnitState state, UnitId id, SimosTimestamp at) {
    Unit unit = require(state, id);
    if (unit.parent().valueAt(at).isEmpty()) {
      throw new IllegalArgumentException("单位 " + id + " 在 " + at + " 没有父：它本来就是顶层，无需脱离编制");
    }
    return withUnit(
        state,
        copyFormation(
            unit,
            unit.parent(),
            unit.position(),
            setOrAppend(unit.attached(), at, false),
            unit.offset()));
  }

  /**
   * 相对偏移（spec §一.3 / P2）：追加一条 `offset` 段（`Optional.empty()` = 清除偏移）。
   *
   * <p>★★ **编制 v2 起它不再影响任何计算**（2026-09-24，「取消跟随」的连带），**P8 起命令面已具名拒**：`offset` 原本只服务
   * "跟随时的相对站位"（{@code effectivePosition} 里"向父取 ⊕ offset"那一支），而那一支已作废 ⇒ {@code
   * unit.SetFormationOffset} 命中即拒、不再调用本操作。本操作与 {@code RelativeOffset} 保留（不破老档往返、旧档/模型仍可能读），
   * 只是命令面不再接受。
   */
  public static UnitState setOffset(
      UnitState state, UnitId id, Optional<RelativeOffset> offset, SimosTimestamp at) {
    Objects.requireNonNull(offset, "offset");
    Unit unit = require(state, id);
    return withUnit(
        state,
        copyFormation(
            unit,
            unit.parent(),
            unit.position(),
            unit.attached(),
            setOrAppend(unit.offset(), at, offset)));
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
              current,
              setOrAppend(current.parent(), at, parent),
              current.position(),
              current.attached(),
              current.offset()));
    }
    return state.withUnits(next);
  }

  /**
   * ★ 拆分（T4 / spec §一.3 / §一.5 表）：`subUnitIds` 里的每个单位都必须在 `rootId` 在 `at` 的**子树内**，然后逐个 {@link
   * #detachUnit}——**只节点**（P3 的不对称：拆下来的节点**自己的后代不动**，与 detach 同一口径）。
   *
   * <p>★★ **编制 v2 下这条命令的地位变了**（2026-09-24）：跟随取消后，"派一支部队出去独立行动"的**唯一**正道就是拆分——拆出来的
   * 节点成为**它自己那一支的顶层**（`attached=false`），于是它可以自己下路线（它的下挂 `attached=true` 的后代仍跟着它走）；
   * 而**没拆的成员不能自己走**（{@link #planRoute} 会拒）。⇒ 它与 {@link #planRoute} 的拒绝理由里那句"先用 SplitFormation 或
   * DetachUnit 拆成独立单位"是**成对**的：一条拦、一条放。
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
   * ★ **编制 v2 起 `attachSubtree` 也要求同格**（跟随取消后，"一同移动"的前提就是同格）⇒ 本命令的同格前置与它**同向**， 两处都在判、理由各有措辞（本条 =
   * "只有同格才能合体"，attach 那条 = "只有同格的单位才能编入同一支编制"）。
   *
   * <p>★★ **最慢者决定速度**（2026-09-24 新增，用户："合体的单位速度是其中速度最低单位的速度"）：
   *
   * <ul>
   *   <li><b>合并后的单位 = {@code parentId} 那一方</b>（存活方；本命令把 `childId` 重新挂到 `parentId` 下，`parentId`
   *       不换父、不被销毁）。只有它改 `speed`；`childId` 与其余单位的 `speed` 不动。
   *   <li><b>`min` 的作用域 = 合体后整棵子树</b>（`parentId` 及其全部后代，**含新加入的子树**）里所有单位的 `speed` 最小值。 ⇒
   *       最慢的**下属**也会拖住整支编制（用户"速度最低单位的速度"的字面）。
   *   <li><b>只改 `speed`</b>：`mobilityPerMille` 是否同样取 `min` **本轮未裁定，不动**（用户只说了速度）。
   *   <li>{@code min} 与方向无关：父慢子快、父快子慢，存活方最终都等于那个最小值。
   * </ul>
   *
   * <p>★ **{@link #attachSubtree} 本身不改速度**：用户说的是"合体"，attach（加入编队）不在此列；若也要它的速度语义，是另一条待裁定的事。
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
    UnitState attached = attachSubtree(state, childId, parentId, at);
    // ★ 最慢者决定速度：合体后整棵子树（含新加入的子树）里 speed 的最小值，落到**存活方** parentId 上。
    //   subtreeOf 含 parentId 自身 ⇒ 该 min ≤ parentId 原来的 speed，故只会变慢、不会凭空变快。
    int slowest = Integer.MAX_VALUE;
    for (UnitId member : subtreeOf(attached, parentId, at)) {
      slowest = Math.min(slowest, attached.units().get(member).speed());
    }
    return withUnit(attached, withSpeed(attached.units().get(parentId), slowest));
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

  // ── 回归路径（T7 / spec §二.2 / P7 / P8） ──────────────────────

  /**
   * 设/清回归目标（T7 / spec §四 表 `unit.SetRejoinTarget` / 裁定 U4）：`target` 缺省或 `null` ⇒ **清除**回归意图。
   *
   * <p>★ **只记引用、绝不记 hex**（P8 / §二.3 不变量 3 / 裁定 U6）：终点是目标单位**当前**的 {@code effectivePosition}，每 tick
   * 现算——本操作**不写** {@code movement}、**不追加** {@code position} 段，物化归 {@code UnitTimeParticipant}。存成"冻结
   * hex"是 research §B.7 坑 2 的形态（CMO #16284：航点不更新移动母体 ⇒ 飞向错误位置并坠毁），本操作面从类型上就不给它落点。
   *
   * <p>★ **两条拒绝**（spec §四 表"目标不存在；自指"）：目标不在 `units` 里 ⇒ 拒（§二.3 不变量 2 的命令期半边）；目标 = 自身 ⇒
   * 拒（自指会让"终点"退化成自己的当前位置，永远无路可走）。
   *
   * <p>★ **不在这里判"有没有能力回归"**（P7）：那是**环境**的函数（目标会动、地图会改地形），**每 tick 重算、不存成布尔** ——判定与物化同在 {@link
   * #rejoinRoute} / {@code UnitTimeParticipant}，本操作只管意图本身。
   *
   * <p>★ U5：状态是 RESTING/ENGAGED 时**照记不误**（引用**不清**）——状态回到 MOVING 后回归自动恢复。
   */
  public static UnitState setRejoinTarget(UnitState state, UnitId id, Optional<UnitId> target) {
    Objects.requireNonNull(target, "target");
    Unit unit = require(state, id);
    if (target.isPresent()) {
      UnitId targetId = target.get();
      if (targetId.equals(id)) {
        throw new IllegalArgumentException("回归目标不得是自身: " + id);
      }
      if (!state.units().containsKey(targetId)) {
        throw new IllegalArgumentException("回归目标不存在: " + targetId);
      }
    }
    return withUnit(state, withRejoinTarget(unit, target));
  }

  /**
   * ★★ **"有能力回归"的纯判定 + 本刻路线**（T7 / spec §二.2 / P7 / 裁定 U5 / U6）：三个条件**每 tick 现算、不存布尔**——
   * 状态允许移动（`status == MOVING`，裁定 U5）∧ 自身与目标的 {@code effectivePosition} 都可确定 ∧ A\* 从自身当前位置到
   * 目标**当前**位置可达。
   *
   * <p>★ 返回的是**本刻**的 {@link Route}（`waypoints` = 起终点两格，`path` 由 {@link PathFinder} 展开）；**不落任何持久事实**
   * ——下一 tick 拿新状态再算一次，终点自然随动（§二.3 不变量 3）。调用方要写进状态的只有这条路线 + 引用本身。
   *
   * <p>★ 四类"假"**共用一个出口**（空）：无回归意图 / 非 MOVING / 位置（自身或目标）不可确定 / 不可达。调用方对它们的处置
   * **完全相同**——不进变更集（U5：`rejoinTarget` 引用**不清**，下一 tick 状态或环境变了就自动恢复）。四种"假"由 {@code
   * UnitTimeParticipantTest} 的夹具分开钉（同一出口不等于同一判据）。
   *
   * <p>★ **已与目标同格 ⇒ 空**：那是"已经回归"（且 `Route` 至少要两格）；此时**引用也不清**（可复用的判据同 U5：目标再动时重新规划）。
   *
   * <p>★ 目标不存在（手工拼装的状态可以有悬空引用）⇒ 空：{@code effectivePosition} 对查无此人的 id 就返回空，本方法**不额外**为它 设一条守卫（§二.3
   * 不变量 2 的构造期半边**不在本任务的文件清单里**，见 T7 报告的缺口表）。
   */
  public static Optional<Route> rejoinRoute(
      UnitState state, UnitId id, GameMap map, MovementCost cost, SimosTimestamp at) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(at, "at");
    Unit unit = state.units().get(id);
    if (unit == null || unit.rejoinTarget().isEmpty()) {
      return Optional.empty();
    }
    if (unit.status() != UnitStatus.MOVING) {
      return Optional.empty(); // 裁定 U5：RESTING/ENGAGED 不自动回归（引用不清，回到 MOVING 后恢复）
    }
    // ★ 编制 v2：**只有顶层能自己走**（回归也是一次自主移动）⇒ 与 planRoute 同一闸门（这里只"跳过"不抛：本方法的
    //   契约是"给不出路线就空"，抛会把它变成一条会炸的查询）。
    if (state.formationRoot(id, at).filter(root -> !root.equals(id)).isPresent()) {
      return Optional.empty();
    }
    Optional<HexCoord> start = state.effectivePosition(id, at);
    Optional<HexCoord> goal = state.effectivePosition(unit.rejoinTarget().get(), at);
    if (start.isEmpty() || goal.isEmpty() || start.get().equals(goal.get())) {
      return Optional.empty();
    }
    return PathFinder.findPath(map, start.get(), goal.get(), unit, cost)
        .map(path -> new Route(List.of(start.get(), goal.get()), path));
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
   * 改单个单位的**唯一**入口（面最宽）：`rename` / `setComposition` / `adjustComposition` / `applyCasualties` /
   * `placeAt` / `planRoute` / `setStatus` / `cancelRoute` / `setOffset` / `create` / `reparent` /
   * `detachUnit` 十个操作都经这里落盘。
   *
   * <p>★ **T5-U2**：`commandChains` 用 {@link UnitState#withUnits(Map)} 带过。此前这里用 1 参兼容构造器 （`new
   * UnitState(next)`）⇒ **十个操作的任何一次调用都会把全部链静默清空**；链是**无时刻的具名集合**，改某个单位的字段
   * 与它无关。既有用例之所以全绿，是因为"`Map.of()` 换 `Map.of()`"是恒等——没有一条用例在带链的状态上跑过本助手。
   */
  private static UnitState withUnit(UnitState state, Unit unit) {
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）
    return state.withUnits(next);
  }

  private static <T> SegmentedSeries<T> append(
      SegmentedSeries<T> series, SimosTimestamp at, T value) {
    List<Segment<T>> segments = new ArrayList<>(series.segments());
    segments.add(new Segment<>(at, value));
    return new SegmentedSeries<>(segments, series.events(), series.addition());
  }

  /**
   * ★ **同刻后写者胜**的段写入（**全部改编类字段**：`position`/`offset`/`attached`/`parent`）：末段已是 `from == at` ⇒
   * **替换**它，否则追加。
   *
   * <p>为什么必须有它：{@link SegmentedSeries} 禁止同刻两段（`段必须按 from 严格升序`）。而"同一个 base 时间戳上多条命令写 同一条 `position`
   * 序列"是**真实形态**——{@code McpCoverageTest} 逐条命令都落在同一个 tick（信封不带时刻，裁定 35）， `unit.PlaceAt` 先在 `at`
   * 落一段、`unit.SplitFormation`（内部 detach）随后又要物化位置：若仍走 {@link #append}
   * 直接撞严格升序。语义上同刻的第二笔写就是"覆盖此刻生效的值"，替换即正确解。
   *
   * <p>★ <b>{@code offset} 走本方法的原因是 {@link #setOffset}</b>（它会写 {@code offset}）；{@link
   * #attachSubtree} 编制 v2 起**不再动 {@code offset}**（位置各归各的），只是 {@code attached}/{@code parent}
   * 的同刻写也要覆盖， 故同样经本方法落段。
   *
   * <p>★★ <b>{@code attached}/{@code parent} 于 2026-09-24 稍后也加入本列</b>（<b>live
   * 跑出来的</b>，不是顺手扩张）：三国决策人在 <b>tick 0</b> 出令"先 `unit.DetachUnit` 再下路线"，三条 detach 全被拒——创世段就在 tick
   * 0、命令也落在 tick 0 ⇒ `段必须按 from 严格升序（同刻两段无法判定谁生效）`。世界不推进时间时，<b>任何第二条改编都会撞</b>。语义与上面
   * 那条一致：同刻的第二笔写就是"覆盖此刻生效的归属"，替换即正确解。⇒ {@link #attachSubtree} / {@link #detachUnit} / {@link
   * #reparent} / {@link #reparentSubtree} 全部改走本方法。
   */
  private static <T> SegmentedSeries<T> setOrAppend(
      SegmentedSeries<T> series, SimosTimestamp at, T value) {
    List<Segment<T>> segments = new ArrayList<>(series.segments());
    int last = segments.size() - 1;
    if (last >= 0 && segments.get(last).from().compareTo(at) == 0) {
      segments.set(last, new Segment<>(at, value));
    } else {
      segments.add(new Segment<>(at, value));
    }
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
   * ★ **canonical 拷贝点**：9 个可变字段（含装备表）由调用方给，T1 的四个新字段（{@code status}/{@code attached}/{@code
   * offset}/{@code rejoinTarget}）、Task 1 的 {@code visionRadius}、辖区阶段 5 的 {@code jurisdiction}、阶段 9
   * 的 {@code module} 与 D1 的 {@code stateDescriptions} 一律**原样带过**——不用兼容构造器（那会把新字段重置成默认值， 正是 R1
   * 的残留风险）。 只动编队三件套（{@code parent}/{@code attached}/{@code offset}）的操作用同族的 {@link #copyFormation}。
   */
  private static Unit copy(
      Unit unit,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    return new Unit(
        unit.id(),
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * ★ **T3 的 canonical 拷贝点**：在 9 参 {@link #copy} 之上**显式**给 `position`/`attached`/`offset`
   * 三个分量（`status`/`rejoinTarget`/`visionRadius`/`jurisdiction`/`module` 仍原样带过）。四个形参类型两两不同 ⇒
   * 传错顺序是**编译错误**，不是静默错位；编制三件套的操作 （`parent`/`attached`/`offset` **加上新落地的 `position`**）只动这四个，故不走全 17
   * 参。
   *
   * <p>★★ **编制 v2（2026-09-24，取消跟随）起本方法的实际用法**：{@code attachSubtree}、{@code detachUnit}、 {@code
   * reparentSubtree} 都**只改 `parent`/`attached`**，`position`/`offset` 一律原样传回（不再清位、不再反算、
   * 不再物化位置）；当前唯一写 `offset` 的调用点是 {@link #setOffset}。★ 两个形参保留，是因为 {@code Unit} 的构造
   * 仍需要它们且未来若要写位也不必再改签名。
   */
  private static Unit copyFormation(
      Unit unit,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset) {
    return new Unit(
        unit.id(),
        unit.name(),
        parent,
        position,
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        attached,
        offset,
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * ★ <b>只换 {@code households}、其余 17 个组件原样带过</b>（S3a / 2026-10-09 的 canonical 拷贝点，与 {@link
   * #withStateDescriptions} 同形）。
   *
   * <p>★★ <b>18 个组件逐一显式列出</b>（不是走兼容构造器）：那会把 {@code status}/{@code attached}/{@code offset}/ {@code
   * rejoinTarget}/{@code visionRadius}/{@code jurisdiction}/{@code module}/{@code
   * stateDescriptions} 一并重置成默认值——改容纳家户顺手清掉编制/管辖/编队是本仓最贵的教训形态（R1 字段漂移）。
   */
  private static Unit withHouseholds(Unit unit, List<HouseholdId> households) {
    Objects.requireNonNull(households, "households");
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        households);
  }

  /**
   * 只换 `speed`、其余 17 个组件（尤其是 {@code mobilityPerMille}、视野半径、管辖与编制模块）原样带过——**mergeFormation
   * 的最慢者决定速度**专用 （canonical 拷贝点，同 {@link #withStatus} 的形制）。
   */
  private static Unit withSpeed(Unit unit, int speed) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        speed,
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /** 只换 status、其余 16 个组件（含另外三个新字段、视野半径、管辖与编制模块）原样带过。 */
  private static Unit withStatus(Unit unit, UnitStatus status) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        status,
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * 只换 `rejoinTarget`、其余 16 个组件原样带过（T7，与 {@link #withStatus} 同形的 canonical 拷贝点）。
   *
   * <p>★ **不用兼容构造器**：那会把 `status`/`attached`/`offset`（以及视野半径、管辖、编制模块）一并重置成默认值（R1 的残留风险，同 {@link
   * #copy} 的注）。
   */
  private static Unit withRejoinTarget(Unit unit, Optional<UnitId> rejoinTarget) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        rejoinTarget,
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * 只换 `jurisdiction`、其余 16 个组件原样带过（辖区阶段 5，与 {@link #withStatus} 同形的 canonical 拷贝点）。
   *
   * <p>★ **不用兼容构造器**：那会把全部既有字段（含编制模块）重置成默认值——管辖变更绝不能顺手清掉编制/位置/视野。
   */
  private static Unit withJurisdiction(Unit unit, Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        jurisdiction,
        unit.module(),
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * ★ <b>只换 {@code module}、其余 16 个组件原样带过</b>（阶段 10a 的 canonical 拷贝点，与 {@link #withJurisdiction} /
   * {@link #withStatus} 同形）。
   *
   * <p>★★ <b>18 个组件逐一显式列出</b>（不是走兼容构造器）：那会把 {@code status}/{@code attached}/{@code offset}/ {@code
   * rejoinTarget}/{@code visionRadius}/{@code jurisdiction} 一并重置成默认值——立编制顺手清掉管辖/视野/编队 是本仓最贵的教训形态（R1
   * 字段漂移）。★ 两条"立编制"命令（{@link #setGovFormation} / {@link #setArmyFormation}） 都只经此一处换 {@code
   * module}，不为 GOV / Army 各写一份拷贝点。
   */
  private static Unit withModule(Unit unit, Optional<UnitModule> module) {
    Objects.requireNonNull(module, "module");
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        module,
        unit.stateDescriptions(),
        unit.households());
  }

  /**
   * ★ <b>只换 {@code stateDescriptions}、其余 16 个组件原样带过</b>（阶段 D1 / D-012 的 canonical 拷贝点，与 {@link
   * #withModule} 同形）。
   *
   * <p>★★ <b>18 个组件逐一显式列出</b>（不是走兼容构造器）：那会把 {@code status}/{@code attached}/{@code offset}/{@code
   * rejoinTarget}/{@code visionRadius}/{@code jurisdiction}/{@code module}
   * 一并重置成默认值——改一条状态链接顺手清掉编制/管辖/编队是本仓最贵的教训形态（R1 字段漂移）。
   */
  private static Unit withStateDescriptions(Unit unit, Map<String, String> stateDescriptions) {
    Objects.requireNonNull(stateDescriptions, "stateDescriptions");
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module(),
        stateDescriptions,
        unit.households());
  }
}
