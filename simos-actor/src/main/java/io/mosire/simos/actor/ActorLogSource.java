package io.mosire.simos.actor;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>actor 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 actor 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。actor 侧只有账户/库存写口、播种与编解码/解析五类执行面：
 *
 * <ul>
 *   <li>{@link #ACTOR_ACCOUNT}：家户账户写口（转移/调整/上缴/开户/清账）——命令入口的工具/人工交互由 app 层自记 {@code
 *       interaction}（用户裁定 origin 不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #ACTOR_STOCK}：家户库存扣除与入账写口（命令面）；
 *   <li>{@link #ACTOR_SEED}：actor 播种与区域账本清空（命令面）；
 *   <li>{@link #ACTOR_CODEC}：ActorCodec 编解码、变更集施加与旧形状迁移；
 *   <li>{@link #ACTOR_RESOLVE}：ActorResolver 候选装配、空结果与故障诊断。
 * </ul>
 */
public enum ActorLogSource implements LogOrigin {
  ACTOR_ACCOUNT("actor-account", "家户账户写口：转移/调整/上缴/开户/清账（命令面）", LogOriginKind.SYSTEM),
  ACTOR_STOCK("actor-stock", "家户库存扣除与入账写口（命令面）", LogOriginKind.SYSTEM),
  ACTOR_SEED("actor-seed", "actor 播种与区域账本清空（命令面）", LogOriginKind.SYSTEM),
  ACTOR_CODEC("actor-codec", "ActorCodec 编解码、变更集施加与旧形状迁移", LogOriginKind.SYSTEM),
  ACTOR_RESOLVE("actor-resolve", "ActorResolver 候选装配、空结果与故障诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  ActorLogSource(String id, String description, LogOriginKind kind) {
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
