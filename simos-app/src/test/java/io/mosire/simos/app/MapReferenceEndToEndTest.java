package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.map.terrain.TerrainHeights;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T0/T1/T2/T3/T4 的真实 Shell + GUI HTTP 端到端：参考图 POST/GET 与 MCP 共用同一服务、发布不推 revision、真实 PNG
 * 解码/尺寸/坐标校验/路径穿越/重复坐标/坏 base64 清晰拒绝；直接多格地形与 river/road merge/remove；关壳重开同 store 后地形、 连通性与参考图绑定仍可读。
 *
 * <p>判别力：地形/边一律经 {@code POST /api/command} 真 handler/Core 提交，回读走 {@code GET /api/map/*}（重放
 * checkpoint）， 最后关壳重开同一 store 再回读；若写入被拆多命令、没持久化，或参考图共享推了 revision，这里都红。
 */
class MapReferenceEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration WAIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  @Test
  void referenceViewAndFreeMapWritesRejectBadInputsAndSurviveRestart() throws Exception {
    ShellConfig config = blankEditorConfig();
    String referenceId;
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      assertThat(getJson(shell, "/api/map/overview").get("hexCount").asInt()).isZero();

      // 三格走廊：先由导入建格，之后 Paint/Connect 只能改已存在格。
      JsonNode imported =
          postCommand(
              shell,
              "map.ImportRaster",
              "{\"mode\":\"merge\",\"cells\":["
                  + "{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":0.4},"
                  + "{\"q\":0,\"r\":1,\"terrain\":\"plains\",\"height\":0.4},"
                  + "{\"q\":1,\"r\":1,\"terrain\":\"plains\",\"height\":0.4}]}",
              head(shell));
      assertThat(imported.get("result").asText()).isEqualTo("committed");
      long afterImport = head(shell);

      // 参考图 POST 不推 revision；同内容重复发布稳定 id。
      byte[] png = png(120, 90, new Color(20, 120, 70));
      String referenceBody = referenceBody(png, "africa.png");
      long beforePublish = head(shell);
      JsonNode first = postJson(shell, "/api/map/references", referenceBody);
      referenceId = first.get("reference").get("referenceId").asText();
      assertThat(referenceId).hasSize(64);
      assertThat(head(shell)).as("发布参考图不修改世界").isEqualTo(beforePublish);
      JsonNode second = postJson(shell, "/api/map/references", referenceBody);
      assertThat(second.get("reference").get("referenceId").asText()).isEqualTo(referenceId);
      assertThat(getJson(shell, "/api/map/references").get("references")).hasSize(1);
      assertThat(
              getJson(shell, "/api/map/references")
                  .get("references")
                  .get(0)
                  .get("cellCount")
                  .asInt())
          .isEqualTo(3);

      // 局部视图：真实 PNG 可解码、坐标/尺寸/裁剪来自服务端。
      JsonNode local =
          getJson(
              shell,
              "/api/map/reference?referenceId="
                  + referenceId
                  + "&q=0&r=0&radius=1&terrainOverlay=true");
      assertThat(local.get("branch").asText()).isEqualTo("main");
      assertThat(local.get("revision").asLong()).isEqualTo(afterImport);
      assertThat(local.get("overview").asBoolean()).isFalse();
      assertThat(local.get("cells")).as("半径 1 局部：中心 (0,0) + (0,1)；第三格 (1,1) 距离 2 不在半径内").hasSize(2);
      assertThat(local.get("cells").get(0).get("q").asInt()).isZero();
      assertThat(local.get("cells").get(0).get("built").asBoolean()).isTrue();
      assertThat(local.get("cells").get(0).get("terrain").asText()).isEqualTo("plains");
      BufferedImage decoded = decode(local.get("imageBase64").asText());
      assertThat(decoded).as("GUI 返回的 PNG 必须真能解码").isNotNull();
      assertThat(decoded.getWidth()).isLessThanOrEqualTo(1280);
      assertThat(local.get("image").get("width").asInt()).isEqualTo(decoded.getWidth());
      assertThat(local.get("sourceCrop").get("width").asDouble()).isPositive();

      JsonNode overview =
          getJson(shell, "/api/map/reference?referenceId=" + referenceId + "&radius=4");
      assertThat(overview.get("overview").asBoolean()).isTrue();
      assertThat(overview.get("cells")).as("概览不塞满全图 cells").isEmpty();
      assertThat(overview.get("imageBase64").asText()).isNotBlank();

      // 自由写：一次多格异类地形；river/road merge；road 局部 remove。
      long rev = head(shell);
      JsonNode painted =
          postCommand(
              shell,
              "map.PaintTerrain",
              "{\"cells\":["
                  + "{\"q\":0,\"r\":0,\"terrain\":\"mountains\",\"height\":0.9},"
                  + "{\"q\":0,\"r\":1,\"terrain\":\"desert\"}]}",
              rev);
      assertThat(painted.get("result").asText()).isEqualTo("committed");
      rev = head(shell);
      assertThat(rev).isEqualTo(afterImport + 1);

      JsonNode river =
          postCommand(
              shell,
              "map.ConnectHexes",
              "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":1},"
                  + "{\"q\":1,\"r\":1}],\"op\":\"merge\"}",
              rev);
      assertThat(river.get("result").asText()).isEqualTo("committed");
      rev = head(shell);
      JsonNode road =
          postCommand(
              shell,
              "map.ConnectHexes",
              "{\"kind\":\"road\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":1},"
                  + "{\"q\":1,\"r\":1}],\"op\":\"merge\"}",
              rev);
      assertThat(road.get("result").asText()).isEqualTo("committed");
      rev = head(shell);
      JsonNode removed =
          postCommand(
              shell,
              "map.ConnectHexes",
              "{\"kind\":\"road\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":1}],"
                  + "\"op\":\"remove\"}",
              rev);
      assertThat(removed.get("result").asText()).isEqualTo("committed");
      long finalRevision = head(shell);

      assertHex(shell, 0, 0, "mountains", 0.9);
      assertHex(shell, 0, 1, "desert", TerrainHeights.paintHeight("desert"));
      JsonNode h00 = getJson(shell, "/api/map/hex?q=0&r=0");
      assertPathways(h00, "0_0|0_1", "river");
      JsonNode h01 = getJson(shell, "/api/map/hex?q=0&r=1");
      assertPathways(h01, "0_0|0_1", "river");
      assertPathways(h01, "0_1|1_1", "river", "road");

      // 负例：非法坐标/半径/重复坐标/坏 base64/非图/as=/路径穿越全部清晰拒绝且不推 revision。
      long beforeBad = head(shell);
      assertRejectedCommand(
          shell,
          "map.PaintTerrain",
          "{\"cells\":[{\"q\":9,\"r\":9,\"terrain\":\"plains\"}]}",
          beforeBad,
          "图外");
      assertThat(head(shell)).isEqualTo(beforeBad);
      assertRejectedCommand(
          shell,
          "map.ConnectHexes",
          "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":1}],\"op\":\"merge\"}",
          beforeBad,
          "不相邻");
      assertThat(head(shell)).isEqualTo(beforeBad);

      assertBadRequest(
          shell, "/api/map/reference?referenceId=" + referenceId + "&q=0&radius=1", "同时给");
      assertBadRequest(
          shell, "/api/map/reference?referenceId=" + referenceId + "&q=0&r=0&radius=9", "radius");
      assertBadRequest(shell, "/api/map/reference?referenceId=" + "f".repeat(64), "不存在");
      assertBadRequest(shell, "/api/map/reference?referenceId=..%2F..%2Fetc%2Fpasswd", "id 非法");
      assertBadRequest(
          shell, "/api/map/reference?referenceId=" + referenceId + "&q=0&r=0&as=dm-1", "as=");
      assertBadRequest(shell, "/api/map/references?as=dm-1", "as=");

      assertBadPost(
          shell,
          "/api/map/references",
          JSON.writeValueAsString(
              Map.of(
                  "fileName",
                  "x.png",
                  "imageBase64",
                  "!!!",
                  "cells",
                  List.of(cell(0, 0, 10, 10, 8)))),
          "base64");
      assertBadPost(shell, "/api/map/references?as=dm-1", referenceBody, "as=");
      assertBadPost(shell, "/api/map/references", referenceBodyWithCells(png, List.of()), "cells");
      assertBadPost(
          shell,
          "/api/map/references",
          referenceBodyWithCells(png, List.of(cell(0, 0, 10, 10, 8), cell(0, 0, 20, 20, 8))),
          "重复坐标");
      assertBadPost(
          shell,
          "/api/map/references",
          referenceBodyWithCells(png, List.of(cell(0, 0, -1, 10, 8))),
          "格心");
      assertBadPost(
          shell,
          "/api/map/references",
          referenceBodyWithCells(png, List.of(cell(0, 0, 10, 10, 0))),
          "imageRadius");
      assertBadPost(
          shell,
          "/api/map/references",
          JSON.writeValueAsString(
              Map.of(
                  "fileName", "x.png",
                  "imageBase64",
                      Base64.getEncoder()
                          .encodeToString("not an image".getBytes(StandardCharsets.UTF_8)),
                  "cells", List.of(cell(0, 0, 10, 10, 8)))),
          "PNG 或 JPEG");
      assertBadPost(
          shell,
          "/api/map/references",
          JSON.writeValueAsString(
              Map.of(
                  "fileName",
                  " ",
                  "imageBase64",
                  Base64.getEncoder().encodeToString(png),
                  "cells",
                  List.of(cell(0, 0, 10, 10, 8)))),
          "fileName");

      assertThat(head(shell)).isEqualTo(beforeBad);
      assertThat(finalRevision).isEqualTo(beforeBad);
      assertThat(getJson(shell, "/api/map/overview").get("blocks")).isNotEmpty();
    }

    try (Shell reopened = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(reopened)).as("非空库不重新创世").isFalse();
      assertThat(head(reopened)).isEqualTo(6L);
      assertHex(reopened, 0, 0, "mountains", 0.9);
      assertHex(reopened, 0, 1, "desert", TerrainHeights.paintHeight("desert"));
      JsonNode h01 = getJson(reopened, "/api/map/hex?q=0&r=1");
      assertPathways(h01, "0_0|0_1", "river");
      assertPathways(h01, "0_1|1_1", "river", "road");
      assertThat(getJson(reopened, "/api/map/overview").get("blocks")).isNotEmpty();
      assertThat(getJson(reopened, "/api/map/references").get("references")).hasSize(1);
      JsonNode view =
          getJson(reopened, "/api/map/reference?referenceId=" + referenceId + "&q=0&r=1&radius=1");
      assertThat(view.get("cells")).hasSize(3);
      assertThat(view.get("cells").get(0).get("imageX").asDouble()).isPositive();
      assertThat(decode(view.get("imageBase64").asText())).isNotNull();
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private ShellConfig blankEditorConfig() {
    return ShellConfig.defaults(tempDir).withWorldId(WorldRegistry.BLANK_EDITOR).withPorts(0, 0);
  }

  private static long head(Shell shell) {
    return shell
        .coreSimos()
        .head(new io.mosire.simos.util.state.BranchId("main"))
        .orElseThrow()
        .value();
  }

  private static void assertHex(Shell shell, int q, int r, String terrain, double height)
      throws Exception {
    JsonNode hex = getJson(shell, "/api/map/hex?q=" + q + "&r=" + r);
    assertThat(hex.get("terrain").asText()).as("q=%s r=%s", q, r).isEqualTo(terrain);
    assertThat(hex.get("height").asDouble()).as("q=%s r=%s", q, r).isEqualTo(height);
  }

  private static void assertPathways(JsonNode hex, String edge, String... kinds) {
    boolean found = false;
    for (JsonNode item : hex.get("edges")) {
      if (edge.equals(item.get("edge").asText())) {
        found = true;
        List<String> actual = new ArrayList<>();
        item.get("pathways").forEach(pathway -> actual.add(pathway.asText()));
        assertThat(actual).containsExactlyInAnyOrder(kinds);
      }
    }
    assertThat(found).as("hex 入射边 %s", edge).isTrue();
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

  private static void assertRejectedCommand(
      Shell shell, String type, String payloadJson, long expectedRevision, String reason)
      throws Exception {
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
    assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
    assertThat(response.body()).contains(reason);
  }

  private static void assertBadRequest(Shell shell, String path, String reason) throws Exception {
    HttpResponse<String> response = get(shell, path);
    assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(400);
    assertThat(response.body()).contains(reason);
  }

  private static void assertBadPost(Shell shell, String path, String body, String reason)
      throws Exception {
    HttpResponse<String> response = post(shell, path, body);
    assertThat(response.statusCode()).as("POST %s -> %s", path, response.body()).isEqualTo(400);
    assertThat(response.body()).contains(reason);
  }

  private static String referenceBody(byte[] png, String fileName) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "fileName",
            fileName,
            "imageBase64",
            Base64.getEncoder().encodeToString(png),
            "cells",
            List.of(cell(0, 0, 30, 30, 12), cell(0, 1, 30, 60, 12), cell(1, 1, 60, 60, 12))));
  }

  private static String referenceBodyWithCells(byte[] png, List<Map<String, Object>> cells)
      throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "fileName",
            "x.png",
            "imageBase64",
            Base64.getEncoder().encodeToString(png),
            "cells",
            cells));
  }

  private static Map<String, Object> cell(int q, int r, double x, double y, double radius) {
    Map<String, Object> cell = new LinkedHashMap<>();
    cell.put("q", q);
    cell.put("r", r);
    cell.put("imageX", x);
    cell.put("imageY", y);
    cell.put("imageRadius", radius);
    return cell;
  }

  private static JsonNode postJson(Shell shell, String path, String body) throws Exception {
    HttpResponse<String> response = post(shell, path, body);
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

  private static BufferedImage decode(String base64) {
    try {
      return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] png(int width, int height, Color color) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(color);
      g.fillRect(0, 0, width, height);
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertThat(ImageIO.write(image, "png", out)).isTrue();
      return out.toByteArray();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static URI uri(Shell shell, String path) {
    return URI.create("http://" + shell.config().bindAddress() + ":" + shell.boundGuiPort() + path);
  }
}
