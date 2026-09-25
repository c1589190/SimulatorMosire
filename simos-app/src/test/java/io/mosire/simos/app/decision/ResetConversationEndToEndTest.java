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
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.write.ResetDecisionMakerConversationTool;
import io.mosire.simos.app.tools.write.RunDecisionTool;
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
import io.mosire.simos.social.population.PopulationSeries;
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
 * ★★ **会话重置的端到端验收**：GM 用 {@code sd.ResetDecisionMakerConversation} 把某个决策人的 LLM 会话**换新的**，
 * 于是它**下一轮从空上下文重新开始**。
 *
 * <p>★ **现实动因**：改版前落盘的老会话在回放时会 400（历史里没有 {@code reasoning_content}，补不上）⇒ 现场处置只能是"换个新会话重开"。
 * 本任务把那件事工具化：它是一条**真命令**（铁律 2），不是一个改库的操作。
 *
 * <p>★★ **主判据的形态（本任务最要紧的一处）**：**同一个用例里跑三轮**，第 2 轮 **不管**重置、第 3 轮才在重置之后——
 *
 * <ol>
 *   <li>第 1 轮：决策人真的跑一轮（真壳 + 真 MCP + 真审批 + 真会话库），落进 {@code decision-maker:dm-fra}；
 *   <li>第 2 轮（**对照**）："下一轮**看得见**上一轮" ⇒ 它的首个请求里带着第 1 轮的历史——**没有这一轮，"第 3 轮是空的"就与 "runner
 *       压根不读历史"分不开**（两个世界产出同一份观测）；
 *   <li>**重置**（GM 经真 MCP 口发命令）⇒ 世代 0 → 1；
 *   <li>第 3 轮：首个请求里**只有那条身份消息**（1 条）⇒ **真的从空上下文重新开始**，且它落进的是**另一段**会话。
 * </ol>
 *
 * <p>★★ **"不删任何历史字节"是同时断言的**：重置之后，**老会话逐条还在**（{@code ConversationStore} 的 append-only
 * 契约）——重置只是让**下一轮**换一个 id，不是把库擦了。这一条单独看像细枝末节， 其实是"可审计"与"改库里的一条记录"的分界。
 *
 * <p>★ 唯一替身是 LLM 客户端（{@code FakeLlmClient} + 记录器）：**测试不打真网络**；世界状态、权限、审批、落盘、会话库全是真的。
 */
class ResetConversationEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "agent:reset-conv-mcp";
  private static final String PROVIDER_ID = "stub";
  private static final String DM_ID = "dm-fra";

  /** ★ 旧格式（世代 0）的会话 id：**现场落盘的老会话就是这个写法**，故它是一份硬兼容要求，不是自选口味。 */
  private static final String LEGACY_CONVERSATION_ID = "decision-maker:dm-fra";

  /** 世代 1 的会话 id（格式见 {@code DecisionAgentRunner.conversationIdOf} 的类注）。 */
  private static final String GENERATION_ONE_CONVERSATION_ID = "decision-maker#1:dm-fra";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON = new ObjectMapper();

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

  private Path storeDir;
  private Shell shell;
  private McpSyncClient client;
  private RecordingLlmClient llm;

  /** 本次 MCP 调用里答过的审批（工具名 = {@code classKey}）。 */
  private final List<String> approvals = new ArrayList<>();

  private final Set<String> answeredIds = new HashSet<>();

  @BeforeEach
  void startShell() {
    storeDir = tempDir;
    seedGenesis();
    llm = new RecordingLlmClient();
    shell = Shell.start(ShellConfig.defaults(storeDir).withPorts(0, 0, 0), llm::provider);
    client = newClient();
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

  // ── 主判据：重置之后，下一轮真的从空上下文开始 ───────────────────────────────────

  @Test
  void resettingMovesTheNextRoundToAFreshConversationThatStartsEmpty() throws Exception {
    // ── 第 1 轮：真跑一轮（读一格 + 收口），落进旧会话 ──
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("第一轮到此"));
    JsonNode first = body(callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head())));
    assertThat(first.get("conversationId").asText())
        .as("★ 世代 0 ⇒ 会话 id 与**旧格式逐字相同**（现场落盘的老会话因此接得上）")
        .isEqualTo(LEGACY_CONVERSATION_ID);
    long headAfterFirst = head();

    // ── 第 2 轮（对照）：**不**重置 ⇒ 它看得见第 1 轮的历史 ──
    llm.enqueue(LlmResponse.text("第二轮继续"));
    callWithApproval(RunDecisionTool.NAME, runDecisionArgs(headAfterFirst));
    assertThat(llm.lastRequest().messages())
        .as("★ 对照组：同一段会话里的下一轮**带着**上一轮的历史（没有这一组，第 3 轮的「空了」分不清是" + "「重置生效」还是「runner 压根不读历史」）")
        .hasSize(4);
    assertThat(conversationsIn(storeDir).load(LEGACY_CONVERSATION_ID))
        .as("第 1+2 轮共 5 条（身份 + 第 1 轮的 3 条 + 第 2 轮的收口）")
        .hasSize(5);

    // ── 重置：GM 经真 MCP 口发命令（2026-09-24 用户裁定后：**无脑过**，不进审批） ──
    long beforeReset = head();
    JsonNode reset =
        body(callWithApproval(ResetDecisionMakerConversationTool.NAME, resetArgs(head())));
    assertThat(reset.get("result").asText()).as("重置是一条真 revision").isEqualTo("committed");
    assertThat(head()).as("head 前进 1（世界事实变了）").isEqualTo(beforeReset + 1);
    assertThat(revisionRowCommandType(head()))
        .as("★ 铁律 2：连「换会话」也走 Command → ChangeSet → Revision（AAR 看得见谁在什么时候重开过）")
        .isEqualTo("sd.ResetDecisionMakerConversation");

    // ── 第 3 轮：**从空上下文重新开始** ──
    llm.enqueue(LlmResponse.text("新会话第一句"));
    JsonNode third = body(callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head())));

    assertThat(llm.lastRequest().messages())
        .as("★★ **本任务的主判据**：重置之后首个请求里**只有那条身份消息**——上一轮的 5 条一条都没跟过来")
        .hasSize(1);
    assertThat(llm.lastRequest().messages().get(0).role())
        .as("那一条是身份消息（system），不是残留的 assistant/tool")
        .isEqualTo("system");
    assertThat(textOf(llm.lastRequest().messages().get(0)))
        .as("而且它说的是**这个决策人**（新会话的第一句话里就有身份，不是一段空白开局）")
        .contains(DM_ID);

    assertThat(third.get("conversationId").asText())
        .as("★ 报出来的会话 id 是**新那一段**（不是沿用旧 id 却把内容写进别处）")
        .isEqualTo(GENERATION_ONE_CONVERSATION_ID);
    assertThat(third.get("conversationId").asText()).isNotEqualTo(LEGACY_CONVERSATION_ID);

    // ── 老会话的字节一条都没少（append-only 的契约） ──
    try (SqliteConversationStore conversations = conversationsIn(storeDir)) {
      assertThat(conversations.load(LEGACY_CONVERSATION_ID))
          .as("★★ **重置不删任何历史字节**：旧会话逐条还在（那是可审计的凭据，不是缓存）")
          .hasSize(5);
      assertThat(conversations.load(GENERATION_ONE_CONVERSATION_ID))
          .as("新会话独立地从头长起（身份 + 这一轮的收口）")
          .hasSize(2);
    }
  }

  /**
   * ★ **新会话也是"稳定的那一段"**：重置一次之后，接下来的每一轮都落在**同一段**新会话上（不是每轮换一个）。
   *
   * <p>判别力：把世代做成"每轮 +1"（或把 id 派生成随机数）的实现，本用例红——而"重置只是换一次"这个语义会被悄悄改掉，且没有任何症状。
   */
  @Test
  void theNewConversationIsStableAcrossTheRoundsThatFollowIt() throws Exception {
    llm.enqueue(LlmResponse.text("重置前"));
    callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head()));
    callWithApproval(ResetDecisionMakerConversationTool.NAME, resetArgs(head()));

    llm.enqueue(LlmResponse.text("重置后第一轮"));
    callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head()));

    llm.enqueue(LlmResponse.text("重置后第二轮"));
    callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head()));

    assertThat(llm.lastRequest().messages())
        .as("★ 重置后的第二个轮次**接着新会话**（身份 + 重置后第一轮那一条 = 2），不是又换一段")
        .hasSize(2);
    try (SqliteConversationStore conversations = conversationsIn(storeDir)) {
      assertThat(conversations.load(GENERATION_ONE_CONVERSATION_ID))
          .as("新会话里攒下了三轮的话（身份 + 重置后两条）")
          .hasSize(3);
      assertThat(conversations.load(LEGACY_CONVERSATION_ID)).as("老会话仍是它那一条").hasSize(2);
    }
  }

  // ── 失败路径也要报**新**会话 ─────────────────────────────────────────────────────

  /**
   * ★★ **失败路径上报告的会话 id 必须按世界事实现算**（重置之后就是新那一段）。
   *
   * <p>★★ 为什么专门测这一条：成功路径报的是**本轮账自带**的那一个（同源，不会错），而失败路径（撞回合预算 / fail-closed） 得**自己算**一个出去。在这里"id
   * 拼前缀"糊一个的实现**不会报错**，只是把一个**早已作废**的会话 id 交给 GM—— 而那句话看起来完全正常，据此做的排查会被引到错的地方。
   *
   * <p>★ 判别力：把 {@code DecisionAgentService.conversationIdOf} 改回"只按 id
   * 派生"（忽略世代）的实现，本用例红在**最后一条**断言上。
   */
  @Test
  void theFailurePathReportsTheConversationOfTheCurrentGeneration() throws Exception {
    // 撞回合预算：模型一直要工具（真 provider 由替身提供；预算在调用前判，故这里不需要真脚本）
    enqueueRunaway();
    JsonNode before = errorBody(callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head())));
    assertThat(before.get("result").asText()).isEqualTo("aborted");
    assertThat(before.get("conversationId").asText())
        .as("重置之前：失败路径报的是旧格式那一段")
        .isEqualTo(LEGACY_CONVERSATION_ID);

    callWithApproval(ResetDecisionMakerConversationTool.NAME, resetArgs(head()));

    enqueueRunaway();
    JsonNode after = errorBody(callWithApproval(RunDecisionTool.NAME, runDecisionArgs(head())));
    assertThat(after.get("result").asText()).isEqualTo("aborted");
    assertThat(after.get("conversationId").asText())
        .as("★★ 重置之后：失败路径报的是**新那一段**（拿旧 id 糊一个出去不会报错，只会误导排查）")
        .isEqualTo(GENERATION_ONE_CONVERSATION_ID);
  }

  // ── 拒绝方向 ───────────────────────────────────────────────────────────────

  /** 决策人不存在 ⇒ 拒，且**head 不动**（不是"落了一条空重置"）。 */
  @Test
  void anUnknownDecisionMakerIsRejectedAndNothingMoves() throws Exception {
    long before = head();
    McpSchema.CallToolResult result =
        callWithApproval(ResetDecisionMakerConversationTool.NAME, resetArgs(head(), "dm-nope"));

    assertThat(result.isError()).isTrue();
    assertThat(wireText(result)).contains("决策人不存在").contains("dm-nope");
    assertThat(head()).as("被拒 ⇒ 没有新 revision").isEqualTo(before);
    assertThat(llm.calls()).as("重置不碰 LLM").isZero();
  }

  // ── 桶归属 ─────────────────────────────────────────────────────────────────

  /**
   * ★ **只在 GM 桶**（= 运行时 MCP 口）：决策人不重置自己的会话——那等于给自己开一个"忘掉刚才答应过什么"的按钮。
   *
   * <p>判别力：把它也挂进决策人桶的实现，本用例红在 {@code doesNotContain} 上。
   */
  @Test
  void theResetToolIsOnlyInTheGmBucket() {
    assertThat(names(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("GM 面（= 运行时 MCP 口）含重置工具")
        .contains(ResetDecisionMakerConversationTool.NAME);
    assertThat(names(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("决策人面**不含**它")
        .doesNotContain(ResetDecisionMakerConversationTool.NAME);
    assertThat(shell.toolRegistry().find(ResetDecisionMakerConversationTool.NAME))
        .as("MCP 口的注册表里真的注册着它（上面那几次 MCP 调用就是它跑通的，这里再点一手名字）")
        .isPresent();
  }

  // ────────────────────────────── MCP / 审批助手 ──────────────────────────────

  private Map<String, Object> runDecisionArgs(long expectedRevision) {
    return commandArgs("{\"decisionMakerId\":\"" + DM_ID + "\"}", expectedRevision);
  }

  private Map<String, Object> resetArgs(long expectedRevision) {
    return resetArgs(expectedRevision, DM_ID);
  }

  private Map<String, Object> resetArgs(long expectedRevision, String decisionMakerId) {
    return commandArgs("{\"decisionMakerId\":\"" + decisionMakerId + "\"}", expectedRevision);
  }

  private static Map<String, Object> commandArgs(String payloadJson, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /**
   * 经真 MCP 传输调用写工具，并**把这一轮里出现的每一条审批都答成"批一次"** ⇒ 取结果。
   *
   * <p>★ 与 {@code RunDecisionEndToEndTest} 同法（那里记着"只答第一条会卡死在内层的写审批上"的实测）：一轮里可能不止一条敏感写， 故循环答到调用返回。
   */
  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("reset-conv-mcp-call").start(task);
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
    // ★★ 2026-09-24 用户裁定「MCP/GM Agent 无脑过」：GM 面的两条（外层触发 sd.RunDecision / 重置
    //   sd.ResetDecisionMakerConversation）**都不该进审批**；进来的只可能是决策人自己出的令（本类的桩不出令）。
    assertThat(approvals)
        .as("GM 面的工具不得进审批（进了 = 两条链配反了）")
        .doesNotContain(RunDecisionTool.NAME, ResetDecisionMakerConversationTool.NAME);
    return task.get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  /** 成功结果的正文（工具结果的 JSON）。 */
  private static JsonNode body(McpSchema.CallToolResult result) throws Exception {
    assertThat(result.isError()).as(wireText(result)).isFalse();
    return JSON.readTree(wireText(result));
  }

  /**
   * 错误结果的正文：AgentLib 的 MCP 线格式在工具结果前加了一层 {@code [mosire:code=<码>]} 前缀（常量 {@code "]"}
   * 收尾、**没有空格**），解析前要剥掉。
   */
  private static JsonNode errorBody(McpSchema.CallToolResult result) throws Exception {
    assertThat(result.isError()).as(wireText(result)).isTrue();
    String text = wireText(result);
    int close = text.indexOf(']');
    String json = text.startsWith("[mosire:code=") && close > 0 ? text.substring(close + 1) : text;
    return JSON.readTree(json.strip());
  }

  /** 让模型一直要工具 ⇒ 这一轮必然撞回合预算（是本类唯一能稳定触发的失败路径）。 */
  private void enqueueRunaway() {
    for (int i = 0; i < DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS; i++) {
      llm.enqueue(LlmResponse.toolCall("call-" + i, "simos_map_hex", Map.of("q", 1, "r", 1)));
    }
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

  /** 一条消息的首个文本分片（身份消息是纯文本）。 */
  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(part -> ((ContentPart.Text) part).text())
        .findFirst()
        .orElseThrow(() -> new AssertionError("这条消息没有文本分片: " + message.role()));
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

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

  private static SqliteConversationStore conversationsIn(Path dir) {
    return SqliteConversationStore.open(dir.resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME));
  }

  /** 头一条 revision 行（genesis 之外的那些）的命令类型。 */
  private String revisionRowCommandType(long revision) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return new Timeline(store, CHECKPOINT_INTERVAL)
          .row(ref("main", revision))
          .orElseThrow()
          .commandType();
    }
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private Path dbFile() {
    return storeDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  private void seedGenesis() {
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
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithOneNationOneArmy())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人（绑定 provider）。 */
  private static SdState sdWithOneNationOneArmy() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
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

  /** 一条最小的人口序列（锚点 + 一段增速；本用例不碰人口语义）。 */
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
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  // ────────────────────────────── LLM 替身 ──────────────────────────────

  /**
   * **记下每一次请求**的 LLM 客户端（包住真脚本执行器）。
   *
   * <p>★ 它是"空上下文"那条判据的唯一载体：{@code FakeLlmClient} 只回放脚本、不暴露收到了什么 ⇒ 没有它就分不开"会话换新的了"与 "会话压根没喂进去"。
   */
  private static final class RecordingLlmClient implements LlmClient {

    private final FakeLlmClient delegate = new FakeLlmClient();
    private final List<LlmRequest> requests = new ArrayList<>();

    /** 注入进壳的那条缝：providerId 是**真的**（壳按世界事实解析出来），只是客户端由本替身提供。 */
    ProviderLlm provider(String providerId) {
      assertThat(providerId).as("★ 壳解析出的 providerId 必须与夹具里绑的一致").isEqualTo(PROVIDER_ID);
      // ★ 本用例集不验图片通路：如实给"没有视觉能力"（图一张都不该发，见 DecisionAgentRunner 的类注）。
      return new ProviderLlm(this, false);
    }

    void enqueue(LlmResponse... responses) {
      for (LlmResponse response : responses) {
        delegate.enqueue(response);
      }
    }

    /** 最近一次发出去的请求（主判据看的就是它的 {@code messages}）。 */
    LlmRequest lastRequest() {
      assertThat(requests).as("一次 LLM 调用都还没发生").isNotEmpty();
      return requests.get(requests.size() - 1);
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
