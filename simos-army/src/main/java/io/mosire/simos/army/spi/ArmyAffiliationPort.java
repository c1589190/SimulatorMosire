package io.mosire.simos.army.spi;

import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * ★★ <b>army 归属表的只读输入口</b>（<b>端口在 army、实现在 app、{@code Shell} 注入</b>）——2026-10-12 批次 3d-1，形制逐条照抄 3c
 * 的 {@code io.mosire.simos.gov.spi.NationAffiliationLookup}。
 *
 * <p>★★ <b>为什么需要它</b>：本批把 {@code armies} 表搬进 army，而"哪个决策人自称归属哪个军队"这条事实住在 {@code simos-ga}
 * 的决策人记录里（{@code ga.CreateDecisionMaker} 的 {@code affiliation} 载荷）。{@code simos-army} 的 enforcer
 * <b>禁 {@code simos-ga}</b>（army 的允许面只有 util/map/unit/social/gov/ga-spi）⇒ 结构性读不到 ⇒ 走本仓既有的
 * 「端口在域模块、实现在组合根」形制（同 gov 的 {@code NationAffiliationLookup}、同 sd 的 {@code DecisionTablesLookup}）。
 *
 * <p>★★ <b>返回值是"不透明引用"，不是类型化的军队</b>：{@code String} 就是 {@code Affiliation.Army(String)} 里那个串 ——sd
 * 侧不认识 {@code ArmyId}（它在 army）。<b>解释这个串、并裁定它是否成立的地方是 army</b>（{@link
 * io.mosire.simos.army.ArmyAffiliations#resolve}）：解析不了、或军队不在 {@code armies} 里 ⇒ 该决策人
 * <b>没有军队归属</b>（读不到 ⇒ 调用方 deny-all）。
 *
 * <p>★ <b>实现必须"只读"且 fail-closed</b>：拿不到切片（装配故障）应当抛，而不是返回空表——返回空表会把装配错误伪装成"这个决策人没有归属" （同 {@code
 * ArmyAffiliations} 与 {@code ArmyScope} 的分工：装配故障抛、世界数据缺失降权）。
 */
public interface ArmyAffiliationPort {

  /**
   * 当前世界里<b>自称</b>有军队归属的决策人 → 军队引用原文（不透明串；缺省/无归属者不必出现在表里）。
   *
   * @param state 当前世界状态（实现按它读决策人表；本接口不规定切片来路）
   */
  Map<DecisionMakerId, String> armyClaims(SimulationState state);
}
