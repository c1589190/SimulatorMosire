/**
 * ★★ <b>决策人运行流</b>（2026-10-12 用户原话 ⑤：「把工具运作、决策人运行放到ga模块」）。
 *
 * <p>本包 = 运行流的<b>「怎么跑」</b>：LLM 回合循环、工具面装配（白名单 ∩ 注册表、线格式转义）、 工具调用的裁决（冻结回合的世界读覆盖 / 决策包写 /
 * 世界写拒绝）、回合结算信号与进程内运行留痕。
 *
 * <p><b>包内类型分两半</b>：
 *
 * <ul>
 *   <li><b>运行流本体</b>：{@code DecisionAgentRunner}（回合循环）、{@code DecisionRunRegistry}（进度留痕）、 {@code
 *       DecisionToolDefs}（工具面 → {@code ToolDef}）、{@code DecisionProposable}（可 propose 的预览入口契约）；
 *   <li><b>宿主注入面</b>（本模块定接口、{@code simos-app} 给实现，设计书 §4.5 I-G2）： {@link
 *       io.mosire.simos.ga.decision.DecisionToolCaller}（权限链执行 + 全局白名单）、 {@link
 *       io.mosire.simos.ga.decision.DecisionToolNames}（工具名声明）、 {@link
 *       io.mosire.simos.ga.decision.DecisionToolReach}（可达面：开场消息按它决定点不点某件工具的名）、 {@link
 *       io.mosire.simos.ga.decision.ToolImages}（图片通路）。
 * </ul>
 *
 * <p>★ <b>不在这里的东西</b>（按设计书 §4.5 明确留 {@code simos-app}）：权限范围函数（{@code GovScope} / {@code
 * NationScope} / {@code ArmyScope} / {@code DecisionScopeFunctions}）——它们要看辖区 + 单位 + 外交，
 * 属"装配"而不是"运行"；以及持有域工具实例的 {@code ProposalCatalog}、读世界的 {@code NationOpeningSnapshot}。
 *
 * <p>★ <b>本包不认识 {@code simos-app}</b>：那是 {@code app → ga → app} 环，Maven 建图期直接拒。
 */
package io.mosire.simos.ga.decision;
