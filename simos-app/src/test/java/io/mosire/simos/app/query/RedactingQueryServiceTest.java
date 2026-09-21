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
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
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
  private static final DecisionMakerId DM_FULL = new DecisionMakerId("dm-full");
  private static final DecisionMakerId DM_PERCEPTION = new DecisionMakerId("dm-perception");
  private static final DecisionMakerId DM_WITHHELD = new DecisionMakerId("dm-withheld");
  private static final DecisionMakerId DM_REDACT_POSITION = new DecisionMakerId("dm-redact");
  private static final VerdictId V1 = new VerdictId("v-1");

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

  // ── T6：adjudicationDisclosure 真正生效（C28）───────────────────────────

  @Test
  void withheldDisclosureHidesEveryVerdict() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    assertThat(service.verdicts(DM_WITHHELD, target)).as("WITHHELD ⇒ 判决整条不出现（空列表，不是空串）").isEmpty();
    assertThat(service.verdicts(DM_FULL, target)).as("FULL ⇒ 有判决").isNotEmpty();
    assertThat(service.verdicts(DM_PERCEPTION, target)).isNotEmpty();
  }

  @Test
  void perceptionOnlyDropsTheNonObservableVerdictFields() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    Map<String, Object> full = service.verdicts(DM_FULL, target).get(0);
    Map<String, Object> perception = service.verdicts(DM_PERCEPTION, target).get(0);

    assertThat(full).as("FULL 含模型输出与 meta").containsKeys("payload", "meta");
    assertThat(perception)
        .as("PERCEPTION_ONLY 只留可观察项，去掉不可感知的模型内部量")
        .containsKeys("id", "breakpoint", "subject", "atRevision")
        .doesNotContainKeys("payload", "meta");
    assertThat(perception.get("id")).isEqualTo(full.get("id"));
  }

  @Test
  void verdictsWithoutActorAreFullDisclosure() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    assertThat(service.verdicts(target)).hasSize(1);
    assertThat(service.verdicts(target).get(0)).containsKeys("payload", "meta");
  }

  // ── T6：redactedFields 真正生效（C29）──────────────────────────────────

  @Test
  void redactedFieldsRemovesTheNamedFieldFromUnitViews() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    List<Map<String, Object>> redacted = service.units(DM_REDACT_POSITION, target);
    assertThat(redacted).as("单位仍可见").hasSize(1);
    assertThat(redacted.get(0))
        .as("position 被按名剔除")
        .doesNotContainKey("position")
        .containsKey("id");

    List<Map<String, Object>> plain = service.units(DM_FULL, target);
    assertThat(plain.get(0)).as("未声明 redactedFields 的 actor 仍有 position").containsKey("position");
  }

  @Test
  @SuppressWarnings("unchecked")
  void redactedFieldsRecursesIntoNestedLists() {
    RedactingQueryService service = start();
    ViewScope scope =
        new ViewScope(Set.of(), Set.of(H11), Set.of(U1), false, DisclosurePolicy.FULL, Set.of("q"));

    Object stripped =
        service.applyRedactedFields(Map.of("hexes", List.of(Map.of("q", 1, "r", 2))), scope);

    Map<String, Object> body = (Map<String, Object>) stripped;
    List<Map<String, Object>> nested = (List<Map<String, Object>>) body.get("hexes");
    assertThat(nested.get(0)).as("嵌套列表里的 q 也被剔除").doesNotContainKey("q").containsKey("r");
  }

  // ── T6：可见性谓词（按地址取单个实体的 fail-closed）────────────────────

  @Test
  void seesPredicatesFollowTheScope() {
    RedactingQueryService service = start();
    QueryTarget target = QueryTarget.head(MAIN);

    assertThat(service.seesHex(DM_SCOPE_A, target, H11)).isTrue();
    assertThat(service.seesHex(DM_SCOPE_A, target, H12)).as("H12 不在 A 的可见格").isFalse();
    assertThat(service.seesRegion(DM_SCOPE_A, target, new RegionId("r1"))).isTrue();
    assertThat(service.seesRegion(DM_SCOPE_A, target, new RegionId("r2"))).isFalse();
    assertThat(service.seesUnit(DM_SCOPE_A, target, U1)).isTrue();
    assertThat(service.seesUnit(DM_SCOPE_B, target, U1)).as("B 看不到 u-1").isFalse();
    assertThat(service.seesHex(DM_SCOPE_A, target, H11)).isTrue();
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
    makers.put(
        DM_SCOPE_A,
        maker("dm-a", Set.of(H11), Set.of(U1), DisclosurePolicy.PERCEPTION_ONLY, Set.of()));
    makers.put(
        DM_SCOPE_B,
        maker("dm-b", Set.of(H12), Set.of(), DisclosurePolicy.PERCEPTION_ONLY, Set.of()));
    makers.put(DM_FULL, maker("dm-full", Set.of(H11), Set.of(U1), DisclosurePolicy.FULL, Set.of()));
    makers.put(
        DM_PERCEPTION,
        maker(
            "dm-perception", Set.of(H11), Set.of(U1), DisclosurePolicy.PERCEPTION_ONLY, Set.of()));
    makers.put(
        DM_WITHHELD,
        maker("dm-withheld", Set.of(H11), Set.of(U1), DisclosurePolicy.WITHHELD, Set.of()));
    makers.put(
        DM_REDACT_POSITION,
        maker("dm-redact", Set.of(H11), Set.of(U1), DisclosurePolicy.FULL, Set.of("position")));
    Map<VerdictId, Verdict> verdicts = new LinkedHashMap<>();
    verdicts.put(V1, verdict("v-1"));
    return SdState.empty().withDecisionMakers(makers).withVerdicts(verdicts);
  }

  private static DecisionMaker maker(
      String id,
      Set<HexCoord> hexes,
      Set<UnitId> units,
      DisclosurePolicy disclosure,
      Set<String> redacted) {
    return new DecisionMaker(
        new DecisionMakerId(id),
        new Affiliation.Nation(new NationId("n1")),
        Set.of(),
        new ViewScope(Set.of(new RegionId("r1")), hexes, units, false, disclosure, redacted),
        1);
  }

  private static Verdict verdict(String id) {
    Address subject = new Address(List.of(new Namespace("sd"), Entity.of("combat", "c1")));
    return new Verdict(
        new VerdictId(id),
        new AdjudicationBreakpoint("D1"),
        subject,
        "{\"rationaleText\":\"理由\",\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[]}",
        new VerdictMeta("model-x", "prompt-v1", "digest-abc"),
        new RevisionId(7));
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
