package io.mosire.simos.ga.spi;

import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.DecisionTablesLookup;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@link DecisionTablesLookup} 的唯一实现</b>（2026-10-12 批次 3b-2；前身 {@code GaDirectiveLookup} 只兑现
 * {@code directives} 一支）：sd 侧的读写口由本模块兑现——{@code ga → sd} 是<b>既有</b>方向（{@code GaState} 的组件值类型都住
 * sd），不新增模块边、零 pom 改动。
 *
 * <p>★ <b>只读两支、无状态</b>（铁律 3）：每次调用现从 {@link SimulationState} 取 ga 切片（{@link GaSnapshots#state}）， 不
 * cache、不改写。ga 切片缺席 / 类型不对 ⇒ {@code GaSnapshots} 当场抛（装配故障，<b>不</b>折成"不存在"）。
 *
 * <p>★★ <b>2026-10-12 批次 3c：{@code gov.DeleteNation} 不再是本口的调用方</b>——那条命令随外交三表搬进 {@code simos-gov}，而
 * gov <b>既禁 sd 也禁 ga</b> ⇒ 它改问 gov 自己的窄口 {@code
 * io.mosire.simos.gov.spi.NationAffiliationLookup}（端口在 gov、实现在 app）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本口由"三读一写"收成"两读"（如实记）</b>——{@code info(SimulationState)} 与 {@code
 * replaceInfo(SimulationState, Map)} 是批次 3b-2 为跨切片写引入的（sd 的推进器要在**另一个模块**的 {@code info} 表上落地 {@code
 * Action.PutInfo}）；{@code SdTimeParticipant} 迁入 {@code simos-ga} 且 {@code effects} 与 {@code info}
 * 同住 {@code GaState} 之后，那条写路径改走 {@code GaState.withInfo(...)}（同一个变更集里）， 这两个方法<b>失去全部调用者</b> ⇒
 * 同批整条删除（"看着能注入、其实无人注入"的接缝不留）。★ 今天的调用方 = {@code SdResolver} 的 {@code directive} / {@code
 * decision-maker} 两支存在性判据。
 */
public final class GaDecisionTablesLookup implements DecisionTablesLookup {

  @Override
  public boolean containsDirective(SimulationState state, DirectiveId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    return GaSnapshots.state(state).directives().containsKey(id);
  }

  @Override
  public Map<DecisionMakerId, DecisionMaker> decisionMakers(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return GaSnapshots.state(state).decisionMakers();
  }
}
