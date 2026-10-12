package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * ★★ <b>归属目标的存在性/形态校验</b>（计划 §0 裁定 10 / 阶段 10 验收）—— 2026-10-12 批次 3b-2 自 {@code
 * CreateDecisionMakerHandler} 的私有方法整条**搬家**（不是复制）到这里。
 *
 * <p>★★ <b>为什么它留在 sd 而不是跟 handler 进 ga</b>：判据要读三张表 —— {@code nations} / {@code armies}（sd 自己的，批次 3b
 * 不搬）与 <b>unit 切片</b>（{@code GovernmentFormation}）。{@code simos-ga} <b>没有声明</b> {@code simos-unit}
 * 依赖（设计书 §15：{@code ga → unit + map} 两条边随 {@code effects} 在 3b-4 一起开）⇒ 把这段判据放进 ga 会凭空多一条**未声明**的
 * 传递依赖（本仓明文"依赖传递不是契约"）。它服务的正是 sd 自己的 {@link Affiliation} 值类型 ⇒ 落 sd 是它的本位。
 *
 * <p>★ <b>只此一处拼写点</b>：{@code ga.CreateDecisionMaker} 经 {@code ga → sd}（既有方向）直接调本类；不复制第二份。
 *
 * <p>★ **返回具名理由而不是 boolean**：Gov 的"单位不存在"与"单位存在但不是 GOV"是两条不同的纠正方向（先建单位 vs 先 {@code
 * unit.SetGovFormation}），合成一句"目标不存在"会把后者说成谎。★ 运行期缺失由 {@code GovScope} 的 deny-all 兜底（fail-closed）。
 */
public final class AffiliationTargets {

  private AffiliationTargets() {}

  /**
   * 归属目标是否成立：Nation 要在 {@code sd.nations()}、Army 要在 {@code sd.armies()}、<b>Gov 要在 unit 切片里存在且带
   * {@code GovernmentFormation}</b>——GOV 决策人只绑 GOV 单位。
   *
   * @return 成立 ⇒ 空；否则 ⇒ 具名拒因（逐字保留搬迁前的文案）
   */
  public static Optional<String> problem(SimulationState state, Affiliation affiliation) {
    SdState sd = SdSnapshots.of(state).state();
    return switch (affiliation) {
      case Affiliation.Nation nation ->
          sd.nations().containsKey(nation.nationId())
              ? Optional.empty()
              : Optional.of("affiliation 目标不存在: " + affiliation);
      case Affiliation.Army army ->
          sd.armies().containsKey(army.armyId())
              ? Optional.empty()
              : Optional.of("affiliation 目标不存在: " + affiliation);
      case Affiliation.Gov gov -> {
        Unit unit = SdSnapshots.units(state).units().get(gov.govUnit());
        if (unit == null) {
          yield Optional.of("affiliation 目标不存在: " + affiliation);
        }
        if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
          yield Optional.of(
              "affiliation 单位 "
                  + gov.govUnit().value()
                  + " 没有 GovernmentFormation：GOV 决策人只能绑 GOV 单位（先 unit.SetGovFormation）");
        }
        yield Optional.empty();
      }
    };
  }
}
