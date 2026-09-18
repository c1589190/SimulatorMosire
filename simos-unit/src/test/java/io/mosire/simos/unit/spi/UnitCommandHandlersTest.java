package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 七个 unit 命令 handler（spec §四）：逐命令验证 happy path（{@code Applied} + 应用之后的 {@code UnitState} 逐值）与关键拒绝。
 *
 * <p>★ 带时刻的命令（CreateUnit / ReparentUnit / PlaceAt / PlanRoute / DisbandUnit）在**非零 base 时间戳**（{@link
 * #T5}）上跑： 既符合真实推进语义（base 的 anchor 段在 T0），也把"初始段时刻 = base 状态时间戳"钉成可判别的断言（T5 ≠ T0）。
 */
class UnitCommandHandlersTest {

  private static final SimosTimestamp T5 = SimosTimestamp.of(5);
  private static final UnitId U2 = new UnitId("u-2");

  private static final CreateUnitHandler CREATE = new CreateUnitHandler();
  private static final ReparentUnitHandler REPARENT = new ReparentUnitHandler();
  private static final SetStrengthHandler SET_STRENGTH = new SetStrengthHandler();
  private static final PlaceAtHandler PLACE_AT = new PlaceAtHandler();
  private static final PlanRouteHandler PLAN_ROUTE = new PlanRouteHandler();
  private static final CancelRouteHandler CANCEL_ROUTE = new CancelRouteHandler();
  private static final DisbandUnitHandler DISBAND = new DisbandUnitHandler();

  // ── 夹具与世界构造 ──────────────────────────────────────────────

  /** u-1（T0 anchor：无父、位置 H11、100 人、{"步枪":50}、speed 2、mobility 500）。 */
  private static UnitState oneUnit() {
    return SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));
  }

  /** u-1 与 u-2 两个无父单位（改编/解散的基线）。 */
  private static UnitState twoIndependent() {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()),
        unit("u-2", Optional.empty(), Optional.of(SpiFixture.H12)));
  }

  /** u-2 在 T0 挂在 u-1 下（环/下属拒绝的基线）。 */
  private static UnitState twoWithSubordinate() {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()),
        unit("u-2", Optional.of("u-1"), Optional.of(SpiFixture.H12)));
  }

  private static Unit unit(String id, Optional<String> parent, Optional<HexCoord> position) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /** 世界的时间戳 = T0（大多数命令；无时刻命令与 T0 语义相同）。 */
  private static SimulationState world(UnitState base) {
    return SpiFixture.state(SpiFixture.map(), base);
  }

  /** 世界的时间戳 = at（带时刻命令的基线；各切片时间戳同步）。 */
  private static SimulationState worldAt(SimosTimestamp at, UnitState base) {
    return new SimulationState(
        new StateMeta(SpiFixture.REF, at),
        Map.of(
            "unit", new UnitSnapshot(SpiFixture.REF, at, base),
            "map", new MapSnapshot(SpiFixture.REF, at, SpiFixture.map())),
        InMemoryInfoSystem.empty());
  }

  private static UnitState unitSlice(SimulationState world) {
    return ((UnitSnapshot) world.module("unit").orElseThrow()).state();
  }

  /** 断言 Applied，把变更集应用回 base，返回应用之后的 UnitState（逐值断言的输入）。 */
  private static UnitState applied(CommandHandler handler, SimulationState world, String payload) {
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome).as("期望 Applied 而不是 %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return UnitChangeSet.apply(changeSet, unitSlice(world));
  }

  /** 断言 Rejected，返回拒绝理由。 */
  private static String reason(CommandHandler handler, SimulationState world, String payload) {
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome)
        .as("期望 Rejected 而不是 %s", outcome)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  // ── type() ─────────────────────────────────────────────────────

  @Test
  void typeNamesMatchTheSpecTable() {
    assertThat(CREATE.type()).isEqualTo("unit.CreateUnit");
    assertThat(REPARENT.type()).isEqualTo("unit.ReparentUnit");
    assertThat(SET_STRENGTH.type()).isEqualTo("unit.SetStrength");
    assertThat(PLACE_AT.type()).isEqualTo("unit.PlaceAt");
    assertThat(PLAN_ROUTE.type()).isEqualTo("unit.PlanRoute");
    assertThat(CANCEL_ROUTE.type()).isEqualTo("unit.CancelRoute");
    assertThat(DISBAND.type()).isEqualTo("unit.DisbandUnit");
  }

  // ── unit.CreateUnit ────────────────────────────────────────────

  @Test
  void createUnitAppliesEveryFieldWithInitialSegmentsAtBaseTimestamp() {
    UnitState base = oneUnit();
    UnitState next =
        applied(
            CREATE,
            worldAt(T5, base),
            "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},"
                + "\"member\":80,\"equipment\":{\"炮\":4},\"speed\":3,\"mobilityPerMille\":900,"
                + "\"parent\":\"u-1\"}");

    assertThat(next.units()).containsKey(U2);
    Unit created = next.units().get(U2);
    assertThat(created.name()).isEqualTo("第二连");
    assertThat(created.member()).isEqualTo(80);
    assertThat(created.equipment()).containsOnlyKeys("炮").containsEntry("炮", 4);
    assertThat(created.speed()).isEqualTo(3);
    assertThat(created.mobilityPerMille()).isEqualTo(900);
    assertThat(created.movement()).isEmpty();
    // ★ 初始段时刻 = base 状态时间戳 T5（m2 的靶子）
    assertThat(created.parent().segments()).hasSize(1);
    assertThat(created.parent().segments().get(0).from()).isEqualTo(T5);
    assertThat(created.parent().segments().get(0).value()).contains(SpiFixture.U1);
    assertThat(created.position().segments()).hasSize(1);
    assertThat(created.position().segments().get(0).from()).isEqualTo(T5);
    assertThat(created.position().segments().get(0).value()).contains(SpiFixture.H12);
    // 既存单位原样带过
    assertThat(next.units().get(SpiFixture.U1)).isEqualTo(base.units().get(SpiFixture.U1));
  }

  @Test
  void createUnitWithoutParentLeavesParentEmpty() {
    UnitState next =
        applied(
            CREATE,
            worldAt(T5, oneUnit()),
            "{\"id\":\"u-2\",\"name\":\"独立连\",\"position\":{\"q\":1,\"r\":3},\"member\":10,"
                + "\"equipment\":{},\"speed\":1,\"mobilityPerMille\":100}");
    assertThat(next.units().get(U2).parent().valueAt(T5)).isEmpty();
    assertThat(next.units().get(U2).position().valueAt(T5)).contains(SpiFixture.H13);
  }

  @Test
  void createUnitRejectsDuplicateId() {
    assertThat(
            reason(
                CREATE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"name\":\"重复\",\"position\":{\"q\":1,\"r\":2},\"member\":1,"
                    + "\"equipment\":{},\"speed\":1,\"mobilityPerMille\":1}"))
        .contains("已存在");
  }

  @Test
  void createUnitRejectsNegativeMember() {
    assertThat(
            reason(
                CREATE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-2\",\"name\":\"负员\",\"position\":{\"q\":1,\"r\":2},\"member\":-1,"
                    + "\"equipment\":{},\"speed\":1,\"mobilityPerMille\":1}"))
        .contains("member 必须 ≥ 0");
  }

  @Test
  void createUnitRejectsMissingPosition() {
    assertThat(
            reason(
                CREATE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-2\",\"name\":\"无位\",\"member\":1,\"equipment\":{},\"speed\":1,"
                    + "\"mobilityPerMille\":1}"))
        .contains("position");
  }

  @Test
  void createUnitRejectsBadJson() {
    assertThat(reason(CREATE, worldAt(T5, oneUnit()), "这不是 JSON")).contains("不是合法 JSON");
  }

  // ── unit.ReparentUnit ──────────────────────────────────────────

  @Test
  void reparentUnitAppendsParentSegmentAtBaseTimestamp() {
    UnitState next =
        applied(REPARENT, worldAt(T5, twoIndependent()), "{\"id\":\"u-2\",\"parent\":\"u-1\"}");
    Unit u2 = next.units().get(U2);
    assertThat(u2.parent().segments()).hasSize(2);
    assertThat(u2.parent().valueAt(T5)).contains(SpiFixture.U1);
    assertThat(u2.parent().valueAt(SpiFixture.T0)).isEmpty();
  }

  @Test
  void reparentUnitWithNullParentClearsTheTree() {
    UnitState next =
        applied(REPARENT, worldAt(T5, twoWithSubordinate()), "{\"id\":\"u-2\",\"parent\":null}");
    assertThat(next.units().get(U2).parent().valueAt(T5)).isEmpty();
  }

  @Test
  void reparentUnitRejectsUnknownId() {
    assertThat(reason(REPARENT, worldAt(T5, twoIndependent()), "{\"id\":\"u-404\"}"))
        .contains("单位不存在");
  }

  @Test
  void reparentUnitRejectsCycle() {
    assertThat(
            reason(
                REPARENT, worldAt(T5, twoWithSubordinate()), "{\"id\":\"u-1\",\"parent\":\"u-2\"}"))
        .contains("成环");
  }

  // ── unit.SetStrength ───────────────────────────────────────────

  @Test
  void setStrengthReplacesMemberAndEquipment() {
    UnitState base = oneUnit();
    UnitState next =
        applied(
            SET_STRENGTH, world(base), "{\"id\":\"u-1\",\"member\":40,\"equipment\":{\"炮\":4}}");
    Unit u1 = next.units().get(SpiFixture.U1);
    assertThat(u1.member()).isEqualTo(40);
    // ★ equipment 是整份替换，不是合并（m1 的靶子：忽略载荷装备会保留 {"步枪":50}）
    assertThat(u1.equipment()).containsOnlyKeys("炮").containsEntry("炮", 4);
    assertThat(u1.name()).isEqualTo(base.units().get(SpiFixture.U1).name());
    assertThat(u1.speed()).isEqualTo(base.units().get(SpiFixture.U1).speed());
  }

  @Test
  void setStrengthRejectsUnknownId() {
    assertThat(
            reason(
                SET_STRENGTH, world(oneUnit()), "{\"id\":\"u-404\",\"member\":1,\"equipment\":{}}"))
        .contains("单位不存在");
  }

  @Test
  void setStrengthRejectsNegativeMember() {
    assertThat(
            reason(
                SET_STRENGTH,
                world(oneUnit()),
                "{\"id\":\"u-1\",\"member\":-3,\"equipment\":{\"炮\":4}}"))
        .contains("member 必须 ≥ 0");
  }

  @Test
  void setStrengthRejectsNonIntegerEquipmentValues() {
    assertThat(
            reason(
                SET_STRENGTH,
                world(oneUnit()),
                "{\"id\":\"u-1\",\"member\":1,\"equipment\":{\"炮\":\"many\"}}"))
        .contains("整数");
  }

  // ── unit.PlaceAt ───────────────────────────────────────────────

  @Test
  void placeAtAppendsPositionSegmentAtBaseTimestamp() {
    UnitState base = oneUnit();
    UnitState next =
        applied(PLACE_AT, worldAt(T5, base), "{\"id\":\"u-1\",\"hex\":{\"q\":1,\"r\":2}}");
    Unit u1 = next.units().get(SpiFixture.U1);
    assertThat(u1.position().segments()).hasSize(2);
    assertThat(u1.position().valueAt(T5)).contains(SpiFixture.H12);
    assertThat(u1.position().valueAt(SpiFixture.T0)).contains(SpiFixture.H11);
  }

  @Test
  void placeAtWithNullHexClearsPosition() {
    UnitState next = applied(PLACE_AT, worldAt(T5, oneUnit()), "{\"id\":\"u-1\",\"hex\":null}");
    assertThat(next.units().get(SpiFixture.U1).position().valueAt(T5)).isEmpty();
  }

  @Test
  void placeAtRejectsUnknownId() {
    assertThat(
            reason(
                PLACE_AT, worldAt(T5, oneUnit()), "{\"id\":\"u-404\",\"hex\":{\"q\":1,\"r\":2}}"))
        .contains("单位不存在");
  }

  // ── unit.PlanRoute ─────────────────────────────────────────────

  @Test
  void planRouteStoresMovementWithDepartureSnapshot() {
    UnitState next =
        applied(
            PLAN_ROUTE,
            worldAt(T5, oneUnit()),
            "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}");
    Movement movement = next.units().get(SpiFixture.U1).movement().orElseThrow();
    assertThat(movement.route())
        .isEqualTo(
            new Route(
                List.of(SpiFixture.H11, SpiFixture.H12), List.of(SpiFixture.H11, SpiFixture.H12)));
    assertThat(movement.departedAt()).isEqualTo(T5);
    assertThat(movement.speedAtDeparture()).isEqualTo(2);
    assertThat(movement.mobilityAtDeparture()).isEqualTo(500);
  }

  @Test
  void planRouteRejectsNonAdjacentWaypoints() {
    assertThat(
            reason(
                PLAN_ROUTE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}"))
        .contains("相邻");
  }

  @Test
  void planRouteRejectsTooFewWaypoints() {
    assertThat(
            reason(
                PLAN_ROUTE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1}]}"))
        .contains("至少两个");
  }

  @Test
  void planRouteRejectsStartThatIsNotTheEffectivePosition() {
    assertThat(
            reason(
                PLAN_ROUTE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":2},{\"q\":1,\"r\":1}]}"))
        .contains("起点");
  }

  @Test
  void planRouteRejectsUnknownId() {
    assertThat(
            reason(
                PLAN_ROUTE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-404\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}"))
        .contains("单位不存在");
  }

  // ── unit.CancelRoute ───────────────────────────────────────────

  @Test
  void cancelRouteClearsMovement() {
    UnitState base =
        SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.of(SpiFixture.inFlight())));
    assertThat(base.units().get(SpiFixture.U1).movement()).isPresent();
    UnitState next = applied(CANCEL_ROUTE, world(base), "{\"id\":\"u-1\"}");
    assertThat(next.units().get(SpiFixture.U1).movement()).isEmpty();
  }

  @Test
  void cancelRouteRejectsUnknownId() {
    assertThat(reason(CANCEL_ROUTE, world(oneUnit()), "{\"id\":\"u-404\"}")).contains("单位不存在");
  }

  // ── unit.DisbandUnit ───────────────────────────────────────────

  @Test
  void disbandUnitRemovesTheUnit() {
    UnitState next = applied(DISBAND, world(twoIndependent()), "{\"id\":\"u-2\"}");
    assertThat(next.units()).doesNotContainKey(U2);
    assertThat(next.units()).containsKey(SpiFixture.U1);
  }

  @Test
  void disbandUnitRefusesWhileSubordinateExists() {
    assertThat(reason(DISBAND, world(twoWithSubordinate()), "{\"id\":\"u-1\"}")).contains("下属");
  }

  @Test
  void disbandUnitRejectsUnknownId() {
    assertThat(reason(DISBAND, world(twoIndependent()), "{\"id\":\"u-404\"}")).contains("单位不存在");
  }

  /** 七个 handler 的载荷畸形一律折成拒绝（不逃逸成异常）。 */
  @Test
  void everyHandlerRejectsMalformedPayload() {
    assertThat(reason(REPARENT, worldAt(T5, oneUnit()), "不是 JSON")).contains("不是合法 JSON");
    assertThat(reason(SET_STRENGTH, world(oneUnit()), "[1,2,3]")).contains("JSON 对象");
    assertThat(reason(PLACE_AT, worldAt(T5, oneUnit()), "{\"id\":\"u-1\",\"hex\":\"H12\"}"))
        .contains("hex");
    assertThat(reason(PLAN_ROUTE, worldAt(T5, oneUnit()), "{\"id\":\"u-1\",\"waypoints\":{}}"))
        .contains("waypoints");
    assertThat(reason(CANCEL_ROUTE, world(oneUnit()), "{}")).contains("id");
    assertThat(reason(DISBAND, world(twoIndependent()), "{}")).contains("id");
  }
}
