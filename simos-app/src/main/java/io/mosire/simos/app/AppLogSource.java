package io.mosire.simos.app;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>app 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 app 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>：推进/结算/算法执行 = {@link LogOriginKind#TICK}（即使由 GM
 * 工具触发），工具/决策/审批/人工命令 = {@link LogOriginKind#INTERACTION}， 启动/装配/配置读取 = {@link
 * LogOriginKind#SYSTEM}。决策回合内的工具调用用 {@link #DECISION_TURN} + {@code tool=} 字段表达（控制方默认，用户未反对）。
 *
 * <ul>
 *   <li>{@link #SHELL_LIFECYCLE}：组合根启动/装配/关闭/GUI 服务生命周期；
 *   <li>{@link #SHELL_ADVANCE}：组合根发起的时间推进与推进后 drain；
 *   <li>{@link #COMMAND_ENTRY}：GUI/人工命令与工具命令的路由入口；
 *   <li>{@link #TOOL_CALL}：MCP/GM 工具调用（发起/具名拒绝/结果）；
 *   <li>{@link #DECISION_TURN}：LLM 决策回合、逐次工具调用与回合结算；
 *   <li>{@link #APPROVAL}：审批链 pending/approved/denied/timeout；
 *   <li>{@link #DAILY_LOOP}：人口—经济—gov 日循环推进；
 *   <li>{@link #HOUSEHOLD_SYNC}：家户↔单位位置同步与经济行人口投影；
 *   <li>{@link #LLM_CONFIG}：LLM 路由/密钥引用的读取（只记元信息）；
 *   <li>{@link #LLM_CALL}：LLM 调用的响应用量与模型元信息；
 *   <li>{@link #GUI_REQUEST}：GUI HTTP 访问与请求处理。
 * </ul>
 */
public enum AppLogSource implements LogOrigin {
  SHELL_LIFECYCLE("shell-lifecycle", "组合根启动/装配/关闭与 GUI 服务生命周期", LogOriginKind.SYSTEM),
  SHELL_ADVANCE("shell-advance", "组合根发起的时间推进与推进后 drain（算法执行）", LogOriginKind.TICK),
  COMMAND_ENTRY("command-entry", "GUI/人工命令与工具命令的路由入口", LogOriginKind.INTERACTION),
  TOOL_CALL("tool-call", "MCP/GM 工具调用（发起/具名拒绝/结果）", LogOriginKind.INTERACTION),
  DECISION_TURN("decision-turn", "LLM 决策回合、逐次工具调用与回合结算", LogOriginKind.INTERACTION),
  APPROVAL("approval", "审批链 pending/approved/denied/timeout", LogOriginKind.INTERACTION),
  DAILY_LOOP("daily-loop", "人口—经济—gov 日循环推进（算法执行）", LogOriginKind.TICK),
  HOUSEHOLD_SYNC("household-sync", "家户↔单位位置同步与经济行人口投影（算法执行）", LogOriginKind.TICK),
  LLM_CONFIG("llm-config", "LLM 路由/密钥引用的读取（只记元信息）", LogOriginKind.SYSTEM),
  LLM_CALL("llm-call", "LLM 调用的响应用量与模型元信息", LogOriginKind.INTERACTION),
  GUI_REQUEST("gui-request", "GUI HTTP 访问与请求处理（人工交互）", LogOriginKind.INTERACTION);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  AppLogSource(String id, String description, LogOriginKind kind) {
    this.id = id;
    this.description = description;
    this.kind = kind;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public LogOriginKind kind() {
    return kind;
  }
}
