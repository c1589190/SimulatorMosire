package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
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
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /api/map/hex} 的**入射连通性**字段（M8 T11）。
 *
 * <p>★ **为什么加这个字段**：连通性此前在 app 层**没有任何读路径**（overview 只有块、mapHex 只有地形），于是 T11 的两条 ★ 判据——"`merge` 后既有
 * tag **仍在**"与"`replace` 后**只剩新的**"——在浏览器里**观测不到**，那样的 e2e 断言是装饰（形态 3）。本字段是 **只读**增量，不改任何写路径。
 *
 * <p>★ **夹具是"两种实现会分叉"的形态**（不是随手挑的）：
 *
 * <ul>
 *   <li>{@code byPathway} 的插入序是 {@code road → river}，响应必须是 {@code ["river","road"]}（**字典序**）；
 *       照插入序发的实现当场红。
 *   <li>{@code edges} 组件里 {@code E2} 插在 {@code E1} **之前**，{@code (1,2)} 的入射表必须是 {@code [E1,
 *       E2]}（{@link EdgeRef} 自然序，**不是字符串序**也不是插入序）；
 *   <li>另有一条"同一 revision 两次响应**逐字节相同**"的断言——顺序不确定的实现在这里就会抖。
 * </ul>
 *
 * <p>★ **只断言入射关系与 pathway 键**，不断言 props（当前命令载荷里 props 恒为空表，spec §二）。
 */
class MapHexEdgesApiTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H14 = new HexCoord(1, 4); // 无入射边 ⇒ 空数组

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void mapHexCarriesIncidentEdgesSortedByEdgeRefAndPathwayKey() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=1");

    JsonNode edges = body.get("edges");
    assertThat(edges).as("edges 是数组（键名就是 edges）").isNotNull();
    assertThat(edges).hasSize(1);
    assertThat(edges.get(0).get("edge").asText()).as("规范序串").isEqualTo("1_1|1_2");
    // ★ 判别力：夹具按 road → river 插入，响应必须是字典序；照插入序的实现当场红。
    assertThat(edges.get(0).get("pathways")).hasSize(2);
    assertThat(edges.get(0).get("pathways").get(0).asText()).isEqualTo("river");
    assertThat(edges.get(0).get("pathways").get(1).asText()).isEqualTo("road");
  }

  @Test
  void mapHexOnASharedHexListsBothIncidentEdgesInEdgeRefOrder() throws Exception {
    JsonNode edges = getJson("/api/map/hex?q=1&r=2").get("edges");

    assertThat(edges).as("(1,2) 是两条边的公共端点").hasSize(2);
    // ★ 判别力：夹具里 E2 插在 E1 **之前**，响应必须是 EdgeRef 自然序（先 (1,1)-(1,2)，后 (1,2)-(1,3)）。
    assertThat(edges.get(0).get("edge").asText()).isEqualTo("1_1|1_2");
    assertThat(edges.get(1).get("edge").asText()).isEqualTo("1_2|1_3");
    assertThat(edges.get(1).get("pathways").get(0).asText()).isEqualTo("river");
  }

  @Test
  void mapHexWithoutEdgesGivesEmptyArray() throws Exception {
    JsonNode edges = getJson("/api/map/hex?q=1&r=4").get("edges");

    assertThat(edges).as("H14 一条边都不沾").isNotNull();
    assertThat(edges.isArray()).isTrue();
    assertThat(edges).isEmpty();
  }

  @Test
  void mapHexEdgesAreByteIdenticalAcrossTwoCalls() throws Exception {
    String first = get("/api/map/hex?q=1&r=2").body();
    String second = get("/api/map/hex?q=1&r=2").body();

    JsonNode edges = JSON.readTree(first).get("edges");
    assertThat(edges).as("先断言聚合非空，别把空==空当成功").isNotNull();
    assertThat(edges.size()).isEqualTo(2);
    assertThat(second).as("同一 revision 两次响应逐字节相同（排序是它的前提）").isEqualTo(first);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 创世 {@code (main,1)}：三格走廊 + 两条边（{@code E2} 先入表，用于钉住响应排序）。 */
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
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, new UnitState(new LinkedHashMap<>())),
                "social",
                    new SocialSnapshot(ref("main", 1), T7, new SocialData(new LinkedHashMap<>())),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    hexes.put(H14, new HexCell(0.5)); // 不沾任何边
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    EdgeRef e1 = new EdgeRef(H11, H12);
    EdgeRef e2 = new EdgeRef(H12, H13);
    Map<EdgeRef, EdgeTags> edges = new LinkedHashMap<>();
    edges.put(e2, tags("river")); // ★ E2 先入表：响应仍须按 EdgeRef 自然序
    edges.put(e1, tags("road", "river")); // ★ road 先入 byPathway：响应仍须字典序
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        edges,
        GenerationSpec.defaults(0L));
  }

  private static EdgeTags tags(String... pathwayIds) {
    Map<String, Map<String, Object>> byPathway = new LinkedHashMap<>();
    for (String id : pathwayIds) {
      byPathway.put(id, Map.of());
    }
    return new EdgeTags(byPathway);
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
