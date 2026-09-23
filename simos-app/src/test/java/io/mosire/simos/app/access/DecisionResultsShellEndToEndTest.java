package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.read.DecisionResultsTool;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.core.CoreSimos;
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
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ **决策人端到端**（第 3 波第 3 步如实记下的缺口）：从**真 {@link Shell} 的决策人桶**里取出 {@code sd.DecisionResults}， 经**真
 * {@link DecisionCallerFactory}**（与壳装配期那一个同源：{@code
 * DecisionCallerFactory.defaults(toolAuthorizer)}） 调一次，拿到数据。
 *
 * <p>★ **为什么必须有这一条**：{@code DecisionResultsToolTest} 证的是"可见性判据"（手工 new 一个工具、手工装夹具世界）——
 * 它证不了"壳真的把这条工具挂进了决策人桶"、"真权限组真的放它过去"、"工具从真状态里真的读得到那几条"。三件事任一装配错， 那个用例照样全绿。
 *
 * <p>★ **桶的判别力**：先断言 {@code sd.DecisionResults} **在**决策人桶、**不在** GM 桶（后者是本条工具的设计：GM 走 GUI 面）——
 * 少了这条，"从错桶里取"（比如将来图省事塞进 GM 桶）不会被发现。
 *
 * <p>★ **不占固定端口、不常驻**：{@code withPorts(0, 0, 0)}，用完 {@code shell.close()}。
 */
class DecisionResultsShellEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final DecisionMakerId DM = new DecisionMakerId("dm-e2e");

  private static final String ADDR_8 = address(8);
  private static final String ADDR_9 = address(9);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void theDecisionBucketToolReadsTheRealStateThroughTheRealCallerFactory() throws Exception {
    ToolRegistry decisionRegistry = new ToolRegistry();
    for (AgentTool tool : shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)) {
      decisionRegistry.register(tool);
    }
    assertThat(decisionRegistry.find(DecisionResultsTool.NAME))
        .as("★ sd.DecisionResults 必须在**决策人桶**里（它是挂给 Agent 决策人的读工具）")
        .isPresent();
    assertThat(names(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("★ 它**不在** GM 桶（GM 有 GUI 面与 sd 只读全权，不靠这条）")
        .doesNotContain(DecisionResultsTool.NAME);

    SimulationState state = shell.queryService().stateAt(QueryTarget.head(new BranchId("main")));
    DecisionMaker dm =
        ((SdSnapshot) state.module("sd").orElseThrow()).state().decisionMakers().get(DM);
    assertThat(dm).as("前提：决策人确实在真状态里").isNotNull();

    // ★ 真 DecisionCallerFactory（与 Shell 装配期同一个：defaults(真 toolAuthorizer)）——唯一入口。
    ToolResult result =
        DecisionCallerFactory.defaults(shell.toolAuthorizer())
            .execute(
                decisionRegistry,
                DecisionResultsTool.NAME,
                dm,
                state,
                shell.config().mapId(),
                Map.of());

    assertThat(result.success()).as("真路径必须拿到数据（拒因：%s）", result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    System.out.println("[decision-results e2e] " + body);
    assertThat(body.get("count").asInt()).as("真状态里 tags 含自己的那条取到了").isEqualTo(1);
    JsonNode row = body.get("results").get(0);
    assertThat(row.get("tick").asLong()).isEqualTo(8L);
    assertThat(row.get("id").asText()).isEqualTo(idOf(ADDR_8, 0));
    assertThat(row.get("tags")).hasSize(1);
    assertThat(row.get("tags").get(0).asText()).isEqualTo("dm-e2e");
    assertThat(row.get("value").isTextual()).as("value 是字符串（JSON 原文）").isTrue();
    assertThat(JSON.readTree(row.get("value").asText()).get("marker").asText())
        .as("value 是本条真值、可 parse")
        .isEqualTo("own@8");
  }

  @Test
  void anUnownedResultDoesNotReachTheToolEvenFromTheRealState() throws Exception {
    ToolRegistry decisionRegistry = new ToolRegistry();
    for (AgentTool tool : shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)) {
      decisionRegistry.register(tool);
    }
    SimulationState state = shell.queryService().stateAt(QueryTarget.head(new BranchId("main")));
    assertThat(infoIds(state)).as("前提：无主条目确实在真状态里").contains(idOf(ADDR_9, 0));

    ToolResult result =
        DecisionCallerFactory.defaults(shell.toolAuthorizer())
            .execute(
                decisionRegistry,
                DecisionResultsTool.NAME,
                ((SdSnapshot) state.module("sd").orElseThrow()).state().decisionMakers().get(DM),
                state,
                shell.config().mapId(),
                Map.of("tick", 9L));

    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("results")).as("★ 无主结果不出现（空集 ≠ 大家都能看）").isEmpty();
    assertThat(body.get("note").asText()).isNotEmpty();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private static List<String> names(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  private static List<String> infoIds(SimulationState state) {
    List<String> out = new java.util.ArrayList<>();
    for (List<SdInfoEntry> entries :
        ((SdSnapshot) state.module("sd").orElseThrow()).state().info().values()) {
      for (SdInfoEntry entry : entries) {
        out.add(entry.id().value());
      }
    }
    return out;
  }

  private static String address(long tick) {
    return Address.parse(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick).canonical();
  }

  private static String idOf(String canonicalAddress, int ordinal) {
    return SdInfoIds.synthesize(canonicalAddress, ordinal).value();
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** INFO 层：{@code sd:adjudication.8} 一条 tags={dm-e2e}；{@code sd:adjudication.9} 一条无主。 */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  new BranchId("main"),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, map()),
                "unit", new UnitSnapshot(ref("main", 1), T7, unitState()),
                "social",
                    new SocialSnapshot(ref("main", 1), T7, new SocialData(new LinkedHashMap<>())),
                "sd", new SdSnapshot(ref("main", 1), T7, sdState())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private static SdState sdState() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        DM,
        new DecisionMaker(
            DM, new Affiliation.Nation(new NationId("n-e2e")), Set.of(), AccessLimit.empty(), 1));
    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>();
    info.put(ADDR_8, List.of(entry(ADDR_8, 0, 8, Set.of(DM), "own@8")));
    info.put(ADDR_9, List.of(entry(ADDR_9, 0, 9, Set.of(), "unowned@9")));
    return SdState.empty().withDecisionMakers(makers).withInfo(info);
  }

  private static SdInfoEntry entry(
      String canonicalAddress, int ordinal, long tick, Set<DecisionMakerId> tags, String marker) {
    return new SdInfoEntry(
        SdInfoIds.synthesize(canonicalAddress, ordinal),
        tick,
        tags,
        "result",
        "{\"marker\":\"" + marker + "\"}",
        Optional.empty(),
        new RevisionId(1),
        Optional.empty());
  }

  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        new RegionId("r1"),
        Region.of(
            new RegionId("r1"),
            "区域一",
            Set.of(H11),
            new RegionMeta(null, NationTag.tagFor(new NationId("n-e2e")), null, null)));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        PathwayGroup.defaults(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static UnitState unitState() {
    UnitId u1 = new UnitId("u-1");
    Unit unit =
        new Unit(
            u1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of("步枪", 50),
            2,
            500,
            Optional.empty());
    return new UnitState(new LinkedHashMap<>(Map.of(u1, unit)));
  }
}
