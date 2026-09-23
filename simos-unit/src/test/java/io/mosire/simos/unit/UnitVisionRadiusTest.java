package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ **视野半径（spec §4.1 / 权限阶段 Task 1）**：形状、缺省、校验，以及最要紧的那一条 —— **9 处生产拷贝/创建点逐处不丢字段**。
 *
 * <p>★★ 装置的主体是 {@link #changedComponents}：它**逐 record 分量对拍**
 * before/after，返回**值变了**的分量名集。它**不按名字引用** {@code visionRadius} ⇒ 某个拷贝点漏传那一刻，{@code "visionRadius"}
 * 自己出现在差集里，红点**直接指名丢的是哪个字段** （而不是"某个断言不成立"）。这条纪律的由来是本仓最贵的教训：{@code MapData} 加字段时 {@code MapDiff}
 * 没人提醒要跟上，四个字段漂移出去、对非 root 节点写连通性**静默丢失**（CLAUDE.md 铁律 5）。
 *
 * <p>★ 兼容构造器（9 参与 13 参）**取缺省**是**唯一正确**的语义：那里没有来源。判据是"**创建点给缺省、拷贝点给原值**"——
 * 两者都有用例，故"一律给缺省"与"一律给原值"都活不过。
 */
class UnitVisionRadiusTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T1 = SimosTimestamp.of(1);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final String MAP_ID = "Map1";
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  /** 夹具的视野半径：**非缺省**（3）。拷贝点丢字段 ⇒ 它掉回 1，差值当场落在 {@code visionRadius} 上。 */
  private static final int RADIUS = 3;

  // ── 形状与缺省（spec §4.1 / 用户裁定⑤） ────────────────────────────

  @Test
  void unitDeclaresVisionRadiusAsTheLastComponent() {
    RecordComponent[] components = Unit.class.getRecordComponents();
    List<String> names = componentNames();
    assertThat(names).as("Unit 应为 14 分量，visionRadius 追加在最后").hasSize(14);
    assertThat(names.get(names.size() - 1)).isEqualTo("visionRadius");
    assertThat(components[components.length - 1].getType()).as("视野半径是 int").isEqualTo(int.class);
  }

  @Test
  void defaultVisionRadiusConstantIsOne() throws ReflectiveOperationException {
    assertThat(Unit.class.getField("DEFAULT_VISION_RADIUS").getInt(null))
        .as("用户裁定⑤：缺省 1 圈")
        .isEqualTo(1);
  }

  @Test
  void nineParameterCompatibilityConstructorTakesTheDefault() {
    assertThat(nineParameterUnit().visionRadius()).isEqualTo(Unit.DEFAULT_VISION_RADIUS);
  }

  @Test
  void thirteenParameterCompatibilityConstructorTakesTheDefault() {
    assertThat(thirteenParameterUnit().visionRadius())
        .as("13 参形态没有视野的来源 ⇒ 取缺省（它的调用点是夹具，不是拷贝点）")
        .isEqualTo(Unit.DEFAULT_VISION_RADIUS);
  }

  @Test
  void canonicalConstructorKeepsAnExplicitRadius() {
    assertThat(unit(U1, Optional.empty(), Optional.of(H11), RADIUS).visionRadius())
        .isEqualTo(RADIUS);
  }

  @Test
  void zeroMeansSelfOnlyAndIsLegal() {
    assertThat(unit(U1, Optional.empty(), Optional.of(H11), 0).visionRadius())
        .as("0 = 只看自身格，是合法值")
        .isZero();
  }

  @Test
  void negativeRadiusIsRejected() {
    assertThatThrownBy(() -> unit(U1, Optional.empty(), Optional.of(H11), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("visionRadius 必须 ≥ 0: -1");
  }

  @Test
  void onlyThreeConstructorShapesExist() {
    // 两条兼容构造器都只补缺省、**不接受半径** ⇒ 它们不可能绕过构造期校验（这是结构断言，不靠"我记得")。
    Set<Integer> arities = new LinkedHashSet<>();
    for (var constructor : Unit.class.getConstructors()) {
      arities.add(constructor.getParameterCount());
    }
    assertThat(arities)
        .as("恰三种构造形态：14 参 canonical + 9/13 参兼容（兼容形态没有视野的来源）")
        .isEqualTo(Set.of(9, 13, 14));
  }

  // ── ★★ 9 处生产拷贝/创建点：逐处不丢字段 ──────────────────────────

  @Test
  void renamePreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.rename(base, U1, "新名").units().get(U1),
        "rename",
        "name");
  }

  @Test
  void setStrengthPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setStrength(base, U1, 70, Map.of("步枪", 40)).units().get(U1),
        "setStrength",
        "member",
        "equipment");
  }

  @Test
  void applyCasualtiesPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.applyCasualties(base, U1, -30, Map.of("步枪", -10)).units().get(U1),
        "applyCasualties",
        "member",
        "equipment");
  }

  @Test
  void placeAtPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.placeAt(base, U1, Optional.of(H12), T1).units().get(U1),
        "placeAt",
        "position");
  }

  @Test
  void planRoutePreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.planRoute(base, U1, corridor(), T0).units().get(U1),
        "planRoute",
        "movement");
  }

  @Test
  void cancelRoutePreservesVisionRadius() {
    // 先真的装上一条路线 ⇒ cancelRoute 的差集里 movement 才会出现（否则判据对"清空"这一支是恒真的）。
    UnitState planned =
        UnitOperations.planRoute(
            stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS)), U1, corridor(), T0);
    assertCopied(
        planned.units().get(U1),
        UnitOperations.cancelRoute(planned, U1).units().get(U1),
        "cancelRoute",
        "movement");
  }

  @Test
  void reparentPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS), plain(U2));
    assertCopied(
        base.units().get(U1),
        UnitOperations.reparent(base, U1, Optional.of(U2), T1).units().get(U1),
        "reparent",
        "parent");
  }

  @Test
  void setStatusPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setStatus(base, U1, UnitStatus.RESTING).units().get(U1),
        "setStatus",
        "status");
  }

  @Test
  void setRejoinTargetPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS), plain(U2));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setRejoinTarget(base, U1, Optional.of(U2)).units().get(U1),
        "setRejoinTarget",
        "rejoinTarget");
  }

  @Test
  void attachSubtreePreservesVisionRadius() {
    // ★ 2026-09-24（偏移式加入）：attach 清自身位置**并反算 offset**（两者都进差集）；不再要求同格。
    UnitState base =
        stateOf(
            unit(U1, Optional.empty(), Optional.of(H11), RADIUS),
            unit(U2, Optional.empty(), Optional.of(H11), Unit.DEFAULT_VISION_RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.attachSubtree(base, U1, U2, T1).units().get(U1),
        "attachSubtree",
        "parent",
        "attached",
        "position",
        "offset");
  }

  @Test
  void detachUnitPreservesVisionRadius() {
    UnitState base = stateOf(childOfU2(RADIUS), plain(U2));
    assertCopied(
        base.units().get(U1),
        UnitOperations.detachUnit(base, U1, T1).units().get(U1),
        "detachUnit",
        "attached",
        // ★ 本次改动：detach 把有效位置物化进自身 position（这里 U1 本就有位，仍追加一段）⇒ position 也是差集的一员。
        "position");
  }

  @Test
  void setOffsetPreservesVisionRadius() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), RADIUS));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setOffset(base, U1, Optional.of(new RelativeOffset(1, 0)), T1)
            .units()
            .get(U1),
        "setOffset",
        "offset");
  }

  @Test
  void reparentSubtreePreservesVisionRadius() {
    UnitState base = stateOf(childOfU2(RADIUS), plain(U2));
    assertCopied(
        base.units().get(U1),
        UnitOperations.reparentSubtree(base, U1, Optional.empty(), T1).units().get(U1),
        "reparentSubtree",
        "parent");
  }

  @Test
  void frozenViewInUnitMovesPreservesVisionRadius() {
    Unit inFlight = withMovement(unit(U1, Optional.empty(), Optional.of(H11), RADIUS), corridor());
    CapturingCost cost = new CapturingCost();
    UnitMoves.evaluate(inFlight, T1, map(), cost);
    assertThat(cost.seen).as("成本函数必须真的被询价过（否则本用例对 frozen 视图是恒真的）").isNotEmpty();
    assertCopied(inFlight, cost.seen.get(0), "UnitMoves.evaluate 的 frozen 视图（差集应为空：整份原样拷贝）");
  }

  @Test
  void tickMaterializationPreservesVisionRadius() {
    Unit inFlight = withMovement(unit(U1, Optional.empty(), Optional.of(H11), RADIUS), corridor());
    UnitState base = stateOf(inFlight);
    UnitState next = applyProposal(base, rangeTo(1), new FixedCost());
    assertCopied(
        base.units().get(U1), next.units().get(U1), "推进物化 withPositionAndMovement", "position");
  }

  @Test
  void rejoinReplanPreservesVisionRadius() {
    // 第二趟（withMovement）：U1 无在途行程、位于 H11，回归目标 U2 在 H13 ⇒ 本刻重新装载一条 Movement。
    UnitState base =
        stateOf(
            unit(U1, Optional.empty(), Optional.of(H11), RADIUS),
            unit(U2, Optional.empty(), Optional.of(H13), Unit.DEFAULT_VISION_RADIUS));
    UnitState aimed = UnitOperations.setRejoinTarget(base, U1, Optional.of(U2));
    UnitState next = applyProposal(aimed, rangeTo(1), new FixedCost());
    Unit before = aimed.units().get(U1);
    Unit after = next.units().get(U1);
    assertThat(after.movement()).as("回归轨道必须真的装载了行程（否则本用例对 withMovement 是恒真的）").isPresent();
    assertCopied(before, after, "回归重规划 withMovement", "movement");
  }

  // ── 装置 ────────────────────────────────────────────────────────

  /**
   * ★★ **本测试的主力装置**：逐 record 分量对拍 {@code before}/{@code after}，返回**值发生变化**的分量名集。
   *
   * <p>**不按名字引用 {@code visionRadius}** ⇒ 拷贝点漏传那一刻，{@code "visionRadius"} 自己出现在差集里。写死比较对象是错的做法：
   * 那样新增第 15 个分量时本装置不会自动跟上，正是铁律 5 的由来（{@code MapDiff} 手工维护、四个字段漂移出去）。
   */
  private static Set<String> changedComponents(Unit before, Unit after) {
    Set<String> changed = new LinkedHashSet<>();
    for (RecordComponent rc : Unit.class.getRecordComponents()) {
      Method accessor = rc.getAccessor();
      try {
        if (!Objects.equals(accessor.invoke(before), accessor.invoke(after))) {
          changed.add(rc.getName());
        }
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException("读不出分量 " + rc.getName(), e);
      }
    }
    return changed;
  }

  /** 拷贝点的判据：**恰好**这些分量变了。少一个（漏改动）或多一个（丢字段）都红，后者直接点名。 */
  private static void assertCopied(Unit before, Unit after, String op, String... expected) {
    assertThat(changedComponents(before, after))
        .as("%s：除 %s 外其余分量（含 visionRadius）必须原样带过", op, Set.of(expected))
        .isEqualTo(Set.of(expected));
  }

  // ── 夹具 ────────────────────────────────────────────────────────

  private static List<String> componentNames() {
    List<String> names = new ArrayList<>();
    for (RecordComponent rc : Unit.class.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  private static Unit unit(
      UnitId id, Optional<UnitId> parent, Optional<HexCoord> position, int visionRadius) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent)), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        visionRadius);
  }

  /** U1 挂在 U2 之下（detach / reparentSubtree 的输入）。 */
  private static Unit childOfU2(int visionRadius) {
    return unit(U1, Optional.of(U2), Optional.of(H11), visionRadius);
  }

  /** 编队操作里"另一个单位"（父或子树外的旁观者），视野半径不参与本用例。 */
  private static Unit plain(UnitId id) {
    return unit(id, Optional.empty(), Optional.of(H13), Unit.DEFAULT_VISION_RADIUS);
  }

  private static Unit nineParameterUnit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  private static Unit thirteenParameterUnit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty());
  }

  private static Unit withMovement(Unit unit, Route route) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        Optional.of(new Movement(route, T0, unit.effectiveSpeed(), unit.mobilityPerMille())),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius());
  }

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  private static Route corridor() {
    return new Route(List.of(H11, H13), List.of(H11, H12, H13));
  }

  private static TimeRange rangeTo(long tick) {
    return new TimeRange(T0, Optional.of(T0.plus(tick)));
  }

  private static UnitState applyProposal(UnitState base, TimeRange range, MovementCost cost) {
    TimeProposal proposal = new UnitTimeParticipant(cost, MAP_ID).simulate(worldOf(base), range);
    return UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);
  }

  private static SimulationState worldOf(UnitState unitState) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of(
            "unit", new UnitSnapshot(REF, T0, unitState),
            "map", new MapSnapshot(REF, T0, map())),
        InMemoryInfoSystem.empty());
  }

  private static GameMap map() {
    TerrainType flat = new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
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

  /** 记下被询价的单位（{@code UnitMoves.evaluate} 把 frozen 视图交给成本函数，这是取到它的唯一入口）。 */
  private static final class CapturingCost implements MovementCost {

    private final List<Unit> seen = new ArrayList<>();

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      seen.add(unit);
      return OptionalLong.of(1500);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  /** 每段固定 1500：Δ=1 时 speed 2 的预算 2000 ⇒ 走得动一格。 */
  private static final class FixedCost implements MovementCost {

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return OptionalLong.of(1500);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }
}
