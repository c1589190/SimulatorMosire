package io.mosire.simos.social.facet;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PopulationFacet} 的验收（M5 T3 Step 1）——**对拍**：每个查询值都与直接调 {@link
 * PopulationSeries#valueAt(SimosTimestamp)} 逐值相等，不是抄一个常量。
 */
class PopulationFacetTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final PopulationFacet FACET = new PopulationFacet();

  @Test
  void facetNameIsPopulation() {
    assertThat(FACET.facetName()).isEqualTo("population");
  }

  // ── 对拍：逐值等于领域 API ──────────────────────────────────────────

  @Test
  void valueMatchesDirectSeriesCallPerValue() {
    PopulationSeries series = seed();
    List<SimosTimestamp> ats =
        List.of(
            SimosTimestamp.of(-5),
            SimosTimestamp.of(0),
            SimosTimestamp.of(20),
            SimosTimestamp.of(45),
            SimosTimestamp.of(53),
            SimosTimestamp.of(70));
    for (SimosTimestamp at : ats) {
      List<FacetEntry> entries = FACET.query(Address.parse("map:Map1:hex.1_1"), ctx(series, at));
      assertThat(entries).as("at=%s 应恰有一条", at.tick()).hasSize(1);
      FacetEntry entry = entries.get(0);
      assertThat(entry.namespace()).isEqualTo("social");
      assertThat(entry.label()).isEqualTo("1_1");
      assertThat(entry.typeName()).isEqualTo("Population");
      assertThat(entry.value())
          .as("at=%s 的值必须等于 PopulationSeries.valueAt 的直接结果", at.tick())
          .isEqualTo(series.valueAt(at));
    }
  }

  @Test
  void valueIsNumberNotPreformattedString() {
    assertThat(FACET.query(Address.parse("map:Map1:hex.1_1"), ctx(seed(), T0)).get(0).value())
        .isInstanceOf(Number.class);
  }

  // ── 无序列 / 外来主体 ───────────────────────────────────────────────

  @Test
  void hexWithoutSeriesIsEmpty() {
    assertThat(FACET.query(Address.parse("map:Map1:hex.1_2"), ctx(seed(), T0))).isEmpty();
  }

  @Test
  void foreignSubjectsAreEmpty() {
    List<String> foreign =
        List.of(
            "unit:u-1",
            "social:Map1:hex.1_1",
            "map:Map1:region.r-1",
            "map:Map1:hex.1_1:population",
            "map:Map1:[1,1]");
    for (String subject : foreign) {
      assertThat(FACET.query(Address.parse(subject), ctx(seed(), T0)))
          .as("主体 %s 不服务 ⇒ 空列表", subject)
          .isEmpty();
    }
  }

  @Test
  void malformedHexNameIsEmptyNotAnError() {
    assertThat(FACET.query(Address.parse("map:Map1:hex.not-a-coord"), ctx(seed(), T0))).isEmpty();
  }

  @Test
  void foreignSubjectDoesNotRequireTheSocialSlice() {
    SimulationState stateless =
        new SimulationState(new StateMeta(REF, T0), Map.of(), InMemoryInfoSystem.empty());
    assertThat(FACET.query(Address.parse("unit:u-1"), new ResolveContext(stateless, T0))).isEmpty();
  }

  @Test
  void claimedHexOnAWorldWithoutSocialSliceIsAnAssemblyFault() {
    SimulationState stateless =
        new SimulationState(new StateMeta(REF, T0), Map.of(), InMemoryInfoSystem.empty());
    try {
      FACET.query(Address.parse("map:Map1:hex.1_1"), new ResolveContext(stateless, T0));
      throw new AssertionError("social 切片缺席时应抛装配故障");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageContaining("social");
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 与 {@code PopulationSeriesTest.seed()} 同款：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries seed() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  private static ResolveContext ctx(PopulationSeries series, SimosTimestamp at) {
    SocialData data = new SocialData(new LinkedHashMap<>(Map.of(H11, series)), Map.of(), Map.of());
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, at),
            Map.of("social", new SocialSnapshot(REF, at, data)),
            InMemoryInfoSystem.empty());
    return new ResolveContext(state, at);
  }
}
