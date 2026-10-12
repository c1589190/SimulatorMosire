package io.mosire.simos.ga.spi;

import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;

/**
 * ★★ <b>决策人身份的只读存在性口</b>（<b>端口在 {@code ga-spi}、实现在 app、装配期注入</b>）——2026-10-12 乙收尾。
 *
 * <p>★★ <b>为什么需要它</b>：本批把归属的<b>写入</b>从 {@code ga.CreateDecisionMaker} 的 {@code affiliation} 载荷
 * 搬到各持有模块的赋权命令（{@code gov.AssignDecisionMakerNation} / {@code gov.AssignDecisionMakerGov} / {@code
 * army.AssignDecisionMakerArmy}），而<b>决策人身份表住 {@code ga}</b>（{@code GaState.decisionMakers()}），
 * {@code gov} / {@code army} 的 enforcer <b>都禁 {@code ga}</b> ⇒ 结构性看不见 ⇒ 走本仓既有的「端口在契约层、实现在组合根」 形制（同
 * {@code ga-spi.ToolWritePort}、gov 的 {@code GovJurisdictionGuard}）。
 *
 * <p>★★ <b>这条判据是拆分<u>必然</u>新增的</b>（如实记）：拆分前"给决策人绑归属"与"创建这个决策人"是同一条命令，不存在"绑到一个不存在的
 * 决策人"这回事；拆分后两次提交之间有窗口 ⇒ 必须显式判一次，否则表里会留下<b>永远够不着</b>的行（也可能在 id 被后来复用时长出一份没人 赋予过的归属）。方向是
 * fail-closed：查不到 ⇒ 具名拒。
 *
 * <p>★ <b>实现必须 fail-closed</b>：拿不到 {@code ga} 切片（装配故障）应当抛，而不是返回 {@code false}——返回 false 会把装配错误
 * 伪装成"这个决策人不存在"（同 {@code ToolSupport.gaState} 与各归属视图的分工：装配故障抛、世界数据缺失降权）。
 */
public interface DecisionMakerExistencePort {

  /**
   * 该决策人此刻在不在世界里。
   *
   * @param state 当前世界状态（实现按它读决策人表；本接口不规定切片来路）
   * @param decisionMakerId 决策人 id
   */
  boolean exists(SimulationState state, DecisionMakerId decisionMakerId);
}
