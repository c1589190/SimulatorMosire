package io.mosire.simos.map;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>map 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 map 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。map 侧只有命令处理、生成、编解码与解析四类执行面：
 *
 * <ul>
 *   <li>{@link #MAP_EDIT}：地图写命令处理（区域/地形/连通性/通路组）。命令入口的工具/人工交互由 app 层自记 {@code interaction}（用户裁定
 *       origin 不跨模块传、不改 SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #MAP_GENERATE}：地图生成器阶段与产物汇总。生成发生在创世期、没有 {@code day} 上下文，按纪律“没有 day 就别硬造”记 {@link
 *       LogOriginKind#SYSTEM}（不标 {@code TICK}）；
 *   <li>{@link #MAP_CODEC}：MapCodec 编解码、旧形状迁移与变更集施加；
 *   <li>{@link #MAP_RESOLVE}：MapResolver 候选装配、空结果与故障诊断。
 * </ul>
 */
public enum MapLogSource implements LogOrigin {
  MAP_EDIT("map-edit", "地图写命令处理：区域/地形/连通性/通路组（命令面）", LogOriginKind.SYSTEM),
  MAP_GENERATE("map-generate", "地图生成器阶段与产物汇总（创世期生成，无 day 上下文）", LogOriginKind.SYSTEM),
  MAP_CODEC("map-codec", "MapCodec 编解码、旧形状迁移与变更集施加", LogOriginKind.SYSTEM),
  MAP_RESOLVE("map-resolve", "MapResolver 候选装配、空结果与故障诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  MapLogSource(String id, String description, LogOriginKind kind) {
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
