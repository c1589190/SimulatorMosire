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
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.write.RunDecisionTool;
import io.mosire.simos.app.tools.write.UnitRenameTool;
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
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          AccessLimit.empty(),
          1,
          Optional.of(PROVIDER_ID),
          // 会话世代：夹具世界里的决策人没被重置过（世代 0 ⇒ 会话 id 就是旧格式那一个）。
          0L);

  /** 未绑定 provider 的同一个人（fail-closed 的靶子）。 */
  private static final DecisionMaker DM_NO_PROVIDER =
      new DecisionMaker(
          new DecisionMakerId(DM_ID),
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          AccessLimit.empty(),
          1,
          Optional.empty(),
          0L);

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
        LlmResponse.toolCall("call-2", "sd_IssueDirective", directiveArgs(2L)),
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
    assertThat(calls.get(1).get("tool").asText()).isEqualTo("sd.IssueDirective");
    assertThat(calls.get(1).get("ok").asBoolean()).isTrue();

    // ★★ 判据 2：轨迹里带着**真世界**的值（读工具的返回原文）——"决策人看见了什么"的载体。
    assertThat(calls.get(0).get("summary").asText())
        .as("编出来的轨迹不会恰是 desert（真跑过 simos.map.hex 才会带上它）")
        .contains("desert");
    assertThat(calls.get(1).get("summary").asText()).as("出令真的提交了").contains("committed");

    // ★ 可观察副作用之一：两条真 revision（触发事实 + 决策人自己的出令）。
    assertThat(head()).as("触发事实 1 条 + 决策人的 sd.IssueDirective 1 条").isEqualTo(3L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      assertThat(timeline.row(ref("main", 3)).orElseThrow().commandType())
          .as("★ 铁律 2：决策不是「记在对话里」，它是一条真 revision（可回放、可回退分岔）")
          .isEqualTo("sd.IssueDirective");
      assertThat(timeline.row(ref("main", 2)).orElseThrow().commandType())
          .as("触发事实本身也是世界事实（AAR 看得见「谁让谁跑了一轮」）")
          .isEqualTo("sd.RunDecision");
    }

    // ★ 可观察副作用之二：这一轮的每条消息都落了会话（跨 tick 的锚）。
    try (SqliteConversationStore conversations = openConversations()) {
      assertThat(conversations.load("decision-maker:" + DM_ID))
          .as(
              "identity(system) + assistant(toolCall) + tool + assistant(toolCall) + tool + assistant(text)")
          .hasSize(6);
    }

    // ★★ 审批面（2026-09-24 用户裁定后的口径）：**决策人自己出的令仍要审批**（不是"在运行流里偷偷写"），
    //   而**外层触发（GM 面）不再审批**（无脑过）——两条链的区别在这一行里看得见。
    assertThat(approvals).as("内层决策人的 sd.IssueDirective 进了审批").contains("sd.IssueDirective");
    assertThat(approvals).as("外层 GM 触发不进审批（无脑过）").doesNotContain(RunDecisionTool.NAME);
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
    assertThat(callWithApproval(runDecisionArgs(DM_ID, 2L)).isError()).isFalse();

    List<LlmMessage> firstRoundFirstRequest = llm.requests().get(0).messages();
    assertThat(firstRoundFirstRequest)
        .as("★ 首轮开局**不是空 messages**：空会话先注入那条身份（真 provider 对空 messages 直接 400）")
        .hasSize(1);
    assertThat(firstRoundFirstRequest.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    List<LlmMessage> secondRoundFirstRequest = llm.requests().get(2).messages();
    assertThat(secondRoundFirstRequest)
        .as(
            "★ 第二轮的**首个请求**里就带着上一轮的 4 条消息（identity + assistant(toolCall) + tool + assistant(text)，跨 tick 接得上）")
        .hasSize(4);
    assertThat(secondRoundFirstRequest.get(0).role())
        .as("★ 身份仍是首条——第二轮从会话里取回它，不重复注入")
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
    for (int i = 0; i < DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS; i++) {
      llm.enqueue(LlmResponse.toolCall("call-" + i, "simos_map_hex", Map.of("q", 1, "r", 1)));
    }

    McpSchema.CallToolResult result = callWithApproval(runDecisionArgs(DM_ID, 1L));

    assertThat(result.isError()).isTrue();
    JsonNode body = errorBody(result);
    assertThat(body.get("result").asText()).isEqualTo("aborted");
    assertThat(body.get("abortedByBudget").asBoolean()).isTrue();
    assertThat(body.get("llmCalls").asInt()).isEqualTo(DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS);
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
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(RunDecisionTool.NAME, args);
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
    assertThat(approvals).as("GM 面的外层触发不得进审批（进了 = 两条链配反了）").doesNotContain(RunDecisionTool.NAME);
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
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithOneNationOneArmy(maker))),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人。 */
  private static SdState sdWithOneNationOneArmy(DecisionMaker maker) {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(maker.id(), maker);
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
        100,
        Map.of("步枪", 50),
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
