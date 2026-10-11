package io.mosire.simos.ga.decision;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Optional;

/**
 * **工具结果里的图片 ⇒ 一条 {@code user} 图片消息**（宿主注入）：运行流需要的是"把图资产折成一条消息"这个**能力**， 不是工件库与媒体类型。
 *
 * <p>★★ <b>为什么不认识媒体类型</b>：{@code assetId} 指的内容寻址工件库与"当前只存 PNG"这个事实都住在 {@code simos-app} 的渲染层（{@code
 * ArtifactStore} / {@code RenderService}）——那正是 {@link DecisionAgentRunner.OpeningSnapshot}
 * 的同一条道理：塞进"循环"里会让本模块同时认识地图、社交、单位与渲染，而它的全部世界知识只该是 {@code SimulationState}
 * 这个不透明句柄。故媒体类型与消息形状由宿主给，运行流只决定**发不发、什么时候发**。
 *
 * <p>★★ <b>两条硬约束由运行流兑现，实现方不必操心</b>：
 *
 * <ol>
 *   <li>图片**不能**附在 {@code tool} 消息上（{@code AgentLib} 对 {@code tool} 角色带图**响亮抛 CONFIG**），
 *       故本接口返回的是一条**独立的 {@code user} 消息**；
 *   <li>它的位置由 {@link DecisionAgentRunner} 定：必须排在本回合**所有** {@code tool} 消息之后（真网关实测的硬约束： {@code
 *       assistant(tool_calls)} 后面必须紧跟回应每一个 {@code tool_call_id} 的 {@code tool} 消息）。
 * </ol>
 *
 * <p>★ <b>调用时机</b>：只有"该 provider 有视觉能力"且工具结果真的带图资产时才会被调用（见运行流的 {@code imagesMessage}）。
 */
@FunctionalInterface
public interface ToolImages {

  /**
   * 什么都不发的实现。
   *
   * <p>★★ <b>装配方若已声明 provider 有视觉能力，就不该传它</b>：那会让"看得见图"退化成"一张都不发"， 而且**没有任何症状**（工具结果的文本摘要仍在 tool
   * 消息里，回合照常跑完）。它服务的是"这条路本来就不出图"的装配 （例如无视觉能力的路由、只测文本通路的夹具）。
   */
  ToolImages NONE = result -> Optional.empty();

  /**
   * @param result 一次工具调用的结局（{@code assetDocIds()} 是这一次渲染出来的图资产引用）
   * @return 图片消息（{@code user} 角色，含 {@code ContentPart.Image}）；没有可给的就空
   */
  Optional<LlmMessage> messageFor(ToolResult result);
}
