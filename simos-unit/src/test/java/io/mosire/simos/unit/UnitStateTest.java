package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编制树不变量（R6）+ 位置继承（R7）。 */
class UnitStateTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  private static Unit unit(
      String id, Optional<String> parent, Optional<HexCoord> position, SimosTimestamp parentAt) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(parentAt, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of(),
        2,
        1000,
        Optional.empty());
  }

  // ── R6 ──────────────────────────────────────────────────────────

  @Test
  void cycleAcrossUnitsThrowsAtConstruction() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), unit("a", Optional.of("b"), Optional.of(H11), T0));
    units.put(new UnitId("b"), unit("b", Optional.of("a"), Optional.of(H22), T0));

    assertThatThrownBy(() -> new UnitState(units))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成环");
  }

  @Test
  void legalReparentAcrossTimeIsNotACycle() {
    // t<10：a 无父、b 的父是 a（链 b→a）；t>=10：a 改编到 b 之下、b 同时脱离 a（链 a→b）。
    // 任一时刻都是链，**不是环**。
    // ★ 取代说明（执行期就地校正，计划原稿此处自相矛盾）：原稿只给 a 配段、b 仍是单段 [T0→a]——
    //   按 M1 时间语义"段值向前恒定延拓"，t≥10 时 a→b 与 b→a **同时在场**，是真环，计划自己的
    //   实现（逐关键时点查，spec §4.2"含 anchor 之前的恒定延拓"）必抛，实测确实抛了。合法改编
    //   要求**双方同刻各改一段**（子单位挂到新父、旧父脱离），故 b 补 [T10→空] 段。
    Unit a =
        new Unit(
            new UnitId("a"),
            "单位 a",
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(T0, Optional.<UnitId>empty()),
                    new Segment<>(T10, Optional.of(new UnitId("b")))),
                List.of(),
                null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of(),
            2,
            1000,
            Optional.empty());
    Unit b =
        new Unit(
            new UnitId("b"),
            "单位 b",
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(T0, Optional.of(new UnitId("a"))),
                    new Segment<>(T10, Optional.<UnitId>empty())),
                List.of(),
                null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H22))), List.of(), null),
            100,
            Map.of(),
            2,
            1000,
            Optional.empty());

    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), a);
    units.put(new UnitId("b"), b);
    UnitState state = new UnitState(units); // 不得抛
    assertThat(state.units()).hasSize(2);
  }

  // ── R7 ──────────────────────────────────────────────────────────

  @Test
  void effectivePositionPrefersOwnThenWalksToParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.empty(), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22);
    assertThat(state.effectivePosition(new UnitId("c"), T0)).as("自身无位置 ⇒ 向父取").contains(H22);
  }

  @Test
  void ownPositionWinsOverParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.of(H11), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(H11);
  }

  @Test
  void noPositionAnywhereIsEmpty() {
    UnitState state =
        new UnitState(Map.of(new UnitId("c"), unit("c", Optional.empty(), Optional.empty(), T0)));
    assertThat(state.effectivePosition(new UnitId("c"), T0)).isEmpty();
    assertThat(state.effectivePosition(new UnitId("nobody"), T0)).as("查不存在的主体 ⇒ 空").isEmpty();
  }
}
