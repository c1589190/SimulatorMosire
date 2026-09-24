package io.mosire.simos.app.decision;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.llm.LlmProviderResolver;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **决策人 agent 运行流的装配点**（T11C）：把 {@link DecisionAgentRunner} 接进壳——「让某个决策人跑一轮」真会调 LLM。
 *
 * <p>★ **它解决的真实缺口**：{@link DecisionAgentRunner} 已交付并关账（判据 J11），但**全仓只有测试调用它**—— 没有生产调用者。本类是那个"接线"，与
 * {@code DecisionAdjudicationService} 同形（同一个壳、同一条 provider 解析链）。
 *
 * <p>★ **为什么每轮现建 runner 而不是持有一个**：{@link DecisionAgentRunner} 的 {@code llmClient} 是**按决策人
 * providerId 解析出来的**（不同的决策人可以绑不同的 provider），而 providerId 是**世界事实**（随 revision 变）⇒
 * 只能在**使用时刻**解析。runner 的其余分量（调用者工厂 / 工具面 / 会话存储 / mapId）与本服务同生命周期，故此处只是"拼起来"。
 *
 * <p>★★ **fail-closed 的第一道在这里**（与 {@link LlmProviderResolver} 的第二道分工）：
 *
 * <ul>
 *   <li>本类管**世界事实**那一半：{@code providerId} 为空（未绑定）⇒ **抛**，绝不落到"某个默认 provider"；
 *   <li>{@link LlmProviderResolver} 管**配置**那一半：名字在当前路由表里查无 ⇒ 抛**点名该 id** 的错，绝不换一条能用的顶上； 路由在场但坏掉 ⇒
 *       AgentLib 的异常原样冒泡。
 * </ul>
 *
 * 两道各自有位：{@code DecisionMaker.providerId} 是"不透明的基础设施引用"（见其类注），**使用时刻必须 fail-closed**，
 * 而"有没有绑"是**世界**说了算（不该问配置）。故本类的检查**不可被注入的客户端来源绕开**——{@link LlmClients} 的替身（用例里的 {@code
 * FakeLlmClient}）只替掉"怎么造客户端"，替不掉"该不该跑"。
 *
 * <p>★ **状态是入参、不是字段**：范围函数与工具读的都是"此刻的世界"，故 {@link #runRound} 每轮从 {@code core.replay} 取（与 {@code
 * DecisionAdjudicationService} 同法）。
 */
