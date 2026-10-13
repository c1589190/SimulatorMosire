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
 *   <li>{@link #ARMY_REGISTRY}：<b>军队身份</b>（{@code armies} 表）的建档与主子改派写口（命令面）——2026-10-12 批次 3d-1 新增
 *       （用户裁定「战斗模块，包括阶段之类的，归 Army 模块管」⇒ 军队身份与两条命令自 {@code sd} 迁入）。★ 它替代了迁入前那两条命令用的 {@code
 *       SdLogSource.SD_NATION}（{@code sd-nation}："国家/军队/决策人生命周期"）——军队身份不再住 sd，来源表随之换家； 同 {@link
 *       #ARMY_COMBAT}，工具/人工交互仍由 app 层自记，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #ARMY_CODEC}：ArmyCodec 编解码、变更集施加与旧形状迁移；
 *   <li>{@link #ARMY_RESOLVE}：ArmyResolver 候选装配、空结果与故障诊断。
 * </ul>
 */
public enum ArmyLogSource implements LogOrigin {
  ARMY_COMBAT("army-combat", "交战记录/阶段追加/裁决写口与逐条战损（命令面）", LogOriginKind.SYSTEM),
  ARMY_REGISTRY("army-registry", "军队身份（armies 表）的建档与主子改派写口（命令面）", LogOriginKind.SYSTEM),
  /**
   * ★ 2026-10-13 急停协议新增：{@code ArmyTimeParticipant} 的<b>两军相遇判据</b>（逐日判定 / 命中上报 / 跳过原因）。
   *
   * <p>它记的是"这个模块怎么监控、什么算突发事件"（用户原话：「这些条件之类都是可自定义、<b>具体条件交给对应模块具体维护</b>」）—— 急停本身由 core
   * 负责，本来源只记模块侧的判定，故仍是 {@link LogOriginKind#SYSTEM}。
   */
  ARMY_CONTACT("army-contact", "两军相遇判据（ArmyTimeParticipant 逐日判定、命中上报与跳过原因）", LogOriginKind.SYSTEM),
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
