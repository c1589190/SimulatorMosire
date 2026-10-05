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
import io.mosire.agentlib.llm.ToolDef;
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
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
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

  /** 现场实测里模型第一次出令给的那一条：{@code sd.RegisterEffect} —— **自指**，{@code DirectiveWhitelist} 必拒。 */
  private static final String SELF_REFERENCING_COMMAND =
      "{\"type\":\"sd.RegisterEffect\",\"payloadJson\":\"{}\"}";

  /** 一条**非 sd 的领域命令**：身份消息新补的那条规则指示的形态，重试时用它（证明按规则重试走得通）。 */
  private static final String DOMAIN_COMMAND =
      "{\"type\":\"unit.RenameUnit\",\"payloadJson\":\"{}\"}";

  private static final DecisionMaker DM_FRA =
      new DecisionMaker(
          new DecisionMakerId("dm-fra"),
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          AccessLimit.empty(),
          1);

  /** 军队决策人（**只为验身份正文的军队分支**）：它不必在世界里存在——注入的那条消息只取 {@code DecisionMaker} 自己的字段、不查世界。 */
  private static final DecisionMaker DM_ARMY =
      new DecisionMaker(
          new DecisionMakerId("dm-army"),
          new Affiliation.Army(new ArmyId("a1")),
          Set.of(),
          AccessLimit.empty(),
          1);

  @TempDir Path tempDir;

  /**
   * 开场快照用例里那个**假** assetId：形状合法（工件库只收 {@code [0-9a-f]{64}}）但**不在库里**。
   *
   * <p>★ 用假 id 是有意的：那几条用例验的是"**快照消息注没注进请求**"，而不是"渲染出没出图"（后者由 {@link
   * #theNationOpeningSnapshotRendersTheHomeRegionAroundItsLabelHex} 用真渲染件单独证）。真发送路径（解析字节）不在
   * 这两条用例的射程内——它们用的是回放的 {@code FakeLlmClient}，不会发出去。
   */
  private static final String FAKE_ASSET_ID = "0".repeat(64);

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
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("call-2", "sd_IssueDirective", directiveArgs(headBefore)),
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

  // ── 线格式：送给模型的工具名必须匹配供应商的函数名文法 ──────────────────────────

  /**
   * ★★ **真 LLM 实测缺陷的判据**（2026-09-22）：送给模型的工具名必须匹配 {@code ^[a-zA-Z0-9_-]+$}。
   *
   * <p>★ **缺陷现场**（不是推断）：Simos 的工具名形如 {@code simos.map.hex} / {@code sd.IssueDirective}，含 {@code
   * .}；OpenAI 兼容端点直接拒（{@code HTTP 400: Invalid 'tools[0].function.name': string does not match
   * pattern ... '^[a-zA-Z0-9_-]+$'}）⇒ 一轮决策**一次都没发出去**。
   *
   * <p>★ 断言取**发出去的请求本身**（{@code llm.requests()}），不是某个内部表：只有请求里的字节是真的， 别的都是"我们以为发出去的是什么"。
   */
  @Test
  void theToolNamesSentToTheModelAreWireSafe() {
    llm.enqueue(LlmResponse.text("收到"));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    List<String> sent = llm.requests().get(0).tools().stream().map(ToolDef::name).toList();
    assertThat(sent).as("工具面一条不少（转义不得把工具弄丢）").hasSize(DecisionCallerFactory.WHITELIST.size());
    assertThat(sent)
        .as("★★ 每一条都匹配供应商的函数名文法——旧形态（含 .）在这里必红")
        .allMatch(name -> name.matches("^[a-zA-Z0-9_-]+$"));
    assertThat(sent)
        .as("★ 转义规则是 `.` → `_`（不是删掉点：`simosmaphex` 也匹配文法，却读不出层级、且更容易撞名）")
        .contains("simos_map_hex", "sd_IssueDirective");
  }

  /**
   * ★★ **模型叫了一个名字表里没有、注册表里也没有的名字 ⇒ 可读拒绝**（判据：模型要能看懂它**叫错了名**）。
   *
   * <p>★ 正文里必须有**它给的那个名字**：只说"调用失败"等于把"你叫的是谁"这件事抹掉，模型下一轮还会照叫。
   *
   * <p>★ 这条走的是**既有路径**（AgentLib {@code ToolExecutionGuard} 的 {@code TOOL_NOT_FOUND}），本层不新造拒绝理由。
   */
  @Test
  void aNameThatIsNeitherAWireNameNorAToolIsRejectedReadably() {
    long headBefore = head();
    llm.enqueue(
        LlmResponse.toolCall("call-x", "simos.made.up", Map.of("q", 1)), LlmResponse.text("换个办法"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(turn.toolInvocations()).hasSize(1);
    DecisionAgentRunner.ToolInvocation call = turn.toolInvocations().get(0);
    assertThat(call.success()).isFalse();
    assertThat(call.code()).isEqualTo("TOOL_NOT_FOUND");
    assertThat(call.resultSummary())
        .as("★ 拒因正文里带着**模型给的那个名字**——它据此才知道自己叫错了名")
        .contains("simos.made.up");
    assertThat(call.toolName())
        .as("★ 账里记的是**它说的那个名字**（这一笔的事实就是「模型叫了这个」；线名才是供应商方言的产物）")
        .isEqualTo("simos.made.up");
    assertThat(toolResults(conversations.load(conversationId())).get(0).error())
        .as("失败也回灌给模型（含码与它给的名字）")
        .contains("simos.made.up");
    assertThat(head()).as("拒掉的调用不留 revision").isEqualTo(headBefore);
  }

  /**
   * ★★ **过不了名字表、但注册表里真有的名字，本层不抢着拒**（真实名**原样**交给权限链）。
   *
   * <p>★ **为什么不在这里判"名字不在表里"**：本层的表是从"白名单 ∩ 注册表"建的 ⇒ **凡是能过表的都在白名单里**
   * （权限组必放行）。若本层抢先拒，则"工具真的在注册表里、只是权限组不放它"这条既有判据就**再也测不到**（见 {@link
   * #aToolOutsideTheDecisionWhitelistIsRejectedByThePermissionSet}）——那等于用一个更弱的理由顶掉一个更强的判据。
   *
   * <p>★ **顺带保住一件事**：修这个缺陷之前落盘的老会话里，身份消息说的是真实名（那时模型看不见线名）；放行原话意味着**老会话不必作废** （模型照旧话叫得动），而新会话一律说线名。
   */
  @Test
  void aRealNameThatIsNotAWireNameStillReachesTheRegistryUnchanged() {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos.map.hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("用真名也叫得动"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    assertThat(turn.toolInvocations().get(0).success()).isTrue();
    assertThat(toolResults(conversations.load(conversationId())).get(0).content())
        .as("真的跑到了那个工具上（真世界的值）")
        .contains("desert");
    assertThat(turn.toolInvocations().get(0).toolName())
        .as("表里没有它 ⇒ 账里记原话")
        .isEqualTo("simos.map.hex");
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
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
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
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
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

  // ── 会话世代：id 由「id + 世代」派生（老格式必须逐字不变） ─────────────────────────

  /**
   * ★★ **世代 0 ⇒ 与旧格式逐字相同**（本任务最硬的一条兼容要求）。
   *
   * <p>★★ 期望值写的是**字面量**，不是拿同一个函数算出来的：用函数算期望值的话，「永远加后缀」的实现本用例照样绿——而现场已落盘的老会话 （{@code
   * decision-maker:dm-fra}，本阶段验收实测）就会**全部接不上**，症状是"决策人失忆"，不是一条报错。
   */
  @Test
  void generationZeroKeepsTheLegacyConversationIdByteForByte() {
    assertThat(DecisionAgentRunner.conversationIdOf(DM_FRA))
        .as("★ 字面量，不是同函数算出来的期望值")
        .isEqualTo("decision-maker:dm-fra");
  }

  /**
   * ★ **任意两对 (id, 世代) 派生出不同的会话 id**——包括 {@code id 里含分隔符} 那种边界。
   *
   * <p>★★ 判别力就是**格式本身**：把世代拼在 id **后面**（{@code "decision-maker:" + id + "#" + gen}）的实现在这条上红—— 那时 id
   * 叫 {@code a#1} 的决策人（世代 0）与 id 叫 {@code a} 的（世代 1）**派生出同一个 id**，两个人共用一段会话， 而且不会有任何报错。
   * 现格式让两族**前缀**就不同（{@code decision-maker:} vs {@code decision-maker#}）⇒ 对任意 id 都不会撞。
   */
  @Test
  void noTwoIdGenerationPairingsEverCollide() {
    Set<String> ids = Set.of("dm-fra", "a", "a#1", "a#0", "#1", "1");

    Set<String> seen = new HashSet<>();
    for (String id : ids) {
      for (long generation : new long[] {0L, 1L, 2L}) {
        String derived = DecisionAgentRunner.conversationIdOf(new DecisionMakerId(id), generation);
        assertThat(seen.add(derived))
            .as("(id=%s, 世代=%d) 派生出 %s —— 与之前某一对撞了", id, generation, derived)
            .isTrue();
      }
    }
    assertThat(seen).as("夹具自检：这一组确实产出了 %d 个不同 id", ids.size() * 3).hasSize(18);
  }

  /**
   * ★★ **世代变了 ⇒ 下一轮真的从空上下文开始**（本任务的主行为，运行流这一层的最小判据）。
   *
   * <p>脚本：先用世代 0 跑一轮（落 4 条进老会话）⇒ 再用**同一个决策人、世代 1** 跑一轮 ⇒ 它的**首个请求里只有身份那一条**。
   *
   * <p>判别力：只把会话 id 换个写法、但 {@code run} 仍按别的键读写会话的实现，本用例红在条数上；只动了 id 却没让 runner 用它的实现， 红在"第二轮的请求是 4
   * 条"上。
   */
  @Test
  void aNewGenerationStartsFromAnEmptyContextAndLeavesTheOldOneIntact() {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.text("世代 0 到此"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());
    assertThat(conversations.load("decision-maker:dm-fra")).hasSize(4);

    DecisionMaker reset = atGeneration(1);
    llm.enqueue(LlmResponse.text("新会话第一句"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(reset, state());

    assertThat(llm.requests().get(2).messages())
        .as("★★ 世代 1 的首个请求里**只有身份**（世代 0 的那 4 条一条都没跟过来）")
        .hasSize(1);
    assertThat(roles(llm.requests().get(2).messages())).containsExactly("system");
    assertThat(conversations.load(DecisionAgentRunner.conversationIdOf(reset)))
        .as("新会话独立地从头长起")
        .hasSize(2);
    assertThat(conversations.load("decision-maker:dm-fra"))
        .as("★★ 老会话的字节一条都没少（重置不是「擦库」）")
        .hasSize(4);
  }

  /** 同一个决策人、只换世代（其余字段逐字相同）：reset 语义就是"id 不变、会话换代"。 */
  private static DecisionMaker atGeneration(long generation) {
    return new DecisionMaker(
        DM_FRA.id(),
        DM_FRA.affiliation(),
        DM_FRA.allowedTools(),
        DM_FRA.accessLimit(),
        DM_FRA.decisionCadenceTicks(),
        DM_FRA.providerId(),
        generation);
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
        .as(
            "任务：范围由系统强制 + 先查看再决策 + 出令与载荷提示 + expectedRevision 的来源；"
                + "★ 名字一律是**线格式**（模型眼里的那个写法），说真名等于让它去叫一个看不见的名字")
        .contains("sd_IssueDirective")
        .contains("simos_command_catalog")
        .contains("simos_timeline_branches")
        .contains("expectedRevision")
        .doesNotContain("sd.IssueDirective")
        .doesNotContain("simos.command.catalog");
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
   * ★★ **身份消息必须交代「出令的 {@code commands} 不得含 {@code sd.*}」**（真 LLM 实测缺陷的修法，2026-09-22）。
   *
   * <p>★ **缺陷现场**（不是推断）：决策人 {@code dm-osman} 勘察完之后**第一次出令就用了** {@code sd.RegisterEffect} —— 那是
   * {@code sd} 自指，被 {@code DirectiveWhitelist} **正确拒绝**（防无限递归）。它此前**无从得知**这条规则：身份消息只说 "出令用 {@code
   * sd_IssueDirective}、载荷字段见 catalog"，**没有一个字说 commands 里不许放 sd 前缀的类型**。代价是**白烧一轮**
   * ——而那一轮预算已经见底（同一次现场的另一条发现）。
   *
   * <p>★ **断言方式刻意不依赖任何清单**：只钉这条规则的**两半**——① 受管对象是**命令类型**那一层（写作 {@code commands}）； ② 被禁的是 {@code
   * sd.} 这个**前缀**（不是某个具体命令名）。故日后 sd 命令族增删、catalog 里多一条少一条，本用例都不动。
   *
   * <p>★ **为什么钉 {@code "sd."}（点）而不是 {@code "sd_"}**：点号是**命令类型**的命名空间分隔符（{@code
   * DirectiveWhitelist.isSdSelfReference} 判的正是它，现场那次拒绝的理由正文逐字是「决策命令不得自指 sd.*（防无限递归）:
   * sd.RegisterEffect」）；下划线是**工具名**的线格式转义产物（{@code sd_IssueDirective}）——两者混为一谈就等于教错了层。
   * 判别力也在这里：现有正文里有 {@code sd_IssueDirective} 却**没有** {@code sd.}，故 {@code contains("sd.")} 不是恒真。
   */
  @Test
  void theIdentityMessageStatesTheNoSelfReferencingCommandsRule() {
    llm.enqueue(LlmResponse.text("收到"));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS).run(DM_FRA, state());

    String text = textOf(llm.requests().get(0).messages().get(0));
    assertThat(text)
        .as("★ commands 里不得放 sd 前缀的命令类型（防无限递归）——不说这条，真模型第一次出令必然被拒、白烧一轮")
        .contains("commands")
        .contains("sd.");
    assertThat(text)
        .as("★ 但**不许**把禁令写成真名（模型眼里的工具名一律是线格式，见身份消息的类注）")
        .doesNotContain("sd.RegisterEffect");
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
    // ★ 第 3 波第 3 步：决策人白名单新增了只读工具 {@code sd.DecisionResults}（**只挂决策人桶**，GM 桶没有它），
    //   而 DecisionAgentRunner 的构造期要求"白名单 ⊆ 注册表"（DecisionToolDefs.requireAll）⇒ 补上 GM 桶缺的那条
    //   （本用例要证的仍是"GM 桶里的通用写 simos.command.submit 不在白名单，故被权限组拒"）。
    shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
        .filter(tool -> gmRegistry.find(tool.name()).isEmpty())
        .forEach(gmRegistry::register);
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
        LlmResponse.toolCall("c1", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("c2", "simos_map_hex", Map.of("q", 1, "r", 2)),
        LlmResponse.text("永远到不了"));

    DecisionAgentRunner runaway = runner(decisionRegistry(), 2);

    assertThatThrownBy(() -> runaway.run(DM_FRA, state()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("疑似跑飞");

    assertThat(conversations.load(conversationId()))
        .as("★ 中止不丢上下文：已发生的消息逐条落盘（含首条身份），下一 tick 可续")
        .hasSize(5);
  }

  /**
   * ★★ **现场重放：默认预算必须装得下「勘察 → 出令被拒 → 重读 → 重发 → 收口」**（真 LLM 实测缺陷的修法，2026-09-22）。
   *
   * <p>★ **现场**（不是推断）：决策人 {@code dm-osman} 在**最小世界**（1 国 / 1 区域 / **573 格** / 1 单位）上跑一轮， **7 次勘察 +
   * 1 次出令**就把 8 次预算用满；出令因 {@code sd.RegisterEffect} 自指被正确拒绝后， 它**正要重读状态再试**时预算耗尽 ⇒ {@code
   * abortedByBudget}（设计如此、**不静默截断**，这点是对的）。⇒ **8 是「最小世界的勘察成本本身」**，连一次重试都装不下。
   *
   * <p>★ 本用例按现场**逐调用重放**（7 勘察 + 1 被拒出令 + 1 重读 + 1 重发 + 1 收口 = **11** 次），用**默认构造器** （不给显式上限）：预算是 8
   * 时，第 9 次调用**之前**就会抛 {@code TurnBudgetExceeded}，本用例必红。
   *
   * <p>★ **承重断言是「跑完了」这件事本身**（不抛、且重试那条真的落了 revision）——不是"读到一个常量等于某个数"。 那条值断言在 {@link
   * #theDefaultBudgetIsTheNumberTheLiveRunArithmeticYields} 里另有一条，两条各管一件事。
   *
   * <p>★ 重试那条用的是**非 sd 的领域命令**（{@code unit.RenameUnit}）：这正是身份消息新补的那条规则所指示的形态，故本用例
   * **顺带证明"按新规则重试真的走得通"**——不是只证明"预算变大了"。
   */
  @Test
  void theDefaultBudgetFitsTheObservedSurveyThenRetrySequence() {
    long headBefore = head();
    llm.enqueue(
        // ── 7 次勘察（现场那 7 次摸工具面）
        LlmResponse.toolCall("s1", "simos_command_catalog", Map.of()),
        LlmResponse.toolCall("s2", "simos_timeline_branches", Map.of()),
        LlmResponse.toolCall("s3", "simos_map_overview", Map.of()),
        LlmResponse.toolCall("s4", "simos_unit_list", Map.of()),
        LlmResponse.toolCall("s5", "simos_unit_get", Map.of("id", U1.value())),
        LlmResponse.toolCall("s6", "simos_map_hex", Map.of("q", 1, "r", 1)),
        LlmResponse.toolCall("s7", "simos_state_facets", Map.of("address", "map:" + MAP_ID)),
        // ── 第 8 次：出令，用**自指**命令 ⇒ 被白名单正确拒绝（现场那一笔）
        LlmResponse.toolCall(
            "d1",
            "sd_IssueDirective",
            directiveArgs(headBefore, "d-1", "[" + SELF_REFERENCING_COMMAND + "]")),
        // ── 现场正好停在这一步：它要重读状态、再出一次令
        LlmResponse.toolCall("s8", "simos_timeline_branches", Map.of()),
        LlmResponse.toolCall(
            "d2",
            "sd_IssueDirective",
            directiveArgs(headBefore, "d-2", "[" + DOMAIN_COMMAND + "]")),
        LlmResponse.text("已按重读到的 head 改用领域命令重新出令"));

    // ★ 默认构造器（不给显式上限）——测的就是 DEFAULT_MAX_LLM_CALLS 那个数
    DecisionAgentRunner.DecisionTurn turn =
        new DecisionAgentRunner(factory(), decisionRegistry(), llm, conversations, MAP_ID)
            .run(DM_FRA, state());

    assertThat(turn.llmCalls())
        .as("★ 11 次调用全部走完（7 勘察 + 被拒出令 + 重读 + 重发 + 收口）；预算是 8 时这里根本走不到")
        .isEqualTo(11);
    assertThat(turn.toolInvocations())
        .as("★ 10 次工具调用（11 次 LLM 调用里最后一次是**收口文本**、不带工具）——勘察读 + 两次出令")
        .hasSize(10);
    assertThat(turn.toolInvocations().get(7).success()).as("★ 自指那条被拒（现场实测的正确行为，本用例不改它）").isFalse();
    assertThat(turn.toolInvocations().get(7).resultSummary()).contains("自指");
    assertThat(turn.toolInvocations().get(9).success())
        .as("★ 按身份消息新补的规则改用领域命令后**真的出令成功**——不只是「预算变大」")
        .isTrue();
    assertThat(head()).as("★ 只落一条 revision：被拒那条不留痕、重试那条留下").isEqualTo(headBefore + 1);
  }

  /**
   * ★ **默认回合预算就是现场算出来的那个数**（{@code 20}）：这是一条**带算术的裁定**，不是实现细节 ⇒ 不许静默改动。
   *
   * <p>★ 算术（每一项都取**上界**，依据是 2026-09-22 的真 LLM 现场）：
   *
   * <ol>
   *   <li>勘察 <b>14</b> = 实测 <b>7</b> × 2——实测那 7 次是**最小世界**新会话首轮"把整个工具面摸一遍"的量（catalog / branches /
   *       overview / unit.list / unit.get / map.hex / facets / …），世界变大只会更多；
   *   <li>出令 <b>1</b>；
   *   <li>重试 <b>4</b> = 2 轮 × (重读状态 + 重发令)——两种被拒理由（{@code commands} 自指 / {@code expectedRevision}
   *       过期）各留一轮；
   *   <li>收口 <b>1</b>（工具轮之后模型还要一次纯文本轮才算完，既有用例 {@code
   *       theRunnerReallyExecutesTheToolsTheModelAsksFor} 实测的就是这个形态）。
   * </ol>
   *
   * <p>★ 合计 <b>20</b>。**下界是 11**（现场那次撞墙的序列长度）——那个下界由 {@link
   * #theDefaultBudgetFitsTheObservedSurveyThenRetrySequence} 在**行为层**另证一次； 本用例钉的是「别把这条决定改掉而不重新算一遍」，
   * 它的判别力在**值**本身（改数就红），不在行为上。两者缺一不可：只有行为断言则"悄悄降到 11"能过，只有值断言则"降到 11 恰好还够用"这件事无人证明。
   */
  @Test
  void theDefaultBudgetIsTheNumberTheLiveRunArithmeticYields() {
    assertThat(DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS)
        .as("勘察 14 + 出令 1 + 重试 4 + 收口 1 = 20（算术见本用例注释与常量注释）")
        .isEqualTo(20);
  }

  // ────────────────────────────── 图片通路（P4，2026-09-24） ──────────────────────────────

  /**
   * ★★ **P4 主判据（正）**：有视觉能力时，工具结果里的图片资产**真的随请求发给模型**。
   *
   * <p>装置：让模型调 {@code simos_map_render}（真渲染工具、真工件库）⇒ 它回的 {@code ToolResult.assetDocIds()} 非空 ⇒
   * 下一次请求里必须多出一条 **user 图片消息**。
   *
   * <p>★★ **三处一起断言，缺一条这条判据就是假的**：① 那是一条 {@code user} 消息（{@code tool} 角色带图是 AgentLib 的响亮 CONFIG 错）；②
   * 里面是 {@link ContentPart.Image}（不是一段描述图的话）；③ 那个 {@code assetId} **能经工件库解析出真正的 PNG
   * 字节**（"字段在"不等于"图在"——解析不到就只是一串 sha256）。
   */
  @Test
  void withVisionTheImagesInToolResultsReachTheModelAsAUserImageMessage() {
    llm.enqueue(
        LlmResponse.toolCall("call-1", "simos_map_render", Map.of("q", 1, "r", 1)),
        LlmResponse.text("看过了"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, true)
            .run(DM_FRA, state());

    assertThat(turn.toolInvocations()).hasSize(1);
    assertThat(turn.toolInvocations().get(0).success()).as("真渲染工具应当在两格世界上成功").isTrue();
    assertThat(turn.toolInvocations().get(0).resultSummary()).contains("assetId");

    // ★ 第 2 次请求 = 工具轮之后：tool 消息 + 图片消息都已在历史里。
    List<LlmMessage> afterTool = llm.requests().get(1).messages();
    assertThat(roles(afterTool)).endsWith("tool", "user");
    LlmMessage imageMessage = afterTool.get(afterTool.size() - 1);
    ContentPart.Image image = imagePartOf(imageMessage);
    assertThat(image.mediaType()).isEqualTo("image/png");
    assertThat(shell.artifactStore().resolve(image.assetId()))
        .as("★★ 引用必须解析得到字节——否则模型收到的是一个查无此物的 id")
        .isPresent();
    assertThat(pngOf(image.assetId()))
        .as("PNG 魔数：工件库里那份字节真的是一张 PNG")
        .startsWith(0x89, 'P', 'N', 'G');
  }

  /**
   * ★★ **P4 主判据（反）**：**没有**视觉能力时，一张图都不许发——**即使工具真的产出了**一张图。
   *
   * <p>★★ **为什么载荷要显式写 {@code format=image}**（这一点是本用例的要害）：{@code auto} 在无视觉能力的路由上会**回落成 字符图**（不产工件，见
   * {@code MapRenderTool}）⇒ 用 auto 的话"没发图"**证明不了任何东西**（压根没有图可发，断言恒真）。 显式要 image ⇒ 工具真产出了图、结果里带着
   * {@code assetId}，而运行流**仍然**没把它附进消息——那才是"vision 门控真的在拦"。
   *
   * <p>★ 判别力：把 {@code imagesMessage} 里的 {@code vision} 判断去掉（或写反）⇒ 本用例当场红。
   */
  @Test
  void withoutVisionNoImageIsEverSentEvenWhenTheToolProducedOne() {
    llm.enqueue(
        LlmResponse.toolCall(
            "call-1", "simos_map_render", Map.of("q", 1, "r", 1, "format", "image")),
        LlmResponse.text("看过了"));

    DecisionAgentRunner.DecisionTurn turn =
        runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, false)
            .run(DM_FRA, state());

    assertThat(turn.toolInvocations().get(0).success()).isTrue();
    assertThat(turn.toolInvocations().get(0).resultSummary())
        .as("前置条件：工具这次**真的产出了图**（否则本用例恒真、没有判别力）")
        .contains("assetId");
    for (LlmRequest request : llm.requests()) {
      for (LlmMessage message : request.messages()) {
        assertThat(message.content())
            .as("无视觉能力的路由上不得出现任何图片分片（发过去只会换一个 400）")
            .noneMatch(ContentPart.Image.class::isInstance);
      }
    }
    assertThat(toolResults(llm.requests().get(1).messages()))
        .as("回灌的 tool 消息里仍有文本摘要 + assetId（图片是附加，不是替代）")
        .anySatisfy(result -> assertThat(result.content()).as("工具结果正文").contains("assetId"));
  }

  /**
   * ★★ **回归：一个回合里要了多个工具时，图片消息必须排在所有 tool 消息之后**（真网关实测的 400，2026-09-24）。
   *
   * <p>★★ **这条用例的由来是现场失败**：第一次真跑决策人，模型一个回合里一次要了 5 个工具 （`simos_map_render` + `simos_map_hex`
   * ×4）。我原来的写法把图片消息**紧跟在它那条 tool 消息后面**发出去， 于是历史成了 {@code assistant(tool_calls) → tool → user(图) →
   * tool …}，真网关当场拒： {@code HTTP 400: An assistant message with 'tool_calls' must be followed by
   * tool messages responding to each 'tool_call_id'. (insufficient tool messages following
   * tool_calls message)}。
   *
   * <p>★★ **为什么单工具用例查不出它**：只有一个工具调用时，"紧跟其后"与"排在最后"是同一个位置 ⇒ 两种写法都绿。
   * 所以本用例**必须**让模型一次要两个工具，并用"图片消息之前不许再出现 tool 消息"这条**结构判据**钉住顺序 （回放式的假客户端不校验协议，光看内容看不出来）。
   */
  @Test
  void theImageMessageComesAfterEveryToolMessageOfTheSameTurn() {
    // ★ 要证的是"一个 assistant 里**多个** tool_call"那种形态，故直接拼一条含两个 tool_call 的响应
    //   （`LlmResponse.toolCall(...)` 只给单个 —— 用它的话两次调用分属两轮，"紧跟其后"与"排在最后"就又是同一个位置了）。
    LlmResponse twoCalls =
        new LlmResponse(
            LlmMessage.assistant(
                List.of(
                    new ContentPart.ToolCall(
                        "call-1", "simos_map_render", Map.of("q", 1, "r", 1, "format", "image")),
                    new ContentPart.ToolCall("call-2", "simos_map_hex", Map.of("q", 1, "r", 1)))),
            "fake-model",
            LlmResponse.UNKNOWN_TOKENS,
            LlmResponse.UNKNOWN_TOKENS);
    llm.enqueue(twoCalls, LlmResponse.text("看过了"));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, true)
        .run(DM_FRA, state());

    List<LlmMessage> afterTools = llm.requests().get(1).messages();
    assertThat(roles(afterTools))
        .as("一个回合两个工具 + 一张图 ⇒ system, assistant(2 个 tool_call), tool, tool, user(图)")
        .containsExactly("system", "assistant", "tool", "tool", "user");
    assertThat(afterTools.get(afterTools.size() - 1).content())
        .as("★ 图片消息是这一回合的最后一条（排在**所有** tool 消息之后）")
        .anyMatch(ContentPart.Image.class::isInstance);
  }

  /**
   * ★★ **开场快照（P4）**：空会话首轮，身份之后紧跟一条图片消息；**只在空会话注入一次**。
   *
   * <p>★ 顺序判据落在 {@code roles(...)} 的**逐字相等**上：{@code [system, user]}（图片消息是 user）——
   * 反了就会变成"你连自己是谁都还不知道，先看一张图"。
   */
  @Test
  void theOpeningSnapshotIsAttachedOnceRightAfterTheIdentityMessage() {
    llm.enqueue(LlmResponse.text("看到了"));
    DecisionAgentRunner.OpeningSnapshot snapshot =
        (dm, state) ->
            Optional.of(
                new LlmMessage(
                    LlmMessage.ROLE_USER,
                    List.of(
                        new ContentPart.Text("【开场快照】"),
                        new ContentPart.Image("image/png", FAKE_ASSET_ID))));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, true, snapshot)
        .run(DM_FRA, state());
    assertThat(roles(llm.requests().get(0).messages())).containsExactly("system", "user");
    assertThat(imagePartOf(llm.requests().get(0).messages().get(1)).assetId())
        .isEqualTo(FAKE_ASSET_ID);

    // ★ 第二轮（会话已有历史）不得再注一次：否则每一轮都白花一张图的钱。
    llm.enqueue(LlmResponse.text("还是看到了"));
    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, true, snapshot)
        .run(DM_FRA, state());
    assertThat(roles(llm.requests().get(1).messages()))
        .as("第二轮请求 = 历史里的 [system, user(快照), assistant]（本轮的 assistant 要等请求之后才落）")
        .containsExactly("system", "user", "assistant");
    assertThat(
            llm.requests().get(1).messages().stream()
                .filter(
                    message ->
                        message.content().stream().anyMatch(ContentPart.Image.class::isInstance))
                .count())
        .as("整个第二轮请求里带图的消息仍只有历史里那一张（快照没有第二次注入）")
        .isEqualTo(1);
  }

  /**
   * ★ **开关开着但路由没有视觉能力**：跳过（不注一条 resolving 不了的图片消息），且**不是静默**——留一行 warn。
   *
   * <p>★ 判别力：把 {@code openingSnapshotMessage} 里的 {@code !vision} 那一支去掉 ⇒ 首轮会多出一条带图的 user 消息 ⇒ 本用例红。
   */
  @Test
  void theOpeningSnapshotIsSkippedWithoutVision() {
    llm.enqueue(LlmResponse.text("看到了"));
    DecisionAgentRunner.OpeningSnapshot snapshot =
        (dm, state) ->
            Optional.of(
                new LlmMessage(
                    LlmMessage.ROLE_USER,
                    List.of(new ContentPart.Image("image/png", FAKE_ASSET_ID))));

    runner(decisionRegistry(), DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS, false, snapshot)
        .run(DM_FRA, state());

    assertThat(roles(llm.requests().get(0).messages()))
        .as("无视觉能力 ⇒ 首轮只有身份那条 system（快照被跳过）")
        .containsExactly("system");
  }

  /**
   * ★★ **国家决策人的开场快照真的能出图**（{@link NationOpeningSnapshot} 的端到端）：国家 FRA 的首府区域是 701 （{@code
   * (1,1)/(1,2)} 两格）⇒ 取景中心 = 最北偏西那格 {@code (1,1)}，图能渲染、字节能解析。
   */
  @Test
  void theNationOpeningSnapshotRendersTheHomeRegionAroundItsLabelHex() throws Exception {
    Optional<LlmMessage> message =
        new NationOpeningSnapshot(shell.renderService()).forDecisionMaker(DM_FRA, state());

    assertThat(message).as("国家决策人 + 世界里真有那个首府区域 ⇒ 应当出图").isPresent();
    LlmMessage user = message.get();
    assertThat(user.role()).isEqualTo("user");
    assertThat(textOf(user)).as("图上要说清这是什么、以及中心在哪一格").contains("1,1");
    ContentPart.Image image = imagePartOf(user);
    assertThat(ImageIO.read(new ByteArrayInputStream(pngOf(image.assetId()))))
        .as("★ 解析出来的字节要真能被解成一张图（不是看起来像 PNG 的垃圾）")
        .isNotNull();
  }

  /** 军队决策人**本批不给**开场快照（如实记：取景语义未裁决，见 NationOpeningSnapshot 的类注）。 */
  @Test
  void theNationOpeningSnapshotGivesNothingForAnArmyDecisionMaker() {
    assertThat(new NationOpeningSnapshot(shell.renderService()).forDecisionMaker(DM_ARMY, state()))
        .isEmpty();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private DecisionAgentRunner runner(ToolRegistry registry, int maxLlmCalls) {
    return runner(registry, maxLlmCalls, false, DecisionAgentRunner.OpeningSnapshot.NONE);
  }

  private DecisionAgentRunner runner(ToolRegistry registry, int maxLlmCalls, boolean vision) {
    return runner(registry, maxLlmCalls, vision, DecisionAgentRunner.OpeningSnapshot.NONE);
  }

  private DecisionAgentRunner runner(
      ToolRegistry registry,
      int maxLlmCalls,
      boolean vision,
      DecisionAgentRunner.OpeningSnapshot openingSnapshot) {
    return new DecisionAgentRunner(
        factory(),
        registry,
        llm,
        conversations,
        MAP_ID,
        maxLlmCalls,
        DecisionAgentRunner.ProgressListener.NONE,
        vision,
        openingSnapshot);
  }

  /** 一条消息里**唯一**的那张图片分片（没有就当场失败——"字段在不在"这件事本身是判据）。 */
  private static ContentPart.Image imagePartOf(LlmMessage message) {
    List<ContentPart.Image> images =
        message.content().stream()
            .filter(ContentPart.Image.class::isInstance)
            .map(ContentPart.Image.class::cast)
            .toList();
    assertThat(images).as("这条消息里应当恰好有一张图").hasSize(1);
    return images.get(0);
  }

  /** 工件库里的那份字节（解析不到 ⇒ 当场失败）。 */
  private byte[] pngOf(String assetId) {
    return shell.artifactStore().resolve(assetId).orElseThrow().bytes();
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
    return DecisionAgentRunner.conversationIdOf(DM_FRA);
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
    return directiveArgs(expectedRevision, "d-1", "[]");
  }

  /**
   * 出令载荷（可带 {@code commands}）——现场重放要它：那条自指的与那条合法的各要一份。
   *
   * @param directiveId 指令 id（两次尝试各用一个，免与"指令 id 已存在"的拒绝理由混起来）
   * @param commandsJson {@code commands} 数组的**原样 JSON 文本**（空集合传 {@code "[]"}）
   */
  private static Map<String, Object> directiveArgs(
      long expectedRevision, String directiveId, String commandsJson) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put(
        "payloadJson",
        "{\"directiveId\":\""
            + directiveId
            + "\",\"decisionMakerId\":\"dm-fra\",\"tick\":7,"
            + "\"intentInfo\":\"向北推进\",\"commands\":"
            + commandsJson
            + "}");
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
        List.of(new CompositionEntry("步枪", 50)),
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
