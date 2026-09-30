package io.mosire.simos.unit.spi;

import static io.mosire.simos.unit.spi.SpiFixture.H11;
import static io.mosire.simos.unit.spi.SpiFixture.H12;
import static io.mosire.simos.unit.spi.SpiFixture.H13;
import static io.mosire.simos.unit.spi.SpiFixture.MAP_ID;
import static io.mosire.simos.unit.spi.SpiFixture.T0;
import static io.mosire.simos.unit.spi.SpiFixture.U1;
import static io.mosire.simos.unit.spi.SpiFixture.inFlight;
import static io.mosire.simos.unit.spi.SpiFixture.map;
import static io.mosire.simos.unit.spi.SpiFixture.state;
import static io.mosire.simos.unit.spi.SpiFixture.unitState;
import static io.mosire.simos.unit.spi.SpiFixture.unitWithMovement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link UnitTimeParticipant}（spec §9.1）。
 *
 * <p>夹具算术（**日制，2026-09-24 裁定：1 tick = 1 天**）：日预算 = {@code speedAtDeparture × 1000 × 24 × Δ天} =
 * 48000·Δ（speed = 2 MP/小时）；走廊每段成本 48000（= 整整一天的路）⇒ Δ=1 时 IN_TRANSIT 在 {@code [1,2]}（余 48000），Δ=2 时
 * ARRIVED 在 {@code [1,3]}。
 */
class UnitTimeParticipantTest {

  private static final CommandChainId CHAIN = new CommandChainId("c-1");

  /**
   * 每段 48000 毫 MP（= speed 2 MP/小时的**一整天**预算）的固定成本 + 全程记账（形态 4 的"原样转交"就钉在记账上）。 ★ 日制重标定：旧口径的"1500/刻"在
   * 1 tick = 1 天之下等价于"48000/天"——这样"每天恰走一格"的节奏不变。
   */
  private static final class RecordingCost implements MovementCost {

    final List<String> edges = new ArrayList<>();
    final List<GameMap> maps = new ArrayList<>();
    HexCoord impassableTo;

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      edges.add(from + "→" + to);
      maps.add(map);
      if (to.equals(impassableTo)) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(48000);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  private static UnitTimeParticipant participant(RecordingCost cost) {
    return new UnitTimeParticipant(cost, MAP_ID);
  }

  private static TimeRange advanceTo(long tick) {
    return new TimeRange(T0, Optional.of(T0.plus(tick)));
  }

  /** 跑一次推进，并把提案落回 base：返回推进后的单位。 */
  private static Unit appliedUnit(RecordingCost cost, TimeRange range) {
    Unit inTransit = unitWithMovement(Optional.of(inFlight()));
    UnitState base = unitState(inTransit);
    TimeProposal proposal = participant(cost).simulate(state(map(), base), range);
    UnitState next = UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);
    return next.units().get(U1);
  }

  @Test
  void namespaceIsUnit() {
    assertThat(participant(new RecordingCost()).namespace()).isEqualTo("unit");
  }

  @Test
  void arrivedUnitGetsArrivalPositionAndClearsMovement() {
    Unit moved = appliedUnit(new RecordingCost(), advanceTo(2));

    assertThat(moved.movement()).as("已抵达 ⇒ movement 真的清了").isEmpty();
    assertThat(moved.position().valueAt(T0.plus(2))).as("position 段写入抵达点").contains(H13);
  }

  @Test
  void inTransitUnitGetsCurrentHexAndKeepsRoute() {
    Unit moved = appliedUnit(new RecordingCost(), advanceTo(1));

    assertThat(moved.position().valueAt(T0.plus(1))).as("未抵达 ⇒ position 写当前所在格").contains(H12);
    assertThat(moved.movement()).as("不改路线").contains(inFlight());
  }

