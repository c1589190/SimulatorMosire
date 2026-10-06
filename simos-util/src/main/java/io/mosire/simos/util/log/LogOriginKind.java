package io.mosire.simos.util.log;

/**
 * ★★ <b>日志来源的粗分类</b>（2026-10-23 用户裁定：接受 kind = tick / interaction / system）。
 *
 * <p>它回答"这条事件是<b>哪一类工作</b>产生的"，用于跨模块粗筛：
 *
 * <ul>
 *   <li>{@link #TICK} —— 算法推进/结算/逐日计算（即使由 GM 工具手动触发，计算本身仍归这一档）；
 *   <li>{@link #INTERACTION} —— 与人/LLM 的主动交互（工具调用、决策回合、审批、人工命令入口）；
 *   <li>{@link #SYSTEM} —— 启动/装配/检查点/配置读取这类系统动作。
 * </ul>
 *
 * <p>★ <b>本类型零领域知识</b>（2026-10-09 用户裁定）：它不知道任何模块、事件名、logger 名，也不持有开关； 具体来源表由各模块自建（{@link LogOrigin}
 * 的实现）。渲染成线格式时取 {@link #name()} 的<b>小写</b>。
 */
public enum LogOriginKind {
  /** 算法推进/结算/逐日计算。 */
  TICK,
  /** 与人/LLM 的主动交互（工具、决策、审批、人工命令）。 */
  INTERACTION,
  /** 启动/装配/检查点/配置读取等系统动作。 */
  SYSTEM
}
