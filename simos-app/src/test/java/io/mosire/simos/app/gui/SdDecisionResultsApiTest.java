package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 决策结果子页端点端到端验收（第 3 波第 3 步的前端桥）：{@code GET /api/sd/decision-results}。
 *
 * <p>★ 起真 {@link Shell}（三端口全 0），用 JDK {@link HttpClient} 打真 HTTP；夹具直接种入创世 checkpoint（测的是**读路径的归属**，
 * 不是命令写路径）。INFO 层里预置"同一 tick 两个决策人各一条 + 一条共同 + 一条无主"（见 {@link #genesis()}）， 让"同一端点、不同 {@code as=} ⇒
 * 不同数据"一次可测。
 *
 * <p>★★ **判别力**：{@link #twoDecisionMakersInTheSameTickEachSeeOnlyTheirOwnResult} 若实现成"都能看"或"都看不到"
 * 都当场红；{@link #unownedResultIsInvisibleToEveryActor} 同时钉住"空集 ≠ 大家都能看"。
 *
 * <p>★ **前提断言**：每条"看不到"之前先断言那些条目**确实在服务端状态里**（经 {@code shell.queryService()} 读回），
 * 否则"过滤器坏了"与"夹具里根本没那条"分不开。
 */
class SdDecisionResultsApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final DecisionMakerId DM_A = new DecisionMakerId("dm-a");
  private static final DecisionMakerId DM_B = new DecisionMakerId("dm-b");
  private static final DecisionMakerId DM_C = new DecisionMakerId("dm-c");

  private static final String ADDR_7 = address(7);
  private static final String ADDR_8 = address(8);
  private static final String ADDR_9 = address(9);

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

  // ── 判据 ①：as 缺失/非法、解不出决策人 ────────────────────────────────────────────────

  @Test
  void aMissingOrInvalidActorParameterIsRejected() throws Exception {
    HttpResponse<String> missing = get("/api/sd/decision-results");
    assertThat(missing.statusCode()).as("as 缺失 ⇒ 400（决策结果没有 GM 全量口径）").isEqualTo(400);
    assertThat(error(missing)).contains("as");

    HttpResponse<String> blank = get("/api/sd/decision-results?as=");
    assertThat(blank.statusCode()).as("as 空白 ⇒ 400（同 requiredParam 口径）").isEqualTo(400);
    assertThat(error(blank)).contains("as");
  }

  @Test
  void anUnknownActorIsNotFoundNotAnEmptyList() throws Exception {
    HttpResponse<String> response = get("/api/sd/decision-results?as=dm-ghost");

    assertThat(response.statusCode())
        .as("解不出决策人 ⇒ 404（与 /api/sd/decision-makers/{id}/scope 同款，**不是**空列表）")
        .isEqualTo(404);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("error").asText()).contains("not found");
    assertThat(body.get("id").asText()).isEqualTo("dm-ghost");
  }

  // ── 判据 ②：tick 与区间互斥（方向 / 冲突都拒）────────────────────────────────────────

  @Test
  void tickAndRangeAreMutuallyExclusive() throws Exception {
    HttpResponse<String> both = get("/api/sd/decision-results?as=dm-a&tick=7&fromTick=7");
    assertThat(both.statusCode()).as("tick 与区间同给 ⇒ 400").isEqualTo(400);
    assertThat(error(both)).contains("互斥");

    HttpResponse<String> reversed = get("/api/sd/decision-results?as=dm-a&fromTick=9&toTick=8");
    assertThat(reversed.statusCode()).as("fromTick > toTick ⇒ 400").isEqualTo(400);
    assertThat(error(reversed)).contains("不得大于");
  }

  // ── 判据 ③：limit 超限/非整数明确拒（不是静默截断）──────────────────────────────────

  @Test
  void aLimitAboveTheCapIsRejectedNotSilentlyTruncated() throws Exception {
    assertThat(get("/api/sd/decision-results?as=dm-a&limit=20").statusCode())
        .as("边界内照常 200")
        .isEqualTo(200);
    assertThat(get("/api/sd/decision-results?as=dm-a&limit=200").statusCode())
        .as("恰在上限也 200")
        .isEqualTo(200);

    HttpResponse<String> over = get("/api/sd/decision-results?as=dm-a&limit=201");
    assertThat(over.statusCode()).as("超上限 ⇒ 400").isEqualTo(400);
    assertThat(error(over)).contains("200");

    HttpResponse<String> zero = get("/api/sd/decision-results?as=dm-a&limit=0");
    assertThat(zero.statusCode()).as("limit=0 ⇒ 400（正数才合法）").isEqualTo(400);

    HttpResponse<String> notANumber = get("/api/sd/decision-results?as=dm-a&limit=abc");
    assertThat(notANumber.statusCode()).as("非整数 ⇒ 400").isEqualTo(400);
    assertThat(error(notANumber)).contains("整数");
  }

  // ── 判据 ④：空结果显式带 note ──────────────────────────────────────────────────────

  @Test
  void aKnownActorWithNoResultGetsAnExplicitNote() throws Exception {
    JsonNode body = getJson("/api/sd/decision-results?as=dm-c");

    assertThat(body.get("results")).as("dm-c 没有任何命中").isEmpty();
    assertThat(body.get("count").asInt()).isZero();
    assertThat(body.get("note").asText())
        .as("★ 空结果不是静默 200——明确可读的『没有可查看的决策结果』")
        .contains("没有可查看的决策结果");
  }

  // ── 判据 ⑤：同 tick 两个决策人各只看到自己那条（可区分）──────────────────────────────

  @Test
  void twoDecisionMakersInTheSameTickEachSeeOnlyTheirOwnResult() throws Exception {
    assertThat(infoIds()).as("前提：tick7 的两条确实在服务端状态里").contains(idOf(ADDR_7, 0), idOf(ADDR_7, 1));

    JsonNode a = getJson("/api/sd/decision-results?as=dm-a&tick=7");
    JsonNode b = getJson("/api/sd/decision-results?as=dm-b&tick=7");

    assertThat(ids(a)).as("★ dm-a 只看到 tags 含自己的那条").containsExactly(idOf(ADDR_7, 0));
    assertThat(ids(b)).as("★ dm-b 只看到自己那条（与 dm-a 的不是同一条）").containsExactly(idOf(ADDR_7, 1));
    assertThat(ids(a)).as("★ dm-a **看不到** dm-b 在同一个 tick 上的结果").doesNotContain(idOf(ADDR_7, 1));
    assertThat(ids(b)).as("★ dm-b **看不到** dm-a 在同一个 tick 上的结果").doesNotContain(idOf(ADDR_7, 0));

    assertThat(tags(a.get("results").get(0))).containsExactly("dm-a");
    assertThat(tags(b.get("results").get(0))).containsExactly("dm-b");
  }

  @Test
  void unownedResultIsInvisibleToEveryActor() throws Exception {
    assertThat(infoIds()).as("前提：无主条目确实在服务端状态里").contains(idOf(ADDR_9, 0));

    for (String actor : List.of("dm-a", "dm-b", "dm-c")) {
      JsonNode body = getJson("/api/sd/decision-results?as=" + actor + "&tick=9");
      assertThat(ids(body)).as("★ %s 看不到无主结果（空集 ≠ 大家都能看）", actor).isEmpty();
      assertThat(body.get("note").asText()).as("%s 得到明确空态", actor).isNotEmpty();
    }
  }

  // ── 历史窗口：branch / revision 走既有 target(params)，at.revision 是**条目自己**的 ──────────

  /**
   * ★ **本条补的是"在 head 之外读历史"这件本端点唯一没覆盖的事**：`revision` 参数经既有 {@code target(params)} 生效——后加的条目在 **head
   * 视图里有、在旧 revision 视图里没有**。
   *
   * <p>★ 判别力在两处：① 旧 revision 视图**少了**一条（写成"忽略 revision、永远读 head"当场红）；② {@code at.revision}
   * 是**写入所基于**的基态 revision（三条在 head=2 视图里都仍报 1）——写成"报被查 revision"当场红。
   *
   * <p>★ **实测记下的一条契约细节（前端会踩）**：{@code at.revision} 取自基态（`PutInfoHandler` 用 {@code
   * state.meta().ref().revision()}），故它**可以小于**"该条目首次可见的 revision"——后加那条 {@code at=1}，但它只在 {@code
   * revision>=2} 的视图里出现。想判"这条属于哪一版"要用**被查 revision 与 id/tick**，不能拿 {@code at.revision} 比。
   */
  @Test
  void aHistoricalRevisionExcludesLaterResultsAndAtKeepsTheEntrysOwnRevision() throws Exception {
    assertThat(head()).as("前提：创世头 = 1（夹具里 tick8#0 / tick7#0 都是它）").isEqualTo(1L);

    // 经**真写路径**在 tick7 那个地址下追加一条 tags={dm-a}（tick 取世界当前 tick=7——
    // 载荷 tick 不得记在未来，故不能用夹具里那个越界的 8）⇒ 落成 revision 2。
    submitPutInfo(ADDR_7, idOf(ADDR_7, 2), "later@7", 7L);
    assertThat(head()).as("追加命令落了一条 revision").isEqualTo(2L);

    JsonNode atHead = getJson("/api/sd/decision-results?as=dm-a");
    assertThat(ids(atHead))
        .as("head=2：tick8 那条 + tick7 的两条（#0 创世 + #2 后加；#1 是 dm-b 的，dm-a 看不到）")
        .containsExactly(idOf(ADDR_8, 0), idOf(ADDR_7, 0), idOf(ADDR_7, 2));

    JsonNode atRev1 = getJson("/api/sd/decision-results?as=dm-a&revision=1");
    assertThat(ids(atRev1))
        .as("★ 读历史 revision=1：后加的那条**不该出现**（忽略 revision 的实现会多出它）")
        .containsExactly(idOf(ADDR_8, 0), idOf(ADDR_7, 0));

    // ★★ 同一张 **head=2** 视图里，三条的 at.revision **全是 1**：
    //   ① `at` 是**写入所基于**的那个 revision（`PutInfoHandler:73` 取 `state.meta().ref().revision()` = 基态），
    //      不是"该条目首次出现的 revision"（后加那条在基态 1 上算出 ⇒ at=1，但它只在 revision 2 起才可见）；
    //      —— 故 `at.revision` **小于等于**"该条目可见的 revision"，前端别拿它当"这条属于哪版"。
    //   ② 它也**不是被查 revision**：写成"报被查 revision"的实现会在 head 视图里读到 2 ⇒ 下面三条当场红。
    for (String id : List.of(idOf(ADDR_8, 0), idOf(ADDR_7, 0), idOf(ADDR_7, 2))) {
      assertThat(rowById(atHead, id).get("at").get("revision").asLong())
          .as("★ %s 在 head=2 视图里仍报 at.revision=1（基态；不是被查的 2）", id)
          .isEqualTo(1L);
    }
  }

  // ── 响应形状：前端按它开发（value 是字符串、at 带 branch/revision）──────────────────

  @Test
  void responseShapeMatchesTheFrontendContract() throws Exception {
    JsonNode body = getJson("/api/sd/decision-results?as=dm-a");
    // ★ 把真响应打一行出来当"样例"（前端子页据此开发；value 是字符串、note 只在空结果时出现）。
    System.out.println("[decision-results sample] " + body);

    assertThat(body.get("count").asInt()).isEqualTo(2);
    JsonNode first = body.get("results").get(0);
    assertThat(first.get("tick").asLong()).as("★ 序 = tick 降序（新的在前）").isEqualTo(8L);
    assertThat(first.get("id").asText()).isEqualTo(idOf(ADDR_8, 0));
    assertThat(tags(first)).containsExactly("dm-a", "dm-b");
    assertThat(first.get("value").isTextual())
        .as("★ value 是**字符串**（裁决工具写的 JSON 原文；本层不解析、不改写）")
        .isTrue();
    assertThat(JSON.readTree(first.get("value").asText()).get("marker").asText()).isEqualTo("AB@8");
    assertThat(first.get("at").get("branch").asText()).isEqualTo("main");
    assertThat(first.get("at").get("revision").asLong()).isEqualTo(1L);

    assertThat(body.get("results").get(1).get("tick").asLong()).isEqualTo(7L);
    assertThat(body.has("note")).as("非空结果不带 note").isFalse();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 经**真写路径**（{@code POST /api/command}，GUI 身份）在裁决结果地址下追加一条条目。 */
  private void submitPutInfo(String address, String id, String marker, long tick) throws Exception {
    String payload =
        "{\"address\":\""
            + address
            + "\",\"key\":\"result\",\"value\":\"{\\\"marker\\\":\\\""
            + marker
            + "\\\"}\",\"tags\":[\"dm-a\"],\"tick\":"
            + tick
            + ",\"id\":\""
            + id
            + "\"}";
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("type", "sd.PutInfo");
    request.put("payloadJson", payload);
    request.put("branch", "main");
    request.put("expectedRevision", head());
    HttpResponse<String> response = post("/api/command", JSON.writeValueAsString(request));
    assertThat(response.statusCode()).as("提交 sd.PutInfo: %s", response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");
  }

  private long head() {
    return shell.coreSimos().head(new BranchId("main")).orElseThrow().value();
  }

  private static JsonNode rowById(JsonNode body, String id) {
    for (JsonNode row : body.get("results")) {
      if (row.get("id").asText().equals(id)) {
        return row;
      }
    }
    throw new AssertionError("结果里没有 " + id + "：" + body);
  }

  private static String error(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body()).get("error").asText();
  }

  private static List<String> ids(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : body.get("results")) {
      out.add(row.get("id").asText());
    }
    return out;
  }

  private static List<String> tags(JsonNode row) {
    List<String> out = new ArrayList<>();
    for (JsonNode tag : row.get("tags")) {
      out.add(tag.asText());
    }
    return out;
  }

  /** **服务端**状态里 INFO 层全部条目的 id（"那条确实在状态里"的前提断言用）。 */
  private List<String> infoIds() {
    SimulationState state = shell.queryService().stateAt(QueryTarget.head(new BranchId("main")));
    List<String> out = new ArrayList<>();
    for (List<SdInfoEntry> entries :
        ((SdSnapshot) state.module("sd").orElseThrow()).state().info().values()) {
      for (SdInfoEntry entry : entries) {
        out.add(entry.id().value());
      }
    }
    return out;
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  /** 决策结果地址（与写路径同源：{@code sd:adjudication.<tick>} 的 canonical 形）。 */
  private static String address(long tick) {
    return Address.parse(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick).canonical();
  }

  private static String idOf(String canonicalAddress, int ordinal) {
    return SdInfoIds.synthesize(canonicalAddress, ordinal).value();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * INFO 层（全部挂在 {@code sd:adjudication.<tick>} 前缀下）：
   *
   * <pre>
   *   sd:adjudication.7  ── #0 tags={dm-a}        （同一 tick 两条，各自的主人）
   *                        #1 tags={dm-b}
   *   sd:adjudication.8  ── #0 tags={dm-a, dm-b}  （共同涉及）
   *   sd:adjudication.9  ── #0 tags={}            （无主）
   * </pre>
   */
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
    makers.put(DM_A, maker(DM_A));
    makers.put(DM_B, maker(DM_B));
    makers.put(DM_C, maker(DM_C));

    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>();
    info.put(
        ADDR_7,
        List.of(
            entry(ADDR_7, 0, 7, Set.of(DM_A), "A@7"), entry(ADDR_7, 1, 7, Set.of(DM_B), "B@7")));
    info.put(ADDR_8, List.of(entry(ADDR_8, 0, 8, Set.of(DM_A, DM_B), "AB@8")));
    info.put(ADDR_9, List.of(entry(ADDR_9, 0, 9, Set.of(), "unowned@9")));
    return SdState.empty().withDecisionMakers(makers).withInfo(info);
  }

  private static SdInfoEntry entry(
      String canonicalAddress, int ordinal, long tick, Set<DecisionMakerId> tags, String marker) {
    return new SdInfoEntry(
        SdInfoIds.synthesize(canonicalAddress, ordinal),
        tick,
        tags,
        Set.of(),
        "result",
        "{\"marker\":\"" + marker + "\"}",
        Optional.empty(),
        new RevisionId(1),
        Optional.empty());
  }

  private static DecisionMaker maker(DecisionMakerId id) {
    return new DecisionMaker(
        id, new Affiliation.Nation(new NationId("n1")), Set.of(), AccessLimit.empty(), 1);
  }

  /** 一格 + 一片区域（区域表是壳装配所需；本端点不读地图，故只需最小的合法世界）。 */
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
            new RegionMeta(null, NationTag.tagFor(new NationId("n1")), null, null)));
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
