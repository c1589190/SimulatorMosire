package io.mosire.simos.culture;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>{@code simos-culture} 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 culture 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。本模块只有命令写入面、熟练度合成服务与 codec， <b>全部无 {@code day}
 * 上下文</b> ⇒ 按 gov/Z2 既有纪律一律归 {@link LogOriginKind#SYSTEM}：
 *
 * <ul>
 *   <li>{@link #CULTURE_COMMAND}：文化配置命令 handler（{@code culture.SetHouseholdCulture} / {@code
 *       culture.UpsertAptitudeRule} / {@code culture.RemoveAptitudeRule}）；
 *   <li>{@link #CULTURE_CODEC}：{@code CultureCodec} 编解码与变更集施加；
 *   <li>{@link #CULTURE_APTITUDE}：熟练度合成服务（纯服务，有 {@code unit} 上下文但无 {@code day}；合法中性 INFO、跨切片故障
 *       ERROR、逐 unit TRACE）。
 * </ul>
 *
 * <p>★ 本模块**不新增 {@code TimeParticipant}**：文化没有自己的推进能力，逐 tick 算法面不归这里；P0 真实日循环不调用 composer。
 */
public enum CultureLogSource implements LogOrigin {
  CULTURE_COMMAND(
      "culture-command",
      "文化配置命令 handler：设置家户文化标签、增改删熟练度规则（无 day 上下文，按纪律归 system）",
      LogOriginKind.SYSTEM),
  CULTURE_CODEC("culture-codec", "CultureCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  CULTURE_APTITUDE(
      "culture-aptitude",
      "熟练度合成服务：按出工家户劳动量千分加权（纯服务，无 day 上下文；P0 未接入日循环，按纪律归 system）",
      LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  CultureLogSource(String id, String description, LogOriginKind kind) {
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
