package io.mosire.simos.gov;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>gov 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 gov 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。gov 侧只有日结算主链与逐格需求读数两类执行面：
 *
 * <ul>
 *   <li>{@link #GOV_DAILY}：GOV 日结算主链（供给/治安/文书/俸禄支付，tick 算法，事件必带 {@code day}）；
 *   <li>{@link #GOV_DEMAND}：逐格行政需求读数——{@code GovDemand.of(...)} 的方法签名没有 {@code day} 上下文，按纪律 “TICK
 *       类必带 day、无 day 上下文改用 SYSTEM 表项并说明”归 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #GOV_EFFICIENCY}：Z2 两维效率公式（纯函数，无 {@code day}/{@code unit} 上下文）——契约故障 ERROR 与汇总
 *       DEBUG，归 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #GOV_COMMAND}：gov 配置命令 handler（编制计划/预算政策）——载荷有 {@code unitId}，但无 {@code day} 上下文， 归
 *       {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #GOV_CODEC}：GovCodec 编解码与变更集施加。
 * </ul>
 */
public enum GovLogSource implements LogOrigin {
  GOV_DAILY("gov-daily", "GOV 日结算主链：供给/治安/文书/俸禄支付（tick 算法，必带 day）", LogOriginKind.TICK),
  GOV_DEMAND("gov-demand", "逐格行政需求与效率读数（调用点无 day 上下文，按纪律归 system）", LogOriginKind.SYSTEM),
  GOV_EFFICIENCY(
      "gov-efficiency", "Z2 两维效率公式（纯函数，无 day/unit 上下文，按纪律归 system）", LogOriginKind.SYSTEM),
  GOV_COMMAND(
      "gov-command", "gov 配置命令 handler（编制计划/预算政策，无 day 上下文，按纪律归 system）", LogOriginKind.SYSTEM),
  GOV_CODEC("gov-codec", "GovCodec 编解码与变更集施加", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  GovLogSource(String id, String description, LogOriginKind kind) {
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
