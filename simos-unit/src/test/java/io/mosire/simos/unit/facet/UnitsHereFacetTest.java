package io.mosire.simos.unit.facet;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.facet.FacetEntry;
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
 * {@link UnitsHereFacet} 的验收（M5 T3 Step 1）：命中/排序/外来主体/空格/父位继承/装配故障。
 *
 * <p>夹具自带（不复用 {@code SpiFixture}：它在 {@code spi} 包内、包私有）。
 */
class UnitsHereFacetTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final UnitsHereFacet FACET = new UnitsHereFacet();

  // ── 面名 ────────────────────────────────────────────────────────────

  @Test
  void facetNameIsUnitsHere() {
    assertThat(FACET.facetName()).isEqualTo("unitsHere");
  }

  // ── 命中：一次一件，值为 canonical 地址 ──────────────────────────────

  @Test
  void unitAtHexIsOneEntryWithCanonicalAddressValue() {
    List<FacetEntry> entries =
        query(unitState(unit(U1, "第一连", Optional.of(H11), Optional.empty())), "map:Map1:hex.1_1");

    assertThat(entries).containsExactly(new FacetEntry("unit", "第一连", "Unit", "unit:u-1"));
  }

  @Test
  void entriesAreOrderedByUnitIdNotInsertionOrder() {
    UnitState state =
        unitState(
            unit(U2, "第二连", Optional.of(H11), Optional.empty()),
            unit(U1, "第一连", Optional.of(H11), Optional.empty()));

    assertThat(query(state, "map:Map1:hex.1_1"))
        .extracting(FacetEntry::value)
        .as("插入序是 u-2 在前，结果必须按 unit id 字典序排成 u-1、u-2")
        .containsExactly("unit:u-1", "unit:u-2");
  }

  @Test
  void unitElsewhereIsNotReturned() {
    UnitState state = unitState(unit(U1, "第一连", Optional.of(H11), Optional.empty()));
    assertThat(query(state, "map:Map1:hex.1_2")).isEmpty();
  }

  @Test
  void childWithoutOwnPositionInheritsParentHex() {
    UnitState state =
        unitState(
            unit(U1, "第一连", Optional.of(H11), Optional.empty()),
            unit(U2, "第一连.1排", Optional.empty(), Optional.of(U1)));

    assertThat(query(state, "map:Map1:hex.1_1"))
        .extracting(FacetEntry::value)
        .as("u-2 自身无位置 ⇒ effectivePosition 向父取，应出现在父所在的格子")
        .containsExactly("unit:u-1", "unit:u-2");
  }

  // ── 外来主体：空列表，不抛 ──────────────────────────────────────────

  @Test
  void foreignSubjectsAreEmpty() {
    UnitState state = unitState(unit(U1, "第一连", Optional.of(H11), Optional.empty()));
    List<String> foreign =
        List.of(
            "unit:u-1",
            "social:Map1:hex.1_1",
            "map:Map1:region.r-1",
            "map:Map1:hex.1_1:height",
            "map:Map1:[1,1]");
    for (String subject : foreign) {
      assertThat(query(state, subject)).as("主体 %s 不服务 ⇒ 空列表", subject).isEmpty();
    }
  }

  @Test
  void malformedHexNameIsEmptyNotAnError() {
    UnitState state = unitState(unit(U1, "第一连", Optional.of(H11), Optional.empty()));
    assertThat(query(state, "map:Map1:hex.not-a-coord")).isEmpty();
  }

  @Test
  void foreignSubjectDoesNotRequireTheUnitSlice() {
    SimulationState stateless =
        new SimulationState(new StateMeta(REF, T0), Map.of(), InMemoryInfoSystem.empty());
    assertThat(FACET.query(Address.parse("unit:u-1"), new ResolveContext(stateless, T0))).isEmpty();
  }

  @Test
  void claimedHexOnAWorldWithoutUnitSliceIsAnAssemblyFault() {
    SimulationState stateless =
        new SimulationState(new StateMeta(REF, T0), Map.of(), InMemoryInfoSystem.empty());
    try {
      FACET.query(Address.parse("map:Map1:hex.1_1"), new ResolveContext(stateless, T0));
      throw new AssertionError("unit 切片缺席时应抛装配故障");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageContaining("unit");
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static List<FacetEntry> query(UnitState state, String subject) {
    return FACET.query(Address.parse(subject), new ResolveContext(stateOf(state), T0));
  }

  private static SimulationState stateOf(UnitState units) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of("unit", new UnitSnapshot(REF, T0, units)),
        InMemoryInfoSystem.empty());
  }

  private static UnitState unitState(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  private static Unit unit(
      UnitId id, String name, Optional<HexCoord> position, Optional<UnitId> parent) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent)), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }
}
