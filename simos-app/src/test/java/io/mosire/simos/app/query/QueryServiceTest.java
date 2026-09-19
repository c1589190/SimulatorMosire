package io.mosire.simos.app.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.facet.FacetProvider;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link QueryService} 经 {@link Shell} 的验收（M5 T3 Step 3）：resolve/facets 全链、装配完整性（R7）与
 * **转发原样**（R6，用记录型替身 provider）。
 *
 * <p>创世 checkpoint 含 **map + unit + social** 三切片（state 时间戳取 {@code of(7)}，非 0——这样"转发时把时间戳换成 {@code
 * of(0)}"这类变异才会被记录型替身当场看见）。
 */
class QueryServiceTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    shell.close();
  }

  // ── 装配完整性（R7）─────────────────────────────────────────────────

  @Test
  void facetNamesContainsBothRealFacetsInRegistrationOrder() {
    assertThat(shell.queryService().facetNames()).containsExactly("unitsHere", "population");
  }

  // ── resolve ─────────────────────────────────────────────────────────

  @Test
  void resolveMapHexThroughTheShellReturnsCanonicalCandidate() {
    QueryResult result = shell.queryService().resolve("map:Map1:[1,1]", QueryTarget.head(main()));

    assertThat(result.candidates()).hasSize(1);
    ResolvedSubject subject = result.candidates().get(0);
    assertThat(subject.canonicalAddress()).isEqualTo("map:Map1:hex.1_1");
    assertThat(subject.typeName()).isEqualTo("Hex");
  }

  @Test
  void resolveUnitThroughTheShellReturnsCanonicalCandidate() {
    QueryResult result = shell.queryService().resolve("unit:u-1", QueryTarget.head(main()));

    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("unit:u-1");
    assertThat(result.candidates().get(0).typeName()).isEqualTo("Unit");
  }

  @Test
  void stateAtHeadAndExplicitRevisionAgree() {
    SimulationState byHead = shell.queryService().stateAt(QueryTarget.head(main()));
    SimulationState byRevision =
        shell.queryService().stateAt(QueryTarget.at(main(), new RevisionId(1)));

    assertThat(byHead.meta().ref()).isEqualTo(ref("main", 1));
    assertThat(byHead.meta().timestamp()).isEqualTo(T7);
    assertThat(byRevision.meta()).isEqualTo(byHead.meta());
  }

  @Test
  void unknownBranchHeadFailsExplicitly() {
    assertThatThrownBy(() -> shell.queryService().stateAt(QueryTarget.head(new BranchId("nope"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  // ── M9 T3：读路径按坐标记忆化 ────────────────────────────────────────

  @Test
  void secondRequestForTheSameTargetDoesNotReadACheckpoint() {
    QueryService queryService = shell.queryService();
    QueryTarget target = QueryTarget.head(main());

    SimulationState first = queryService.stateAt(target);
    long readsAfterFirst = shell.coreSimos().checkpointReadCount();
    long hitsAfterFirst = queryService.stateCacheHits();

    SimulationState second = queryService.stateAt(target);

    assertThat(shell.coreSimos().checkpointReadCount())
        .as("同一坐标第二次读不得再碰 checkpoint（增量为 0）")
        .isEqualTo(readsAfterFirst);
    assertThat(queryService.stateCacheHits()).isEqualTo(hitsAfterFirst + 1);
    assertThat(queryService.stateCacheMisses()).as("首读至少真重放过一次").isPositive();
    assertThat(second).as("缓存返回同一个不可变实例").isSameAs(first);
    assertThat(second.meta().ref()).isEqualTo(ref("main", 1));
  }

  @Test
  void commitIsVisibleOnTheNextHeadQuery() {
    QueryService queryService = shell.queryService();
    QueryTarget head = QueryTarget.head(main());
    assertThat(queryService.stateAt(head).meta().ref().revision().value()).isEqualTo(1L);

    CommandResult result = shell.coreSimos().submit(renameEnvelope(main(), 1L, "缓存后的名字"));

    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref().revision().value()).isEqualTo(2L);
    SimulationState after = queryService.stateAt(head);
    assertThat(after.meta().ref().revision().value())
        .as("提交后 head 查询必须看到新 revision（key = 已解析坐标，新 revision ⇒ 新键）")
        .isEqualTo(2L);
    assertThat(nameOf(after)).isEqualTo("缓存后的名字");
  }

  @Test
  void cachedEntriesAreSeparatedByBranch() {
    QueryService queryService = shell.queryService();

    assertThat(shell.coreSimos().submit(forkEnvelope(main(), 1L, "side")))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(shell.coreSimos().submit(renameEnvelope(main(), 1L, "main-2")))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(shell.coreSimos().submit(renameEnvelope(side(), 1L, "side-2")))
        .isInstanceOf(CommandResult.Committed.class);

    SimulationState mainAtTwo = queryService.stateAt(QueryTarget.at(main(), new RevisionId(2)));
    SimulationState sideAtTwo = queryService.stateAt(QueryTarget.at(side(), new RevisionId(2)));

    assertThat(nameOf(mainAtTwo)).isEqualTo("main-2");
    assertThat(nameOf(sideAtTwo)).as("键漏 branch ⇒ main@2 与 side@2 串味").isEqualTo("side-2");
  }

  @Test
  void cachedStateIsDeeplyImmutableSoDownstreamCannotPolluteIt() {
    QueryService queryService = shell.queryService();
    SimulationState first = queryService.stateAt(QueryTarget.head(main()));

    assertThatThrownBy(() -> first.modules().put("x", first.module("map").orElseThrow()))
        .as("modules 是 Map.copyOf ⇒ 下游就地修改必抛（故无需防御性拷贝）")
        .isInstanceOf(UnsupportedOperationException.class);

    assertThat(queryService.stateAt(QueryTarget.head(main())))
        .as("就地修改尝试之后，缓存仍在且未被污染")
        .isSameAs(first);
    assertThat(queryService.stateAt(QueryTarget.head(main())).modules().keySet())
        .containsExactlyInAnyOrder("map", "unit", "social");
  }

  @Test
  void unknownNamespaceFailsExplicitly() {
    assertThatThrownBy(() -> shell.queryService().resolve("agent:x-1", QueryTarget.head(main())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent");
  }

  @Test
  void badAddressTextFailsExplicitly() {
    assertThatThrownBy(
            () -> shell.queryService().facets("not-an-address", QueryTarget.head(main())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── facets：两个真 facet 同时在场 ───────────────────────────────────

  @Test
  void facetsThroughTheShellReturnUnitsHereAndPopulation() {
    List<FacetEntry> entries =
        shell.queryService().facets("map:Map1:hex.1_1", QueryTarget.head(main()));

    assertThat(entries).extracting(FacetEntry::namespace).containsExactly("unit", "social");
    assertThat(entries.get(0)).isEqualTo(new FacetEntry("unit", "第一连", "Unit", "unit:u-1"));
    assertThat(entries.get(1).typeName()).isEqualTo("Population");
    assertThat(entries.get(1).value())
        .as("population 值 = 领域 API 在同一状态时间戳上的直接结果")
        .isEqualTo(populationSeries().valueAt(T7));
  }

  @Test
  void facetsOnAnEmptyHexReturnNoEntries() {
    assertThat(shell.queryService().facets("map:Map1:hex.1_2", QueryTarget.head(main()))).isEmpty();
  }

  // ── R6：参数原样转交 + 翻译确定 ─────────────────────────────────────

  @Test
  void facetsForwardTheExactAddressAndContextToTheProvider() {
    RecordingFacet recorder = new RecordingFacet();
    FacetRegistry recordingRegistry = new FacetRegistry();
    recordingRegistry.register(recorder);
    ResolverRegistry resolvers = new ResolverRegistry();
    resolvers.register(new MapResolver());

    QueryService queryService = new QueryService(shell.coreSimos(), resolvers, recordingRegistry);
    String text = "map:Map1:hex.1_1";
    queryService.facets(text, QueryTarget.head(main()));
    queryService.facets(text, QueryTarget.head(main()));

    assertThat(recorder.subjects).hasSize(2);
    for (Address received : recorder.subjects) {
      assertThat(received).isEqualTo(Address.parse(text));
      assertThat(received.canonical()).isEqualTo(text);
    }
    for (ResolveContext received : recorder.contexts) {
      assertThat(received.at()).isEqualTo(T7);
      assertThat(received.state().meta().ref()).isEqualTo(ref("main", 1));
    }
    // 翻译确定：两次查询转发的是逐字段相同的 Address 与 at
    assertThat(recorder.subjects.get(0)).isEqualTo(recorder.subjects.get(1));
    assertThat(recorder.contexts.get(0).at()).isEqualTo(recorder.contexts.get(1).at());
  }

  /** 记录型替身：只记下收到的 subject/ctx，返回空列表。 */
  private static final class RecordingFacet implements FacetProvider {

    private final List<Address> subjects = new ArrayList<>();
    private final List<ResolveContext> contexts = new ArrayList<>();

    @Override
    public String facetName() {
      return "recording";
    }

    @Override
    public List<FacetEntry> query(Address subject, ResolveContext ctx) {
      subjects.add(subject);
      contexts.add(ctx);
      return List.of();
    }
  }

  // ── M9 T3 的命令夹具 ────────────────────────────────────────────────

  private static CommandEnvelope renameEnvelope(
      BranchId branch, long expectedRevision, String name) {
    String id = UUID.randomUUID().toString();
    return new CommandEnvelope(
        id,
        id,
        "test:query",
        branch,
        new RevisionId(expectedRevision),
        "unit.RenameUnit",
        "{\"id\":\"u-1\",\"name\":\"" + name + "\"}");
  }

  private static ForkBranch forkEnvelope(BranchId source, long expectedRevision, String newBranch) {
    String id = UUID.randomUUID().toString();
    return new ForkBranch(
        id, id, "test:query", source, new RevisionId(expectedRevision), new BranchId(newBranch));
  }

  private static String nameOf(SimulationState state) {
    UnitSnapshot snapshot =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return snapshot.state().units().get(U1).name();
  }

  private static BranchId side() {
    return new BranchId("side");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit())));
    SocialData social = new SocialData(new LinkedHashMap<>(Map.of(H11, populationSeries())));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis, List.of(new MapCodec(), new SocialCodec(), new UnitCodec())));
  }

  private static Unit unit() {
    return new Unit(
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
  }

  /** 与 {@code PopulationSeriesTest.seed()} 同款：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries populationSeries() {
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

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
