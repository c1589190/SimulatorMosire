package io.mosire.simos.ga.decision;

import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Set;

/**
 * **决策人工具调用的执行面**（宿主注入）：运行流需要的是"经权限链执行一次工具调用"这个**能力**， 不是 {@code simos-app} 的那个工厂类。
 *
 * <p>★★ <b>为什么是本接口</b>（设计书 §1 用户原话②「app只负责组装」/ §4.5 / I-G2）：实现在 {@code simos-app} （{@code
 * DecisionCallerFactory}）——它持有范围函数、审批编排器与 {@code AgentLib} {@code ToolCallAuthorizer}，而运行流在 {@code
 * simos-ga}。{@code ga} 依赖 {@code app} 会成环 （{@code app → ga → app}），故方向只能是「{@code ga} 定接口，{@code
 * app} 给实现」。
 *
 * <p>★★ <b>执行必须经实现方的权限链，不得绕过</b>：{@code AgentLib} 的资源判定者只在 {@code ToolCallAuthorizer} 的判定链第 ③ 段注入 ⇒
 * 手工拼一个 {@code ToolContext} 后直接 {@code tool.execute(ctx)} 会让每个调 {@code require} 的工具
 * `RESOURCE_DENIED`（那是"没有判定者"的 fail-closed 缺省，不是"拒"）。 本接口只暴露"给一次调用"的形态，正是为了不给运行流留下绕过的余地。
 *
 * <p>★ <b>范围每次现算</b>：{@code state} 是方法入参而不是实现的状态 —— 决策人的可见范围随世界变（国家圈地、军队移动）， 范围函数必须看"此刻的世界"。
 */
public interface DecisionToolCaller {

  /**
   * **全局（缺省）工具白名单**：per-DM 的 {@code allowedTools} 为空时沿用的那一份。
   *
   * <p>★ 它服务的是运行流的**旧构造形态**（不带显式白名单的那些）：生产路径一律把 {@code whitelistFor(dm)}
   * 的结果**显式**传给运行流（"给模型看的工具面"与"权限组放行的"因此同源）。
   */
  Set<String> whitelist();

  /**
   * 以决策人自己的权限执行一次工具调用（判定链五段全走一遍，与 MCP 口同一条路）。
   *
   * @param registry 工具注册表（生产路径给**决策人桶**的注册表）
   * @param toolName 工具名（模型给出；不受信）
   * @param dm 这一轮的决策人（范围、白名单与身份都由它派生）
   * @param state 此刻的世界（范围现算的输入）
   * @param mapId 本世界的 map 称谓（范围函数要它拼资源前缀）
   * @param args 本次调用的参数（模型填入的 JSON）
   * @param toolConfig **宿主编排配置**（进 {@code ToolContext#config()}；目前唯一用途是让渲染工具按视觉能力选形态）
   * @return 工具结局（成功与失败都在这里，失败带码——运行流把它原样回灌给模型）
   */
  ToolResult execute(
      ToolRegistry registry,
      String toolName,
      DecisionMaker dm,
      SimulationState state,
      String mapId,
      Map<String, Object> args,
      Map<String, Object> toolConfig);
}
