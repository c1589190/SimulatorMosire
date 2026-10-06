package io.mosire.simos.unit;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>unit 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 unit 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。unit 侧执行面分四类：
 *
 * <ul>
 *   <li>{@link #UNIT_COMMAND}：单位/编制/公务/路线写命令处理。命令入口的工具/人工交互由 app 层自记 {@code interaction}（用户裁定
 *       origin 不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #UNIT_ADVANCE}：单位每 tick 推进——在途移动/整支搬运/回归重规划（tick 算法）；tick 类事件必带 {@code day}；
 *   <li>{@link #UNIT_CODEC}：UnitCodec 编解码与变更集施加；
 *   <li>{@link #UNIT_RESOLVE}：UnitResolver 候选装配与空结果诊断。
 * </ul>
 *
 * <p>★ 禁止把 {@link #UNIT_COMMAND} 标成 {@link LogOriginKind#INTERACTION}：交互来源由 app 层在入口处另记，本表只描述领域执行面。
 */
public enum UnitLogSource implements LogOrigin {
  UNIT_COMMAND("unit-command", "单位/编制/公务/路线写命令处理（命令面）", LogOriginKind.SYSTEM),
  UNIT_ADVANCE("unit-advance", "单位每 tick 推进：在途移动/整支搬运/回归重规划（tick 算法）", LogOriginKind.TICK),
  UNIT_CODEC("unit-codec", "UnitCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  UNIT_RESOLVE("unit-resolve", "UnitResolver 候选装配与空结果诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  UnitLogSource(String id, String description, LogOriginKind kind) {
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
