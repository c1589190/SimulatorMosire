package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R4/R5：真实 {@link Shell} 中 {@code map.ImportRaster} 的 GM-only 注册、单命令写入与关壳/重开回读。
 *
 * <p>判别力：写入走 GUI 的 {@code POST /api/command}（真 handler、真 Core 提交），回读走 {@code GET /api/map/*}（每次重放
 * checkpoint）， 再**关壳重开同一 store** 回读同一格高度/地形/块与 revision 数。断言不读发送写请求的那个内存对象；若导入被拆成多条命令、或没有持久化，重开后的
 * revision 数/格状态会红。拒绝用例断言 422 + revision 不变，证明失败不部分写入。
 */
class RasterImportEndToEndTest {
  private static final BranchId MAIN = new BranchId("main");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration WAIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  @Test
  void oneCommandImportSurvivesCloseAndReopenWithSameHeightTerrainAndBlocks() throws Exception {
    ShellConfig config = blankEditorConfig();
    String firstBlocks;
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      assertThat(getJson(shell, "/api/map/overview").get("hexCount").asInt()).isZero();

      JsonNode committed =
          postCommand(
              shell,
              "map.ImportRaster",
              "{\"mode\":\"merge\",\"cells\":["
                  + "{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":0.37},"
                  + "{\"q\":2,\"r\":0,\"terrain\":\"mountains\",\"height\":0.8125}]}",
              1L);
      assertThat(committed.get("result").asText()).isEqualTo("committed");
      assertThat(committed.get("ref").get("revision").asLong())
          .as("一次导入 = 一条 revision")
          .isEqualTo(2L);
      assertThat(shell.coreSimos().revisions(MAIN)).as("单命令，不是逐格多命令").hasSize(2);

      assertImportedMap(shell, 2);
      firstBlocks = getJson(shell, "/api/map/overview").get("blocks").toString();
    }

    try (Shell reopened = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(reopened)).as("非空库不重新创世").isFalse();
      assertThat(reopened.coreSimos().revisions(MAIN)).as("revision 数在重启后不变").hasSize(2);
      assertImportedMap(reopened, 2);
      assertThat(getJson(reopened, "/api/map/overview").get("blocks").toString())
          .as("地形块切分在重启后逐字一致")
          .isEqualTo(firstBlocks);
    }
  }

  @Test
  void invalidImportIsRejectedWithoutAnyRevisionOrMapChange() throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();

      HttpResponse<String> response =
          post(
              shell,
              "/api/command",
              JSON.writeValueAsString(
                  Map.of(
                      "type",
                      "map.ImportRaster",
                      "payloadJson",
                      "{\"mode\":\"merge\",\"cells\":["
                          + "{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":0.4},"
                          + "{\"q\":0,\"r\":0,\"terrain\":\"ocean\",\"height\":0.1}]}",
                      "branch",
                      "main",
                      "expectedRevision",
                      1)));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
      assertThat(shell.coreSimos().revisions(MAIN)).as("拒绝不产生 revision").hasSize(1);
      assertThat(getJson(shell, "/api/map/overview").get("hexCount").asInt()).isZero();
      assertThat(get(shell, "/api/map/hex?q=0&r=0").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void importToolIsOnTheGmFaceAndAbsentFromTheDecisionFace() throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      Set<String> gmNames =
          shell.toolsFor(SimosToolSource.Role.GM).stream()
              .map(tool -> tool.name())
              .collect(Collectors.toSet());
      Set<String> decisionNames =
          shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
              .map(tool -> tool.name())
              .collect(Collectors.toSet());
      assertThat(gmNames).as("map.ImportRaster 必须在 GM 桶").contains("map.ImportRaster");
      assertThat(decisionNames)
          .as("GM-only：决策人桶不得拿到地图写入口")
          .doesNotContain("map.ImportRaster")
          .doesNotContain("map.SetTerrain");
    }
  }

  private ShellConfig blankEditorConfig() {
    return ShellConfig.defaults(tempDir).withWorldId(WorldRegistry.BLANK_EDITOR).withPorts(0, 0);
  }

  private static void assertImportedMap(Shell shell, long revision) throws Exception {
    JsonNode overview = getJson(shell, "/api/map/overview");
    assertThat(overview.get("hexCount").asInt()).as("两格导入").isEqualTo(2);
    assertThat(overview.get("terrainTypes").size())
        .as("blank-editor 的标准地形词表不变（不另立词表）")
        .isEqualTo(io.mosire.simos.map.terrain.TerrainCatalog.defaults().size());
    JsonNode blocks = overview.get("blocks");
    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).get("hexCount").asInt()).isEqualTo(1);
    assertThat(blocks.get(1).get("hexCount").asInt()).isEqualTo(1);
    Set<String> blockTerrains =
        Set.of(blocks.get(0).get("terrain").asText(), blocks.get(1).get("terrain").asText());
    assertThat(blockTerrains).containsExactlyInAnyOrder("plains", "mountains");

    JsonNode plains = getJson(shell, "/api/map/hex?q=0&r=0");
    assertThat(plains.get("terrain").asText()).isEqualTo("plains");
    assertThat(plains.get("height").asDouble()).isEqualTo(0.37);
    JsonNode mountains = getJson(shell, "/api/map/hex?q=2&r=0");
    assertThat(mountains.get("terrain").asText()).isEqualTo("mountains");
    assertThat(mountains.get("height").asDouble()).isEqualTo(0.8125);

    SimulationState replayed =
        shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(revision)));
    GameMap map = ((MapSnapshot) replayed.module("map").orElseThrow()).map();
    TerrainBlocks.requirePartition(map.hexes(), map.terrainBlocks());
  }

  private static JsonNode postCommand(
      Shell shell, String type, String payloadJson, long expectedRevision) throws Exception {
    HttpResponse<String> response =
        post(
            shell,
            "/api/command",
            JSON.writeValueAsString(
                Map.of(
                    "type",
                    type,
                    "payloadJson",
                    payloadJson,
                    "branch",
                    "main",
                    "expectedRevision",
                    expectedRevision)));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> post(Shell shell, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(shell, path))
            .timeout(WAIT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> get(Shell shell, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri(shell, path)).timeout(WAIT).GET().build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static JsonNode getJson(Shell shell, String path) throws Exception {
    HttpResponse<String> response = get(shell, path);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static URI uri(Shell shell, String path) {
    return URI.create("http://" + shell.config().bindAddress() + ":" + shell.boundGuiPort() + path);
  }
}
