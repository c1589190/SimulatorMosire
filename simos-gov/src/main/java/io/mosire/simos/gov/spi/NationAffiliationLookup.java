package io.mosire.simos.gov.spi;

import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * ★★ <b>nation 归属表的只读输入口</b>（<b>端口在 gov、实现在 app、{@code Shell} 注入</b>）。
 *
 * <p>★★ <b>为什么需要它</b>：本批（3c）把 {@code nations} 表搬进 gov，而"哪个决策人自称归属哪个国家"这条事实住在 {@code simos-ga}
 * 的决策人记录里（{@code ga.CreateDecisionMaker} 的 {@code affiliation} 载荷）。{@code simos-gov} 的 enforcer
 * <b>禁 {@code simos-ga}</b>（gov 的允许面只有 util/map/unit/social/economy-api/ga-spi）⇒ 结构性读不到 ⇒ 走本仓既有的
 * 「端口在域模块、实现在组合根」形制（同 {@code sd} 的 {@code DecisionTablesLookup}、同 app 的 {@code
 * GovJurisdictionGuard}）。
 *
 * <p>★★ <b>返回值是"不透明引用"，不是类型化的国家</b>：{@code String} 就是 {@code Affiliation.Nation(String)} 里那个串 ——sd
 * 侧不认识 {@code NationId}（它在 gov）。<b>解释这个串、并裁定它是否成立的地方是 gov</b>（ {@link
 * io.mosire.simos.gov.nation.NationAffiliations#resolve}）：解析不了、或国家不在 {@code nations} 里 ⇒
 * 该决策人<b>没有国家归属</b> （读不到 ⇒ 调用方 deny-all）。
 *
 * <p>★ <b>实现必须"只读"且 fail-closed</b>：拿不到切片（装配故障）应当抛，而不是返回空表——返回空表会把装配错误伪装成"这个决策人没有归属" （同 {@code
 * NationAffiliations} 与 {@code NationScope} 的分工：装配故障抛、世界数据缺失降权）。
 */
public interface NationAffiliationLookup {

  /**
   * 当前世界里<b>自称</b>有国家归属的决策人 → 国家引用原文（不透明串；缺省/无归属者不必出现在表里）。
   *
   * @param state 当前世界状态（实现按它读决策人表；本接口不规定切片来路）
   */
  Map<DecisionMakerId, String> nationClaims(SimulationState state);
}
