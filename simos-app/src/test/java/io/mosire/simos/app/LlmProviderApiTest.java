package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mosire.simos.app.llm.LlmProvider;
import io.mosire.simos.app.llm.LlmProviderRegistry;
import io.mosire.simos.app.llm.SecretRef;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M11 判据 C13/C14：provider CRUD（掩码、无密钥值）+ 测试连接（打本地 stub）+ 决策人绑定落 revision。
 *
 * <p>★ 不碰真网络：{@code /api/llm/providers/test} 打到本用例起的本地 stub。★ 密钥用 FILE 引用（tempDir 下的文件）， 让生产侧
 * {@code LlmProviderRegistry} 能解析。
 */
class LlmProviderApiTest {

  private static final ObjectMapper JSON = SimosObjectMapper.create();
  private static final String SECRET_VALUE = "sk-local-stub-secret-1234567890";

  @TempDir Path tempDir;

  private HttpServer stub;
  private Shell shell;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/v1/chat/completions",
        exchange -> {
          try (var in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stub.start();

    seedGenesis();
    // 写 provider 配置（FILE 密钥引用）⇒ Shell 启动时会加载同一份。
    Files.writeString(tempDir.resolve("key.txt"), SECRET_VALUE + "\n");
    LlmProviderRegistry seed = LlmProviderRegistry.load(tempDir);
    seed.upsert(
        new LlmProvider(
            "p-stub",
            "http://127.0.0.1:" + stub.getAddress().getPort(),
            "gpt-stub",
            SecretRef.file(tempDir.resolve("key.txt").toString()),
            Duration.ofSeconds(5)));

    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0);
    shell = Shell.start(base);
  }

  @AfterEach
  void stop() {
    if (shell != null) {
      shell.close();
    }
    if (stub != null) {
      stub.stop(0);
    }
  }

  @Test
  void listProvidersIsMaskedAndNeverLeaksTheSecret() throws Exception {
    HttpResponse<String> response = get("/api/llm/providers");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain(SECRET_VALUE);
    assertThat(response.body())
        .contains("p-stub")
        .contains("FILE")
        .contains("\"secretResolvable\":true");
  }

  @Test
  void upsertAndDeleteRoundTrip() throws Exception {
    String body =
        "{\"id\":\"p-new\",\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\","
            + "\"apiKeyRefKind\":\"ENV\",\"apiKeyRef\":\"SIMOS_FAKE_KEY\",\"timeoutMs\":1000}";
    HttpResponse<String> created = post("/api/llm/providers", body);
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.body()).contains("p-new").contains("SIMOS_FAKE_KEY");

    JsonNode listed = JSON.readTree(get("/api/llm/providers").body()).get("providers");
    assertThat(listed.findValuesAsText("id")).contains("p-new", "p-stub");

    HttpResponse<String> deleted = post("/api/llm/providers/delete", "{\"id\":\"p-new\"}");
    assertThat(deleted.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(deleted.body()).get("deleted").asBoolean()).isTrue();
  }

  @Test
  void testConnectionCallsTheLocalStub() throws Exception {
    HttpResponse<String> response = post("/api/llm/providers/test", "{\"id\":\"p-stub\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("ok").asBoolean()).as(response.body()).isTrue();
    assertThat(response.body()).doesNotContain(SECRET_VALUE);
  }

  @Test
  void testConnectionForUnknownProviderReportsFailureNotFiveHundred() throws Exception {
    HttpResponse<String> response = post("/api/llm/providers/test", "{\"id\":\"p-missing\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("ok").asBoolean()).isFalse();
    assertThat(body.get("detail").asText()).contains("p-missing");
  }

  @Test
  void bindingCommandLandsARevisionAndIsVisibleInTheDecisionMakerView() throws Exception {
    long head = headRevision();
    HttpResponse<String> response = post("/api/sd/set-decision-maker-provider", bindingBody(head));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");
    assertThat(headRevision()).isEqualTo(head + 1);

    HttpResponse<String> maker = get("/api/sd/decision-makers/dm1");
    assertThat(maker.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(maker.body()).get("providerId").asText()).isEqualTo("p-stub");
  }

  private static String bindingBody(long expectedRevision) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("branch", "main");
    body.put("expectedRevision", expectedRevision);
    body.put("decisionMakerId", "dm1");
    body.put("providerId", "p-stub");
    return JSON.writeValueAsString(body);
  }

  private long headRevision() {
    return shell.coreSimos().head(new BranchId("main")).orElseThrow().value();
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + shell.boundGuiPort() + path))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + shell.boundGuiPort() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void seedGenesis() {
    BranchId main = new BranchId("main");
    try (var seedStore =
        io.mosire.simos.core.store.SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, 100)
          .appendRevision(
              new RevisionRow(
                  main,
                  new RevisionId(1),
                  Optional.empty(),
                  SimosTimestamp.of(0),
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(io.mosire.simos.core.state.WorldChangeSet.empty())));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    DecisionMaker dm =
        new DecisionMaker(
            new DecisionMakerId("dm1"),
            new Affiliation.Nation(new NationId("n1")),
            Set.of(),
            ViewScope.empty(),
            1,
            Optional.empty());
    SdState sd =
        SdState.empty()
            .withNations(
                Map.of(
                    new NationId("n1"),
                    new Nation(new NationId("n1"), "甲国", new RegionId("r1"), 1)))
            .withDecisionMakers(Map.of(new DecisionMakerId("dm1"), dm));
    StateRef ref = new StateRef(main, new RevisionId(1));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref, SimosTimestamp.of(0)),
            Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), sd)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(ref, CheckpointEncoder.encode(genesis, java.util.List.of(new SdCodec())));
  }
}
