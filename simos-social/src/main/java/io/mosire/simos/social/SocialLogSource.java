package io.mosire.simos.social;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>social 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 social 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。social 侧执行面分七类：
 *
 * <ul>
 *   <li>{@link #SOCIAL_COMMAND}：家户/城市/人口写命令处理。命令入口的工具/人工交互由 app 层自记 {@code interaction}（用户裁定 origin
 *       不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #SOCIAL_SETTLE}：逐 tick 人口生命事件结算（出生/死亡/余数池/守恒检查）；tick 类事件必带 {@code day}；
 *   <li>{@link #SOCIAL_WORLDGEN}：创世/播种期聚落结算生成与人口分配。生成只在创世/播种期被 app 的 worldgen 工具调用、 请求里没有 {@code
 *       day} 上下文，按纪律"没有 day 就别硬造"记 {@link LogOriginKind#SYSTEM}（不标 {@code TICK}）；
 *   <li>{@link #SOCIAL_WORK_ORDER}：家户工单受理/拒收/幂等；
 *   <li>{@link #SOCIAL_PROVISIONING}：需求/劳动权威表载入、逐户展开与系数查表；
 *   <li>{@link #SOCIAL_CODEC}：SocialCodec 编解码与变更集施加；
 *   <li>{@link #SOCIAL_RESOLVE}：SocialResolver 候选装配与空结果诊断。
 * </ul>
 *
 * <p>★ 禁止把 {@link #SOCIAL_COMMAND} 标成 {@link LogOriginKind#INTERACTION}：交互来源由 app
 * 层在入口处另记，本表只描述领域执行面。
 */
public enum SocialLogSource implements LogOrigin {
  SOCIAL_COMMAND("social-command", "家户/城市/人口写命令处理（命令面）", LogOriginKind.SYSTEM),
  SOCIAL_SETTLE(
      "social-settle", "逐 tick 人口生命事件结算：出生/死亡/余数池/守恒检查（tick 算法，必带 day）", LogOriginKind.TICK),
  SOCIAL_WORLDGEN("social-worldgen", "创世/播种期聚落结算生成与人口分配（无 day 上下文）", LogOriginKind.SYSTEM),
  SOCIAL_WORK_ORDER("social-workorder", "家户工单受理/拒收/幂等", LogOriginKind.SYSTEM),
  SOCIAL_PROVISIONING("social-provisioning", "需求/劳动权威表载入、逐户展开与系数查表", LogOriginKind.SYSTEM),
  SOCIAL_CODEC("social-codec", "SocialCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  SOCIAL_RESOLVE("social-resolve", "SocialResolver 候选装配与空结果诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  SocialLogSource(String id, String description, LogOriginKind kind) {
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
