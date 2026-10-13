package io.mosire.simos.army.spi;

import static io.mosire.simos.army.spi.EmergencyStopFixture.T0;
import static io.mosire.simos.army.spi.EmergencyStopFixture.army;
import static io.mosire.simos.army.spi.EmergencyStopFixture.corridor;
import static io.mosire.simos.army.spi.EmergencyStopFixture.data;
import static io.mosire.simos.army.spi.EmergencyStopFixture.mover;
import static io.mosire.simos.army.spi.EmergencyStopFixture.oneHexPerDay;
import static io.mosire.simos.army.spi.EmergencyStopFixture.singleModuleState;
import static io.mosire.simos.army.spi.EmergencyStopFixture.still;
import static io.mosire.simos.army.spi.EmergencyStopFixture.units;
import static io.mosire.simos.army.spi.EmergencyStopFixture.world;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.util.spi.Incident;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@link ArmyTimeParticipant} 的急停判据</b>（2026-10-13 急停协议 §6.2 P2/P4 + §6.3 N2 的靶子）。
 *
 * <p>★★ <b>本类的核心夹具是"第 5 天相遇、第 6 天分开"</b>（甲军自西向东、乙军自东向西，每天恰走一格 ⇒ 第 5 天同处 {@code (5,0)}、第 6 天相距 2 格、第
 * 10 天天各一方）。这是本批最关键的一条判别力：
 *
 * <ul>
 *   <li><b>逐日</b>实现 ⇒ 报 {@code tick=5}（P2 绿）；
 *   <li><b>只在 {@code range.to} 算一次</b>（复刻旧 {@code UnitTimeParticipant} 的形态）⇒ 第 10 天看过去"从没相遇" ⇒
 *       <b>报不出来</b>（N2 变异体在本类当场红）。
 * </ul>
 *
 * <p>★ 每个用例都**先自证夹具形态**（用 {@code UnitMoves.evaluate} 独立算第 5/10 天的位置）——"红"必须红在实现上，
 * 不能红在夹具上（本仓纪律：计划数字是预测、夹具要自证）。
 */
class ArmyTimeParticipantStopTest {

  private static final String A_ARMY = "a-army";
  private static final String B_ARMY = "b-army";
  private static final String A_UNIT = "u-a";
  private static final String B_UNIT = "u-b";

  private static ArmyTimeParticipant participant() {
    return new ArmyTimeParticipant(oneHexPerDay());
  }