public final class DecisionAgentService {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionAgentService.class);

  /** 会话库文件名（落 {@code <store>} 下，与 {@code simos.db} 同目录 ⇒ 跨进程重启沿用同一段会话）。 */
  public static final String CONVERSATIONS_FILE_NAME = "conversations.db";

  /**
   * **决策人的 LLM 客户端来源**（providerId ⇒ 客户端 **+ 它有没有视觉能力**）：生产路径 = {@code
   * LlmProviderResolver::providerFor}（绑上工件解析器）；用例 = 一个回放的 {@code FakeLlmClient}。
   *
   * <p>★★ **它是"怎么造客户端"的接缝，不是"该不该跑"的开关**：未绑定的判定在 {@link #runRound} 里（见类注）， 无论实现是谁都绕不过去。
   *
   * <p>★★ **P4 起它多带回一个能力位**（{@link ProviderLlm}）：链路的图片通路要同时知道"往哪发"与"发不发图"，而这两件事
   * **同住一份路由配置**——分两处各查一次就会漂移（改了一处漏了另一处，两种方向都不会报装配错，见 {@link ProviderLlm} 的类注）。
   */
  @FunctionalInterface
  public interface LlmClients {

    /**
     * @param providerId 决策人绑定的 provider id（**非空**——空值在 {@link #runRound} 就被拒了）
     * @return 该 provider 的客户端**与其能力**（客户端必须是带工件解析器的那条：消息里有图时发送侧要按引用取字节）
     * @throws RuntimeException 名字查无 / 路由坏掉（生产路径 fail-closed，不兜底）
     */
    ProviderLlm providerFor(String providerId);
  }

  private final CoreSimos core;
  private final LlmClients llmClients;
  private final DecisionCallerFactory callerFactory;
  private final ToolRegistry decisionTools;
  private final ConversationStore conversations;
  private final String mapId;
  private final int maxLlmCalls;

  /**
   * **开场快照来源**（P4）：装配方按开关给（{@code Shell} 用渲染件与"国家→首府区域"的取景规则实现）。
   *
   * <p>★ 缺省 {@link DecisionAgentRunner.OpeningSnapshot#NONE} = 关：不装就是既有行为，且**关与"开着但没出图"分得开** （见
   * {@code DecisionAgentRunner.openingSnapshotMessage} 的注释）。
   */
  private final DecisionAgentRunner.OpeningSnapshot openingSnapshot;

  /**
   * @param core 唯一写入口（读状态经它的只读 {@code replay}；写仍只发生在运行流内部的 {@code CoreSimos.submit}）
   * @param llmClients providerId ⇒ 客户端 + 能力（生产路径 = {@link LlmProviderResolver}）
   * @param callerFactory 决策人调用者工厂（范围**每次现算**；其 {@code whitelist()} 同时是"给模型看的工具面"的来源）
   * @param decisionTools **决策人桶**的注册表（工具面与执行都从它取）
   * @param conversations 会话存储（跨 tick / 跨重启沿用同一段会话）
   * @param mapId 本世界的 map 称谓（范围函数要它拼资源前缀）
   */
  public DecisionAgentService(
      CoreSimos core,
      LlmClients llmClients,
      DecisionCallerFactory callerFactory,
      ToolRegistry decisionTools,
      ConversationStore conversations,
      String mapId) {
    this(
        core,
        llmClients,
        callerFactory,
        decisionTools,
        conversations,
        mapId,
        DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS);
  }

  /** 显式给回合上限的形态（用例要测"跑飞会被中止"就得把它压小）。 */
  public DecisionAgentService(
      CoreSimos core,
      LlmClients llmClients,
      DecisionCallerFactory callerFactory,
      ToolRegistry decisionTools,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls) {
    this(
        core,
        llmClients,
        callerFactory,
        decisionTools,
        conversations,
        mapId,
        maxLlmCalls,
        DecisionAgentRunner.OpeningSnapshot.NONE);
  }

  /** **全参**（P4 生产路径）：另给开场快照来源（{@link DecisionAgentRunner.OpeningSnapshot}；{@code NONE} = 关）。 */
  public DecisionAgentService(
      CoreSimos core,
      LlmClients llmClients,
      DecisionCallerFactory callerFactory,
      ToolRegistry decisionTools,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls,
      DecisionAgentRunner.OpeningSnapshot openingSnapshot) {
    this.core = Objects.requireNonNull(core, "core");
    this.llmClients = Objects.requireNonNull(llmClients, "llmClients");
    this.callerFactory = Objects.requireNonNull(callerFactory, "callerFactory");
    this.decisionTools = Objects.requireNonNull(decisionTools, "decisionTools");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.maxLlmCalls = maxLlmCalls;
    this.openingSnapshot = Objects.requireNonNull(openingSnapshot, "openingSnapshot");
  }

  /**
   * **让某个决策人在给定的世界版本上跑一轮**（LLM ↔ 工具，直到模型不再请求工具调用）。
   *
   * <p>★ **决策人取自那个版本的世界**（不是调用方手里那份）：这样"跑的是谁"与"它看到的世界"必然同源， 且调用方只需给 id + 坐标（窄工具的载荷正是这两样）。
   *
   * @param branch 分支
   * @param revision 世界版本（权威取值点 —— 工具在**触发事实落盘之后**用新 head 调这里）
   * @param decisionMakerId 决策人 id（查无 ⇒ 抛）
   * @return 本轮的账（见 {@link DecisionAgentRunner.DecisionTurn}）
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人（或 sd 切片缺失——装配故障）
   * @throws IllegalStateException 该决策人**未绑定** LLM provider（fail-closed，见类注）
   * @throws DecisionAgentRunner.TurnBudgetExceeded 撞上回合预算
   */
  public DecisionAgentRunner.DecisionTurn runRound(
      BranchId branch, RevisionId revision, DecisionMakerId decisionMakerId) {
    return runRound(branch, revision, decisionMakerId, DecisionAgentRunner.ProgressListener.NONE);
  }

  /**
   * **同上，但把进度推给 {@code listener}**（GUI 异步跑那条用；GM 经 MCP 的那条走上面那个重载 ⇒ 行为逐字不变）。
   *
   * <p>★ 两条路**只有"装不装监听"这一个差别**（同一个 {@link DecisionAgentRunner} 构造器链）：若各写一份装配， 就会出现"两条路跑出两个略有差异的
   * runner"这种**无症状**的漂移。
   */
  public DecisionAgentRunner.DecisionTurn runRound(
      BranchId branch,
      RevisionId revision,
      DecisionMakerId decisionMakerId,
      DecisionAgentRunner.ProgressListener listener) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(listener, "listener");
    SimulationState state = core.replay(new StateRef(branch, revision));
    DecisionMaker maker = ToolSupport.sdState(state).decisionMakers().get(decisionMakerId);
    if (maker == null) {
      throw new IllegalArgumentException("决策人不存在: " + decisionMakerId.value());
    }
    ProviderLlm provider = llmClients.providerFor(requireProviderId(maker));
    // ★★ P4：vision 与快照都从这一个值里出（能力与客户端同源，见 LlmClients 的类注）——图发不发、
    //   渲染工具的 auto 落在图还是字符图、开场快照注不注，三处**同一位**说了算。
    DecisionAgentRunner runner =
        new DecisionAgentRunner(
            callerFactory,
            decisionTools,
            provider.client(),
            conversations,
            mapId,
            maxLlmCalls,
            listener,
            provider.vision(),
            openingSnapshot);
    try {
      DecisionAgentRunner.DecisionTurn turn = runner.run(maker, state);
      // ★ 一轮的**一行留痕**（运维/验收要看"哪个 provider 真被调、用了几轮、调了什么"）：只打名字与计数，不打内容
      //   （内容在轨迹与会话里，且**绝不打密钥**——本行没有任何配置值）。
      LOG.info(
          "决策人 agent 一轮完成 decisionMakerId={} model={} vision={} llmCalls={} toolCalls={} conversationId={}",
          maker.id().value(),
          provider.client().model(),
          provider.vision(),
          turn.llmCalls(),
          turn.toolInvocations().size(),
          turn.conversationId());
      return turn;
    } catch (DecisionAgentRunner.TurnBudgetExceeded e) {
      LOG.warn(
          "决策人 agent 撞上回合预算 decisionMakerId={} model={} llmCalls={}",
          maker.id().value(),
          provider.client().model(),
          e.llmCalls());
      throw e;
    }
  }

  /**
   * ★★ **往某决策人的会话里追加一条 user 消息**（2026-09-23，用户要的"文本框"）：界面上的输入框就是这条。
   *
   * <p>★★ **空会话要先补身份消息**（顺序不能反）：{@link DecisionAgentRunner} 只在**会话为空**时注入身份（{@code
   * openingSystemMessage}），而"第一轮之前就先说一句话"是用户的明确要求（"不管是第一轮还是最后一轮都可以"）。若这里直接 追加 user 消息，会话就**不再为空** ⇒
   * runner 认为身份已经说过了 ⇒ 模型收到一段**没有 system 消息**的上下文 （和现场那次 {@code HTTP 400: field messages is
   * required} 是同一族的病：模型不知道"我是谁"）。 故这里先把身份消息落盘，再落 user 消息 ⇒ 顺序恒为 {@code system → user…}。
   *
   * <p>★ **不改世界**：会话库是 append-only 的旁路存储（不在 {@code revision} 里），故本方法**不**经 {@code
   * CoreSimos.submit}——它记的不是世界事实（与 {@code /api/sd/run-decision} 的"轨迹不落盘"同一口径）。
   *
   * @param ref 世界版本（**用来查会话世代**：{@code sd.ResetDecisionMakerConversation} 会把它改掉）
   * @param decisionMakerId 决策人 id（查无 ⇒ 抛，与 {@link #conversationIdOf} 同口径）
   * @param text 用户那句话（空白 ⇒ 抛：不落一条空消息下去）
   * @return 这条消息落进的会话 id（界面据它显示"说给哪一段会话听"）
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人；或 {@code text} 为空白
   */
  public String appendUserMessage(StateRef ref, DecisionMakerId decisionMakerId, String text) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("消息不得为空");
    }
    DecisionMaker maker =
        ToolSupport.sdState(core.replay(ref)).decisionMakers().get(decisionMakerId);
    if (maker == null) {
      throw new IllegalArgumentException("决策人不存在: " + decisionMakerId.value());
    }
    String conversationId = DecisionAgentRunner.conversationIdOf(maker);
    if (conversations.load(conversationId).isEmpty()) {
      conversations.append(conversationId, DecisionAgentRunner.openingSystemMessage(maker));
    }
    conversations.append(conversationId, LlmMessage.user(text));
    return conversationId;
  }

  /**
   * **某个世界版本上，该决策人此刻该用的会话 id**（触发工具在**失败**路径上也要报它：中止/未绑定时历史仍已落盘，下一轮可续）。
   *
   * <p>★★ **它必须按世界事实算，不能只按 id 算**：会话 id 由「id + 会话世代」派生，而世代是**世界事实**——被 {@code
   * sd.ResetDecisionMakerConversation} 改过之后，同一个 id 的会话已经换了一段。拿"id 拼前缀"糊一个 id 出去（旧签名
   * 就是这么做的）会在重置之后**报一个早已作废的会话 id**，而调用方（GM / AAR）看到的是一句**看起来很正常**的话。
   *
   * <p>★ **查无 ⇒ 抛**（不兜一个"看起来像"的 id 出去）：调用方本来就要处理"决策人不存在"这条失败路径，如实报它比编一个 id 好。
   *
   * @param ref 世界版本（失败路径上报的是"这一轮本该用哪段会话"，故取触发事实落盘后的那个 ref）
   * @param decisionMakerId 决策人 id
   * @throws IllegalArgumentException 该版本的世界里没有这个决策人（或 sd 切片缺失——装配故障）
   */
  public String conversationIdOf(StateRef ref, DecisionMakerId decisionMakerId) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    DecisionMaker maker =
        ToolSupport.sdState(core.replay(ref)).decisionMakers().get(decisionMakerId);
    if (maker == null) {
      throw new IllegalArgumentException("决策人不存在: " + decisionMakerId.value());
    }
    return DecisionAgentRunner.conversationIdOf(maker);
  }

  /**
   * ★★ **fail-closed 的第一道**（见类注）：未绑定 ⇒ **抛**，**绝不**落到某个默认 provider。
   *
   * <p>★ 判据是 {@code Optional} 的**空**（"世界说没绑"）而不是字符串空白：{@code DecisionMaker} 的构造期不变式已保证 "空白必用 {@code
   * Optional.empty()} 表达"，故字符串那一半由 sd 模型层守着，本类不重复判。
   */
  private static String requireProviderId(DecisionMaker maker) {
    return maker
        .providerId()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    LlmProviderResolver.E_UNBOUND
                        + ": 决策人 "
                        + maker.id().value()
                        + " 未绑定 LLM provider（providerId 为空）——本项绝不落到默认 provider"));
  }
}
