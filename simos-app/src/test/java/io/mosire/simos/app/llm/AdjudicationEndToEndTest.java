package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.simos.app.sd.DecisionAdjudicationService;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.spi.SubmitVerdictHandler;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端：真 {@link CoreSimos} + 真 handler + **真 AgentLib HTTP 客户端**打**本地 stub**（无外网） ⇒ 判决落 revision。
 *
 * <p>★ 这是「{@code AdjudicatorRunner} 接进壳」的可执行证据：本类用 {@link DecisionAdjudicationService} （{@link
 * io.mosire.simos.app.Shell} 里装配的那一个）跑通 "provider 解析 → AgentLib 客户端 → 判决 → revision"。
 *
 * <p>★ **测试不打外网**：stub 是本进程的 {@link HttpServer}，符合"唯一允许打真外网的只有端到端实测那一步"。
 */
class AdjudicationEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final DecisionMakerId DM1 = new DecisionMakerId("dm1");

  @TempDir Path tempDir;

  private CoreSimos core;
  private HttpServer stub;

  @AfterEach
  void tearDown() {
    if (core != null) {
      core.close();
    }
    if (stub != null) {
      stub.stop(0);
    }
  }

  @Test
  void realAgentLibClientProducesAVerdictThatLandsARevision() throws Exception {
    AtomicReference<String> seenModel = new AtomicReference<>();
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenBody = new AtomicReference<>();
    startStub(seenModel, seenAuth, seenBody);

    CoreSimos core = start();
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("stub", baseUrl(), "deepseek-flash", "keys.stub", 5_000L);
    config.putKey("stub", "sk-test-key");
    LlmProviderResolver resolver = new LlmProviderResolver(config, tempDir);
    DecisionAdjudicationService service = new DecisionAdjudicationService(core, resolver);

    DecisionMaker maker = sdState(core, 1).decisionMakers().get(DM1);
    assertThat(maker.providerId()).contains("stub");

    List<Judgement> judgements = service.adjudicate(MAIN, new RevisionId(1), maker);

    assertThat(judgements).as("D1 与 D3 合并 ⇒ 两组判决（D1+D3 / D6）").hasSize(2);
    assertThat(judgements).allMatch(Judgement.Accepted.class::isInstance);
    assertThat(core.head(MAIN).orElseThrow())
        .as("两条判决各落一条 revision（1 → 3）")
        .isEqualTo(new RevisionId(3));
    assertThat(sdState(core, 3).verdicts()).hasSize(2);

    assertThat(seenModel.get()).as("请求体里的 model").isEqualTo("deepseek-flash");
    assertThat(seenAuth.get()).as("Authorization 头").isEqualTo("Bearer sk-test-key");
    assertThat(seenBody.get())
        .as("判决请求必须带采样：temperature=0（可复现）+ max_tokens 给足（需求 A2）")
        .contains("\"temperature\":0")
        .contains("\"max_tokens\":4096");
  }

  @Test
  void unboundDecisionMakerMakesTheServiceFailClosedRatherThanUseAnotherProvider()
      throws Exception {
    startStub(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    CoreSimos core = start();
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("stub", baseUrl(), "deepseek-flash", "keys.stub", 5_000L);
    config.putKey("stub", "sk-test-key");
    DecisionAdjudicationService service =
        new DecisionAdjudicationService(core, new LlmProviderResolver(config, tempDir));

    DecisionMaker unbound = maker(Optional.empty());
    assertThatThrownBy(() -> service.adjudicate(MAIN, new RevisionId(1), unbound))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(LlmProviderResolver.E_UNBOUND);
    assertThat(core.head(MAIN).orElseThrow()).as("不落任何 revision").isEqualTo(new RevisionId(1));
  }

  /** 一条**真实** OpenAI 兼容 SSE 帧（data: + 空行），与 AgentLib 客户端解析的契约一致。 */
  private static String sseChunk(String deltaJson) {
    return "data: " + deltaJson + "\n\ndata: [DONE]\n\n";
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
  }

  private void startStub(
      AtomicReference<String> seenModel,
      AtomicReference<String> seenAuth,
      AtomicReference<String> seenBody)
      throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/v1/chat/completions",
        (HttpExchange exchange) -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (body.contains("\"model\":\"deepseek-flash\"")) {
            seenModel.set("deepseek-flash");
          }
          seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          seenBody.set(body);
          byte[] payload = stubResponseFor(body).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    stub.start();
  }

  /**
   * 按**请求里的断点**回一条通过 schema 的 HTTP 响应（stub 也有"语义"，只回固定串会被 D6 的 schema 拒）。
   *
   * <p>★ 断点由请求体里的 system prompt 识别（{@code "断点 D1"} 形态）——这是真实的输入依赖，不是把答案写死。
   */
  private static String stubResponseFor(String requestBody) {
    String content;
    if (requestBody.contains("断点 D6")) {
      content = "{\"disposition\":\"HOLD\",\"rationaleText\":\"守\"}";
    } else {
      content =
          "{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],\"rationaleText\":\"强攻\"}";
    }
    String envelope =
        "{\"id\":\"chatcmpl-1\",\"model\":\"deepseek-flash\",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7},"
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
            + jsonString(content)
            + "}}]}";
    return sseChunk(envelope);
  }

  /** JSON 字符串字面量（转义内容里的引号）。 */
  private static String jsonString(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new SdCodec());
    core.register(new SubmitVerdictHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static SdState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    DecisionMaker dm = maker(Optional.of("stub"));
    SdState sd =
        SdState.empty()
            .withNations(
                Map.of(
                    new NationId("n1"),
                    new Nation(new NationId("n1"), "甲国", new RegionId("r1"), 1)))
            .withDecisionMakers(Map.of(DM1, dm))
            .withCombats(
                Map.of(
                    new CombatId("c1"),
                    new Combat(new CombatId("c1"), "战斗一", List.of(), Set.of(), Optional.empty())));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), sd)),
        InMemoryInfoSystem.empty());
  }

  private static DecisionMaker maker(Optional<String> providerId) {
    return new DecisionMaker(
        DM1,
        new Affiliation.Nation(new NationId("n1")),
        Set.of(),
        ViewScope.empty(),
        1L,
        providerId);
  }
}
