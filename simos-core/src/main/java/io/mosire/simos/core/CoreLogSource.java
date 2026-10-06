package io.mosire.simos.core;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>core 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 core 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>：推进/批执行 = {@link LogOriginKind#TICK}， 命令入口 = {@link
 * LogOriginKind#INTERACTION}，装配/检查点 = {@link LogOriginKind#SYSTEM}。
 *
 * <ul>
 *   <li>{@link #CORE_LIFECYCLE}：core 引擎打开/装配/创世/关闭；
 *   <li>{@link #TICK_ADVANCE}：时间推进六阶段与参与者结算（算法执行）；
 *   <li>{@link #COMMAND_ENTRY}：命令信封/批的接收与单条命令结局（人工/Agent 命令入口）；
 *   <li>{@link #BATCH_EXECUTION}：命令批的领域执行与批级结局（算法执行）；
 *   <li>{@link #CHECKPOINT}：检查点读写与缺失回退。
 * </ul>
 */
public enum CoreLogSource implements LogOrigin {
  CORE_LIFECYCLE("core-lifecycle", "core 引擎打开/装配/创世/关闭", LogOriginKind.SYSTEM),
  TICK_ADVANCE("tick-advance", "时间推进六阶段与参与者结算（算法执行）", LogOriginKind.TICK),
  COMMAND_ENTRY("command-entry", "命令信封/批的接收与单条命令结局（人工/Agent 命令入口）", LogOriginKind.INTERACTION),
  BATCH_EXECUTION("batch-execution", "命令批的领域执行与批级结局（算法执行）", LogOriginKind.TICK),
  CHECKPOINT("checkpoint", "检查点读写与缺失回退", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  CoreLogSource(String id, String description, LogOriginKind kind) {
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
