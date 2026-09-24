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
 * {@code GET /api/map/block} —— 单格所在**地形块**（整块成员格 + 大小）：前端「油漆桶」的取块口（2026-09-24）。
 *
 * <p>★ **夹具具判别力**：三格连成一块 {@code plains}（点击**中间那格**必须回**三格**，只回一格的实现在此当场红）， 另有一格孤立的 {@code
 * desert}（大小 1）。
 *
 * <p>★ **fail-closed 两条**：图外格 ⇒ 404（与 {@code /api/map/hex} 同形）；{@code as=} ⇒ 400（本端点未接 data
 * redaction，不接受视角参数）。
 */
class MapBlockApiTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord P1 = new HexCoord(1, 1);
  private static final HexCoord P2 = new HexCoord(1, 2);
  private static final HexCoord P3 = new HexCoord(1, 3);
  private static final HexCoord D1 = new HexCoord(5, 5);

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
  void blockCarriesEveryMemberHexInNaturalOrder() throws Exception {
    JsonNode body = getJson("/api/map/block?q=1&r=2");

    assertThat(body.get("terrain").asText()).isEqualTo("plains");
    assertThat(body.get("hexCount").asInt()).as("★ 点中间那格 ⇒ 整块三格（只回一格即红）").isEqualTo(3);
    JsonNode hexes = body.get("hexes");
    assertThat(hexes).hasSize(3);
    assertThat(hexes.get(0).get("q").asInt()).isEqualTo(1);
    assertThat(hexes.get(0).get("r").asInt()).isEqualTo(1);
    assertThat(hexes.get(2).get("r").asInt()).as("自然序：末位是 (1,3)").isEqualTo(3);
  }

  @Test
  void singleHexBlockIsSizeOne() throws Exception {
    JsonNode body = getJson("/api/map/block?q=5&r=5");

    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("hexCount").asInt()).isEqualTo(1);
    assertThat(body.get("hexes")).hasSize(1);
  }

  @Test
  void hexOutsideTheMapIs404() throws Exception {
    HttpResponse<String> response = get("/api/map/block?q=9&r=9");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("hex not found");
  }

  @Test
  void asParameterIsRejectedFailClosed() throws Exception {
    HttpResponse<String> response = get("/api/map/block?q=1&r=1&as=dm-1");

    assertThat(response.statusCode()).as("未接 redaction 的端点不接受 as=").isEqualTo(400);
  }

  @Test
  void twoCallsAreByteIdentical() throws Exception {
    String first = get("/api/map/block?q=1&r=1").body();
    String second = get("/api/map/block?q=1&r=1").body();

    assertThat(JSON.readTree(first).get("hexCount").asInt()).as("先断言非平凡，别把空==空当成功").isEqualTo(3);
    assertThat(second).as("同一 revision 两次响应逐字节相同").isEqualTo(first);
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

  /** 创世 {@code (main,1)}：三格连片 {@code plains} + 一格孤立 {@code desert}。 */
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
                "map", new MapSnapshot(ref("main", 1), T7, blockMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, new UnitState(new LinkedHashMap<>())),
                "social",
                    new SocialSnapshot(
                        ref("main", 1), T7, new SocialData(new LinkedHashMap<>(), Map.of())),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private static GameMap blockMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(P1, new HexCell(0.15));
    hexes.put(P2, new HexCell(0.15));
    hexes.put(P3, new HexCell(0.15));
    hexes.put(D1, new HexCell(0.38));
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    terrainByHex.put(P1, "plains");
    terrainByHex.put(P2, "plains");
    terrainByHex.put(P3, "plains");
    terrainByHex.put(D1, "desert");
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put("plains", TerrainCatalog.of("plains"));
    terrainTypes.put("desert", TerrainCatalog.of("desert"));
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrainByHex),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
