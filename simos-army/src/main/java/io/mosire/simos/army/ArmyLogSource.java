package io.mosire.simos.army;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>army 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 army 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。army 侧只有交战记录/阶段/裁决写口与编解码/解析三类执行面：
 *
 * <ul>
 *   <li>{@link #ARMY_COMBAT}：交战记录/阶段追加/裁决写口与逐条战损（命令面）——命令入口的工具/人工交互由 app 层自记 {@code
 *       interaction}（用户裁定 origin 不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #ARMY_CODEC}：ArmyCodec 编解码、变更集施加与旧形状迁移；
 *   <li>{@link #ARMY_RESOLVE}：ArmyResolver 候选装配、空结果与故障诊断。
 * </ul>
 */
public enum ArmyLogSource implements LogOrigin {
  ARMY_COMBAT("army-combat", "交战记录/阶段追加/裁决写口与逐条战损（命令面）", LogOriginKind.SYSTEM),
  ARMY_CODEC("army-codec", "ArmyCodec 编解码、变更集施加与旧形状迁移", LogOriginKind.SYSTEM),
  ARMY_RESOLVE("army-resolve", "ArmyResolver 候选装配、空结果与故障诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  ArmyLogSource(String id, String description, LogOriginKind kind) {
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
