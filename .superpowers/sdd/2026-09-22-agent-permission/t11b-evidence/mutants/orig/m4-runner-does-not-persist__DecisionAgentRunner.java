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
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * <p>★ **每一条消息都落盘**（不只是有工具调用的那一轮）：非工具轮（模型给的纯文本）也是这一轮的实际产出， 不记会让下一 tick
 * 的上下文缺一段自相矛盾的空白。落盘在**请求之前**不成立、在**响应之后**才成立——所以先 append 再进下一轮。
 *
 * <p>★ **回合预算**（{@link #DEFAULT_MAX_LLM_CALLS}）：spec 只写了 {@code while(true)}，但模型可以无限请求工具， 而 {@code
 * run} 没有别的出口 ⇒ 本实现给一个**兜底上限**、超限**响亮失败**（不是静默截断——静默截断会产出一个"看起来正常但没做决策" 的回合）。历史已逐条落盘，故中止不丢上下文。
 */
public final class DecisionAgentRunner {

  /** 会话 id 前缀（与 {@link DecisionCallerFactory#INSTANCE_ID_PREFIX} 同源：都按决策人派生）。 */
  public static final String CONVERSATION_ID_PREFIX = DecisionCallerFactory.INSTANCE_ID_PREFIX;

  /**
   * 一轮 {@code run} 允许的最大 LLM 调用次数（**本实现自设的兜底**，spec 未规定）。
   *
   * <p>取 8：一次"查几个格 + 出令"的正常决策用 3~4 次（每次工具调用各占一次），8 给足余量又能在跑飞时及时止损。
   */
  public static final int DEFAULT_MAX_LLM_CALLS = 8;

  private final DecisionCallerFactory callerFactory;
  private final ToolRegistry registry;
  private final LlmClient llmClient;
  private final ConversationStore conversations;
  private final String mapId;
  private final int maxLlmCalls;

  /**
   * @param callerFactory 决策人调用者工厂（范围**每次现算**；其 {@code whitelist()} 同时是"给模型看的工具面"的来源）
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

  /** 显式给回合上限的形态（用例要测"跑飞会被中止"就得把它压小）。 */
  public DecisionAgentRunner(
      DecisionCallerFactory callerFactory,
      ToolRegistry registry,
      LlmClient llmClient,
      ConversationStore conversations,
      String mapId,
      int maxLlmCalls) {
    this.callerFactory = Objects.requireNonNull(callerFactory, "callerFactory");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    if (maxLlmCalls <= 0) {
      throw new IllegalArgumentException("maxLlmCalls 必须为正: " + maxLlmCalls);
    }
    this.maxLlmCalls = maxLlmCalls;
  }

  /** 会话 id：{@code "decision-maker:" + <决策人 id>}（**唯一拼写点**，用例与装配都从这里取）。 */
  public static String conversationIdOf(String decisionMakerId) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    return CONVERSATION_ID_PREFIX + decisionMakerId;
  }

  public static String conversationIdOf(DecisionMakerId decisionMakerId) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    return conversationIdOf(decisionMakerId.value());
  }

  /**
   * **跑一轮**：LLM ↔ 工具，直到模型不再请求工具调用（或撞上回合预算）。
   *
   * <p>★ **状态是入参、不是字段**：范围每次现算要的是"此刻的世界"，把它挂在字段上就会变成"这个 runner 绑在某个时刻上"。
   *
   * @param dm 这一轮的决策人（会话 id 由它派生）
   * @param state 此刻的世界状态（范围函数与工具读的都是它）
   * @return 本轮的账（调了几次模型、真的执行了哪些工具、最后一句文本）
   * @throws IllegalStateException 撞上回合预算（见类注：响亮失败，历史已落盘）
   */
  public DecisionTurn run(DecisionMaker dm, SimulationState state) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    String conversationId = conversationIdOf(dm.id());
    // ★ 工具面与权限组**同一份数据**（spec §2.3 要点 1）：两者若各有一张表，错位不会有任何症状。
    List<ToolDef> toolDefs = DecisionToolDefs.requireAll(registry, callerFactory.whitelist());
    List<LlmMessage> history = new ArrayList<>(conversations.load(conversationId));
    List<ToolInvocation> invocations = new ArrayList<>();
    int llmCalls = 0;
    while (true) {
      if (llmCalls >= maxLlmCalls) {
        throw new IllegalStateException(
            "决策人 "
                + dm.id().value()
                + " 在 "
                + maxLlmCalls
                + " 次 LLM 调用内仍持续请求工具（会话 "
                + conversationId
                + "）——疑似跑飞，本轮中止；历史已逐条落盘，下一 tick 可续");
      }
      LlmResponse response = llmClient.chat(new LlmRequest(List.copyOf(history), toolDefs));
      llmCalls++;
      LlmMessage assistant = response.assistantMessage();
      // ★ 先落盘再判：模型说了什么都要记（非工具轮同样是这一轮的产出）。
      conversations.append(conversationId, assistant);
      history.add(assistant);
      List<ContentPart.ToolCall> requested = toolCallsOf(assistant);
      if (requested.isEmpty()) {
        return new DecisionTurn(conversationId, llmCalls, invocations, response.textPart());
      }
      for (ContentPart.ToolCall call : requested) {
        LlmMessage toolMessage = execute(dm, state, call, invocations);
        conversations.append(conversationId, toolMessage);
        history.add(toolMessage);
      }
    }
  }

  /**
   * 执行**一次**模型请求的工具调用，并把它折成回灌给模型的那条 tool 消息。
   *
   * <p>★ **失败也回灌**（{@code ToolResult.error} 的码与文本原样进 {@code error}/{@code content}，{@code
   * isError()} 为真）：AgentLib 特意把 {@code RESOURCE_DENIED} / {@code APPROVAL_DENIED}
   * 与别的失败分开，就是为了让模型**知道该换个 资源还是换个参数**——把它折成一句"调用失败"是把这个意图丢掉（T10 实测发现 2 的同一条道理）。
   */
  private LlmMessage execute(
      DecisionMaker dm,
      SimulationState state,
      ContentPart.ToolCall call,
      List<ToolInvocation> invocations) {
    // ★ 唯一入口：callerFor **每次现算**（世界变了范围就变），执行走 authorizer 的五段判定链。
    ToolResult result =
        callerFactory.execute(registry, call.name(), dm, state, mapId, call.arguments());
    invocations.add(new ToolInvocation(call.id(), call.name(), result.success(), result.code()));
    // ★ ToolResult 的**两个字段互斥且必居其一**（ContentPart.ToolResult 构造期强制：content 与 error
    //   **恰好一个非 null**）⇒ 成功走 content，失败走 error，且失败时把**码**一起带上——模型据此才知道
    //   该换个资源（RESOURCE_DENIED）还是换个参数（BAD_REQUEST）。
    return LlmMessage.tool(
        result.success()
            ? new ContentPart.ToolResult(call.id(), call.name(), result.message(), null)
            : new ContentPart.ToolResult(
                call.id(), call.name(), null, result.code() + ": " + result.message()));
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

  /** 一次工具调用的事实（**不是**工具的输出——输出在会话里，这个只是本轮的账）。 */
  public record ToolInvocation(String toolCallId, String toolName, boolean success, String code) {}

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
