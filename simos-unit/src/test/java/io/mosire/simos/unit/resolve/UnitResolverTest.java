package io.mosire.simos.unit.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * unit: 寻址（R12 空候选 vs 抛 + R13 canonical 只回 ID）。
 *
 * <p>夹具：`高地人旅指挥部`（无父）→ `1营指挥部` → `1连指挥部` 三级链 + 一个用 ID 定位的 `u-f82a`；查询时刻 TS=5。
 */
class UnitResolverTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp TS = SimosTimestamp.of(5);
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static Unit unit(String id, String name, String parent) {
    return unitWithParentSegments(
        id,
        name,
        List.of(
            new Segment<>(
                TS, parent == null ? Optional.<UnitId>empty() : Optional.of(new UnitId(parent)))));
  }

  private static Unit unitWithParentSegments(
      String id, String name, List<Segment<Optional<UnitId>>> parentSegments) {
    return new Unit(
        new UnitId(id),
        name,
        new SegmentedSeries<>(parentSegments, List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(TS, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        1000,
        Optional.empty());
  }

  private static ResolveContext ctxOf(Map<UnitId, Unit> units) {
    UnitSnapshot snapshot = new UnitSnapshot(REF, TS, new UnitState(units));
    return new ResolveContext(
        new SimulationState(
            new StateMeta(REF, TS), Map.of("unit", snapshot), InMemoryInfoSystem.empty()),
        TS);
  }

  private static ResolveContext ctx() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-brigade"), unit("u-brigade", "高地人旅指挥部", null));
    units.put(new UnitId("u-bn1"), unit("u-bn1", "1营指挥部", "u-brigade"));
    units.put(new UnitId("u-co1"), unit("u-co1", "1连指挥部", "u-bn1"));
    units.put(new UnitId("u-f82a"), unit("u-f82a", "独立连", null));
    return ctxOf(units);
  }

  @Test
  void idFormResolvesAndCanonicalisesToTheId() {
    var result = new UnitResolver().resolve(Address.parse("unit:u-f82a"), ctx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).typeName()).isEqualTo("Unit");
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("unit:u-f82a");
  }

  /** ★ R13：链式输入（人读形式）的 canonical 一律回 ID。 */
  @Test
  void chainFormCanonicalisesToTheId() {
    var result = new UnitResolver().resolve(Address.parse("unit:\"高地人旅指挥部.1营指挥部.1连指挥部\""), ctx());
    assertThat(result.candidates()).as("链式逐级定位").hasSize(1);
    assertThat(result.candidates().get(0).canonicalAddress())
        .as("canonical 一律回 ID")
        .isEqualTo("unit:u-co1");
  }

  @Test
  void chainWithMultipleHitsIsOrderedByUnitId() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-b"), unit("u-b", "同名连", null));
    units.put(new UnitId("u-a"), unit("u-a", "同名连", null));
    ResolveContext context = ctxOf(units);

    var result = new UnitResolver().resolve(Address.parse("unit:\"同名连\""), context);
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates().get(0).canonicalAddress())
        .as("多解按 UnitId 字典序保序")
        .isEqualTo("unit:u-a");
  }

  @Test
  void chainIsOnlyServedForTwoSegmentAddresses() {
    // 两段之外（unit:"链":equipment.X 会落成三段）⇒ 空候选
    assertThat(
            new UnitResolver()
                .resolve(Address.parse("unit:\"高地人旅指挥部.1营指挥部\":member"), ctx())
                .candidates())
        .isEmpty();
  }

  @Test
  void equipmentResolvesOnlyWhenTheNameExists() {
    var hit = new UnitResolver().resolve(Address.parse("unit:u-f82a:equipment.步枪"), ctx());
    assertThat(hit.candidates()).hasSize(1);
    assertThat(hit.candidates().get(0).canonicalAddress()).isEqualTo("unit:u-f82a:equipment.步枪");
    assertThat(hit.candidates().get(0).id().localId()).isEqualTo("u-f82a/步枪");

    assertThat(
            new UnitResolver()
                .resolve(Address.parse("unit:u-f82a:equipment.炮"), ctx())
                .candidates())
        .as("没有该装备 ⇒ 空候选")
        .isEmpty();
  }

  /**
   * ★ R-12-b（取代计划 m2 建议的靶子夹具）：链式定位跟随**查询时刻**的 {@code parent} 值。
   *
   * <p>计划的建议（"T0 时 A→B、T10 时 A 无父"）不分叉——查询时刻是 TS=5，单段序列的 {@code valueAt(5)} 就等于首段值。故让 `1连指挥部` 的
   * parent 带两段：`[T0→1营, TS→空]`（严格升序 T0&lt;T5）⇒ {@code valueAt(TS)}=空 而首段值=1营 ⇒ 两种实现分叉：1连在 TS
   * 已脱挂、不再是 1营 的子级， 这条三级链在 TS 解析不出候选；若 {@code childrenAt} 错用首段值，1连仍会被算作 1营 的子级 ⇒ 链被错误解析成功 ⇒ 红。
   */
  @Test
  void chainFollowsTheParentAtTheQueryTime() {
    Unit detachedCompany =
        unitWithParentSegments(
            "u-co1",
            "1连指挥部",
            List.of(
                new Segment<>(SimosTimestamp.of(0), Optional.of(new UnitId("u-bn1"))),
                new Segment<>(TS, Optional.<UnitId>empty())));
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-brigade"), unit("u-brigade", "高地人旅指挥部", null));
    units.put(new UnitId("u-bn1"), unit("u-bn1", "1营指挥部", "u-brigade"));
    units.put(new UnitId("u-co1"), detachedCompany);
    ResolveContext context = ctxOf(units);

    var result = new UnitResolver().resolve(Address.parse("unit:\"高地人旅指挥部.1营指挥部.1连指挥部\""), context);
    assertThat(result.candidates()).as("1连在 TS 已脱挂 ⇒ 链在此断，空候选").isEmpty();
  }

  // ── R12 ────────────────────────────────────────────────────────

  @Test
  void unknownUnitAndUnknownChainAreEmptyCandidates() {
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-ghost"), ctx()).candidates())
        .isEmpty();
    assertThat(new UnitResolver().resolve(Address.parse("unit:\"不存在的名字\""), ctx()).candidates())
        .isEmpty();
    assertThat(
            new UnitResolver().resolve(Address.parse("unit:\"高地人旅指挥部.不存在的营\""), ctx()).candidates())
        .isEmpty();
  }

  @Test
  void attributeSegmentsAndForeignNamespacesAreEmptyCandidates() {
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-f82a:member"), ctx()).candidates())
        .isEmpty();
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-f82a:hex.1_1"), ctx()).candidates())
        .isEmpty();
    assertThat(new UnitResolver().resolve(Address.parse("map:Map1"), ctx()).candidates()).isEmpty();
  }

  @Test
  void missingSliceThrows() {
    ResolveContext noSlice =
        new ResolveContext(
            new SimulationState(new StateMeta(REF, TS), Map.of(), InMemoryInfoSystem.empty()), TS);
    assertThatThrownBy(() -> new UnitResolver().resolve(Address.parse("unit:u-f82a"), noSlice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit");
  }
}
