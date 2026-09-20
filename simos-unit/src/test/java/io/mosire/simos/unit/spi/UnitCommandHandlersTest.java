package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
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
 * 十四个 unit 命令 handler（spec §四 + T3 的三条编制命令 A + T4 的三条编制命令 B）：逐命令验证 happy path（{@code Applied} +
 * 应用之后的 {@code UnitState} 逐值）与关键拒绝。
 *
 * <p>★ 带时刻的命令（CreateUnit / ReparentUnit / PlaceAt / PlanRoute / DisbandUnit / AttachUnit /
 * DetachUnit / SetFormationOffset / ReparentSubtree / SplitFormation / MergeFormation）在**非零 base
 * 时间戳**（{@link #T5}）上跑： 既符合真实推进语义（base 的 anchor 段在 T0），也把"初始段时刻 = base 状态时间戳"钉成可判别的断言（T5 ≠ T0）。
 */
class UnitCommandHandlersTest {

  private static final SimosTimestamp T5 = SimosTimestamp.of(5);
  private static final SimosTimestamp T6 = SimosTimestamp.of(6);
  private static final UnitId U2 = new UnitId("u-2");
  private static final UnitId U3 = new UnitId("u-3");

  private static final CreateUnitHandler CREATE = new CreateUnitHandler();
  private static final ReparentUnitHandler REPARENT = new ReparentUnitHandler();
  private static final SetStrengthHandler SET_STRENGTH = new SetStrengthHandler();
  private static final PlaceAtHandler PLACE_AT = new PlaceAtHandler();
  private static final PlanRouteHandler PLAN_ROUTE = new PlanRouteHandler();
  private static final CancelRouteHandler CANCEL_ROUTE = new CancelRouteHandler();
  private static final DisbandUnitHandler DISBAND = new DisbandUnitHandler();
  private static final SetStatusHandler SET_STATUS = new SetStatusHandler();
  private static final AttachUnitHandler ATTACH = new AttachUnitHandler();
  private static final DetachUnitHandler DETACH = new DetachUnitHandler();
  private static final SetFormationOffsetHandler SET_OFFSET = new SetFormationOffsetHandler();
  private static final ReparentSubtreeHandler REPARENT_SUBTREE = new ReparentSubtreeHandler();
  private static final SplitFormationHandler SPLIT = new SplitFormationHandler();
  private static final MergeFormationHandler MERGE = new MergeFormationHandler();

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

  /** u-2 与 u-3 都挂在空父下（`u-2` 是根、`u-3` 挂 `u-2`），且**两者 `attached=false`**：attach 级联的基线。 */
  private static UnitState detachedPair() {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()),
        unit("u-2", Optional.empty(), Optional.of(SpiFixture.H12), false),
        unit("u-3", Optional.of("u-2"), Optional.empty(), false));
  }

  /** u-2 挂在 u-1 下、u-3 挂在 u-2 下，三者都 `attached=true`（detach 只节点与偏移的基线）。 */
  private static UnitState attachedLine() {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()),
        unit("u-2", Optional.of("u-1"), Optional.empty(), true),
        unit("u-3", Optional.of("u-2"), Optional.empty(), true));
  }

  /**
   * u-2（根、自身位置 H12、`attached=false`）与 u-3（挂 u-2 下、**自身位置也是 H12**、`attached` 由形参给）：合体基线的 "同格"。
   *
   * <p>★ u-3 **必须有自己的位置**：detached 且无自身位置 ⇒ `effectivePosition` 为空（P2 的第五情形），拆下来之后再没有位置可判
   * "同格"，拆→合往返根本走不通。
   */
  private static UnitState sameHexLine(boolean childAttached) {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()),
        unit("u-2", Optional.empty(), Optional.of(SpiFixture.H12), false),
        unit("u-3", Optional.of("u-2"), Optional.of(SpiFixture.H12), childAttached));
  }

  private static Unit unit(String id, Optional<String> parent, Optional<HexCoord> position) {
    return unit(id, parent, position, true);
  }

  /** 同上，`attached` 逐节点给（T3：缺省 `true` 会把"级联"整个掩盖掉）。 */
  private static Unit unit(
      String id, Optional<String> parent, Optional<HexCoord> position, boolean attached) {
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
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, attached)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.<RelativeOffset>empty())),
            List.of(),
            null),
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

  /** 断言 Applied，**原样返回**变更集（不 apply）——往返测试要比对"命令边界给出的那份字节"。 */
  private static UnitChangeSet changeSetOf(
      CommandHandler handler, SimulationState world, String payload) {
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome).as("期望 Applied 而不是 %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
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
    assertThat(SET_STATUS.type()).isEqualTo("unit.SetStatus");
    assertThat(ATTACH.type()).isEqualTo("unit.AttachUnit");
    assertThat(DETACH.type()).isEqualTo("unit.DetachUnit");
    assertThat(SET_OFFSET.type()).isEqualTo("unit.SetFormationOffset");
    assertThat(REPARENT_SUBTREE.type()).isEqualTo("unit.ReparentSubtree");
    assertThat(SPLIT.type()).isEqualTo("unit.SplitFormation");
    assertThat(MERGE.type()).isEqualTo("unit.MergeFormation");
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
    // ★ T2：不传 status ⇒ 缺省 MOVING
    assertThat(created.status()).isEqualTo(UnitStatus.MOVING);
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

  /** ★ T2：可选 status —— 传 "RESTING" ⇒ RESTING。 */
  @Test
  void createUnitHonoursAnExplicitStatus() {
    UnitState next =
        applied(
            CREATE,
            worldAt(T5, oneUnit()),
            "{\"id\":\"u-2\",\"name\":\"休整连\",\"position\":{\"q\":1,\"r\":2},\"member\":10,"
                + "\"equipment\":{},\"speed\":1,\"mobilityPerMille\":100,\"status\":\"RESTING\"}");
    assertThat(next.units().get(U2).status()).isEqualTo(UnitStatus.RESTING);
  }

  /** ★ T2：未知 status 串 ⇒ 拒。 */
  @Test
  void createUnitRejectsAnUnknownStatus() {
    assertThat(
            reason(
                CREATE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-2\",\"name\":\"幽灵连\",\"position\":{\"q\":1,\"r\":2},\"member\":10,"
                    + "\"equipment\":{},\"speed\":1,\"mobilityPerMille\":100,\"status\":\"SLEEPING\"}"))
        .contains("不是合法状态");
  }

  // ── unit.SetStatus ─────────────────────────────────────────────

  @Test
  void setStatusAppliesTheNewStatus() {
    UnitState next =
        applied(SET_STATUS, world(oneUnit()), "{\"id\":\"u-1\",\"status\":\"ENGAGED\"}");
    assertThat(next.units().get(SpiFixture.U1).status()).isEqualTo(UnitStatus.ENGAGED);
  }

  @Test
  void setStatusRejectsAnUnknownStatus() {
    assertThat(reason(SET_STATUS, world(oneUnit()), "{\"id\":\"u-1\",\"status\":\"X\"}"))
        .contains("不是合法状态");
  }

  @Test
  void setStatusRejectsUnknownId() {
    assertThat(reason(SET_STATUS, world(oneUnit()), "{\"id\":\"u-404\",\"status\":\"MOVING\"}"))
        .contains("单位不存在");
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

  // ── unit.AttachUnit / unit.DetachUnit（T3 / spec §一.3 / P3） ────

  /** ★ 判据（P3）：attach 级联到**全部后代**；只有 `id` 换父；子树外一字不变。 */
  @Test
  void attachUnitCascadesToTheWholeSubtree() {
    UnitState base = detachedPair();
    UnitState next = applied(ATTACH, worldAt(T5, base), "{\"id\":\"u-2\",\"parent\":\"u-1\"}");
    assertThat(next.units().get(U2).parent().valueAt(T5)).contains(SpiFixture.U1);
    assertThat(next.units().get(U2).attached().valueAt(T5)).isTrue();
    assertThat(next.units().get(U3).attached().valueAt(T5)).as("级联到后代").isTrue();
    assertThat(next.units().get(U3).parent().valueAt(T5)).as("后代父不动").contains(U2);
    assertThat(next.units().get(U3).attached().valueAt(SpiFixture.T0)).as("T0 仍是旧值").isFalse();
    assertThat(next.units().get(SpiFixture.U1))
        .as("子树外的单位一字不变")
        .isEqualTo(base.units().get(SpiFixture.U1));
  }

  @Test
  void attachUnitRejectsACycleUnknownUnitsAndAMissingParent() {
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{\"id\":\"u-2\",\"parent\":\"u-3\"}"))
        .as("u-3 是 u-2 的后代")
        .contains("子树");
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{\"id\":\"u-2\",\"parent\":\"u-2\"}"))
        .as("自身也是子树的一员")
        .contains("子树");
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{\"id\":\"u-404\",\"parent\":\"u-1\"}"))
        .contains("单位不存在");
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{\"id\":\"u-2\",\"parent\":\"u-404\"}"))
        .contains("父单位不存在");
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{\"id\":\"u-2\"}"))
        .as("parent 必填")
        .contains("parent");
  }

  /** ★ 判据（P3）：detach **只节点**——子节点不动；`parent` 也不动。 */
  @Test
  void detachUnitTouchesOnlyTheNode() {
    UnitState base = attachedLine();
    UnitState next = applied(DETACH, worldAt(T5, base), "{\"id\":\"u-2\"}");
    assertThat(next.units().get(U2).attached().valueAt(T5)).as("u-2").isFalse();
    assertThat(next.units().get(U3).attached().valueAt(T5)).as("u-3（子节点）不动").isTrue();
    assertThat(next.units().get(U2).parent().valueAt(T5)).as("detach 不改父").contains(SpiFixture.U1);
    assertThat(next.units().get(U3)).as("子节点一字不变").isEqualTo(base.units().get(U3));
  }

  @Test
  void detachUnitRejectsARootAndUnknownUnits() {
    assertThat(reason(DETACH, worldAt(T5, attachedLine()), "{\"id\":\"u-1\"}")).contains("已是根");
    assertThat(reason(DETACH, worldAt(T5, attachedLine()), "{\"id\":\"u-404\"}")).contains("单位不存在");
  }

  // ── unit.SetFormationOffset（T3 / spec §一.3 / P2） ──────────────

  /** ★ 判据（P2）：设偏移 ⇒ 有效位置 = 父位 ⊕ 偏移；两分量皆缺 ⇒ 清偏移 ⇒ 回父位。 */
  @Test
  void setFormationOffsetAppliesAndClears() {
    UnitState base = attachedLine();
    UnitState shifted =
        applied(SET_OFFSET, worldAt(T5, base), "{\"id\":\"u-3\",\"dq\":1,\"dr\":0}");
    assertThat(shifted.units().get(U3).offset().valueAt(T5)).contains(new RelativeOffset(1, 0));
    assertThat(shifted.effectivePosition(U3, T5))
        .as("u-3 无自身位置 ⇒ 父位 ⊕ 偏移")
        .contains(new HexCoord(2, 1));

    UnitState cleared = applied(SET_OFFSET, worldAt(T6, shifted), "{\"id\":\"u-3\"}");
    assertThat(cleared.units().get(U3).offset().valueAt(T6)).as("两者皆缺 ⇒ 清").isEmpty();
    assertThat(cleared.effectivePosition(U3, T6)).as("清偏移 ⇒ 回父位").contains(SpiFixture.H11);
    assertThat(cleared.effectivePosition(U3, T5)).as("T5 的历史值不受影响").contains(new HexCoord(2, 1));
  }

  /** ★ 只给一个分量 ⇒ 另一个按 0 补（部分更新，不是清）。 */
  @Test
  void setFormationOffsetAcceptsAPartialComponent() {
    UnitState next = applied(SET_OFFSET, worldAt(T5, attachedLine()), "{\"id\":\"u-3\",\"dr\":-2}");
    assertThat(next.units().get(U3).offset().valueAt(T5)).contains(new RelativeOffset(0, -2));
  }

  @Test
  void setFormationOffsetRejectsBadShapesAndUnknownIds() {
    assertThat(reason(SET_OFFSET, worldAt(T5, attachedLine()), "{\"id\":\"u-3\",\"dq\":\"1\"}"))
        .contains("整数");
    assertThat(reason(SET_OFFSET, worldAt(T5, attachedLine()), "{\"id\":\"u-3\",\"dr\":1.5}"))
        .contains("整数");
    assertThat(reason(SET_OFFSET, worldAt(T5, attachedLine()), "{\"id\":\"u-404\",\"dq\":1}"))
        .contains("单位不存在");
  }

  /** 十四个 handler 的载荷畸形一律折成拒绝（不逃逸成异常）。 */
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
    assertThat(reason(SET_STATUS, world(oneUnit()), "{\"id\":\"u-1\"}")).contains("status");
    assertThat(reason(ATTACH, worldAt(T5, detachedPair()), "{}")).contains("id");
    assertThat(reason(DETACH, worldAt(T5, attachedLine()), "{}")).contains("id");
    assertThat(reason(SET_OFFSET, worldAt(T5, attachedLine()), "[1,2,3]")).contains("JSON 对象");
    assertThat(reason(REPARENT_SUBTREE, worldAt(T5, detachedPair()), "{}")).contains("rootId");
    assertThat(reason(SPLIT, worldAt(T5, attachedLine()), "{}")).contains("rootId");
    assertThat(
            reason(SPLIT, worldAt(T5, attachedLine()), "{\"rootId\":\"u-2\",\"subUnitIds\":[1]}"))
        .as("数组元素必须是字符串")
        .contains("subUnitIds");
    assertThat(reason(SPLIT, worldAt(T5, attachedLine()), "{\"rootId\":\"u-2\",\"subUnitIds\":{}}"))
        .as("必须是数组")
        .contains("subUnitIds");
    assertThat(reason(MERGE, worldAt(T5, sameHexLine(false)), "{\"childId\":\"u-3\"}"))
        .contains("parentId");
  }

  // ── unit.ReparentSubtree（T4 / spec §一.3 / §一.5 表 / P4） ──────

  /**
   * ★ 判据（spec §一.3 的机制列 + §八 #6 + §一.5 表）：**整树迁移**——`u-2` 换到 `u-1` 下，**u-3 在同一刻也被重新挂载**。
   *
   * <p>★ **值**和**段**都要断言：只断言值时，"只改 root"的变异体与参照实现**逐值相同**（u-3 的父本来就是 u-2），杀不掉它；段数
   * （`hasSize(2)`）才是它的杀点。
   */
  @Test
  void reparentSubtreeRemountsTheWholeSubtree() {
    UnitState base = detachedPair();
    UnitState next =
        applied(REPARENT_SUBTREE, worldAt(T5, base), "{\"rootId\":\"u-2\",\"parent\":\"u-1\"}");

    assertThat(next.units().get(U2).parent().valueAt(T5)).contains(SpiFixture.U1);
    assertThat(next.units().get(U2).parent().segments()).as("root 追加一段").hasSize(2);
    assertThat(next.units().get(U3).parent().valueAt(T5)).as("后代不改挂（反扁平化）").contains(U2);
    assertThat(next.units().get(U3).parent().segments())
        .as("★ 后代在同一刻也落段；只改 root ⇒ 这里是 1")
        .hasSize(2);
    assertThat(next.units().get(U3).parent().valueAt(SpiFixture.T0)).as("T0 的历史值不动").contains(U2);
    assertThat(next.units().get(SpiFixture.U1))
        .as("子树外一字不变")
        .isEqualTo(base.units().get(SpiFixture.U1));
  }

  /** ★ 判据（P4 / spec §一.3 的 `parent?`）：缺省或显式 `null` ⇒ **提升为根**（本操作面唯一的降根路径）。 */
  @Test
  void reparentSubtreeWithoutAParentPromotesTheSubtreeToRoot() {
    UnitState omitted =
        applied(REPARENT_SUBTREE, worldAt(T5, attachedLine()), "{\"rootId\":\"u-2\"}");
    assertThat(omitted.units().get(U2).parent().valueAt(T5)).isEmpty();
    assertThat(omitted.units().get(U2).parent().segments()).as("追加段，不是改写历史").hasSize(2);
    assertThat(omitted.units().get(U3).parent().valueAt(T5)).as("后代仍挂 u-2").contains(U2);

    UnitState nulled =
        applied(
            REPARENT_SUBTREE, worldAt(T5, attachedLine()), "{\"rootId\":\"u-2\",\"parent\":null}");
    assertThat(nulled.units().get(U2).parent().valueAt(T5)).as("显式 null 同缺省").isEmpty();
  }

  @Test
  void reparentSubtreeRejectsCyclesAndUnknownUnits() {
    SimulationState before = worldAt(T5, detachedPair());
    assertThat(reason(REPARENT_SUBTREE, before, "{\"rootId\":\"u-2\",\"parent\":\"u-3\"}"))
        .as("u-3 是 u-2 的后代")
        .contains("子树");
    assertThat(reason(REPARENT_SUBTREE, before, "{\"rootId\":\"u-2\",\"parent\":\"u-2\"}"))
        .as("含自身")
        .contains("子树");
    assertThat(reason(REPARENT_SUBTREE, before, "{\"rootId\":\"u-404\",\"parent\":\"u-1\"}"))
        .contains("单位不存在");
    assertThat(reason(REPARENT_SUBTREE, before, "{\"rootId\":\"u-2\",\"parent\":\"u-404\"}"))
        .contains("父单位不存在");
    assertThat(unitSlice(before)).as("拒绝 ⇒ 世界里的状态一字不变").isEqualTo(detachedPair());
  }

  // ── unit.SplitFormation（T4 / spec §一.3 / §一.5 表 / P3） ──────

  /** ★ 判据（P3 的不对称 + spec §一.5 表）：拆**只节点**——被拆的 `u-2` 翻 `attached`，它的子节点 `u-3` 一动不动。 */
  @Test
  void splitFormationDetachesTheNamedNodeOnly() {
    UnitState base = attachedLine();
    UnitState next =
        applied(SPLIT, worldAt(T5, base), "{\"rootId\":\"u-1\",\"subUnitIds\":[\"u-2\"]}");

    assertThat(next.units().get(U2).attached().valueAt(T5)).isFalse();
    assertThat(next.units().get(U2).parent().valueAt(T5))
        .as("拆不改父（父是合体的同格判据之一，也不该被拆掉）")
        .contains(SpiFixture.U1);
    assertThat(next.units().get(U2).attached().valueAt(SpiFixture.T0)).as("追加段，T0 保旧值").isTrue();
    assertThat(next.units().get(U3).attached().valueAt(T5)).as("P3：子节点不动").isTrue();
    assertThat(next.units().get(U3)).as("子节点一字不变").isEqualTo(base.units().get(U3));

    UnitState selfTarget =
        applied(SPLIT, worldAt(T5, base), "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-2\"]}");
    assertThat(selfTarget.units().get(U2).attached().valueAt(T5))
        .as("root 自己在自己的子树内：合法，能不能拆由 detach 的既有语义判")
        .isFalse();

    UnitState dup =
        applied(SPLIT, worldAt(T5, base), "{\"rootId\":\"u-1\",\"subUnitIds\":[\"u-2\",\"u-2\"]}");
    assertThat(dup.units().get(U2).attached().segments()).as("重复项按首现序去重：同一刻只追加一段").hasSize(2);
  }

  /** ★ 判据（spec §一.5 表的**两条独立拒绝**）：不存在 ⇒ "单位不存在"；存在但不在 root 子树内 ⇒ "不在…子树内"。 */
  @Test
  void splitFormationRejectsTargetsOutsideTheSubtree() {
    SimulationState world = worldAt(T5, attachedLine());
    assertThat(reason(SPLIT, world, "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-1\"]}"))
        .as("u-1 是 u-2 的父，不在它的子树内")
        .contains("子树");
    assertThat(reason(SPLIT, world, "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-404\"]}"))
        .contains("单位不存在");
    assertThat(reason(SPLIT, world, "{\"rootId\":\"u-404\",\"subUnitIds\":[\"u-3\"]}"))
        .contains("单位不存在");
    assertThat(reason(SPLIT, world, "{\"rootId\":\"u-1\",\"subUnitIds\":[\"u-1\"]}"))
        .as("root 自己且已是根 ⇒ 用 detach 的既有理由，不新增守卫")
        .contains("已是根");
    assertThat(reason(SPLIT, world, "{\"rootId\":\"u-1\",\"subUnitIds\":[]}")).contains("不得为空");
    assertThat(unitSlice(world)).as("拒绝 ⇒ 一字不变").isEqualTo(attachedLine());
  }

  // ── unit.MergeFormation（T4 / spec §一.4 / §一.5 表 / P9） ──────

  /**
   * ★★ 判据（spec §一.4）：**同格**与**正在移动**是两个**各自独立**的前置条件——同格但静止 ⇒ 拒；移动但不同格 ⇒ 拒； 两条都过 ⇒
   * attach（`child.parent == parentId` 且 `attached == true`）。
   */
  @Test
  void mergeFormationRequiresTheSameHexAndTheMovingStatus() {
    UnitState base = sameHexLine(false);
    UnitState next =
        applied(MERGE, worldAt(T5, base), "{\"childId\":\"u-3\",\"parentId\":\"u-2\"}");
    assertThat(next.units().get(U3).parent().valueAt(T5)).contains(U2);
    assertThat(next.units().get(U3).parent().segments()).as("重挂 ⇒ 追加段").hasSize(2);
    assertThat(next.units().get(U3).attached().valueAt(T5)).as("合体 = 重新 attach").isTrue();
    assertThat(next.units().get(U3).attached().valueAt(SpiFixture.T0)).as("追加段，不是覆写").isFalse();

    // 不同格（两者都在 MOVING ⇒ 能拒的理由只剩位置）
    assertThat(
            reason(
                MERGE,
                worldAt(T5, twoWithSubordinate()),
                "{\"childId\":\"u-2\",\"parentId\":\"u-1\"}"))
        .as("u-2 有自己的位置 H12，u-1 在 H11")
        .contains("同格");

    // 位置不可确定（u-3 detached 且无自身位置 ⇒ effectivePosition 为空）同样拒
    assertThat(
            reason(
                MERGE, worldAt(T5, detachedPair()), "{\"childId\":\"u-3\",\"parentId\":\"u-2\"}"))
        .contains("同格");

    // 同格、但经**真命令**改成静止 ⇒ 另一条独立地拒（不是"同格"那一条的复述）
    UnitState resting =
        applied(
            SET_STATUS, worldAt(T5, sameHexLine(false)), "{\"id\":\"u-3\",\"status\":\"RESTING\"}");
    assertThat(reason(MERGE, worldAt(T5, resting), "{\"childId\":\"u-3\",\"parentId\":\"u-2\"}"))
        .as("位置相同 ⇒ 这次能拒的理由只剩状态")
        .contains("MOVING");
  }

  /**
   * ★★ 判据（spec §一.4 / §八 E2 的核心场景）：**拆 → 合往返**。`unit.DetachUnit` 只翻 `attached`、**不碰 `parent`**，
   * 所以合体时 `u-3` 的父**本来就是** `u-2`——"已是父 ⇒ 拒"这条守卫一旦有人补上（T3 已裁定 `AttachUnit` 不得判，T4 同）， 本用例当场红。
   */
  @Test
  void splittingThenMergingRoundTripsThroughTheCommandBoundary() {
    UnitState base = sameHexLine(true);

    UnitState split =
        applied(SPLIT, worldAt(T5, base), "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-3\"]}");
    assertThat(split.units().get(U3).attached().valueAt(T5)).as("拆下来").isFalse();
    assertThat(split.effectivePosition(U3, T5)).as("拆下来仍有自己的位置（同格的前提还在）").contains(SpiFixture.H12);

    UnitState back =
        applied(MERGE, worldAt(T6, split), "{\"childId\":\"u-3\",\"parentId\":\"u-2\"}");
    assertThat(back.units().get(U3).parent().valueAt(T6)).as("★ 同一个父，照样重挂").contains(U2);
    assertThat(back.units().get(U3).parent().segments()).as("重挂 ⇒ 再追加一段").hasSize(2);
    assertThat(back.units().get(U3).attached().valueAt(T6)).as("往返回到已归属").isTrue();
    assertThat(back.units().get(U3).attached().valueAt(T5)).as("拆过的历史留着").isFalse();
    assertThat(back.effectivePosition(U3, T6)).contains(SpiFixture.H12);
  }

  /**
   * ★ 判据（铁律 5 / spec §一.3）：三条命令都**经 `between` 产变更集**——把命令边界交出去的那份变更集 `apply` 回 base，
   * 逐字段重建出**域操作算出的目标**（不是"重跑一遍 op 再比"：比的是命令边界真正给出的差分）。
   */
  @Test
  void allThreeFormationCommandsProduceChangeSetsThatRebuildTheTarget() {
    UnitState base = sameHexLine(true);
    SimulationState world = worldAt(T5, base);

    UnitChangeSet reparent =
        changeSetOf(REPARENT_SUBTREE, world, "{\"rootId\":\"u-3\",\"parent\":\"u-2\"}");
    assertThat(reparent.isEmpty()).as("同父重挂也要落段 ⇒ 差分非空").isFalse();
    assertThat(UnitChangeSet.apply(reparent, base))
        .isEqualTo(UnitOperations.reparentSubtree(base, U3, Optional.of(U2), T5));

    UnitChangeSet split =
        changeSetOf(SPLIT, world, "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-3\"]}");
    assertThat(split.isEmpty()).isFalse();
    assertThat(UnitChangeSet.apply(split, base))
        .isEqualTo(UnitOperations.splitFormation(base, U2, List.of(U3), T5));

    UnitChangeSet merge = changeSetOf(MERGE, world, "{\"childId\":\"u-3\",\"parentId\":\"u-2\"}");
    assertThat(merge.isEmpty()).as("值没变但落了段 ⇒ 差分仍非空").isFalse();
    assertThat(UnitChangeSet.apply(merge, base))
        .isEqualTo(UnitOperations.mergeFormation(base, U3, U2, T5));
  }
}
