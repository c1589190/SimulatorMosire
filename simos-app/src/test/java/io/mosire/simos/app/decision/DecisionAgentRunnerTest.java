package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.SimosToolSource;
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
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.ViewScope;
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
 * ★★ **判据 J11 的用例**（spec §2.3 / §〇 的 J11）：决策人**自己调工具**读世界 + **跨 tick 上下文真的接上**。
 *
 * <p>★ **真装配、不用替身工具**（与 T10 的验收面同法）：真 {@code Shell} + 真 store + 真工具链 + 真审批。这是必要的——
 * 本任务最容易写成"看起来在调工具、其实只是把模型的话记下来"的形态，只有真工具留下的**可观察副作用**（revision 前进、工具结果里 带着真世界的地形名）才分得开这两种。
 *
 * <p>★ **三条判据的证据形态**：
 *
 * <ol>
 *   <li>**工具真的被执行** ⇒ ① {@code head} 前进（决策落成真 revision，是 runner 自己的账本之外的副作用）；② 会话里那条 tool 消息的正文
 *       带着夹具世界的真值（{@code "desert"}）；
 *   <li>**assistant + tool-result 都落了会话** ⇒ 逐条断言 cid 上的消息序列；
 *   <li>**跨 tick 接得上** ⇒ 用**它发出去的请求本身**当证据（{@link RecordingLlmClient} 记下每次 {@code LlmRequest}），
 *       断言第二次 {@code run} 的首个请求里带着上一轮的**逐条消息**；换一个 store 实例再跑一次同法。
 * </ol>
 *
 * <p>★ 夹具世界（{@code Map1}）：{@code (1,1)/(1,2)} 两格 desert，区域 701（{@code nation:FRA}），单位 {@code u-1} 在
 * {@code (1,1)}，国家决策人 {@code dm-fra}（{@code Affiliation.Nation(FRA)}）——它的范围是**区域级**前缀，故它能读到 本国那两格。
 */
class DecisionAgentRunnerTest {

  private static final String MAP_ID = "Map1";
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "agent:decision-runner-test";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");

  private static final DecisionMaker DM_FRA =
      new DecisionMaker(
          new DecisionMakerId("dm-fra"),
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          ViewScope.empty(),
          1);

  /** 军队决策人（**只为验身份正文的军队分支**）：它不必在世界里存在——注入的那条消息只取 {@code DecisionMaker} 自己的字段、不查世界。 */
  private static final DecisionMaker DM_ARMY =
      new DecisionMaker(
          new DecisionMakerId("dm-army"),
          new Affiliation.Army(new ArmyId("a1")),
          Set.of(),
          ViewScope.empty(),
          1);

  @TempDir Path tempDir;

