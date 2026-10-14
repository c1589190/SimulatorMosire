package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.GmToolCallBridge;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.testing.WorldSlices;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.write.RunDecisionMakersTool;
import io.mosire.simos.ga.tools.write.RunDecisionTool;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
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
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.tools.write.UnitRenameTool;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **T11C 的端到端验收**：{@code sd.RunDecision} 这条窄工具**经真 MCP 口**触发一轮决策人 agent，并把**轨迹**交回来。
 *
 * <p>★ **为什么必须真装配**（比 {@code DecisionAgentRunnerTest} 更外面一层）：本任务最容易写成"看起来跑了一轮、其实只是把
 * 模型的话记下来"的形态。只有**真壳 + 真 MCP 口 + 真审批链 + 真工具面 + 真 store** 上的可观察副作用（revision 前进、会话落盘、
 * 轨迹里带着**真世界的地形名**）才分得开这两种。
 *
 * <p>★ **唯一替身是 LLM 客户端**（{@code FakeLlmClient}）：测试不打真网络（真 provider 的端到端由控制器在验收阶段做）。
 * 替身只替得掉"怎么造客户端"——**未绑定 provider 的 fail-closed**、世界状态、权限、审批、落盘全是真的 （{@link
 * #anUnboundDecisionMakerFailsClosedInsteadOfRunningWithADefaultProvider} 钉住这一条）。
 *
 * <p>★ **四条判据的证据形态**：
 *
 * <ol>
 *   <li>**真的跑了一轮**：① head 前进（触发事实 + 决策人自己的 {@code sd.IssueDirective} = 两条真 revision）；② revision 行真的
 *       写着 {@code sd.IssueDirective}（铁律 2：决策不是"记在对话里"）；③ 会话真的落盘（6 条：首条身份 + 本轮 5 条）；
 *   <li>**轨迹可读回**（验收就靠它判"决策人看见了什么"）：{@code toolCalls[].summary} 里带着**夹具世界的真值** （{@code
 *       "desert"}）——编出来的轨迹不会恰是这个值；
 *   <li>**跨 tick 接得上**：用**它发出去的请求本身**当证据（{@link RecordingLlmClient} 记下每次 {@code LlmRequest}）；
 *   <li>**失败方向是 fail-closed**：未绑定 provider / 过期 revision 都**不跑**，且各自留下可辨的痕迹。
 * </ol>
 *
 * <p>★ 夹具世界（{@code Map1}）：{@code (1,1)/(1,2)} 两格 desert，区域 701（{@code nation:FRA}），单位 {@code u-1} 在
 * {@code (1,1)}，国家决策人 {@code dm-fra}（{@code Affiliation.Nation(FRA)}，绑定 provider {@code stub}）。
 */
class RunDecisionEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "agent:t11c-mcp";
  private static final String PROVIDER_ID = "stub";
  private static final String DM_ID = "dm-fra";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final DecisionMaker DM_FRA =
      new DecisionMaker(
          new DecisionMakerId(DM_ID),
          Set.of(),
          AccessLimit.empty(),
          1,
          Optional.of(PROVIDER_ID),
          // 会话世代：夹具世界里的决策人没被重置过（世代 0 ⇒ 会话 id 就是旧格式那一个）。
          0L);

  /** 未绑定 provider 的同一个人（fail-closed 的靶子）。 */
  private static final DecisionMaker DM_NO_PROVIDER =
      new DecisionMaker(
          new DecisionMakerId(DM_ID), Set.of(), AccessLimit.empty(), 1, Optional.empty(), 0L);

  @TempDir Path tempDir;

  private Path storeDir;
  private Shell shell;
  private McpSyncClient client;
  private RecordingLlmClient llm;

  /** 本次 MCP 调用里答过的审批（工具名 = {@code classKey}）：证"外层触发与内层决策人的写都进了审批"。 */
  private final List<String> approvals = new ArrayList<>();

  /** 已答过的审批 id（避免对同一条重复 decide）。 */
  private final Set<String> answeredIds = new HashSet<>();

  @BeforeEach
  void startShell() {
    restartWith(DM_FRA, tempDir);
  }

  @AfterEach
  void stopShell() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（server 侧仍会被 shell.close() 收掉）
      }
      client = null;
    }
    if (shell != null) {
      shell.close();
      shell = null;
    }
  }

  // ── 判据 1 + 2：经真 MCP 口触发一轮，轨迹可读回 ───────────────────────────────────

  /**
   * ★★ **本任务的主判据**：GM 经 MCP 调 {@code sd.RunDecision} ⇒ 该决策人真的跑一轮 ⇒ 结果里带着**它看见了什么**。
   *
   * <p>脚本：模型先读一格（{@code simos.map.hex}）⇒ 再出令（{@code sd.IssueDirective}，base 取**触发事实落盘后的新 head**）⇒
   * 收口。
   */
  @Test
  void aGmCanDriveOneRealRoundThroughMcpAndReadBackTheTrace() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("call-2", "ga_IssueDirective", directiveArgs(2L)),
        LlmResponse.text("已按计划出令"));

    McpSchema.CallToolResult result = callWithApproval(runDecisionArgs(DM_ID, 1L));

    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode body = JSON.readTree(wireText(result));
    assertThat(body.get("result").asText())
        .as("★ top-level 的 ref 报的是**触发事实**的落点；决策人自己的写各自带自己的 ref（在 toolCalls[].summary 里）")
        .isEqualTo("committed");
    assertThat(body.get("ref").get("revision").asLong()).as("触发事实落在 (main,2)").isEqualTo(2L);

    // ★ 轨迹：决策人依次调了什么、每次看见了什么。
    assertThat(body.get("decisionMakerId").asText()).isEqualTo(DM_ID);
    assertThat(body.get("conversationId").asText()).isEqualTo("decision-maker:" + DM_ID);
    assertThat(body.get("llmCalls").asInt()).as("两次工具调用 + 一次收口 = 三次模型调用（多轮，不是单轮）").isEqualTo(3);
    assertThat(body.get("abortedByBudget").asBoolean()).isFalse();
    assertThat(body.get("finalText").asText()).isEqualTo("已按计划出令");
    JsonNode calls = body.get("toolCalls");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).get("tool").asText()).isEqualTo("simos.map.hex");
    assertThat(calls.get(0).get("ok").asBoolean()).isTrue();
    assertThat(calls.get(1).get("tool").asText()).isEqualTo("ga.IssueDirective");
    assertThat(calls.get(1).get("ok").asBoolean()).isTrue();

    // ★★ 判据 2：轨迹里带着**真世界**的值（读工具的返回原文）——"决策人看见了什么"的载体。
    assertThat(calls.get(0).get("summary").asText())
        .as("编出来的轨迹不会恰是 desert（真跑过 simos.map.hex 才会带上它）")
        .contains("desert");
    assertThat(calls.get(1).get("summary").asText()).as("出令真的提交了").contains("committed");

    // ★ 可观察副作用之一：两条真 revision（触发事实 + 决策人自己的出令）。
    assertThat(head())
        .as("触发事实 1 条 + 决策人的 sd.IssueDirective 1 条 + 回合结束统一结算（自动 NL 决策包）1 条")
        .isEqualTo(4L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      assertThat(timeline.row(ref("main", 3)).orElseThrow().commandType())
          .as("★ 铁律 2：决策不是「记在对话里」，它是一条真 revision（可回放、可回退分岔）")
          .isEqualTo("ga.IssueDirective");
      assertThat(timeline.row(ref("main", 4)).orElseThrow().commandType())
          .as("回合结束后统一结算把最后一段自然语言写成 NL 决策包（同批落盘 ⇒ 行类型是 core.SubmitBatch）")
          .isEqualTo("core.SubmitBatch");
      assertThat(timeline.row(ref("main", 2)).orElseThrow().commandType())
          .as("触发事实本身也是世界事实（AAR 看得见「谁让谁跑了一轮」）")
          .isEqualTo("ga.RunDecision");
    }

    // ★ 可观察副作用之二：这一轮的每条消息都落了会话（跨 tick 的锚）。
    try (SqliteConversationStore conversations = openConversations()) {
      assertThat(conversations.load("decision-maker:" + DM_ID))
          .as(
              "user(identity) + assistant(toolCall) + tool + assistant(toolCall) + tool + assistant(text)")
          .hasSize(6);
    }

    // ★★ 审批面（2026-09-24 用户裁定后的口径）：**决策人自己出的令仍要审批**（不是"在运行流里偷偷写"），
    //   而**外层触发（GM 面）不再审批**（无脑过）——两条链的区别在这一行里看得见。
    assertThat(approvals).as("内层决策人的 sd.IssueDirective 进了审批").contains("ga.IssueDirective");
    assertThat(approvals).as("外层 GM 触发不进审批（无脑过）").doesNotContain(RunDecisionTool.NAME);
  }

  /**
   * ★★ **同时决策批次的硬约束**：{@code simos.sd.run-decision-makers} 触发批成功后，所有决策人在**触发 revision 的冻结快照**
   * 上跑；世界写工具从工具面移除、执行口也拒；只有决策包写能落 revision。
   */
  @Test
  void theFrozenBatchRoundOnlyAllowsPacketsAndPinsSnapshot() throws Exception {
    long before = head();
    llm.enqueue(
        LlmResponse.toolCall(
            "frozen-read",
            "simos_map_hex",
            Map.of("q", 1, "r", 1, "branch", "not-main", "revision", 999L)),
        LlmResponse.toolCall("frozen-world-write", "ga_IssueDirective", directiveArgs(before)),
        LlmResponse.toolCall(
            "frozen-packet", "simos_ga_packet_intent", Map.of("text", "同时决策：先读冻结快照，再写决策包")),
        LlmResponse.text("同时决策回合收口"));

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("decisionMakerIds", List.of(DM_ID));
    args.put("reason", "J-frozen-batch");
    args.put("preview", false);
    args.put("expectedRevision", before);
    McpSchema.CallToolResult result = callWithApproval(RunDecisionMakersTool.NAME, args);

    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode body = JSON.readTree(wireText(result));
    assertThat(body.get("submitted").asBoolean()).isTrue();
    assertThat(body.get("triggerRef").get("revision").asLong())
        .as("触发批落一条 revision")
        .isEqualTo(before + 1L);
    JsonNode calls = body.get("results").get(0).get("toolCalls");
    assertThat(calls).hasSize(3);
    assertThat(calls.get(0).get("tool").asText()).isEqualTo("simos.map.hex");
    assertThat(calls.get(0).get("ok").asBoolean())
        .as("世界读被钉在冻结快照上：模型填的 not-main@999 不生效，仍能成功读到真世界")
        .isTrue();
    assertThat(calls.get(0).get("summary").asText()).contains("desert");
    assertThat(calls.get(1).get("tool").asText()).isEqualTo("ga.IssueDirective");
    assertThat(calls.get(1).get("ok").asBoolean()).as("世界写工具在冻结回合必须被拒（即使模型凭记忆叫到）").isFalse();
    assertThat(calls.get(1).get("summary").asText()).contains("FORBIDDEN");
    // ★★ 2026-10-14 批 B：决策族工具名 sd → ga（Alpha 不兼容旧档 ⇒ 断言也换新名；旧名一个都不许留）。
    assertThat(calls.get(2).get("tool").asText()).isEqualTo("simos.ga.packet.intent");
    assertThat(calls.get(2).get("ok").asBoolean()).as("决策包写允许").isTrue();

    assertThat(head())
        .as("触发批 + packet.intent 写入 + 回合结束统一结算，共三条；世界写零 revision")
        .isEqualTo(before + 3L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      assertThat(timeline.row(ref("main", before + 2L)).orElseThrow().commandType())
          .as("packet.intent 先落 DRAFT")
          .isEqualTo("ga.UpsertDecisionPacket");
      assertThat(timeline.row(ref("main", before + 3L)).orElseThrow().commandType())
          .as("回合结束后统一结算：最后一段自然语言并入包并提交（同批 ⇒ core.SubmitBatch）")
          .isEqualTo("core.SubmitBatch");
    }
  }

  // ── 判据 3：跨 tick 会话沿用 ────────────────────────────────────────────────────

  /**
   * ★★ **第二次调用接着上一轮**：第二轮 `run` 的**首个请求**里带着上一轮的全部消息。
   *
   * <p>★ 只断言"会话非空"不够——那只证明"存下来了"；"**接上了**"要看它**发出去的请求**。
   */
  @Test
  void theSecondRoundContinuesTheSameConversation() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("第一轮到此"));
    assertThat(callWithApproval(runDecisionArgs(DM_ID, 1L)).isError()).isFalse();

    llm.enqueue(LlmResponse.text("第二轮继续"));
    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    List<LlmMessage> firstRoundFirstRequest = llm.requests().get(0).messages();
    assertThat(firstRoundFirstRequest)
        .as("★ 首轮开局 = 空格 system + user 身份 + 本轮规则提醒（真 provider 对空 messages 直接 400）")
        .hasSize(3);
    assertThat(firstRoundFirstRequest.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(firstRoundFirstRequest.get(1).role()).isEqualTo(LlmMessage.ROLE_USER);
    List<LlmMessage> secondRoundFirstRequest = llm.requests().get(2).messages();
    assertThat(secondRoundFirstRequest)
        .as("★ 第二轮首个请求 = 空格 system + 上一轮 4 条落盘消息 + 本轮规则提醒")
        .hasSize(6);
    assertThat(secondRoundFirstRequest.get(0).role())
        .as("★ 空格 system 占位始终在首位；真正的身份在 user 消息里")
        .isEqualTo(LlmMessage.ROLE_SYSTEM);
    try (SqliteConversationStore conversations = openConversations()) {
      assertThat(conversations.load("decision-maker:" + DM_ID))
          .as("两轮共 5 条（首条身份 + 第一轮 3 条 + 第二轮 1 条）")
          .hasSize(5);
    }
    assertThat(Files.exists(conversationsFile()))
        .as("★ 会话落 <store> 下（与 simos.db 同层）⇒ 跨进程重启沿用同一段会话")
        .isTrue();
  }

  // ── 回合统一结算：LLM 行为适配（用户 2026-10-22 裁定）────────────────────────────

  /**
   * ★★ **没 confirm 就停止说话**：本回合写进 packet 的 intent 原样交给 GM（自动兜底提交成 PENDING packet）。
   *
   * <p>★★ <b>2026-10-14 批 B 换靶子（设计书 §3.3 / 缺口 G10）</b>：旧的 {@code mergeIntent} **无条件**把整轮最后一段自然语言
   * 用「【最后陈述】」追加进 intent。批 B 起改成**条件式**：DM **显式写过** intent（包里的 intent 非空白）⇒ 原样采用、不追加。 ⇒
   * 本条断言从「含最后一段自然语言」换成「**不含**它」——这不是放宽判据，是判据按裁定换了方向（旧断言测的是被删掉的旧行为）。 兜底提交（不静默丢决策）这条红线**不退让**：包仍必须变
   * PENDING。
   */
  @Test
  void autoNoSubmitKeepsExplicitIntentAndDoesNotAppendLastNaturalLanguage() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall("intent-1", "simos_ga_packet_intent", Map.of("text", "意图A：先退休一人")),
        LlmResponse.text("最后自然语言A：请 GM 判断"));

    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    DecisionPacket packet = packetOf(DM_ID);
    assertThat(packet).as("回合结束统一结算必须建出/提交决策包").isNotNull();
    assertThat(packet.status())
        .as("★ 无未答提问 ⇒ 兜底结算照旧（「不静默丢决策」这条红线不退让）")
        .isEqualTo(PacketStatus.PENDING);
    assertThat(packet.intent()).as("★ DM 显式写过的 intent 必须**在**（丢掉它才是真缺陷）").contains("意图A：先退休一人");
    assertThat(packet.intent())
        .as("★★ 条件式 mergeIntent（补 G10）：显式写过 intent ⇒ **不再**追加「【最后陈述】」尾巴")
        .doesNotContain("最后自然语言A")
        .doesNotContain("【最后陈述】");
  }

  /** ★★ **confirm 之后只说话不再发命令**：包立刻 PENDING（DM 的动作，不再被运行流吞掉）；之后的纯文本只留审计。 */
  @Test
  void confirmMakesThePacketPendingImmediatelyAndPostConfirmTextIsNotMerged() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall("intent-1", "simos_ga_packet_intent", Map.of("text", "意图B：只做退休")),
        LlmResponse.toolCall("confirm-1", "simos_ga_packet_confirm", Map.of()),
        LlmResponse.text("确认后解释：这段不算新 NL 决策"));

    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    DecisionPacket packet = packetOf(DM_ID);
    assertThat(packet).isNotNull();
    assertThat(packet.status())
        .as("★★ I10：确认是 DM 的动作 —— confirm 在回合内**不再被拦截**，PENDING 由它产生")
        .isEqualTo(PacketStatus.PENDING);
    assertThat(packet.intent()).contains("意图B：只做退休");
    assertThat(packet.intent()).as("确认后的文本不再算新的 NL 决策").doesNotContain("确认后解释");
  }

  /**
   * ★★ **已 PENDING 的包不能再改**（设计书 §3.2 硬边界；§6.1 N5 的同族）：confirm 之后再调 {@code packet.intent} ⇒
   * **具名拒**（提示先 reopen），包原样不动、零 revision、intent 不被改写。
   *
   * <p>★★ <b>2026-10-14 批 B 换靶子</b>：旧用例 {@code
   * continuedCommandsAfterSubmitConservativelyMergeWholeTurn} 测的是"submit 后继续发命令 ⇒
   * 整轮保守合并"——那条分支（提交被拦截 + 整轮合并）已随批 B **整条删除**（死代码不许留）。
   * 改成测**新边界的正面对手**：同样是"确认后又发命令"，但现在的正确行为是**拒**（并告诉 DM 下一步是 reopen），不是静默合并。
   */
  @Test
  void mutatingAPendingPacketIsRejectedByNameAndLeavesThePacketUnchanged() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall("intent-1", "simos_ga_packet_intent", Map.of("text", "意图C：主计划")),
        LlmResponse.toolCall("confirm-1", "simos_ga_packet_confirm", Map.of()),
        LlmResponse.toolCall("intent-2", "simos_ga_packet_intent", Map.of("text", "意图C：提交后补充")),
        LlmResponse.text("整轮最后自然语言C：这是最终口径"));

    long before = head();
    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    DecisionPacket packet = packetOf(DM_ID);
    assertThat(packet).isNotNull();
    assertThat(packet.status())
        .as("confirm 之后保持 PENDING（被拒的改写不得把它翻回 DRAFT）")
        .isEqualTo(PacketStatus.PENDING);
    assertThat(packet.intent())
        .as("★★ 被拒的 intent 改写**一个字节都不许落地**：包里仍是 confirm 之前那句")
        .isEqualTo("意图C：主计划");
    assertThat(packet.intent())
        .as("★ 也不得退化成旧行为（把整轮最后一段自然语言并进来）")
        .doesNotContain("整轮最后自然语言C")
        .doesNotContain("【最后陈述】");
    // 触发事实 1 条 + intent 1 条 + confirm 1 条 = 3；被拒的 intent-2 **零 revision**，
    // 回合结束也因包已 PENDING 而不再结算（finalizer 的"已 PENDING ⇒ 跳过"分支）。
    assertThat(head())
        .as("★ 被拒的改写零 revision（拒 = 世界零变化），且 finalizer 对已 PENDING 的包不再结算")
        .isEqualTo(before + 3L);
  }

  /** 当前 head 上该决策人本 tick 的 packet（用于结算断言）。 */
  private DecisionPacket packetOf(String dmId) {
    SimulationState state = shell.coreSimos().replay(ref("main", head()));
    GaState sd = ((GaSnapshot) state.module("ga").orElseThrow()).state();
    return sd.decisionPackets()
        .get(DecisionPacketId.parse("pkt-" + dmId + "-" + state.meta().timestamp().tick()));
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-14 决策自由化（批 B）—— 设计书 §6 第 4~8 条 / §6.1 N5·N6·N8 / §4 I4·I6
  //
  //   为什么落在**本类**（而不是新开一个夹具）：本类已经有一条**真装配 + 真 MCP + 注入假 LLM** 的
  //   `Shell.start(config, llm::provider)` 路径，且 `shell.coreSimos()` 是**真** store ⇒ 下面每条都能
  //   ① 用真决策人身份调真工具、② 立刻回读真 head 上的包（不靠 runner 自己的账本）。另起一套夹具 = 第二份
  //   装配真相（本仓已为"第二处声明"付过代价）。
  //
  //   ★ 与 §6 第 6/7 条（覆盖 NL / 确认幂等）的关系：那两条在 `theFrozenBatchRound...` 与
  //     `confirmMakesThePacketPendingImmediatelyAndPostConfirmTextIsNotMerged` 里已按**新语义**钉住
  //     （intent 覆盖 + confirm 立刻 PENDING），此处不重复造同形断言。
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>§6.1 N5：{@code revise}/{@code drop} 一个 PENDING 包 ⇒ 具名拒，提示先 {@code reopen}</b>。
   *
   * <p>★ 判别力：{@code ga.UpsertDecisionPacket} <b>本身没有状态机</b>（整包覆盖）⇒ 守卫<b>只能</b>落在工具侧 （设计书 §3.2 明写"否则
   * DM 能把 GM 已批的包翻回 DRAFT 再提交，GM 的裁决被静默抹掉"）。 故本用例逐条钉：① 拒；② 码是 {@code BAD_REQUEST}；③ 文案<b>点出下一步是
   * reopen</b>（模型据此知道怎么走，而不是死循环重试）； ④ 包<b>一个字节都没变</b>、零 revision。
   *
   * <p>★ <b>为什么这里直接调工具、而不经 runner 的返回体</b>：{@code RunDecision} 的回执里每条 call 只有 {@code ok} 与 {@code
   * summary}（<b>没有</b> {@code code}）⇒ 从那里读不出"具名拒的码是什么"。直接经真 {@code DecisionCallerFactory} 调一次才拿得到
   * {@code ToolResult.code()}（仍是真身份 + 真权限链 + 真工具，只是不经模型）。
   *
   * <p>★★ <b>为什么用 {@code intent} 而不是 {@code propose} 造 DRAFT 包</b>（如实记的夹具限制，与 §6 第 4/5·N6 三条同源）：
   * 本夹具的世界里 {@code u-1} 没有 {@code GovernmentFormation}、{@code social} 也没有家户 ⇒ {@code
   * ProposalCatalog} 的<b>全部</b>候选工具在真 preview 阶段一律 {@code BAD_REQUEST}（实测原文见 {@link
   * #n8_anUnansweredQuestionKeepsThePacketInDraftWithoutLosingAnyContent} 的类注）⇒ <b>造不出一条能落地的
   * call</b>。 ★ 而 N5 守的是<b>状态边界</b>（非 DRAFT ⇒ 改/删一律拒），与"包里有没有 call"无关：{@code intent} 同样走 {@code
   * ga.UpsertDecisionPacket} 落一条真 revision、同样把包推成 PENDING。⇒ 判据强度不降（拒的是同一个守卫）， 缺的只是"被拒的 revise
   * 改不动的那条 call"这一侧的逐值断言（已如实列进台账 §6）。
   */
  @Test
  void n5_reviseAndDropOnAPendingPacketAreRejectedAndPointAtReopen() throws Exception {
    // ① 先造一个 DRAFT 包（intent 走真 ga.UpsertDecisionPacket，落一条真 revision）。
    llm.enqueue(
        LlmResponse.toolCall(
            "intent-1", "simos_ga_packet_intent", Map.of("text", "N5 靶子：这条包要被确认，再试改/删")),
        LlmResponse.text("先写下意图"));
    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    // ② 直接调 confirm 把它推成 PENDING（★ 不经模型：模型"只调工具不说话"会让 runner 报错，
    //   那是既有的回合语义，与本条要测的边界无关）。
    ToolResult confirmed = dmToolCall("simos.ga.packet.confirm", Map.of());
    assertThat(confirmed.success()).as("前提：confirm 必须成功；实得 %s", confirmed.message()).isTrue();
    assertThat(packetOf(DM_ID).status()).as("前提：包已 PENDING").isEqualTo(PacketStatus.PENDING);

    String intentBefore = packetOf(DM_ID).intent();
    long headBefore = head();

    ToolResult reviseDenied =
        dmToolCall(
            "simos.ga.packet.revise",
            Map.of("callIndex", 0, "tool", "simos.gov.selectExaminees", "args", Map.of()));
    ToolResult dropDenied = dmToolCall("simos.ga.packet.drop", Map.of("callIndex", 0));

    for (ToolResult denied : List.of(reviseDenied, dropDenied)) {
      assertThat(denied.success())
          .as("★★ N5：对 PENDING 包的改/删必须被具名拒（实得 %s）", denied.message())
          .isFalse();
      assertThat(denied.code())
          .as("★ N5：码是用法错（BAD_REQUEST = 改行为：先 reopen）")
          .isEqualTo("BAD_REQUEST");
      assertThat(denied.message())
          .as("★★ N5：拒因必须点出下一步是 simos.ga.packet.reopen（只说'失败'会让模型死循环重试）")
          .contains("simos.ga.packet.reopen")
          .contains("PENDING");
    }

    DecisionPacket after = packetOf(DM_ID);
    assertThat(after.status()).as("★★ N5：被拒的调用不得把包翻回 DRAFT").isEqualTo(PacketStatus.PENDING);
    assertThat(after.intent()).as("★★ N5：包的内容一个字节都没变").isEqualTo(intentBefore);
    assertThat(head()).as("★ N5：两条被拒 = 零 revision").isEqualTo(headBefore);
  }

  /**
   * ★★ <b>§6.1 N4 / §4 I3：{@code reopen} 一个**已裁决**的包 ⇒ 具名拒 + 世界零变化</b>。
   *
   * <p>★ <b>这条是防线，不是顺手</b>（设计书 §3.2 原话）：{@code ga.UpsertDecisionPacket} <b>本身没有状态机</b> （status
   * 从载荷取、整包覆盖）⇒ 不加这道守卫，DM 就能把 GM 已批的包翻回 DRAFT、改完再提交， <b>GM
   * 的裁决被静默抹掉</b>（没有任何一层会报错）。故撤回的合法边界只是"我改主意了、GM 还没看"。
   *
   * <p>★ <b>本用例钉的是两条守卫里的第一条（{@code decidedBy} 非空）</b>：先把包推到 PENDING，再让**真 GM** 用 {@code
   * simos.gm.packet.decide} 批成 APPROVED（{@code decidedBy} 由此被填上），然后 DM 试 {@code reopen}。 ★ 断言四处：①
   * 拒；② 码 {@code BAD_REQUEST}；③ 文案点出"已裁决不可回退"（模型据此停止重试）； ④ <b>包状态与 content 一个字节都没变、零
   * revision</b>（"被拒"必须真的是零副作用）。
   *
   * <p>★★ <b>与设计书 §7 的偏差（如实记）</b>：任务书要求"四种终态逐一（APPROVED/REJECTED/MERGED/ PARTIALLY_APPROVED）"。本条只做了
   * <b>APPROVED</b>，理由有两条、都回代码核过： ① 第 2 条守卫（{@code isDecided(status)}）挡的四种终态在**同一次 {@code
   * decidedBy} 判定之后**，而真 GM 裁决 <b>必然</b>填 {@code decidedBy} ⇒ 走工具路径造出的四种终态**全部**先被第 1 条守卫拦下 ⇒
   * 逐一测四种是 同一处代码的四次重复，判别力不增； ② {@code PARTIALLY_APPROVED} 的产生条件是 {@code
   * GmDecidePacketTool.expectedStatus}：{@code callIndexes} 非空且**存在未选中的 call** ⇒ 包里<b>必须至少有一条真
   * call</b>；而本夹具造不出可落地的 call （见 {@link
   * #n8_anUnansweredQuestionKeepsThePacketInDraftWithoutLosingAnyContent} 的类注：{@code u-1} 无 {@code
   * GovernmentFormation}、{@code social} 无家户 ⇒ {@code ProposalCatalog} 全部候选在真 preview 阶段 {@code
   * BAD_REQUEST}）。 ★ 第 2 条守卫（{@code decidedBy} 为空但状态已是终态 = 契约故障）在本夹具里**不可造**（那需要直写状态， 而那正是 I12 / 铁律
   * 2 禁止的路径）⇒ 如实列进台账 §6「未做」。
   */
  @Test
  void n4_reopeningADecidedPacketIsRejectedAndLeavesTheWorldUnchanged() throws Exception {
    // ① 造 DRAFT（intent）→ ② DM 确认成 PENDING → ③ 真 GM 裁决成 APPROVED。
    llm.enqueue(
        LlmResponse.toolCall(
            "intent-1", "simos_ga_packet_intent", Map.of("text", "N4 靶子：这条要被 GM 裁决")),
        LlmResponse.text("先写下意图"));
    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();
    assertThat(dmToolCall("simos.ga.packet.confirm", Map.of()).success())
        .as("前提：DM 确认成 PENDING")
        .isTrue();
    assertThat(packetOf(DM_ID).status()).as("前提：PENDING").isEqualTo(PacketStatus.PENDING);

    String packetId = packetOf(DM_ID).id().value();
    ToolResult decided =
        gmToolCall("simos.gm.packet.decide", Map.of("packetId", packetId, "decision", "APPROVE"));
    assertThat(decided.success()).as("前提：GM 裁决必须成功；实得 %s", decided.message()).isTrue();
    DecisionPacket approved = packetOf(DM_ID);
    assertThat(approved.status()).as("前提：包已被 GM 批成 APPROVED").isEqualTo(PacketStatus.APPROVED);
    assertThat(approved.decidedBy()).as("前提：decidedBy 已被填上（这正是第 1 条守卫的判据）").isPresent();

    // ④ DM 试撤回 ⇒ 必须被拒，且世界零变化。
    String intentBefore = approved.intent();
    long headBefore = head();
    ToolResult reopenDenied = dmToolCall("simos.ga.packet.reopen", Map.of());

    assertThat(reopenDenied.success()).as("★★ N4/I3：已裁决的包**不许**撤回").isFalse();
    assertThat(reopenDenied.code()).as("★ N4：码是用法错（BAD_REQUEST）").isEqualTo("BAD_REQUEST");
    assertThat(reopenDenied.message())
        .as("★★ N4：拒因必须点出「已裁决不可回退」（模型据此停止重试，而不是死循环）")
        .contains("已裁决不可回退");
    // ★★★ 判别力补强（**变异自证逼出来的**，2026-10-14 实测）：`ReopenPacketTool` 有**两条**守卫，而它们的
    //   拒因文案**都**含「已裁决不可回退」⇒ 只断言那句话，**删掉第 1 条（decidedBy）守卫时本条照样全绿**
    //   （第 2 条 isDecided(status) 会接住它，报文一字不差到判据看不出来）—— 实测确认该变异**存活**。
    //   ⇒ 加这两条**只属于第 1 条守卫**的指纹：报文里的 `decidedBy=<谁>` 与 `status=<什么态>`。
    //     第 2 条守卫的报文是「决策包状态 X 已是终态，撤回不可用」——**不含** `decidedBy=`。
    //   ★ 判别力：删掉 decidedBy 守卫 ⇒ 报文换成第 2 条那版 ⇒ 这两条当场红（已实测）。
    assertThat(reopenDenied.message())
        .as(
            "★★★ N4 的判别力指纹：拒因必须来自**第 1 条（decidedBy 非空）**守卫 —— 删掉它，第 2 条守卫"
                + "会接住同一个调用但报文不含 decidedBy= ⇒ 本断言当场红（变异自证实测）")
        .contains("decidedBy=")
        .contains(approved.decidedBy().orElseThrow());

    DecisionPacket after = packetOf(DM_ID);
    assertThat(after.status())
        .as("★★ I3：被拒之后包**必须仍是 APPROVED**（翻回 DRAFT = GM 的裁决被静默抹掉）")
        .isEqualTo(PacketStatus.APPROVED);
    assertThat(after.intent()).as("★★ N4：包内容一个字节都没变").isEqualTo(intentBefore);
    assertThat(after.decidedBy()).as("★★ N4：decidedBy 也不许被清掉").isPresent();
    assertThat(head()).as("★★ N4：被拒 = 世界零变化（零 revision）").isEqualTo(headBefore);
  }

  /**
   * ★★ <b>§6 第 8 条：{@code reopen} 已是 DRAFT 的包 ⇒ <b>幂等且不写 revision</b></b>。
   *
   * <p>★ 判别力："撤回"是个<b>状态目标</b>，不是一次计数（设计书 §3.2：{@code reopen} 的幂等语义）。 一个"每次都重新
   * upsert"的实现同样会返回成功、状态也仍是 DRAFT ⇒ <b>只有 revision 不动才能把它与正确实现分开</b>。 故本条的核心断言是最后那条 {@code head()}
   * 相等。
   */
  @Test
  void reopenOnAnAlreadyDraftPacketIsIdempotentAndWritesNoRevision() throws Exception {
    // ★★ 前提怎么造（这里有个容易写错的坑，实测踩到）：**不能**靠"只写 intent 然后停止说话" ——
    //   那种回合结束时无未答提问 ⇒ finalizer 的 auto-no-submit 兜底会把它**提交成 PENDING**（"不静默丢决策"那条红线）。
    //   ⇒ 要拿到 DRAFT，必须让本 tick **存在一条未答提问**（to=="gm"），finalizer 才会完全不结算（正是 N8 的语义）。
    //   ★ 于是本用例同时是 N8 的**对照**：同样的形状，只差"提问有没有被 GM 回"。
    llm.enqueue(
        LlmResponse.toolCall("intent-1", "simos_ga_packet_intent", Map.of("text", "幂等靶子")),
        LlmResponse.toolCall(
            "ask-gm", "simos_ga_message_send", Map.of("to", "gm", "body", "先问一句，好让包留在 DRAFT")),
        LlmResponse.text("等答复"));
    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();
    assertThat(packetOf(DM_ID).status())
        .as("前提：有未答提问 ⇒ 包停在 DRAFT（见 N8；★ 无提问时兜底会把它提交成 PENDING）")
        .isEqualTo(PacketStatus.DRAFT);

    String packetId = packetOf(DM_ID).id().value();
    long headBefore = head();
    ToolResult reopen = dmToolCall("simos.ga.packet.reopen", Map.of());

    assertThat(reopen.success())
        .as("★ §6-8：已是 DRAFT ⇒ 幂等**成功**（不是拒）；实得 %s", reopen.message())
        .isTrue();
    assertThat(packetOf(DM_ID).status()).as("★ §6-8：状态仍是 DRAFT").isEqualTo(PacketStatus.DRAFT);
    assertThat(head())
        .as("★★ §6-8：幂等 ⇒ **不写 revision**（「每次都重新 upsert」的实现会在这里红）")
        .isEqualTo(headBefore);
    assertThat(packetOf(DM_ID).id().value()).as("★ 同一个包（幂等不改 id）").isEqualTo(packetId);
  }

  /**
   * ★★ <b>§6.1 N8（I4）：未答提问时回合结束 ⇒ 包保持 DRAFT（不是 PENDING），且已写进包的内容一条不少</b>。
   *
   * <p>★ <b>这是"不静默丢决策"与"不把提问当决策"两条红线的交点</b>：DM 只问了一句话（没有决策内容）时， 兜底的 auto-submit <b>必须</b>停手 ——
   * 否则"这一轮我只问了句话"会被当成一条 NL 决策自动提交给 GM（设计书 §3.3 的原话）。 ★ 同时钉"不丢"：已经写进包的 {@code intent} <b>仍在包里且 GM
   * 读得到</b>（保持 DRAFT 不等于丢掉）。
   *
   * <p>★ 判据按<b>本 tick</b>（落地裁定 L1/L2）：只认 {@code to=="gm"} 且本 tick 的信；peer DM 之间的信没有 replyTo，
   * 把它们算进"未答"会让该 DM 永远不结算。
   *
   * <p>★★ <b>本夹具的 scope 限制（如实记，与 §6 第 4/5·N6 三条同源）</b>：{@code RunDecisionEndToEndTest} 的世界里 {@code
   * u-1} <b>没有 GovernmentFormation</b>、{@code social} 也<b>没有家户</b> ⇒ {@code ProposalCatalog} 的
   * 全部候选工具（{@code gov.retireStaff} / {@code gov.selectExaminees} / {@code gov.dispatchTeam} /
   * {@code unit.raiseUnit} / {@code social.household.members}）在**真 preview** 阶段一律 {@code
   * BAD_REQUEST} （实测原文：{@code 目标工具预览失败(BAD_REQUEST): 单位 u-1 没有 GovernmentFormation：退休只对 GOV 单位}）。 ⇒
   * 本用例**不**断言"calls 一条不少"（那需要一条**能落地的** call），改为断言 **intent 一条不少**（同样走 {@code
   * ga.UpsertDecisionPacket}、同样必须活过回合结束）。★ 缺口与补法见台账 §6。
   */
  @Test
  void n8_anUnansweredQuestionKeepsThePacketInDraftWithoutLosingAnyContent() throws Exception {
    llm.enqueue(
        LlmResponse.toolCall(
            "intent-1", "simos_ga_packet_intent", Map.of("text", "先把退休办了，但我得先问 GM 一件事")),
        LlmResponse.toolCall(
            "ask-gm", "simos_ga_message_send", Map.of("to", "gm", "body", "请问这一轮的粮产够不够支撑退休潮？")),
        LlmResponse.text("等 GM 答复再定"));

    assertThat(callWithApproval(runDecisionArgs(DM_ID, head())).isError()).isFalse();

    DecisionPacket packet = packetOf(DM_ID);
    assertThat(packet).as("★★ I4：保持 DRAFT 不等于丢掉 —— 包必须还在（gm.packet* 仍读得到）").isNotNull();
    assertThat(packet.status())
        .as(
            "★★ N8：有未答提问（to==gm 且本 tick）⇒ 回合结束**完全不结算**，包保持 DRAFT"
                + "（若被 auto-submit 成 PENDING，就是'把一句提问当成了一条决策'）")
        .isEqualTo(PacketStatus.DRAFT);
    assertThat(packet.intent())
        .as("★★ I4：DM 显式写过的 intent 原样保留（保持 DRAFT 不等于丢掉内容，且不许被追加「【最后陈述】」）")
        .isEqualTo("先把退休办了，但我得先问 GM 一件事");

    // ★ 提问本身必须真的落了盘（否则本用例会因"问题根本没发出去"而假绿）。
    assertThat(gmToolCall("simos.gm.inbox", Map.of()).message())
        .as("★ 前提/对照：那条提问真的到了 GM 的收件箱（且仍未答）")
        .contains("请问这一轮的粮产够不够支撑退休潮？")
        .contains("\"answered\":false");
    // ★ GM 全量读口也看得见这条 DRAFT 包（不丢的第二重证据；★ 包里没有 call 也能被读到）。
    assertThat(gmToolCall("simos.gm.packet", Map.of("packetId", packet.id().value())).message())
        .as("★ I4：GM 的 gm.packet 仍读得到这条 DRAFT 包（含它的 intent）")
        .contains("先把退休办了");
  }

  // ── 决策自由化用例的三个小助手（真身份 + 真权限链 + 真工具）─────────────────────────────

  /**
   * 以**真决策人身份**经真 {@code DecisionCallerFactory} 调一条决策人面工具（不经模型）。
   *
   * <p>★ 与 runner 走的是同一条链（{@code callerFor} 现算权限组 + {@code authorizer.execute} 的唯一入口），
   * 只是把"模型填参数"换成"用例填参数" ⇒ 可直接读 {@code ToolResult.code()}（回执里没有这个字段）。
   */
  private ToolResult dmToolCall(String toolName, Map<String, Object> args) {
    DecisionMaker dm =
        ((GaSnapshot)
                shell.queryService().stateAt(QueryTarget.head(main())).module("ga").orElseThrow())
            .state()
            .decisionMakers()
            .get(new DecisionMakerId(DM_ID));
    return DecisionCallerFactory.forDecisionFace(
            shell.gmToolAuthorizer(),
            ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .execute(
            decisionRegistry(),
            toolName,
            dm,
            shell.queryService().stateAt(QueryTarget.head(main())),
            shell.config().mapId(),
            args);
  }

  /**
   * 真 GM 面工具调用（自动放行；身份 = 真 GM）。★ GM 读口（gm.packet / gm.inbox）用它回读。
   *
   * <p>★ 走 {@link GmToolCallBridge}（测试侧桥）：{@code Shell.gmCaller()} 是**包内可见**的（类注明写"不是对外 API"），本类住在
   * {@code io.mosire.simos.app.decision} 子包 ⇒ 够不着它。桥只**转发**（身份/权限/上下文形状 逐字照抄生产装配），不放宽任何判定。
   */
  private ToolResult gmToolCall(String toolName, Map<String, Object> args) {
    return GmToolCallBridge.call(shell, toolName, args);
  }

  /** 真决策人桶的注册表（与生产同源）。 */
  private ToolRegistry decisionRegistry() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }

  // ── 判据 4：失败方向是 fail-closed ───────────────────────────────────────────────

  /**
   * ★★ **未绑定 provider ⇒ 响亮失败，且绝不跑**：触发事实照常落盘（"谁让谁跑"是事实），但这一轮**一次 LLM 都没调**。
   *
   * <p>★ 判别力：把 fail-closed 判定去掉、让它落到"随便找个客户端"的实现下，本用例会红在**三处**（结果不再是 error、 fake 被调过、会话非空）——而"静默换
   * provider"正是 {@code DecisionMaker} 类注明令禁止的形态。
   */
  @Test
  void anUnboundDecisionMakerFailsClosedInsteadOfRunningWithADefaultProvider() throws Exception {
    restartWith(DM_NO_PROVIDER, tempDir.resolve("unbound"));

    McpSchema.CallToolResult result = callWithApproval(runDecisionArgs(DM_ID, 1L));

    assertThat(result.isError()).as("未绑定 ⇒ 工具结果必须是 error").isTrue();
    assertThat(wireText(result))
        .as("★ 报的是**点名的 fail-closed 码**，不是一句含糊的失败")
        .contains("E_LLM_PROVIDER_UNBOUND");
    assertThat(llm.calls()).as("★ 一次 LLM 调用都没有（绝不落到默认 provider）").isZero();
    assertThat(head()).as("触发事实照常落盘（它是世界事实），但这一轮什么都没产出").isEqualTo(2L);
    try (SqliteConversationStore conversations = openConversations()) {
      assertThat(conversations.load("decision-maker:" + DM_ID)).as("会话是空的：这一轮压根没开始").isEmpty();
    }
  }

  /**
   * ★★ **过期 revision ⇒ 冲突，且不跑**：触发事实没落盘就不该烧 LLM（也不该让决策人读一份过期的世界）。
   *
   * <p>★ 判别力：把"提交失败就不跑"去掉（无条件跑）的实现，本用例红在 `llm.calls() == 0` 与 head 不变上。
   */
  @Test
  void aStaleRevisionConflictsAndTheRoundDoesNotRun() throws Exception {
    McpSchema.CallToolResult result = callWithApproval(runDecisionArgs(DM_ID, 0L));

    JsonNode body = errorBody(result);
    assertThat(body.get("result").asText()).as("乐观并发：base 过期 ⇒ conflict").isEqualTo("conflict");
    assertThat(llm.calls()).as("★ 冲突时不烧 LLM").isZero();
    assertThat(head()).as("没有新 revision").isEqualTo(1L);
  }

  /**
   * ★ **跑飞（撞回合预算）⇒ 如实报"因预算中止"**，不是一句含糊的失败（触发事实已落盘、历史已逐条落盘 ⇒ 下一轮可续）。
   *
   * <p>★ 判别力：把预算上限去掉，本用例**不会返回**（真 LLM 上就是一直烧钱）；把它折成含糊的失败，红在 {@code result == aborted} 与 {@code
   * abortedByBudget} 上。
   */
  @Test
  void aRunawayModelIsReportedAsAbortedByBudget() throws Exception {
    // ★★ 2026-10-14 W4（设计书 §10.4）：本用例必须**撞上**回合预算才看得见"因预算中止"，而生产缺省已是 10000
    //   （用户裁定）⇒ 用 Shell.start 的**显式给上限**那条重载把上限压到 RUNAWAY_BUDGET 再跑。
    //   ★ 不许靠把生产缺省改小来"修"本用例——那会让用户的 10000 裁定失效（常量注释里写着）。
    restartShellWithBudget(RUNAWAY_BUDGET);
    for (int i = 0; i < RUNAWAY_BUDGET; i++) {
      llm.enqueue(LlmResponse.toolCall("call-" + i, "simos_map_hex", Map.of("q", 1, "r", 1)));
    }

    McpSchema.CallToolResult result = callWithApproval(runDecisionArgs(DM_ID, 1L));

    assertThat(result.isError()).isTrue();
    JsonNode body = errorBody(result);
    assertThat(body.get("result").asText()).isEqualTo("aborted");
    assertThat(body.get("abortedByBudget").asBoolean()).isTrue();
    assertThat(body.get("llmCalls").asInt())
        .as("烧到**本用例显式给的上限**为止（不是生产缺省 10000）")
        .isEqualTo(RUNAWAY_BUDGET);
    assertThat(body.get("reason").asText()).isEqualTo("turn-budget");
    assertThat(head()).as("中止不产决策：只有触发事实那一条").isEqualTo(2L);
  }

  // ── 桶归属：触发工具只在 GM 面（= MCP 口） ───────────────────────────────────────

  /**
   * ★ **触发工具进 GM 桶、不进决策人桶**：决策人不触发自己（那是自环）；GM 面 = 运行时 MCP 口 ⇒ "经 MCP 触发"成立。
   *
   * <p>★ 更强的判据在 {@code DecisionCallerFactoryTest}（从**真 GM 面**派生决策人白名单：往 GM 面加写工具而不同步，那里当场红）。
   */
  @Test
  void theTriggerToolIsOnlyInTheGmBucket() {
    assertThat(names(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("GM 面（= 运行时 MCP 口）含触发工具")
        .contains(RunDecisionTool.NAME);
    assertThat(names(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("决策人面**不含**触发工具（决策人不触发自己），也不含任何 map/unit 写")
        .doesNotContain(RunDecisionTool.NAME)
        .doesNotContain(UnitRenameTool.NAME);
    assertThat(shell.toolRegistry().find(RunDecisionTool.NAME))
        .as("MCP 口的注册表里真的注册着它（上面那次 MCP 调用就是它跑通的，这里再点一手名字）")
        .isPresent();
  }

  // ────────────────────────────── MCP / 审批助手 ──────────────────────────────

  private Map<String, Object> runDecisionArgs(String decisionMakerId, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", "{\"decisionMakerId\":\"" + decisionMakerId + "\"}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /**
   * 经真 MCP 传输调用写工具，并**把这一轮里出现的每一条审批都答成"批一次"** ⇒ 取结果。
   *
   * <p>★★ **为什么要循环答，而不是只答一条**：一轮里**不止一条**敏感写——外层是触发本身（{@code sd.RunDecision}），
   * 内层还有决策人**自己**出的令（{@code sd.IssueDirective}）——它走的是**决策人链**（仍要人批，2026-09-24 用户裁定 只免了 GM
   * 面）。故本助手仍循环应答内层的审批；只答第一条的写法会**卡死在内层那一条上**（T11C 首次运行实测： 20 秒超时，正是这条）。**外层触发（GM 面）不再进审批** ⇒
   * 本助手的循环对它是空转，断言见方法末。
   *
   * @return 工具结果（{@link #approvals} 里留着本轮答过哪些审批——用例据此断言"内层写也进了审批"）
   */
  private McpSchema.CallToolResult callWithApproval(Map<String, Object> args) throws Exception {
    return callWithApproval(RunDecisionTool.NAME, args);
  }

  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("t11c-mcp-call").start(task);
    approvals.clear();
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      boolean answered = false;
      for (ApprovalRequest pending : shell.pendingApprovals().pending()) {
        if (answeredIds.add(pending.id())
            && shell
                .pendingApprovals()
                .decide(pending.id(), ApprovalDecision.APPROVE_ONCE, "test:gui")) {
          approvals.add(pending.classKey());
          answered = true;
        }
      }
      if (!answered && task.isDone()) {
        break;
      }
      Thread.sleep(10);
    }
    // ★★ 2026-09-24 用户裁定「MCP/GM Agent 无脑过」：**外层触发（GM 面）不再进审批**；
    //   内层决策人自己出的令仍走决策人链 ⇒ 该进还得进（下面 trace 用例正面断言它进了）。
    if (RunDecisionTool.NAME.equals(toolName)) {
      assertThat(approvals).as("GM 面的外层触发不得进审批（进了 = 两条链配反了）").doesNotContain(RunDecisionTool.NAME);
    }
    return task.get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  /**
   * 错误结果的正文：AgentLib 的 MCP 线格式在工具结果前面加了一层 {@code [mosire:code=<码>]} 前缀（{@code McpWireCode}， 常量
   * {@code "]" } 收尾、**没有空格**），解析前要剥掉。
   */
  private static JsonNode errorBody(McpSchema.CallToolResult result) throws Exception {
    String text = wireText(result);
    int close = text.indexOf(']');
    String json = text.startsWith("[mosire:code=") && close > 0 ? text.substring(close + 1) : text;
    return JSON.readTree(json.strip());
  }

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    McpSyncClient syncClient = McpClient.sync(transport).build();
    syncClient.initialize();
    return syncClient;
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  private static List<String> names(List<AgentTool> tools) {
    List<String> out = new ArrayList<>(tools.size());
    for (AgentTool tool : tools) {
      out.add(tool.name());
    }
    return out;
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 「跑飞」类用例的**小**回合上限（2026-10-14 W4，设计书 §10.4）。
   *
   * <p>★ 取值 20 = **旧的生产缺省**（2026-09-22 的现场算术 {@code 14+1+4+1}，见 {@code
   * DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS} 的新旧对照）——现在它只是本用例的"小到几秒跑完"的上限， 与生产缺省（10000，用户裁定）无关。★
   * 本用例的判别力不靠这个数字：它只要求"模型不收口 ⇒ 撞上限 ⇒ 如实报 abortedByBudget"。
   */
  private static final int RUNAWAY_BUDGET = 20;

  /** 在给定目录里起一套新壳（同一用例内换夹具世界/换决策人绑定时用：**换目录**，不往旧库上叠种）。 */
  private void restartWith(DecisionMaker maker, Path dir) {
    if (client != null) {
      client.closeGracefully();
      client = null;
    }
    if (shell != null) {
      shell.close();
      shell = null;
    }
    storeDir = dir;
    seedGenesis(maker);
    llm = new RecordingLlmClient();
    // ★ 真壳 + 注入的 LLM 客户端来源（生产路径传 null ⇒ 按决策人的 providerId 解析真 provider）。
    shell = Shell.start(ShellConfig.defaults(storeDir).withPorts(0, 0, 0), llm::provider);
    client = newClient();
  }

  /**
   * 在**同一个已种好的世界**上换一套壳，但把一轮的回合上限显式压小（2026-10-14 W4）。
   *
   * <p>★ 为什么不复用 {@link #restartWith}：那条会**再种一次 genesis**（往已有库上叠种 = 世界重复）。 本方法只换壳：store
   * 一字不动，仅把装配参数（回合上限）换掉。
   */
  private void restartShellWithBudget(int maxLlmCalls) {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（server 侧仍会被 shell.close() 收掉）
      }
      client = null;
    }
    if (shell != null) {
      shell.close();
      shell = null;
    }
    llm = new RecordingLlmClient();
    shell =
        Shell.start(ShellConfig.defaults(storeDir).withPorts(0, 0, 0), llm::provider, maxLlmCalls);
    client = newClient();
  }

  /** 出令载荷：base 取调用方给的 revision（= 触发事实落盘后的新 head）。 */
  private static Map<String, Object> directiveArgs(long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put(
        "payloadJson",
        "{\"directiveId\":\"d-1\",\"decisionMakerId\":\""
            + DM_ID
            + "\",\"tick\":7,"
            + "\"intentInfo\":\"向北推进\",\"commands\":[]}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  private void seedGenesis(DecisionMaker maker) {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, genesisUnit(U1, "第一连", H11))));
    SocialData social =
        new SocialData(new LinkedHashMap<>(Map.of(H12, populationSeries())), Map.of(), Map.of());
    WorldSlices slices = sdWithOneNationOneArmy(maker);
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "gov", new GovSnapshot(ref("main", 1), T7, slices.gov()),
                "army", new ArmySnapshot(ref("main", 1), T7, slices.army()),
                "ga", new GaSnapshot(ref("main", 1), T7, slices.ga())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new GovCodec(),
                    new ArmyCodec(),
                    new GaCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人。 */
  private static WorldSlices sdWithOneNationOneArmy(DecisionMaker maker) {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, Optional.empty(), U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(maker.id(), maker);
    // ★★ 2026-10-13 收尾测试第 3 层：归属行**真的落进 `GovState.nationAffiliations()`**（归属归模块之后，
    //   "这个决策人属 FRA"不再住在 `DecisionMaker` 值上）。只造裸决策人 ⇒ 范围函数 deny-all ⇒
    //   回合里的世界读一律被拒（"仍能成功读到真世界"这条断言会红，而根因是夹具少了一行）。
    return WorldSlices.of(
            new GovState(Map.of(), Map.of(), nations, Map.of(), Map.of(), Map.of(), Map.of()),
            ArmyData.empty().withArmies(armies),
            GaState.empty().withDecisionMakers(makers))
        .withNationAffiliation(maker.id(), fra);
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
            new RegionId("701"),
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

  /** 一条最小的人口序列（锚点 + 一段增速；不含事件——本用例不碰人口语义）。 */
  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  /** 最小单位：自身带位置、无父、无路线、视野缺省（1）。 */
  private static Unit genesisUnit(UnitId id, String name, HexCoord position) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  private SqliteConversationStore openConversations() {
    return SqliteConversationStore.open(conversationsFile());
  }

  private Path conversationsFile() {
    return storeDir.resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME);
  }

  private Path dbFile() {
    return storeDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  // ────────────────────────────── LLM 替身 ──────────────────────────────

  /**
   * **记下每一次请求**的 LLM 客户端（包住真脚本执行器）。
   *
   * <p>★ 为什么必须有它：判据 ③ 要证的是"**接上了**"，而 {@code FakeLlmClient} 只回放脚本、不暴露收到了什么 ⇒
   * 否则"会话存下来了"与"会话喂进去了"分不开。
   */
  private static final class RecordingLlmClient implements LlmClient {

    private final FakeLlmClient delegate = new FakeLlmClient();
    private final List<LlmRequest> requests = new ArrayList<>();

    /**
     * 这条 provider 有没有视觉能力（P4）：**缺省 false**（图一张都不发），要验图片通路的用例先把它打开。
     *
     * <p>★ 它模拟的是"路由配置里 {@code capabilities.vision}"这一条**配置事实**——所以它挂在"provider ⇒ 客户端 + 能力"
     * 那条缝上（{@link #provider}），而不是让某个用例去改 runner 的形参。
     */
    private boolean vision;

    void withVision(boolean enabled) {
      this.vision = enabled;
    }

    /** 注入进壳的那条缝：providerId 是**真的**（壳按世界事实解析出来），只是客户端由本替身提供。 */
    ProviderLlm provider(String providerId) {
      assertThat(providerId).as("★ 壳解析出的 providerId 必须与夹具里绑的一致").isEqualTo(PROVIDER_ID);
      return new ProviderLlm(this, vision);
    }

    void enqueue(LlmResponse... responses) {
      for (LlmResponse response : responses) {
        delegate.enqueue(response);
      }
    }

    List<LlmRequest> requests() {
      return List.copyOf(requests);
    }

    int calls() {
      return delegate.calls();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      return delegate.chat(request);
    }
  }
}
