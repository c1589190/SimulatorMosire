package io.mosire.simos.unit.spi;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.MovementState;
import io.mosire.simos.unit.move.MovementStatus;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * unit 侧的时间推进参与者（spec §9.1，U15 乙）：对每个持有在途 {@link Movement} 的单位， 用 {@link UnitMoves#evaluate} （M3
 * 已有，纯函数）算到 {@code range.to} 的位置。
 *
 * <p>★ **产出**（spec §9.1 三行）：
 *
 * <ul>
 *   <li>已抵达（{@code ARRIVED}）⇒ 提案：{@code position} 段写入抵达点、**清空** {@code movement}
 *   <li>未抵达（{@code IN_TRANSIT} / {@code NEED_REPLAN}）⇒ 提案：{@code position} 段写入当前所在格，
 *       **不改路线**（{@code NEED_REPLAN} 的判定沿用 M3——地图实时生效）
 *   <li>无在途 {@code Movement} 的单位 ⇒ 不进变更集（{@code Unchanged}）
 * </ul>
 *
 * <p>★★ **第二趟：回归重规划**（T7 / spec §二.2 / P8 / 裁定 U6，本类的第二个职责）：对每个 `rejoinTarget` 非空的单位，
 * 在**第一趟物化之后**的状态上现算"有能力回归"（{@code UnitOperations.rejoinRoute}：`status == MOVING` ∧ 自身与目标的
 * `effectivePosition` 都可确定 ∧ A\* 到目标**当前**位置可达），满足 ⇒ 本刻**重新装载**一条 `Movement`（`departedAt` = 本刻、路线起点
 * = 它此刻所在格）；不满足 ⇒ 不动。**持久事实只有 `rejoinTarget` 那个引用**——终点 hex 每 tick 现算， 绝不落进任何字段（§二.3 不变量
 * 3：大编制移动时终点**随动**，不冻结在旧 hex）。
 *
 * <p>★ 与上面"不改路线"的关系（不矛盾，两条轨道）：**在途的普通路线**照旧不重规划（spec §9.1 的"不做 REPLAN_EVERY_STEP"说的是那条轨道）；**回归轨道**按
 * P8 明写"每 tick 重规划"，它每次都产出一条**新** `Movement` ——旧行程被替换，而不是被"改路线"。
 *
 * <p>★ **不做**：不改路线策略、不做 {@code REPLAN_EVERY_STEP}、不碰 social（spec §9.1 原文）。
 *
 * <p>★ **装配注入两个无法从状态导出的事实**：{@link MovementCost}（与 M3「走参数显式传入、不依赖任何地图单例」同一口径） 与 {@code
 * mapId}——{@link GameMap} 没有 id 字段（M2/M3 挂起项，{@code map:<mapId>} 的 mapId 只回显不可校验）， 而读写集要写 {@code
 * map:<mapId>:hex.q_r} 形式的 canonical 地址，故由装配提供与本世界一致的称谓。地图有了身份字段后，这里就是收紧点。
 *
 * <p>★ **读写集**（spec §9.1）：{@code reads} = 涉及单位的 {@code unit:<id>} + 其路线经过的 {@code
 * map:<mapId>:hex.q_r}； {@code writes} = 被写单位的 {@code unit:<id>}。canonical 一律由 {@link Address} AST
 * 构造（与 {@code MapResolver} 同一条纪律，mapId 含特殊字符时引号自动正确）。
 *
 * <p>★ **两个显式边界**（台账裁定 19：不要靠"不会发生"）：
 *
 * <ul>
 *   <li>{@code range.to} 缺省（无上界推进）⇒ **提案零变更**：无上界的推进落不成 revision（spec §5.4 第 0 项 Core 必拒），
 *       参与者无可评估的时刻，交空提案即可——**不抛**，让 Core 的 Validate 走它自己的拒绝路径。
 *   <li>{@code range.to} 早于某单位 {@code departedAt} ⇒ **照调 {@link UnitMoves#evaluate} 让它抛**：M3
 *       对该输入的口径就是 {@code IllegalArgumentException}（调用方 bug），真实链路里 {@code to} 严格晚于快照时刻 ≥ {@code
 *       departedAt}， 到不了这里。
 * </ul>
 */
public final class UnitTimeParticipant implements TimeParticipant {

  private final MovementCost cost;
  private final String mapId;

  public UnitTimeParticipant(MovementCost cost, String mapId) {
    this.cost = Objects.requireNonNull(cost, "cost");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String namespace() {
    return "unit";
  }

  @Override
  public TimeProposal simulate(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty()) {
      // 无上界推进：没有可评估的时刻，交零变更提案（该推进随后必被 Core 的 Validate 拒绝，spec §5.4 第 0 项）
      return new TimeProposal(
          namespace(),
          UnitChangeSet.between(snapshot.state(), snapshot.state()),
          Set.of(),
          Set.of());
    }
    GameMap map = mapOf(state);
    Map<UnitId, Unit> units = new LinkedHashMap<>(snapshot.state().units());
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();
    for (Unit unit : snapshot.state().units().values()) {
      if (unit.movement().isEmpty()) {
        continue; // 无在途 Movement ⇒ 不进变更集（spec §9.1 第 3 行）
      }
      Movement inFlight = unit.movement().orElseThrow();
      // ★ 编制 v2（2026-09-24）：**只有顶层会自己走**。成员若（老档/手工状态）带着自己的 movement，**不推进它**
      //   ——它的位置由顶层带动（见下面那段"整支一起搬"）。这条判定让"谁在动"与 `planRoute` 的闸门**同源**，
      //   也把"同一支里两个单位各走各的"这种形态从推进器里排除掉。
      UnitId root = snapshot.state().formationRoot(unit.id(), to.get()).orElse(unit.id());
      if (!root.equals(unit.id())) {
        continue;
      }
      MovementState materialized = UnitMoves.evaluate(unit, to.get(), map, cost);
      boolean arrived = materialized.status() == MovementStatus.ARRIVED;
      Optional<Movement> nextMovement = arrived ? Optional.empty() : Optional.of(inFlight);
      HexCoord here = materialized.currentHex();
      units.put(unit.id(), withPositionAndMovement(unit, to.get(), here, nextMovement));
      String unitAddress = unitAddress(unit.id());
      reads.add(unitAddress);
      writes.add(unitAddress);
      // ── ★★ 编制 v2：整支一起搬（顶层动 ⇒ 它那一支的成员一起到**同一格**） ─────────────────────
      //   跟随取消后，"一起移动"不再靠 effectivePosition 的继承，而是**显式**把成员的位置一起写出来：
      //   这样"整支始终同格"是个看得见的事实，而不是某条查询规则的副作用。
      //   ★ 成员自己的在途行程一并清掉——它正被带着走，再留一条自己的路线只会变成一句过期的决心。
      for (UnitId member : snapshot.state().formationMembers(root, to.get())) {
        if (member.equals(root)) {
          continue;
        }
        units.put(
            member, withPositionAndMovement(units.get(member), to.get(), here, Optional.empty()));
        String memberAddress = unitAddress(member);
        reads.add(memberAddress);
        writes.add(memberAddress);
      }
      for (HexCoord hex : inFlight.route().path()) {
        reads.add(hexAddress(mapId, hex));
      }
    }
    // ── 第二趟（T7 / spec §二.2 / P8 / 裁定 U6）：回归重规划 ──────────
    // ★ "目标**当前**有效位置"取的是**第一趟物化之后**的那份状态：在途单位的 position 段此刻才落在 to，用推进前的快照
    // 会拿到上一 tick 的旧格——那正是 research §B.7 坑 2 的"冻结在旧 hex"形态（m1 靶子）。
    UnitState materialized = snapshot.state().withUnits(units);
    for (Unit unit : snapshot.state().units().values()) {
      Optional<Route> planned =
          UnitOperations.rejoinRoute(materialized, unit.id(), map, cost, to.get());
      if (planned.isEmpty()) {
        continue; // 无意图 / 没能力回归（不可达 · 位置不可确定 · 非 MOVING）⇒ 不动（不进变更集）
      }
      Route route = planned.orElseThrow();
      // ★ 只写 movement（U6：本刻重新装载一条行程，departedAt = 本刻）——**不碰 position**：起点就是该单位此刻
      // 所在格，而 position 段要么已被第一趟写在 to（在途单位），要么本就等于它（非在途单位）⇒ 不写也不会破
      // "路线起点 == departedAt 的有效位置"。更关键的是：**绝不把终点 hex 写进任何持久字段**（§二.3 不变量 3）。
      units.put(
          unit.id(),
          withMovement(
              units.get(unit.id()),
              Optional.of(
                  new Movement(
                      route,
                      to.get(),
                      // ★ 编制 v2：回归也是一次"整支一起走"⇒ 速度用整支最慢（与 planRoute 同口径）；
                      //   而 rejoinRoute 已把成员挡在外面（只有顶层会走到这里）。
                      materialized.formationSpeed(unit.id(), to.get()),
                      unit.mobilityPerMille()))));
      String unitAddress = unitAddress(unit.id());
      reads.add(unitAddress);
      writes.add(unitAddress);
      // 终点取自**目标**的当前有效位置 ⇒ 它的地址是真输入；新路线经过的格同理（A* 询价过它们）
      reads.add(unitAddress(unit.rejoinTarget().orElseThrow()));
      for (HexCoord hex : route.path()) {
        reads.add(hexAddress(mapId, hex));
      }
    }
    // ★ T5-U2：用 withUnits 保留 commandChains——推进改的是 position/movement，链与它无关（旧写法 new UnitState(units)
    // 会把链静默抹掉）
    UnitState target = snapshot.state().withUnits(units);
    return new TimeProposal(
        namespace(), UnitChangeSet.between(snapshot.state(), target), reads, writes);
  }

  // ── 私有助手 ────────────────────────────────────────────────────

  /** 追加一条 {@code position} 段（{@code from = at}）并替换在途行程；其余字段（含视野半径、管辖、编制模块与状态链接）原样带过。 */
  private static Unit withPositionAndMovement(
      Unit unit, SimosTimestamp at, HexCoord hex, Optional<Movement> movement) {
    List<Segment<Optional<HexCoord>>> segments = new ArrayList<>(unit.position().segments());
    segments.add(new Segment<>(at, Optional.of(hex)));
    SegmentedSeries<Optional<HexCoord>> position =
        new SegmentedSeries<>(segments, unit.position().events(), unit.position().addition());
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        position,
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
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

  /** 只换在途行程、其余 16 个组件（含 {@code position}、视野半径、管辖、编制模块与状态链接）原样带过（T7：回归重规划**不碰位置**）。 */
  private static Unit withMovement(Unit unit, Optional<Movement> movement) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
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

  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  private static String unitAddress(UnitId id) {
    return new Address(List.of(new Namespace("unit"), Entity.of(id.value()))).canonical();
  }

  private static String hexAddress(String mapId, HexCoord hex) {
    return new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", hex.toString())))
        .canonical();
  }
}