  private static TimeRange days(long from, long to) {
    return new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)));
  }

  /** 迎面而行的一对军：甲 q=0 → 10、乙 q=10 → 0（每天一格 ⇒ 第 5 天同格、第 10 天天各一方）。 */
  private static GameMap crossingMap() {
    return corridor(10);
  }

  private static SimulationState crossingWorld(boolean watchA, boolean watchB) {
    GameMap map = crossingMap();
    Unit a = mover(A_UNIT, 0, 10);
    Unit b = mover(B_UNIT, 10, 0);
    ArmyData armies = data(army(A_ARMY, A_UNIT, watchA), army(B_ARMY, B_UNIT, watchB));
    UnitState unitState = units(a, b);
    SimulationState state = world(map, armies, unitState, 4L);
    // ★ 夹具自证：第 5 天同格、第 10 天相距 ≥ 2（"擦肩而过"的形态本身）。
    assertThat(UnitMoves.evaluate(a, SimosTimestamp.of(5), map, oneHexPerDay()).currentHex())
        .as("前提：第 5 天甲军在 (5,0)")
        .isEqualTo(new HexCoord(5, 0));
    assertThat(UnitMoves.evaluate(b, SimosTimestamp.of(5), map, oneHexPerDay()).currentHex())
        .as("前提：第 5 天乙军也在 (5,0)")
        .isEqualTo(new HexCoord(5, 0));
    HexCoord aAtTen =
        UnitMoves.evaluate(a, SimosTimestamp.of(10), map, oneHexPerDay()).currentHex();
    HexCoord bAtTen =
        UnitMoves.evaluate(b, SimosTimestamp.of(10), map, oneHexPerDay()).currentHex();
    assertThat(aAtTen.distanceTo(bAtTen))
        .as("★★ 前提（N2 的判别力来源）：第 10 天两军已相距 %d 格 ⇒ 只算终点的实现看不到这场相遇", aAtTen.distanceTo(bAtTen))
        .isGreaterThan(1);
    return state;
  }

  /**
   * ★★ <b>P2：中途相遇 ⇒ 报在命中的那一天（第 5 天）</b>，且第 10 天已经分开。
   *
   * <p>★ 这条同时是 <b>N2 的靶子</b>：把实现改成"只按 {@code range.to} 算一次位置"（复刻旧 {@code UnitTimeParticipant}
   * 只算终点的形态），第 10 天的位置相差 10 格 ⇒ {@code incidents} 空 ⇒ 本用例当场红。
   */
  @Test
  void meetingOnDayFiveIsReportedEvenThoughTheyHaveSeparatedByDayTen() {
    TimeProposal proposal = participant().simulate(crossingWorld(true, false), days(4, 10));

    assertThat(proposal.incidents()).as("★ 逐日扫描 ⇒ 第 5 天那场相遇必须被报出来（只算终点的实现这里是空的）").hasSize(1);
    Incident incident = proposal.incidents().get(0);
    assertThat(incident.tick()).as("★ 报的是**命中的那一天**（5），不是 range.to（10）").isEqualTo(5L);
    assertThat(incident.kind()).isEqualTo(ArmyContacts.KIND);
    assertThat(incident.namespace()).isEqualTo("army");
    assertThat(incident.payload().get("armies")).isEqualTo(List.of(A_ARMY, B_ARMY));
    assertThat(incident.payload().get("hex")).as("两军同格 ⇒ 就是那一格").isEqualTo("5_0");
  }

  /**
   * ★ <b>I2 分段等价</b>：{@code [4,10)} 一次算出的突发事件 == 6 次单日（5…10）逐次算出的并集，**逐字段相同**。
   *
   * <p>逐日自足（无跨日记忆）是"停在 tick 5、从 5 续推"语义安全的前提（设计书 §2.1 第 1 条）。
   */
  @Test
  void oneLongRangeEqualsSixChainedSingleDayRanges() {
    List<Incident> once =
        participant().simulate(crossingWorld(true, true), days(4, 10)).incidents();

    List<Incident> chained = new ArrayList<>();
    for (long day = 5; day <= 10; day++) {
      chained.addAll(
          participant().simulate(crossingWorld(true, true), days(day - 1, day)).incidents());
    }

    assertThat(once).as("一次 [4,10) == 六次单日（分段等价不破）").isEqualTo(chained);
    assertThat(once).as("前提：这条等价性不是在比两个空集").isNotEmpty();
  }

  /** ★ P4（参与者层）：**没被要求监控**的两军相遇 ⇒ 提案里一条突发事件都没有（⇒ core 不停）。 */
  @Test
  void unwatchedPairProducesNoIncidentOnTheSameCrossing() {
    assertThat(participant().simulate(crossingWorld(false, false), days(4, 10)).incidents())
        .as("★ P4：未启用 ⇒ 即使每天擦肩也不报")
        .isEmpty();
  }

  /** 只启用一方也报（"这两军相遇时告诉我"是 GM 的语义）——判据不要求双方都启用。 */
  @Test
  void oneWatchedSideIsEnough() {
    assertThat(participant().simulate(crossingWorld(false, true), days(4, 10)).incidents())
        .as("只启用乙军 ⇒ 第 5 天照样报")
        .hasSize(1);
  }

  /** 静止且相距很远的军 ⇒ 推进 10 天一条不报（"不接触就不该停"）。 */
  @Test
  void distantStillArmiesNeverReport() {
    ArmyData armies = data(army(A_ARMY, A_UNIT, true), army(B_ARMY, B_UNIT, true));
    SimulationState state =
        world(corridor(10), armies, units(still(A_UNIT, 0), still(B_UNIT, 10)), 4L);

    assertThat(participant().simulate(state, days(4, 14)).incidents())
        .as("静止 + 相距 10 格 ⇒ 十天内一条不报")
        .isEmpty();
  }

  /** 相邻（距离 1）也算相遇 —— 参与者层再钉一遍界的下侧。 */
  @Test
  void adjacentStillArmiesReport() {
    ArmyData armies = data(army(A_ARMY, A_UNIT, true), army(B_ARMY, B_UNIT, false));
    SimulationState state =
        world(corridor(10), armies, units(still(A_UNIT, 3), still(B_UNIT, 4)), 4L);

    TimeProposal proposal = participant().simulate(state, days(4, 5));
    assertThat(proposal.incidents()).as("相邻 ⇒ 第 5 天报一条").hasSize(1);
    assertThat(proposal.incidents().get(0).payload().get("hex"))
        .as("相邻 ⇒ 取被监控那一军（甲军）的格")
        .isEqualTo("3_0");
  }

  /**
   * ★ <b>无上界推进</b>（{@code range.to} 缺失）：与 {@code UnitTimeParticipant} 同一条处置 —— 交不变提案、不猜终点、
   * 不报事件（没有"逐日"可枚举）。
   */
  @Test
  void unboundedRangeReportsNothing() {
    TimeProposal proposal = participant().simulate(crossingWorld(true, true), TimeRange.since(T0));

    assertThat(proposal.incidents()).isEmpty();
    assertThat(proposal.reads()).isEmpty();
    assertThat(proposal.writes()).isEmpty();
    assertThat(proposal.changeSet()).as("moduleChanges 不得为空（不变变更集）").isNotNull();
  }

  /** 本参与者**不写世界**（判据是观测）：提案是不变变更集、读写集为空（I1）。 */
  @Test
  void proposalDoesNotWriteTheWorld() {
    TimeProposal proposal = participant().simulate(crossingWorld(true, true), days(4, 10));

    assertThat(proposal.reads()).as("★ 判据是纯读：不声明任何读面（自己算位置）").isEmpty();
    assertThat(proposal.writes()).as("★ 不写任何东西（否则急停就不只是观测）").isEmpty();
    assertThat(proposal.changeSet()).isInstanceOf(io.mosire.simos.army.change.ArmyChangeSet.class);
  }

  /**
   * 缺切片是**装配故障**（当场抛，不当成"没有军队"）——四种缺法各钉一遍，且**每条断言点名缺的那一个切片**： {@code ArmySnapshots.of} 先查 army，故"只缺
   * map"必须给全 army+unit 才走得到 map 那一步。
   */
  @Test
  void missingSlicesAreAssemblyFaults() {
    TimeRange range = days(4, 5);
    ArmyData armies = data(army(A_ARMY, A_UNIT, true), army(B_ARMY, B_UNIT, true));
    UnitState unitState = units(mover(A_UNIT, 0, 10), mover(B_UNIT, 10, 0));
    GameMap map = corridor(10);

    SimulationState armyOnly =
        singleModuleState("army", EmergencyStopFixture.armySlice(armies, 4L), 4L);
    SimulationState unitOnly =
        singleModuleState("unit", EmergencyStopFixture.unitSlice(unitState, 4L), 4L);
    SimulationState mapOnly = singleModuleState("map", EmergencyStopFixture.mapSlice(map, 4L), 4L);
    Map<String, Snapshot> noMap = new LinkedHashMap<>();
    noMap.put("army", EmergencyStopFixture.armySlice(armies, 4L));
    noMap.put("unit", EmergencyStopFixture.unitSlice(unitState, 4L));
    SimulationState armyAndUnitOnly = EmergencyStopFixture.worldOf(noMap, 4L);

    assertThatThrownBy(() -> participant().simulate(armyOnly, range))
        .as("只有 army 切片 ⇒ 走到取 unit 那一步才炸，点名 unit")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unit");
    assertThatThrownBy(() -> participant().simulate(unitOnly, range))
        .as("只有 unit 切片 ⇒ 第一跳就炸，点名 army")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("army");
    assertThatThrownBy(() -> participant().simulate(armyAndUnitOnly, range))
        .as(
            "army + unit 都在、只缺 map ⇒ 点名 map。★ 如实记：这一处抛的是 IllegalArgumentException"
                + "（ArmyTimeParticipant.mapOf），与 army/unit 两处的 IllegalStateException 不同类 —— "
                + "两处都自报「装配故障」，但异常类型不一致（已写入交账的观察项，未改生产）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
    assertThatThrownBy(() -> participant().simulate(mapOnly, range))
        .as("只有 map 切片 ⇒ 点名 army")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("army");
  }
}
