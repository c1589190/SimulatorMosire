package io.mosire.simos.ga.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.llm.LlmToolNames;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.spi.DecisionMakerAffiliationPort;
import io.mosire.simos.ga.spi.GaSnapshots;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.tools.read.GaMessageInboxTool;
import io.mosire.simos.ga.tools.read.GmInboxTool;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <p>★★ **权限在这一层强制，且只能在这一层**：每次工具调用都经 {@link DecisionToolCaller#execute} → AgentLib {@code
 * ToolCallAuthorizer}（判定链五段全走），上下文由调用方的 {@code callerFor} **每次现算**（世界变了范围就变）。 直接 {@code
 * tool.execute(ctx)} 是错的——资源判定者只在 authorizer 的第 ③ 段注入。★ 执行面是**注入**的（{@link DecisionToolCaller}）：
 * 它住在 {@code simos-app}（持有范围函数与审批编排器），而本类住在 {@code simos-ga}——{@code ga} 依赖 {@code app} 会成环。
 *
 * <p>★★ **本类在 2026-10-12（B2a）自 {@code simos-app} 迁入 {@code simos-ga}**（用户原话⑤「把工具运作、决策人运行放到ga模块」）：
 * 迁移的硬约束是"运行流不得依赖组合根"，故凡"宿主的东西"一律改成**构造期注入**——工具执行面（{@link DecisionToolCaller}）、 工具名声明（{@link
 * DecisionToolNames}）、可达面（{@link DecisionToolReach}，2026-10-14 真机缺陷的修法：开场消息只点够得着的工具名）、图片通路（{@link
 * ToolImages}）、日志门面（{@code GaLog}）； 地图 id / 决策人桶注册表 / 会话库 / 每轮 LLM 客户端本来就是入参。**行为与载荷逐字不变**（I-G1）。
 *
 * <p>★ **会话 id 由决策人 id 派生**（{@link #conversationIdOf}），**不隐式取全局状态**：同一决策人跨 tick（乃至跨进程重启， 因为 {@code
 * SqliteConversationStore} 落在 {@code <store>} 下）落到同一段会话上。
 *
 * <p>★★ **但"同一段"要按「世代」算**（{@link DecisionMaker#conversationGeneration()}）：{@code
 * ga.ResetDecisionMakerConversation} 把世代 +1 ⇒ 下一个轮次落到**另一段**新会话上、**从空上下文重新开始**，而旧会话
 * **一条字节都不动**（会话库是 append-only，可审计）。世代 0 的 id 与旧格式**逐字相同**，故现场已落盘的老会话接得上。
 *
 * <p>★★ **空会话先注入一条 user 身份/规范消息**（{@link #openingUserMessage}，T11D）：{@code load} 对从未写过的会话返回空表 ⇒
 * 不注入的话首轮请求的 user 内容缺失，真供应商直接 400（现场实测）。★ 2026-10-22 用户裁定：system 角色固定传一个空格占位 （不传会报错），身份与规则全部改走 user
 * 消息，且模型**不知道自己是哪一支决策人**。 注入的消息**同时落盘**（ {@code append}），故重启之后它还在、且**不会第二轮再注入一次**。
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
 *       会按同一个能力位回落成字符图，见 {@link DecisionToolNames#visionConfigKey()}）；
 *   <li>**开场快照**（{@link OpeningSnapshot}，装配方按开关给）：空会话时在身份消息之后补一条图片消息，让决策人"先看一眼世界"。
 * </ul>
 *
 * <p>★ **图片是引用式的**（{@link ContentPart.Image} 只带 mediaType + assetId，字节在工件库里）：故这条消息落进会话库之后 **跨 tick
 * / 跨重启都能原样回放**，而请求体里的大小由发送那一刻的解析决定。
 */
public final class DecisionAgentRunner {

  /** 决策回合事件的发射通道（分类 = {@link GaLog#decision()}，来源 = {@link GaLogSource#GA_DECISION_TURN}）。 */
  private static final LogChannel DECISION = EventLog.channel(GaLog.decision());

  /**
   * 会话 id 前缀（{@code "decision-maker:"}）。
   *
   * <p>★★ **它同时是 {@code simos-app} 侧决策人身份 id 的前缀**（{@code DecisionCallerFactory.INSTANCE_ID_PREFIX}
   * ——"决策人身份 ↔ 会话锚"必须逐字同源）：那处常量现在**引用本常量**（{@code app → ga} 是合法方向），故全仓只有这一处字面量。
   */
  public static final String CONVERSATION_ID_PREFIX = "decision-maker:";

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
   * <p>★★ <b>现值 {@code 10000} = 用户裁定（2026-10-14，逐字）</b>：
   *
   * <blockquote>
   *
   * 「能把llm calls给设置成10000吗？我不希望再看到这个恼人的20」
   *
   * </blockquote>
   *
   * <p>★ <b>新旧对照</b>（旧值怎么写出来的、为什么改）：
   *
   * <ol>
   *   <li><b>旧值 20</b> 是<b>从现场算术来的、不是拍的</b>（真 LLM 验收，2026-09-22，35 条会话在案）：勘察 14（＝实测 <b>7</b> × 2，那
   *       7 次是**最小世界新会话首轮**"把整个工具面摸一遍"的量）+ 出令 1 + 重试 4（＝2 轮 ×〔重读状态 + 重发令〕）+ 收口 1 ⇒ {@code 14 + 1 +
   *       4 + 1 = 20}；<b>下界是 11</b>（现场那次撞墙的序列长度）。 更早取 8 <b>实测不够</b>：决策人 {@code dm-osman} 在最小世界（1 国
   *       / 1 区域 / 573 格 / 1 单位）上 7 次勘察 + 1 次出令就用满， 出令因 {@code sd.RegisterEffect}
   *       自指被拒后它正要重读再试时预算耗尽。
   *   <li><b>新值 10000</b> <b>不是重新算出来的</b>——用户明确要求取消这个"烦人的 20"。原注释里"为什么不做成可配" 那段论证（挂进 {@code
   *       ShellConfig} 要四层各加一个形参、换来的只是"部署期调一个我们不希望有人乱调的兜底值"）
   *       在裁定之后<b>不再是理由</b>：用户要的就是一个大到不会打断正常回合的上限。
   * </ol>
   *
   * <p>★★ <b>如实风险说明（不许删）</b>：{@code 20 → 10000} <b>实质上等于取消了兜底</b>。原来模型若不收口，20 次就中止，并经 {@link
   * TurnBudgetExceeded} 给出<b>可判别信号</b>（{@code ga.RunDecision} 回报 {@code result=aborted} / {@code
   * abortedByBudget=true} / {@code reason=turn-budget}）；现在这个信号在 10000 次之前不会出现——本地/自建模型一旦陷入
   * "反复调工具不收口"，烧掉的是 10000 次调用（真 LLM 上是真金白银与真时间）。兜底<b>还在</b>（常量存在、信号仍可判别、中止不丢上下文），
   * 只是<b>不再在正常量级上起作用</b>。
   *
   * <p>★ <b>需要非常规取值时</b>：装配方仍可用下面那些<b>显式给上限</b>的构造器（用例正是这么测"跑飞会被中止"的）——本常量只是<b>缺省</b>。
   */
  public static final int DEFAULT_MAX_LLM_CALLS = 10_000;

  /**
   * **一轮的进度回调**（2026-09-23，GUI 准实时进度）：**每轮 LLM 结束**、**每次工具调用结束**各推一次。
   *
   * <p>★★ **默认 no-op ⇒ MCP 路径零影响**：GM 经 MCP 调 {@code ga.RunDecision} 那条是**同步**的（调用方等到整轮结束才拿到
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

  /** **权限链执行面**（宿主注入，见 {@link DecisionToolCaller}）：范围每次现算，执行走 AgentLib 判定链。 */
  private final DecisionToolCaller caller;

  private final ToolRegistry registry;
  private final LlmClient llmClient;
  private final ConversationStore conversations;
  private final String mapId;

  /** **工具名声明**（宿主注入，见 {@link DecisionToolNames}）：冻结分类、提交拦截与开场消息都按它取名字。 */
  private final DecisionToolNames toolNames;

  /** **图片通路**（宿主注入，见 {@link ToolImages}）：ga 不认识工件库与媒体类型，只决定发不发、什么时候发。 */
  private final ToolImages toolImages;

  /**
   * ★★ <b>归属读口</b>（宿主注入，见 {@link DecisionMakerAffiliationPort}；2026-10-12 乙收尾新增）：空会话身份消息里的"归属：…"
   * 那一段由它现取。
   *
   * <p>★★ <b>为什么必须是端口而不是直接读</b>：归属（国家 / 军队 / GOV 单位）是<b>各模块自己的表</b>（用户裁定「归属肯定归模块」）， 而 {@code ga}
   * 结构性看不见 gov / army（enforcer 禁列）⇒ 走本仓既有的"端口在契约层、实现在组合根"形制。★ 本端口只交<b>不透明标签 + 一句人话</b>，ga
   * <b>不认识任何归属种类</b>（没有枚举、没有 switch、没有 instanceof）——加一个新模块不必改 ga 一个字。
   */
  private final DecisionMakerAffiliationPort affiliations;

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
   * 工具却以为有视觉能力"（或反过来）这种无症状的错位。★ 键的拼写点在渲染工具那边，由 {@link DecisionToolNames#visionConfigKey()} 给。
   */
  private final Map<String, Object> toolConfig;

  /**
   * **本 runner 构建工具面所用的白名单**（P6a）：生产路径 = {@code caller.whitelistFor(dm)}（per-DM）， 旧构造器 = {@code
   * caller.whitelist()}（全局，兼容旧档/夹具）。
   *
   * <p>★ 它与执行期 {@code caller.permissionsFor(dm)} 用的是同一份 {@code whitelistFor(dm)} ⇒ "模型能看到的"与
   * "权限组放行的"不会错位（spec §2.3 要点 1）。两处都在装配方（{@code simos-app}）。
   */
  private final Set<String> toolWhitelist;

  /**
   * ★★ **可达面**（宿主注入，见 {@link DecisionToolReach}）：开场消息按它决定"点不点某件工具的名"。
   *
   * <p>★★ <b>为什么消息要点名之前先问它</b>：点名一件够不着的工具 = 让模型去够一件拿不到的东西；现场实测里模型不会 报错，它会**换路径**（把工具名当文档挂载名反复调
   * {@code doc_list}，一轮 35 次同一条拒绝）。见 {@link DecisionToolReach} 的类注（缺陷现场逐字）。
   *
   * <p>★ <b>缺省 = {@link DecisionToolReach#ofNames(Set)}（本 runner 的白名单）</b>：对"调用方已显式给白名单"的装配
   * 形态（旧构造器与用例），白名单**就是**要发给模型的工具面，故"在不在清单里"与"够不够得着"是同一件事——不是第二份真相。
   */
  private final DecisionToolReach toolReach;

  /** **送给模型的那一份工具面**（名字是线格式；描述与 schema 逐字来自真工具）。 */
  private final List<ToolDef> toolDefs;

  /** 线名 ↔ 真实名（装配期建好：碰撞或转义不合文法就**在这里**炸，而不是发一份坏请求出去换一个 400）。 */
  private final LlmToolNames wireNames;

  /** 同时决策冻结上下文；{@code null} = 旧（单人/顺序）模式。 */
  private final FrozenDecisionContext frozen;

  /** 冻结回合里每个工具的语义分类（工具面装配期算好，执行口只认这张表）。 */
  private final Map<String, FrozenToolKind> frozenToolKinds;

  /**
   * @param caller 权限链执行面（宿主注入，见 {@link DecisionToolCaller}：范围**每次现算**）；本条旧构造器仍用其 {@code
   *     whitelist()}——全局白名单，兼容旧档/夹具；per-DM 走显式白名单那条
   * @param registry **决策人桶**的注册表（{@code Shell.toolsFor(Role.DECISION_AGENT)}）——工具面与执行都从它取
   * @param llmClient 模型客户端（生产路径是真 LLM；用例给 {@code FakeLlmClient}）
   * @param conversations 会话存储（{@code SqliteConversationStore} 落 {@code <store>} 下 ⇒ 跨 tick / 跨重启沿用）
   * @param mapId 本世界的 map 称谓（范围函数要它拼资源前缀）
   * @param toolNames 工具名声明（宿主注入，见 {@link DecisionToolNames}）
   * @param toolImages 图片通路（宿主注入，见 {@link ToolImages}）
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        DEFAULT_MAX_LLM_CALLS);
  }

  /**
   * ★★ **显式白名单**的便利形态（P6a 的接缝）：其余分量全走缺省（回合上限 {@link #DEFAULT_MAX_LLM_CALLS}、无监听、无视觉、
   * 不快照），只有"给模型看的工具面"用调用方给的白名单。
   *
   * <p>★ **旧构造器不受影响**：不带 {@code Set<String>} 的那几条仍在内部交 {@code caller.whitelist()}（全局）。 生产 {@code
   * DecisionAgentService} 走全参并把 {@code caller.whitelistFor(dm)} 传进来。
   *
   * @param toolWhitelist 本 runner 的工具面白名单（**非空校验**；空集合 = 给模型一个空工具面，不是"回落到全局"——回落由 构造方决定，即装配方的
   *     {@code DecisionCallerFactory#whitelistFor(DecisionMaker)}）
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      Set<String> toolWhitelist) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        DEFAULT_MAX_LLM_CALLS,
        ProgressListener.NONE,
        false,
        OpeningSnapshot.NONE,
        toolWhitelist);
  }

  /** 显式给回合上限的形态（用例要测"跑飞会被中止"就得把它压小）。 */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
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
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls,
      ProgressListener progressListener) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        maxLlmCalls,
        progressListener,
        false,
        OpeningSnapshot.NONE);
  }

  /**
   * **全参（旧调用点兼容形态）**（P4 生产路径）：另给"该 provider 有没有视觉能力"与开场快照来源。
   *
   * <p>★ 这条**不带**显式白名单 ⇒ 交 {@code caller.whitelist()}（全局白名单），逐字保留 P6a 之前的旧行为。 per-DM 的 {@code
   * allowedTools} 走下面那条十一参重载。
   *
   * @param vision 该 provider 的模型有没有视觉能力（{@code
   *     LlmRouteLoader.capabilities(...).vision()}）：它**同时**决定 "工具结果里的图发不发"与"渲染工具的 {@code auto}
   *     落在图还是字符图"（两处同源，见 {@link #toolConfig}）
   * @param openingSnapshot 空会话首轮要不要补一张世界图（装配方按开关给；{@link OpeningSnapshot#NONE} = 关）
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        maxLlmCalls,
        progressListener,
        vision,
        openingSnapshot,
        globalWhitelistOf(caller));
  }

  /**
   * ★★ **全参 + 显式工具白名单**（P6a 生产路径）：给模型看的工具面用调用方给的白名单构建（生产 = 装配方的 {@code
   * DecisionCallerFactory#whitelistFor(maker)}）。
   *
   * <p>★ **"用哪份白名单建 face，就检查哪份白名单"**：{@link DecisionToolDefs#requireAll} 拿到的就是这里的 {@code
   * toolWhitelist} ⇒ 它里面每一条都必须在注册表里，缺一条**装配期当场炸**（per-DM 也 fail-closed，不静默少能力、 也不静默兜底到全局）。★
   * 落在全局白名单之外的 {@code allowedTools} 条目不会出现在这里（交集在装配方的 {@code DecisionCallerFactory#whitelistFor}
   * 里完成），故"未知项"只可能表现为**能力收窄**，不可能表现为提权。
   *
   * <p>★ 其余参数语义与上面那条旧全参**逐字相同**（同一个构造器体，不复制字段赋值）。
   *
   * @param toolWhitelist 本 runner 的工具面白名单（**非空引用校验**；空集合 = 空工具面，不是"回落到全局"——回落由构造方 决定，生产走 {@code
   *     whitelistFor} 的空名单兼容语义）
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot,
      Set<String> toolWhitelist) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        maxLlmCalls,
        progressListener,
        vision,
        openingSnapshot,
        toolWhitelist,
        null,
        DecisionMakerAffiliationPort.NONE);
  }

  /**
   * **全参 + 同时决策冻结回合**：在显式白名单基础上多一个 {@link FrozenDecisionContext}（{@code null} = 旧模式）。
   *
   * <p>★★ 冻结回合的工具面在装配期就被裁掉：只留世界读 + 决策包四件套 + 显式无状态读口；带 {@code expectedRevision} 的旧世界写工具（{@code
   * ga.IssueDirective} / {@code GovPay} / 外交写等）既不发给模型，执行口也会拒绝。 世界写因此不可能在决策期发生——这正是"同时决策"的硬约束。
   *
   * <p>★ <b>可达面缺省 = 本 runner 的白名单</b>（{@link DecisionToolReach#ofNames(Set)}）：对这条形态的调用方，白名单就是要发给模型的
   * 工具面。要按**宿主自己的可达面真值**（{@code DecisionCallerFactory#whitelistFor}）答，走下面那条带端口的重载。
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot,
      Set<String> toolWhitelist,
      FrozenDecisionContext frozen,
      DecisionMakerAffiliationPort affiliations) {
    this(
        caller,
        registry,
        llmClient,
        conversations,
        mapId,
        toolNames,
        toolImages,
        maxLlmCalls,
        progressListener,
        vision,
        openingSnapshot,
        toolWhitelist,
        DecisionToolReach.ofNames(toolWhitelist),
        frozen,
        affiliations);
  }

  /**
   * ★★ **全参（生产路径）+ 宿主注入的可达面**：与上面那条的唯一差别是开场消息"点不点某件工具的名"由 {@code toolReach} 作答（见 {@link
   * DecisionToolReach}）。
   *
   * <p>★ <b>生产装配</b>（{@code simos-app.DecisionAgentService}）给的是 {@code AppDecisionToolReach}：它转问
   * {@code DecisionCallerFactory#whitelistFor(dm)} —— 与 {@code toolWhitelist}（工具面）**同一份数据、同一个来源**，
   * 故"消息里点名的"与"模型清单里有的"不会各说各话。其余参数语义与上面那条逐字相同（同一个构造器体，不复制字段赋值）。
   *
   * @param toolReach 可达面（非 null）；缺省形态见上面两条重载
   */
  public DecisionAgentRunner(
      DecisionToolCaller caller,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      DecisionToolNames toolNames,
      ToolImages toolImages,
      int maxLlmCalls,
      ProgressListener progressListener,
      boolean vision,
      OpeningSnapshot openingSnapshot,
      Set<String> toolWhitelist,
      DecisionToolReach toolReach,
      FrozenDecisionContext frozen,
      DecisionMakerAffiliationPort affiliations) {
    this.caller = Objects.requireNonNull(caller, "caller");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
    this.toolImages = Objects.requireNonNull(toolImages, "toolImages");
    this.affiliations = Objects.requireNonNull(affiliations, "affiliations");
    this.toolReach = Objects.requireNonNull(toolReach, "toolReach");
    if (maxLlmCalls <= 0) {
      throw new IllegalArgumentException("maxLlmCalls 必须为正: " + maxLlmCalls);
    }
    this.maxLlmCalls = maxLlmCalls;
    this.progressListener = Objects.requireNonNull(progressListener, "progressListener");
    this.vision = vision;
    this.openingSnapshot = Objects.requireNonNull(openingSnapshot, "openingSnapshot");
    // ★ 键由宿主的工具名声明给（ga 不认识渲染工具），值与 vision 同源（见 toolConfig 的类注）。
    this.toolConfig = Map.of(toolNames.visionConfigKey(), vision);
    this.toolWhitelist = Set.copyOf(Objects.requireNonNull(toolWhitelist, "toolWhitelist"));
    this.frozen = frozen;
    // ★★ 工具面在**装配期**建一次（不随世界变：它只取决于注册表与白名单），并当场建好名字映射：
    //   ① 白名单里有工具不在注册表 ⇒ requireAll 抛（旧行为，只是提前到构造期；per-DM 白名单同样适用）；
    //   ② 转义碰撞 / 转义结果不合供应商文法 ⇒ LlmToolNames 抛（**真 LLM 实测缺陷**的护栏，2026-09-22）。
    //   两条都是装配故障，都不该等到"模型第一轮已经花掉"才发现。
    List<ToolDef> face = DecisionToolDefs.requireAll(registry, this.toolWhitelist);
    // ★ 名字映射建在**全量白名单**上：冻结回合虽然不把世界写工具发给模型，但模型凭记忆叫到它们时，
    //   执行口还要把线名翻回真实名再给出具名 FORBIDDEN（否则会误报 TOOL_NOT_FOUND，把"本回合禁止"说成"没这个工具"）。
    this.wireNames = LlmToolNames.of(face.stream().map(ToolDef::name).toList());
    Map<String, FrozenToolKind> kinds = Map.of();
    List<ToolDef> exposed = face;
    if (frozen != null) {
      LinkedHashMap<String, FrozenToolKind> allowed = new LinkedHashMap<>();
      List<ToolDef> filtered = new ArrayList<>(face.size());
      for (ToolDef def : face) {
        FrozenToolKind kind =
            frozenKindOf(def, toolNames.packetTools(), toolNames.statelessReads());
        if (kind != null) {
          allowed.put(def.name(), kind);
          filtered.add(def);
        }
      }
      kinds = Map.copyOf(allowed);
      exposed = List.copyOf(filtered);
    }
    this.frozenToolKinds = kinds;
    this.toolDefs = wireNames.wireDefs(exposed);
  }

  /**
   * 冻结回合分类（只认 schema 里的坐标字段，不靠命名约定）：
   *
   * <ul>
   *   <li>packet 四件套 ⇒ {@link FrozenToolKind#PACKET_WRITE}；
   *   <li>schema 有 {@code revision} ⇒ {@link FrozenToolKind#WORLD_READ}（runner 会强制覆盖成冻结坐标）；
   *   <li>schema 有 {@code expectedRevision} 且不是 packet ⇒ {@code null}（旧世界写/自指写，冻结回合直接移除）；
   *   <li>显式列出的无状态读口 ⇒ {@link FrozenToolKind#STATELESS_READ}；
   *   <li>其余 ⇒ {@code null}（安全默认：新工具若没被明确分类，同时决策回合里不出现）。
   * </ul>
   *
   * <p>★ 两组名字由宿主注入的 {@link DecisionToolNames} 派生（{@code packetTools()} / {@code statelessReads()}），
   * 不在 ga 里写字面量：工具改名而这里没跟着改的话，症状是**静默**的（提交不再被延迟、新读口在冻结回合里消失）。
   */
  private static FrozenToolKind frozenKindOf(
      ToolDef def, Set<String> packetTools, Set<String> statelessReads) {
    if (packetTools.contains(def.name())) {
      return FrozenToolKind.PACKET_WRITE;
    }
    Map<String, Object> schema = def.jsonSchema();
    if (schema != null) {
      Object properties = schema.get("properties");
      if (properties instanceof Map<?, ?> props) {
        if (props.containsKey("revision")) {
          return FrozenToolKind.WORLD_READ;
        }
        if (props.containsKey("expectedRevision")) {
          return null;
        }
      }
    }
    if (statelessReads.contains(def.name())) {
      return FrozenToolKind.STATELESS_READ;
    }
    return null;
  }

  /**
   * **缺省全局白名单的取值点**（旧构造器专用）：显式保留 {@code caller} 的 null 校验语义，同时让旧构造器与 P6a 之前逐字同源（{@code
   * caller.whitelist()}）。
   */
  private static Set<String> globalWhitelistOf(DecisionToolCaller caller) {
    return Objects.requireNonNull(caller, "caller").whitelist();
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
   * **同时决策回合的冻结上下文**（用户 2026-10-22 裁定）：一次 {@code simos.sd.run-decision-makers} 批里所有决策人 <b>读同一
   * branch/revision 快照</b>；世界写工具在工具面与执行口都被移除，只允许：
   *
   * <ul>
   *   <li>世界读工具：runner 强制把 {@code branch/revision} 覆盖成冻结坐标，模型自己填什么都不生效；
   *   <li>决策包读/写工具（{@code simos.sd.propose} / {@code packet.intent} / {@code packet.submit} /
   *       {@code packet.my}）：强制到冻结 branch，且剥掉 {@code expectedRevision}（让工具自己取当下 head）——
   *       于是每个决策人只写自己的 packet 前缀，不碰世界，也不会被前一个决策人的 packet 写版本顶掉；
   *   <li>{@code packetWriteLock}：并发跑轮时所有 packet 写共用同一把锁，把"读快照并发、写包串行"钉死。
   * </ul>
   *
   * <p>★★ **为什么世界写工具必须被移除**：只要一个决策人能在决策期直接落世界命令，后跑的决策人就会看到前一个决策人的写入 ——那就退回顺序回合，而不是同时回合。世界效果统一由 GM
   * 在所有包收齐后经 merged plan 一条 revision 执行（D3）。
   */
  public record FrozenDecisionContext(
      BranchId branch, RevisionId revision, Object packetWriteLock) {

    public FrozenDecisionContext {
      Objects.requireNonNull(branch, "branch");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(packetWriteLock, "packetWriteLock");
    }
  }

  /** 冻结回合里工具被允许的三类语义；不在表里的工具既不出现在工具面、也不会被执行。 */
  private enum FrozenToolKind {
    WORLD_READ,
    PACKET_WRITE,
    STATELESS_READ
  }

  // ★ 决策包四件套与"无状态共享读口"两组名字**不再在这里写字面量**（B2a）：它们由宿主注入的
  //   DecisionToolNames 派生（packetTools() / statelessReads()），见 frozenKindOf 的类注。

  /**
   * **会话 id 的唯一拼写点**（生产路径只走这个重载）：由**世界事实**（{@link DecisionMaker#id()} + {@link
   * DecisionMaker#conversationGeneration()}）**纯函数**派生。
   *
   * <p>★★ **世代 0 必须与旧格式逐字相同**（{@code "decision-maker:" + id}）：现场已经落盘的老会话（{@code
   * decision-maker:dm-osman} 之类）就是这个写法，换一个写法 = **全部接不上**，症状是"决策人失忆"，不是一条报错。 世代 > 0 才换到 {@link
   * #GENERATION_CONVERSATION_ID_PREFIX} 那一族。
   *
   * <p>★ **入参是整个 {@code DecisionMaker}、不是一个 id 字符串**：这样"用哪个世代"不可能由调用方随手给错——它是**世界事实** 的一部分（{@code
   * ga.ResetDecisionMakerConversation} 每次 +1）。传入 id + 世代的重载只是**格式原语**（用例与派生判据用）， 生产路径不该用它。
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
   *
   * <p>★ **公开可见性**（B2a）：装配方在"用户先说话"那条路上要先补这条身份消息（{@code
   * DecisionAgentService#appendUserMessage}）；运行流与装配从 B2a 起分居两个模块（{@code ga} / {@code app}） ⇒
   * 它不能再是包内可见，故为 {@code public}（语义与文本逐字未变，只是可见性放宽）。
   *
   * <p>★★ <b>按可达面点名</b>（2026-10-14 真机实测缺陷的修法，见 {@link DecisionToolReach} 的类注）：消息里**点名一件工具 =
   * 叫模型去用它**，故点名之前先问宿主"这个决策人此刻够不够得着它"，**够不着就不点**（见下面那条重载的 {@code toolReach}）。 现场证据：决策人 {@code
   * r8-dm} 只被允许 6 件文档/工作区工具，而消息无条件点名 {@code simos_command_catalog} / {@code
   * simos_timeline_branches} ⇒ 模型在工具清单里找不到它们，**换路径**把工具名当**文档挂载名**反复调 {@code doc_list}，一轮 38 次调用里
   * 35 次同一条 {@code INVALID_ARGUMENTS}，整轮烧光。 ★ 那句既有的"范围之外的调用会被拒——那不是你
   * 参数写错了"**不够**：它解释了"被拒不是你的错"，却没有阻止模型**换路径死磕**；故新正文另加一条止损规则（见下）。
   *
   * @param toolReach 可达面（宿主注入，见 {@link DecisionToolReach}）：决定"先查看"那一段点不点名 {@code catalog} / {@code
   *     branches}，以及是否补一条"本消息点到的名字可能不在你清单里"的标注
   */
  public static LlmMessage openingUserMessage(
      DecisionMaker dm,
      String affiliationText,
      DecisionToolNames toolNames,
      DecisionToolReach toolReach) {
    return openingUserMessage(dm, affiliationText, null, toolNames, toolReach);
  }

  /**
   * ★★ <b>一句人话的"归属：…"</b>（2026-10-12 乙收尾）：从注入的 {@link DecisionMakerAffiliationPort} 现取该决策人此刻的归属，
   * 多个种类按标签升序用 {@code 、} 连接；<b>一条都没有 ⇒ "尚未被任何模块赋予（裸决策人）"</b>（用户原话：「和 Unit
   * 一样，不但允许创建没有更多附加状态的单个决策人」）。
   *
   * <p>★ 每一段文本由<b>持有该归属的模块</b>渲染（{@code NationAffiliations.describe} 等）⇒ 单个归属时与迁移前 <b>逐字相同</b>；ga
   * 侧不认识任何归属种类（没有枚举、没有 switch）。
   */
  public static String affiliationTextOf(
      DecisionMakerAffiliationPort affiliations, DecisionMaker dm, SimulationState state) {
    Objects.requireNonNull(affiliations, "affiliations");
    List<DecisionMakerAffiliationPort.AffiliationClaim> claims =
        affiliations.affiliationsOf(dm.id(), state);
    if (claims.isEmpty()) {
      return "尚未被任何模块赋予（裸决策人）";
    }
    StringBuilder text = new StringBuilder();
    for (DecisionMakerAffiliationPort.AffiliationClaim claim : claims) {
      if (text.length() > 0) {
        text.append("、");
      }
      text.append(claim.description());
    }
    return text.toString();
  }

  /**
   * 空会话首轮注入的**user**消息：身份 + 权限 + 决策规范。★ 按用户 2026-10-22 裁定，system 角色不再承载任何内容； 请求里只保留一条 content
   * 为一个空格的 system 占位（供应商要求 system 必须存在），身份与规范全部走这条 user。
   *
   * <p>★ 工具名一律从宿主注入的 {@link DecisionToolNames} 取（不在这里写字面量）：改名时这条消息跟着走，"说的"与"注册的"不会错位。
   *
   * <p>★★ 2026-10-14 起**点名之前先问可达面**（{@code toolReach}，真机实测缺陷的修法）：能点名的只有 {@link
   * DecisionToolReach#reachable} 答"够得着"的那些；一个都够不着时"先查看"那一段**一个工具名都不点**，只交代 "以你这次收到的工具清单为准"。★
   * 这条消息**永久落盘**、而可达面此后可能被 GM 改（{@code ga.SetDecisionMakerAccess}） ⇒
   * 正文一律写成"以**你这次收到的工具清单**为准"这种**自纠正**措辞，不写"你有/你没有 X"的绝对断言：后者在配权变化之后 就会变成一句**模型无法察觉的谎**。
   */
  static LlmMessage openingUserMessage(
      DecisionMaker dm,
      String affiliationText,
      FrozenDecisionContext frozen,
      DecisionToolNames toolNames,
      DecisionToolReach toolReach) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(affiliationText, "affiliationText");
    Objects.requireNonNull(toolNames, "toolNames");
    Objects.requireNonNull(toolReach, "toolReach");
    String where = affiliationText;
    String catalog = LlmToolNames.wireNameOf(toolNames.catalog());
    String branches = LlmToolNames.wireNameOf(toolNames.branches());
    String intentTool = LlmToolNames.wireNameOf(toolNames.packetIntent());
    String proposeTool = LlmToolNames.wireNameOf(toolNames.propose());
    String submitTool = LlmToolNames.wireNameOf(toolNames.packetSubmit());
    String myPacketTool = LlmToolNames.wireNameOf(toolNames.myPacket());
    // ★★ 可达面：**问宿主**（唯一真相 = 装配方的 DecisionCallerFactory.whitelistFor），运行流不自己算（见 DecisionToolReach
    // 类注）。
    boolean canCatalog = toolReach.reachable(dm, toolNames.catalog());
    boolean canBranches = toolReach.reachable(dm, toolNames.branches());
    boolean canIntent = toolReach.reachable(dm, toolNames.packetIntent());
    boolean canPropose = toolReach.reachable(dm, toolNames.propose());
    boolean canSubmit = toolReach.reachable(dm, toolNames.packetSubmit());
    boolean canMyPacket = toolReach.reachable(dm, toolNames.myPacket());
    // ★ 冻结回合不点旧单人回合的两件（下面走另一段），故它们不参与"有没有点了却够不着的名字"。
    boolean canLegacyWrites =
        frozen != null
            || (toolReach.reachable(dm, toolNames.issueDirective())
                && toolReach.reachable(dm, toolNames.submitVerdict()));
    boolean allNamedReachable =
        canCatalog
            && canBranches
            && canIntent
            && canPropose
            && canSubmit
            && canMyPacket
            && canLegacyWrites;
    StringBuilder text = new StringBuilder();
    text.append("你是决策人「")
        .append(dm.id().value())
        .append("」，归属：")
        .append(where)
        .append("。\n")
        .append("\n")
        .append("【你能看到什么】由系统强制：每次工具调用都由系统按你的归属与当前世界现算可见范围，范围之外的调用会被拒——")
        .append("那不是你参数写错了，是那件事你看不到。先查看，再决策。\n")
        .append("\n")
        // ★★ 2026-10-14 真机实测教训的直接产物（见 DecisionToolReach 类注的 35 连撞现场）：光说"被拒不是你的错"不够
        //   —— 模型会**换路径去够**（把工具名当文档挂载名）。这条止损规则点名那种换路径的形态。
        .append("【被拒了怎么办（止损，重要）】任何一个拒绝都只说明\"那件事你看不到、或那条路不通\"")
        .append("（RESOURCE_DENIED / TOOL_NOT_FOUND / INVALID_ARGUMENTS / 工作区类报错都一样）：\n")
        .append("- 同一个调用被拒之后不要重试（参数没变，结果一定一样），也不要为了够到同一件事去换一件工具、换一个名字或换一种地址。\n")
        .append("- ★ 不要把世界实体的名字当成**文档/工作区的挂载名**：某个命令名、某个工具名（包括本消息里点到的那些工具名）是工具、不是一块地，")
        .append("拿它去调文档类工具只会被一遍遍拒。\n")
        .append("- 被拒一次就收口：如实报告\"这件事我看不到\"，或改做你清单里确实能做的事。\n")
        .append("\n")
        .append("【先查看】");
    if (canCatalog && canBranches) {
      text.append(catalog)
          .append(" 给出你有途径触发的命令类型及其载荷字段；")
          .append(branches)
          .append(" 给出分支与当前 head。\n");
    } else if (canCatalog) {
      text.append(catalog).append(" 给出你有途径触发的命令类型及其载荷字段。\n");
    } else if (canBranches) {
      text.append(branches).append(" 给出分支与当前 head。\n");
    } else {
      // ★ 两件都够不着 ⇒ **一个名字都不点**：点名 = 叫它去够一件拿不到的东西（现场实测的根因）。
      text.append("你此刻能查看什么，以**这次实际发给你的工具清单**为准——先查看，再决策。\n");
    }
    if (!allNamedReachable) {
      // ★ 用户裁定允许的另一半：「明确标注"系统可能拒你，那不是错误"」。措辞指向**当次**工具清单 ⇒ 配权变了也不会过期。
      text.append("★ 本消息里点到名的工具，凡不在你**这次收到的工具清单**里的，就是你看不到的能力——不要换别的工具、别的名字去够它（见上面的止损）。\n");
    }
    text.append("\n")
        .append("【决策的两种表达】\n")
        .append("1) 自然语言决策：用 ")
        .append(intentTool)
        .append(" 写 text；或者在你结束本回合时，用最后一段自然语言陈述表达。\n")
        .append("2) 结构命令：用 ")
        .append(proposeTool)
        .append(" 提议真实工具调用，参数里带 {tool, args, intent}；一条命令一次提议。\n")
        .append("最后用 ")
        .append(submitTool)
        .append(" 统一提交本 tick 的决策包。用 ")
        .append(myPacketTool)
        .append(" 查看自己的包。\n")
        .append("\n")
        .append(
            "【本轮可 propose 的工具】simos.unit.raiseUnit / simos.gov.recruit / simos.gov.retireStaff / ")
        .append(
            "simos.social.household.members / simos.unit.levyRegion / simos.gov.selectExaminees / ")
        .append("simos.gov.dispatchTeam（以你实际 allowedTools 为准；清单外不要在 propose 里试，试一次拒一次）。\n")
        .append("【工具预算与写法】本回合最多做 4–6 次工具调用：");
    if (canCatalog) {
      // ★ 够不着就不点它的名（点名 = 叫它去用；见本方法的类注）。
      text.append("先看一次 ").append(catalog).append(" 选一条可 propose 的工具，");
    }
    text.append("写 intent + propose + submit 后立刻收口。不要逐条试工具；任何工具的 args 里不要出现 JSON null，")
        .append("缺字段就省略。\n")
        .append("\n")
        .append("【系统如何结算你的回合（重要）】\n")
        .append("- 如果你一直不调用 ")
        .append(submitTool)
        .append(" 就停止说话：本回合写进包里的所有自然语言与结构命令，加上你整轮最后一段自然语言，")
        .append("会被自动一起交给 GM；系统自动完成提交。\n")
        .append("- 如果你调用 ")
        .append(submitTool)
        .append(" 后又继续写/提议命令：系统会保守地把整个回合的所有命令合并，")
        .append("并用整轮最后一段自然语言作为 NL 决策，最后统一提交一次。\n")
        .append("- 如果你调用 ")
        .append(submitTool)
        .append(" 后不再发命令、只继续说话：之前提交/写进包的命令照常作为你的决策；")
        .append("提交后的纯文本只作为解释留档，不再额外算一条新的自然语言决策。\n")
        .append("- GM 收到包后决定批准/拒绝/合并执行；你不需要自己执行世界效果。\n")
        .append("\n");
    if (frozen == null) {
      text.append("【本轮为单人回合】旧的 ")
          .append(LlmToolNames.wireNameOf(toolNames.issueDirective()))
          .append(" / ")
          .append(LlmToolNames.wireNameOf(toolNames.submitVerdict()))
          .append(" 仍可按旧语义使用；但推荐优先用上面的决策包三件套表达。\n")
          .append("如果使用 ")
          .append(LlmToolNames.wireNameOf(toolNames.issueDirective()))
          .append("：expectedRevision 必须填 ");
      if (canBranches) {
        text.append(branches).append(" 读到的当前 head；");
      } else {
        // ★ 同一条纪律：{@code branches} 够不着就不点它的名（"读到的当前 head"这句不依赖任何工具名）。
        text.append("你能读到的当前 head；");
      }
      text.append("commands 里不得放 sd. 前缀的命令类型（防无限递归）。\n");
    } else {
      text.append("【本轮为同时决策回合】世界冻结在 ")
          .append(frozen.branch().value())
          .append("@")
          .append(frozen.revision().value())
          .append("：所有决策人读同一份快照，谁都不能在决策期直接改世界（世界写工具已不在你的工具面）。")
          .append("你的推理只能基于这份快照，不要猜别人已经做了什么。\n");
    }
    text.append("\n【tick 与坐标】当前 tick 一律从读工具给的世界状态里取，不要凭记忆、也不要沿用上一轮的值。");
    return LlmMessage.user(text.toString());
  }

  /**
   * 每轮不落盘的 user 规范提醒：保证老会话与新会话都按同一套回合结算规则跑。
   *
   * <p>★★ 2026-10-14 GM⇄DM 双向信件（设计书 §3.4 / §9-C）：这条提醒里多一句"本 tick 你已收发 N 条信件（上限 128）" +
   * "新信件会自动出现、要复查完整收件箱用收件箱读工具"。★ 它**不落盘**（每轮现插）——既有语义不变；★ 报数而不是拒发： 上限是**命令期**判的（第 129
   * 条具名拒），这里只把"还剩多少额度"摆在模型面前，让收敛责任有个落点。
   *
   * @param lettersThisTick 本 tick 与该决策人有关的信件条数（收 + 发；见 {@link #lettersThisTick}）
   */
  private static LlmMessage decisionRoundNotice(
      DecisionMaker dm,
      FrozenDecisionContext frozen,
      DecisionToolNames toolNames,
      int lettersThisTick) {
    StringBuilder text = new StringBuilder();
    text.append("[本回合规则提醒] 决策人「")
        .append(dm.id().value())
        .append("」：自然语言决策用 ")
        .append(LlmToolNames.wireNameOf(toolNames.packetIntent()))
        .append(" 或最后一段文本表达；结构命令用 ")
        .append(LlmToolNames.wireNameOf(toolNames.propose()))
        .append("；最后用 ")
        .append(LlmToolNames.wireNameOf(toolNames.packetSubmit()))
        .append(" 提交。提交后又继续发命令会被保守合并；一直不提交就结束，系统会把整轮最后一段文本自动作为 NL 决策交 GM。")
        .append("提交后只说话不再发命令，则提交后的文本不再算新的 NL 决策。")
        .append("★ 预算：最多 4–6 次工具调用；只 propose 工具面列出的 proposeable 工具，args 里不要写 null，缺字段就省略。");
    // ★★ 信件口径（2026-10-14）：一句话说清"能发几条 / 答复会自动出现 / 去哪复查"。★ 工具名走线格式
    //   （模型收到的工具清单里就是这个名字，写真实名会让它照着一个调不到的名字去点）。
    text.append("★ 信件：本 tick 你已收发 ")
        .append(lettersThisTick)
        .append(" 条（每 tick 每方向上限 ")
        .append(GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION)
        .append("）；**新收到的信**（GM 的答复 / 被授权决策人的来信）会在每轮开始时自动出现在你的会话里，")
        .append("你不需要自己去捞；要复查完整收件箱用 ")
        .append(LlmToolNames.wireNameOf(GaMessageInboxTool.NAME))
        .append("。");
    if (frozen != null) {
      text.append("世界冻结在 ")
          .append(frozen.branch().value())
          .append("@")
          .append(frozen.revision().value())
          .append("，读同一快照；世界写工具不在工具面。");
    }
    return LlmMessage.user(text.toString());
  }

  /** 注入消息的正文头（也是游标扫描的**身份判据**：只认以它开头的消息，见 {@link #lettersCursorOf}）。 */
  static final String LETTERS_MESSAGE_HEADER = "[新信件]";

  /** 注入游标的正文标记前缀：{@code [本消息已注入至 <最后一条信的 id>]}（见 {@link #newLettersMessage}）。 */
  static final String LETTERS_CURSOR_PREFIX = "[本消息已注入至 ";

  /** 注入游标的正文标记后缀。 */
  static final String LETTERS_CURSOR_SUFFIX = "]";

  /**
   * ★★ <b>新信件注入</b>（设计书 §3.4）：取本 tick 状态里**寄给该 DM**、且**会话历史里还没有的**信，拼成一条 user 消息。
   *
   * <p>★★ <b>游标由历史自己承载</b>（零新存储、跨进程重启可恢复）：扫 {@code history} 里已有的注入消息正文中的 {@code [本消息已注入至 <id>]}
   * 标记，取标记里那个 id 在信件序列里的位置，只注入它**之后**的。找不到标记 = 从未注入过 ⇒ 从最早开始。 ★ <b>游标 id 不在这批信里</b>（换分支 / 条目被裁剪 /
   * 旧档没有这个 id）⇒ 从最早开始**重注一次**：宁可重复， 不可静默丢——"GM 的答复必须到达 DM"是这一批的红线，重复只是啰嗦，丢信是断链。
   *
   * <p>★ <b>正文逐条列 {@code from / tick / subject? / body}，不截断正文</b>（设计书 §9-C 的裁定：截断会静默丢掉 GM 的答复 =
   * 问答断链）。★ 每条还带上 {@code id}（回信/对账用得上，也是"这条信注入过没有"的显式痕迹）。
   */
  private static Optional<LlmMessage> newLettersMessage(
      DecisionMaker dm, SimulationState state, List<LlmMessage> history) {
    String self = dm.id().value();
    List<GmInboxTool.Letter> incoming = new ArrayList<>();
    for (GmInboxTool.Letter letter : GmInboxTool.letters(GaSnapshots.state(state))) {
      // ★ 只注入**寄给我的**（GM 的回信 + 被授权 peer DM 的来信都落在这里：`to` 就是收件人）。
      if (self.equals(letter.to())) {
        incoming.add(letter);
      }
    }
    if (incoming.isEmpty()) {
      return Optional.empty();
    }
    int start = 0;
    String cursor = lettersCursorOf(history);
    if (cursor != null) {
      int index = indexOfLetter(incoming, cursor);
      if (index >= 0) {
        start = index + 1;
      }
    }
    if (start >= incoming.size()) {
      return Optional.empty();
    }
    List<GmInboxTool.Letter> fresh = incoming.subList(start, incoming.size());
    StringBuilder text = new StringBuilder();
    text.append(LETTERS_MESSAGE_HEADER)
        .append(" 有你 ")
        .append(fresh.size())
        .append(" 条还没看过的信（GM 的答复 / 被授权决策人的来信）——**不需要自己去读收件箱，它们就在这里**：\n");
    text.append(LETTERS_CURSOR_PREFIX)
        .append(fresh.get(fresh.size() - 1).id())
        .append(LETTERS_CURSOR_SUFFIX)
        .append("\n");
    for (GmInboxTool.Letter letter : fresh) {
      text.append("---\n")
          .append("id=")
          .append(letter.id())
          .append(" · from=")
          .append(letter.from())
          .append(" · tick=")
          .append(letter.tick());
      JsonNode value = letter.value();
      if (value.hasNonNull("subject")) {
        text.append(" · subject=").append(value.path("subject").asText(""));
      }
      if (value.hasNonNull("replyTo")) {
        text.append(" · replyTo=").append(value.path("replyTo").asText(""));
      }
      text.append("\n").append(value.path("body").asText("")).append("\n");
    }
    text.append("---\n★ 要回信 / 追问：用 ")
        .append(LlmToolNames.wireNameOf(GaMessageSendTool.NAME))
        .append("（to=\"")
        .append(ToolResources.GM_ID)
        .append("\" 或 GM 授权给你的那个决策人 id）。");
    return Optional.of(LlmMessage.user(text.toString()));
  }

  /**
   * 历史里最后一条注入消息的游标 id（{@code [本消息已注入至 <id>]} 里那个 id）；从未注入过 ⇒ {@code null}。
   *
   * <p>★ 取**最后一条**：会话是追加式的，后一条注入消息的游标一定覆盖前一条（幂等）。
   *
   * <p>★ <b>只认以 {@link #LETTERS_MESSAGE_HEADER} 开头的消息</b>：信件的**正文**是原样贴进来的（不截断），
   * 万一某封信的正文里恰好写了同样的标记串，它也不该被当成游标——身份判据（消息是不是注入消息）与内容判据（标记在哪）分开。
   */
  private static String lettersCursorOf(List<LlmMessage> history) {
    String cursor = null;
    for (LlmMessage message : history) {
      String text = textOf(message);
      if (!text.startsWith(LETTERS_MESSAGE_HEADER)) {
        continue;
      }
      int at = text.lastIndexOf(LETTERS_CURSOR_PREFIX);
      if (at < 0) {
        continue;
      }
      int end = text.indexOf(LETTERS_CURSOR_SUFFIX, at + LETTERS_CURSOR_PREFIX.length());
      if (end < 0) {
        continue;
      }
      String id = text.substring(at + LETTERS_CURSOR_PREFIX.length(), end).strip();
      if (!id.isEmpty()) {
        cursor = id;
      }
    }
    return cursor;
  }

  /** 信件序列里该 id 的下标；不在 ⇒ {@code -1}（调用方按"从最早开始重注"处置）。 */
  private static int indexOfLetter(List<GmInboxTool.Letter> letters, String id) {
    for (int i = 0; i < letters.size(); i++) {
      if (letters.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  /** 一条消息里的**文本分片**拼起来（游标扫描只关心正文；工具调用/图片分片不参与）。 */
  private static String textOf(LlmMessage message) {
    StringBuilder text = new StringBuilder();
    for (ContentPart part : message.content()) {
      if (part instanceof ContentPart.Text literal) {
        text.append(literal.text());
      }
    }
    return text.toString();
  }

  /**
   * 本 tick 与该决策人有关的信件条数（**收 + 发**）：给 {@code decisionRoundNotice} 报"已收发 N 条 / 上限 128"。
   *
   * <p>★ 判据按 {@code value.tick} == 世界当前 tick（信件自己记的 tick 就是发信那一刻的世界 tick），不是按条目落盘的 revision。
   */
  private static int lettersThisTick(DecisionMaker dm, SimulationState state) {
    String self = dm.id().value();
    int count = 0;
    for (GmInboxTool.Letter letter : GmInboxTool.letters(GaSnapshots.state(state))) {
      if (letter.tick() != state.meta().timestamp().tick()) {
        continue;
      }
      if (self.equals(letter.from()) || self.equals(letter.to())) {
        count++;
      }
    }
    return count;
  }

  /**
   * ★ 发给 provider 的 messages：system 角色必须存在（用户 2026-10-22 裁定：不传会报错），但内容固定为一个空格； 历史里旧的 system
   * 消息全部过滤掉，身份与规范走 user 消息。这样新老会话都不会再向 provider 发送非空 system。
   */
  private static List<LlmMessage> requestMessages(List<LlmMessage> history) {
    List<LlmMessage> out = new ArrayList<>(history.size() + 1);
    out.add(LlmMessage.system(" "));
    for (LlmMessage message : history) {
      if (!LlmMessage.ROLE_SYSTEM.equals(message.role())) {
        out.add(message);
      }
    }
    return List.copyOf(out);
  }

  /** 回合结算信号：记录提交标记、提交后是否继续发命令、整轮最后一段文本与提交前最后一段文本。 */
  private static final class DecisionTurnSignals {

    /** 工具名声明（宿主注入的那一份）：只用来认出"提交 / 提议 / 自然语言"三件套。 */
    private final DecisionToolNames toolNames;

    private boolean submitSeen;
    private boolean commandsAfterSubmit;
    private Optional<String> lastAssistantText = Optional.empty();
    private Optional<String> lastTextBeforeSubmit = Optional.empty();

    DecisionTurnSignals(DecisionToolNames toolNames) {
      this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
    }

    void onAssistantText(Optional<String> text) {
      if (text == null || text.isEmpty() || text.get() == null || text.get().isBlank()) {
        return;
      }
      lastAssistantText = text;
      if (!submitSeen) {
        lastTextBeforeSubmit = text;
      }
    }

    void onToolCall(String toolName) {
      if (toolNames.packetSubmit().equals(toolName)) {
        submitSeen = true;
        return;
      }
      if (submitSeen
          && (toolNames.propose().equals(toolName) || toolNames.packetIntent().equals(toolName))) {
        commandsAfterSubmit = true;
      }
    }

    boolean submitSeen() {
      return submitSeen;
    }

    boolean commandsAfterSubmit() {
      return commandsAfterSubmit;
    }

    Optional<String> lastAssistantText() {
      return lastAssistantText;
    }

    Optional<String> lastTextBeforeSubmit() {
      return lastTextBeforeSubmit;
    }
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
    DECISION.info(
        LogEvent.of(
            "DECISION_RUN_START",
            GaLogSource.GA_DECISION_TURN,
            "dm",
            dm.id().value(),
            "day",
            state.meta().timestamp().tick(),
            "conversation",
            conversationId));
    // ★ 工具面与权限组**同一份数据**（spec §2.3 要点 1）：两者若各有一张表，错位不会有任何症状。
    //   （那份面在构造期已建好、名字已转义成线格式——见构造器与 LlmToolNames 的类注。）
    List<LlmMessage> history = new ArrayList<>(conversations.load(conversationId));
    // ★★ 空会话 ⇒ 先注入身份（**并落盘**），再发第一个请求：不注入的话首轮请求的 messages 是空的，真供应商
    //   直接 400（`field messages is required`），一次都跑不成（现场实测）。落盘是这件事的要害——只往内存里塞
    //   的"修法"在**换进程/换 store 实例**之后又回到空会话，缺陷原样复现（见 openingUserMessage 的类注）。
    //   ★ 只在**空会话**上注入（"有没有历史"是纯函数，不看世界）：第二轮 `load` 里已经有它，故不会重复追加。
    if (history.isEmpty()) {
      LlmMessage opening =
          openingUserMessage(
              dm, affiliationTextOf(affiliations, dm, state), frozen, toolNames, toolReach);
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
    // ★★ 2026-10-14 GM⇄DM 双向信件（设计书 §3.4，缺口 G6）：**新信件在每轮开始时自动注入、且落盘**。
    //   ★ 为什么必须由运行流注入、而不是指望 DM 自己去调收件箱：DM 不会主动去捞（缺口 G6 的原话："DM 不调 inbox
    //     就永远看不到答复"）⇒ GM 的答复到不了 DM = 问答断链。
    //   ★ 位置有意：开场消息/开场快照**之后**（先有"你是谁、你在哪"，再有"有人给你回信了"）、`decisionRoundNotice`
    //     之前（那条每轮提醒里要报"本 tick 你已收发 N 条信件"）。
    //   ★ **只在有"新"信件时注入**：一条都没有 ⇒ 一个字节都不写会话（不许每轮塞一条空消息——那会污染会话，
    //     也是"每轮现插"与"落盘"混用的错误形态）。
    Optional<LlmMessage> newLetters = newLettersMessage(dm, state, history);
    if (newLetters.isPresent()) {
      conversations.append(conversationId, newLetters.get());
      history.add(newLetters.get());
    }
    // ★ 回合规范提醒**不落盘**、每轮现插：老会话（首轮身份消息里没有新口径）也必须被本轮规则覆盖。
    history.add(decisionRoundNotice(dm, frozen, toolNames, lettersThisTick(dm, state)));
    DecisionTurnSignals signals = new DecisionTurnSignals(toolNames);
    List<ToolInvocation> invocations = new ArrayList<>();
    int llmCalls = 0;
    while (true) {
      if (llmCalls >= maxLlmCalls) {
        // ★ 保留 WARN（用户 2026-10-23：不接受降级）。
        DECISION.warn(
            LogEvent.of(
                "DECISION_RUN_BUDGET_EXCEEDED",
                GaLogSource.GA_DECISION_TURN,
                "dm",
                dm.id().value(),
                "day",
                state.meta().timestamp().tick(),
                "llmCalls",
                llmCalls,
                "tools",
                invocations.size()));
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
      LlmResponse response = llmClient.chat(new LlmRequest(requestMessages(history), toolDefs));
      llmCalls++;
      LlmMessage assistant = response.assistantMessage();
      signals.onAssistantText(response.textPart());
      // ★ 先落盘再判：模型说了什么都要记（非工具轮同样是这一轮的产出）。
      conversations.append(conversationId, assistant);
      history.add(assistant);
      // ★ 进度点 ①：本轮 LLM 已结束（工具调用还没跑）——GUI 的"第 N 轮"就是这个数。
      progressListener.progress(llmCalls, List.copyOf(invocations));
      List<ContentPart.ToolCall> requested = toolCallsOf(assistant);
      if (requested.isEmpty()) {
        DECISION.info(
            LogEvent.of(
                "DECISION_RUN_END",
                GaLogSource.GA_DECISION_TURN,
                "dm",
                dm.id().value(),
                "day",
                state.meta().timestamp().tick(),
                "llmCalls",
                llmCalls,
                "tools",
                invocations.size()));
        return new DecisionTurn(
            conversationId,
            llmCalls,
            invocations,
            response.textPart(),
            signals.submitSeen(),
            signals.commandsAfterSubmit(),
            signals.lastAssistantText(),
            signals.lastTextBeforeSubmit());
      }
      List<LlmMessage> imageMessages = new ArrayList<>();
      for (ContentPart.ToolCall call : requested) {
        // ★ 一次工具调用产出两条消息：tool 消息（永远有）+ 图片消息（有图且有视觉能力时，P4）。
        ToolExecution execution = execute(dm, state, call, invocations, signals);
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
      List<ToolInvocation> invocations,
      DecisionTurnSignals signals) {
    // ★★ **线名 ⇒ 真实名**（`simos_map_hex` → `simos.map.hex`）：权限链、注册表、工具自身**只认真实名**，本层是唯一的翻译点。
    //   ★ **查不到就原样交下去**（不新造"这个名字不在表里"的拒绝理由）：于是 AgentLib 的两条既有判据逐字保留——
    //   真没这个工具 ⇒ `TOOL_NOT_FOUND`（正文是「工具不存在: <模型给的名字>」，模型据此看得见自己叫错了名）；
    //   工具真在注册表里、只是权限组不放它 ⇒ `PERMISSION_DENIED`。本层若抢先拒，后者就再也测不到了（工具面 = 白名单，
    //   凡是能过名字表的都在白名单里），等于用一个更弱的理由顶掉一个更强的判据。
    String toolName = wireNames.realNameOf(call.name()).orElse(call.name());
    signals.onToolCall(toolName);
    if (toolNames.packetSubmit().equals(toolName)) {
      // ★★ 回合统一结算：提交工具在决策回合里只登记，不立刻把 packet 锁成 PENDING——
      //   这样"提交后继续 propose / intent"仍然能写进同一个 DRAFT，回合结束时由 service 层合并提交一次。
      //   真工具本身不改：GM / 其他非回合路径直接调 simos.sd.packet.submit 仍是原语义。
      ToolResult deferred =
          ToolResult.ok(
              "{\"submitted\":false,\"deferred\":true,"
                  + "\"note\":\"已登记：本回合结束时统一提交；可继续补充命令，最后一段自然语言会作为 NL 决策一并交给 GM。\"}");
      return recordResult(dm, toolName, call, deferred, invocations);
    }
    Map<String, Object> args = new LinkedHashMap<>(call.arguments());
    FrozenToolKind kind = null;
    if (frozen != null) {
      kind = frozenToolKinds.get(toolName);
      if (kind == null) {
        // ★ 工具面已经在装配期移除了它；模型若是凭记忆/幻觉点回来，执行口也必须一致地拒（不能靠"模型没看见"当护栏）。
        ToolResult blocked =
            ToolResult.error(
                "FORBIDDEN",
                "同时决策回合 "
                    + frozen.branch().value()
                    + "@"
                    + frozen.revision().value()
                    + " 不允许工具 "
                    + toolName
                    + "：决策期只允许读同一世界快照与写自己的决策包；要改世界请用 "
                    + toolNames.propose()
                    + " 写成 call，由 GM 在所有包收齐后合并成一条 revision 执行。");
        return recordResult(dm, toolName, call, blocked, invocations);
      }
      switch (kind) {
        case WORLD_READ -> {
          // ★ 冻结回合的世界读：模型填的 branch/revision 一律被覆盖，所有决策人看到同一份世界。
          args.put("branch", frozen.branch().value());
          args.put("revision", frozen.revision().value());
        }
        case PACKET_WRITE -> {
          // ★ 只写自己的 packet 前缀：branch 钉死，expectedRevision 剥掉让工具取"执行那一刻"的 head；
          //   这样后一个决策人不会因为前一个决策人的 packet 写而 CONFLICT。
          args.put("branch", frozen.branch().value());
          args.remove("expectedRevision");
        }
        case STATELESS_READ -> {
          // 目录/技能/分支列表这类不读世界 revision 的读口：原样放行。
        }
      }
    }
    // ★ 唯一入口：callerFor **每次现算**（世界变了范围就变），执行走 authorizer 的五段判定链。
    //   ★ toolConfig 是本轮的宿主编排（目前只有"有没有视觉能力"一项）：渲染工具的 auto 靠它选形态。
    ToolResult result;
    if (kind == FrozenToolKind.PACKET_WRITE) {
      // ★ 并发跑轮时"读快照并发、写包串行"：共享锁保证 packet 写各自取到当下 head，不相互冲突。
      synchronized (frozen.packetWriteLock()) {
        result = caller.execute(registry, toolName, dm, state, mapId, args, toolConfig);
      }
    } else {
      result = caller.execute(registry, toolName, dm, state, mapId, args, toolConfig);
    }
    return recordResult(dm, toolName, call, result, invocations);
  }

  /**
   * 把一次工具结局折成回灌消息与本轮账（成功与失败同一条路；见 {@link #execute} 的类注）。
   *
   * <p>★ **同一段文本两处用**（T11C）：既回灌给模型，也记进本轮的账（{@code resultSummary}）——{@code ga.RunDecision}
   * 的轨迹就是靠它报告"决策人看见了什么"。两处若各拼一次，轨迹与实际回灌的内容就会**各说各话**，而没有任何症状。
   */
  private ToolExecution recordResult(
      DecisionMaker dm,
      String toolName,
      ContentPart.ToolCall call,
      ToolResult result,
      List<ToolInvocation> invocations) {
    String feedback = feedbackText(result);
    invocations.add(
        new ToolInvocation(call.id(), toolName, result.success(), result.code(), feedback));
    DECISION.debug(
        LogEvent.of(
            "DECISION_TOOL_CALL",
            GaLogSource.GA_DECISION_TURN,
            "dm",
            dm.id().value(),
            "tool",
            toolName,
            "success",
            result.success(),
            "code",
            result.code()));
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
   * tool 消息里本来就有（渲染工具还会按同一个能力位回落成字符图， 见 {@link DecisionToolNames#visionConfigKey()}）。
   *
   * <p>★★ **消息形状由宿主给**（{@link ToolImages}，B2a 起注入）：媒体类型（{@code ArtifactStore.PNG_MEDIA_TYPE}）
   * 与工件库都住在 {@code simos-app} 的渲染层，{@code ga} 不认识它们 ⇒ 本方法只决定**发不发**（视觉能力 + 真有图资产）， 折消息那一步交给注入的实现。★
   * 与 {@link OpeningSnapshot} 同一条道理：运行流的全部世界知识只该是 {@code SimulationState} 这个不透明句柄。
   */
  private Optional<LlmMessage> imagesMessage(ToolResult result) {
    List<String> assetIds = result.assetDocIds();
    if (!vision || assetIds.isEmpty()) {
      return Optional.empty();
    }
    return toolImages.messageFor(result);
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
      // ★ 保留 WARN（用户 2026-10-23：不接受降级）。
      DECISION.warn(
          LogEvent.of(
              "OPENING_SNAPSHOT_SKIPPED",
              GaLogSource.GA_DECISION_TURN,
              "dm",
              dm.id().value(),
              "reason",
              "no-vision"));
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
   *     #feedbackText}）。★ T11C 起它在账里：{@code ga.RunDecision} 把它当"决策人看见了什么"的载体交给调用方 ——
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
   * <p>★ **为什么给一个类型而不是沿用裸 {@code IllegalStateException}**：{@code ga.RunDecision}
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
      Optional<String> finalText,
      boolean packetSubmitSeen,
      boolean commandsAfterSubmit,
      Optional<String> lastAssistantText,
      Optional<String> lastTextBeforeSubmit) {

    /** 旧 4 参兼容构造器（测试与旧调用点）：没有回合信号的轮次按"无提交、最后文本即整轮文本"处理。 */
    public DecisionTurn(
        String conversationId,
        int llmCalls,
        List<ToolInvocation> toolInvocations,
        Optional<String> finalText) {
      this(
          conversationId, llmCalls, toolInvocations, finalText, false, false, finalText, finalText);
    }

    public DecisionTurn {
      toolInvocations = List.copyOf(toolInvocations);
      Objects.requireNonNull(finalText, "finalText");
      Objects.requireNonNull(lastAssistantText, "lastAssistantText");
      Objects.requireNonNull(lastTextBeforeSubmit, "lastTextBeforeSubmit");
    }

    /** 这一轮有没有用过工具（J11 的"不是被动收简报"就落在这一位上）。 */
    public boolean usedTools() {
      return !toolInvocations.isEmpty();
    }
  }
}
