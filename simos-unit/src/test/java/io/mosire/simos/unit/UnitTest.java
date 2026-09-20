package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit 的构造期守卫（R5：parent/position 不得带事件）+ 自环 + 数值下限。 */
class UnitTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);

  static SegmentedSeries<Optional<UnitId>> noParent() {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.empty())), List.of(), null);
  }

  static SegmentedSeries<Optional<HexCoord>> positionAt(HexCoord hex) {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.ofNullable(hex))), List.of(), null);
  }

  static Unit unit(UnitId id) {
    return new Unit(
        id, "第一连", noParent(), positionAt(H11), 100, Map.of("步枪", 50), 2, 1000, Optional.empty());
  }

  @Test
  void aWellFormedUnitIsConstructed() {
    Unit u = unit(new UnitId("u-1"));
    assertThat(u.parent().valueAt(T0)).isEmpty();
    assertThat(u.position().valueAt(T0)).contains(H11);
    assertThat(u.mobilityPerMille()).isEqualTo(1000);
  }

  // ── 三态速度（T2 / spec §三.2，缺口 U1 的 clamp 裁定） ────────────

  private static Unit withSpeedAndStatus(int speed, UnitStatus status) {
    return new Unit(
        new UnitId("u-1"),
        "第一连",
        noParent(),
        positionAt(H11),
        100,
        Map.of("步枪", 50),
        speed,
        1000,
        Optional.empty(),
        status,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty());
  }

  @Test
  void effectiveSpeedScalesByStatusFactor() {
    assertThat(withSpeedAndStatus(8, UnitStatus.MOVING).effectiveSpeed()).isEqualTo(8);
    assertThat(withSpeedAndStatus(8, UnitStatus.RESTING).effectiveSpeed()).isEqualTo(4);
    assertThat(withSpeedAndStatus(8, UnitStatus.ENGAGED).effectiveSpeed()).isEqualTo(2);
  }

  /** 缺口 U1 的裁定：`speed × factor / 1000` 可能 &lt; 1，clamp 到 1（`Movement.speedAtDeparture ≥ 1`）。 */
  @Test
  void effectiveSpeedNeverDropsBelowOne() {
    assertThat(withSpeedAndStatus(2, UnitStatus.ENGAGED).effectiveSpeed()).isEqualTo(1);
    assertThat(withSpeedAndStatus(1, UnitStatus.ENGAGED).effectiveSpeed()).isEqualTo(1);
  }

  // ── R5 ──────────────────────────────────────────────────────────

  @Test
  void parentAndPositionRejectEvents() {
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.<UnitId>empty())),
                        List.of(new Event<>(T0, Optional.<UnitId>empty(), EventMode.SET)),
                        null),
                    positionAt(H11),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent");

    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.<HexCoord>empty())),
                        List.of(new Event<>(T0, Optional.of(H11), EventMode.SET)),
                        null),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("position");
  }

  @Test
  void parentPointingToItselfThrows() {
    UnitId id = new UnitId("u-1");
    assertThatThrownBy(
            () ->
                new Unit(
                    id,
                    "第一连",
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.of(id))), List.of(), null),
                    positionAt(H11),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent");
  }

  @Test
  void numericBudgetsAreEnforced() {
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    positionAt(H11),
                    -1,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    positionAt(H11),
                    0,
                    Map.of(),
                    0,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    positionAt(H11),
                    0,
                    Map.of(),
                    2,
                    0,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equipmentIsFrozenAndValidated() {
    Map<String, Integer> mutable = new LinkedHashMap<>();
    mutable.put("步枪", 50);
    Unit u =
        new Unit(
            new UnitId("u-1"),
            "第一连",
            noParent(),
            positionAt(H11),
            100,
            mutable,
            2,
            1000,
            Optional.empty());
    mutable.put("炮", 1);
    assertThat(u.equipment()).containsOnlyKeys("步枪");

    Map<String, Integer> negative = new LinkedHashMap<>();
    negative.put("炮弹", -1);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    positionAt(H11),
                    100,
                    negative,
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
