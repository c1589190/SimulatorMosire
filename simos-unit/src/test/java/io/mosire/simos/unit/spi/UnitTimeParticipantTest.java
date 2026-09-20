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
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
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
 * <p>夹具算术：预算 = {@code speedAtDeparture × 1000 × Δ刻} = 2000·Δ；走廊每段成本 1500 ⇒ Δ=1 时 IN_TRANSIT 在
 * {@code [1,2]}（余 1000），Δ=2 时 ARRIVED 在 {@code [1,3]}。
 */
class UnitTimeParticipantTest {

  private static final CommandChainId CHAIN = new CommandChainId("c-1");

  /** 每段 1500 毫 MP 的固定成本 + 全程记账（形态 4 的"原样转交"就钉在记账上）。 */
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
      return OptionalLong.of(1500);
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
}
