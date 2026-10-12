package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>gov 归属的 app 读口</b>（2026-10-12 乙收尾新建）：形制逐条照抄 3c 的 {@link AppNationAffiliations} 与 3d-1 的
 * {@link AppArmyAffiliations} —— 归属表本体住 {@code gov} 切片（{@code GovState.govAffiliations()}，写口 =
 * {@code gov.AssignDecisionMakerGov}），解释与裁剪在 gov 自己的 {@link GovAffiliations#resolve} （单位被删 /
 * 被摘掉编制后那一行自动失效）。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺 gov / unit 切片、类型不对）当场抛（{@link ToolSupport} 的口径）；
 * <b>世界数据</b>问题（单位不存在 / 不是 GOV）降权 ⇒ 读侧 <b>deny-all</b>（{@code GovScope} 的既有口径）。
 */
public final class AppGovAffiliations {

  private AppGovAffiliations() {}

  /** 当前世界里成立的 gov 归属表（空表 = 没有任何成立的归属 ⇒ 读侧一律 deny-all）。 */
  public static GovAffiliations of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    GovState gov = ToolSupport.govState(state);
    return GovAffiliations.resolve(gov.govAffiliations(), ToolSupport.unitState(state).units());
  }

  /** 该决策人绑的 GOV 单位；**空 = 读不到归属 ⇒ 调用方必须 deny-all**。 */
  public static Optional<UnitId> govUnitOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return of(state).govUnitOf(decisionMaker);
  }

  /** 该单位的记录（{@code null} = 读不到 ⇒ 调用方按"没有归属"处置）；只读 unit 切片。 */
  public static Unit govUnitOrNull(SimulationState state, UnitId unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return ToolSupport.unitState(state).units().get(unitId);
  }

  /** 该单位是不是一个 GOV 编制（{@code GovScope} 的既有判据，唯一拼写点在这里）。 */
  public static boolean isGovUnit(Unit unit) {
    return unit != null && unit.module().orElse(null) instanceof GovernmentFormation;
  }
}
