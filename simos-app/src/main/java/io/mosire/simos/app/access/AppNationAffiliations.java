package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>nation 归属的 app 读口</b>（2026-10-12 批次 3c 建；<b>乙收尾改造</b>）。
 *
 * <p>★★★ <b>乙收尾改了什么</b>：3c 时本类是"决策人记录里的自称（{@code Affiliation.Nation} 的不透明串）× gov 的裁定（拿 {@code
 * nations} 裁掉指向不存在国家的引用）"的唯一拼装点，且还要实现 gov 侧的 {@code NationAffiliationLookup} 端口（因为 {@code gov} 看不见
 * {@code ga} 的决策人表）。<b>本批起归属的写入与表本体都归 gov</b>（写口 = {@code gov.AssignDecisionMakerNation}，表 = {@code
 * GovState.nationAffiliations()}）⇒
 *
 * <ul>
 *   <li>不再有"自称"这一层：读的就是那张<b>类型化的</b>表；解释与裁剪在 gov 自己的 {@link NationAffiliations#resolve}（按 {@code
 *       nations} 裁——国家被删后那一行自动失效）；
 *   <li>{@code NationAffiliationLookup} 端口<b>整条删除</b>（它存在的唯一理由"决策人记录住 ga、gov 看不见"已消失）。
 * </ul>
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛（{@link ToolSupport#govState} 的口径）；
 * <b>世界数据</b>问题（没有这个国家）降权——{@link NationAffiliations#resolve} 把它裁掉，读侧拿不到归属 ⇒ <b>deny-all</b>。
 */
public final class AppNationAffiliations {

  private AppNationAffiliations() {}

  /** 当前世界里成立的 nation 归属表（空表 = 没有任何成立的归属 ⇒ 读侧一律 deny-all）。 */
  public static NationAffiliations of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return NationAffiliations.resolve(
        ToolSupport.govState(state).nationAffiliations(), ToolSupport.govState(state).nations());
  }

  /** 该决策人的国家；**空 = 读不到归属 ⇒ 调用方必须 deny-all**。 */
  public static Optional<NationId> nationOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return of(state).nationOf(decisionMaker);
  }

  /** 该国家的记录（{@code null} = 读不到 ⇒ 调用方按"没有归属"处置）。 */
  public static Nation nationOrNull(SimulationState state, NationId nationId) {
    Objects.requireNonNull(nationId, "nationId");
    return ToolSupport.govState(state).nations().get(nationId);
  }
}
