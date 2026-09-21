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
import io.mosire.simos.map.pathway.PathwayGroup;
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
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
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
 * redaction 收口端到端验收（T6，spec `C28`/`C29`/`C30`）：起真 {@link Shell}（四端口全 0），用 JDK {@link HttpClient} 打真
 * HTTP。
 *
 * <p>★ **夹具直接种入创世 checkpoint**（不经命令）：T6 测的是**读路径的脱敏**，不是命令写路径；故 sd 切片里预置 5 个不同 {@code ViewScope}
 * 的决策人 + 1 条判决，让"同一端点、不同 {@code as=} ⇒ 不同数据"一次可测。
 *
 * <p>★ **三条主判据**：{@code C29}（{@code redactedFields=["position"]} ⇒ 单位读数里 {@code position} 消失，另一
 * actor 仍在）；{@code C28}（{@code adjudicationDisclosure} 三档对判决内容分叉）；{@code C30}（未接 redaction 的读端点带
 * {@code as=} ⇒ **拒绝**，不是全量）。
 */
class RedactionApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final UnitId U1 = new UnitId("u-1");

  private static final String DM_FULL = "dm-full";
  private static final String DM_PERCEPTION = "dm-perception";
  private static final String DM_WITHHELD = "dm-withheld";
  private static final String DM_REDACT_POSITION = "dm-redact";
  private static final String DM_NO_SCOPE = "dm-none";
  private static final String DM_REDACT_HEADS = "dm-state";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── C29：redactedFields 真的把命名字段去掉 ────────────────────────────

  @Test
  void positionIsRedactedForOneActorButPresentForAnother() throws Exception {
    JsonNode redacted = getJson("/api/unit/u-1?as=" + DM_REDACT_POSITION);
    JsonNode full = getJson("/api/unit/u-1?as=" + DM_FULL);

    assertThat(redacted.get("id").asText()).isEqualTo("u-1");
    assertThat(redacted.has("position")).as("redactedFields=[position] ⇒ 字段整条消失").isFalse();
    assertThat(full.has("position")).as("另一 actor 同请求里 position 仍在").isTrue();
    assertThat(full.get("position").get("q").asInt()).isEqualTo(1);
  }

  @Test
  void unitsListRedactsTheNamedFieldForEveryItem() throws Exception {
    JsonNode redacted = getJson("/api/units?as=" + DM_REDACT_POSITION).get("units");
    JsonNode full = getJson("/api/units?as=" + DM_FULL).get("units");

    assertThat(redacted).hasSize(1);
    assertThat(redacted.get(0).has("position")).isFalse();
    assertThat(full.get(0).has("position")).isTrue();
  }

  @Test
  void stateRedactionRemovesTheNamedTopLevelField() throws Exception {
    JsonNode redacted = getJson("/api/state?as=" + DM_REDACT_HEADS);
    JsonNode full = getJson("/api/state");

    assertThat(full.has("heads")).as("基线里 heads 在").isTrue();
    assertThat(redacted.has("heads")).as("redactedFields=[heads] ⇒ 顶层字段被剔除").isFalse();
    assertThat(redacted.get("branches")).as("其余字段不受影响").isNotNull();
  }

  // ── C28：adjudicationDisclosure 真的改变判决可见内容 ──────────────────

  @Test
  void verdictDisclosureGatesTheVisibleContent() throws Exception {
    JsonNode withheld = getJson("/api/sd/verdicts?as=" + DM_WITHHELD).get("verdicts");
    JsonNode perception = getJson("/api/sd/verdicts?as=" + DM_PERCEPTION).get("verdicts");
    JsonNode full = getJson("/api/sd/verdicts?as=" + DM_FULL).get("verdicts");

    assertThat(withheld).as("WITHHELD ⇒ 判决整条不出现").isEmpty();

    assertThat(perception).hasSize(1);
    assertThat(perception.get(0).has("payload")).as("PERCEPTION_ONLY 去掉模型输出").isFalse();
    assertThat(perception.get(0).has("meta")).as("PERCEPTION_ONLY 去掉 meta").isFalse();
    assertThat(perception.get(0).get("id").asText()).isEqualTo("v-1");

    assertThat(full).hasSize(1);
    assertThat(full.get(0).has("payload")).as("FULL 含模型输出").isTrue();
    assertThat(full.get(0).has("meta")).as("FULL 含 meta").isTrue();
  }

  @Test
  void verdictsWithoutActorAreFullDisclosure() throws Exception {
    JsonNode body = getJson("/api/sd/verdicts").get("verdicts");

    assertThat(body).hasSize(1);
    assertThat(body.get(0).has("payload")).isTrue();
  }

  @Test
  void twoScopesSeeDifferentUnitListsOnTheSameEndpoint() throws Exception {
    JsonNode full = getJson("/api/units?as=" + DM_FULL).get("units");
    JsonNode none = getJson("/api/units?as=" + DM_NO_SCOPE).get("units");

    assertThat(full).hasSize(1);
    assertThat(none).as("空 scope 的 actor 看不到任何单位（fail-closed）").isEmpty();
  }

  // ── C30：可见性 fail-closed（按地址取单个实体）─────────────────────────

  @Test
  void invisibleHexIsNotFoundUnderAs() throws Exception {
    HttpResponse<String> invisible = get("/api/map/hex?q=1&r=3&as=" + DM_FULL);
    HttpResponse<String> visible = get("/api/map/hex?q=1&r=1&as=" + DM_FULL);

    assertThat(invisible.statusCode()).as("不可见的格与不存在同形").isEqualTo(404);
    assertThat(visible.statusCode()).isEqualTo(200);
  }

  @Test
  void invisibleUnitIsNotFoundUnderAs() throws Exception {
    HttpResponse<String> response = get("/api/unit/u-1?as=" + DM_NO_SCOPE);

    assertThat(response.statusCode()).as("不可见的单位 ⇒ 404").isEqualTo(404);
  }

  // ── C30：未接 redaction 的读端点 ⇒ 显式拒绝（不是全量）───────────────

  @Test
  void endpointsWithoutRedactionRejectTheAsParameter() throws Exception {
    HttpResponse<String> path = get("/api/map/path?unit=u-1&q=1&r=2&as=" + DM_FULL);
    HttpResponse<String> makers = get("/api/sd/decision-makers?as=" + DM_FULL);
    HttpResponse<String> maker = get("/api/sd/decision-makers/" + DM_FULL + "?as=" + DM_FULL);

    assertThat(path.statusCode()).as("/api/map/path 未接 ⇒ 400").isEqualTo(400);
    assertThat(makers.statusCode()).as("/api/sd/decision-makers 未接 ⇒ 400").isEqualTo(400);
    assertThat(maker.statusCode()).as("/api/sd/decision-makers/{id} 未接 ⇒ 400").isEqualTo(400);
    assertThat(JSON.readTree(path.body()).get("error")).isNotNull();
  }

  @Test
  void unsupportedEndpointsStillWorkWithoutTheAsParameter() throws Exception {
    assertThat(get("/api/sd/decision-makers").statusCode()).isEqualTo(200);
    assertThat(get("/api/map/path?unit=u-1&q=1&r=2").statusCode()).isEqualTo(200);
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

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  /** 创世 {@code (main,1)}：三格地图 + 单位 u-1（在 H11）+ 五个不同 scope 的决策人 + 一条判决。 */
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

  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        new RegionId("r1"), Region.of(new RegionId("r1"), "区域一", Set.of(H11), RegionMeta.empty()));
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

  private static SdState sdState() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        new DecisionMakerId(DM_FULL),
        maker(DM_FULL, Set.of(H11), Set.of(U1), DisclosurePolicy.FULL, Set.of()));
    makers.put(
        new DecisionMakerId(DM_PERCEPTION),
        maker(DM_PERCEPTION, Set.of(H11), Set.of(U1), DisclosurePolicy.PERCEPTION_ONLY, Set.of()));
    makers.put(
        new DecisionMakerId(DM_WITHHELD),
        maker(DM_WITHHELD, Set.of(H11), Set.of(U1), DisclosurePolicy.WITHHELD, Set.of()));
    makers.put(
        new DecisionMakerId(DM_REDACT_POSITION),
        maker(
            DM_REDACT_POSITION,
            Set.of(H11),
            Set.of(U1),
            DisclosurePolicy.FULL,
            Set.of("position")));
    makers.put(
        new DecisionMakerId(DM_NO_SCOPE),
        maker(DM_NO_SCOPE, Set.of(), Set.of(), DisclosurePolicy.FULL, Set.of()));
    makers.put(
        new DecisionMakerId(DM_REDACT_HEADS),
        maker(DM_REDACT_HEADS, Set.of(H11), Set.of(U1), DisclosurePolicy.FULL, Set.of("heads")));
    Map<VerdictId, Verdict> verdicts = new LinkedHashMap<>();
    verdicts.put(new VerdictId("v-1"), verdict("v-1"));
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
}
