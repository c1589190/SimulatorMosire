package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.world.RichWorld;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdSnapshot;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 决策文档只读面端到端验收：{@code GET /api/sd/decision-docs}（Docs 系统，2026-09-23）。
 *
 * <p>★★ **本端点就是"以某个决策人的视角预览它实际能看到哪些文档"**（用户对 Docs 的原始表述是「限定范围到单个决策人的文档信息查看
 * 系统」）。夹具刻意造出三条轴都齐的形态——**显式指派**（tags）、**归属自动**（affiliations）、**无主**（两轴都空）—— 只报其一的实现在这里会红。
 *
 * <p>★ **文档经真写路径种入**（{@code POST /api/command} 发 {@code sd.PutInfo}），不是直接拼状态：可见性判据读的是真条目。
 */
class SdDecisionDocsApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    // ★ 空库不播种：与 CLI 路径同源地种一次 genesis（{@code ShellMain.seedGenesisIfEmpty} 用的是同一个
    //   {@code RichWorld}）。本用例只需要国家/决策人/文档，都经真命令建。
    shell.coreSimos().bootstrapGenesis(RichWorld.state(shell.config().mapId()));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 判据一：两轴取并集，无主对谁都不出现 ────────────────────────────────────────

  @Test
  void aDecisionMakerSeesTheUnionOfTaggedAndAffiliationDocsAndNeverTheUnowned() throws Exception {
    createFixture();

    assertThat(docIds(getJson(docs("dm-a"))))
        .as("★ dm-a（n1）：显式指派给自己的 + 归属自动的")
        .containsExactlyInAnyOrder("alpha-brief", "shared-brief");
    assertThat(docIds(getJson(docs("dm-c"))))
        .as("★ dm-c 与 dm-a **同属 n1** ⇒ 看得到归属文档，但看不到指派给 dm-a 的那条（tags 只认自己）")
        .containsExactly("shared-brief");
    assertThat(docIds(getJson(docs("dm-b"))))
        .as("★ dm-b 属 n2 ⇒ 只看得到 n2 的归属文档")
        .containsExactly("beta-brief");
    for (String actor : List.of("dm-a", "dm-b", "dm-c")) {
      assertThat(docIds(getJson(docs(actor))))
          .as("★ %s 看不到无主文档（两个轴都不命中 ⇒ fail-closed）", actor)
          .doesNotContain("secret");
    }
    assertThat(infoIds())
        .as("前提：无主文档确实在 INFO 层里（否则上面的『看不到』是假象）")
        .contains("sd:doc.secret#0");
  }

  // ── 判据二：docId 精确取一篇；"不存在"与"无权看"返回同一个回答 ─────────────────────

  @Test
  void aDocIdFetchesExactlyOneAndAnInvisibleOneLooksExactlyLikeAnUnknownOne() throws Exception {
    createFixture();

    JsonNode one = getJson(docs("dm-a") + "&docId=alpha-brief");
    assertThat(docIds(one)).as("★ 给了 docId ⇒ 只回那一篇").containsExactly("alpha-brief");
    assertThat(one.get("docs").get(0).get("value").asText())
        .as("★ value 原样返回（端点不解析、不改写）")
        .isEqualTo("{\"title\":\"Alpha 简报\"}");

    String invisible = getJson(docs("dm-a") + "&docId=beta-brief").toString();
    String unknown = getJson(docs("dm-a") + "&docId=no-such-doc").toString();
    assertThat(invisible)
        .as("★ 两者响应逐字相同：否则读出的是『这篇文档存在』（一个不该漏的信息）")
        .isEqualTo(unknown);
  }

  // ── 判据三：错误口径 ───────────────────────────────────────────────────────────

  @Test
  void aMissingActorIsRejectedAndAnUnknownActorIsNotFound() throws Exception {
    createFixture();

    assertThat(get("/api/sd/decision-docs").statusCode()).as("as 必填").isEqualTo(400);
    assertThat(get("/api/sd/decision-docs?as=%20").statusCode()).as("as 空白 ⇒ 400").isEqualTo(400);
    HttpResponse<String> unknown = get("/api/sd/decision-docs?as=dm-nope");
    assertThat(unknown.statusCode()).as("未知决策人 ⇒ 404（不是空列表）").isEqualTo(404);
    assertThat(error(unknown)).as("与既有决策人面同款文案").contains("decision maker not found");
  }

  @Test
  void boundParametersAreRejectedRatherThanSilentlyTruncated() throws Exception {
    createFixture();

    assertThat(get(docs("dm-a") + "&limit=999").statusCode()).as("limit 超上限 ⇒ 400").isEqualTo(400);
    assertThat(get(docs("dm-a") + "&tick=0&fromTick=0").statusCode())
        .as("tick 与区间互斥 ⇒ 400")
        .isEqualTo(400);
    assertThat(get(docs("dm-a") + "&limit=abc").statusCode()).as("limit 非整数 ⇒ 400").isEqualTo(400);
  }

  // ── 判据四：响应形状（前端契约）────────────────────────────────────────────────

  @Test
  void responseShapeMatchesTheFrontendContract() throws Exception {
    createFixture();

    JsonNode body = getJson(docs("dm-a"));
    assertThat(body.get("docs")).as("docs 是数组").isNotNull();
    assertThat(body.get("count").asInt()).isEqualTo(body.get("docs").size());
    JsonNode row = body.get("docs").get(0);
    for (String key : List.of("docId", "id", "tick", "tags", "affiliations", "key", "value", "at")) {
      assertThat(row.has(key)).as("前端契约字段 %s 必须在", key).isTrue();
    }
    assertThat(row.get("at").get("branch").asText()).isEqualTo("main");
    assertThat(row.get("at").has("revision")).isTrue();

    // 空集不是静默成功：明确可读的"没有"。
    JsonNode empty = getJson(docs("dm-b") + "&tick=999");
    assertThat(empty.get("count").asInt()).isZero();
    assertThat(empty.get("note").asText()).as("★ 明确可读的『没有可查看的文档』").contains("没有可查看的文档");
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private static String docs(String actor) {
    return "/api/sd/decision-docs?as=" + actor;
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static List<String> docIds(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : body.get("docs")) {
      out.add(row.get("docId").asText());
    }
    return out;
  }

  private static String error(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body()).get("error").asText();
  }

  /** 服务端状态里 INFO 层全部条目的 id（"那条确实在状态里"的前提断言用）。 */
  private List<String> infoIds() {
    SimulationState state = shell.queryService().stateAt(QueryTarget.head(main()));
    List<String> out = new ArrayList<>();
    for (List<SdInfoEntry> entries :
        ((SdSnapshot) state.module("sd").orElseThrow()).state().info().values()) {
      for (SdInfoEntry entry : entries) {
        out.add(entry.id().value());
      }
    }
    return out;
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  /** 真世界（genesis）里的区域 id（升序，可复现）。 */
  private List<String> regionIds() {
    SimulationState genesis =
        shell.coreSimos().replay(new StateRef(main(), new RevisionId(1)));
    GameMap map = ((MapSnapshot) genesis.module("map").orElseThrow()).map();
    List<String> out = new ArrayList<>();
    for (RegionId id : map.regions().keySet()) {
      out.add(id.value());
    }
    java.util.Collections.sort(out);
    assertThat(out).as("genesis 世界必须有区域（否则本用例的国家建不起来）").hasSizeGreaterThanOrEqualTo(2);
    return out;
  }

  private void submit(String type, String payloadJson) throws Exception {
    long expected = shell.coreSimos().head(main()).orElseThrow().value();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("type", type);
    request.put("payloadJson", payloadJson);
    request.put("branch", "main");
    request.put("expectedRevision", expected);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/command"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(request), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("提交 %s: %s", type, response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");
  }

  /** {@code sd.PutInfo} 发一条文档（{@code affiliations} 为 null 时不带该键）。 */
  private void putDoc(String docId, String value, String tagsJson, String affiliationsJson)
      throws Exception {
    StringBuilder payload = new StringBuilder("{\"address\":\"sd:doc.").append(docId);
    payload.append("\",\"key\":\"doc\",\"value\":\"").append(escape(value)).append('"');
    if (tagsJson != null) {
      payload.append(",\"tags\":").append(tagsJson);
    }
    if (affiliationsJson != null) {
      payload.append(",\"affiliations\":").append(affiliationsJson);
    }
    payload.append('}');
    submit("sd.PutInfo", payload.toString());
  }

  /** JSON 字符串里再嵌 JSON 文本：只需转义反斜杠与引号（夹具里的正文不含别的特殊字符）。 */
  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 两个国家（n1/n2）+ 三个决策人（dm-a/dm-b/dm-c）+ 四条 INFO：
   *
   * <pre>
   *   sd:doc.alpha-brief   tags={dm-a}   affiliations={}    （显式指派）
   *   sd:doc.shared-brief  tags={}       affiliations={n1}  （归属自动）
   *   sd:doc.beta-brief    tags={}       affiliations={n2}  （归属自动，另一国）
   *   sd:doc.secret        tags={}       affiliations={}    （无主）
   * </pre>
   */
  private void createFixture() throws Exception {
    // ★ 本国国土区域 id 从**真世界里读**（RichWorld 的区域是生成的，写死一个 id 会被 CreateNation 的
    //   "homeRegion 不存在"正确拒掉），并按 R13 先用 map.UpdateRegion 补上 {@code nation:<id>} 前缀
    //   ——否则 CreateNation 会以"Region … 无国家 tag"拒掉。
    List<String> regions = regionIds();
    submit(
        "map.UpdateRegion",
        "{\"regionId\":\"" + regions.get(0) + "\",\"meta\":{\"tag\":\"nation:n1\"}}");
    submit(
        "map.UpdateRegion",
        "{\"regionId\":\"" + regions.get(1) + "\",\"meta\":{\"tag\":\"nation:n2\"}}");
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\""
            + regions.get(0)
            + "\",\"adminBudgetPerTick\":10}");
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n2\",\"name\":\"乙国\",\"homeRegionId\":\""
            + regions.get(1)
            + "\",\"adminBudgetPerTick\":10}");
    for (String[] dm :
        new String[][] {
          {"dm-a", "nation", "n1"}, {"dm-c", "nation", "n1"}, {"dm-b", "nation", "n2"}
        }) {
      submit(
          "sd.CreateDecisionMaker",
          "{\"id\":\""
              + dm[0]
              + "\",\"affiliation\":{\"kind\":\""
              + dm[1]
              + "\",\"id\":\""
              + dm[2]
              + "\"},\"allowedTools\":[],\"cadence\":1}");
    }

    putDoc("alpha-brief", "{\"title\":\"Alpha 简报\"}", "[\"dm-a\"]", null);
    putDoc("shared-brief", "{\"title\":\"共享简报\"}", null, "[{\"kind\":\"nation\",\"id\":\"n1\"}]");
    putDoc("beta-brief", "{\"title\":\"Beta 简报\"}", null, "[{\"kind\":\"nation\",\"id\":\"n2\"}]");
    putDoc("secret", "{\"title\":\"无主\"}", null, null);
  }
}
