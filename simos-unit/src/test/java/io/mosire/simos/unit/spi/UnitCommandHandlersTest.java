package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 十八个 unit 命令 handler（spec §四 + T3 的三条编制命令 A + T4 的三条编制命令 B + T5 的两条命令链命令 + T6 的 {@code
 * PlanSparseRoute} + T7 的 {@code SetRejoinTarget}）：逐命令验证 happy path（{@code Applied} + 应用之后的 {@code
 * UnitState} 逐值）与关键拒绝。
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
  private static final UnitId U4 = new UnitId("u-4");
  private static final CommandChainId CHAIN_1 = new CommandChainId("c-1");
  private static final CommandChainId CHAIN_2 = new CommandChainId("c-2");
  private static final CommandChainId CHAIN_3 = new CommandChainId("c-3");

  private static final CreateUnitHandler CREATE = new CreateUnitHandler();
  private static final ReparentUnitHandler REPARENT = new ReparentUnitHandler();
  private static final SetStrengthHandler SET_STRENGTH = new SetStrengthHandler();
  private static final PlaceAtHandler PLACE_AT = new PlaceAtHandler();
  private static final PlanRouteHandler PLAN_ROUTE = new PlanRouteHandler();

  /** ★ 裁定 U3：成本由装配注入——测试与 `Shell` 传同一个 `TerrainMovementCost.INSTANCE`（同源）。 */
  private static final PlanSparseRouteHandler PLAN_SPARSE =
      new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE);

  private static final CancelRouteHandler CANCEL_ROUTE = new CancelRouteHandler();
  private static final DisbandUnitHandler DISBAND = new DisbandUnitHandler();
  private static final SetStatusHandler SET_STATUS = new SetStatusHandler();
  private static final AttachUnitHandler ATTACH = new AttachUnitHandler();
  private static final DetachUnitHandler DETACH = new DetachUnitHandler();
  private static final SetFormationOffsetHandler SET_OFFSET = new SetFormationOffsetHandler();
  private static final ReparentSubtreeHandler REPARENT_SUBTREE = new ReparentSubtreeHandler();
  private static final SplitFormationHandler SPLIT = new SplitFormationHandler();
  private static final MergeFormationHandler MERGE = new MergeFormationHandler();
  private static final CreateCommandChainHandler CREATE_CHAIN = new CreateCommandChainHandler();
  private static final UpdateCommandChainHandler UPDATE_CHAIN = new UpdateCommandChainHandler();
  private static final SetRejoinTargetHandler SET_REJOIN_TARGET = new SetRejoinTargetHandler();

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
    assertThat(CREATE_CHAIN.type()).isEqualTo("unit.CreateCommandChain");
    assertThat(UPDATE_CHAIN.type()).isEqualTo("unit.UpdateCommandChain");
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

  /** 十六个 handler 的载荷畸形一律折成拒绝（不逃逸成异常）。 */
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
        .contains("元素必须是非空字符串");
    assertThat(reason(SPLIT, worldAt(T5, attachedLine()), "{\"rootId\":\"u-2\",\"subUnitIds\":{}}"))
        .as("必须是数组")
        .contains("必须是 [字符串…] 数组");
    assertThat(reason(MERGE, worldAt(T5, sameHexLine(false)), "{\"childId\":\"u-3\"}"))
        .contains("parentId");
    assertThat(reason(CREATE_CHAIN, worldAt(T5, oneUnit()), "{}")).contains("chainId");
    assertThat(reason(UPDATE_CHAIN, worldAt(T5, oneUnit()), "[1,2,3]")).contains("JSON 对象");
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

  // ── 命令链（T5 / spec §一.2 / §五.2 / §五.3 / P11） ──────────────

  /**
   * u-1…u-4 四个无父单位 + 两条链：c-1（commander `u-1`，成员 `{u-1,u-2}`）、c-2（commander `u-2`，成员 `{u-2,u-3}`） ⇒
   * `u-2` **多属**（在 c-1 里是成员、在 c-2 里是 commander），`u-4` **不在任何链里**（解散对照）。`LinkedHashMap` 保序遍历序 ⇒
   * 拒绝消息里报的是哪一条链可判。
   */
  private static UnitState twoChains() {
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(CHAIN_1, new CommandChain(CHAIN_1, "第一链", SpiFixture.U1, Set.of(SpiFixture.U1, U2)));
    chains.put(CHAIN_2, new CommandChain(CHAIN_2, "第二链", U2, Set.of(U2, U3)));
    return SpiFixture.unitState(
            SpiFixture.unitWithMovement(Optional.empty()),
            unit("u-2", Optional.empty(), Optional.of(SpiFixture.H12)),
            unit("u-3", Optional.empty(), Optional.of(SpiFixture.H13)),
            unit("u-4", Optional.empty(), Optional.of(SpiFixture.H11)))
        .withCommandChains(chains);
  }

  /**
   * ★ 判据（spec §五.3 第 1 条）：建链 happy path + 三条拒绝**分属三层**（链 id 已在 / 引用不存在 / `commander ∉ members` 由
   * `CommandChain` 构造期给）。
   */
  @Test
  void createCommandChainAppliesAndRejectsDuplicatesAndDanglingUnits() {
    UnitState base =
        SpiFixture.unitState(
            SpiFixture.unitWithMovement(Optional.empty()),
            unit("u-2", Optional.empty(), Optional.of(SpiFixture.H12)));
    SimulationState world = worldAt(T5, base);

    UnitState next =
        applied(
            CREATE_CHAIN,
            world,
            "{\"chainId\":\"c-1\",\"name\":\"第一链\",\"commander\":\"u-1\","
                + "\"members\":[\"u-1\",\"u-2\"]}");
    assertThat(next.commandChains()).containsOnlyKeys(CHAIN_1);
    assertThat(next.commandChains().get(CHAIN_1).name()).isEqualTo("第一链");
    assertThat(next.commandChains().get(CHAIN_1).commander()).isEqualTo(SpiFixture.U1);
    assertThat(next.commandChains().get(CHAIN_1).members())
        .containsExactlyInAnyOrder(SpiFixture.U1, U2);
    assertThat(next.units()).as("建链不动 units").isEqualTo(base.units());

    assertThat(
            reason(
                CREATE_CHAIN,
                worldAt(T5, twoChains()),
                "{\"chainId\":\"c-1\",\"name\":\"又来一条\",\"commander\":\"u-1\","
                    + "\"members\":[\"u-1\"]}"))
        .as("链 id 已在")
        .contains("已存在")
        .contains("c-1");
    assertThat(
            reason(
                CREATE_CHAIN,
                world,
                "{\"chainId\":\"c-1\",\"name\":\"幽灵链\",\"commander\":\"u-404\","
                    + "\"members\":[\"u-404\"]}"))
        .as("commander 不在 units 里")
        .contains("commander 不存在")
        .contains("u-404");
    assertThat(
            reason(
                CREATE_CHAIN,
                world,
                "{\"chainId\":\"c-1\",\"name\":\"幽灵成员\",\"commander\":\"u-1\","
                    + "\"members\":[\"u-1\",\"u-404\"]}"))
        .as("成员不在 units 里")
        .contains("成员不存在")
        .contains("u-404");
    assertThat(
            reason(
                CREATE_CHAIN,
                world,
                "{\"chainId\":\"c-1\",\"name\":\"司令不在队里\",\"commander\":\"u-1\","
                    + "\"members\":[\"u-2\"]}"))
        .as("★ commander ∉ members：由 CommandChain 构造期给（op 层不重复实现这条检查）")
        .contains("commander 必须是 members 之一");
    assertThat(
            reason(
                CREATE_CHAIN,
                world,
                "{\"chainId\":\"c-1\",\"name\":\"x\",\"commander\":\"u-1\",\"members\":{}}"))
        .as("members 形状")
        .contains("必须是 [字符串…] 数组");
    assertThat(
            reason(
                CREATE_CHAIN,
                world,
                "{\"chainId\":\"c-1\",\"name\":\"x\",\"commander\":\"u-1\",\"members\":[1]}"))
        .as("members 元素类型")
        .contains("元素必须是非空字符串");
    assertThat(
            reason(
                CREATE_CHAIN, world, "{\"chainId\":\"c-1\",\"name\":\"x\",\"commander\":\"u-1\"}"))
        .as("members 缺失（四字段全必填）")
        .contains("members");
  }

  /**
   * ★★ 判据（spec §五.3 第 2/3 条）：`UpdateCommandChain` 的未给字段**不动**（**不是清空**）——三个字段各自独立，`null`
   * 与缺失同义；三缺省合法（空转）。
   */
  @Test
  void updateCommandChainTouchesOnlyTheGivenFields() {
    SimulationState world = worldAt(T5, twoChains());

    UnitState renamed = applied(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"name\":\"改过的名字\"}");
    assertThat(renamed.commandChains().get(CHAIN_1).name()).isEqualTo("改过的名字");
    assertThat(renamed.commandChains().get(CHAIN_1).commander())
        .as("未给 commander ⇒ 不动")
        .isEqualTo(SpiFixture.U1);
    assertThat(renamed.commandChains().get(CHAIN_1).members())
        .as("★ 未给 members ⇒ 不动，不是清空")
        .containsExactlyInAnyOrder(SpiFixture.U1, U2);
    assertThat(renamed.commandChains().get(CHAIN_2))
        .as("别的链一字不变")
        .isEqualTo(twoChains().commandChains().get(CHAIN_2));

    UnitState promoted =
        applied(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"commander\":\"u-2\"}");
    assertThat(promoted.commandChains().get(CHAIN_1).commander()).isEqualTo(U2);
    assertThat(promoted.commandChains().get(CHAIN_1).members())
        .as("只给 commander：members 是既有那组")
        .containsExactlyInAnyOrder(SpiFixture.U1, U2);
    assertThat(promoted.commandChains().get(CHAIN_1).name()).as("名字没被顺手清掉").isEqualTo("第一链");

    UnitState blanked =
        applied(
            UPDATE_CHAIN,
            world,
            "{\"chainId\":\"c-1\",\"name\":null,\"commander\":null,\"members\":null}");
    assertThat(blanked.commandChains())
        .as("★ 三个都显式 null ⇒ 一个都不动（与缺失同义）")
        .isEqualTo(twoChains().commandChains());

    UnitState regiven =
        applied(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"members\":[\"u-1\",\"u-3\"]}");
    assertThat(regiven.commandChains().get(CHAIN_1).members())
        .containsExactlyInAnyOrder(SpiFixture.U1, U3);
    assertThat(regiven.commandChains().get(CHAIN_1).commander())
        .as("未给 commander ⇒ 仍是 u-1，它落在新 members 里 ⇒ 放行")
        .isEqualTo(SpiFixture.U1);

    UnitState idle = applied(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\"}");
    assertThat(idle.commandChains()).as("三缺省 ⇒ 合法空转").isEqualTo(twoChains().commandChains());
  }

  /** ★ 判据（spec §五.3 第 2/3 条的另一半）：四条拒绝——链不存在 / 生效 commander 被挤出去 / 引用不存在 / 形状不对。 */
  @Test
  void updateCommandChainRejectsUnknownChainsAndCommandersOutsideTheEffectiveMembers() {
    SimulationState world = worldAt(T5, twoChains());

    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-404\",\"name\":\"改名\"}"))
        .as("改一条不存在的链是坏命令，不是顺手建一条")
        .contains("链不存在")
        .contains("c-404");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"members\":[\"u-2\"]}"))
        .as("★ 只给 members：生效 commander 仍是 u-1（链上原有），它被挤出去了")
        .contains("不在 members 内")
        .contains("c-1");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"commander\":\"u-3\"}"))
        .as("★ 只给 commander：u-3 存在，但不在既有 members 里")
        .contains("不在 members 内");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"commander\":\"u-404\"}"))
        .as("新 commander 不存在（域层消息，与载荷层刻意不同）")
        .contains("commander 不存在")
        .contains("u-404");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"members\":[\"u-1\",\"u-404\"]}"))
        .as("新成员不存在")
        .contains("成员不存在")
        .contains("u-404");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"members\":{}}"))
        .as("★ members 形状：T5 新增的 optionalTextArray 自己的消息（与 requireTextArray 刻意不同）")
        .contains("必须是 [字符串…] 数组或 null");
    assertThat(reason(UPDATE_CHAIN, world, "{\"chainId\":\"c-1\",\"members\":[1]}"))
        .as("members 元素类型")
        .contains("元素必须是非空字符串");
  }

  /**
   * ★★ 判据（spec §一.2 不变量 2 + 裁定 T5-U2）：**链上单位的解散在命令边界被拒**（两个方向都拒、理由带链 id 与"先改链"）；
   * **链外单位照样能解散**，且**链经变更集往返逐值活下来**。
   *
   * <p>★ 第二半是本用例真正钉的东西：`applied` 走的是 `UnitChangeSet.apply(between(base, target), base)`——若
   * `disband` 改回 `new UnitState(next)`（1 参兼容构造器），`between` 会看到链被删、`apply` 之后链全没，这里是 **值**红而非异常。
   */
  @Test
  void disbandRejectsChainedUnitsAndKeepsTheChainsForChainFreeOnes() {
    SimulationState world = worldAt(T5, twoChains());

    assertThat(reason(DISBAND, world, "{\"id\":\"u-1\"}"))
        .as("c-1 的 commander")
        .contains("c-1")
        .contains("commander")
        .contains("先改链");
    assertThat(reason(DISBAND, world, "{\"id\":\"u-2\"}"))
        .as("多属：先撞 c-1 的成员分支")
        .contains("c-1")
        .contains("成员")
        .contains("先改链");
    assertThat(reason(DISBAND, world, "{\"id\":\"u-3\"}"))
        .as("c-2 的成员")
        .contains("c-2")
        .contains("成员")
        .contains("先改链");

    UnitState next = applied(DISBAND, world, "{\"id\":\"u-4\"}");
    assertThat(next.units()).as("链外的 u-4 真的没了").doesNotContainKey(U4);
    assertThat(next.commandChains())
        .as("★ T5-U2：经命令边界的变更集往返，两条链逐值活下来")
        .isEqualTo(twoChains().commandChains());
  }

  /**
   * ★ 判据（铁律 5 / spec §一.2）：两条链命令都**经 `between` 产变更集**——把命令边界交出去的那份变更集 `apply` 回 base，
   * 逐字段重建出**域操作算出的目标**（比的是命令边界真正给出的差分，不是重跑一遍 op 再比）。
   */
  @Test
  void chainCommandsProduceChangeSetsThatRebuildTheTarget() {
    UnitState base = twoChains();
    SimulationState world = worldAt(T5, base);

    UnitChangeSet create =
        changeSetOf(
            CREATE_CHAIN,
            world,
            "{\"chainId\":\"c-3\",\"name\":\"第三链\",\"commander\":\"u-4\","
                + "\"members\":[\"u-4\",\"u-1\"]}");
    assertThat(create.commandChains().changed()).as("链的差分非空").isTrue();
    assertThat(create.units().changed()).as("建链不动 units").isFalse();
    assertThat(UnitChangeSet.apply(create, base))
        .isEqualTo(
            UnitOperations.createChain(
                base, new CommandChain(CHAIN_3, "第三链", U4, Set.of(U4, SpiFixture.U1))));

    UnitChangeSet update =
        changeSetOf(
            UPDATE_CHAIN,
            world,
            "{\"chainId\":\"c-2\",\"name\":\"改名\",\"commander\":\"u-3\","
                + "\"members\":[\"u-3\"]}");
    assertThat(UnitChangeSet.apply(update, base))
        .isEqualTo(
            UnitOperations.updateChain(
                base, CHAIN_2, Optional.of("改名"), Optional.of(U3), Optional.of(List.of(U3))));
  }

  // ── unit.PlanSparseRoute（T6 / spec §二.2 / P10 / P12 / R4） ──────

  /** 与走廊**不相邻**的孤岛：邻格都不在图上 ⇒ 在图上但走不通（"段不可达"的强靶子）。 */
  private static final HexCoord FAR = new HexCoord(5, 5);

  /** 三格走廊 `H11→H12→H13` + 孤岛 `FAR`（平坦 `moveCost 25`；与 {@code SpiFixture.map()} 同制，多一格）。 */
  private static GameMap sparseMap() {
    TerrainType flat =
        new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "稀疏路线夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(SpiFixture.H11, new HexCell(0.5));
    hexes.put(SpiFixture.H12, new HexCell(0.5));
    hexes.put(SpiFixture.H13, new HexCell(0.5));
    hexes.put(FAR, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(flat.key(), flat);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), flat.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 同 {@link #worldAt}，地图切片换成"走廊 + 孤岛"。 */
  private static SimulationState sparseWorldAt(SimosTimestamp at, UnitState base) {
    return new SimulationState(
        new StateMeta(SpiFixture.REF, at),
        Map.of(
            "unit", new UnitSnapshot(SpiFixture.REF, at, base),
            "map", new MapSnapshot(SpiFixture.REF, at, sparseMap())),
        InMemoryInfoSystem.empty());
  }

  /**
   * 只封**一条边**的成本替身（裁定 U3 的靶子）：指定的 `from → to` 一步返回空，其余原样转交。
   *
   * <p>★ 替身自己也要判"图外"——转交的 {@code TerrainMovementCost} 已经这么做了，这里不重复实现，只**加**一条封路。
   */
  private record BlockedStep(HexCoord blockedFrom, HexCoord blockedTo, MovementCost delegate)
      implements MovementCost {

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      if (from.equals(blockedFrom) && to.equals(blockedTo)) {
        return OptionalLong.empty();
      }
      return delegate.costMillis(from, to, unit, map);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return delegate.minStepCostMillis(unit, map);
    }
  }

  @Test
  void planSparseRouteTypeNameMatchesTheSpecTable() {
    assertThat(PLAN_SPARSE.type()).isEqualTo("unit.PlanSparseRoute");
  }

  /**
   * ★★ 判据（P10 / **m1 靶子**）：非相邻 `waypoints`（`(1,1) → (1,3)`）也能展开成逐格 `path` 并落成 `Movement`。
   *
   * <p>m1（回到 `new Route(waypoints, waypoints)`）在本用例红：`(1,1) → (1,3)` 不相邻 ⇒ 构造期拒 ⇒ 边界返回 `Rejected`，
   * 而本用例要的是 `Applied`。
   */
  @Test
  void planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath() {
    UnitState next =
        applied(
            PLAN_SPARSE,
            worldAt(T5, oneUnit()),
            "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}");
    Movement movement = next.units().get(SpiFixture.U1).movement().orElseThrow();
    assertThat(movement.route().waypoints())
        .as("稀疏路点原样保留")
        .containsExactly(SpiFixture.H11, SpiFixture.H13);
    assertThat(movement.route().path())
        .as("path 逐格展开（A* 产物）")
        .containsExactly(SpiFixture.H11, SpiFixture.H12, SpiFixture.H13);
    assertThat(movement.departedAt()).isEqualTo(T5);
    assertThat(movement.speedAtDeparture()).isEqualTo(2);
    assertThat(movement.mobilityAtDeparture()).isEqualTo(500);
  }

  /**
   * ★★ 判据（P12 / **m2 靶子**）：段不可达 ⇒ **命令期拒**，理由带"不可达"，状态一字不动。
   *
   * <p>★ 本用例是"拒绝 ⇒ 不变更集"的**模块侧**实测：{@code HandlerOutcome.Rejected} 里没有 {@code UnitChangeSet} ⇒
   * 流水线无处可落新 `revision` 行（`revisions` 表在 `simos-core`，本轮不动它 ⇒ 见报告 §待控制器裁的缺口）。
   *
   * <p>★ m2（静默截断）在本用例红：截断后 `path` 只剩一格 ⇒ `Route` 拒的是"至少两格"（不是"不可达"），空段单段更是直接 `Applied`。
   */
  @Test
  void planSparseRouteRejectsAnUnreachableSegment() {
    SimulationState world = sparseWorldAt(T5, oneUnit());
    assertThat(
            reason(
                PLAN_SPARSE,
                world,
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":5,\"r\":5}]}"))
        .as("在图上但不可达（孤岛）")
        .contains("不可达");
    assertThat(
            reason(
                PLAN_SPARSE,
                world,
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3},{\"q\":5,\"r\":5}]}"))
        .as("前一段可达、后一段不可达")
        .contains("不可达");
    assertThat(
            reason(
                PLAN_SPARSE,
                world,
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":9,\"r\":9}]}"))
        .as("根本不在图上")
        .contains("不可达");
    assertThat(unitSlice(world).units().get(SpiFixture.U1).movement()).as("拒绝 ⇒ 状态不动").isEmpty();
  }

  /**
   * ★★ 判据（**R4** / m4 靶子）：跨段回头 ⇒ 拼接出的 `path` 有重复格 ⇒ `Route` 构造期拒，理由带"重复"。
   *
   * <p>`(1,1) → (1,3) → (1,1)`：两段各自都是简单路径，拼起来才重复。★ m4（拼接时顺手去重）在本用例红——去重后 `path` 的首尾与 `waypoints` 不符
   * ⇒ 拒的理由变成"首尾"，不是"重复"。
   */
  @Test
  void planSparseRouteRejectsACrossSegmentRepeat() {
    assertThat(
            reason(
                PLAN_SPARSE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3},{\"q\":1,\"r\":1}]}"))
        .contains("重复");
  }

  /** ★★ 判据（§二.2 / **m3 靶子**）：起点 ≠ 单位在 `at` 的位置 ⇒ 拒（既有 `planRoute` 校验，本轮不动它）。 */
  @Test
  void planSparseRouteRejectsStartThatIsNotTheEffectivePosition() {
    assertThat(
            reason(
                PLAN_SPARSE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}"))
        .contains("起点");
  }

  /** ★ 判据（§二.2 步骤 3）：逐格相邻的 `waypoints` ⇒ 与既有 `unit.PlanRoute` 落下来的路线逐值相同。 */
  @Test
  void planSparseRouteMatchesPlanRouteForAdjacentWaypoints() {
    String payload = "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}";
    assertThat(
            applied(PLAN_SPARSE, worldAt(T5, oneUnit()), payload)
                .units()
                .get(SpiFixture.U1)
                .movement()
                .orElseThrow())
        .as("与既有命令同一条路（复用 planRoute）")
        .isEqualTo(
            applied(PLAN_ROUTE, worldAt(T5, oneUnit()), payload)
                .units()
                .get(SpiFixture.U1)
                .movement()
                .orElseThrow());
  }

  /**
   * ★★ 判据（裁定 U3 / **m5 靶子**）：成本实现**真的用了注入的那一个**——同一条载荷，两个注入给相反结论。
   *
   * <p>m5（handler 里写死 `TerrainMovementCost.INSTANCE`）在本用例红：封路替身那条会照旧 `Applied`。
   */
  @Test
  void planSparseRouteUsesTheInjectedMovementCost() {
    String payload = "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}";
    SimulationState world = worldAt(T5, oneUnit());
    assertThat(applied(PLAN_SPARSE, world, payload).units().get(SpiFixture.U1).movement())
        .as("前提：地里成本（回廊畅通）⇒ 可达")
        .isPresent();
    assertThat(
            reason(
                new PlanSparseRouteHandler(
                    new BlockedStep(SpiFixture.H12, SpiFixture.H13, TerrainMovementCost.INSTANCE)),
                world,
                payload))
        .as("注入封掉 H12→H13 的替身 ⇒ 同一载荷变不可达")
        .contains("不可达");
  }

  /** ★ 判据（同既有 `PlanRoute` 的拒法）：未知 id / 载荷畸形 ⇒ 拒（不是装配故障）。 */
  @Test
  void planSparseRouteRejectsUnknownIdAndMalformedPayload() {
    assertThat(
            reason(
                PLAN_SPARSE,
                worldAt(T5, oneUnit()),
                "{\"id\":\"u-404\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}"))
        .contains("单位不存在");
    assertThat(reason(PLAN_SPARSE, worldAt(T5, oneUnit()), "{\"id\":\"u-1\",\"waypoints\":{}}"))
        .contains("waypoints");
    assertThat(reason(PLAN_SPARSE, worldAt(T5, oneUnit()), "{\"id\":\"u-1\",\"waypoints\":[]}"))
        .contains("至少两个");
    assertThat(reason(PLAN_SPARSE, worldAt(T5, oneUnit()), "不是 JSON")).contains("不是合法 JSON");
  }

  /**
   * ★★ 判据（spec §二.2「读法与 `UnitTimeParticipant.mapOf` 同制」）：缺 `map` 切片 ⇒ **装配故障当场炸**，不是拒绝。
   *
   * <p>★ 与 {@code RenameUnitHandlerTest.missingUnitSliceIsAssemblyFaultNotRejection} 同族：装配故障走异常，
   * 坏命令走拒绝，两者不能混成一条路。
   */
  @Test
  void planSparseRouteBlowsUpWhenTheMapSliceIsMissing() {
    UnitSnapshot unitOnly = new UnitSnapshot(SpiFixture.REF, T5, oneUnit());
    assertThatThrownBy(
            () ->
                PLAN_SPARSE.handle(
                    SpiFixture.singleModuleState("unit", unitOnly),
                    "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("map");
  }

  /**
   * ★ 判据（同制）：`map` 切片在、但类型不对 ⇒ 同样是装配故障。
   *
   * <p>★ 这个替身是**必要的**：{@code SimulationState} 的构造期要求"键 == 快照的 {@code namespace()}"，而 {@code map}
   * 命名空间的正主只有 {@code MapSnapshot} ⇒ 不自己造一个自称 `map` 的切片，这条分支在测试里根本够不着。
   */
  private record ImpostorSnapshot(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }

  @Test
  void planSparseRouteBlowsUpWhenTheMapSliceIsNotAMapSnapshot() {
    // 两个切片都在（否则先炸的就是"没有 unit 切片"，判据就落不到类型判断那一行上）。
    SimulationState world =
        new SimulationState(
            new StateMeta(SpiFixture.REF, T5),
            Map.of(
                "unit", new UnitSnapshot(SpiFixture.REF, T5, oneUnit()),
                "map", new ImpostorSnapshot(SpiFixture.REF, T5)),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () ->
                PLAN_SPARSE.handle(
                    world, "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MapSnapshot");
  }

  // ── unit.SetRejoinTarget（T7 / spec §二.2 / §四 表 / P7 · P8 / 裁定 U4~U6） ──

  /**
   * ★★ 判据（spec §四 表）：命令名与 spec 逐字一致。
   *
   * <p>★ **单列一条方法**：上面那条 `typeNamesMatchTheSpecTable` 是"十六个"的清单（T6 起已是第十七、十八个）， 逐条断言 ⇒
   * 动它等于改写既有证据，不改；新命令各自立一条。
   */
  @Test
  void setRejoinTargetTypeNameMatchesTheSpecTable() {
    assertThat(SET_REJOIN_TARGET.type()).isEqualTo("unit.SetRejoinTarget");
  }

  /**
   * ★★ 判据（spec §二.2 / §四 表 / P8 / **裁定 U4**）：**只设引用**——写的是 `rejoinTarget` 那个普通字段， `position` 与
   * `movement` 逐值不动；**目标单位**一字不动；命令边界的变更集非空。
   *
   * <p>★ 这是 P8 的"命令期不冻结"那一半：`target` 指的是"往谁靠"，**不是某一格** ⇒ 本命令不建路线、不落 hex。
   */
  @Test
  void setRejoinTargetSetsOnlyTheReference() {
    UnitState base = twoIndependent();
    UnitChangeSet changeSet =
        changeSetOf(SET_REJOIN_TARGET, world(base), "{\"id\":\"u-1\",\"target\":\"u-2\"}");
    UnitState next = UnitChangeSet.apply(changeSet, base);

    assertThat(changeSet.isEmpty()).as("真的改了字段（不是空转）").isFalse();
    assertThat(changeSet.units().changed()).isTrue();
    assertThat(changeSet.commandChains().changed()).as("命令链与它无关").isFalse();
    assertThat(next.units().get(SpiFixture.U1).rejoinTarget()).contains(U2);
    assertThat(next.units().get(SpiFixture.U1).position())
        .as("★ U4：位置分量逐值不动（没往状态里塞 hex）")
        .isEqualTo(base.units().get(SpiFixture.U1).position());
    assertThat(next.units().get(SpiFixture.U1).movement())
        .as("★ P8：不建路线——终点每 tick 现算，命令期只落引用")
        .isEqualTo(base.units().get(SpiFixture.U1).movement());
    assertThat(next.units().get(U2)).as("目标单位一字不动").isEqualTo(base.units().get(U2));
  }

  /**
   * ★★ 判据（spec §二.2 「设 / 清同一条命令，往返闭合」 / §四 表 `target?`）：**缺失**与**显式 `null`** 都清，
   * 且清完之后**逐值回到原状态**（铁律 5 的往返在这里是直接的 record 相等，不只是"字段对得上"）。
   */
  @Test
  void setRejoinTargetClearsOnMissingOrNullTargetAndRoundTrips() {
    UnitState base = twoIndependent();
    UnitState set = applied(SET_REJOIN_TARGET, world(base), "{\"id\":\"u-1\",\"target\":\"u-2\"}");
    assertThat(set.units().get(SpiFixture.U1).rejoinTarget()).contains(U2);

    UnitState clearedMissing = applied(SET_REJOIN_TARGET, world(set), "{\"id\":\"u-1\"}");
    assertThat(clearedMissing.units().get(SpiFixture.U1).rejoinTarget()).as("缺失 ⇒ 清").isEmpty();
    assertThat(clearedMissing).as("★ 设 → 清往返：逐值回到原状态").isEqualTo(base);

    UnitState setAgain =
        applied(SET_REJOIN_TARGET, world(base), "{\"id\":\"u-1\",\"target\":\"u-2\"}");
    UnitState clearedNull =
        applied(SET_REJOIN_TARGET, world(setAgain), "{\"id\":\"u-1\",\"target\":null}");
    assertThat(clearedNull.units().get(SpiFixture.U1).rejoinTarget()).as("显式 null ⇒ 清").isEmpty();
    assertThat(clearedNull).as("★ 两种清法（缺失 / null）结果同值").isEqualTo(clearedMissing);
  }

  /**
   * ★★ 判据（spec §四 表的拒绝列）：目标不存在 / 指自己 / 命令对象不存在 —— 三条都折成拒绝，且**三条理由互不相同**。
   *
   * <p>★ 理由的**层次**要能分辨：把"目标在不在"与"自己在不在"混成一条消息的实现（例如两者都报"单位不存在"）在本用例红 ——
   * 这里第一条要的是"回归目标不存在"、第三条要的是"单位不存在"（载荷层对 `target` 形状的消息又是第三种，见下一条）。
   */
  @Test
  void setRejoinTargetRejectsUnknownSelfAndSelfReference() {
    SimulationState world = world(twoIndependent());

    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-1\",\"target\":\"u-404\"}"))
        .as("目标不在 units 里")
        .contains("回归目标不存在")
        .contains("u-404");
    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-1\",\"target\":\"u-1\"}"))
        .as("指向自己（自环）")
        .contains("不得是自身")
        .contains("u-1");
    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-404\",\"target\":\"u-2\"}"))
        .as("命令的对象自己不存在")
        .contains("单位不存在")
        .contains("u-404");
    assertThat(unitSlice(world).units().get(SpiFixture.U1).rejoinTarget())
        .as("拒绝 ⇒ 输入状态不动（handler 是纯函数）")
        .isEmpty();
    assertThat(unitSlice(world).units().get(U2).rejoinTarget()).as("目标那一侧也不会被顺手写上什么").isEmpty();
  }

  /**
   * ★ 判据（spec §四 表 + T5 的载荷口径）：形状坏一律拒绝，**理由整句**属于载荷层（与域层那三条刻意不同）。
   *
   * <p>★ 断言写整句而不只写字段 token：`target` 这个 token 在域层消息里也会出现（"回归目标不存在"），只判 token 的 断言分不出是哪一层拒的（T4/T5
   * 记下的同族缺口）。
   */
  @Test
  void setRejoinTargetRejectsMalformedPayload() {
    SimulationState world = world(twoIndependent());

    assertThat(reason(SET_REJOIN_TARGET, world, "[1,2,3]")).contains("必须是 JSON 对象");
    assertThat(reason(SET_REJOIN_TARGET, world, "不是 JSON")).contains("不是合法 JSON");
    assertThat(reason(SET_REJOIN_TARGET, world, "{}")).contains("id 必须是字符串");
    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-1\",\"target\":{}}"))
        .as("target 形状（载荷层整句）")
        .contains("target 必须是字符串或 null");
    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-1\",\"target\":\"\"}"))
        .as("空串不是清（清只能靠缺失或 null）")
        .contains("target 不得为空白");
    assertThat(reason(SET_REJOIN_TARGET, world, "{\"id\":\"u-1\",\"target\":\"   \"}"))
        .as("空白串同理")
        .contains("target 不得为空白");
  }

  /**
   * ★★ 判据（裁定 U5 的命令层半边）：切到 `RESTING` **不清**回归引用 —— 这不是"取消回归"；状态回到 `MOVING` 时 参与者能接着规划（参与者侧见 {@code
   * UnitTimeParticipantTest#restingUnitKeepsTheReferenceButDoesNotRejoin}）。
   *
   * <p>★ 靶子是"顺手把引用清掉"的实现（读起来像垃圾回收，实则是把 U5 判反了）：本用例红在引用那条断言上。
   */
  @Test
  void setStatusDoesNotClearTheRejoinTarget() {
    UnitState set =
        applied(SET_REJOIN_TARGET, world(twoIndependent()), "{\"id\":\"u-1\",\"target\":\"u-2\"}");
    UnitState resting = applied(SET_STATUS, world(set), "{\"id\":\"u-1\",\"status\":\"RESTING\"}");

    assertThat(resting.units().get(SpiFixture.U1).status()).isEqualTo(UnitStatus.RESTING);
    assertThat(resting.units().get(SpiFixture.U1).rejoinTarget())
        .as("★ U5：引用留着，MOVING 时自然续上")
        .contains(U2);
  }

  /**
   * ★★ 判据（T5-L4 的又一处站点）：`setRejoinTarget` 重建状态也**只走 `withUnits`** ⇒ 链经命令边界的变更集往返逐值活下来。
   *
   * <p>★ 靶子是那个助手改回 `new UnitState(next)`：链会在 `between` 里被当成"被删掉"，`apply` 之后全空 —— 值红，不是异常。
   */
  @Test
  void setRejoinTargetKeepsCommandChains() {
    UnitState base = twoChains();
    UnitState next =
        applied(SET_REJOIN_TARGET, worldAt(T5, base), "{\"id\":\"u-1\",\"target\":\"u-2\"}");

    assertThat(next.units().get(SpiFixture.U1).rejoinTarget()).contains(U2);
    assertThat(next.commandChains()).as("★ T5-L4：链逐值活下来（旧写法会全清）").isEqualTo(base.commandChains());
  }
}