  @Test
  void impassableEdgeKeepsRouteWithPositionAtCurrentHex() {
    RecordingCost cost = new RecordingCost();
    cost.impassableTo = H13; // H12→H13 不可通行 ⇒ NEED_REPLAN（判定沿用 M3）
    Unit moved = appliedUnit(cost, advanceTo(1));

    assertThat(moved.position().valueAt(T0.plus(1))).contains(H12);
    assertThat(moved.movement()).as("NEED_REPLAN 也不改路线").contains(inFlight());
  }

  @Test
  void unitWithoutMovementDoesNotEnterChangeSet() {
    UnitState base = unitState(unitWithMovement(Optional.empty()));
    TimeProposal proposal =
        participant(new RecordingCost()).simulate(state(map(), base), advanceTo(1));

    assertThat(((UnitChangeSet) proposal.changeSet()).isEmpty()).isTrue();
    assertThat(proposal.reads()).isEmpty();
    assertThat(proposal.writes()).isEmpty();
  }

  @Test
  void readsCoverUnitAndRouteHexesWritesCoverUnit() {
    UnitState base = unitState(unitWithMovement(Optional.of(inFlight())));
    TimeProposal proposal =
        participant(new RecordingCost()).simulate(state(map(), base), advanceTo(1));

    assertThat(proposal.reads())
        .containsExactly("unit:u-1", "map:Map1:hex.1_1", "map:Map1:hex.1_2", "map:Map1:hex.1_3");
    assertThat(proposal.writes()).containsExactly("unit:u-1");
    // canonical 地址必须真的能被解析回来（canonical 只能由 AST 构造的那条纪律，在这里复核）
    for (String read : proposal.reads()) {
      assertThat(Address.parse(read).canonical()).isEqualTo(read);
    }
  }

  @Test
  void evaluateReceivesStateMapAndRouteVerbatim() {
    GameMap theMap = map();
    UnitState base = unitState(unitWithMovement(Optional.of(inFlight())));
    SimulationState world = state(theMap, base);
    RecordingCost cost = new RecordingCost();
    participant(cost).simulate(world, advanceTo(1));

    assertThat(cost.maps).as("每次询价拿到的都是 state 里的那张图（同一实例）").containsExactly(theMap, theMap);
    assertThat(cost.edges)
        .as("询价的正是路线的相邻格对，按 path 顺序")
        .containsExactly(H11 + "→" + H12, H12 + "→" + H13);
  }

  @Test
  void unboundedRangeProposesZeroChange() {
    RecordingCost cost = new RecordingCost();
    UnitState base = unitState(unitWithMovement(Optional.of(inFlight())));
    TimeProposal proposal = participant(cost).simulate(state(map(), base), TimeRange.since(T0));

    assertThat(((UnitChangeSet) proposal.changeSet()).isEmpty()).isTrue();
    assertThat(proposal.reads()).isEmpty();
    assertThat(proposal.writes()).isEmpty();
    assertThat(cost.edges).as("无可评估的时刻 ⇒ 根本不询价").isEmpty();
  }

  /**
   * ★★ 判据（裁定 T5-U2，site 5 = `UnitTimeParticipant` 的返回）：推进**保留 `commandChains`**——位置与行程真的变了，
   * 链逐值活下来，且**链根本没进差分**。
   *
   * <p>★ 靶子是那行 `snapshot.state().withUnits(units)`：改回 `new UnitState(units)`（1 参兼容构造器 ⇒
   * `commandChains = Map.of()`），本用例当场红两处——链的逐值断言、与"链没进差分"（变异体下会变成 {@code Remove}）。
   *
   * <p>★ 夹具只有 `u-1` 一个单位，链就只能围着它建（commander 也在成员里，T1 的不变量满足即可）——本用例钉的是**链这个 组件有没有被抹掉**，与链的规模无关。
   */
  @Test
  void advanceKeepsCommandChainsWhilePositionAndMovementChange() {
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(CHAIN, new CommandChain(CHAIN, "第一链", U1, Set.of(U1)));
    UnitState base = unitState(unitWithMovement(Optional.of(inFlight()))).withCommandChains(chains);

    TimeProposal proposal =
        participant(new RecordingCost()).simulate(state(map(), base), advanceTo(2));
    UnitState next = UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);

