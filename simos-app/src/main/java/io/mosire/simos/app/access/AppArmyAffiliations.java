package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>army 归属的 app 读口</b>（2026-10-12 批次 3d-1 建；<b>乙收尾改造</b>，形制逐条照抄 {@link
 * AppNationAffiliations}）。
 *
 * <p>★★★ <b>乙收尾改了什么</b>：3d-1 时本类是"决策人记录里的自称（{@code Affiliation.Army} 的不透明串）× army 的裁定（拿 {@code
 * armies} 裁掉指向不存在军队的引用）"的唯一拼装点，且还实现 army 侧的 {@code ArmyAffiliationPort} 端口。<b>本批起归属的写入与表本体都归
 * army</b>（写口 = {@code army.AssignDecisionMakerArmy}，表 = {@code ArmyData.armyAffiliations()}）⇒
 * 不再有"自称"这一层， 也没有端口；解释与裁剪在 army 自己的 {@link ArmyAffiliations#resolve}（军队被删后那一行自动失效）。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛（{@link ToolSupport#armyData} 的口径）；
 * <b>世界数据</b>问题（没有这个军队）降权 ⇒ 读侧 <b>deny-all</b>。
 */
public final class AppArmyAffiliations {

  private AppArmyAffiliations() {}

  /** 当前世界里成立的 army 归属表（空表 = 没有任何成立的归属 ⇒ 读侧一律 deny-all）。 */
  public static ArmyAffiliations of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    ArmyData army = ToolSupport.armyData(state);
    return ArmyAffiliations.resolve(army.armyAffiliations(), army.armies());
  }

  /** 该决策人的军队；**空 = 读不到归属 ⇒ 调用方必须 deny-all**。 */
  public static Optional<ArmyId> armyOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return of(state).armyOf(decisionMaker);
  }

  /** 该军队的记录（{@code null} = 读不到 ⇒ 调用方按"没有归属"处置）。 */
  public static Army armyOrNull(SimulationState state, ArmyId armyId) {
    Objects.requireNonNull(armyId, "armyId");
    return ToolSupport.armyData(state).armies().get(armyId);
  }
}
