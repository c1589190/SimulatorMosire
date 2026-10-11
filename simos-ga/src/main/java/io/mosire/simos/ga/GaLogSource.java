package io.mosire.simos.ga;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>ga 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 ga 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。ga 侧执行面目前只有一类：
 *
 * <ul>
 *   <li>{@link #GA_DECISION_TURN}：决策人运行流的一轮（开始/结束/预算中止/逐次工具调用）—— 由 GM 命令或 GUI 异步触发，
 *       本质是"人工/交互发起的一次运行" ⇒ {@link LogOriginKind#INTERACTION}（与 app 侧 {@code
 *       AppLogSource.DECISION_TURN} 同口径；运行流迁入 ga 后事件来源改由本表表达）。
 * </ul>
 *
 * <p>★ 禁止把 tick 算法类事件标成 {@link LogOriginKind#INTERACTION}：那一类属各领域模块自己的来源表； 本表只描述 ga 运行流自身的工作面。
 */
public enum GaLogSource implements LogOrigin {
  GA_DECISION_TURN(
      "ga-decision-turn", "决策人运行流的一轮（LLM 回合、逐次工具调用、回合结算与中止）", LogOriginKind.INTERACTION);

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
