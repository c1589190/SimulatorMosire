package io.mosire.simos.production;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>{@code simos-production} 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 production 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。本模块有命令写入面、技能目录查询、 codec 与（D-032 起）生产运行时：
 *
 * <ul>
 *   <li>{@link #PRODUCTION_COMMAND}：生产配置命令 handler（{@code production.UpsertIndustry} / {@code
 *       UpsertProductionMode} / {@code DeactivateProductionMode} / {@code Seed} / {@code
 *       ClearRegionDefinitions}）——无 {@code day} 上下文，归 {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #PRODUCTION_SKILL}：跨地区生产技能目录查询（纯函数，无 {@code day}/{@code unit} 上下文）—— 未命中只记 TRACE，归
 *       {@link LogOriginKind#SYSTEM}；
 *   <li>{@link #PRODUCTION_CODEC}：{@code ProductionCodec} 编解码与变更集施加；
 *   <li>{@link #PRODUCTION_RUNTIME}：★ D-032 新增——试算 / 执行 / 周期关账 / 经验增长与衰减 / 解锁 / 效率契约故障。
 * </ul>
 *
 * <p>★ <b>本模块仍不新增 {@code TimeParticipant}</b>：生产没有自己的推进器，状态推进由组合根经 {@code ProductionRuntime}
 * 驱动（{@code beginTick} / {@code probe} / {@code execute}），仍走 "本域候选态 → 本域 ChangeSet → 同一 revision"。★
 * 旧的 {@code production.RegisterTechnology} / {@code SetMastery} 两条科技点命令已随 D1 删除，故 {@link
 * #PRODUCTION_COMMAND} 的说明同步改写。
 */
public enum ProductionLogSource implements LogOrigin {
  PRODUCTION_COMMAND(
      "production-command",
      "生产/科技配置命令 handler：登记科技定义与设置掌握（无 day 上下文，按纪律归 system）",
      LogOriginKind.SYSTEM),
  PRODUCTION_SKILL(
      "production-skill", "跨地区生产技能目录查询（未命中 ⇒ empty，不解析 id 猜种类；纯函数，归 system）", LogOriginKind.SYSTEM),
  PRODUCTION_CODEC("production-codec", "ProductionCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  PRODUCTION_RUNTIME(
      "production-runtime", "D-032 生产运行时：试算/执行/周期关账/经验增长与衰减/解锁/效率契约故障", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  ProductionLogSource(String id, String description, LogOriginKind kind) {
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
