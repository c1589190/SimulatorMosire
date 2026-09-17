package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编制树操作面 8 项（13 条 = 计划 12 条 + R-11-b 补的 placeAtClearsInTransitRoute）。 */
class UnitOperationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId BRIGADE = new UnitId("u-brigade");
  private static final UnitId COMPANY = new UnitId("u-company");
  private static final UnitId LOST = new UnitId("u-lost");

  private static Unit unit(String id, Optional<String> parent, Optional<HexCoord> position) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        1000,
        Optional.empty());
  }

  private static UnitState twoUnits() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(BRIGADE, unit("u-brigade", Optional.empty(), Optional.of(H11)));
    units.put(COMPANY, unit("u-company", Optional.of("u-brigade"), Optional.empty()));
    return new UnitState(units);
  }

  /** 真·无位置单位（R-11-a 取代写法）：整链（自身与父）都无位置，effectivePosition 必为空。 */
  private static UnitState withLost() {
    return UnitOperations.create(twoUnits(), unit("u-lost", Optional.empty(), Optional.empty()));
  }

  /** 无父、无位置的新兵（改编用例的 T0 基线：parent 在 T0 为空，才能证明"追加段的时间作用域"）。 */
  private static UnitState withRecruit() {
    return UnitOperations.create(twoUnits(), unit("u-recruit", Optional.empty(), Optional.of(H12)));
  }

  @Test
  void createRejectsDuplicateId() {
    assertThatThrownBy(
            () ->
                UnitOperations.create(
                    twoUnits(), unit("u-company", Optional.empty(), Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");
  }

  @Test
  void createRejectsAnUnknownParent() {
    assertThatThrownBy(
            () ->
                UnitOperations.create(
                    twoUnits(), unit("u-new", Optional.of("u-ghost"), Optional.of(H12))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createAcceptsAWellFormedUnit() {
    UnitState state =
        UnitOperations.create(
            twoUnits(), unit("u-new", Optional.of("u-brigade"), Optional.of(H12)));
    assertThat(state.units()).containsKey(new UnitId("u-new"));
  }

  @Test
  void reparentAppendsASegmentAndRejectsUnknownParents() {
    // ★ 取代说明（R-11-a 同族）：计划的正例把 COMPANY 改编给 BRIGADE——但 COMPANY 在 T0 就已是
    // BRIGADE 的下属，其 valueAt(T0) 恒非空，"T0 为空"断言对正确实现也红。改用无父新兵：
    // T0 空 → T10 起 BRIGADE，恰好证明"追加段"（替换式覆写或跳过追加都会红）。
    UnitState recruit = withRecruit();
    UnitState state =
        UnitOperations.reparent(recruit, new UnitId("u-recruit"), Optional.of(BRIGADE), T10);
    assertThat(state.units().get(new UnitId("u-recruit")).parent().valueAt(T10)).contains(BRIGADE);
    assertThat(state.units().get(new UnitId("u-recruit")).parent().valueAt(T0)).isEmpty();
    assertThat(recruit.units().get(new UnitId("u-recruit")).parent().segments())
        .as("纯函数：旧状态不变")
        .hasSize(1);

    assertThatThrownBy(
            () ->
                UnitOperations.reparent(
                    twoUnits(), COMPANY, Optional.of(new UnitId("u-ghost")), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), new UnitId("u-ghost"), Optional.empty(), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reparentOntoItselfThrows() {
    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), COMPANY, Optional.of(COMPANY), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renameChangesOnlyTheName() {
    UnitState state = UnitOperations.rename(twoUnits(), COMPANY, "一营指挥部");
    assertThat(state.units().get(COMPANY).name()).isEqualTo("一营指挥部");
    assertThat(state.units().get(COMPANY).member()).isEqualTo(100);
    assertThatThrownBy(() -> UnitOperations.rename(twoUnits(), new UnitId("u-ghost"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setStrengthReplacesMemberAndEquipment() {
    UnitState state = UnitOperations.setStrength(twoUnits(), COMPANY, 80, Map.of("炮", 4));
    assertThat(state.units().get(COMPANY).member()).isEqualTo(80);
    assertThat(state.units().get(COMPANY).equipment()).containsOnlyKeys("炮");
    assertThatThrownBy(
            () -> UnitOperations.setStrength(twoUnits(), new UnitId("u-ghost"), 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void placeAtAppendsAPositionSegmentAndClearsTheRoute() {
    UnitState state = UnitOperations.placeAt(twoUnits(), BRIGADE, Optional.of(H12), T10);
    assertThat(state.units().get(BRIGADE).position().valueAt(T10)).contains(H12);
    assertThat(state.units().get(BRIGADE).position().valueAt(T0)).contains(H11);
    assertThatThrownBy(
            () -> UnitOperations.placeAt(twoUnits(), new UnitId("u-ghost"), Optional.of(H12), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ R-11-b 补条（spec §4.6 第 5 条的靶子）：placeAt 必须顺带清空在途路线。 */
  @Test
  void placeAtClearsInTransitRoute() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState planned = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    UnitState state = UnitOperations.placeAt(planned, BRIGADE, Optional.of(H12), T10);
    assertThat(state.units().get(BRIGADE).movement()).isEmpty();
    assertThat(state.units().get(BRIGADE).position().valueAt(T10)).contains(H12);
  }

  @Test
  void planRouteRequiresAStartThatMatchesTheEffectivePosition() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState state = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(state.units().get(BRIGADE).movement()).isPresent();
    assertThat(state.units().get(BRIGADE).movement().orElseThrow().route()).isEqualTo(route);

    // ★ R-11-a 取代说明：计划断言"COMPANY 整链无位置 ⇒ 抛"不成立——COMPANY 无自身位置，
    // 但 effectivePosition 会沿父链继承 BRIGADE 的 H11（Task 6 的 R7 语义），起点对得上 ⇒ 应成功。
    // 正例钉住继承语义：
    assertThat(
            UnitOperations.planRoute(twoUnits(), COMPANY, route, T10)
                .units()
                .get(COMPANY)
                .movement())
        .isPresent();
    // "无位置 ⇒ 抛"的真靶子：u-lost 整链（自身与父）都无位置 ⇒ 抛。
    assertThatThrownBy(() -> UnitOperations.planRoute(withLost(), LOST, route, T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("位置");
    // 起点对不上：抛
    assertThatThrownBy(
            () ->
                UnitOperations.planRoute(
                    twoUnits(), BRIGADE, new Route(List.of(H12, H11), List.of(H12, H11)), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.planRoute(twoUnits(), new UnitId("u-ghost"), route, T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancelRouteClearsTheMovement() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState planned = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(UnitOperations.cancelRoute(planned, BRIGADE).units().get(BRIGADE).movement())
        .isEmpty();
    assertThatThrownBy(() -> UnitOperations.cancelRoute(twoUnits(), new UnitId("u-ghost")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disbandRefusesWhileSubordinatesExist() {
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), BRIGADE, T10))
        .as("在 at 时刻有下属 ⇒ 先改编子单位、再解散")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");

    UnitState state = UnitOperations.disband(twoUnits(), COMPANY, T10);
    assertThat(state.units()).doesNotContainKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), new UnitId("u-ghost"), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 解散的时点敏感：改编走在前 ⇒ 同一对单位在**晚一点的时刻**可以解散。 */
  @Test
  void disbandIsTimeSensitive() {
    UnitState reparented =
        UnitOperations.reparent(twoUnits(), COMPANY, Optional.empty(), SimosTimestamp.of(20));
    assertThat(UnitOperations.disband(reparented, BRIGADE, SimosTimestamp.of(20)).units())
        .containsKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(reparented, BRIGADE, T10))
        .as("在 T10 时刻 COMPANY 仍挂在 BRIGADE 下")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