  private Shell shell;
  private SqliteConversationStore conversations;
  private RecordingLlmClient llm;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
    shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                INITIATOR,
                base.mapId(),
                base.bindAddress()));
    conversations = SqliteConversationStore.open(conversationsFile());
    llm = new RecordingLlmClient();
  }

  @AfterEach
  void stopShell() {
    if (conversations != null) {
      conversations.close();
    }
    if (shell != null) {
      shell.close();
    }
  }

  // ── 判据 1 + 2：工具真的被执行、两条消息都落了会话 ─────────────────────────────────

  /** ★★ **一轮里"先调两个工具、再收口"**：模型请求 {@code simos.map.hex} 与 {@code sd.IssueDirective}，两者**都真的执行**。 */
  @Test
  void theRunnerReallyExecutesTheToolsTheModelAsksFor() throws Exception {
    long headBefore = head();
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos.map.hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("call-2", "sd.IssueDirective", directiveArgs(headBefore)),
        LlmResponse.text("已按计划出令"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(turn.llmCalls()).as("两次工具调用 + 一次收口 = 三次模型调用（多轮，不是单轮）").isEqualTo(3);
    assertThat(turn.usedTools()).isTrue();
    assertThat(turn.toolInvocations())
        .extracting(DecisionAgentRunner.ToolInvocation::toolName)
        .containsExactly("simos.map.hex", "sd.IssueDirective");
    assertThat(turn.toolInvocations())
        .as("两次都真的跑通了（被权限拒也会出现在这里，但 success=false）")
        .allMatch(DecisionAgentRunner.ToolInvocation::success);
    assertThat(turn.finalText()).contains("已按计划出令");

    // ★★ 可观察副作用之一：真 revision 落了盘。这是 runner 自己的账本之外的证据——
    //   "假装执行了"的实现在这里必然红（head 不会动）。
    assertThat(head())
        .as("★ 决策落成真 revision（铁律 2：所有修改走 Command → ChangeSet → Revision）")
        .isEqualTo(headBefore + 1);

    // ★★ 可观察副作用之二：读工具的结果里带着**真世界**的值。
    List<LlmMessage> history = conversations.load(conversationId());
    assertThat(history)
        .as(
            "identity(system) + assistant(toolCall) + tool(result) + assistant(toolCall) + tool(result) + assistant(text)")
        .hasSize(6);
    assertThat(roles(history))
        .containsExactly("system", "assistant", "tool", "assistant", "tool", "assistant");
    assertThat(toolResults(history).get(0).content())
        .as("真跑过 map.hex 的正文里带着夹具世界的地形；编出来的结果不会恰是这个值")
        .contains("desert");
    assertThat(toolResults(history).get(1).content()).as("出令那条真的提交了").contains("committed");
    assertThat(toolResults(history).get(0).isError()).isFalse();
  }

  // ── 判据 3：跨 tick 会话沿用 ─────────────────────────────────────────────────────

  /**
   * ★★ **第二次 {@code run} 的上下文里带着上一轮**：证据是**它发出去的请求本身**（第一个请求的消息条数 == 上一轮的 6 条）。
   *
   * <p>★ 只断言 {@code load(cid)} 非空是不够的——那只证明"存下来了"；"**接上了**"要看请求。
   */
  @Test
  void theNextRunSeesThePreviousRunsHistory() {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos.map.hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("第一轮到此"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(roles(llm.requests().get(0).messages()))
        .as("★ 首轮开局**不是空 messages**：空会话先注入那条身份（真 provider 对空 messages 直接 400）")
        .containsExactly("system");

    llm.enqueue(LlmResponse.text("第二轮继续"));
    DecisionAgentRunner.DecisionTurn second =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    List<LlmMessage> fed = llm.requests().get(2).messages();
    assertThat(fed).as("★ 第二轮的**首个请求**里就带着上一轮的 4 条消息").hasSize(4);
    assertThat(roles(fed)).containsExactly("system", "assistant", "tool", "assistant");
    // ★ 逐值：会话里的正文与上一轮落盘的一模一样（不是"条数对了但内容丢了"）。
    assertThat(toolResults(fed).get(0).content()).contains("desert");
    assertThat(second.toolInvocations()).as("第二轮没有调工具").isEmpty();
    assertThat(conversations.load(conversationId())).hasSize(5);
  }

  /** ★★ **换一个 store 实例读同一 cid**（"重启后接得上"）：关掉旧实例、用同一个文件重开，用**新实例**喂新一轮 —— 首个请求里仍带着上一轮的全部历史。 */
  @Test
  void aFreshStoreInstanceStillCarriesTheConversation() {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos.map.hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("第一轮到此"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());
    conversations.close();

    SqliteConversationStore reopened = SqliteConversationStore.open(conversationsFile());
    try {
      assertThat(roles(reopened.load(conversationId())))
          .as("★ 重开后逐条还在，**首条身份也在**——它必须落盘（只往内存里塞的修法在换 store 实例后会话又空、真 provider 再次 400）")
          .containsExactly("system", "assistant", "tool", "assistant");
      conversations = reopened;
      llm.enqueue(LlmResponse.text("重启后继续"));
      new DecisionAgentRunner(factory(), decisionRegistry(), llm, reopened, MAP_ID)
          .run(DM_FRA, state());

      assertThat(roles(llm.requests().get(2).messages()))
          .as("★ 新 store 上的新一轮，开局请求里带着重启前的历史（首条身份也还在）")
          .containsExactly("system", "assistant", "tool", "assistant");
    } finally {
      reopened.close();
      conversations = null;
    }
  }

  // ── 首轮注入：空会话必须先有身份（真 LLM 实测缺陷的修法） ───────────────────────────

  /**
   * ★★ **空会话的首个请求里必须先有身份**（真 provider 实测缺陷的修法，2026-09-22）。
   *
   * <p>★ **缺陷现场**：{@code conversations.load} 对从未写过的会话返回空表 ⇒ 首轮请求的 messages **是空的** ⇒ 供应商直接拒（{@code
   * HTTP 400: field messages is required}，{@code llmCalls=0}，一次都没成）。
   *
   * <p>★ **判别力（变异实测）**：不注入的变异体红在**第一条**（{@code Expecting actual not to be empty}）；只注入、不落盘的变异体红在
   * **最后一条**（会话里只有 {@code assistant} ⇒ 重启后又空、真 provider 再次 400 ⇒ 只补内存不改存储的修法是假修）。
   */
  @Test
  void anEmptyConversationGetsTheIdentityMessageBeforeTheFirstRequest() {
    llm.enqueue(LlmResponse.text("收到"));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    List<LlmMessage> first = llm.requests().get(0).messages();
    assertThat(first).as("★ 首轮请求的 messages 绝不是空的——真 provider 对空 messages 直接 400").isNotEmpty();
    assertThat(first.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    String text = textOf(first.get(0));
    assertThat(text).as("身份：决策人 id + 归属（含 id）").contains(DM_FRA.id().value()).contains("FRA");
    assertThat(text)
        .as("任务：范围由系统强制 + 先查看再决策 + 出令与载荷提示 + expectedRevision 的来源")
        .contains("sd.IssueDirective")
        .contains("simos.command.catalog")
        .contains("simos.timeline.branches")
        .contains("expectedRevision");
    assertThat(text)
        .as(
            "★ 正文里一个数字都没有：这条消息**永久落盘**（写进去就不再更新）⇒ 里面不能有 head/revision/tick 这类会漂的值；夹具的决策人 id 与归属 id 都不含数字")
        .doesNotContainPattern("[0-9]");
    assertThat(roles(conversations.load(conversationId())))
        .as("★ 注入的消息**同时落盘**（否则进程重启后会话又空、真 provider 再次 400）")
        .containsExactly("system", "assistant");
  }

  /** ★ 归属是**军队**时如实报 {@code armyId}（{@code Affiliation} 的两个分支各被自己的用例钉住，不靠"国家那支顺带覆盖"）。 */
  @Test
  void theIdentityMessageNamesTheArmyForAnArmyDecisionMaker() {
    llm.enqueue(LlmResponse.text("收到"));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_ARMY, state());

    assertThat(textOf(llm.requests().get(0).messages().get(0)))
        .as("军队决策人的身份里是 armyId，不是 nationId")
        .contains(DM_ARMY.id().value())
        .contains("a1")
        .contains("armyId")
        .doesNotContain("nationId");
  }

  /**
   * ★★ **只注入一次**：第二轮从会话里取回它，不得再追加一条。
   *
   * <p>★ 判别力（变异实测）："每轮都注入"的变异体把身份**追加到尾部**，于是第二轮的首个请求成了 {@code system, assistant,
   * system}（身份出现两次）——而 {@code load} 非空这件事**两种实现都成立**，故不能只断言"有 system"。
   */
  @Test
  void theIdentityMessageIsNotInjectedTwiceOnLaterRounds() {
    llm.enqueue(LlmResponse.text("第一轮"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());
    llm.enqueue(LlmResponse.text("第二轮"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(roles(llm.requests().get(1).messages()))
        .as("★ 第二轮的首个请求里 identity 只有一条")
        .containsExactly("system", "assistant");
    assertThat(
            conversations.load(conversationId()).stream()
                .filter(message -> LlmMessage.ROLE_SYSTEM.equals(message.role()))
                .toList())
        .as("落盘的 system 消息恰好一条")
        .hasSize(1);
  }

  // ── 权限：白名单外的工具调不动 ───────────────────────────────────────────────────

  /**
   * ★★ **白名单外的工具 ⇒ 拒，且不留 revision**。这里把注册表换成 **GM 桶**（里面**有** {@code simos.command.submit}） ⇒
   * 工具**存在**，"找不到工具"这条理由不成立，被拒只可能因为**权限组白名单**没放它（J3/J9 的同一条链）。
   */
  @Test
  void aToolOutsideTheDecisionWhitelistIsRejectedByThePermissionSet() {
    ToolRegistry gmRegistry = new ToolRegistry();
    gmRegistry.registerAll(shell.toolsFor(SimosToolSource.Role.GM));
    long headBefore = head();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"改名\"}");
    args.put("branch", "main");
    args.put("expectedRevision", headBefore);
    llm.enqueue(
        LlmResponse.toolCall("call-x", "simos.command.submit", args), LlmResponse.text("换个办法"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(gmRegistry, DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(turn.toolInvocations()).hasSize(1);
    assertThat(turn.toolInvocations().get(0).success()).as("通用写不在决策人白名单里").isFalse();
    assertThat(turn.toolInvocations().get(0).code())
        .as("拒因来自**权限组的工具白名单**（不是「工具不存在」——它在 GM 桶里、真的注册着）")
        .isEqualTo("PERMISSION_DENIED");
    ContentPart.ToolResult fedBack = toolResults(conversations.load(conversationId())).get(0);
    assertThat(fedBack.isError()).as("★ 失败也回灌给模型（附上真实拒因），而不是静默跳过").isTrue();
    assertThat(fedBack.error())
        .as("拒因原文进 error，且带着**码**（模型据此才知道该换资源还是换参数）")
        .contains(turn.toolInvocations().get(0).code());
    assertThat(head()).as("被拒的写不留 revision").isEqualTo(headBefore);
  }

  /** ★ 同上，但换成**决策人桶**（工具压根没注册）：同样拒、同样不留 revision。两条理由各被自己的用例钉住。 */
  @Test
  void aToolThatIsNotEvenRegisteredIsRejected() {
    long headBefore = head();
    llm.enqueue(
        LlmResponse.toolCall("call-x", "simos.command.submit", Map.of("type", "unit.RenameUnit")),
        LlmResponse.text("换个办法"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(turn.toolInvocations().get(0).success()).isFalse();
    assertThat(head()).isEqualTo(headBefore);
  }

  // ── 回合预算：跑飞要响亮中止 ─────────────────────────────────────────────────────

  /**
   * ★ 模型一直要工具（脚本里没有收口轮）⇒ 撞上回合预算**抛**，不是静默截断出一个"看起来正常"的回合。
   *
   * <p>★ 判别力：没有这道预算时 {@code while(true)} 会一直转（真 LLM 上就是一直烧钱），本用例**不会返回**—— 故它是"预算存在"这件事的
   * 靶子；而"静默截断"的实现会在 {@code finalText} 上留下痕迹 ⇒ 用"抛"把两者分开。
   */
  @Test
  void aRunawayModelIsStoppedByTheTurnBudget() {
    llm.enqueue(
        LlmResponse.toolCall("c1", "simos.map.hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("c2", "simos.map.hex", Map.of("q", 1, "r", 2)),
        LlmResponse.text("永远到不了"));

    DecisionAgentRunner runaway = runner(decisionRegistry(), 2);

    assertThatThrownBy(() -> runaway.run(DM_FRA, state()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("疑似跑飞");

    assertThat(conversations.load(conversationId()))
        .as("★ 中止不丢上下文：已发生的消息逐条落盘（含首条身份），下一 tick 可续")
        .hasSize(5);
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private DecisionAgentRunner runner(ToolRegistry registry, int maxLlmCalls) {
    return new DecisionAgentRunner(factory(), registry, llm, conversations, MAP_ID, maxLlmCalls);
  }

  /** 决策人桶（{@code Role.DECISION_AGENT}）的注册表——生产路径交的就是这个。 */
  private ToolRegistry decisionRegistry() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }

  /**
   * 带审批编排器的调用者工厂（**不是** {@code standard()}：那条路遇到 {@code Ask} 一律拒）。两条决策窄写是敏感工具，
   * 没有编排器就永远进不了工具体，测到的只会是 {@code APPROVAL_DENIED} 而**不是**工具是否真的执行。
   */
  private static DecisionCallerFactory factory() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(),
            List.of(new AutoAnsweringChannel(pending)),
            pending,
            Duration.ofSeconds(5),
            null);
    return new DecisionCallerFactory(
        DecisionScopeFunctions.defaults(),
        ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator));
  }

  /** 测试用的审批通道：登记后立刻按人答"批一次"（与 {@code DecisionMakerScopeEndToEndTest} 同法）。 */
  private static final class AutoAnsweringChannel implements ApprovalChannel {

    private final PendingApprovals pending;

    AutoAnsweringChannel(PendingApprovals pending) {
      this.pending = pending;
    }

    @Override
    public String name() {
      return "test:auto";
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public void publish(ApprovalRequest req) {
      pending.decide(req.id(), ApprovalDecision.APPROVE_ONCE, name());
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      return pending.await(id, wait);
    }
  }

  /**
   * **记下每一次请求**的 LLM 客户端（包住真脚本执行器）。
   *
   * <p>★ 为什么必须有它：判据 ③ 要证的是"**接上了**"，而 {@code FakeLlmClient} 只回放脚本、不暴露收到了什么 ⇒ 只能自己留痕。
   * 否则"会话存下来了"与"会话喂进去了"分不开。
   */
  private static final class RecordingLlmClient implements LlmClient {

    private final FakeLlmClient delegate = new FakeLlmClient();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse... responses) {
      for (LlmResponse response : responses) {
        delegate.enqueue(response);
      }
    }

    List<LlmRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      return delegate.chat(request);
    }
  }

  private static String conversationId() {
    return DecisionAgentRunner.conversationIdOf(DM_FRA.id());
  }

  private static List<String> roles(List<LlmMessage> messages) {
    return messages.stream().map(LlmMessage::role).toList();
  }

  /** 一条消息的首个文本分片（身份消息是纯文本）。 */
  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(part -> ((ContentPart.Text) part).text())
        .findFirst()
        .orElseThrow(() -> new AssertionError("这条消息没有文本分片: " + message.role()));
  }

  /** 会话里所有 tool 结果（按发生序）。 */
  private static List<ContentPart.ToolResult> toolResults(List<LlmMessage> messages) {
    List<ContentPart.ToolResult> out = new ArrayList<>();
    for (LlmMessage message : messages) {
      for (ContentPart part : message.content()) {
        if (part instanceof ContentPart.ToolResult result) {
          out.add(result);
        }
      }
    }
    return out;
  }

  private static Map<String, Object> directiveArgs(long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put(
        "payloadJson",
        "{\"directiveId\":\"d-1\",\"decisionMakerId\":\"dm-fra\",\"tick\":7,"
            + "\"intentInfo\":\"向北推进\",\"commands\":[]}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  private SimulationState state() {
    return shell.queryService().stateAt(QueryTarget.head(main()));
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private Path conversationsFile() {
    return tempDir.resolve("conversations.db");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

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
    SocialData social = new SocialData(new LinkedHashMap<>(Map.of(H12, populationSeries())));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithOneNationOneArmy())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人。 */
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

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
