package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.spi.DecisionMakerExistencePort;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;

/**
 * ★★ <b>{@link DecisionMakerExistencePort} 的 app 实现</b>（端口在 {@code ga-spi}、实现在 app、{@code Shell}
 * 注入）—— 2026-10-12 乙收尾。
 *
 * <p>★★ <b>为什么实现在 app</b>：判据要读 {@code ga} 切片的决策人表，而 {@code gov} / {@code army} 的 enforcer <b>都禁
 * {@code ga}</b>，只有组合根看得见 ⇒ 走本仓既有的「端口在契约层、实现在组合根」形制（同 {@code AppNationAffiliations} 在 3c 里的角色）。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺 {@code ga} 切片/类型不对）当场抛（{@link ToolSupport#gaState}
 * 的口径）； <b>世界数据</b>问题（没有这个决策人）返回 {@code false}，由调用方（三条赋权命令）折成具名拒。
 */
public final class AppDecisionMakerExistence implements DecisionMakerExistencePort {

  /** 无状态实现（{@code Shell} 注入给 gov/army 的三条赋权命令）。 */
  public static final AppDecisionMakerExistence INSTANCE = new AppDecisionMakerExistence();

  private AppDecisionMakerExistence() {}

  @Override
  public boolean exists(SimulationState state, DecisionMakerId decisionMakerId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    return ToolSupport.gaState(state).decisionMakers().containsKey(decisionMakerId);
  }
}
