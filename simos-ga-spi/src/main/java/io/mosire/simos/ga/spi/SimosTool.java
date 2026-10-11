package io.mosire.simos.ga.spi;

import io.mosire.agentlib.tool.AgentTool;
import java.util.Set;

/**
 * 模拟世界的工具接口：agentlib 的 {@link AgentTool} **收窄 + 角色声明**。
 *
 * <p>★★ <b>它取代今天"三处手抄"</b>（设计书 §4.4「一处声明、三处派生」；现状见 §3 的 F3/F4）：
 *
 * <ol>
 *   <li>GM 桶 —— 今天 {@code SimosToolSource} 里一份 {@code built.add(...)} 清单；
 *   <li>决策人桶 —— 同一份工具在 {@code SimosToolSource} 里**再注册一次**（同一工具两个桶各写一遍）；
 *   <li>{@code DecisionCallerFactory.WHITELIST} —— 44 条工具名字面量。
 * </ol>
 *
 * <p>{@code SimosToolSource} 自己的注释已写明"只改一处 = 留旁路"——本接口就是那个"一处"： 桶与白名单**从 {@link #roles()} 派生**（B4
 * 收口），不许再有第 2/3 处手抄（不变量 I-G3）。
 *
 * <p>★ <b>本批（B1）只立契约</b>：现有 176 个工具类**尚未实现**本接口（它们仍住 {@code app}、 仍靠两桶清单注册）；工具类逐個下放到各域模块时（B3）才实现
 * {@code SimosTool}，收口在 B4 （设计书 §6 批次表）。
 *
 * <p>★ <b>为什么不在这里声明审批相关的方法</b>：审批链按**装配**决定（GM 面 {@code GmAutoApproveGate}、 DM 面 {@code
 * ConfirmGate}），工具只声明"谁能用"（设计书 §4.4 第 4 条）。把它写进接口 = 第二处真相。
 */
public interface SimosTool extends AgentTool {

  /**
   * "这个工具能被谁用"的**唯一拼写点**（设计书 §4.4）。
   *
   * <p>★ 返回**非空**集合：空集 = 谁都看不见 = 装配故障（B4 的收口判据应当把它变成编译期或测试期红， 而不是让它静默地什么都不注册）。★ 集合必须**不可变**（{@code
   * Set.of(...)} / {@code EnumSet} 的副本）， 免得注册面把它当可变容器改出第二份真相。
   *
   * @return 该工具的角色标签；{@code {GM}} / {@code {DM}} / {@code {GM, DM}} 三种合法形态
   */
  Set<ToolRole> roles();
}
