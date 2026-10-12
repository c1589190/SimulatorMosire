package io.mosire.simos.sd;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>sd 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 sd 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。sd 侧执行面分七类：
 *
 * <ul>
 *   <li>{@link #SD_DECISION}：决策通道/裁决/令/判决/决策人运行（命令与裁决面）——命令入口的工具/人工交互由 app 层自记 {@code
 *       interaction}（用户裁定 origin 不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #SD_NATION}：国家/军队/决策人生命周期（命令面）；
 *   <li>{@link #SD_COMBAT}：交战记录/阶段/伤亡写命令（命令面）——★ <b>2026-10-12 批次 3d-2 起无生产者</b>：旧战斗族五条命令
 *       整族退役（用户裁定「战斗模块，包括阶段之类的，归 Army 模块管」＋「sd 整个弃用！」），交战记录归 army。表项 id 是稳定 id
 *       （发布后不改），故<b>本批不删</b>该档，否则等于悄悄改模块的日志来源表；留到批次 3e（sd 空壳退役）与 {@code SdLog.combat()} 一并收口；
 *   <li>{@link #SD_DIPLOMACY}：外交关系与外交事件（命令面）；
 *   <li>{@link #SD_TICK}：每 tick 推进——effect 触发、逐日结算（tick 算法，必带 {@code day}）；
 *   <li>{@link #SD_CODEC}：SdCodec 编解码、变更集施加与旧形状迁移（只记元信息，不记 JSON 原文）；
 *   <li>{@link #SD_RESOLVE}：SdResolver 候选装配与空结果诊断（不逐次记成功查询）。
 * </ul>
 *
 * <p>★ 禁止把命令面（handler）标成 {@link LogOriginKind#INTERACTION}：交互来源由 app 层在入口处另记，本表只描述领域执行面。
 */
public enum SdLogSource implements LogOrigin {
  SD_DECISION("sd-decision", "决策通道/裁决/令/判决/决策人运行（命令与裁决面）", LogOriginKind.SYSTEM),
  SD_NATION("sd-nation", "国家/军队/决策人生命周期（命令面）", LogOriginKind.SYSTEM),
  /** ★ 2026-10-12 批次 3d-2 起<b>无生产者</b>（旧战斗族退役）；表项保留的理由见类注。 */
  SD_COMBAT("sd-combat", "交战记录/阶段/伤亡写命令（命令面）", LogOriginKind.SYSTEM),
  SD_DIPLOMACY("sd-diplomacy", "外交关系与外交事件（命令面）", LogOriginKind.SYSTEM),
  SD_TICK("sd-tick", "每 tick 推进：effect 触发、逐日结算（tick 算法，必带 day）", LogOriginKind.TICK),
  SD_CODEC("sd-codec", "SdCodec 编解码、变更集施加与旧形状迁移", LogOriginKind.SYSTEM),
  SD_RESOLVE("sd-resolve", "SdResolver 候选装配与空结果诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  SdLogSource(String id, String description, LogOriginKind kind) {
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
