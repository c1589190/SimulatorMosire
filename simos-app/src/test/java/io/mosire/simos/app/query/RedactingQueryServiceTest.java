package io.mosire.simos.app.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D4 判据（R10/N6）：两个不同 {@code ViewScope} 的决策人调**同一读端点** ⇒ 返回**不同**数据（可观察项的子集，不是否定式报告）。 */
class RedactingQueryServiceTest {

  private static final String MAP_ID = "Map1";
  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final DecisionMakerId DM_SCOPE_A = new DecisionMakerId("dm-a");
  private static final DecisionMakerId DM_SCOPE_B = new DecisionMakerId("dm-b");

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void twoScopesSeeDifferentHexesOnTheSameEndpoint() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    Map<String, Object> seenByA = service.mapOverview(DM_SCOPE_A, target, MAP_ID);
    Map<String, Object> seenByB = service.mapOverview(DM_SCOPE_B, target, MAP_ID);

    assertThat(hexes(seenByA)).containsExactly("1_1");
    assertThat(hexes(seenByB)).containsExactly("1_2");
    assertThat(seenByA).as("两个 scope 的响应必须不同（去掉插桩即成恒等）").isNotEqualTo(seenByB);
  }

  @Test
  void unitVisibilityFollowsTheScope() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    assertThat(service.units(DM_SCOPE_A, target))
        .extracting(view -> view.get("id"))
        .containsExactly("u-1");
    assertThat(service.units(DM_SCOPE_B, target)).as("B 看不到 u-1").isEmpty();
  }

  @Test
  void unknownActorIsFailClosedToEmptyScope() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    assertThat(service.scopeOf(new DecisionMakerId("ghost"), target).visibleHexes()).isEmpty();
    assertThat(hexes(service.mapOverview(new DecisionMakerId("ghost"), target, MAP_ID))).isEmpty();
  }

  private RedactingQueryService start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new MapCodec());
    core.register(new UnitCodec());
    core.register(new SdCodec());
    core.bootstrapGenesis(genesis());
    QueryService query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
    return new RedactingQueryService(query);
  }

  @SuppressWarnings("unchecked")
  private static List<String> hexes(Map<String, Object> overview) {
    List<Map<String, Object>> hexes = (List<Map<String, Object>>) overview.get("hexes");
    return hexes.stream().map(hex -> hex.get("q") + "_" + hex.get("r")).toList();
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T0),
        Map.of(
            "map", new MapSnapshot(ref, T0, map()),
            "unit", new UnitSnapshot(ref, T0, units()),
            "sd", new SdSnapshot(ref, T0, sdState())),
        InMemoryInfoSystem.empty());
  }

  private static SdState sdState() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_SCOPE_A, maker("dm-a", Set.of(H11), Set.of(U1)));
    makers.put(DM_SCOPE_B, maker("dm-b", Set.of(H12), Set.of()));
    return SdState.empty().withDecisionMakers(makers);
  }

  private static DecisionMaker maker(String id, Set<HexCoord> hexes, Set<UnitId> units) {
    return new DecisionMaker(
        new DecisionMakerId(id),
        new Affiliation.Nation(new io.mosire.simos.sd.id.NationId("n1")),
        Set.of(),
        new ViewScope(Set.of(), hexes, units, false, DisclosurePolicy.PERCEPTION_ONLY, Set.of()),
        1);
  }

  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        new RegionId("r1"), Region.of(new RegionId("r1"), "区域一", Set.of(H11), RegionMeta.empty()));
    regions.put(
        new RegionId("r2"), Region.of(new RegionId("r2"), "区域二", Set.of(H12), RegionMeta.empty()));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static UnitState units() {
    Unit unit =
        new Unit(
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
    return new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
  }
}
