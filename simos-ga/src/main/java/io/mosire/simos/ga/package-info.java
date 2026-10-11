/**
 * ★★ <b>simos-ga —— 推演框架（运行流）</b>（2026-10-12 用户原话 ⑤：「把工具运作、决策人运行放到ga模块」）。
 *
 * <p>本模块只做<b>「怎么跑」</b>：工具运作（工具面装配、审批链、执行桥）+ 决策人运行（LLM 回合、工具调用裁决、决策包结算）。
 * 它<b>不知道「有哪些域」</b>——需要"宿主的东西"（工具名声明、权限链执行、图片通路、地图 id、宿主编排配置…）时， 一律由<b>构造期注入</b>：接口定义在本模块，实现在
 * {@code simos-app}（设计书 {@code docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md}
 * §4.1 / §4.5 / I-G2）。
 *
 * <p><b>依赖方向是单向的</b>：{@code app ──▶ ga ──▶ { core, sd, util, ga-spi, agentlib }}。 <b>本模块永不依赖 {@code
 * simos-app}</b>（否则 {@code app → ga → app} 成环，Maven 建图期直接拒）。
 *
 * <p><b>包结构</b>：
 *
 * <ul>
 *   <li>{@code io.mosire.simos.ga}：模块级设施（日志门面 {@code GaLog} / 来源表 {@code GaLogSource}）；
 *   <li>{@code io.mosire.simos.ga.llm}：LLM 线格式的工具名映射（{@code LlmToolNames}）——"工具运作"的供应商方言面；
 *   <li>{@code io.mosire.simos.ga.decision}：决策人运行流（回合循环、工具面装配、冻结回合、运行留痕、工具面契约）。
 * </ul>
 *
 * <p>★ <b>本批（B2a）的边界</b>：只落地"决策人运行"那一半（{@code ga.decision} 的四件 + 三个注入接口 + 日志门面）。工具下放（B3）、{@code
 * SimosToolSource} / 审批链迁入（B2b）随后 —— 在那之前， 本模块不持有任何域工具实例、不读世界（世界以 {@code SimulationState} 不透明句柄出现）。
 */
package io.mosire.simos.ga;
