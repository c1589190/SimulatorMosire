package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.llm.ProviderLlm;
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
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「让它跑一轮」窄写端到端验收：{@code POST /api/sd/run-decision} + 轮询 {@code GET
 * /api/sd/decision-makers/{id}/run-status}。
 *
 * <p>★ **它补的是哪一处空白**：让某个决策人真跑一轮（真 LLM 自行读世界、出令）此前**只有 GM 的 MCP 窄工具做得到** ⇒ 界面上点不出来。本端点把同一条路接进工作台。
 *
 * <p>★★ **2026-09-23 起它是异步的**（用户要的"状态标识 + 可展开进度窗"）：POST **立即返回**（体里只有"触发事实已落盘 +
 * 起跑了"），这一轮在**服务端后台**跑，进度与结局靠 {@code …/run-status} **轮询**取得。理由是硬的：这一轮里决策人若出令 （{@code
 * sd.IssueDirective} 是敏感写），那次工具调用要**阻塞式**等审批（上限 = 壳的 {@code APPROVAL_TIMEOUT}） ⇒ 让 HTTP
 * 请求停在那里，界面除了"卡死"没有别的表现。★ GM 经 MCP 的那条**仍是同步**的（{@code RunDecisionEndToEndTest}
 * 钉住它），两条路语义不同是**有意的**。
 *
 * <p>★ **唯一替身是 LLM 客户端**（{@link RecordingFakeLlm}，本仓纪律：测试不打真网络）：真壳、真 store、真命令处理器、
 * 真工具面、真权限链，替身只替"怎么造客户端"。它额外记下**每次请求的 messages 条数**——那正是"重跑沿用上下文"的证据载体。
 *
 * <p>★ **本类只留四条**（用户 2026-09-23：少做测试，写完直接编译、我直接看）：一条钉「异步形状 + 轨迹读得回」、一条钉「未知决策人被拒 ⇒
 * 触发事实也落不下」、一条钉「重跑沿用上下文 / 只有重置才清」（**带对照组**）、一条钉「第一轮之前说一句话 ⇒ 身份消息仍在前」。
 *
 * <p>★ **2026-09-24（E4）新增第五条**：连点两次同一个决策人 ⇒ 第二次**明确拒绝**、第一轮的账**不被覆盖**。它是本条端点上的**真并发** 缺陷（见 {@code
 * runDecisionReply} 的并发闸门），故落在这里；用"假 LLM 卡在第一次调用"把第一轮**摁在 running** 上，好让第二次点击 确实发生在"正在跑"的窗口里。
 */
class SdRunDecisionApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String PROVIDER_ID = "stub";
  private static final String DM_ID = "dm-fra";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R_FRA = new RegionId("701");

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 夹具决策人：国家 FRA 的决策人，**绑了** provider（没绑的话那一轮 fail-closed，跑不起来）。 */
  private static final DecisionMaker DM_FRA =
      new DecisionMaker(
          new DecisionMakerId(DM_ID),
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          AccessLimit.empty(),
          1,
          Optional.of(PROVIDER_ID),
          0L);

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;
  private RecordingFakeLlm llm;

  @BeforeEach
  void startShell() {
    seedGenesis();
    llm = new RecordingFakeLlm();
    // ★ 注入的只是"怎么造客户端"：未绑定 provider 的 fail-closed、世界、权限、落盘全是真的。
    shell =
        Shell.start(
            ShellConfig.defaults(tempDir).withPorts(0, 0, 0),
            // ★ 本用例集不验图片通路：如实给"没有视觉能力"（图一张都不该发，见 DecisionAgentRunner 的类注）。
            providerId -> new ProviderLlm(llm, false));
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
  void theRoundRunsInTheBackgroundAndTheTraceIsPolledFromRunStatus() throws Exception {
    // ★ **跑之前**：这个人从没跑过 ⇒ run-status 如实报"没有记录"（`startedAt`/`llmCalls` 都是 null，
    //   **不拿 0 / false 顶替**："一次都没调"与"本进程从没见过它跑"是两件事）。
    JsonNode before = JSON.readTree(get("/api/sd/decision-makers/" + DM_ID + "/run-status").body());
    assertThat(before.get("startedAt").isNull()).as("没跑过 ⇒ startedAt 必须 null").isTrue();
    assertThat(before.get("llmCalls").isNull()).as("没跑过 ⇒ llmCalls 必须 null（不是 0）").isTrue();
    assertThat(before.get("done").asBoolean()).isFalse();

    // 模型先读一格（真工具、真世界）⇒ 再收口。★ 刻意**不出令**：出令是敏感写、要过阻塞式审批（见类注）。
    llm.enqueue(LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)));
    llm.enqueue(LlmResponse.text("已读图，本 tick 无动作"));

    HttpResponse<String> response = post("/api/sd/run-decision", runDecisionBody(DM_ID, 1L));

    // ★★ **POST 只报"触发事实已落盘 + 起跑了"**：HTTP 状态码只反映**世界写**的结局；这一轮自己在后台。
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("ref").get("revision").asLong())
        .as("触发事实落在 (main,2)——**先落事实、再跑那一轮**")
        .isEqualTo(2L);
    assertThat(body.get("decisionMakerId").asText()).isEqualTo(DM_ID);
    assertThat(body.get("running").asBoolean()).as("★ 异步：POST 返回时它还在跑").isTrue();
    assertThat(body.has("llmCalls")).as("★ 旧同步版的轨迹字段**不再出现在 POST 的响应里**（否则「立即返回」就是假的）").isFalse();

    JsonNode status = awaitDone(DM_ID);

    // ★★ 轨迹（决策人调了什么、看见了什么）现在从 run-status 读回——字段与旧同步版**同名同形**。
    assertThat(status.get("running").asBoolean()).isFalse();
    assertThat(status.get("done").asBoolean()).isTrue();
    assertThat(status.get("llmCalls").asInt()).as("一次工具调用 + 一次收口 = 两次模型调用（多轮，不是单轮）").isEqualTo(2);
    assertThat(status.get("startedAt").isNull()).as("跑过了 ⇒ 有起始时刻").isFalse();
    assertThat(status.get("elapsedMs").asLong()).as("跑完 ⇒ 时长是确定的读数").isGreaterThanOrEqualTo(0L);
    JsonNode result = status.get("result");
    assertThat(result.get("status").asText()).as("如实报结局（ok / aborted / failed）").isEqualTo("ok");
    assertThat(result.get("conversationId").asText())
        .as("会话 id 按世界事实（id + 会话世代）派生")
        .isEqualTo("decision-maker:" + DM_ID);
    assertThat(result.get("finalText").asText()).isEqualTo("已读图，本 tick 无动作");

    JsonNode calls = status.get("toolCalls");
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).get("tool").asText())
        .as("真名（平台身份），不是模型的线名 simos_map_hex")
        .isEqualTo("simos.map.hex");
    assertThat(calls.get(0).get("ok").asBoolean()).isTrue();
    assertThat(calls.get(0).get("summary").asText())
        .as("★ 摘要是**回灌给模型的那段文本**的同源副本——真跑过才会带上夹具世界的地形名")
        .contains("desert");

    // ★ 真经 Core：revision 行上写着这条命令（铁律 2：连"谁让谁跑了一轮"也是世界事实）。
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      assertThat(
              new Timeline(store, CHECKPOINT_INTERVAL)
                  .row(ref("main", 2))
                  .orElseThrow()
                  .commandType())
          .isEqualTo("sd.RunDecision");
    }
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("触发事实 + 回合结束统一结算（最后一段自然语言自动作为 NL 决策包）")
        .isEqualTo(3L);
  }

  @Test
  void anUnknownDecisionMakerIsRejectedAndLandsNoTriggerFact() throws Exception {
    HttpResponse<String> response = post("/api/sd/run-decision", runDecisionBody("dm-nope", 1L));

    assertThat(response.statusCode()).as("领域拒绝 ⇒ 422（与 /api/command 同表）").isEqualTo(422);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("result").asText()).isEqualTo("rejected");
    assertThat(body.get("reason").asText()).contains("决策人不存在");
    assertThat(llm.calls()).as("★ 触发事实没落盘 ⇒ 一次 LLM 都不烧").isZero();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(1L);
  }

  /**
   * ★★ **E4：连点两次同一个决策人 ⇒ 第二次明确拒绝（422 + 可读原因），且第一轮的账不被覆盖**。
   *
   * <p>★ **怎么造出"正在跑"的窗口**：假 LLM 卡在**第一次调用**上（两个 {@code CountDownLatch}：{@code entered} 让用例知道
   * 它真进了调用、{@code release} 由用例放行）⇒ 第二次 POST 确实发生在第一轮的运行窗口里，而不是"跑完了才点"。
   *
   * <p>★★ **两个判别位**（修之前必红）：① 第二次的状态码 = 200 而不是 422（旧代码没有闸门）；② 第二次用 {@code expectedRevision=2}（=
   * 第一轮触发事实落下的 head）发 ⇒ 旧代码会**再落一条触发事实**（head→3）并 {@code begin} 覆盖第一轮的 账。故这里两条都断言死：状态码 + {@code
   * head 仍 = 2}。
   */
  @Test
  void aSecondRunForTheSameMakerIsRejectedAndDoesNotOverwriteTheFirstRound() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    llm.gate(entered, release);
    scriptOneRound();

    HttpResponse<String> first = post("/api/sd/run-decision", runDecisionBody(DM_ID, 1L));
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    assertThat(entered.await(5, TimeUnit.SECONDS)).as("第一轮必须真的进了 LLM 调用（否则'正在跑'的窗口是假的）").isTrue();
    assertThat(
            JSON.readTree(get("/api/sd/decision-makers/" + DM_ID + "/run-status").body())
                .get("running")
                .asBoolean())
        .as("第一轮此刻正在跑")
        .isTrue();

    // ★ 连点第二次：用**当前 head（2）**当 expectedRevision（否则会先撞 409 游标过期，测不到并发闸门）。
    HttpResponse<String> second = post("/api/sd/run-decision", runDecisionBody(DM_ID, 2L));
    assertThat(second.statusCode()).as(second.body()).isEqualTo(422);
    JsonNode rejected = JSON.readTree(second.body());
    assertThat(rejected.get("result").asText()).isEqualTo("rejected");
    assertThat(rejected.get("reason").asText()).as("可读原因").contains("已有一轮在跑");
    assertThat(head()).as("★ 被拒的那次**不许**落触发事实（head 仍是第一轮落下的 2）").isEqualTo(2L);

    // 放行第一轮 ⇒ 它照常跑完，账是它自己的。
    release.countDown();
    JsonNode done = awaitDone(DM_ID);
    assertThat(done.get("result").get("status").asText()).as("第一轮照常收口").isEqualTo("ok");
    assertThat(done.get("llmCalls").asInt()).as("★ 第一轮的账没被第二次覆盖").isEqualTo(2);
    assertThat(done.get("toolCalls")).hasSize(1);
    assertThat(head()).as("触发事实 + 第一轮结束统一结算（自动 NL 决策包）").isEqualTo(3L);
  }

  /**
   * ★★ **重跑沿用上下文，只有显式重置才清**（用户 2026-09-23 原话：「重新决策（已有决策的情况下开始决策），llm
   * 的上下文是**沿用**而不是重置的；**只有点额外的上下文重置按键才重置**」）。
   *
   * <p>★★ **证据 = 每次运行"第一个请求的 {@code messages} 条数"**（不是会话库的行数）：会话库里有多少条只说明
   * "存了"，只有请求体才有资格证明"模型**真的看到了**它们"。三轮 + 一个对照组：
   *
   * <ol>
   *   <li>① 首轮（空会话）⇒ 恰 **1** 条（先注入的身份消息）；
   *   <li>② **重跑**（同一段会话，不重置）⇒ 比 ① **多**（上一轮的消息都还在）；
   *   <li>③ **显式重置**之后再来一轮 ⇒ 回到 **1** 条（落到了**另一段**新会话上，只有身份消息）。
   * </ol>
   *
   * <p>③ 就是对照组：它把"清空"这件事**只**归到重置那一步上——若重跑偷偷清了上下文，② 会等于 ① 而不是大于 ①。
   */
  @Test
  void rerunsKeepTheConversationAndOnlyAnExplicitResetClearsIt() throws Exception {
    scriptOneRound();
    int run1First = runOneRoundAndFirstRequestMessageCount();
    assertThat(run1First).as("① 首轮：空格 system + user 身份 + 本轮 user 规则提醒 = 3").isEqualTo(3);

    scriptOneRound();
    int run2First = runOneRoundAndFirstRequestMessageCount();
    assertThat(run2First).as("② **重跑沿用上下文** ⇒ 第一个请求里带着上一轮落下的消息（多于 ①）").isGreaterThan(run1First);

    // ③ 对照组：显式重置（真命令、落 revision、会话世代 +1）之后，上下文才清。
    long headBeforeReset = head();
    HttpResponse<String> reset =
        post("/api/sd/reset-decision-maker-conversation", resetBody(DM_ID, headBeforeReset));
    assertThat(reset.statusCode()).as(reset.body()).isEqualTo(200);
    assertThat(JSON.readTree(reset.body()).get("result").asText()).isEqualTo("committed");

    scriptOneRound();
    int run3First = runOneRoundAndFirstRequestMessageCount();
    // ★ 把三个读数**打出来**（本仓既有惯例：关键测量值随测试打印，便于写报告时**引用实测值**而不是推导值）。
    System.out.println(
        "[CONTEXT-CONTINUITY] 每轮第一个请求的 messages 条数：首轮="
            + run1First
            + " 重跑="
            + run2First
            + " 重置后="
            + run3First);
    assertThat(run3First)
        .as("③ 只有重置才清 ⇒ 下一轮落到另一段会话上，首个请求又回到 3 条（空格 system + 新 user 身份 + 本轮提醒）")
        .isEqualTo(3);
    assertThat(run3First).as("对照组：与重跑那一轮形成对照").isLessThan(run2First);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────

  /**
   * ★★ **第一轮之前先说一句**（用户要的文本框，2026-09-23）：{@code POST /api/sd/decision-makers/{id}/say}。
   *
   * <p>★★ 它钉的是一条**顺序**要求：空会话若直接落这条 user 消息，会话就不再为空 ⇒ {@code DecisionAgentRunner} 会认为身份已经说过了 ⇒
   * 模型收到一段**没有 system 消息**的上下文（不知道自己是谁——与现场那次 {@code HTTP 400: field messages is required}
   * 同一族的病）。故服务端必须**先补身份消息**再落这句。
   *
   * <p>★ 证据 = 下一轮**第一个请求**的 messages：`[system, user]`，且那条 user 正是刚说的那句话。
   */
  @Test
  void sayingSomethingBeforeTheFirstRoundKeepsTheIdentityMessageFirst() throws Exception {
    HttpResponse<String> response =
        post("/api/sd/decision-makers/" + DM_ID + "/say", "{\"text\":\"先守住北面的渡口\"}");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("conversationId").asText())
        .as("落进按世界事实派生的那段会话")
        .isEqualTo("decision-maker:" + DM_ID);
    assertThat(body.get("length").asInt()).isEqualTo("先守住北面的渡口".length());

    scriptOneRound();
    post("/api/sd/run-decision", runDecisionBody(DM_ID, head()));
    awaitDone(DM_ID);

    List<LlmMessage> first = llm.requests().get(0).messages();
    assertThat(first).as("空格 system + user 身份 + 用户那句话 + 本轮 user 规则提醒 = 4").hasSize(4);
    assertThat(first.get(0).role()).as("★ 顺序不能反：空格 system 占位在最前").isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(first.get(1).role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(first.get(2).role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(first.get(2).content())
        .as("模型**真的看到**了那句话（不是「存了没送」）")
        .anyMatch(part -> part instanceof ContentPart.Text text && text.text().contains("北面的渡口"));
  }

  /** 一轮的脚本：读一格 + 收口（**不出令** ⇒ 不触发审批）。 */
  private void scriptOneRound() {
    llm.enqueue(LlmResponse.toolCall("call-hex", "simos_map_hex", Map.of("q", 1, "r", 1)));
    llm.enqueue(LlmResponse.text("已读图，无动作"));
  }

  /** 跑一轮（等到 done）并返回**这一轮第一个请求**的 messages 条数。 */
  private int runOneRoundAndFirstRequestMessageCount() throws Exception {
    int before = llm.requestMessageCounts().size();
    HttpResponse<String> response = post("/api/sd/run-decision", runDecisionBody(DM_ID, head()));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    awaitDone(DM_ID);
    assertThat(llm.requestMessageCounts().size())
        .as("这一轮必须真的调过模型（否则下面这条读数无从谈起）")
        .isGreaterThan(before);
    return llm.requestMessageCounts().get(before);
  }

  /** 轮询到 {@code done=true}（**有界**：超时就抛，不无限等）。 */
  private JsonNode awaitDone(String decisionMakerId) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      JsonNode status =
          JSON.readTree(get("/api/sd/decision-makers/" + decisionMakerId + "/run-status").body());
      if (status.get("done").asBoolean()) {
        return status;
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("等不到这一轮跑完（" + decisionMakerId + "）——run-status 始终 done=false");
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private static String runDecisionBody(String decisionMakerId, long expectedRevision) {
    return "{\"branch\":\"main\",\"expectedRevision\":"
        + expectedRevision
        + ",\"decisionMakerId\":\""
        + decisionMakerId
        + "\"}";
  }

  private static String resetBody(String decisionMakerId, long expectedRevision) {
    return "{\"branch\":\"main\",\"expectedRevision\":"
        + expectedRevision
        + ",\"decisionMakerId\":\""
        + decisionMakerId
        + "\"}";
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * 脚本化假客户端 + **记下每次请求的 messages 条数**（"重跑沿用上下文"的证据载体）。
   *
   * <p>★ 只加一件事、不改语义：响应序列仍由 {@link FakeLlmClient} 逐条出队、耗空即抛（"跑飞"照旧当场暴露）。
   */
  private static final class RecordingFakeLlm implements LlmClient {

    private final FakeLlmClient delegate = new FakeLlmClient();
    private final List<Integer> messageCounts = new ArrayList<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    /**
     * ★ **闸门**（E4 用例用）：非空时，每次 {@code chat} 先 {@code countDown} 那个 {@code entered} 再 {@code await}
     * 那个 {@code release}（**有界 5s**，不让用例挂死）——用它把一轮"摁在 LLM 调用里"，好制造"正在跑"的窗口。
     */
    private volatile CountDownLatch entered;

    private volatile CountDownLatch release;

    void enqueue(LlmResponse response) {
      delegate.enqueue(response);
    }

    /** 装闸门：{@code entered} 在进入 {@code chat} 时倒数，{@code release} 放行（用完 {@code 5s} 超时兜底）。 */
    void gate(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    int calls() {
      return delegate.calls();
    }

    /** 按发生序：每次请求的 {@code messages} 条数。 */
    List<Integer> requestMessageCounts() {
      return List.copyOf(messageCounts);
    }

    /** 按发生序：每次请求本身（要判"模型到底看到了什么"就得看这个，不是看计数）。 */
    List<LlmRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public String model() {
      return delegate.model();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      messageCounts.add(request.messages().size());
      requests.add(request);
      CountDownLatch gateEntered = entered;
      if (gateEntered != null) {
        gateEntered.countDown();
      }
      CountDownLatch gateRelease = release;
      if (gateRelease != null) {
        try {
          gateRelease.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return delegate.chat(request);
    }
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 创世 {@code (main,1)}：两格世界 + 国家 FRA（区域 701）+ 单位 u-1 + 一个绑了 provider 的决策人。 */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
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
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, genesisUnit())));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social",
                    new SocialSnapshot(
                        ref("main", 1),
                        T7,
                        new SocialData(new LinkedHashMap<>(), Map.of(), Map.of())),
                "sd", new SdSnapshot(ref("main", 1), T7, sdState())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人。 */
  private static SdState sdState() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", R_FRA, 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_FRA.id(), DM_FRA);
    return SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
  }

  /** 两格世界（{@code (1,1)/(1,2)}，desert），区域 701（{@code nation:FRA}）覆盖两格。 */
  private static GameMap twoHexMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    Region fra =
        Region.of(
            R_FRA,
            "区域 701",
            Set.of(H11, H12),
            new RegionMeta(null, NationTag.tagFor(new NationId("FRA")), null, null));
    regions.put(fra.id(), fra);
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 最小单位：自身带位置、无父、无路线、视野缺省（1）。 */
  private static Unit genesisUnit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }
}
