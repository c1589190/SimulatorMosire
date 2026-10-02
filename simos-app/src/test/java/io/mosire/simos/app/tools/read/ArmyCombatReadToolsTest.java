package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.army.combats} / {@code simos.army.combat}（阶段 D1 / D-012）读口判据：
 *
 * <ul>
 *   <li>过滤：{@code tick}、{@code q/r}（半给 ⇒ BAD_REQUEST），清单按 id 字典序；
 *   <li><b>可见性</b>：逐条按记录所在格判（{@link ToolSupport#hexVisible}）——看不见的格上的记录**不进结果**（不是整调拒）；
 *   <li><b>不可见与不存在同款</b>：单条详情对"记录存在但格不可见"与"记录不存在"给逐字同形的 {@code NOT_FOUND}（不泄露"有这条记录但你看不到"）；
 *   <li>清单与详情同形（都走 {@code ApiViews.armyCombat}）。
 * </ul>
 */
class ArmyCombatReadToolsTest {

  private static final String MAP_ID = "Map1";
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void listWithoutFiltersReturnsAllRecordsSortedById() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("list"))) {
      ToolResult result = fx.list(fxContext(fx, "Map1", Map.of()));
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode rows = JSON.readTree(result.message()).get("combats");
      assertThat(ids(rows)).containsExactly("c-1", "c-2", "c-3");
      assertThat(rows.get(1).get("kind").asText()).isEqualTo("野战");
      assertThat(rows.get(1).get("tick").asLong()).isEqualTo(8L);
      assertThat(rows.get(1).get("hex").get("q").asInt()).isEqualTo(1);
      assertThat(rows.get(1).get("hex").get("r").asInt()).isEqualTo(2);
      assertThat(values(rows.get(1).get("participants"))).containsExactly("u-2");
      assertThat(rows.get(1).get("text").asText()).isEqualTo("过程 c-2");
      assertThat(rows.get(1).get("stages")).hasSize(1);
      assertThat(rows.get(1).get("losses")).isEmpty();
    }
  }

  @Test
  void listFiltersByTickAndByHexAndRejectsHalfHex() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("filters"))) {
      ToolResult byTick = fx.list(fxContext(fx, "Map1", Map.of("tick", 8L)));
      assertThat(ids(JSON.readTree(byTick.message()).get("combats")))
          .as("tick=8 恰两条（不是隐含当前 tick）")
          .containsExactly("c-2", "c-3");

      ToolResult byHex = fx.list(fxContext(fx, "Map1", Map.of("q", 1L, "r", 1L)));
      assertThat(ids(JSON.readTree(byHex.message()).get("combats"))).containsExactly("c-1", "c-3");

      ToolResult half = fx.list(fxContext(fx, "Map1", Map.of("q", 1L)));
      assertThat(half.success()).isFalse();
      assertThat(half.code()).isEqualTo("BAD_REQUEST");
      assertThat(half.message()).contains("q 与 r 同时给");

      ToolResult both = fx.list(fxContext(fx, "Map1", Map.of("tick", 8L, "q", 1L, "r", 1L)));
      assertThat(ids(JSON.readTree(both.message()).get("combats")))
          .as("tick 与格过滤是交集")
          .containsExactly("c-3");
    }
  }

  @Test
  void invisibleHexRecordsAreOmittedFromTheListNotRejected() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("invisible-list"))) {
      ToolResult result = fx.list(fxContext(fx, "Map1/hex/1_2", Map.of()));
      assertThat(result.success()).as("整调仍成功；只是看不见的记录不进结果").isTrue();
      assertThat(ids(JSON.readTree(result.message()).get("combats")))
          .as("只有 H12 上的记录可见")
          .containsExactly("c-2");
    }
  }

  @Test
  void detailForInvisibleRecordAndMissingRecordAreTheSameNotFound() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("not-found"))) {
      ToolResult invisible = fx.detail(fxContext(fx, "Map1/hex/1_2", Map.of("id", "c-1")));
      ToolResult missing = fx.detail(fxContext(fx, "Map1/hex/1_2", Map.of("id", "c-404")));

      assertThat(invisible.success()).isFalse();
      assertThat(missing.success()).isFalse();
      assertThat(invisible.code()).as("不可见与不存在同款码").isEqualTo("NOT_FOUND");
      assertThat(missing.code()).isEqualTo("NOT_FOUND");
      assertThat(invisible.message()).isEqualTo("交战记录不存在: c-1");
      assertThat(missing.message()).isEqualTo("交战记录不存在: c-404");
      assertThat(invisible.message())
          .as("不得泄露'存在但不可见'（无可见性/权限字样）")
          .doesNotContain("可见")
          .doesNotContain("权限")
          .doesNotContain("不可见");
    }
  }

  @Test
  void visibleDetailReturnsTheSameShapeAsTheListRow() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("detail-shape"))) {
      ToolContext visible = fxContext(fx, "Map1/hex/1_2", Map.of("id", "c-2"));
      ToolResult detail = fx.detail(visible);
      assertThat(detail.success()).as(detail.message()).isTrue();
      JsonNode row = JSON.readTree(detail.message());
      assertThat(row.get("id").asText()).isEqualTo("c-2");
      assertThat(row.get("kind").asText()).isEqualTo("野战");
      assertThat(row.get("hex").get("q").asInt()).isEqualTo(1);
      assertThat(row.get("hex").get("r").asInt()).isEqualTo(2);
      assertThat(values(row.get("participants"))).containsExactly("u-2");

      ToolResult list = fx.list(fxContext(fx, "Map1/hex/1_2", Map.of()));
      JsonNode listRow = JSON.readTree(list.message()).get("combats").get(0);
      assertThat(row.toString())
          .as("详情与清单同形（同一 ApiViews.armyCombat）")
          .isEqualTo(listRow.toString());
    }
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────────

  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final ArmyCombatsTool listTool;
    final ArmyCombatTool detailTool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.listTool = new ArmyCombatsTool(query, MAP_ID);
      this.detailTool = new ArmyCombatTool(query, MAP_ID);
    }

    static Fixture open(Path storeDir) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      core.register(new MapCodec()).register(new ArmyCodec());
      Fixture fixture = new Fixture(core);
      GameMap map = ScopeFixtures.mapOf();
      StateRef ref = new StateRef(main(), new RevisionId(1));
      SimulationState genesis =
          new SimulationState(
              new StateMeta(ref, T7),
              Map.of(
                  "map", new MapSnapshot(ref, T7, map),
                  "army", new ArmySnapshot(ref, T7, armyData())),
              InMemoryInfoSystem.empty());
      core.bootstrapGenesis(genesis);
      return fixture;
    }

    ToolResult list(ToolContext context) {
      return execute(listTool, context);
    }

    ToolResult detail(ToolContext context) {
      return execute(detailTool, context);
    }

    private static ToolResult execute(AgentTool tool, ToolContext context) {
      return tool.execute(
          new ToolContext(
                  context.caller(),
                  context.permissions(),
                  context.config(),
                  context.arguments(),
                  context.identity())
              .withResources(ResourceAuthorizer.of(context.permissions(), tool.resources())));
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static ArmyData armyData() {
    Map<CombatRecordId, CombatRecord> records = new LinkedHashMap<>();
    records.put(new CombatRecordId("c-1"), record("c-1", 7L, H11, List.of(U1), "过程 c-1"));
    records.put(new CombatRecordId("c-2"), record("c-2", 8L, H12, List.of(U2), "过程 c-2"));
    records.put(new CombatRecordId("c-3"), record("c-3", 8L, H11, List.of(U1, U2), "过程 c-3"));
    return new ArmyData(records);
  }

  private static CombatRecord record(
      String id, long tick, HexCoord hex, List<UnitId> participants, String text) {
    return new CombatRecord(
        new CombatRecordId(id),
        "野战",
        tick,
        hex,
        participants,
        text,
        List.of(
            new CombatStage(
                new CombatStageId("start"),
                "初始阶段",
                participants,
                text,
                List.of(),
                Optional.empty(),
                Optional.empty())));
  }

  /** 读上下文：{@code mapScopePrefix} = "Map1"（全域）或 "Map1/hex/1_2"（只有一格可见）。 */
  private static ToolContext fxContext(
      Fixture fx, String mapScopePrefix, Map<String, Object> extraArgs) {
    Map<String, Object> args = new LinkedHashMap<>(extraArgs);
    ResourceScopeMap scopes =
        ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.of(mapScopePrefix));
    AgentPermissionSet permissions =
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            false,
            false,
            false,
            scopes);
    return new ToolContext(
        AccessToken.DEFAULT, permissions, Map.of(), args, AgentIdentity.external());
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static List<String> ids(JsonNode rows) {
    List<String> out = new ArrayList<>();
    rows.forEach(row -> out.add(row.get("id").asText()));
    return out;
  }

  private static List<String> values(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }
}
