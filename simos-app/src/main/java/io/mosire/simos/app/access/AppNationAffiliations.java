package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.spi.NationAffiliationLookup;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>{@link NationAffiliationLookup} 的 app 实现</b>（<b>端口在 gov、实现在 app、{@code Shell} 注入</b>）——
 * 2026-10-12 批次 3c。
 *
 * <p>★★ <b>为什么实现在 app</b>：这条判据要**同时**看两片——决策人记录（{@code ga} 切片里 {@code Affiliation.Nation}
 * 的那个不透明引用）与 国家表（{@code gov} 切片）。{@code simos-gov} 禁 {@code simos-ga}/{@code simos-sd}，{@code
 * simos-ga} 禁 {@code simos-gov} ⇒ 只有组合根同时看得见两侧（与 {@code GovJurisdictionGuard} 同一条理由）。
 *
 * <p>★★ <b>本类同时是"读侧唯一入口"</b>：{@link #of(SimulationState)} 是"这个世界里谁归属哪个国家"的<b>唯一拼装点</b>——
 * ①决策人记录里的<b>自称</b>（{@link Affiliation.Nation} 的不透明串）；②gov 的<b>裁定</b>（{@link
 * NationAffiliations#resolve} 拿 {@code GovState.nations()} 裁掉指向不存在国家的引用）。 权限分派（{@link
 * NationScope}）与 gov 自己的删除守卫（{@code gov.DeleteNation}）问的都是这一处，不各写一遍。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛（{@link ToolSupport#govState} 与 {@link
 * ToolSupport#gaState} 的口径）；<b>世界数据</b>问题（没有这个国家 / 没有这个决策人）降权——{@link NationAffiliations#resolve}
 * 把它裁掉，读侧拿不到归属 ⇒ <b>deny-all</b>。
 */
public final class AppNationAffiliations implements NationAffiliationLookup {

  /** 无状态实现（{@code Shell} 注入给 gov 的删除守卫用）。 */
  public static final AppNationAffiliations INSTANCE = new AppNationAffiliations();

  /** 当前世界里成立的 nation 归属表（唯一拼装点；空表 = 没有任何成立的归属 ⇒ 读侧一律 deny-all）。 */
  public static NationAffiliations of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return NationAffiliations.resolve(claimsOf(state), ToolSupport.govState(state).nations());
  }

  /** 该决策人的国家；**空 = 读不到归属 ⇒ 调用方必须 deny-all**（{@link NationScope} 与 gov 的删除守卫共用）。 */
  public static Optional<NationId> nationOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return of(state).nationOf(decisionMaker);
  }

  /** 该国家的记录（{@code null} = 读不到 ⇒ 调用方按"没有归属"处置）。 */
  public static Nation nationOrNull(SimulationState state, NationId nationId) {
    Objects.requireNonNull(nationId, "nationId");
    return ToolSupport.govState(state).nations().get(nationId);
  }

  /**
   * 决策人记录里的<b>自称</b>（{@code Affiliation.Nation} 的不透明串；其余归属种类不在此表里）。
   *
   * <p>★ 这是 {@link NationAffiliationLookup} 的契约实现：<b>只报原文，不解释</b>——解析与裁定都在 gov 侧。
   */
  @Override
  public Map<DecisionMakerId, String> nationClaims(SimulationState state) {
    return claimsOf(state);
  }

  private static Map<DecisionMakerId, String> claimsOf(SimulationState state) {
    Objects.requireNonNull(state, "state");
    GaState ga = ToolSupport.gaState(state);
    Map<DecisionMakerId, String> claims = new LinkedHashMap<>();
    for (DecisionMaker maker : ga.decisionMakers().values()) {
      if (maker.affiliation() instanceof Affiliation.Nation nation) {
        claims.put(maker.id(), nation.nationId());
      }
    }
    return Map.copyOf(claims);
  }
}
