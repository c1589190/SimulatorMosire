package io.mosire.simos.ga;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>ga 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 ga 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。ga 侧执行面分三类：
 *
 * <ul>
 *   <li>{@link #GA_DECISION_TURN}：决策人运行流的一轮（开始/结束/预算中止/逐次工具调用）—— 由 GM 命令或 GUI 异步触发，
 *       本质是"人工/交互发起的一次运行" ⇒ {@link LogOriginKind#INTERACTION}（与 app 侧 {@code
 *       AppLogSource.DECISION_TURN} 同口径；运行流迁入 ga 后事件来源改由本表表达）；
 *   <li>{@link #GA_DECISION} / {@link #GA_CODEC}：{@link LogOriginKind#SYSTEM}（命令面与编解码面的执行）；
 *   <li>{@link #GA_TICK}：每 tick 推进（effect 逐日触发）⇒ {@link LogOriginKind#TICK}。
 * </ul>
 *
 * <p>★ 禁止把 tick 算法类事件标成 {@link LogOriginKind#INTERACTION}：那一类属各领域模块自己的来源表； 本表只描述 ga 运行流自身的工作面。
 */
public enum GaLogSource implements LogOrigin {
  GA_DECISION_TURN(
      "ga-decision-turn", "决策人运行流的一轮（LLM 回合、逐次工具调用、回合结算与中止）", LogOriginKind.INTERACTION),
  /** 工具面的每一次实际执行（{@code RecordingToolSource} 装饰器；发起 / 具名拒绝 / 成功结果三行）。 */
  GA_TOOL_CALL("ga-tool-call", "工具面的每一次实际执行（工具名、调用者身份、结果码）", LogOriginKind.INTERACTION),
  /** 审批控制面：待裁决清单的读、裁决结果、未接入。 */
  GA_APPROVAL("ga-approval", "审批控制面（待裁决清单、裁决结果、未接入）", LogOriginKind.INTERACTION),
  /**
   * ★ 2026-10-12 批次 3a：决策包 / 合并效果集四条命令的 handler 面（落盘与具名拒绝）。
   *
   * <p>★ <b>与 {@code sd.SdLogSource.SD_DECISION} 逐字同档</b>（{@link LogOriginKind#SYSTEM}）：这些命令自
   * {@code sd.*} 改名 {@code ga.*} 并从 {@code simos-sd} 搬进本模块（批次 3a 四条状态命令 + 批次 3b′
   * 决策族三条），日志的<b>粗分类与事件名保持原样</b> ⇒ 消费者按 kind 粗筛的语义不变；变的只有 logger 名（{@code
   * io.mosire.simos.sd.decision} → {@code io.mosire.simos.ga.decision}）与来源表。
   */
  GA_DECISION(
      "ga-decision",
      "ga 命令的 handler 面（决策包 ga.ApplyDecisionPackage、合并效果集落盘；出令/状态翻转/交判决；★ 3b-4 起含效果登记/取消）与具名拒绝",
      LogOriginKind.SYSTEM),
  /** ★ 2026-10-12 批次 3a：{@code GaCodec} 的编解码与变更集施加（照 {@code GovLogSource.GOV_CODEC} 同档）。 */
  GA_CODEC("ga-codec", "GaCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  /**
   * ★★ 2026-10-12 批次 3b-4：每 tick 推进——effect 逐日触发（{@code GaTimeParticipant}）。
   *
   * <p>★ <b>与 {@code sd.SdLogSource.SD_TICK} 逐字同档</b>（{@link LogOriginKind#TICK}，必带 {@code day}）：
   * {@code SdTimeParticipant} 随 {@code effects} 表迁入本模块，日志的<b>粗分类与事件名保持原样</b>（{@code SD_ADVANCE_*} /
   * {@code SD_EFFECT_FIRED}）⇒ 消费者按 kind 粗筛的语义不变；变的只有 logger 名（{@code io.mosire.simos.sd.time} →
   * {@code io.mosire.simos.ga.time}）与来源表。
   */
  GA_TICK("ga-tick", "每 tick 推进：effect 逐日触发（tick 算法，必带 day）", LogOriginKind.TICK),
  /**
   * ★★ 2026-10-12 批次 3e：{@code ga:} 地址族的解析诊断（{@code GaResolver} 的空候选与装配故障）。
   *
   * <p>★ <b>与 {@code sd.SdLogSource.SD_RESOLVE} 逐字同档</b>（{@link LogOriginKind#SYSTEM}）：{@code
   * SdResolver} 整类迁入本模块并改挂 {@code ga:} 命名空间（{@code simos-sd} 退役）⇒ 日志的粗分类与 「只记空候选 /
   * 装配故障、不逐次记成功查询」的口径不变；变的只有 logger 名（{@code io.mosire.simos.sd.resolve} → {@code
   * io.mosire.simos.ga.resolve}）与来源档（{@code sd-resolve} → {@code ga-resolve}，同 3b-4 的 {@code
   * SD_TICK} → {@code GA_TICK}）。
   */
  GA_RESOLVE("ga-resolve", "GaResolver 候选装配与空结果诊断", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  GaLogSource(String id, String description, LogOriginKind kind) {
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