    assertThat(next.units().get(U1).movement()).as("前提：推进真的改了行程（抵达 ⇒ 清空）").isEmpty();
    assertThat(next.units().get(U1).position().valueAt(T0.plus(2))).as("前提：位置真的变了").contains(H13);
    assertThat(next.commandChains())
        .as("★ T5-U2：参与者走 withUnits ⇒ 链逐值活下来（旧写法会全清）")
        .isEqualTo(chains);
    assertThat(((UnitChangeSet) proposal.changeSet()).commandChains().changed())
        .as("★ 链与推进无关 ⇒ 连差分都不该有")
        .isFalse();
  }

  @Test
  void missingSlicesAreAssemblyFaults() {
    UnitSnapshot unitOnly =
        new UnitSnapshot(SpiFixture.REF, T0, unitState(unitWithMovement(Optional.of(inFlight()))));
    MapSnapshot mapOnly = new MapSnapshot(SpiFixture.REF, T0, map());
    TimeRange range = advanceTo(1);

    assertThatThrownBy(
            () ->
                participant(new RecordingCost())
                    .simulate(SpiFixture.singleModuleState("unit", unitOnly), range))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("map");
    assertThatThrownBy(
            () ->
                participant(new RecordingCost())
                    .simulate(SpiFixture.singleModuleState("map", mapOnly), range))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unit");
  }

  // ── §十一（2026-09-25）：一次 N 天 == N 次单日 ──────────────────────────────────────

  /**
   * ★★ **§十一 等价性**：{@code advance(from, from + 30)} 的终态 == 30 次单日 advance
   * 的终态（**含"顶层带动整支一起搬"那条路径**）。
   *
   * <p>★ 公式本身是**时间预算**（{@code speed × Δtick}），故 {@link UnitMoves#evaluate} 直接跨日算即可；本用例把它钉死： 顶层
   * {@code u-1} 带成员 {@code u-2}，成本替身让每段 = **20 天** ⇒ 30 天走到第二段**中途**（{@code H12}，IN_TRANSIT），
   * 而不是"一跨就抵达"的退化形态。
   *
   * <p>★★ **位置的"历史"分段数允许不同**（§十一 明文：事件链可以不同）——一次 30 天只追加**一个** {@code position} 段（时刻 = to）， 逐日版追加
   * 30 个段；两者作为"**to 时刻的状态**"（{@code valueAt(to)} 及其后的行为）**逐值相同**。故断言的是**终态视图**： {@code valueAt(to)}
   * + {@code movement} + 全部非位置分量 + 编制链，而不是整条 {@code SegmentedSeries} 的 record 相等。
   */
  @Test
  void multiDayAdvanceEqualsChainedSingleDayAdvancesIncludingTheWholeFormation() {
    MovementCost cost = new TwentyDayPerEdgeCost();
    UnitId member = new UnitId("u-2");
    UnitState base = pair(rootWithMovement(), memberOf(member, U1, H11));

    // 一份：一次 30 天。
    UnitState once =
        UnitChangeSet.apply(
            (UnitChangeSet)
                participantWith(cost).simulate(state(map(), base), advanceTo(30)).changeSet(),
            base);

    // 另一份：30 次单日（每次用上一刻的状态）。
    UnitState chained = base;
    for (long day = 0; day < 30; day++) {
      TimeRange dayRange = new TimeRange(T0.plus(day), Optional.of(T0.plus(day + 1)));
      TimeProposal step = participantWith(cost).simulate(state(map(), chained), dayRange);
      chained = UnitChangeSet.apply((UnitChangeSet) step.changeSet(), chained);
    }

    SimosTimestamp t30 = T0.plus(30);
    assertThat(once.effectivePosition(U1, t30)).as("前提：顶层 30 天走到第二段中途（H12，不是终点 H13）").contains(H12);
    assertTerminalUnitsEqual(once, chained, t30);

    // ★ 顶层带动整支一起搬：成员也被搬到 H12，且它自己没有行程。
    assertThat(once.effectivePosition(member, t30)).as("成员随顶层到 H12").contains(H12);
    assertThat(once.units().get(member).movement()).as("成员不自己走").isEmpty();
    assertThat(chained.effectivePosition(member, t30)).contains(H12);
  }

  /** 终态视图逐字段相等：{@code valueAt(at)} + movement + 全部非位置分量 + 编制链（位置历史的分段数不比较，见用例注释）。 */
  private static void assertTerminalUnitsEqual(
      UnitState once, UnitState chained, SimosTimestamp at) {
    assertThat(once.units().keySet()).as("同一组单位").isEqualTo(chained.units().keySet());
    for (UnitId id : once.units().keySet()) {
      Unit left = once.units().get(id);
      Unit right = chained.units().get(id);
      assertThat(left.name()).isEqualTo(right.name());
      assertThat(left.parent()).as("parent 是归属历史，推进不改它").isEqualTo(right.parent());
      assertThat(left.member()).isEqualTo(right.member());
      assertThat(left.equipment()).isEqualTo(right.equipment());
      assertThat(left.speed()).isEqualTo(right.speed());
      assertThat(left.mobilityPerMille()).isEqualTo(right.mobilityPerMille());
      assertThat(left.status()).isEqualTo(right.status());
      assertThat(left.attached()).isEqualTo(right.attached());
      assertThat(left.offset()).isEqualTo(right.offset());
      assertThat(left.rejoinTarget()).isEqualTo(right.rejoinTarget());
      assertThat(left.visionRadius()).isEqualTo(right.visionRadius());
      assertThat(left.movement()).as("在途行程逐值相同").isEqualTo(right.movement());
      assertThat(left.position().valueAt(at))
          .as("终态位置 valueAt(to) 逐值相同（历史分段数允许不同）")
          .isEqualTo(right.position().valueAt(at));
    }
    assertThat(once.commandChains()).isEqualTo(chained.commandChains());
  }

  /** 每段 20 天（48000 × 20 毫 MP）的成本替身 ⇒ 三格走廊走完要 40 天，30 天时停在第二段中途。 */
  private static final class TwentyDayPerEdgeCost implements MovementCost {

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return OptionalLong.of(48000L * 20L);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  /** 顶层（无父、attached、带在途行程）。 */
  private static Unit rootWithMovement() {
    return unitWithMovement(Optional.of(inFlight()));
  }

  /** 成员：{@code parent} 给定、{@code attached=true}、带自身位置、无自身行程（"整支一起搬"的载体）。 */
  private static Unit memberOf(UnitId id, UnitId parent, HexCoord position) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(parent))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
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

  // ── 回归路径（T7 / spec §二.2、§二.3 / P7 / P8 / 裁定 U5·U6） ──────────

  private static final UnitId U2 = new UnitId("u-2");

  /**
   * 认图界的成本替身（T7）：图内每条边 48000 毫 MP（= speed 2 的一整天预算）；**图外的边**与 {@code blockedFrom→blockedTo} 那条 ⇒ 空。
   *
   * <p>★ 与 {@link RecordingCost} 的差别只有"认图界"这一条：`RecordingCost` 对任何 {@code to} 都报价 ⇒ A\* 可以绕出
   * 图外再绕回来，"封掉一条边"根本封不住 —— 那样就造不出"在图上但不可达"的夹具（而"不可达 ⇒ 不回归"是本轮的一条判据）。
   */
  private static final class BoundedCost implements MovementCost {

    HexCoord blockedFrom;
    HexCoord blockedTo;

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      if (!map.hexes().containsKey(to)) {
        return OptionalLong.empty();
      }
      if (to.equals(blockedTo) && from.equals(blockedFrom)) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(48000);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  private static UnitTimeParticipant participantWith(MovementCost cost) {
    return new UnitTimeParticipant(cost, MAP_ID);
  }

  /** 13 参规范形态的夹具单位（T7 要造"带回归意图 / 换状态 / 无自身位置"的单位）。 */
  private static Unit unit(
      UnitId id, Optional<HexCoord> position, Optional<Movement> movement, UnitStatus status) {
    return unit(id, position, movement, status, Optional.empty(), true);
  }

  private static Unit unit(
      UnitId id,
      Optional<HexCoord> position,
      Optional<Movement> movement,
      UnitStatus status,
      Optional<UnitId> rejoinTarget,
      boolean attached) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        movement,
        status,
        new SegmentedSeries<>(List.of(new Segment<>(T0, attached)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        rejoinTarget);
  }

  /** 回归方 + 目标两单位状态；**两参规范构造器**（T5-L4 的口径，本用例不碰兼容构造器）。 */
  private static UnitState pair(Unit first, Unit second) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    byId.put(first.id(), first);
    byId.put(second.id(), second);
    return new UnitState(byId, Map.of());
  }

  /** 从 {@code base} 推进到 {@code T0+tick} 并把提案落回 base。 */
  private static UnitState advance(MovementCost cost, UnitState base, long tick) {
    TimeProposal proposal = participantWith(cost).simulate(state(map(), base), advanceTo(tick));
    return UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);
  }

  /**
   * ★★ 判据（spec §二.3 不变量 3 / P8 / **m1 靶子**）：大编制移动后回归终点**随动** —— 终点 == 目标**当前**的 {@code
   * effectivePosition}，不是上一 tick 物化出来的旧格。
   *
   * <p>夹具算术（日制重标定：每边 48000、日预算 48000 ⇒ 每天一格）：大编制 `u-2` 从 H11 出发，第 1 天物化在 H12、第 2 天抵达 H13；回归方 `u-1`
   * 带着"回归 u-2"的意图停在 H11，每 tick 重规划 ⇒ 第 1 刻拿到 [H11,H12]、第 2 刻拿到 [H12,H13]。
   *
   * <p>★ m1（终点取自**第一趟物化之前**的那份状态 ⇒ 等价于"把终点冻结在已有的那份 hex 上"）在本用例红：第 1 刻的目标 位置会读成 H11（= u-1 自己所在格，起点
   * == 终点）⇒ **根本不建行程**，而本用例要的是终点 H12。
   */
  @Test
  void rejoinEndpointFollowsTheTargetsCurrentEffectivePosition() {
    Unit child =
        unit(U1, Optional.of(H11), Optional.empty(), UnitStatus.MOVING, Optional.of(U2), true);
    Unit formation = unit(U2, Optional.of(H11), Optional.of(inFlight()), UnitStatus.MOVING);
    UnitState base = pair(child, formation);

    TimeProposal firstProposal =
        participantWith(new BoundedCost()).simulate(state(map(), base), advanceTo(1));
    UnitState afterFirst = UnitChangeSet.apply((UnitChangeSet) firstProposal.changeSet(), base);
    assertThat(afterFirst.effectivePosition(U2, T0.plus(1))).as("前提：大编制这一刻物化在 H12").contains(H12);
    assertThat(afterFirst.units().get(U1).movement())
        .as("★ 第 1 刻必须装载回归行程（终点随风向：H11 → H12）")
        .isPresent();
    Movement firstPlan = afterFirst.units().get(U1).movement().orElseThrow();
    assertThat(firstPlan.route().path()).as("第 1 刻：终点 = 目标当前有效位置 H12").containsExactly(H11, H12);
    assertThat(afterFirst.effectivePosition(U1, T0.plus(1)))
        .as("★ 回归不瞬移：它还在自己那格（终点只落进 movement，position 没被碰过）——**m7 靶子**")
        .contains(H11);
    assertThat(firstPlan.departedAt()).as("裁定 U6：departedAt = 本刻").isEqualTo(T0.plus(1));
    assertThat(firstPlan.speedAtDeparture()).isEqualTo(2);
    assertThat(firstPlan.mobilityAtDeparture()).isEqualTo(500);
    assertThat(firstProposal.writes()).as("回归方与目标都在写集里").contains("unit:u-1", "unit:u-2");
    assertThat(firstProposal.reads()).as("目标地址与路线格都是真输入").contains("unit:u-1", "unit:u-2");

    UnitState afterSecond = advance(new BoundedCost(), afterFirst, 2);
    Optional<HexCoord> targetNow = afterSecond.effectivePosition(U2, T0.plus(2));
    assertThat(targetNow).as("前提：大编制真的又动了一格").contains(H13);
    assertThat(afterSecond.units().get(U1).movement())
        .as("★ 第 2 刻必须**重新**装载回归行程（旧行程第 1 刻就抵达了 ⇒ 不重装则停在 H12）")
        .isPresent();
    Movement secondPlan = afterSecond.units().get(U1).movement().orElseThrow();
    assertThat(secondPlan.route().path())
        .as("★ 终点随动：上一刻的终点 H12 已变成起点，冻结实现会留在这里")
        .containsExactly(H12, H13);
    assertThat(secondPlan.route().path().get(secondPlan.route().path().size() - 1))
        .as("★ 终点逐值等于目标的当前有效位置（不是持久字段，是现算）")
        .isEqualTo(targetNow.orElseThrow());
    assertThat(secondPlan.departedAt()).as("每 tick 一条新行程，departedAt = 本刻").isEqualTo(T0.plus(2));
    assertThat(afterSecond.effectivePosition(U1, T0.plus(2)))
        .as("★ 回归不瞬移：第 2 刻它只走了一格（没被写到终点 H13）")
        .contains(H12);
  }

  /**
   * ★★ 判据（P7 "有能力回归" = 假 ⇒ 不动 / **m2 靶子**）：目标**不可达** ⇒ 零变更（连差分都不该有）。
   *
   * <p>夹具：走廊只有 H11-H12-H13 三格，`u-1` 在 H12、`u-2` 在 H13，**唯一那条 H12→H13 被成本替身封掉** （H13 在图上的邻居只有 H12）⇒
   * A\* 无路。
   *
   * <p>★ m2（不判可达、恒建一条 `[起点, 终点]` 的路线）在本用例红：两格**相邻** ⇒ 那条"恒建"的路线能过 `Route` 的全部 构造期校验（首尾一致、相邻、无重复）⇒
   * 一个 `Movement` 凭空出现，而本用例要的是空变更集。★ 同一条夹具在**不封边**时 由 {@link
   * #restingUnitKeepsTheReferenceButDoesNotRejoin} 证明确实有路（判别力来自"只差封边这一项"）。
   */
  @Test
  void unreachableTargetLeavesTheStateUntouched() {
    BoundedCost cost = new BoundedCost();
    cost.blockedFrom = H12;
    cost.blockedTo = H13;
    Unit child =
        unit(U1, Optional.of(H12), Optional.empty(), UnitStatus.MOVING, Optional.of(U2), true);
    Unit formation = unit(U2, Optional.of(H13), Optional.empty(), UnitStatus.MOVING);
    TimeProposal proposal =
        participantWith(cost).simulate(state(map(), pair(child, formation)), advanceTo(1));

    assertThat(((UnitChangeSet) proposal.changeSet()).isEmpty()).as("不可达 ⇒ 有能力回归为假 ⇒ 零变更").isTrue();
    assertThat(proposal.reads()).isEmpty();
    assertThat(proposal.writes()).isEmpty();
  }

  /**
   * ★★ 判据（裁定 U5 / **m3 靶子**）：状态**不允许移动**（RESTING）⇒ 不建回归；**引用不清** —— 状态回到 MOVING 就地恢复。
   *
   * <p>★ m3（删掉"状态允许移动"那条判据）在本用例红：RESTING 也会建出回归行程。
   *
   * <p>★ 后半段（回到 MOVING ⇒ 恢复）同时是"**不封边时确有路**"的正面样本：同一夹具（H11 → H13）在 RESTING 下零变更、 在 MOVING 下建出
   * [H11,H12,H13] ⇒ 两半只差 `status` 这一项。
   */
  @Test
  void restingUnitKeepsTheReferenceButDoesNotRejoin() {
    Unit resting =
        unit(U1, Optional.of(H11), Optional.empty(), UnitStatus.RESTING, Optional.of(U2), true);
    Unit formation = unit(U2, Optional.of(H13), Optional.empty(), UnitStatus.MOVING);
    UnitState base = pair(resting, formation);

    UnitState after = advance(new BoundedCost(), base, 1);
    assertThat(after.units().get(U1).movement()).as("RESTING ⇒ 不建回归行程").isEmpty();
    assertThat(after.units().get(U1).rejoinTarget()).as("★ 裁定 U5：引用**不清**（这里不是取消回归）").contains(U2);

    UnitState resumed = UnitOperations.setStatus(after, U1, UnitStatus.MOVING);
    UnitState rejoined = advance(new BoundedCost(), resumed, 2);
    assertThat(rejoined.units().get(U1).movement()).as("回到 MOVING ⇒ 重新规划、恢复回归").isPresent();
    assertThat(rejoined.units().get(U1).movement().orElseThrow().route().path())
        .as("★ 恢复后走的就是那条真路（不封边时可达）")
        .containsExactly(H11, H12, H13);
  }

  /**
   * ★★ 判据（P7 的第三条"假"）：目标**位置不可确定** ⇒ 不建回归（零变更）。
   *
   * <p>夹具：`u-2` **未挂靠**（{@code attached=false}）且**无自身位置** ⇒ {@code effectivePosition} 为空
   * （`UnitState` 的第五种情形）——它还在 world 里（不是"不存在"，那条在命令期就拒了），但此刻"往谁靠"无解。
   */
  @Test
  void undeterminableTargetPositionLeavesTheStateUntouched() {
    Unit child =
        unit(U1, Optional.of(H11), Optional.empty(), UnitStatus.MOVING, Optional.of(U2), true);
    Unit formation =
        unit(U2, Optional.empty(), Optional.empty(), UnitStatus.MOVING, Optional.empty(), false);
    TimeProposal proposal =
        participantWith(new BoundedCost())
            .simulate(state(map(), pair(child, formation)), advanceTo(1));

    assertThat(((UnitChangeSet) proposal.changeSet()).isEmpty())
        .as("目标位置不可确定 ⇒ 有能力回归为假 ⇒ 零变更")
        .isTrue();
    assertThat(proposal.reads()).isEmpty();
  }

  /**
   * ★★ 判据（T5-L4 的第五个站点）：**回归重规划这一趟也只走 `withUnits`** ⇒ 链逐值活下来、且链不进差分。
   *
   * <p>★ 靶子是第二趟之后那行 `snapshot.state().withUnits(units)`：改回 `new UnitState(units)`，本用例与 T5 的既有守卫
   * 一起红（两处各钉一遍：一趟推进有回归、一趟没有）。
   */
  @Test
  void rejoinTickKeepsCommandChains() {
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(CHAIN, new CommandChain(CHAIN, "第一链", U1, Set.of(U1, U2)));
    Unit child =
        unit(U1, Optional.of(H11), Optional.empty(), UnitStatus.MOVING, Optional.of(U2), true);
    Unit formation = unit(U2, Optional.of(H13), Optional.empty(), UnitStatus.MOVING);
    UnitState base = pair(child, formation).withCommandChains(chains);

    TimeProposal proposal =
        participantWith(new BoundedCost()).simulate(state(map(), base), advanceTo(1));
    UnitState next = UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);

    assertThat(next.units().get(U1).movement()).as("前提：回归行程真的建了").isPresent();
    assertThat(next.commandChains()).as("★ T5-L4：回归趟也只走 withUnits ⇒ 链活着").isEqualTo(chains);
    assertThat(((UnitChangeSet) proposal.changeSet()).commandChains().changed())
        .as("★ 链与回归无关 ⇒ 连差分都不该有")
        .isFalse();
  }

  /**
   * ★★ 判据（spec §二.3 不变量 3 的**结构**半边）：状态里**没有**可以持久存放"终点 hex"的位置。
   *
   * <p>行为半边由 {@link #rejoinEndpointFollowsTheTargetsCurrentEffectivePosition} 钉（终点每 tick
   * 现算、随动）；本用例钉 **类型**：`UnitState` 仍只有两个组件、`Unit` 的组件集**逐名列举** —— 想加一个"回归终点 / 回归时刻"字段就会当场红。
   *
   * <p>★ **m5 靶子**：给 `UnitState` 加第三个组件（另留一个两参构造器重载，故既有调用点照旧编译）的变异体在本用例红，**并且** 与 {@code
   * UnitRoundTripTest} 的两条一起红——那个字段谁都不读 ⇒ 回归场景的**行为层**判不出来（t7 的六条回归用例在 m5 下全绿），红的是**类型**与
   * **往返**两处。这是"等价变异体必须报两种形态"的实例（见 t7 报告 §五）。
   *
   * <p>★ **2026-09-22（权限阶段 Task 1）**：{@code visionRadius}（int，视野半径，spec §4.1）加进来时本用例**如期红了** ——
   * 逐名列举的用意就是"每加一个分量都要有人过一眼"。过完的结论是它**不违反不变量 3**：半径不是位置，更不是终点 hex。 ⇒ 名单补上它； **并同时补一条不依赖名单的判据**（下面的
   * hex 型分量检查）——只更新名单的话，下一个"忘了过眼"的人把 hex 字段**加进名单**就绕过去了， 而那正是本用例存在的理由。
   *
   * <p>★ **2026-09-30（辖区阶段 5）**：{@code jurisdiction}（{@code
   * Optional<Jurisdiction>}，单位侧管辖富结构）加进来时本用例 同样**如期红了**。过完的结论也是它**不违反不变量 3**：管辖是"谁管哪些 Region +
   * 税率/上限/行政能力"的制度事实，不是位置、更不是回归终点 hex（下面的类型判据同时确认 Unit 上仍只有 {@code position} 一个 {@code HexCoord}
   * 分量）。⇒ 名单补上它。
   */
  @Test
  void stateHasNoPlaceToPersistAnEndpointHex() {
    assertThat(componentNames(UnitState.class))
        .as("★ 不变量 3：状态类型里没有旧格 / 终点这类持久事实的位置")
        .containsExactly("units", "commandChains");
    assertThat(componentNames(Unit.class))
        .as("★ 单位上唯一的回归事实是那个引用（rejoinTarget），没有任何 hex 字段")
        .containsExactly(
            "id",
            "name",
            "parent",
            "position",
            "member",
            "equipment",
            "speed",
            "mobilityPerMille",
            "movement",
            "status",
            "attached",
            "offset",
            "rejoinTarget",
            "visionRadius",
            "jurisdiction");
    assertThat(hexTypedComponentNames(Unit.class))
        .as("★ **名单之外的牙齿**：`position` 是 Unit 上唯一能装 hex 的分量 —— 想塞「回归终点」只能塞在这里，改名换名单都绕不过")
        .containsExactly("position");
  }

  /**
   * 分量类型里出现 {@code HexCoord} 的名字集（**按类型**判，不看名单）——不变量 3 的直接编码。
   *
   * <p>它比名单强的地方：名单要靠"下一个人也照规矩过眼"，本条不靠人 —— 一个叫 `endpoint`/`rejoinAt` 的 hex 字段无论如何都会在这里现形。
   */
  private static List<String> hexTypedComponentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .filter(rc -> rc.getGenericType().getTypeName().contains("HexCoord"))
        .map(RecordComponent::getName)
        .toList();
  }

  private static List<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}
