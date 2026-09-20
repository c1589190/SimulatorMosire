package io.mosire.simos.unit.spi;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.MovementState;
import io.mosire.simos.unit.move.MovementStatus;
import io.mosire.simos.unit.move.UnitMoves;
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
      MovementState materialized = UnitMoves.evaluate(unit, to.get(), map, cost);
      boolean arrived = materialized.status() == MovementStatus.ARRIVED;
      Optional<Movement> nextMovement = arrived ? Optional.empty() : Optional.of(inFlight);
      units.put(
          unit.id(),
          withPositionAndMovement(unit, to.get(), materialized.currentHex(), nextMovement));
      String unitAddress = unitAddress(unit.id());
      reads.add(unitAddress);
      writes.add(unitAddress);
      for (HexCoord hex : inFlight.route().path()) {
        reads.add(hexAddress(mapId, hex));
      }
    }
    UnitState target = new UnitState(units);
    return new TimeProposal(
        namespace(), UnitChangeSet.between(snapshot.state(), target), reads, writes);
  }

  // ── 私有助手 ────────────────────────────────────────────────────

  /** 追加一条 {@code position} 段（{@code from = at}）并替换在途行程；其余字段原样带过。 */
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
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        movement,
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget());
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
