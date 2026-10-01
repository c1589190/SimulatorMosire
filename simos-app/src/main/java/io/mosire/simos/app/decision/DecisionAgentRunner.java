package io.mosire.simos.app.decision;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.llm.LlmToolNames;
import io.mosire.simos.app.render.ArtifactStore;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapRenderTool;
import io.mosire.simos.app.tools.read.SkillTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **决策人 agent 运行流**（spec §2.3，判据 **J11**）：让决策人**自己调工具**读世界，并且**跨 tick 沿用同一段会话**。
 *
 * <p>★ **它取代的形态**：在此之前决策人的 LLM 路径是**单轮**——{@code AdjudicatorRunner} 把"简报 + 输出 schema" 喂给模型、收一段
 * JSON（{@code DecisionAdjudicator.adjudicate}），**既无工具调用、也无跨 tick 上下文**。用户 2026-09-22
 * 原话「要决策人自己调工具、带上下文」指的就是这里。
 *
 * <p>★ **为什么不改 AgentLib**（spec §2.3）：AgentLib 提供全部原料（{@code LlmClient} / {@code ToolDef} / {@code
 * ConversationStore} / {@code ToolCallAuthorizer}），**只有循环本身没有**——而隔壁 Brain 的 {@code AgentPipeline}
 * 依赖的是 Brain 自己的类（{@code AgentConfig}/{@code ContextAssembler}/{@code Compactor}…）， 搬不动；AgentLib
 * 的定位本就是设施库、运行流属应用层。故这里自建一个**精简**循环（不做子 agent、技能、压缩档位、bash 工具）。
 *
 * <p>★★ **权限在这一层强制，且只能在这一层**：每次工具调用都经 {@link DecisionCallerFactory#execute} → AgentLib {@code
 * ToolCallAuthorizer}（判定链五段全走），上下文由 {@code callerFor} **每次现算**（世界变了范围就变）。 直接 {@code
 * tool.execute(ctx)} 是错的——资源判定者只在 authorizer 的第 ③ 段注入。
 *
 * <p>★ **会话 id 由决策人 id 派生**（{@link #conversationIdOf}），**不隐式取全局状态**：同一决策人跨 tick（乃至跨进程重启， 因为 {@code
 * SqliteConversationStore} 落在 {@code <store>} 下）落到同一段会话上。
 *
 * <p>★★ **但"同一段"要按「世代」算**（{@link DecisionMaker#conversationGeneration()}）：{@code
 * sd.ResetDecisionMakerConversation} 把世代 +1 ⇒ 下一个轮次落到**另一段**新会话上、**从空上下文重新开始**，而旧会话
 * **一条字节都不动**（会话库是 append-only，可审计）。世代 0 的 id 与旧格式**逐字相同**，故现场已落盘的老会话接得上。
 *
 * <p>★★ **空会话先注入一条身份消息**（{@link #openingSystemMessage}，T11D）：{@code load} 对从未写过的会话返回空表 ⇒ 不注入的话首轮请求的
 * {@code messages} 是空的，真供应商直接 400（现场实测），且模型**不知道自己是哪一支决策人**。 注入的消息**同时落盘**（ {@code
 * append}），故重启之后它还在、且**不会第二轮再注入一次**。
 *
 * <p>★ **每一条消息都落盘**（不只是有工具调用的那一轮）：非工具轮（模型给的纯文本）也是这一轮的实际产出， 不记会让下一 tick
 * 的上下文缺一段自相矛盾的空白。落盘在**请求之前**不成立、在**响应之后**才成立——所以先 append 再进下一轮。
 *
 * <p>★ **回合预算**（{@link #DEFAULT_MAX_LLM_CALLS}）：spec 只写了 {@code while(true)}，但模型可以无限请求工具， 而 {@code
 * run} 没有别的出口 ⇒ 本实现给一个**兜底上限**、超限**响亮失败**（不是静默截断——静默截断会产出一个"看起来正常但没做决策" 的回合）。历史已逐条落盘，故中止不丢上下文。
 *
 * <p>★★ **工具名要过线格式**（真 LLM 实测缺陷的修法，2026-09-22）：Simos 的工具名形如 {@code simos.map.hex}，含 {@code .}，而
 * OpenAI 兼容端点只收 {@code ^[a-zA-Z0-9_-]+$} ⇒ 整份请求被拒（{@code HTTP 400}，{@code llmCalls=0}）。故本类在**装配期**
 * 用 {@link LlmToolNames} 建一张双向名表：送出去的是转义名（{@code simos_map_hex}），模型回来的名字**映射回真实名**再进权限链。
 * 碰撞与非法转义都在装配期**响亮失败**（绝不静默挑一个——猜错就是执行了另一个工具）。
 *
 * <p>★★ **P4（2026-09-24）：工具结果里的图真的发给模型**。在此之前 {@link #execute} 只把工具结果的**文本**折成回灌消息， {@code
 * ToolResult.assetDocIds()}（渲染出来的 PNG 引用）**被整个丢掉** ⇒ 决策人永远看不见图。现在：
 *
 * <ul>
 *   <li>有图且**该 provider 有视觉能力**（{@link DecisionAgentRunner} 的 {@code vision}，来自路由配置）⇒ 除 tool 消息外
 *       **另发一条 {@code user} 图片消息**（★ tool 角色带图是 AgentLib 的响亮 CONFIG 错，故必须另起一条）；
 *   <li>没有视觉能力 ⇒ **一张都不发**（发过去只会换一个 400；文本摘要本来就在 tool 消息里，且渲染工具的 {@code format=auto}
 *       会按同一个能力位回落成字符图，见 {@link MapRenderTool#VISION_CONFIG_KEY}）；
 *   <li>**开场快照**（{@link OpeningSnapshot}，装配方按开关给）：空会话时在身份消息之后补一条图片消息，让决策人"先看一眼世界"。
 * </ul>
 *
 * <p>★ **图片是引用式的**（{@link ContentPart.Image} 只带 mediaType + assetId，字节在工件库里）：故这条消息落进会话库之后 **跨 tick
 * / 跨重启都能原样回放**，而请求体里的大小由发送那一刻的解析决定。
 */
public final class DecisionAgentRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionAgentRunner.class);

  /** 会话 id 前缀（与 {@link DecisionCallerFactory#INSTANCE_ID_PREFIX} 同源：都按决策人派生）。 */
  public static final String CONVERSATION_ID_PREFIX = DecisionCallerFactory.INSTANCE_ID_PREFIX;

  /**
   * **开场快照**（P4）：给某个决策人产出一条"世界长这样"的图片消息（空会话首轮注入）。
   *
   * <p>★★ **为什么由装配方给、不由本类自己渲染**：取景中心与图层取决于领域知识（国家 → 首府区域 → 取景格），渲染要走 {@code RenderService}
   * 与查询面——那都是 app 层的既有件，塞进"循环"里会让本类同时认识地图、社交、单位与渲染（它现在的全部世界知识 仅限 {@code SimulationState} 这个不透明句柄）。
   *
   * <p>★★ **"关"只能用 {@link #NONE} 表达**（引用比较，见 {@link #openingSnapshotMessage}）：若装配方自己写一个等价的
   * lambda，本类就分不出"开关关着"与"开着但这次没出图"，而那两件事**该不该留下痕迹是不同的**（后者要说明为什么没出图）。
   */
  @FunctionalInterface
  public interface OpeningSnapshot {

    /** 什么都不给（默认 = 开关关着）。 */
    OpeningSnapshot NONE = (dm, state) -> Optional.empty();

    /**
     * @param dm 这一轮的决策人（取景通常要看它的归属：国家 → 首府区域）
     * @param state 此刻的世界（**入参、不是字段**：快照必须画的是"这一轮看到的世界"）
     * @return 图片消息（{@code user} 角色，含 {@link ContentPart.Image}）；没有可给的就空
     */
    Optional<LlmMessage> forDecisionMaker(DecisionMaker dm, SimulationState state);
  }

  /**
   * 一轮 {@code run} 允许的最大 LLM 调用次数（**本实现自设的兜底**，spec 未规定）。
   *
   * <p>★★ **取 20，且这个数是从现场算术来的、不是拍的**（真 LLM 验收，2026-09-22，35 条会话在案）。曾经取 8，**实测不够**： 决策人 {@code
   * dm-osman} 在**最小世界**（1 国 / 1 区域 / **573 格** / 1 单位）上，**7 次勘察 + 1 次出令**就把 8 用满； 出令因 {@code
   * sd.RegisterEffect} 自指被正确拒绝后，它**正要重读状态再试**时预算耗尽 ⇒ {@code abortedByBudget} （设计如此、不静默截断，这点是对的）。⇒
   * **8 是"最小世界的勘察成本本身"**，连一次重试都装不下。
   *
   * <p>★ **算术**（每一项都取上界，逐项可回溯到现场）：
   *
   * <ol>
   *   <li><b>勘察 14</b> = 实测 <b>7</b> × 2：实测那 7 次是**新会话首轮**"把整个工具面摸一遍"的量（catalog / branches /
   *       overview / unit.list / unit.get / map.hex / facets / …），而且那是**最小世界**的量——世界一大只会更多； 2
   *       倍余量买的是"多查几个格、多问几个单位"；
   *   <li><b>出令 1</b>；
   *   <li><b>重试 4</b> = 2 轮 × (重读状态 + 重发令)：出令有两种**已知**的被拒理由（{@code commands} 自指 / {@code
   *       expectedRevision} 过期——后者本类的身份消息自己就警告了），各留一轮；
   *   <li><b>收口 1</b>：工具轮之后模型还要一次纯文本轮才算完（既有用例实测的就是这个形态：两次工具 + 一次收口 = 3 次调用）。
   * </ol>
   *
   * <p>★ 合计 {@code 14 + 1 + 4 + 1 = 20}。**下界是 11**（现场那次撞墙的序列长度），20 给的是两倍于勘察的余量与两轮重试。
   *
   * <p>★★ **为什么不做成可配**（评估过，决定不做）：这个数的正确取值由**模型行为**决定，**不由部署决定**——把它挂进 {@code ShellConfig} 要让 {@code
   * ShellConfig → Shell → DecisionAgentService → 本类} 四层各加一个形参，
   * 换来的只是"部署期能调一个**我们不希望有人乱调**的兜底值"（调小了现场那种跑法必挂、调大了等于取消兜底）。
   * 需要非常规取值时，装配方本来就能用下面那个**显式给上限**的构造器（用例正是这么测"跑飞会被中止"的）； 故**不新增装配复杂度**。
   */
  public static final int DEFAULT_MAX_LLM_CALLS = 20;

  /**
   * **一轮的进度回调**（2026-09-23，GUI 准实时进度）：**每轮 LLM 结束**、**每次工具调用结束**各推一次。
   *
   * <p>★★ **默认 no-op ⇒ MCP 路径零影响**：GM 经 MCP 调 {@code sd.RunDecision} 那条是**同步**的（调用方等到整轮结束才拿到
   * 轨迹），它不需要进度；GUI 那条改成了**异步 + 轮询**，进度就是从这里出去的。两条路的区别只有"装不装监听"。
   *
   * <p>★ **只在两个真实的观察点推**（不另设节流/心跳）：模型的每一次响应、工具的每一次执行——它们就是这一轮**仅有**的 事件。界面上的"已 Ns"由渲染方按 {@code
   * startedAt} 自己算，本类不发定时事件（发了就会有"零进度的心跳"这种噪声）。
   *
   * <p>★ **回调在调用方线程上同步跑**（就是跑这一轮的那条线程）：实现方**不得**阻塞（GUI 那个只写内存）。
   */
  @FunctionalInterface
  public interface ProgressListener {

    /** 什么都不做的实现（默认）。 */
    ProgressListener NONE = (llmCalls, toolInvocations) -> {};

    /**
     * @param llmCalls 到目前为止**已完成**的 LLM 调用次数（≥ 1）
     * @param toolInvocations 到目前为止**真的执行过**的工具调用（按发生序，末条 = 刚刚那一次）
     */
    void progress(int llmCalls, List<ToolInvocation> toolInvocations);
  }

  private final DecisionCallerFactory callerFactory;
  private final ToolRegistry registry;
  private final LlmClient llmClient;
  private final ConversationStore conversations;
  private final String mapId;
  private final int maxLlmCalls;

  /** 进度回调（默认 {@link ProgressListener#NONE}；见其类注：MCP 那条同步路不装，行为逐字不变）。 */
  private final ProgressListener progressListener;

  /** **该 provider 有没有视觉能力**（P4，见类注）：决定工具结果里的图发不发、开场快照注不注。 */
  private final boolean vision;

  /** 开场快照（P4，见 {@link OpeningSnapshot}）：装配方按开关给；默认 {@link OpeningSnapshot#NONE}（关）。 */
  private final OpeningSnapshot openingSnapshot;

  /**
   * **本次调用透传给工具的宿主编排配置**（P4）：目前只有一项——有没有视觉能力。
   *
   * <p>★ 它与 {@link #vision} **同源、在构造期算一次**：两处各拼一次（一处判断发不发图、一处拼给工具）就会出现"图不发、
   * 工具却以为有视觉能力"（或反过来）这种无症状的错位。
   */
  private final Map<String, Object> toolConfig;

  /**
   * **本 runner 构建工具面所用的白名单**（P6a）：生产路径 = {@code callerFactory.whitelistFor(dm)}（per-DM）， 旧构造器 =
   * {@code callerFactory.whitelist()}（全局，兼容旧档/夹具）。
   *
   * <p>★ 它与执行期 {@code callerFactory.permissionsFor(dm)} 用的是同一份 {@code whitelistFor(dm)} ⇒ "模型能看到的"与
   * "权限组放行的"不会错位（spec §2.3 要点 1）。
   */
  private final Set<String> toolWhitelist;

  /** **送给模型的那一份工具面**（名字是线格式；描述与 schema 逐字来自真工具）。 */
  private final List<ToolDef> toolDefs;

  /** 线名 ↔ 真实名（装配期建好：碰撞或转义不合文法就**在这里**炸，而不是发一份坏请求出去换一个 400）。 */
  private final LlmToolNames toolNames;

  /**
   * @param callerFactory 决策人调用者工厂（范围**每次现算**；本条旧构造器仍用其 {@code whitelist()}——全局白名单， 兼容旧档/夹具；per-DM
   *     走显式白名单那条）
   * @param registry **决策人桶**的注册表（{@code Shell.toolsFor(Role.DECISION_AGENT)}）——工具面与执行都从它取
   * @param llmClient 模型客户端（生产路径是真 LLM；用例给 {@code FakeLlmClient}）
   * @param conversations 会话存储（{@code SqliteConversationStore} 落 {@code <store>} 下 ⇒ 跨 tick / 跨重启沿用）
   * @param mapId 本世界的 map 称谓（范围函数要它拼资源前缀）
   */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId) {
    this(callerFactory, registry, llmClient, conversations, mapId, DEFAULT_MAX_LLM_CALLS);
  }

  /**
   * ★★ **显式白名单**的便利形态（P6a 的接缝）：其余分量全走缺省（回合上限 {@link #DEFAULT_MAX_LLM_CALLS}、无监听、无视觉、
   * 不快照），只有"给模型看的工具面"用调用方给的白名单。
   *
   * <p>★ **旧构造器不受影响**：不带 {@code Set<String>} 的那几条仍在内部交 {@code callerFactory.whitelist()}（全局）。 生产
   * {@code DecisionAgentService} 走全参并把 {@code callerFactory.whitelistFor(dm)} 传进来。
   *
   * @param toolWhitelist 本 runner 的工具面白名单（**非空校验**；空集合 = 给模型一个空工具面，不是"回落到全局"——回落由 构造方决定，见 {@link
   *     DecisionCallerFactory#whitelistFor(DecisionMaker)}）
   */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      Set<String> toolWhitelist) {
    this(
        callerFactory,
        registry,
        llmClient,
        conversations,
        mapId,
        DEFAULT_MAX_LLM_CALLS,
        ProgressListener.NONE,
        false,
        OpeningSnapshot.NONE,
        toolWhitelist);
  }

  /** 显式给回合上限的形态（用例要测"跑飞会被中止"就得把它压小）。 */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls) {
    this(
        callerFactory,
        registry,
        llmClient,
        conversations,
        mapId,
        maxLlmCalls,
        ProgressListener.NONE);
  }

  /**
   * **带进度回调**的形态（GUI 异步跑的装配点）：除多一个监听外与上面那条**逐字同形**（同一个构造器链， 不复制字段赋值——复制一份就会出现"两条路装配出两个略有差异的
   * runner"这种无症状的漂移）。
   *
   * <p>★ 这条走**无视觉能力、不开快照**的缺省（既有调用点的行为逐字不变）：要图片通路请走全参那条。
   */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls,
      ProgressListener progressListener) {
    this(
        callerFactory,
        registry,
        llmClient,
        conversations,
        mapId,
        maxLlmCalls,
        progressListener,
        false,
        OpeningSnapshot.NONE);
  }

  /**
   * **全参（旧调用点兼容形态）**（P4 生产路径）：另给"该 provider 有没有视觉能力"与开场快照来源。
   *
   * <p>★ 这条**不带**显式白名单 ⇒ 交 {@code callerFactory.whitelist()}（全局白名单），逐字保留 P6a 之前的旧行为。 per-DM 的
   * {@code allowedTools} 走下面那条十参重载。
   *
   * @param vision 该 provider 的模型有没有视觉能力（{@code
   *     LlmRouteLoader.capabilities(...).vision()}）：它**同时**决定 "工具结果里的图发不发"与"渲染工具的 {@code auto}
   *     落在图还是字符图"（两处同源，见 {@link #toolConfig}）
   * @param openingSnapshot 空会话首轮要不要补一张世界图（装配方按开关给；{@link OpeningSnapshot#NONE} = 关）
   */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot) {
    this(
        callerFactory,
        registry,
        llmClient,
        conversations,
        mapId,
        maxLlmCalls,
        progressListener,
        vision,
        openingSnapshot,
        globalWhitelistOf(callerFactory));
  }

  /**
   * ★★ **全参 + 显式工具白名单**（P6a 生产路径）：给模型看的工具面用调用方给的白名单构建（生产 = {@code
   * callerFactory.whitelistFor(maker)}）。
   *
   * <p>★ **"用哪份白名单建 face，就检查哪份白名单"**：{@link DecisionToolDefs#requireAll} 拿到的就是这里的 {@code
   * toolWhitelist} ⇒ 它里面每一条都必须在注册表里，缺一条**装配期当场炸**（per-DM 也 fail-closed，不静默少能力、 也不静默兜底到全局）。★
   * 落在全局白名单之外的 {@code allowedTools} 条目不会出现在这里（交集在 {@link DecisionCallerFactory#whitelistFor}
   * 里完成），故"未知项"只可能表现为**能力收窄**，不可能表现为提权。
   *
   * <p>★ 其余参数语义与上面那条旧全参**逐字相同**（同一个构造器体，不复制字段赋值）。
   *
   * @param toolWhitelist 本 runner 的工具面白名单（**非空引用校验**；空集合 = 空工具面，不是"回落到全局"——回落由构造方 决定，生产走 {@code
   *     whitelistFor} 的空名单兼容语义）
   */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot,
      Set<String> toolWhitelist) {
    this.callerFactory = Objects.requireNonNull(callerFactory, "callerFactory");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    if (maxLlmCalls <= 0) {
      throw new IllegalArgumentException("maxLlmCalls 必须为正: " + maxLlmCalls);
    }
    this.maxLlmCalls = maxLlmCalls;
    this.progressListener = Objects.requireNonNull(progressListener, "progressListener");
    this.vision = vision;
    this.openingSnapshot = Objects.requireNonNull(openingSnapshot, "openingSnapshot");
    this.toolConfig = Map.of(MapRenderTool.VISION_CONFIG_KEY, vision);
    this.toolWhitelist = Set.copyOf(Objects.requireNonNull(toolWhitelist, "toolWhitelist"));
    // ★★ 工具面在**装配期**建一次（不随世界变：它只取决于注册表与白名单），并当场建好名字映射：
    //   ① 白名单里有工具不在注册表 ⇒ requireAll 抛（旧行为，只是提前到构造期；per-DM 白名单同样适用）；
    //   ② 转义碰撞 / 转义结果不合供应商文法 ⇒ LlmToolNames 抛（**真 LLM 实测缺陷**的护栏，2026-09-22）。
    //   两条都是装配故障，都不该等到"模型第一轮已经花掉"才发现。
    List<ToolDef> face = DecisionToolDefs.requireAll(registry, this.toolWhitelist);
    this.toolNames = LlmToolNames.of(face.stream().map(ToolDef::name).toList());
    this.toolDefs = toolNames.wireDefs(face);
  }

  /**
   * **缺省全局白名单的取值点**（旧构造器专用）：显式保留 {@code callerFactory} 的 null 校验语义，同时让旧构造器与 P6a 之前逐字同源（{@code
   * callerFactory.whitelist()}）。
   */
  private static Set<String> globalWhitelistOf(DecisionCallerFactory callerFactory) {
    return Objects.requireNonNull(callerFactory, "callerFactory").whitelist();
  }

  /**
   * **世代 > 0 那一族的会话 id 前缀**（{@code "decision-maker#"}）。
   *
   * <p>★★ **为什么世代不是拼在 id 后面**（{@code "decision-maker:" + id + "#" + gen}，那样读起来更顺）：那种写法**会撞车**
   * ——{@code DecisionMakerId} 接受任意非空字符串，于是 id 叫 {@code a#1} 的决策人（世代 0）与 id 叫 {@code a} 的（世代
   * 1）派生出**同一个** id，两个人共用一段会话，**而且不会有任何报错**。两个前缀在**第一个字符上就不同**（冒号 vs 井号）⇒ 对**任意** id 都不会撞，不需要给 id
   * 加任何字符集限制（加限制反而会让老档里那些"带井号的 id"读不回来）。
   */
  public static final String GENERATION_CONVERSATION_ID_PREFIX = "decision-maker#";

  /**
   * **会话 id 的唯一拼写点**（生产路径只走这个重载）：由**世界事实**（{@link DecisionMaker#id()} + {@link
   * DecisionMaker#conversationGeneration()}）**纯函数**派生。
   *
   * <p>★★ **世代 0 必须与旧格式逐字相同**（{@code "decision-maker:" + id}）：现场已经落盘的老会话（{@code
   * decision-maker:dm-osman} 之类）就是这个写法，换一个写法 = **全部接不上**，症状是"决策人失忆"，不是一条报错。 世代 > 0 才换到 {@link
   * #GENERATION_CONVERSATION_ID_PREFIX} 那一族。
   *
   * <p>★ **入参是整个 {@code DecisionMaker}、不是一个 id 字符串**：这样"用哪个世代"不可能由调用方随手给错——它是**世界事实** 的一部分（{@code
   * sd.ResetDecisionMakerConversation} 每次 +1）。传入 id + 世代的重载只是**格式原语**（用例与派生判据用）， 生产路径不该用它。
   */
  public static String conversationIdOf(DecisionMaker dm) {
    Objects.requireNonNull(dm, "dm");
    return conversationIdOf(dm.id(), dm.conversationGeneration());
  }

  /**
   * 会话 id 的**格式原语**：{@code 世代 0 ⇒ "decision-maker:" + id}；{@code 世代 > 0 ⇒ "decision-maker#" + 世代 +
   * ":" + id}。
   *
   * <p>★ 生产路径一律走 {@link #conversationIdOf(DecisionMaker)}（世代从世界事实里取，不由调用方给）。
   */
  public static String conversationIdOf(
      DecisionMakerId decisionMakerId, long conversationGeneration) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    if (conversationGeneration < 0) {
      throw new IllegalArgumentException(
          "conversationGeneration 必须 ≥ 0: " + conversationGeneration);
    }
    if (conversationGeneration == 0) {
      // ★ 旧格式：一个字都不能变（见上面那条硬要求）。
      return CONVERSATION_ID_PREFIX + decisionMakerId.value();
    }
    return GENERATION_CONVERSATION_ID_PREFIX
        + conversationGeneration
        + ":"
        + decisionMakerId.value();
  }

  /**
   * ★★ **空会话的首条消息：身份 + 任务**（T11D，真 LLM 实测缺陷的修法）。
   *
   * <p>★ **缺陷现场**（不是推断）：{@code conversations.load} 对一个从未写过的会话返回**空表** ⇒ 首轮请求的 {@code messages} 是空的
   * ⇒ 真供应商直接拒（{@code HTTP 400: field messages is required}，{@code llmCalls=0}，
   * **一次都没成**）。真模型还因此**不知道自己是哪一国/哪一支军队的决策人**——"我是谁"从来没有任何一条消息说过。
   *
   * <p>★★ **只写不变量，绝不写会漂的值**：这条消息**永久落盘**（写进去之后每一轮都从会话里原样取回、再也不会更新） ⇒ 里面只能有"不随世界变化"的东西（身份、工具用法）。一旦把
   * head / revision / tick 这类会漂的值拼进去，模型此后 **每一轮读到的都是过期值**，而且没有任何症状（本类的用例 {@code
   * anEmptyConversationGetsTheIdentityMessageBeforeTheFirstRequest} 用"正文里一个数字都没有"把这条钉死）。
   *
   * <p>★ **身份只取 {@link DecisionMaker} 自己的字段**（id + 归属），**不查世界**：查世界会让这段文本成为世界状态的函数，
   * 而它是要被永久钉在会话里的（且世界里的归属名字还会改名）。
   *
   * <p>★ **工具名取自各工具的 {@code NAME} 常量**（不是手抄的字面量）：工具改名时这条消息跟着走，"说的"与"注册的"不会错位。
   *
   * <p>★★ **Skill 那一句是"指针"不是"内容"**（2026-09-23）：技能库是**外部文件、改了即生效**（见 {@code SkillLibrary}），
   * 若把正文钉进这条永久消息，模型此后每一轮读到的都是**改之前**的版本，而且没有任何症状。故这里只说"用哪个工具去读、要读就读当下这一版"。 ★ **代价要明说**：opening
   * 只在**空会话**注入 ⇒ 这条新指针**已落盘的老会话看不到**（它们仍能从工具列表里看到该工具）。 要让老会话也带上它，走 {@code
   * sd.ResetDecisionMakerConversation} **换代重开**（生成一个新会话，opening 重新注入）。
   *
   * <p>★★ **必须交代「出令的 {@code commands} 不得含 {@code sd.*}」**（真 LLM 现场实测缺陷的修法，2026-09-22）：模型勘察完
   * **第一次出令就用了 {@code sd.RegisterEffect}**——那是 sd 自指，被 {@code DirectiveWhitelist} **正确拒绝**（防无限递归），
   * 而它此前**无从得知**这条规则（本条消息只说"出令用哪个工具、载荷字段问谁"）。代价是**白烧一轮**，且那一轮预算已见底。 ★
   * 这条规则**写在消息里而不是靠模型去试**：试错的代价是**一次真 LLM 调用**，而防递归规则是**确定不会变**的硬约束（"猜"没有意义）。 ★
   * 措辞**只点出规则的两半**（受管对象是 {@code commands}、被禁的是 {@code sd.} 这个**前缀**），**不枚举任何类型清单**
   * ——清单会漂（命令族增删），前缀不会；具体可用类型一律指向 catalog 那个工具。
   *
   * <p>★★ **且必须按线格式转义**（{@link LlmToolNames#wireNameOf}）：这条消息是**写给模型看的**，而模型眼里的工具名 {@code
   * simos_command_catalog} 不是 {@code simos.command.catalog}（真 LLM 实测缺陷的修法，2026-09-22）。说了真名等于
   * 指示模型去叫一个它看不见的名字——最好的下场是白费一轮（{@code TOOL_NOT_FOUND}），最坏是它照做之后对工具面失去信任。
   * 转义点仍是**唯一**的（同一个函数），故"说的"与"发给模型的"永远同一个写法。
   */
  static LlmMessage openingSystemMessage(DecisionMaker dm) {
    Objects.requireNonNull(dm, "dm");
    Affiliation affiliation = dm.affiliation();
    String where;
    if (affiliation instanceof Affiliation.Nation nation) {
      where = "国家（nationId=" + nation.nationId().value() + "）";
    } else if (affiliation instanceof Affiliation.Army army) {
      where = "军队（armyId=" + army.armyId().value() + "）";
    } else if (affiliation instanceof Affiliation.Gov gov) {
      where = "政府（govUnit=" + gov.govUnit().value() + "）";
    } else {
      // 封闭类型（sealed）不会走到这里；留一句响亮的话，好过静默给一个错的身份。
      throw new IllegalStateException("未知的归属类型: " + affiliation.getClass().getName());
    }
    // ★ 这三个名字是**给模型看的**，故一律走线格式（真实名只活在本方法内部，见类注）。
    String catalog = LlmToolNames.wireNameOf(CatalogTool.NAME);
    String branches = LlmToolNames.wireNameOf(BranchListTool.NAME);
    return LlmMessage.system(
        "你是决策人「"
            + dm.id().value()
            + "」，归属："
            + where
            + "。\n"
            + "\n"
            + "【你能看到什么】由系统强制：每次工具调用都由系统按你的归属与当前世界**现算**可见范围，范围之外的调用会被拒——"
            + "那不是你参数写错了，是那件事你看不到。所以不要假设、也不要猜测自己看不见的东西：先查看，再决策。\n"
            + "\n"
            + "【先查看】"
            + catalog
            + " 给出**你有途径触发的**命令类型及其载荷字段（范围之外的不会列出来，所以看不到 = 你不用想它）；"
            + branches
            + " 给出分支与各自的当前 head。\n"
            + "\n"
            + "【怎么做决策】"
            + LlmToolNames.wireNameOf(SkillTool.NAME)
            + " 是一份**决策方法论与常识**（政治/经济/军事的判断口径与常见误区）——不带参数先看目录，"
            + "再按需读你要的那一两篇。★ 它是别人维护的文本，可能已经改过；**别凭记忆**，要读就读当下这一版。\n"
            + "\n"
            + "【再决策】出令用 "
            + LlmToolNames.wireNameOf(IssueDirectiveTool.NAME)
            + "（载荷字段见 "
            + catalog
            + "）：它的 expectedRevision 必须填 "
            + branches
            + " 读到的**当前 head**，不要凭记忆、也不要沿用上一轮的旧值——填错会被拒。裁决用 "
            + LlmToolNames.wireNameOf(SubmitVerdictTool.NAME)
            + "。\n"
            + "\n"
            + "【令里的 tick 是哪一个】那个字段记的是**你出令的这一刻**（世界当前所在的 tick），"
            + "不是「这条令针对哪一刻」。**想推演将来、想把「以后某时要怎么做」写下来，那是正当的**——"
            + "写进 intentInfo（决心与理由）里，别塞进 tick。反过来，tick 填**大于当前**的值会被系统拒绝"
            + "（时间线只追加，记错的令改不回来），而当前 tick 一律**从工具读到的世界状态里取**，"
            + "不要凭记忆、也不要沿用上一轮的值。\n"
            + "\n"
            + "【出令的 commands 放什么】只放**领域命令**（改地图、动单位这一类），可用类型与载荷字段见 "
            + catalog
            + "。★ **以 sd. 开头的命令类型一律会被拒**——那是防无限自指（令不得再生成令，否则会一直递归下去），"
            + "所以别让令去注册效果、下指令或改动推演自身的状态；第一次出令就撞上这条，纯属白烧一轮。");
  }

  /**
   * **跑一轮**：LLM ↔ 工具，直到模型不再请求工具调用（或撞上回合预算）。
   *
   * <p>★ **状态是入参、不是字段**：范围每次现算要的是"此刻的世界"，把它挂在字段上就会变成"这个 runner 绑在某个时刻上"。
   *
   * @param dm 这一轮的决策人（会话 id 由它派生）
   * @param state 此刻的世界状态（范围函数与工具读的都是它）
   * @return 本轮的账（调了几次模型、真的执行了哪些工具、最后一句文本）
   * @throws TurnBudgetExceeded 撞上回合预算（{@link IllegalStateException} 的子类，便于调用方**按类型**报"是否因预算中止"）
   */
  public DecisionTurn run(DecisionMaker dm, SimulationState state) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    String conversationId = conversationIdOf(dm);
    // ★ 工具面与权限组**同一份数据**（spec §2.3 要点 1）：两者若各有一张表，错位不会有任何症状。
    //   （那份面在构造期已建好、名字已转义成线格式——见构造器与 LlmToolNames 的类注。）
    List<LlmMessage> history = new ArrayList<>(conversations.load(conversationId));
    // ★★ 空会话 ⇒ 先注入身份（**并落盘**），再发第一个请求：不注入的话首轮请求的 messages 是空的，真供应商
    //   直接 400（`field messages is required`），一次都跑不成（现场实测）。落盘是这件事的要害——只往内存里塞
    //   的"修法"在**换进程/换 store 实例**之后又回到空会话，缺陷原样复现（见 openingSystemMessage 的类注）。
    //   ★ 只在**空会话**上注入（"有没有历史"是纯函数，不看世界）：第二轮 `load` 里已经有它，故不会重复追加。
    if (history.isEmpty()) {
      LlmMessage opening = openingSystemMessage(dm);
      conversations.append(conversationId, opening);
      history.add(opening);
      // ★★ P4：身份之后补一条**开场快照**（世界长这样）。★ 顺序不可反——先有"你是谁"，再有"你在哪"；
      //   而且这条只在**空会话**注入一次（与身份同一条判据），第二轮起就在历史里了。
      Optional<LlmMessage> snapshot = openingSnapshotMessage(dm, state);
      if (snapshot.isPresent()) {
        conversations.append(conversationId, snapshot.get());
        history.add(snapshot.get());
      }
    }
    List<ToolInvocation> invocations = new ArrayList<>();
    int llmCalls = 0;
    while (true) {
      if (llmCalls >= maxLlmCalls) {
        throw new TurnBudgetExceeded(
            "决策人 "
                + dm.id().value()
                + " 在 "
                + maxLlmCalls
                + " 次 LLM 调用内仍持续请求工具（会话 "
                + conversationId
                + "）——疑似跑飞，本轮中止；历史已逐条落盘，下一 tick 可续",
            maxLlmCalls);
      }
      LlmResponse response = llmClient.chat(new LlmRequest(List.copyOf(history), toolDefs));
      llmCalls++;
      LlmMessage assistant = response.assistantMessage();
      // ★ 先落盘再判：模型说了什么都要记（非工具轮同样是这一轮的产出）。
      conversations.append(conversationId, assistant);
      history.add(assistant);
      // ★ 进度点 ①：本轮 LLM 已结束（工具调用还没跑）——GUI 的"第 N 轮"就是这个数。
      progressListener.progress(llmCalls, List.copyOf(invocations));
      List<ContentPart.ToolCall> requested = toolCallsOf(assistant);
      if (requested.isEmpty()) {
        return new DecisionTurn(conversationId, llmCalls, invocations, response.textPart());
      }
      List<LlmMessage> imageMessages = new ArrayList<>();
      for (ContentPart.ToolCall call : requested) {
        // ★ 一次工具调用产出两条消息：tool 消息（永远有）+ 图片消息（有图且有视觉能力时，P4）。
        ToolExecution execution = execute(dm, state, call, invocations);
        conversations.append(conversationId, execution.toolMessage());
        history.add(execution.toolMessage());
        execution.imageMessage().ifPresent(imageMessages::add);
        // ★ 进度点 ②：一次工具调用已结束（末条 = 刚跑完的那一个）——GUI 的"正在调 X / 调了什么"。
        progressListener.progress(llmCalls, List.copyOf(invocations));
      }
      // ★★ **图片消息一律排在本回合所有 tool 消息之后**（真网关实测的硬约束，2026-09-24）：
      //   OpenAI 兼容线要求"assistant(tool_calls) 后面必须紧跟回应**每一个** tool_call_id 的 tool 消息"，
      //   中间插一条 user 就是 `HTTP 400: An assistant message with 'tool_calls' must be followed by tool
      //   messages responding to each 'tool_call_id'`。★ 一个回合里模型**一次要多个工具**是常态
      //   （实测那轮一次要了 5 个：map_render + map_hex×4），所以"图紧跟它那条 tool 消息"是错的形态，
      //   而**回放式的假客户端看不见这个约束**（它不校验协议）——只有真网关会拒。
      for (LlmMessage imageMessage : imageMessages) {
        conversations.append(conversationId, imageMessage);
        history.add(imageMessage);
      }
    }
  }

  /**
   * 执行**一次**模型请求的工具调用：落 tool 消息、记账，并（有图且有视觉能力时）另备一条图片消息。
   *
   * <p>★ **失败也回灌**（{@code ToolResult.error} 的码与文本原样进 {@code error}/{@code content}，{@code
   * isError()} 为真）：AgentLib 特意把 {@code RESOURCE_DENIED} / {@code APPROVAL_DENIED}
   * 与别的失败分开，就是为了让模型**知道该换个 资源还是换个参数**——把它折成一句"调用失败"是把这个意图丢掉（T10 实测发现 2 的同一条道理）。
   *
   * <p>★★ **为什么图片是"另备"而不是"紧跟着发"**（P4，真网关实测的硬约束）：{@code tool} 角色的 {@code content} 只接受字符串（带图是
   * AgentLib 的响亮 CONFIG 错），故图必须另起一条 {@code user} 消息；但**它的位置由 {@code run} 定**—— 必须等本回合**所有** tool
   * 消息落完再发，否则真网关直接 400（见 {@code run} 里那段注释）。
   */
  private ToolExecution execute(
      DecisionMaker dm,
      SimulationState state,
      ContentPart.ToolCall call,
      List<ToolInvocation> invocations) {
    // ★★ **线名 ⇒ 真实名**（`simos_map_hex` → `simos.map.hex`）：权限链、注册表、工具自身**只认真实名**，本层是唯一的翻译点。
    //   ★ **查不到就原样交下去**（不新造"这个名字不在表里"的拒绝理由）：于是 AgentLib 的两条既有判据逐字保留——
    //   真没这个工具 ⇒ `TOOL_NOT_FOUND`（正文是「工具不存在: <模型给的名字>」，模型据此看得见自己叫错了名）；
    //   工具真在注册表里、只是权限组不放它 ⇒ `PERMISSION_DENIED`。本层若抢先拒，后者就再也测不到了（工具面 = 白名单，
    //   凡是能过名字表的都在白名单里），等于用一个更弱的理由顶掉一个更强的判据。
    String toolName = toolNames.realNameOf(call.name()).orElse(call.name());
    // ★ 唯一入口：callerFor **每次现算**（世界变了范围就变），执行走 authorizer 的五段判定链。
    //   ★ toolConfig 是本轮的宿主编排（目前只有"有没有视觉能力"一项）：渲染工具的 auto 靠它选形态。
    ToolResult result =
        callerFactory.execute(registry, toolName, dm, state, mapId, call.arguments(), toolConfig);
    // ★★ **同一段文本两处用**（T11C）：既回灌给模型，也记进本轮的账（{@code resultSummary}）——
    //   `sd.RunDecision` 的轨迹就是靠它报告"决策人看见了什么"。两处若各拼一次，轨迹与实际回灌的
    //   内容就会**各说各话**，而没有任何症状。
    String feedback = feedbackText(result);
    invocations.add(
        new ToolInvocation(call.id(), toolName, result.success(), result.code(), feedback));
    // ★ ToolResult 的**两个字段互斥且必居其一**（ContentPart.ToolResult 构造期强制：content 与 error
    //   **恰好一个非 null**）⇒ 成功走 content，失败走 error，且失败时把**码**一起带上——模型据此才知道
    //   该换个资源（RESOURCE_DENIED）还是换个参数（BAD_REQUEST）。
    //   ★ 这里的 name 一律回**模型自己给的那个写法**（`call.name()`），不回真实名：这段 transcript 要说的是
    //   "模型说了什么、我们回了什么"，两边用同一个拼写才不会自相矛盾（且 AgentLib 不发这个字段，见 appendMessage）。
    LlmMessage toolMessage =
        LlmMessage.tool(
            result.success()
                ? new ContentPart.ToolResult(call.id(), call.name(), feedback, null)
                : new ContentPart.ToolResult(call.id(), call.name(), null, feedback));
    return new ToolExecution(toolMessage, imagesMessage(result));
  }

  /**
   * 一次工具调用的产物：**回灌的 tool 消息**（永远有）+ **另备的图片消息**（可选）。
   *
   * <p>★ 图片消息的位置由 {@link #run} 决定（必须排在本回合所有 tool 消息之后），故这里只是"备好"，不负责落盘顺序。
   */
  private record ToolExecution(LlmMessage toolMessage, Optional<LlmMessage> imageMessage) {}

  /**
   * 工具结果里的**图片资产** ⇒ 一条 {@code user} 图片消息（P4）。
   *
   * <p>★★ **两条硬约束都在这里兑现**：① 图片**不能**附在 tool 消息上（AgentLib 的 {@code appendMessage} 对 {@code tool}
   * 角色带图**响亮抛 CONFIG**——它不静默丢图，所以我们也不能指望它替我们兜底）；② 没有视觉能力时**一张都不发**—— 发过去只会从供应商换一个 400，而工具结果的文本摘要在
   * tool 消息里本来就有（渲染工具还会按同一个能力位回落成字符图， 见 {@code MapRenderTool.VISION_CONFIG_KEY}）。
   *
   * <p>★ **媒体类型取 {@code ArtifactStore.PNG_MEDIA_TYPE}**：工件库当前只存 PNG（其类注写明"扩展时把类型写进 id/清单"）⇒
   * 与"本库只存一种"这个既有事实同源，不在这里另立一张 id→类型 的表。
   */
  private Optional<LlmMessage> imagesMessage(ToolResult result) {
    List<String> assetIds = result.assetDocIds();
    if (!vision || assetIds.isEmpty()) {
      return Optional.empty();
    }
    List<ContentPart> parts = new ArrayList<>(assetIds.size() + 1);
    parts.add(new ContentPart.Text("（下面是上一次工具结果里的图片）"));
    for (String assetId : assetIds) {
      parts.add(new ContentPart.Image(ArtifactStore.PNG_MEDIA_TYPE, assetId));
    }
    return Optional.of(new LlmMessage(LlmMessage.ROLE_USER, parts));
  }

  /**
   * 空会话首轮的**开场快照**（P4）：装配方按开关给（{@link OpeningSnapshot}）。
   *
   * <p>★ **"开关关着"与"开着但没给图"要分得开**：前者一个字都不留，后者说明原因（路由没有视觉能力）——否则运维打开开关却什么都没发生， 而日志里没有任何一行能解释为什么。这也是
   * {@link OpeningSnapshot#NONE} 必须是**同一个常量实例**（引用比较）的原因。
   *
   * <p>★ **注在身份消息之后**：顺序恒为 {@code system → user…}（本类的既有不变式，见 {@code run} 的注释）。
   */
  private Optional<LlmMessage> openingSnapshotMessage(DecisionMaker dm, SimulationState state) {
    if (openingSnapshot == OpeningSnapshot.NONE) {
      return Optional.empty();
    }
    if (!vision) {
      LOG.warn("开场快照开关已开，但决策人 {} 绑的路由没有视觉能力 ⇒ 本轮跳过（图发过去只会换来一个 400）", dm.id().value());
      return Optional.empty();
    }
    return openingSnapshot.forDecisionMaker(dm, state);
  }

  /**
   * 回灌给模型的那段文本（成功 ⇒ 正文；失败 ⇒ {@code <码>: <正文>}）：**唯一拼写点**，见 {@link #execute} 的注释。
   *
   * <p>★ 失败也回灌**码**：AgentLib 特意把 {@code RESOURCE_DENIED} / {@code APPROVAL_DENIED} 与别的失败分开，就是为了让模型
   * **知道该换个资源还是换个参数**。
   */
  private static String feedbackText(ToolResult result) {
    return result.success() ? result.message() : result.code() + ": " + result.message();
  }

  /** 从一条 assistant 消息里取出模型请求的工具调用（AgentLib 的 {@code LlmResponse} 没有现成的取法）。 */
  private static List<ContentPart.ToolCall> toolCallsOf(LlmMessage assistant) {
    List<ContentPart.ToolCall> calls = new ArrayList<>();
    for (ContentPart part : assistant.content()) {
      if (part instanceof ContentPart.ToolCall call) {
        calls.add(call);
      }
    }
    return calls;
  }

  /**
   * 一次工具调用的事实。
   *
   * @param toolName **平台身份**：过得了名字表就是**真实工具名**（{@code simos.map.hex}），过不了就是**模型给的那个写法**。 ★
   *     为什么不记线名：这份账是给 GM / AAR 看的（"决策人调了什么"），而线名是供应商方言的产物、在 catalog 与 GUI 里都不存在； ★
   *     为什么过不了表时记原话：那一笔的**事实**就是"模型叫了这个名字"，换掉它等于把"叫错了名"这件事抹掉。
   * @param resultSummary **回灌给模型的那段文本**（与 {@link LlmMessage#tool} 里那条 tool 消息**同源**，见 {@link
   *     #feedbackText}）。★ T11C 起它在账里：{@code sd.RunDecision} 把它当"决策人看见了什么"的载体交给调用方 ——
   *     在此之前本记录刻意**不带**输出（"输出在会话里"），但那条路对**工具调用者**不成立：它拿不到会话，只能拿到这个账。 ★ 原样保存、**不在此截断**（截断是渲染方的事，见
   *     {@code RunDecisionTool}）。
   */
  public record ToolInvocation(
      String toolCallId, String toolName, boolean success, String code, String resultSummary) {

    public ToolInvocation {
      Objects.requireNonNull(resultSummary, "resultSummary");
    }
  }

  /**
   * **撞上回合预算**（{@link #DEFAULT_MAX_LLM_CALLS}）时的可判别信号（T11C 新增）。
   *
   * <p>★ **为什么给一个类型而不是沿用裸 {@code IllegalStateException}**：{@code sd.RunDecision}
   * 要如实报"这一轮**是否因预算中止**"， 而在**消息文本**里找关键词是脆的（改一个字就静默失配，且本类里还有别的 {@code
   * IllegalStateException}——装配故障）。 子类仍然满足"响亮失败"的原判据（{@code IllegalStateException} 的 {@code
   * isInstanceOf} 断言照旧成立）。
   */
  public static final class TurnBudgetExceeded extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * 本轮实际用掉的 LLM 调用次数（= 撞上的那个上限）。
     *
     * <p>★ **不是 {@code transient}**（门禁实测）：本类是 {@link java.io.Serializable}（异常族的共同祖先使然）， {@code
     * transient} 会让 SpotBugs 判 {@code SE_TRANSIENT_FIELD_NOT_RESTORED}（"反序列化时这个字段不会被恢复"） ——
     * 对一个纯计数而言，让它随异常一起序列化才是正确形态，故**不加** {@code transient}。
     */
    private final int llmCalls;

    TurnBudgetExceeded(String message, int llmCalls) {
      super(message);
      this.llmCalls = llmCalls;
    }

    /** 本轮用掉的 LLM 调用次数（如实报给调用方的那一项）。 */
    public int llmCalls() {
      return llmCalls;
    }
  }

  /**
   * 一轮的账。
   *
   * @param conversationId 这一段会话的 id（跨 tick 的锚）
   * @param llmCalls 本轮向模型发起的调用次数（≥ 1）
   * @param toolInvocations **真的被执行过**的工具调用（按发生序；被权限拒的也在内，{@code success=false}）
   * @param finalText 收尾那条助手消息的文本（模型不停在工具调用上时的产出；不含文本 ⇒ 空）
   */
  public record DecisionTurn(
      String conversationId,
      int llmCalls,
      List<ToolInvocation> toolInvocations,
      Optional<String> finalText) {

    public DecisionTurn {
      toolInvocations = List.copyOf(toolInvocations);
      Objects.requireNonNull(finalText, "finalText");
    }

    /** 这一轮有没有用过工具（J11 的"不是被动收简报"就落在这一位上）。 */
    public boolean usedTools() {
      return !toolInvocations.isEmpty();
    }
  }
}
