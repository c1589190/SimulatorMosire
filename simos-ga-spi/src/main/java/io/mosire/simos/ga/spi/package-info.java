/**
 * {@code simos-ga-spi} —— 模拟世界的**工具契约层**（2026-10-12 B1 新增）。
 *
 * <p>本模块装四类东西，**全部是契约，零逻辑、零状态、零领域知识**：
 *
 * <ol>
 *   <li>{@link io.mosire.simos.ga.spi.SimosTool}：工具接口（{@code AgentTool} + 角色声明）；
 *   <li>{@link io.mosire.simos.ga.spi.ToolRole}：角色标签 {@code {GM, DM}}——"这个工具能被谁用"的**唯一拼写点**；
 *   <li>{@link io.mosire.simos.ga.spi.GmOnlyRead}：GM-only 读工具标记（自 app 迁入）；
 *   <li>{@link io.mosire.simos.ga.spi.DecisionProposable}：**可被决策人 propose 的声明**（批 K ①，自 {@code
 *       simos-ga} 迁入） ——**工具自己实现它**即可被提议，"可提议名单"不再手抄（用户 2026-10-15：「为啥额外要张表？」）；
 *   <li>{@link io.mosire.simos.ga.spi.ToolWritePort}：窄写口（替代工具直连 {@code CoreSimos}） 与 {@link
 *       io.mosire.simos.ga.spi.ToolResources} / {@link io.mosire.simos.ga.spi.ToolSchema} 两个纯函数助手（自
 *       app 的 {@code ToolSupport} 拆出）。
 * </ol>
 *
 * <p>★★ <b>依赖位置</b>（设计书 {@code docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md}
 * §4.1 目标依赖图）：本模块紧贴 {@code util} 之上，**只依赖 {@code simos-util} 与 {@code agentlib-mosire}**。这是用户原话 ④
 * 的答案——
 *
 * <blockquote>
 *
 * 「不是不是，为啥其他模块要写工具不能直接依赖ga这一整个模块啊？还是得写个ga-spi才满意」
 *
 * </blockquote>
 *
 * <p>域模块依赖 {@code ga-spi} 而**不是** {@code ga}：{@code ga} 必须依赖各域（它要跑回合、读状态）， 域再依赖 {@code ga} ⇒
 * 环，Maven 建图期直接拒。契约层是唯一出路——与既有的 {@code actor-api} / {@code economy-api} / {@code social-api} 同一形制。
 *
 * <p>★ <b>本模块**不许依赖 {@code core}**（设计书 §4.2 末段 / 不变量 I-G2）</b>：{@code core} 的 test scope 依赖 {@code
 * map}/{@code social}/{@code unit} ⇒ 一旦依赖 {@code core}， {@code map → ga-spi → core → map(test)}
 * 会成环。故窄写口只用 util 类型表达（见 {@link io.mosire.simos.ga.spi.ToolWritePort} 的类注）。
 *
 * <p>★ <b>本批（B1）只立契约、不改实现</b>：现有 176 个工具类仍住 {@code app}、仍直连 {@code CoreSimos}、尚未实现 {@link
 * io.mosire.simos.ga.spi.SimosTool}；工具下放与窄写口接线属 B2/B3/B4 （设计书 §6 批次表）。
 */
package io.mosire.simos.ga.spi;
