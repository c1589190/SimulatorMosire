/**
 * CoreSimos —— 唯一知晓全部模块的集成点。
 *
 * <p>时间线 DAG、两阶段时间推进、Command Bus、存储、可观测性、AgentBinding、GUI 与 MCP。
 *
 * <p><b>硬约束</b>：只负责组合与调度，不重新实现任何领域逻辑。GUI / MCP / Agent / 玩家
 * 全部走同一个 Command 入口。
 */
package io.mosire.simos.core;
